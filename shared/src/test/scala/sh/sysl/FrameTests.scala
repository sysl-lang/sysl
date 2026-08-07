package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** `@reads` and `@writes` — which module storage a function may touch (`17 §7`).
  *
  * The feature is three rules, so the tests are the three plus the neighbouring case for each: what
  * each refuses, and the thing one step away from it that it must not. Two of those neighbours carry
  * most of the design and are worth naming here, because a check that got either backwards would
  * still pass every refusal below:
  *
  *   - **`@writes(v)` alone permits reading `v`.** `count += 1` is a read and a write of one
  *     variable, and a form that common should not need both annotations to say one thing.
  *   - **Writing any part of a global is writing the global.** After `buf[i] = b` the variable holds
  *     something different, so a frame that named `buf` only under `@reads` has not covered it.
  *
  * The unannotated case is tested as an acceptance rather than left implicit: adoption from the
  * leaves up depends on a function with no frame being undisturbed, and a check that defaulted the
  * unannotated to an empty frame would break every existing program while passing every test here.
  */
class FrameTests extends AnyFreeSpec with RunSupport with CodegenSupport {

  "what a frame permits" - {

    "reading a variable it names" in {
      run("""static var limit: int = 7
            |
            |@reads(limit)
            |ceiling() -> int = limit
            |
            |print(ceiling())
            |""".stripMargin) shouldBe "7\n"
    }

    "writing a variable its '@writes' names" in {
      run("""static var count: int = 0
            |
            |@writes(count)
            |bump()
            |    count = count + 1
            |
            |bump()
            |bump()
            |print(count)
            |""".stripMargin) shouldBe "2\n"
    }

    // `W` is inside the readable set on purpose (`17 §7`), which is what makes this one annotation
    // rather than two saying the same thing.
    "reading a variable that only its '@writes' names" in {
      run("""static var count: int = 0
            |
            |@writes(count)
            |bump()
            |    count += 1
            |
            |bump()
            |bump()
            |bump()
            |print(count)
            |""".stripMargin) shouldBe "3\n"
    }

    "an empty frame on a function that touches no module storage" in {
      run("""@reads()
            |@writes()
            |double(x: int) -> int = x * 2
            |
            |print(double(21))
            |""".stripMargin) shouldBe "42\n"
    }

    "calling a '@pure' function, which is inside every frame there is" in {
      run("""static var count: int = 0
            |
            |@pure
            |twice(x: int) -> int = x * 2
            |
            |@writes(count)
            |bump()
            |    count = twice(count) + 1
            |
            |bump()
            |bump()
            |print(count)
            |""".stripMargin) shouldBe "3\n"
    }

    "calling a framed function whose frame fits inside this one" in {
      run("""static var count: int = 0
            |static var limit: int = 10
            |
            |@writes(count)
            |bump()
            |    count += 1
            |
            |@reads(limit)
            |@writes(count)
            |bump_if_room()
            |    if count < limit then bump()
            |
            |bump_if_room()
            |bump_if_room()
            |print(count)
            |""".stripMargin) shouldBe "2\n"
    }

    // Adoption runs from the leaves up (`17 §7`), so a function that never said anything is held to
    // nothing and may still call and be called by anything.
    "an unannotated function doing whatever it likes" in {
      run("""static var count: int = 0
            |
            |noisy()
            |    count += 1
            |    print(count)
            |
            |noisy()
            |noisy()
            |""".stripMargin) shouldBe "1\n2\n"
    }

    "an unannotated function calling a framed one" in {
      run("""static var count: int = 0
            |
            |@writes(count)
            |bump()
            |    count += 1
            |
            |anything()
            |    bump()
            |    print(count)
            |
            |anything()
            |""".stripMargin) shouldBe "1\n"
    }

    // `17 §7` said a frame could reach only the entry file's `static var`, because that was the only
    // mutable module storage there was when it was written. `13 §7`'s module `var` arrived after,
    // and a frame is resolved through the same lookup a body's own reference is — so it reaches
    // both, and this is the case that says so.
    "a variable declared with 'var' in a module of its own" in {
      runOf(
        "counter.sysl" ->
          """module counter
            |
            |var count: int = 0
            |
            |@writes(count)
            |bump()
            |    count += 1
            |
            |@reads(count)
            |value() -> int = count
            |""".stripMargin,
        "main.sysl" ->
          """import counter
            |
            |counter.bump()
            |counter.bump()
            |print(counter.value())
            |""".stripMargin,
      ) shouldBe "2\n"
    }

    "a local of the same name as a variable the frame does not have" in {
      run("""static var count: int = 0
            |
            |@reads(count)
            |peek() -> int
            |    var scratch = count
            |    scratch += 1
            |    scratch
            |
            |print(peek())
            |""".stripMargin) shouldBe "1\n"
    }
  }

  "what a frame refuses" - {

    "reading a variable neither half names" in {
      err("""static var count: int = 0
            |static var limit: int = 10
            |
            |@reads(count)
            |f() -> int = limit
            |
            |print(f())
            |""".stripMargin) should include("reads 'limit'")
    }

    "writing a variable its '@reads' names but its '@writes' does not" in {
      err("""static var count: int = 0
            |
            |@reads(count)
            |f()
            |    count = 1
            |
            |f()
            |""".stripMargin) should include("writes 'count'")
    }

    // The rule is about the *storage*, not about the name being mentioned: after this the variable
    // holds something different, which is the only thing a prover is asking.
    "writing an element of an array its '@writes' does not name" in {
      err("""static var buf: [4]u8
            |static var pos: usize = 0
            |
            |@reads(buf)
            |@writes(pos)
            |put(b: u8)
            |    buf[pos] = b
            |    pos += 1
            |
            |put(65u8)
            |""".stripMargin) should include("writes 'buf'")
    }

    "calling a function that has no frame at all" in {
      err("""static var count: int = 0
            |
            |noisy()
            |    count += 1
            |
            |@writes(count)
            |f()
            |    noisy()
            |
            |f()
            |""".stripMargin) should include("has no '@reads' or '@writes'")
    }

    "calling a framed function that writes something this one does not" in {
      err("""static var a: int = 0
            |static var b: int = 0
            |
            |@writes(b)
            |touch_b()
            |    b += 1
            |
            |@writes(a)
            |f()
            |    touch_b()
            |
            |f()
            |""".stripMargin) should include("which writes 'b'")
    }

    "calling a framed function that reads something this one does not" in {
      err("""static var a: int = 0
            |static var b: int = 0
            |
            |@reads(b)
            |peek_b() -> int = b
            |
            |@writes(a)
            |f()
            |    a = peek_b()
            |
            |f()
            |""".stripMargin) should include("which reads 'b'")
    }

    "calling an extern, which says nothing about what it touches" in {
      err("""extern abs(x: int) -> int
            |
            |static var count: int = 0
            |
            |@writes(count)
            |f()
            |    count = abs(count)
            |
            |f()
            |""".stripMargin) should include("'extern'")
    }

    "reading a variable from inside its own contract" in {
      err("""static var count: int = 0
            |static var limit: int = 10
            |
            |@writes(count)
            |bump()
            |    require count < limit
            |    count += 1
            |
            |bump()
            |""".stripMargin) should include("reads 'limit'")
    }
  }

  "what the annotation itself refuses" - {

    "'@pure' beside a frame, which says one thing twice" in {
      err("""static var count: int = 0
            |
            |@pure
            |@writes(count)
            |f()
            |    count += 1
            |
            |f()
            |""".stripMargin) should include("says one thing twice")
    }

    "a name that is not module storage" in {
      err("""@reads(nothing)
            |f() -> int = 1
            |
            |print(f())
            |""".stripMargin) should include("not module-level storage")
    }

    // The message is raised inside the parentheses rather than after them, so that it beats the
    // "not an annotation" alternative on position rather than losing to it.
    "a frame written without its parentheses" in {
      err("""static var count: int = 0
            |
            |@writes
            |f()
            |    count += 1
            |
            |f()
            |""".stripMargin) should include("in parentheses")
    }

    "the same half written twice" in {
      err("""static var a: int = 0
            |static var b: int = 0
            |
            |@writes(a)
            |@writes(b)
            |f()
            |    a += 1
            |
            |f()
            |""".stripMargin) should include("written twice")
    }
  }
}
