package morney

import zio.*
import zio.http.*

object Main extends ZIOAppDefault:
  override def run: ZIO[Any, Throwable, Nothing] =
    Server.serve(HealthzRoutes.app).provide(Server.default)
