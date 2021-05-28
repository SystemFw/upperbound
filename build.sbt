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
  scalaVersion := "2.12.14",
  crossScalaVersions := Seq("2.11.12", scalaVersion.value, "2.13.1"),
  scalafmtOnCompile := true
)

lazy val consoleSettings = Seq(
  initialCommands := s"import upperbound._",
  scalacOptions in (Compile, console) -= "-Ywarn-unused-import"
)

lazy val compilerOptions = {
  val commonOptions = Seq(
    "-unchecked",
    "-deprecation",
    "-encoding",
    "utf8",
    "-target:jvm-1.8",
    "-feature",
    "-language:implicitConversions",
    "-language:higherKinds",
    "-language:existentials",
    "-Ywarn-value-discard"
  )

  scalacOptions ++= commonOptions ++ PartialFunction.condOpt(CrossVersion.partialVersion(scalaVersion.value)){
    case Some((2, scalaMajor)) if scalaMajor <= 12 => Seq("-Ypartial-unification", "-Ywarn-unused-import")
    case Some((2, scalaMajor)) if scalaMajor >= 13 => Seq()
  }.toList.flatten
}

lazy val typeSystemEnhancements =
  addCompilerPlugin("org.typelevel" %% "kind-projector" % "0.10.3")

def dep(org: String)(version: String)(modules: String*) =
  Seq(modules: _*) map { name =>
    org %% name % version
  }

lazy val dependencies =
  libraryDependencies ++= Seq(
    "co.fs2" %% "fs2-core" % "2.1.0",
    "org.typelevel" %% "cats-core" % "2.0.0",
    "org.typelevel" %% "cats-effect" % "2.0.0",
    "org.typelevel" %% "cats-collections-core" % "0.9.0"
  )

lazy val tests = {
  val dependencies =
    libraryDependencies ++= Seq(
      "org.scalacheck" %% "scalacheck" % "1.14.2",
      "org.scalatest" %% "scalatest" % "3.0.8",
      "org.typelevel" %% "cats-effect-laws" % "2.0.0"
    ).map(_ % "test")

  val frameworks =
    testFrameworks := Seq(TestFrameworks.ScalaTest)

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
