Global / onChangedBuildSource := ReloadOnSourceChanges

ThisBuild / baseVersion := "0.4.0"
ThisBuild / organization := "org.systemfw"
ThisBuild / publishGithubUser := "SystemFw"
ThisBuild / publishFullName := "Fabio Labella"
ThisBuild / homepage := Some(url("https://github.com/SystemFw/upperbound"))
ThisBuild / scmInfo := Some(
  ScmInfo(
    url("https://github.com/SystemFw/upperbound"),
    "git@github.com:SystemFw/upperbound.git"
  )
)
ThisBuild / licenses := List(("MIT", url("http://opensource.org/licenses/MIT")))
ThisBuild / startYear := Some(2017)
Global / excludeLintKeys += scmInfo

val Scala213 = "2.13.8"
ThisBuild / spiewakMainBranches := Seq("main")

ThisBuild / crossScalaVersions := Seq(Scala213, "3.2.2", "2.12.14")
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

ThisBuild / testFrameworks += new TestFramework("munit.Framework")
ThisBuild / Test / parallelExecution := false

def dep(org: String, prefix: String, version: String)(
    modules: String*
)(testModules: String*) =
  Def.setting {
    modules.map(m => org %%% (prefix ++ m) % version) ++
      testModules.map(m => org %%% (prefix ++ m) % version % Test)
  }

lazy val root = project
  .in(file("."))
  .enablePlugins(NoPublishPlugin, SonatypeCiReleasePlugin)
  .aggregate(core.jvm, core.js, core.native)

lazy val core = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("modules/core"))
  .settings(
    name := "upperbound",
    scalafmtOnCompile := true,
    libraryDependencies ++=
      dep("org.typelevel", "cats-", "2.9.0")("core")().value ++
        dep("org.typelevel", "cats-effect", "3.5.0")("")("-laws", "-testkit").value ++
        dep("co.fs2", "fs2-", "3.7.0")("core")().value ++
        dep("org.scalameta", "munit", "1.0.0-M7")()("", "-scalacheck").value ++
        dep("org.typelevel", "", "2.0.0-M3")()("munit-cats-effect").value ++
        dep("org.typelevel", "scalacheck-effect", "2.0.0-M2")()("", "-munit").value
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
    fatalWarningsInCI := false
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
