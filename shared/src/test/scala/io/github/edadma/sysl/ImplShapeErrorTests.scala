package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** What an `impl` matching a **shape** may and may not say (`02`).
 *
 * A shape is one key, exactly as a generic type's name is, so most of these are that fact seen from
 * different angles — a block that fixed part of what it matched, or a second block for what one
 * already covers.
 *
 * The rule with the most consequence is the one about a shape and a type of that shape written out
 * in full: they are two implementations for one type, and sysl has no rule that picks between two.
 * Neither is more specific than the other, because being more specific is not something the language
 * knows how to be. So whichever is written second is refused, and the diagnostic says which is
 * already there.
 */
class ImplShapeErrorTests extends AnyFreeSpec with CodegenSupport {

  private val show  = "trait Show\n    show(self) -> string\n"
  private val other = "trait Other\n    show(self) -> int\n"

  /** A conditional implementation, and a struct that does not meet its condition. */
  private val conditional =
    s"""${show}impl[T: Show] Show for []T
       |    show(self) -> string = self[0].show()
       |struct P
       |    v: int
       |""".stripMargin

  "the subject is the shape applied to the block's parameters" - {

    "so an element fixed to one type is refused" in {
      err(s"${show}impl[T] Show for []int\n    show(self) -> string = \"b\"") should
        include("'int' fixes the element type, and an 'impl' with type parameters covers every slice")
    }

    // Matching `[][]T` would mean matching a shape's argument by *its* shape, which is a second
    // question the one key cannot answer.
    "and so is a shape nested inside the subject" in {
      err(s"${show}impl[T] Show for [][]T\n    show(self) -> string = \"b\"") should
        include("'[]T' fixes the element type")
    }

    "every declared parameter appearing in it" in {
      err(s"${show}impl[T, U] Show for []T\n    show(self) -> string = \"b\"") should
        include("'U' is declared by this 'impl' but does not appear in '[]T', so nothing would ever fix it")
    }

    // The subject would be every type at once, which is what a trait's own default bodies are for.
    "and the subject being a shape rather than a bare parameter" in {
      err(s"${show}impl[T] Show for T\n    show(self) -> string = \"b\"") should
        include("'T' is a type parameter of this 'impl', so it stands for every type at once")
    }

    "a built-in has no shape to match" in {
      err(s"${show}impl[T] Show for int\n    show(self) -> string = \"b\"") should
        include("'int' takes no type arguments, so an 'impl' for it has nothing to be generic over")
    }

    // A memory mode is refused for the reason it always was, which is the more useful complaint
    // about the line than one about the parameters.
    "and a memory mode is refused as the mode it is" in {
      err(s"${show}impl[T] Show for *T\n    show(self) -> string = \"b\"") should
        include("is a way of holding a T rather than a type of its own")
    }

    "two blocks for one shape collide like two for one generic type" in {
      err(
        s"""${show}impl[T] Show for []T
           |    show(self) -> string = "b"
           |impl[U] Show for []U
           |    show(self) -> string = "c"""".stripMargin,
      ) should include("'[]U' already implements 'Show'")
    }
  }

  "a shape and a type of it written out in full are two implementations for one type" - {

    "so the shape is refused after the type" in {
      err(
        s"""${show}impl Show for []int
           |    show(self) -> string = "a"
           |impl[T] Show for []T
           |    show(self) -> string = "b"""".stripMargin,
      ) should include("'[]int' already implements 'Show', and this 'impl' would implement it for " +
        "every slice — including that one")
    }

    "and the type after the shape" in {
      err(
        s"""${show}impl[T] Show for []T
           |    show(self) -> string = "b"
           |impl Show for []int
           |    show(self) -> string = "a"""".stripMargin,
      ) should include("every slice already implements 'Show', so '[]int' has an implementation and " +
        "cannot be given a second one")
    }

    "and an array of the block's length is the same collision" in {
      err(
        s"""${show}impl[T] Show for [2]T
           |    show(self) -> string = "b"
           |impl Show for [2]int
           |    show(self) -> string = "a"""".stripMargin,
      ) should include("every array of 2 already implements 'Show'")
    }
  }

  "a type's members are one namespace, and the shape's are in it" - {

    // Two traits declaring a member of one name were always refused for a single type. A shape
    // reaches types that may already have one, so the same rule reaches across the two.
    "so a shape may not give a name a written implementation already used" in {
      err(
        s"""$show${other}impl Other for []int
           |    show(self) -> int = 1
           |impl[T] Show for []T
           |    show(self) -> string = "b"""".stripMargin,
      ) should include("'[]int' already has a member named 'show', and this 'impl' would give every " +
        "slice one — a call on a '[]int' could mean either")
    }

    "nor a written implementation one the shape already gave" in {
      err(
        s"""$show${other}impl[T] Show for []T
           |    show(self) -> string = "b"
           |impl Other for []int
           |    show(self) -> int = 1""".stripMargin,
      ) should include("every slice already has a member named 'show', so '[]int' cannot declare " +
        "one of its own")
    }
  }

  "conformance is checked with the parameters standing in for themselves" - {

    "so a missing member is reported against the shape" in {
      err(s"${show}impl[T] Show for []T") should
        include("'[]T' does not implement 'Show': method 'show' is missing")
    }

    // `Self` on the trait's side is the shape applied to the block's parameters, so the two
    // spellings are the one signature.
    "and 'Self' matches the subject written out" in {
      err(
        s"""trait Dup
           |    dup(self) -> Self
           |impl[T] Dup for []T
           |    dup(self) -> []int = self""".stripMargin,
      ) should include("returns []int, but trait 'Dup' declares []T")
    }

    // The block states what it assumes of its element, so the body is checked once — here, against
    // a block that assumed nothing.
    "and the body is checked at its definition, against the bounds alone" in {
      err(s"${show}impl[T] Show for []T\n    show(self) -> string = str(self[0])") should
        include("'str' needs 'T: Display'")
    }
  }

  "the block's bounds decide which slices conform" - {

    // The advice a type with no implementation gets — write one — is the wrong advice here: an
    // implementation covers this slice already, and writing a second is exactly what it may not do.
    // So the condition is what the diagnostic names.
    "so print names the condition rather than an 'impl' that would be refused" in {
      err(
        s"""impl[T: Display] Display for []T
           |    display(self, out: *Writer, fmt: FormatSpec) = out.write("x".bytes)
           |struct P
           |    v: int
           |var a = [P(1)]
           |print(a[0..])""".stripMargin,
      ) should include("cannot print a []P value — the 'impl' that covers it asks 'Display' of P, " +
        "which does not implement it")
    }

    "a bound refuses an element that does not conform" in {
      err(s"${conditional}tell[S: Show](x: S) -> string = x.show()\nvar a = [P(1)]\nprint(tell(a[0..]))") should
        include("'tell' requires its type parameter 'S' to implement 'Show', but []P does not — " +
          "the 'impl' that covers it asks 'Show' of P, which does not implement it")
    }

    "and an erasure does too" in {
      err(
        s"""${show}impl[T: Show] Show for [1]T
           |    show(self) -> string = self[0].show()
           |struct P
           |    v: int
           |var a: &[1]P = [P(1)]
           |var o: &Show = a
           |print(o.show())""".stripMargin,
      ) should include("a &Show needs a type that implements 'Show', and [1]P does not — " +
        "the 'impl' that covers it asks 'Show' of P, which does not implement it")
    }
  }

  /** `02 § Coherence` says an `impl Trait for Type` may appear only in the module that declares
   * `Trait` or the module that declares `Type`, and that a foreign trait for a foreign type is the
   * case with no home. `Eq` and `Option` are both the prelude's, so a user module implementing one
   * for the other is exactly that case — and it is accepted today, which means nothing enforces the
   * rule the chapter states.
   */
  "an implementation of a foreign trait for a foreign type has no home" ignore {
    err("""impl[T: Eq] Eq for Option[T]
          |    eq(self, rhs: Option[T]) -> bool = self.is_some() == rhs.is_some()
          |print(1)
          |""".stripMargin) should include("may appear only in the module that declares")
  }
}
