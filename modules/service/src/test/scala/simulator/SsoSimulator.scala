package lucuma.sso.service
package simulator

import cats.effect._
import org.http4s.HttpRoutes
import org.http4s.implicits._
import org.http4s.client.Client
import org.http4s.server.Router
import lucuma.sso.service.orcid.OrcidService
import lucuma.sso.service.config.Config
import natchez.Trace.Implicits.noop
import lucuma.sso.service.database.Database
import lucuma.sso.client.SsoCookieReader
import org.http4s.Uri
import org.http4s.Uri.RegName
import io.chrisdavenport.log4cats.Logger
import lucuma.sso.service.config.OrcidConfig

object SsoSimulator {

  // The exact same routes and database used by SSO, but a fake ORCID back end
  private def httpRoutes[F[_]: Concurrent: ContextShift: Timer: Logger]: Resource[F, (OrcidSimulator[F], HttpRoutes[F], SsoCookieReader[F])] =
    Resource.liftF(OrcidSimulator[F]).flatMap { sim =>
      val config = Config.local(OrcidConfig("bogus", "bogus"))
      FMain.databasePoolResource[F](config.database).map { pool =>
        (sim, Routes[F](
          dbPool       = pool.map(Database.fromSession(_)),
          orcid        = OrcidService("unused", "unused", sim.client),
          publicKey    = config.publicKey,
          cookieReader = config.cookieReader,
          cookieWriter = config.cookieWriter,
          scheme       = Uri.Scheme.https,
          authority    = Uri.Authority(
            host = RegName("sso.gemini.edu"),
            port = Some(80),
          ),
        ), config.cookieReader)
    }
  }

  /** An Http client that hits an SSO server backed by a simulated ORCID server. */
  def apply[F[_]: Concurrent: ContextShift: Timer: Logger]: Resource[F, (OrcidSimulator[F], Client[F], SsoCookieReader[F])] =
    httpRoutes[F].map { case (sim, routes, reader) =>
      (sim, Client.fromHttpApp(Router("/" -> routes).orNotFound), reader)
    }

}
