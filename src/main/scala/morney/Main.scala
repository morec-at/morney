package morney

import morney.ui.CliApp
import zio.{Scope, ZIO, ZIOAppArgs, ZIOAppDefault}

object Main extends ZIOAppDefault {
  override def run: ZIO[ZIOAppArgs & Scope, Any, Any] =
    CliApp.run
}
