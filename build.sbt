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
  scalaVersion := "2.11.11",
  crossScalaVersions := Seq("2.11.11", "2.12.1")
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
  addCompilerPlugin("org.spire-math" %% "kind-projector" % "0.9.3")

def dep(org: String)(version: String)(modules: String*) =
  Seq(modules: _*) map { name =>
    org %% name % version
  }

lazy val dependencies = {
  val scalaz = dep("org.scalaz")("7.2.8")(
    "scalaz-core",
    "scalaz-concurrent"
  )

  val fs2 = Seq(
    "co.fs2" %% "fs2-core" % "0.9.7",
    "co.fs2" %% "fs2-scalaz" % "0.2.0"
  )

  libraryDependencies ++= Seq(
    scalaz,
    fs2
  ).flatten
}

lazy val tests = {
    val dependencies = {
    val specs2 = dep("org.specs2")("3.8.9")(
      "specs2-core",
      "specs2-scalaz",
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
    "-skip-packages",
    "fs2:scalaz",
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
