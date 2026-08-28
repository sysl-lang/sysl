package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** `val` at pointer type — the file-scope register block (`07`, `reference/modules.md § val — a thing`).
 *
 * Split out of `ValTests`, which is about what a `val` lays down; this is about the one payload that
 * makes people ask whether it should be allowed at all.
 *
 * What a `val` promises is that its **own storage** is written once and never again, and holding an
 * address keeps that promise exactly as holding a number does. It is not a promise about what the
 * address reaches, and it could not be: slicing a `val` and writing `&v[0]` already yields a
 * writable `*T` on purpose (`reference/arrays.md § []const T — a view that may not be written`), so
 * refusing one here would decline a route to what another route grants.
 */
class ValPointerTests extends AnyFreeSpec with CodegenSupport with RunSupport {

  /** A `val` at pointer type — the file-scope register block (`reference/modules.md § val — a thing`).
   *
   * What a `val` promises is that its **own storage** is written once and never again, and holding
   * an address keeps that promise exactly as holding a number does. It is not a promise about what
   * the address reaches, and it could not be: slicing a `val` and writing `&v[0]` already yields a
   * writable `*T` on purpose (`reference/arrays.md § []const T — a view that may not be written`),
   * so refusing one here declined a route to what another route grants.
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

    // A tuple is a struct with its fields named for their positions, so whatever a struct field may
    // be, a part may be — a raw pointer, which owns nothing, and a reference, which is a count the
    // storage takes and never gives back. Both are asked, because the pair used to be the
    // discriminating one and is now the recursive half of one rule reaching two ways.
    "a tuple with a pointer in it, and one with a reference" in {
      run(
        """extern malloc(n: usize) -> *u8
          |mk() -> (int, *u8) = (1, malloc(8))
          |static val t: (int, *u8) = mk()
          |print(str(t.0))""".stripMargin,
      ) shouldBe "1\n"

      run(
        """struct Node
          |    v: int
          |end Node
          |mk() -> (int, &Node) = (1, Node(2))
          |static val t: (int, &Node) = mk()
          |print(t.0, t.1.v)""".stripMargin,
      ) shouldBe "1 2\n"
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

    // `reference/modules.md § val — a thing` puts a constant tree in the object file and orders nothing, and an address written as
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

    // **A repeat collapses and the literal above it does not, and the asymmetry is the point.** A
    // fill says one value and a count, so writing the count out is work proportional to a number
    // the source never spelled -- that is card 0319, where a 16 MiB `[0u8; N]` became 100 MB of
    // module text. An array *literal* already has its elements in the source, so there is nothing to
    // save and the line above goes on naming each one.
    //
    // `zeroinitializer` at a pointer type is the null pointer, which is why this is a spelling
    // change and not a behaviour one -- the trait-pointer case two tests up already expected that
    // word for a null. The claim in the name is unchanged and is what the assertion still makes:
    // the global is written out, so there is no loop anywhere to run.
    "and a repeat of one, written out as a global has no loop to run" in {
      val m = ir("static val ps: [3]*u8 = [null; 3]\nprint(\"ok\")")

      m should include("@ps = private constant [3 x ptr] zeroinitializer")
      m should not include "fill.test"
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
    // a cycle (`reference/modules.md § val — a thing`, "what order the initializers run in").
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
    // down, not about what a value inside it addresses. `reference/arrays.md § []const T — a view
    // that may not be written` says the same thing from the other side, and the test below is what
    // makes this one a claim rather than an assertion about an implementation.
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
}
