package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** `Zero` and `One` at the integers — the first compiler-provided membership whose member has **no
 * receiver** (`14 §5`).
 *
 * `Signed` and `Bits` are provided over the same open family and are reached from a value, so what
 * they lower to is read off that value. An identity has no value in hand: the whole situation is
 * that the accumulator does not exist yet, which is why the member is reached through the type.
 * What is pinned here is that the constant arrives at the receiver's own width, that a bound naming
 * either trait now takes an integer, and that the two types deliberately left out — a constrained
 * subtype and a vector — are still refused.
 */
class ZeroOneTests extends AnyFreeSpec with RunSupport with CodegenSupport {

  private val sum =
    """sum[T: Add + Zero](xs: []T) -> T
      |    var total = T.zero()
      |    for i in 0..<xs.len
      |        total = total + xs[i]
      |    total
      |end sum
      |""".stripMargin

  "reached through the type" - {

    "an integer answers both identities" in {
      run("print(int.zero(), int.one())\n") shouldBe "0 1\n"
    }

    // The constant is the *receiver type's*, not `int`'s, and the wrap is what says so: at `u8` the
    // sum is 0, and at any wider type it would be 256.
    "the constant takes the width it was named at" in {
      run("var top: u8 = 255\nprint(u8.one() + top)\n") shouldBe "0\n"
    }

    // The argument for a provided membership in one line: `u12` and `i5` are types a program may
    // name, so no list of `impl` blocks in the library could have covered them.
    "reaches widths no impl block could have been written for" in {
      run("var a: u12 = u12.zero()\nvar b: i5 = i5.one()\nprint(a == 0, b == 1)\n") shouldBe "true true\n"
    }

    "a float still reaches the written impl beside it" in {
      run("print(real.zero() == 0.0, real.one() == 1.0, f32.one() == 1.0f32)\n") shouldBe "true true true\n"
    }
  }

  "a bound naming either trait" - {

    // The probe the card was blocked on: `x + T.one()` needs the `1` to be a `T`, and until the
    // membership existed nothing generic over integers could make one.
    "takes an integer" in {
      run("bump[T: Add + One](x: T) -> T = x + T.one()\n\nprint(bump(3))\n") shouldBe "4\n"
    }

    "accumulates from the identity over integers and over floats alike" in {
      run(sum + "\nprint(sum([1, 2, 3]), sum([1.5, 2.5]) == 4.0)\n") shouldBe "6 true\n"
    }

    "the empty sequence answers the identity itself" in {
      run(sum + "\nvar none: []int = []\nprint(sum(none))\n") shouldBe "0\n"
    }

    "a product starts from one" in {
      run("""product[T: Mul + One](xs: []T) -> T
            |    var acc = T.one()
            |    for i in 0..<xs.len
            |        acc = acc * xs[i]
            |    acc
            |end product
            |
            |print(product([1, 2, 3, 4]))
            |""".stripMargin) shouldBe "24\n"
    }

    // `14 §5`'s promise, at the one membership that could most easily have broken it: an identity is
    // a literal at the instantiation, so the accumulator costs no call and nothing is emitted for
    // the trait.
    "lowers to the literal, not to a call" in {
      ir(sum + "\nprint(sum([1, 2, 3]))\n") should not include "Zero.zero"
    }
  }

  "what is deliberately not a member" - {

    // Every other row of the table promises an operation, and `16 §3` gives a subtype its base's
    // operations. These promise a value, and a range written to exclude zero has not got one.
    "a constrained subtype, whose range need not hold the value" in {
      err("""type Small = int within 1..10
            |
            |first[T: Zero](x: T) -> T = T.zero()
            |
            |var s: Small = 5
            |print(first(s))
            |""".stripMargin) should include("'sysl.Zero'")
    }

    "a vector, which has lanes the member names none of" in {
      err("""first[T: Zero](x: T) -> T = T.zero()
            |
            |val v: <4>i32 = [1, 2, 3, 4]
            |print(first(v)[0])
            |""".stripMargin) should include("'sysl.Zero'")
    }
  }

  // The cases a person reaches that a bound and a bare type name do not cover between them.
  "the other spellings of the type" - {

    "`Self` inside a member's body" in {
      run("""trait Counter
            |    start() -> Self
            |
            |impl Counter for u16
            |    start() -> u16 = Self.zero()
            |
            |print(u16.start())
            |""".stripMargin) shouldBe "0\n"
    }

    "a transparent alias, which is its base type" in {
      run("type Word = u32\n\nvar w: Word = Word.one()\nprint(w)\n") shouldBe "1\n"
    }

    // The literal is a constant, so it reaches the static emitter rather than a function body — a
    // path a `TCall` could not have taken at all.
    "module storage, which is initialized before anything runs" in {
      run("val base: usize = usize.zero()\n\nprint(base, base + 1)\n") shouldBe "0 1\n"
    }

    "a width past what the mixer and the machine word hold" in {
      run("var big: u128 = u128.one()\nprint(big == 1, u128.zero() == 0)\n") shouldBe "true true\n"
    }
  }

  "the refusals" - {

    "an argument is refused, the member taking none" in {
      err("print(int.zero(1))\n") should include("takes no arguments")
    }

    // The membership is the compiler's, so a block for one is competing with what is already there
    // rather than adding anything — the same complaint `impl Add for int` gets.
    "an impl block for an integer is refused as already provided" in {
      err("""impl Zero for int
            |    zero() -> int = 0
            |
            |print(1)
            |""".stripMargin) should include("already implements")
    }

    // The same complaint a written `impl`'s associated function gets, since the two are the same
    // mistake: `real.zero` is refused here too and this is not a case the membership invents.
    "reading it without the parentheses says what a float's read says" in {
      val provided = err("print(int.zero)\n")
      val written  = err("print(real.zero)\n")

      provided should include("zero")
      written should include("zero")
    }

    "a name the trait does not declare is still missing" in {
      err("print(int.two())\n") should include("associated function")
    }
  }
}
