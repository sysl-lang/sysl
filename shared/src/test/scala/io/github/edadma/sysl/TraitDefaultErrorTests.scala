package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Diagnostics for trait defaults (`02`).
 *
 * The half that matters is where a bad default is *reported*. A default body may assume of its
 * receiver exactly what its own trait declares, and that is checked once at the trait — so a
 * mistake in one lands on the line that made it even when nothing implements the trait at all,
 * rather than on each implementing type in turn.
 */
class TraitDefaultErrorTests extends AnyFreeSpec with CodegenSupport {

  "a default is checked at the trait" - {
    // Nothing implements `Greet`, so there is no instantiation to catch this — the trait is the
    // only place it can be reported, which is the whole point of checking it there.
    "a default calling a method the trait does not declare is refused with no impl in sight" in {
      err(
        """trait Greet
          |    name(self) -> string
          |    greet(self) -> string = self.shout()""".stripMargin
      ) should include("'shout'")
    }

    // A trait promises methods, so a field is the one thing no bound could ever license — which is
    // why it is answered here rather than deferred to whatever types turn up.
    "a default may not read a field of its receiver, which the trait does not promise" in {
      err(
        """trait Greet
          |    name(self) -> string
          |    greet(self) -> string = self.n""".stripMargin
      ) should include("has no fields to read")
    }

    "a default using an operator asks for the bound that supplies it" in {
      err(
        """trait Doubler
          |    value(self) -> Self
          |    twice(self) -> Self = self.value() + self.value()""".stripMargin
      ) should include("'Self: Add'")
    }

    "a default printing its receiver asks for Display" in {
      err(
        """trait Loud
          |    say(self) = print(self)""".stripMargin
      ) should include("'Self: Display'")
    }

    // The default's body is one body however many types inherit it, so its mistake is one
    // diagnostic — not one per implementing type.
    "a bad default is reported once, not once per implementing type" in {
      val out = err(
        """trait Greet
          |    name(self) -> string
          |    greet(self) -> string = self.shout()
          |struct Cat
          |    n: string
          |struct Dog
          |    n: string
          |impl Greet for Cat
          |    name(self) -> string = self.n
          |impl Greet for Dog
          |    name(self) -> string = self.n""".stripMargin
      )

      out.split("error:").length - 1 shouldBe 1
    }

    // Not every mistake in a default is one the trait's own promises can settle: this one needs the
    // types the body works with, and those are the implementing type's. So it is caught where every
    // other concrete mistake in a generic body is, at each type the body is materialized for.
    "a mistake the bounds cannot settle is caught at the implementing type" in {
      err(
        """trait Greet
          |    name(self) -> string
          |    tag(self) -> int = self.name()
          |struct Cat
          |    n: string
          |impl Greet for Cat
          |    name(self) -> string = self.n""".stripMargin
      ) should include("should return int")
    }
  }

  "what a trait may declare" - {
    "a default on something with no receiver has nothing to work on" in {
      err(
        """trait Maker
          |    make() -> int = 1""".stripMargin
      ) should include("no receiver")
    }

    "a generic trait method is refused at the trait" in {
      err(
        """trait Store
          |    put[T](self, item: T) -> int""".stripMargin
      ) should include("declares type parameters of its own, which a trait's member may not")
    }
  }

  "conformance still holds" - {
    "a method with no default is still required" in {
      err(
        """trait Greet
          |    name(self) -> string
          |    greet(self) -> string = self.name()
          |struct Cat
          |    n: string
          |impl Greet for Cat""".stripMargin
      ) should include("method 'name' is missing")
    }

    "an impl may not define a method the trait does not declare, default or not" in {
      err(
        """trait Greet
          |    greet(self) -> string = "hi"
          |struct Cat
          |    n: string
          |impl Greet for Cat
          |    shout(self) -> string = "HI"""".stripMargin
      ) should include("declares no method 'shout'")
    }

    "an override is held to the trait's signature" in {
      err(
        """trait Greet
          |    greet(self) -> string = "hi"
          |struct Cat
          |    n: string
          |impl Greet for Cat
          |    greet(self) -> int = 1""".stripMargin
      ) should include("but trait 'Greet' declares")
    }

    // Two defaults of one name are two bodies for one member, and nothing at the call site would
    // say which — the same collision an inherent member of that name makes.
    "two traits whose defaults share a name cannot both be implemented for one type" in {
      err(
        """trait A
          |    tag(self) -> int = 1
          |trait B
          |    tag(self) -> int = 2
          |struct C
          |    n: int
          |impl A for C
          |impl B for C""".stripMargin
      ) should include("already has a member named 'tag'")
    }

    "an inherited default may not collide with a member the type already has" in {
      err(
        """trait Greet
          |    greet(self) -> string = "hi"
          |struct Cat
          |    n: string
          |    greet(self) -> string = "own"
          |impl Greet for Cat""".stripMargin
      ) should include("already has a member named 'greet'")
    }
  }
}
