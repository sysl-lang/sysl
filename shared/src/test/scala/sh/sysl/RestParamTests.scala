package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** `xs: ...T` — the parameter that collects a call's trailing arguments into a `[]const T`
 * (`reference/declarations.md § A parameter may collect the rest of the call`).
 *
 * It is the **checked** variadic, beside the C-faithful one: what a `...` tail holds is known only
 * to whatever reads it with `va_arg`, and what a `...T` tail holds is in the signature. The
 * heterogeneous form is the same parameter at `T = &Display`, which is what a checked `print`-alike
 * wants — and it is why this could not have been built earlier: a built-in used to reach `Display`
 * by a compiler rule, and a rule supplies no function for a method table to point at.
 *
 * **The packing is a rewrite of the argument list and nothing else.** `xs: ...int` already carries
 * the type `[]const int`, so the callee is an ordinary function taking a slice and every pass
 * downstream is untouched. The array is the caller's frame, which is what the `@no_alloc` case below
 * is here to pin.
 */
class RestParamTests extends AnyFreeSpec with RunSupport with CodegenSupport {

  private val total =
    """total(xs: ...int) -> int
      |    var s = 0
      |
      |    for x in xs
      |        s += x
      |
      |    s
      |
      |""".stripMargin

  "the trailing arguments are collected" - {

    "into a slice the body walks" in {
      run(total + "print(total(1, 2, 3))") shouldBe "6\n"
    }

    // The empty call is the case the packing has to answer differently and not the case it may
    // refuse: "the rest" with nothing left is the empty slice.
    "and a call with none gets the empty slice" in {
      run(total + "print(total(), total(1))") shouldBe "0 1\n"
    }

    "the parameters in front of it are ordinary, and may be named" in {
      run("""label(tag: string, xs: ...int) -> string
            |    var out = tag
            |
            |    for x in xs
            |        out = out + " " + str(x)
            |
            |    out
            |
            |print(label("sum", 1, 2))
            |print(label(tag = "none"))""".stripMargin) shouldBe "sum 1 2\nnone\n"
    }

    "and a method takes one, with the receiver in front" in {
      run("""struct Adder
            |    base: int
            |
            |    total(self, xs: ...int) -> int
            |        var s = self.base
            |
            |        for x in xs
            |            s += x
            |
            |        s
            |
            |var a = Adder(10)
            |print(a.total(1, 2, 3), a.total())""".stripMargin) shouldBe "16 10\n"
    }
  }

  /** The form the card this was built for names as the point: a checked `print`-alike takes
    * `...&Display`, and each argument is erased where it stands.
    */
  "the heterogeneous form is the same parameter at a trait object" in {
    run("""show(xs: ...&Display) -> unit
          |    for x in xs
          |        print(x)
          |
          |show(1, "hi", true)""".stripMargin) shouldBe "1\nhi\ntrue\n"
  }

  "`xs...` hands an existing slice through" - {

    // Without it a function forwarding its own tail could not be written at all — it has a slice
    // and not a list of values, and packing that would make a slice of one slice.
    "which is what lets a function forward its own tail" in {
      run(total + "forward(xs: ...int) -> int = total(xs...)\n\nprint(forward(4, 5, 6))") shouldBe "15\n"
    }

    "and an ordinary slice from anywhere else" in {
      run(total + "var xs = [7, 8]\n\nprint(total(xs[..]...))") shouldBe "15\n"
    }
  }

  /** The array a call packs is laid out where the call is written, so a module that gave the
    * allocator up may still make one. That is the whole difference between this and a `Buf`.
    */
  "the packed array is the caller's frame, not the heap" - {

    "so an allocator-free module may call one" in {
      run("@no_alloc\n\n" + total + "print(total(1, 2, 3), total())") shouldBe "6 0\n"
    }

    "and the empty slice literal it rests on needs no allocator either" in {
      run("@no_alloc\n\nval e: []const int = []\nvar w: []int = []\nprint(e.len, w.len)") shouldBe "0 0\n"
    }
  }

  /** An array literal is the one receiver with no type of its own, so a view of one is the one place
    * the view's own expectation has to reach through to the elements. It is what the packing rests
    * on and it is worth writing down on its own, since a program may write it by hand.
    */
  "a sliced array literal takes its element type from the view that was asked for" in {
    run("""show(xs: []const &Display) -> unit
          |    for x in xs
          |        print(x)
          |
          |show([1, "hi", true][..])""".stripMargin) shouldBe "1\nhi\ntrue\n"
  }

  "where it may stand" - {

    "nothing may follow it, because the arguments that would be its are already inside it" in {
      err("f(xs: ...int, last: int) -> int = last\n\nprint(f(1))") should
        include("collects the rest of the call, so nothing can follow it")
    }

    "and it may not stand beside C's ellipsis, which is a different tail" in {
      err("f(xs: ...int, ...) -> int = 1\n\nprint(f(1))") should include("has two tails")
    }

    "an 'extern' may not carry one: what it hands over is a sysl slice" in {
      err("extern f(xs: ...int) -> int\n\nprint(f(1))") should
        include("not what a C function was compiled to read")
    }

    "and it declares no default — a call that leaves it out gets the empty slice" in {
      err("f(xs: ...int = [1][..]) -> int = 1\n\nprint(f())") should
        include("a default beside that is a second answer")
    }
  }

  "what a call may write" - {

    "its arguments are positional: a name picks out one parameter and this takes what is left" in {
      err(total + "print(total(xs = 1))") should include("so its arguments are positional")
    }

    "a spread is the whole of the tail or none of it" in {
      err(total + "var xs = [1, 2]\n\nprint(total(1, xs[..]...))") should
        include("a slice and a value cannot both be part of one tail")
    }

    "and a spread at a call that collects nothing is refused, naming the callee" in {
      err("f(a: int) -> int = a\n\nvar xs = [1]\n\nprint(f(xs[..]...))") should
        include("has no such parameter")
    }

    // Outside an argument list the grammar never reaches it at all — `...` is read where `name =`
    // is read and nowhere else, so this is a parse error rather than one of the sentences above.
    // The analyzer keeps an arm for it all the same: a library artifact carries the written AST, so
    // a decoded body could hold one that never passed through this parser.
    "and outside an argument list the grammar does not read it" in {
      err("var xs = [1]\nval y = xs[..]...\nprint(1)") should include("newline expected")
    }
  }

  /** `xs: ...T` and `xs: []const T` are the same parameter *type*, so only the flag says which of
    * the two a caller was written against — which makes it exactly as necessary to carry through an
    * artifact as `byName` is.
    */
  "the parameter's own mark travels in a library artifact" in {
    val parsed = SyslParser.parse(Source("<t>", "module m\n\ntotal(xs: ...int) -> int = 0\n")) match
      case Right(p) => p
      case Left(e)  => fail(s"the fixture does not parse: $e")

    val back = AstCodec.decode(AstCodec.encode(List(parsed)), Map.empty) match
      case Right(ps) => ps.head
      case Left(e)   => fail(s"decode failed: $e")

    back.body.collect { case f: FuncDecl => f.params.map(_.rest) } shouldBe List(List(true))
  }
}
