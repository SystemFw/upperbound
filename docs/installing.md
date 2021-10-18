# Installing

Add to your `build.sbt`

```scala
libraryDependencies += "org.systemfw" %% "upperbound" % "@version@"
```

`upperbound` is published for the following versions of Scala:

@scalaVersions@

and depends on **cats-effect** and **fs2**.

Versioning follows SemVer, binary compatibility is maintained between patch
versions in 0.x releases, and between minor versions from 1.x releases
forward.
