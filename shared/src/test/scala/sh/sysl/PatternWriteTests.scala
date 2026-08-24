package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** A name bound by a **pattern** is written once — in a `match` arm, in an `is`, and under `@`.
 *
 * The reason is the copy. A pattern hands the arm the part it matched rather than a way back into
 * the value it came out of, so `Full(n) -> n = 99` reads as though it changed the enum, compiles,
 * and does nothing that any later line can observe. Refusing it is what turns a misleading no-op
 * into a sentence.
 *
 * **The language already said so in the spelling that carries a keyword**, which is what settles it:
 * `var (a, b)` binds parts that may be assigned and `val (a, b)` binds parts that may not, and an
 * arm has no keyword at all. A name with no `var` in front of it is written once everywhere else in
 * sysl, and the arm was the one place that was not true.
 *
 * So what these assert is one rule reaching four surfaces — assignment, a compound assignment, `&`,
 * and a `*self` method — through every spelling that binds, and a diagnostic that names the
 * **pattern** rather than the `val` the reader did not write.
 */
class PatternWriteTests extends AnyFreeSpec with CodegenSupport with RunSupport {

  private val cell =
    """enum Cell
      |    Empty
      |    Full(v: int)
      |
      |""".stripMargin

  /** A struct behind a variant, so a write can be aimed at a *field* of what the pattern bound. */
  private val shape =
    """struct P
      |    x: int
      |    y: int
      |
      |enum Shape
      |    Nothing
      |    At(p: P)
      |
      |""".stripMargin

  "the write is refused, whichever spelling bound the name" - {

    "a match arm's capture" in {
      err(
        cell +
          """var d: Cell = Full(1)
            |
            |d match
            |    Full(n) -> n = 99
            |    Empty -> print("empty")""".stripMargin
      ) should include("a pattern binding is written once")
    }

    "an 'is' binding, which is the one-arm match written without the arm" in {
      err(
        cell +
          """var d: Cell = Full(1)
            |
            |if d is Full(m)
            |    m = 77
            |    print(m)""".stripMargin
      ) should include("a pattern binding is written once")
    }

    "the name an '@' puts in front of a pattern" in {
      err(
        cell +
          """var d: Cell = Full(1)
            |
            |d match
            |    w @ Full(n) -> w = Empty
            |    Empty -> print("empty")""".stripMargin
      ) should include("a pattern binding is written once")
    }

    // A bare name in an arm is a binding when nothing answers to it, and it holds the whole value
    // rather than a part — the copy is the same copy, and so is the rule.
    "a bare name, which binds the whole value rather than a part" in {
      err(
        cell +
          """var d: Cell = Full(1)
            |
            |d match
            |    Full(n) -> print(n)
            |    other -> other = Full(2)""".stripMargin
      ) should include("a pattern binding is written once")
    }

    "a compound assignment, which is a write with a read in front of it" in {
      err(
        cell +
          """var d: Cell = Full(1)
            |
            |d match
            |    Full(n) -> n += 1
            |    Empty -> print("empty")""".stripMargin
      ) should include("a pattern binding is written once")
    }

    // Reaching *into* the binding keeps the property, exactly as reaching into a `val` does: the
    // field is a field of the arm's copy, so a store to it is discarded with the copy.
    "a field of what the pattern bound" in {
      err(
        shape +
          """var s: Shape = At(P(1, 2))
            |
            |s match
            |    At(p) -> p.x = 9
            |    Nothing -> print("nothing")""".stripMargin
      ) should include("a pattern binding is written once")
    }

    "a field the pattern itself named, which is the same name one level down" in {
      err(
        shape +
          """var s: Shape = At(P(1, 2))
            |
            |s match
            |    At(P{x, y}) -> x = 9
            |    Nothing -> print("nothing")""".stripMargin
      ) should include("a pattern binding is written once")
    }
  }

  "the two writes that are not assignments" - {

    // `&` is refused for the reason a `val`'s is: a `*T` is a licence to write, and handing one out
    // would make the promise unkeepable one step away from where it was made.
    "'&' on a bound name" in {
      val said = err(
        cell +
          """take(q: *int) = print(*q)
            |
            |var d: Cell = Full(1)
            |
            |d match
            |    Full(n) -> take(&n)
            |    Empty -> print("empty")""".stripMargin
      )

      said should include("a pattern binding is written once")
      said should include("'&'")
    }

    // A `*self` receiver is an `&` the caller did not write, and it is asked the same question.
    "a method that takes '*self'" in {
      val said = err(
        """struct Counter
          |    n: int
          |
          |    bump(*self) = self.n += 1
          |
          |    read(self) -> int = self.n
          |end Counter
          |
          |enum Box
          |    Nothing
          |    Has(c: Counter)
          |
          |var b: Box = Has(Counter(0))
          |
          |b match
          |    Has(c) -> c.bump()
          |    Nothing -> print("nothing")""".stripMargin
      )

      said should include("'bump' takes '*self'")
      said should include("a pattern binding holds a copy of what it matched")
    }

    // The advice a `val` receiver gets is "write 'var c'", and there is no keyword on an arm's
    // capture to rewrite — so the two messages have to differ in the fix as well as the reason.
    "and its advice is a copy rather than a keyword, because there is no keyword to change" in {
      err(
        """struct Counter
          |    n: int
          |
          |    bump(*self) = self.n += 1
          |
          |    read(self) -> int = self.n
          |end Counter
          |
          |enum Box
          |    Nothing
          |    Has(c: Counter)
          |
          |var b: Box = Has(Counter(0))
          |
          |b match
          |    Has(c) -> c.bump()
          |    Nothing -> print("nothing")""".stripMargin
      ) should include("Copy it into a 'var'")
    }
  }

  "the diagnostic is the pattern's own" - {

    // The whole complaint the card was filed on: "a 'val' is written once" names a keyword that is
    // not on the reader's screen, and leaves out the copy — which is the half that says why sysl
    // refuses this at all.
    "it says what a pattern binding holds, and does not name a 'val' the program never wrote" in {
      val said = err(
        cell +
          """var d: Cell = Full(1)
            |
            |d match
            |    Full(n) -> n = 99
            |    Empty -> print("empty")""".stripMargin
      )

      said should include("holds a copy of what it matched")
      said should include("would reach that copy rather than the value it came from")
      said should not include "a 'val' is written once"
    }

    "and it names the fix" in {
      err(
        cell +
          """var d: Cell = Full(1)
            |
            |d match
            |    Full(n) -> n = 99
            |    Empty -> print("empty")""".stripMargin
      ) should include("take a 'var' from the binding first")
    }

    // The `val` message is untouched where a `val` is what was written, which is the other half of
    // the same claim: one of these two sentences is right and the compiler picks by binding form.
    "while a real 'val' still gets the sentence about a 'val'" in {
      err(
        """show() =
          |    val n = 5
          |    n = 6
          |
          |show()""".stripMargin
      ) should include("a 'val' is written once")
    }
  }

  "the copy is what the rule is about, and a 'var' taken from it is the way to change one" - {

    "the copy may be changed under its own name, and the matched value does not move" in {
      run(
        cell +
          """var d: Cell = Full(1)
            |
            |d match
            |    Full(n) ->
            |        var m = n
            |        m = 99
            |        print(m)
            |    Empty -> print("empty")
            |
            |d match
            |    Full(n) -> print(n)
            |    Empty -> print("empty")""".stripMargin
      ) shouldBe "99\n1\n"
    }

    // The same thing said about a field, since that is the shape a program actually writes: the
    // struct the arm holds is a copy, so changing it leaves the enum where it was.
    "a struct payload copied out, changed, and the original still holding its own value" in {
      run(
        shape +
          """var s: Shape = At(P(1, 2))
            |
            |s match
            |    At(p) ->
            |        var q = p
            |        q.x = 9
            |        print(q.x, q.y)
            |    Nothing -> print("nothing")
            |
            |s match
            |    At(p) -> print(p.x, p.y)
            |    Nothing -> print("nothing")""".stripMargin
      ) shouldBe "9 2\n1 2\n"
    }
  }

  /** The boundary the rule has to stop at, and the one place it would be wrong to cross.
   *
   * A binding copies **the payload**, and where the payload is a `&T` the copy is the *reference* —
   * so a write through it reaches the object the enum is holding rather than an arm-local copy of
   * it, which is what *Refcounts survive destructuring* is about. Selection dereferences, so
   * `e.value = 99` is a store through the reference and not a store into the binding, and refusing
   * it would take a facility away for a reason that does not apply to it.
   *
   * The name itself is still written once, which is the pair worth asserting together: the rule is
   * about the binding, not about everything the binding can reach.
   */
  "a '&T' payload still writes through, because the copy is the reference" - {

    val slot =
      """struct Entry
        |    value: int
        |
        |enum Slot
        |    Empty
        |    Filled(e: &Entry)
        |
        |""".stripMargin

    "the write reaches the object the enum is holding" in {
      run(
        slot +
          """var s: Slot = Filled(Entry(1))
            |
            |s match
            |    Filled(e) -> e.value = 99
            |    Empty -> print("empty")
            |
            |s match
            |    Filled(e) -> print(e.value)
            |    Empty -> print("empty")""".stripMargin
      ) shouldBe "99\n"
    }

    "and the name is written once all the same, so it may not be aimed somewhere else" in {
      err(
        slot +
          """var s: Slot = Filled(Entry(1))
            |var other: &Entry = Entry(5)
            |
            |s match
            |    Filled(e) -> e = other
            |    Empty -> print("empty")""".stripMargin
      ) should include("a pattern binding is written once")
    }
  }

  "what the rule does not reach" - {

    "reading the binding, which is everything an arm usually does with one" in {
      run(
        cell +
          """add(a: int, b: int) -> int = a + b
            |
            |var d: Cell = Full(20)
            |
            |d match
            |    Full(n) -> print(add(n, n) + 2)
            |    Empty -> print("empty")""".stripMargin
      ) shouldBe "42\n"
    }

    "the variable that was matched, which is a 'var' and stays one" in {
      run(
        cell +
          """var d: Cell = Full(1)
            |
            |d match
            |    Full(n) -> print(n)
            |    Empty -> print("empty")
            |
            |d = Empty
            |
            |d match
            |    Full(n) -> print(n)
            |    Empty -> print("empty")""".stripMargin
      ) shouldBe "1\nempty\n"
    }

    // A `for` element is not a pattern binding, and this rule was deliberately not widened to it.
    "a 'for' element, which is bound by a header rather than by a pattern" in {
      run(
        """for i in 0..<3
          |    var j = i
          |    j += 10
          |    print(j)""".stripMargin
      ) shouldBe "10\n11\n12\n"
    }
  }
}
