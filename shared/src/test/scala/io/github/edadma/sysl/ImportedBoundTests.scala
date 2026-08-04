package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** A type parameter bounded by a trait that came from **another module**, however the bound spells
 * it (`13 §3`, `14 §4`).
 *
 * An `import` shortens a reference and changes nothing else, so a bound written with the short name
 * has to ask what the qualified one asks. The two spellings meeting at different keys is invisible
 * at the declaration — the bound is recorded there and answered later — and surfaces as a
 * declaration being told its own parameter is not bounded by the trait it is bounded by.
 */
class ImportedBoundTests extends AnyFreeSpec with CodegenSupport with RunSupport {

  private val num =
    ("num", "n.sysl",
     """module num
       |
       |trait Scale
       |    double(self) -> Self
       |
       |impl Scale for int
       |    double(self) -> int = self * 2
       |""".stripMargin)

  "a bound naming an imported trait by its short name" - {
    "bounds a struct's parameter" in {
      runIn(
        ("", "main.sysl",
         """import num.Scale
           |
           |struct Box[T: Scale]
           |    v: T
           |end Box
           |
           |var b = Box(21)
           |
           |print(b.v.double())
           |""".stripMargin),
        num,
      ) shouldBe "42\n"
    }

    "bounds a function's parameter" in {
      runIn(
        ("", "main.sysl",
         """import num.Scale
           |
           |twice[T: Scale](x: T) -> T = x.double()
           |
           |print(twice(21))
           |""".stripMargin),
        num,
      ) shouldBe "42\n"
    }

    "bounds an enum's parameter" in {
      runIn(
        ("", "main.sysl",
         """import num.Scale
           |
           |enum Maybe[T: Scale]
           |    Nothing
           |    Just(v: T)
           |end Maybe
           |
           |var m: Maybe[int] = Maybe.Just(21)
           |
           |m match
           |    Nothing -> print(0)
           |    Just(v) -> print(v.double())
           |""".stripMargin),
        num,
      ) shouldBe "42\n"
    }

    "bounds an 'impl' block's parameter, which is a conditional conformance" in {
      runIn(
        ("", "main.sysl",
         """import num.Scale
           |
           |struct Box[T]
           |    v: T
           |end Box
           |
           |impl[T: Scale] Scale for Box[T]
           |    double(self) -> Box[T] = Box(self.v.double())
           |
           |print(Box(21).double().v)
           |""".stripMargin),
        num,
      ) shouldBe "42\n"
    }

    // The whole of what an import does is supply the module, so a bound that names one is asking
    // exactly what the qualified spelling asks and reaches the same declaration.
    "asks the same thing the qualified spelling asks" in {
      runIn(
        ("", "main.sysl",
         """import num.Scale
           |
           |struct Short[T: Scale]
           |    v: T
           |end Short
           |
           |struct Long[T: num.Scale]
           |    v: T
           |end Long
           |
           |pass[T: Scale](x: Long[T]) -> T = x.v.double()
           |
           |print(Short(3).v, pass(Long(21)))
           |""".stripMargin),
        num,
      ) shouldBe "3 42\n"
    }
  }

  "a bound naming a trait by its module path" - {
    // The bound is satisfied and the type is built; reaching the *member* is the separate question
    // of whether the trait is in scope, which a path in a bound does not answer.
    "bounds a struct's parameter with no import at all" in {
      runIn(
        ("", "main.sysl",
         """struct Box[T: num.Scale]
           |    v: T
           |end Box
           |
           |var b = Box(21)
           |
           |print(b.v)
           |""".stripMargin),
        num,
      ) shouldBe "21\n"
    }
  }

  // A trait's own parameters are read in the file that declared the trait, which is a second place
  // the same question is asked and was answered the same wrong way.
  "a trait's own type parameter, bounded by an imported trait" - {
    "carries the bound into the types its members name" in {
      runIn(
        ("", "main.sysl",
         """import num.Scale
           |
           |struct Box[T: Scale]
           |    v: T
           |end Box
           |
           |trait Holder[T: Scale]
           |    take(self, b: Box[T]) -> T
           |
           |struct Keep
           |    n: int
           |end Keep
           |
           |impl Holder[int] for Keep
           |    take(self, b: Box[int]) -> int = b.v.double() + self.n
           |
           |print(Keep(1).take(Box(20)))
           |""".stripMargin),
        num,
      ) shouldBe "41\n"
    }
  }

  // A chain of requirements is walked from whichever trait a use names, and each link is read in the
  // file that wrote it — so a middle module's `: Scale` is its own import's, not the walk's.
  "a required trait named by a short name in the module that required it" - {
    "is reached from a trait two modules away" in {
      runIn(
        ("", "main.sysl",
         """import mid.Mid
           |
           |trait Top: mid.Mid
           |    top(self) -> int
           |
           |impl Top for int
           |    top(self) -> int = self.mid() + 1
           |
           |print(20.top())
           |""".stripMargin),
        ("mid", "m.sysl",
         """module mid
           |
           |import num.Scale
           |
           |trait Mid: Scale
           |    mid(self) -> int
           |
           |impl Mid for int
           |    mid(self) -> int = self.double()
           |""".stripMargin),
        num,
      ) shouldBe "41\n"
    }
  }

  "a type argument that does not implement the imported trait is still refused" in {
    errIn(
      ("", "main.sysl",
       """import num.Scale
         |
         |struct Box[T: Scale]
         |    v: T
         |end Box
         |
         |struct Tag
         |    n: int
         |end Tag
         |
         |var b = Box(Tag(1))
         |""".stripMargin),
      num,
    ) should include("'Box' requires its type parameter 'T' to implement 'num.Scale'")
  }
}
