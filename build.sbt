lazy val root = (project in file(".")).settings(
  commonSettings,
  compilerOptions,
  consoleSettings,
  typeSystemEnhancements,
  dependencies,
  tests,
  docs,
  publishSettings
)

lazy val commonSettings = Seq(
  organization := "org.systemfw",
  name := "upperbound",
  scalaVersion := "2.12.6",
  crossScalaVersions := Seq("2.11.12", scalaVersion.value)
)

lazy val consoleSettings = Seq(
  initialCommands := s"import upperbound._",
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
  addCompilerPlugin("org.spire-math" %% "kind-projector" % "0.9.8")

def dep(org: String)(version: String)(modules: String*) =
  Seq(modules: _*) map { name =>
    org %% name % version
  }

lazy val dependencies =
  libraryDependencies ++= Seq(
    "co.fs2" %% "fs2-core" % "1.0.0",
    "org.typelevel" %% "cats-core" % "1.4.0",
    "org.typelevel" %% "cats-effect" % "1.0.0",
    "org.typelevel" %% "cats-collections-core" % "0.7.0"
  )

lazy val tests = {
  // TODO scalatest, cats-effect-laws
  val dependencies = {
    val specs2 = dep("org.specs2")("3.8.9")(
      "specs2-core",
      "specs2-scalacheck"
    )

    val mixed = Seq(
      "org.scalacheck" %% "scalacheck" % "1.13.4",
      "org.scalactic" %% "scalactic" % "3.0.1"
    )

    libraryDependencies ++= Seq(
      specs2,
      mixed
    ).flatten.map(_ % "test")
  }

  val frameworks =
    testFrameworks := Seq(TestFrameworks.Specs2)

  Seq(dependencies, frameworks)
}

lazy val docs =
  scalacOptions in (Compile, doc) ++= Seq(
    "-no-link-warnings"
  )

lazy val publishSettings = {
  import ReleaseTransformations._

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
