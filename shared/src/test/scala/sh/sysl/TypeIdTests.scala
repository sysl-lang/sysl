package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** `T::Id` and `o::Id` — a type's compile-time identity (`02`, `TypeId`).
  *
  * The feature is one number with one promise: **equal ids mean the same type, and nothing else.**
  * So the suite is organized around what that promise has to survive — the same type reached two
  * ways, two types that must not collide, and an erased value, which is the case the whole thing
  * exists for.
  *
  * The last section is the two refusals, which are what keeps the promise from being read as more
  * than it is: there is no way back to the type, and a value whose type is already known asks for it
  * by name.
  */
class TypeIdTests extends AnyFreeSpec with RunSupport with CodegenSupport {

  private val shapes =
    """trait Shape
      |    area(self) -> int
      |end Shape
      |
      |struct Rect
      |    w: int
      |end Rect
      |
      |struct Sq
      |    s: int
      |end Sq
      |
      |impl Shape for Rect
      |    area(self) -> int = self.w
      |
      |impl Shape for Sq
      |    area(self) -> int = self.s
      |
      |""".stripMargin

  "a type answers the same number every time, and no two types answer alike" - {

    "the same type twice" in {
      run(shapes + "print(Rect::Id == Rect::Id)\n") shouldBe "true\n"
    }

    "two structs" in {
      run(shapes + "print(Rect::Id == Sq::Id)\n") shouldBe "false\n"
    }

    "two built-in scalars, which are types like any other here" in {
      run("print(int::Id == usize::Id, int::Id == int::Id)\n") shouldBe "false true\n"
    }

    // The id keys on the identity a type's *members* are filed under rather than on its mangling,
    // and this is the case that separates the two: a transparent subtype shares its base's
    // representation and mangles as the base. An id that merged them would silently merge a cache
    // keyed on it, which is the use the feature is for.
    "a constrained subtype is not its base" in {
      run("""type Age = int within 0..150
            |
            |print(Age::Id == int::Id)
            |""".stripMargin) shouldBe "false\n"
    }
  }

  "an erased value carries it, which is the case the form exists for" - {

    "the id of what is inside it, not of the trait" in {
      run(shapes + """var a: *Shape = &Rect(2)
                     |
                     |print(a::Id == Rect::Id, a::Id == Sq::Id)
                     |""".stripMargin) shouldBe "true false\n"
    }

    "two objects holding different types disagree" in {
      run(shapes + """var a: *Shape = &Rect(2)
                     |var b: *Shape = &Sq(3)
                     |
                     |print(a::Id == b::Id)
                     |""".stripMargin) shouldBe "false\n"
    }

    // There is a table per memory mode, so a counted object and a raw one over one type are two
    // tables — and both carry the same number, because the number is a property of the type.
    "a counted object and a raw one over the same type agree" in {
      run(shapes + """var r: &Shape = Rect(4)
                     |var p: *Shape = &Rect(5)
                     |
                     |print(r::Id == p::Id, r::Id == Rect::Id)
                     |""".stripMargin) shouldBe "true true\n"
    }

    // The id sits in front of the slots, so every dispatch through a table is one slot further in
    // than it was. This is the test that would fail if that arithmetic were wrong, and it would fail
    // by calling the wrong function rather than by crashing.
    "and dispatch through the table still reaches the right method" in {
      run(shapes + """var a: *Shape = &Rect(2)
                     |var b: *Shape = &Sq(3)
                     |
                     |print(a.area(), b.area())
                     |""".stripMargin) shouldBe "2 3\n"
    }

    "a trait with several methods, so the shift is exercised past the first slot" in {
      run("""trait Named
            |    first(self) -> int
            |    second(self) -> int
            |    third(self) -> int
            |end Named
            |
            |struct P
            |    n: int
            |end P
            |
            |impl Named for P
            |    first(self) -> int = self.n
            |    second(self) -> int = self.n * 2
            |    third(self) -> int = self.n * 3
            |
            |var o: *Named = &P(5)
            |
            |print(o.first(), o.second(), o.third())
            |""".stripMargin) shouldBe "5 10 15\n"
    }
  }

  "a type parameter answers what the instantiation bound it to" - {

    "one instantiation is itself, and agrees with the type written out" in {
      run("""kind[T](x: T) -> usize = T::Id
            |
            |print(kind(1) == int::Id, kind(1) == kind(2))
            |""".stripMargin) shouldBe "true true\n"
    }

    "and two instantiations are two answers" in {
      run("""kind[T](x: T) -> usize = T::Id
            |
            |print(kind(1u8) == kind(1))
            |""".stripMargin) shouldBe "false\n"
    }

    // `Min` and `Max` need an integer, which is a bound on what the parameter may be. `Id` needs
    // nothing, because every type has one — so it is the attribute a parameter can be asked for
    // without narrowing what the parameter is.
    "a parameter carrying a struct, which has no bounds to answer with" in {
      run(shapes + """kind[T](x: T) -> usize = T::Id
                     |
                     |print(kind(Rect(1)) == Rect::Id)
                     |""".stripMargin) shouldBe "true\n"
    }
  }

  "what it does not offer" - {

    // `02 § There is no way back to the type` is untouched: the id compares and nothing more. The
    // refusal below is the other half of keeping that true — a value whose type is known asks for it
    // by name, so `::Id` on a value never reads as "the runtime type of anything".
    "a value whose type is known here is told to ask for it by name" in {
      val e = err(shapes + """var r = Rect(2)
                             |
                             |print(r::Id)
                             |""".stripMargin)

      e should include("'::Id' on a value reads the identity an erased value carries")
      e should include("write 'Rect::Id'")
    }

    "and the spelling it names is the one that works" in {
      run(shapes + """var r = Rect(2)
                     |
                     |print(Rect::Id == Rect::Id)
                     |""".stripMargin) shouldBe "true\n"
    }

    // A generic name has no single type to be the identity of, which is the same answer `X::Max`
    // gives on a generic enum. The arguments cannot be written here either — `Buf[int]::Id` reads
    // the brackets as a subscript — so v1 answers for a written name and for a type parameter, and
    // a parameter is how a generic body asks.
    "a generic type name has no single type to answer for" in {
      err("import sysl.buf.Buf\n\nprint(Buf::Id)\n") should include("takes 1 type argument")
    }
  }
}
