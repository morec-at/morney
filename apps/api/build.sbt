name := "morney-api"

ThisBuild / scalaVersion := "3.7.4"
ThisBuild / scalacOptions ++= Seq(
  // format: off
  "-encoding", "utf8",
  // format: on
  "-deprecation",
  "-unchecked",
  "-feature",
  "-Xfatal-warnings",
  "-Wvalue-discard",
  "-Wnonunit-statement",
  "-Wunused:imports",
  "-Wunused:locals",
  "-Wunused:params",
  "-Wunused:privates",
  "-Wunused:patvars",
  "-Wunused:implicits"
)

lazy val root = project
  .in(file("."))

libraryDependencies ++= Seq(
  "dev.zio" %% "zio" % "2.1.12",
  "dev.zio" %% "zio-http" % "3.0.0"
)

libraryDependencies ++= Seq(
  "dev.zio" %% "zio-test" % "2.1.12" % Test,
  "dev.zio" %% "zio-test-sbt" % "2.1.12" % Test
)

testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")
