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
