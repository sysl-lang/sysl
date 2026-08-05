package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Diagnostics for a property declared in a trait (`02`).
 *
 * A property and a method are told apart by shape rather than by a keyword — one has a parameter
 * list and the other has none — so the mistakes worth reporting well are the ones where the two
 * shapes are confused, at the `impl` that supplies the member and at every site that reads it.
 */
class TraitPropertyErrorTests extends AnyFreeSpec with CodegenSupport {

  "conformance tells the two shapes apart" - {
    // A property and an associated function both have no receiver to compare, so the kind is what
    // has to be checked — otherwise `size(self)` would quietly stand in for `size`.
    "a method may not stand in for a property the trait asks for" in {
      err(
        """trait Sized
          |    size -> int
          |struct Box
          |    n: int
          |impl Sized for Box
          |    size(self) -> int = self.n""".stripMargin
      ) should include("is a property of trait 'Sized'")
    }

    "a property may not stand in for a method the trait asks for" in {
      err(
        """trait Sized
          |    size(self) -> int
          |struct Box
          |    n: int
          |impl Sized for Box
          |    size -> int = self.n""".stripMargin
      ) should include("is a method of trait 'Sized'")
    }

    "a property's result is held to the trait's" in {
      err(
        """trait Sized
          |    size -> int
          |struct Box
          |    n: int
          |impl Sized for Box
          |    size -> string = "big"""".stripMargin
      ) should include("but trait 'Sized' declares")
    }

    // A diagnostic that called this a missing *method* would send the reader off to write a
    // parameter list, which is the one thing that would keep it from conforming.
    "a property with no default is still required of an impl, and named as one" in {
      err(
        """trait Sized
          |    size -> int
          |struct Box
          |    n: int
          |impl Sized for Box""".stripMargin
      ) should include("property 'size' is missing")
    }

    "a property no trait declares cannot be defined by an impl" in {
      err(
        """trait Sized
          |    size -> int
          |struct Box
          |    n: int
          |impl Sized for Box
          |    size -> int = self.n
          |    width -> int = 1""".stripMargin
      ) should include("declares no property 'width'")
    }

    // A property is filed under the type's members like any other, so it collides with a field of
    // the same name exactly as an inherent property would.
    "an inherited property may not collide with a field of the same name" in {
      err(
        """trait Sized
          |    size -> int = 0
          |struct Box
          |    size: int
          |impl Sized for Box""".stripMargin
      ) should include("both a field and a member named 'size'")
    }
  }

  "reading one, and not reading one" - {
    "a property read with parentheses says to drop them" in {
      err(
        """trait Sized
          |    size -> int
          |struct Box
          |    n: int
          |impl Sized for Box
          |    size -> int = self.n
          |print(Box(1).size())""".stripMargin
      ) should include("without '()'")
    }

    "a method read without parentheses says to add them" in {
      err(
        """trait Sized
          |    size(self) -> int
          |struct Box
          |    n: int
          |impl Sized for Box
          |    size(self) -> int = self.n
          |print(Box(1).size)""".stripMargin
      ) should include("call it with 'size(…)'")
    }

    "a property named on a trait object with parentheses says to drop them" in {
      err(
        """trait Sized
          |    size -> int
          |struct Box
          |    n: int
          |impl Sized for Box
          |    size -> int = self.n
          |show(s: *Sized) = print(s.size())
          |var b = Box(1)
          |show(&b)""".stripMargin
      ) should include("without '()'")
    }

    "a name a trait object's trait does not declare is refused" in {
      err(
        """trait Sized
          |    size -> int
          |struct Box
          |    n: int
          |impl Sized for Box
          |    size -> int = self.n
          |show(s: *Sized) = print(s.width)
          |var b = Box(1)
          |show(&b)""".stripMargin
      ) should include("declares no 'width'")
    }
  }

  "a bound is what licenses the read" - {
    // The definition-time pass reports this whether or not anything ever instantiates the function,
    // which is what makes the bound worth naming rather than deferring to a call site.
    "an unbounded parameter is told which bound would license the property" in {
      err("size[T](x: T) -> int = x.size") should include("no trait declares a property 'size'")
    }

    "a property one trait declares names that trait as the bound to write" in {
      err(
        """trait Sized
          |    size -> int
          |widest[T](x: T) -> int = x.size""".stripMargin
      ) should include("'size' needs 'T: Sized'")
    }

    "a property two traits declare asks for a bound without guessing which" in {
      val out = err(
        """trait Sized
          |    size -> int
          |trait Weighed
          |    size -> int
          |widest[T](x: T) -> int = x.size""".stripMargin
      )

      out should include("'Sized'")
      out should include("'Weighed'")
    }

    // The bound is spent on what the trait declares and no more, so reaching for a method through a
    // property's spelling is a mistake the trait itself settles.
    "a bounded parameter reading a method as a property is told to call it" in {
      err(
        """trait Sized
          |    size(self) -> int
          |widest[T: Sized](x: T) -> int = x.size""".stripMargin
      ) should include("call it with 'size(…)'")
    }

    "a bound licenses only the property its own trait declares" in {
      err(
        """trait Sized
          |    size -> int
          |trait Weighed
          |    mass -> int
          |widest[T: Sized](x: T) -> int = x.mass""".stripMargin
      ) should include("'mass' needs 'T: Weighed'")
    }
  }

  "a default property is checked at the trait" - {
    // A default property's body is a default like any other, so it is walked once at the trait with
    // its receiver abstract — which is what catches this with nothing implementing the trait at all.
    "a default property reading a field of its receiver is refused with no impl in sight" in {
      err(
        """trait Sized
          |    size -> int = self.n""".stripMargin
      ) should include("has no fields to read")
    }

    "a default property may not call a method the trait does not declare" in {
      err(
        """trait Sized
          |    size -> int = self.measure()""".stripMargin
      ) should include("'measure'")
    }

    "a bad default property is reported once, not once per implementing type" in {
      val out = err(
        """trait Sized
          |    size -> int = self.measure()
          |struct A
          |    n: int
          |struct B
          |    n: int
          |impl Sized for A
          |impl Sized for B""".stripMargin
      )

      out.split("error:").length - 1 shouldBe 1
    }
  }

  "object safety is unchanged by a property" - {
    // A property's receiver is by value, so what makes a trait unsafe is still `Self` away from the
    // receiver — and a property's result is exactly where that shows up.
    "a property returning Self has no size for an erased value to hand back" in {
      err(
        """trait Cloneable
          |    copy -> Self
          |struct Box
          |    n: int
          |impl Cloneable for Box
          |    copy -> Self = Box(self.n)
          |show(c: *Cloneable) = print(1)""".stripMargin
      ) should include("mentions 'Self' away from its receiver")
    }
  }
}
