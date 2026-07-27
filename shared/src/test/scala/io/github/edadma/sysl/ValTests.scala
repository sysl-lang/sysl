package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** `val` — a binding written once (`07`, `13 §7`).
 *
 * One keyword read at two levels. At the top of a file it is a module member: read-only storage laid
 * into the object file, which is what a table of round constants needs and what a `const` can never
 * be, since a constant is folded into its uses and has no address to index. Inside a block it is the
 * immutable counterpart of `var`.
 *
 * The two properties worth pinning are that it has an **address** — so it can be indexed, iterated,
 * and reached into — and that the address is not a writable one, at any depth and through `&` as
 * well as through assignment.
 */
class ValTests extends AnyFreeSpec with CodegenSupport with RunSupport with ParseSupport {

  "the form parses" - {
    "with a type and a value" in {
      prog("val n: int = 3") shouldBe List(ValDecl("n", Some(NamedType("int", Nil)), i(3)))
    }

    "with the type left off" in {
      prog("val n = 3") shouldBe List(ValDecl("n", None, i(3)))
    }

    "and takes a visibility modifier, as every other declaration does" in {
      prog("private val n: int = 3") shouldBe
        List(ValDecl("n", Some(NamedType("int", Nil)), i(3), Visibility.File))
    }

    // The value is what tells a `val` from a `var` to the parser as well as to a reader: a binding
    // written once with nothing to hold is not a declaration of anything.
    "but not without a value" in {
      progError("val n: int") should not be empty
    }
  }

  "a module-level 'val'" - {
    "is read by name" in {
      run("val n: int = 7\nprint(str(n))") shouldBe "7\n"
    }

    "holds a table, which is the thing a 'const' cannot be" in {
      run("val k: [4]u32 = [11, 22, 33, 44]\nprint(str(k[2]))") shouldBe "33\n"
    }

    "is indexed at a value only known while running" in {
      run(
        """val k: [4]int = [11, 22, 33, 44]
          |var total = 0
          |for i in 0..<4 do total += k[i]
          |print(str(total))""".stripMargin,
      ) shouldBe "110\n"
    }

    "is iterated" in {
      run(
        """val k: [3]int = [2, 3, 4]
          |var p = 1
          |for x in k do p *= x
          |print(str(p))""".stripMargin,
      ) shouldBe "24\n"
    }

    "reports its length" in {
      run("val k: [5]u8 = [1; 5]\nprint(str(k.len))") shouldBe "5\n"
    }

    "may be built with a repeat" in {
      run("val k: [8]int = [3; 8]\nprint(str(k[7]))\nprint(str(k.len))") shouldBe "3\n8\n"
    }

    "takes its element type from its declaration, so the elements need no suffix" in {
      run("val k: [2]u64 = [0xcbf29ce484222325, 2]\nprint(str(k[0]))") shouldBe "14695981039346656037\n"
    }

    "is reached from a function, wherever the function is written" in {
      run(
        """first() -> int = k[0]
          |val k: [2]int = [9, 8]
          |print(str(first()))""".stripMargin,
      ) shouldBe "9\n"
    }

    // Order-freedom is what makes it a declaration rather than a statement: nothing runs to
    // initialize it, so nothing has to run first.
    "may be written in terms of a 'const' declared below it" in {
      run("val k: [n]int = [4; n]\nconst n: usize = 3\nprint(str(k.len))\nprint(str(k[2]))") shouldBe "3\n4\n"
    }

    "is reached from another module, fully qualified" in {
      runIn(
        ("tables", "t.sysl", "module tables\nval k: [2]int = [5, 6]"),
        ("", "main.sysl", "print(str(tables.k[1]))"),
      ) shouldBe "6\n"
    }

    "and privately, only from its own file" in {
      errIn(
        ("tables", "t.sysl", "module tables\nprivate val k: [2]int = [5, 6]"),
        ("", "main.sysl", "print(str(tables.k[1]))"),
      ) should include("private")
    }

    "nests" in {
      run("val g: [2][3]int = [[1, 2, 3], [4, 5, 6]]\nprint(str(g[1][0]))") shouldBe "4\n"
    }

    "holds floats" in {
      run("val w: [2]f64 = [0.5, 0.25]\nprint(str(w[0] + w[1]))") shouldBe "0.75\n"
    }

    "holds narrow floats" in {
      run("val w: [2]f32 = [0.5, 0.25]\nprint(str(w[0] + w[1]))") shouldBe "0.75\n"
    }

    "holds bools" in {
      run("val b: [3]bool = [true, false, true]\nprint(str(b[0] && b[2]))") shouldBe "true\n"
    }

    // An array is a value, so binding one to a `var` copies it — which is how a program that wants
    // to work from a table gets writable storage without the table being writable.
    "is copied, not aliased, when it is bound to a 'var'" in {
      run(
        """val k: [3]int = [1, 2, 3]
          |var c = k
          |c[0] = 99
          |print(str(c[0]))
          |print(str(k[0]))""".stripMargin,
      ) shouldBe "99\n1\n"
    }

    "is passed to a function by value" in {
      run(
        """sum(a: [3]int) -> int = a[0] + a[1] + a[2]
          |val k: [3]int = [1, 2, 3]
          |print(str(sum(k)))""".stripMargin,
      ) shouldBe "6\n"
    }
  }

  // The point of the whole exercise: storage that costs nothing to reach and nothing to set up.
  "it is laid into the object file" - {
    "as a constant global, with no code to initialize it" in {
      val out = ir("val k: [3]u32 = [7, 8, 9]\nprint(str(k[0]))")

      out should include("private constant [3 x i32] [i32 7, i32 8, i32 9]")
    }

    "with a repeat written out, since a global has no loop to run" in {
      ir("val k: [4]u8 = [2; 4]\nprint(str(k[0]))") should
        include("private constant [4 x i8] [i8 2, i8 2, i8 2, i8 2]")
    }

    // A narrow float has to be rounded where the constant is written, since there is no `fptrunc`
    // to run before the program starts — and LLVM refuses a hex constant a `float` cannot hold, so
    // the f64 bits of `0.1` (0x3FB999999999999A) would not merely be imprecise, it would not build.
    "with a narrow float rounded to its own width, not to a double's" in {
      ir("val w: [1]f32 = [0.1]\nprint(str(w[0]))") should
        include("private constant [1 x float] [float 0x3FB99999A0000000]")
    }

    "and reaching an element is a 'getelementptr' from the global itself" in {
      irMain("val k: [4]int = [1, 2, 3, 4]\nprint(str(k[1]))") should include("ptr @k")
    }
  }

  "a local 'val'" - {
    "binds like a 'var'" in {
      run("twice() -> int\n    val n = 4\n    n * 2\nend twice\nprint(str(twice()))") shouldBe "8\n"
    }

    "infers its type from its value" in {
      run("narrow() -> u8\n    val b = 3u8\n    b\nend narrow\nprint(str(narrow()))") shouldBe "3\n"
    }

    "holds an array, which may then be indexed" in {
      run(
        """f() -> int
          |    val xs = [4, 5, 6]
          |    xs[0] + xs[2]
          |end f
          |print(str(f()))""".stripMargin,
      ) shouldBe "10\n"
    }

    // Inside a block it is a local of that block, so the top-level one it shadows is untouched.
    "shadows a module-level one for the rest of its block" in {
      run(
        """val n: int = 1
          |f() -> int
          |    val n = 50
          |    n
          |end f
          |print(str(f() + n))""".stripMargin,
      ) shouldBe "51\n"
    }
  }

  "what it refuses" - {
    "assigning to a module-level 'val'" in {
      err("val n: int = 1\nn = 2") should include("written once")
    }

    "assigning to one of its elements" in {
      err("val k: [2]int = [1, 2]\nk[0] = 9") should include("written once")
    }

    "compound assignment, which is an assignment" in {
      err("val k: [2]int = [1, 2]\nk[1] += 1") should include("written once")
    }

    "an increment, for the same reason" in {
      err("val k: [2]int = [1, 2]\nk[0]++") should include("written once")
    }

    // A `*T` is a licence to write, so handing one out would move the mistake one step from where
    // it could still be reported.
    "taking its address" in {
      err("val k: [2]int = [1, 2]\nvar p = &k[0]") should include("written once")
    }

    "assigning to a local 'val'" in {
      err("f() -> int\n    val n = 1\n    n = 2\n    n\nend f\nprint(str(f()))") should include("written once")
    }

    "assigning through a field of one" in {
      err(
        """struct P
          |    x: int
          |end P
          |f() -> int
          |    val p = P(1)
          |    p.x = 2
          |    p.x
          |end f
          |print(str(f()))""".stripMargin,
      ) should include("written once")
    }

    // The decision recorded rather than a limit stumbled into: a `[]T` permits writes and records
    // nothing about whose elements it views, so a view of a `val` would be a way of writing one.
    "slicing one, until a slice can say its elements are read-only" in {
      err("val k: [4]int = [1, 2, 3, 4]\nvar s = k[1..<3]") should include("cannot be sliced")
    }

    "a module-level 'val' with no type, which every module member states" in {
      err("val k = [1, 2, 3]") should include("states its type")
    }

    "a value that is not a constant" in {
      err("f() -> int = 3\nval n: int = f()") should include("not a constant")
    }

    "a value computed from a variable" in {
      err("var x = 1\nval n: int = x") should include("undefined name")
    }

    "an initializer that does not fit its declared type" in {
      err("val k: [2]u8 = [1, 300]") should include("does not fit")
    }

    "an initializer of the wrong shape" in {
      err("val k: [3]int = [1, 2]") should include("[3]int")
    }

    // Storage that exists before anything runs has to be laid down as something. A string is three
    // words including an owner, so there is nothing to write into the object file yet.
    "a string, which is a view with an owner rather than a value" in {
      err("val s: string = \"hi\"") should include("not a constant")
    }

    "a struct, which is the obvious next thing to allow and is not allowed yet" in {
      err("struct P\n    x: int\nend P\nval p: P = P(1)") should include("not a constant")
    }

    "one built from another, since reading storage is not folding a value" in {
      err("val a: [2]int = [1, 2]\nval b: int = a[0]") should include("not a constant")
    }

    // The line between the two declarations, from the other side: a `const` sizes an array because
    // it is a value, and a `val` cannot because it is storage.
    "naming one as an array's bound" in {
      err("val n: usize = 4\nvar bad: [n]int") should include("must be a constant")
    }

    // `13 §7` argues that sysl cannot have Rust's trap where a name in a pattern quietly binds
    // instead of matching. A `val` is the one thing that could have reintroduced it.
    "matching against one, which would bind instead of compare" in {
      err("val n: int = 1\nvar x = 2\nx match\n    n -> print(1)\n    else -> print(2)") should
        include("cannot match against it")
    }
  }

  "the name it takes" - {
    "clashes with a constant of that name" in {
      err("const n: int = 1\nval n: int = 2") should include("already used by a constant")
    }

    "clashes with a function of that name" in {
      err("val n: int = 1\nn() -> int = 2") should include("already declared as a 'val'")
    }

    "clashes with an enum variant of that name" in {
      err("enum Colour\n    Red\nend Colour\nval Red: int = 1") should include("already used by enum")
    }

    "and a second 'val' of that name" in {
      err("val n: int = 1\nval n: int = 2") should include("already declared")
    }

    "while a constant written over one is reported the other way round" in {
      err("val n: int = 1\nconst n: int = 2") should include("already used by a 'val'")
    }
  }
}
