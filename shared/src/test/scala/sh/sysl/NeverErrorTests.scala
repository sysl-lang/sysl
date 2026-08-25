package sh.sysl

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
        """f(n: int) -> int = n match
          |    1 -> 1
          |    2 -> exit(1)
          |    else "no"
          |print(f(1))""".stripMargin

      err(src) should include("match arms have different types: int and string")
    }

    "two branches that yield different values still conflict" in {
      err("var x = if true then 1 else \"no\"") should include("if branches have different types: int and string")
    }

    "a match with a diverging arm must still be exhaustive" in {
      val src =
        """f(n: int) -> int = n match
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
      ir("var n = 0\nif true then n = 1 else print(2)\nprint(n)") should include("define i32 @main(")
    }
  }

  "externs" - {
    // An extern shares the one function namespace with everything else in its module, which is what
    // this says — a second declaration of the name is the ordinary duplicate, not a separate
    // foreign-name space that happens to collide.
    "an extern shares the function namespace" in {
      err("f() -> int = 1\nextern f() -> int\nprint(1)") should include("function 'f' is already declared")
    }

    // The library's `exit` is `sysl.exit` and a program's own is its own, so declaring one is no
    // longer the clash it was while the prelude held the name. The *symbol* is another matter: both
    // resolve to `@exit`, since an extern's symbol is what was written and cannot be qualified — so
    // this is the one shape where two library-and-program declarations share a linker name, and it
    // has to stay a single `declare`.
    "a program may declare its own 'exit', and the two share one declaration" in {
      val out = Compiler.compileToLlvm("extern exit(code: int) -> never\nprint(1)\nexit(0)")

      out.map(_.linesIterator.count(_.contains("declare void @exit("))) shouldBe Right(1)
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

  /** A variadic tail has no declared type to check an argument against, so what stands in for one
   * is the C rule the call site must satisfy itself: only what varargs can carry may be passed.
   * These are the other side of that — everything the ellipsis does *not* excuse.
   */
  "a variadic tail" - {
    "needs a named parameter before it" in {
      err("extern f(...) -> int\nprint(f(1))") should
        include("'f' needs at least one named parameter before '...'")
    }

    "does not excuse the named parameters" in {
      err("extern printf(fmt: *u8, ...) -> int\nprint(printf())") should
        include("takes at least 1 argument, but 0 arguments were given")
    }

    "still checks the named parameters it does have" in {
      err("extern printf(fmt: *u8, ...) -> int\nprint(printf(1, 2))") should
        include("'fmt' of 'printf' is *byte, but int was given")
    }

    // An **aggregate** does cross to a foreign callee, and under exactly the classification a
    // declared parameter of that type gets (`getting-started/cli.md § targets`) — C allows a struct
    // in a variadic tail and says which registers it arrives in, so there is nothing left for sysl
    // to withhold. The shapes that are refused are the ones with no layout to hand over, or none C
    // could read back.
    "carries a string, a struct, a slice — every aggregate" in {
      ir("extern printf(fmt: *u8, ...) -> int\nvar p: *u8 = null\nprint(printf(p, \"hi\"))") should
        include("declare i32 @printf(ptr, ...)")

      ir("""extern printf(fmt: *u8, ...) -> int
           |struct Point
           |    x: int
           |    y: int
           |var p: *u8 = null
           |print(printf(p, Point(1, 2)))""".stripMargin) should include("declare i32 @printf(ptr, ...)")

      ir("""extern printf(fmt: *u8, ...) -> int
           |var a: [3]int = [1, 2, 3]
           |var p: *u8 = null
           |print(printf(p, a))""".stripMargin) should include("declare i32 @printf(ptr, ...)")
    }

    // A `&T` is a counted reference, and what a tail argument cannot carry is the *count*: a declared
    // `&T` parameter says the callee borrows for the duration of the call, and there is nothing in a
    // tail to say it with.
    "carries no reference" in {
      val src =
        """extern printf(fmt: *u8, ...) -> int
          |struct Inner
          |    v: int
          |var p: *u8 = null
          |var r: &Inner = Inner(1)
          |print(printf(p, r))""".stripMargin

      err(src) should include("a &Inner cannot be passed to '...'")
    }

    // A *simple* enum is an integer wearing a name, and C's promotions have nothing to say about a
    // name — where a data enum is a tag and a payload, an aggregate like any other.
    "carries no simple enum, though it carries a data one" in {
      val simple =
        """extern printf(fmt: *u8, ...) -> int
          |enum Color
          |    Red
          |    Green
          |var p: *u8 = null
          |print(printf(p, Red))""".stripMargin

      err(simple) should include("a Color cannot be passed to '...'")

      ir("""extern printf(fmt: *u8, ...) -> int
           |enum Shape
           |    Circle(r: int)
           |    Square(s: int)
           |var p: *u8 = null
           |print(printf(p, Circle(2)))""".stripMargin) should include("declare i32 @printf(ptr, ...)")
    }

    // C promotes a `bool` to `int`, but sysl has no conversion that says so — nothing widens
    // without being written — so there is nothing to promote it *with*, and it is refused rather
    // than promoted by a rule that exists nowhere else in the language.
    "carries no bool" in {
      err("extern printf(fmt: *u8, ...) -> int\nvar p: *u8 = null\nprint(printf(p, true))") should
        include("a bool cannot be passed to '...'")
    }

    "carries no unit, and nothing that never arrives" in {
      err("extern printf(fmt: *u8, ...) -> int\nvar p: *u8 = null\nprint(printf(p, ()))") should
        include("a unit cannot be passed to '...'")
      err("extern printf(fmt: *u8, ...) -> int\nvar p: *u8 = null\nprint(printf(p, exit(1)))") should
        include("a never cannot be passed to '...'")
    }

    "says what it would have taken" in {
      err("extern printf(fmt: *u8, ...) -> int\nvar p: *u8 = null\nprint(printf(p, true))") should
        include("must be an integer, a float, a char, or a raw pointer")
    }

    // The tail is the extern's alone: a sysl function's arity is fixed, so an extra argument to
    // one is the ordinary arity error however many the callee declares.
    "belongs to the extern that declared it, not to every call" in {
      err("extern printf(fmt: *u8, ...) -> int\nf(n: int) -> int = n\nprint(f(1, 2))") should
        include("function 'f' takes 1 argument, but 2 arguments were given")
    }
  }
}
