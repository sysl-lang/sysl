package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** What the raw tier refuses (`03 § Reinterpreting storage`).
 *
 * The shape of the whole section is here: reading an address *out of* a pointer is an ordinary
 * conversion and reading one *into* a pointer is not, so the refusals cluster on the second
 * direction — what `ptr_cast` may produce, and what it may be given.
 */
class RawStorageErrorTests extends AnyFreeSpec with CodegenSupport {

  private val arena = "var arena: [64]u8 = [0u8; 64]\nvar p: *u8 = &arena[0]\n"

  "the target comes from the context, so a context that says nothing is refused" - {
    "a binding with no annotation" in {
      val e = err(arena + "var q = ptr_cast(p)")
      e should include("nothing here says which")
      e should include("annotate the variable, field, or result it is read into")
    }
    "a discarded expression" in {
      err(arena + "ptr_cast(p)") should include("nothing here says which")
    }
  }

  /** The one refusal that is about the safe subset rather than about shapes. A `&T` is counted and
   * non-null, and an address invented from bytes says neither — so this is the boundary the compiler
   * can still police once the alias itself has stopped being checkable.
   */
  "it never produces a reference" - {
    "a '&T' binding" in {
      val e = err(arena + "var r: &int = ptr_cast(p)")
      e should include("never produces a reference")
      e should include("carries no count")
    }
    "a 'weak T' binding" in {
      err("struct Node\n    v: int\n" + arena + "var w: weak Node = ptr_cast(p)") should
        include("never produces a weak reference")
    }
  }

  "it produces a pointer, so a target that is not one is refused with what it is" - {
    "a plain scalar" in {
      err(arena + "var n: int = ptr_cast(p)") should include("'ptr_cast' produces a pointer, and int is not one")
    }
    "a slice, which is a pointer and a length together" in {
      val e = err(arena + "var s: []u8 = ptr_cast(p)")
      e should include("a pointer and a length together")
      e should include("p[0..<n]")
    }
    "a string, for the same reason" in {
      err(arena + "var s: string = ptr_cast(p)") should include("a pointer and a length together")
    }
    "a pointer to a trait, which is two words rather than an address" in {
      val e = err("trait Shape\n    area(self) -> int\n" + arena + "var o: *Shape = ptr_cast(p)")
      e should include("a pointer to a trait is two words")
    }
  }

  "the value it is given must be an address" - {
    "a fixed-width integer is not one on every target" in {
      val e = err(arena + "var n = 4u32\nvar q: *u8 = ptr_cast(n)")
      e should include("is not address-width")
      e should include("ptr_cast(usize(n))")
    }
    "a value that is not a number at all" in {
      err(arena + "var q: *u8 = ptr_cast(true)") should
        include("takes a pointer or an address-width integer")
    }
    "a trait object, whose address half is reached by reading through it" in {
      val src =
        """trait Shape
          |    area(self) -> int
          |struct Sq
          |    s: int
          |impl Shape for Sq
          |    area(self) -> int
          |        self.s * self.s
          |var sq = Sq(2)
          |var o: *Shape = &sq
          |var q: *u8 = ptr_cast(o)""".stripMargin

      err(src) should include("a pointer to a trait is two words")
    }
  }

  "it takes exactly one value" in {
    err(arena + "var q: *u8 = ptr_cast(p, p)") should include("takes exactly one value")
  }

  /** The inverse direction is an ordinary conversion, so its refusals are the conversion table's
   * (`01`): only the two address-width targets, and nothing at all for a fat pointer.
   */
  "an address is read as 'usize' or 'isize' and not as a fixed width" - {
    "a 32-bit target" in {
      val e = err(arena + "var n = u32(p)")
      e should include("read as 'usize' or 'isize'")
    }
    "'int', which is a fixed width like any other" in {
      err(arena + "var n = int(p)") should include("read as 'usize' or 'isize'")
    }
    "a pointer to a trait is not a number" in {
      val src =
        """trait Shape
          |    area(self) -> int
          |struct Sq
          |    s: int
          |impl Shape for Sq
          |    area(self) -> int
          |        self.s * self.s
          |var sq = Sq(2)
          |var o: *Shape = &sq
          |var n = usize(o)""".stripMargin

      err(src) should include("so it is not a number")
    }
    "and there is no conversion the other way" in {
      err("var n = 4096usize\nvar p: *u8 = n") should include("declared *byte but the value is usize")
    }
  }

  /** The operands with no width to give. `Layout` answers with a `sys.error` for each of these,
   * because nothing inside the compiler ever asks — so the question is whether the *diagnostic*
   * arrives first, which is what keeps a reader's typo from ending in a stack trace.
   */
  "an operand that has no storage is refused before it is measured" - {
    "a bare trait name, which is only ever the pointee of a pointer" in {
      err("trait Shape\n    area(self) -> int\nprint(sizeof(Shape))") should not be empty
    }
    "a name that is not a type at all" in {
      err("print(sizeof(Nope))") should not be empty
    }
    "a name that is a value rather than a type" in {
      err("var n = 3\nprint(sizeof(n))") should not be empty
    }
    "a generic given the wrong number of arguments" in {
      err("print(sizeof(Option[int, int]))") should not be empty
    }
  }

  /** Found by asking `sizeof` about one: `unit` has a width, and an array of it is refused anyway.
   * The two are not in tension — the array rule is about there being no element to address, not
   * about the width being zero — but a reader who has just been told the width is a number can
   * reasonably try, so the refusal is pinned beside the measurement.
   */
  "a zero-sized type has a width, and an array of one is still refused" in {
    err("print(sizeof([8]unit))") should include("occupies no storage")
  }

  /** A type's own size cannot be part of its own layout — C's "incomplete type", reached here
   * through the array bound that asks. It used to end the compiler with a stack trace about a `void`
   * alignment; the diagnostic the reader is owed is the cycle, at the `sizeof` that closed it.
   */
  "a type may not be laid out in terms of its own size" in {
    val e = err("struct Loop\n    bytes: [sizeof(Loop)]u8\nprint(sizeof(Loop))")
    e should include("contains itself")
    e should include("Loop")
  }
}
