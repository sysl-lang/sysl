package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** What a `@crossing` parameter **accepts**, run rather than compiled (`06 § Marking a domain
 * boundary`).
 *
 * The annotation adds a refusal and nothing else — it emits no code, changes no signature, and a
 * program that satisfies it is the program it would have been without the line. These are what says
 * so, and they are the half of the rule that would go unnoticed if it were wrong: an over-strict
 * walk fails a build, and this is where such a failure shows up.
 */
class CrossingAttrRunTests extends AnyFreeSpec with CodegenSupport with RunSupport {

  "what may be handed to another domain through a pointer" - {

    "a scalar at the far end" in {
      run("""@crossing(state)
            |start(state: *int) -> int = state[0]
            |var n = 7
            |print(start(&n))
            |""".stripMargin) shouldBe "7\n"
    }

    "an object whose every count is atomic" in {
      run("""struct Node
            |    v: int
            |struct Job
            |    node: &sync Node
            |@crossing(state)
            |start(state: *Job) -> int = state[0].node.v
            |var n: &sync Node = Node(41)
            |var j = Job(n)
            |print(start(&j) + 1)
            |""".stripMargin) shouldBe "42\n"
    }

    // The walk's other leaf, reached through the pointer rather than through a field: a `*T` has no
    // count to make atomic, so there is nothing here to say about it.
    "an object holding another raw pointer" in {
      run("""struct Job
            |    slot: *int
            |@crossing(state)
            |start(state: *Job) -> int = state[0].slot[0]
            |var n = 7
            |var j = Job(&n)
            |print(start(&j))
            |""".stripMargin) shouldBe "7\n"
    }
  }

  "a parameter that is not a pointer crosses as itself" - {

    "a plain value" in {
      run("""struct Reading
            |    n: int
            |    ok: bool
            |@crossing(r)
            |start(r: Reading) -> int = if r.ok then r.n else 0
            |print(start(Reading(7, true)))
            |""".stripMargin) shouldBe "7\n"
    }

    "an atomic reference" in {
      run("""struct Node
            |    v: int
            |@crossing(state)
            |start(state: &sync Node) -> int = state.v
            |var n: &sync Node = Node(9)
            |print(start(n))
            |""".stripMargin) shouldBe "9\n"
    }
  }

  /** A generic facility is asked afresh per instantiation, so one that shares nothing is unaffected
   * by a sibling instantiation that would have been refused.
   */
  "a generic parameter, at an instantiation that shares nothing" in {
    run("""@crossing(state)
          |start[T](state: *T) -> unit = ()
          |var n = 7
          |var c = 'x'
          |start(&n)
          |start(&c)
          |print(n)
          |""".stripMargin) shouldBe "7\n"
  }
}
