// Copyright (c) 2016-2020 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package gpp.sso.service

import cats.effect._
import cats.implicits._
import cats.Monad
import gpp.sso.service.config._
import gpp.sso.service.config.Environment._
import gpp.sso.service.database.Database
import gpp.sso.service.orcid.OrcidService
import io.jaegertracing.Configuration.{ ReporterConfiguration, SamplerConfiguration }
import java.io.{ PrintWriter, StringWriter }
import natchez.{ EntryPoint, Trace }
import natchez.http4s.implicits._
import natchez.jaeger.Jaeger
import org.http4s._
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits._
import org.http4s.server._
import org.http4s.server.middleware.Logger
import skunk._
import org.flywaydb.core.Flyway

object Main extends IOApp {
  def run(args: List[String]): IO[ExitCode] =
    FMain.main[IO]
}

object FMain {

  // TODO: put this in the config
  val MaxConnections = 10

  def poolResource[F[_]: Concurrent: ContextShift: Trace](
    config: DatabaseConfig
  ): Resource[F, Resource[F, Session[F]]] =
    Session.pooled(
      host     = config.host,
      port     = config.port,
      user     = config.user,
      password = config.password,
      database = config.database,
      ssl      = SSL.Trusted.withFallback(true),
      max      = MaxConnections,
      strategy = Strategy.SearchPath,
      // debug    = true
    )

  // Run flyway migrations
  def migrate[F[_]: Sync](config: DatabaseConfig): F[Int] =
    Sync[F].delay {
      Flyway
        .configure()
        .dataSource(config.jdbcUrl, config.user, config.password.orEmpty)
        .baselineOnMigrate(true)
        .load()
        .migrate()
    }

  def app[F[_]: Monad](routes: HttpRoutes[F]): HttpApp[F] =
    Router("/" -> routes).orNotFound

  def server[F[_]: Concurrent: ContextShift: Timer](
    port: Int,
    app:  HttpApp[F]
  ): Resource[F, Server[F]] =
    EmberServerBuilder
      .default[F]
      .withHost("0.0.0.0")
      .withHttpApp(app)
      .withPort(port)
      .withOnError { t =>
        // for now let's include the stacktrace in the message body
        val sw = new StringWriter
        t.printStackTrace()
        t.printStackTrace(new PrintWriter(sw))
        Response[F](
          status = Status.InternalServerError,
          body   = EntityEncoder[F, String].toEntity(sw.toString).body
        )
      }
      .build

  def entryPoint[F[_]: Sync]: Resource[F, EntryPoint[F]] = {
    Jaeger.entryPoint[F]("gpp-sso") { c =>
      Sync[F].delay {
        c.withSampler(SamplerConfiguration.fromEnv)
         .withReporter(ReporterConfiguration.fromEnv)
         .getTracer
      }
    }
  }

  def loggingMiddleware[F[_]: Concurrent: ContextShift](
    env: Environment
  ): HttpRoutes[F] => HttpRoutes[F] =
    Logger.httpRoutes[F](
        logHeaders = true,
        logBody    = env == Local,
        redactHeadersWhen = { h =>
          env match {
            case Local                => false
            case Staging | Production => Headers.SensitiveHeaders.contains(h)
          }
        }
      )

  def orcidServiceResource[F[_]: Concurrent: Timer: ContextShift](config: OrcidConfig) =
    EmberClientBuilder.default[F].build.map(org.http4s.client.middleware.Logger[F](
      logHeaders = true,
      logBody = true,
    )).map { client =>
      OrcidService(config.clientId, config.clientSecret, client)
    }

  def routesResource[F[_]: Concurrent: ContextShift: Trace: Timer](config: Config): Resource[F, HttpRoutes[F]] =
    (poolResource[F](config.database), orcidServiceResource(config.orcid)).mapN { (pool, orcid) =>
      Routes[F](
        dbPool       = pool.map(Database.fromSession(_)),
        orcid        = orcid,
        cookieReader = config.cookieReader,
        cookieWriter = config.cookieWriter,
        scheme       = config.scheme,
        authority    = config.authority,
      )
    }
    .map(natchezMiddleware(_))
    .map(loggingMiddleware(config.environment))

  def banner[F[_]: Sync](config: Config): F[Unit] =
    Sync[F].delay{
      // https://manytools.org/hacker-tools/ascii-banner/ .. font here is Calvin S
      Console.err.println(
        s"""|╦  ╦ ╦╔═╗╦ ╦╔╦╗╔═╗   ╔═╗╔═╗╔═╗
            |║  ║ ║║  ║ ║║║║╠═╣───╚═╗╚═╗║ ║
            |╩═╝╚═╝╚═╝╚═╝╩ ╩╩ ╩   ╚═╝╚═╝╚═╝
            |${config.environment} Environment
            |""".stripMargin
      )
    }

  def rmain[F[_]: Concurrent: ContextShift: Timer]: Resource[F, ExitCode] =
    for {
      c  <- Resource.liftF(Config.config.load[F])
      _  <- Resource.liftF(banner[F](c))
      _  <- Resource.liftF(migrate[F](c.database))
      ep <- entryPoint
      rs <- ep.liftR(routesResource(c))
      ap  = app(rs)
      _  <- server(c.httpPort, ap)
    } yield ExitCode.Success

  def main[F[_]: Concurrent: ContextShift: Timer]: F[ExitCode] =
    rmain.use(_ => Concurrent[F].never[ExitCode])

}

