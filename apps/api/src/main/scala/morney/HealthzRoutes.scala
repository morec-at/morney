package morney

import zio.http.*

object HealthzRoutes:
  val app: HttpApp[Any] = Http.collect[Request] {
    case Method.GET -> !! / "healthz" =>
      Response.text("""{"status":"ok"}""")
  }
