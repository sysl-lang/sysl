package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** The library as a **tree of modules** rather than one flat module (`13 §1`, `13 §3`).
 *
 * A module is a directory, so a submodule of the standard one is a directory under `lib/sysl` and
 * needs nothing new from the compiler to exist. What it does need is for the standard module's
 * privileges to stop at the standard module: the names in scope everywhere with no import are the
 * ones the language desugars onto, and a submodule is an offer like any other library's, reached by
 * naming it or importing it.
 *
 * These are asked of a stand-in library because the real one is still a single module — which is
 * exactly the position that leaves the question unaskable, and is how the flat library's own
 * assumptions got written into the compiler in the first place.
 */
class LibraryTreeTests extends AnyFreeSpec with Matchers with CodegenSupport {

  // A library in two modules: the standard one, and a submodule holding something a program has to
  // ask for. `flag` and `mark` differ so that which one a name reached is visible in the IR.
  private val tree =
    Seq(
      ("sysl", "core.sysl",
       """module sysl
         |mark(n: int) -> int = n + 1
         |""".stripMargin),
      ("sysl.sys", "sys.sysl",
       """module sysl.sys
         |flag(n: int) -> int = n * 2
         |""".stripMargin),
    )

  // The same spelling declared in both library modules, for the question of which one a bare name
  // reaches.
  private val both =
    Seq(
      ("sysl", "core.sysl", "module sysl\npick(n: int) -> int = n + 1"),
      ("sysl.sys", "sys.sysl", "module sysl.sys\npick(n: int) -> int = n * 2"),
    )

  // A submodule holding something only the rest of the library may name.
  private val kept =
    Seq(
      ("sysl", "core.sysl", "module sysl\nmark(n: int) -> int = sysl.sys.hold(n)"),
      ("sysl.sys", "sys.sysl", "module sysl.sys\nprivate[sysl] hold(n: int) -> int = n * 2"),
    )

  private def sysKey(name: String): String = Modules.qualify("sysl.sys", name)

  "where a carried file sits" - {
    // The generator writes each file's name as the path below `lib`, and the directory it says the
    // file is in is what its header is held to. Deriving it is the whole of what lets the library be
    // a tree, and it is a string question, so it is asked as one.
    "is the directory between the library root and it" in {
      Std.directoryOf("lib/sysl/print.sysl") shouldBe List("sysl")
    }

    "which for a submodule's file is the path down to it" in {
      Std.directoryOf("lib/sysl/sys/args.sysl") shouldBe List("sysl", "sys")
    }

    "however deep it goes" in {
      Std.directoryOf("lib/sysl/text/utf8/decode.sysl") shouldBe List("sysl", "text", "utf8")
    }
  }

  "a submodule of the library" - {
    "does not put its names in scope for nothing" in {
      errAgainstTree(tree*)(
        "main.sysl" -> "flag(21)",
      ) should include("undefined function 'flag'")
    }

    "while the standard module's names still arrive unasked-for" in {
      irAgainstTree(tree*)(
        "main.sysl" -> "mark(21)",
      ) should include(s"call i32 @${Library.key("mark")}")
    }

    "is reached by naming it, with nothing imported" in {
      irAgainstTree(tree*)(
        "main.sysl" -> "sysl.sys.flag(21)",
      ) should include(s"call i32 @${sysKey("flag")}")
    }

    "or by importing it" in {
      irAgainstTree(tree*)(
        "main.sysl" -> "import sysl.sys.flag\nflag(21)",
      ) should include(s"call i32 @${sysKey("flag")}")
    }

    "or by a wildcard over it" in {
      irAgainstTree(tree*)(
        "main.sysl" -> "import sysl.sys.*\nflag(21)",
      ) should include(s"call i32 @${sysKey("flag")}")
    }

    // The terser rule would let a program write `sys.flag`, and it is not the one in force: a
    // submodule is named by its path, so `text`, `io` and `sys` stay a program's own words. Pinned
    // because it is a decision rather than an accident of how `modulePath` happens to be written.
    "and not by its last segment alone" in {
      errAgainstTree(tree*)(
        "main.sysl" -> "sys.flag(21)",
      ) should include("undefined name 'sys'")
    }

    // A submodule shares the library's key space exactly as the standard module does, so a program
    // adding to it would be adding to the library rather than writing a module of its own.
    "which a program may not declare itself, any more than it may declare the standard one" in {
      errAgainstTree(tree*)(
        "mine.sysl" -> "module sysl.sys\nflag(n: int) -> int = n",
        "main.sysl" -> "1",
      ) should include("'sysl.sys' is the module every program is compiled against")
    }
  }

  // The standard module's step is the one a bare name reaches, so a submodule declaring the same
  // spelling takes nothing away from it — which is what makes a submodule safe to add.
  "a name in two library modules" - {
    "resolves bare to the standard module's" in {
      irAgainstTree(both*)(
        "main.sysl" -> "pick(21)",
      ) should include(s"call i32 @${Library.key("pick")}")
    }

    "and the submodule's is still there for a program that names it" in {
      irAgainstTree(both*)(
        "main.sysl" -> "sysl.sys.pick(21)",
      ) should include(s"call i32 @${sysKey("pick")}")
    }
  }

  "the library's own files" - {
    // Library files used to share one scope standing for the whole library, which was exact while
    // there was one module and says the wrong thing the moment there are two: a file of `sysl.sys`
    // naming something in `sysl.text` is naming another module's declaration, and says so the way
    // any other file would.
    "reach the standard module's names with no import, wherever in the tree they are" in {
      irAgainstTree(
        ("sysl", "core.sysl", "module sysl\nmark(n: int) -> int = n + 1"),
        ("sysl.sys", "sys.sysl", "module sysl.sys\nflag(n: int) -> int = mark(n) * 2"),
      )(
        "main.sysl" -> "sysl.sys.flag(21)",
      ) should include(s"call i32 @${Library.key("mark")}")
    }

    "reach a sibling submodule's by importing it" in {
      irAgainstTree(
        ("sysl", "core.sysl", "module sysl\nmark(n: int) -> int = n + 1"),
        ("sysl.text", "t.sysl", "module sysl.text\nwiden(n: int) -> int = n + 7"),
        ("sysl.sys", "sys.sysl", "module sysl.sys\nimport sysl.text.widen\nflag(n: int) -> int = widen(n)"),
      )(
        "main.sysl" -> "sysl.sys.flag(21)",
      ) should include(s"call i32 @${Modules.qualify("sysl.text", "widen")}")
    }

    "and not without one" in {
      errAgainstTree(
        ("sysl", "core.sysl", "module sysl\nmark(n: int) -> int = n + 1"),
        ("sysl.text", "t.sysl", "module sysl.text\nwiden(n: int) -> int = n + 7"),
        ("sysl.sys", "sys.sysl", "module sysl.sys\nflag(n: int) -> int = widen(n)"),
      )(
        "main.sysl" -> "sysl.sys.flag(21)",
      ) should include("undefined function 'widen'")
    }

    // Writing a free name is a reference like any other, so a submodule using one depends on `sysl`
    // — which is inert for a program, since nothing in the library can point back at one, and is the
    // whole constraint on the library's own layout. It is what decides that a module `sysl` reaches
    // holds only what needs nothing.
    "and a submodule the standard module reaches may not reach back, even through a free name" in {
      errAgainstTree(
        ("sysl", "core.sysl", "module sysl\nimport sysl.sys.flag\nmark(n: int) -> int = flag(n)"),
        ("sysl.sys", "sys.sysl", "module sysl.sys\nflag(n: int) -> int = mark(n) * 2"),
      )(
        "main.sysl" -> "mark(21)",
      ) should include("modules may not depend on each other")
    }
  }

  "what a submodule keeps to itself" - {
    "is out of reach of a program that names it" in {
      errAgainstTree(kept*)(
        "main.sysl" -> "sysl.sys.hold(21)",
      ) should include("'sysl.sys.hold' is private to module 'sysl'")
    }

    // `private[sysl]` names an ancestor of `sysl.sys`, so the rest of the library is inside it
    // (`13 §2`) — which is what makes a submodule a place to put the library's own workings rather
    // than a second public surface.
    "and reachable from the rest of the library, which is what the modifier says" in {
      irAgainstTree(kept*)(
        "main.sysl" -> "mark(21)",
      ) should include(s"call i32 @${sysKey("hold")}")
    }
  }

  "the edges of the arrangement" - {
    // A submodule is a directory and nothing more, so nothing stops at one level. Pinned because
    // every part of the machinery — the generator's walk, the derivation of a file's module, the
    // path a program writes — is written once and would be just as green if it only ever handled
    // one segment.
    "a submodule of a submodule is reached the same way" in {
      irAgainstTree(
        ("sysl", "core.sysl", "module sysl\nmark(n: int) -> int = n + 1"),
        ("sysl.text.utf8", "d.sysl", "module sysl.text.utf8\ndecode(n: int) -> int = n * 3"),
      )(
        "main.sysl" -> "sysl.text.utf8.decode(7)",
      ) should include(s"call i32 @${Modules.qualify("sysl.text.utf8", "decode")}")
    }

    // `import a.b.c` binds the last segment as the module's short name, which is a rule about
    // imports rather than about the library — so the library's submodules get it too, and the terse
    // spelling a program cannot write unasked-for is one it can ask for.
    "an imported submodule is named by its last segment" in {
      irAgainstTree(tree*)(
        "main.sysl" -> "import sysl.sys\nsys.flag(21)",
      ) should include(s"call i32 @${sysKey("flag")}")
    }

    // The wildcard offers only what is visible from where it was written (`13 §3`), and a submodule
    // is where a library keeps what it does not offer — so the two meet here rather than in theory.
    //
    // "Undefined" and not the restriction, which is what an ordinary module's wildcard says too: a
    // wildcard passes over what it cannot see rather than offering it and refusing. The standard
    // module answers the other way for the same name (`VisibilityTests`) because it is reached at a
    // step of its own where the name *is* a candidate — a submodule has no such step, which is the
    // point of it being a submodule.
    "a wildcard over a submodule does not offer what it keeps" in {
      errAgainstTree(kept*)(
        "main.sysl" -> "import sysl.sys.*\nhold(21)",
      ) should include("undefined function 'hold'")
    }

    "while naming it in a selector is refused where the selector is written" in {
      errAgainstTree(kept*)(
        "main.sysl" -> "import sysl.sys.hold\nhold(21)",
      ) should include("'sysl.sys.hold' is private to module 'sysl'")
    }

    // A scope argument is one segment, resolved outward from the declaration (`13 §2`), so a
    // submodule names itself by its own last segment — `private[sys]` inside `sysl.sys`. That makes
    // `sysl` *outside* it: the ancestor direction does not run both ways, and a submodule can keep
    // something from the standard module as well as the other way round.
    "a submodule may keep something from the standard module too" in {
      errAgainstTree(
        ("sysl", "core.sysl", "module sysl\nmark(n: int) -> int = sysl.sys.hold(n)"),
        ("sysl.sys", "sys.sysl", "module sysl.sys\nprivate[sys] hold(n: int) -> int = n * 2"),
      )(
        "main.sysl" -> "mark(21)",
      ) should include("'sysl.sys.hold' is private to module 'sysl.sys'")
    }

    "while `private[sysl]` on the same declaration reaches the whole library, which is the widening" in {
      irAgainstTree(kept*)(
        "main.sysl" -> "mark(21)",
      ) should include(s"call i32 @${sysKey("hold")}")
    }

    // A program's own directory tree makes its own module names, and a module of the library is
    // refused only where the library actually carries it — so a name that merely *looks* like one
    // of the library's is the program's to use.
    "a program may declare a module under a library name the library does not carry" in {
      irAgainstTree(tree*)(
        "mine.sysl" -> "module sysl.other\nflag(n: int) -> int = n + 5",
        "main.sysl" -> "sysl.other.flag(21)",
      ) should include(s"call i32 @${Modules.qualify("sysl.other", "flag")}")
    }
  }

  // The real library, which is what all of the above was for. What has left the set every program
  // gets for free: the four C functions the printing and reading are built on, the argument
  // conversion nearly nobody calls, and the whole of the reading surface.
  "the standard library's own submodules" - {
    "keep the platform's C functions out of every program's namespace" in {
      errOf(
        "main.sysl" -> "var b: [4]u8 = [65u8, 66u8, 67u8, 0u8]\nvar p = sysl_memchr(&b[0], 65, 4usize)",
      ) should include("undefined function 'sysl_memchr'")
    }

    "and out of reach even where a program names the module they are in" in {
      errOf(
        "main.sysl" -> "var b: [4]u8 = [65u8, 66u8, 67u8, 0u8]\nvar p = sysl.sys.sysl_memchr(&b[0], 65, 4usize)",
      ) should include("'sysl.sys.sysl_memchr' is private to module 'sysl'")
    }

    "while the printing built on them is reached with no import at all" in {
      irOf("main.sysl" -> """print("hi")""") should include(s"call void @${Library.key("prints")}")
    }

    // Public, because a vector that is not the platform's is the only way to reach the failure at
    // all — but a name a program has to ask for, which is the difference a submodule makes.
    "leave the argument conversion reachable, under the path that names it" in {
      irOf(
        "main.sysl" -> "var v: [1]*u8 = [c\"x\"]\nvar a = sysl.args.args_of(1i32, &v[0])\nprint(a.len)",
      ) should include(s"call { ptr, ptr, i64 } @${Library.key("args_of")}")
    }

    "and not under a bare one" in {
      errOf(
        "main.sysl" -> "var v: [1]*u8 = [c\"x\"]\nvar a = args_of(1i32, &v[0])",
      ) should include("undefined function 'args_of'")
    }

    // The compiler names it by key rather than by resolving the word, so where it lives is the
    // library's business and not something a `main` had to be told about.
    "though a 'main' taking arguments still gets it without naming anything" in {
      irOf(
        "main.sysl" -> "main(args: []string)\n    print(args.len)\n",
      ) should include(s"@${Library.key("args_of")}(i32 %argc, ptr %argv)")
    }

    "and `exit` stays a word every program has, which is why it is not in `sys`" in {
      irOf("main.sysl" -> "exit(3)") should include("call void @exit(")
    }

    // Nothing the language desugars onto reads, so the whole input half is an offer: a program that
    // takes input says so, and one that does not never sees the names.
    "put the reading surface behind an import, so a program that reads says so" in {
      errOf("main.sysl" -> "var r = stdin()") should include("undefined function 'stdin'")
    }

    "which the path reaches without one" in {
      irOf(
        "main.sysl" -> "var r = sysl.io.stdin()\nprint(r.fd)",
      ) should include(s"call %struct.${Library.key("FdReader")} @${Library.key("stdin")}()")
    }

    "and an import reaches by the bare word again" in {
      irOf(
        "main.sysl" -> "import sysl.io.stdin\n\nvar r = stdin()\nprint(r.fd)",
      ) should include(s"call %struct.${Library.key("FdReader")} @${Library.key("stdin")}()")
    }

    // The trait too, which is what a program implements to be read through — and the one that says
    // the whole surface moved rather than just its entry points.
    "including the trait a program implements to be read from" in {
      errOf("main.sysl" -> "var r: *Reader = null") should include("unknown type 'Reader'")
    }

    // The direction §6 permits, demonstrated by the library compiling at all: `line_text` calls
    // `print` and `exit`, both of them the standard module's, and reaches them with no import
    // because a submodule gets the free names like any other file.
    "while the surface itself reaches the standard module's names freely" in {
      irOf(
        "main.sysl" -> "print(sysl.io.line_text(\"hi\".bytes))",
      ) should include(s"call { ptr, ptr, i64 } @${Library.key("line_text")}(")
    }

    // The conversions either side of a `string`, which a program that stays in `string` never needs.
    "put the text conversions behind an import too" in {
      errOf("main.sysl" -> "var s = from_utf8([0x61u8])") should include("undefined function 'from_utf8'")
    }

    "and the builder and the C copy with them" in {
      val e = errOf("main.sysl" -> "var b = str_builder()\nvar c = cstring(\"x\")")

      e should include("undefined function 'str_builder'")
      e should include("undefined function 'cstring'")
    }

    // One import reaches a whole file of them, `sysl.text` being two files and one module (`13 §1`).
    "which one import over the module reaches, however many files declared them" in {
      irOf(
        "main.sysl" -> "import sysl.text.*\n\nvar b = str_builder()\nb.push(\"x\")\nprint(b.finish(), cstring(\"y\").len)",
      ) should include(s"call %struct.${Library.key("StrBuilder")} @${Library.key("str_builder")}()")
    }

    // The exception, and the reason it is one: `.chars` is a member the compiler provides, so it
    // names `chars_of` by key rather than by resolving the word. A cursor over characters costs a
    // program nothing even though the cursor moved out of reach of its bare name.
    "while a character walk still costs a program no import at all" in {
      irOf(
        "main.sysl" -> """for c in "ab".chars do print(c)""",
      ) should include(s"call %struct.${Library.key("Chars")} @${Library.key("chars_of")}(")
    }

    "though naming the cursor itself does need one" in {
      errOf("main.sysl" -> "var c: Chars = \"ab\".chars") should include("unknown type 'Chars'")
    }

    // An array literal makes a `[]T` and a `for` walks anything that implements `Iterate`, so
    // nothing the language does reaches the growable sequence: a program that wants one asks.
    "put the growable sequence behind an import" in {
      errOf("main.sysl" -> "var b: Buf[int] = buf()") should include("unknown type 'Buf'")
    }

    "and the sink built on it, which is a program's only supplied Writer" in {
      errOf("main.sysl" -> "var g = byte_sink()") should include("undefined function 'byte_sink'")
    }

    "while an import reaches the generic, whose instantiation is keyed by the module it moved to" in {
      irOf(
        "main.sysl" -> "import sysl.buf.*\n\nvar b: Buf[int] = buf()\nb.push(7)\nprint(b[0usize])",
      ) should include(s"@${Library.key("Buf")}.push.int(")
    }

    // The sink could move because nothing the language does reaches it: a render lays out storage
    // the compiler chose, not a `ByteSink`. So a program may still make itself printable — naming
    // `Display`, `Writer`, `FormatSpec` and a renderer, all of them the standard module's — with
    // no import written at all, which is the half of `04`'s line that did *not* move.
    "while making a value printable still needs nothing named at all" in {
      irOf(
        "main.sysl" ->
          ("struct P\n    n: int\n\n" +
            "impl Display for P\n" +
            "    display(self, out: *Writer, fmt: FormatSpec) = display_str(\"p\", out, fmt)\n\n" +
            "print(f\"[${P(1)}%4s]\")\n"),
      ) should include(s"@${Library.key("display_str")}(")
    }
  }
}
