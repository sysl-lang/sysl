package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** A module is a directory, so its files are one scope (`13 §1`, `13 §6`).
 *
 * These are that claim from three sides: the header a file writes to say which module it
 * contributes to, the fact that splitting a module across files changes nothing about what its
 * declarations can see, and the two things a set of files can disagree about — which module they
 * are, and which of them the program starts in.
 */
class ModuleTests extends AnyFreeSpec with ParseSupport with CodegenSupport with RunSupport {

  "the module header" - {
    "names the module a file contributes to" in {
      moduleOf("module oskit.arch\nprint(1)") shouldBe Some("oskit.arch")
    }

    "may be a single segment" in {
      moduleOf("module std\nprint(1)") shouldBe Some("std")
    }

    "is absent from a file that writes none" in {
      moduleOf("print(1)") shouldBe None
    }

    "does not become a statement" in {
      prog("module a.b\nprint(1)") shouldBe List(printStmt(i(1)))
    }

    "comes before everything, so a second one is not a statement" in {
      progError("module a\nprint(1)\nmodule b") should include("module")
    }

    "may be preceded by blank lines" in {
      moduleOf("\n\nmodule a.b\nprint(1)") shouldBe Some("a.b")
    }

    // `module` is a reserved word, which is the cost of the header having no other marker.
    "spends the word, so nothing else may be called it" in {
      progError("var module = 1") should include("identifier")
    }
  }

  "one module, however many files" - {
    "a call reaches a function declared in another file" in {
      runOf(
        "main.sysl" -> "print(double(21))",
        "math.sysl" -> "double(n: int) -> int = n * 2",
      ) shouldBe "42\n"
    }

    // Hoisting registers every signature in the module before it checks any body, so a file may
    // call something declared in a file that comes later — there is no ordering to get wrong.
    "and the order the files are handed over does not matter" in {
      runOf(
        "math.sysl" -> "double(n: int) -> int = n * 2",
        "main.sysl" -> "print(double(21))",
      ) shouldBe "42\n"
    }

    "two files may call each other" in {
      val src = runOf(
        "main.sysl" -> "print(even(10))",
        "a.sysl"    -> "even(n: int) -> bool =\n    if n == 0 then true else odd(n - 1)",
        "b.sysl"    -> "odd(n: int) -> bool =\n    if n == 0 then false else even(n - 1)",
      )

      src shouldBe "true\n"
    }

    "a type declared in one file is used in another" in {
      runOf(
        "main.sysl"  -> "var p = Point(3, 4)\nprint(p.x + p.y)",
        "point.sysl" -> "struct Point\n    x: int\n    y: int",
      ) shouldBe "7\n"
    }

    "a trait, its implementation, and its use may each be in their own file" in {
      runOf(
        "main.sysl"  -> "var s: &Show = Point(3, 4)\nprint(s.show())",
        "trait.sysl" -> "trait Show\n    show(self) -> int",
        "point.sysl" -> "struct Point\n    x: int\n    y: int",
        "impl.sysl"  -> "impl Show for Point\n    show(self) -> int = self.x + self.y",
      ) shouldBe "7\n"
    }

    "a generic declared in one file is instantiated from another" in {
      runOf(
        "main.sysl" -> "print(first(Pair(7, 9)))",
        "pair.sysl" -> "struct Pair[T]\n    a: T\n    b: T\nfirst[T](p: Pair[T]) -> T = p.a",
      ) shouldBe "7\n"
    }

    "every file writes the same header" in {
      runOf(
        "main.sysl" -> "module oskit.arch\nprint(halt())",
        "cpu.sysl"  -> "module oskit.arch\nhalt() -> int = 3",
      ) shouldBe "3\n"
    }

    "and one file is the whole module where that is all there is" in {
      runOf("only.sysl" -> "print(1)") shouldBe "1\n"
    }

    "a file may be a header and nothing else" in {
      runOf(
        "main.sysl" -> "module m\nprint(halt())",
        "spare.sysl" -> "module m\n",
        "cpu.sysl"  -> "module m\nhalt() -> int = 3",
      ) shouldBe "3\n"
    }

    // A module none of whose files carries a statement still has an entry point, which does
    // nothing and succeeds. The declarations are checked and emitted either way.
    "a module that runs nothing still compiles" in {
      val out = irOf("a.sysl" -> "f() -> int = 1")

      out should include("define i32 @f()")
      out should include("define i32 @main()")
    }

    "and a module of no files at all is the empty program" in {
      Compiler.compile(Nil) match {
        case Right(out) => out should include("define i32 @main()")
        case Left(e)    => fail(e)
      }
    }
  }

  "what a set of files may not disagree about" - {
    "which module they are" in {
      errOf(
        "a.sysl" -> "module oskit.arch\nf() -> int = 1",
        "b.sysl" -> "module oskit.vm\ng() -> int = 2",
      ) should include("b.sysl declares 'oskit.vm', but a.sysl declares 'oskit.arch'")
    }

    "including a file that writes no header at all" in {
      errOf(
        "a.sysl" -> "module oskit.arch\nf() -> int = 1",
        "b.sysl" -> "g() -> int = 2",
      ) should include("b.sysl declares no module at all, but a.sysl declares 'oskit.arch'")
    }

    "reported the other way round just as plainly" in {
      errOf(
        "a.sysl" -> "f() -> int = 1",
        "b.sysl" -> "module oskit.arch\ng() -> int = 2",
      ) should include("b.sysl declares 'oskit.arch', but a.sysl declares no module at all")
    }

    // The first file's header is the module's, so a directory where one file was missed reports
    // once per stray file rather than once per pair of them.
    "with one report per stray file" in {
      val out = errOf(
        "a.sysl" -> "module m\nf() -> int = 1",
        "b.sysl" -> "module n\ng() -> int = 2",
        "c.sysl" -> "module o\nh() -> int = 3",
      )

      out should include("b.sysl declares 'n'")
      out should include("c.sysl declares 'o'")
    }

    "which of them the program runs" in {
      errOf(
        "a.sysl" -> "print(1)",
        "b.sysl" -> "print(2)",
      ) should include("a.sysl already carries the statements this module runs, so b.sysl may hold declarations only")
    }

    // A top-level `var` is a statement — it is a local of the entry point, not a member of the
    // module — so a second file holding one is the same mistake as a second file holding a `print`.
    "counting a top-level binding as one of them" in {
      errOf(
        "a.sysl" -> "print(1)",
        "b.sysl" -> "var x = 2",
      ) should include("may hold declarations only")
    }

    // Requiring the header rather than inferring it from the path is what keeps a file
    // self-describing (`13 §1`), and a file with nothing in it has not written one — so it is in
    // the root module, and among files that are not it says so. There is nothing to point at, so
    // the diagnostic names the file and carries no caret.
    "and a file with nothing in it has not said which module it is" in {
      errOf(
        "a.sysl"     -> "module m\nf() -> int = 1",
        "empty.sysl" -> "",
      ) should include("empty.sysl declares no module at all")
    }

    "which is no complaint at all when nothing else claimed one" in {
      runOf(
        "a.sysl"     -> "print(1)",
        "empty.sysl" -> "",
      ) shouldBe "1\n"
    }

    "though a file of nothing but declarations is never the one that runs" in {
      runOf(
        "a.sysl" -> "f() -> int = 1",
        "b.sysl" -> "g() -> int = 2",
        "c.sysl" -> "print(f() + g())",
      ) shouldBe "3\n"
    }
  }

  "what one scope means for a mistake" - {
    "a name declared twice across two files is declared twice" in {
      errOf(
        "a.sysl" -> "f() -> int = 1",
        "b.sysl" -> "f() -> int = 2",
      ) should include("already declared")
    }

    "a type declared twice across two files is too" in {
      errOf(
        "a.sysl" -> "struct P\n    x: int",
        "b.sysl" -> "struct P\n    y: int",
      ) should include("already declared")
    }

    "and one implementation per type holds across files" in {
      errOf(
        "t.sysl" -> "trait Show\n    show(self) -> int",
        "p.sysl" -> "struct P\n    x: int",
        "a.sysl" -> "impl Show for P\n    show(self) -> int = 1",
        "b.sysl" -> "impl Show for P\n    show(self) -> int = 2",
      ) should include("Show")
    }

    // Diagnostics sort by file and then by line, so reading them top to bottom is reading the
    // module top to bottom rather than following the order the driver happened to collect it in.
    "an error in each file is reported against that file" in {
      val out = errOf(
        "b.sysl" -> "g() -> int = nope",
        "a.sysl" -> "f() -> int = missing",
      )

      out should include("a.sysl")
      out should include("b.sysl")
      out.indexOf("a.sysl") should be < out.indexOf("b.sysl")
    }

    "a syntax error in one file does not hide one in another" in {
      val out = errOf(
        "a.sysl" -> "f() -> int = (",
        "b.sysl" -> "g() -> int = )",
      )

      out should include("a.sysl")
      out should include("b.sysl")
    }
  }

  "the emitted module is one module" - {
    "whatever it was written across" in {
      val out = irOf(
        "main.sysl" -> "print(double(21))",
        "math.sysl" -> "double(n: int) -> int = n * 2",
      )

      out should include("define i32 @double(i32 %n.param)")
      out should include("define i32 @main()")
    }

    "and holds exactly what the single-file spelling holds" in {
      val split = irOf(
        "main.sysl" -> "print(double(21))",
        "math.sysl" -> "double(n: int) -> int = n * 2",
      )

      split shouldBe ir("double(n: int) -> int = n * 2\nprint(double(21))")
    }
  }
}
