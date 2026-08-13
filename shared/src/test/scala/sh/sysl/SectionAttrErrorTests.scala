package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** What `@section("…")` refuses: a declaration that occupies no address, a binding whose storage is
 * the frame, and the two ways of writing the attribute without naming a section.
 */
class SectionAttrErrorTests extends AnyFreeSpec with CodegenSupport {

  "the section has to be named" - {
    "there is no bare form" in {
      err("@section\nstatic var n: u32 = 0u32\nprint(n)") should include("names the linker section")
    }

    "nor an empty pair of parentheses" in {
      err("@section()\nstatic var n: u32 = 0u32\nprint(n)") should include("names the linker section")
    }

    "the name is a string, as an exported symbol's is" in {
      err("@section(vectors)\nstatic var n: u32 = 0u32\nprint(n)") should
        include("names the linker section")
    }

    // Every other spelling belongs to some target. `""` belongs to none, and it is the one thing the
    // string form checks — nothing else about what is in it is sysl's business.
    "and \"\" is not the name of one" in {
      err("@section(\"\")\nstatic var n: u32 = 0u32\nprint(n)") should
        include("is not the name of one")
    }
  }

  "it marks something that occupies an address" - {
    "a 'const' is folded into every use and has none" in {
      err("@section(\".rodata\")\nconst N: int = 4\nprint(N)") should include("places one object")
    }

    "a struct is a type rather than an object" in {
      err("@section(\".data\")\nstruct S\n    a: int\n") should include("places one object")
    }

    "an 'extern' names storage this program does not lay down" in {
      err("@section(\".data\")\nextern errno: int\nprint(errno)") should include("places one object")
    }
  }

  /** A local and module storage are the same syntax and differ only in where they stand (`13 §7`),
   * so this is refused by the analyzer rather than by the grammar — and the message is about the
   * frame, which is the fact that explains it.
   */
  "a local's storage is the frame, which no section is about" - {
    "a 'var' inside a function" in {
      val e = err("f() -> int\n    @section(\".noinit\")\n    var n: int = 1\n    n\n\nprint(f())")

      e should include("is a local")
      e should include("section \".noinit\"")
    }

    "a 'val' inside a function" in {
      err("f() -> int\n    @section(\".noinit\")\n    val n: int = 1\n    n\n\nprint(f())") should
        include("is a local")
    }

    // The file a program starts in is the case worth pinning: a top-level `var` there is a local of
    // the entry point, which is exactly why the grammar cannot answer this.
    "and a top-level 'var' in the file the program starts in, which is one" in {
      err("@section(\".noinit\")\nvar n: int = 1\nprint(n)") should include("is a local")
    }
  }

  "it does not stand beside an attribute about something else" - {
    "'@packed' lays out a type and this places an object" in {
      err("@packed\n@section(\".data\")\nstruct S\n    a: int\n") should
        include("cannot stand above one declaration")
    }

    "'@align' beside it is a binding, and only a binding" in {
      err("@align(16)\n@section(\".data\")\nstruct S\n    a: int\n") should include("marks one binding")
    }

    "a binding that names several has no one object for either to be about" in {
      err("@section(\".data\")\nstatic var a, b = 1, 2\nprint(a)") should include("about one object")
    }
  }

  "one written twice is refused, as any repeated attribute is" in {
    err("@section(\".a\")\n@section(\".b\")\nstatic var n: u32 = 0u32\nprint(n)") should
      include("written twice")
  }
}
