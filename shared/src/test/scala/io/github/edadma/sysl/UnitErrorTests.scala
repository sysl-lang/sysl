package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Diagnostics for `unit`, the type of an expression run only for its effect.
 *
 * `unit` is a scalar, because a function may declare it as its result and a block may have it, but
 * it is no more a *layout* than `never` is: its one value occupies nothing, so there is nothing for
 * a parameter, a field, an element, or a type argument to hold. These tests are that boundary from
 * both sides — the written type refused everywhere but a result, the value refused everywhere one
 * has to arrive, and inference stopped from concluding a slot where there is no value to put in one.
 */
class UnitErrorTests extends AnyFreeSpec with CodegenSupport {

  "unit is only a result type" - {
    "not a variable's type" in {
      err("var x: unit = ()") should include("can only be a result type")
    }

    "not a parameter's type" in {
      err("f(x: unit) -> int = 1\nprint(1)") should include("can only be a result type")
    }

    "not an extern parameter's type" in {
      err("extern e(x: unit) -> int\nprint(1)") should include("can only be a result type")
    }

    "not a struct field's type" in {
      err("struct S\n    x: unit\nprint(1)") should include("can only be a result type")
    }

    "not a variant payload's type" in {
      err("enum E\n    V(x: unit)\nprint(1)") should include("can only be a result type")
    }

    "not a type argument" in {
      err("var o: Option[unit] = None") should include("can only be a result type")
    }

    "not an element type, however deep" in {
      err("f(a: [4]unit) -> int = 1\nprint(1)") should include("can only be a result type")
      err("f(a: []unit) -> int = 1\nprint(1)") should include("can only be a result type")
      err("f(a: [2][3]unit) -> int = 1\nprint(1)") should include("can only be a result type")
    }

    "not behind a pointer or a reference" in {
      err("f(x: *unit) -> int = 1\nprint(1)") should include("can only be a result type")
      err("f(x: &unit) -> int = 1\nprint(1)") should include("can only be a result type")
    }

    // A generic trait names its arguments in three places, and a type with no layout is refused in
    // all three for the one reason: what the trait promises about that argument is a value.
    "not a trait's type argument, wherever the trait is named" in {
      val sink = "trait Sink[T]\n    put(self, x: T) -> int\n"

      err(s"${sink}struct A\n    n: int\nimpl Sink[unit] for A\n    put(self, x: unit) -> int = 1\nprint(1)") should
        include("can only be a result type")
      err(s"${sink}f(s: &Sink[unit]) -> int = 1\nprint(1)") should include("can only be a result type")
      err(s"${sink}f[X: Sink[unit]](x: X) -> int = 1\nprint(1)") should include("can only be a result type")
    }
  }

  "a unit value is not a value" - {
    "it cannot be bound" in {
      err("var x = ()") should include("cannot bind 'x' to a unit value")
      err("var x = print(1)") should include("cannot bind 'x' to a unit value")
    }

    "it cannot fill an array" in {
      err("var a = [print(1)]") should include("an array cannot hold unit values")
    }

    "it cannot be assigned" in {
      err("var x = 1\nx = print(2)") should include("cannot assign unit")
    }

    "it cannot be made into a string" in {
      err("print(str(print(1)))") should include("cannot make a string of a unit value")
    }

    "it cannot be compared" in {
      err("print(print(1) == print(2))") should include("'==' is not defined for unit")
    }

    "it cannot ride a variadic tail" in {
      err("extern printf(fmt: *u8, ...) -> int\nvar p: *u8 = null\nprint(printf(p, ()))") should
        include("a unit cannot be passed to '...'")
    }
  }

  /** A written parameter of a valueless type is refused where the type is resolved, so the only way
   * a slot can come to hold one is inference — a generic parameter accepts whatever its argument's
   * type turns out to be. Left alone it instantiates at `unit` or `never` and emits a `void` slot,
   * which is not a legal layout in the first place.
   */
  "inference will not make a slot of nothing" - {
    "a generic function's parameter" in {
      err("f[T](x: T) -> int = 1\nprint(f(print(2)))") should include("cannot pass a unit value as 'x' of 'f'")
    }

    "a member's own type parameter" in {
      val src =
        """struct A
          |    n: int
          |    take[T](self, x: T) -> int = self.n
          |var a = A(4)
          |print(a.take(print(5)))""".stripMargin

      err(src) should include("cannot pass a unit value as 'x' of 'A.take'")
    }

    "a generic struct's field" in {
      err("struct Box[T]\n    v: T\nvar b = Box(print(1))") should
        include("cannot pass a unit value as 'v' of 'Box[unit]'")
    }

    "a generic variant's payload" in {
      err("enum Box[T]\n    Full(v: T)\nvar b = Full(print(1))") should
        include("cannot pass a unit value as 'v' of 'Full'")
    }

    // The same hole, reached by the other valueless type: a diverging argument says nothing about
    // what the parameter holds, so binding one to it is the same empty slot.
    "and a diverging argument is refused the same way" in {
      err("f[T](x: T) -> int = 1\nprint(f(exit(1)))") should
        include("cannot pass an expression that never returns as 'x' of 'f'")
      err("struct Box[T]\n    v: T\nvar b = Box(exit(1))") should
        include("cannot pass an expression that never returns as 'v' of 'Box[never]'")
    }

    // Only where the parameter *became* valueless. A written one still has a layout, and the call
    // is simply dead code — which is what makes a guard clause in argument position legal.
    "but a written parameter still takes a diverging argument" in {
      ir("f(x: int) -> int = 1\nprint(f(exit(1)))") should include("define i32 @f(i32 %x.param)")
    }
  }

  "what a result may still be" - {
    "a function's" in {
      ir("f() -> unit = print(1)\nf()") should include("define void @f()")
    }

    "a method's" in {
      val src =
        """struct A
          |    n: int
          |    bump(self) -> unit = print(self.n)
          |var a = A(1)
          |a.bump()""".stripMargin

      ir(src) should include("define void @A.bump(%struct.A %self.param)")
    }

    "a trait method's, dispatched through an object" in {
      val src =
        """trait Show
          |    show(self) -> unit
          |struct A
          |    n: int
          |impl Show for A
          |    show(self) -> unit = print(self.n)
          |var s: &Show = A(7)
          |s.show()""".stripMargin

      ir(src) should include("@vt.ref.Show.A")
    }

    "an extern's" in {
      ir("extern e() -> unit\ne()") should include("declare void @e()")
    }

    "and writing it changes nothing about leaving it off" in {
      ir("f() -> unit = print(1)\nf()") shouldBe ir("f()\n    print(1)\nf()")
    }
  }
}
