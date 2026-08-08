package sh.sysl

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** What a program built for a bare board actually **calls**, which is a different question from what
 * it is allowed to write.
 *
 * `capabilities.md` is about the second: what the type system admits in a module without `alloc`.
 * This is about the first, and only a link finds it — a call to a function nothing defines is a
 * perfectly good module, assembles into a perfectly good object, and fails at the one step no test
 * tier below `QemuRunTests` performs. Which is how the finding below went unnoticed: every
 * freestanding target has been in the registry for months and nothing had ever linked a program for
 * one.
 */
class NoAllocEmissionTests extends AnyFreeSpec with Matchers {

  private val uart =
    """struct Uart
      |    data: volatile u8
      |
      |val UART: usize = 0x10000000
      |val regs: *Uart = ptr_cast(UART)
      |
      |putc(c: u8)
      |    regs.data = c
      |""".stripMargin

  private val bare = Target.riscv32Freestanding

  /** The functions a module **calls**, not the ones it declares. The distinction is the whole test:
   * an unreferenced `declare` costs a link nothing, and `-O2` deletes an internal function nobody
   * reached — so a module can name `free` and link perfectly well on a board that has none.
   */
  private def callsOf(src: String): Set[String] = {
    val ir = Compiler.compile(List(Source("p.sysl", src)), bare)
      .getOrElse(fail(s"did not compile for ${bare.name}"))

    List("@free", "@malloc", "@realloc")
      .filter(f => ir.linesIterator.exists(l => l.contains(f) && l.contains(" call ")))
      .toSet
  }

  "a program for a bare board" - {

    // The no-alloc subset's simplest half, and it is clean: a fixed array and an index into it,
    // constant or computed, reach no allocator at all.
    "reaches no allocator for a fixed array and an index into it" in {
      callsOf(s"$uart\nvar a = [u8('a'), u8('b')]\nputc(a[0])\n") shouldBe empty
      callsOf(s"$uart\nvar a = [u8('a'), u8('b')]\nvar i: usize = 1\nputc(a[i])\n") shouldBe empty
    }

    // A slice is a shared view, so releasing one goes through ARC, and ARC's release path frees. On
    // a target whose capability set includes `alloc` -- which is every target's default, since
    // `Target` carries no capabilities and they come from the package config -- that is correct, and
    // it is stated here so that the claim below is a contrast rather than an isolated complaint.
    "calls free for a slice, which is right where the program may allocate" in {
      callsOf(s"$uart\nvar a = [u8('a'), u8('b')]\nval s = a[..]\nputc(u8(s.len))\n") should contain("@free")
    }

    /* An `@no_alloc` module emits the same call, and that is the defect.
     *
     * `capabilities.md` says three things that together make this wrong rather than merely
     * surprising: slices `[]T` are in the **no-alloc subset** (§ *What `alloc` gates, precisely*);
     * holding, passing and releasing a slice needs no allocator because **"the free path goes
     * through the object's own hook"**; and an `@no_alloc` module is *"allocator-free, enforced"*
     * and *"portable across every target"*.
     *
     * What is emitted instead is `@arc.unshare` calling `@free` by name, so the headline promise
     * fails at the link for any no-alloc program that touches a slice — which is most of them, a
     * slice being the subset's main data structure.
     *
     * **The fix is the hook the chapter already names, and it is a design decision rather than a
     * repair**: a no-alloc module may legitimately hold a heap-backed slice created elsewhere, so
     * the free path cannot simply be deleted — it has to become indirect. That is why this is
     * reported rather than fixed in the branch that found it.
     */
    "and an @no_alloc module reaches no allocator, a slice being in its subset" ignore {
      callsOf(s"@no_alloc\n$uart\nvar a = [u8('a'), u8('b')]\nval s = a[..]\nputc(u8(s.len))\n") shouldBe empty

      callsOf(s"@no_alloc\n$uart\npick(xs: []const u8, i: usize) -> u8\n    xs[i]\n" +
        "var a = [u8('a'), u8('b')]\nputc(pick(a[..], 1))\n") shouldBe empty
    }
  }
}
