package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** What a closure *does* (`12 §5`–`§8`): the two representations, what a body captures, and the
 * cases where the answer would be the same under a weaker rule and is not.
 */
class ClosureRunTests extends AnyFreeSpec with RunSupport with CodegenSupport {

  "a closure is passed to a parameter that calls it" - {
    "the bare arrow takes a closure and calls it" in {
      run("""apply(f: int -> int, x: int) -> int = f(x)
            |
            |print(apply(x -> x + 1, 5))
            |""".stripMargin) shouldBe "6\n"
    }

    "the parameter may be called more than once" in {
      run("""twice(f: int -> int, x: int) -> int = f(f(x))
            |
            |print(twice(x -> x * 2, 3))
            |""".stripMargin) shouldBe "12\n"
    }

    "two parameters are parenthesized, and none is the empty pair" in {
      run("""pair(f: (int, int) -> int, a: int, b: int) -> int = f(a, b)
            |now(f: () -> int) -> int = f()
            |
            |print(pair((a, b) -> a * b, 6, 7))
            |print(now(() -> 41 + 1))
            |""".stripMargin) shouldBe "42\n42\n"
    }

    "a closure yielding nothing is an ordinary one" in {
      run("""each3(f: int -> unit)
            |    for i in 0..<3 do f(i)
            |
            |each3(i -> print("i", i))
            |""".stripMargin) shouldBe "i 0\ni 1\ni 2\n"
    }

    "a method takes one the same way a function does" in {
      run("""struct Counter
            |    n: int
            |
            |    each(self, f: int -> unit)
            |        for i in 0..<self.n do f(i)
            |
            |var c = Counter(3)
            |
            |c.each(x -> print("x", x * x))
            |""".stripMargin) shouldBe "x 0\nx 1\nx 4\n"
    }

    "the parameter and result types may be a generic caller's, solved from its other arguments" in {
      // `A` and `B` come from the two slices, and only then is `A -> B` something the closure can
      // be read against — which is why the arguments are analyzed in two passes rather than one.
      run("""map_into[A, B](xs: []A, out: []B, f: A -> B)
            |    for i in 0..<xs.len do out[i] = f(xs[i])
            |
            |var src = [1, 2, 3]
            |var dst = [0u8; 3]
            |
            |map_into(src[..], dst[..], x -> u8(x * 10))
            |print(dst[0], dst[1], dst[2])
            |""".stripMargin) shouldBe "10 20 30\n"
    }

    "a declared function is the capture-free closure" in {
      run("""double(x: int) -> int = x * 2
            |apply(f: int -> int, x: int) -> int = f(x)
            |
            |print(apply(double, 5))
            |""".stripMargin) shouldBe "10\n"
    }

    "and a closure and a function reach the same parameter in one program" in {
      run("""double(x: int) -> int = x * 2
            |apply(f: int -> int, x: int) -> int = f(x)
            |
            |print(apply(double, 5), apply(x -> x + 1, 5))
            |""".stripMargin) shouldBe "10 6\n"
    }
  }

  "what a body captures" - {
    "a value is copied in when the closure is formed" in {
      run("""apply(f: int -> int, x: int) -> int = f(x)
            |
            |var n = 10
            |
            |print(apply(x -> x + n, 5))
            |""".stripMargin) shouldBe "15\n"
    }

    "a later change to the outer variable does not reach a closure already formed" in {
      // The whole of `§7`'s "the closure carries its own" is this line, and a capture that read the
      // variable instead of a copy of it would print 200.
      run("""struct Held
            |    f: &Fn(int) -> int
            |
            |var n = 100
            |var h = Held(x -> x + n)
            |
            |n = 200
            |print(h.f(0))
            |""".stripMargin) shouldBe "100\n"
    }

    "a counted reference is retained, so it outlives the scope that made it" in {
      run("""struct Node
            |    v: int
            |
            |made() -> &Fn(int) -> int
            |    var node: &Node = Node(9)
            |
            |    x -> x + node.v
            |
            |var f = made()
            |
            |print(f(1))
            |""".stripMargin) shouldBe "10\n"
    }

    "a parameter shadows an outer name of the same spelling" in {
      run("""apply(f: int -> int, x: int) -> int = f(x)
            |
            |var x = 999
            |
            |print(apply(x -> x + 1, 7))
            |""".stripMargin) shouldBe "8\n"
    }

    "a local of the body's own shadows it too, and the initializer still reads the outer one" in {
      run("""var n = 5
            |var f: &Fn(int) -> int = k ->
            |    var n = n * 2
            |    k + n
            |
            |print(f(1))
            |""".stripMargin) shouldBe "11\n"
    }

    "capture reaches through a closure inside a closure" in {
      run("""apply(f: int -> int, x: int) -> int = f(x)
            |
            |var k = 100
            |
            |print(apply(x -> apply(y -> y + k, x), 5))
            |""".stripMargin) shouldBe "105\n"
    }

    "a body that captures nothing captures nothing" in {
      // A closure over no names is an empty struct, so the call passes an environment with nothing
      // in it — the degenerate case `§5` says a named function already is.
      val out = ir("""apply(f: int -> int, x: int) -> int = f(x)
                     |
                     |print(apply(x -> x + 1, 5))
                     |""".stripMargin)

      out should include("%struct.$closure0 = type {  }")
    }

    "a write through a captured name reaches the closure's own field" in {
      // Two calls, and the second sees what the first left — the reading `§ Open c` prefers, where a
      // mutating closure is an ordinary one whose captures happen to be mutable.
      run("""var n = 0
            |var f: &Fn() -> int = () ->
            |    n = n + 1
            |    n
            |
            |print(f() + f())
            |""".stripMargin) shouldBe "3\n"
    }
  }

  "where a concrete type is required, the callable is boxed" - {
    "a closure is returned" in {
      run("""make_adder(n: int) -> &Fn(int) -> int
            |    x -> x + n
            |
            |var add5 = make_adder(5)
            |
            |print(add5(3))
            |""".stripMargin) shouldBe "8\n"
    }

    "a closure is stored in a field and called through it" in {
      run("""struct Button
            |    on_click: &Fn(int) -> unit
            |
            |var b = Button(x -> print("clicked", x))
            |
            |b.on_click(7)
            |""".stripMargin) shouldBe "clicked 7\n"
    }

    "an annotated local holds one" in {
      run("""var f: &Fn(int) -> int = x -> x * 3
            |
            |print(f(4))
            |""".stripMargin) shouldBe "12\n"
    }

    "a boxed callable dispatches through a table, and an inlined one does not" in {
      // The discriminating pair: both programs call a closure once, and what tells them apart is
      // whether the call goes through a slot. A bare-arrow parameter that boxed would emit the
      // `load` and the indirect `call` this one has, and the assertion would not hold.
      val direct = ir("""apply(f: int -> int, x: int) -> int = f(x)
                        |print(apply(x -> x + 1, 5))
                        |""".stripMargin)

      val boxed = ir("""var f: &Fn(int) -> int = x -> x + 1
                       |print(f(5))
                       |""".stripMargin)

      direct should not include "@vt."
      boxed should include("@vt.")
    }

    "several closures of one shape are several types" in {
      run("""apply(f: int -> int, x: int) -> int = f(x)
            |
            |print(apply(x -> x + 1, 10), apply(x -> x * 2, 10))
            |""".stripMargin) shouldBe "11 20\n"
    }

    "a boxed one is released with the last reference to it" in {
      // Ten thousand of them, each dropped at the end of its iteration: a closure whose box leaked
      // would grow the heap without bound rather than print.
      run("""make(n: int) -> &Fn(int) -> int
            |    x -> x + n
            |
            |var total = 0
            |
            |for i in 0..<10000
            |    var f = make(i)
            |    total += f(1)
            |
            |print(total)
            |""".stripMargin) shouldBe "50005000\n"
    }
  }

  "what a closure may not quietly be" - {
    "a parameter with nothing to infer it from is reported at the parameter" in {
      err("""var f = x -> x + 1
            |""".stripMargin) should include("'x' has no type here")
    }

    "and writing the annotation is what the message says to do" in {
      run("""var f: &Fn(int) -> int = (x: int) -> x + 1
            |
            |print(f(1))
            |""".stripMargin) shouldBe "2\n"
    }

    "a closure of the wrong arity is refused against what it is used as" in {
      err("""apply(f: int -> int, x: int) -> int = f(x)
            |
            |print(apply((a, b) -> a + b, 5))
            |""".stripMargin) should include("takes 2 parameters, and what it is being used as takes 1")
    }

    "a body yielding the wrong type is refused against the callable's result" in {
      err("""var f: &Fn(int) -> int = x -> "no"
            |""".stripMargin) should include("should yield int")
    }

    "a bare arrow in a concrete slot names the box it should have been" in {
      err("""struct Button
            |    on_click: int -> unit
            |""".stripMargin) should include("write '&Fn(int) -> unit'")
    }

    "a bare arrow as a result is the same refusal" in {
      err("""make() -> int -> int
            |    x -> x
            |""".stripMargin) should include("&Fn(int) -> int")
    }

    "'Fn' without a mode is a trait, and a trait is not a type" in {
      err("""var f: Fn(int) -> int = x -> x
            |""".stripMargin) should include("write '&Fn(int) -> int'")
    }

    "a callable wider than the library declares says where to go instead" in {
      err("""f(g: (int, int, int, int, int) -> int) -> int = 0
            |""".stripMargin) should include("pass a struct of them")
    }

    "and four is the widest one that is not" in {
      run("""four(f: (int, int, int, int) -> int) -> int = f(1, 2, 3, 4)
            |
            |print(four((a, b, c, d) -> a + b + c + d))
            |""".stripMargin) shouldBe "10\n"
    }

    "a chained bare arrow is refused by the rule that has no currying" in {
      // `§10` says a bare arrow type has a single domain, and the inner arrow here is a *result* —
      // a concrete slot — so the one rule about where a bare arrow may stand covers it.
      err("""curry(f: int -> int -> int) -> int = 0
            |""".stripMargin) should include("write '&Fn(int) -> int'")
    }

    "a local that is not callable, called anyway, is its own mistake" in {
      err("""var n = 5
            |
            |print(n(1))
            |""".stripMargin) should include("'n' is int and is not callable")
    }

    "a closure is not a value where an ordinary type is wanted" in {
      err("""take(n: int) -> int = n
            |
            |print(take(x -> x))
            |""".stripMargin) should include("'x' has no type here")
    }
  }

  "a closure is a function, so what a function's body may hold it may hold" - {
    "a match is a body" in {
      run("""var m: &Fn(int) -> int = x -> x match
            |    0 -> 100
            |    else x
            |
            |print(m(0), m(4))
            |""".stripMargin) shouldBe "100 4\n"
    }

    "a name bound by a match arm is captured like any other" in {
      run("""apply(f: int -> int, x: int) -> int = f(x)
            |
            |enum Shape
            |    Circle(r: int)
            |    Square(s: int)
            |
            |var sh = Circle(3)
            |
            |sh match
            |    Circle(r) -> print(apply(x -> x + r, 1))
            |    Square(s) -> print(s)
            |""".stripMargin) shouldBe "4\n"
    }

    "'return' leaves the closure, not the function around it" in {
      run("""var g: &Fn(int) -> int = x ->
            |    if x > 0 then return 1
            |    0
            |
            |print(g(5), g(-5))
            |""".stripMargin) shouldBe "1 0\n"
    }

    "'break' does not reach a loop outside it" in {
      // The loops a body may name are the ones it opened. A closure written inside a loop is still
      // a function, and `break` in a function that is not looping is the mistake it always was.
      err("""apply(f: int -> int, x: int) -> int = f(x)
            |
            |for i in 0..<3
            |    print(apply(x -> if x > 1 then break else x, i))
            |""".stripMargin) should include("'break' is only allowed inside a loop")
    }

    "leading contract clauses are its own" in {
      run("""var f: &Fn(int) -> int = x ->
            |    require x > 0, "positive"
            |    ensure result > x, "grew"
            |    x * 2
            |
            |print(f(3))
            |""".stripMargin) shouldBe "6\n"
    }

    "and a broken one stops the program" in {
      exits("""var f: &Fn(int) -> int = x ->
              |    require x > 0, "positive"
              |    x * 2
              |
              |print(f(-1))
              |""".stripMargin)
    }

    "a closure formed inside a loop is a fresh one each time round" in {
      run("""var total = 0
            |
            |for i in 0..<3
            |    var h: &Fn(int) -> int = x -> x + i
            |    total += h(0)
            |
            |print(total)
            |""".stripMargin) shouldBe "3\n"
    }

    "an escaping closure over a local array's slice keeps the elements alive" in {
      // The array is promoted to the heap by the escape analysis of `05`, and the closure's capture
      // is a view of what moved — so the slice is still good after the frame that declared it is
      // gone. Nothing here is closure machinery; that it composes is the point.
      run("""made() -> &Fn(int) -> int
            |    var a = [1, 2, 3]
            |    var v = a[..]
            |
            |    x -> x + v[1]
            |
            |var f = made()
            |
            |print(f(10))
            |""".stripMargin) shouldBe "12\n"
    }
  }

  "a program may make a type of its own callable" - {
    "written with the arrow, like every other callable type" in {
      run("""struct Doubler
            |    k: int
            |
            |impl Fn(int) -> int for Doubler
            |    call(*self, a: int) -> int = a * self.k
            |
            |var d = Doubler(3)
            |
            |print(d(5))
            |""".stripMargin) shouldBe "15\n"
    }

    "and it reaches both of the places a closure does" in {
      run("""struct Doubler
            |    k: int
            |
            |impl Fn(int) -> int for Doubler
            |    call(*self, a: int) -> int = a * self.k
            |
            |apply(f: int -> int, x: int) -> int = f(x)
            |
            |var d = Doubler(3)
            |var boxed: &Fn(int) -> int = d
            |
            |print(apply(d, 5), boxed(5))
            |""".stripMargin) shouldBe "15 15\n"
    }
  }

  "the callable's type reads as one wherever it is named" in {
    // The trait is filed under a name carrying its arity, and no diagnostic ever spells that.
    val message = err("""var f: &Fn(int) -> bool = 5
                        |""".stripMargin)

    message should include("&Fn(int) -> bool")
    message should not include "Fn1"
  }
}
