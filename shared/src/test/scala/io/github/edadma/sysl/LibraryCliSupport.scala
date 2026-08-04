package io.github.edadma.sysl

import io.github.edadma.cross_platform.*

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** The fixtures the two `--lib` suites are both written against: a library tree on disk, an artifact
 * built out of one, and the ways of watching a driver run — its status, its diagnostics, and the
 * module it emitted.
 *
 * They sit in a trait rather than in either suite because the two halves of this surface ask the
 * same things of the same artifacts: one is about **producing** an artifact (`LibraryBuildCliTests`)
 * and the other about **which one a compilation is given** (`LibraryCliTests`), and neither can be
 * written without being able to build one and read what came out.
 */
trait LibraryCliSupport extends AnyFreeSpec with Matchers {

  /** The driver, under a name of its own: `Suite` has an `execute` too, and it is the one that wins
   * unqualified.
   *
   * **A compilation that finds no standard module is an error**, and these tests run in a tree where
   * none has been built — so one that says nothing about the standard module gets `--no-std-lib`, which is what
   * it means: *this test is about `--lib`, or about the driver, and not about which standard module a
   * compilation gets.* Spelled once here rather than at twenty call sites.
   *
   * A test that mentions the standard module **in any way** — names one, refuses one, redirects the search path,
   * or builds one — opts out of this entirely and is run exactly as it was written. Otherwise this
   * default would quietly rewrite the premise of the very tests that exist to pin it.
   */
  protected def cli(cfg: Config): Int = Console.withOut(Discarded)(driver(cfg))

  /** The same run with stdout left where it was, for the three helpers below that capture it. Every
   * other call goes through `cli`, which throws it away — see `Discarded`.
   */
  private def driver(cfg: Config): Int =
    io.github.edadma.sysl.execute(if mentionsCore(cfg) then cfg else cfg.copy(noStdLib = true))

  protected def mentionsCore(cfg: Config): Boolean =
    cfg.std || cfg.noStdLib || cfg.stdLib.isDefined || cfg.stdSearch != LibraryArtifact.stdDefault

  protected val library =
    """module demo
      |
      |double(n: int) -> int = n * 2
      |
      |larger[T: Ord](a: T, b: T) -> T = if a < b then b else a
      |""".stripMargin

  /** A second library, so that "more than one `--lib`" has something to be more than one of. Its
   * module is distinct from `demo`'s, which is what makes a program calling both able to say which
   * it meant.
   */
  protected val other =
    """module extra
      |
      |triple(n: int) -> int = n * 3
      |""".stripMargin

  /** A library tree on disk, as the driver reads one: a root, with the module in a directory under
   * it whose name is the module's.
   */
  protected def libraryRoot(): String = rootOf("demo", library)

  protected def rootOf(module: String, text: String, file: String = "lib.sysl"): String = {
    val root = createTempDirectory("sysl-cli-lib-")
    val dir  = s"$root/$module"

    createDirectory(dir)
    writeFile(s"$dir/$file", text)
    root
  }

  protected def program(text: String): String = {
    val path = createTempFile("sysl-cli-prog-", ".sysl")
    writeFile(path, text)
    path
  }

  /** A built artifact, and the paths that made it. */
  protected def artifact(): String = artifactOf(libraryRoot())

  protected def artifactOf(root: String): String = {
    val out = createTempFile("sysl-cli-", LibraryArtifact.extension)

    succeeds(Config(command = "build-lib", file = root, output = Some(out)))
    out
  }

  /** The standard module built into an artifact, which is what `--std-lib` consumes. Built once —
   * it is the slowest thing in this file, and every test below wants the same one.
   */
  protected lazy val std: String = {
    val out = createTempFile("sysl-cli-std-", LibraryArtifact.extension)

    succeeds(Config(command = "build-lib", file = StdRoot.root.get, output = Some(out), std = true))
    out
  }

  /** A driver run with its diagnostics captured, since for `--std-lib` the warning *is* the
   * observation: falling back and linking both exit 0, and only stderr tells them apart.
   */
  protected def diagnostics(cfg: Config): (Int, String) = {
    val captured = new java.io.ByteArrayOutputStream

    val status = Console.withErr(captured)(cli(cfg))

    (status, captured.toString)
  }

  /** A run asserted to have succeeded, and one asserted to have been refused — both with the
   * driver's diagnostics caught rather than let out onto the console.
   *
   * `build-lib` announces the artifact it wrote, and a refusal prints the reason it was refused; a
   * suite made largely of those two says a great deal on stderr and none of it about the tests that
   * ran. Nothing is lost by catching them, because it is the *failing* assertion that wants them:
   * what was captured becomes the failure message, which is more than a bare status ever said.
   */
  protected def succeeds(cfg: Config): Unit = {
    val (status, notes) = diagnostics(cfg)

    withClue(notes)(status shouldBe 0)
  }

  protected def refused(cfg: Config): Unit = {
    val (status, notes) = diagnostics(cfg)

    withClue(notes)(status should not be 0)
  }

  /** A driver run with the program's own output captured — `run` prints what the child wrote, so
   * this is what lets a test assert the answer a library computed rather than only that the link
   * held.
   *
   * Both streams are captured and **both are quoted when the run fails**, because the two ways it
   * fails are invisible in different places: a compilation that was refused says so on stderr and
   * prints nothing, while a program that ran and panicked says so on stdout and exits non-zero. A
   * bare status assertion would report neither, and the failure a library test is most likely to
   * produce is exactly one of those two.
   */
  protected def ran(cfg: Config): String = {
    val out    = new java.io.ByteArrayOutputStream
    val notes  = new java.io.ByteArrayOutputStream
    val status = Console.withOut(out)(Console.withErr(notes)(driver(cfg)))

    if status != 0 then fail(s"the driver exited with $status:\n${out.toString}${notes.toString}")

    out.toString
  }

  /** A library tree on disk carrying C source beside its sysl (`15 §7`), which is what a binding to
   * a real library is: the module in a directory under the root, and the shims beside it.
   */
  protected def rootWithC(module: String, sysl: String, cFiles: (String, String)*): String = {
    val root = createTempDirectory("sysl-cli-clib-")
    val dir  = s"$root/$module"

    createDirectory(dir)
    writeFile(s"$dir/lib.sysl", sysl)
    cFiles.foreach((name, text) => writeFile(s"$dir/$name", text))
    root
  }

  /** A driver run with its emitted module captured. `emit-llvm` prints, so this is what lets a test
   * see *which* standard module a compilation was given rather than only that it succeeded: the
   * library arriving prebuilt is a declaration where the built-in copy is a definition.
   */
  protected def emitted(cfg: Config): String = {
    val captured = new java.io.ByteArrayOutputStream
    val notes    = new java.io.ByteArrayOutputStream
    val status   = Console.withOut(captured)(Console.withErr(notes)(driver(cfg)))

    withClue(notes.toString)(status shouldBe 0)
    captured.toString
  }

  /** The symbols an emitted module `define`s or `declare`s. Which of the two a library's symbol
   * appears under is how a test tells a library that was **linked** from one that was compiled in,
   * and it is the only difference visible from outside.
   */
  protected def symbols(ir: String, form: String): Set[String] =
    ir.linesIterator.filter(_.startsWith(s"$form ")).flatMap { line =>
      val at = line.indexOf('@')

      Option.when(at >= 0)(line.drop(at + 1).takeWhile(c => c != '(' && c != ' '))
    }.toSet

  /** The same, narrowed to the standard module's own. */
  protected def libraryOwn(ir: String, form: String): Set[String] =
    symbols(ir, form).filter(_.startsWith(Library.key("")))

  /** One function's emitted body, so that two compilations can be compared on what a function does
   * rather than only on which functions there are.
   */
  protected def bodyOf(ir: String, name: String): List[String] =
    ir.linesIterator
      .dropWhile(l => !(l.startsWith("define ") && l.contains(s"@$name(")))
      .takeWhile(_ != "}")
      .toList

  /** An artifact carrying given metadata and nothing the linker would want. Assembled rather than
   * built, because every one of these is a container a toolchain would refuse to produce.
   */
  protected def artifactOfMeta(meta: String): Array[Byte] =
    FakeAr(LibraryArtifact.metadataMember -> LibraryArtifact.frame(meta))

  /** An artifact whose frame promises more than the member holds. The version comes from the constant
   * rather than being written out, so that a bump to the container format leaves these testing
   * truncation rather than the version check that would otherwise fire ahead of it.
   */
  protected def truncated: Array[Byte] =
    FakeAr(LibraryArtifact.metadataMember ->
      LibraryArtifact.framed(s"syslib ${LibraryArtifact.Version} 900", "short"))

  /** The real std's metadata wearing somebody else's fingerprint — a readable, decodable artifact
   * that is simply not the standard module this compiler carries.
   */
  protected def stale: String = {
    val meta = LibraryArtifact.metadataOf(std, readBytes(std)) match
      case Right(m)  => m
      case Left(err) => fail(err)

    "0000000000000000" + meta.drop(meta.indexOf('\n'))
  }

  protected def corrupt(bytes: Array[Byte]): String = {
    val path = createTempFile("sysl-cli-bad-", LibraryArtifact.extension)
    writeBytes(path, bytes)
    path
  }
}
