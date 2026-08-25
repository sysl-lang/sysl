package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** What `05-escape-analysis.md` claims, run rather than read.
 *
 * The chapter is pinned about as thoroughly as `16` — `EscapeErrorTests` and `EscapePromotionTests`
 * cover the five escape routes, the call-crossing summaries, recursion, erasure, the `Reader`
 * non-exception, the `&Struct` owner walk and the `--explain-escapes` report. All of it held.
 *
 * What the sweep found is an **enumeration short by two**, with the implementation on the right
 * side of it: `§ What escapes` names a returned "struct, enum, or `Option`" as the carriers a view
 * can ride out in, and the language has two more — a tuple (`reference/types.md § Tuples`) and a
 * slot of a multi-result list (`reference/expressions.md § Closures`). Both are caught; only the
 * prose was behind. The rest of this file is the claims no suite happened to reach, all of which
 * held.
 */
class EscapeClaimTests extends AnyFreeSpec with RunSupport with CodegenSupport {

  /** The promotion notes, which are what `--explain-escapes` prints. */
  private def promotions(src: String): List[String] =
    Compiler.compiled(List(Source("t.sysl", src))) match
      case Right(built) => built.notes
      case Left(err)    => fail(s"did not compile:\n$err")

  "a view rides out in whatever carries it, not only in the three the chapter listed" - {

    "a tuple is a carrier, and the array it views is promoted" in {
      val src =
        """leak() -> ([]u8, int)
          |    var buf: [8]u8
          |    buf[3] = 42u8
          |    (buf[0..<4], 1)
          |var t = leak()
          |print(t.0[3], t.1)
          |""".stripMargin

      promotions(src) should have length 1
      promotions(src).head should include("'buf' is promoted to the heap")
      run(src) shouldBe "42 1\n"
    }

    "and so is one slot of a multi-result list, which is not a tuple at all" in {
      val src =
        """leak() -> []u8, int
          |    var buf: [8]u8
          |    buf[3] = 42u8
          |    return buf[0..<4], 1
          |var v, n = leak()
          |print(v[3], n)
          |""".stripMargin

      promotions(src) should have length 1
      promotions(src).head should include("'buf' is promoted to the heap")
      run(src) shouldBe "42 1\n"
    }
  }

  """an escaping closure over a local array's slice needs nothing added on either side — the
    |composition '§ Deferred' claims, which neither suite had run""".stripMargin in {
    val src =
      """make() -> &Fn() -> u8
        |    var buf: [8]u8
        |    buf[3] = 42u8
        |    var view = buf[0..<4]
        |    () -> view[3]
        |var f = make()
        |print(f())
        |""".stripMargin

    promotions(src) should have length 1
    run(src) shouldBe "42\n"
  }

  "taking a '*T' to a local does NOT promote it, because promotion follows slices" in {
    // A raw pointer carries no length and no owner, so there is nothing for promotion to serve —
    // and a `*T` into a local may dangle exactly as in C, which is the opt-out the mode is for.
    val src =
      """grab() -> u8
        |    var buf: [8]u8
        |    var p = &buf[0]
        |    *p = 7u8
        |    buf[0]
        |print(grab())
        |""".stripMargin

    promotions(src) shouldBe empty
    run(src) shouldBe "7\n"
  }

  "a promoted array keeps its type, and only its storage moves" in {
    // Its length is still a compile-time constant and it is still a value that copies on assignment,
    // which is what makes promotion invisible to everything but the allocator.
    run(
      """promoted() -> []u8
        |    var buf: [8]u8
        |    buf[0] = 1u8
        |    var copy = buf
        |    copy[0] = 2u8
        |    print(buf.len, buf[0], copy[0])
        |    buf[0..<4]
        |var out = promoted()
        |print(out.len, out[0])
        |""".stripMargin) shouldBe "8 1 2\n4 1\n"
  }
}
