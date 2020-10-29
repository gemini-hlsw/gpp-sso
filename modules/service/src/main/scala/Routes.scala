// Copyright (c) 2016-2020 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.sso.service

import cats.effect._
import cats.implicits._
import lucuma.sso.service.database.Database
import lucuma.sso.service.database.RoleRequest
import lucuma.sso.service.orcid.OrcidService
import org.http4s._
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.Location
import java.security.PublicKey
import lucuma.sso.client.util.GpgPublicKeyReader
import lucuma.sso.client.SsoJwtReader
import io.chrisdavenport.log4cats.Logger
import scala.concurrent.duration._
import lucuma.core.model.StandardRole
import lucuma.core.util.Gid
import lucuma.sso.client._

object Routes {

  implicit def gidQueryParamDecoder[A: Gid]: QueryParamDecoder[A] =
    QueryParamDecoder[String].emap { s =>
      Gid[A].fromString.getOption(s).toRight(ParseFailure("<gid>", "Invalid GID"))
    }

  // This is the main event. Here are the routes we're serving.
  def apply[F[_]: Sync: Timer: Logger](
    dbPool:    Resource[F, Database[F]],
    orcid:     OrcidService[F],
    jwtReader: SsoJwtReader[F],
    jwtWriter: SsoJwtWriter[F],
    cookies:   CookieService[F],
    publicUri: Uri,
    publicKey: PublicKey,
  ): HttpRoutes[F] = {
    object FDsl extends Http4sDsl[F]
    import FDsl._

    // The auth stage 2 URL is sent to ORCID, which redirects the user back. So we need to construct
    // a URL that makes sense to the user's browser!
    val Stage2Uri: Uri =
      publicUri.copy(path = "/auth/v1/stage2")

    // Some parameter matchers. The parameter names are NOT arbitrary! They are requied by ORCID.
    object OrcidCode   extends QueryParamDecoderMatcher[String]("code")
    object RedirectUri extends QueryParamDecoderMatcher[Uri]("state")
    object Key         extends QueryParamDecoderMatcher[ApiKey]("key")
    object Role        extends QueryParamDecoderMatcher[StandardRole.Id]("role")

    // Entity encoder for public keys.
    implicit val pubKeyEntityEncoder = GpgPublicKeyReader.entityEncoder[F]

    HttpRoutes.of[F] {

      // Allow clients to ask for our public key.
      case GET -> Root / "api" / "v1" / "public-key" =>
        Ok(publicKey)

      // If the user has a refresh token, return a new JWT. Otherwise 403.
      case r@(POST -> Root / "api" / "v1" / "refresh-token") =>
        cookies.findSessionToken(r).flatMap {
          case None => Forbidden("Not logged in.")
          case Some(tok) =>
            dbPool.use { db =>
              for {
                user <- db.getUserFromToken(tok)
                jwt  <- jwtWriter.newJwt(user)
                res  <- Ok(jwt)
              } yield res
            }
        }

      // Authenticate as a guest. Response body is the new user's JWT, and a refresh token is set.
      case POST -> Root / "api" / "v1" / "auth-as-guest" =>
        dbPool.use { db =>
          db.createGuestUserAndSessionToken.flatMap { case (user, token) =>
            for {
              jwt <- jwtWriter.newJwt(user)
              res <- Created(jwt)
              coo <- cookies.sessionCookie(token)
            } yield res.addCookie(coo)
          }
        }

      // Log out. TODO: If it's a guest user, delete that user.
      case POST -> Root / "api" / "v1" / "logout" =>
        Ok("Logged out.").flatMap(cookies.removeCookie)

      // Create an API key
      case r@(POST -> Root / "api" / "v1" / "create-api-key" :? Role(rid)) =>
        jwtReader.findStandardUser(r).flatMap {
          case None    => Forbidden("Standard user required.")
          case Some(u) =>
            if (u.role.id === rid || u.otherRoles.exists(_.id === rid))
              dbPool.use(_.createApiKey(u.role.id)).flatMap(Created(_))
            else
              Forbidden("Role is not owned by user.")
        }

      // Exchange an API key for a JWT
      case r@(GET -> Root / "api" / "v1" / "exchange-api-key" :? Key(apiKey)) =>
        jwtReader.findServiceUser(r).flatMap {
          case None     => Forbidden("Service user required.")
          case Some(su) =>
            dbPool.use(_.findStandardUserFromApiKey(apiKey)).flatMap {
              case None => Forbidden("Invalid API Key")
              case Some(u) =>
                Logger[F].info(s"API key ${apiKey.id} for ${u.displayName} (${u.id}) exchanged by ${su.displayName}.") *>
                jwtWriter.newJwt(u, Some(3.hours)).flatMap(Ok(_))
            }
        }

      // Authentication Stage 1: Send the user to ORCID.
      case GET -> Root / "auth" / "v1" / "stage1" :? RedirectUri(redirectUrl) =>
        for {
          uri <- orcid.authenticationUri(Stage2Uri, Some(redirectUrl.toString))
          res <- Found(Location(uri))
        } yield res

      // Authentication Stage 2: The user has returned from ORCID.
      // Insert/update a standard user, set a refresh token, and redirect to wherever the user asked to go in Stage 1.
      case r@(GET -> Root / "auth" / "v1" / "stage2" :? OrcidCode(code) +& RedirectUri(redir)) =>
        dbPool.use { db =>
          for {
            access   <- orcid.getAccessToken(Stage2Uri, code) // when this fails we get a 400 back so we need to handle that case somehow
            person   <- orcid.getPerson(access)
            otoken   <- cookies.findSessionToken(r)
            oguest   <- otoken.flatTraverse(db.findGuestUserFromToken)
            token    <-
              oguest match {
                case None =>
                  db.canonicalizeUser(access, person, RoleRequest.Pi)
                case Some(g) =>
                  db.promoteGuestUser(access, person, g.id, RoleRequest.Pi).flatMap {
                    case (None, tok) => tok.pure[F]
                    case (Some(existing), tok) =>
                      Sync[F].delay(println(s"==> TODO: chown ${g.id} -> $existing"))
                        .as(tok)
                  }
              }
            cookie   <- cookies.sessionCookie(token)
            res      <- Found(Location(redir))
          } yield res.addCookie(cookie)
        }

    }
  }

}


