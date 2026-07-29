package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Handing a walk over the tail to another function, and duplicating one
 * (`12-functions-and-closures.md` §9, *Handing a walk on*).
 *
 * This is C's `vprintf` shape: the function that received the tail does not read it itself but
 * passes it to somebody who does. The parameter type is **`*va_list`** and the call writes `&ap`,
 * because a parameter is a by-value binding (`12 §2`) and a copy of a walk advances nothing the
 * caller can see — which is the one thing the form exists to do.
 *
 * The tests that carry the weight are the ones that *run*. A lent walk advancing the lender's own
 * list is not something the IR can be read for, and it is the whole claim.
 */
class VariadicForwardTests extends AnyFreeSpec with CodegenSupport with RunSupport {

  /** Sums `n` `int`s out of a walk somebody else started — the `vprintf` half of the shape, and a
   * function with no tail of its own.
   */
  private val tailSum =
    """tail_sum(n: int, ap: *va_list) -> int
      |    var total = 0
      |    for i in 0..<n
      |        var v: int = va_arg(ap)
      |        total += v
      |    total
      |end tail_sum""".stripMargin

  "lending a walk" - {
    "reaches the whole tail from the function that received it" in {
      val src =
        s"""$tailSum
           |sum(n: int, ...) -> int
           |    var ap: va_list
           |    va_start(ap)
           |    var t = tail_sum(n, &ap)
           |    va_end(ap)
           |    t
           |end sum
           |print(sum(3, 10, 20, 30))""".stripMargin

      run(src) shouldBe "60\n"
    }

    // The load-bearing claim of the whole feature, and the one a by-value parameter would break:
    // the callee walks the *lender's* list, so what it consumed is gone when the lender reads on.
    // A `va_list` parameter passing a copy would print 79 here — both reading the first argument.
    "advances the lender's own list, which is why it is lent by address" in {
      val src =
        """take(ap: *va_list) -> int
          |    var v: int = va_arg(ap)
          |    v
          |end take
          |lend(n: int, ...) -> int
          |    var ap: va_list
          |    va_start(ap)
          |    var a = take(&ap)
          |    var b: int = va_arg(ap)
          |    va_end(ap)
          |    a * 100 + b
          |end lend
          |print(lend(2, 7, 9))""".stripMargin

      run(src) shouldBe "709\n"
    }

    // A function holding a lent walk is in the same position its lender was, so it can lend it on
    // without copying: the type it holds is already the address.
    "may be handed on again, unchanged" in {
      val src =
        s"""$tailSum
           |middle(n: int, ap: *va_list) -> int = tail_sum(n, ap)
           |sum(n: int, ...) -> int
           |    var ap: va_list
           |    va_start(ap)
           |    var t = middle(n, &ap)
           |    va_end(ap)
           |    t
           |end sum
           |print(sum(4, 1, 2, 3, 4))""".stripMargin

      run(src) shouldBe "10\n"
    }

    // `va_start` needs a tail of this function's own; `va_arg` needs only a walk, and that is what
    // lets a borrower exist at all.
    "a borrower reads a tail without having one" in {
      val src =
        s"""$tailSum
           |sum(n: int, ...) -> int
           |    var ap: va_list
           |    va_start(ap)
           |    var t = tail_sum(n, &ap)
           |    va_end(ap)
           |    t
           |end sum
           |print(sum(2, 21, 21))""".stripMargin
      val out = ir(src)

      out should include("define i32 @tail_sum(i32 %n.param, ptr %ap.param)")
      out should not include "define i32 @tail_sum(i32 %n.param, ptr %ap.param, ..."
      run(src) shouldBe "42\n"
    }

    // A `*va_list` is an ordinary raw pointer, so nothing about it has to be a place: a field read
    // says the same thing a parameter does, and a struct can carry the walk around.
    "a walk may be carried in a struct" in {
      val src =
        """struct Cursor
          |    ap: *va_list
          |
          |    take(self) -> int
          |        var v: int = va_arg(self.ap)
          |        v
          |    end take
          |end Cursor
          |sum(n: int, ...) -> int
          |    var ap: va_list
          |    va_start(ap)
          |    var c = Cursor(&ap)
          |    var t = 0
          |    for i in 0..<n
          |        t += c.take()
          |    va_end(ap)
          |    t
          |end sum
          |print(sum(3, 1, 2, 3))""".stripMargin

      run(src) shouldBe "6\n"
    }

    // The promotions are the *caller's* (`12 §1`), and lending the walk moves nothing about them —
    // so the mistake a body makes reading the tail too narrow is the same mistake and the same
    // message wherever the walk came from.
    "reading one too narrow is diagnosed through a lent walk too" in {
      err("narrow(ap: *va_list) -> int\n    var v: u8 = va_arg(ap)\n    int(v)\nend narrow\nprint(1)") should
        include("promoted to at least 32 bits")
    }

    // Every width the tail can carry survives the handover, because the callee's `va_arg` is the
    // same instruction on the same list — nothing about it knows it was lent.
    "each type still comes back at the width it went in at" in {
      val src =
        """show(n: int, ap: *va_list)
          |    var i: int  = va_arg(ap)
          |    var d: real = va_arg(ap)
          |    var c: char = va_arg(ap)
          |    var l: long = va_arg(ap)
          |    print(i, d, c, l)
          |end show
          |go(n: int, ...)
          |    var ap: va_list
          |    va_start(ap)
          |    show(n, &ap)
          |    va_end(ap)
          |end go
          |go(4, 42, 2.5, 'A', 9000000000i64)""".stripMargin

      run(src) shouldBe "42 2.5 A 9000000000\n"
    }

    "a borrower may recurse down the tail" in {
      val src =
        """drain(n: int, ap: *va_list) -> int
          |    if n <= 0 then
          |        0
          |    else
          |        var v: int = va_arg(ap)
          |        v + drain(n - 1, ap)
          |end drain
          |sum(n: int, ...) -> int
          |    var ap: va_list
          |    va_start(ap)
          |    var t = drain(n, &ap)
          |    va_end(ap)
          |    t
          |end sum
          |print(sum(5, 1, 2, 3, 4, 5))""".stripMargin

      run(src) shouldBe "15\n"
    }
  }

  "duplicating a walk" - {
    "starts the copy where the original has reached" in {
      val src =
        """both(n: int, ...) -> int
          |    var ap: va_list
          |    va_start(ap)
          |    var first: int = va_arg(ap)
          |    var bp: va_list
          |    va_copy(bp, ap)
          |    var a: int = va_arg(ap)
          |    var b: int = va_arg(bp)
          |    va_end(bp)
          |    va_end(ap)
          |    first * 10000 + a * 100 + b
          |end both
          |print(both(3, 1, 2, 3))""".stripMargin

      // The copy is taken after the `1`, so both walks hand back the `2`.
      run(src) shouldBe "10202\n"
    }

    // What `va_copy` is *for*: the callee advanced the list it was handed, so a body that means to
    // go on reading its own hands over a copy instead.
    "is what lets a tail be read twice over" in {
      val src =
        s"""$tailSum
           |twice(n: int, ...) -> int
           |    var ap: va_list
           |    va_start(ap)
           |    var bp: va_list
           |    va_copy(bp, ap)
           |    var a = tail_sum(n, &ap)
           |    var b = tail_sum(n, &bp)
           |    va_end(bp)
           |    va_end(ap)
           |    a + b
           |end twice
           |print(twice(3, 1, 2, 3))""".stripMargin

      run(src) shouldBe "12\n"
    }

    "the two walks are independent afterwards" in {
      val src =
        """apart(n: int, ...) -> int
          |    var ap: va_list
          |    va_start(ap)
          |    var bp: va_list
          |    va_copy(bp, ap)
          |    var a1: int = va_arg(ap)
          |    var a2: int = va_arg(ap)
          |    var b1: int = va_arg(bp)
          |    va_end(bp)
          |    va_end(ap)
          |    a1 * 10000 + a2 * 100 + b1
          |end apart
          |print(apart(3, 1, 2, 3))""".stripMargin

      // The original reached the third argument; the copy is still on the first.
      run(src) shouldBe "10201\n"
    }

    // A borrower is holding an address, not a list, so the copy it makes is of the lender's walk —
    // which is exactly what a nested formatter needs.
    "a borrower may copy the walk it was lent" in {
      val src =
        """peek(ap: *va_list) -> int
          |    var save: va_list
          |    va_copy(save, ap)
          |    var v: int = va_arg(save)
          |    va_end(save)
          |    v
          |end peek
          |look(n: int, ...) -> int
          |    var ap: va_list
          |    va_start(ap)
          |    var seen = peek(&ap)
          |    var first: int = va_arg(ap)
          |    va_end(ap)
          |    seen * 100 + first
          |end look
          |print(look(2, 7, 9))""".stripMargin

      // `peek` looked at the first argument through a copy, so the lender's own walk is untouched.
      run(src) shouldBe "707\n"
    }

    "the intrinsic is declared where it is used and nowhere else" in {
      val copies =
        """f(n: int, ...) -> int
          |    var ap: va_list
          |    va_start(ap)
          |    var bp: va_list
          |    va_copy(bp, ap)
          |    var v: int = va_arg(bp)
          |    va_end(bp)
          |    va_end(ap)
          |    v
          |end f
          |print(f(1, 5))""".stripMargin

      ir(copies) should include("declare void @llvm.va_copy.p0(ptr, ptr)")
      ir(copies) should include regex "call void @llvm\\.va_copy\\.p0\\(ptr %bp\\.addr, ptr %ap\\.addr\\)"

      ir("f(n: int, ...)\n    var ap: va_list\n    va_start(ap)\n    va_end(ap)\nf(1)") should
        not include "llvm.va_copy"
    }
  }

  "what the forms refuse" - {
    "va_copy takes two lists" in {
      val one =
        """f(n: int, ...)
          |    var ap: va_list
          |    va_start(ap)
          |    va_copy(ap)
          |    va_end(ap)
          |end f
          |f(1, 2)""".stripMargin

      err(one) should include("'va_copy' takes two arguments")
    }

    "each of them has to be one" in {
      val src =
        """f(n: int, ...)
          |    var ap: va_list
          |    va_start(ap)
          |    var x = 1
          |    va_copy(x, ap)
          |    va_end(ap)
          |end f
          |f(1, 2)""".stripMargin

      err(src) should include("'va_copy' needs a va_list, not int")
    }

    "a borrower still has no tail of its own to start" in {
      err("f(ap: *va_list)\n    va_start(ap)\nvar p: *va_list = null\nf(p)") should
        include("'va_start' is only allowed in a function declared with '...'")
    }

    // The destination is written through, so it has to be somewhere — the same demand `va_start`
    // makes, and for the same reason.
    "a copy needs somewhere to go" in {
      val src =
        """f(n: int, ...)
          |    var ap: va_list
          |    va_start(ap)
          |    va_copy(1, ap)
          |    va_end(ap)
          |end f
          |f(1, 2)""".stripMargin

      err(src) should include("'va_copy'")
    }
  }
}
