package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** A type implementing one trait at more than one argument list.
 *
 * The rule `02` used to state was that a trait is implemented once per type, and the reason given
 * was that a trait's members become the type's while a type's members are one namespace (`08`). The
 * namespace argument still holds; what changed is that a parameterized trait is a **family** of
 * promises, and the argument list is a thing every use of one already carries — an operator has the
 * pair of operands, a bound names the arguments, a call passes values of those types. So the
 * argument list selects, and the selection is determined rather than preferred: nothing here ranks
 * two candidates, and a call that determines neither is reported.
 *
 * The suite is in two halves. The first asks whether what the design documents *claim* is true; the
 * second asks what breaks at the second occurrence, the empty case, and the boundaries between this
 * rule and the ones it had to be fitted between — shapes, generic subjects, conditional blocks and
 * trait objects.
 */
class MultipleImplementationTests extends AnyFreeSpec with RunSupport with CodegenSupport {

  /** A trait taking one argument, and a struct that will implement it more than once. */
  private val sink =
    """trait Sink[T]
      |    put(self, x: T) -> int
      |struct A
      |    tag: int
      |impl Sink[int] for A
      |    put(self, x: int) -> int = self.tag + x
      |impl Sink[real] for A
      |    put(self, x: f64) -> int = self.tag - int(x)
      |""".stripMargin

  "what the documents claim" - {
    // `02 § A trait's members become the type's` — and both implementations' members do, under the
    // one name the trait declared.
    "both implementations' members are reachable by the name the trait wrote" in {
      run(sink + """print(A(10).put(1))
                   |print(A(10).put(1.0))""".stripMargin) shouldBe "11\n9\n"
    }

    // `14 §3` — the operator is dispatched at the type of its right operand, however many
    // implementations there are to dispatch among.
    "an operator dispatches on the pair, at three argument lists" in {
      run("""struct C
            |    v: int
            |impl Mul for C
            |    mul(self, o: C) -> C = C(self.v * o.v)
            |impl Mul[int] for C
            |    mul(self, k: int) -> C = C(self.v + k)
            |impl Mul[bool] for C
            |    mul(self, b: bool) -> C = C(if b then self.v else 0)
            |print((C(3) * C(4)).v)
            |print((C(3) * 4).v)
            |print((C(3) * false).v)""".stripMargin) shouldBe "12\n7\n0\n"
    }

    // `10 §3`'s default is what keeps every bound already written meaning what it meant: a bare
    // `Mul` is `Mul[Self]`, so it names the homogeneous implementation and not "whichever".
    "a bound names one of them, and a bare bound still names the homogeneous one" in {
      run("""struct C
            |    v: int
            |impl Mul for C
            |    mul(self, o: C) -> C = C(self.v * o.v)
            |impl Mul[int] for C
            |    mul(self, k: int) -> C = C(self.v + k)
            |sq[T: Mul](x: T) -> T = x * x
            |bump[T: Mul[int]](x: T) -> T = x * 5
            |print(sq(C(3)).v)
            |print(bump(C(3)).v)""".stripMargin) shouldBe "9\n8\n"
    }

    // Two bounds on one parameter naming one trait at two argument lists — which is the bound-side
    // reading of exactly the same claim.
    "and one parameter may be held to both at once" in {
      run("""struct C
            |    v: int
            |impl Mul for C
            |    mul(self, o: C) -> C = C(self.v * o.v)
            |impl Mul[int] for C
            |    mul(self, k: int) -> C = C(self.v + k)
            |both[T: Mul + Mul[int]](x: T) -> T = x * x * 1
            |print(both(C(3)).v)""".stripMargin) shouldBe "10\n"
    }

    // `08 § one name, one member` is what makes this a question at all — and a property has no
    // arguments, so it is the case where nothing determines the answer.
    "a property has no argument to select with, and is told so" in {
      err("""trait Named[T]
            |    tag(self) -> int
            |struct A
            |    v: int
            |impl Named[int] for A
            |    tag(self) -> int = 1
            |impl Named[real] for A
            |    tag(self) -> int = 2
            |print(A(0).tag)""".stripMargin) should include(
        "'tag' comes from 2 implementations of one trait on A, and the arguments do not say which " +
          "was meant",
      )
    }

    // `02 § A type that implements the trait at other arguments` — the diagnostic now names every
    // implementation there is, because writing one more is the thing to do about it.
    "a bound that names neither is told what the type does implement" in {
      err(sink + """f[X: Sink[bool]](x: X) -> int = 1
                   |print(f(A(0)))""".stripMargin) should include(
        "it implements 'Sink[int]' and 'Sink[real]'",
      )
    }
  }

  "what a trait object does with two of them" - {
    // A table is built for one trait-at-arguments, so its slot has to name the member that
    // implementation brought and not merely one of that name.
    "each object's table points at its own implementation" in {
      run(sink + """var u: &Sink[int] = A(10)
                   |var v: &Sink[real] = A(10)
                   |print(u.put(1))
                   |print(v.put(1.0))""".stripMargin) shouldBe "11\n9\n"
    }

    "and erasing to one says nothing about the other" in {
      err(sink + """var u: &Sink[bool] = A(10)
                   |print(u.put(true))""".stripMargin) should include(
        "a &Sink[bool] needs a type that implements 'Sink[bool]', and A does not",
      )
    }
  }

  "what a second implementation may not be" - {
    "the same argument list twice" in {
      err(sink + """impl Sink[int] for A
                   |    put(self, x: int) -> int = 0""".stripMargin) should include(
        "'A' already implements 'Sink[int]'",
      )
    }

    // A defaulted list is not one promise but one per type it covers, so on a **generic** subject
    // an argument built out of that same subject would coincide with it at one instantiation and
    // not at others.
    "on a generic type, an argument built out of the type itself" in {
      err("""struct Box[T]
            |    v: T
            |impl[T] Mul[Box[int]] for Box[T]
            |    mul(self, o: Box[int]) -> Box[T] = self""".stripMargin) should include(
        s"'Box[int]' is a Box, and a '${lib("Mul")}' whose arguments default names the type it is " +
          "written for — so at one Box this block and a defaulted one would promise the same thing",
      )
    }

    // The same rule does not reach an argument built out of something else, which is the whole
    // point of allowing the second implementation.
    "though an argument built out of anything else is fine" in {
      run("""struct Box[T]
            |    v: T
            |impl[T] Mul for Box[T]
            |    mul(self, o: Box[T]) -> Box[T] = o
            |impl[T] Mul[int] for Box[T]
            |    mul(self, k: int) -> Box[T] = self
            |print((Box(7) * Box(9)).v)
            |print((Box(7) * 2).v)""".stripMargin) shouldBe "9\n7\n"
    }
  }

  "the boundary with a shape" - {
    // The arguments do not reach across this boundary, and the reason is the same one that makes
    // them work everywhere else. Several implementations are told apart inside one namespace; a
    // shape's members and a written-out type's are filed under two different owner keys and a lookup
    // takes one or the other, so the second would be one no call could name. It stays refused, and
    // this is the case that says why — the two blocks here write *different* arguments.
    "different written argument lists do not coexist either" in {
      err("""trait Sink[T]
            |    put(self, x: T) -> int
            |impl[E] Sink[int] for [3]E
            |    put(self, x: int) -> int = x + 1
            |impl Sink[real] for [3]int
            |    put(self, x: f64) -> int = int(x) + 2""".stripMargin) should include(
        "every array of 3 already implements 'Sink', so '[3]int' has an implementation and cannot " +
          "be given a second one",
      )
    }

    "and the same written list is the collision it always was" in {
      err("""trait Sink[T]
            |    put(self, x: T) -> int
            |impl[E] Sink[int] for [3]E
            |    put(self, x: int) -> int = x
            |impl Sink[int] for [3]int
            |    put(self, x: int) -> int = x""".stripMargin) should include(
        "already implements 'Sink'",
      )
    }

    "and a block that leaves the arguments out is no different" in {
      err("""trait Sink[T = Self]
            |    put(self, x: T) -> int
            |impl[E] Sink for [3]E
            |    put(self, x: [3]E) -> int = 1
            |impl Sink[real] for [3]int
            |    put(self, x: f64) -> int = 2""".stripMargin) should include(
        "already implements 'Sink'",
      )
    }
  }

  "what a call that determines nothing gets" - {
    "an argument matching neither implementation" in {
      err(sink + "print(A(0).put(true))") should include(
        "'put' comes from 2 implementations of one trait on A, and none of them takes (bool) — " +
          "write the argument at the type of the implementation that was meant",
      )
    }

    // A literal has no type of its own to be matched by, and picking the "nearest" candidate would
    // be a preference rule — which is the thing this resolution deliberately is not.
    "a literal whose default type matches neither" in {
      err("""trait Sink[T]
            |    put(self, x: T) -> int
            |struct A
            |    tag: int
            |impl Sink[i64] for A
            |    put(self, x: i64) -> int = 1
            |impl Sink[real] for A
            |    put(self, x: f64) -> int = 2
            |print(A(0).put(3))""".stripMargin) should include(
        "none of them takes (int)",
      )
    }

    "and the wrong number of arguments to any of them" in {
      err(sink + "print(A(0).put(1, 2))") should include(
        "'put' comes from 2 implementations of one trait on A",
      )
    }
  }

  "what the order of the blocks does not change" - {
    // The suffix a second block's members carry is an internal name, so which block was written
    // first has to be invisible. The two programs differ only in that order.
    "the homogeneous block first" in {
      run("""struct C
            |    v: int
            |impl Mul for C
            |    mul(self, o: C) -> C = C(self.v * o.v)
            |impl Mul[int] for C
            |    mul(self, k: int) -> C = C(self.v + k)
            |print((C(3) * C(4)).v)
            |print((C(3) * 4).v)""".stripMargin) shouldBe "12\n7\n"
    }

    "and the same two the other way round" in {
      run("""struct C
            |    v: int
            |impl Mul[int] for C
            |    mul(self, k: int) -> C = C(self.v + k)
            |impl Mul for C
            |    mul(self, o: C) -> C = C(self.v * o.v)
            |print((C(3) * C(4)).v)
            |print((C(3) * 4).v)""".stripMargin) shouldBe "12\n7\n"
    }
  }

  "what else still holds" - {
    // A compound assignment reads its value at the type the operator's own bound names, which is
    // the same question the binary form asks one step in.
    "a compound assignment picks by the value's type" in {
      run("""struct C
            |    v: int
            |impl Mul for C
            |    mul(self, o: C) -> C = C(self.v * o.v)
            |impl Mul[int] for C
            |    mul(self, k: int) -> C = C(self.v + k)
            |var a = C(3)
            |a *= C(4)
            |print(a.v)
            |a *= 5
            |print(a.v)""".stripMargin) shouldBe "12\n17\n"
    }

    // A conditional block's condition is asked of the implementation the arguments selected, not of
    // whichever one happens to be filed first.
    "a conditional block's own bound is still the one reported" in {
      err("""trait Show
            |    show(self) -> int
            |struct Box[U]
            |    v: U
            |impl[U] Mul[int] for Box[U]
            |    mul(self, k: int) -> Box[U] = self
            |impl[U: Show] Mul for Box[U]
            |    mul(self, o: Box[U]) -> Box[U] = o
            |sq[T: Mul](x: T) -> T = x * x
            |print(1)
            |var q = sq(Box(1))""".stripMargin) should include(
        "the 'impl' that covers it asks 'Show' of int, which does not implement it",
      )
    }

    // A trait default is copied per implementing type; two implementations of one trait are two
    // copies, each reaching the members of its own block.
    "a trait's default body is copied once per implementation" in {
      run("""trait Sink[T]
            |    put(self, x: T) -> int
            |    twice(self, x: T) -> int = self.put(x) + self.put(x)
            |struct A
            |    tag: int
            |impl Sink[int] for A
            |    put(self, x: int) -> int = x
            |impl Sink[real] for A
            |    put(self, x: f64) -> int = int(x) + 100
            |print(A(0).twice(1))
            |print(A(0).twice(1.0))""".stripMargin) shouldBe "2\n202\n"
    }

    // The scalars are `Mul` at themselves and at nothing else (`14 §5`, `01`'s no-promotion rule).
    "a built-in still cannot be given an operator implementation" in {
      err("""impl Mul for int
            |    mul(self, k: int) -> int = self""".stripMargin) should include("the compiler provides")
    }

    // A second argument list is not a way in either, and it never reaches that rule: `Mul` is the
    // prelude's and so is `int`, so a program has nowhere to write the block at all (`02`).
    "and a second argument list does not open one" in {
      err("""struct C
            |    v: int
            |impl Mul[C] for int
            |    mul(self, c: C) -> int = self""".stripMargin) should include("so this one has no home")
    }
  }

  /** The argument list selects **which** implementation runs; it does not reach the result.
   *
   * Every operator row is `op(self, rhs: Rhs) -> Self`, so an implementation may say what it takes
   * and never what it produces. That is invisible in the arithmetic the rule was designed against —
   * scaling a vector by a scalar gives a vector, and `guide/fft` wanted nothing else — and it is
   * the first thing a timeline asks for: two points subtract to a *distance*, which is a different
   * type from either of them. The three-quarters that do work are pinned beside it, because what
   * makes this worth recording is that it is one row of an otherwise complete algebra.
   */
  "what an argument list does not reach" - {
    val timeline =
      """struct Instant
        |    us: long
        |struct Duration
        |    us: long
        |""".stripMargin

    "a moment plus a length is a moment" in {
      run(timeline +
        """impl Add[Duration] for Instant
          |    add(self, d: Duration) -> Instant = Instant(self.us + d.us)
          |print((Instant(100i64) + Duration(5i64)).us)""".stripMargin) shouldBe "105\n"
    }

    "a moment minus a length is a moment" in {
      run(timeline +
        """impl Sub[Duration] for Instant
          |    sub(self, d: Duration) -> Instant = Instant(self.us - d.us)
          |print((Instant(100i64) - Duration(5i64)).us)""".stripMargin) shouldBe "95\n"
    }

    "and two lengths add to a length" in {
      run(timeline +
        """impl Add for Duration
          |    add(self, d: Duration) -> Duration = Duration(self.us + d.us)
          |print((Duration(100i64) + Duration(5i64)).us)""".stripMargin) shouldBe "105\n"
    }

    // The one the whole subject is named after, and the one that cannot be written.
    "but two moments cannot subtract to a length" in {
      err(timeline +
        """impl Sub[Instant] for Instant
          |    sub(self, o: Instant) -> Duration = Duration(self.us - o.us)
          |print(1)""".stripMargin) should include(s"method 'sub' returns Duration, but trait '${lib("Sub")}' declares Instant")
    }

    // Nor does dispatching on the pair rescue it: the two implementations differ in what they take,
    // which the rule above allows, and in what they produce, which no row can express.
    "and giving the subject both argument lists does not help" in {
      err(timeline +
        """impl Sub[Duration] for Instant
          |    sub(self, d: Duration) -> Instant = Instant(self.us - d.us)
          |impl Sub[Instant] for Instant
          |    sub(self, o: Instant) -> Duration = Duration(self.us - o.us)
          |print(1)""".stripMargin) should include(s"but trait '${lib("Sub")}' declares Instant")
    }
  }
}
