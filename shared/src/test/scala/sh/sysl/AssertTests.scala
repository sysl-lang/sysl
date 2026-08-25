package sh.sysl

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** `@assert` — a condition settled while compiling.
 *
 * `require` is a runtime precondition (`17`), so until this there was no way to fail a build on a
 * fact the compiler already knew. What wants one most is a binding: sysl lays a struct out in
 * declaration order and claims C compatibility by construction (`reference/types.md § Structs`),
 * and from inside sysl that claim could not be checked, because `sizeof` reports what sysl laid out
 * rather than what the header says. Paired with a `_Static_assert` in a `.c` beside it, the two pin
 * both sides to one number.
 */
class AssertTests extends AnyFreeSpec with Matchers with CodegenSupport with RunSupport {

  "a true assertion emits nothing and gets out of the way" - {
    "over literals" in {
      run(
        """@assert(1 + 1 == 2)
          |print(1)""".stripMargin
      ) shouldBe "1\n"
    }

    // The window matters: asserts are settled after every constant has folded, so the condition may
    // name one wherever it was written — including below.
    "naming a const declared below it" in {
      run(
        """@assert(capacity == 512)
          |const capacity: usize = 512
          |print(1)""".stripMargin
      ) shouldBe "1\n"
    }

    "over sizeof, which is the case it exists for" in {
      run(
        """struct FRect
          |    x: f32
          |    y: f32
          |    w: f32
          |    h: f32
          |end FRect
          |@assert(sizeof(FRect) == 16, "FRect must match SDL_FRect")
          |print(1)""".stripMargin
      ) shouldBe "1\n"
    }

    "over alignof" in {
      run(
        """@assert(alignof(i32) == 4)
          |print(1)""".stripMargin
      ) shouldBe "1\n"
    }

    // `sizeof` alone pins the total, which catches a field that changed width and one that was
    // added. It says nothing about *order*, so this is the half that catches a transposition — the
    // failure that leaves the size right and every read wrong.
    "over offsetof, which is what tells a transposition from a match" in {
      run(
        """struct Header
          |    tag: u8
          |    length: u32
          |    flags: u16
          |end Header
          |@assert(offsetof(Header, tag) == 0)
          |@assert(offsetof(Header, length) == 4, "the padding after 'tag' is what puts it at 4")
          |@assert(offsetof(Header, flags) == 8)
          |@assert(sizeof(Header) == 12)
          |print(1)""".stripMargin
      ) shouldBe "1\n"
    }

    // The same fields packed: no interior padding, so every offset is the sum of the widths before
    // it. That is the shape a register block and a wire header have, and the one where a mirror is
    // most worth checking.
    "over offsetof on a packed struct, where the offsets are the widths before" in {
      run(
        """@packed
          |struct Wire
          |    tag: u8
          |    length: u32
          |    flags: u16
          |end Wire
          |@assert(offsetof(Wire, tag) == 0)
          |@assert(offsetof(Wire, length) == 1)
          |@assert(offsetof(Wire, flags) == 5)
          |print(1)""".stripMargin
      ) shouldBe "1\n"
    }

    // The transposition itself: two same-width fields swapped in the mirror leave `sizeof` at 12 and
    // every read wrong. This is the assertion that fires where the size assertion does not.
    "and a transposition of two same-width fields is what it catches" in {
      val e = err(
        """struct Status
          |    state: u32
          |    priority: u8
          |    runtime: u32
          |end Status
          |@assert(sizeof(Status) == 12)
          |@assert(offsetof(Status, state) == 8, "Status.state moved")
          |print(1)""".stripMargin
      )

      e should include("assertion failed: Status.state moved")
    }

    "several, each settled on its own" in {
      run(
        """@assert(sizeof(u8) == 1)
          |@assert(sizeof(u16) == 2)
          |@assert(sizeof(u32) == 4)
          |print(1)""".stripMargin
      ) shouldBe "1\n"
    }
  }

  "a false assertion stops the compilation" - {
    // The reader's own sentence, because they know what the number means and the expression alone
    // says only that two of them differ.
    "quoting the message where there is one" in {
      err(
        """struct FRect
          |    x: f32
          |    y: f32
          |end FRect
          |@assert(sizeof(FRect) == 16, "FRect must match SDL_FRect")
          |print(1)""".stripMargin
      ) should include("assertion failed: FRect must match SDL_FRect")
    }

    "and saying so plainly where there is not" in {
      err(
        """@assert(1 == 2)
          |print(1)""".stripMargin
      ) should include("assertion failed")
    }
  }

  /** The number the compiler was holding at the moment it said the number was wrong.
   *
   * It folds both sides in order to decide the comparison, and used to throw the result away — so
   * recovering it meant editing the literal and rebuilding until the message stopped, which is a
   * bisection over builds for a fact that was already in hand.
   */
  "a failed comparison says what the sides actually were" - {
    "naming the computed side" in {
      val msg = err(
        """struct FRect
          |    x: f32
          |    y: f32
          |end FRect
          |@assert(sizeof(FRect) == 16, "FRect must match SDL_FRect")
          |print(1)""".stripMargin
      )

      msg should include("assertion failed: FRect must match SDL_FRect")
      msg should include("the left side is 8")
    }

    // The literal is on screen directly above the message, so repeating it back teaches nothing.
    "and not the side the reader wrote as a literal" in {
      err(
        """struct FRect
          |    x: f32
          |    y: f32
          |end FRect
          |@assert(sizeof(FRect) == 16)
          |print(1)""".stripMargin
      ) should not include "the right side"
    }

    "both, where both were computed" in {
      val msg = err(
        """struct Wide
          |    a: u64
          |end Wide
          |struct Narrow
          |    a: u8
          |end Narrow
          |@assert(sizeof(Wide) == sizeof(Narrow))
          |print(1)""".stripMargin
      )

      msg should include("the left side is 8")
      msg should include("the right side is 1")
    }

    // A negative bound parses as a unary minus over a literal, and is still something the reader
    // wrote rather than something folding produced.
    "treating a written negative as the literal it is" in {
      err(
        """const limit: int = 3
          |@assert(limit == -1)
          |print(1)""".stripMargin
      ) should not include "the right side"
    }

    // The order the two notes compose in: what the sides were, then which instantiation asked.
    "alongside the instantiation note, not instead of it" in {
      val msg = err(
        """slab[T](x: T) -> int
          |    @assert(sizeof(T) >= 8, "too narrow")
          |    3
          |
          |print(slab(1u8))""".stripMargin
      )

      msg should include("the left side is 1")
      msg should include("where T = byte")
    }

    // A condition that is not a comparison has no operand worth naming: the thing that folded to
    // `false` is its only one, and the message has already said it is false.
    "and adds nothing to a condition that is not a comparison" in {
      val msg = err(
        """const enabled: bool = false
          |@assert(enabled, "the feature has to be on")
          |print(1)""".stripMargin
      )

      msg should include("assertion failed: the feature has to be on")
      msg should not include "side is"
    }
  }

  /** The parentheses are what make this a declaration rather than an annotation about the one under
   * it, so leaving them off falls out of this rule and into `attributedDecl` — whose refusal
   * enumerates every annotation the language has and does not include this one. A reader comparing
   * their line against that roster concludes there is no `@assert`, which is the opposite of true.
   */
  "the parentheses left off are answered as the bracket they are" - {
    "at a declaration's position" in {
      val message = err(
        """struct S
          |    a: u64
          |
          |@assert sizeof(S) == 8
          |print(1)""".stripMargin
      )

      message should include("takes its condition in parentheses")
      message should include("@assert(sizeof(T) == 16)")
    }

    "inside a body, where it is a statement" in {
      err(
        """main()
          |    @assert 1 + 1 == 2
          |    print(1)""".stripMargin
      ) should include("takes its condition in parentheses")
    }

    // The defect in one line: the roster is what the reader used to get, and it is exhaustive-looking
    // and wrong. A word that genuinely is not an annotation must still get it.
    "and the roster is kept for a word that really is not one" in {
      val message = err(
        """@wibble
          |f() -> int = 1
          |print(f())""".stripMargin
      )

      message should include("'wibble' is not an annotation")
      message should not include "takes its condition in parentheses"
    }

    "which the parenthesised form is not touched by" in {
      run(
        """struct S
          |    a: u64
          |
          |@assert(sizeof(S) == 8)
          |print(1)""".stripMargin
      ) shouldBe "1\n"
    }
  }

  "what it refuses" - {
    // A call is the line `reference/modules.md § const — a value` draws around a constant
    // expression, and it is the line that makes this feature necessary rather than redundant: a
    // shim accessor is a call, so a C library's constants cannot be `const`s, which is why they
    // have to be written down and checked.
    "a condition that is not a constant expression" in {
      err(
        """f() -> int = 3
          |@assert(f() == 3)
          |print(1)""".stripMargin
      ) should include("has to be a constant expression")
    }

    "a condition that is not a boolean" in {
      err(
        """@assert(1 + 1)
          |print(1)""".stripMargin
      ) should include("an assertion is something that can be true or false")
    }

    "a val, which is read while the program runs" in {
      err(
        """val limit: int = 10
          |@assert(limit == 10)
          |print(1)""".stripMargin
      ) should include("has to be a constant expression")
    }

    // A misspelled field is the mistake this form exists to catch, in miniature: being told nothing
    // about a name that is not there — or being told only that the condition did not fold — would be
    // the same silent pass the whole feature is against. It names what the struct does store.
    "a field the struct does not have, by name" in {
      val e = err(
        """struct Header
          |    tag: u8
          |    length: u32
          |end Header
          |@assert(offsetof(Header, len) == 4)
          |print(1)""".stripMargin
      )

      e should include("has no field 'len'")
      e should include("'tag'")
      e should include("'length'")
    }

    "an operand that is not a struct at all" in {
      err(
        """@assert(offsetof(u32, x) == 0)
          |print(1)""".stripMargin
      ) should include("'offsetof' measures a field of a struct")
    }
  }

  // Two paths reach a check, and they must not overlap. In a module file an assert is a
  // declaration, collected by hoisting and settled by the walk; in the entry file it is a statement
  // (`13 §7`) and is settled where it is met. One assert must produce one message, not two.
  "in a module file" - {
    "a true one is silent" in {
      runOf(
        "m.sysl" ->
          """module m
            |const capacity: usize = 512
            |@assert(capacity == 512)""".stripMargin,
        "main.sysl" -> "print(1)",
      ) shouldBe "1\n"
    }

    "a false one is reported exactly once" in {
      val msg = errOf(
        "m.sysl" ->
          """module m
            |@assert(1 == 2, "arithmetic broke")""".stripMargin,
        "main.sysl" -> "print(1)",
      )

      msg should include("assertion failed: arithmetic broke")
      msg.sliding("assertion failed".length).count(_ == "assertion failed") shouldBe 1
    }
  }

  /** Inside a generic, where the interesting facts are per instantiation rather than per declaration.
   *
   * A body is analyzed once for each set of arguments, so the condition is settled against the types
   * that were actually chosen — which is the only moment `sizeof(T)` is a number. This is what
   * `guide/slab` wanted and had to write as a `require`: a runtime branch for a fact that was known
   * at the call.
   */
  "inside a generic body, settled once per instantiation" - {
    "a claim the argument satisfies is silent" in {
      run(
        """slab[T](x: T) -> int
          |    @assert(sizeof(T) >= sizeof(*u8), "a free block has to hold the link through it")
          |    3
          |
          |print(slab(1u64))""".stripMargin
      ) shouldBe "3\n"
    }

    // The whole point: the same generic, an argument too narrow, and a build that stops. Before this
    // the condition reported `unknown type 'T'` — about a parameter declared one line above.
    "and one it does not stops the compilation" in {
      val msg = err(
        """slab[T](x: T) -> int
          |    @assert(sizeof(T) >= sizeof(*u8), "a free block has to hold the link through it")
          |    3
          |
          |print(slab(1u8))""".stripMargin
      )

      msg should include("assertion failed: a free block has to hold the link through it")
      msg should not include "unknown type"
    }

    // The mistake is at the call that chose `u8` while the sentence explaining why is at the
    // declaration, so a report carrying only one of the two sends the reader to the wrong file.
    "naming which instantiation asked" in {
      err(
        """slab[T](x: T) -> int
          |    @assert(sizeof(T) >= 8, "too narrow")
          |    3
          |
          |print(slab(1u8))""".stripMargin
      ) should include("where T = byte")
    }

    // One generic, two arguments, one of them bad: the good instantiation says nothing and the bad
    // one is named, which is what makes the note worth printing at all.
    "one instantiation may fail while another passes" in {
      val msg = err(
        """slab[T](x: T) -> int
          |    @assert(sizeof(T) >= 8, "too narrow")
          |    3
          |
          |print(slab(1u64))
          |print(slab(1u16))""".stripMargin
      )

      msg should include("where T = ushort")
      msg should not include "where T = ulong"
    }


    // A concrete type's members carry a `Self` binding too, resolved once at hoist rather than per
    // call — so the note must not announce an *instantiation* to somebody looking at a plain struct
    // that nothing instantiated. Naming the binding is true either way, and in an inherited default
    // body — one text shared by every implementing type — it is the whole of what the reader needs.
    "a concrete type's member names its Self without claiming to have been instantiated" in {
      val msg = err(
        """struct Point
          |    x: int
          |    y: int
          |
          |    check(self) -> int
          |        @assert(sizeof(Point) == 4, "Point must be four bytes")
          |        1
          |end Point
          |
          |var p = Point(1, 2)
          |print(p.check())""".stripMargin
      )

      msg should include("assertion failed: Point must be four bytes")
      msg should include("where Self = Point")
      msg should not include "instantiation"
    }

    // A value parameter is bound the same way, so a claim about one is settled the same way — and
    // this is the shape that catches a table too large for the storage it is going into, which is
    // the other half of what a bounded generic wants to say.
    "over a value parameter, which is inferred from the argument's length" in {
      val msg = err(
        """buffer[const N: usize](xs: [N]int) -> usize
          |    @assert(N <= 4, "the scratch buffer is a stack array")
          |    N
          |
          |var small: [2]int = [1, 2]
          |var big: [8]int = [1, 2, 3, 4, 5, 6, 7, 8]
          |print(buffer(small))
          |print(buffer(big))""".stripMargin
      )

      msg should include("assertion failed: the scratch buffer is a stack array")
      msg should include("where N = 8")
    }

    // A generic nothing calls carries an unchecked claim, and that is the same deferral
    // `[sizeof(T)]u8` already lives under: the width is not wrong, it is not being measured yet.
    // What must NOT happen is the walk over the declaration reporting "not a constant expression".
    "a generic nothing instantiates is not complained about" in {
      run(
        """slab[T](x: T) -> int
          |    @assert(sizeof(T) >= 8, "too narrow")
          |    3
          |
          |print(1)""".stripMargin
      ) shouldBe "1\n"
    }

    // The deferral is about a type that will become concrete, and nothing else: a condition that
    // could never fold is still refused inside a generic, exactly as it is outside one.
    "and a condition that will never be constant is still refused there" in {
      err(
        """f() -> int = 3
          |
          |slab[T](x: T) -> int
          |    @assert(f() == 3)
          |    3
          |
          |print(slab(1u64))""".stripMargin
      ) should include("has to be a constant expression")
    }
  }

}
