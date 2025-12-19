package morney

import zio.*
import zio.http.*
import zio.test.*

object HealthzSpec extends ZIOSpecDefault:
  private val app = HealthzRoutes.app

  override def spec: Spec[Any, Any] =
    suite("HealthzRoutes")(
      test("GET /healthz returns ok status body") {
        ZIO.scoped {
          for
            url <- ZIO.fromEither(URL.decode("/healthz"))
            response <- app.runZIO(Request.get(url))
            body <- response.body.asString
          yield assertTrue(
            response.status == Status.Ok,
            body == """{"status":"ok"}"""
          )
        }
      }
    )
