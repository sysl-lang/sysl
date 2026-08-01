package io.github.edadma.sysl

import io.github.edadma.cross_platform.*

/** The standard module as a **prebuilt artifact**, built once for the whole test run.
 *
 * Every run-tier test compiles a program against the standard module, and there has been one way to
 * be handed it: the copy the compiler carries, whose declarations are then *emitted into each test
 * program's IR* and handed to clang. This builds the artifact instead — once, on first use — so that
 * the library's determined half is **linked** rather than compiled again for every test.
 *
 * It is the same artifact `sysl build-lib lib --core` writes, produced by the same calls in the same
 * order, so a test compiled against this one is compiled against what a user's build would find at
 * `.sysl/core.syslib`. That is the property worth having: the suite exercises the path an ordinary
 * compilation takes, rather than the bootstrap path that only exists for the suite's own benefit.
 *
 * **Absent a toolchain there is nothing to build, and that is not a failure.** `RunSupport` already
 * cancels a run-tier test when `clang` is missing; this answers `None` under the same condition so
 * the cancellation still comes from there, with its own message, rather than from an error here
 * about an archiver.
 */
object PrebuiltCore {

  /** The artifact for the machine the suite is running on: what to compile against, which symbols it
   * already defines, and the archive to link them from.
   *
   * One target, because a run-tier test builds a program it is about to execute. A `lazy val` because
   * building it costs two clang invocations and an archiver, and nothing about the answer changes
   * between two tests in one run — which is the whole point of it.
   */
  lazy val forHost: Option[(Core, Set[String], String)] = built(Target.default)

  private def built(target: Target): Option[(Core, Set[String], String)] = {
    if !Toolchain.clangAvailable then return None

    val ar = Toolchain.findAr(None) match
      case Right(path) => path
      case Left(_)     => return None

    // The library's own source, compiled as the library rather than handed to itself: `building` is
    // what stops the files that *declare* `sysl` from also being given a copy of it, which is the
    // same distinction `build-lib --core` draws.
    val (ir, meta) =
      LibraryArtifact.build(Std.sources, target, LibraryArtifact.core, Some(Core.embedded(target))) match
        case Right(pair) => pair
        case Left(err)   => sys.error(s"the standard module does not build as a library: $err")

    val staging  = createTempDirectory("sysl-test-core-")
    val code     = s"$staging/${LibraryArtifact.codeMember}"
    val metadata = s"$staging/${LibraryArtifact.metadataMember}"
    val out      = s"$staging/core${LibraryArtifact.extension}"

    val archived =
      for
        _ <- Toolchain.compileObject(ir, code, target)
        _ <- Toolchain.compileObject(LibraryArtifact.metadataIr(meta, target), metadata, target)
        _ <- Toolchain.archive(List(code, metadata), out, ar)
      yield ()

    archived match
      case Left(err) => sys.error(s"the standard module does not archive: $err")
      case Right(_)  => ()

    // Read back rather than kept from the build: what a compilation is compiled against has to be
    // what the artifact *says*, decoded through `AstCodec` exactly as an ordinary build would decode
    // it. Keeping the trees that went in would test a path no user takes.
    val core =
      for
        m      <- LibraryArtifact.metadataOf(out, readBytes(out))
        result <- Core.read(out, m, target)
      yield result

    core match
      case Left(err)                  => sys.error(s"the standard module does not read back: $err")
      case Right((decoded, symbols))  => Some((decoded, symbols, out))
  }
}
