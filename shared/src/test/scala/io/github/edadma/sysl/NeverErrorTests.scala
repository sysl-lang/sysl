package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Diagnostics for the bottom type and for `extern` declarations.
 *
 * `never` is permissive in exactly one direction — it stands where any type was asked for — and
 * the point of these tests is the other direction: nowhere a *value* is needed will it do, since
 * there are no values of it. That rules it out of every position but a result type, and rules a
 * diverging expression out of every place a value has to arrive.
 */
class NeverErrorTests extends AnyFreeSpec with CodegenSupport {

  "never is only a result type" - {
    "not a variable's type" in {
      err("var x: never = exit(1)") should include("can only be a result type")
    }

    "not a parameter's type" in {
      err("f(x: never) -> int = 1\nprint(f(exit(1)))") should include("can only be a result type")
    }

    "not a struct field's type" in {
      err("struct S\n    x: never\nprint(1)") should include("can only be a result type")
    }

    "not a variant payload's type" in {
      err("enum E\n    V(x: never)\nprint(1)") should include("can only be a result type")
    }

    "not a type argument" in {
      err("var o: Option[never] = None") should include("can only be a result type")
    }

    "not an element type" in {
      err("var a: [4]never\nprint(1)") should include("can only be a result type")
    }

    "not behind a reference" in {
      err("f(x: &never) -> int = 1\nprint(1)") should include("can only be a result type")
    }

    "and nothing else may take the name" in {
      err("struct never\n    x: int\nprint(1)") should include("already declared")
      err("enum never\n    A\nprint(1)") should include("already declared")
    }
  }

  "a diverging expression is not a value" - {
    "it cannot be bound" in {
      err("var x = exit(1)") should include("cannot bind 'x' to an expression that never returns")
    }

    "it cannot be printed" in {
      err("print(exit(1))") should include("cannot print a never value")
    }

    "it cannot fill an array" in {
      err("var a = [exit(1)]") should include("an array cannot hold never values")
    }

    "it cannot be assigned" in {
      err("var x = 1\nx = exit(1)") should include("cannot assign never")
    }

    "it cannot be made into a string" in {
      err("print(str(exit(1)))") should include("cannot make a string of a never value")
    }

    "it cannot be compared" in {
      err("print(exit(1) == exit(2))") should include("'==' is not defined for never")
    }

    "it cannot be a condition" in {
      err("if exit(1) then print(1)") should include("condition must be bool, got never")
    }
  }

  "a function declared 'never' must not finish" - {
    "a body that falls through is rejected" in {
      err("f() -> never\n    print(1)\nf()") should include("should return never, but its body yields unit")
    }

    "a body that yields a value is rejected" in {
      err("f() -> never = 1\nf()") should include("should return never, but its body yields int")
    }

    "handing off to something that does not return is accepted" in {
      ir("f() -> never = exit(1)\nf()") should include("define void @f()")
    }
  }

  // A diverging alternative is set aside before the others are compared, so the rules that were
  // there before have to be exactly as strict as they were.
  "the join is no more permissive than it was" - {
    "two arms that yield different values still conflict" in {
      val src =
        """f(n: int) -> int = match n
          |    1 -> 1
          |    2 -> exit(1)
          |    else -> "no"
          |print(f(1))""".stripMargin

      err(src) should include("match arms have different types: int and string")
    }

    "two branches that yield different values still conflict" in {
      err("var x = if true then 1 else \"no\"") should include("if branches have different types: int and string")
    }

    "a match with a diverging arm must still be exhaustive" in {
      val src =
        """f(n: int) -> int = match n
          |    1 -> 1
          |    2 -> exit(1)
          |print(f(1))""".stripMargin

      err(src) should include("must be exhaustive")
    }

    "a value-carrying break still needs an else to meet it" in {
      val src =
        """f() -> int
          |    var i = 0
          |    while i < 3
          |        break 7
          |print(f())""".stripMargin

      err(src) should include("breaks with a int but has no 'else'")
    }

    // A jumping block's *type* becomes `never`, but the jump itself is checked exactly as before:
    // its value against the function's result, and its target against the loops in scope.
    "a returning branch still checks the value it returns" in {
      err("f() -> int\n    var x = if true then 1 else return \"no\"\n    x") should
        include("return type mismatch: expected int, got string")
    }

    "a continuing branch still needs a loop to continue" in {
      err("f() -> int\n    var x = if true then 1 else continue\n    x") should
        include("'continue' is only allowed inside a loop")
    }

    "a value branch beside a unit one is still a statement" in {
      ir("var n = 0\nif true then n = 1 else print(2)\nprint(n)") should include("define i32 @main()")
    }
  }

  "externs" - {
    // The prelude declares `exit`, so a program that declares it again is really the ordinary
    // duplicate-name case — which is the point: an extern shares the one function namespace.
    "an extern shares the function namespace" in {
      err("extern exit(code: int) -> never\nprint(1)") should include("function 'exit' is already declared")
    }

    "two externs cannot share a name" in {
      err("extern f() -> int\nextern f() -> int\nprint(1)") should include("already declared")
    }

    "an extern and a function cannot share a name" in {
      err("extern f() -> int\nf() -> int = 1\nprint(1)") should include("already declared")
    }

    "an extern is checked against its declared signature" in {
      err("extern f(n: int) -> int\nprint(f(true))") should include("'n' of 'f' is int, but bool was given")
    }

    "an extern's arity is checked like any other call" in {
      err("extern f(n: int) -> int\nprint(f(1, 2))") should include("takes 1 argument, but 2 arguments were given")
    }

    "an extern's parameter types still have to resolve" in {
      err("extern f(n: Bogus) -> int\nprint(1)") should include("unknown type 'Bogus'")
    }

    "an extern may not be declared inside a function" in {
      err("f() -> int\n    extern g() -> int\n    1") should include("may only be declared at the top level")
    }
  }
}
