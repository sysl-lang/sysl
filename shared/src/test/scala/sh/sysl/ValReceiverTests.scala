package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** A `*self` receiver is an `&` the caller did not write, and it is asked the same question.
 *
 * **This suite exists because it was not.** Assignment and an explicit `&` both refused a `val`, and
 * a method taking `*self` — which is the address of the receiver, handed over to be written through
 * — did not, so `val` said read-only and meant nothing:
 *
 * {{{
 * val c = Counter(0)
 * c.n = 5      // refused
 * poke(&c)     // refused
 * c.bump()     // accepted, and the write landed
 * }}}
 *
 * At module scope that was **unsound rather than merely loose**. A module-level `val` is emitted as
 * an LLVM `constant`, so the call handed a mutating method the address of read-only storage and the
 * store was emitted into it — undefined behaviour, which the optimizer duly acted on by folding the
 * reads to the initial value and losing the write. Nothing in the source was unsafe.
 *
 * What is asserted here is both halves: the refusal, and the three things that must keep working —
 * a `var` receiver, a read-only method on a `val`, and mutable content reached *through* a `&` held
 * by one, which is the shape that makes `val` useful rather than merely safe.
 */
class ValReceiverTests extends AnyFreeSpec with CodegenSupport with RunSupport {

  private val counter =
    """struct Counter
      |    n: int
      |
      |    bump(*self) = self.n += 1
      |
      |    read(self) -> int = self.n
      |end Counter
      |
      |""".stripMargin

  "a val receiver" - {

    "is refused where the method writes through it" in {
      val said = err(counter + "val c: Counter = Counter(0)\nc.bump()")

      said should include("'bump' takes '*self'")
      said should include("a 'val' is written once")
    }

    // The message names the member, because the write is not at the call: the caller wrote a call
    // and the assignment is a line inside somebody else's body.
    "and says which member wanted to write" in {
      err(counter + "val c: Counter = Counter(0)\nc.bump()") should include("'bump'")
    }

    // **And it names the fix, with the binding in it.** One `val` bound to a mutable object
    // produces one of these per mutating call — five, for a five-line program that builds a table —
    // so every one of them has to be enough on its own to find the single word that is wrong.
    "and names the binding to change, which is the whole fix" in {
      val said = err(counter + "val c: Counter = Counter(0)\nc.bump()")

      said should include("write 'var c'")
    }

    "including a local one, which is where it was found" in {
      val src = counter + "main()\n    val c = Counter(0)\n\n    c.bump()"

      err(src) should include("a 'val' is written once")
    }

    // The unsound case. A module-level `val` is `constant` in the emitted module, so this call used
    // to aim a store into read-only storage.
    "and a module-level one, whose storage really is read-only" in {
      err(counter + "val shared: Counter = Counter(0)\nshared.bump()") should
        include("a 'val' is written once")
    }
  }

  "what must keep working" - {

    "a var receiver, which is what the refusal asks for instead" in {
      run(counter + "main()\n    var c = Counter(0)\n\n    c.bump()\n    c.bump()\n\n    print(c.n)") shouldBe "2\n"
    }

    // Only the mode that writes is refused. A method that merely reads its receiver takes `self`,
    // and a `val` is exactly what it should be callable on.
    "a read-only method on a val" in {
      run(counter + "main()\n    val c = Counter(7)\n\n    print(c.read())") shouldBe "7\n"
    }

    // **The shape that keeps `val` worth having**, and the answer to wanting Scala's reading of the
    // word: a fixed name over mutable content is a `val` holding a `&`. The binding cannot be
    // reaimed; what it points at is ordinary writable storage.
    "and mutable content reached through a reference the val holds" in {
      val src =
        """import sysl.buf.{Buf, buf}
          |
          |struct Holder
          |    xs: &Buf[int]
          |
          |    push(*self, v: int) = self.xs.push(v)
          |end Holder
          |
          |main()
          |    val h = Holder(buf())
          |
          |    h.xs.push(1)
          |    h.xs.push(2)
          |
          |    print(h.xs.len())
          |""".stripMargin

      run(src) shouldBe "2\n"
    }
  }
}
