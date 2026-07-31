package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** The capability clause `capabilities.md` and `13 §4` specify and nothing implements.
 *
 * Both documents describe it at length — `no alloc` narrows a module below its target and is called
 * "the enforcement lever"; `requires alloc` declares what a module cannot do without — so a reader
 * writes one and, until this rule existed, was answered with "newline expected" at the head of the
 * line the design told them to write. The words were already reserved, which is why the clause had
 * nowhere to go and no way to say so.
 *
 * This is the shape `noVisibility` uses: match what the design specifies, refuse it with the reason.
 */
class CapabilityClauseTests extends AnyFreeSpec with RunSupport with CodegenSupport {

  "a capability clause is refused with what is true of it, not with a parse error" - {

    "'no alloc', the narrowing form" in {
      val e = err("module thing\nno alloc\n\nf() -> int = 1\n")

      e should include("a module's capability clause is designed but not built")
      e should include("'capabilities.md' and '13 §4'")
      e shouldNot include("newline expected")
    }

    "'requires alloc', the declaring form" in {
      err("module thing\nrequires alloc\n\nf() -> int = 1\n") should
        include("a module's capability clause is designed but not built")
    }

    "and a capability whose name is not reserved reaches the same refusal" in {
      // `os`, `posix` and `threads` lex as ordinary identifiers where `alloc` does not.
      err("module thing\nrequires posix\n\nf() -> int = 1\n") should
        include("a module's capability clause is designed but not built")
    }
  }

  "a module with no clause is unaffected" in {
    runOf("thing/a.sysl" -> "module thing\n\nf() -> int = 1\n", "main.sysl" -> "print(thing.f())") shouldBe "1\n"
  }
}
