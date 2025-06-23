Global / onChangedBuildSource := ReloadOnSourceChanges

ThisBuild / tlBaseVersion := "0.5"
ThisBuild / tlCiReleaseBranches := Seq()
ThisBuild / organization := "org.systemfw"
ThisBuild / organizationName := "Fabio Labella"
ThisBuild / developers ++= List(
  tlGitHubDev("SystemFw", "Fabio Labella")
)
ThisBuild / licenses := List(("MIT", url("http://opensource.org/licenses/MIT")))
ThisBuild / startYear := Some(2017)

val Scala213 = "2.13.16"

ThisBuild / crossScalaVersions := Seq(Scala213, "3.3.6", "2.12.20")
ThisBuild / scalaVersion := (ThisBuild / crossScalaVersions).value.head
ThisBuild / initialCommands := """
  |import cats._, data._, syntax.all._
  |import cats.effect._, concurrent._
  |import cats.effect.implicits._
  |import cats.effect.unsafe.implicits.global
  |import fs2._
  |import fs2.concurrent._
  |import scala.concurrent.duration._
  |import upperbound._
""".stripMargin

// If debugging tests, it's sometimes useful to disable parallel
// execution and test result buffering:
// ThisBuild / Test / parallelExecution := false
// ThisBuild / Test / testOptions += Tests.Argument(TestFrameworks.MUnit, "-b")

lazy val root = tlCrossRootProject
  .aggregate(core.jvm, core.js, core.native)

lazy val core = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("modules/core"))
  .settings(
    name := "upperbound",
    scalafmtOnCompile := true,
    libraryDependencies ++= List(
      "org.typelevel" %%% "cats-core" % "2.11.0",
      "org.typelevel" %%% "cats-effect" % "3.6.1",
      "org.typelevel" %%% "cats-effect-testkit" % "3.6.1" % Test,
      "co.fs2" %%% "fs2-core" % "3.12.0",
      "org.typelevel" %%% "munit-cats-effect" % "2.1.0" % Test,
      "org.typelevel" %%% "scalacheck-effect-munit" % "2.0.0-M2" % Test
    )
  )

lazy val docs = project
  .in(file("mdoc"))
  .settings(
    mdocIn := file("docs"),
    mdocOut := file("target/website"),
    mdocVariables := Map(
      "version" -> version.value,
      "scalaVersions" -> crossScalaVersions.value
        .map(v => s"- **$v**")
        .mkString("\n")
    ),
    laikaSite := {
      sbt.IO.copyDirectory(mdocOut.value, (laikaSite / target).value)
      Set.empty
    },
    tlJdkRelease := None,
    tlFatalWarnings := false
  )
  .dependsOn(core.jvm)
  .enablePlugins(TypelevelSitePlugin)
