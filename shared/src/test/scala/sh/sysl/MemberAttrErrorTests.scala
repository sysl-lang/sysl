package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** An annotation written above a **member** — a method, a property, an associated function, a field
 * or an enum variant.
 *
 * No annotation in this language marks one (`06 § What it does not reach`), and until this suite the
 * grammar said so by having no alternative that begins with `@`: the block simply ended there, and
 * what the reader was shown was whichever rule was going to complain about the line anyway —
 * `dedent expected` where a member had already been read, `identifier expected` where none had.
 * Both are about indentation and about names, which is the one thing that is not wrong.
 *
 * So the assertions below are about **what the sentence says and where the caret is**, in every
 * block that reads a member, rather than about the refusal existing. The caret belongs on the `@`
 * itself, since that is the line the reader has to delete.
 */
class MemberAttrErrorTests extends AnyFreeSpec with ParseSupport {

  /** The message and the `file:line:column` of a refusal, from the rendered diagnostic. */
  private def refusal(src: String): (String, String) = {
    val out   = progError(src)
    val lines = out.linesIterator.toList
    val msg   = lines.find(_.startsWith("error: ")).getOrElse(fail(out)).stripPrefix("error: ")
    val where = lines.find(_.trim.startsWith("-->")).getOrElse(fail(out)).trim.stripPrefix("--> ")

    (msg, where)
  }

  private val sentence = "an annotation marks a function, and a member is not one"

  "an annotation above a member is refused with a sentence" - {

    // The reduction the card was written from: the `@` follows a field, so the member block had
    // already read a line and ended at the annotation — which is where `dedent expected` came from.
    "a method inside a struct, after a field" in {
      val (msg, where) = refusal(
        """struct S
          |    v: int
          |
          |    @crossing(p)
          |    take(self, p: *int) -> int = p[0]
          |
          |main() =
          |    print(1)
          |""".stripMargin,
      )

      msg should startWith(sentence)
      where shouldBe "<input>:4:5"
    }

    // The other half of the same defect: with no member read yet the block had nothing to end, so
    // the complaint came from the field rule instead and said `identifier expected`.
    "a method inside a struct, as the block's first line" in {
      val (msg, where) = refusal(
        """struct S
          |    @test
          |    f(self) -> int = 1
          |
          |main() =
          |    print(1)
          |""".stripMargin,
      )

      msg should startWith(sentence)
      where shouldBe "<input>:2:5"
    }

    "a field" in {
      val (msg, where) = refusal(
        """struct S
          |    @align(8)
          |    v: int
          |
          |main() =
          |    print(1)
          |""".stripMargin,
      )

      msg should startWith(sentence)
      where shouldBe "<input>:2:5"
    }

    "a member of an enum" in {
      val (msg, where) = refusal(
        """enum E
          |    A
          |    B
          |
          |    @pure
          |    tag(self) -> int = 0
          |
          |main() =
          |    print(1)
          |""".stripMargin,
      )

      msg should startWith(sentence)
      where shouldBe "<input>:5:5"
    }

    "a variant" in {
      val (msg, where) = refusal(
        """enum E
          |    @test
          |    A
          |
          |main() =
          |    print(1)
          |""".stripMargin,
      )

      msg should startWith(sentence)
      where shouldBe "<input>:2:5"
    }

    "a member of a trait" in {
      val (msg, where) = refusal(
        """trait T
          |    @pure
          |    f(self) -> int
          |
          |main() =
          |    print(1)
          |""".stripMargin,
      )

      msg should startWith(sentence)
      where shouldBe "<input>:2:5"
    }

    "a member of an impl block" in {
      val (msg, where) = refusal(
        """trait T
          |    f(self) -> int
          |
          |struct S
          |    v: int
          |
          |impl T for S
          |    @test
          |    f(self) -> int = self.v
          |
          |main() =
          |    print(1)
          |""".stripMargin,
      )

      msg should startWith(sentence)
      where shouldBe "<input>:8:5"
    }

    // The sentence has to say where the annotation goes instead, since deleting it is not what the
    // reader wanted — a `@test` becomes a free function that calls the member, and a `@crossing`
    // goes on the wrapper a caller already goes through (`06`).
    "the sentence names where the annotation goes instead" in {
      val (msg, _) = refusal(
        """struct S
          |    @test
          |    f(self) -> int = 1
          |
          |main() =
          |    print(1)
          |""".stripMargin,
      )

      msg should include("above a free function")
      msg should include("'sysl test'")
      msg should include("'@crossing'")
    }
  }

  /** `@assert` is not an annotation about the declaration under it — it stands *where* a declaration
   * stands and describes nothing but itself, which is why `assertDecl` is ordered before
   * `attributedDecl` at statement position. A member block has to make the same distinction, or the
   * sentence about what annotations mark is exactly the wrong thing to say about it.
   */
  "'@assert' inside a type's body is told what it is, not what annotations mark" - {

    "inside a struct" in {
      val (msg, where) = refusal(
        """struct S
          |    v: int
          |
          |    @assert(sizeof(S) == 8)
          |
          |main() =
          |    print(1)
          |""".stripMargin,
      )

      msg should startWith("'@assert' stands where a declaration stands")
      msg should include("beside the type")
      where shouldBe "<input>:4:5"
    }

    "inside an impl block" in {
      val (msg, where) = refusal(
        """trait T
          |    f(self) -> int
          |
          |struct S
          |    v: int
          |
          |impl T for S
          |    @assert(1 == 1)
          |    f(self) -> int = self.v
          |
          |main() =
          |    print(1)
          |""".stripMargin,
      )

      msg should startWith("'@assert' stands where a declaration stands")
      where shouldBe "<input>:8:5"
    }
  }

  /** What the refusal must not have taken with it: a member block still reads everything it read
   * before, and the two refusals it already carried still fire. Both are checked because the new
   * rule is the **first** thing each member line is read through, so it is the one placed to
   * swallow them.
   */
  "the blocks still read what they read before" - {

    "a struct with fields, a member, an invariant and a visibility modifier" in {
      prog(
        """struct S
          |    private v: int
          |    w: int
          |
          |    invariant v > 0
          |
          |    private sum(self) -> int = self.v + self.w
          |    scale -> int = self.v
          |
          |main() =
          |    print(1)
          |""".stripMargin,
      ) should not be empty
    }

    "an impl block still refuses a visibility modifier on its member" in {
      progError(
        """trait T
          |    f(self) -> int
          |
          |struct S
          |    v: int
          |
          |impl T for S
          |    private f(self) -> int = self.v
          |
          |main() =
          |    print(1)
          |""".stripMargin,
      ) should include("carry no visibility of their own")
    }

    "a type's own member still refuses 'override'" in {
      progError(
        """struct S
          |    v: int
          |
          |    override f(self) -> int = self.v
          |
          |main() =
          |    print(1)
          |""".stripMargin,
      ) should include("'override' says a member replaces a body its trait supplied")
    }
  }
}
