package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** A struct with **no fields** — one value carrying no data, written `struct Name` closed by
 * `end Name`.
 *
 * Everything the compiler does with such a type it could already do: construction, methods, `impl`
 * blocks, being taken by reference, standing behind a trait object. The only thing missing was a way
 * to *say* one, because a struct with an empty body and a struct whose body the author forgot to
 * indent look identical, and the second is the far more likely of the two. So emptiness is written
 * rather than inferred: the `end` marker, optional everywhere else, is what a fieldless struct is
 * declared with, and a lone `struct Name` is still the mistake it always was.
 *
 * What wants one is a **sink**: a value standing for a destination fixed at compile time — the
 * console, a UART — which has nothing to keep and so has no field to keep it in. A sink that is a
 * value rather than a global is what lets a writer be passed to a function, held in a struct, and
 * chosen by a caller, and none of that is reachable while the type cannot be named.
 */
class FieldlessStructTests extends AnyFreeSpec with RunSupport with CodegenSupport {

  /** A writer standing for standard output, and a `show` that renders through it — the sink the
   * language change is for, written entirely in sysl.
   */
  private val stdout =
    """struct Stdout
      |end Stdout
      |
      |impl Fallible for Stdout
      |
      |impl Writer for Stdout
      |    write(*self, bytes: []const u8) = putbytes(bytes)
      |end Stdout
      |
      |show[T: Display](x: T)
      |    var out = Stdout()
      |
      |    x.display(&out, FormatSpec(0, -1, false))
      |    printc('\n')
      |end show
      |""".stripMargin

  /** A type rendering itself, and a `report` that writes it into whatever sink it is handed — the
   * shape a program takes when the destination is a parameter rather than a fixed global.
   */
  private val point =
    """struct Point
      |    x: int
      |    y: int
      |end Point
      |
      |impl Display for Point
      |    display(self, out: *Writer, fmt: FormatSpec)
      |        display_str("pt(", out, fmt)
      |        display_int(long(self.x), out, fmt)
      |        display_str(")", out, fmt)
      |end Point
      |
      |report(where: *Writer, p: Point)
      |    p.display(where, FormatSpec(0, -1, false))
      |    where.write("\n".bytes)
      |end report
      |""".stripMargin

  "the declaration" - {

    "a struct with no fields is written closed by its 'end'" in {
      run("""struct Marker
            |end Marker
            |
            |var m = Marker()
            |
            |print("made one")
            |""".stripMargin) shouldBe "made one\n"
    }

    "it takes methods like any other struct" in {
      run("""struct Marker
            |    tag(self) -> int = 5
            |end Marker
            |
            |print(Marker().tag())
            |""".stripMargin) shouldBe "5\n"
    }

    "a lone 'struct Name' is still the misindented body it usually is" in {
      val e = err("struct Empty\n\nprint(1)\n")

      e should include("declares no fields")
      e should include("closed by 'end Empty'")
      e should include("opaque struct Empty")
    }

    "and the marker's name is checked, as it is everywhere else" in {
      err("struct Empty\nend Other\n\nprint(1)\n") should include("'end Other' does not match 'Empty'")
    }

    // `opaque struct Name` with no body at all is C's incomplete type and stays that (`15 §9`);
    // writing the marker as well says the same thing in the spelling every other block uses.
    "an opaque struct may be closed the same way, and is still opaque" in {
      val e = errOf("sq/lib.sysl" -> "module sq\n\nopaque struct Session\nend Session\n",
        "main.sysl" -> "f(p: *u8) -> int = 1\nvar s: *sq.Session = null\nprint(f(s))\n")

      e should include("*byte")
      e should include("*sq.Session")
    }
  }

  /** A type with no fields has no bytes, which is the answer that keeps it free to embed: a sink
   * held in a struct beside the things that do carry data costs that struct nothing.
   *
   * The consequence, stated because it is real and not because it is wanted: two such values have
   * nothing to separate their storage, so their addresses may be equal. That is what C gives for the
   * same declaration, and it is meaningless in the only way it can be observed — an empty value has
   * no state for an address to reach.
   */
  "the layout" - {

    "no fields is no bytes" in {
      run("""struct Marker
            |end Marker
            |
            |print(sizeof(Marker))
            |print(alignof(Marker))
            |""".stripMargin) shouldBe "0\n1\n"
    }

    "so embedding one costs the struct holding it nothing" in {
      run("""struct Marker
            |end Marker
            |
            |struct Counted
            |    mark: Marker
            |    n: int
            |end Counted
            |
            |print(sizeof(Counted) == sizeof(int))
            |print(Counted(Marker(), 9).n)
            |""".stripMargin) shouldBe "true\n9\n"
    }

    "and two of them have nothing to tell their storage apart" in {
      run("""struct Marker
            |end Marker
            |
            |var a = Marker()
            |var b = Marker()
            |
            |print(&a == &b)
            |""".stripMargin) shouldBe "true\n"
    }

    /* `[4]unit` is refused because `unit` is dropped, so an array of it would have no elements at
     * all. A fieldless struct is an ordinary type whose stride happens to be zero, so the array is
     * a real array — its length is its own, and the bounds check is still the whole of what an
     * index does.
     */
    "an array of them is a real array whose elements share their storage" in {
      run("""struct Marker
            |    tag(self) -> int = 5
            |end Marker
            |
            |var xs: [4]Marker = [Marker(); 4]
            |
            |print(xs.len)
            |print(xs[2].tag())
            |print(&xs[0] == &xs[3])
            |""".stripMargin) shouldBe "4\n5\ntrue\n"
    }

    "and the bounds check is what an index of one still gets" in {
      exits("""struct Marker
              |end Marker
              |
              |var xs: [4]Marker = [Marker(); 4]
              |var i = 9
              |
              |var m = xs[i]
              |
              |print("reached")
              |""".stripMargin)
    }

    "one behind the allocator is still a reference, since the header is real" in {
      run("""struct Marker
            |    tag(self) -> int = 5
            |end Marker
            |
            |var r: &Marker = Marker()
            |
            |print(r.tag())
            |""".stripMargin) shouldBe "5\n"
    }

    "and it passes through a generic that carries no data at all" in {
      run("""struct Marker
            |    tag(self) -> int = 5
            |end Marker
            |
            |id[T](x: T) -> T = x
            |
            |print(id(Marker()).tag())
            |""".stripMargin) shouldBe "5\n"
    }
  }

  /** The use it was added for. A writer standing for standard output keeps nothing — the destination
   * is fixed and `putbytes` reaches it — so until a struct could have no fields, the one thing every
   * `Display` implementation writes into was the one thing that could not be written in sysl.
   */
  "a sink is what has no fields" - {

    "it renders a scalar exactly as the compiler's own 'print' does" in {
      run(stdout + "show(42)\nprint(42)\n") shouldBe "42\n42\n"
    }

    "and a type of the program's own, through the same trait" in {
      run(stdout +
        """struct Point
          |    x: int
          |    y: int
          |end Point
          |
          |impl Display for Point
          |    display(self, out: *Writer, fmt: FormatSpec)
          |        display_str("pt(", out, fmt)
          |        display_int(long(self.x), out, fmt)
          |        display_str(", ", out, fmt)
          |        display_int(long(self.y), out, fmt)
          |        display_str(")", out, fmt)
          |end Point
          |
          |show(Point(3, 4))
          |print(Point(3, 4))
          |""".stripMargin) shouldBe "pt(3, 4)\npt(3, 4)\n"
    }

    /* The property an embedded target needs, and the reason a *value* rather than a global is worth
     * a language change: the whole path — the sink, the trait object it is reached through, the
     * rendering that walks it — stays inside a module that has given the allocator up. `putbytes`
     * stands in for the UART write; what is being checked is everything around it.
     */
    "and the whole path holds inside a module that has given up the allocator" in {
      run("@no_alloc\n\n" + stdout + "show(42)\nshow(\"uart\")\n") shouldBe "42\nuart\n"
    }

    /* Without this the test above proves nothing: a `no alloc` clause that was not being enforced
     * would let it pass for the wrong reason. This is the same module with one allocating line.
     */
    "which means something only because that clause is enforced" in {
      err("@no_alloc\n\n" + stdout + "var boxed: &int = 1\n\nshow(*boxed)\n") should
        include("declared '@no_alloc'")
    }
  }

  /** The library's own sink, which is the one above written once in `print.sysl` — `Stdout`, its
   * `impl Writer`, and the `stdout()` that hands one out.
   *
   * This is what the language change was for. The destination `print` writes into used to be a node
   * the compiler built and a method table it laid out by hand, so it was reachable from exactly one
   * place and nothing could be said about it in sysl. It is now a type a program may name, a value
   * it may hold, and an argument it may pass — and swapping it for a serial port is writing another
   * `impl Writer`, not editing the compiler.
   */
  "the library declares that sink once, and a program may use it" - {

    "the sink 'print' uses is one a program can ask for by name" in {
      run(point + "report(stdout(), Point(1, 2))\n") shouldBe "pt(1)\n"
    }

    "and the type behind it is one a program can build for itself" in {
      run(point + "var own = Stdout()\n\nreport(&own, Point(3, 4))\n") shouldBe "pt(3)\n"
    }

    "the two are the same destination, in the order the writes happened" in {
      run(point + "var own = Stdout()\n\nreport(stdout(), Point(1, 2))\nreport(&own, Point(3, 4))\n") shouldBe
        "pt(1)\npt(3)\n"
    }

    // What `print` of a user type now emits: a call for the sink rather than two words the compiler
    // laid down, and no hand-written table anywhere in the program.
    "and 'print' reaches it as an ordinary call" in {
      val out = ir(point + "print(Point(1, 2))\n")

      out should include regex raw"""call \{ ptr, ptr \} @${keyRe("stdout")}\(\)"""
      out should not include "@sysl.vt.out"
      out should not include "@sysl.w.out.write"
    }
  }
}
