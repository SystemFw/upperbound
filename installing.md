# Installing

Add to your `build.sbt`

```scala
libraryDependencies += "org.systemfw" %% "upperbound" % "0.5.1"
```

`upperbound` is published for the following versions of Scala:

- **2.13.16**
- **3.3.6**
- **2.12.20**

and depends on **cats-effect** and **fs2**.

Versioning follows SemVer, binary compatibility is maintained between patch
versions in 0.x releases, and between minor versions from 1.x releases
forward.
