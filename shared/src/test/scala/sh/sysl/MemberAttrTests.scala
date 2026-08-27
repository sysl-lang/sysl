package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** The three annotations a **member** may carry, which are the ones that are about a **parameter**:
 * `@crossing`, `@reads` and `@writes` (card `0313`).
 *
 * **The rule is what the annotation is about, not what it is written above.** `@test` names what a
 * runner calls, `@tailrec` what recurses, `@export` what a symbol names, `@packed`/`@align` how a
 * type is laid out — none of which a member supplies. These three name parameters, and a member has
 * parameters exactly as a free function does, so refusing them meant an API that wanted one had to
 * route every such call through a free function whose only purpose was to carry the word.
 * `sysl.posix.threads.Channel[T]`'s transfers were exactly that.
 *
 * `MemberAttrErrorTests` is the other half: everything else, refused, with the caret on the `@`.
 */
class MemberAttrTests extends AnyFreeSpec with CodegenSupport with RunSupport {

  "a member may carry '@crossing', and the rule is asked at the call" - {

    "on a method of a struct, where the value satisfies it" in {
      run("""struct Chan
            |    open: bool
            |
            |    @crossing(v)
            |    send(*self, v: *int) -> int = v[0]
            |
            |var c = Chan(true)
            |var n = 7
            |print(c.send(&n))
            |""".stripMargin) shouldBe "7\n"
    }

    // The whole point of admitting it: the refusal fires at the **call**, off the member's own
    // signature, with no wrapper anywhere in the program.
    "and the refusal fires where the value does not" in {
      // A `&Node` whose count is not atomic is the shape the rule exists to refuse, and the `*T`
      // parameter is looked *through* — so what crossed is the `Job` at the far end.
      val e = err("""struct Node
                    |    v: int
                    |
                    |struct Job
                    |    node: &Node
                    |
                    |struct Chan
                    |    open: bool
                    |
                    |    @crossing(v)
                    |    send(*self, v: *Job) -> int = v[0].node.v
                    |
                    |var c = Chan(true)
                    |var n: &Node = Node(1)
                    |var j = Job(n)
                    |print(c.send(&j))
                    |""".stripMargin)

      e should include("reaches another concurrency domain")
    }

    "on an associated function, which has no receiver at all" in {
      run("""struct Chan
            |    open: bool
            |
            |    @crossing(v)
            |    of(v: *int) -> int = v[0]
            |
            |var n = 9
            |print(Chan.of(&n))
            |""".stripMargin) shouldBe "9\n"
    }

    "on a member of an enum" in {
      run("""enum Slot
            |    Full
            |    Empty
            |
            |    @crossing(v)
            |    put(self, v: *int) -> int = v[0]
            |
            |var n = 3
            |print(Slot.Full.put(&n))
            |""".stripMargin) shouldBe "3\n"
    }

    "and on a member of an 'impl' block, which is where a trait's is supplied" in {
      run("""trait Sink
            |    take(self, v: *int) -> int
            |
            |struct S
            |    base: int
            |
            |impl Sink for S
            |    @crossing(v)
            |    take(self, v: *int) -> int = self.base + v[0]
            |
            |var s = S(10)
            |var n = 5
            |print(s.take(&n))
            |""".stripMargin) shouldBe "15\n"
    }
  }

  /** The name is checked against the member's **own** parameters, and the receiver is not one of
   * them — it is prepended when the member is lowered, so a `@crossing(self)` would be naming
   * something the writer did not declare.
   */
  "what it may name is the member's own parameters" - {

    "a word that is not one is refused by name" in {
      val e = err("""struct Chan
                    |    open: bool
                    |
                    |    @crossing(w)
                    |    send(*self, v: *int) -> int = v[0]
                    |
                    |print(1)
                    |""".stripMargin)

      e should include("'w'")
      e should include("not a parameter")
    }
  }

  "a member may carry a frame, which is the same kind of claim" - {

    "'@reads' and '@writes' name parameters and module storage alike" in {
      run("""static var counter: int = 0
            |
            |struct Tick
            |    step: int
            |
            |    @writes(counter)
            |    bump(self) = counter += self.step
            |
            |    @reads(counter)
            |    peek(self) -> int = counter
            |
            |var t = Tick(2)
            |t.bump()
            |t.bump()
            |print(t.peek())
            |""".stripMargin) shouldBe "4\n"
    }

    // The empty frame is a claim rather than an absence, exactly as it is on a free function: it
    // says the member writes no module storage at all.
    "an empty '@writes()' on a member that writes something is refused" in {
      val e = err("""static var counter: int = 0
                    |
                    |struct Tick
                    |    step: int
                    |
                    |    @writes()
                    |    bump(self) = counter += self.step
                    |
                    |print(1)
                    |""".stripMargin)

      e should include("counter")
    }
  }

  "the annotation travels through the artifact, since the check is made at the call" in {
    // A member's `@crossing` is read back by a consumer that has only the artifact, so a codec that
    // dropped it would be the same signature with the rule silently off for everybody but the
    // library's own tests. `AstCodec.Version` moved for this.
    val src =
      """module m
        |
        |struct Chan
        |    open: bool
        |
        |    @crossing(v)
        |    send(*self, v: *int) -> int = v[0]
        |""".stripMargin

    val p = SyslParser.parse(Source("<input>", src)) match {
      case Right(prog) => prog
      case Left(e)     => fail(e)
    }

    AstCodec.decode(AstCodec.encode(List(p)), Map("<input>" -> p.source)) match {
      case Left(e) => fail(s"decode failed: $e")
      case Right(List(back)) =>
        val member = back.body.collectFirst { case s: StructDecl => s.members.head }
          .getOrElse(fail("no member came back"))

        member.crossing shouldBe List("v")
      case Right(other) => fail(s"expected one program, got ${other.length}")
    }
  }
}
