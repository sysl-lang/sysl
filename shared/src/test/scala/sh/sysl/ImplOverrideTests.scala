package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** `override` — the one relaxation of "one implementation per type" (`02 § override`), and the
 * marking it requires of a member that replaces a trait's default body.
 *
 * The keyword goes on the **overriding** side, which is the whole of the design: a library author
 * cannot know which of their implementations somebody will need to replace, so nothing has to be
 * granted in advance. What it buys, granting no permission, is the diagnostic — an *unmarked* second
 * implementation is still refused exactly as it was, so the accidental duplicate the rule exists to
 * catch is still caught.
 *
 * The two checks in the other direction are what keep it honest: an `override` with nothing to
 * override is refused, and so is a block written for the general kind, which has nothing below it to
 * be more specific than.
 */
class ImplOverrideTests extends AnyFreeSpec with CodegenSupport with RunSupport {

  private val show = "trait Show\n    show(self) -> string\n"

  /** A trait with a default body, its two spellings apart. */
  private val fallible = "trait Fallible2\n    failed(self) -> bool = false\n"

  "a written-out type overriding a shape" - {

    // The lookup order was already what the design asks for: `memberKey` tries the type's own key
    // before the shape's, so the more specific block answers with nothing added to resolution.
    "the override answers for the type it names" in {
      run(s"""${show}impl[T] Show for []T
             |    show(self) -> string = "any"
             |struct P
             |    v: int
             |override impl Show for []P
             |    show(self) -> string = "points"
             |var a = [P(1)]
             |var b = [1, 2]
             |print(a[0..].show())
             |print(b[0..].show())
             |""".stripMargin) shouldBe "points\nany\n"
    }

    // Written the other way about, to show the answer does not depend on which block the file put
    // first — the flag travels with the written-out type rather than with whichever is hoisted last.
    "in whichever order the file writes the two" in {
      run(s"""${show}struct P
             |    v: int
             |override impl Show for []P
             |    show(self) -> string = "points"
             |impl[T] Show for []T
             |    show(self) -> string = "any"
             |var a = [P(1)]
             |var b = [1, 2]
             |print(a[0..].show())
             |print(b[0..].show())
             |""".stripMargin) shouldBe "points\nany\n"
    }

    "and an array of the block's length is the same case" in {
      run(s"""${show}impl[T] Show for [2]T
             |    show(self) -> string = "any"
             |struct P
             |    v: int
             |override impl Show for [2]P
             |    show(self) -> string = "points"
             |var a = [P(1), P(2)]
             |print(a.show())
             |""".stripMargin) shouldBe "points\n"
    }

    // The whole point of the keyword: without it the pair is refused exactly as it was before, so a
    // duplicate written by accident still lands on the diagnostic that finds it.
    "while the unmarked pair is refused as it always was" in {
      err(s"""${show}impl[T] Show for []T
             |    show(self) -> string = "any"
             |struct P
             |    v: int
             |impl Show for []P
             |    show(self) -> string = "points"
             |""".stripMargin) should include("every slice already implements 'Show', so '[]P' has an " +
        "implementation and cannot be given a second one — write 'override impl' to say that " +
        "replacing it for this one type is what was meant")
    }

    "and so is the pair with the shape written second" in {
      err(s"""${show}struct P
             |    v: int
             |impl Show for []P
             |    show(self) -> string = "points"
             |impl[T] Show for []T
             |    show(self) -> string = "any"
             |""".stripMargin) should include("'[]P' already implements 'Show', and this 'impl' would " +
        "implement it for every slice — including that one")
    }
  }

  "an erasure sees the override, because one type still has one table" - {

    // The soundness argument of `02 §` made concrete: a `&Show` built from a `[]P` reaches the
    // override, not the shape's block, so the two levels of generality do not disagree about what a
    // `[]P` is.
    "so a trait object over the overridden type dispatches to the override" in {
      run(s"""${show}impl[T] Show for [1]T
             |    show(self) -> string = "any"
             |struct P
             |    v: int
             |override impl Show for [1]P
             |    show(self) -> string = "points"
             |var a: &[1]P = [P(1)]
             |var o: &Show = a
             |print(o.show())
             |""".stripMargin) shouldBe "points\n"
    }
  }

  "an 'override' with nothing to override" - {

    "is refused when no block covers the type more generally" in {
      err(s"""${show}struct P
             |    v: int
             |override impl Show for []P
             |    show(self) -> string = "points"
             |""".stripMargin) should include("'[]P' says 'override', but nothing else implements " +
        "'Show' for it — an override replaces an implementation that covers the type more generally, " +
        "and there is none to replace")
    }

    // What is asked is the trait **at these arguments**, not the trait: a shape may implement one
    // promise of a generic trait and the override name another, and then there is nothing under it
    // however much the two blocks look alike. This is the shape the check has to take for it to
    // catch a library that narrows what it implements rather than dropping it.
    "and it is asked of the promise the block makes, not of the trait" in {
      err(s"""trait Take[T]
             |    take(self, x: T) -> int
             |impl[U] Take[int] for []U
             |    take(self, x: int) -> int = x
             |struct P
             |    v: int
             |override impl Take[string] for []P
             |    take(self, x: string) -> int = 0
             |""".stripMargin) should include("'[]P' says 'override', but nothing else implements " +
        "'Take[string]' for it")
    }

    // A **conditional** block is one there is something to override: the condition decides which
    // slices conform, not whether the block covers the shape, so overriding it for one element type
    // is exactly the case `02 §` describes.
    "while a conditional block is something to override" in {
      run(s"""${show}impl[T: Show] Show for []T
             |    show(self) -> string = self[0].show()
             |struct P
             |    v: int
             |override impl Show for []P
             |    show(self) -> string = "points"
             |var a = [P(1)]
             |print(a[0..].show())
             |""".stripMargin) shouldBe "points\n"
    }
  }

  "'override' on the general kind is refused, since nothing is below it" - {

    "a shape covers every type it matches at once" in {
      err(s"""${show}override impl[T] Show for []T
             |    show(self) -> string = "any"
             |""".stripMargin) should include("'override' says this block replaces a more general one, " +
        "and '[]T' is the general kind")
    }

    "and a generic type has one key for all of its instantiations" in {
      err(s"""${show}struct Box[T]
             |    v: T
             |override impl[T] Show for Box[T]
             |    show(self) -> string = "box"
             |""".stripMargin) should include("an implementation for a shape or for a generic type " +
        "covers every type it matches at once, so there is nothing below it")
    }
  }

  /** `02 § What keeps this sound` argues from coherence rather than from the keyword, and these are
   * the two halves of that argument as the compiler actually enforces them.
   */
  "coherence is untouched, which is what keeps one type to one table" - {

    // The claim the soundness argument rests on: the only override anybody can write across a module
    // boundary is one naming their own type. `[]int` names nothing outside the library, so a program
    // cannot override the library's block for it however the block is marked — and the refusal is
    // coherence's, arriving after `override` has lifted the overlap.
    "so a program cannot override the library's block for a slice of a built-in" in {
      err("""override impl Display for []int
            |    display(self, out: *Writer, fmt: FormatSpec) = display_str("ints", out, fmt)
            |print(1)
            |""".stripMargin) should include("nothing in '[]int' is declared outside the library")
    }

    // And exactly one override per type, which is the other half: two blocks for one key are two
    // blocks for one key whatever they say, since neither is more specific than the other.
    "and one type takes one override, not a stack of them" in {
      err(s"""${show}impl[T] Show for []T
             |    show(self) -> string = "any"
             |struct P
             |    v: int
             |override impl Show for []P
             |    show(self) -> string = "points"
             |override impl Show for []P
             |    show(self) -> string = "again"
             |""".stripMargin) should include("'[]P' already implements 'Show'")
    }
  }

  /** The third kind of general block, and the one with the most obvious use: the library's
   * `impl[T: Integer] Display for T` covers every integer, and `16 §3` gives a derived subtype its
   * base's memberships — so a named unit type printed the way its base prints was a documented
   * refusal with no way round it. Now it says `override`.
   */
  "a derived subtype overrides the blanket its base is covered by" in {
    run("""type Stamp = new int
          |override impl Display for Stamp
          |    display(self, out: *Writer, fmt: FormatSpec) = display_str("#" + str(int(self)), out, fmt)
          |var s: Stamp = Stamp(7)
          |print(s)
          |print(7)
          |""".stripMargin) shouldBe "#7\n7\n"
  }

  /** A tuple is a shape like a slice — its arity is the key — so the same pairing works there, and
   * it is worth pinning because the library's tuple blocks are the ones an override meets first.
   */
  "a tuple of the program's own types overrides the library's block for its arity" in {
    run("""struct P
          |    v: int
          |impl Display for P
          |    display(self, out: *Writer, fmt: FormatSpec) = display_int(i64(self.v), out, fmt)
          |override impl Display for (P, int)
          |    display(self, out: *Writer, fmt: FormatSpec) = display_str("a pair", out, fmt)
          |var t = (P(1), 2)
          |var u = (1, 2)
          |print(t)
          |print(u)
          |""".stripMargin) shouldBe "a pair\n(1, 2)\n"
  }

  "a member that replaces a trait's default body says so" - {

    "the keyword is required where a body is replaced" in {
      err(s"""${fallible}struct File
             |    e: int
             |impl Fallible2 for File
             |    failed(self) -> bool = self.e != 0
             |""".stripMargin) should include("trait 'Fallible2' supplies a body for method 'failed', " +
        "so writing one here replaces it — say 'override failed', or leave the member out to keep " +
        "the trait's")
    }

    "and with it the member replaces the default" in {
      run(s"""${fallible}struct File
             |    e: int
             |impl Fallible2 for File
             |    override failed(self) -> bool = self.e != 0
             |print(File(1).failed())
             |print(File(0).failed())
             |""".stripMargin) shouldBe "true\nfalse\n"
    }

    // An implementation content with the default writes no member at all, which is what makes the
    // requirement cost almost nothing.
    "while leaving the member out keeps the trait's body and needs no keyword" in {
      run(s"""${fallible}struct File
             |    e: int
             |impl Fallible2 for File
             |print(File(1).failed())
             |""".stripMargin) shouldBe "false\n"
    }

    "and the keyword is refused where the trait declared no body" in {
      err(s"""${show}struct P
             |    v: int
             |impl Show for P
             |    override show(self) -> string = "p"
             |""".stripMargin) should include("trait 'Show' declares method 'show' without a body, so " +
        "this member supplies what the trait asked for rather than replacing anything")
    }

    // A property carries a body in its declaration form, so the same question is asked of it and the
    // diagnostic names the kind it is.
    "a property's default is replaced the same way" in {
      err("""trait Named
            |    label -> string = "?"
            |struct P
            |    v: int
            |impl Named for P
            |    label -> string = "p"
            |""".stripMargin) should include("supplies a body for property 'label'")
    }
  }

  "where the word may not be written at all" - {

    "a trait's own member is where a default is written rather than replaced" in {
      err("""trait Named
            |    override label -> string = "?"
            |print(1)
            |""".stripMargin) should include("a trait's own member is where a default is written " +
        "rather than replaced")
    }

    // An inherent member implements no trait — a name a trait also declares is a collision on the
    // type, reported as one — so there is nothing for the keyword to mark.
    "and a member of a type's own body implements no trait" in {
      err("""struct P
            |    v: int
            |    override show(self) -> string = "p"
            |print(1)
            |""".stripMargin) should include("a member of a type's body implements no trait")
    }

    "nor does a declaration of its own replace anything" in {
      err("""override struct P
            |    v: int
            |print(1)
            |""".stripMargin) should include("'override' marks something that replaces an " +
        "implementation already covering the same type")
    }
  }
}
