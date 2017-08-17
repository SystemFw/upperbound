lazy val root = (project in file(".")).settings(
  commonSettings,
  compilerOptions,
  consoleSettings,
  typeSystemEnhancements,
  dependencies,
  testSettings,
  publishSettings
)

lazy val commonSettings = Seq(
  organization := "org.systemfw",
  name := "upperbound",
  scalaVersion := "2.11.11",
  crossScalaVersions := Seq("2.11.11", "2.12.1")
)

lazy val consoleSettings = Seq(
  initialCommands := s"import com.ncr.bound._",
  scalacOptions in (Compile, console) -= "-Ywarn-unused-import"
)

lazy val compilerOptions =
  scalacOptions ++= Seq(
    "-unchecked",
    "-deprecation",
    "-encoding",
    "utf8",
    "-target:jvm-1.8",
    "-feature",
    "-language:implicitConversions",
    "-language:higherKinds",
    "-language:existentials",
    "-Ypartial-unification",
    "-Ywarn-unused-import",
    "-Ywarn-value-discard"
  )

lazy val typeSystemEnhancements =
  addCompilerPlugin("org.spire-math" %% "kind-projector" % "0.9.3")

def dep(org: String)(version: String)(modules: String*) =
  Seq(modules: _*) map { name =>
    org %% name % version
  }

lazy val dependencies = {
  val fs2 = dep("co.fs2")("0.10.0-M4")(
    "fs2-core",
    "fs2-io"
  )

  val mixed = Seq(
    "org.typelevel" %% "cats" % "0.9.0",
    "org.typelevel" %% "cats-effect" % "0.3"
  )

  libraryDependencies ++= Seq(
    fs2,
    mixed
  ).flatten
}

lazy val testSettings = {
  val specs2 = Seq(
    "specs2-core",
    "specs2-matcher-extra",
    "specs2-scalacheck"
  ).map("org.specs2" %% _ % "3.8.8")

  val scalacheck = "org.scalacheck" %% "scalacheck" % "1.13.4"

  Seq(
    libraryDependencies ++= (specs2 :+ scalacheck).map(_ % "test"),
    testFrameworks := Seq(TestFrameworks.Specs2)
  )
}

import ReleaseTransformations._

lazy val publishSettings = {
  val username = "SystemFw"

  Seq(
    homepage := Some(url(s"https://github.com/$username/${name.value}")),
    licenses += "MIT" -> url("http://opensource.org/licenses/MIT"),
    scmInfo := Some(
      ScmInfo(
        url(s"https://github.com/$username/${name.value}"),
        s"git@github.com:$username/${name.value}.git"
      )
    ),
    publishMavenStyle := true,
    publishArtifact in Test := false,
    publishTo := Some(
      if (isSnapshot.value)
        Opts.resolver.sonatypeSnapshots
      else Opts.resolver.sonatypeStaging
    ),
    pomExtra := (
      <developers>
        <developer>
         <id>{username}</id>
         <name>Fabio Labella</name>
         <url>http://github.com/{username}</url>
        </developer>
      </developers>
    ),
    releaseCrossBuild := true,
    releasePublishArtifactsAction := PgpKeys.publishSigned.value,
    releaseProcess := Seq[ReleaseStep](
      checkSnapshotDependencies,
      inquireVersions,
      runClean,
      runTest,
      setReleaseVersion,
      commitReleaseVersion,
      tagRelease,
      publishArtifacts,
      setNextVersion,
      commitNextVersion,
      releaseStepCommand("sonatypeReleaseAll"),
      pushChanges
    )
  )
}
