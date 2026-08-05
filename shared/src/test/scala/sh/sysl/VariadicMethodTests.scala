package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** A **member** that takes a `...` (`12-functions-and-closures.md` §9).
 *
 * A member is a function with a receiver in front, and that is the whole of the rule: the ellipsis
 * reaches a method, an associated function, a member of a generic type, a nested function, and a
 * trait's declaration, under exactly the rules a free function's tail follows. What the receiver
 * buys is that it counts as the named parameter a `...` has to anchor on, so `only(self, ...)` is a
 * complete declaration while a receiverless `make(...)` is not.
 *
 * The one thing a variadic member cannot do is be reached through a **trait object**: a call to a
 * variadic names the callee's whole function type, and a slot in a method table is a word.
 */
class VariadicMethodTests extends AnyFreeSpec with CodegenSupport with RunSupport with ParseSupport {

  /** Sums `n` `int`s out of a walk it was lent — the helper most of these members read their tail
   * with, so each test is about the member rather than about the walk.
   */
  private val sumTail =
    """sum_tail(n: int, ap: *va_list) -> int
      |    var t = 0
      |    for i in 0..<n
      |        var v: int = va_arg(ap)
      |        t += v
      |    t
      |end sum_tail""".stripMargin

  /** A struct whose method reads a tail and adds its own field to the total. */
  private val acc =
    s"""$sumTail
       |struct Acc
       |    base: int
       |
       |    total(self, n: int, ...) -> int
       |        var ap: va_list
       |        va_start(ap)
       |        var t = sum_tail(n, &ap)
       |        va_end(ap)
       |        self.base + t
       |    end total
       |end Acc""".stripMargin

  "a method" - {
    "reads its tail exactly as a free function does" in {
      run(s"$acc\nvar a = Acc(100)\nprint(a.total(3, 1, 2, 3))") shouldBe "106\n"
    }

    "carries the ellipsis into the definition, and the call names the whole type" in {
      val out = ir(s"$acc\nvar a = Acc(100)\nprint(a.total(2, 1, 2))")

      out should include("define i32 @Acc.total(%struct.Acc %self.param, i32 %n.param, ...)")
      out should include regex "call i32 \\(%struct\\.Acc, i32, \\.\\.\\.\\) @Acc\\.total"
    }

    "may be called with an empty tail" in {
      run(s"$acc\nvar a = Acc(100)\nprint(a.total(0))") shouldBe "100\n"
    }

    // The receiver is a parameter once the member is lowered, so it is what a tail with no other
    // fixed parameter anchors on — which is why this declaration is complete.
    "may take the receiver as its only fixed parameter" in {
      val src =
        """struct Tag
          |    v: int
          |
          |    only(self, ...) -> int
          |        var ap: va_list
          |        va_start(ap)
          |        var a: int = va_arg(ap)
          |        va_end(ap)
          |        self.v + a
          |    end only
          |end Tag
          |print(Tag(100).only(5))""".stripMargin

      run(src) shouldBe "105\n"
    }

    "may take its receiver in any mode" in {
      val src =
        s"""$sumTail
           |struct P
           |    v: int
           |
           |    thru(*self, n: int, ...) -> int
           |        var ap: va_list
           |        va_start(ap)
           |        var t = sum_tail(n, &ap)
           |        va_end(ap)
           |        self.v + t
           |    end thru
           |end P
           |
           |struct R
           |    v: int
           |
           |    thru(&self, n: int, ...) -> int
           |        var ap: va_list
           |        va_start(ap)
           |        var t = sum_tail(n, &ap)
           |        va_end(ap)
           |        self.v + t
           |    end thru
           |end R
           |
           |var p = P(100)
           |var r: &R = R(200)
           |print(p.thru(2, 1, 2), r.thru(2, 1, 2))""".stripMargin

      run(src) shouldBe "103 203\n"
    }

    "an enum's members take one too" in {
      val src =
        s"""$sumTail
           |enum Sign
           |    Up
           |    Down
           |
           |    lift(self, n: int, ...) -> int
           |        var ap: va_list
           |        va_start(ap)
           |        var t = sum_tail(n, &ap)
           |        va_end(ap)
           |        t
           |    end lift
           |end Sign
           |print(Sign.Up.lift(2, 5, 6))""".stripMargin

      run(src) shouldBe "11\n"
    }
  }

  "an associated function" - {
    "takes a tail as long as something else is named before it" in {
      val src =
        s"""$sumTail
           |struct Acc
           |    base: int
           |
           |    of(n: int, ...) -> Acc
           |        var ap: va_list
           |        va_start(ap)
           |        var t = sum_tail(n, &ap)
           |        va_end(ap)
           |        Acc(t)
           |    end of
           |end Acc
           |print(Acc.of(2, 20, 22).base)""".stripMargin

      run(src) shouldBe "42\n"
    }

    // It has no receiver to anchor on, so the rule a free function meets is the rule it meets —
    // and the message is the same one, named for the lowered member.
    "with nothing named before it is refused" in {
      err("struct S\n    v: int\n    make(...) -> S = S(1)\nprint(S.make(1).v)") should
        include("'S.make' needs at least one named parameter before '...'")
    }
  }

  // `12 §9` names a nested function alongside a member: its environment holds the first parameter
  // slot the way a receiver does, so the tail anchors after what the program wrote. That makes it
  // the one variadic whose fixed parameters are not all written at the declaration.
  "a nested function" - {
    "walks its own tail, and reaches the frame it was declared in" in {
      val src =
        """outer(base: int)
          |    total(n: int, ...) -> int
          |        var ap: va_list
          |        va_start(ap)
          |        var t = base
          |        for i in 0..<n
          |            var v: int = va_arg(ap)
          |            t += v
          |        va_end(ap)
          |        t
          |    end total
          |
          |    print(total(3, 10, 20, 30))
          |
          |outer(1)""".stripMargin

      run(src) shouldBe "61\n"
    }

    // The environment is not a *named* parameter, so it does not stand in for one: the rule an
    // associated function's `make(...)` meets is the rule this meets.
    "with nothing named before it is refused, as anywhere else" in {
      err("outer()\n    make(...) -> int = 1\n    print(make(1))\nouter()") should
        include("needs at least one named parameter before '...'")
    }
  }

  "a member of a generic type" - {
    "may be variadic, and the tail stays out of the inference" in {
      val src =
        s"""$sumTail
           |struct Box[T]
           |    item: T
           |
           |    plus(self, n: int, ...) -> int
           |        var ap: va_list
           |        va_start(ap)
           |        var t = sum_tail(n, &ap)
           |        va_end(ap)
           |        t
           |    end plus
           |end Box
           |var b = Box(7)
           |print(b.plus(2, 3, 4))""".stripMargin

      run(src) shouldBe "7\n"
    }

    // A member's own type parameters are solved from the arguments that stand at a parameter, and
    // the tail stands at none — so what solves `T` here is the label alone.
    "a member's own type parameters are solved from the declared arguments only" in {
      val src =
        s"""$sumTail
           |struct S
           |    v: int
           |
           |    tag[T](self, label: T, n: int, ...) -> int
           |        var ap: va_list
           |        va_start(ap)
           |        var t = sum_tail(n, &ap)
           |        va_end(ap)
           |        t
           |    end tag
           |end S
           |var s = S(1)
           |print(s.tag("x", 2, 3, 4), s.tag(2.5, 1, 9))""".stripMargin

      run(src) shouldBe "7 9\n"
    }
  }

  "a trait" - {
    "may declare one, and an implementation supplies it" in {
      val src =
        s"""$sumTail
           |trait Log
           |    note(self, n: int, ...) -> int
           |
           |struct W
           |    base: int
           |
           |impl Log for W
           |    note(self, n: int, ...) -> int
           |        var ap: va_list
           |        va_start(ap)
           |        var t = sum_tail(n, &ap)
           |        va_end(ap)
           |        self.base + t
           |    end note
           |end W
           |print(W(100).note(3, 1, 2, 3))""".stripMargin

      run(src) shouldBe "106\n"
    }

    // A bound is where a variadic trait method is usable: the call is monomorphized, so it knows
    // which function it is reaching and can name its whole type.
    "reaches one through a bound" in {
      val src =
        s"""$sumTail
           |trait Log
           |    note(self, n: int, ...) -> int
           |
           |struct W
           |    base: int
           |
           |impl Log for W
           |    note(self, n: int, ...) -> int
           |        var ap: va_list
           |        va_start(ap)
           |        var t = sum_tail(n, &ap)
           |        va_end(ap)
           |        self.base + t
           |    end note
           |end W
           |through[T: Log](x: T) -> int = x.note(2, 1, 2)
           |print(through(W(100)))""".stripMargin

      run(src) shouldBe "103\n"
    }

    "a default body may walk the tail it was declared with" in {
      val src =
        s"""$sumTail
           |trait Loud
           |    shout(self, n: int, ...) -> int
           |        var ap: va_list
           |        va_start(ap)
           |        var t = sum_tail(n, &ap)
           |        va_end(ap)
           |        t * 10
           |    end shout
           |
           |struct W
           |    base: int
           |
           |impl Loud for W
           |end W
           |print(W(1).shout(2, 2, 3))""".stripMargin

      run(src) shouldBe "50\n"
    }

    // A `...` is part of what a caller may write, so an implementation with one where the trait has
    // none is a different promise rather than a wider one. Both directions.
    "an implementation must agree about the ellipsis" in {
      val trait_ = "trait Log\n    note(self, n: int, ...) -> int\n\nstruct W\n    base: int\n\n"

      err(trait_ + "impl Log for W\n    note(self, n: int) -> int = self.base + n\nend W\nprint(1)") should
        include("does not take a '...', but trait 'Log' declares one")

      val plain = "trait Log\n    note(self, n: int) -> int\n\nstruct W\n    base: int\n\n"

      err(plain + "impl Log for W\n    note(self, n: int, ...) -> int = self.base + n\nend W\nprint(1)") should
        include("takes a '...', but trait 'Log' declares none")
    }

    "but not through a trait object, and it says why" in {
      val src =
        """trait Log
          |    note(self, n: int, ...) -> int
          |
          |struct W
          |    base: int
          |
          |impl Log for W
          |    note(self, n: int, ...) -> int = self.base + n
          |end W
          |show(x: &Log) -> int = x.note(1, 2)
          |print(show(W(1)))""".stripMargin
      val out = err(src)

      out should include("'note' of 'Log' takes a '...'")
      out should include("a slot in a method table is a word and names none")
    }

    // A trait's members lower to nothing, so a promise nothing keeps is where a signature rule has
    // to be asked at the declaration or nowhere.
    "a promise nothing implements is held to the same rules" in {
      err("trait T\n    hold(self, ap: va_list) -> int\nprint(1)") should
        include("a va_list is a parameter as '*va_list'")
    }

    // Two implementations of one trait are told apart by the arguments (`08 § One name, one
    // member`), and a tail stands at no parameter — so what tells them apart stops where the
    // declared parameters do. Comparing the whole list would leave a variadic member unreachable
    // the moment anything was passed to its tail.
    "two implementations of one variadic member are told apart by the declared arguments" in {
      val src =
        """trait Sink[T]
          |    put(self, x: T, n: int, ...) -> int
          |
          |struct B
          |    v: int
          |
          |impl Sink[int] for B
          |    put(self, x: int, n: int, ...) -> int = self.v + x
          |end B
          |
          |impl Sink[string] for B
          |    put(self, x: string, n: int, ...) -> int = self.v
          |end B
          |print(B(1).put(5, 2, 7, 8), B(1).put("a", 1, 9))""".stripMargin

      run(src) shouldBe "6 1\n"
    }
  }

  "the call is checked the way a free function's is" - {
    "the declared parameters are still required" in {
      err(s"$acc\nvar a = Acc(1)\nprint(a.total())") should
        include("takes at least 1 argument, but 0 arguments were given")
    }

    "the declared parameters are still checked" in {
      err(s"""$acc\nvar a = Acc(1)\nprint(a.total("three", 1))""") should
        include("'n' of 'Acc.total' is int, but string was given")
    }

    "the tail carries only what varargs can carry" in {
      err(s"""$acc\nvar a = Acc(1)\nprint(a.total(1, "one"))""") should
        include("a string cannot be passed to a sysl function's '...'")
    }

    "and a narrow argument arrives promoted" in {
      run(s"$acc\nvar a = Acc(0)\nvar b: u8 = 200\nvar c: i8 = -5\nprint(a.total(2, b, c))") shouldBe "195\n"
    }

    "an associated function's tail is checked the same way" in {
      val decl =
        s"""$sumTail
           |struct Acc
           |    base: int
           |
           |    of(n: int, ...) -> Acc
           |        var ap: va_list
           |        va_start(ap)
           |        var t = sum_tail(n, &ap)
           |        va_end(ap)
           |        Acc(t)
           |    end of
           |end Acc""".stripMargin

      err(s"$decl\nprint(Acc.of().base)") should include("takes at least 1 argument")
      err(s"""$decl\nprint(Acc.of(1, "x").base)""") should
        include("a string cannot be passed to a sysl function's '...'")
    }
  }

  "what the grammar refuses" - {
    // The ellipsis is the end of a parameter list wherever one is written, so a parameter after it
    // is a parse error rather than a silently ignored one.
    "an ellipsis before a parameter" in {
      progError("struct S\n    v: int\n    add(self, ..., n: int) -> int = self.v") should not be empty
    }

    "a member with no parameter list at all is still a property" in {
      run("struct S\n    v: int\n    twice -> int = self.v * 2\nprint(S(21).twice)") shouldBe "42\n"
    }
  }
}
