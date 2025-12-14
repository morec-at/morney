package morney

import java.nio.file.{Path, Paths}

final case class AppConfig(dbPath: Path)

object AppConfig {
  private val DbPathEnv = "MORNEY_DB_PATH"

  def defaultDbPath: Path =
    Paths.get(sys.props("user.home"), ".morney", "morney.db")

  def fromArgs(args: List[String]): Either[String, (AppConfig, List[String])] = {
    val envDbPath = sys.env.get(DbPathEnv).filter(_.nonEmpty).map(Paths.get(_))
    val base = AppConfig(envDbPath.getOrElse(defaultDbPath))

    parseDbFlag(args, base).map { case (cfg, rest) => (cfg, rest) }
  }

  private def parseDbFlag(
    args: List[String],
    base: AppConfig
  ): Either[String, (AppConfig, List[String])] =
    args match {
      case "--db" :: value :: tail =>
        Right((base.copy(dbPath = Paths.get(value)), tail))
      case "--db" :: Nil =>
        Left("Missing value for --db")
      case head :: tail if head.startsWith("--db=") =>
        val value = head.drop("--db=".length)
        if (value.isEmpty) Left("Missing value for --db")
        else Right((base.copy(dbPath = Paths.get(value)), tail))
      case other =>
        Right((base, other))
    }
}

