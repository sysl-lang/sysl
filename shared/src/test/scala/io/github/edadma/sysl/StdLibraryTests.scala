package io.github.edadma.sysl

import io.github.edadma.cross_platform.*

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** Where the standard module library sits, as seen from wherever the suite was started.
 *
 * `lib/` is a **library project root** holding the single module `sysl`, so a module's name is the
 * path below it exactly as it is for a program (`13 §1`) — which is why the root is what is found
 * here and `lib/sysl` is not.
 */
object StdRoot {

  def root: Option[String] = List("lib", "../lib", "../../lib").find(isDirectory)
}

/** How a compilation gets to the standard module's source, now that the compiler reads it off disk
 * rather than carrying a generated copy of it (`Std`).
 *
 * **This used to be the test that said the two copies agreed**, and there is only one copy now — so
 * what it asks instead is the question that replaced that one: *can the compiler find its library,
 * and is what it found the library?* The failure it guards against changed shape with the mechanism.
 * A generator that stopped running left a compiler working against source a reader could not see; a
 * resolution that goes wrong leaves a compiler that cannot compile anything at all, and has to say
 * so well enough to be fixed.
 */
class StdLibraryTests extends AnyFreeSpec with Matchers {

  /** A file's place in the library: the module directories it sits under, then its own name. The
   * one thing that is true of a file wherever the library was found, which is what lets two readings
   * of it be compared at all.
   */
  private def place(s: Source): String =
    (s.dir.getOrElse(Nil) :+ Project.basename(s.name)).mkString("/")

  /** The resolution, driven over made-up inputs so that every branch of it is reachable from any
   * machine — the ambient one can only ever exercise whichever case happens to hold here.
   */
  private def resolve(named: Option[String], exe: Option[String], present: String*) =
    Std.rootOf(named, exe, present.map(root => s"$root/${Std.module}").toSet)

  "finding the library" - {

    "takes an installed compiler to the tree installed beside it" in {
      // The whole point of the change: `/opt/homebrew/bin/sysl` is a symlink, `executablePath`
      // resolves it to the Cellar, and the library is under that prefix in the ordinary Unix place.
      // Nothing is configured and nothing is carried.
      resolve(None, Some("/opt/homebrew/Cellar/sysl/0.0.2/bin/sysl"),
        "/opt/homebrew/Cellar/sysl/0.0.2/share/sysl/lib") shouldBe
        Right("/opt/homebrew/Cellar/sysl/0.0.2/share/sysl/lib")
    }

    "and two installs of different versions each find their own" in {
      // Which is what keying on the compiler's own location buys over a fixed path: the answer moves
      // with the binary, so an old sysl left in the Cellar compiles against the library it shipped
      // with rather than against the newest one on the machine.
      val both = Seq("/opt/sysl/0.0.1/share/sysl/lib", "/opt/sysl/0.0.2/share/sysl/lib")

      resolve(None, Some("/opt/sysl/0.0.1/bin/sysl"), both*) shouldBe Right(both.head)
      resolve(None, Some("/opt/sysl/0.0.2/bin/sysl"), both*) shouldBe Right(both.last)
    }

    "while a compiler run out of a checkout finds the tree it was built from" in {
      // The development case, and the one every run of this suite is actually in. The JVM build has
      // no executable path to speak of — its executable is `java` — so this is the only answer it
      // ever gets, which is why it has to work with nothing else present.
      resolve(None, None, "lib") shouldBe Right("lib")
    }

    "from a directory or two below it, since a suite need not run at the root" in {
      resolve(None, None, "../lib") shouldBe Right("../lib")
      resolve(None, None, "../../lib") shouldBe Right("../../lib")
    }

    "and prefers the installed tree to whatever `lib` the working directory happens to hold" in {
      // Ordering is a guard, so it is asserted with both present rather than read off the list.
      resolve(None, Some("/usr/local/bin/sysl"), "/usr/local/share/sysl/lib", "lib") shouldBe
        Right("/usr/local/share/sysl/lib")
    }

    "and a `lib` that is not a sysl library is not a candidate at all" in {
      // The other half of that guard, and the one ordering cannot supply: `lib` is an ordinary
      // directory name — a C project has one, and so does half of everything else — so a compiler
      // standing in someone's source tree would take theirs and fail somewhere far from the cause.
      // What is asked for is `<root>/sysl`, so a `lib` holding anything else is simply skipped.
      Std.rootOf(None, None, Set("lib")) match
        case Right(found) => fail(s"a lib/ with no '${Std.module}' in it resolved to $found")
        case Left(err)    => err should include("lib")
    }
  }

  "SYSL_LIB" - {

    "names the library outright, beating everything else" in {
      resolve(Some("/tmp/mine"), Some("/usr/local/bin/sysl"),
        "/tmp/mine", "/usr/local/share/sysl/lib", "lib") shouldBe Right("/tmp/mine")
    }

    "aimed at the module rather than at the root, says which of the two is wanted" in {
      // The mistake anybody would make once: `lib/sysl` is the module, `lib` is the root, and they
      // differ by one segment. Told the wrong one, the message says which — rather than reporting
      // that a directory plainly sitting there is not a library.
      Std.rootOf(Some("/tmp/lib/sysl"), None, Set("/tmp/lib/sysl")) match
        case Right(found) => fail(s"the module directory should not have resolved as a root: $found")
        case Left(err) =>
          err should include("/tmp/lib/sysl")
          err should include("above")
    }

    "and one that is not a library root at all is refused rather than fallen back from" in {
      // The rule a named `--std-lib` and a named `--ar` already take: someone who wrote down which
      // library to use is owed an error when it is not there, not a different library compiled
      // against underneath them. It is the only candidate that reports its own failure.
      resolve(Some("/tmp/typo"), None, "lib") match
        case Right(found) => fail(s"SYSL_LIB naming nothing should not have resolved to $found")
        case Left(err) =>
          err should include("/tmp/typo")
          err should include("SYSL_LIB")
    }
  }

  "a compiler that cannot find its library" - {

    "says so, and names every place it looked" in {
      // What replaces the guarantee the carried copy gave. A broken install is now possible, so the
      // difference between a broken install and a mystery is this list — and each path is asserted
      // rather than the message merely being non-empty.
      val err = resolve(None, Some("/opt/sysl/0.0.2/bin/sysl")).left.getOrElse(fail("should not have resolved"))

      err should include("/opt/sysl/0.0.2/share/sysl/lib")
      err should include("  lib (")
      err should include("  ../lib (")
      err should include("  ../../lib (")
      err should include("SYSL_LIB")
    }

    "and lists them in the order it tried them" in {
      val err = resolve(None, Some("/opt/sysl/0.0.2/bin/sysl")).left.getOrElse(fail("should not have resolved"))

      err.indexOf("/opt/sysl/0.0.2/share/sysl/lib") should be < err.indexOf("  lib (")
      err.indexOf("  lib (") should be < err.indexOf("  ../lib (")
      err.indexOf("  ../lib (") should be < err.indexOf("  ../../lib (")
    }
  }

  "the library this compiler resolved" - {

    "is the one in this checkout" in {
      assume(StdRoot.root.isDefined, "lib/ not found from the test working directory")

      // The development-loop claim, and the one the whole suite rests on: what every test compiles
      // against is the tree a reader can open and edit. Compared by place and by text, so a file
      // added to the tree and not seen by the compiler fails here.
      val onDisk = Project.collect(StdRoot.root.get).map(s => place(s) -> s.text).toMap

      Std.sources.map(s => place(s) -> s.text).toMap shouldBe onDisk
    }

    "and names each file by its place in the library rather than by where it was read from" in {
      // So that a diagnostic naming a library file reads the same on every machine. See `Std.named`
      // — this is the claim the full suite caught being broken, in two library pages that quote a
      // refusal about a private field of `sysl.thread.Mutex`.
      Std.sources.map(_.name) shouldBe Std.sources.map(s => s"lib/${place(s)}")
    }

    "the same name whether it was read from a checkout or from an install" in {
      // The discriminating case, which the checkout alone cannot show: the two roots differ in every
      // segment but the last, and the names have to come out identical.
      val checkout = Std.named("lib", Source("lib/sysl/print.sysl", "x", List("sysl")))

      val installed =
        Std.named("/opt/homebrew/Cellar/sysl/0.0.3/share/sysl/lib",
          Source("/opt/homebrew/Cellar/sysl/0.0.3/share/sysl/lib/sysl/print.sysl", "x", List("sysl")))

      checkout.name shouldBe "lib/sysl/print.sysl"
      installed.name shouldBe checkout.name
    }

    "in a fixed order, decided by the library rather than by a directory listing" in {
      Std.sources.map(place) shouldBe Std.sources.map(place).sorted
    }

    "with every file in the module its own location says, however deep" in {
      // The tree claim, asked of the real library rather than of a string helper: a file's `dir` is
      // exactly the directories between the root and it, so its path has to end with them.
      for s <- Std.sources do withClue(s"${s.name}: ")(s.name should endWith(place(s)))

      Std.sources.foreach(_.dir.get.head shouldBe Std.module)

      // And the deep case is genuinely exercised — `sysl.math.complex` is two below the standard
      // module — so the claim above is not being met by a flat directory.
      Std.sources.map(_.dir.get.length).max should be >= 3
    }
  }

  "where the library was found does not change what it is" - {

    "so the same tree read from somewhere else fingerprints the same" in {
      assume(StdRoot.root.isDefined, "lib/ not found from the test working directory")

      // **This is the claim the whole change rests on.** The artifact is keyed by this fingerprint,
      // and an installed compiler reads the library from its own prefix while a checkout reads it
      // from `lib/`. If the paths reached the hash, every install would key an artifact of its own
      // and an artifact built anywhere would be refused everywhere else.
      val copy = createTempDirectory("sysl-lib-copy-")

      try
        copyTree(StdRoot.root.get, copy)

        val moved = Project.collect(copy)

        moved.map(place).sorted shouldBe Std.sources.map(place)
        LibraryArtifact.fingerprint(moved) shouldBe Std.fingerprint
      finally discardTree(copy)
    }

    "while an edit to it does, so a changed library cannot take a stale artifact" in {
      // The other half, and the reason editing the library is now something anybody can do: the
      // artifact's path holds this fingerprint, so a changed file *is* a different path. Nothing has
      // to be invalidated and a compiler running against the unedited library is unaffected.
      val first = Std.sources.headOption.getOrElse(fail("the library has no files"))
      val edited = Source(first.name, first.text + "\n", first.dir.getOrElse(Nil)) :: Std.sources.tail

      LibraryArtifact.fingerprint(edited) should not be Std.fingerprint
    }
  }

  "the library as the driver reads it" - {

    "puts every file in the module its own header names" in {
      // The header and the directory both say it, and the driver is what checks they agree — so
      // this is the same question `build-lib lib` would ask, asked without building anything.
      for source <- Std.sources do
        SyslParser.parse(source) match
          case Right(p)  => p.module.map(_.show) shouldBe Some(source.dir.get.mkString("."))
          case Left(err) => fail(s"${source.name} does not parse: $err")
    }

    "and every module those headers name is one the library says it declares" in {
      Std.sources.map(_.dir.get.mkString(".")).toSet shouldBe Library.modules.toSet
    }
  }

  "two files of one module reach each other with nothing imported" in {
    // `Display.display` names `Writer`, which the *other* file declares, and `13 §6` is why that
    // needs no import: a module's members are one set however many files they came from. It is
    // also what a one-file standard module would never have said.
    val declared = Std.parsed(Target.default).map(p => place(p.source) -> p.body).toMap

    declared("sysl/write.sysl").collect { case t: TraitDecl => t.name } shouldBe List("Writer")
    declared("sysl/display.sysl").collect { case t: TraitDecl => t.name } shouldBe List("Display")

    // Which is the claim, rather than "the library imports nothing": a file may well have a sibling
    // module of the library to import, and none of them has itself. The two import forms spell the
    // module differently — a selector list or a wildcard names it outright, and a bare path names it
    // with the imported name still on the end — so which segments are the module is read off the
    // form.
    for p <- Std.parsed(Target.default); i <- p.body.collect { case i: ImportDecl => i } do
      val named = if i.selectors.nonEmpty || i.wildcard then i.path else i.path.init

      named.mkString(".") should not be p.module.map(_.show).get
  }

  /** A directory copied whole, so that the library can be read from somewhere it was never built. */
  private def copyTree(from: String, to: String): Unit =
    for entry <- listFiles(from) do
      val there = s"$to/${Project.basename(entry)}"

      if isDirectory(entry) then
        createDirectory(there)
        copyTree(entry, there)
      else copyFile(entry, there)

  private def discardTree(path: String): Unit = {
    for entry <- listFiles(path) do
      if isDirectory(entry) then discardTree(entry) else Project.discard(entry)

    Project.discard(path)
  }
}
