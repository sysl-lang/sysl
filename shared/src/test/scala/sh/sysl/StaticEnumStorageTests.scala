package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** A module-level slot holding a variant that carries nothing — `Option[T] = None` above all — laid
 * into the object file rather than filled by an initializer
 * (`reference/modules.md § val — a thing`).
 *
 * **What this is for is the freestanding case, and nothing else would have noticed.** A `val` or
 * `var` whose initializer is not a constant tree becomes *computed*: the program's prologue fills it
 * before anything else runs. That is invisible on a hosted target and fatal on a bare one, where a
 * `build-c` archive has no entry point and there is no loader to run a constructor — so an `@export`
 * reaching such a slot is refused outright, naming the storage. A module whose only offence was a
 * `None` was shut out of every board program that way.
 *
 * **A dataless variant of a SIMPLE enum was never affected**, which is why this went unnoticed: such
 * an enum lowers to its discriminant, so `var level: Level = Level.Info` was a `TIntLit` and always
 * folded. What did not was the empty variant of an enum whose *other* variants carry something,
 * which is exactly `Option`'s `None` and exactly the shape a slot that starts out unset has.
 *
 * The cases below are the tag not being zero — which is where `Option`'s own `None` lands, since
 * `Some` is written first — the tag being zero, that each reads back as the variant it was written
 * as, and the limit that stays: a variant carrying something is still computed, because filling a
 * payload means knowing which bytes of a union a variant's fields land on, and that is a store
 * rather than a constant.
 */
class StaticEnumStorageTests extends AnyFreeSpec with CodegenSupport with RunSupport {

  /** A `val` is emitted `constant` when its initializer folded and `global` when a prologue has to
   * fill it, so the keyword is the discriminator — and it is the *only* one that does not depend on
   * reading the prologue's stores.
   */
  private def globals(out: String): List[String] =
    out.linesIterator.filter(l => l.startsWith("@") && l.contains(" = private ")).toList

  "a variant carrying nothing is constant data" - {

    "'None' at a payload-carrying enum, which is the case that blocked a board" in {
      val out = irIn(
        ("", "main.sysl", "print(slot.peek())"),
        ("slot", "s.sysl",
         """module slot
           |
           |val held: Option[int] = None
           |
           |peek() -> int = held match
           |    Some(v) -> v
           |    None -> 7""".stripMargin),
      )

      // `constant` rather than `global`: the storage is in the object file, and no store fills it.
      globals(out).find(_.contains("slot$held")) match
        case Some(line) => line should include("private constant")
        case None       => fail(s"no global for 'slot.held':\n${globals(out).mkString("\n")}")
    }

    "and at a trait pointer, which has no null a program could have written instead" in {
      // The workaround a caller would otherwise reach for does not exist: `ptr_cast(0)` is refused
      // at a trait, a pointer to one being two words. So `None` is the only way to say "unset" and
      // it had to be constant for the slot to exist at all.
      val out = irIn(
        ("", "main.sysl", "print(slot.peek())"),
        ("slot", "s.sysl",
         """module slot
           |
           |val held: Option[*Display] = None
           |
           |peek() -> int = held match
           |    Some(_) -> 1
           |    None -> 7""".stripMargin),
      )

      globals(out).find(_.contains("slot$held")) match
        case Some(line) => line should include("private constant")
        case None       => fail(s"no global for 'slot.held':\n${globals(out).mkString("\n")}")
    }

    "a variant whose tag is not zero, which cannot be 'zeroinitializer'" in {
      // The whole-zero form is right only when the tag is, so this one has to write the tag out —
      // and getting it wrong reads back as the variant at tag zero, which is a value the program
      // would then take apart. The run below is what says which was written.
      val out = irIn(
        ("", "main.sysl", "print(slot.peek())"),
        ("slot", "s.sysl",
         """module slot
           |
           |enum Where
           |    Carried(n: int)
           |    Nowhere
           |end Where
           |
           |val held: Where = Where.Nowhere
           |
           |peek() -> int = held match
           |    Carried(n) -> n
           |    Nowhere -> 7""".stripMargin),
      )

      globals(out).find(_.contains("slot$held")) match
        case Some(line) =>
          line should include("private constant")

          // The value written out rather than `zeroinitializer`, since the tag is not zero. Getting
          // this wrong reads back as whatever variant sits at zero, which the run below catches.
          line should include("i32 1")
        case None => fail(s"no global for 'slot.held':\n${globals(out).mkString("\n")}")
    }

    "a variant whose tag IS zero, which is the whole-zero form" in {
      // The other branch of the emitter, and the one `Option` does not reach: `None` is `Option`'s
      // SECOND variant, so its tag is one. A nullary variant written first is what gets tag zero,
      // and there the whole value is zero bits and `zeroinitializer` says so in one word.
      val out = irIn(
        ("", "main.sysl", "print(slot.peek())"),
        ("slot", "s.sysl",
         """module slot
           |
           |enum Where
           |    Nowhere
           |    Carried(n: int)
           |end Where
           |
           |val held: Where = Where.Nowhere
           |
           |peek() -> int = held match
           |    Carried(n) -> n
           |    Nowhere -> 7""".stripMargin),
      )

      globals(out).find(_.contains("slot$held")) match
        case Some(line) =>
          line should include("private constant")
          line should include("zeroinitializer")
        case None => fail(s"no global for 'slot.held':\n${globals(out).mkString("\n")}")
    }
  }

  "and it reads back as the variant it was written as" - {

    "'None' rather than whatever a zeroed payload would be taken for" in {
      runIn(
        ("", "main.sysl", "print(slot.peek())"),
        ("slot", "s.sysl",
         """module slot
           |
           |val held: Option[int] = None
           |
           |peek() -> int = held match
           |    Some(v) -> v
           |    None -> 7""".stripMargin),
      ) shouldBe "7\n"
    }

    "a non-zero tag rather than the variant sitting at zero" in {
      runIn(
        ("", "main.sysl", "print(slot.peek())"),
        ("slot", "s.sysl",
         """module slot
           |
           |enum Where
           |    Carried(n: int)
           |    Nowhere
           |end Where
           |
           |val held: Where = Where.Nowhere
           |
           |peek() -> int = held match
           |    Carried(n) -> n
           |    Nowhere -> 7""".stripMargin),
      ) shouldBe "7\n"
    }

    "a 'var' the program then writes, which is the shape a settable slot has" in {
      runIn(
        ("", "main.sysl", "slot.install(4)\nprint(slot.peek())"),
        ("slot", "s.sysl",
         """module slot
           |
           |var held: Option[int] = None
           |
           |install(n: int) = held = Some(n)
           |
           |peek() -> int = held match
           |    Some(v) -> v
           |    None -> 7""".stripMargin),
      ) shouldBe "4\n"
    }
  }

  "a variant that carries something is still computed" - {

    /* The limit, stated where somebody widening this will read it. A payload lives in a region every
     * variant shares, written as an array of whatever alignment the widest one needs — so laying a
     * carried value into it means knowing which bytes of that array the variant's fields occupy,
     * which is `CallEmitter`'s store through a pointer and is not something a constant expression
     * can spell. */

    "so a slot holding one is filled by the prologue rather than laid down" in {
      val out = irIn(
        ("", "main.sysl", "print(slot.peek())"),
        ("slot", "s.sysl",
         """module slot
           |
           |val held: Option[int] = Some(3)
           |
           |peek() -> int = held match
           |    Some(v) -> v
           |    None -> 7""".stripMargin),
      )

      globals(out).find(_.contains("slot$held")) match
        case Some(line) => line should include("private global")
        case None       => fail(s"no global for 'slot.held':\n${globals(out).mkString("\n")}")
    }
  }
}
