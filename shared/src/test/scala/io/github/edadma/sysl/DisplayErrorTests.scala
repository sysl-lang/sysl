package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** What `Display` and `Writer` refuse, and what they say about it (`14 §2`, `§6`).
 *
 * The point of routing rendering through a trait is that "this cannot be printed" stops being a
 * special case and becomes a missing implementation or a missing bound, like any other. So each
 * test here checks that the diagnostic names the thing to *write* — an `impl`, a bound — rather
 * than merely reporting that the compiler declined.
 */
class DisplayErrorTests extends AnyFreeSpec with CodegenSupport {

  "a value with no rendering" - {
    "names the impl that would give a struct one" in {
      err("""struct Q
            |    a: int
            |print(Q(1))""".stripMargin) should include(
        "cannot print a Q value — write an 'impl Display for Q' to say how it renders")
    }

    "says the same about an enum" in {
      err("""enum Colour
            |    Red
            |print(Red)""".stripMargin) should include("write an 'impl Display for Colour'")
    }

    "reports 'str' in the words 'str' was asked in" in {
      err("""struct Q
            |    a: int
            |print(str(Q(1)))""".stripMargin) should include(
        "cannot make a string of a Q value — write an 'impl Display for Q'")
    }

    // A pointer is deliberately not `Display` (`14 §5`): an address renders differently on every
    // run. There is no `impl` to suggest either, since a composed type cannot carry one yet.
    "offers no impl for a type that could not carry one" in {
      err("var n = 1\nprint(&n)") should include("cannot print a *int value — it does not implement 'Display'")
    }

    "refuses a reference rather than reaching through it" in {
      err("""struct P
            |    x: int
            |var p: &P = P(1)
            |print(p)""".stripMargin) should include("cannot print a &P value")
    }

    "refuses an array, which has no one text of its own" in {
      err("var a = [1, 2]\nprint(a)") should include("cannot print a [2]int value")
    }

    "refuses a trait object, which has forgotten what it renders as" in {
      err("""trait Shape
            |    area(self) -> int
            |struct R
            |    w: int
            |impl Shape for R
            |    area(self) -> int = self.w
            |var r = R(1)
            |var s: *Shape = &r
            |print(s)""".stripMargin) should include("cannot print a *Shape value")
    }
  }

  "a type parameter" - {
    // The whole payoff of `14 §4`: the complaint lands on the definition, naming the bound that
    // would license it, whether or not anything ever instantiates the function.
    "is told which bound would let it be printed" in {
      err("f[T](x: T) = print(x)\nf(1)") should include("'print' needs 'T: Display'")
    }

    "is told the same about 'str'" in {
      err("f[T](x: T) -> string = str(x)\nprint(f(1))") should include("'str' needs 'T: Display'")
    }

    // No call site at all, which is what tells a definition-time check apart from a template.
    "is told so even where nothing instantiates it" in {
      err("f[T](x: T) = print(x)") should include("'print' needs 'T: Display'")
    }

    "is accepted once the bound is written" in {
      ir("f[T: Display](x: T) = print(x)\nf(1)") should include("@main")
    }

    "may not pass an unbounded parameter to one that renders" in {
      err("""show[T: Display](x: T) = print(x)
            |pass[T](x: T) = show(x)
            |pass(1)""".stripMargin) should include("'T' is not bounded by it")
    }

    "may not instantiate a rendering generic with a type that has no impl" in {
      err("""struct Q
            |    a: int
            |show[T: Display](x: T) = print(x)
            |show(Q(1))""".stripMargin) should include("requires its type parameter 'T' to implement 'Display'")
    }
  }

  "an impl" - {
    // The scalars' memberships are the compiler's (`14 §5`), so there is no second answer to be
    // had — the same guard that refuses `impl Add for int`.
    "may not compete with a built-in's own rendering" in {
      err("""impl Display for int
            |    display(self, out: *Writer, fmt: FormatSpec) = display_str("no", out, fmt)
            |print(1)""".stripMargin) should include("'int' already implements 'Display'")
    }

    "must match the trait's signature" in {
      err("""struct P
            |    n: int
            |impl Display for P
            |    display(self, out: *Writer) = ()
            |print(P(1))""".stripMargin) should include("display")
    }

    "is called with the arity the trait declares" in {
      err("""struct P
            |    n: int
            |impl Display for P
            |    display(self, out: *Writer, fmt: FormatSpec) = self.n.display(out)
            |print(P(1))""".stripMargin) should include("method 'Display.display' takes 2 arguments")
    }
  }

  "a writer" - {
    /* The bytes are borrowed for the call, which is what lets a renderer pass a slice of its own
     * stack buffer — and that is only sound because an implementation that keeps them is refused
     * here rather than trusted not to. */
    "may not keep the bytes it is written" in {
      err("""struct Bad
            |    held: []u8
            |impl Writer for Bad
            |    write(*self, bytes: []const u8)
            |        self.held = bytes
            |    failed(*self) -> bool = false
            |var b: Bad
            |var w: *Writer = &b
            |display_int(1, w, FormatSpec(0, -1, false))""".stripMargin) should include(
        "'Bad.write' keeps the bytes it is written, but a 'Writer' borrows them for the call")
    }

    "may keep what it copies out of them" in {
      ir("""struct Ok
            |    n: usize
            |impl Writer for Ok
            |    write(*self, bytes: []const u8)
            |        self.n += bytes.len
            |    failed(*self) -> bool = false
            |var o: Ok
            |var w: *Writer = &o
            |display_int(1, w, FormatSpec(0, -1, false))""".stripMargin) should include("@main")
    }

    "is refused as a counted object where a raw one is asked for" in {
      err("""struct S
            |    n: usize
            |impl Writer for S
            |    write(*self, bytes: []const u8)
            |        self.n += bytes.len
            |    failed(*self) -> bool = false
            |var w: &Writer = S(0usize)
            |display_int(1, w, FormatSpec(0, -1, false))""".stripMargin) should include(
        "'out' of 'display_int' is *Writer, but &Writer was given")
    }
  }

  "a format specifier" - {
    // A built-in keeps the strict conversion check, so `%s` on a number stays the mistake it was
    // rather than quietly becoming a rendering that drops the width.
    "still refuses the wrong conversion on a built-in" in {
      err("""var n = 1
            |print(f"${n}%s")""".stripMargin) should include("format '%s' expects a string")
    }

    "refuses a numeric conversion on a type that renders itself" in {
      err("""struct P
            |    n: int
            |impl Display for P
            |    display(self, out: *Writer, fmt: FormatSpec) = self.n.display(out, fmt)
            |print(f"${P(1)}%d")""".stripMargin) should include("format '%d' expects an integer")
    }

    "refuses '%s' on a type with no rendering at all" in {
      err("""struct Q
            |    a: int
            |print(f"${Q(1)}%s")""".stripMargin) should include("format '%s' expects a string")
    }
  }
}
