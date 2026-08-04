package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** `n @ pat` — a pattern that matches and **names what it matched**.
 *
 * A pattern that takes a value apart leaves the arm holding only the parts, so an arm that also
 * wants the whole had to choose: destructure and lose it, or bind it and test the shape a second
 * time. This is both at once.
 *
 * The `@` is the character an annotation opens with, and the pair being unambiguous is the reason
 * the annotation rename could take that character at all — so the last section here is not a
 * curiosity, it is the claim the two features rest on together.
 */
class AtPatternTests extends AnyFreeSpec with CodegenSupport with RunSupport {

  private val shape =
    """enum Shape
      |    Circle(r: int)
      |    Rect(w: int, h: int)
      |""".stripMargin

  "the whole value and its parts, in one pattern" - {
    /** The thing that could not be written before: an arm that reads a field *and* hands the value
     * on. Both are asserted in one run, so a version binding only the part or only the whole fails.
     */
    "an arm names what it destructured" in {
      run(
        shape +
          """area(s: Shape) -> int
            |    s match
            |        Circle(r) -> r * r * 3
            |        Rect(w, h) -> w * h
            |
            |describe(s: Shape) -> string
            |    s match
            |        c @ Circle(r) -> "circle r=" + str(r) + " area=" + str(area(c))
            |        other -> "other area=" + str(area(other))
            |
            |print(describe(Circle(2)))""".stripMargin) shouldBe "circle r=2 area=12\n"
    }

    "a struct pattern binds the whole beside its fields" in {
      run(
        """struct Point
          |    x: int
          |    y: int
          |
          |sum(p: Point) -> int = p.x + p.y
          |
          |var p = Point(3, 4)
          |
          |p match
          |    whole @ Point{x, y} -> print(x, y, sum(whole))""".stripMargin) shouldBe "3 4 7\n"
    }

    // The form nests, because what follows the `@` is an ordinary pattern and this is one.
    "and it nests, each level naming its own value" in {
      run(
        """enum Wrap
          |    Val(n: int)
          |    Two(a: int, b: int)
          |enum Outer
          |    One(w: Wrap)
          |
          |inner_n(w: Wrap) -> int
          |    w match
          |        Val(n) -> n
          |        Two(a, b) -> a + b
          |
          |var o = One(Val(7))
          |
          |o match
          |    whole @ One(part @ Val(n)) -> print(n, inner_n(part), inner_n(part) + n)
          |    One(other) -> print(inner_n(other))""".stripMargin,
      ) shouldBe "7 7 14\n"
    }

    "a literal may be named too, which is where a guard would otherwise repeat it" in {
      run(
        """classify(n: int) -> string
          |    n match
          |        z @ 0 -> "zero:" + str(z)
          |        s @ 1..3 -> "small:" + str(s)
          |        big -> "big:" + str(big)
          |
          |print(classify(0), classify(2), classify(9))""".stripMargin) shouldBe "zero:0 small:2 big:9\n"
    }

    // A binding is not a test, so an arm written this way is the arm without it as far as coverage
    // is concerned — no `else` is owed, and none is accepted as unreachable either.
    "a named arm still counts as covering its variant" in {
      run(
        shape +
          """name(s: Shape) -> string
            |    s match
            |        c @ Circle(_) -> "c"
            |        r @ Rect(_, _) -> "r"
            |
            |print(name(Circle(1)), name(Rect(2, 3)))""".stripMargin) shouldBe "c r\n"
    }

    "and a match that leaves one out is still refused when the rest are named" in {
      err(
        shape +
          """name(s: Shape) -> string
            |    s match
            |        c @ Circle(_) -> "c"
            |print(name(Circle(1)))""".stripMargin) should include("Rect")
    }
  }

  "where else a pattern is read" - {
    /** `is` takes a pattern, so it takes this one — and the binding reaches the branch the test
     * guards, which is what makes the form worth having there rather than only in a `match`.
     */
    "an 'is' test binds the whole value into the branch it guards" in {
      run(
        shape +
          """var s: Shape = Circle(5)
            |
            |if s is c @ Circle(r) then print("yes", r) else print("no")""".stripMargin) shouldBe "yes 5\n"
    }

    // A binding declaration takes an irrefutable pattern, and `n @ pat` is refutable exactly where
    // `pat` is — so a struct destructuring stays irrefutable with a name on it.
    "a 'var' binding names the whole and the parts" in {
      run(
        """struct Point
          |    x: int
          |    y: int
          |
          |var p = Point(3, 4)
          |var whole @ Point{x, y} = p
          |
          |print(whole.x, x, y)""".stripMargin) shouldBe "3 3 4\n"
    }

    "while a refutable inner pattern is refused there, exactly as it is without the name" in {
      err(
        shape +
          """var s: Shape = Circle(1)
            |var c @ Circle(r) = s""".stripMargin) should include("Circle")
    }
  }

  "what it refuses" - {
    // What a binding introduces is a local, and a name with a dot in it is not a name a program can
    // declare — so a qualified name before an `@` is a mistake about what is being bound.
    "a qualified name cannot be bound" in {
      err(
        shape +
          """var s: Shape = Circle(1)
            |s match
            |    m.x @ Circle(r) -> print(r)
            |    else print(0)""".stripMargin) should
        include("has a dot in it, and what a binding introduces is a local")
    }

    /** A name written twice in one pattern. The *inner* one is the repeat, because the outer is
     * declared first — which is the order a reader meets them in.
     *
     * This refusal did not exist before the `@` form was built, and the gap it closes is **older
     * than the form**: `Rect(v, v)` compiled and quietly bound the second `v`, which reads like a
     * test that the two fields are equal and is not one. It was found by probing this feature and
     * fixed where it lives, so the plain case below is pinned beside the one that found it.
     */
    "a name bound twice in one pattern is refused" in {
      err(
        shape +
          """var s: Shape = Circle(1)
            |s match
            |    v @ Circle(v) -> print(v)
            |    else print(0)""".stripMargin) should
        include("'v' is bound twice in one pattern")
    }

    "including the plain case the '@' form has nothing to do with" in {
      err(
        shape +
          """var s: Shape = Rect(1, 2)
            |s match
            |    Rect(v, v) -> print(v)
            |    else print(0)""".stripMargin) should
        include("'v' is bound twice in one pattern")
    }

    // Two *alternatives* are two patterns, so a name in each is not a repeat — they are refused for
    // the separate reason that a body cannot know which alternative matched.
    "while two alternatives naming one thing are refused for the other reason" in {
      err(
        shape +
          """var s: Shape = Circle(1)
            |s match
            |    Circle(v) | Rect(v, _) -> print(v)
            |    else print(0)""".stripMargin) should include("cannot bind a name")
    }

    // And a name reused in a *different* arm is ordinary, each arm being its own scope.
    "and a name reused in another arm is ordinary" in {
      run(
        shape +
          """var s: Shape = Rect(3, 4)
            |s match
            |    Circle(v) -> print(v)
            |    Rect(v, _) -> print(v)""".stripMargin) shouldBe "3\n"
    }
  }

  /** **The two `@`s do not compete**, which is what let the annotation rename take the character.
   *
   * An annotation's `@` is a prefix, on its own line above a declaration; a pattern's is infix,
   * between a name and a pattern. No declaration may stand where a pattern is read, so neither
   * position can be reached by the other form — and this is one program using both, which is the
   * only way to say that and be believed.
   */
  "the annotation sigil and the pattern sigil in one program" in {
    run(
      shape +
        """@tailrec
          |count(s: Shape, n: int) -> int
          |    s match
          |        c @ Circle(r) -> if n <= 0 then r else count(c, n - 1)
          |        Rect(w, h) -> w * h
          |
          |print(count(Circle(9), 3))""".stripMargin) shouldBe "9\n"
  }
}
