package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** `unit` — the type of an expression run only for its effect, and the language's **zero-sized
 * type** (`reference/types.md § unit and never`).
 *
 * It has one value, and that value is nothing at all, so it has a layout: the empty one. A field, a
 * parameter, a type argument, or a binding of it is therefore legal and costs nothing — the field is
 * skipped with the indices behind it shifted, the parameter is dropped from the emitted signature,
 * the binding is not a slot. That is what makes `Result[unit, E]` writable, which is what the whole
 * rule exists for.
 *
 * What stays refused is what is refused for a reason other than layout: `&unit`, `*unit`, a slice or
 * an array of it — all four reach their contents through an address, and there is nothing to point
 * at — plus the operations that would have to invent a value (`str`, `==`, a variadic tail).
 * `never` is untouched throughout: it has no values at all, so a field of it could never be given
 * one.
 */
class UnitErrorTests extends AnyFreeSpec with CodegenSupport with RunSupport {

  "unit is zero-sized, so it may be held" - {
    "as a variable's type, which takes no slot" in {
      run("var x: unit = ()\nprint(str(1))") shouldBe "1\n"
    }

    // The parameter is in the signature the analyzer checks and absent from the one LLVM sees.
    "as a parameter's type, which the emitted signature then does not mention" in {
      ir("f(x: unit, n: int) -> int = n\nprint(f((), 2))") should include("define i32 @f(i32 %n.param)")
    }

    "as an extern parameter's type, for the same reason" in {
      ir("extern e(x: unit, n: int) -> int\nprint(e((), 1))") should include("declare i32 @e(i32)")
    }

    // The field is skipped and the ones behind it shift up, which is the whole of the layout rule.
    "as a struct field's type, skipped in the layout" in {
      val out = ir("struct S\n    a: unit\n    b: int\nvar s = S((), 7)\nprint(s.b)")

      out should include("%struct.S = type { i32 }")
      out should include("extractvalue %struct.S")
      out should not include "%struct.S = type { i32, i32 }"
    }

    "as a variant payload's type" in {
      run("enum E\n    V(a: unit, b: int)\nend E\nvar e = V((), 3)\ne match\n    V(a, b) -> print(str(b))") shouldBe "3\n"
    }

    "as a type argument — which is the point of the whole rule" in {
      run(
        """f(n: int) -> Result[unit, int]
          |    if n < 0 then return Err(n)
          |    Ok(())
          |end f
          |f(1) match
          |    Ok(u) -> print("ok")
          |    Err(e) -> print(str(e))""".stripMargin,
      ) shouldBe "ok\n"
    }

    "and '?' chains through one, carrying the failure and yielding nothing on success" in {
      run(
        """step(n: int) -> Result[unit, int]
          |    if n < 0 then return Err(n)
          |    print(str(n))
          |    Ok(())
          |end step
          |both() -> Result[unit, int]
          |    step(1)?
          |    step(-2)?
          |    step(3)?
          |    Ok(())
          |end both
          |both() match
          |    Ok(u) -> print("ok")
          |    Err(e) -> print(str(e))""".stripMargin,
      ) shouldBe "1\n-2\n"
    }

    "as a generic trait's argument" in {
      val src =
        """trait Sink[T]
          |    put(self, x: T) -> int
          |struct A
          |    n: int
          |impl Sink[unit] for A
          |    put(self, x: unit) -> int = self.n
          |var a = A(4)
          |print(a.put(()))""".stripMargin

      run(src) shouldBe "4\n"
    }

    // Through a trait object the adapter and the implementation have to agree about which
    // parameters exist, and the dropped one is dropped on both sides.
    "as a trait object's method parameter" in {
      val src =
        """trait Sink[T]
          |    put(self, x: T) -> int
          |struct A
          |    n: int
          |impl Sink[unit] for A
          |    put(self, x: unit) -> int = self.n
          |var s: &Sink[unit] = A(9)
          |print(s.put(()))""".stripMargin

      run(src) shouldBe "9\n"
    }
  }

  "a zero-sized field is skipped wherever a layout is built" - {
    "leaving the fields behind it at the indices they actually occupy" in {
      run(
        """struct S
          |    a: int
          |    b: unit
          |    c: int
          |var s = S(1, (), 2)
          |print(str(s.a))
          |print(str(s.c))""".stripMargin,
      ) shouldBe "1\n2\n"
    }

    "including where a reference sits behind it, which the ownership walk has to find" in {
      run(
        """struct Inner
          |    v: int
          |struct S
          |    a: unit
          |    b: &Inner
          |mk(n: int) -> int
          |    var s = S((), Inner(n))
          |    s.b.v
          |end mk
          |var total = 0
          |for i in 0..<1000 do total += mk(i % 3)
          |print(str(total))""".stripMargin,
      ) shouldBe "999\n"
    }

    "and a written one is assignable, which does nothing at all" in {
      run("struct S\n    a: unit\n    b: int\nvar s = S((), 1)\ns.a = ()\nprint(str(s.b))") shouldBe "1\n"
    }

    // Nothing is written, but the walk *to* the place is still owed: it is why the expression was
    // written where it was.
    "while the receiver chain of such an assignment still runs" in {
      run(
        """struct S
          |    a: unit
          |    b: int
          |side() -> usize
          |    print("side")
          |    0
          |end side
          |var xs = [S((), 1), S((), 2)]
          |xs[side()].a = ()
          |print(str(xs[0].b))""".stripMargin,
      ) shouldBe "side\n1\n"
    }

    "and a struct carrying one still passes by value, both ways" in {
      run(
        """struct S
          |    a: unit
          |    b: int
          |bump(s: S) -> S = S((), s.b + 1)
          |print(str(bump(S((), 4)).b))""".stripMargin,
      ) shouldBe "5\n"
    }
  }

  /** A type with exactly one value is trivially its own zero: there is nothing to produce and
   * nowhere to put it. Without that a struct would lose its zero value by gaining a field that
   * costs nothing, which is the opposite of what zero-sized means.
   */
  "it is its own zero value" - {
    "so a declaration with no initializer is legal" in {
      run("var x: unit\nprint(str(1))") shouldBe "1\n"
    }

    "and a struct does not lose its zero by gaining a field that costs nothing" in {
      run("struct S\n    a: unit\n    b: int\nvar s: S\nprint(str(s.b))") shouldBe "0\n"
    }
  }

  /** Zero-sizedness is deliberately **not transitive**: a struct whose fields are all zero-sized is
   * emitted as an empty aggregate and is still a type with an address. Making that zero-sized too
   * would be a second rule, with its own consequences for identity and for `&T`'s non-null
   * guarantee, and nothing has asked for it.
   */
  "a struct of nothing but zero-sized fields is still a type" - {
    "with an empty layout" in {
      ir("struct S\n    a: unit\nvar s = S(())\nprint(1)") should include("%struct.S = type {  }")
    }

    "and an address a reference may point at" in {
      run("struct S\n    a: unit\nholds(e: &S) -> int = 1\nvar e: &S = S(())\nprint(str(holds(e)))") shouldBe "1\n"
    }
  }

  "a unit value arrives, and occupies nothing" - {
    "so binding one is legal and emits no slot" in {
      run("var x = print(1)\nprint(2)") shouldBe "1\n2\n"
    }

    "and so is passing one to a generic parameter" in {
      run("f[T](x: T) -> int = 1\nprint(f(print(2)))") shouldBe "2\n1\n"
    }

    "into a generic struct's field" in {
      run("struct Box[T]\n    v: T\nvar b = Box(print(1))\nprint(2)") shouldBe "1\n2\n"
    }

    "into a generic variant's payload" in {
      run("enum Box[T]\n    Full(v: T)\nend Box\nvar b = Full(print(1))\nprint(2)") shouldBe "1\n2\n"
    }

    // Order is still owed even where nothing is passed: the argument's effect is the reason it was
    // written at all.
    "and an argument's effect happens in the order it was written" in {
      run("f(a: unit, n: int, b: unit) -> int = n\nprint(f(print(1), 2, print(3)))") shouldBe "1\n3\n2\n"
    }
  }

  "what is still refused, and not because of a layout" - {
    "a reference or a pointer to nothing" in {
      err("f(x: *unit) -> int = 1\nprint(1)") should include("nothing for '*' to point at")
      err("f(x: &unit) -> int = 1\nprint(1)") should include("nothing for '&' to point at")
    }

    // Not because the elements would share an address — a fieldless struct's do, and an array of
    // *those* is admitted (`FieldlessStructTests`). It is that `unit` is the type the compiler
    // **drops**: an array of it would have nothing to be an array of, where an array of an ordinary
    // type whose stride happens to be zero is still a row of real elements with a real length.
    "an array or a slice of it, however deep" in {
      err("f(a: [4]unit) -> int = 1\nprint(1)") should include("nothing for an array to point at")
      err("f(a: []unit) -> int = 1\nprint(1)") should include("nothing for a slice to point at")
      err("f(a: [2][3]unit) -> int = 1\nprint(1)") should include("nothing for an array to point at")
    }

    "filling an array with one" in {
      err("var a = [print(1)]") should include("an array cannot hold unit values")
    }

    "assigning one where a value belongs" in {
      err("var x = 1\nx = print(2)") should include("cannot assign unit")
    }

    "making a string of one" in {
      err("print(str(print(1)))") should include("cannot make a string of a unit value")
    }

    "comparing two" in {
      err("print(print(1) == print(2))") should include("'==' is not defined for unit")
    }

    // A C variadic counts what it was handed at run time, so an argument that is not passed would
    // silently shift everything after it.
    "riding a variadic tail" in {
      err("extern printf(fmt: *u8, ...) -> int\nvar p: *u8 = null\nprint(printf(p, ()))") should
        include("a unit cannot be passed to '...'")
    }
  }

  /** `never` is the other valueless type and it is **not** zero-sized: it has no values at all, so a
   * slot of it could never be given one. Everything below is unchanged by the layout rule.
   */
  "never is untouched" - {
    "a diverging argument still cannot fix a generic parameter" in {
      err("f[T](x: T) -> int = 1\nprint(f(exit(1)))") should
        include("cannot pass an expression that never returns as 'x' of 'f'")
      err("struct Box[T]\n    v: T\nvar b = Box(exit(1))") should
        include("cannot pass an expression that never returns as 'v' of 'Box[never]'")
    }

    "and it still cannot be bound" in {
      err("var x = exit(1)") should include("never returns")
    }

    "nor be a field, a parameter, or a type argument" in {
      err("struct S\n    x: never\nprint(1)") should include("can only be a result type")
      err("f(x: never) -> int = 1\nprint(1)") should include("can only be a result type")
      err("var o: Option[never] = None") should include("can only be a result type")
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
