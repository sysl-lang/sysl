package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** What a bound on a **type's own** parameters refuses (`10 §5`).
 *
 * There are two sides to it and they are the two halves of what a bound *is*. Everything applying
 * the type must supply what it asks — that is checked at the application, wherever one is written.
 * And its members may assume no more than it asks — that is checked once, at the definition, which
 * is the asymmetry with a generic function that having somewhere to write the bound removes.
 */
class TypeBoundsErrorTests extends AnyFreeSpec with CodegenSupport with RunSupport {

  private val show = "trait Show\n    show(self) -> string\n"

  /** A bounded wrapper and a struct that does not implement the bound. */
  private val wrap =
    s"""${show}struct Wrap[T: Show]
       |    inner: T
       |struct P
       |    v: int
       |""".stripMargin

  "an argument must implement what the type asks" - {

    "so a struct without the implementation is refused" in {
      err(s"${wrap}var w = Wrap(P(1))") should
        include("'Wrap' requires its type parameter 'T' to implement 'Show', but P does not")
    }

    "and so is a built-in that is outside the trait" in {
      err(s"${wrap}var w = Wrap(5)") should
        include("'Wrap' requires its type parameter 'T' to implement 'Show', but int does not")
    }

    "wherever the type is written — a declared parameter" in {
      err(s"${wrap}label(w: Wrap[P]) -> int = 1") should
        include("'Wrap' requires its type parameter 'T' to implement 'Show', but P does not")
    }

    "a declared result" in {
      err(s"${wrap}make(p: P) -> Wrap[P] = Wrap(p)") should
        include("'Wrap' requires its type parameter 'T' to implement 'Show', but P does not")
    }

    "a field of another type" in {
      err(s"${wrap}struct Holder\n    held: Wrap[P]") should
        include("'Wrap' requires its type parameter 'T' to implement 'Show', but P does not")
    }

    "a variant's payload" in {
      err(s"${wrap}enum Held\n    One(w: Wrap[P])") should
        include("'Wrap' requires its type parameter 'T' to implement 'Show', but P does not")
    }

    "and an argument of a further application" in {
      err(s"${wrap}var w: Wrap[Wrap[P]] = Wrap(Wrap(P(1)))") should
        include("'Wrap' requires its type parameter 'T' to implement 'Show', but P does not")
    }

    // An enum asks for what it asks in the same place and is held to it the same way.
    "an enum's parameter asking the same question" in {
      err(
        s"""${show}enum Maybe[T: Show]
           |    Just(value: T)
           |    Nothing
           |struct P
           |    v: int
           |var m = Just(P(1))""".stripMargin,
      ) should include("'Maybe' requires its type parameter 'T' to implement 'Show', but P does not")
    }

    // A trait object still fails a bound on a trait it does *not* dispatch through — the one it
    // holds a table for is the one it implements, and nothing else follows from erasing.
    "and a trait object still fails a bound on some other trait" in {
      err(
        s"""${show}trait Other
           |    other(self) -> int
           |struct P
           |    v: int
           |impl Show for P
           |    show(self) -> string = "p"
           |struct Wrap[T: Other]
           |    inner: T
           |var w: Wrap[&Show] = Wrap(P(1))""".stripMargin,
      ) should include("'Wrap' requires its type parameter 'T' to implement 'Other', but &Show does not")
    }

    // A type an implementation *covers* is told what that implementation asked of it, so the reader
    // is sent one step in rather than to a block that is already written.
    "and a covered type is told which of its own arguments fails" in {
      err(
        s"""${show}struct Box[T]
           |    v: T
           |impl[T: Show] Show for Box[T]
           |    show(self) -> string = self.v.show()
           |struct Wrap[T: Show]
           |    inner: T
           |struct P
           |    v: int
           |var w = Wrap(Box(P(1)))""".stripMargin,
      ) should include("the 'impl' that covers it asks 'Show' of P, which does not implement it")
    }
  }

  "a member may assume no more than the type asks" - {

    // The whole payoff: the body is wrong on its own line, whether or not anything instantiates it.
    "so a method calling what no bound licenses is reported at the definition" in {
      err(
        s"""${show}struct Box[T]
           |    value: T
           |    label(self) -> string = self.value.show()""".stripMargin,
      ) should include("'show' needs 'T: Show'")
    }

    "and an operator the parameter does not promise names the bound to add" in {
      err(
        """struct Box[T]
          |    value: T
          |    twice(self) -> T = self.value + self.value""".stripMargin,
      ) should include(s"'+' needs 'T: ${lib("Add")}'")
    }

    "a bound licenses only its own trait's members" in {
      err(
        """trait Show
          |    show(self) -> string
          |trait Size
          |    size(self) -> int
          |struct Wrap[T: Show]
          |    inner: T
          |    n(self) -> int = self.inner.size()""".stripMargin,
      ) should include("'size' needs 'T: Size'")
    }

    "and rendering the element asks for 'Display'" in {
      err(
        """struct Wrap[T]
          |    inner: T
          |    line(self) -> string = str(self.inner)""".stripMargin,
      ) should include("'T: sysl.Display'")
    }

    // A field is layout rather than behaviour, so no bound could ever license one — the rule is the
    // same inside a type's member as inside a generic function (`10 §5`).
    "while a field read off the parameter is refused with no bound to suggest" in {
      val out = err(
        """struct Wrap[T]
          |    inner: T
          |    peek(self) -> int = self.inner.v""".stripMargin,
      )

      out should include("has no fields to read")
      out should include("no trait declares a property 'v'")
    }

    // One mistake stays one diagnostic: the instantiations would each fail the same way, in terms
    // of whatever type they were made at, so they are dropped in favour of the definition's.
    "and the definition's diagnostic is the only one, however many instantiations there are" in {
      val out = err(
        """struct Box[T]
          |    value: T
          |    twice(self) -> T = self.value + self.value
          |var a = Box(1)
          |var b = Box(2.5)
          |print(a.twice(), b.twice())""".stripMargin,
      )

      out.linesIterator.count(_.contains(s"needs 'T: ${lib("Add")}'")) shouldBe 1
    }
  }

  "a parameter standing in for itself carries only its own bounds" - {

    // The declaration is wrong on its own line, and the fix is on that line too: bound `U` by what
    // `Wrap` asks. Reporting it at the instantiation instead would blame whatever type turned up.
    "so a function applying a bounded type to its own parameter must bound it" in {
      err(s"${wrap}label[U](w: Wrap[U]) -> int = 1") should
        include("'Wrap' requires its type parameter 'T' to implement 'Show', but 'U' is not bounded by it")
    }

    // A generic type has no layout until something instantiates it, so its fields are laid out once
    // against its own bounds — which is the same rule reaching a declaration that has no body.
    "and so must a field of one generic type applying another" in {
      err(
        s"""${show}trait Rank
           |    rank(self) -> int
           |struct Inner[U: Rank]
           |    u: U
           |struct Wrap[T: Show]
           |    held: Inner[T]""".stripMargin,
      ) should include("'Inner' requires its type parameter 'U' to implement 'Rank', but 'T' is not bounded by it")
    }

    "a variant's payload the same way" in {
      err(
        s"""${show}trait Rank
           |    rank(self) -> int
           |struct Inner[U: Rank]
           |    u: U
           |enum Held[T: Show]
           |    One(held: Inner[T])
           |    None2""".stripMargin,
      ) should include("'Inner' requires its type parameter 'U' to implement 'Rank', but 'T' is not bounded by it")
    }
  }

  "a generic 'impl' for a bounded type" - {

    // Its subject is an application of that type like any other, so it must ask at least as much.
    "must ask at least what the type asks of its own parameter" in {
      err(
        s"""${show}trait Loud
           |    loud(self) -> string
           |struct Wrap[T: Show]
           |    inner: T
           |impl[T] Loud for Wrap[T]
           |    loud(self) -> string = "!"
           |""".stripMargin,
      ) should include("'Wrap' requires its type parameter 'T' to implement 'Show', but 'T' is not bounded by it")
    }
  }

  "a bound names a trait" - {

    "so a struct name in bound position is refused" in {
      err(
        """struct P
          |    v: int
          |struct Wrap[T: P]
          |    inner: T""".stripMargin,
      ) should include("the bound on 'T' in 'Wrap' names 'P', which is not a trait")
    }

    "and so is a name that declares nothing at all" in {
      err("struct Wrap[T: Nothing]\n    inner: T") should
        include("the bound on 'T' in 'Wrap' names 'Nothing', which is not a trait")
    }

    "on an enum as readily as on a struct" in {
      err("enum Maybe[T: Nothing]\n    Just(value: T)\n    Nothing") should
        include("the bound on 'T' in 'Maybe' names 'Nothing', which is not a trait")
    }
  }
}
