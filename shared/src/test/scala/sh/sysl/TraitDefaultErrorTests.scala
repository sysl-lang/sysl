package sh.sysl

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
    // **The default is read in its trait's file**, which is not something the declaration carries:
    // it is synthesized rather than hoisted, so nothing had filed where it was written and it was
    // read with no imports at all. The body then stopped at its first imported name, taking the
    // rest of the walk with it — and what that walk is *for* is the complaints nothing else makes,
    // so a bound the body needed after that line was asked for by nobody. A copy carried onto an
    // implementing type is read in the trait's terms and always was, which is why an implemented
    // trait showed nothing.
    "what a default needs a bound for is asked in an expression that also calls an import" in {
      err(
        """import sysl.text.cstring
          |
          |trait Loud
          |    say(self) -> string = s"${cstring("x").len} $self"
          |""".stripMargin
      ) should include("'Self: sysl.Display'")
    }

    "and one calling an imported function correctly is accepted with no impl in sight" in {
      ir(
        """import sysl.text.cstring
          |
          |trait Greet
          |    greet(self) -> usize = cstring("hi").len
          |
          |main()
          |    print(1)""".stripMargin
      ) should include("define")
    }

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
      ) should include(s"'Self: ${lib("Add")}'")
    }

    "a default printing its receiver asks for Display" in {
      err(
        """trait Loud
          |    say(self) = print(self)""".stripMargin
      ) should include("'Self: sysl.Display'")
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

    // Two defaults of one name are two bodies, and a default is filed exactly as a written method
    // is — so this is `13 §2`'s case and not a collision: both blocks stand, and it is a *use*
    // reaching both that has nothing to say which was meant.
    "two traits whose defaults share a name may both be implemented for one type" in {
      val both =
        """trait A
          |    tag(self) -> int = 1
          |trait B
          |    tag(self) -> int = 2
          |struct C
          |    n: int
          |impl A for C
          |impl B for C
          |""".stripMargin

      ir(both + "main()\n    print(1)") should include("define")
      err(both + "main()\n    print(C(1).tag())") should include("which was meant")
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
