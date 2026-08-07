package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** `T::Min` and `T::Max` on a built-in integer type — the bounds a `within` subtype has answered
 * since `16 §5` while the integer it is declared over answered nothing.
 *
 * The wide cases carry the point of the feature. A program can write `4294967295` for a `u32` and
 * cannot write the largest `u10000` at all, so for a wide member of the open family the attribute is
 * the only way to name a value the type obviously has.
 */
class IntegerAttrRunTests extends AnyFreeSpec with RunSupport {

  "the common widths report their bounds" - {
    "unsigned starts at zero" in {
      run("print(u8::Min, u8::Max)") shouldBe "0 255\n"
    }
    "signed is asymmetric, one further down than up" in {
      run("print(i8::Min, i8::Max)") shouldBe "-128 127\n"
    }
    "u32 and i32" in {
      run("print(u32::Max)") shouldBe "4294967295\n"
      run("print(i32::Min, i32::Max)") shouldBe "-2147483648 2147483647\n"
    }
    "u64 reaches the width a literal still can" in {
      run("print(u64::Max)") shouldBe "18446744073709551615\n"
    }
  }

  "the friendly aliases answer as the widths they stand for" - {
    "int is i32" in {
      run("print(int::Min, int::Max)") shouldBe "-2147483648 2147483647\n"
    }
    "byte is u8" in {
      run("print(byte::Min, byte::Max)") shouldBe "0 255\n"
    }
    "usize is pointer-width, so only its floor is fixed across targets" in {
      run("print(usize::Min)") shouldBe "0\n"
    }
  }

  "an odd width is not a special case" - {
    "u3 holds seven" in {
      run("print(u3::Min, u3::Max)") shouldBe "0 7\n"
    }
    "i5 is asymmetric at its own width" in {
      run("print(i5::Min, i5::Max)") shouldBe "-16 15\n"
    }
    "u1 is the narrowest the family admits" in {
      run("print(u1::Min, u1::Max)") shouldBe "0 1\n"
    }
  }

  "a width no literal could spell" - {
    // 1146! is 10000 bits to the bit, which is what `test/factorial.sysl` is about. The largest
    // `u10000` is 3,011 digits, so this attribute is the only access to it.
    "u10000's maximum is 2^10000 - 1, to the digit" in {
      run("print(u10000::Max)") shouldBe ((BigInt(1) << 10000) - 1).toString + "\n"
    }
    "and its minimum is still zero" in {
      run("print(u10000::Min)") shouldBe "0\n"
    }
    "a signed one of the same width is one bit narrower in magnitude" in {
      // Not written as `i10000::Max < u10000::Max`: comparing across signedness is refused, which
      // is the language's rule and not this feature's business.
      run("print(i10000::Max)") shouldBe ((BigInt(1) << 9999) - 1).toString + "\n"
      run("print(i10000::Min)") shouldBe (-(BigInt(1) << 9999)).toString + "\n"
    }
  }

  "the bounds are constants, not calls" - {
    // `13 §5` admits no call in a `const` initializer, so an attribute that resolved as one would be
    // unusable in the two places bounds are most wanted. This is the test that pins that.
    "a const initializer folds one" in {
      run("const LIMIT: u16 = u16::Max\nprint(LIMIT)") shouldBe "65535\n"
    }
    "arithmetic over them folds too" in {
      run("const HALF: u32 = u32::Max / 2\nprint(HALF)") shouldBe "2147483647\n"
    }
    "an @assert reads one at compile time" in {
      run("@assert(u8::Max == 255)\nprint(1)") shouldBe "1\n"
    }
    "one sizes an array" in {
      run("val a: [u3::Max]u8 = [0u8; 7]\nprint(a.len)") shouldBe "7\n"
    }
  }

  "through a type parameter" - {
    // The generic case is what makes bounds usable by the library rather than only by a program:
    // bounded narrowing, integer parsing, saturating arithmetic and a min/max reduction's identity
    // all want `T`'s extreme. Whether an attribute reaches through a parameter is undecided — `10`
    // never mentions attributes and no chapter refuses them either.
    // Refused today: `'::Max' is a type attribute, so its left side must be a type name`. The
    // attribute is resolved against the written scalar names, and a parameter is not one of them —
    // `sizeof(T)` reaches its argument only because that argument parses as a *type* and is resolved
    // through the instantiation's substitution. There is no ambient substitution at an expression,
    // so reaching one here is a change rather than an oversight, and which way it should go is the
    // decision the ticket records.
    "a generic body reads the argument's maximum" ignore {
      run("widest[T]() -> T = T::Max\nval x: u8 = widest()\nprint(x)") shouldBe "255\n"
    }
  }

  "they answer in the type they are the bounds of" - {
    "a maximum assigns to its own width without a cast" in {
      run("val x: u8 = u8::Max\nprint(x)") shouldBe "255\n"
    }
    "and wraps like any other value of it" in {
      run("var x: u8 = u8::Max\nx += 1u8\nprint(x)") shouldBe "0\n"
    }
  }
}
