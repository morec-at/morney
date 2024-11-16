val scala3Version = "3.5.2"

lazy val root = project
  .in(file("."))
  .settings(
    name := "morney-api",
    version := "0.1.0-SNAPSHOT",
    scalaVersion := scala3Version,
    libraryDependencies ++= List(
      "dev.hnaderi" %% "edomata-core" % "0.12.4",
      "org.scalatest" %% "scalatest" % "3.2.19" % Test
    )
  )
