package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** `_` where a closure's parameter would be named (`reference/expressions.md § _ — a parameter with
 * the name left out`).
 *
 * The feature is one operand and a boundary rule, and the boundary is the whole of it: a
 * placeholder compiles under either reading of where its closure ends, so a test that only shows
 * the closure works shows nothing about the part that was designed. Every test here that names a
 * boundary has the reading it is *not* beside it.
 */
class PlaceholderTests extends AnyFreeSpec with RunSupport with CodegenSupport {

  /** Takes a closure and calls it, which is what gives the placeholder a type to infer from. */
  private val apply = "apply(f: int -> int, x: int) -> int = f(x)\n"

  private val pair = "pair(f: (int, int) -> int, a: int, b: int) -> int = f(a, b)\n"

  "a placeholder is the parameter of the closure it stands in" - {

    "one occurrence is one parameter" in {
      run(apply + "print(apply(_ + 1, 5))\n") shouldBe "6\n"
    }

    // Two occurrences are two parameters rather than one used twice, so the test has to be one
    // whose answer depends on the order: `_ + _` on (7, 2) is 9 either way, and would have passed
    // against a closure that bound one parameter and read it twice.
    "two occurrences are two parameters, left to right" in {
      run(pair + "print(pair(_ - _, 7, 2))\n") shouldBe "5\n"
    }

    "and a closure that wants its argument twice is still written with the arrow" in {
      run(apply + "print(apply(x -> x * x, 6))\n") shouldBe "36\n"
    }

    "an operator reaches out through the placeholder, so a prefix one is inside the body" in {
      run(apply + "print(apply(-_, 5))\n") shouldBe "-5\n"
    }

    "field selection is inside the body" in {
      run("""struct Row
            |    stamp: int
            |
            |take(f: Row -> int, r: Row) -> int = f(r)
            |
            |print(take(_.stamp, Row(41)))
            |""".stripMargin) shouldBe "41\n"
    }

    "a method call on it is inside the body, the receiver position being no boundary" in {
      run("""struct Row
            |    stamp: int
            |
            |    doubled(self) -> int = self.stamp * 2
            |
            |take(f: Row -> int, r: Row) -> int = f(r)
            |
            |print(take(_.doubled(), Row(21)))
            |""".stripMargin) shouldBe "42\n"
    }

    // `reference/arrays.md § Length` and the boundary rule meeting: a subscript does not end the
    // closure, so the array is captured and the placeholder is the index.
    "a subscript is inside the body, and the array around it is captured" in {
      run(apply + """val table: [4]int = [10, 20, 30, 40]
                    |
                    |print(apply(table[_], 2))
                    |""".stripMargin) shouldBe "30\n"
    }
  }

  "a bare '_' at an argument is absorbed by the call it is an argument of" - {

    "the first argument left open" in {
      run(apply + """sub(a: int, b: int) -> int = a - b
                    |
                    |print(apply(sub(_, 3), 10))
                    |""".stripMargin) shouldBe "7\n"
    }

    // The discriminating half of the pair: the same call with the hole on the other side has to
    // give the other answer, or nothing has shown which argument the parameter reached.
    "the second argument left open" in {
      run(apply + """sub(a: int, b: int) -> int = a - b
                    |
                    |print(apply(sub(3, _), 10))
                    |""".stripMargin) shouldBe "-7\n"
    }

    "both left open, in the order they were written" in {
      run(pair + """sub(a: int, b: int) -> int = a - b
                   |
                   |print(pair(sub(_, _), 7, 2))
                   |""".stripMargin) shouldBe "5\n"
    }

    "and a named argument leaves its own parameter open, not the one in that position" in {
      run(apply + """sub(a: int, b: int) -> int = a - b
                    |
                    |print(apply(sub(b = _, a = 3), 10))
                    |""".stripMargin) shouldBe "-7\n"
    }
  }

  "the three boundaries, each with the reading it is not" - {

    // An argument ends the closure, so `map` is handed one rather than the whole call becoming
    // one. Under the other reading this would be `x -> apply(x + 1, 5)`, which `print` could not
    // take, so the test discriminates by compiling at all.
    "a call argument ends it, so the callee is handed the closure" in {
      run(apply + "print(apply(_ + 1, 5))\n") shouldBe "6\n"
    }

    // Nothing between the placeholder and the argument ends the closure, so the whole arithmetic
    // is the body: `_ + 1 * 2` is `x -> x + 2`, not `(x -> x + 1) * 2`.
    "an operator does not, so precedence alone decides the body" in {
      run(apply + "print(apply(_ + 1 * 2, 5))\n") shouldBe "7\n"
    }

    // ... and the parenthesized group is how a program says otherwise. Here that closes the
    // closure at the `)`, leaving it as an operand of `*` where nothing says what it takes — so
    // the refusal naming the placeholder IS the evidence that the parentheses were obeyed. Under
    // the operator reading the argument would have been `x -> (x + 1) * 2` and compiled.
    "a parenthesized group does, which is how the operator reading is overridden" in {
      err(apply + "print(apply((_ + 1) * 2, 5))\n") should include(
        "this '_' has no type here — nothing says what the closure it stands in takes",
      )
    }

    "a statement-level expression ends it, so a binding may hold one" in {
      run("""var g: &Fn(int) -> int = _ * 3
            |print(g(14))
            |""".stripMargin) shouldBe "42\n"
    }

    "and a closure's own body ends it, so an arrow over a placeholder yields a closure" in {
      run("""outer(f: int -> &Fn(int) -> int, a: int, b: int) -> int = f(a)(b)
            |
            |print(outer(x -> _ + x, 40, 2))
            |""".stripMargin) shouldBe "42\n"
    }
  }

  "what it does not reach, said as the diagnostic a reader gets" - {

    // The placeholder is an ordinary closure by the time anything types it, so a position that
    // says nothing about what it takes gives a complaint of the same shape an un-annotated arrow
    // gets — but not the same words, since the advice to annotate names a parameter the compiler
    // made up and the program has no way to write.
    "a binding with nothing to infer from is refused, and is not told to annotate '$ph'" in {
      val out = err("var h = _ * 3\n")

      out should include("this '_' has no type here")
      out should include("write that closure with a named parameter")
      out should not include "$ph"
    }

    // The named form keeps the advice it had, so the placeholder's message is an addition and not
    // a replacement.
    "while a written parameter is still told to annotate itself, by name" in {
      err("var h = x -> x * 3\n") should include("'x' has no type here")
    }

    // An array element is inside the body rather than a boundary, so this is one closure yielding
    // an array — which the annotation then contradicts. `[(_ + 1)]` is how the other is written.
    "an array element is inside the body, so an array OF closures needs the group" in {
      run("""var fs: [1]&Fn(int) -> int = [(_ + 1)]
            |print(fs[0](41))
            |""".stripMargin) shouldBe "42\n"
    }
  }

  "'_' in a pattern is untouched, the two never standing in one position" - {

    "a match arm's wildcard is still a wildcard" in {
      run("""classify(n: int) -> string
            |    n match
            |        0 -> "zero"
            |        _ -> "other"
            |
            |print(classify(0), classify(7))
            |""".stripMargin) shouldBe "zero other\n"
    }

    "and one file may use both meanings without either noticing" in {
      run(apply + """classify(n: int) -> int
                    |    n match
                    |        0 -> 100
                    |        _ -> n
                    |
                    |print(apply(classify(_), 7), classify(0))
                    |""".stripMargin) shouldBe "7 100\n"
    }

    // The sharpest version: one statement holding both, the scrutinee being a placeholder and an
    // arm being a wildcard. A production that took the other's token breaks here rather than in a
    // file where the two merely coexist.
    //
    // It is written at statement level because a bracket used to suspend the off-side rule outright,
    // so the arms could not be indented inside an argument list. Card `0248` gave `match` and `->` a
    // block wherever they are written, so that reason has gone — the shape is kept because it is the
    // one this test is about, not because the other is refused.
    "even in one statement, whose scrutinee is a placeholder and whose last arm is a wildcard" in {
      run("""var f: &Fn(int) -> int = _ match
            |    0 -> 100
            |    _ -> 7
            |
            |print(f(0), f(5))
            |""".stripMargin) shouldBe "100 7\n"
    }
  }

  "a lifted closure is the one the arrow would have produced" - {

    // The claim the implementation rests on: nothing new reaches any later pass, so a placeholder
    // closure inside a generic body survives the tree encoding a library artifact carries — which
    // it could not if the placeholder were a node of its own without a codec tag.
    "including through the tree a library artifact carries" in {
      run("""twice[T](f: T -> T, x: T) -> T = f(f(x))
            |
            |print(twice(_ + 1, 5))
            |print(twice(_ + "!", "hi"))
            |""".stripMargin) shouldBe "7\nhi!!\n"
    }

    "and it captures what an arrow there would have captured" in {
      run(apply + """val base: int = 100
                    |
                    |print(apply(_ + base, 5))
                    |""".stripMargin) shouldBe "105\n"
    }

    // `§8` decides representation by escape, and a returned closure is the escaping case — a
    // different mechanism from the one every test above takes, so passing there is no evidence
    // about passing here.
    "including the escaping representation, where the closure outlives the body that made it" in {
      run("""adder(n: int) -> &Fn(int) -> int = _ + n
            |
            |var add40 = adder(40)
            |print(add40(2))
            |""".stripMargin) shouldBe "42\n"
    }

    // `§2a`'s default is produced afresh at each call that omits it, and it is read at the type its
    // own parameter declares — so a placeholder there stands for what the parameter takes, exactly
    // as one written at the call does. **This is not about placeholders**: the arrow form beside it
    // is read the same way, which is what says the rule is `§2a`'s and not `§5c`'s. Both are
    // asserted so that neither can quietly stop working while the other goes on.
    //
    // Both were refusals until the type was pushed into a default, and they were the pair that
    // proved the gap belonged to `§2a`.
    "and a default parameter value, where the parameter's own type says what it stands for" in {
      run("""go(x: int, f: int -> int = _ * 2) -> int = f(x)
            |print(go(21))
            |""".stripMargin) shouldBe "42\n"
    }

    "which is the arrow form's answer there too, so the rule is the default's and not the placeholder's" in {
      run("""go(x: int, f: int -> int = y -> y * 2) -> int = f(x)
            |print(go(21))
            |""".stripMargin) shouldBe "42\n"
    }

    "though an argument that is written out still reaches the parameter" in {
      run("""go(x: int, f: int -> int) -> int = f(x)
            |print(go(21, _ * 2))
            |""".stripMargin) shouldBe "42\n"
    }

    // `§5a`'s nested function is the one place a callable is not a closure literal, so its body is
    // worth asking about separately.
    "and inside a nested function's body" in {
      run(apply + """outer(x: int) -> int
                    |    inner(n: int) -> int = apply(_ + n, x)
                    |    inner(2)
                    |
                    |print(outer(40))
                    |""".stripMargin) shouldBe "42\n"
    }
  }

  "the edge cases, each of which compiles under a rule that is not the one written" - {

    // Two placeholders in one expression that belong to *different* closures, because an inner
    // argument closed one of them. A lift that collected them together would give the outer
    // closure two parameters and the inner none.
    "two closures in one expression, each taking the placeholder nearest it" in {
      run(apply + "print(apply(_ + apply(_ * 10, 3), 5))\n") shouldBe "35\n"
    }

    // A closure literal is not callable where it stands, so a partial application has to be bound
    // before it is called. The arrow form beside it is refused identically, which is what says
    // this is `§5`'s limit on a literal callee rather than anything the placeholder introduced.
    "a partial application is bound before it is called, a literal callee being refused" in {
      run("""sub(a: int, b: int) -> int = a - b
            |
            |var take3: &Fn(int) -> int = sub(_, 3)
            |print(take3(10))
            |""".stripMargin) shouldBe "7\n"
    }

    "and calling one where it stands is refused, as calling any closure literal is" in {
      val placeholder = err("sub(a: int, b: int) -> int = a - b\nprint(sub(_, 3)(10))\n")
      val arrow       = err("sub(a: int, b: int) -> int = a - b\nprint((x -> sub(x, 3))(10))\n")

      placeholder should include("must be a name, or something whose type says it is callable")
      arrow should include("must be a name, or something whose type says it is callable")
    }

    // The parentheses that delimit a tuple are the ones that end the closure, so the whole tuple
    // is the body rather than each part being its own closure.
    "a tuple in parentheses is lifted whole, not part by part" in {
      run("""take(f: int -> (int, int), x: int) -> int
            |    val t = f(x)
            |    t.0 + t.1
            |
            |print(take((_ + 1, 2), 39))
            |""".stripMargin) shouldBe "42\n"
    }

    // A hole is its own little source with its own parser, so it is a boundary — otherwise a `_`
    // inside one and a `_` outside the string are numbered from zero independently and the lift
    // builds a parameter list naming one thing twice.
    "an interpolation hole is a boundary, so a placeholder cannot close over the whole string" in {
      err("""print(s"${_ + 1}")""" + "\n") should include("this '_' has no type here")
    }

    // The collision the boundary exists to prevent: the sub-parser numbers from zero, so without
    // it this expression holds two placeholders both named `$ph1` and the lift builds a parameter
    // list naming one thing twice — the outer one then reading the inner one's argument.
    "and a placeholder outside a string is unaffected by a hole in the same expression" in {
      run(apply + """print(apply(_ + 1, 5), s"${2 + 2}")""" + "\n") shouldBe "6 4\n"
    }

    // A placeholder standing alone as a statement is a closure of one parameter yielding it, with
    // nothing to say what it takes — a refusal rather than a crash, which is the only thing the
    // lift promises about a position nobody would write.
    "a placeholder alone as a statement is refused rather than crashing" in {
      err("_\n") should include("this '_' has no type here")
    }
  }
}
