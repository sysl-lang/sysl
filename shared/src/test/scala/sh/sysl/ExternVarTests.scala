package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** `extern name: type` — storage the linker supplies (`reference/ffi.md § An extern also declares a variable`).
 *
 * The seam had one half for as long as it existed: a program could call out but could not *name*
 * anything the other side had laid down. `stdout`, `stderr`, `stdin`, `environ`, `optarg`, `optind`
 * are variables, and half of `stdio.h`'s interface is reached through the first of them — so the
 * value of this feature is not that a declaration parses but that a real C global is really reached,
 * which is what the runs at the bottom are for.
 *
 * They are what makes the file worth its length. `fputs(s, stdout)` only prints in the right *order*
 * if `stdout` is libc's own `FILE` and not another one — the library renders through `putchar`, so an
 * `fdopen(1, "w")` standing in for it would buffer separately and reorder the output. And walking
 * `environ` to the null terminator is a loop over storage nothing in this program laid down, which
 * terminates only because the address really is the one the loader filled in.
 */
class ExternVarTests
    extends AnyFreeSpec
    with RunSupport
    with CodegenSupport
    with ParseSupport
    with TestFrameworkSupport {

  "the declaration" - {
    "is a name and a type where a function's is a name and a parameter list" in {
      prog("extern stdout: *u8") shouldBe
        List(ExternVarDecl("stdout", PtrType(NamedType("u8"))))
    }

    "takes a link name exactly as the function form does" in {
      prog("""extern "environ" env: **u8""") shouldBe
        List(ExternVarDecl("env", PtrType(PtrType(NamedType("u8"))), Some("environ")))
    }

    "carries a visibility of its own" in {
      prog("private extern optind: i32") shouldBe
        List(ExternVarDecl("optind", NamedType("i32"), None, Visibility.File))
    }

    // The type is written and there is nothing to infer it from, which is the one shape of this
    // declaration that could not have been guessed from the function form.
    "states a type and cannot leave it out" in {
      progError("extern stdout") should include("'(' or ':'")
      progError("extern stdout =") should include("'(' or ':'")
    }

    // Reported as the mistake it is rather than as the rule falling back to the sentence above: the
    // colon has been read by then, so what follows it is committed to being a type.
    "a colon with no type after it says so" in {
      progError("extern stdout:") should include("states the type the other side laid down")
    }

    "and type parameters are no more an extern variable's than a function's" in {
      progError("extern env[T]: *T") should include("declares no type parameters")
    }
  }

  "what the name may be" - {
    "not one already declared" in {
      err("extern stdout: *u8\nextern stdout: *u8\nprint(1)") should
        include("'stdout' is already declared")
    }

    "not one a 'val' holds" in {
      err("static val n: int = 1\nextern n: i32\nprint(1)") should include("'n' is already used by a 'val'")
    }

    "not one a constant holds" in {
      err("const n: int = 1\nextern n: i32\nprint(1)") should
        include("'n' is already used by a constant")
    }

    // The other direction of the same clash, which is a different registration and so a different
    // check: the function pass runs after the value one and has to look back at what it filled.
    "and a function declared afterwards may not take it either" in {
      err("extern optind: i32\noptind() -> int = 1\nprint(1)") should
        include("'optind' is already declared as an 'extern' variable")
      err("extern optind: i32\nextern optind(n: int) -> int\nprint(1)") should
        include("'optind' is already declared as an 'extern' variable")
    }
  }

  "what the symbol may be" - {
    "something a linker could resolve" in {
      err("""extern "not a symbol" x: i32""" + "\nprint(1)") should
        include("'not a symbol' is not a symbol a linker can resolve")
    }

    // The same rule the function form is held to, for the same reason: this program defines `main`.
    "and never the one the platform starts the program at" in {
      err("""extern "main" start: i32""" + "\nprint(1)") should
        include("'main' is where the platform starts this program")
    }
  }

  "what the type may be" - {
    // Every global worth reaching is a pointer. A `val` takes one too (`reference/modules.md § val — a thing`); what it refuses is
    // a counted value, and an extern variable is refused nothing at all, because the storage is the
    // other side's and so is whatever releasing it would mean.
    "a pointer, which is what nearly every C global is" in {
      ir("extern stdout: *u8\nprint(stdout == null)") should
        include("@stdout = external global ptr")
      ir("extern environ: **u8\nprint(environ == null)") should
        include("@environ = external global ptr")
    }

    // A `&T` reaches both kinds of module storage, and the pair is worth asking together because the
    // two answer for quite different reasons. A `val` holds one and never releases it, which is what
    // a static is; an `extern` variable holds one and the question does not arise at all, since the
    // storage is the other side's and so is whatever releasing it would mean.
    "a counted reference, which both kinds of module storage take" in {
      val node = "struct Node\n    v: int\nend Node\n"

      ir(node + "extern r: &Node\nprint(str(r.v))") should include("@r = external global ptr")
      run(node + "mk() -> &Node = Node(1)\nstatic val r: &Node = mk()\nprint(r.v)") shouldBe "1\n"
    }

    "a scalar, laid out as the type says" in {
      ir("extern optind: i32\nprint(optind)") should include("@optind = external global i32")
      ir("extern tzoff: i64\nprint(tzoff)") should include("@tzoff = external global i64")
    }

    // The one refusal, and the `val`'s reason for it: a symbol is an address, and a value with no
    // representation has nothing to put one at.
    "and nothing that occupies no storage at all" in {
      err("extern nothing: unit\nprint(1)") should
        include("cannot be an 'extern' variable: a unit value occupies nothing")
    }

    // Checked whether or not anything reads it — it is the declaration that is wrong.
    "which is reported even where nothing names it" in {
      err("extern nothing: unit\nprint(2)") should include("occupies nothing")
    }
  }

  "what is emitted" - {
    "the link name is what the declaration resolves to, and the sysl name is not emitted at all" in {
      val out = ir("""extern "environ" env: **u8""" + "\nprint(env == null)")

      out should include("@environ = external global ptr")
      out should not include "@env ="
    }

    // The same accounting an `extern` function gets: a declaration nothing reads costs the output
    // nothing, which is what lets a library offer one without every program carrying it.
    "an extern variable nothing reads is not declared" in {
      ir("extern stdout: *u8\nprint(1)") should not include "@stdout"
    }

    // Two names for one symbol is the case the link name exists for, and a module may declare one
    // symbol only once.
    "two names for one symbol declare it once" in {
      val out = ir(
        """extern "environ" a: **u8
          |extern "environ" b: **u8
          |print(a == null, b == null)""".stripMargin,
      )

      out.linesIterator.count(_.startsWith("@environ = external global")) shouldBe 1
    }

    "a read is a load through the symbol" in {
      ir("extern optind: i32\nprint(optind)") should include("load i32, ptr @optind")
    }

    "and a write is a store through it" in {
      ir("extern optind: i32\noptind = 1i32\nprint(optind)") should
        include("store i32 1, ptr @optind")
    }
  }

  /** A `val` is read-only at every depth: the analyzer refuses both an assignment to one and a `&`
    * that would take the address of its storage (`reference/modules.md § val — a thing`). An `extern` variable is the one global
    * those rules do not reach, and the difference is what the storage *is*: `optind` and `optarg`
    * are assigned by ordinary C, and a declaration that could only read them would name half of
    * `getopt`'s interface.
    */
  "it is a place, unlike a 'val'" - {
    "assignment reaches it, where a 'val' refuses" in {
      err("static val n: int = 1\nn = 2\nprint(n)") should include("a 'val' is written once")
      ir("extern optind: i32\noptind = 3i32\nprint(optind)") should include("define")
    }

    "and so does its address, where a 'val' refuses that too" in {
      err("static val n: int = 1\nvar p = &n\nprint(*p)") should include("a 'val' is written once")
      ir("extern optind: i32\nvar p = &optind\nprint(*p)") should include("define")
    }

    // Storage read while the program runs is not a value a pattern can be written against, which is
    // the `val`'s answer and for one more reason here: the linker fills it.
    "while a pattern still cannot be written against one" in {
      err("extern optind: i32\n1i32 match\n    optind -> print(1)\n    _ -> print(2)") should
        include("'optind' is an 'extern' variable")
    }
  }

  "reached through the module system" - {
    "a public one is named fully-qualified with no import" in {
      irOf(
        "os/env.sysl"  -> "module os\n\nextern environ: **u8",
        "main.sysl"    -> "print(os.environ == null)",
      ) should include("@environ = external global ptr")
    }

    "an imported one is named by its short name" in {
      irOf(
        "os/env.sysl" -> "module os\n\nextern environ: **u8",
        "main.sysl"   -> "import os.environ\n\nprint(environ == null)",
      ) should include("@environ = external global ptr")
    }

    // The key carries the module and the **symbol** does not, and cannot: the linker knows nothing
    // about sysl's modules. Two modules may each declare `environ` and both reach the one symbol.
    "and its symbol carries no module, because the linker knows of none" in {
      val out = irOf(
        "os/env.sysl"  -> "module os\n\nextern environ: **u8",
        "sys/env.sysl" -> "module sys\n\nextern environ: **u8",
        "main.sysl"    -> "print(os.environ == null, sys.environ == null)",
      )

      out.linesIterator.count(_.startsWith("@environ = external global")) shouldBe 1
      out should not include "@os.environ"
    }

    "a private one is refused where a public one is reached" in {
      errOf(
        "os/env.sysl" -> "module os\n\nprivate extern environ: **u8",
        "main.sysl"   -> "print(os.environ == null)",
      ) should include("environ")
    }
  }

  /** What the neighbouring rules say about storage, asked of the one storage they do not own. */
  "the rules around it, asked rather than assumed" - {
    // `reference/ffi.md § extern — a declaration with no body`'s declarations are top-level forms, and the message that says so lists them.
    "it is a top-level declaration, like every other extern" in {
      err("f() -> int\n    extern optind: i32\n    1\nprint(f())") should
        include("may only be declared at the top level")
    }

    // `reference/modules.md § Capabilities are a module property`: what a C function does is not
    // this compiler's to know, so an `extern` is not followed. Naming C's storage allocates nothing
    // either, and a `no alloc` module may do it.
    "a 'no alloc' module may name one, because reading C's storage allocates nothing" in {
      irOf(
        "drv/a.sysl" -> "module drv\n@no_alloc\n\nextern optind: i32\n\nstep()\n    optind = 1i32\n",
        "main.sysl"  -> "drv.step()\nprint(1)",
      ) should include("@optind = external global i32")
    }

    // An aggregate type is the case the emission order exists for: `%struct.Pair` is opaque until its
    // `= type` line, so a declaration naming it above that line would not lower at all.
    "an aggregate one is declared after the type it names" in {
      val out = ir(
        """struct Pair
          |    a: i32
          |    b: i32
          |extern the_pair: Pair
          |print(the_pair.a)""".stripMargin,
      )

      out should include("%struct.Pair = type { i32, i32 }")
      out should include("@the_pair = external global %struct.Pair")
      out.indexOf("%struct.Pair = type") should be < out.indexOf("@the_pair = external global")
    }

    // An array is storage with elements, and an element of it is a place — which is the `val`'s own
    // rule (`reference/modules.md § val — a thing`) with the one difference this declaration has: the element may be written.
    "an array one is indexed, and its elements are places" in {
      val out = ir(
        """extern tab: [4]i32
          |tab[0] = 7i32
          |print(tab[1])""".stripMargin,
      )

      out should include("@tab = external global [4 x i32]")
      out should include("getelementptr i32, ptr @tab, i64 0")
      out should include("store i32 7, ptr")
      // The declared length is what the subscript is checked against, exactly as an array of this
      // program's own is — the length is in the type, and the type is what was written.
      out should include("icmp ult i64 0, 4")
    }

    "a local of the same name shadows it, as a local shadows any module member" in {
      run("extern optind: i32\nvar optind = 5\nprint(optind)") shouldBe "5\n"
    }

    // Two files of one module are one unordered set of members (`reference/modules.md § The module
    // graph is acyclic`), so a second declaration of the same name is a duplicate wherever it was
    // written.
    "and two files of one module may not both declare it" in {
      errOf(
        "a.sysl"    -> "module os\n\nextern environ: **u8",
        "b.sysl"    -> "module os\n\nextern environ: **u8",
        "main.sysl" -> "print(1)",
      ) should include("'environ' is already declared")
    }
  }

  /** What being a *place* rather than a `val` reaches, asked of each thing a place can do. Every one
    * of these is a rule written for storage this program owns, met by storage it does not.
    */
  "everything a place does, it does" - {
    // Run rather than read, because what is being asked is that each form reaches the *same* storage:
    // an increment that read one address and stored to another would still emit a plausible pair of
    // instructions.
    "a compound assignment, and an increment, both reaching the one storage" in {
      run("extern optind: i32\noptind = 1i32\noptind += 4i32\noptind++\nprint(optind)") shouldBe "6\n"
    }

    // `reference/modules.md § val — a thing` slices a `val` to a `[]const T` because the read-only promise has to travel with the
    // view. There is no such promise here, so the view is the ordinary writable one — asserted by
    // *writing through it*, since that is the whole difference between the two types.
    "slicing yields a view that may be written, where a 'val's yields one that may not" in {
      err("static val t: [4]i32 = [0i32, 0i32, 0i32, 0i32]\nvar v = t[0..<2]\nv[0] = 1i32\nprint(v[0])") should
        include("views elements it may not write")
      ir("extern tab: [4]i32\nvar v = tab[0..<2]\nv[0] = 1i32\nprint(v[0])") should include("define")
    }

    "iterating one walks its elements" in {
      ir("extern tab: [4]i32\nvar n = 0i32\nfor x in tab\n    n += x\nprint(n)") should
        include("@tab = external global [4 x i32]")
    }

    "and a field of an aggregate one is written through" in {
      val out = ir(
        """struct Pair
          |    a: i32
          |    b: i32
          |extern the_pair: Pair
          |the_pair.a = 3i32
          |print(the_pair.b)""".stripMargin,
      )

      out should include("getelementptr %struct.Pair, ptr @the_pair, i32 0, i32 0")
      out should include("store i32 3, ptr")
    }

    // Its address outlives every frame, so a function may hand one back with nothing promoted and
    // nothing to decide — which is what `05` says about anything that is not a local.
    "its address outlives every frame, so a function may hand one back" in {
      run("extern optind: i32\nwhere_is() -> *i32 = &optind\noptind = 9i32\nprint(*where_is())") shouldBe
        "9\n"
    }

    "and it renders like any other value of its type" in {
      run("extern optind: i32\noptind = 12i32\nprint(f\"[${optind}%4d]\")") shouldBe "[  12]\n"
    }
  }

  /** Claims the neighbouring chapters make, asked of the declaration rather than assumed of it. */
  "what the chapters around it claim" - {
    // `reference/ffi.md § What crosses the boundary`: what crosses the boundary is the programmer's business — a `string` or a `&T` is a
    // sysl layout C has no notion of, and handing one over is the same promise `*T` already is. That
    // sentence is about parameters, and this asks whether it holds for storage: it does, and the
    // consequence is that a nonsense declaration compiles rather than being singled out here.
    "a sysl layout is the author's business here as it is at a parameter" in {
      ir("extern s: string\nprint(s == \"\")") should
        include("@s = external global { ptr, ptr, i64 }")
    }

    // `reference/modules.md § Separate compilation`: a sysl definition is mangled with its module
    // path and an `extern` is the exception, emitting the raw C symbol. Asked of a *library*
    // module, which is where a key most differs from a symbol — `sysl.io$lines` against a bare
    // `environ`.
    "a symbol carries no module even where the key is a library one" in {
      val out = irAgainstTree(
        ("sysl", "std.sysl", "module sysl\nextern environ: **u8\nmark(n: int) -> int = n + 1"),
      )("main.sysl" -> "mark(if environ == null then 0 else 1)")

      out should include("@environ = external global ptr")
      out should not include s"@${Modules.qualify("sysl", "environ")}"
    }

    // `reference/modules.md § Capabilities are a module property`: an `extern` is not followed at
    // all, because what a C function does is not this compiler's to know. Storage is the same
    // question with a smaller answer — naming it does nothing at all — so a `no alloc` module
    // writing one is not a narrowing it broke.
    "a 'no alloc' module writing one is not a narrowing it broke" in {
      irOf(
        "drv/a.sysl" -> "module drv\n@no_alloc\n\nextern optind: i32\n\nreset()\n    optind = 1i32\n",
        "main.sysl"  -> "drv.reset()\nprint(1)",
      ) should include("store i32 1, ptr @optind")
    }

    /** `reference/modules.md § val — a thing`: a module-level `val` may be *computed*, filled by code that runs before the program's
      * own statements. One filled from C's storage is the case that asks whether the ordering means
      * anything here — and it does not: what fills `environ` is the loader, which ran before this
      * program's first instruction, so the read is ordered by the platform rather than by `reference/modules.md § val — a thing`.
      */
    "a computed 'val' may be filled from one, and the loader has already filled it" in {
      run("extern optind: i32\nstatic val start: i32 = optind\nprint(start)") shouldBe "1\n"
    }

    // `reference/attributes.md § What is dropped, and when`: a test build drops the entry point and dispatches to the `@test` functions
    // instead, so what a test reaches is reached from a different root. An extern variable read only
    // from a test still has to be declared, and the storage read is still C's.
    "a '@test' build declares one that only a test reads" in {
      val src =
        """extern optind: i32
          |
          |@test("a write to C's storage is read back from it")
          |round_trips() =
          |    optind = 7i32
          |    assert(optind == 7i32, "optind")
          |""".stripMargin

      testIr(src) should include("@optind = external global i32")
      allPass(src)
    }

    /** `reference/modules.md § Visibility`: a declaration may not be more visible than the types it
      * names. It reaches this one exactly as it reaches a function — a module that may write
      * `os.there` would hold a `Hidden` it cannot write, which is the hole the rule exists to close
      * and not a smaller one.
      *
      * `VisibilityTests` is where the rule itself lives, over every form a declaration takes. What is
      * asked here is only that this declaration is among them.
      */
    "a public one may not name a private type, exactly as a public function may not" in {
      val hidden = "module os\n\nprivate struct Hidden\n    a: i32\n"

      errOf(
        "os/env.sysl" -> (hidden + "\nget() -> Hidden = Hidden(1)"),
        "main.sysl"   -> "print(1)",
      ) should include("'get' is public, but its result names 'os.Hidden'")

      errOf(
        "os/env.sysl" -> (hidden + "\nextern there: Hidden"),
        "main.sysl"   -> "print(1)",
      ) should include("'there' is public, but its type names 'os.Hidden'")

      // The exemption both share: restricted to the file that declares the type, there is nobody who
      // could hold the value and be unable to name it.
      irOf(
        "os/env.sysl" -> (hidden + "\nprivate extern there: Hidden"),
        "main.sysl"   -> "print(1)",
      ) should include("define")
    }
  }

  /** The point of the whole feature: a real C global, really resolved, really read. */
  "reaching a C global that is actually there" - {
    /** `stdout` is the one that mattered, and it is also the case the link name was made for: in C
      * it is a *macro*, and what the linker has under it is the platform's business. A program
      * transcribing the header's spelling reaches nothing, and the declaration is what says so.
      *
      * **The two platforms disagree, which is the point rather than an inconvenience.** macOS
      * exports `__stdoutp`; glibc exports `stdout` itself. Writing either one unconditionally makes
      * the test a claim about a machine instead of about the feature, and this suite ran on macOS
      * alone for long enough that the Darwin spelling read as *the* spelling. `#if` is exactly the
      * construct a program uses for this, so the fixture uses it — which also means the fixture
      * demonstrates the feature it depends on.
      *
      * The stream only interleaves with `print` in the order written if it really is libc's own —
      * the library renders through `putchar`, which writes to that same stream, so a stand-in opened
      * with `fdopen` would buffer separately and reorder this output.
      */
    "stdout is libc's own stream, in the order print writes to it" in {
      val src =
        """#if macos
          |extern "__stdoutp" stdout: *u8
          |#else
          |extern stdout: *u8
          |#endif
          |extern fputs(s: *u8, stream: *u8) -> i32
          |print("one")
          |fputs(c"two\n", stdout)
          |print("three")""".stripMargin

      run(src) shouldBe "one\ntwo\nthree\n"
    }

    "and stderr is a different stream, so what goes there is not what came back" in {
      val src =
        """#if macos
          |extern "__stderrp" stderr: *u8
          |#else
          |extern stderr: *u8
          |#endif
          |extern fputs(s: *u8, stream: *u8) -> i32
          |fputs(c"to stderr\n", stderr)
          |print("to stdout")""".stripMargin

      run(src) shouldBe "to stdout\n"
    }

    // The one with no workaround at all: `environ` is not exposed as a getter anywhere. Walking it
    // to the null terminator only stops because the address is the one the loader filled in, and
    // every entry holding an `=` is a property of the real environment rather than of any memory.
    "environ is a null-terminated vector of NAME=VALUE, walked to its end" in {
      val src =
        """extern environ: **u8
          |var n = 0
          |var all_named = true
          |while environ[n] != null
          |    var e = environ[n]
          |    var i = 0
          |    var named = false
          |    while e[i] != 0u8
          |        if e[i] == 61u8 then named = true
          |        i++
          |    if !named then all_named = false
          |    n++
          |print(n > 0, all_named)""".stripMargin

      run(src) shouldBe "true true\n"
    }

    // A link name reaching the *linker* rather than just the emitted text, which is only settled by
    // a program that links and runs.
    "a link name on a variable resolves to the C symbol it names" in {
      val src =
        """extern "environ" the_env: **u8
          |print(the_env != null, the_env[0] != null)""".stripMargin

      run(src) shouldBe "true true\n"
    }

    // Writing one, which is what `optind` is for: `getopt` reads it back, so a write that did not
    // reach the real storage would leave the parse where it started.
    "optind is written, and getopt reads back what was written" in {
      val src =
        """extern optind: i32
          |extern getopt(argc: i32, argv: **u8, opts: *u8) -> i32
          |var argv: [3]*u8 = [c"prog", c"-a", null]
          |optind = 1i32
          |var first = getopt(2i32, &argv[0], c"a")
          |print(first == 97i32, optind)""".stripMargin

      // `getopt` returns the option character 'a' and steps `optind` past the argument it read.
      run(src) shouldBe "true 2\n"
    }
  }

}
