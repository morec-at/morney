package morney

import zio.http.*

object HealthzRoutes:
  val app = Routes(
    Method.GET / "healthz" -> Handler.text("""{"status":"ok"}""")
  )
