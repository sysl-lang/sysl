package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** A **sysl** function that takes a `...`, and the forms its body reads the tail with
 * (`12-functions-and-closures.md` §9). Lending a walk to another function and duplicating one are
 * the same chapter and live in `VariadicForwardTests`; a member that takes a `...` is in
 * `VariadicMethodTests`.
 *
 * The calling side is the same rule an `extern`'s tail follows and is tested with it; what is new
 * here is the *receiving* side — `va_list`, `va_start`, `va_arg`, `va_end` — which is C's, with the
 * one difference that `va_arg` takes the type it reads from the context rather than as a second
 * argument, since a sysl expression cannot hold a type.
 *
 * These are ABI primitives, so the tests that matter are the ones that really run: whether a value
 * comes back out at the width it went in at is not something the IR can be read for.
 */
class VariadicFunctionTests extends AnyFreeSpec with CodegenSupport with RunSupport with ParseSupport {

  /** A function that sums `n` `int`s out of its tail — the smallest complete variadic body. */
  private val sumInts =
    """sum(n: int, ...) -> int
      |    var ap: va_list
      |    va_start(ap)
      |    var total = 0
      |    for i in 0..<n
      |        var v: int = va_arg(ap)
      |        total += v
      |    va_end(ap)
      |    total
      |end sum""".stripMargin

  "the declaration" - {
    "carries the ellipsis into the definition" in {
      ir(s"$sumInts\nprint(sum(1, 5))") should include("define i32 @sum(i32 %n.param, ...)")
    }

    "a call names the callee's whole function type, as for any variadic" in {
      ir(s"$sumInts\nprint(sum(2, 5, 6))") should include regex
        "call i32 \\(i32, \\.\\.\\.\\) @sum\\(i32 2, i32 5, i32 6\\)"
    }

    "the tail walk lowers to LLVM's own two intrinsics and its one instruction" in {
      val out = ir(s"$sumInts\nprint(sum(1, 5))")

      out should include("declare void @llvm.va_start.p0(ptr)")
      out should include("declare void @llvm.va_end.p0(ptr)")
      out should include regex "va_arg ptr %ap\\.addr, i32"
    }

    // A program with no variadic body of its own must not carry the declarations for one.
    "a program that walks no tail declares neither intrinsic" in {
      val out = ir("extern printf(fmt: *u8, ...) -> int\nvar p: *u8 = null\nprint(printf(p))")

      out should not include "@llvm.va_start"
      out should not include "@llvm.va_end"
    }

    "a non-variadic function is unchanged" in {
      ir("f(n: int) -> int = n + 1\nprint(f(1))") should include("define i32 @f(i32 %n.param)")
    }
  }

  "reading the tail" - {
    "reaches every argument, in order" in {
      run(s"$sumInts\nprint(sum(3, 10, 20, 30))") shouldBe "60\n"
    }

    "works for one argument and for many" in {
      run(s"$sumInts\nprint(sum(1, 7), sum(5, 1, 2, 3, 4, 5))") shouldBe "7 15\n"
    }

    // A variadic call with an empty tail is legal, and a body that reads none of it must not walk
    // off the end of anything.
    "an empty tail is not read at all" in {
      run(s"$sumInts\nprint(sum(0))") shouldBe "0\n"
    }

    "each type comes back at the width it went in at" in {
      val src =
        """show(n: int, ...)
          |    var ap: va_list
          |    va_start(ap)
          |    var i: int  = va_arg(ap)
          |    var d: real = va_arg(ap)
          |    var c: char = va_arg(ap)
          |    var l: long = va_arg(ap)
          |    va_end(ap)
          |    print(i, d, c, l)
          |end show
          |show(4, 42, 2.5, 'A', 9000000000i64)""".stripMargin

      run(src) shouldBe "42 2.5 A 9000000000\n"
    }

    // The promotions of `12 §1` are the caller's, so a narrow argument arrives widened and the
    // callee reads it at the promoted width — this is the pair of rules meeting.
    "a narrow argument is read at the width it was promoted to" in {
      val src =
        s"""$sumInts
           |var a: u8 = 200
           |var b: i8 = -5
           |print(sum(2, a, b))""".stripMargin

      run(src) shouldBe "195\n"
    }

    "a raw pointer survives the round trip" in {
      val src =
        """deref(n: int, ...) -> int
          |    var ap: va_list
          |    va_start(ap)
          |    var p: *int = va_arg(ap)
          |    va_end(ap)
          |    *p
          |end deref
          |var x = 41
          |print(deref(1, &x) + 1)""".stripMargin

      run(src) shouldBe "42\n"
    }

    "a variadic function calls and is called like any other" in {
      val src =
        s"""$sumInts
           |twice(n: int) -> int = sum(2, n, n)
           |print(twice(21))""".stripMargin

      run(src) shouldBe "42\n"
    }

    "reading the tail twice over needs a fresh walk each time" in {
      val src =
        """total(n: int, ...) -> int
          |    var acc = 0
          |    var pass = 0
          |    while pass < 2
          |        var ap: va_list
          |        va_start(ap)
          |        for i in 0..<n
          |            var v: int = va_arg(ap)
          |            acc += v
          |        va_end(ap)
          |        pass += 1
          |    acc
          |end total
          |print(total(3, 1, 2, 3))""".stripMargin

      run(src) shouldBe "12\n"
    }

    "a variadic call in a loop neither leaks nor drifts" in {
      val src =
        s"""$sumInts
           |var acc = 0
           |for i in 1..4
           |    acc += sum(2, i, i)
           |print(acc)""".stripMargin

      // 2·(1+2+3+4).
      run(src) shouldBe "20\n"
    }
  }

  "shapes a variadic function may take" - {
    "several named parameters may precede the tail" in {
      val src =
        """between(lo: int, hi: int, n: int, ...) -> int
          |    var ap: va_list
          |    va_start(ap)
          |    var found = 0
          |    for i in 0..<n
          |        var v: int = va_arg(ap)
          |        if lo <= v && v <= hi then found += 1
          |    va_end(ap)
          |    found
          |end between
          |print(between(10, 20, 5, 5, 10, 15, 20, 25))""".stripMargin

      run(src) shouldBe "3\n"
    }

    // Nothing obliges a body to read the whole tail, and C's does not either — an unread argument
    // is simply never looked at.
    "a body may read fewer arguments than were passed" in {
      val src =
        """head(n: int, ...) -> int
          |    var ap: va_list
          |    va_start(ap)
          |    var v: int = va_arg(ap)
          |    va_end(ap)
          |    v
          |end head
          |print(head(3, 7, 8, 9))""".stripMargin

      run(src) shouldBe "7\n"
    }

    // Each instantiation is its own definition, so each gets its own ellipsis — a generic variadic
    // is where monomorphization and the tail meet.
    "a generic function may be variadic" in {
      val src =
        """first[T](tag: T, n: int, ...) -> int
          |    var ap: va_list
          |    va_start(ap)
          |    var v: int = va_arg(ap)
          |    va_end(ap)
          |    v + n
          |end first
          |print(first("tag", 1, 41), first(2.5, 2, 40))""".stripMargin
      val out = ir(src)

      out should include("define i32 @first.string(")
      out should include("define i32 @first.real(")
      out.linesIterator.count(l => l.startsWith("define") && l.contains("@first.") && l.contains("...")) shouldBe 2
      run(src) shouldBe "42 42\n"
    }

    // A nested function states its own signature (`12 §5a`), so a `...` on one is its own tail —
    // the environment holds the first parameter slot, exactly as a method's receiver does, and the
    // tail anchors after what the program wrote.
    "a nested function may be variadic, and still reach what it captured" in {
      val src =
        """outer(base: int) -> int
          |    inner(n: int, ...) -> int
          |        var ap: va_list
          |        va_start(ap)
          |        var t = 0
          |        for i in 0..<n
          |            var v: int = va_arg(ap)
          |            t += v
          |        va_end(ap)
          |        t + base
          |    end inner
          |    inner(2, 1, 2) + inner(0)
          |end outer
          |print(outer(10))""".stripMargin

      run(src) shouldBe "23\n"
    }

    "and it needs something named before the ellipsis like any other" in {
      err("outer() -> int\n    inner(...) -> int = 1\n    inner(1)\nend outer\nprint(outer())") should
        include("'inner' needs at least one named parameter before '...'")
    }

    "a variadic function may recurse" in {
      val src =
        """countdown(n: int, ...) -> int
          |    if n <= 0 then 0 else n + countdown(n - 1, n)
          |end countdown
          |print(countdown(4, 0))""".stripMargin

      run(src) shouldBe "10\n"
    }

    "a variadic function may declare that it does not return" in {
      val src =
        """stop(code: int, ...) -> never
          |    print("stopping")
          |    exit(code)
          |end stop
          |stop(3, 1, 2)
          |print("unreachable")""".stripMargin
      val out = ir(src)

      out should include("define void @stop(i32 %code.param, ...)")
      out should include regex "call void \\(i32, \\.\\.\\.\\) @stop"
      out should include("unreachable")
      panics(src, "stopping")
    }
  }

  "the calling side is the extern's rule" - {
    "the declared parameters are still checked" in {
      err(s"$sumInts\nprint(sum(\"three\", 1))") should include("'n' of 'sum' is int, but string was given")
    }

    "the declared parameters are still required" in {
      err(s"$sumInts\nprint(sum())") should include("takes at least 1 argument, but 0 arguments were given")
    }

    // A *sysl* callee's tail refuses an aggregate for a reason of its own, and the message says
    // which: it is the callee's own walk that would have to read the value back. A foreign callee
    // takes one, because there the convention says which registers it arrives in (`CAbi`).
    "the tail carries only what a walk over it can read back" in {
      err(s"$sumInts\nprint(sum(1, \"one\"))") should
        include("a string cannot be passed to a sysl function's '...'")
    }

    "an ellipsis needs a named parameter before it" in {
      err("f(...) -> int = 1\nprint(f(1))") should include("'f' needs at least one named parameter before '...'")
    }
  }

  "the forms refuse what they cannot mean" - {
    "va_start needs a function that has a tail" in {
      val src =
        """f(n: int) -> int
          |    var ap: va_list
          |    va_start(ap)
          |    n
          |end f
          |print(f(1))""".stripMargin

      err(src) should include("'va_start' is only allowed in a function declared with '...'")
    }

    "each form needs a va_list" in {
      err(s"$sumInts\nvar x = 1\nva_end(x)") should include("'va_end' needs a va_list, not int")
    }

    "each form needs exactly one" in {
      err("f(n: int, ...)\n    var ap: va_list\n    va_start(ap, n)\nf(1)") should
        include("'va_start' takes exactly one argument")
    }

    // A place, because the walk advances the list rather than reading a copy of it.
    "each form needs something with an address" in {
      err("f(n: int, ...)\n    va_start(1)\nf(1)") should include("'va_start'")
    }

    "va_arg needs to know what it is reading" in {
      val src =
        """f(n: int, ...)
          |    var ap: va_list
          |    va_start(ap)
          |    print(va_arg(ap))
          |end f
          |f(1, 2)""".stripMargin

      err(src) should include("nothing here says which")
    }

    // C's commonest varargs mistake: asking for the type as written rather than as promoted.
    // Diagnosed rather than answered wrongly, and nothing is lost — read it wide and convert.
    "va_arg refuses a type narrower than the promotions produce" in {
      val narrow =
        """f(n: int, ...)
          |    var ap: va_list
          |    va_start(ap)
          |    var v: u8 = va_arg(ap)
          |    print(v)
          |end f
          |f(1, 2)""".stripMargin

      err(narrow) should include("promoted to at least 32 bits")
      err(narrow) should include("read it as 'uint' and convert")
      err(narrow.replace("u8", "f32")) should include("promoted to double")
    }

    "va_arg refuses a type varargs never carried" in {
      val src =
        """f(n: int, ...)
          |    var ap: va_list
          |    va_start(ap)
          |    var v: string = va_arg(ap)
          |    print(v)
          |end f
          |f(1, 2)""".stripMargin

      err(src) should include("a variadic argument cannot be read as string")
    }
  }

  // A walk is handed on by address (`12 §9`, *Handing a walk on*), and the spellings that would
  // mean something else are each refused for their own reason.
  "where a va_list may and may not stand" - {
    "a bare va_list parameter names the spelling that works" in {
      val out = err("f(ap: va_list) -> int = 1\nprint(1)")

      out should include("a va_list is a parameter as '*va_list', not as 'va_list'")
      out should include("the call writes '&ap'")
    }

    "a va_list cannot be returned" in {
      err("f(n: int, ...) -> va_list\n    var ap: va_list\n    ap\nend f\nprint(1)") should
        include("a va_list cannot be returned from 'f'")
    }

    // An `extern` transcribes a C header, so C's own spelling is what it is written in and the
    // refusal above does not reach it. Which of the two was written decides what the call hands
    // over, and that is `VariadicForeignTests`.
    "an extern is written in C's spellings, and takes either" in {
      ir("extern vprintf(fmt: *u8, ap: va_list) -> i32\nvar p: *u8 = null\nprint(vprintf(p, null))") should
        include("declare i32 @vprintf(ptr, ptr)")

      ir("extern vsomething(ap: *va_list) -> i32\nprint(vsomething(null))") should
        include("declare i32 @vsomething(ptr)")
    }

    // A `*va_list` is an ordinary pointer, so handing one back is ordinary. The bare spelling is
    // not: `va_list` names the storage a walk lives in, and there is no value of it to return.
    "an extern may hand back the address of one, but not one" in {
      ir("extern current() -> *va_list\nprint(current() == null)") should
        include("declare ptr @current()")

      err("extern current() -> va_list\nprint(1)") should
        include("a va_list cannot be returned from 'current'")
    }

    "and it is not something to print" in {
      err("f(n: int, ...)\n    var ap: va_list\n    print(ap)\nf(1)") should include("cannot print a va_list value")
    }
  }
}
