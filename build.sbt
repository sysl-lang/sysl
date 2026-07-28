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
    libraryDependencies += "org.scalatest" %%% "scalatest" % "3.2.19" % "test",
    libraryDependencies ++= Seq(
      "com.github.scopt"         %%% "scopt"                    % "4.1.0",
      "org.scala-lang.modules"   %%% "scala-parser-combinators" % "2.4.0",
      // Off-side-rule lexer base (see docs/design/front-end.md).
      "io.github.edadma"         %%% "indentation"              % "0.0.5",
      // Cross-platform I/O boundary (see docs/design/cross-platform.md).
      "io.github.edadma"         %%% "path"                     % "0.0.6",
      "io.github.edadma"         %%% "cross_platform"           % "0.1.8",
//      "com.lihaoyi" %%% "pprint" % "0.9.6" % "test",
    ),
    publishMavenStyle      := true,
    Test / publishArtifact := false,
  )
  .jvmSettings(
    libraryDependencies += "org.scala-js" %% "scalajs-stubs" % "1.1.0" % "provided",
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
