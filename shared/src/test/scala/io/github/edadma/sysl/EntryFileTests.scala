package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** What a file's top level means, which depends on where the file sits (`13 §7`).
 *
 * A file carrying statements is the one the program starts in, and its top level is a **body**: what
 * it declares is local to that body, so a `val` there is a stack local initialized where it stands
 * and a function there is a nested function (`12 §5a`) capturing the locals above it. A file carrying
 * no statements declares module members, as does one with a `module` header.
 *
 * `static` is how a declaration in the body opts back into the module — the escape hatch for the
 * three things a nested function cannot be and the one thing a local cannot be.
 *
 * The single fact worth holding on to: **which file the program starts in is decided by statements,
 * and what that file's declarations mean is decided by which file it is.** Those two questions used
 * to have one answer, and a top-level `var` being invisible to every function while a top-level `val`
 * was visible to all of them was the seam where that showed.
 */
class EntryFileTests extends AnyFreeSpec with CodegenSupport with RunSupport with ParseSupport {

  "a declaration in the file the program starts in is local to it" - {
    // The whole point, and the thing that could not be written before: a sequence that is a sequence.
    // A `val` bound from what the statements above it did is what a script wants, and what a module
    // member could never be, since a module member is bound before any statement runs.
    "so a 'val' runs its initializer where it is written, not ahead of the statements" in {
      run(
        """noisy() -> int
          |    print("second")
          |    7
          |end noisy
          |
          |print("first")
          |val n: int = noisy()
          |print(str(n))""".stripMargin,
      ) shouldBe "first\nsecond\n7\n"
    }

    // The cost of the helpers being nested functions, and it is worth stating rather than
    // discovering: a group's environment is formed where the first of them is written, so a call
    // above that point has no environment to make (`12 §5a`). Declaration order stops being free in
    // the one file where the declarations are part of what runs.
    "though a call above the helpers is refused, since their environment is formed where they are" in {
      err(
        """print(str(later()))
          |later() -> int = 1""".stripMargin,
      ) should include("declared below this call")
    }

    "and one bound from a variable the statements above it set is ordinary" in {
      run(
        """var total = 0
          |for i in 1..<4 do total += i
          |val frozen = total
          |total = 0
          |print(str(frozen), str(total))""".stripMargin,
      ) shouldBe "6 0\n"
    }

    // A local has no address in the object file, which is the observable half of "it is not a module
    // member". The same program with `static` on it is in `ValTests`, emitting `private constant`.
    "so it is not laid into the object file the way a module member is" in {
      ir("val k: [3]u32 = [7, 8, 9]\nprint(str(k[0]))") should not include "private constant [3 x i32]"
    }

    "while 'static' on the same declaration puts it back" in {
      ir("static val k: [3]u32 = [7, 8, 9]\nprint(str(k[0]))") should
        include("private constant [3 x i32] [i32 7, i32 8, i32 9]")
    }
  }

  /** A function written at the top of the entry file is a nested function, so `12 §5a` applies to it
    * whole — including the half that is the reason anybody wants this: it may read and write the
    * bindings above it, because the environment holds their addresses rather than copies.
    */
  "a function declared there is a nested function" - {
    "so it reads the bindings above it, with nothing passed in" in {
      run(
        """val base = 10
          |
          |plus(n: int) -> int = base + n
          |
          |print(str(plus(5)))""".stripMargin,
      ) shouldBe "15\n"
    }

    "and assigns to them, which is what an environment of addresses buys" in {
      run(
        """var count = 0
          |
          |bump()
          |    count += 1
          |end bump
          |
          |bump()
          |bump()
          |print(str(count))""".stripMargin,
      ) shouldBe "2\n"
    }

    // Names hoist, captures do not (`12 §5a`). Two helpers may call each other whichever order they
    // are written in, which is what makes the group a group.
    "while two of them call each other, whichever order they are written in" in {
      run(
        """even(n: int) -> bool = if n == 0 then true else odd(n - 1)
          |odd(n: int) -> bool = if n == 0 then false else even(n - 1)
          |
          |print(str(even(4)), str(odd(4)))""".stripMargin,
      ) shouldBe "true false\n"
    }

    "but a binding written below the group is not one it can capture" in {
      err(
        """show() -> int = later
          |val later = 1
          |print(str(show()))""".stripMargin,
      ) should include("declared below")
    }
  }

  "'static' is what a declaration writes to belong to the module instead" - {
    // The three things a nested function cannot be. Each is a real program someone writes, and each
    // is why the modifier exists rather than being a tidiness rule.
    "a generic helper, which a nested function may not be" in {
      run(
        """static first[A, B](a: A, b: B) -> A = a
          |print(str(first(1, "x")))""".stripMargin,
      ) shouldBe "1\n"
    }

    "a function whose address is taken, which a nested one has none of" in {
      run(
        """static twice(n: int) -> int = n * 2
          |val f: *extern(int) -> int = &twice
          |print(str(f(21)))""".stripMargin,
      ) shouldBe "42\n"
    }

    "and a name another file reads, which a local is not" in {
      runIn(
        ("", "main.sysl", "static val shared: int = 4\nprint(str(doubled()))"),
        ("", "other.sysl", "doubled() -> int = shared * 2"),
      ) shouldBe "8\n"
    }

    // The mirror of the first test in this file: a module member is bound before any statement runs,
    // so its initializer cannot call something that is part of the body.
    "and its initializer is bound before the body, so it may not call a local helper" in {
      err(
        """static val n: int = helper()
          |helper() -> int = 1
          |print(str(n))""".stripMargin,
      ) should include("undefined function 'helper'")
    }
  }

  "'static' says nothing anywhere else, and is refused rather than ignored" - {
    "in a file that carries no statements, where everything is the module's already" in {
      errIn(
        ("", "main.sysl", "print(str(k))"),
        ("", "table.sysl", "static val k: int = 1"),
      ) should include("this file has no such body")
    }

    "in a file with a module header, for the same reason" in {
      errIn(
        ("", "main.sysl", "print(str(tables.k))"),
        ("tables", "t.sysl", "module tables\nstatic val k: int = 1"),
      ) should include("this file has no such body")
    }

    "inside a block, where there is no module member for a declaration to be instead" in {
      err(
        """f() -> int
          |    static val n: int = 1
          |    n
          |end f
          |print(str(f()))""".stripMargin,
      ) should include("local to that block")
    }

    "and on a declaration that is a module member wherever it is written" in {
      err("static const n: int = 1\nprint(str(n))") should
        include("'static' marks a 'val', a 'var' or a function")
    }
  }

  "one file carries the statements the program runs" - {
    "so a second that carries any is told which one already does" in {
      errIn(
        ("", "main.sysl", "print(1)"),
        ("", "other.sysl", "print(2)"),
      ) should include("already carries the statements this program runs")
    }

    // A file of declarations is not a file of statements however much it declares, which is what
    // keeps a program's tables and helpers from making their file the one it starts in.
    "while a file of nothing but declarations is not one of them" in {
      runIn(
        ("", "main.sysl", "print(str(size()))"),
        ("", "table.sysl", "val k: [2]int = [1, 2]\nsize() -> usize = k.len\nconst width: int = 2"),
      ) shouldBe "2\n"
    }

    "and a program with no statements at all runs and does nothing" in {
      run("f() -> int = 1") shouldBe ""
    }
  }
}
