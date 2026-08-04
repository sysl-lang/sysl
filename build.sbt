import xerial.sbt.Sonatype.sonatypeCentralHost

ThisBuild / licenses               := Seq("ISC" -> url("https://opensource.org/licenses/ISC"))
ThisBuild / versionScheme          := Some("semver-spec")
ThisBuild / evictionErrorLevel     := Level.Warn
ThisBuild / scalaVersion           := "3.8.4"
ThisBuild / organization           := "io.github.edadma"
ThisBuild / organizationName       := "edadma"
ThisBuild / organizationHomepage   := Some(url("https://github.com/edadma"))
ThisBuild / version                := "0.0.1"
ThisBuild / sonatypeCredentialHost := sonatypeCentralHost

ThisBuild / publishConfiguration := publishConfiguration.value.withOverwrite(true).withChecksums(Vector.empty)
ThisBuild / resolvers += Resolver.mavenLocal
ThisBuild / resolvers += Resolver.sonatypeCentralSnapshots
ThisBuild / resolvers += Resolver.sonatypeCentralRepo("releases")

ThisBuild / sonatypeProfileName := "io.github.edadma"

ThisBuild / scmInfo := Some(
  ScmInfo(
    url("https://github.com/edadma/sysl"),
    "scm:git@github.com:edadma/sysl.git",
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

ThisBuild / homepage := Some(url("https://github.com/edadma/sysl"))
ThisBuild / description := "A modern, ref-counted, OS-level systems language — easier than Rust."

ThisBuild / publishTo := sonatypePublishToBundle.value

// The core library's own source, embedded into the compiler that ships it.
//
// `lib/sysl` is where the standard module is *written* -- ordinary sysl files, read by the same
// driver a user's library goes through. But a compiler has to carry it: the standard module is what
// every program is compiled against, so it cannot be something a compilation goes looking for on
// disk and may not find. Generating the carrier from the files rather than hand-keeping a copy
// beside them is what makes the files the fact.
//
// It is a **tree** and not a flat directory. A module is a directory (`13 s1`), so a submodule of
// `sysl` is a directory under `lib/sysl`, and a listing one level deep would leave the files that
// declare it out of the compiler while they sat in plain sight in the tree -- the exact failure the
// generator exists to prevent, arrived at from the other side.
def syslLiteral(s: String): String =
  s.flatMap {
    case '"'  => "\\\""
    case '\\' => "\\\\"
    case '\n' => "\\n"
    case '\r' => "\\r"
    case '\t' => "\\t"
    case c    => c.toString
  }.mkString("\"", "", "\"")

lazy val embedCoreLibrary = Def.task {
  val utf8 = java.nio.charset.StandardCharsets.UTF_8
  val out  = (Compile / sourceManaged).value / "io" / "github" / "edadma" / "sysl" / "StdSource.scala"
  val dir  = (ThisBuild / baseDirectory).value / "lib" / "sysl"

  // Sorted by the path below `lib/sysl` rather than by the bare file name, so that what a
  // compilation sees does not depend on the order a directory happened to list, and two files of
  // different submodules that share a name stay apart. Separators are normalised because the name
  // is a diagnostic's file name and half of a module's, neither of which is the build host's
  // business.
  // Both spellings, because a library file may be literate (`Literate`) and the walk that reads the
  // library off disk takes both. A generator that took only one would leave a file out of the
  // embedded copy while it sat in plain sight in the tree -- which is the failure this generator
  // exists to prevent, arrived at from a third side.
  val files =
    ((dir ** "*.sysl").get ++ (dir ** "*.lsysl").get)
      .map(f => IO.relativize(dir, f).getOrElse(sys.error(s"$f is not under $dir")).replace('\\', '/') -> f)
      .sortBy(_._1)

  if (files.isEmpty) sys.error(s"the core library has no source files at $dir")

  val entries =
    files.map { case (path, f) =>
      s"""    ("lib/sysl/$path", ${syslLiteral(IO.read(f, utf8))}),"""
    }.mkString("\n")
  val text =
    s"""package io.github.edadma.sysl
       |
       |/** Generated from the sysl files under `lib/sysl` by `build.sbt` -- do not edit. See `Std`. */
       |private[sysl] object StdSource {
       |  val files: List[(String, String)] = List(
       |$entries
       |  )
       |}
       |""".stripMargin

  if (!out.exists || IO.read(out, utf8) != text) IO.write(out, text, utf8)
  Seq(out)
}

// The version, carried into the compiler so that `sysl --version` can answer with it.
//
// Generated rather than hand-kept beside `ThisBuild / version`, for the reason the core library is:
// two places holding one fact drift, and this one drifts *silently* — a binary that reports the
// version before last is worse than one that reports none, since the whole use of the flag is telling
// which build somebody has when they report something.
lazy val embedVersion = Def.task {
  val utf8 = java.nio.charset.StandardCharsets.UTF_8
  val out  = (Compile / sourceManaged).value / "io" / "github" / "edadma" / "sysl" / "BuildInfo.scala"
  val text =
    s"""package io.github.edadma.sysl
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
    Compile / sourceGenerators += embedCoreLibrary.taskValue,
    Compile / sourceGenerators += embedVersion.taskValue,
    libraryDependencies += "org.scalatest" %%% "scalatest" % "3.2.20" % "test",
    libraryDependencies ++= Seq(
      "com.github.scopt"         %%% "scopt"                    % "4.1.0",
      "org.scala-lang.modules"   %%% "scala-parser-combinators" % "2.4.0",
      // Off-side-rule lexer base (see docs/design/front-end.md).
      "io.github.edadma"         %%% "indentation"              % "0.0.5",
      // Cross-platform I/O boundary (see docs/design/cross-platform.md).
      "io.github.edadma"         %%% "path"                     % "0.0.7",
      "io.github.edadma"         %%% "cross_platform"           % "0.1.8",
      // The project config's format (see docs/design/packages.md §1).
      "io.github.edadma"         %%% "hocon"                    % "0.1.1",
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
    // which is how `DocsTests` came to spend six minutes doing four hundred compilations on one
    // core while seventeen sat idle.
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
