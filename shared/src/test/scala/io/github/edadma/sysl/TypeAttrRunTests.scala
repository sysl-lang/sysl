package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** The attributes a constrained integer subtype exposes: its bounds (`First`/`Last`, honouring an
 * exclusive upper), the total membership test (`Valid`, which never traps), the trapping steps
 * (`Succ`/`Pred`), and `Range` as a `for` loop's iterable.
 */
class TypeAttrRunTests extends AnyFreeSpec with RunSupport {

  private val Age  = "type Age = int within 0..150\n"
  private val Prob = "type Prob = int within 0..<10\n"

  "First and Last read the bounds" - {
    "an inclusive range reports both endpoints" in {
      run(Age + "print(Age::First, Age::Last)") shouldBe "0 150\n"
    }
    "an exclusive upper bound reports one below it" in {
      run(Prob + "print(Prob::First, Prob::Last)") shouldBe "0 9\n"
    }
  }

  "Valid is a total membership test" - {
    "an in-range value is valid" in {
      run(Age + "print(Age::Valid(40))") shouldBe "true\n"
    }
    "an out-of-range value is not — and does not trap" in {
      run(Age + "print(Age::Valid(200))") shouldBe "false\n"
    }
    "the excluded upper endpoint is not valid" in {
      run(Prob + "print(Prob::Valid(10))") shouldBe "false\n"
    }
  }

  "Succ and Pred step within the range" - {
    "they move one along" in {
      run(Age + "print(Age::Succ(40), Age::Pred(40))") shouldBe "41 39\n"
    }
    "Succ traps at Last" in {
      exits(Age + "print(Age::Succ(150))")
    }
    "Pred traps at First" in {
      exits(Age + "print(Age::Pred(0))")
    }
    "Succ at the value below Last is fine" in {
      run(Age + "print(Age::Succ(149))") shouldBe "150\n"
    }
  }

  "Range drives a for loop over First..Last inclusive" in {
    run(Prob + "var sum = 0\nfor i in Prob::Range do\n    sum = sum + i\nprint(sum)") shouldBe "45\n"
  }

  /** What the attributes are typed *as*. Every test above uses a **transparent** subtype, where the
   * subtype and its base agree and the question cannot be asked — so these use a `new` one, which
   * is the only place the answer is observable. `16 §5` rules that all of them but `Valid` speak the
   * subtype: a bound of `T` is a `T`, and the step from one `T` is another. `Valid` takes the base,
   * because asking whether a value is a `T` is only a question about something that is not one yet.
   */
  private val Slot = "type Slot = new u8 within 0..<8\nonly(s: Slot) -> int = int(s)\n"

  "on a derived subtype the attributes are the subtype, not its base" - {
    "a bound is a value of the type it bounds" in {
      run(Slot + "print(only(Slot::First), only(Slot::Last))") shouldBe "0 7\n"
    }

    "a step takes one and gives another" in {
      run(Slot + "print(only(Slot::Succ(Slot(3u8))), only(Slot::Pred(Slot(3u8))))") shouldBe "4 2\n"
    }

    "and the loop variable of Range is one too" in {
      run(Slot + "var n = 0\nfor s in Slot::Range do n += only(s)\nprint(n)") shouldBe "28\n"
    }

    // The point of all three: a `new` subtype exists to be nominally distinct from its base, and an
    // attribute surface that handed back the base would make its own type the one thing you had to
    // cast away to use it.
    "so stepping round a ring needs no cast through the base" in {
      run(Slot + "wrap(s: Slot) -> Slot = Slot(u8((usize(s) + 1) % 8))\n" +
        "print(only(wrap(Slot::Last)), only(wrap(Slot::First)))") shouldBe "0 1\n"
    }

    // `Valid` is the exception, and the asymmetry is the whole of its job.
    "while Valid asks about the base, since its answer may be no" in {
      run(Slot + "print(Slot::Valid(7u8), Slot::Valid(8u8), Slot::Valid(255u8))") shouldBe
        "true false false\n"
    }
  }

  // `16 §5` says `::` is what keeps these out of the member namespace. A member of the same name is
  // the discriminating case: if the two shared a namespace one would have to win, and the chapter's
  // claim is that the question never arises.
  "an attribute is not a member, so a member of the same name does not shadow it" in {
    run(
      Age +
        """trait Marked
          |    First -> int
          |impl Marked for Age
          |    First -> int = 99
          |var a = Age(7)
          |print(Age::First, a.First)""".stripMargin
    ) shouldBe "0 99\n"
  }
}
