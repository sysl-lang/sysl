package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** The heterogeneous operand of `14 §7`: the catalog's binary arithmetic traits take the right-hand
  * type as a parameter, so `Complex * f64` is an operator rather than a named method.
  *
  * The result stays `Self`, and deliberately — that is what covers scaling a vector by a real and
  * deliberately does not cover a dot product, which returns neither operand's type. `Eq` and `Ord`
  * stay homogeneous for the same kind of reason: what a comparison across two types would promise
  * about reflexivity and transitivity is a question nothing has asked.
  *
  * The parameter defaults to `Self` (`10 §3`), so nothing already written had to be respelled:
  * `impl Mul for Point` is still `Mul[Point]`, and `[T: Mul]` still asks for `Mul[T]`.
  */
class HeterogeneousOperandTests extends AnyFreeSpec with RunSupport with CodegenSupport {

  /** A complex number over `f64`, scalable by a real — the customer `guide/fft` had to write a
    * `scale` method for.
    */
  private val complex =
    """struct C
      |    re: f64
      |    im: f64
      |impl Mul[f64] for C
      |    mul(self, k: f64) -> C = C(self.re * k, self.im * k)
      |show(c: C) -> string = str(c.re) + " " + str(c.im)
      |""".stripMargin

  /** The same, multiplied by another complex number as well — the two argument lists `guide/fft`
    * needs on one type, since the butterfly wants `C * C` and the inverse wants `C * f64`.
    */
  private val both =
    complex +
      """impl Mul for C
        |    mul(self, o: C) -> C = C(self.re * o.re - self.im * o.im, self.re * o.im + self.im * o.re)
        |""".stripMargin

  "an operator over two types" - {
    "a struct scaled by a scalar" in {
      run(complex + """print(show(C(1.0, 2.0) * 2.5))""") shouldBe "2.5 5\n"
    }

    "the operand order is the one written" in {
      run("""struct S
            |    v: int
            |impl Sub[int] for S
            |    sub(self, k: int) -> S = S(self.v - k)
            |print((S(10) - 3).v)""".stripMargin) shouldBe "7\n"
    }

    // The place is read once and updated from the value codegen is already holding (`14 §8 e`), and
    // a dispatched operator must not have changed that — a call rebuilt over the place's own tree
    // would read it twice.
    "a compound assignment updates through the same implementation" in {
      run(complex + """var c = C(1.0, 2.0)
                      |c *= 3.0
                      |print(show(c))""".stripMargin) shouldBe "3 6\n"
    }

    "and updates the place from one call, not a rebuilt one" in {
      val out = irMain(complex + "var c = C(1.0, 2.0)\nc *= 3.0")

      out.split("@C.mul", -1).length - 1 shouldBe 1
    }

    // Every arithmetic and bitwise trait took the parameter, so the rule is one rule rather than a
    // special case for `*`.
    "every binary arithmetic trait takes the right-hand type" in {
      run("""struct B
            |    v: int
            |impl Add[int] for B
            |    add(self, k: int) -> B = B(self.v + k)
            |impl Div[int] for B
            |    div(self, k: int) -> B = B(self.v / k)
            |impl Rem[int] for B
            |    rem(self, k: int) -> B = B(self.v % k)
            |impl BitAnd[int] for B
            |    bitand(self, k: int) -> B = B(self.v & k)
            |impl BitOr[int] for B
            |    bitor(self, k: int) -> B = B(self.v | k)
            |impl BitXor[int] for B
            |    bitxor(self, k: int) -> B = B(self.v ^ k)
            |impl Shl[int] for B
            |    shl(self, k: int) -> B = B(self.v << k)
            |impl Shr[int] for B
            |    shr(self, k: int) -> B = B(self.v >> k)
            |print((B(20) + 1).v)
            |print((B(20) / 2).v)
            |print((B(20) % 7).v)
            |print((B(20) & 6).v)
            |print((B(20) | 1).v)
            |print((B(20) ^ 4).v)
            |print((B(20) << 1).v)
            |print((B(20) >> 2).v)""".stripMargin) shouldBe "21\n10\n6\n4\n21\n16\n40\n5\n"
    }
  }

  "what the default keeps working" - {
    "an implementation written with no arguments is the homogeneous one" in {
      run("""struct P
            |    v: int
            |impl Mul for P
            |    mul(self, k: P) -> P = P(self.v * k.v)
            |print((P(6) * P(7)).v)""".stripMargin) shouldBe "42\n"
    }

    "and writing the argument out is the same implementation" in {
      run("""struct P
            |    v: int
            |impl Mul[P] for P
            |    mul(self, k: P) -> P = P(self.v * k.v)
            |print((P(6) * P(7)).v)""".stripMargin) shouldBe "42\n"
    }

    "a bare bound still asks for the homogeneous trait" in {
      run("""struct P
            |    v: int
            |impl Mul for P
            |    mul(self, k: P) -> P = P(self.v * k.v)
            |sq[T: Mul](x: T) -> T = x * x
            |print(sq(P(5)).v)
            |print(sq(9))""".stripMargin) shouldBe "25\n81\n"
    }

    "a bound may name the right-hand type instead" in {
      run(complex + """twice[T: Mul[f64]](x: T) -> T = x * 2.0
                      |print(show(twice(C(1.5, 2.0))))""".stripMargin) shouldBe "3 4\n"
    }

    "a requirement carries the default down" in {
      run("""struct P
            |    v: int
            |trait Word: Mul
            |    tag(self) -> int
            |impl Mul for P
            |    mul(self, k: P) -> P = P(self.v * k.v)
            |impl Word for P
            |    tag(self) -> int = self.v
            |sq[T: Word](x: T) -> T = x * x
            |print(sq(P(4)).v)""".stripMargin) shouldBe "16\n"
    }
  }

  "what the scalars kept" - {
    // `14 §5`'s promise is that a membership changes no codegen, and the parameter must not have
    // changed that: an `int` is `Mul[int]` and reaches the machine's instruction, not a table.
    "a scalar operator is still one instruction" in {
      irMain("var a = 6\nvar b = 7\nprint(a * b)") should include("mul i32")
    }

    "at every width, signed and not" in {
      run("""print(200u8 + 100u8)
            |print(3u8 * 4u8)
            |print(-7i16 / 2i16)
            |print(7u16 / 2u16)
            |print(-7 % 3)
            |print(1u8 << 7u8)
            |print(-8i8 >> 1i8)""".stripMargin) shouldBe "44\n12\n-3\n3\n-1\n128\n-4\n"
    }

    "and a scalar's trait method called by name is the same instruction" in {
      run("print(5.add(3))\nprint(5.mul(3))\nprint(5.shl(1))") shouldBe "8\n15\n10\n"
    }

    // The built-in memberships compare their arguments the way an assignment compares two types, so
    // a transparent subtype is `Mul` at its base exactly as it is its base's operand.
    "a transparent subtype is its base's member" in {
      run("""type Age = int within 0..150
            |var a: Age = 20
            |print(a * 2)""".stripMargin) shouldBe "40\n"
    }
  }

  "what stays homogeneous" - {
    "a comparison takes no right-hand type" in {
      err("""struct P
            |    v: int
            |impl Eq[int] for P
            |    eq(self, k: int) -> bool = self.v == k
            |print(1)""".stripMargin) should include("trait 'Eq' does not take type arguments")
    }

    "so comparing two types is still a mismatch" in {
      err("""struct P
            |    v: int
            |impl Ord for P
            |    lt(self, k: P) -> bool = self.v < k.v
            |print(P(1) < 2)""".stripMargin) should include("'<' needs matching types")
    }
  }

  "what a mistake says" - {
    "the wrong right-hand type names the trait that is missing" in {
      err(complex + "print(show(C(1.0, 2.0) * 2))") should include(
        "'*' between C and int needs 'Mul[int]' — it implements 'Mul[real]'",
      )
    }

    "a type with no implementation at all keeps the plain diagnostic" in {
      err("""struct P
            |    v: int
            |print(1)
            |var q = P(1) * P(2)""".stripMargin) should include("*")
    }

    "a bounded parameter is told which bound to write" in {
      err(complex + "twice[T](k: f64, x: T) -> T = x * k") should include("'*' needs 'T: Mul[real]'")
    }

    // The bare spelling is what a program writes, so it is what the advice has to say — telling
    // someone to write `T: Mul[T]` would be telling them to write out what they may leave out.
    "and a homogeneous one is told the bare trait" in {
      err("sq[T](x: T) -> T = x * x") should include("'*' needs 'T: Mul'")
    }

    "a scalar keeps its instruction rather than taking a user implementation" in {
      err(complex + """impl Mul[C] for f64
                      |    mul(self, c: C) -> f64 = self * c.re
                      |print(show(2.0 * C(1.0, 2.0)))""".stripMargin) should include(
        "'*' needs matching types",
      )
    }

    // Two implementations at one argument list is still one implementation too many, and the
    // arguments are no help — this is the case that has nothing to pick between them.
    "two implementations at one argument list is still one too many" in {
      err(complex + """impl Mul[f64] for C
                      |    mul(self, k: f64) -> C = C(self.re, self.im)
                      |print(1)""".stripMargin) should include("'C' already implements 'Mul[real]'")
    }
  }

  // The butterfly of a transform wants `Complex * Complex`; scaling every sample on the way out of
  // an inverse wants `Complex * f64`. They are two argument lists for one trait on one type, which
  // is what `guide/fft`'s `.scale(k)` workaround existed for.
  "one trait at two argument lists" - {
    "the operator picks by the pair of operands" in {
      run(both + """print(show(C(1.0, 2.0) * C(3.0, 4.0)))
                   |print(show(C(1.0, 2.0) * 2.5))""".stripMargin) shouldBe "-5 10\n2.5 5\n"
    }

    "and so does the named call" in {
      run(both + """print(show(C(1.0, 2.0).mul(C(3.0, 4.0))))
                   |print(show(C(1.0, 2.0).mul(2.5)))""".stripMargin) shouldBe "-5 10\n2.5 5\n"
    }

    "each implementation is a function of its own" in {
      val out = ir(both + "print(show(C(1.0, 2.0) * C(3.0, 4.0) * 2.5))")

      out should include("define %struct.C @C.mul(%struct.C %self.param, double %k.param)")
      out should include("define %struct.C @C.mul.2(%struct.C %self.param, %struct.C %o.param)")
      out should include("call %struct.C @C.mul.2(%struct.C %t2, %struct.C %t4)")
    }

    "a bound asks for the one it names" in {
      run(both + """scaled[T: Mul[f64]](x: T, k: f64) -> T = x * k
                   |print(show(scaled(C(1.0, 2.0), 3.0)))""".stripMargin) shouldBe "3 6\n"
    }

    "and the other bound reaches the other implementation" in {
      run(both + """squared[T: Mul](x: T) -> T = x * x
                   |print(show(squared(C(1.0, 2.0))))""".stripMargin) shouldBe "-3 4\n"
    }

    "a call whose argument names neither is refused rather than guessed at" in {
      err(both + "print(show(C(1.0, 2.0).mul(2)))") should include(
        "'mul' comes from 2 implementations of one trait on C, and none of them takes (int) — " +
          "write the argument at the type of the implementation that was meant",
      )
    }
  }

  "a bare literal beside a type parameter" - {
    // A parameter is opaque, so the literal's type is not forced either way, and the parameter's own
    // bounds are the only thing in scope that knows which reading the body meant.
    "takes the parameter's type where the bound is homogeneous" in {
      run("""struct P
            |    v: int
            |impl Sub for P
            |    sub(self, k: P) -> P = P(self.v - k.v)
            |dec[T: Sub](x: T, one: T) -> T = x - one
            |print(dec(9u8, 1u8))
            |print(dec(P(9), P(1)).v)""".stripMargin) shouldBe "8\n8\n"
    }

    "and the bound's own right-hand type where it names one" in {
      run(complex + """twice[T: Mul[f64]](x: T) -> T = x * 2.0
                      |print(show(twice(C(1.0, 3.0))))""".stripMargin) shouldBe "2 6\n"
    }

    // With no bound for the operator at all there is nothing to read the literal against, and the
    // homogeneous reading is the one whose advice — write `T: Sub` — is the advice that helps.
    "and is left homogeneous where no bound names the operator" in {
      err("dec[T](x: T) -> T = x - 1") should include("'-' needs 'T: Sub'")
    }

    "a negative literal is read the same way" in {
      run("""neg[T: Add](x: T, d: T) -> T = x + d
            |print(neg(9i8, -1i8))""".stripMargin) shouldBe "8\n"
    }

    "and a width-checked literal is still checked at the instantiation" in {
      err("""wide[T: Add](x: T) -> T = x + 300
            |print(wide(1u8))""".stripMargin) should include("does not fit")
    }
  }

  "a default that is not 'Self'" - {
    // The fill has to run under the type being asked about, not under the declaration's own
    // parameters — and a default naming a concrete type is the case that tells the two apart.
    "is what an implementation leaving it out supplies" in {
      run("""trait Sink[T = int]
            |    put(self, x: T) -> int
            |struct C
            |    n: int
            |impl Sink for C
            |    put(self, x: int) -> int = self.n + x
            |f[T: Sink](c: T) -> int = c.put(5)
            |print(f(C(10)))""".stripMargin) shouldBe "15\n"
    }

    "and a generic implementation supplies it at each instantiation" in {
      run("""struct Box[T]
            |    v: T
            |impl[T] Mul for Box[T]
            |    mul(self, k: Box[T]) -> Box[T] = k
            |print((Box(1) * Box(2)).v)
            |print((Box("a") * Box("b")).v)""".stripMargin) shouldBe "2\nb\n"
    }

    // A shape reaches the fill by a different key than a named type does, so it is asked separately.
    "including one written for a shape" in {
      run("""impl[T] Mul for []T
            |    mul(self, rhs: []T) -> []T = rhs
            |var a = [1, 2]
            |var b = [3, 4]
            |print((a[0..] * b[0..])[1])""".stripMargin) shouldBe "4\n"
    }
  }
}
