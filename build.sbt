Global / onChangedBuildSource := ReloadOnSourceChanges

ThisBuild / tlBaseVersion := "0.5"
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

// If debugging tests, it's sometimes useful to disable parallel execution and test result buffering:
// ThisBuild / Test / parallelExecution := false
// ThisBuild / Test / testOptions += Tests.Argument(TestFrameworks.MUnit, "-b")


def dep(org: String, prefix: String, version: String)(
    modules: String*
)(testModules: String*) =
  Def.setting {
    modules.map(m => org %%% (prefix ++ m) % version) ++
      testModules.map(m => org %%% (prefix ++ m) % version % Test)
  }

lazy val root = project
  .in(file("."))
  .enablePlugins(NoPublishPlugin)
  .aggregate(core.jvm, core.js, core.native)

lazy val core = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("modules/core"))
  .settings(
    name := "upperbound",
    scalafmtOnCompile := true,
    libraryDependencies ++=
      dep("org.typelevel", "cats-", "2.11.0")("core")().value ++
        dep("org.typelevel", "cats-effect", "3.6.1")("")(
          "-laws",
          "-testkit"
        ).value ++
        dep("co.fs2", "fs2-", "3.12.0")("core")().value ++
        dep("org.typelevel", "", "2.1.0")()("munit-cats-effect").value ++
        dep("org.typelevel", "", "2.0.0-M2")()("scalacheck-effect-munit").value
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
    githubWorkflowArtifactUpload := false,
    tlFatalWarnings := false
  )
  .dependsOn(core.jvm)
  .enablePlugins(MdocPlugin, NoPublishPlugin)

ThisBuild / githubWorkflowBuildPostamble ++= List(
  WorkflowStep.Sbt(
    List("docs/mdoc"),
    cond = Some(s"matrix.scala == '$Scala213'")
  )
)

ThisBuild / githubWorkflowAddedJobs += WorkflowJob(
  id = "docs",
  name = "Deploy docs",
  needs = List("publish"),
  cond = """
  | always() &&
  | needs.build.result == 'success' &&
  | (needs.publish.result == 'success' || github.ref == 'refs/heads/docs-deploy')
  """.stripMargin.trim.linesIterator.mkString.some,
  steps = githubWorkflowGeneratedDownloadSteps.value.toList :+
    WorkflowStep.Use(
      UseRef.Public("peaceiris", "actions-gh-pages", "v3"),
      name = Some(s"Deploy docs"),
      params = Map(
        "publish_dir" -> "./target/website",
        "github_token" -> "${{ secrets.GITHUB_TOKEN }}"
      )
    ),
  scalas = List(Scala213),
  javas = githubWorkflowJavaVersions.value.toList
)
