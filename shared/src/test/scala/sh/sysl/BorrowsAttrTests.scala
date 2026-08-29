package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** `@borrows` — a trait method's promise that it will not keep what it is passed
  * (`reference/traits.md § A method may promise to borrow`).
  *
  * **A call through a trait object is opaque**, so the escape analysis has to assume every argument
  * is kept and a local array passed through one is promoted to the heap. That was right and it was
  * absolute: the one exception was `sysl.Writer`, keyed on the trait's *name*, with the verifying
  * half reaching into `WriterEmitter` for the slot. A second trait wanting the same guarantee had no
  * way to ask, and the exemption could not be lost automatically if `Writer` ever stopped deserving
  * it.
  *
  * Now the trait says so and the compiler holds every implementation to it. `Writer` declares
  * `@borrows(bytes)` and is an ordinary user of the feature, which is the test that it is real.
  */
class BorrowsAttrTests extends AnyFreeSpec with RunSupport with CodegenSupport {

  /** The promotion notes, which are what `--explain-escapes` prints. */
  private def promotions(src: String): List[String] =
    Compiler.compiled(List(Source("t.sysl", src))) match
      case Right(built) => built.notes
      case Left(e)      => fail(s"did not compile:\n$e")

  /** A sink of a program's own: not `Writer`, so nothing about it is known to the compiler. */
  private val sink =
    """trait Sink
      |    @borrows(bytes)
      |    put(*self, bytes: []const u8)
      |
      |struct Counter
      |    n: usize
      |
      |impl Sink for Counter
      |    put(*self, bytes: []const u8)
      |        self.n += bytes.len
      |
      |""".stripMargin

  "a trait of one's own can ask for what Writer had" - {
    // The whole point. Without the promise this array is passed through an opaque call, so a view of
    // it could be kept and it goes on the heap; with it, the frame keeps its storage.
    "a local array passed through the object stays in the frame" in {
      val src = sink +
        """var c: Counter
          |var s: *Sink = &c
          |var buf: [8]u8
          |
          |buf[0] = 7u8
          |s.put(buf[0..<4])
          |print(c.n)
          |""".stripMargin

      promotions(src) shouldBe Nil
      run(src) shouldBe "4\n"
    }

    // And the same program with the promise taken off, which is what says the assertion above is
    // about the annotation rather than about something else in the shape.
    "and is promoted where the trait does not promise" in {
      val src = sink.replace("    @borrows(bytes)\n", "") +
        """var c: Counter
          |var s: *Sink = &c
          |var buf: [8]u8
          |
          |buf[0] = 7u8
          |s.put(buf[0..<4])
          |print(c.n)
          |""".stripMargin

      promotions(src) should have length 1
      promotions(src).head should include("'buf' is promoted to the heap")
    }
  }

  "the promise is checked against every implementation" - {
    "one that keeps what it was lent is refused, by the name the trait gave it" in {
      err(sink +
        """struct Bad
          |    held: []const u8
          |
          |impl Sink for Bad
          |    put(*self, bytes: []const u8)
          |        self.held = bytes
          |
          |var b: Bad
          |var s: *Sink = &b
          |var buf: [8]u8
          |
          |s.put(buf[0..<4])
          |""".stripMargin) should include(
        "'Bad.put' keeps what it is passed as 'bytes', but 'Sink' declares that parameter borrowed")
    }

    // The distinction the check has to draw, and the reason it is a `keeps` question rather than a
    // syntactic one: reading out of a lent view is what every honest implementation does.
    "and one that only reads out of it is not" in {
      ir(sink +
        """var c: Counter
          |var s: *Sink = &c
          |
          |s.put([1u8, 2u8][..])
          |""".stripMargin) should include("@main")
    }
  }

  "sysl.Writer is an ordinary user of it" in {
    // The hardcoded key is gone, so this refusal now comes from the library's own declaration.
    err("""struct Bad
          |    held: []const u8
          |impl Fallible for Bad
          |
          |impl Writer for Bad
          |    write(*self, bytes: []const u8)
          |        self.held = bytes
          |var b: Bad
          |var w: *Writer = &b
          |display_int(1, w, FormatSpec(0, -1, false))""".stripMargin) should include(
      s"'Bad.write' keeps what it is passed as 'bytes', but '${Modules.show(Library.key("Writer"))}' " +
        "declares that parameter borrowed")
  }

  "what it may not be written on" - {
    // A free function's body is right here and the analysis reads the answer out of it, so a written
    // promise would restate what the compiler knows and go stale when the body changed.
    "a free function is refused, and told why rather than told the word is unknown" in {
      err("""@borrows(xs)
            |take(xs: []const u8) -> usize = xs.len
            |print(take([1u8][..]))""".stripMargin) should include(
        "'@borrows' is about a call whose body the compiler cannot see")
    }

    // A misspelling would promise nothing and say nothing, which reads exactly like a rule being
    // enforced while every implementation is free to keep what it likes.
    "a name that is not a parameter is refused by name" in {
      err("""trait Sink
            |    @borrows(byets)
            |    put(*self, bytes: []const u8)
            |""".stripMargin) should include("'@borrows' names 'byets', which is not a parameter")
    }

    "and there is no empty form" in {
      err("""trait Sink
            |    @borrows
            |    put(*self, bytes: []const u8)
            |""".stripMargin) should include("There is no empty form")
    }
  }
}
