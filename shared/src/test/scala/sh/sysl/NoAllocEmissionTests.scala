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
class NoAllocEmissionTests extends AnyFreeSpec with Matchers with QemuSupport {

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

    /* A slice is where this went wrong, and it is worth saying what the wrong answer was.
     *
     * Releasing a slice goes through ARC, and ARC's release path used to name `free` itself — in
     * `@arc.unshare`, which every module that touches a view reaches, because merely extracting a
     * view's owner word brings the runtime in. A view of an array on the *frame* did exactly that,
     * and such a view's owner is null, so the call could never run. It still had to resolve.
     *
     * `capabilities.md` says three things that together made that wrong rather than merely
     * wasteful: slices `[]T` are in the **no-alloc subset** (§ *What `alloc` gates, precisely*);
     * holding, passing and releasing one needs no allocator because **"the free path goes through
     * the object's own hook"**; and an `@no_alloc` module is *"allocator-free, enforced"* and
     * *"portable across every target"*. The free is in the hook now, so all three are true.
     */
    "reaches no allocator for a slice of storage it did not allocate" in {
      callsOf(s"$uart\nvar a = [u8('a'), u8('b')]\nval s = a[..]\nputc(u8(s.len))\n") shouldBe empty
    }

    "and none through a function that takes one" in {
      callsOf(s"$uart\npick(xs: []const u8, i: usize) -> u8\n    xs[i]\n" +
        "var a = [u8('a'), u8('b')]\nputc(pick(a[..], 1))\n") shouldBe empty
    }

    // `@no_alloc` is the same programs with the claim written down. It is stated separately because
    // the promise is the chapter's headline one, and a rule that happens to hold is not the same
    // thing as one the module is held to.
    "an @no_alloc module reaches no allocator, a slice being in its subset" in {
      callsOf(s"@no_alloc\n$uart\nvar a = [u8('a'), u8('b')]\nval s = a[..]\nputc(u8(s.len))\n") shouldBe empty

      callsOf(s"@no_alloc\n$uart\npick(xs: []const u8, i: usize) -> u8\n    xs[i]\n" +
        "var a = [u8('a'), u8('b')]\nputc(pick(a[..], 1))\n") shouldBe empty
    }

    // The contrast, and it is what keeps every case above from being vacuous: a program that really
    // does put something on the heap calls both, and must. Promotion is what puts it there — a view
    // of a local array outliving its frame — so this is the same slice as above, with the one
    // difference that decides it.
    "a program that does allocate calls both, which is what the cases above are the absence of" in {
      val calls = callsOf(
        s"$uart\nkeep() -> []const u8\n    var a = [u8('a'), u8('b')]\n    a[..]\n" +
          "putc(keep()[0])\n")

      calls should contain("@malloc")
      calls should contain("@free")
    }
  }

  /** The link, which is the only step that can fail on a symbol nothing defines — and the step no
   * tier performed for a freestanding target without also linking a support package that defines
   * `free`. That is why this survived months with these targets in the registry.
   *
   * **It is not the pin for that defect, and saying so is the point of this paragraph.** Run against
   * the old emitter this case passes: the owner of a view of an array on the frame is provably null,
   * so the release is unreachable and the optimizer deletes the call before the linker sees it. What
   * failed on a real board was a program it could not do that to. So the cases above — which count
   * calls in the module as emitted — are what catch the defect, and this catches the one thing they
   * cannot: a call that survives to the link, on a board with no allocator to resolve it.
   */
  "and it links against the board's startup alone, with no allocator anywhere" in {
    val (status, notes) = linksWithoutSupport(bare,
      List(Source("p.sysl",
        s"@no_alloc\n$uart\nvar a = [u8('a'), u8('b')]\nval s = a[..]\nputc(u8(s.len))\n")))

    withClue(notes)(status shouldBe 0)
  }
}
