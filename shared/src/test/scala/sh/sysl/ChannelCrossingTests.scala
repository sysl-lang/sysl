package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** What a `Channel[T]` may carry, which is the language's rule about leaving a concurrency domain
 * asked of a **value** (`library/sync.md`, `reference/memory.md § Crossing a concurrency domain`).
 *
 * **These are here rather than in `library/sysl/posix/threads/tests.sysl` because they are refusals**
 * — what the compiler will not compile, quoted, which is a claim a `@test` cannot make about itself.
 * The behaviour of the ring and of closing is in that file, where it belongs.
 *
 * The channel is the first thing in the library whose *element* type is held to the rule. Everything
 * else in the module shares by address, so what the rule looked at was whatever a `*T` pointed at;
 * here a value itself goes across, and `send`'s `@crossing(value)` is what asks.
 */
class ChannelCrossingTests extends AnyFreeSpec with RunSupport with CodegenSupport {

  private val importing = "import sysl.posix.threads.*\n\n"

  "what may cross" - {

    "a scalar crosses, which is the ordinary case" in {
      run(importing +
        """var slots: [2]int = [0; 2]
          |var ch = channel(slots[..])
          |
          |print(ch.try_send(7), ch.try_receive())
          |""".stripMargin) shouldBe "true Some(7)\n"
    }

    // A plain count is not atomic, and the whole point of a channel is that the object arrives on a
    // thread the count knows nothing about.
    "a counted reference does not, and the message names the atomic spelling" in {
      err(importing +
        """struct Node
          |    n: int
          |
          |var slots: [2]&Node = [Node(0); 2]
          |var ch = channel(slots[..])
          |
          |print(ch.send(Node(1)))
          |""".stripMargin) should
        include("reaches another concurrency domain, so every count inside it has to be atomic")
    }

    "and the refusal lands on the value, which is the thing that could have been written differently" in {
      err(importing +
        """struct Node
          |    n: int
          |
          |var slots: [2]&Node = [Node(0); 2]
          |var ch = channel(slots[..])
          |
          |print(ch.send(Node(1)))
          |""".stripMargin) should include("Hold it as a '&sync Node'")
    }

    "`try_send` is held to the same rule, since it puts a value in by the same door" in {
      err(importing +
        """struct Node
          |    n: int
          |
          |var slots: [2]&Node = [Node(0); 2]
          |var ch = channel(slots[..])
          |
          |print(ch.try_send(Node(1)))
          |""".stripMargin) should include("has to be atomic")
    }
  }

  /** The channel itself crosses, which is what the raw-pointer ring is for: a struct holding a `[]T`
    * may not reach another domain at all — a view owns its elements through a count that is not
    * atomic — so a channel that kept the slice would have been refused at the `spawn` that shares
    * it, one line before the first `send`.
    */
  "the channel itself crosses, which a slice-holding one could not have" in {
    run(importing +
      """struct Feed
        |    ch: Channel[int]
        |
        |feeder(f: *Feed) -> unit
        |    for i in 1..5
        |        f.ch.send(i)
        |
        |    f.ch.close()
        |
        |var slots: [2]int = [0; 2]
        |var f = Feed(channel(slots[..]))
        |var running = spawn(&feeder, &f) match
        |    Some(t) -> t
        |    None -> panic("the thread did not start")
        |
        |var total = 0
        |
        |loop
        |    f.ch.receive() match
        |        Some(v) -> total += v
        |        None -> break
        |
        |print(running.join(), total)
        |""".stripMargin) shouldBe "true 15\n"
  }
}
