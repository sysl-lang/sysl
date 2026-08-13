package sh.sysl

import io.github.edadma.cross_platform.*

/** A scalar **narrower than a register** crossing to C and back, run rather than read
 * (`CAbi.extension`).
 *
 * `AbiAgainstClangTests` is what pins the rule: it asks clang what the attribute should be for every
 * width on every target and requires sysl to say the same. This is the other half — a program that
 * really calls a C function really compiled beside it, so that the agreement is about *behaviour*
 * and not only about two `declare` lines matching.
 *
 * **The reproduction below is the one that was reported, and it is worth keeping in its reported
 * shape.** What made it visible is that clang, promised a widened argument, compares the whole
 * register rather than masking it first — so the failure needs a callee that *uses* the value in a
 * way the promise lets it optimize. A version that assigns the parameter to an `int` first passes
 * either way, which is exactly the sort of near-miss that would have made this look fixed.
 */
class CNarrowScalarTests extends LibraryCliSupport {

  private def guard(): Unit = assume(Toolchain.clangAvailable, "clang not available")

  /** A project whose module carries the C it calls, and a `main.sysl` that prints the result. */
  private def project(sysl: String, c: String): String = {
    val root = createTempDirectory("sysl-narrow-")

    createDirectories(s"$root/p")
    writeFile(s"$root/main.sysl", "print(p.check())\n")
    writeFile(s"$root/p/p.sysl", s"module p\n\n$sysl")
    writeFile(s"$root/p/probe.c", c)
    root
  }

  private def ranProject(sysl: String, c: String): String = {
    guard()
    ran(Config(command = "run", file = project(sysl, c)))
  }

  "a u8 handed to C arrives as the number that was written" - {

    /** The reported case: a `u8` from a six-arm `match`, in a function that also takes a slice it
     * never reads, reaching a C function that tests it against zero. Every one of those was needed
     * to see it, and none of them is what is wrong — the argument was simply handed over without the
     * convention's `zeroext`, so what the callee compared was the register rather than the byte.
     *
     * `999` is the C saying it saw something other than zero, which is what this printed before the
     * attribute was emitted.
     */
    "even from a match whose arms partly coincide with the discriminants" in {
      ranProject(
        """extern "probe_a" probe_a(d: *u8, lk: int, ln: i64, lp: *u8, ll: usize, req: u8, out: *i64) -> int
          |
          |enum Six
          |    A
          |    B
          |    C
          |    D
          |    E
          |    F
          |
          |call(lb: []const u8, t: Six) -> int
          |    var v: i64 = 0
          |    val req: u8 = t match
          |        A -> 0
          |        B -> 1
          |        C -> 2
          |        D -> 3
          |        E -> 4
          |        F -> 0
          |
          |    probe_a(null, 0, 0, null, 0, req, &v)
          |end call
          |
          |check() -> int = call([], Six.A)
          |""".stripMargin,
        """#include <stdint.h>
          |#include <stddef.h>
          |
          |int probe_a(unsigned char *d, int lk, int64_t ln, const uint8_t *lp, size_t ll,
          |            uint8_t req, int64_t *out) {
          |    *out = 1;
          |    return req == 0 ? 0 : 999;
          |}
          |""".stripMargin) shouldBe "0\n"
    }

    /** The same question asked of a value whose top bits are dirty **by construction** rather than by
     * whatever was left in the register: a `u8` narrowed from a `u32` that has something above the
     * low byte. Nothing about the caller is unusual here, which is the point — the widening is owed
     * on every argument of this width and not only on one that came out of a `match`.
     */
    "and from a value narrowed out of a wider one" in {
      ranProject(
        """extern "probe_low" probe_low(v: u8) -> int
          |
          |check() -> int
          |    val wide: u32 = 0xABCD0007
          |    probe_low(u8(wide))
          |end check
          |""".stripMargin,
        """#include <stdint.h>
          |
          |int probe_low(uint8_t v) { return v == 7 ? 0 : 999; }
          |""".stripMargin) shouldBe "0\n"
    }
  }

  /** The obligation seen from the other side. Widening a **result** is the callee's, so a sysl
   * function C calls owes it — and `@export` and `&f` are the two ways C gets to call one.
   *
   * A `bool` is the case that matters most in practice, because it is what a callback answers:
   * `i1` is one bit in a register whose other thirty-one are undefined, and a C caller reading it as
   * `_Bool` was promised they are zero.
   */
  "a narrow result C reads back was widened by the sysl that returned it" - {

    "an exported function returning a bool" in {
      ranProject(
        """@export("probe_yes")
          |yes() -> bool = true
          |
          |extern "probe_ask" ask() -> int
          |
          |check() -> int = ask()
          |""".stripMargin,
        """#include <stdbool.h>
          |
          |bool probe_yes(void);
          |
          |int probe_ask(void) { return probe_yes() ? 0 : 999; }
          |""".stripMargin) shouldBe "0\n"
    }

    "an exported function returning a u8" in {
      ranProject(
        """@export("probe_byte")
          |byte() -> u8 = 7
          |
          |extern "probe_ask" ask() -> int
          |
          |check() -> int = ask()
          |""".stripMargin,
        """#include <stdint.h>
          |
          |uint8_t probe_byte(void);
          |
          |int probe_ask(void) { return probe_byte() == 7 ? 0 : 999; }
          |""".stripMargin) shouldBe "0\n"
    }

    /** A callback, which is the shape a C library actually reaches back through: the address of a
     * sysl function handed over as a `*extern`, and C calling it. Nothing marks that function as
     * exported, so this is the case a rule written in terms of `@export` alone would have missed.
     */
    "a sysl function whose address C was handed" in {
      ranProject(
        """extern "probe_call" probe_call(f: *extern(u8) -> bool) -> int
          |
          |odd(v: u8) -> bool = v % 2 == 1
          |
          |check() -> int = probe_call(&odd)
          |""".stripMargin,
        """#include <stdint.h>
          |#include <stdbool.h>
          |
          |int probe_call(bool (*f)(uint8_t)) { return f(7) && !f(8) ? 0 : 999; }
          |""".stripMargin) shouldBe "0\n"
    }
  }
}
