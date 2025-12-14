ThisBuild / scalaVersion := "3.3.4"
ThisBuild / organization := "dev.morney"
ThisBuild / version := "0.1.0-SNAPSHOT"

name := "morney"

libraryDependencies ++= Seq(
  "dev.zio" %% "zio" % "2.0.21",
  "dev.zio" %% "zio-streams" % "2.0.21",
  "org.xerial" % "sqlite-jdbc" % "3.46.1.3",
  "dev.zio" %% "zio-test" % "2.0.21" % Test,
  "dev.zio" %% "zio-test-sbt" % "2.0.21" % Test
)

testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")
