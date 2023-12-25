# Installing

Add to your `build.sbt`

```scala
libraryDependencies += "org.systemfw" %% "upperbound" % "0.4.0-2-db0722f"
```

`upperbound` is published for the following versions of Scala:

- **2.13.8**
- **3.2.2**
- **2.12.14**

and depends on **cats-effect** and **fs2**.

Versioning follows SemVer, binary compatibility is maintained between patch
versions in 0.x releases, and between minor versions from 1.x releases
forward.
