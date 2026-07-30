package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Integer widths above 64 bits. `00 §5` states that `iN` / `uN` is an open family over any
 * positive width and names `i128` among its examples, on the grounds that a width is "a capability
 * of the target, not something sysl emulates" — so the semantics here are not new rules, they are
 * the existing ones at a width that needs two registers.
 *
 * The load-bearing tests are the ones whose value does not fit in 64 bits. A width that merely
 * *parses* proves nothing: every operation on the way to the screen used to narrow to 64 bits, so a
 * `u128` would have printed the low half of itself and looked right for every small value.
 */
class WideIntegerTests extends AnyFreeSpec with CodegenSupport with RunSupport {

  "a width above 64 is a type" - {
    "the width `00 §5` names as its example resolves" in {
      run("var x: i128 = 1\nprint(x)") shouldBe "1\n"
    }

    "so does its unsigned counterpart, and a width between the two registers" in {
      run("var a: u128 = 7\nvar b: u96 = 8\nvar c: i65 = 9\nprint(a, b, c)") shouldBe "7 8 9\n"
    }

    "a width the back end will not lower is still a diagnostic, and names the limit" in {
      err("var x: i129 = 1") should include("128")
    }
  }

  "arithmetic wraps at the declared width, which is the rule at every other width" - {
    "a sum carries past what 64 bits could hold" in {
      run("""var a: u128 = 18446744073709551615
            |print(a + 1)""".stripMargin) shouldBe "18446744073709551616\n"
    }

    "a product of two values that each fit lands somewhere neither does" in {
      run("""var a: u128 = 4294967296
            |print(a * a * a)""".stripMargin) shouldBe "79228162514264337593543950336\n"
    }

    "the unsigned maximum is writable and prints every digit" in {
      run("""var a: u128 = 340282366920938463463374607431768211455
            |print(a)""".stripMargin) shouldBe "340282366920938463463374607431768211455\n"
    }

    "and wraps to zero one past it" in {
      run("""var a: u128 = 340282366920938463463374607431768211455
            |print(a + 1)""".stripMargin) shouldBe "0\n"
    }

    "the signed extremes print unmangled" in {
      run("""var hi: i128 = 170141183460469231731687303715884105727
            |var lo: i128 = -170141183460469231731687303715884105728
            |print(hi)
            |print(lo)""".stripMargin) shouldBe
        "170141183460469231731687303715884105727\n-170141183460469231731687303715884105728\n"
    }

    "a signed minimum negates by wrapping, as it does at 64 bits" in {
      run("""var lo: i128 = -170141183460469231731687303715884105728
            |print(0 - lo)""".stripMargin) shouldBe "-170141183460469231731687303715884105728\n"
    }
  }

  "division and remainder pick the instruction from the signedness" - {
    "an unsigned division of a value with its top bit set is not a negative one" in {
      run("""var a: u128 = 340282366920938463463374607431768211455
            |print(a / 3)
            |print(a % 7)""".stripMargin) shouldBe "113427455640312821154458202477256070485\n3\n"
    }

    "a signed division truncates toward zero at this width too" in {
      run("""var a: i128 = -170141183460469231731687303715884105727
            |print(a / 2)
            |print(a % 10)""".stripMargin) shouldBe
        "-85070591730234615865843651857942052863\n-7\n"
    }

    "the right shift keeps the sign only when the type has one" in {
      run("""var s: i128 = -256
            |var u: u128 = 340282366920938463463374607431768211455
            |print(s >> 4)
            |print(u >> 120)""".stripMargin) shouldBe "-16\n255\n"
    }

    "a left shift moves a bit into the upper half" in {
      run("""var a: u128 = 1
            |print(a << 100)""".stripMargin) shouldBe "1267650600228229401496703205376\n"
    }
  }

  "comparison sees the whole value, not its low half" - {
    "two values that agree in their low 64 bits still order correctly" in {
      run("""var a: u128 = 18446744073709551616
            |var b: u128 = 0
            |print(a > b, a == b, a != b)""".stripMargin) shouldBe "true false true\n"
    }

    "a signed comparison across zero" in {
      run("""var a: i128 = -170141183460469231731687303715884105728
            |var b: i128 = 1
            |print(a < b, a >= b)""".stripMargin) shouldBe "true false\n"
    }
  }

  "conversions are written, and each is the one `01` describes" - {
    "narrowing to 64 bits keeps the low half" in {
      run("""var a: u128 = 18446744073709551617
            |print(u64(a))""".stripMargin) shouldBe "1\n"
    }

    "widening from a signed 64-bit value sign-extends" in {
      run("""var n: i64 = -1
            |var w: i128 = i128(n)
            |print(w)""".stripMargin) shouldBe "-1\n"
    }

    "widening from an unsigned one does not" in {
      run("""var n: u64 = 18446744073709551615
            |var w: u128 = u128(n)
            |print(w)""".stripMargin) shouldBe "18446744073709551615\n"
    }

    // The digits below are `1.5e30`'s own — the nearest `f64` to it is 1499999999999999889089448902656
    // — so this also shows the conversion reaching past 64 bits rather than saturating at one.
    "a float is reachable in both directions" in {
      run("""var a: u128 = 1099511627776
            |print(real(a))
            |print(u128(1.5e30))""".stripMargin) shouldBe "1.09951e+12\n1499999999999999889089448902656\n"
    }
  }

  "rendering agrees with printing, because both go through one routine" - {
    "`str` of a wide value is the digits `print` writes" in {
      run("""var a: u128 = 340282366920938463463374607431768211455
            |print(str(a) == "340282366920938463463374607431768211455")
            |print(str(a).len)""".stripMargin) shouldBe "true\n39\n"
    }

    "a negative one carries its sign into the string" in {
      run("""var a: i128 = -170141183460469231731687303715884105728
            |print(str(a).len, str(a) == "-170141183460469231731687303715884105728")
            |""".stripMargin) shouldBe "40 true\n"
    }

    "interpolation reaches it the same way" in {
      run("""var a: u128 = 18446744073709551616
            |print(s"a=$a")""".stripMargin) shouldBe "a=18446744073709551616\n"
    }
  }

  "a wide integer is stored like any other value of its size" - {
    "as a struct field, read back at full width" in {
      run("""struct Counter
            |    total: u128
            |    step: u64
            |end Counter
            |var c = Counter(340282366920938463463374607431768211455, 3)
            |print(c.total, c.step)""".stripMargin) shouldBe
        "340282366920938463463374607431768211455 3\n"
    }

    "as an array element, so the stride is right" in {
      run("""var xs: [3]u128 = [0; 3]
            |xs[0] = 18446744073709551616
            |xs[1] = 340282366920938463463374607431768211455
            |xs[2] = 1
            |print(xs[0], xs[1], xs[2])""".stripMargin) shouldBe
        "18446744073709551616 340282366920938463463374607431768211455 1\n"
    }

    "inside a data enum, where the payload width is written into the emitted text" in {
      run("""enum Load
            |    Wide(v: u128)
            |    Narrow(v: u8)
            |
            |var l = Wide(340282366920938463463374607431768211455)
            |l match
            |    Wide(v) -> print("wide", v)
            |    Narrow(v) -> print("narrow", v)""".stripMargin) shouldBe
        "wide 340282366920938463463374607431768211455\n"
    }
  }

  "a literal that does not fit is refused, at this width as at every other" in {
    err("var x: u128 = 340282366920938463463374607431768211456") should include("does not fit")
  }

  /** Reaching an element happens at `usize`, and a wider index would have to be truncated to get
   * there. Truncating is what makes a bounds test lie: `2^64 + 5` arrives as 5 and passes on a
   * six-element array, so the program reads an element that the index it wrote does not name. The
   * refusal is the no-implicit-narrowing rule (`01`) reaching one more position.
   */
  "a width past 64 bits is not an index" - {
    "reading an element with one is refused, and the message names the narrowing" in {
      val e = err("""var xs: [6]int = [7; 6]
                    |var i: u128 = 18446744073709551621
                    |print(xs[i])""".stripMargin)
      e should include("wider")
      e should include("usize(i)")
    }

    "so is writing through one, since a place and a read take one selection" in {
      err("""var xs: [6]int = [7; 6]
            |var i: u128 = 1
            |xs[i] = 0""".stripMargin) should include("wider")
    }

    "and so is a slice bound at either end" in {
      err("""var xs: [6]int = [7; 6]
            |var i: u128 = 1
            |var v = xs[i..<3usize]""".stripMargin) should include("wider")
      err("""var xs: [6]int = [7; 6]
            |var i: u128 = 3
            |var v = xs[0usize..<i]""".stripMargin) should include("wider")
    }

    "and a repeat count, which would otherwise size storage by a truncated number" in {
      err("""var n: u128 = 3
            |var xs: []int = [0; n]""".stripMargin) should include("wider")
    }

    "the narrowing written out is ordinary, and lands where it says" in {
      run("""var xs: [6]int = [7; 6]
            |var i: u128 = 5
            |xs[usize(i)] = 42
            |print(xs[usize(i)], xs[0])""".stripMargin) shouldBe "42 7\n"
    }

    "a narrower index still widens with nothing written, which is the other half of the rule" in {
      run("""var xs: [6]int = [7; 6]
            |var i: u8 = 5
            |xs[i] = 42
            |print(xs[i])""".stripMargin) shouldBe "42\n"
    }
  }

  /** The paths that reach a value through a trait rather than through `print`. Each of these used to
   * widen the receiver to 64 bits on the way in, so a wide value rendered or hashed as its low half —
   * right for every value small enough to test with, and wrong for the values the width exists for.
   */
  "the trait surface sees the whole value" - {
    "a `Display` reached through a bound renders every digit" in {
      run("""show[T: Display](x: T) = print(x)
            |var a: u128 = 340282366920938463463374607431768211455
            |show(a)""".stripMargin) shouldBe "340282366920938463463374607431768211455\n"
    }

    "a struct's own `Display` renders a wide field through the sink" in {
      run("""struct Id
            |    v: u128
            |
            |impl Display for Id
            |    display(self, out: *Writer, fmt: FormatSpec) = display_wide(str(self.v), out, fmt)
            |
            |print(Id(340282366920938463463374607431768211455))""".stripMargin) shouldBe
        "340282366920938463463374607431768211455\n"
    }

    // The specifier reaches `display_wide` the way it reaches any renderer that forwards it: through
    // a type that renders itself, since a built-in keeps the strict conversion check. The digits are
    // the language's and the field is the prelude's, which is the split every other width has.
    "and the specifier it is handed pads the digits like any other number's" in {
      run("""struct Id
            |    v: u128
            |
            |impl Display for Id
            |    display(self, out: *Writer, fmt: FormatSpec) = display_wide(str(self.v), out, fmt)
            |
            |var a: u128 = 18446744073709551616
            |print(f"[${Id(a)}%25s]")
            |print(f"[${Id(a)}%-25s]")""".stripMargin) shouldBe
        "[     18446744073709551616]\n[18446744073709551616     ]\n"
    }

    "two values differing only above the 64th bit do not hash alike" in {
      run("""var a: u128 = 0
            |var b: u128 = 18446744073709551616
            |var c: u128 = 36893488147419103232
            |print(a.hash() == b.hash(), b.hash() == c.hash())""".stripMargin) shouldBe "false false\n"
    }

    "and equal values still hash equal, which is the law the mixing must keep" in {
      run("""var a: u128 = 340282366920938463463374607431768211455
            |var b: u128 = 340282366920938463463374607431768211455
            |print(a.hash() == b.hash())""".stripMargin) shouldBe "true\n"
    }
  }

  "a printf specifier is refused, because C has no conversion this wide" - {
    "a decimal one names the width and points at `str`" in {
      val e = err("""var a: u128 = 1
                    |print(f"${a}%d")""".stripMargin)
      e should include("128 bits")
      e should include("'str'")
    }

    "so does a hexadecimal one, since the argument is the problem and not the letter" in {
      err("""var a: u128 = 1
            |print(f"${a}%x")""".stripMargin) should include("no conversion")
    }

    "a width at or below 64 bits still formats, so the refusal is about the width alone" in {
      run("""var a: u64 = 255
            |print(f"${a}%04x")""".stripMargin) shouldBe "00ff\n"
    }
  }
}
