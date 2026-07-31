package io.github.edadma.sysl

import org.scalatest.Assertions
import org.scalatest.matchers.should.Matchers

/** Shared helpers for the Tier-1 codegen suites: assert on the emitted LLVM IR *text* without
 * running it. The IR is just a string, so these are pure and cross-platform.
 */
trait CodegenSupport extends Matchers { this: Assertions =>

  /** How a diagnostic spells a library declaration — the key it resolves to, rendered.
   *
   * A diagnostic names a moved declaration by the path that reaches it, so a bound on the core
   * catalog reads `T: sysl.Add`. Reading the spelling off `Library` rather than writing it out is
   * what keeps these assertions honest across a move: the library is being drained into the
   * standard module a surface at a time, and an expectation with the name baked in either fails for
   * a reason that has nothing to do with what it is testing, or — if it is a negative — quietly
   * stops testing anything at all.
   *
   * **This is the rendered form, and an emitted symbol is not.** A key separates with `$` and this
   * shows it with `.`, so an assertion about IR wants `Library.key` directly — `@sysl$args_of`, not
   * `@sysl.args_of`. Reaching for this one there fails in a way that looks like the move went wrong.
   */
  protected def lib(name: String): String = Modules.show(Library.key(name))

  /** A library key for use inside `include regex`, with the module separator escaped.
   *
   * `Modules.sep` is `$`, which a regex reads as an end-of-input anchor rather than as a character —
   * so `sysl$printi` unescaped matches nothing at all, and the assertion fails as though the symbol
   * were missing. Every `include regex` naming a library symbol goes through this.
   */
  protected def keyRe(name: String): String = Library.key(name).replace("$", "\\$")

  /** The IR for a program that must compile. */
  protected def ir(src: String): String =
    Compiler.compileToLlvm(src) match {
      case Right(out) => out
      case Left(e)    => fail(e)
    }

  /** The IR for a program built for a machine other than this one (`targets.md`). Reading the text
   * is the whole of what a cross-target test can do — there is nothing here to run the result on —
   * and it is enough, because what a target decides is what instructions come out.
   */
  protected def irFor(target: Target, src: String): String =
    Compiler.compileToLlvm(src, "<input>", target) match {
      case Right(out) => out
      case Left(e)    => fail(e)
    }

  /** The error message for a program that must be rejected. */
  protected def err(src: String): String =
    Compiler.compileToLlvm(src) match {
      case Right(out) => fail(s"expected an error, got:\n$out")
      case Left(e)    => e
    }

  /** Several files, named so a diagnostic about one can be told from a diagnostic about another.
   * `"a.sysl" -> "…"` reads at a call site the way a small directory looks.
   *
   * They carry no location, which is what a file handed to the compiler with no project around it
   * has: the module each contributes to is whatever its header says, and there is nothing for that
   * header to be held against.
   */
  protected def files(fs: (String, String)*): List[Source] =
    fs.toList.map { case (name, text) => Source(name, text) }

  /** The same files as a **project**: each one paired with the directory it sits in, written as the
   * dotted path a driver walking the tree would have derived. `""` is the project root.
   */
  protected def project(fs: (String, String, String)*): List[Source] =
    fs.toList.map { case (dir, name, text) =>
      Source(name, text, if dir.isEmpty then Nil else dir.split('.').toList)
    }

  /** The IR for a program of several files, all of which must compile. */
  protected def irOf(fs: (String, String)*): String = compiled(files(fs*))

  /** The error message for a program of several files that must be rejected. */
  protected def errOf(fs: (String, String)*): String = rejected(files(fs*))

  /** The IR for a project laid out across directories. */
  protected def irIn(fs: (String, String, String)*): String = compiled(project(fs*))

  /** The error message for a project laid out across directories that must be rejected. */
  protected def errIn(fs: (String, String, String)*): String = rejected(project(fs*))

  /** A **stand-in standard module**, written by the test rather than read from `lib/sysl`.
   *
   * Some questions are about the library's *position* rather than about what it happens to declare —
   * that a program reaches its members without importing them, that it is searched after a file's
   * imports, that what it keeps to itself is out of reach. Asking those of the real library means
   * the answer changes whenever the library does, and a question it has no case for cannot be asked
   * at all: nothing in `lib/sysl` is private, so nothing there can show what a private member of the
   * library does.
   *
   * The trees go in as the `Core` and **not** as units of the compilation, which is the same way the
   * embedded library arrives: a `Core` contributes its own declarations to the walk, and a file that
   * were also a unit would be a program declaring `module sysl` — which is refused, and rightly.
   *
   * A program compiled this way has **only** what the stand-in declares, `print` included, so these
   * fixtures state everything they use.
   */
  protected def standIn(fs: (String, String)*): (List[Program], Core) =
    standInTree(fs.map { case (name, text) => (Std.module, name, text) }*)

  /** The same, for a library that is a **tree**: each file paired with the module it sits in,
   * written as the dotted path a driver walking `lib` would have derived (`Project.walk`).
   *
   * A library with more than one module is not something the real one can stand for while it has
   * only the standard module, and it is the case every question about a submodule is about — that
   * its names do not arrive unasked-for, that a program may reach it by naming it, that what it
   * keeps to itself it keeps from the rest of the library too.
   */
  protected def standInTree(fs: (String, String, String)*): (List[Program], Core) = {
    val units = fs.toList.map { case (module, name, text) =>
      SyslParser.parse(Source(name, text, module.split('.').toList)) match {
        case Right(p) => p
        case Left(e)  => fail(s"the stand-in standard module does not parse: $e")
      }
    }

    (units, new Core(units))
  }

  /** The IR for a program compiled against a stand-in standard module, which must compile. */
  protected def irAgainst(lib: (String, String)*)(fs: (String, String)*): String =
    resultOf(standIn(lib*)._2, fs) match {
      case Right(out) => out
      case Left(e)    => fail(e)
    }

  /** The error for a program compiled against a stand-in standard module, which must be rejected. */
  protected def errAgainst(lib: (String, String)*)(fs: (String, String)*): String =
    resultOf(standIn(lib*)._2, fs) match {
      case Right(out) => fail(s"expected an error, got:\n$out")
      case Left(e)    => e
    }

  /** The IR for a program compiled against a stand-in library of several modules. */
  protected def irAgainstTree(lib: (String, String, String)*)(fs: (String, String)*): String =
    resultOf(standInTree(lib*)._2, fs) match {
      case Right(out) => out
      case Left(e)    => fail(e)
    }

  /** The error for a program compiled against a stand-in library of several modules. */
  protected def errAgainstTree(lib: (String, String, String)*)(fs: (String, String)*): String =
    resultOf(standInTree(lib*)._2, fs) match {
      case Right(out) => fail(s"expected an error, got:\n$out")
      case Left(e)    => e
    }

  private def resultOf(core: Core, fs: Seq[(String, String)]): Either[String, String] =
    Compiler.compiledWith(files(fs*), Nil, Target.default, Set.empty, core).map(_._1)

  private def compiled(sources: List[Source]): String =
    Compiler.compile(sources) match {
      case Right(out) => out
      case Left(e)    => fail(e)
    }

  private def rejected(sources: List[Source]): String =
    Compiler.compile(sources) match {
      case Right(out) => fail(s"expected an error, got:\n$out")
      case Left(e)    => e
    }

  /** One emitted function, for a test that counts instructions rather than looking for one.
   *
   * A whole-module count is not what such a test means: the module also holds the ARC runtime and
   * whatever library functions the program reached, and either can grow without the lowering under
   * test having changed.
   */
  protected def defineOf(out: String, name: String): String =
    val header = (l: String) => l.startsWith("define") && l.contains(s"@$name(")
    val body   = out.linesIterator.dropWhile(!header(_)).takeWhile(_ != "}")

    body.mkString("\n")

  /** Just `main`, which is where a top-level statement lands. */
  protected def mainOf(out: String): String = defineOf(out, "main")

  /** The IR of `main` alone. */
  protected def irMain(src: String): String = mainOf(ir(src))
}
