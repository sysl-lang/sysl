package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** `static freezing -> int` — a property of the **type** rather than of a value of it, read as
 * `Temp.freezing` (`reference/declarations.md § A static property`).
 *
 * **The claim these cases make is that a static property is an associated function a reader writes
 * no parentheses after, and that is deliberately stronger than "the word parses".** A property has
 * nowhere to write `self`, so every property was an instance member by construction and `static` is
 * what says otherwise; the whole of the lowering is that `MethodDecl.recvMode` answers `None` for
 * one, which every pass that dispatches on a receiver already reads. So the cases below check the
 * two directions of that — the type reaches it and a value does not — rather than only that a
 * program using it prints the right number, which would pass on an implementation that had quietly
 * made it an ordinary property.
 */
class StaticPropertyTests extends AnyFreeSpec with RunSupport with CodegenSupport {

  "a type reaches its own" - {

    "on a struct" in {
      run(
        """struct Temp
          |    n: int
          |    static freezing -> int = 32
          |print(Temp.freezing)""".stripMargin,
      ) shouldBe "32\n"
    }

    "on an enum" in {
      run(
        """enum Colour
          |    Red
          |    Green
          |    static count -> int = 2
          |print(Colour.count)""".stripMargin,
      ) shouldBe "2\n"
    }

    "in an impl block" in {
      run(
        """trait Bounded
          |    static lowest -> int
          |struct Age
          |    n: int
          |impl Bounded for Age
          |    static lowest -> int = 0
          |print(Age.lowest)""".stripMargin,
      ) shouldBe "0\n"
    }

    /** The body may be an indented block, exactly as an instance property's may — a static property
      * is a function with the parameter list left off, and having only the one-expression spelling
      * made it the one member whose body could not be written out.
      */
    "with a block body, reading another static property" in {
      run(
        """struct Temp
          |    n: int
          |    static freezing -> int = 32
          |    static boiling -> int
          |        Temp.freezing + 180
          |print(Temp.boiling)""".stripMargin,
      ) shouldBe "212\n"
    }

    "returning a value of the type it belongs to" in {
      run(
        """struct P
          |    x: int
          |    static origin -> P = P(0)
          |print(P.origin.x)""".stripMargin,
      ) shouldBe "0\n"
    }
  }

  "a bound reaches one, which is what the form is for" - {

    /** The case a static property exists to make possible: a generic body needs a fact about the
      * type — a zero, a width, a limit — and a type parameter is not a value, so before this the
      * fact had to arrive as a member carrying a receiver nothing reads.
      */
    "a generic body reads it off its own type parameter" in {
      run(
        """trait Bounded
          |    static lowest -> int
          |struct Age
          |    n: int
          |impl Bounded for Age
          |    static lowest -> int = 7
          |lowest_of[T: Bounded]() -> int = T.lowest
          |print(lowest_of[Age]())""".stripMargin,
      ) shouldBe "7\n"
    }

    "an implementation that does not supply it is refused at the block" in {
      err(
        """trait Bounded
          |    static lowest -> int
          |struct Age
          |    n: int
          |impl Bounded for Age
          |print(Age.lowest)""".stripMargin,
      ) should include("property 'lowest' is missing")
    }
  }

  "it is read on the type and not on a value" - {

    /** The mirror of the refusal a *type* gets for an instance property, and it is a refusal rather
      * than a convenience: the lowered function has no parameter for a receiver to arrive in, so a
      * read on a value would pass an argument the callee does not have.
      */
    "a value is refused, and told which side of the dot to change" in {
      val e = err(
        """struct Temp
          |    n: int
          |    static freezing -> int = 32
          |print(Temp(1).freezing)""".stripMargin,
      )

      e should include("property of the type 'Temp'")
      e should include("Temp.freezing")
    }

    "so is 'self' inside an instance method of the same type" in {
      err(
        """struct Temp
          |    n: int
          |    static freezing -> int = 32
          |    above(self) -> int = self.freezing
          |print(Temp(50).above())""".stripMargin,
      ) should include("property of the type 'Temp'")
    }

    /** The body has no receiver at all, which is the other half of the same fact — and it is what
      * makes "a property that never names `self` is static" the wrong way to decide this. What a
      * member is has to be said, or deleting a `self.` from an expression would silently move the
      * member from the value to the type.
      */
    "the body cannot name 'self'" in {
      err(
        """struct Temp
          |    n: int
          |    static freezing -> int = self.n
          |print(Temp.freezing)""".stripMargin,
      ) should include("undefined name 'self'")
    }
  }

  "the form is bounded where a read would have nothing to solve from" - {

    "a parameter list makes it an associated function already, and says so" in {
      err(
        """struct Temp
          |    n: int
          |    static freezing(k: int) -> int = k
          |print(Temp.freezing(1))""".stripMargin,
      ) should include("A member with a parameter list is an associated function already")
    }

    "type parameters are refused, since a read has nothing to fix them from" in {
      err(
        """struct Temp
          |    n: int
          |    static freezing[T] -> int = 1
          |print(Temp.freezing)""".stripMargin,
      ) should include("a property takes no type parameters")
    }

    /** A trait's receiverless member may not carry a default body, and a static property is that
      * rule rather than an exception to it — every implementation would inherit one constant. It
      * cannot take the advice the receiverless message gives, though, since a property has nowhere
      * to write a `self` parameter, so it gets a refusal of its own.
      */
    "a default body in a trait is refused, and not told to add a 'self' it cannot write" in {
      val e = err(
        """trait Bounded
          |    static lowest -> int = 0
          |struct Age
          |    n: int
          |impl Bounded for Age
          |print(Age.lowest)""".stripMargin,
      )

      e should include("drop the body")
      e should not include "'self' parameter"
    }

    "a name the type's own body already has is a collision, as any other member's is" in {
      err(
        """struct Temp
          |    n: int
          |    freezing -> int = 1
          |    static freezing -> int = 32
          |print(Temp.freezing)""".stripMargin,
      ) should include("already has a member named 'freezing'")
    }
  }

  /** `static` was already reserved — `static val` in an entry file says a binding belongs to the
    * module rather than to that file's body — so this form added no keyword. These pin that the
    * older meaning is untouched, since the two are told apart by nothing but position.
    */
  "the word's other job is untouched" in {
    run(
      """static var seen: int = 0
        |
        |bump() -> int
        |    seen += 1
        |    seen
        |
        |print(bump())
        |print(bump())""".stripMargin,
    ) shouldBe "1\n2\n"
  }
}
