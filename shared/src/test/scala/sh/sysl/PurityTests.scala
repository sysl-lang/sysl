package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** `@pure` — a function a caller can observe nothing about but its result (`17 §6`).
 *
 * The whole feature is a list of exclusions, so the tests are the list plus the neighbouring case for
 * each: what is banned, and the thing one step away from it that is not. The two exclusions this
 * design deliberately does **not** make — allocation and trapping — are tested as acceptances, since
 * a check that quietly banned either would pass every refusal test here.
 */
class PurityTests extends AnyFreeSpec with RunSupport with CodegenSupport {

  "what a pure function may do" - {

    "arithmetic over its parameters" in {
      run("""@pure
            |square(x: int) -> int = x * x
            |
            |print(square(7))
            |""".stripMargin) shouldBe "49\n"
    }

    "recurse" in {
      run("""@pure
            |fact(n: int) -> int
            |    require n >= 0
            |    if n <= 1 then 1
            |    else n * fact(n - 1)
            |
            |print(fact(5))
            |""".stripMargin) shouldBe "120\n"
    }

    "call another pure function" in {
      run("""@pure
            |double(x: int) -> int = x * 2
            |
            |@pure
            |quad(x: int) -> int = double(double(x))
            |
            |print(quad(3))
            |""".stripMargin) shouldBe "12\n"
    }

    // Storage the call made is the call's, however deep the path into it goes — which is what makes
    // the rule about the *path* rather than about writing at all.
    "declare and mutate its own locals, including into an array it holds" in {
      run("""@pure
            |sum(a: [4]int) -> int
            |    var b = a
            |    var t = 0
            |
            |    b[0] = 99
            |
            |    for i in 0..<4
            |        t += b[i]
            |
            |    t
            |
            |var xs = [1, 2, 3, 4]
            |print(sum(xs))
            |""".stripMargin) shouldBe "108\n"
    }

    // `17 §6` departs from old sysl here and argues it out: a caller cannot observe an object that
    // did not exist when the call began. A check that banned allocation would refuse this, and every
    // string operation with it.
    "allocate, which is the departure from old sysl" in {
      run("""@pure
            |shout(s: string) -> string = s + "!"
            |
            |print(shout("hi"))
            |""".stripMargin) shouldBe "hi!\n"
    }

    // Termination is the one effect a caller can observe that purity does not exclude, because
    // `16`'s constrained arithmetic can trap and excluding it would exclude those types wholesale.
    "trap" in {
      exits("""type Slot = new u8 within 0..<200
              |
              |@pure
              |narrow(n: int) -> Slot = Slot(u8(n))
              |
              |print(int(narrow(250)))
              |""".stripMargin)
    }

    "carry a contract, and a variant" in {
      run("""@pure
            |half(x: int) -> int
            |    require x >= 0
            |    ensure result >= 0
            |    x / 2
            |
            |print(half(9))
            |""".stripMargin) shouldBe "4\n"
    }

    "be generic" in {
      run("""@pure
            |twice[T: Add](x: T) -> T = x + x
            |
            |print(twice(21))
            |""".stripMargin) shouldBe "42\n"
    }
  }

  "what it may not do" - {

    "call a function that is not pure" in {
      err("""noisy(x: int) -> int = x + 1
            |
            |@pure
            |f(x: int) -> int = noisy(x)
            |
            |print(f(1))
            |""".stripMargin) should include("is not marked '@pure'")
    }

    "perform I/O" in {
      err("""@pure
            |f(x: int) -> int
            |    print(x)
            |    x
            |
            |print(f(1))
            |""".stripMargin) should include("is not marked '@pure'")
    }

    // The caret is the part worth pinning: `print` lowers to a call nobody wrote, so a walk reporting
    // the node's own position lands in the library rather than on the line the reader typed.
    "and the I/O diagnostic points at the call that was written" in {
      err("""@pure
            |f(x: int) -> int
            |    print(x)
            |    x
            |
            |print(f(1))
            |""".stripMargin) should include("print(x)")
    }

    "call an extern" in {
      err("""extern puts(s: *u8) -> int
            |
            |@pure
            |f() -> int
            |    puts(c"hi")
            |    1
            |
            |print(f())
            |""".stripMargin) should include("says nothing about what it does")
    }

    "write through a pointer" in {
      err("""@pure
            |f(p: *int) -> int
            |    *p = 5
            |    1
            |
            |print(f(null))
            |""".stripMargin) should include("storage it did not create")
    }

    // **`TreeWalk` ends in `case _ => Nil`, so a node missing from it hides its own operands.**
    // `TLane` was missing, which made the call inside `g()[0]` invisible to every walk that uses it
    // — this one, the escape analysis and the ARC insertion. The lane read is not the observable
    // thing; the call underneath it is, and the test is that the walk got there at all.
    "call an impure function and read one lane of what it answers" in {
      err("""g() -> <4>f32 = [1.0, 2.0, 3.0, 4.0]
            |
            |@pure
            |f() -> f32 = g()[0]
            |
            |print(f())
            |""".stripMargin) should include("not marked '@pure'")
    }

    "call an impure function inside the index of a load" in {
      err("""g() -> usize = 0
            |
            |@pure
            |f(xs: []const f32) -> f32
            |    val v: <4>f32 = xs.load(g())
            |    v[0]
            |
            |var a: [8]f32
            |print(f(a[..]))
            |""".stripMargin) should include("not marked '@pure'")
    }

    // `xs.store(i, v)` is the one write that is not a `TStore`, so it is listed in `observable`
    // rather than reached by the walk — and a write missing from that list is a `@pure` function
    // that writes.
    "store a vector's lanes into a reference it was handed" in {
      err("""@pure
            |f(a: &[8]f32) -> int
            |    val v: <4>f32 = [1.0, 2.0, 3.0, 4.0]
            |    a.store(0, v)
            |    1
            |
            |var b: &[8]f32
            |print(f(b))
            |""".stripMargin) should include("storage it did not create")
    }

    "write into a field of a reference it was handed" in {
      err("""struct Cell
            |    n: int
            |
            |@pure
            |f(c: &Cell) -> int
            |    c.n = 5
            |    1
            |
            |print(f(Cell(1)))
            |""".stripMargin) should include("storage it did not create")
    }

    "increment through a reference it was handed" in {
      err("""struct Cell
            |    n: int
            |
            |@pure
            |f(c: &Cell) -> int
            |    c.n++
            |    1
            |
            |print(f(Cell(1)))
            |""".stripMargin) should include("storage it did not create")
    }

    // A call through an `Fn` resolves to the closure's own `call` body, which is a real function
    // with a name — so what has to be checked is that the reader is told they called *through a
    // closure*, and not told the name the compiler filed one under.
    "call through a value" in {
      err("""@pure
            |f(g: int -> int) -> int = g(1)
            |
            |print(f(x -> x + 1))
            |""".stripMargin) should include("through a closure")
    }

    "dispatch through a trait object" in {
      err("""trait Speak
            |    say(self) -> int
            |
            |struct Dog
            |end Dog
            |
            |impl Speak for Dog
            |    say(self) -> int = 1
            |
            |@pure
            |f(s: &Speak) -> int = s.say()
            |
            |print(f(Dog()))
            |""".stripMargin) should include("trait object")
    }

    "read a mutable global" in {
      err("""extern optind: i32
            |
            |@pure
            |f() -> int = int(optind)
            |
            |print(f())
            |""".stripMargin) should include("storage outside the call")
    }

    "contain an asm block" in {
      // One arm naming every processor a target can be built for, read from the registry rather than
      // spelled out. Spelled out, this stopped being a test of purity the moment the 32-bit targets
      // arrived: the block was then missing two arms, so the answer was about exhaustiveness and the
      // `@pure` rule was never reached. A fixture whose subject is one rule must not be able to trip
      // another one on its way there.
      val arms = Cpu.buildable.map(_.symbol).mkString("[", ", ", "]")

      err(s"""@pure
             |f() -> int
             |    asm
             |        $arms "nop"
             |
             |    1
             |
             |print(f())
             |""".stripMargin) should include("'asm' block")
    }

    // The clauses are the function's own code and run on every call, so they are held to the same
    // rule the body is — this would otherwise be a pure function that prints.
    "print from inside its own contract" in {
      err("""noisy(x: int) -> bool
            |    print(x)
            |    true
            |
            |@pure
            |f(x: int) -> int
            |    require noisy(x)
            |    x
            |
            |print(f(1))
            |""".stripMargin) should include("is not marked '@pure'")
    }
  }

  "the diagnostics" - {

    // A body with two problems has two places to change and gets two messages; one line that lowers
    // to several observable nodes gets one. `print` is the second case — a call for the value and
    // another for the newline.
    "give one message per line, not one per lowered node" in {
      err("""@pure
            |f(x: int) -> int
            |    print(x)
            |    x
            |""".stripMargin).split("error:").length - 1 shouldBe 1
    }

    // Two mistakes on two lines are two places to change, so they are two messages. The dedupe is
    // by *position* rather than by mistake, which is the trade the case above buys: two problems on
    // one line get one caret between them.
    "and one per distinct mistake, where they are on distinct lines" in {
      err("""noisy(x: int) -> int = x + 1
            |
            |@pure
            |f(p: *int, x: int) -> int
            |    *p = 5
            |    noisy(x)
            |""".stripMargin).split("error:").length - 1 shouldBe 2
    }
  }

  "the annotation itself" - {

    "is refused on anything but a function" in {
      err("""@pure
            |struct Point
            |    x: int
            |""".stripMargin) should include("annotation marks a function")
    }

    "written twice is refused" in {
      err("""@pure
            |@pure
            |f(x: int) -> int = x
            |""".stripMargin) should include("'@pure' is written twice")
    }

    "and an unknown annotation names the three that exist" in {
      err("""@sideways
            |f(x: int) -> int = x
            |""".stripMargin) should include("'@pure'")
    }

    "composes with the others" in {
      run("""@pure
            |@tailrec
            |walk(n: int, acc: int) -> int
            |    if n == 0 then acc
            |    else walk(n - 1, acc + n)
            |
            |print(walk(4, 0))
            |""".stripMargin) shouldBe "10\n"
    }
  }

  "a value may still be called 'pure'" in {
    run("""f() -> int
          |    var pure = 41
          |    pure + 1
          |
          |print(f())
          |""".stripMargin) shouldBe "42\n"
  }
}
