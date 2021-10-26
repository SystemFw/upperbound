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

val Scala213 = "2.13.6"
ThisBuild / spiewakMainBranches := Seq("main")

ThisBuild / crossScalaVersions := Seq(Scala213, "3.0.0", "2.12.14")
ThisBuild / versionIntroduced := Map("3.0.0" -> "0.4.0")
ThisBuild / scalaVersion := (ThisBuild / crossScalaVersions).value.head
Global / excludeLintKeys += versionIntroduced
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

def dep(org: String, prefix: String, version: String)(modules: String*)(testModules: String*) =
  modules.map(m => org %% (prefix ++ m) % version) ++
   testModules.map(m => org %% (prefix ++ m) % version % Test)

lazy val root = project
  .in(file("."))
  .enablePlugins(NoPublishPlugin, SonatypeCiReleasePlugin)
  .aggregate(core)

lazy val core = project
  .in(file("modules/core"))
  .settings(
    name := "upperbound",
    scalafmtOnCompile := true,
    libraryDependencies ++=
      dep("org.typelevel", "cats-", "2.6.1")("core")() ++
      dep("org.typelevel", "cats-effect", "3.3-393-da7c7c7")("")("-laws", "-testkit") ++
      dep("co.fs2", "fs2-", "3.1.3")("core")() ++
      dep("org.scalameta", "munit", "0.7.29")()("", "-scalacheck") ++
      dep("org.typelevel", "", "1.0.5")()("munit-cats-effect-3") ++
      dep("org.typelevel",  "scalacheck-effect", "1.0.2")()("", "-munit")
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
  .dependsOn(core)
  .enablePlugins(MdocPlugin, NoPublishPlugin)

ThisBuild / githubWorkflowJavaVersions := Seq("adopt@1.11.0-11")

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
