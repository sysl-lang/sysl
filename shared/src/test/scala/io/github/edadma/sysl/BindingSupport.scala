package io.github.edadma.sysl

import io.github.edadma.cross_platform.*

/** Where a binding's library root sits, as seen from wherever the suite was started.
 *
 * `bindings/<name>/lib` is a **library project root**, so a module's name is the path below it —
 * `bindings/sqlite3/lib/sqlite` holds `sqlite`. The demo program beside the root is deliberately
 * outside it, since anything under the root is compiled *into* the library.
 */
object Bindings {

  def root(name: String): Option[String] =
    List(s"bindings/$name/lib", s"../bindings/$name/lib", s"../../bindings/$name/lib").find(isDirectory)
}

/** What a suite about one of the bindings in `bindings/` needs: the checked-in tree built into an
 * artifact, and programs compiled against it and run.
 *
 * **These suites read the checked-in source rather than a fixture, and that is the point.** A
 * binding is not a worked example whose audience is a reader — it is a library other programs
 * depend on, and the failure worth catching is the one where its source stops agreeing with the C
 * library underneath it. A fixture reconstructed in the test would pin a copy and leave the real
 * file free to rot, which is why `DevLibraryTests` writing its library out is right for what *it*
 * proves and would be wrong here. The demo program beside each root is the reader's half, and is
 * deliberately left alone.
 *
 * What these suites are *not* is a second test of `15 §7` or `15 §8`. Those are pinned on fixtures
 * in `LibraryBuildCliTests`, where the inputs can be chosen to be discriminating. Here the claims
 * are each binding's own.
 *
 * Everything goes through the driver — `build-lib`, then `run --lib` — because that is the whole
 * path a consumer takes and none of it is reachable from the compiler's own API.
 */
trait BindingSupport extends LibraryCliSupport {

  /** The directory under `bindings/`, which is named for the C library rather than for the module:
   * `sqlite3` holds the module `sqlite`.
   */
  protected def binding: String

  /** Whether the C library this binds is on the machine. `true` where binding it needs nothing
   * installed — POSIX regex is in libc — and a real probe otherwise, so that a machine without the
   * library skips these tests while a binding that has broken still fails them.
   */
  protected def available: Boolean = true

  protected def bindingRoot: Option[String] = Bindings.root(binding)

  /** The binding built into an artifact, once. It is the slowest thing in a suite — a compilation, a
   * clang run over the C, and an archive — and every test wants the same one.
   */
  protected lazy val bound: String = {
    val out = createTempFile(s"sysl-$binding-", LibraryArtifact.extension)

    cli(Config(command = "build-lib", file = bindingRoot.get, output = Some(out))) shouldBe 0
    out
  }

  protected def guard(): Unit = {
    assume(bindingRoot.isDefined, s"bindings/$binding/lib is not reachable from the working directory")
    assume(Toolchain.clangAvailable, "clang not available")
    assume(available, s"the library $binding binds is not on this machine")
  }

  /** A program compiled against the binding and run, giving its stdout. Nothing names a library on
   * the command line: a `link` directive lives in the binding's own header and travels in the
   * artifact, so a program that failed to link would be saying the directive did not arrive.
   */
  protected def out(src: String): String = {
    guard()
    ran(Config(command = "run", file = program(src), libs = List(bound)))
  }

  /** The diagnostics from a program that must not compile. */
  protected def refused(src: String): String = {
    guard()

    val (status, notes) = diagnostics(Config(command = "run", file = program(src), libs = List(bound)))

    status should not be 0
    notes
  }

  /** Whether the artifact holds a compiled member for one of the library's C files, named after the
   * directory it was found in — which is what keeps it distinct from another library's file of the
   * same name in the same link.
   */
  protected def carriesObject(name: String): Boolean =
    Ar.members(readBytes(bound)) match
      case Right(members) => members.find(_.name == name).map(_.body.length).getOrElse(0) > 0
      case Left(why)      => fail(why)

  protected def metadata: String =
    LibraryArtifact.metadataOf(bound, readBytes(bound)) match
      case Right(meta) => meta
      case Left(err)   => fail(err)
}
