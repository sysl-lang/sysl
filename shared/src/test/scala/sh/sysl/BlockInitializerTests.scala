package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** An indented block under a binding's `=`, whose trailing expression is the value
 * (`00 § Continuing a line`).
 *
 * The form was missing for a reason that was itself the argument for building it: `=` is excluded
 * from the set of operators that carry a line onto the next one, and the reason `00` gives for the
 * exclusion is that `=` *already* means "an indented block starts here" — a body, a match arm. A
 * binding's `=` was neither of those, so it was excluded on the strength of introducing a block and
 * then introduced none. What a reader got for writing the natural thing was `expression expected`
 * pointing at the `=`.
 *
 * **The value rules are a value block's, unchanged**, which is the whole of the design: the trailing
 * expression is the value, a block ending in a jump is `never`, one ending in neither is `unit`, and
 * the names it binds are its own. Nothing here is a rule of this form's — every one of them is
 * already what a function body and an `if` branch do, which is what makes the feature a place to put
 * a block rather than a second kind of block.
 *
 * Two decisions are pinned below because nothing else would notice them changing. **A block of one
 * expression is that expression**, collapsed in the parser: a module `val` whose value moved to the
 * next line to fit the margin is still a constant tree, rather than becoming a computed initializer
 * on a question of layout. And **a `const` is refused one**, with the rule rather than a verdict —
 * the grammar reads it there precisely so the analyzer can say why.
 */
class BlockInitializerTests extends AnyFreeSpec with CodegenSupport with RunSupport {

  "the form the card asked for" - {

    "a 'val' takes an indented block, whose trailing expression is the value" in {
      val src =
        """val limits =
          |    val raw = 7
          |    val capped = raw * 2
          |    capped
          |print(limits)""".stripMargin

      run(src) shouldBe "14\n"
    }

    "a 'var' takes one too" in {
      val src =
        """var total =
          |    var t = 0
          |    for i in 1..5 do t += i
          |    t
          |total += 100
          |print(total)""".stripMargin

      run(src) shouldBe "115\n"
    }

    // Not an `if`-specific gap: a plain expression on the next line failed identically, which is
    // what said the missing thing was the block rather than anything about branching.
    "a single expression on the next line" in {
      run("val x =\n    1 + 2\nprint(x)") shouldBe "3\n"
    }

    // The shape that produced the report: the natural way to write a long conditional initializer is
    // to put the `if` on the line under the `=`, and that was the one thing refused.
    "an 'if' on the next line, which is what the report was about" in {
      val src =
        """pick(c: bool) -> int
          |    val n =
          |        if c then
          |            val a = 3
          |            a + 1
          |        else
          |            0
          |    n * 10
          |print(pick(true))
          |print(pick(false))""".stripMargin

      run(src) shouldBe "40\n0\n"
    }

    "a 'match' on the next line" in {
      val src =
        """describe(n: int) -> string
          |    val word =
          |        n match
          |            0 -> "none"
          |            1 -> "one"
          |            _ -> "many"
          |    word
          |print(describe(0), describe(1), describe(9))""".stripMargin

      run(src) shouldBe "none one many\n"
    }

    // A destructuring binding is a binding, so its `=` takes the same block.
    "a pattern binding takes one" in {
      val src =
        """val (lo, hi) =
          |    val a = 3
          |    (a, a * a)
          |print(lo, hi)""".stripMargin

      run(src) shouldBe "3 9\n"
    }
  }

  "the block's scope is its own" - {

    "a name bound inside does not escape" in {
      err("val x =\n    val hidden = 1\n    hidden\nprint(hidden)") should include("hidden")
    }

    "a name bound outside is readable inside" in {
      run("val base = 10\nval x =\n    val step = 4\n    base + step\nprint(x)") shouldBe "14\n"
    }

    // Two blocks binding the same name is the property that says each opened a scope rather than
    // spilling into the one around it.
    "two blocks may bind the same name" in {
      val src =
        """val a =
          |    val t = 1
          |    t
          |val b =
          |    val t = 2
          |    t * 10
          |print(a, b)""".stripMargin

      run(src) shouldBe "1 20\n"
    }

    "a 'var' inside the block shadows one outside it, and the outer one is unchanged" in {
      val src =
        """var n = 1
          |val x =
          |    var n = 50
          |    n += 1
          |    n
          |print(n, x)""".stripMargin

      run(src) shouldBe "1 51\n"
    }
  }

  "the value rules are a value block's" - {

    // The declared type reaches the trailing expression rather than the block, which is what lets a
    // literal be written without a suffix — the same courtesy a branch gets.
    "the declared type is pushed down to the trailing expression" in {
      val src =
        """val big: u8 =
          |    val why = "the top of a byte"
          |    print(why)
          |    255
          |print(big)""".stripMargin

      run(src) shouldBe "the top of a byte\n255\n"
    }

    // A block that leaves rather than arriving is `never`, so the code after it is unreachable and
    // the binding never happens — exactly what a branch ending in a `return` already is.
    "a block ending in a jump is 'never'" in {
      val src =
        """early(c: bool) -> int
          |    val n =
          |        if c then 5
          |        else
          |            return -1
          |    n * 2
          |print(early(true))
          |print(early(false))""".stripMargin

      run(src) shouldBe "10\n-1\n"
    }

    // A block with no trailing expression is `unit`, which is what an `if` with no `else` already
    // binds — so the complaint arrives at the use rather than at the binding. The last line has to
    // be a *declaration* for there to be no trailing expression at all: an assignment and an `i++`
    // are expressions, and a block ending in one is that value.
    "a block with no trailing expression is 'unit'" in {
      val src =
        """val nothing =
          |    var t = 0
          |    var u = t + 1
          |print(nothing)""".stripMargin

      err(src) should include("unit")
    }

    "a block nests inside a block" in {
      val src =
        """val x =
          |    val inner =
          |        val a = 2
          |        a * 3
          |    inner + 1
          |print(x)""".stripMargin

      run(src) shouldBe "7\n"
    }

    // A converting context belongs to the expression the value comes from, so the box is built
    // around the trailing expression and not around the statement list.
    "a context that converts reaches the trailing expression" in {
      val src =
        """struct Point
          |    x: int
          |    y: int
          |val p: &Point =
          |    val n = 3
          |    Point(n, n * 2)
          |print(p.x, p.y)""".stripMargin

      run(src) shouldBe "3 6\n"
    }
  }

  "a block of one expression is that expression" - {

    // The collapse, seen from outside: a module `val` whose value moved to the next line is still
    // laid into the object file, rather than becoming code that runs before `main` because of where
    // the line broke.
    "so a module 'val' written this way is still a constant tree" in {
      val out = ir("static val k: int =\n    42\nprint(k)")

      out should include("@k = private constant i32 42")
      out should not include "@k = private global"
    }

    "and one whose block binds something is computed storage" in {
      val out = ir("static val k: int =\n    val a = 6\n    a * 7\nprint(k)")

      out should include("@k = private global i32 zeroinitializer")
    }

    "a computed one still runs before the program's own statements" in {
      val src =
        """static val k: int =
          |    print("built")
          |    7
          |print(k)""".stripMargin

      run(src) shouldBe "built\n7\n"
    }
  }

  // The block is a scope, and everything that already keys off a scope keys off this one. None of
  // these is a rule of the form's — each is what a branch's block already does — but nothing else in
  // the suite would notice them stopping.
  "it is a scope like any other block" - {

    "counted locals are released at the end of it, before the value is used" in {
      val src =
        """struct Node
          |    v: int
          |impl Drop for Node
          |    drop(self) = print("dropped", self.v)
          |sum() -> int
          |    val n =
          |        val a: &Node = Node(1)
          |        val b: &Node = Node(2)
          |        a.v + b.v
          |    print("after", n)
          |    n
          |print(sum())""".stripMargin

      run(src) shouldBe "dropped 2\ndropped 1\nafter 3\n3\n"
    }

    // `03 § defer` puts a deferred statement at the end of its *block*, and this is a block — so it
    // runs before the value is bound rather than at the end of the function.
    "a 'defer' inside it runs at the end of it" in {
      val src =
        """f() -> int
          |    val n =
          |        defer print("deferred")
          |        print("in")
          |        5
          |    print("after", n)
          |    n
          |print(f())""".stripMargin

      run(src) shouldBe "in\ndeferred\nafter 5\n5\n"
    }

    // A block initializer inside a loop body is not itself a loop, so a `break` written *after* one
    // reaches the loop — and the block bound its value on every round before that.
    "a loop around it still breaks with a value" in {
      val src =
        """f() -> int
          |    var i = 0
          |    val found =
          |        loop
          |            i += 1
          |            val step =
          |                val doubled = i * 2
          |                doubled
          |            if step > 6 then break step
          |    found
          |print(f())""".stripMargin

      run(src) shouldBe "8\n"
    }

    "a closure body holds one, and it closes over the parameter" in {
      val src =
        """f() -> int
          |    var g = (x: int) ->
          |        val bumped =
          |            val one = 1
          |            x + one
          |        bumped * 10
          |    g(4)
          |print(f())""".stripMargin

      run(src) shouldBe "50\n"
    }
  }

  "a 'const' is refused one, with the rule" in {
    val out = err("const C: int =\n    val a = 1\n    a + 1\nprint(C)")

    out should include("folded into every use")
    out should include("Write a 'val'")
  }

  "what still ends the statement" - {

    // The cost the form accepts: a value forgotten and a line indented under it is a block now,
    // where it used to be this error. What has *not* moved is the case with nothing indented under
    // it, which is the one a mistyped line actually produces.
    "a binding with nothing after the '=' is still refused" in {
      err("val x =\nprint(1)") should include("expression expected")
    }

    "and one at the end of the file" in {
      err("val x =") should include("expression expected")
    }

    // The other half of the look-ahead: once the indent is there the block has begun, so a mistake
    // inside it is reported inside it rather than back at the `=`.
    "a mistake inside a block that did begin is reported where it was made" in {
      val src =
        """f() -> int
          |    val x =
          |        val y = 1
          |        ]
          |    x
          |print(f())""".stripMargin

      val out = err(src)

      out should include("4 |")
      out should not include "2 |     val x ="
    }
  }
}
