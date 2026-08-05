package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** How much of a C library can actually be externed (`12 §1`, `03`).
 *
 * The question is not whether `extern` works — it plainly does — but whether the *signatures a real
 * header contains* can be spelled, because one that cannot is a library sysl cannot bind. So the bulk
 * of this file is a broad sweep of libc declarations, each asserted to compile. A declaration nothing
 * calls is not emitted, which is what makes the sweep cheap: it tests the type spellings and nothing
 * else.
 *
 * The refusals at the bottom are the more valuable half. Each is a **known gap**, written as an
 * assertion about today's behaviour so that closing one is a test that fails and says so — a
 * capability that quietly arrives is as invisible as a refusal that quietly goes away.
 */
class CInteropTests extends AnyFreeSpec with RunSupport with CodegenSupport {

  /** Whether the hand-transcribed `<regex.h>` layouts further down describe this machine's libc.
    * See the block comment above the two tests that ask.
    */
  private def transcribedForThisLibc: Boolean = Target.host.map(_.os).contains(Os.MacOS)

  /** Every one of these must compile. They are grouped by header so a failure names the area. */
  private def spellable(ds: String*): Unit =
    for d <- ds do
      Compiler.compileToLlvm(d + "\nprint(1)") match
        case Right(_) => ()
        case Left(e)  => fail(s"could not spell:\n$d\n\n$e")

  "the signatures a C header contains can be spelled" - {
    "stdio, including the variadic and va_list forms" in {
      spellable(
        "extern puts(s: *u8) -> int",
        "extern printf(f: *u8, ...) -> int",
        "extern fprintf(st: *u8, f: *u8, ...) -> int",
        "extern snprintf(b: *u8, n: usize, f: *u8, ...) -> int",
        "extern sscanf(s: *u8, f: *u8, ...) -> int",
        "extern vfprintf(st: *u8, f: *u8, ap: va_list) -> int",
        "extern fopen(p: *u8, m: *u8) -> *u8",
        "extern fread(b: *u8, sz: usize, n: usize, st: *u8) -> usize",
        "extern fgets(b: *u8, n: int, st: *u8) -> *u8",
        "extern getline(lineptr: **u8, n: *usize, st: *u8) -> isize",
        "extern fseek(st: *u8, off: isize, whence: int) -> int",
        "extern setvbuf(st: *u8, b: *u8, mode: int, n: usize) -> int",
      )
    }

    // NB a struct-returning declaration *compiles*; what it does at run time is wrong, and the
    // ignored tests below say how. Spellable is not the same as correct, which is why this file has
    // a second section.
    "stdlib, including a struct returned by value" in {
      spellable(
        "extern malloc(n: usize) -> *u8",
        "extern free(p: *u8)",
        "extern strtol(s: *u8, endp: **u8, base: int) -> i64",
        "extern strtod(s: *u8, endp: **u8) -> f64",
        "extern qsort(b: *u8, n: usize, sz: usize, cmp: *u8)",
        "extern bsearch(k: *u8, b: *u8, n: usize, sz: usize, cmp: *u8) -> *u8",
        "extern atexit(f: *u8) -> int",
        "extern setenv(n: *u8, v: *u8, ow: int) -> int",
        "extern abort() -> never",
        "struct div_t\n    quot: i32\n    rem: i32\nextern div(a: i32, b: i32) -> div_t",
      )
    }

    "string and memory, the whole interior-pointer family included" in {
      spellable(
        "extern strlen(p: *u8) -> usize",
        "extern strcmp(a: *u8, b: *u8) -> int",
        "extern strdup(s: *u8) -> *u8",
        "extern strchr(s: *u8, c: int) -> *u8",
        "extern strstr(h: *u8, n: *u8) -> *u8",
        "extern strerror(e: int) -> *u8",
        "extern memcpy(d: *u8, s: *u8, n: usize) -> *u8",
        "extern memset(d: *u8, c: int, n: usize) -> *u8",
        "extern memchr(p: *u8, c: int, n: usize) -> *u8",
        "extern memmem(h: *u8, hn: usize, n: *u8, nn: usize) -> *u8",
      )
    }

    "math, including the out-parameter forms" in {
      spellable(
        "extern sqrt(x: f64) -> f64",
        "extern atan2(y: f64, x: f64) -> f64",
        "extern frexp(x: f64, e: *i32) -> f64",
        "extern modf(x: f64, ip: *f64) -> f64",
        "extern sqrtf(x: f32) -> f32",
      )
    }

    "unistd and the filesystem, including a struct the callee fills" in {
      spellable(
        "extern read(fd: int, p: *u8, n: usize) -> isize",
        "extern write(fd: int, p: *u8, n: usize) -> isize",
        "extern open(p: *u8, flags: int, ...) -> int",
        "extern lseek(fd: int, off: i64, whence: int) -> i64",
        "extern execv(p: *u8, argv: **u8) -> int",
        "extern pipe(fds: *i32) -> int",
        "extern mmap(a: *u8, n: usize, prot: int, flags: int, fd: int, off: i64) -> *u8",
        "struct stat_t\n    dev: i32\n    mode: u16\n    ino: u64\nextern stat(p: *u8, st: *stat_t) -> int",
        "struct dirent_t\n    ino: u64\n    reclen: u16\nextern readdir(d: *u8) -> *dirent_t",
      )
    }

    "time, whose every interface is a struct behind a pointer" in {
      spellable(
        "extern time(t: *i64) -> i64",
        "struct tm\n    sec: i32\n    min: i32\nextern localtime(t: *i64) -> *tm",
        "struct tm\n    sec: i32\nextern strftime(b: *u8, n: usize, f: *u8, t: *tm) -> usize",
        "struct timespec\n    sec: i64\n    nsec: i64\nextern clock_gettime(id: i32, t: *timespec) -> int",
      )
    }

    // Each of these *declares* fine — the callback is a `void *` and so is spellable. Producing the
    // argument is what cannot be done, which is the first refusal below.
    "the callback-taking interfaces, as declarations" in {
      spellable(
        "extern signal(sig: int, h: *u8) -> *u8",
        "struct sigaction_t\n    handler: *u8\n    mask: u32\nextern sigaction(s: int, a: *sigaction_t, o: *sigaction_t) -> int",
        "extern pthread_create(t: *u8, a: *u8, start: *u8, arg: *u8) -> int",
        "extern dlopen(p: *u8, f: int) -> *u8\nextern dlsym(h: *u8, n: *u8) -> *u8",
        "extern setjmp(b: *u8) -> int\nextern longjmp(b: *u8, v: int) -> never",
      )
    }
  }

  /** The half of the boundary that works end to end, run rather than compiled — a declaration that
    * compiles and passes the wrong bytes is the failure mode these rule out.
    */
  "and the values cross correctly" - {
    /** A struct returned by value has to be read out of the registers the *convention* puts it in,
      * which is not where LLVM would put it if left alone: given a struct result it assigns one
      * register per element, and `div_t` is eight bytes, which AAPCS64 packs into one. Reading field
      * 1 out of the second register got `2` — the second argument, still sitting there.
      *
      * `ldiv`, two `i64`s, is the instructive contrast and the reason this hid for so long: clang
      * declares `[2 x i64] @ldiv(...)`, LLVM's naive per-element assignment lands on the same two
      * registers, and a 16-byte struct happens to work. A test written only against that one proves
      * nothing, so any check here needs a struct **smaller than two registers**.
      */
    "a struct returned by value comes back in its fields" in {
      run("""struct div_t
            |    quot: i32
            |    rem: i32
            |extern div(a: i32, b: i32) -> div_t
            |var d = div(7, 2)
            |print(d.quot, d.rem)""".stripMargin) shouldBe "3 1\n"
    }

    // The same thing read off the declaration rather than off the answer, which is where it lives.
    // Both of these are what clang emits for the same two headers.
    //
    // **Named target, because "the way C classifies it" is a different answer per convention.**
    // AAPCS64 returns a 8-byte struct in one register and a 16-byte one in two; SysV x86-64 splits
    // by eightbyte class and spells the result differently again. Compiled for whatever machine ran
    // the suite, this asserted one convention's spelling and could only ever pass on that machine --
    // which is exactly what it did until the suite met a Linux runner.
    "a struct-returning extern is declared the way C classifies it" in {
      irFor(Target.aarch64MacOS,
            """struct div_t
              |    quot: i32
              |    rem: i32
              |extern div(a: i32, b: i32) -> div_t
              |print(div(7, 2).quot)""".stripMargin) should include("declare i64 @div(i32, i32)")

      irFor(Target.aarch64MacOS,
            """struct ldiv_t
              |    quot: i64
              |    rem: i64
              |extern ldiv(a: i64, b: i64) -> ldiv_t
              |print(ldiv(7i64, 2i64).quot)""".stripMargin) should include("declare [2 x i64] @ldiv(i64, i64)")
    }

    "a struct the callee fills is read back out" in {
      run("""struct timespec
            |    sec: i64
            |    nsec: i64
            |extern clock_gettime(id: i32, t: *timespec) -> int
            |var ts: timespec
            |print(clock_gettime(0, &ts) == 0)""".stripMargin) shouldBe "true\n"
    }

    "a struct C hands back a pointer to is read through it" in {
      run("""struct tm
            |    sec: i32
            |    min: i32
            |extern localtime(t: *i64) -> *tm
            |var now: i64 = 0i64
            |print(localtime(&now).sec >= 0)""".stripMargin) shouldBe "true\n"
    }

    // `char **endp`, the out-parameter shape half of stdlib uses. The pointer C wrote is then indexed
    // to show it points where C says it does.
    "a 'char **' the callee writes through is followed afterwards" in {
      run("""extern strtol(s: *u8, endp: **u8, base: int) -> i64
            |var endp: *u8 = null
            |var n = strtol(c"42rest", &endp, 10)
            |print(n, endp[0usize] == 114u8)""".stripMargin) shouldBe "42 true\n"
    }

    "a real file descriptor is written to" in {
      run("""extern write(fd: int, p: *u8, n: usize) -> isize
            |var msg: [3]u8 = [104u8, 105u8, 10u8]
            |print(write(1, &msg[0], 3usize) == 3isize)""".stripMargin) shouldBe "hi\ntrue\n"
    }

    /* The caller-allocated opaque type, which is the shape `regcomp` and half of POSIX are written
     * in: the callee fills storage the caller supplies, and the storage's size lives in a header.
     *
     * Both of these run, and that is the point worth recording — the shape is *reachable*, so what a
     * binding is missing is never "sysl cannot call this". What both spellings really hold is the
     * numbers 32 and 8 and the value of `REG_EXTENDED`, transcribed by hand, correct on this machine
     * and different under glibc, with nothing checking either. That is the argument for a shim
     * (`15 §7`), and these two are what it is an argument against.
     *
     * **That paragraph turned out to be literally true, and these two are held to macOS because of
     * it.** Run on glibc they fail, and not by a little: `regex_t` is a different size, and
     * `regoff_t` is `int` there rather than `long`, so `so`/`eo` declared `i64` read the wrong
     * halves of the wrong words. A second transcription could be written for glibc, but it would be
     * another set of hand-copied numbers that nothing checks — the exact thing being warned about
     * — so what is asserted instead is the transcription that was actually verified, on the
     * platform it was verified against. Elsewhere they are *cancelled*, which reads differently from
     * passing and is the honest answer. */

    "a caller-allocated C struct, transcribed field by field" in {
      assume(transcribedForThisLibc, "the struct layouts here are macOS's <regex.h>, not glibc's")

      // macOS <regex.h>: int re_magic; size_t re_nsub; const char *re_endp; struct re_guts *re_g.
      run("""struct regex_t
            |    magic: i32
            |    nsub: usize
            |    endp: *u8
            |    g: *u8
            |struct regmatch_t
            |    so: i64
            |    eo: i64
            |extern regcomp(preg: *regex_t, pattern: *u8, cflags: int) -> int
            |extern regexec(preg: *regex_t, s: *u8, nmatch: usize, pmatch: *regmatch_t, eflags: int) -> int
            |extern regfree(preg: *regex_t)
            |var re: regex_t
            |print(regcomp(&re, c"a+b", 1) == 0)
            |var m: regmatch_t
            |print(regexec(&re, c"xxaaab", 1usize, &m, 0) == 0)
            |print(m.so, m.eo)
            |regfree(&re)""".stripMargin) shouldBe "true\ntrue\n2 6\n"
    }

    "and the same one as nothing but sized, aligned storage" in {
      assume(transcribedForThisLibc, "four words is macOS's regex_t; glibc's is larger")

      // The fields are never read here, so the whole declaration is the two numbers: four words, at
      // the alignment `u64` carries. It is the smaller lie of the two and exactly as unchecked.
      run("""struct regex_t
            |    words: [4]u64
            |struct regmatch_t
            |    so: i64
            |    eo: i64
            |extern regcomp(preg: *regex_t, pattern: *u8, cflags: int) -> int
            |extern regexec(preg: *regex_t, s: *u8, nmatch: usize, pmatch: *regmatch_t, eflags: int) -> int
            |extern regfree(preg: *regex_t)
            |var re: regex_t
            |print(regcomp(&re, c"a+b", 1) == 0)
            |var m: regmatch_t
            |print(regexec(&re, c"xxaaab", 1usize, &m, 0) == 0)
            |print(m.so, m.eo)
            |regfree(&re)""".stripMargin) shouldBe "true\ntrue\n2 6\n"
    }
  }

  /** The gaps, as assertions about today. Each names what a C library needs and sysl cannot yet do,
    * so closing one breaks a test here and the message says which capability arrived.
    */
  "what a C library still cannot be given" - {
    /** **A function's address was the biggest gap here and is closed** — `*extern(A) -> R` is the
      * type, `&f` produces one, and `FuncAddressTests` is where the capability is held. What stays
      * here is the *shape of the mistake a reader still makes*, which is reaching for the address
      * with the bare name C would let them use.
      *
      * A bare `f` is the capture-free closure it has always been (`12 §5`), so what it needs is the
      * `&` and the message says so rather than reporting a typo.
      */
    "a function's address is reached with '&', not with the bare name C allows" in {
      val addr = err("cmp(a: *u8, b: *u8) -> int = 0\nprint(cmp)")

      addr should include("'cmp' is a function")
      addr should not include "undefined name"

      err("extern qsort(b: *u8, n: usize, sz: usize, c: *extern(*u8, *u8) -> int)\n" +
        "cmp(a: *u8, b: *u8) -> int = 0\nvar xs: [2]i32 = [2, 1]\n" +
        "qsort(ptr_cast(&xs[0]), 2usize, 4usize, cmp)\nprint(xs[0])") should
        include("'cmp' is a function")
    }

    // The other direction is open too, and what stays refused is the step that was skipped: a `*u8`
    // is an address of bytes and says nothing about a signature, so it is read as a `*extern` first.
    "and one C handed back is called after it is read as a function pointer, not before" in {
      err("extern dlsym(h: *u8, n: *u8) -> *u8\nvar f = dlsym(null, c\"abs\")\nprint(f(1))") should
        include("is not callable")

      run("extern dlopen(p: *u8, m: i32) -> *u8\nextern dlsym(h: *u8, n: *u8) -> *u8\n" +
        "var f: *extern(i32) -> i32 = ptr_cast(dlsym(dlopen(null, 1i32), c\"abs\"))\n" +
        "print(f(-3))") shouldBe "3\n"
    }

    /** **A global variable was the gap that half of `stdio.h` sat behind, and it is closed** —
      * `extern name: type` is the declaration and `ExternVarTests` is where the capability is held.
      * What stays here is that the sweep above is no longer only about functions: a header's
      * variables are as much of its interface as its calls, and `environ` had no way round at all.
      */
    "a global variable, which half of stdio's interface is reached through" in {
      spellable(
        "extern environ: **u8",
        "extern optind: i32",
        "extern \"__stdoutp\" stdout: *u8",
        "extern \"__stderrp\" stderr: *u8",
        "extern \"__stdinp\" stdin: *u8",
      )

      ir("extern environ: **u8\nprint(environ == null)") should
        include("@environ = external global ptr")
    }

    // `errno` is a macro on both platforms and the symbol behind it differs — `__error` on Darwin,
    // `__errno_location` on glibc — so the declaration is gated the same way `sysl.fs`'s own is.
    // One spelling links on one machine, which is not what this test is about.
    "while a global C also exposes as a function is reachable that way too" in {
      run(
        """#if macos
          |extern "__error" errno_at() -> *i32
          |#else
          |extern "__errno_location" errno_at() -> *i32
          |#endif
          |print(*errno_at() == 0)""".stripMargin) shouldBe "true\n"
    }

    /** A callable's *sysl* type is a trait object — two words, a method table beside the value — and
      * nothing about it is a C function pointer. The bare arrow is refused here for its own reason,
      * and the advice it gives (`&Fn`) is advice about sysl callables rather than about this seam.
      */
    "a bare arrow in an extern is refused, and names a boxed callable that C could not read" in {
      val e = err("extern qsort(b: *u8, n: usize, sz: usize, cmp: (*u8, *u8) -> int)\nprint(1)")

      e should include("only a parameter may be")
      e should include("&Fn(*u8, *u8) -> int")
    }

    /** **The trap `*extern` exists to keep somebody out of, and it is still open.** A `*Fn` in an
      * extern's parameter list compiles, and a trait object is two words where C reads one — so the
      * call would hand over the table and read the value as the callback.
      *
      * It is not refused because `12 §1` decided the general question the other way: a `string` and
      * a `&T` are sysl layouts C has no notion of, and handing one over is the programmer's business,
      * the same promise `*T` already is. Singling this one out would be a rule the chapter
      * contradicts. What has changed is that there is now a right answer to reach for, so the cost of
      * the trap is a spelling somebody did not know rather than a capability they did not have.
      */
    "while a '*Fn' in an extern still compiles, being a sysl layout the chapter leaves to its author" in {
      ir("extern qsort(b: *u8, n: usize, sz: usize, cmp: *Fn(*u8, *u8) -> int)\nprint(1)") should
        include("define")

      // What it should have said, which does cross as the one word C reads.
      ir("extern qsort(b: *u8, n: usize, sz: usize, cmp: *extern(*u8, *u8) -> int)\nprint(1)") should
        include("define")
    }

    "the widest C float is not lowered" in {
      err("extern sqrtl(x: f128) -> f128\nprint(1)") should include("'f128' is not lowered yet")
    }
  }
}
