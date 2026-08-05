package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Tier-1 lowering of rendering (`14 §6`): the two sinks, and which path a value takes to text.
 *
 * What is worth pinning here rather than leaving to the run suite is the part a working program
 * cannot tell you about — that a scalar still renders with no sink at all, that the sink a `print`
 * reaches is the library's own value rather than anything the compiler built, and that a program
 * which never renders a user type carries neither writer.
 */
class DisplayCodegenTests extends AnyFreeSpec with CodegenSupport {

  /** The table standing for the library's `impl Writer for Stdout`, named the way every erasure
   * names one. Read off `Library` rather than spelled, since a key is what an emitted name is.
   */
  private val outTable = s"vt.${Library.key("Writer")}.${Library.key("Stdout")}"

  private val point =
    """struct Point
      |    x: int
      |    y: int
      |impl Display for Point
      |    display(self, out: *Writer, fmt: FormatSpec)
      |        self.x.display(out, fmt)
      |""".stripMargin

  "the scalar path is untouched" - {
    // `14 §8 b`: the two renderings are identical, so the one that does not build a sink is the one
    // to emit — and a program that only prints numbers should not carry a method table at all.
    "a printed scalar calls its renderer directly" in {
      val out = irMain("print(5)")

      out should include regex raw"""call void @${keyRe("printi")}\(i64 %t\d+\)"""
      out should not include "insertvalue { ptr, ptr }"
    }

    "a program that prints only scalars carries no writer" in {
      val out = ir("print(5, \"a\", true)")

      out should not include s"@$outTable"
      out should not include s"@${Library.key("stdout")}("
      out should not include "@sysl.w.buf.write"
    }

    "str of a scalar still renders without a buffer" in {
      val out = irMain("print(str(5))")

      out should include("@sysl.str.int")
      out should not include "@sysl.w.buf.finish"
    }
  }

  "the sink a print reaches" - {
    // Nothing is built here any more. The sink is a value the library hands out, so what `print`
    // emits is a call — and the two words it comes back as are assembled inside that function,
    // out of an ordinary `impl Writer` for a struct with no fields.
    "is a call to the library's own, not two words the compiler laid down" in {
      val out = irMain(point + "print(Point(1, 2))")

      out should include regex raw"""call \{ ptr, ptr \} @${keyRe("stdout")}\(\)"""
      out should not include "@sysl.vt.out"
    }

    "hands the value, the sink, and the specifier to the type's own display" in {
      // The specifier's emitted type name **is** its key, as every emitted name is, so it is read
      // off the seam rather than spelled here — and goes on saying the same thing after a move.
      val spec = "%struct\\." + Library.key("FormatSpec").replace("$", "\\$")

      irMain(point + "print(Point(1, 2))") should include regex
        s"""call void @Point\\.display\\(%struct\\.Point %t\\d+, \\{ ptr, ptr \\} %t\\d+, $spec %t\\d+\\)"""
    }

    // The `write` a print ends in is a library function like any other, so what the program carries
    // is a table pointing at it rather than a body the compiler wrote. That the body reaches
    // `putbytes` is `print.sysl`'s business now, and the run suite's.
    "through a table pointing at the library's own writer" in {
      ir(point + "print(Point(1, 2))") should include(
        s"@$outTable = private constant [2 x ptr] " +
          s"[ptr @${Library.key("Stdout")}.failed, ptr @${Library.key("Stdout")}.write]")
    }
  }

  "the sink a str builds" - {
    // A stack slot, zeroed on arrival rather than once: an alloca is hoisted to the entry block, so
    // a render inside a loop meets the same slot every time round and has to start it empty.
    "is a stack slot the render zeroes each time" in {
      val out = irMain(point + "print(str(Point(1, 2)))")

      out should include("alloca { ptr, i64, i64 }")
      out should include regex """store \{ ptr, i64, i64 \} zeroinitializer, ptr %t\d+"""
    }

    "turns what landed there into a string the statement owns" in {
      val out = irMain(point + "print(str(Point(1, 2)))")

      out should include regex """call \{ ptr, ptr, i64 \} @sysl\.w\.buf\.finish\(ptr %t\d+\)"""
      out should include("call void @arc.release_maybe")
    }
  }

  "the writers' tables" - {
    // The buffer's is laid out by hand, so its order is a contract the compiler keeps, and the order
    // is the one a trait *offers* rather than the one `Writer` declares: `Writer: Fallible` puts the
    // required trait's members first, so slot 0 is `failed` and slot 1 is `write`. That is what a
    // call through the object indexes by, and getting it backwards would call the wrong function
    // with the right arguments — which is why `SpecialForms.checkWriterShape` asks as well.
    //
    // The sink's is now built by the ordinary erasure machinery from the library's `impl`, and it
    // comes out in the same order — which is the point of checking both here rather than one: the
    // hand-written table and the derived one have to agree, and nothing else compares them.
    "hold failed first and write second, the order the trait offers them" in {
      val out = ir(point + "print(Point(1, 2))\nprint(str(Point(3, 4)))")

      out should include(s"@$outTable = private constant [2 x ptr] " +
        s"[ptr @${Library.key("Stdout")}.failed, ptr @${Library.key("Stdout")}.write]")
      out should include("@sysl.vt.buf = private constant [2 x ptr] " +
        "[ptr @sysl.w.buf.failed, ptr @sysl.w.buf.write]")
    }

    "are emitted once however many values render" in {
      val out = ir(point + "print(Point(1, 2))\nprint(Point(3, 4))\nprint(Point(5, 6))")

      out.linesIterator.count(_.startsWith(s"@$outTable =")) shouldBe 1
    }

    "a program that only prints a user type carries no buffer" in {
      val out = ir(point + "print(Point(1, 2))")

      out should include(s"@$outTable")
      out should not include "@sysl.vt.buf"
    }
  }

  "the renderer a built-in's Display reaches" - {

    // The one thing an IR test has to say about this family, and nothing was saying it: the symbol
    // an integer's `display` lowers to is one the *compiler* spells rather than one resolved from
    // source, so it has to carry the library's key like every other emitted name. That is the
    // `putbytes`-in-emitted-IR defect class — a link failure rather than a diagnostic, and one no
    // behavioural test can see, since the program runs identically either way until the linker
    // refuses it.
    //
    // The blanket makes this sharper than it was. Its members are emitted **per receiver**, so the
    // name is built rather than written down anywhere, and a bare `bound.Integer.display.int` would
    // be a symbol a program could collide with by declaring a trait of that name.
    "is emitted under the library's key, wherever the family lives" in {
      val out = ir(point + "print(Point(1, 2))")

      out should include(s"call void @bound.${Library.key("Integer")}.display.int(")
      out should not include "call void @bound.Integer.display.int("
    }

    // The width is part of the name, which is what makes one blanket serve an open family: each
    // instantiation is its own function, sized for the type it was made at.
    "and each width instantiates a renderer of its own" in {
      val out = ir(
        """struct B
          |    a: u8
          |    b: u128
          |impl Display for B
          |    display(self, out: *Writer, fmt: FormatSpec)
          |        self.a.display(out, fmt)
          |        self.b.display(out, fmt)
          |print(B(1u8, 2u128))
          |""".stripMargin)

      // `u8` mangles as `byte`, which is the name it has everywhere else an emitted symbol carries
      // a type — so the instantiation is named by the mangling and not by the spelling written.
      out should include(s"define void @bound.${Library.key("Integer")}.display.byte(")
      out should include(s"define void @bound.${Library.key("Integer")}.display.u128(")
    }

    "and a closed family still reaches the renderer its own impl names" in {
      // `bool` has a written `impl` rather than a share of the blanket, so it forwards to the
      // library function by name and the symbol is the one that function was declared under.
      val out = ir(
        """struct B
          |    v: bool
          |impl Display for B
          |    display(self, out: *Writer, fmt: FormatSpec)
          |        self.v.display(out, fmt)
          |print(B(true))
          |""".stripMargin)

      out should include(s"call void @${Library.key("display_bool")}(")
    }
  }

  "a writer a program wrote" - {
    // An ordinary erasure, so `write`'s `*self` lands in the slot with no adapter between — the
    // data word of a raw object already *is* the receiver it declared.
    "puts its own implementation straight into the table" in {
      ir("""struct C
            |    n: usize
            |impl Fallible for C
            |
            |impl Writer for C
            |    write(*self, bytes: []const u8)
            |        self.n += bytes.len
            |var c: C
            |var w: *Writer = &c
            |display_int(1, w, FormatSpec(0, -1, false))""".stripMargin) should include(
        // The table's symbol carries the trait's **key**, as every emitted name does, so it is read
        // off the seam rather than spelled — and goes on saying the same thing after a move.
        // The required trait's slot comes first, and `C.failed` is the **default** `Fallible`
        // supplied — an implementation that writes nothing still fills the slot, which is what makes
        // the block optional rather than merely short.
        s"@vt.${Library.key("Writer")}.C = private constant [2 x ptr] [ptr @C.failed, ptr @C.write]")
    }
  }
}
