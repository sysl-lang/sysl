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
        "cannot print a Q value — write an 'impl sysl.Display for Q' to say how it renders")
    }

    "says the same about an enum" in {
      err("""enum Colour
            |    Red
            |print(Red)""".stripMargin) should include("write an 'impl sysl.Display for Colour'")
    }

    "reports 'str' in the words 'str' was asked in" in {
      err("""struct Q
            |    a: int
            |print(str(Q(1)))""".stripMargin) should include(
        "cannot make a string of a Q value — write an 'impl sysl.Display for Q'")
    }

    // A pointer is deliberately not `Display` (`14 §5`): an address renders differently on every
    // run. There is no `impl` to suggest either, since a composed type cannot carry one yet.
    "offers no impl for a type that could not carry one" in {
      err("var n = 1\nprint(&n)") should include("cannot print a *int value — it does not implement 'sysl.Display'")
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
      err("f[T](x: T) = print(x)\nf(1)") should include(s"'print' needs 'T: ${lib("Display")}'")
    }

    "is told the same about 'str'" in {
      err("f[T](x: T) -> string = str(x)\nprint(f(1))") should include(s"'str' needs 'T: ${lib("Display")}'")
    }

    // No call site at all, which is what tells a definition-time check apart from a template.
    "is told so even where nothing instantiates it" in {
      err("f[T](x: T) = print(x)") should include(s"'print' needs 'T: ${lib("Display")}'")
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
            |show(Q(1))""".stripMargin) should include("requires its type parameter 'T' to implement 'sysl.Display'")
    }
  }

  "an impl" - {
    // An integer's `Display` is the library's blanket block over `Integer`, so a second one for
    // `int` is refused — but by **coherence** rather than by the compiler-provides-it guard it used
    // to hit. The rule that catches it is the deeper one and always was: the trait is the library's
    // and so is `int`, so a block written anywhere else has no home.
    "may not compete with the library's own rendering" in {
      err("""impl Display for int
            |    display(self, out: *Writer, fmt: FormatSpec) = display_str("no", out, fmt)
            |print(1)""".stripMargin) should include("so this one has no home")
    }

    // And a program's own type is not a way in either: the family is the compiler's answer, so
    // there is nothing an `impl` of `Integer` could be supplying.
    "may not join the family the blanket is written over" in {
      err("""struct P
            |    n: int
            |impl Integer for P
            |print(1)""".stripMargin) should include("names a family of types the compiler settles")
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
            |print(P(1))""".stripMargin) should include("method 'int.display' takes 2 arguments")
    }
  }

  "a writer" - {
    /* The bytes are borrowed for the call, which is what lets a renderer pass a slice of its own
     * stack buffer — and that is only sound because an implementation that keeps them is refused
     * here rather than trusted not to. */
    "may not keep the bytes it is written" in {
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
        "'Bad.write' keeps the bytes it is written, but a 'sysl.Writer' borrows them for the call")
    }

    "may keep what it copies out of them" in {
      ir("""struct Ok
            |    n: usize
            |impl Fallible for Ok
            |
            |impl Writer for Ok
            |    write(*self, bytes: []const u8)
            |        self.n += bytes.len
            |var o: Ok
            |var w: *Writer = &o
            |display_int(1, w, FormatSpec(0, -1, false))""".stripMargin) should include("@main")
    }

    "is refused as a counted object where a raw one is asked for" in {
      err("""struct S
            |    n: usize
            |impl Fallible for S
            |
            |impl Writer for S
            |    write(*self, bytes: []const u8)
            |        self.n += bytes.len
            |var w: &Writer = S(0usize)
            |display_int(1, w, FormatSpec(0, -1, false))""".stripMargin) should include(
        s"'out' of '${Modules.show(Library.key("display_int"))}' is *sysl.Writer, " +
          "but &sysl.Writer was given")
    }

    /* A member whose signature does not match the trait's is reported at the `impl` and never
     * registered. Errors are collected rather than thrown, so the erasure that follows still runs
     * and finds no function for the slot — and reading it straight out of the table turned a
     * diagnostic the compiler already had into a stack trace with no diagnostic at all. */
    "and a member at the wrong signature is reported rather than crashing the table builder" in {
      val e = err("""struct Counter
                    |    n: usize
                    |impl Fallible for Counter
                    |
                    |impl Writer for Counter
                    |    write(*self, bytes: []u8)
                    |        self.n += bytes.len
                    |var c: Counter
                    |var w: *Writer = &c
                    |display_int(1, w, FormatSpec(0, -1, false))""".stripMargin)

      // The trait is named as the library's, since that is where it is declared — spelled from the
      // key rather than written out, so a diagnostic that stopped qualifying it fails here.
      val writer = Modules.show(Library.key("Writer"))

      e should include(s"parameter 'bytes' of method 'write' is []byte, but trait '$writer' declares []const byte")
      e should include(s"has no 'write' that '$writer' can point a slot at")
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
