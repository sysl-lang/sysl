package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** A type parameter that carries a default (`10 §3`): `trait Scale[R = Self]`, `struct Pair[A, B = A]`.
  *
  * The customer is the operator catalog. `14 §7` records that a heterogeneous operand — `Complex * f64`
  * — wants `Mul[Rhs]` rather than `Mul`, and that the change cannot be made without defaults: every
  * `impl Mul for Point` already written would become `impl Mul[Point] for Point`, and `[T: Mul]` would
  * stop saying what it says now. So the case that matters most here is `Self` as the default, which is
  * what makes those two spellings mean the same thing.
  *
  * A default belongs only where arguments are **written**. sysl offers no call-site type arguments at
  * all (`10 §2`), so a function's, a method's, and an `impl` block's parameters are solved rather than
  * supplied, and a default there is refused — the thing that would be useful in those three is a
  * fallback for an inference that found nothing, which is a different feature.
  */
class DefaultTypeParamTests extends AnyFreeSpec with RunSupport with CodegenSupport {

  /** A trait whose parameter is the operand type, defaulting to the implementing type — the shape the
    * catalog's `Mul` is meant to take.
    */
  private val scale =
    """trait Scale[R = Self]
      |    scale(self, k: R) -> Self
      |struct P
      |    v: int
      |impl Scale for P
      |    scale(self, k: P) -> P = P(self.v * k.v)
      |""".stripMargin

  "what a default stands in for" - {
    "a trait applied to nothing takes its default" in {
      run(
        """trait Sink[T = int]
          |    put(self, x: T) -> int
          |struct C
          |    n: int
          |impl Sink for C
          |    put(self, x: int) -> int = self.n + x
          |f[T: Sink](c: T) -> int = c.put(5)
          |print(f(C(10)))""".stripMargin,
      ) shouldBe "15\n"
    }

    // The half the catalog is waiting on: an `impl` written with no arguments means the one written
    // with `Self`'s value, so nothing already in the language has to be respelled.
    "'Self' as a default is the implementing type at an impl" in {
      run(scale + "print(P(6).scale(P(7)).v)") shouldBe "42\n"
    }

    "and writing that argument out is the same implementation" in {
      run(
        """trait Scale[R = Self]
          |    scale(self, k: R) -> Self
          |struct P
          |    v: int
          |impl Scale[P] for P
          |    scale(self, k: P) -> P = P(self.v * k.v)
          |print(P(6).scale(P(7)).v)""".stripMargin,
      ) shouldBe "42\n"
    }

    // And the other half: a bound naming the trait bare asks for it at the bounded parameter, so
    // `[T: Scale]` keeps meaning what it meant before the trait took a parameter at all.
    "'Self' as a default is the parameter itself at a bound" in {
      run(scale + "f[T: Scale](a: T, b: T) -> T = a.scale(b)\nprint(f(P(6), P(7)).v)") shouldBe "42\n"
    }

    "a bound may still fix the argument to something else" in {
      run(
        """trait Scale[R = Self]
          |    scale(self, k: R) -> Self
          |struct P
          |    v: int
          |impl Scale[int] for P
          |    scale(self, k: int) -> P = P(self.v * k)
          |f[T: Scale[int]](a: T) -> T = a.scale(3)
          |print(f(P(14)).v)""".stripMargin,
      ) shouldBe "42\n"
    }

    "a struct takes its default" in {
      run(
        """struct Pair[A, B = A]
          |    x: A
          |    y: B
          |var p: Pair[int] = Pair(3, 4)
          |print(p.x + p.y)""".stripMargin,
      ) shouldBe "7\n"
    }

    "an enum takes its default" in {
      run(
        """enum Slot[T = int]
          |    Empty
          |    Full(value: T)
          |held(s: Slot) -> int = s match
          |    Full(v) -> v
          |    Empty -> 0
          |var s: Slot = Full(9)
          |print(held(s))""".stripMargin,
      ) shouldBe "9\n"
    }

    "a default may name a parameter written before it" in {
      run(
        """struct Pair[A, B = A]
          |    x: A
          |    y: B
          |var p: Pair[string] = Pair("a", "b")
          |print(p.x + p.y)""".stripMargin,
      ) shouldBe "ab\n"
    }

    "and may build a type out of one" in {
      run(
        """struct Holder[A, B = []A]
          |    one: A
          |    many: B
          |var xs = [1, 2, 3]
          |var h: Holder[int] = Holder(4, xs[0..<3])
          |print(h.one + h.many[2])""".stripMargin,
      ) shouldBe "7\n"
    }

    /** Filling happens before anything is keyed on the arguments, so the written-out and left-out
      * spellings are one instantiation rather than two that happen to have the same fields. Asserted
      * on the emitted module, since that is the only place two would show up as two.
      */
    "the filled and the written spelling are one instantiation" in {
      val out = ir(
        """struct Pair[A, B = A]
          |    x: A
          |    y: B
          |var p: Pair[int] = Pair(1, 2)
          |var q: Pair[int, int] = Pair(3, 4)
          |print(p.x + q.y)""".stripMargin,
      )

      out should include("%struct.Pair.int.int = type { i32, i32 }")
      out.linesIterator.count(_.startsWith("%struct.Pair")) shouldBe 1
    }

    "different fills are different types" in {
      val out = ir(
        """struct Pair[A, B = A]
          |    x: A
          |    y: B
          |var p: Pair[int] = Pair(1, 2)
          |var q: Pair[int, bool] = Pair(3, true)
          |print(p.x + q.x)""".stripMargin,
      )

      out should include("%struct.Pair.int.int = type { i32, i32 }")
      out should include("%struct.Pair.int.bool = type { i32, i1 }")
    }

    "a default may name a type declared below the one that defaults to it" in {
      run(
        """struct Holder[T = Later]
          |    v: T
          |struct Later
          |    n: int
          |var h: Holder = Holder(Later(7))
          |print(h.v.n)""".stripMargin,
      ) shouldBe "7\n"
    }
  }

  "what a requirement does with one" - {
    /** A required trait is applied like any other, so it takes the defaults too — and `Self` in one
      * of them is the type implementing the *requiring* trait, which is the same type all the way
      * down a chain of requirements.
      */
    "a required trait takes its default" in {
      run(
        """trait Scale[R = Self]
          |    scale(self, k: R) -> Self
          |trait Vector: Scale
          |    dim(self) -> int
          |struct P
          |    v: int
          |impl Scale for P
          |    scale(self, k: P) -> P = P(self.v * k.v)
          |impl Vector for P
          |    dim(self) -> int = 1
          |f[T: Vector](a: T, b: T) -> T = a.scale(b)
          |print(f(P(6), P(7)).v)""".stripMargin,
      ) shouldBe "42\n"
    }

    /** The filled argument has to be the one the `impl` was filed under, or the requirement would be
      * "satisfied" by an implementation at other arguments. `impl Scale[int] for P` does not answer a
      * requirement of `Scale[Self]`.
      */
    "an implementation at other arguments does not answer the requirement" in {
      err(
        """trait Scale[R = Self]
          |    scale(self, k: R) -> Self
          |trait Vector: Scale
          |    dim(self) -> int
          |struct P
          |    v: int
          |impl Scale[int] for P
          |    scale(self, k: int) -> P = P(self.v * k)
          |impl Vector for P
          |    dim(self) -> int = 1""".stripMargin,
      ) should include("requires 'Scale[P]'")
    }
  }

  "what the declaration has to satisfy" - {
    /** Arguments are written left to right, so a parameter with no default sitting behind one that
      * has could never be reached — leaving the earlier one out would leave nothing for the later one
      * to be written after.
      */
    "a defaulted parameter may not come before an undefaulted one" in {
      err("struct S[A = int, B]\n    x: A\n    y: B") should include(
        "'B' has no default and comes after 'A', which has one",
      )
    }

    "a default may not name a parameter fixed after it" in {
      err("struct S[A = B, B]\n    x: A\n    y: B") should include("'B' has no default and comes after 'A'")
    }

    "nor one fixed after it when the shape is otherwise legal" in {
      err("struct S[A, B = C, C = int]\n    x: A\n    y: B\n    z: C") should include(
        "the default for 'B' names 'C', which is fixed after it",
      )
    }

    "a default may not name its own parameter" in {
      err("struct S[A = A]\n    x: int") should include("the default for 'A' names 'A'")
    }

    "a struct has no 'Self' for a default to name" in {
      err("struct S[A = Self]\n    x: int") should include("'Self' is the type implementing a trait")
    }

    "and neither has an enum" in {
      err("enum E[A = Self]\n    Empty\n    Full(value: A)") should include(
        "'Self' is the type implementing a trait",
      )
    }

    "a default has to name a type" in {
      err("struct S[A = Nope]\n    x: A") should include("unknown type 'Nope'")
    }
  }

  "where a parameter is solved rather than written" - {
    /** The three refusals are one rule: sysl has no call-site type arguments (`10 §2`), so in none of
      * these is there an argument list with a gap for a default to fill.
      */
    "a function may not default a type parameter" in {
      err("f[T = int](x: T) -> T = x\nprint(f(1))") should include(
        "'T' is a type parameter of the function 'f', whose type parameters are solved",
      )
    }

    "nor may a method" in {
      err("struct P\n    v: int\n    m[T = int](self, x: T) -> int = self.v") should include(
        "'T' is a type parameter of the method 'P.m'",
      )
    }

    /** A trait's method is refused one step earlier and for a larger reason — it may not be generic
      * at all yet — so the default never becomes the thing that is wrong with it. Pinned so that
      * whichever of the two is lifted first, the other is still known to be here.
      */
    "a trait's method cannot be generic, so it cannot default one either" in {
      err("trait T\n    m[U = int](self, x: U) -> int") should include(
        "generic methods are not supported yet",
      )
    }

    "nor may an 'impl' block" in {
      err(
        """trait Show
          |    show(self) -> string
          |struct Box[T]
          |    v: T
          |impl[T = int] Show for Box[T]
          |    show(self) -> string = "box"""".stripMargin,
      ) should include("is a type parameter of the 'impl' block")
    }
  }

  "what a use has to satisfy" - {
    "supplying more arguments than there are parameters" in {
      err("struct S[A, B = A]\n    x: A\n    y: B\nvar s: S[int, int, int] = S(1, 2)") should include(
        "type 'S' takes between 1 and 2 type arguments, but 3 type arguments were given",
      )
    }

    "supplying fewer than the ones with no default" in {
      err("struct S[A, B = A]\n    x: A\n    y: B\nvar s: S = S(1, 2)") should include(
        "type 'S' takes between 1 and 2 type arguments, but 0 type arguments were given",
      )
    }

    "a trait applied to too many is told the same way" in {
      err(
        """trait Sink[T = int]
          |    count(self) -> int
          |f[U: Sink[int, int]](x: U) -> int = x.count()""".stripMargin,
      ) should include("trait 'Sink' takes between 0 and 1 type arguments, but 2 type arguments were given")
    }

    /** A trait whose method names the parameter, applied to the wrong number of arguments, is worth
      * its own case: the arity is what is wrong, and the reader should not also be told that the
      * trait's own signature does not resolve. Written against a trait with **no** default so that
      * it says something about arity checking generally rather than about defaults.
      */
    "an arity mistake does not also report the trait's own signature" in {
      val out = err(
        """trait Sink[T]
          |    put(self, x: T) -> int
          |f[U: Sink[int, int]](x: U) -> int = x.put(1)""".stripMargin,
      )

      out should include("takes 1 type argument, but 2 type arguments were given")
      out should not include "unknown type 'T'"
    }

    /** The parameter's own bound is checked on the filled list by the deferral every other type
      * argument goes through, so a default that fails it is caught wherever the declaration is
      * applied — and against the `impl` blocks, which are registered after the declaration is read.
      */
    "a default that fails the parameter's bound is caught at the use" in {
      err(
        """trait Named
          |    name(self) -> string
          |struct Plain
          |    n: int
          |struct Box[T: Named = Plain]
          |    v: T
          |var b: Box = Box(Plain(1))""".stripMargin,
      ) should include("'Named'")
    }

    "and a fill that meets it is accepted" in {
      run(
        """trait Named
          |    name(self) -> string
          |struct Plain
          |    n: int
          |impl Named for Plain
          |    name(self) -> string = "plain"
          |struct Box[T: Named = Plain]
          |    v: T
          |var b: Box = Box(Plain(1))
          |print(b.v.name())""".stripMargin,
      ) shouldBe "plain\n"
    }
  }

  "what erasure does with one" - {
    /** An object has forgotten which type it holds, so there is no `Self` for a default to stand for.
      * The argument has to be written out, and saying that here is better than letting `Self` resolve
      * to nothing further in.
      */
    "an object cannot take a default of 'Self'" in {
      err(
        """trait Scale[R = Self]
          |    scale(self, k: R) -> int
          |f(x: &Scale) -> int = x.scale(x)""".stripMargin,
      ) should include("an object has forgotten which type it holds")
    }

    "with the argument written out it is an ordinary object" in {
      run(
        """trait Scale[R = Self]
          |    scale(self, k: R) -> int
          |struct P
          |    v: int
          |impl Scale[int] for P
          |    scale(self, k: int) -> int = self.v * k
          |f(x: &Scale[int]) -> int = x.scale(3)
          |var o: &Scale[int] = P(14)
          |print(f(o))""".stripMargin,
      ) shouldBe "42\n"
    }

    "a trait defaulting to something concrete erases without being written out" in {
      run(
        """trait Sink[T = int]
          |    put(self, x: T) -> int
          |struct C
          |    n: int
          |impl Sink for C
          |    put(self, x: int) -> int = self.n + x
          |f(s: &Sink) -> int = s.put(5)
          |var o: &Sink = C(10)
          |print(f(o))""".stripMargin,
      ) shouldBe "15\n"
    }
  }
}
