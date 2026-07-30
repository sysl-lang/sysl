package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Diagnostics for trait objects: what a trait has to look like before one can be formed at all,
 * and what a value has to be before it can be erased into one.
 */
class TraitObjectErrorTests extends AnyFreeSpec with CodegenSupport {

  private val shape =
    """trait Shape
      |    area(self) -> int
      |struct Rect
      |    w: int
      |    h: int
      |impl Shape for Rect
      |    area(self) -> int = self.w * self.h
      |struct Plain
      |    n: int
      |""".stripMargin

  "object safety" - {
    // A bare `*Add` is `*Add[Self, Self]`, and both arguments would have to be the type the object
    // forgot. The advice is a spelling rather than "this trait cannot be erased", because writing the
    // arguments out really does rescue it now that an operator's result is an argument (`14 §7`) —
    // `&Mul[real, real]` is a formable object, which `TraitObjectRunTests` dispatches through.
    "an operator trait is refused bare, because its arguments default to 'Self'" in {
      val e = err("var s: *Add = 1")

      e should include("defaults to 'Self'")
      e should include("write the argument")
    }

    // A built-in's membership is the compiler's rule rather than a table, so there is nothing to
    // erase *it* through — but note which complaint arrives: the arguments being written left the
    // signature with no `Self` in it at all, so object safety is no longer what stops this.
    "an operator trait at written arguments is not what object safety refuses" in {
      val e = err("var s: *Add[int, int] = 1")

      e should not include "mentions 'Self' away from its receiver"
      e should include("*Add[int, int]")
    }

    "so is a 'Self' result" in {
      err(
        """trait Copy
          |    copy(self) -> Self
          |var c: *Copy = 1""".stripMargin,
      ) should include("mentions 'Self' away from its receiver")
    }

    "a 'Self' buried inside another type is found too" in {
      err(
        """trait Pair
          |    pair(self, other: *Self) -> int
          |var p: *Pair = 1""".stripMargin,
      ) should include("mentions 'Self' away from its receiver")
    }

    "an associated function has no receiver to dispatch on" in {
      err(
        """trait Make
          |    build() -> int
          |    use(self) -> int
          |var m: *Make = 1""".stripMargin,
      ) should include("no receiver to dispatch on")
    }

    // A `&self` method wants its receiver inside a box, which only the counted object carries — so
    // this is the one rule that depends on which sigil was written.
    "a '&self' method is refused by a raw object and accepted by a counted one" in {
      val trait_ =
        """trait T
          |    go(&self)
          |    v(self) -> int
          |struct S
          |    n: int
          |impl T for S
          |    go(&self)
          |        self.n += 1
          |    v(self) -> int = self.n
          |""".stripMargin

      err(trait_ + "var m: *T = 1") should include("write '&T' instead")

      ir(trait_ + "var m: &T = S(1)\nm.go()\nprint(m.v())") should include("@vt.ref.T.S")
    }
  }

  "forming one" - {
    "a bare trait name is not a type" in {
      err(shape + "var s: Shape = Rect(1, 2)") should
        include("describes behaviour rather than a layout")
    }

    "a type with no 'impl' cannot be erased" in {
      err(shape + "var p = Plain(1)\nvar s: *Shape = &p") should
        include("needs a type that implements 'Shape', and Plain does not")
    }

    // A raw object points at a value that has to be somewhere, and taking the address of a
    // temporary silently would put a dangling pointer in the program.
    "a raw object needs an address, not a value" in {
      err(shape + "var s: *Shape = Rect(1, 2)") should include("write '&' in front of the Rect")
    }

    "a bound is not satisfied by an object over the same trait" in {
      err(shape + "f[T: Shape](x: T) -> int = x.area()\nvar r = Rect(1,2)\nvar s: *Shape = &r\nprint(f(s))") should
        include("but *Shape does not")
    }
  }

  "using one" - {
    "it cannot be dereferenced" in {
      err(shape + "var r = Rect(1,2)\nvar s: *Shape = &r\nprint((*s).w)") should
        include("has forgotten what it points at")
    }

    "it has no fields" in {
      err(shape + "var r = Rect(1,2)\nvar s: *Shape = &r\nprint(s.w)") should
        include("has no fields, and trait 'Shape' declares no 'w'")
    }

    "a method the trait does not declare is refused, and the ones it does are named" in {
      err(shape + "var r = Rect(1,2)\nvar s: *Shape = &r\nprint(s.nope())") should
        include("trait 'Shape' declares no method 'nope' — it has 'area'")
    }

    "a call is checked against the trait's own signature" in {
      err(shape + "var r = Rect(1,2)\nvar s: *Shape = &r\nprint(s.area(1))") should
        include("method 'Shape.area' takes 0 arguments")
    }

    // Two objects over one value through different traits are the same value and different tables,
    // so what equality would mean is the trait's question rather than the machine's.
    "two objects do not compare" in {
      err(shape + "var r = Rect(1,2)\nvar a: *Shape = &r\nprint(a == a)") should
        include("'==' is not defined for *Shape")
    }

    "a counted object is never absent" in {
      err(shape + "var s: &Shape = null") should include("'null' is a raw pointer")
    }
  }

  /** The two sigils are two types, and there is no way to write the weaker one from the stronger.
   *
   * For a plain reference there is: `&*r` is the address of the place `*r`, so a `&T` reaches a
   * function written against `*T` and the crossing stays written down (`PointerRunTests`). An
   * object has no dereference, so the same spelling has nothing to say — which leaves a function
   * that only asks a shape its area having to exist once per sigil. `guide/shapes` writes both.
   */
  "the two sigils do not convert" - {
    "a counted object is not accepted where a raw one is wanted" in {
      err(shape + "f(s: *Shape) -> int = s.area()\nvar o: &Shape = Rect(3, 4)\nprint(f(o))") should
        include("'s' of 'f' is *Shape, but &Shape was given")
    }

    // The other direction is refused for a stronger reason: a raw object points at a value with no
    // count to take a share of, so accepting one would be inventing ownership.
    "a raw object is not accepted where a counted one is wanted" in {
      err(shape + "f(s: &Shape) -> int = s.area()\nvar r = Rect(3, 4)\nvar o: *Shape = &r\nprint(f(o))") should
        include("'s' of 'f' is &Shape, but *Shape was given")
    }

    "and the lend that works for a plain reference has nothing to say about an object" in {
      err(shape + "f(s: *Shape) -> int = s.area()\nvar o: &Shape = Rect(3, 4)\nprint(f(&*o))") should
        include("has forgotten what it points at")
    }
  }

  /** Erasure keeps the trait that was erased to and drops every other one the type implements.
   *
   * This is what the type says rather than a gap in the implementation — an object offers the
   * trait's members and nothing else — but it is worth pinning, because it is the cost a program
   * pays at the moment it stops knowing the type. What lifts it is the trait **requiring**
   * `Display`, which is what the advice names: the fix is a word on the declaration, not a cast at
   * the use.
   */
  "erasing drops the other traits the type implements" in {
    val src =
      """trait Shape
        |    area(self) -> int
        |struct Rect
        |    w: int
        |    h: int
        |impl Shape for Rect
        |    area(self) -> int = self.w * self.h
        |impl Display for Rect
        |    display(self, out: *Writer, fmt: FormatSpec) = display_str("a rect", out, fmt)
        |var r = Rect(3, 4)
        |print(f"${r}")
        |var o: &Shape = Rect(3, 4)
        |print(f"${o}")""".stripMargin

    err(src) should include("cannot make a string of a &Shape value — an object offers what its " +
      "trait declares and what that trait requires, so write 'trait Shape: Display'")
  }
}
