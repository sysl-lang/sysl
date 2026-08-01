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

  /** A `val` at pointer type — the file-scope register block (`13 §7`).
   *
   * What a `val` promises is that its **own storage** is written once and never again, and holding
   * an address keeps that promise exactly as holding a number does. It is not a promise about what
   * the address reaches, and it could not be: slicing a `val` and writing `&v[0]` already yields a
   * writable `*T` on purpose (`07 § A view that may not be written`), so refusing one here declined
   * a route to what another route grants.
   */
  "a module-level 'val' holds a raw pointer" - {

    // The shape this exists for: an address the datasheet gives as a number, named once and reached
    // by every function of a driver rather than re-materialised in each.
    "an address the program was told, reached from several functions" in {
      run(
        """extern malloc(n: usize) -> *u8
          |struct Uart
          |    status: u32
          |    data:   u32
          |end Uart
          |val regs: *Uart = ptr_cast(malloc(16))
          |arm()
          |    regs.status = 7u32
          |end arm
          |fire(b: u32)
          |    regs.data = b
          |end fire
          |arm()
          |fire(65u32)
          |print(str(regs.status), str(regs.data))""".stripMargin,
      ) shouldBe "7 65\n"
    }

    "and the elements it addresses are read and written through it" in {
      run(
        """extern malloc(n: usize) -> *u8
          |val cells: *int = ptr_cast(malloc(32))
          |put(v: int)
          |    cells[0] = v
          |end put
          |put(42)
          |print(str(cells[0]))""".stripMargin,
      ) shouldBe "42\n"
    }

    "an array of them" in {
      run(
        """extern malloc(n: usize) -> *u8
          |val ps: [2]*u8 = [ptr_cast(malloc(8)), ptr_cast(malloc(8))]
          |print(str(usize(ps[0]) != usize(ps[1])))""".stripMargin,
      ) shouldBe "true\n"
    }

    "a struct with one in it" in {
      run(
        """extern malloc(n: usize) -> *u8
          |struct Dev
          |    base: *u32
          |    id:   int
          |end Dev
          |mk() -> Dev = Dev(ptr_cast(malloc(8)), 5)
          |val d: Dev = mk()
          |print(str(d.id))""".stripMargin,
      ) shouldBe "5\n"
    }

    // The address of a function is the same kind of thing: a machine address that counts nothing.
    // It was already accepted before pointers were, but only by falling off the end of the type
    // test rather than by being meant, so it is pinned here now that the rule names it.
    "the address of a function, and a call through the one it holds" in {
      run(
        """g(x: int) -> int = x + 1
          |val f: *extern(int) -> int = &g
          |print(str(f(3)))""".stripMargin,
      ) shouldBe "4\n"
    }

    // A pointer is not looked through, so what is at the far end is not this storage's business —
    // which is the whole of why a `*T` is admitted where a `&T` is not.
    "one whose pointee is itself counted, since a pointer owns nothing at the far end" in {
      ir(
        """struct Node
          |    v: int
          |end Node
          |extern malloc(n: usize) -> *u8
          |val p: *&Node = ptr_cast(malloc(8))
          |print("ok")""".stripMargin,
      ) should include("@p")
    }

    "an enum whose payload is one" in {
      run(
        """extern malloc(n: usize) -> *u8
          |enum Slot
          |    Empty
          |    At(p: *u8)
          |end Slot
          |mk() -> Slot = At(malloc(8))
          |val s: Slot = mk()
          |s match
          |    At(p) -> print(str(usize(p) != 0))
          |    Empty -> print("empty")""".stripMargin,
      ) shouldBe "true\n"
    }

    "a generic type instantiated at one" in {
      run(
        """extern malloc(n: usize) -> *u8
          |struct Cell[T]
          |    p: *T
          |end Cell
          |mk() -> Cell[int] = Cell(ptr_cast(malloc(8)))
          |val c: Cell[int] = mk()
          |c.p[0] = 6
          |print(str(c.p[0]))""".stripMargin,
      ) shouldBe "6\n"
    }

    // A tuple is a struct with its fields named for their positions, so the recursive half of the
    // rule reaches it — and reaches it in both directions, which is the discriminating pair.
    "a tuple with one in it, where a tuple with a reference is still refused" in {
      run(
        """extern malloc(n: usize) -> *u8
          |mk() -> (int, *u8) = (1, malloc(8))
          |val t: (int, *u8) = mk()
          |print(str(t.0))""".stripMargin,
      ) shouldBe "1\n"

      err(
        """struct Node
          |    v: int
          |end Node
          |mk() -> (int, &Node) = (1, Node(2))
          |val t: (int, &Node) = mk()
          |print("ok")""".stripMargin,
      ) should include("a count with nowhere to write the release")
    }

    "and one 'val' is filled from another" in {
      run(
        """extern malloc(n: usize) -> *u8
          |val a: *u8 = malloc(8)
          |val b: *u8 = a
          |print(str(usize(a) == usize(b)))""".stripMargin,
      ) shouldBe "true\n"
    }

    // The rule this relaxes is the *module* one — a local `val` was never held to it, since a frame
    // ends and whatever it counted is released there. Pinned so the two levels stay told apart.
    "while a local one held a pointer all along, having a frame to end in" in {
      run(
        """extern malloc(n: usize) -> *u8
          |f() -> int
          |    val p: *int = ptr_cast(malloc(8))
          |    p[0] = 5
          |    p[0]
          |end f
          |print(str(f()))""".stripMargin,
      ) shouldBe "5\n"
    }

    "and another module's is reached by naming it" in {
      irIn(
        ("dev", "d.sysl", "module dev\nconst UART: usize = 0x1000\nval regs: *u32 = ptr_cast(UART)"),
        ("", "main.sysl", "print(str(usize(dev.regs)))"),
      ) should include("@dev$regs = private constant ptr inttoptr (i64 4096 to ptr)")
    }
  }

  "a constant address costs no initializer" - {

    // `13 §7` puts a constant tree in the object file and orders nothing, and an address written as
    // a number is one. It matters where it is used: a freestanding program reaches its registers
    // before anything has run, and a prologue that had to fill the pointer in first would be no use.
    "'ptr_cast' of a constant is laid straight in as an 'inttoptr'" in {
      ir(
        """const UART: usize = 0x10000000
          |val regs: *u32 = ptr_cast(UART)
          |print("ok")""".stripMargin,
      ) should include("@regs = private constant ptr inttoptr (i64 268435456 to ptr)")
    }

    "and so is a null one" in {
      ir("val p: *u32 = null\nprint(\"ok\")") should include("@p = private constant ptr null")
    }

    // A pointer to a trait is two words (`03`), so its empty value is not the one-word `null` — the
    // one place the constant form has to look at the type rather than at the tree.
    "including a null at a trait pointer, which is two words rather than one" in {
      ir(
        """trait Shape
          |    area(self) -> int
          |end Shape
          |val p: *Shape = null
          |print("ok")""".stripMargin,
      ) should include("@p = private constant { ptr, ptr } zeroinitializer")
    }

    "and an array of them, element by element" in {
      ir("val ps: [2]*u8 = [null, null]\nprint(\"ok\")") should
        include("@ps = private constant [2 x ptr] [ptr null, ptr null]")
    }

    "and a repeat of one, written out as a global has no loop to run" in {
      ir("val ps: [3]*u8 = [null; 3]\nprint(\"ok\")") should
        include("@ps = private constant [3 x ptr] [ptr null, ptr null, ptr null]")
    }

    // The address of code is the same shape and lands the same way: a reset vector read as
    // something callable, which is what a freestanding program starts from.
    "and an address read as the address of a function" in {
      ir(
        """const RESET: usize = 0x8000
          |val entry: *extern() -> unit = ptr_cast(RESET)
          |print("ok")""".stripMargin,
      ) should include("@entry = private constant ptr inttoptr (i64 32768 to ptr)")
    }

    // The whole point, on the target it is for: no OS, no prologue that has run, and the register
    // block still readable from the first instruction.
    "on a freestanding target as much as on this one" in {
      val riscv = Target.all.find(_.name == "riscv64-freestanding").get

      irFor(
        riscv,
        """const UART: usize = 0x10000000
          |val regs: *u32 = ptr_cast(UART)
          |go() = regs[0] = 1u32
          |go()""".stripMargin,
      ) should include("@regs = private constant ptr inttoptr (i64 268435456 to ptr)")
    }

    // The other side of the same line: an address a call produces is code, so it is a `global` the
    // prologue writes rather than a `constant` the object file carries.
    "while an address a call produces is a computed one, as any other computed value is" in {
      val out = ir(
        """extern malloc(n: usize) -> *u8
          |val p: *u8 = malloc(8)
          |print("ok")""".stripMargin,
      )

      out should include("@p = private global ptr zeroinitializer")
      out should not include "@p = private constant"
    }

    // The ordering rule is over the `val`s themselves, so a pointer one joins the graph like any
    // other — which is worth pinning because a pointer is the type most likely to be reached for in
    // a cycle (`13 §7`, "what order the initializers run in").
    "and a cycle among computed pointer 'val's is still reported" in {
      err(
        """val a: *u8 = ptr_cast(usize(b))
          |val b: *u8 = ptr_cast(usize(a))
          |print("ok")""".stripMargin,
      ) should include("cannot be initialized")
    }
  }

  "what a pointer 'val' still promises, and what it never did" - {

    // The promise it keeps: its own storage is written once.
    "the name may not be assigned to" in {
      err(
        """const UART: usize = 0x1000
          |val regs: *u32 = ptr_cast(UART)
          |regs = ptr_cast(UART)
          |print("ok")""".stripMargin,
      ) should include("written once")
    }

    // The promise it never made: read-only at every depth is about the storage the declaration lays
    // down, not about what a value inside it addresses. `07 § A view that may not be written` says
    // the same thing from the other side, and the test below is what makes this one a claim rather
    // than an assertion about an implementation.
    "while the storage it addresses is writable, which is what the raw tier means" in {
      ir(
        """const UART: usize = 0x1000
          |val regs: *u32 = ptr_cast(UART)
          |set()
          |    regs[0] = 1u32
          |end set
          |print("ok")""".stripMargin,
      ) should include("@regs")
    }

    // …and the route that was already open, which is the argument for admitting the type at all: a
    // `val`'s own elements are reachable as a writable `*T` through a view of it, today, on purpose.
    "because a writable pointer into a 'val' is already reachable through a view of it" in {
      run(
        """val k: [4]int = [1, 2, 3, 4]
          |var s = k[1..<3]
          |var p = &s[0]
          |print(str(p[0]))""".stripMargin,
      ) shouldBe "2\n"
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

    // Slicing is no longer among them: a view of a `val` is a `[]const T`, which carries the
    // read-only-ness rather than losing it, so what is refused is the write through the view.
    "writing through a view of one, which is where the refusal moved when slicing became legal" in {
      run("val k: [4]int = [1, 2, 3, 4]\nvar s = k[1..<3]\nprint(s[0], s[1])") shouldBe "2 3\n"

      err("val k: [4]int = [1, 2, 3, 4]\nvar s = k[1..<3]\ns[0] = 9") should
        include("views elements it may not write")
    }

    "a module-level 'val' with no type, which every module member states" in {
      err("val k = [1, 2, 3]") should include("states its type")
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

    // Storage that outlives every frame is never let go of, so a count taken in one has nowhere to
    // write the release. A string is a view with an owner word, so it is one. Cost noted in `13 §7`.
    "a string, which is a view with an owner rather than a value" in {
      err("val s: string = \"hi\"") should include("a count with nowhere to write the release")
    }

    "a reference, which is the count itself" in {
      err("struct P\n    x: int\nend P\nmk() -> &P = P(1)\nval r: &P = mk()") should
        include("a count with nowhere to write the release")
    }

    "a view, whose owner word is a count like any other" in {
      err("val k: [4]int = [1, 2, 3, 4]\nval s: []const int = k[1..<3]") should
        include("a count with nowhere to write the release")
    }

    // The diagnostic says which types are meant and which one is not, because "not plain data" left
    // a reader looking for a spelling rather than reading the reason.
    "and the message names what may be held instead" in {
      err("val s: string = \"hi\"") should include("A raw pointer may be held: it counts nothing")
    }

    // The line between the two declarations, from the other side: a `const` sizes an array because
    // it is a value, and a `val` cannot because it is storage.
    "naming one as an array's bound" in {
      err("val n: usize = 4\nvar bad: [n]int") should include("must be a constant")
    }

    // `13 §7` argues that sysl cannot have Rust's trap where a name in a pattern quietly binds
    // instead of matching. A `val` is the one thing that could have reintroduced it.
    "matching against one, which would bind instead of compare" in {
      err("val n: int = 1\nvar x = 2\nx match\n    n -> print(1)\n    else print(2)") should
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
