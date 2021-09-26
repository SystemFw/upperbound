# Developer flow

## Local docs

Docs are based on:

- `docsify`, a _dynamic_ , markdown based generator.
- `mdoc`, typechecked scala/markdown compiler

The source for the docs is in `yourProject/docs`, the website in
`yourProject/target/website`. The currently deployed website is in the
`gh-pages` branch.

To preview the site locally, you need to install:

```
npm i docsify-cli -g
```

then, start mdoc in an sbt session:

```
sbt docs/mdoc --watch
```

and docsify in a shell session:

```
cd yourProject/target/website
docsify serve .
```

and you'll get an updating preview.
Note that `mdoc` watches the markdown files, so if you change the code
itself it will need a manual recompile.

`docsify` uses 3 special files: `index.html`, `_coverpage.md`, `_sidebar.md`,
the sidebar needs to have a specific format:

- newlines in between headers
- and no extra modifiers inside links `[good]`, `[**bad**]` (or collapse will not work)

## Release

Push a `vx.y.z` tag on `main` to release. It will fail if semver isn't
respected wrt bincompat.
Docs are released automatically on each code release, if you need a
docs-only deploy, (force) push `main` to the `docs-deploy` branch.

To change/add branches to release:

> ThisBuild / spiewakMainBranches := Seq("main", "develop")

To relax semver:

> ThisBuild / strictSemVer := false

To publish snapshot on every main build:

> ThisBuild / spiewakCiReleaseSnapshots := true

Caveat:
If you are publishing snapshots, you need to make sure that new
commits are fully built before you push a proper release tag: push
`main`, wait for the snapshot release to complete, and then push the
tag.

## Links

- https://github.com/djspiewak/sbt-spiewak
- https://github.com/djspiewak/sbt-github-actions
- https://docsify.js.org/#/
- https://scalameta.org/mdoc/
