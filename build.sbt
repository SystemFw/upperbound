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
  scalaVersion := "2.13.6",
  crossScalaVersions := Seq("2.12.14", scalaVersion.value),
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
  addCompilerPlugin("org.typelevel" %% "kind-projector" % "0.13.0" cross CrossVersion.full)

lazy val dependencies =
  libraryDependencies ++= Seq(
    "co.fs2" %% "fs2-core" % "2.5.9",
    "org.typelevel" %% "cats-core" % "2.6.1",
    "org.typelevel" %% "cats-effect" % "2.5.2",
    "org.typelevel" %% "cats-collections-core" % "0.9.3"
  )

lazy val tests = {
  libraryDependencies ++= Seq(
    "org.scalacheck" %% "scalacheck" % "1.15.4",
    "org.scalatest" %% "scalatest" % "3.2.9",
    "org.scalatestplus" %% "scalacheck-1-15" % "3.2.9.0",
    "org.typelevel" %% "cats-effect-laws" % "2.5.2"
  ).map(_ % "test")
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
