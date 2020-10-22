// Copyright (c) 2016-2020 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.sso.client

import cats.data._
import cats.implicits._
import lucuma.sso.client.util.JwtDecoder
import org.http4s.Request
import org.http4s.Response
import pdi.jwt.exceptions.JwtException
import org.http4s.headers.Authorization
import org.http4s.Credentials
import org.http4s.util.CaseInsensitiveString
import org.http4s.EntityDecoder
import cats.effect.Sync
import org.http4s.InvalidMessageBodyFailure

trait SsoJwtReader[F[_]] { outer =>

  /**
   * Retrieve the JWT from the `Authorization: Bearer <jwt>` header, if present, raising an
   * error in `F` if otherwise.
   */
  def findClaim(req: Request[F]): F[Option[SsoJwtClaim]]

  /** Retrieve the JWT from the `Authorization: Bearer <jwt>` header, if present. */
  def attemptFindClaim(req: Request[F]): F[Option[Either[JwtException, SsoJwtClaim]]]

  /** Retrieve the JWT from the response body. */
  def findClaim(res: Response[F]): F[SsoJwtClaim]

  // import this!
  implicit def entityDecoder: EntityDecoder[F, SsoJwtClaim]

}

object SsoJwtReader {

  private[client] val JwtCookie  = "lucuma-jwt"
  private[client] val lucumaUser = SsoJwtClaim.lucumaUser

  def apply[F[_]: Sync](jwtDecoder: JwtDecoder[F]): SsoJwtReader[F] =
    new SsoJwtReader[F] {

      implicit val entityDecoder: EntityDecoder[F, SsoJwtClaim] =
        EntityDecoder.text[F].flatMapR { token =>
          EitherT(jwtDecoder.attemptDecode(token))
            .map(SsoJwtClaim(_))
            .leftMap {
              case e: Exception => InvalidMessageBodyFailure(s"Invalid or missing JWT.", Some(e))
              case e            => InvalidMessageBodyFailure(s"Invalid or missing JWT: $e")
            }
        }

      val Bearer = CaseInsensitiveString("Bearer")

      def attemptFindClaim(req: Request[F]): F[Option[Either[JwtException, SsoJwtClaim]]] =
        findBearerAuthorization(req).flatMap {
          case None    => none.pure[F]
          case Some(c) => jwtDecoder.attemptDecode(c).map(_.map(SsoJwtClaim(_)).some)
        }

      def findBearerAuthorization(req: Request[F]): F[Option[String]]  =
        req.headers.collectFirst {
          case Authorization(Authorization(Credentials.Token(Bearer, token))) => token
        } .pure[F]

      def findClaim(req: Request[F]): F[Option[SsoJwtClaim]] =
        OptionT(findBearerAuthorization(req))
          .flatMapF(token => jwtDecoder.decodeOption(token).map(_.map(SsoJwtClaim(_))))
          .value

      def findClaim(res: Response[F]): F[SsoJwtClaim] =
        res.as[SsoJwtClaim]


    }

}

