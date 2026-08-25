package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Diagnostics for a type's own members: an inherent method or property, and a member reached on an
 * enum — including the ones an `impl` puts there.
 *
 * The other half is `AnalyzerTraitErrorTests`: what a trait declares, what an `impl` owes it, and
 * what a bound promises a generic body. The line between the two is whose declaration the mistake is
 * against — this suite's diagnostics all name a type, and that suite's all name a trait.
 */
class AnalyzerMemberErrorTests extends AnyFreeSpec with CodegenSupport {

  "methods" - {
    "a '&self' method rejects a bare stack value" in {
      err(
        """struct C
          |    n: int
          |    bump(&self)
          |        self.n += 1
          |var c = C(0)
          |c.bump()""".stripMargin
      ) should include("'&self' needs a counted reference")
    }

    "a property is read without parentheses" in {
      err(
        """struct P
          |    x: int
          |    twice -> int = self.x * 2
          |var p = P(1)
          |print(p.twice())""".stripMargin
      ) should include("is a property")
    }

    "a method is called with parentheses" in {
      err(
        """struct P
          |    x: int
          |    twice(self) -> int = self.x * 2
          |var p = P(1)
          |print(p.twice)""".stripMargin
      ) should include("is a method")
    }

    "an unknown method is reported against its type" in {
      err(
        """struct P
          |    x: int
          |var p = P(1)
          |print(p.area())""".stripMargin
      ) should include("no method 'area'")
    }

    "an unknown associated function is reported against its type" in {
      err(
        """struct P
          |    x: int
          |    id(self) -> int = self.x
          |var p = P.make()""".stripMargin
      ) should include("no associated function 'make'")
    }

    // **A member is filed before its signature is built**, so that a mistake in the signature does
    // not also erase the member it is about — which leaves a window where the declaration is known
    // and its lowered form is not. Everything reaching a member through that window has to report
    // the signature error rather than go looking for a form that was never made, and whether it is
    // reached at all depends on nothing but which of the two was written first: a use *above* its
    // declaration arrives before the signature has failed to be recorded. Both orders are pinned,
    // because the pair disagreeing is the bug — the second spelling of each took the compiler down
    // with a missing key where the first reported properly.
    "a call to a method whose signature does not resolve reports the signature" in {
      err(
        """struct S
          |    n: int
          |    inner(*self) -> Nonesuch = Nonesuch()
          |    outer(*self) -> int
          |        self.inner()
          |        self.n
          |var s = S(0)
          |print(s.outer())""".stripMargin
      ) should include("unknown type 'Nonesuch'")
    }

    "and the same where the call is written above the method" in {
      err(
        """struct S
          |    n: int
          |    outer(*self) -> int
          |        self.inner()
          |        self.n
          |    inner(*self) -> Nonesuch = Nonesuch()
          |var s = S(0)
          |print(s.outer())""".stripMargin
      ) should include("unknown type 'Nonesuch'")
    }

    "a read of a property whose signature does not resolve reports the signature" in {
      err(
        """struct P
          |    x: int
          |    inner -> Nonesuch = Nonesuch()
          |    outer(self) -> int = self.inner
          |var p = P(1)
          |print(p.outer())""".stripMargin
      ) should include("unknown type 'Nonesuch'")
    }

    "and the same where the read is written above the property" in {
      err(
        """struct P
          |    x: int
          |    outer(self) -> int = self.inner
          |    inner -> Nonesuch = Nonesuch()
          |var p = P(1)
          |print(p.outer())""".stripMargin
      ) should include("unknown type 'Nonesuch'")
    }

    "a call to an associated function whose signature does not resolve reports the signature" in {
      err(
        """struct P
          |    x: int
          |    outer(self) -> int = P.make()
          |    make() -> Nonesuch = Nonesuch()
          |var p = P(1)
          |print(p.outer())""".stripMargin
      ) should include("unknown type 'Nonesuch'")
    }

    "a member may not share a name with a field" in {
      err(
        """struct P
          |    x: int
          |    x(self) -> int = 1""".stripMargin
      ) should include("both a field and a member")
    }

    // An associated function on a generic type has no receiver to read the type arguments from, so
    // they are inferred from the call — and a parameter its signature never mentions leaves the
    // call with nothing to infer from.
    "an associated function whose signature never mentions the parameter cannot be called" in {
      err(
        """struct Box[T]
          |    value: T
          |    empty() -> int = 0
          |print(Box.empty())""".stripMargin
      ) should include("cannot infer the type argument 'T' of 'Box.empty'")
    }

    // A method may introduce type parameters of its own, on a type that has none of its own to
    // fix them: the receiver settles nothing there, so the call is the whole of what does.
    "a method's own type parameter is inferred at the call, and must be" in {
      err(
        """struct Registry
          |    n: int
          |    store[T](&self) -> int = self.n
          |var r: &Registry = Registry(1)
          |print(r.store())""".stripMargin
      ) should include("cannot infer the type argument 'T' of 'Registry.store'")
    }

    // The type's parameters and the member's own end up in one signature, so a member spelling one
    // of its own the way the type spells one of its would leave a `T` in the body ambiguous.
    "a member may not reuse a type parameter name the type declares" in {
      err(
        """struct Box[T]
          |    value: T
          |    cast[T](self, u: T) -> T = u""".stripMargin
      ) should include("'Box' already declares a type parameter 'T', so member 'cast' cannot declare one of that name")
    }
  }

  "members on enums" - {
    // A variant is what an enum has instead of fields, so it is a variant a member may not shadow —
    // and the diagnostic says variant, since there is no field to have meant.
    "a member may not share a name with a variant" in {
      err(
        """enum Color
          |    Red
          |    Red(self) -> int = 1""".stripMargin
      ) should include("type 'Color' has both a variant and a member named 'Red'")
    }

    "an unknown method on an enum is reported against its type" in {
      err(
        """enum Color
          |    Red
          |var c = Red
          |print(c.area())""".stripMargin
      ) should include("type 'Color' has no method 'area'")
    }

    // An enum has no fields at all, so an absent name can only have been a property — saying "field
    // or property" here would point at something the type cannot have.
    "an absent name read off an enum value is reported as a property" in {
      err(
        """enum Color
          |    Red
          |var c = Red
          |print(c.nope)""".stripMargin
      ) should include("'Color' has no property 'nope'")
    }

    "a property on an enum is read without parentheses" in {
      err(
        """enum Color
          |    Red
          |    code -> int = 1
          |var c = Red
          |print(c.code())""".stripMargin
      ) should include("is a property")
    }

    "a method on an enum is called with parentheses" in {
      err(
        """enum Color
          |    Red
          |    code(self) -> int = 1
          |var c = Red
          |print(c.code)""".stripMargin
      ) should include("is a method")
    }

    // A `&self` method needs the reference itself; a bare enum on the stack has no refcount to
    // share, exactly as a bare struct has none.
    "a '&self' method on an enum rejects a bare stack value" in {
      err(
        """enum Color
          |    Red
          |    code(&self) -> int = 1
          |var c = Red
          |print(c.code())""".stripMargin
      ) should include("'&self' needs a counted reference")
    }

    // Reached through the type name rather than a value: an instance member is not a variant, and
    // the diagnostic distinguishes the three member kinds rather than claiming the name is unknown.
    "an instance member reached through the enum name says to use a value" in {
      err(
        """enum Color
          |    Red
          |    code(self) -> int = 1
          |print(Color.code())""".stripMargin
      ) should include("'code' is an instance method of 'Color'")

      err(
        """enum Color
          |    Red
          |    code(self) -> int = 1
          |print(Color.code)""".stripMargin
      ) should include("call it on a value, as 'value.code(…)'")

      err(
        """enum Color
          |    Red
          |    code -> int = 1
          |print(Color.code)""".stripMargin
      ) should include("'code' is a property of 'Color'")
    }

    "an associated function read off the enum name without parentheses is rejected" in {
      err(
        """enum Color
          |    Red
          |    make() -> int = 1
          |print(Color.make)""".stripMargin
      ) should include("'make' is an associated function of 'Color' — call it with 'Color.make(…)'")
    }

    "an unknown associated function on an enum is reported against its type" in {
      err(
        """enum Color
          |    Red
          |    make() -> int = 1
          |print(Color.bogus())""".stripMargin
      ) should include("enum 'Color' has no variant or associated function 'bogus'")
    }

    // The rule a struct's members meet, met on an enum: a member's own type parameter is the call's
    // to fix, and one nothing at the call mentions cannot be fixed.
    "a method's own type parameter is inferred at the call here too" in {
      err(
        """enum Color
          |    Red
          |    store[T](self) -> int = 1
          |var c = Red
          |print(c.store())""".stripMargin
      ) should include("cannot infer the type argument 'T' of 'Color.store'")
    }

    "an associated function on a generic enum infers the enum's arguments from the call" in {
      err(
        """enum Maybe[T]
          |    Just(value: T)
          |    make() -> int = 1
          |print(Maybe.make())""".stripMargin
      ) should include("cannot infer the type argument 'T' of 'Maybe.make'")
    }

    "a data enum's method body must still cover every variant" in {
      err(
        """enum Shape
          |    Circle(r: int)
          |    Empty
          |    area(self) -> int = self match
          |        Empty -> 0""".stripMargin
      ) should include("not exhaustive")
    }

    "the library's Option members are checked against the element type" in {
      err(
        """var a: Option[int] = Some(1)
          |print(a.unwrap_or("no"))""".stripMargin
      ) should include("is int, but string was given")
    }
  }

  "traits on enums" - {
    "an impl for an enum that omits a trait method is rejected" in {
      err(
        """trait Show
          |    show(self) -> int
          |    label(self) -> int
          |enum Color
          |    Red
          |impl Show for Color
          |    show(self) -> int = 1""".stripMargin
      ) should include("method 'label' is missing")
    }

    "an impl method colliding with a variant is rejected" in {
      err(
        """trait Show
          |    Red(self) -> int
          |enum Color
          |    Red
          |impl Show for Color
          |    Red(self) -> int = 1""".stripMargin
      ) should include("both a variant and a member")
    }

    // A generic type has one key for all of its instantiations, so an implementation covers it as a
    // whole and the block declares the parameters that says so.
    "implementing a trait for a generic enum needs the block's own parameters" in {
      err(
        """trait Show
          |    show(self) -> int
          |enum Maybe[T]
          |    Just(value: T)
          |impl Show for Maybe
          |    show(self) -> int = 1""".stripMargin
      ) should include("write 'impl[T] Show for Maybe[T]'")
    }

    // A trait may be implemented for any type, so what is wrong with `impl Show for Ghost` is not
    // that `Ghost` is the wrong *kind* of type — it is that there is no such type at all, which is
    // what the diagnostic says.
    "implementing a trait for an unknown type says the name is unknown" in {
      err(
        """trait Show
          |    show(self) -> int
          |impl Show for Ghost
          |    show(self) -> int = 1""".stripMargin
      ) should include("unknown type 'Ghost'")
    }

    "a built-in type may carry an impl, and its methods resolve on a value of it" in {
      ir(
        """trait Show
          |    show(self) -> string
          |impl Show for int
          |    show(self) -> string = "i"
          |print(5.show())""".stripMargin
      ) should include("define { ptr, ptr, i64 } @int.show(")
    }

    // The key is the type, not the spelling it was reached by, so two aliases of one type are one
    // implementation and the second is the duplicate it is.
    "two spellings of one built-in type are one implementation" in {
      err(
        """trait Show
          |    show(self) -> string
          |impl Show for int
          |    show(self) -> string = "a"
          |impl Show for i32
          |    show(self) -> string = "b"
          |print(1)""".stripMargin
      ) should include("already implements 'Show'")
    }

    "a type with no values, and one with only one, carry nothing" in {
      val trait_ = "trait Show\n    show(self) -> string\n"

      err(s"${trait_}impl Show for never\n    show(self) -> string = \"n\"\nprint(1)") should
        include("'never' has no values")
      err(s"${trait_}impl Show for unit\n    show(self) -> string = \"u\"\nprint(1)") should
        include("a trait for it would say nothing")
    }

    "an enum that does not implement a bound's trait is rejected at the call" in {
      err(
        """trait Show
          |    show(self) -> int
          |enum Color
          |    Red
          |render[T: Show](x: T) -> int = x.show()
          |print(render(Red))""".stripMargin
      ) should include("requires its type parameter 'T' to implement 'Show', but Color does not")
    }

    // The definition-time check of `reference/generics.md § Bounds`: the body is walked once with
    // `T` opaque, so a method it did not declare a bound for is reported against the definition
    // that assumed it rather than against whichever caller happened to supply a type without that
    // method.
    "an unbounded generic may not call a method its parameter does not promise" in {
      err(
        """trait Show
          |    show(self) -> int
          |struct P
          |    v: int
          |impl Show for P
          |    show(self) -> int = self.v
          |loose[T](x: T) -> int = x.show()
          |print(loose(P(7)))""".stripMargin
      ) should include("'show' needs 'T: Show'")
    }
  }
}
