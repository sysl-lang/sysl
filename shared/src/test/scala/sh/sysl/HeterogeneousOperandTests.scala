package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** The heterogeneous operand of `library/core.md § Walking a type of your own`: the catalog's
  * binary arithmetic traits take the right-hand type as a parameter, so `Complex * f64` is an
  * operator rather than a named method.
  *
  * **The result is a second parameter, and the same shape answers it.** `Mul[Rhs = Self, Out = Self]`
  * covers scaling a vector by a real, a dot product, and a matrix applied to a vector, none of which
  * returns both operands' type. `Eq` and `Ord` stay homogeneous for a different kind of reason: what a
  * comparison across two types would promise about reflexivity and transitivity is a question nothing
  * has asked.
  *
  * Both parameters default to `Self` (`reference/generics.md § A parameter may carry a default`),
  * so nothing already written had to be respelled: `impl Mul for Point` is still `Mul[Point,
  * Point]`, and `[T: Mul]` still asks for `Mul[T, T]`.
  *
  * **What a use supplies is the operand, never the result** — `a * b` fixes the pair and asks to be
  * told what comes back — so the operands select the implementation and the implementation supplies
  * the result. Two implementations agreeing on the operands are therefore refused, and that refusal
  * is the load-bearing half of the tests below.
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

    // The place is read once and updated from the value codegen is already holding
    // (`reference/expressions.md § Assignment`), and a dispatched operator must not have changed
    // that — a call rebuilt over the place's own tree would read it twice.
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
    // `reference/expressions.md § Operator dispatch`'s promise is that a membership changes no
    // codegen, and the parameter must not have changed that: an `int` is `Mul[int]` and reaches the
    // machine's instruction, not a table.
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
            |print(1)""".stripMargin) should include(s"trait '${lib("Eq")}' does not take type arguments")
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
        s"'*' between C and int needs '${lib("Mul")}[int]' — it implements '${lib("Mul")}[real]'",
      )
    }

    "a type with no implementation at all keeps the plain diagnostic" in {
      err("""struct P
            |    v: int
            |print(1)
            |var q = P(1) * P(2)""".stripMargin) should include("*")
    }

    "a bounded parameter is told which bound to write" in {
      err(complex + "twice[T](k: f64, x: T) -> T = x * k") should include(s"'*' needs 'T: ${lib("Mul")}[real]'")
    }

    // The bare spelling is what a program writes, so it is what the advice has to say — telling
    // someone to write `T: Mul[T]` would be telling them to write out what they may leave out.
    "and a homogeneous one is told the bare trait" in {
      err("sq[T](x: T) -> T = x * x") should include(s"'*' needs 'T: ${lib("Mul")}'")
    }

    /** **This asserted the opposite until card `0385`**, and the rule it was written against is what
      * that card removed: coherence looked only at an `impl`'s subject, so `f64` being the
      * library's settled it and the local `C` in the trait's argument list was never consulted.
      * `c * 2.0` was writable and `2.0 * c` was a block nobody could write in any module.
      *
      * What survives of the old claim is the half that was always the point, and it is asserted
      * beside this one: the scalar keeps its **own** instruction. Only the argument list tells the
      * two apart.
      */
    "a scalar takes a user implementation at an argument list of its own" in {
      run(complex + """impl Mul[C] for f64
                      |    mul(self, c: C) -> f64 = self * c.re
                      |print(2.0 * C(1.5, 2.0))""".stripMargin) shouldBe "3\n"
    }

    "and keeps its instruction for its own pair, by the operator and by name" in {
      run(complex + """impl Mul[C] for f64
                      |    mul(self, c: C) -> f64 = self * c.re
                      |print(2.0 * 3.0, 2.0.mul(3.0))""".stripMargin) shouldBe "6 6\n"
    }

    // Two implementations at one argument list is still one implementation too many, and the
    // arguments are no help — this is the case that has nothing to pick between them.
    "two implementations at one argument list is still one too many" in {
      err(complex + """impl Mul[f64] for C
                      |    mul(self, k: f64) -> C = C(self.re, self.im)
                      |print(1)""".stripMargin) should include(s"'C' already implements '${lib("Mul")}[real]'")
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
      err("dec[T](x: T) -> T = x - 1") should include(s"'-' needs 'T: ${lib("Sub")}'")
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

    // A shape reaches the fill by a different key than a named type does, so it is asked
    // separately. The trait is the program's own, because the library's would leave the block with
    // no home: `reference/traits.md § Where an impl may live` licenses an `impl` by its trait or by
    // a type named in its subject, and `[]T` names neither.
    "including one written for a shape" in {
      run("""trait Times[Rhs = Self]
            |    times(self, rhs: Rhs) -> Rhs
            |impl[T] Times for []T
            |    times(self, rhs: []T) -> []T = rhs
            |var a = [1, 2]
            |var b = [3, 4]
            |print(a[0..].times(b[0..])[1])""".stripMargin) shouldBe "4\n"
    }
  }

  /** The two products a vector space is made of, and neither returns both operands' type. These are
    * what `library/core.md § Walking a type of your own` recorded as uncovered for as long as an
    * operator's result was fixed to `Self`.
    */
  private val space =
    """struct Vec2
      |    x: real
      |    y: real
      |struct Mat2
      |    a: real
      |    b: real
      |    c: real
      |    d: real
      |impl Mul[Vec2, real] for Vec2
      |    mul(self, o: Vec2) -> real = self.x * o.x + self.y * o.y
      |impl Mul[Vec2, Vec2] for Mat2
      |    mul(self, o: Vec2) -> Vec2 = Vec2(self.a * o.x + self.b * o.y, self.c * o.x + self.d * o.y)
      |impl Mul[real, Vec2] for Vec2
      |    mul(self, k: real) -> Vec2 = Vec2(self.x * k, self.y * k)
      |""".stripMargin

  "an operator whose result is neither operand's type" - {
    "a dot product is an operator, and it yields the scalar" in {
      run(space + "print(Vec2(1.0, 2.0) * Vec2(3.0, 4.0))") shouldBe "11\n"
    }

    // Two implementations on one type whose *results* differ, told apart by their operands: `Vec2`
    // multiplied by a `Vec2` is a number and by a `real` is a vector. Nothing but the right operand
    // says which, which is the selection rule in one expression.
    "the same type's other multiplication still gives back a vector" in {
      run(space + """var v = Vec2(1.0, 2.0) * 3.0
                    |print(v.x, v.y)""".stripMargin) shouldBe "3 6\n"
    }

    "a matrix applied to a vector yields a vector" in {
      run(space + """var w = Mat2(1.0, 2.0, 3.0, 4.0) * Vec2(1.0, 2.0)
                    |print(w.x, w.y)""".stripMargin) shouldBe "5 11\n"
    }

    // The result flows into the surrounding expression as any other value would — worth pinning
    // separately, because a result read off the *receiver's* type would have type-checked here and
    // then computed with the wrong one.
    "the result composes, and it is the declared type rather than the receiver's" in {
      run(space + """var m = Mat2(1.0, 0.0, 0.0, 1.0)
                    |var u = Vec2(3.0, 4.0)
                    |print((m * u) * u)""".stripMargin) shouldBe "25\n"
    }

    "and it is what a nested call is checked against" in {
      run(space + """norm2(v: Vec2) -> real = v * v
                    |print(norm2(Vec2(3.0, 4.0)))""".stripMargin) shouldBe "25\n"
    }

    // A result declared as something the operands do not mention at all — the shape that has no
    // homogeneous reading whatsoever.
    "a result mentioning neither operand" in {
      run("""struct A
            |    n: int
            |struct B
            |    n: int
            |struct C
            |    n: int
            |impl Mul[B, C] for A
            |    mul(self, o: B) -> C = C(self.n * o.n)
            |print((A(6) * B(7)).n)""".stripMargin) shouldBe "42\n"
    }
  }

  /** The same four readings on a **generic** subject, which is what a vector space over an element
    * type needs and what was refused until this was fixed.
    *
    * The rule the refusal came from is real and is about a written argument coinciding with the
    * default at *one* instantiation — `impl[T] Mul[Box[int]] for Box[T]` promises at a `Box[int]`
    * what a defaulted block promises there and promises something else everywhere else. An argument
    * built out of the block's **own parameters** is not that: it says the same thing at every
    * instantiation, which is the reading that lets `impl[T] Index[usize, T] for Buf[T]` carry what a
    * container holds.
    *
    * A dot product is the case with no other spelling. Trait arguments are positional, so reaching
    * `Out` means writing `Rhs`, and `Rhs` on a dot product is `Self` — there is no `Mul[Out = T]`.
    * Refusing it also split the generic path from the plain one, since `impl Mul[Vector, real] for
    * Vector` is this same block with the parameter already resolved.
    */
  /** The vector space of `space` above, written once over an element type — the same four readings
    * with `real` replaced by a parameter.
    */
  private val generic =
    """struct Vec2[T]
      |    a: T
      |    b: T
      |end Vec2
      |struct Mat2[T]
      |    r0: Vec2[T]
      |    r1: Vec2[T]
      |end Mat2
      |impl[T: Mul + Add] Mul[Vec2[T], T] for Vec2[T]
      |    mul(self, rhs: Vec2[T]) -> T = self.a * rhs.a + self.b * rhs.b
      |impl[T: Mul] Mul[T] for Vec2[T]
      |    mul(self, rhs: T) -> Vec2[T] = Vec2(self.a * rhs, self.b * rhs)
      |impl[T: Mul + Add] Mul[Vec2[T], Vec2[T]] for Mat2[T]
      |    mul(self, rhs: Vec2[T]) -> Vec2[T] =
      |        Vec2(self.r0.a * rhs.a + self.r0.b * rhs.b, self.r1.a * rhs.a + self.r1.b * rhs.b)
      |""".stripMargin

  "the same readings at a generic element type" - {
    "a dot product whose operand is the subject and whose result is not" in {
      run(generic + "print(Vec2(1.0, 2.0) * Vec2(3.0, 4.0))") shouldBe "11\n"
    }

    // The same body at a second element type, which is the whole point of it being generic: one
    // block, and the element's own multiplication and addition underneath.
    "and the same block at another element type" in {
      run(generic + "print(Vec2(1, 2) * Vec2(3, 4))") shouldBe "11\n"
    }

    "scaling still gives back the vector" in {
      run(generic + """var v = Vec2(1.0, 2.0) * 3.0
                      |print(v.a, v.b)""".stripMargin) shouldBe "3 6\n"
    }

    "and a matrix applied to a vector still gives back a vector" in {
      run(generic + """var w = Mat2(Vec2(1.0, 2.0), Vec2(3.0, 4.0)) * Vec2(1.0, 2.0)
                      |print(w.a, w.b)""".stripMargin) shouldBe "5 11\n"
    }

    /** A **bounded** generic subject, which is what a vector space actually has: `Vector[T: Scalar]`
     * asks something of its own parameter, and the dot product writes `Vector[T]` as a trait
     * argument. Every block filed after that one is read against it, under this block's stand-ins
     * for its parameters — so those stand-ins have to carry the bound, or applying `Vector` to one
     * is an application the type's own declaration refuses and the complaint lands on the *later*
     * block, which is neither where it was written nor wrong.
     *
     * The scaling block is what fires it, and the third one is here because the guide that found
     * this had three: each block after the first is read against every block before it.
     */
    "a bounded generic subject may be written as a trait argument, with blocks filed after it" in {
      run("""trait Scalar: Mul + Add
            |    tag(self) -> int
            |impl Scalar for real
            |    tag(self) -> int = 1
            |struct Vec2[T: Scalar]
            |    a: T
            |    b: T
            |impl[T: Scalar] Mul[Vec2[T], T] for Vec2[T]
            |    mul(self, rhs: Vec2[T]) -> T = self.a * rhs.a + self.b * rhs.b
            |impl[T: Scalar] Mul[T] for Vec2[T]
            |    mul(self, k: T) -> Vec2[T] = Vec2(self.a * k, self.b * k)
            |impl[T: Scalar] Add for Vec2[T]
            |    add(self, rhs: Vec2[T]) -> Vec2[T] = Vec2(self.a + rhs.a, self.b + rhs.b)
            |var v = Vec2(1.0, 2.0)
            |print(v * v, (v * 3.0).a, (v + v).b)""".stripMargin) shouldBe "5 3 4\n"
    }

    // The refusal the rule exists for, unchanged: an argument naming ONE instantiation of the
    // subject collides with the defaulted block there and nowhere else.
    "an argument fixed to one instantiation of the subject is still refused" in {
      err("""struct Box[T]
            |    v: T
            |end Box
            |impl[T] Mul[Box[int]] for Box[T]
            |    mul(self, rhs: Box[int]) -> Box[T] = self""".stripMargin) should
        include("would promise the same thing")
    }

    /** A scalar on the left of a **generic** subject, which is what `0385` was for and what the
     * containment reading of this rule took back: `Box[Pair[T]]` is a `Box`, so against a subject of
     * `Pair[T]` it is not the subject at any instantiation and there is nothing to choose between.
     *
     * It is the shape `linalg` needs -- `Complex[F] * Vector[Complex[F]]` -- and the shape
     * `sysl.math.complex` already ships one type parameter in, where the subject is `real` and this
     * check never ran at all.
     */
    "a scalar on the left of a generic subject, whose argument is built out of the subject" in {
      run("""struct Box[T]
            |    v: T
            |end Box
            |struct Pair[T]
            |    a: T
            |    b: T
            |end Pair
            |impl[T: Mul] Mul[T] for Pair[T]
            |    mul(self, k: T) -> Pair[T] = Pair(self.a * k, self.b * k)
            |impl[T: Mul] Mul[Box[Pair[T]], Box[Pair[T]]] for Pair[T]
            |    mul(self, b: Box[Pair[T]]) -> Box[Pair[T]] = Box(b.v * self.a)
            |val p = Pair(2, 3)
            |val q = p * Box(Pair(4, 5))
            |print(q.v.a, q.v.b)""".stripMargin) shouldBe "8 10\n"
    }

    // The mirror of it, which **passed before this rule was narrowed** and is here for what it
    // pins rather than for what it caught: the subject is the *element* and so is not generic, so
    // the check above never ran on it at all. It is the other half of what a package writes.
    "the container on both sides, with the element type as the subject" in {
      run("""struct Vec[T: Mul]
            |    a: T
            |    b: T
            |end Vec
            |impl[T: Mul] Mul[T] for Vec[T]
            |    mul(self, k: T) -> Vec[T] = Vec(self.a * k, self.b * k)
            |impl Mul[Vec[int], Vec[int]] for int
            |    mul(self, v: Vec[int]) -> Vec[int] = v * self
            |val v = Vec(2, 3)
            |print((4 * v).a, (v * 4).b)""".stripMargin) shouldBe "8 12\n"
    }

    // And a real collision on a generic subject is still caught — by the rule that a result is not a
    // selector, which is where it belonged all along.
    "two generic blocks agreeing on the operands are still refused" in {
      err("""struct V[T]
            |    x: T
            |end V
            |impl[T: Mul] Mul[V[T], T] for V[T]
            |    mul(self, o: V[T]) -> T = self.x * o.x
            |impl[T: Mul] Mul for V[T]
            |    mul(self, o: V[T]) -> V[T] = V(self.x * o.x)""".stripMargin) should
        include("differs only in what it gives back")
    }

    /** A **blanket** over the integers on the left, which is the shape that covers every width at
     * once -- and the one that took the compiler's own member name for all of them (card `0389`).
     *
     * A blanket is filed under its *bound's* key rather than under any type's, so the question
     * "does the compiler already provide this?" was asked of a subject that is `Type.Unknown` and
     * answered no. The member then went in unsuffixed, and `5.mul(2)` -- an integer's own
     * multiplication, reached by name -- stopped compiling in every program, whether or not it
     * mentioned this block. **The operator never broke**, which is why the by-name spelling is
     * asserted here beside it rather than instead of it.
     */
    "a blanket over the integers on the left covers every width, and hides none of their own" in {
      run("""struct Ticks
            |    n: long
            |end Ticks
            |impl Mul[long] for Ticks
            |    mul(self, k: long) -> Ticks = Ticks(self.n * k)
            |impl[N: Integer] Mul[Ticks, Ticks] for N
            |    mul(self, t: Ticks) -> Ticks = t * long(self)
            |val t = Ticks(7)
            |var w: u8 = 3
            |var m: i16 = 4
            |print((2 * t).n, (w * t).n, (m * t).n, (t * 5).n)
            |print(5.mul(2), 6.add(1))""".stripMargin) shouldBe "14 21 28 35\n10 7\n"
    }

    /** The other half of the same predicate, and a hole nobody had noticed: a blanket writing **no**
     * arguments is the implementation the compiler already provides, exactly as `impl Mul for int`
     * is -- and it was accepted, because that refusal asked about the subject too, and a blanket has
     * no subject to ask.
     *
     * **The refusal names the family rather than the block's parameter.** `'N' already implements`
     * sends a reader after what `N` is, and `N` is the thing they just wrote.
     */
    "a blanket over the integers that writes no arguments is the one the compiler provides" in {
      val e = err("""impl[N: Integer] Mul for N
                    |    mul(self, rhs: N) -> N = rhs""".stripMargin)

      e should include("every type in 'sysl.Integer' already implements 'sysl.Mul'")
      e should include("the compiler provides it")
    }

    // `Neg` is provided for the signed integers and not the unsigned ones, so the family is asked
    // with `exists` rather than `forall`: one key carries one suffix, and the conservative answer is
    // the only safe one. A plain name left unclaimed at `u8` costs nothing, where a plain name
    // wrongly taken at `i8` hides an operation the compiler owns.
    "a trait provided for only half the family is still the compiler's" in {
      err("""impl[N: Integer] Neg for N
            |    neg(self) -> N = self""".stripMargin) should
        include("already implements 'sysl.Neg'")
    }

    /** A blanket of the program's **own** trait, whose member name the compiler owns for the whole
     * family. This is `0389`'s shape one trait over, and it is here because it looked like the same
     * defect and is not: `MemberLowering`'s net against a block taking a compiler-owned name reads
     * `home.self`, which is empty for *every* generic block, blankets included -- so nothing there
     * fires, and `provides` above is keyed on the trait being implemented, which a program's own
     * trait is not.
     *
     * It answers `7` rather than `11`, so an integer's own `add` is reached and the block's is not.
     * Probed before the merge rather than reasoned about, and kept as a test because a claim nothing
     * pins is a claim that stops being true quietly.
     */
    "a blanket of a program's own trait does not take a member name the compiler owns" in {
      run("""trait Doubling
            |    add(self, rhs: int) -> int
            |
            |impl[N: Integer] Doubling for N
            |    add(self, rhs: int) -> int = int(self) + rhs * 2
            |
            |print(5.add(1))""".stripMargin) shouldBe "7\n"
    }
  }

  "a result is not a selector" - {
    // The refusal the design rests on: `a * b` carries the operands and not the result, so two
    // implementations agreeing on the operands leave the use with nothing to choose by. Accepting
    // them would silently take whichever was written first.
    "two implementations agreeing on the operands are refused" in {
      val e = err("""struct V
                    |    x: real
                    |impl Mul[V, real] for V
                    |    mul(self, o: V) -> real = self.x * o.x
                    |impl Mul[V, V] for V
                    |    mul(self, o: V) -> V = V(self.x * o.x)""".stripMargin)

      e should include("differs only in what it gives back")
      e should include("nothing at the use to choose with")
    }

    // The homogeneous spelling is one of the two, so writing it out beside a bare `impl` is the same
    // collision by another name.
    "a written result colliding with a defaulted one is the same refusal" in {
      err("""struct V
            |    x: real
            |impl Mul for V
            |    mul(self, o: V) -> V = V(self.x * o.x)
            |impl Mul[V, real] for V
            |    mul(self, o: V) -> real = self.x * o.x""".stripMargin) should
        include("differs only in what it gives back")
    }

    "but differing operands remain two ordinary implementations" in {
      run(space + """print(Vec2(1.0, 1.0) * Vec2(2.0, 3.0))
                    |var s = Vec2(1.0, 1.0) * 2.0
                    |print(s.x)""".stripMargin) shouldBe "5\n2\n"
    }
  }

  "what the result being an argument does not change" - {
    // A compound assignment is `a = a op b` (`reference/expressions.md § Operator dispatch`), so an operator whose result is not the place's
    // type has nothing to assign back. Refused rather than quietly changing what the place holds.
    "a compound assignment through a result that is not the place's type is refused" in {
      val e = err(space + """var v = Vec2(1.0, 2.0)
                            |v *= Vec2(3.0, 4.0)""".stripMargin)

      e should include("'*=' updates Vec2")
      e should include("gives real")
    }

    "while one whose result is the place's type still updates it" in {
      run(space + """var v = Vec2(1.0, 2.0)
                    |v *= 3.0
                    |print(v.x, v.y)""".stripMargin) shouldBe "3 6\n"
    }

    // A method written to return something the trait's own arguments do not describe is still held to
    // the declaration — the result being writable is not the same as its being unchecked.
    "a method disagreeing with the result its own 'impl' declared is refused" in {
      err("""struct V
            |    x: real
            |impl Mul[V, real] for V
            |    mul(self, o: V) -> V = V(self.x * o.x)""".stripMargin) should
        include(s"returns V, but trait '${lib("Mul")}' declares real")
    }

    "an unwritten result still defaults to the implementing type" in {
      err("""struct V
            |    x: real
            |impl Mul[V] for V
            |    mul(self, o: V) -> real = self.x * o.x""".stripMargin) should
        include(s"returns real, but trait '${lib("Mul")}' declares V")
    }

    // A bound names the operands it needs and the result comes with the implementation, so a bounded
    // body multiplying two `T`s gets a `T` — the homogeneous reading, unchanged.
    "a bare bound is still the homogeneous one" in {
      run("""twice[T: Mul](x: T) -> T = x * x
            |print(twice(6))""".stripMargin) shouldBe "36\n"
    }

    // And a bound may name the result, which is what lets a bounded body use one it did not declare.
    "a bound may name the result, and the body gets it" in {
      run(space + """len2[T: Mul[T, real]](v: T) -> real = v * v
                    |print(len2(Vec2(3.0, 4.0)))""".stripMargin) shouldBe "25\n"
    }

    /** The result of one dispatched operator feeding the next, inside a body whose parameter carries
      * several bounds. This is the position that reads a result off a *bound* rather than off an
      * operand, and a `T` recovered from a bound has had its own promises dropped one level in — so
      * the second operator is the first thing that could fail to find them. `sysl.crypto` is written
      * out of exactly this shape.
      */
    "one operator's result is an operand of the next, under several bounds" in {
      run("""two[T: BitAnd + BitXor](x: T, y: T) -> T = (x & y) ^ y
            |print(two(6, 3))""".stripMargin) shouldBe "1\n"
    }

    "and it holds three deep, where each result feeds another trait's operator" in {
      run("""mix[T: BitAnd + BitXor + BitOr](x: T, y: T, z: T) -> T = ((x & y) ^ z) | (x & z)
            |print(mix(12, 10, 6))""".stripMargin) shouldBe "14\n"
    }
  }
}
