package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** `sysl.seq` exercised through the **standard-module artifact**, which is the road a user's build
 * takes and the one the module's own `@test` files cannot reach.
 *
 * `StdSelfTests` compiles `library/` from source into the program (`Stdlib.Choice.FromSource`), so
 * every assertion in `library/sysl/seq/tests.sysl` runs against a library that never crossed the
 * artifact boundary. `RunSupport.run` takes `Stdlib.Choice.Default()` — the prebuilt `std.syslib` —
 * so these do.
 *
 * **The distinction is not theoretical.** Card `0229` records a miscompile that appears only on the
 * artifact road, in a capturing closure returning an aggregate — and `sysl.seq` is built entirely
 * out of capturing closures, with `map` free to return whatever the caller's closure returns. The
 * two aggregate cases below were run by hand against that card's shape before they were written
 * down here; they are correct, and this is what keeps them so.
 */
class SeqArtifactTests extends AnyFreeSpec with RunSupport {

  private def seq(body: String): String =
    run(s"""import sysl.seq.Sequence
           |
           |$body
           |""".stripMargin)

  "the surface answers correctly through the artifact" - {

    "map, filter and fold" in {
      seq(
        """val xs = [1, 2, 3, 4, 5]
          |
          |print(xs[..].map(n -> n * 2)[4])
          |print(xs[..].filter(n -> n % 2 == 1).len)
          |print(xs[..].fold(0, (a, n) -> a + n))""".stripMargin,
      ) shouldBe "10\n3\n15\n"
    }

    "the searching half" in {
      seq(
        """val xs = [3, 0, 7]
          |
          |print(xs[..].any(n -> n == 0), xs[..].all(n -> n > 0))
          |print(xs[..].count_where(n -> n > 1))
          |print(xs[..].find(n -> n > 5).unwrap())
          |print(xs[..].position(n -> n > 5).unwrap())""".stripMargin,
      ) shouldBe "true false\n2\n7\n2\n"
    }

    "the stages chain" in {
      seq(
        """val xs = [1, 2, 3, 4, 5, 6]
          |val out = xs[..].filter(n -> n % 2 == 0).map(n -> n * n)
          |
          |print(out.len, out[0], out[2])""".stripMargin,
      ) shouldBe "3 4 36\n"
    }

    /** The creator, which is the one thing here with no receiver — so it arrives through the
      * artifact as a plain generic function rather than through a table, and is worth asking
      * separately for that reason. What it produces goes straight back into the trait's members,
      * which is the whole reason it lives in this module.
      */
    "generate, and what it makes is an ordinary sequence" in {
      seq(
        """import sysl.seq.generate
          |
          |val squares = generate(5, i -> int(i * i))
          |
          |print(squares.len, squares[0], squares[4])
          |print(generate(3, i -> s"row ${i}")[2])
          |print(generate(0, i -> i + 1).len)
          |print(squares.fold(0, (a, n) -> a + n))""".stripMargin,
      ) shouldBe "5 0 16\nrow 2\n0\n30\n"
    }
  }

  /** The shape card `0229` is about, asked of this module: a closure that **captures** and returns
    * something wider than a scalar, called through the artifact. Each is run twice, because the
    * failure that card describes is non-deterministic across runs of one binary — a single green run
    * would not distinguish a correct answer from a lucky one.
    */
  "a capturing closure returning an aggregate is not miscompiled here" - {

    "returning a string" in {
      val src =
        """val tag = "<"
          |val xs = [1, 2, 3]
          |val out = xs[..].map(n -> tag + str(n) + ">")
          |
          |print(out[0], out[1], out[2])""".stripMargin

      seq(src) shouldBe "<1> <2> <3>\n"
      seq(src) shouldBe "<1> <2> <3>\n"
    }

    "returning a struct" in {
      val src =
        """struct Off
          |    mins: int
          |    flag: bool
          |
          |val base = 0 - 300
          |val xs = [1, 2, 3]
          |val out = xs[..].map(n -> Off(base + n, true))
          |
          |print(out[0].mins, out[1].mins, out[2].mins)""".stripMargin

      seq(src) shouldBe "-299 -298 -297\n"
      seq(src) shouldBe "-299 -298 -297\n"
    }

    "returning a slice, which is what flat_map's closure hands back" in {
      // `pair` is declared **before** any statement, so it is a module-level function rather than a
      // nested one — the entry file is a body, and a body's nested function is not something a
      // closure may reach. The capture under test is `bump`, which the closure holds and `pair`
      // never sees.
      val src =
        """import sysl.buf.{Buf, buf}
          |
          |pair(n: int) -> []int
          |    var b: Buf[int] = buf()
          |
          |    b.push(n)
          |    b.push(n * 10)
          |
          |    b.view()
          |
          |val bump = 1
          |val xs = [1, 2]
          |val out = xs[..].flat_map(n -> pair(n + bump))
          |
          |print(out.len, out[0], out[1], out[3])""".stripMargin

      seq(src) shouldBe "4 2 20 30\n"
      seq(src) shouldBe "4 2 20 30\n"
    }
  }
}
