package morney

import java.nio.charset.StandardCharsets

import zio.http.*

object HealthzRoutes:
  private val okJsonBytes =
    """{"status":"ok"}""".getBytes(StandardCharsets.UTF_8)

  val app = Routes(
    Method.GET / "healthz" ->
      Handler.succeed(
        Response(
          Status.Ok,
          Headers(Header.ContentType(MediaType.application.json).untyped),
          Body.fromArray(okJsonBytes)
        )
      )
  )
