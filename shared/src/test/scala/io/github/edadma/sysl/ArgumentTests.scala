package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** A parameter's default value, and an argument written at the parameter it names (`12 §2a`).
 *
 * The two are one feature because they are one question — what a call may leave to the declaration
 * — and one implementation: both are resolved by `bindArgs` before any call form looks at its
 * arguments, so what the arity check, the generic solve, `checkArgs` and the emitter all receive is
 * the call written out in full. That is what the runs here are for. A test that only read the
 * emitted text would pin the binding and say nothing about whether the *program* means what the
 * chapter says it does — whether a default really is evaluated per call, whether reordering by name
 * really reaches the parameter named and not the one at that position.
 *
 * The collision with assignment is the other reason for the runs. `f(x = 1)` was a legal call
 * before this feature and meant something else; the tests that pin the new reading also pin the
 * escape from it, because a language that quietly changed what an existing line does would be worse
 * than one that never had the feature.
 */
class ArgumentTests
    extends AnyFreeSpec
    with RunSupport
    with CodegenSupport
    with ParseSupport
    with TestFrameworkSupport {

  "a default value" - {
    "stands where the argument was not written" in {
      run("""|greet(name: string, greeting: string = "hi") -> string = greeting + ", " + name
             |print(greet("ed"))
             |""".stripMargin) shouldBe "hi, ed\n"
    }

    "and steps aside where it was" in {
      run("""|greet(name: string, greeting: string = "hi") -> string = greeting + ", " + name
             |print(greet("ed", "hello"))
             |""".stripMargin) shouldBe "hello, ed\n"
    }

    // Several defaults, filled from the right, so what a call writes decides how many are taken. A
    // discriminating shape: three different values, so a fill that took the wrong one shows up.
    "fills from the right, however many the call stops short of" in {
      run("""|f(a: int, b: int = 20, c: int = 300) -> int = a + b + c
             |print(f(1))
             |print(f(1, 2))
             |print(f(1, 2, 3))
             |""".stripMargin) shouldBe "321\n303\n6\n"
    }

    // `12 §2a`: "a fresh call per call site rather than one value computed once and shared". Asserted
    // by defaulting to something with a side effect and counting how often it happened.
    "is an expression evaluated at each call, not one value shared between them" in {
      run("""|import sysl.buf.{Buf, buf}
             |
             |grow(b: &Buf[int] = buf()) -> usize
             |    b.push(1)
             |    b.len()
             |
             |var shared: &Buf[int] = buf()
             |
             |print(grow())
             |print(grow())
             |print(grow(shared))
             |print(grow(shared))
             |""".stripMargin) shouldBe "1\n1\n1\n2\n"
    }

    "may name a module-level value the caller has never heard of" in {
      runOf(
        "conf.sysl" -> """|module conf
                          |
                          |val width: int = 40
                          |
                          |indent(depth: int, w: int = width) -> int = depth * w
                          |""".stripMargin,
        "main.sysl" -> """|import conf.indent
                          |
                          |print(indent(2))
                          |""".stripMargin,
      ) shouldBe "80\n"
    }

    "reaches a method" in {
      run("""|struct Box
             |    n: int
             |
             |    grown(self, by: int = 10) -> int = self.n + by
             |end Box
             |
             |var b = Box(5)
             |print(b.grown())
             |print(b.grown(1))
             |""".stripMargin) shouldBe "15\n6\n"
    }

    "reaches an associated function" in {
      run("""|struct Box
             |    n: int
             |
             |    of(n: int = 7) -> Box = Box(n)
             |end Box
             |
             |print(Box.of().n)
             |print(Box.of(2).n)
             |""".stripMargin) shouldBe "7\n2\n"
    }

    "reaches a nested function" in {
      run("""|work(base: int) -> int
             |    step(n: int, by: int = 4) -> int = n + by
             |    step(base) + step(base, 1)
             |
             |print(work(1))
             |""".stripMargin) shouldBe "7\n"
    }

    "and an 'extern', whose parameter names are sysl's to choose" in {
      run("""|extern abs(n: i32 = -5i32) -> i32
             |print(abs())
             |print(abs(-7i32))
             |""".stripMargin) shouldBe "5\n7\n"
    }

    "reaches a generic function, at the type the call fixes" in {
      run("""|second[T](a: T, b: T, take_first: bool = false) -> T = if take_first then a else b
             |print(second(1, 2))
             |print(second("x", "y", true))
             |""".stripMargin) shouldBe "2\nx\n"
    }
  }

  "a trait's default" - {
    // `12 §2a`: the trait's declaration is what a call names, so the default is filled before the
    // dispatch and means the same thing either way. Both halves asserted, because a fill that
    // happened after the slot lookup would work through a known type and fail through an object.
    "is the same value through a trait object as through a known type" in {
      run("""|trait Volume
             |    loud(self, times: int = 3) -> int
             |
             |struct Horn
             |    n: int
             |
             |impl Volume for Horn
             |    loud(self, times: int) -> int = self.n * times
             |
             |shout(v: &Volume) -> int = v.loud()
             |
             |var h = Horn(2)
             |var boxed: &Volume = Horn(2)
             |print(h.loud())
             |print(shout(boxed))
             |""".stripMargin) shouldBe "6\n6\n"
    }

    // The other half of the same rule: the implementation supplies the body and the trait supplies
    // the default, so an `impl` writing one of its own is refused rather than silently ignored.
    "and an 'impl' block declares none of its own" in {
      err("""|trait Volume
             |    loud(self, times: int) -> int
             |
             |struct Horn
             |    n: int
             |
             |impl Volume for Horn
             |    loud(self, times: int = 3) -> int = self.n * times
             |
             |print(Horn(2).loud(1))
             |""".stripMargin) should include("a member of an 'impl' block declares no default")
    }
  }

  "what a default may not be" - {
    // The suffix rule, worded as `10 §3` words the identical rule about a type parameter's default.
    "a parameter with no default may not come after one that has" in {
      val e = err("""|f(a: int = 1, b: int) -> int = a + b
                     |print(f(1, 2))
                     |""".stripMargin)

      e should include("'b' has no default and comes after 'a', which has one")
      e should include("nothing could leave out 'a' and still supply 'b'")
    }

    // `12 §9`: C reads the tail relative to the last named argument, so an argument that might be
    // the last parameter or might be the first of the tail leaves nowhere for the tail to begin.
    "a variadic parameter list declares none" in {
      err("""|f(a: int, b: int = 2, ...) -> int = a
             |print(f(1))
             |""".stripMargin) should include("a parameter list with a tail declares no default")
    }

    // The rule that makes a default mean one thing: it is analyzed with nothing local in scope, so
    // a parameter is as undefined there as it is anywhere else outside a body.
    "a default may not name another parameter" in {
      err("""|f(n: int, m: int = n) -> int = n + m
             |print(f(1))
             |""".stripMargin) should include("undefined name 'n'")
    }

    // The same rule from the caller's side, and the one that matters more: without an emptied local
    // scope this would quietly compile and read the *caller's* `n`, which is 100 and not 1.
    "and may not find a caller's local of that name either" in {
      err("""|f(m: int = n) -> int = m
             |
             |go() -> int
             |    var n = 100
             |    f()
             |
             |print(go())
             |""".stripMargin) should include("undefined name 'n'")
    }

    // Checked at the declaration and not at the first call that takes it, which is the whole reason
    // the pass exists: nothing here calls `f`, and the mistake is still reported.
    "a default that is not the parameter's type is refused where it is written" in {
      err("""|f(s: string = 3) -> string = s
             |print(1)
             |""".stripMargin) should include("the default for 's'")
    }

    // `13 §2`, applied to the one part of a signature a call does not write. Without this a caller
    // in another module would have had `secret()` called on their behalf.
    "a public declaration's default may not name something that reaches less far" in {
      errOf(
        "lib.sysl"  -> """|module lib
                          |
                          |private secret() -> int = 7
                          |
                          |f(n: int = secret()) -> int = n
                          |""".stripMargin,
        "main.sysl" -> """|import lib.f
                          |
                          |print(f())
                          |""".stripMargin,
      ) should include("does not reach as far as")
    }

    // And the control: the same shape with both declarations private is no leak at all, so it
    // compiles. Without this the test above would pass for a rule that refused every default.
    "while a private one naming a private one is no leak" in {
      runOf(
        "lib.sysl"  -> """|module lib
                          |
                          |private secret() -> int = 7
                          |
                          |private f(n: int = secret()) -> int = n
                          |
                          |show() -> int = f()
                          |""".stripMargin,
        "main.sysl" -> """|import lib.show
                          |
                          |print(show())
                          |""".stripMargin,
      ) shouldBe "7\n"
    }

    "a field declares none" in {
      err("""|struct Point
             |    x: int = 0
             |    y: int
             |end Point
             |
             |print(Point(1, 2).x)
             |""".stripMargin) should include("a field declares no default")
    }
  }

  "an argument written by name" - {
    "reaches the parameter it names rather than the one at its position" in {
      run("""|div(top: int, bottom: int) -> int = top / bottom
             |print(div(bottom = 2, top = 10))
             |""".stripMargin) shouldBe "5\n"
    }

    "may follow positional arguments" in {
      run("""|clamp(v: int, lo: int, hi: int) -> int = if v < lo then lo else if v > hi then hi else v
             |print(clamp(50, hi = 10, lo = 0))
             |""".stripMargin) shouldBe "10\n"
    }

    // The two features meeting: a name is what lets a call skip a defaulted parameter and supply a
    // later one, which is the case neither feature can serve on its own.
    "is what lets a call skip a default and still write what comes after it" in {
      run("""|f(a: int, b: int = 20, c: int = 300) -> int = a + b + c
             |print(f(1, c = 3))
             |""".stripMargin) shouldBe "24\n"
    }

    "reaches a struct's constructor, whose fields are its parameters" in {
      run("""|struct Point
             |    x: int
             |    y: int
             |end Point
             |
             |var p = Point(y = 2, x = 1)
             |print(p.x)
             |print(p.y)
             |""".stripMargin) shouldBe "1\n2\n"
    }

    "reaches an enum variant's payload" in {
      run("""|enum Shape
             |    Rect(w: int, h: int)
             |
             |area(s: Shape) -> int
             |    s match
             |        Rect(w, h) -> w * h
             |
             |print(area(Rect(h = 2, w = 30)))
             |""".stripMargin) shouldBe "60\n"
    }

    "reaches a method" in {
      run("""|struct Span
             |    n: int
             |
             |    between(self, lo: int, hi: int) -> int = self.n + hi - lo
             |end Span
             |
             |print(Span(5).between(hi = 30, lo = 10))
             |""".stripMargin) shouldBe "25\n"
    }
  }

  "what a name at a call may not do" - {
    "come before a positional argument" in {
      err("""|f(a: int, b: int) -> int = a + b
             |print(f(a = 1, 2))
             |""".stripMargin) should include("comes after one written by name")
    }

    "name a parameter the declaration does not have" in {
      val e = err("""|f(a: int, b: int) -> int = a + b
                     |print(f(a = 1, c = 2))
                     |""".stripMargin)

      e should include("declares no parameter named 'c'")
      e should include("'a' and 'b'")
    }

    "name one twice" in {
      err("""|f(a: int, b: int) -> int = a + b
             |print(f(a = 1, a = 2))
             |""".stripMargin) should include("'a' is given twice")
    }

    "name one a positional argument already filled" in {
      err("""|f(a: int, b: int) -> int = a + b
             |print(f(1, a = 2))
             |""".stripMargin) should include("already given by position")
    }

    // A call through a `&Fn` carries types and no names (`12 §6`), so there is nothing to match a
    // name against and the message says that rather than "no such parameter".
    "or be written at a call through a callable, which carries no names" in {
      err("""|apply(f: &Fn(int) -> int) -> int = f(n = 1)
             |
             |var c: &Fn(int) -> int = x -> x + 1
             |print(apply(c))
             |""".stripMargin) should include("names an argument")
    }

    // `*extern(A) -> R` is one machine word (`12 §6a`), so it carries even less than a trait object
    // does — there is no declaration anywhere behind it to have named anything.
    "or at a call through a function's address" in {
      err("""|double(n: int) -> int = n * 2
             |
             |var f: *extern(int) -> int = &double
             |print(f(n = 5))
             |""".stripMargin) should include("names an argument")
    }
  }

  "the collision with assignment" - {
    // Before this feature `f(x = 1)` stored 1 into `x` and passed the stored value. The named
    // argument now wins, which is what the two halves below pin: the new reading, and the escape.
    "is decided for the named argument" in {
      run("""|f(x: int) -> int = x * 10
             |var x = 7
             |print(f(x = 1))
             |print(x)
             |""".stripMargin) shouldBe "10\n7\n"
    }

    "and parentheses still say the store was meant" in {
      run("""|f(x: int) -> int = x * 10
             |var x = 7
             |print(f((x = 1)))
             |print(x)
             |""".stripMargin) shouldBe "10\n1\n"
    }

    // Only a bare identifier before `=` is a name, so neither of these ever was a parameter and
    // both are the stores they always were.
    "while a store through a field or an element is untouched" in {
      run("""|struct Cell
             |    v: int
             |end Cell
             |
             |f(n: int) -> int = n
             |var c = Cell(0)
             |print(f(c.v = 5))
             |print(c.v)
             |""".stripMargin) shouldBe "5\n5\n"
    }
  }

  "the shapes the parser reads" - {
    "a default is held on the parameter it was written after" in {
      prog("""|f(a: int, b: int = 2) -> int
              |    a
              |""".stripMargin) shouldBe
        List(FuncDecl(
          "f",
          Nil,
          List(
            Param("a", NamedType("int")),
            Param("b", NamedType("int"), default = Some(IntLit(2, None))),
          ),
          Some(NamedType("int")),
          List(ExprStmt(Ident("a"))),
        ))
    }

    "and a named argument is a node of its own, not an assignment" in {
      prog("f(a = 1)") shouldBe List(ExprStmt(Call(Ident("f"), List(NamedArg("a", IntLit(1, None))))))
    }

    "while a parenthesized one is still the assignment it reads as" in {
      prog("f((a = 1))") shouldBe
        List(ExprStmt(Call(Ident("f"), List(Assign("=", Ident("a"), IntLit(1, None))))))
    }
  }
}
