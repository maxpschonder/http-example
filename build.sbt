ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "2.13.8"

libraryDependencies ++= Seq(
  "dev.zio" %% "zio-logging" % "0.5.14",
  "io.d11"  %% "zhttp"       % "1.0.0.0-RC27",
  "io.d11"  %% "zhttp-test"  % "1.0.0.0-RC27" % Test,
)

lazy val root = (project in file("."))
  .settings(
    name               := "http-example",
    dockerBaseImage    := "eclipse-temurin:11-jre",
    dockerUpdateLatest := true,
    dockerExposedPorts := Seq(8090),
  )

enablePlugins(JavaServerAppPackaging, DockerPlugin)
