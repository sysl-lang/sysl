package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** `val` — a binding written once (`07`, `13 §7`).
 *
 * This suite is about the **module member**: read-only storage laid into the object file, which is
 * what a table of round constants needs and what a `const` can never be, since a constant is folded
 * into its uses and has no address to index.
 *
 * In a file the program *starts* in — which is what a single-file program is — that is spelled
 * `static val`, because such a file is a body and a plain `val` written there is one of its locals.
 * Hence the modifier on nearly every program below. A file with no statements needs none: everything
 * it declares is the module's already, which is why the two-file programs here are written plain.
 * `EntryFileTests` is where the other half lives.
 *
 * The two properties worth pinning are that it has an **address** — so it can be indexed, iterated,
 * and reached into — and that the address is not a writable one, at any depth and through `&` as
 * well as through assignment.
 */
class ValTests extends AnyFreeSpec with CodegenSupport with RunSupport with ParseSupport {

  "the form parses" - {
    "with a type and a value" in {
      prog("static val n: int = 3") shouldBe List(StaticDecl(ValDecl("n", Some(NamedType("int", Nil)), i(3))))
    }

    "with the type left off" in {
      prog("static val n = 3") shouldBe List(StaticDecl(ValDecl("n", None, i(3))))
    }

    "and takes a visibility modifier, as every other declaration does" in {
      prog("private static val n: int = 3") shouldBe
        List(StaticDecl(ValDecl("n", Some(NamedType("int", Nil)), i(3), Visibility.File)))
    }

    // The value is what tells a `val` from a `var` to the parser as well as to a reader: a binding
    // written once with nothing to hold is not a declaration of anything.
    "but not without a value" in {
      progError("static val n: int") should not be empty
    }
  }

  "a module-level 'val'" - {
    "is read by name" in {
      run("static val n: int = 7\nprint(str(n))") shouldBe "7\n"
    }

    "holds a table, which is the thing a 'const' cannot be" in {
      run("static val k: [4]u32 = [11, 22, 33, 44]\nprint(str(k[2]))") shouldBe "33\n"
    }

    "is indexed at a value only known while running" in {
      run(
        """static val k: [4]int = [11, 22, 33, 44]
          |var total = 0
          |for i in 0..<4 do total += k[i]
          |print(str(total))""".stripMargin,
      ) shouldBe "110\n"
    }

    "is iterated" in {
      run(
        """static val k: [3]int = [2, 3, 4]
          |var p = 1
          |for x in k do p *= x
          |print(str(p))""".stripMargin,
      ) shouldBe "24\n"
    }

    "reports its length" in {
      run("static val k: [5]u8 = [1; 5]\nprint(str(k.len))") shouldBe "5\n"
    }

    "may be built with a repeat" in {
      run("static val k: [8]int = [3; 8]\nprint(str(k[7]))\nprint(str(k.len))") shouldBe "3\n8\n"
    }

    "takes its element type from its declaration, so the elements need no suffix" in {
      run("static val k: [2]u64 = [0xcbf29ce484222325, 2]\nprint(str(k[0]))") shouldBe "14695981039346656037\n"
    }

    "is reached from a function, wherever the function is written" in {
      run(
        """first() -> int = k[0]
          |static val k: [2]int = [9, 8]
          |print(str(first()))""".stripMargin,
      ) shouldBe "9\n"
    }

    // Order-freedom is what makes it a declaration rather than a statement: nothing runs to
    // initialize it, so nothing has to run first.
    "may be written in terms of a 'const' declared below it" in {
      run("static val k: [n]int = [4; n]\nconst n: usize = 3\nprint(str(k.len))\nprint(str(k[2]))") shouldBe "3\n4\n"
    }

    // The other half of order-freedom, and the sharp one: where the initializer *does* have to run,
    // it runs with the module's other initializers and not in the place it was written. So a
    // `static val` sitting between two statements is **not** evaluated between them — it is already
    // bound by the time the first of them runs.
    //
    // That surprises a reader exactly once, and the modifier is now what warns them: a declaration
    // saying it belongs to the module is saying it is not part of the sequence around it. The shape
    // this used to bite — a script binding a value, setting it up with a statement, and binding
    // something derived from it — is written with plain `val`s, which run where they stand
    // (`EntryFileTests`).
    "runs its initializer ahead of the script, wherever it was written" in {
      run(
        """print("script")
          |
          |static val n: int = noisy()
          |
          |print(str(n))
          |
          |noisy() -> int
          |    print("initializer")
          |    7
          |end noisy""".stripMargin,
      ) shouldBe "initializer\nscript\n7\n"
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
      run("static val g: [2][3]int = [[1, 2, 3], [4, 5, 6]]\nprint(str(g[1][0]))") shouldBe "4\n"
    }

    "holds floats" in {
      run("static val w: [2]f64 = [0.5, 0.25]\nprint(str(w[0] + w[1]))") shouldBe "0.75\n"
    }

    "holds narrow floats" in {
      run("static val w: [2]f32 = [0.5, 0.25]\nprint(str(w[0] + w[1]))") shouldBe "0.75\n"
    }

    "holds bools" in {
      run("static val b: [3]bool = [true, false, true]\nprint(str(b[0] && b[2]))") shouldBe "true\n"
    }

    // An array is a value, so binding one to a `var` copies it — which is how a program that wants
    // to work from a table gets writable storage without the table being writable.
    "is copied, not aliased, when it is bound to a 'var'" in {
      run(
        """static val k: [3]int = [1, 2, 3]
          |var c = k
          |c[0] = 99
          |print(str(c[0]))
          |print(str(k[0]))""".stripMargin,
      ) shouldBe "99\n1\n"
    }

    "is passed to a function by value" in {
      run(
        """sum(a: [3]int) -> int = a[0] + a[1] + a[2]
          |static val k: [3]int = [1, 2, 3]
          |print(str(sum(k)))""".stripMargin,
      ) shouldBe "6\n"
    }
  }

  // The point of the whole exercise: storage that costs nothing to reach and nothing to set up.
  "it is laid into the object file" - {
    "as a constant global, with no code to initialize it" in {
      val out = ir("static val k: [3]u32 = [7, 8, 9]\nprint(str(k[0]))")

      out should include("private constant [3 x i32] [i32 7, i32 8, i32 9]")
    }

    "with a repeat written out, since a global has no loop to run" in {
      ir("static val k: [4]u8 = [2; 4]\nprint(str(k[0]))") should
        include("private constant [4 x i8] [i8 2, i8 2, i8 2, i8 2]")
    }

    // A narrow float has to be rounded where the constant is written, since there is no `fptrunc`
    // to run before the program starts — and LLVM refuses a hex constant a `float` cannot hold, so
    // the f64 bits of `0.1` (0x3FB999999999999A) would not merely be imprecise, it would not build.
    "with a narrow float rounded to its own width, not to a double's" in {
      ir("static val w: [1]f32 = [0.1]\nprint(str(w[0]))") should
        include("private constant [1 x float] [float 0x3FB99999A0000000]")
    }

    "and reaching an element is a 'getelementptr' from the global itself" in {
      irMain("static val k: [4]int = [1, 2, 3, 4]\nprint(str(k[1]))") should include("ptr @k")
    }

    // A struct is fields laid side by side, so it is a constant tree exactly when its fields are —
    // the same rule the array above follows. It used to be a `global` the prologue filled in with a
    // store per field, which is the opposite of what a table wants.
    "as a struct constant, field by field, rather than stores a prologue makes" in {
      val out = ir(
        """struct Pair
          |    a: int
          |    b: int
          |end Pair
          |static val p: Pair = Pair(1, 2)
          |print(str(p.a))""".stripMargin,
      )

      out should include("@p = private constant %struct.Pair { i32 1, i32 2 }")
      out should not include "@p = private global"
    }

    "and a table of structs, which is the shape a driver's device list has" in {
      ir(
        """struct Pair
          |    a: int
          |    b: int
          |end Pair
          |static val ps: [2]Pair = [Pair(1, 2), Pair(3, 4)]
          |print(str(ps[1].a))""".stripMargin,
      ) should include(
        "@ps = private constant [2 x %struct.Pair] " +
          "[%struct.Pair { i32 1, i32 2 }, %struct.Pair { i32 3, i32 4 }]",
      )
    }

    // The other side of the line, which is what keeps the arm above from being vacuous: one field
    // that has to be computed makes the whole struct code, and the storage writable again.
    "while a struct with one computed field is a computed 'val' like any other" in {
      val out = ir(
        """struct Pair
          |    a: int
          |    b: int
          |end Pair
          |n() -> int = 1
          |static val p: Pair = Pair(n(), 2)
          |print(str(p.a))""".stripMargin,
      )

      out should include("@p = private global %struct.Pair zeroinitializer")
      out should not include "@p = private constant"
    }
  }

  /** A `val` holds a string whose bytes the object file carries (`13 §7`).
   *
   * The rule it relaxes was never about `string`. It is about a count with nowhere to write the
   * release, and a literal takes none: its bytes are a constant in read-only data and its owner word
   * is null, which both retain and release test for and do nothing about (`04`). So what decides is
   * the initializer — a literal is admitted and a built string is not, and the two are told apart by
   * the same test that decides whether any `val` is laid into the object file.
   *
   * The shape this exists for is a module with **no allocator** naming its messages once. `const`
   * could not serve it: a constant has no address (`13 §7`), so it cannot be indexed at a position
   * computed while running, which is the whole of what a table is for.
   */
  "a module-level 'val' holds a string literal" - {
    "read by name" in {
      run("static val greeting: string = \"hello\"\nprint(greeting)") shouldBe "hello\n"
    }

    "as three words with a null owner, laid straight into the object file" in {
      val out = ir("static val greeting: string = \"hello\"\nprint(greeting)")

      out should include("@greeting = private constant { ptr, ptr, i64 } { ptr null, ptr @.str")
      out should not include "@greeting = private global"
    }

    // The case the whole feature is for: a table indexed at a position only known while running,
    // which is the one thing a `const` can never be.
    "a table of them, indexed at a value only known while running" in {
      run(
        """static val names: [3]string = ["alpha", "beta", "gamma"]
          |var i = 2
          |print(names[i])""".stripMargin,
      ) shouldBe "gamma\n"
    }

    "which is iterated, since it is storage rather than a folded value" in {
      run(
        """static val names: [3]string = ["a", "bb", "ccc"]
          |var total: usize = 0
          |for s in names do total += s.len
          |print(str(total))""".stripMargin,
      ) shouldBe "6\n"
    }

    "a repeat, written out as a global has no loop to run" in {
      run("static val xs: [3]string = [\"x\"; 3]\nvar i = 1\nprint(xs[i])") shouldBe "x\n"
    }

    // A text block is joined by the lexer into one constant (`04 § Text blocks`), so it arrives here
    // as a literal and needs no rule of its own.
    "a text block, which is one literal by the time the analyzer sees it" in {
      run(
        "val banner: string = \"\"\"\n    one\n    two\n    \"\"\"\nprint(banner)",
      ) shouldBe "one\ntwo\n\n"
    }

    // A struct is admitted by the same recursion, so a `{name, code}` pair — which is what a device
    // table actually is — is one declaration rather than two parallel arrays.
    "a struct with one in it, and a table of those" in {
      run(
        """struct Device
          |    name: string
          |    code: int
          |end Device
          |static val devices: [2]Device = [Device("uart", 16), Device("timer", 32)]
          |var i = 1
          |print(devices[i].name, str(devices[i].code))""".stripMargin,
      ) shouldBe "timer 32\n"
    }

    "a tuple with one in it, which is a struct with its fields named for their positions" in {
      run("static val t: (int, string) = (1, \"one\")\nprint(str(t.0), t.1)") shouldBe "1 one\n"
    }

    // The motivation, stated as a test: a module that has declared it will not allocate may still
    // name its messages, index them, measure them, and cut them up.
    "in a module that declared '@no_alloc', which is what the relaxation is for" in {
      run(
        """@no_alloc
          |
          |static val messages: [3]string = ["out of range", "not permitted", "no such device"]
          |
          |var i = 2
          |print(messages[i])
          |print(messages[i].len)
          |print(messages[0][0..<3])""".stripMargin,
      ) shouldBe "no such device\n14\nout\n"
    }
  }

  /** The corners of the constant tree, now that it reaches strings and structs.
   *
   * A constant expression is written out in full rather than built, so every shape that can nest
   * inside another has to be spelled correctly at every depth — which is where a lowering with no
   * basic block to fall back on breaks if it breaks at all.
   */
  "a constant tree nests" - {
    "a struct inside a struct" in {
      val out = ir(
        """struct Inner
          |    a: int
          |    b: int
          |end Inner
          |struct Outer
          |    i: Inner
          |    c: int
          |end Outer
          |static val o: Outer = Outer(Inner(1, 2), 3)
          |print(str(o.i.b))""".stripMargin,
      )

      out should include("@o = private constant %struct.Outer { %struct.Inner { i32 1, i32 2 }, i32 3 }")
    }

    "an array inside a struct" in {
      val out = ir(
        """struct S
          |    xs: [2]int
          |    n:  int
          |end S
          |static val s: S = S([4, 5], 6)
          |print(str(s.xs[1]))""".stripMargin,
      )

      out should include("@s = private constant %struct.S { [2 x i32] [i32 4, i32 5], i32 6 }")
    }

    "a bool and a narrow float in one, each at its own width" in {
      val out = ir(
        """struct S
          |    on: bool
          |    w:  f32
          |end S
          |static val s: S = S(true, 0.1)
          |print(str(s.on))""".stripMargin,
      )

      out should include("@s = private constant %struct.S { i1 1, float 0x3FB99999A0000000 }")
    }

    // A pointer written as a number is an `inttoptr`, which is itself a constant expression — so it
    // nests inside a struct constant the same way a number does. This is the driver's register block
    // with its own name beside it, which is the shape the two halves of this feature meet in.
    "an address the datasheet gives, inside a struct beside its name" in {
      val out = ir(
        """const UART: usize = 0x1000_0000
          |struct Device
          |    name: string
          |    base: *u32
          |end Device
          |static val uart: Device = Device("uart", ptr_cast(UART))
          |print(uart.name)""".stripMargin,
      )

      out should include("ptr inttoptr (i64 268435456 to ptr) }")
      out should include("@uart = private constant %struct.Device")
    }

    "an array of tuples carrying strings" in {
      run(
        """static val rows: [2](int, string) = [(1, "one"), (2, "two")]
          |var i = 1
          |print(str(rows[i].0), rows[i].1)""".stripMargin,
      ) shouldBe "2 two\n"
    }

    "a generic struct instantiated at 'string'" in {
      run(
        """struct Cell[T]
          |    v: T
          |end Cell
          |static val c: Cell[string] = Cell("held")
          |print(c.v)""".stripMargin,
      ) shouldBe "held\n"
    }

    // The length a literal carries is its bytes, not its characters, and the empty one is a real
    // value rather than an absent pointer — both of which the three-word constant has to say.
    "the empty literal, whose bytes are none of them" in {
      run("static val e: string = \"\"\nprint(e.len, e == \"\")") shouldBe "0 true\n"
    }

    "a literal with an escape and a character outside ASCII" in {
      run("static val s: string = \"a\\tb\\u{e9}\"\nprint(s.len)\nprint(s)") shouldBe "5\na\tbé\n"
    }

    // A literal may carry a NUL as an ordinary byte, since a string carries its length — so the
    // constant's length has to come from the bytes rather than from where a terminator falls.
    "a literal with a NUL inside it, which is a byte like any other" in {
      run("static val s: string = \"a\\u{0}b\"\nprint(s.len)") shouldBe "3\n"
    }

    // The bytes of a literal are not interned across uses, so two `val`s written the same way name
    // two globals. Equality is over the bytes rather than the address, so nothing in the language
    // can see the difference — which is what makes the duplication a size question and not a
    // correctness one.
    "the same literal in two 'val's, which are two globals and one value" in {
      run(
        """static val a: string = "same"
          |static val b: string = "same"
          |print(a == b, a.len == b.len)""".stripMargin,
      ) shouldBe "true true\n"
    }

    // The premise the whole relaxation rests on, exercised rather than asserted: a literal's owner
    // word is null, so retain and release find nothing to do — including where the value is copied
    // into heap storage that outlives the read and is then let go of.
    "a literal read out of one, handed to the heap and released with it" in {
      run(
        """struct Box
          |    s: string
          |end Box
          |static val greeting: string = "hello"
          |hold() -> string
          |    var b: &Box = Box(greeting)
          |    b.s
          |end hold
          |print(hold())""".stripMargin,
      ) shouldBe "hello\n"
    }
  }

  /** What the relaxation had to leave alone, checked against the chapters rather than assumed.
   *
   * A constant `val` skips the initializer order entirely, and it skips every check that would have
   * run in one. Both are correct only if nothing that *needs* either has become constant.
   */
  "what a constant string 'val' does not disturb" - {

    // `13 §7`: a value that has to be checked is code, whatever it looks like. A `val` at a
    // constrained type and a table of them are pinned in `ComputedValTests`; the case the struct arm
    // adds is a constrained field *inside* one, where the check is at the argument. The load-bearing
    // assertion is the out-of-range one, since an in-range value reads back either way.
    "a struct with a constrained field is filled by code, so the field's check still runs" in {
      val out = ir(
        """type Age = int within 0..150
          |struct P
          |    age: Age
          |end P
          |static val p: P = P(40)
          |print(p.age)""".stripMargin,
      )

      out should include("@p = private global %struct.P zeroinitializer")
      out should not include "@p = private constant"

      exits(
        """type Age = int within 0..150
          |struct P
          |    age: Age
          |end P
          |static val p: P = P(200)
          |print(p.age)""".stripMargin,
      )
    }

    // A constant `val` is readable before any initializer runs, so a computed one may read it — the
    // ordering graph is over the computed ones, and a constant is already there.
    "a computed 'val' may read a constant string one, which is already in place" in {
      run(
        """static val greeting: string = "hello"
          |static val n: usize = twice()
          |twice() -> usize = greeting.len * 2
          |print(n)""".stripMargin,
      ) shouldBe "10\n"
    }

    // Read-only at every depth is about the storage the declaration lays down, and a struct one lays
    // down a struct — so a field of it is no more writable than an element of a table.
    "a struct 'val' is read-only at every depth, field as well as name" in {
      err(
        """struct Pair
          |    a: int
          |    b: int
          |end Pair
          |static val p: Pair = Pair(1, 2)
          |p.a = 3""".stripMargin,
      ) should include("written once")
    }

    // `07 § A view that may not be written`: slicing a `val` yields a `[]const T`, and the element
    // type being a counted one changes nothing about that.
    "slicing a table of literals yields a view that may be read and not written" in {
      run(
        """static val names: [3]string = ["alpha", "beta", "gamma"]
          |var v = names[1..<3]
          |print(v[0], v.len)""".stripMargin,
      ) shouldBe "beta 2\n"

      err(
        """static val names: [3]string = ["alpha", "beta", "gamma"]
          |var v = names[1..<3]
          |v[0] = "other" """.stripMargin,
      ) should include("views elements it may not write")
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
          |static val regs: *Uart = ptr_cast(malloc(16))
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
          |static val cells: *int = ptr_cast(malloc(32))
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
          |static val ps: [2]*u8 = [ptr_cast(malloc(8)), ptr_cast(malloc(8))]
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
          |static val d: Dev = mk()
          |print(str(d.id))""".stripMargin,
      ) shouldBe "5\n"
    }

    // The address of a function is the same kind of thing: a machine address that counts nothing.
    // It was already accepted before pointers were, but only by falling off the end of the type
    // test rather than by being meant, so it is pinned here now that the rule names it.
    "the address of a function, and a call through the one it holds" in {
      run(
        """g(x: int) -> int = x + 1
          |static val f: *extern(int) -> int = &g
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
          |static val p: *&Node = ptr_cast(malloc(8))
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
          |static val s: Slot = mk()
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
          |static val c: Cell[int] = mk()
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
          |static val t: (int, *u8) = mk()
          |print(str(t.0))""".stripMargin,
      ) shouldBe "1\n"

      err(
        """struct Node
          |    v: int
          |end Node
          |mk() -> (int, &Node) = (1, Node(2))
          |static val t: (int, &Node) = mk()
          |print("ok")""".stripMargin,
      ) should include("a count with nowhere to write the release")
    }

    "and one 'val' is filled from another" in {
      run(
        """extern malloc(n: usize) -> *u8
          |static val a: *u8 = malloc(8)
          |static val b: *u8 = a
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
          |static val regs: *u32 = ptr_cast(UART)
          |print("ok")""".stripMargin,
      ) should include("@regs = private constant ptr inttoptr (i64 268435456 to ptr)")
    }

    "and so is a null one" in {
      ir("static val p: *u32 = null\nprint(\"ok\")") should include("@p = private constant ptr null")
    }

    // A pointer to a trait is two words (`03`), so its empty value is not the one-word `null` — the
    // one place the constant form has to look at the type rather than at the tree.
    "including a null at a trait pointer, which is two words rather than one" in {
      ir(
        """trait Shape
          |    area(self) -> int
          |end Shape
          |static val p: *Shape = null
          |print("ok")""".stripMargin,
      ) should include("@p = private constant { ptr, ptr } zeroinitializer")
    }

    "and an array of them, element by element" in {
      ir("static val ps: [2]*u8 = [null, null]\nprint(\"ok\")") should
        include("@ps = private constant [2 x ptr] [ptr null, ptr null]")
    }

    "and a repeat of one, written out as a global has no loop to run" in {
      ir("static val ps: [3]*u8 = [null; 3]\nprint(\"ok\")") should
        include("@ps = private constant [3 x ptr] [ptr null, ptr null, ptr null]")
    }

    // The address of code is the same shape and lands the same way: a reset vector read as
    // something callable, which is what a freestanding program starts from.
    "and an address read as the address of a function" in {
      ir(
        """const RESET: usize = 0x8000
          |static val entry: *extern() -> unit = ptr_cast(RESET)
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
          |static val regs: *u32 = ptr_cast(UART)
          |go() = regs[0] = 1u32
          |go()""".stripMargin,
      ) should include("@regs = private constant ptr inttoptr (i64 268435456 to ptr)")
    }

    // The other side of the same line: an address a call produces is code, so it is a `global` the
    // prologue writes rather than a `constant` the object file carries.
    "while an address a call produces is a computed one, as any other computed value is" in {
      val out = ir(
        """extern malloc(n: usize) -> *u8
          |static val p: *u8 = malloc(8)
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
        """static val a: *u8 = ptr_cast(usize(b))
          |static val b: *u8 = ptr_cast(usize(a))
          |print("ok")""".stripMargin,
      ) should include("cannot be initialized")
    }
  }

  "what a pointer 'val' still promises, and what it never did" - {

    // The promise it keeps: its own storage is written once.
    "the name may not be assigned to" in {
      err(
        """const UART: usize = 0x1000
          |static val regs: *u32 = ptr_cast(UART)
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
          |static val regs: *u32 = ptr_cast(UART)
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
        """static val k: [4]int = [1, 2, 3, 4]
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
        """static val n: int = 1
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
      err("static val n: int = 1\nn = 2") should include("written once")
    }

    "assigning to one of its elements" in {
      err("static val k: [2]int = [1, 2]\nk[0] = 9") should include("written once")
    }

    "compound assignment, which is an assignment" in {
      err("static val k: [2]int = [1, 2]\nk[1] += 1") should include("written once")
    }

    "an increment, for the same reason" in {
      err("static val k: [2]int = [1, 2]\nk[0]++") should include("written once")
    }

    // A `*T` is a licence to write, so handing one out would move the mistake one step from where
    // it could still be reported.
    "taking its address" in {
      err("static val k: [2]int = [1, 2]\nvar p = &k[0]") should include("written once")
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
      run("static val k: [4]int = [1, 2, 3, 4]\nvar s = k[1..<3]\nprint(s[0], s[1])") shouldBe "2 3\n"

      err("static val k: [4]int = [1, 2, 3, 4]\nvar s = k[1..<3]\ns[0] = 9") should
        include("views elements it may not write")
    }

    "a module-level 'val' with no type, which every module member states" in {
      err("static val k = [1, 2, 3]") should include("states its type")
    }

    "a value computed from a variable" in {
      err("var x = 1\nstatic val n: int = x") should include("undefined name")
    }

    "an initializer that does not fit its declared type" in {
      err("static val k: [2]u8 = [1, 300]") should include("does not fit")
    }

    "an initializer of the wrong shape" in {
      err("static val k: [3]int = [1, 2]") should include("[3]int")
    }

    // Storage that outlives every frame is never let go of, so a count taken in one has nowhere to
    // write the release. What decides is the **value**: a string the program builds takes a count,
    // and the literal above does not.
    "a string built while the program runs" in {
      err("static val s: string = str(1)") should include("a count with nowhere to write the release")
    }

    // Two literals joined is the discriminating pair against the section above: it looks constant
    // and is not, because joining them allocates. Folding it is separate work, deliberately not done
    // here — admitting it on the strength of how it reads would be admitting a leak.
    "a string joined from two literals, which allocates however constant it looks" in {
      err("static val s: string = \"a\" + \"b\"") should include("built while the program runs")
    }

    "a reference, which is the count itself" in {
      err("struct P\n    x: int\nend P\nmk() -> &P = P(1)\nstatic val r: &P = mk()") should
        include("a count with nowhere to write the release")
    }

    "a view, whose owner word is a count like any other" in {
      err("static val k: [4]int = [1, 2, 3, 4]\nstatic val s: []const int = k[1..<3]") should
        include("a count with nowhere to write the release")
    }

    // A struct that carries an `invariant` has to be *checked*, and a check is code (`13 §7`), so
    // there is nowhere for the object file to carry it — which means a counted field in one is
    // refused even though every part of it was written as a literal. That is the specification's own
    // rule rather than a gap, and it is pinned because it is the surprising corner of the relaxation.
    "a struct whose invariant has to run, even with a literal in it" in {
      err(
        """struct Tag
          |    name: string
          |    invariant name.len > 0
          |end Tag
          |static val t: Tag = Tag("uart")""".stripMargin,
      ) should include("a count with nowhere to write the release")
    }

    // The diagnostic names the value rather than the type, because once a literal is legal "its type
    // is string" sends a reader looking for a spelling instead of at what they wrote.
    "and the message says what may be held instead" in {
      err("static val s: string = str(1)") should
        include("a string literal owns nothing, and neither does a table of them")
    }

    // The line between the two declarations, from the other side: a `const` sizes an array because
    // it is a value, and a `val` cannot because it is storage.
    "naming one as an array's bound" in {
      err("static val n: usize = 4\nvar bad: [n]int") should include("must be a constant")
    }

    // `13 §7` argues that sysl cannot have Rust's trap where a name in a pattern quietly binds
    // instead of matching. A `val` is the one thing that could have reintroduced it.
    "matching against one, which would bind instead of compare" in {
      err("static val n: int = 1\nvar x = 2\nx match\n    n -> print(1)\n    else print(2)") should
        include("cannot match against it")
    }
  }

  "the name it takes" - {
    "clashes with a constant of that name" in {
      err("const n: int = 1\nstatic val n: int = 2") should include("already used by a constant")
    }

    "clashes with a function of that name" in {
      err("static val n: int = 1\nn() -> int = 2") should include("already declared as a 'val'")
    }

    "clashes with an enum variant of that name" in {
      err("enum Colour\n    Red\nend Colour\nstatic val Red: int = 1") should include("already used by enum")
    }

    "and a second 'val' of that name" in {
      err("static val n: int = 1\nstatic val n: int = 2") should include("already declared")
    }

    "while a constant written over one is reported the other way round" in {
      err("static val n: int = 1\nconst n: int = 2") should include("already used by a 'val'")
    }
  }
}
