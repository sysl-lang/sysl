package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Tier-2 runtime behaviour of `Display` and its `Writer` sink (`14 §2`, `§6`).
 *
 * The claim under test is that rendering is now **one** mechanism: a scalar, a struct, an enum, and
 * a bounded type parameter all reach text the same way, and the difference between `print` and
 * `str` is only which sink the text lands in. So most cases here drive the same value through both,
 * and expect the two to agree to the byte.
 */
class DisplayRunTests extends AnyFreeSpec with RunSupport {

  /** `Display`, `Writer` and the renderers are the standard module's, since `print` desugars onto
   * them. The one sink the library supplies is `sysl.buf`'s, being ordinary sysl over the growable
   * buffer, so the programs that gather into one ask for it.
   */
  private val importing = "import sysl.buf.*\n\n"

  override protected def run(src: String): String = super.run(importing + src)

  /** A pair whose rendering is unmistakable — punctuation the two fields could not have produced
   * on their own, so a call that reached the wrong renderer shows up as missing text rather than
   * as a plausible wrong number.
   */
  private val point =
    """struct Point
      |    x: int
      |    y: int
      |impl Display for Point
      |    display(self, out: *Writer, fmt: FormatSpec)
      |        display_str("(", out, fmt)
      |        self.x.display(out, fmt)
      |        display_str(", ", out, fmt)
      |        self.y.display(out, fmt)
      |        display_str(")", out, fmt)
      |""".stripMargin

  "a type that renders itself" - {
    "prints through the impl its type carries" in {
      run(point + "print(Point(3, 4))") shouldBe "(3, 4)\n"
    }

    // The same text through the other sink. If the two ever disagreed, one of them would be doing
    // its own rendering rather than reaching the one `display` the type declares.
    "makes the same string as it prints" in {
      run(point +
        """var p = Point(3, 4)
          |print(p)
          |print(str(p))""".stripMargin) shouldBe "(3, 4)\n(3, 4)\n"
    }

    "produces a real string, which joins and slices like any other" in {
      run(point +
        """var s = str(Point(1, 2)) + "!"
          |print(s.len, s[0..<3])""".stripMargin) shouldBe "7 (1,\n"
    }

    "is spliced into an interpolation" in {
      run(point + """print(s"a ${Point(1, 2)} b")""") shouldBe "a (1, 2) b\n"
    }

    "renders an enum" in {
      run("""enum Colour
            |    Red
            |    Green
            |impl Display for Colour
            |    display(self, out: *Writer, fmt: FormatSpec)
            |        var name = self match
            |            Red -> "red"
            |            Green -> "green"
            |        display_str(name, out, fmt)
            |print(Green, str(Red))""".stripMargin) shouldBe "green red\n"
    }

    "renders a type whose fields render themselves" in {
      run("""struct Leaf
            |    n: int
            |struct Node
            |    a: Leaf
            |    b: Leaf
            |impl Display for Leaf
            |    display(self, out: *Writer, fmt: FormatSpec) = self.n.display(out, fmt)
            |impl Display for Node
            |    display(self, out: *Writer, fmt: FormatSpec)
            |        self.a.display(out, fmt)
            |        display_char('/', out, fmt)
            |        self.b.display(out, fmt)
            |print(Node(Leaf(1), Leaf(2)))""".stripMargin) shouldBe "1/2\n"
    }

    // A render inside a render: the inner one has a buffer of its own, so the two cannot be sharing
    // the stack slot the sink lives in.
    "renders through a string it built on the way" in {
      run("""struct Leaf
            |    n: int
            |struct Node
            |    a: Leaf
            |impl Display for Leaf
            |    display(self, out: *Writer, fmt: FormatSpec) = self.n.display(out, fmt)
            |impl Display for Node
            |    display(self, out: *Writer, fmt: FormatSpec)
            |        display_str("<" + str(self.a) + ">", out, fmt)
            |print(str(Node(Leaf(5))))""".stripMargin) shouldBe "<5>\n"
    }

    "writes nothing at all when its impl writes nothing" in {
      run("""struct E
            |    n: int
            |impl Display for E
            |    display(self, out: *Writer, fmt: FormatSpec)
            |        if self.n > 0 then display_str("x", out, fmt)
            |var s = str(E(0))
            |print(s.len, "[" + s + "]")""".stripMargin) shouldBe "0 []\n"
    }

    // Far past the buffer's starting capacity, so growth has run several times and every byte has
    // been carried across at least one reallocation.
    "renders text far larger than the buffer starts at" in {
      run("""struct Rep
            |    n: int
            |impl Display for Rep
            |    display(self, out: *Writer, fmt: FormatSpec)
            |        var i = 0
            |        while i < 1000
            |            display_str("ab", out, fmt)
            |            i++
            |var s = str(Rep(0))
            |print(s.len, s[0..<4], s[1996..<2000])""".stripMargin) shouldBe "2000 abab abab\n"
    }
  }

  "the built-ins render through the same trait" - {
    // Every scalar `14 §5` calls `Display`, reached as a method rather than through `print` — which
    // is the call a struct's own `display` makes about its fields.
    "every scalar type has a display of its own" in {
      run("""struct All
            |    n: int
            |impl Display for All
            |    display(self, out: *Writer, fmt: FormatSpec)
            |        (1).display(out, fmt)
            |        display_char(' ', out, fmt)
            |        (2u8).display(out, fmt)
            |        display_char(' ', out, fmt)
            |        (2.5).display(out, fmt)
            |        display_char(' ', out, fmt)
            |        true.display(out, fmt)
            |        display_char(' ', out, fmt)
            |        'é'.display(out, fmt)
            |        display_char(' ', out, fmt)
            |        "s".display(out, fmt)
            |print(All(0))""".stripMargin) shouldBe "1 2 2.5 true é s\n"
    }

    "a scalar prints the same whether it goes direct or through the sink" in {
      run("""struct W
            |    n: int
            |impl Display for W
            |    display(self, out: *Writer, fmt: FormatSpec) = self.n.display(out, fmt)
            |print(-2147483648)
            |print(W(-2147483648))""".stripMargin) shouldBe "-2147483648\n-2147483648\n"
    }
  }

  "a writer a program wrote itself" - {
    "receives the bytes of everything rendered into it" in {
      run("""struct Counter
            |    n: usize
            |impl Fallible for Counter
            |
            |impl Writer for Counter
            |    write(*self, bytes: []const u8)
            |        self.n += bytes.len
            |var c: Counter
            |var w: *Writer = &c
            |display_int(12345, w, FormatSpec(0, -1, false))
            |display_str("abc", w, FormatSpec(0, -1, false))
            |print(c.n)""".stripMargin) shouldBe "8\n"
    }

    // Most sinks cannot fail, and `Writer.failed` defaults to saying so — a writer that only
    // counts bytes writes nothing about failure at all.
    "may leave out 'failed', which the trait defaults to false" in {
      run("""struct Counter
            |    n: usize
            |impl Fallible for Counter
            |
            |impl Writer for Counter
            |    write(*self, bytes: []const u8)
            |        self.n += bytes.len
            |var c: Counter
            |var w: *Writer = &c
            |display_str("abc", w, FormatSpec(0, -1, false))
            |print(c.n, w.failed())""".stripMargin) shouldBe "3 false\n"
    }

    // The latch: a write that overruns sets a flag the *required* trait reports, and nothing in
    // between had to return or check anything. A sink that can fail is where the two blocks earn
    // their separation — `Fallible` carries the answer and `Writer` carries the writing.
    "latches a failure the writes themselves do not report" in {
      run("""struct Cap
            |    n: usize
            |    over: bool
            |impl Fallible for Cap
            |    failed(*self) -> bool = self.over
            |
            |impl Writer for Cap
            |    write(*self, bytes: []const u8)
            |        self.n += bytes.len
            |        if self.n > 4usize then self.over = true
            |var c: Cap
            |var w: *Writer = &c
            |display_str("abc", w, FormatSpec(0, -1, false))
            |print(w.failed())
            |display_str("defg", w, FormatSpec(0, -1, false))
            |print(w.failed())""".stripMargin) shouldBe "false\ntrue\n"
    }

    "takes a value's own display, so a program can choose where its text goes" in {
      run(point +
        """struct Counter
          |    n: usize
          |impl Fallible for Counter
          |
          |impl Writer for Counter
          |    write(*self, bytes: []const u8)
          |        self.n += bytes.len
          |var c: Counter
          |var w: *Writer = &c
          |Point(10, 20).display(w, FormatSpec(0, -1, false))
          |print(c.n)""".stripMargin) shouldBe "8\n"
    }
  }

  /** `ByteSink` — the one writer the library does supply.
   *
   * It exists because a specifier describes the field the **whole** value occupies (`14 §2`), so an
   * implementation rendering more than one part has to gather them before it can pad what they came
   * to. Every such implementation was writing the same dozen lines. It is ordinary sysl over
   * `Buf[u8]`, which is why it could not be written until a growable array could.
   */
  "the sink the library supplies" - {
    "keeps what is written into it" in {
      run("""var g = byte_sink()
            |var w: *Writer = &g
            |display_str("abc", w, FormatSpec(0, -1, false))
            |display_int(45, w, FormatSpec(0, -1, false))
            |print(g.text().len, str(g.text()[0]), str(g.text()[4]))""".stripMargin) shouldBe "5 97 53\n"
    }

    "reports no failure, having nothing to fail at" in {
      run("""var g = byte_sink()
            |var w: *Writer = &g
            |display_str("abc", w, FormatSpec(0, -1, false))
            |print(w.failed())""".stripMargin) shouldBe "false\n"
    }

    "starts with nothing and grows to whatever is written" in {
      run("""var g = byte_sink()
            |var w: *Writer = &g
            |print(g.text().len)
            |for i in 0..<200 do display_str("xy", w, FormatSpec(0, -1, false))
            |print(g.text().len, str(g.text()[399]))""".stripMargin) shouldBe "0\n400 121\n"
    }

    // The point of it: the three pieces are rendered plainly and the field applied to the whole,
    // so `%9s` pads once rather than three times.
    "lets an implementation put its whole rendering in one field" in {
      val pair =
        """struct Pair
          |    a: int
          |    b: int
          |impl Display for Pair
          |    display(self, out: *Writer, fmt: FormatSpec)
          |        var g = byte_sink()
          |        var plain = FormatSpec(0, -1, false)
          |        display_int(long(self.a), &g, plain)
          |        display_str(":", &g, plain)
          |        display_int(long(self.b), &g, plain)
          |        display_pad(g.text(), out, fmt)
          |    end display
          |""".stripMargin

      run(pair + """print(f"[${Pair(1, 2)}%9s]")
                   |print(f"[${Pair(1, 2)}%-9s]")
                   |print(f"[${Pair(1, 2)}%2s]")
                   |print(Pair(30, -4))""".stripMargin) shouldBe
        "[      1:2]\n[1:2      ]\n[1:2]\n30:-4\n"
    }

    // A sink written into by two implementations in the same expression keeps them apart, since
    // each is a value of its own with its own buffer.
    "two of them do not run into each other" in {
      run("""var a = byte_sink()
            |var b = byte_sink()
            |display_str("left", &a, FormatSpec(0, -1, false))
            |display_str("right", &b, FormatSpec(0, -1, false))
            |print(a.text().len, b.text().len)""".stripMargin) shouldBe "4 5\n"
    }
  }

  "a format specifier reaches the implementation" - {
    // The parts are handed the neutral specifier rather than the one that arrived: `%-12.3s`
    // describes the field this *value* occupies, so applying it to each piece would pad three times.
    "an f-string hole hands its width, precision, and justification over" in {
      run("""struct W
            |    n: int
            |impl Display for W
            |    display(self, out: *Writer, fmt: FormatSpec)
            |        var plain = FormatSpec(0, -1, false)
            |        fmt.width.display(out, plain)
            |        display_char(' ', out, plain)
            |        fmt.prec.display(out, plain)
            |        display_char(' ', out, plain)
            |        fmt.left.display(out, plain)
            |print(f"${W(0)}%-12.3s")""".stripMargin) shouldBe "12 3 true\n"
    }

    // `print` and `str` write no specifier, so what a `Display` gets is the neutral one — and a
    // width of zero has to be distinguishable from a precision that was not written at all.
    "print and str hand over a neutral specifier" in {
      run("""struct W
            |    n: int
            |impl Display for W
            |    display(self, out: *Writer, fmt: FormatSpec)
            |        fmt.width.display(out, fmt)
            |        display_char(' ', out, fmt)
            |        fmt.prec.display(out, fmt)
            |        display_char(' ', out, fmt)
            |        fmt.left.display(out, fmt)
            |print(W(0))
            |print(str(W(0)))""".stripMargin) shouldBe "0 -1 false\n0 -1 false\n"
    }

    // A wrapper whose whole rendering *is* one field's hands the specifier straight down, and the
    // width arrives where the text does.
    "is honoured by the renderer an implementation forwards it to" in {
      run("""struct P
            |    n: int
            |impl Display for P
            |    display(self, out: *Writer, fmt: FormatSpec) = self.n.display(out, fmt)
            |print(f"[${P(7)}%10s]")""".stripMargin) shouldBe "[         7]\n"
    }

    // An implementation is free to drop it, and then nothing pads: the specifier is something a
    // `Display` is told, not something applied around it.
    "leaves an implementation that ignores it rendering plainly" in {
      run("""struct P
            |    n: int
            |impl Display for P
            |    display(self, out: *Writer, fmt: FormatSpec) = self.n.display(out, FormatSpec(0, -1, false))
            |print(f"[${P(7)}%10s]")""".stripMargin) shouldBe "[7]\n"
    }
  }

  "a bounded type parameter" - {
    // One body, three instantiations: two built-ins that render by the compiler's rule and a user
    // type that renders by its `impl`. If the bound were not doing the work, one of the three
    // would have had to be written differently.
    "renders whatever satisfies 'Display'" in {
      run("""struct P
            |    v: int
            |impl Display for P
            |    display(self, out: *Writer, fmt: FormatSpec) = self.v.display(out, fmt)
            |both[T: Display](a: T, b: T)
            |    print(a)
            |    print(str(b))
            |both(1, 2)
            |both("x", "y")
            |both(P(7), P(8))""".stripMargin) shouldBe "1\n2\nx\ny\n7\n8\n"
    }

    "carries the bound down into another generic it calls" in {
      run("""twice[T: Display](x: T)
            |    print(x)
            |    print(x)
            |once[T: Display](x: T) = twice(x)
            |once(5)""".stripMargin) shouldBe "5\n5\n"
    }
  }

  /** A render owns the buffer it fills and the string it hands back, so the ordinary discipline has
   * to hold: a leak grows without bound and a double free crashes, and a long loop is what makes
   * either show up as something other than a passing test.
   */
  "ownership" - {
    "rendering in a loop neither leaks nor frees twice" in {
      run(point +
        """var i = 0
          |var total: usize = 0
          |while i < 200000
          |    total += str(Point(i, i)).len
          |    i++
          |print(total)""".stripMargin) shouldBe "2977780\n"
    }

    // The rendered value holds a count of its own, so the render has to leave it exactly where it
    // found it: the receiver is borrowed by the call, not consumed by it.
    "a value carrying a string is rendered without disturbing its count" in {
      run("""struct Tag
            |    name: string
            |    n: int
            |impl Display for Tag
            |    display(self, out: *Writer, fmt: FormatSpec)
            |        display_str(self.name, out, fmt)
            |        self.n.display(out, fmt)
            |var i = 0
            |var total: usize = 0
            |while i < 200000
            |    var t = Tag("tag" + "!", i)
            |    total += str(t).len
            |    total += str(t).len
            |    i++
            |print(total)""".stripMargin) shouldBe "3777780\n"
    }

    "printing in a loop reaches the impl every time" in {
      run("""struct T
            |    n: int
            |impl Display for T
            |    display(self, out: *Writer, fmt: FormatSpec) = self.n.display(out, fmt)
            |var i = 0
            |while i < 3
            |    print(T(i))
            |    i++""".stripMargin) shouldBe "0\n1\n2\n"
    }
  }
}
