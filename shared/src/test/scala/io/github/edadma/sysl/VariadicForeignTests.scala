package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** A walk over a variadic tail handed to a **C** function — `vprintf` and its family
 * (`12-functions-and-closures.md` §9, `targets.md`).
 *
 * This is the one thing in the compiler that is genuinely different on different machines. C's
 * `va_list` is a different type on every target and is passed three different ways, so the address
 * of a walk — the only thing sysl has — reaches a foreign callee as that address, as the value
 * stored at it, or as the address of a copy of it. All three pass one `ptr`, which is exactly why
 * the difference cannot be read back out of the emitted types and has to be tested per target.
 *
 * The run test at the top is the one that carries the feature: an assertion about emitted text
 * cannot tell a walk that was handed over correctly from one that was handed over plausibly.
 */
class VariadicForeignTests extends AnyFreeSpec with CodegenSupport with RunSupport {

  /** The C shape this whole section exists for: a sysl variadic that renders its own tail through
   * `vprintf`. The extern is written the way the C header is.
   */
  private val logger =
    """import sysl.text.cstring
      |
      |extern vprintf(fmt: *u8, ap: va_list) -> i32
      |
      |log(fmt: *u8, ...) -> i32
      |    var ap: va_list
      |    va_start(ap)
      |    var n = vprintf(fmt, &ap)
      |    va_end(ap)
      |    n
      |end log""".stripMargin

  /** The same program with a call in it, for the tests that read the emitted text. */
  private val call = s"$logger\nvar p: *u8 = null\nprint(log(p, 1))"

  /** The other of C's two spellings — `va_list *`, which a callee advances on its caller's behalf. */
  private val byPointer =
    """extern advance(ap: *va_list) -> i32
      |
      |f(n: int, ...) -> i32
      |    var ap: va_list
      |    va_start(ap)
      |    var r = advance(&ap)
      |    va_end(ap)
      |    r
      |end f""".stripMargin

  "handing a walk to C" - {
    "a variadic renders its own tail through vprintf" in {
      run(s"""$logger
             |var f = cstring("%lld and %lld\\n")
             |print(log(f.ptr, 3, 4))""".stripMargin) shouldBe "3 and 4\n8\n"
    }

    // Every argument of the tail has to arrive, not just the first — a walk handed over at the
    // wrong ABI can still get one right by accident.
    "every argument of the tail arrives" in {
      run(s"""$logger
             |var f = cstring("%lld %lld %lld %lld %lld\\n")
             |var n = log(f.ptr, 1, 2, 3, 4, 5)
             |print(n > 0)""".stripMargin) shouldBe "1 2 3 4 5\ntrue\n"
    }

    // The caller's walk is at some position when it is lent, and C reads on from there.
    "C reads on from wherever the walk had reached" in {
      run(s"""$logger
             |
             |skip_one(fmt: *u8, ...) -> i32
             |    var ap: va_list
             |    va_start(ap)
             |    var first: int = va_arg(ap)
             |    var n = vprintf(fmt, &ap)
             |    va_end(ap)
             |    first + n
             |end skip_one
             |
             |var f = cstring("%lld\\n")
             |print(skip_one(f.ptr, 100, 7))""".stripMargin) shouldBe "7\n102\n"
    }

    // A walk this function was lent is already an address, so lending it on to C asks nothing new.
    "a walk lent by a sysl caller can be handed straight on to C" in {
      run(s"""import sysl.text.cstring
             |
             |extern vprintf(fmt: *u8, ap: va_list) -> i32
             |
             |render(fmt: *u8, ap: *va_list) -> i32 = vprintf(fmt, ap)
             |
             |log(fmt: *u8, ...) -> i32
             |    var ap: va_list
             |    va_start(ap)
             |    var n = render(fmt, &ap)
             |    va_end(ap)
             |    n
             |end log
             |
             |var f = cstring("%lld!\\n")
             |print(log(f.ptr, 9))""".stripMargin) shouldBe "9!\n3\n"
    }
  }

  /** One program, three machines, three lowerings — the whole of what a target decides today. */
  "the three ABIs" - {
    // Darwin arm64's `va_list` *is* a `char *`, so what a call passes is the value in the storage.
    "a target that passes the value loads it out of the storage" in {
      val out = defineOf(irFor(Target.aarch64MacOS, call), "log")

      out should include regex "%t\\d+ = load ptr, ptr %ap\\.addr"
      out should include regex "call i32 @vprintf\\(ptr %t\\d+, ptr %t\\d+\\)"
      out should not include "memcpy"
    }

    // x86-64 System V's is an array of one struct, which decays — so the storage's own address goes.
    "a target that passes the storage passes its address unchanged" in {
      val out = defineOf(irFor(Target.x86_64Linux, call), "log")

      out should include("call i32 @vprintf(ptr %t1, ptr %ap.addr)")
      out should not include "memcpy"
    }

    // AAPCS64's is a struct passed indirectly, so the callee gets a copy it may advance freely.
    "a target that passes it indirectly copies it first" in {
      val out = defineOf(irFor(Target.aarch64Linux, call), "log")

      out should include regex "%t\\d+ = alloca \\[4 x ptr\\]"
      out should include regex
        "call void @llvm\\.memcpy\\.p0\\.p0\\.i64\\(ptr align 8 %t\\d+, ptr align 8 %ap\\.addr, i64 32, i1 false\\)"
    }

    // The number copied is the target's own `va_list`, not sysl's storage for one — the storage is
    // the widest any target needs and copying all of it would read past what `va_start` wrote.
    "the copy is the size of that target's va_list" in {
      irFor(Target.aarch64Linux, call) should include("i64 32, i1 false")
      Target.aarch64Linux.vaListBytes shouldBe 32
    }

    // A module carries the declaration for what it does, so which intrinsics it declares is itself
    // a fact about the target it was built for.
    "only the target that copies declares the copy" in {
      irFor(Target.aarch64Linux, call) should include("declare void @llvm.memcpy.p0.p0.i64(ptr, ptr, i64, i1)")
      irFor(Target.aarch64MacOS, call) should not include "llvm.memcpy"
      irFor(Target.x86_64Linux, call) should not include "llvm.memcpy"
    }

    "and every target declares the callee the same way, taking one pointer" in {
      for t <- Target.all if t.supported do
        withClue(t.name)(irFor(t, call) should include("declare i32 @vprintf(ptr, ptr)"))
    }
  }

  /** C spells the two shapes differently and so does the `extern` that transcribes it: `va_list` is
   * the by-value parameter `vprintf` takes, `*va_list` is `va_list *`, which a function that must
   * advance its caller's own walk takes. The call writes `&ap` for either.
   */
  "C's two spellings" - {
    // `va_list *` is an ordinary pointer on every machine, so nothing is converted for it — which
    // is the observable difference between the two spellings.
    "a va_list* parameter takes the address itself, on every target" in {
      for t <- Target.all if t.supported do
        withClue(t.name) {
          val out = defineOf(irFor(t, s"$byPointer\nprint(f(0))"), "f")

          out should include("call i32 @advance(ptr %ap.addr)")
          out should not include "memcpy"
        }
    }

    // Same source, one word different in the declaration, and a different thing crosses over.
    "the same call converts or does not, according to which was declared" in {
      val value   = irFor(Target.aarch64MacOS, s"$logger\nvar p: *u8 = null\nprint(log(p, 1))")
      val pointer = irFor(Target.aarch64MacOS, s"$byPointer\nprint(f(0))")

      defineOf(value, "log") should include regex "load ptr, ptr %ap\\.addr"
      defineOf(pointer, "f") should not include "load ptr, ptr %ap.addr"
    }
  }

  /** The shapes that would have shipped green under a lowering that handled only the one case the
   * feature was built for.
   */
  "the corners" - {
    // Two walks in one call: the positions have to be converted independently, and a lowering that
    // took "the va_list argument" would convert one of them.
    "an extern taking two walks converts both" in {
      val src =
        """extern both(a: va_list, b: va_list) -> i32
          |
          |f(n: int, ...) -> i32
          |    var x: va_list
          |    var y: va_list
          |    va_start(x)
          |    va_copy(y, x)
          |    var r = both(&x, &y)
          |    va_end(x)
          |    va_end(y)
          |    r
          |end f""".stripMargin

      defineOf(irFor(Target.aarch64MacOS, s"$src\nprint(f(0))"), "f") should include regex
        "load ptr, ptr %x\\.addr[\\s\\S]*load ptr, ptr %y\\.addr[\\s\\S]*call i32 @both"
      defineOf(irFor(Target.aarch64Linux, s"$src\nprint(f(0))"), "f") should include regex
        "memcpy[\\s\\S]*memcpy[\\s\\S]*call i32 @both"
    }

    // A `va_list` before the tail of a *variadic* extern: the conversion is by position, and the
    // tail's arguments are not parameters, so the two must not be counted together.
    "a walk in front of a foreign tail is found by position" in {
      val src =
        """extern odd(ap: va_list, ...) -> i32
          |
          |f(n: int, ...) -> i32
          |    var ap: va_list
          |    va_start(ap)
          |    var r = odd(&ap, 1, 2)
          |    va_end(ap)
          |    r
          |end f""".stripMargin

      defineOf(irFor(Target.aarch64MacOS, s"$src\nprint(f(0))"), "f") should include regex
        "call i32 \\(ptr, \\.\\.\\.\\) @odd\\(ptr %t\\d+, i32 1, i32 2\\)"
    }

    // The storage a copy goes into is an entry-block slot, so a call in a loop reuses one rather
    // than growing the frame per iteration.
    "a call in a loop copies into one slot, not one per iteration" in {
      val src =
        s"""$logger
           |var p: *u8 = null
           |
           |go(n: int, ...) -> int
           |    for i in 0..<n
           |        var ap: va_list
           |        va_start(ap)
           |        var q: *u8 = null
           |        var unused = vprintf(q, &ap)
           |        va_end(ap)
           |    0
           |end go
           |print(go(0))""".stripMargin

      defineOf(irFor(Target.aarch64Linux, src), "go").linesIterator
        .count(_.contains("alloca [4 x ptr]")) shouldBe 2
    }

    // The four forms and the crossing are the same address, so a body may do both to one walk.
    "a walk may be read, lent to C, and read again" in {
      run(s"""$logger
             |
             |mixed(fmt: *u8, ...) -> int
             |    var ap: va_list
             |    va_start(ap)
             |    var first: int = va_arg(ap)
             |    var copy: va_list
             |    va_copy(copy, ap)
             |    var wrote = vprintf(fmt, &copy)
             |    va_end(copy)
             |    var second: int = va_arg(ap)
             |    va_end(ap)
             |    first + second + wrote
             |end mixed
             |
             |var f = cstring("%lld\\n")
             |print(mixed(f.ptr, 10, 20))""".stripMargin) shouldBe "20\n33\n"
    }

    // The extern is reached only through the converted argument, so nothing else keeps it alive.
    "the callee is still declared when the walk is the only reason to reach it" in {
      irFor(Target.aarch64MacOS, call) should include("declare i32 @vprintf(ptr, ptr)")
    }
  }

  "what is still refused" - {
    // The rule is about a *sysl* body, which could do nothing with a copy of a walk. A foreign
    // body is C's, and C's `vprintf` is exactly a body that reads one.
    "a bare va_list parameter on a sysl function, and only there" in {
      err("f(ap: va_list) -> int = 1\nprint(1)") should
        include("a va_list is a parameter as '*va_list', not as 'va_list'")

      irFor(Target.aarch64MacOS, "extern g(ap: va_list) -> i32\nprint(g(null))") should
        include("declare i32 @g(ptr)")
    }

    // There is no by-value `va_list` in sysl at all, foreign or not, so there is nowhere to put one
    // that came back.
    "handing one back, whoever the callee is" in {
      err("extern current() -> va_list\nprint(1)") should
        include("a va_list cannot be returned from 'current'")

      err("f(n: int, ...) -> va_list\n    var ap: va_list\n    ap\nend f\nprint(1)") should
        include("a va_list cannot be returned from 'f'")
    }

    // The parameter is checked as the address it is, so the complaint names `*va_list` — the thing
    // the caller has to produce — rather than C's spelling, which the caller never writes.
    "something that is not a walk at all, where one is wanted" in {
      err("extern vprintf(fmt: *u8, ap: va_list) -> i32\nvar p: *u8 = null\nprint(vprintf(p, p))") should
        include("'ap' of 'vprintf' is *va_list, but *byte was given")
    }

    "too few arguments, which the foreign declaration counts like any other" in {
      err("extern vprintf(fmt: *u8, ap: va_list) -> i32\nvar p: *u8 = null\nprint(vprintf(p))") should
        include("2 arguments")
    }
  }
}
