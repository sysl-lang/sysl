package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** `@needs(...)` — a **declaration** naming what reaching it requires
 * (`reference/modules.md § A declaration may name what reaching it needs`).
 *
 * It is the finer half of the capability clause. A file header's `@requires(...)` is about the
 * **module** and is checked once against the target; this is about one declaration and is checked at
 * the **call**, in the caller's module, which is where the line a reader can change is.
 *
 * **The declaration it exists for is `extern`**, and that is what most of this suite is about. Every
 * other declaration has a body the compiler reads — `NoAlloc` finds an allocation by looking — so an
 * `extern` was the one route by which a module that had given up an environment capability could
 * reach `open()`. Nothing but the declaration itself can close that.
 */
class DeclCapabilityTests extends AnyFreeSpec with RunSupport with CodegenSupport {

  "an extern may name the capability calling it needs" - {

    "and a module that gave that capability up may not reach it" in {
      errOf(
        "sys/a.sysl" -> "module sys\n\n@needs(os)\nextern \"getpid\" pid() -> int\n",
        "main.sysl"  -> "@no_os\n\nprint(sys.pid())\n",
      ) should include("this reaches 'sys.pid', which needs 'os', and this module declared '@no_os'")
    }

    "while a module that said nothing reaches it exactly as before" in {
      irOf(
        "sys/a.sysl" -> "module sys\n\n@needs(os)\nextern \"getpid\" pid() -> int\n",
        "main.sysl"  -> "print(sys.pid())\n",
      ) should include("declare i32 @getpid()")
    }

    // The half with 46 files behind it: `no alloc` is checked at every construction that makes heap
    // storage, and an `extern` that allocates walks straight past that.
    "the heap is the same rule, and it is the case a module clause could not see" in {
      errOf(
        "sys/a.sysl" -> "module sys\n\n@needs(heap)\nextern \"malloc\" grab(n: usize) -> *u8\n",
        "main.sysl"  -> "@no_alloc\n\nval p = sys.grab(8)\nprint(p == null)\n",
      ) should include("this reaches 'sys.grab', which needs 'heap'")
    }

    "and a `private` extern is reached through the wrapper that exports it, which is where the caret goes" in {
      errOf(
        "sys/a.sysl" -> ("module sys\n\n@needs(os)\nprivate extern \"getpid\" c_pid() -> int\n\n" +
          "pid() -> int = c_pid()\n"),
        "main.sysl"  -> "@no_os\n\nprint(sys.pid())\n",
      ) should include("which needs 'os'")
    }
  }

  "the requirement is transitive, because reaching is" - {

    "through a function of the caller's own" in {
      errOf(
        "sys/a.sysl" -> "module sys\n\n@needs(os)\nextern \"getpid\" pid() -> int\n",
        "main.sysl"  -> "@no_os\n\nask() -> int = sys.pid()\n\nprint(ask())\n",
      ) should include("which needs 'os'")
    }

    "and through a third module that has the capability itself" in {
      errOf(
        "sys/a.sysl" -> "module sys\n\n@needs(os)\nextern \"getpid\" pid() -> int\n",
        "mid/a.sysl" -> "module mid\n\nask() -> int = sys.pid()\n",
        "main.sysl"  -> "@no_os\n\nprint(mid.ask())\n",
      ) should include("which needs 'os'")
    }
  }

  "a capability implies what it rests on" - {

    // POSIX needs an operating system under it, so a declaration needing `posix` needs `os` — and a
    // module that gave up only `os` is refused, naming the capability it actually lacks.
    "so `@needs(posix)` is out of reach of a module that gave up `os` alone" in {
      errOf(
        "sys/a.sysl" -> "module sys\n\n@needs(posix)\nextern \"getpid\" pid() -> int\n",
        "main.sysl"  -> "@no_os\n\nprint(sys.pid())\n",
      ) should include("which needs 'os'")
    }
  }

  "an ordinary function may carry it too" - {

    "which is the granularity a module clause could not give a library" in {
      errOf(
        "sys/a.sysl" -> "module sys\n\n@needs(os)\nnow() -> int = 7\n\nplain() -> int = 3\n",
        "main.sysl"  -> "@no_os\n\nprint(sys.now())\n",
      ) should include("which needs 'os'")
    }

    "and the declaration beside it, which said nothing, is reached freely" in {
      runOf(
        "sys/a.sysl" -> "module sys\n\n@needs(os)\nnow() -> int = 7\n\nplain() -> int = 3\n",
        "main.sysl"  -> "@no_os\n\nprint(sys.plain())\n",
      ) shouldBe "3\n"
    }
  }

  "what the annotation may say" - {

    "a capability nothing has heard of is refused, in the words a file header's is" in {
      err("@needs(sockets)\nf() -> int = 1\n\nprint(f())\n") should
        include("no capability is called 'sockets'")
    }

    // The mistake somebody makes having just read `@no_alloc` beside it: `alloc` is what a module
    // *does*, and a requirement names the facility.
    "and the narrowing's own word is answered by naming the facility" in {
      err("@needs(alloc)\nf() -> int = 1\n\nprint(f())\n") should
        include("a '@needs' names the facility, so this is '@needs(heap)'")
    }

    "the parentheses are mandatory and may not be empty" in {
      err("@needs\nf() -> int = 1\n\nprint(f())\n") should
        include("names the capabilities reaching this declaration requires, in parentheses")
    }

    "so is the list inside them" in {
      err("@needs()\nf() -> int = 1\n\nprint(f())\n") should
        include("There is no empty form")
    }

    "and it marks a function or an 'extern', not a struct" in {
      err("@needs(os)\nstruct P\n    x: int\n\nprint(1)\n") should
        include("so it marks a function or an 'extern'")
    }
  }

  /** A declaration that dropped the annotation on the way through an archive would be a capability
    * requirement that held inside the library and nowhere else — the check it asks for is made at
    * the **call**, and the calls an artifact is read for are all in the consumer.
    */
  "it travels in a library artifact, because the calls it governs are in the consumer" in {
    val src = "module sys\n\n@needs(os)\nextern \"getpid\" pid() -> int\n\n" +
      "@needs(heap, posix)\nnow() -> int = 1\n"

    val parsed = SyslParser.parse(Source("<t>", src)) match
      case Right(p) => p
      case Left(e)  => fail(s"the fixture does not parse: $e")

    val back = AstCodec.decode(AstCodec.encode(List(parsed)), Map.empty) match
      case Right(ps) => ps.head
      case Left(e)   => fail(s"decode failed: $e")

    back.body.collect { case e: ExternDecl => e.needs } shouldBe List(List("os"))
    back.body.collect { case f: FuncDecl => f.needs } shouldBe List(List("heap", "posix"))
  }
}
