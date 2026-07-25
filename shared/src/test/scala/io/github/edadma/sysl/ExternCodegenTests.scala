package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Codegen for `extern` declarations and for the `never` type.
 *
 * An extern turns into a `declare` and its calls into ordinary calls, so what is worth asserting
 * is the two things codegen decides on its own: *which* externs are declared, and that a call to
 * one that does not return ends its basic block. The second is what lets a diverging `match` arm
 * work without any dataflow analysis — everything after the `unreachable` is dropped because the
 * block is closed.
 */
class ExternCodegenTests extends AnyFreeSpec with CodegenSupport {

  "declarations" - {
    "a called extern is declared with its lowered signature" in {
      ir("extern f(a: int, b: real, c: bool) -> u8\nprint(f(1, 2.0, true))") should
        include("declare i8 @f(i32, double, i1)")
    }

    "no result at all is declared void" in {
      ir("extern f()\nf()") should include("declare void @f()")
    }

    "a 'never' result is declared void too, since nothing comes back" in {
      ir("extern stop(code: int) -> never\nstop(1)") should include("declare void @stop(i32)")
    }

    "pointers and views lower as they do everywhere else" in {
      ir("extern f(p: *u8, s: string) -> *int\nvar b: u8 = 0\nprint(f(&b, \"hi\") == null)") should
        include("declare ptr @f(ptr, { ptr, ptr, i64 })")
    }

    // The prelude declares `exit` for `unwrap` to stop with, and almost no program calls it. An
    // extern nothing reaches must not reach the output either.
    "an unused extern is not declared" in {
      val out = ir("print(1)")

      out should not include "@exit"
      out should include("declare i32 @printf(ptr, ...)")
    }

    "an extern is declared once however many times it is called" in {
      val out = ir("extern f(n: int) -> int\nprint(f(1), f(2), f(3))")

      out.linesIterator.count(_.startsWith("declare i32 @f")) shouldBe 1
    }

    // The runtime declares `malloc` for its own use, and a module may not declare one symbol
    // twice — so the extern defers to the declaration that is already there.
    "an extern does not redeclare a name the runtime already declared" in {
      val src =
        """extern malloc(n: usize) -> *u8
          |struct Inner
          |    v: int
          |var r: &Inner = Inner(1)
          |print(r.v, malloc(8) == null)""".stripMargin
      val out = ir(src)

      out.linesIterator.count(l => l.startsWith("declare") && l.contains("@malloc(")) shouldBe 1
      out should include("declare ptr @malloc(i64)")
    }
  }

  "the prelude's forcing combinators" - {
    // They are members of a generic enum, so one exists per element type and no more — and a
    // program that never forces anything pays for none of it, nor for the `exit` they stop with.
    "unwrap is monomorphized once per element type, not once per call" in {
      val src =
        """var a: Option[int] = Some(1)
          |var b: Option[real] = Some(2.5)
          |var c: Option[int] = Some(3)
          |print(a.unwrap(), b.unwrap(), c.unwrap())""".stripMargin
      val out = ir(src)

      // Three calls, two element types.
      out.linesIterator.count(l => l.startsWith("define") && l.contains("@Option.unwrap")) shouldBe 2
      out should include("define i32 @Option.unwrap.int(")
      out should include("define double @Option.unwrap.real(")
      out should include("declare void @exit(i32)")
    }

    "unwrap and expect are separate members, each on demand" in {
      val src =
        """var a: Option[int] = Some(1)
          |print(a.unwrap(), a.expect("here"))""".stripMargin
      val out = ir(src)

      out should include("define i32 @Option.unwrap.int(")
      out should include("define i32 @Option.expect.int(")
    }

    "a program that forces nothing carries neither of them" in {
      val out = ir("var a: Option[int] = Some(1)\nprint(a.is_some())")

      out should not include "@Option.unwrap"
      out should not include "@Option.expect"
      out should not include "@exit"
    }
  }

  "a call that does not return" - {
    "ends its block with unreachable" in {
      val out = ir("extern stop() -> never\nstop()")

      out should include("call void @stop()")
      out should include("unreachable")
    }

    "a function whose body diverges needs no return" in {
      val out = ir("extern stop() -> never\nf() -> never = stop()\nf()")

      out should include("define void @f()")
      // The `ret` codegen would otherwise place is dropped: the block is already closed.
      out should not include "ret void\n}"
    }

    // A merge slot is a slot for a *value*, and `never` has none — asking for one would emit an
    // `alloca void`, which is not a type LLVM has.
    "an if whose branches both diverge allocates no slot" in {
      val out = ir("extern stop() -> never\nf(c: bool) -> never\n    if c then stop() else stop()\nf(true)")

      out should not include "alloca void"
      out should include("define void @f(i1 %c.param)")
    }

    "a match whose arms all diverge allocates no slot" in {
      val src =
        """extern stop() -> never
          |f(c: bool) -> never
          |    match c
          |        true -> stop()
          |        false -> stop()
          |f(true)""".stripMargin

      ir(src) should not include "alloca void"
    }

    // The diverging branch stores nothing, so the slot is written only on the paths that arrive.
    "a diverging branch stores nothing into the merge slot" in {
      val out = ir("extern stop() -> never\nvar x = if true then 5 else stop()\nprint(x)")

      out.linesIterator.count(_.contains("store i32 5")) shouldBe 1
      out should include("call void @stop()")
    }
  }

  // A `never`-typed expression must leave its block terminated — that invariant is what lets every
  // consumer of a value ignore the case where none arrives. For a call it falls out of the
  // `unreachable`; for an `if` or `match` whose every path leaves, the merge label has to say so
  // itself, or the `ret` that follows would be emitted with no value to return.
  "a merge nothing reaches" - {
    "an all-returning if ends in unreachable, not a valueless ret" in {
      val out = ir("f(c: bool) -> int\n    if c then return 1 else return 2\nprint(f(true))")

      out should include("ret i32 1")
      out should include("ret i32 2")
      out should include("unreachable")
      out.linesIterator.map(_.trim).toList should not contain "ret i32"
    }

    "an all-returning match does too" in {
      val src =
        """f(o: Option[int]) -> int = match o
          |    Some(v) -> return v
          |    None -> return -1
          |print(f(Some(1)))""".stripMargin
      val out = ir(src)

      out.linesIterator.map(_.trim).toList should not contain "ret i32"
      out should include("unreachable")
    }

    "a loop whose break and else both diverge ends the same way" in {
      val src =
        """extern stop() -> never
          |f(c: bool)
          |    while c
          |        break stop()
          |    else stop()
          |f(true)""".stripMargin
      val out = ir(src)

      out should include("call void @stop()")
      out.linesIterator.map(_.trim).toList should not contain "store void"
    }
  }
}
