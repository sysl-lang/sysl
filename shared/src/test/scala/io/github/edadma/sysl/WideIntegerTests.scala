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

    // Past 128 is now ordinary. What made 128 the ceiling was two claims about the toolchain, and
    // neither held: a wide division needs no compiler-rt routine (the back end expands it inline),
    // and the decimal renderer is generated per width rather than written once.
    "a width past the old 128-bit ceiling is an ordinary type" in {
      run("var a: u256 = 7\nvar b: i256 = -9\nvar c: u512 = 11\nprint(a, b, c)") shouldBe "7 -9 11\n"
    }

    // The value that proves the renderer is generated at the value's own width rather than clamped
    // to 128: every digit here lives above the 128th bit, so a clamped renderer prints zero.
    "a value living entirely above 128 bits prints its real digits" in {
      run(
        """var x: u256 = 1
          |var i = 0
          |
          |while i < 200 do
          |    x = x * 2u256
          |    i = i + 1
          |
          |print(x)""".stripMargin
      ) shouldBe s"${BigInt(2).pow(200)}\n"
    }

    "arithmetic above 128 bits carries, rather than wrapping at 128" in {
      run(
        s"""var a: u256 = ${BigInt(2).pow(128)}
           |var b: u256 = ${BigInt(2).pow(128)}
           |print(a + b)""".stripMargin
      ) shouldBe s"${BigInt(2).pow(129)}\n"
    }

    "the ceiling is the back end's own, and the diagnostic names it" in {
      err("var x: i8388608 = 1") should include("8388607")
    }
  }

  /** `N ≥ 1` has no exception at its low end either, and `i1` is the degenerate case worth pinning:
   * one bit of two's complement is the sign bit, so the type holds `{-1, 0}`.
   *
   * Nothing about it is special-cased, which is the assertion — `abs` answers `-1` because `-1` is
   * this width's most negative value, exactly as `abs` at every width answers its own minimum, and
   * `signum` never has to produce `+1` because no value of the type is positive.
   */
  "the narrowest widths" - {
    "u1 is a single binary digit" in {
      run("var a: u1 = 1\nvar b: u1 = 0\nprint(a, b, a + a)") shouldBe "1 0 0\n"
    }

    "i1 holds minus one and zero, the sign bit being the only bit" in {
      run("var a: i1 = 0\nvar b: i1 = -1\nprint(a, b, a + b, b + b)") shouldBe "0 -1 -1 0\n"
    }

    "i1's abs and signum follow the general rule with no carve-out" in {
      run(
        """import sysl.math.*
          |
          |var a: i1 = 0
          |var b: i1 = -1
          |
          |print(b.abs(), b.signum(), a.signum())""".stripMargin
      ) shouldBe "-1 -1 0\n"
    }

    // Zero is the one width the shape admits and the family does not, so it is a diagnostic about
    // the number rather than "unknown type" — which would send a reader looking for a declaration
    // they never wrote. `scalarType` consults the width family before the declared types, so the
    // shape is reserved whether or not it resolves; the message is the whole difference.
    "a zero width says it has no bits, rather than reading as a missing declaration" in {
      err("var x: i0 = 0") should include("has no bits")
      err("var x: u0 = 0") should include("has no bits")
      err("var x: i000 = 0") should include("has no bits")
    }

    "a leading zero says how to write the width plainly" in {
      err("var x: i01 = 0") should include("leading zero")
      err("var x: u008 = 0") should include("'u8'")
    }

    // The shape is a family letter and digits only. Anything else is an ordinary identifier and
    // must stay available to a program that declares one.
    "a name that only looks like a width is left to the program" in {
      err("var x: i5x = 0") should include("unknown type 'i5x'")
    }

    "and the bit surface counts against a width of one" in {
      run(
        """import sysl.math.*
          |
          |var a: i1 = -1
          |var b: u1 = 0
          |
          |print(a.count_ones(), b.count_zeros(), a.leading_zeros())""".stripMargin
      ) shouldBe "1 1 0\n"
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

  /** The wrapping operators at widths that are not a register and not a byte.
   *
   * A value of an odd width is held in a container wider than the type — a `u5` in eight bits, a
   * `u12` in sixteen — so every one of these has a *plausible* wrong answer available to it: the one
   * the container would give. `7 * 9` at `u5` is `31`, and a multiply that forgot to narrow would
   * answer `63` and still look like arithmetic.
   *
   * The operand pairs are therefore chosen so the two answers differ. A product that fits the type
   * proves nothing, since the container would agree with it.
   */
  "multiplication and the rest wrap at the type's width, not the container's" - {
    "a product wraps at an odd unsigned width" in {
      run("var a: u5 = 7\nvar b: u5 = 9\nprint(a * b)") shouldBe "31\n"
    }

    // Both operands at the type's maximum, so the true product needs ten bits and the answer is one.
    "the largest pair of a width multiplies to something small" in {
      run("var a: u5 = 31\nvar b: u5 = 31\nprint(a * b)") shouldBe "1\n"
    }

    "a signed product wraps into the signed range, keeping the two's-complement meaning" in {
      run("var a: i5 = -13\nvar b: i5 = 5\nprint(a * b)") shouldBe "-1\n"
      run("var a: i3 = 3\nvar b: i3 = 3\nprint(a * b)") shouldBe "1\n"
    }

    "a width between two byte sizes multiplies at its own width" in {
      run("var a: u12 = 4000\nvar b: u12 = 3\nprint(a * b)") shouldBe "3808\n"
    }

    // The identity `(2^n - 1)^2 ≡ 1 (mod 2^n)`, at a wide width that is not a multiple of 64.
    "the widest value of an odd wide width squares to one" in {
      run("var a: u96 = 79228162514264337593543950335\n\nprint(a * a)") shouldBe "1\n"
    }

    "addition and subtraction wrap at the same width" in {
      run("var a: u5 = 30\nvar b: u5 = 5\nprint(a + b, b - a)") shouldBe "3 7\n"
    }

    // Negating the most negative value has no representable answer and wraps to itself — the same
    // rule `abs` follows, and the reason `abs` follows it.
    "negating an odd width's minimum answers the minimum" in {
      run("var a: i3 = -4\nprint(-a)") shouldBe "-4\n"
    }

    "shifts move within the width and off the end of it" in {
      run("var a: u5 = 1\nprint(a << 4u5, (a << 4u5) << 1u5)") shouldBe "16 0\n"
    }

    "a signed right shift carries the sign at an odd width" in {
      run("var a: i5 = -16\nprint(a >> 1i5, a >> 4i5)") shouldBe "-8 -1\n"
    }
  }

  /** Division and remainder at widths that are not a register and not a byte multiple.
   *
   * These are where a division goes wrong quietly rather than loudly: the value is stored in a
   * container wider than the type, so an operand that was not narrowed to its own width first
   * divides as the wrong number, and the answer is plausible instead of absent. Every case below
   * therefore uses operands whose correct answer differs from what the next width up would give.
   */
  "division and remainder at widths that are neither a register nor a byte" - {
    // Truncation toward zero with the remainder taking the dividend's sign, at a width narrower
    // than any C type. `-13 / 4` is `-3` and not `-4`, which is the difference between truncating
    // and flooring.
    "a narrow signed width truncates toward zero and keeps the dividend's sign" in {
      run(
        """var a: i5 = -13
          |var b: i5 = 4
          |
          |print(a / b, a % b, (-a) / b, (-a) % b)""".stripMargin
      ) shouldBe "-3 -1 3 1\n"
    }

    "the narrowest signed width that can divide at all" in {
      run("var a: i3 = -4\nvar b: i3 = 3\nprint(a / b, a % b)") shouldBe "-1 -1\n"
    }

    // The top bit is set, so a division that read the value as signed would answer a negative one.
    "an unsigned width with its top bit set is not read as negative" in {
      run("var a: u5 = 31\nvar b: u5 = 4\nprint(a / b, a % b)") shouldBe "7 3\n"
    }

    "a width between two byte sizes" in {
      run("var a: u12 = 4000\nvar b: u12 = 7\nprint(a / b, a % b)") shouldBe "571 3\n"
    }

    // Wide, and not a multiple of 64, so the value straddles its registers unevenly — the case an
    // implementation that assumed whole limbs would get wrong.
    "a wide width that does not fill its last register" in {
      run(
        """var a: u96 = 79228162514264337593543950335
          |var b: u96 = 1000000007
          |
          |print(a / b, a % b)""".stripMargin
      ) shouldBe "79228161959667203875 873523210\n"
    }

    "and the same, signed and negative" in {
      run(
        """var a: i100 = -633825300114114700748351602687
          |var b: i100 = 12345
          |
          |print(a / b, a % b)""".stripMargin
      ) shouldBe "-51342673156266885439315642 -2197\n"
    }
  }

  /** The two divisions with no answer, which are guarded rather than left to the machine.
   *
   * **The overflow guard is computed at the operand's own width**, and that is what these pin: the
   * emitted test is `icmp eq i4 %x, -8`, `i4`'s minimum, not a constant borrowed from a wider type.
   * A guard written against the wrong width would simply not fire at a narrow one, and `sdiv`
   * overflow is poison — so the failure would be a plausible wrong number rather than a stop.
   *
   * arm64 makes this sharper than it looks: its `sdiv` answers the minimum silently for this case
   * and never faults, so nothing here is inherited from the hardware.
   */
  "a division with no representable answer stops the program" - {
    "the most negative value over minus one, at a narrow width" in {
      exits("var a: i4 = -8\nvar b: i4 = -1\nprint(a / b)")
    }

    // **The remainder does not, and the asymmetry is the right one.** `min / -1` has no
    // representable answer and must stop; `min % -1` is `0`, which every width can hold. LLVM calls
    // both undefined, so the answer is not simply left to `srem`: the divisor is replaced by `1`
    // when it is `-1`, and since `x % 1` and `x % -1` are both zero for every `x`, that is the same
    // answer with the undefined case never reached.
    "while the remainder of the same pair is zero, which is representable" in {
      run("var a: i4 = -8\nvar b: i4 = -1\nprint(a % b)") shouldBe "0\n"
    }

    "and that substitution is the answer at every width, not a narrow-width special case" in {
      run(
        """var a: i100 = -633825300114114700748351602688
          |var b: i100 = -1
          |var c: int = -2147483648
          |var d: int = -1
          |
          |print(a % b, c % d)""".stripMargin
      ) shouldBe "0 0\n"
    }

    "the same at an odd wide width, where the minimum is a bignum" in {
      exits(
        """var a: i100 = -633825300114114700748351602688
          |var b: i100 = -1
          |
          |print(a / b)""".stripMargin
      )
    }

    // One below the minimum divides perfectly well, which is what says the guard is testing the
    // right value rather than refusing every negative division.
    "while one step away from the minimum divides normally" in {
      run("var a: i4 = -7\nvar b: i4 = -1\nprint(a / b)") shouldBe "7\n"
    }

    "an unsigned width has no overflow to guard, only the zero" in {
      run("var a: u4 = 15\nvar b: u4 = 1\nprint(a / b)") shouldBe "15\n"
      exits("var a: u4 = 15\nvar b: u4 = 0\nprint(a / b)")
    }

    "division by zero stops it at a narrow width too" in {
      exits("var a: i5 = 7\nvar b: i5 = 0\nprint(a / b)")
      exits("var a: u12 = 7\nvar b: u12 = 0\nprint(a % b)")
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

    // The field forwards to the width's **own** `display`, which is the blanket instantiated at
    // `u128` — so a wide field is written exactly the way a narrow one is, with no renderer named
    // and no `str` in the middle. That the two used to differ is what `display_wide` was.
    "a struct's own `Display` renders a wide field through the sink" in {
      run("""struct Id
            |    v: u128
            |
            |impl Display for Id
            |    display(self, out: *Writer, fmt: FormatSpec) = self.v.display(out, fmt)
            |
            |print(Id(340282366920938463463374607431768211455))""".stripMargin) shouldBe
        "340282366920938463463374607431768211455\n"
    }

    // The specifier reaches the blanket the way it reaches any renderer that forwards it: through a
    // type that renders itself, since a number keeps the strict conversion check. The digits are the
    // language's and the field is the library's, which is the split every other width has.
    "and the specifier it is handed pads the digits like any other number's" in {
      run("""struct Id
            |    v: u128
            |
            |impl Display for Id
            |    display(self, out: *Writer, fmt: FormatSpec) = self.v.display(out, fmt)
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
