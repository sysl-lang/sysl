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

  /** A `string` owns its bytes, so nothing that answers one is carrying a view of the caller's
   * frame — and a call that answers one must not inherit its arguments' views.
   *
   * It did, because a `string` is a `Type.View` in the layout, and the consequence was a refusal on
   * the most ordinary line there is: a temporary array handed to something taking a `[]const u8`
   * and answering a `string`. `hex_string(sha3_256(msg))` is exactly that, so both hashing packages
   * met it. What made it read as arbitrary is that the **enclosing** function's result decided it —
   * the identical call compiled when the caller answered a `usize` or a `bool`.
   */
  "a string owns its bytes, so answering one carries no view out" - {
    val fixture =
      """filled() -> [4]u8
        |    var out: [4]u8 = [0; 4]
        |    for i in 0..<out.len do out[i] = u8(i)
        |    out
        |
        |shown(xs: []const u8) -> string = if xs.len == 4 then "yes" else "no"
        |counted(xs: []const u8) -> usize = xs.len
        |""".stripMargin

    "a temporary array reaches it from a function answering a string" in {
      run(fixture + "c() -> string = shown(filled())\nprint(c())") shouldBe "yes\n"
    }

    "and from a block body, which is the same call written the other way" in {
      run(fixture + "c() -> string\n    shown(filled())\nprint(c())") shouldBe "yes\n"
    }

    // The two that always compiled, kept as the other side of the claim: what changed is the string
    // case, and a suite that only pinned the string case could not say the rest was untouched.
    "as it always did from one answering something that is not a view at all" in {
      run(fixture + "c() -> usize = counted(filled())\nprint(c())") shouldBe "4\n"
    }

    // The real escape is unaffected: a slice of a local array handed *out* is still promoted, and a
    // slice of an array the frame does not own is still refused. Both are elsewhere in this file and
    // in `EscapeErrorTests`; what is pinned here is that a string in the result type does not turn
    // an ordinary call into one.
    "while a slice answered directly still leaves as a view" in {
      promotions(fixture + "c() -> []const u8\n    var b: [4]u8 = [1; 4]\n    b[..]\nprint(c().len)")
        .mkString should include("promoted to the heap")
    }
  }
}
