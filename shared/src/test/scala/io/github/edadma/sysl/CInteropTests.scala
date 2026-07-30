package io.github.edadma.sysl

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
    "a struct-returning extern is declared the way C classifies it" in {
      ir("""struct div_t
           |    quot: i32
           |    rem: i32
           |extern div(a: i32, b: i32) -> div_t
           |print(div(7, 2).quot)""".stripMargin) should include("declare i64 @div(i32, i32)")

      ir("""struct ldiv_t
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
  }

  /** The gaps, as assertions about today. Each names what a C library needs and sysl cannot yet do,
    * so closing one breaks a test here and the message says which capability arrived.
    */
  "what a C library still cannot be given" - {
    /** **A sysl function has no address.** So every callback-taking interface above — `qsort`,
      * `bsearch`, `signal`, `sigaction`, `atexit`, `pthread_create` — can be declared and cannot be
      * *called*, because there is nothing to pass. `12 § Open e` (a named function as a first-class
      * value) and `12 § Open f` (a symbol for a sysl definition) are the two halves of it.
      *
      * The diagnostic is also wrong about what happened: the name is defined, it is a function, and
      * what is missing is a way to take its address.
      */
    "a function's address, which is what a C callback is" in {
      err("cmp(a: *u8, b: *u8) -> int = 0\nprint(&cmp == null)") should include("undefined name 'cmp'")
      err("extern qsort(b: *u8, n: usize, sz: usize, c: *u8)\ncmp(a: *u8, b: *u8) -> int = 0\n" +
        "var xs: [2]i32 = [2, 1]\nqsort(&xs[0], 2usize, 4usize, cmp)\nprint(xs[0])") should
        include("undefined name 'cmp'")
    }

    // The same gap from the other side: a function pointer C hands back cannot be invoked, so
    // `dlsym` is as unusable as `qsort`.
    "and calling one C handed back" in {
      err("extern dlsym(h: *u8, n: *u8) -> *u8\nvar f = dlsym(null, c\"abs\")\nprint(f(1))") should
        include("is not callable")
    }

    /** **A global variable cannot be externed.** `extern` declares functions only, so `stdout`,
      * `stderr`, `stdin`, `environ`, `optarg` and `optind` have no spelling. Where C also offers a
      * getter there is a way round — `errno` is `__error()` on Darwin, and the next test uses it —
      * but `stdout` and `environ` have none.
      */
    "a global variable, which half of stdio's interface is reached through" in {
      err("extern stdout: *u8\nprint(1)") should include("'(' expected")
      err("extern environ: **u8\nprint(1)") should include("'(' expected")
    }

    "while a global C also exposes as a function is reachable" in {
      run("extern \"__error\" errno_at() -> *i32\nprint(*errno_at() == 0)") shouldBe "true\n"
    }

    /** A callable's *sysl* type is a trait object — three words with a method table — and nothing
      * about it is a C function pointer. The declaration is accepted, which is the trap: it would
      * compile and pass the wrong thing. Recorded so that a real C-function-pointer type, when it
      * arrives, is what an extern is held to instead.
      */
    "a bare arrow in an extern is refused, and names a boxed callable that C could not read" in {
      val e = err("extern qsort(b: *u8, n: usize, sz: usize, cmp: (*u8, *u8) -> int)\nprint(1)")

      e should include("only a parameter may be")
      e should include("&Fn(*u8, *u8) -> int")
    }

    "the widest C float is not lowered" in {
      err("extern sqrtl(x: f128) -> f128\nprint(1)") should include("'f128' is not lowered yet")
    }
  }
}
