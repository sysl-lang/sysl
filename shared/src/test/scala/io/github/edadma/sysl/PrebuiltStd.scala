package io.github.edadma.sysl

import io.github.edadma.cross_platform.*

/** The standard module as a **prebuilt artifact**, built once for the whole test run.
 *
 * Every run-tier test compiles a program against the standard module, and there has been one way to
 * be handed it: the copy the compiler carries, whose declarations are then *emitted into each test
 * program's IR* and handed to clang. This builds the artifact instead — once, on first use — so that
 * the library's determined half is **linked** rather than compiled again for every test.
 *
 * It is the same artifact `sysl build-lib lib --std` writes, made by the same call
 * (`Stdlib.writeArtifact`) rather than by a second routine of the suite's own, so a test compiled
 * against this one is compiled against what a user's build would find at the default path. That is
 * the property worth having: the suite exercises the path an ordinary compilation takes, rather than
 * a bootstrap path that only exists for its own benefit — and a change to how the artifact is made
 * cannot reach the compiler without reaching the suite.
 *
 * **Absent a toolchain there is nothing to build, and that is not a failure.** `RunSupport` already
 * cancels a run-tier test when `clang` is missing; this answers `None` under the same condition so
 * the cancellation still comes from there, with its own message, rather than from an error here
 * about an archiver.
 */
object PrebuiltStd {

  /** The artifact for the machine the suite is running on: what to compile against, which symbols it
   * already defines, and the archive to link them from.
   *
   * One target, because a run-tier test builds a program it is about to execute. A `lazy val` because
   * building it costs two clang invocations and an archiver, and nothing about the answer changes
   * between two tests in one run — which is the whole point of it.
   */
  lazy val forHost: Option[(Stdlib, Set[String], String)] = built(Target.default)

  private def built(target: Target): Option[(Stdlib, Set[String], String)] = {
    if !Toolchain.clangAvailable then return None
    if Toolchain.findAr(None).isLeft then return None

    // The same call `sysl` makes when nothing usable is at the default path (`Main.foundStd`), which
    // is what makes this the artifact an ordinary build would find rather than one the suite knows
    // how to make.
    val out = s"${createTempDirectory("sysl-test-std-")}/std${LibraryArtifact.extension}"

    Stdlib.writeArtifact(out, target) match
      case Left(err) => sys.error(s"the standard module does not build as an artifact: $err")
      case Right(_)  => ()

    // Read back rather than kept from the build: what a compilation is compiled against has to be
    // what the artifact *says*, decoded through `AstCodec` exactly as an ordinary build would decode
    // it. Keeping the trees that went in would test a path no user takes.
    val std =
      for
        m      <- LibraryArtifact.metadataOf(out, readBytes(out))
        result <- Stdlib.read(out, m, target)
      yield result

    std match
      case Left(err)                  => sys.error(s"the standard module does not read back: $err")
      case Right((decoded, symbols))  => Some((decoded, symbols, out))
  }
}
