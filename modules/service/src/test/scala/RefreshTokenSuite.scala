package lucuma.sso.service

import cats.effect._
import org.http4s._
import lucuma.sso.service.simulator.SsoSimulator
import org.http4s.headers.Cookie

object RefreshTokenSuite extends SsoSuite with Fixture {

  simpleTest("Cookie shouldn't expire.") {
    SsoSimulator[IO].use { case (_, _, sso, _, _) =>
      for {
        c  <- sso.run(Request(Method.POST, SsoRoot / "api" / "v1" / "auth-as-guest")).use(CookieReader[IO].getCookie(_))
      } yield expect(c.expires == Some(HttpDate.MaxValue))
    }
  }

  simpleTest("SomeSite should be Strict (simulator is pretending it's using https)") {
    SsoSimulator[IO].use { case (_, _, sso, _, _) =>
      for {
        c  <- sso.run(Request(Method.POST, SsoRoot / "api" / "v1" / "auth-as-guest")).use(CookieReader[IO].getCookie(_))
      } yield expect(c.sameSite == SameSite.Strict)
    }
  }

  simpleTest("Cookie should be removed on logout.") {
    SsoSimulator[IO].use { case (_, _, sso, _, _) =>
      for {
        _  <- sso.status(Request[IO](Method.POST, SsoRoot / "api" / "v1" / "auth-as-guest"))
        c  <- sso.run(Request(Method.POST, SsoRoot / "api" / "v1" / "logout")).use(CookieReader[IO].getCookie(_))
      } yield expect(c.expires == Some(HttpDate.Epoch))
    }
  }

  simpleTest("Refresh should fail after logout.") {
    SsoSimulator[IO].use { case (_, _, sso, _, _) =>
      for {
        _  <- sso.status(Request[IO](Method.POST, SsoRoot / "api" / "v1" / "auth-as-guest"))
        _  <- sso.status(Request[IO](Method.POST, SsoRoot / "api" / "v1" / "logout"))
        s  <- sso.status(Request[IO](Method.POST, SsoRoot / "api" / "v1" / "refresh-token"))
      } yield expect(s == Status.Forbidden)
    }
  }

  simpleTest("Invalid cookie should yield 403.") {
    SsoSimulator[IO].use { case (_, _, sso, _, _) =>
      sso.status {
        Request[IO](
          method  = Method.POST,
          uri     = SsoRoot / "api" / "v1" / "refresh-token",
          headers = Headers.of(Cookie(RequestCookie("lucuma-refresh-token", "8241D73F-EE0B-44D3-A05F-A15416F039DE")))
        )
      } map { status =>
        expect(status == Status.Forbidden)
      }
    }
  }

}




