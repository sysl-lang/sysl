import xerial.sbt.Sonatype.sonatypeCentralHost

ThisBuild / licenses               := Seq("ISC" -> url("https://opensource.org/licenses/ISC"))
ThisBuild / versionScheme          := Some("semver-spec")
ThisBuild / evictionErrorLevel     := Level.Warn
ThisBuild / scalaVersion           := "3.8.4"
ThisBuild / organization           := "sh.sysl"
ThisBuild / organizationName       := "sysl-lang"
ThisBuild / organizationHomepage   := Some(url("https://github.com/sysl-lang"))
ThisBuild / version                := "0.0.19"
ThisBuild / sonatypeCredentialHost := sonatypeCentralHost

ThisBuild / publishConfiguration := publishConfiguration.value.withOverwrite(true).withChecksums(Vector.empty)
ThisBuild / resolvers += Resolver.mavenLocal
ThisBuild / resolvers += Resolver.sonatypeCentralSnapshots
ThisBuild / resolvers += Resolver.sonatypeCentralRepo("releases")

ThisBuild / sonatypeProfileName := "sh.sysl"

ThisBuild / scmInfo := Some(
  ScmInfo(
    url("https://github.com/sysl-lang/sysl"),
    "scm:git@github.com:sysl-lang/sysl.git",
  ),
)
ThisBuild / developers := List(
  Developer(
    id = "edadma",
    name = "Edward A. Maxedon, Sr.",
    email = "edadma@gmail.com",
    url = url("https://github.com/edadma"),
  ),
)

ThisBuild / homepage := Some(url("https://github.com/sysl-lang/sysl"))
ThisBuild / description := "A modern, ref-counted, OS-level systems language — easier than Rust."

// Where a publish goes, and there are two answers because Central is slow.
//
// Central is the real one: it is what anybody depending on sysl resolves from, and a release is not
// a release until it is there. But it can take hours to propagate, and `~/dev/sysl.sh` — which
// compiles every fenced block on every page against a *released* compiler — cannot write a line of
// documentation for a release until it can resolve one. GitHub Packages answers in minutes, so the
// same artifacts go there too and the site can be written while Central catches up.
//
// Gated on an environment variable rather than a separate task, matching `SYSL_RELEASE`: one
// `publishTo`, switched, so there is no second configuration to drift.
//
//     sbt publishSigned                      # Central, 1 of 2 — stages locally, uploads nothing
//     sbt sonatypeBundleRelease              # Central, 2 of 2 — the upload, and the release
//     SYSL_PUBLISH_GITHUB=1 sbt publish      # GitHub Packages, the head start
//
// `publishSigned` alone publishes nothing: it writes the signed artifacts under
// `target/sonatype-staging/` and prints a `published … to <local path>` line for each, then exits
// successfully in about a second. `sonatypeBundleRelease` is what uploads the bundle and polls until
// the deployment reports `PUBLISHED`.
//
// **This does not shorten the release and must never be allowed to stand in for it.** A GitHub
// Packages copy makes a downstream build succeed whether or not the Central upload worked, which is
// the same masking that rules out `publishLocal` — so the Central check against `maven-metadata.xml`
// stays exactly as mandatory as it was.
ThisBuild / publishTo := {
  if (sys.env.contains("SYSL_PUBLISH_GITHUB"))
    Some("GitHub Packages" at "https://maven.pkg.github.com/sysl-lang/sysl")
  else
    sonatypePublishToBundle.value
}

// GitHub Packages authenticates every request, including reads of a public package, so both halves
// of this need a token. Kept out of the build for the obvious reason.
//
// Two places to find one, because the two machines that run this keep it differently. A workstation
// has a file; **CI has `GITHUB_TOKEN` and cannot have a file**, which is the case that matters —
// the whole point of publishing here is the window where Central has not propagated, and that is
// exactly when a build with no credentials would fail to resolve.
ThisBuild / credentials ++= githubCredentials

// The file wins where it exists, so a workstation that has set one up behaves as it did before this
// was added, and `GITHUB_TOKEN` cannot quietly take over from a token somebody chose on purpose.
lazy val githubCredentials: Seq[Credentials] = {
  val f = Path.userHome / ".sbt" / "github-credentials"

  if (f.exists)
    Seq(Credentials(f))
  else
    sys.env.get("GITHUB_TOKEN").toSeq.map { token =>
      // Any username authenticates against a valid token; CI's own actor is the honest one to send.
      Credentials(
        "GitHub Package Registry",
        "maven.pkg.github.com",
        sys.env.getOrElse("GITHUB_ACTOR", "edadma"),
        token,
      )
    }
}

// The version, carried into the compiler so that `sysl --version` can answer with it.
//
// Generated rather than hand-kept beside `ThisBuild / version`, because two places holding one fact
// drift, and this one drifts *silently* — a binary that reports the
// version before last is worse than one that reports none, since the whole use of the flag is telling
// which build somebody has when they report something.
lazy val embedVersion = Def.task {
  val utf8 = java.nio.charset.StandardCharsets.UTF_8
  val out  = (Compile / sourceManaged).value / "sh" / "sysl" / "BuildInfo.scala"
  val text =
    s"""package sh.sysl
       |
       |/** Generated from `version` by `build.sbt` -- do not edit. */
       |private[sysl] object BuildInfo {
       |  val version: String = "${version.value}"
       |}
       |""".stripMargin

  if (!out.exists || IO.read(out, utf8) != text) IO.write(out, text, utf8)
  Seq(out)
}

lazy val sysl = crossProject(JSPlatform, JVMPlatform, NativePlatform)
  .in(file("."))
  .settings(
    name := "sysl",
    scalacOptions ++=
      Seq(
        "-deprecation",
        "-feature",
        "-unchecked",
        // Dead code is only dead once something says so. Without this the build is silent about an
        // unused import, local, private member, or parameter, and a warning sweep before a release
        // has nothing to find. `-Wvalue-discard` and `-Wnonunit-statement` are deliberately *not*
        // here: between them they flag every non-final ScalaTest assertion, ~285 of them, which
        // would bury the handful of warnings that mean something.
        "-Wunused:all",
        "-language:postfixOps",
        "-language:implicitConversions",
        "-language:existentials",
        "-language:dynamics",
      ),
    Compile / sourceGenerators += embedVersion.taskValue,
    libraryDependencies += "org.scalatest" %%% "scalatest" % "3.2.20" % "test",
    libraryDependencies ++= Seq(
      "com.github.scopt"         %%% "scopt"                    % "4.1.0",
      "org.scala-lang.modules"   %%% "scala-parser-combinators" % "2.4.0",
      // Off-side-rule lexer base (see design/front-end.md).
      "io.github.edadma"         %%% "indentation"              % "0.0.6",
      // Cross-platform I/O boundary (see design/cross-platform.md).
      "io.github.edadma"         %%% "path"                     % "0.0.8",
      "io.github.edadma"         %%% "cross_platform"           % "0.1.9",
      // The project config's format (see design/packages.md §1).
      "io.github.edadma"         %%% "hocon"                    % "0.1.2",
//      "com.lihaoyi" %%% "pprint" % "0.9.6" % "test",
    ),
    publishMavenStyle      := true,
    Test / publishArtifact := false,
  )
  .jvmSettings(
    libraryDependencies += "org.scala-js" %% "scalajs-stubs" % "1.1.0" % "provided",
    // Hands ScalaTest a thread pool, which is what a suite mixing in `ParallelTestExecution`
    // needs before it can run its tests at the same time. Without it the mixin is not an error
    // and not a warning — it simply has nowhere to distribute to and runs everything in order,
    // which is how the documentation suite came to spend six minutes doing four hundred
    // compilations on one core while seventeen sat idle. That suite has moved to the site's own
    // repository and this setting stays, because the suites left here mix in the same thing.
    //
    // JVM-only on purpose: the JS and Native runners are single-threaded, so the two suites that
    // ask for parallelism there get the sequential behaviour they had before rather than an
    // argument their runner has no use for.
    // The thread count is explicit because sbt refuses a bare `-P` — it will not infer one on
    // ScalaTest's behalf. What these threads do is wait on `clang` and on the linker, so the
    // count is the machine's rather than a fraction of it: the work is in the child processes.
    Test / testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-P18"),
  )
  .nativeSettings(
//    libraryDependencies += "io.github.cquiroz" %%% "scala-java-time" % "2.7.0",
    libraryDependencies += "org.scala-js" %% "scalajs-stubs" % "1.1.0" % "provided",
    // The binary that gets installed is built in release mode; the one built while developing is
    // not. Scala Native defaults to debug, and that default is the right one here — a link is
    // seconds rather than minutes, and nothing about this project is iterated on from Native
    // anyway (the JVM build is the development loop). But an installed compiler is run by people
    // who did not build it, and shipping the unoptimized link would make sysl look slow for a
    // reason that has nothing to do with sysl.
    //
    // Gated on the environment rather than made the default, so that asking for the slow, careful
    // link is a deliberate act performed when cutting a release: `SYSL_RELEASE=1 sbt
    // syslNative/nativeLink`.
    nativeConfig ~= { c =>
      if (sys.env.get("SYSL_RELEASE").contains("1"))
        c.withMode(scala.scalanative.build.Mode.releaseFast)
          .withLTO(scala.scalanative.build.LTO.thin)
      else c
    },
  )
  .jsSettings(
    jsEnv := new org.scalajs.jsenv.nodejs.NodeJSEnv(),
    scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.ESModule) },
    //  scalaJSLinkerConfig ~= { _.withModuleSplitStyle(ModuleSplitStyle.SmallestModules) },
    scalaJSLinkerConfig ~= { _.withSourceMap(false) },
    //    Test / scalaJSUseMainModuleInitializer := true,
    //    Test / scalaJSUseTestModuleInitializer := false,
    Test / scalaJSUseMainModuleInitializer := false,
    Test / scalaJSUseTestModuleInitializer := true,
    scalaJSUseMainModuleInitializer        := true,
//    libraryDependencies += "io.github.cquiroz" %%% "scala-java-time" % "2.7.0",
  )

lazy val root = project
  .in(file("."))
  .aggregate(sysl.js, sysl.jvm, sysl.native)
  .settings(
    name                := "sysl",
    publish / skip      := true,
    publishLocal / skip := true,
  )
