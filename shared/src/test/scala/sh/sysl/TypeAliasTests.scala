package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** `type Name = Existing` — a transparent alias, which declares no type and is a second spelling for
  * one that already exists.
  *
  * **The distinction every test here turns on is between a NAME and a TYPE.** A constrained subtype
  * is a type: it has its own value set, its own casts, and `new` gives it its own identity. An alias
  * has none of that — it resolves to what it names, and a value crosses between the two spellings
  * with nothing emitted, because there are not two things for anything to be emitted between.
  *
  * The base may be **anything**, which is the half a scalar-only reading would miss: a struct, a
  * pointer, and a callable signature are the three that a C binding actually needs, and each has a
  * test below saying so.
  */
class TypeAliasTests extends AnyFreeSpec with CodegenSupport with RunSupport {

  "a scalar base" - {
    "a value crosses both ways with no cast" in {
      run("""type Count = int
            |
            |double(n: Count) -> Count = n * 2
            |
            |val c: Count = 21
            |
            |print(double(c) + 0)
            |""".stripMargin) shouldBe "42\n"
    }

    // The alias is a spelling, so the two names are one type and a function declared with either
    // takes a value written as the other. A *derived* type is what refuses this, and the test below
    // pins that the two forms still differ.
    "the alias and its base are one type at a call" in {
      run("""type Count = int
            |
            |plain(n: int) -> int = n + 1
            |
            |val c: Count = 8
            |
            |print(plain(c))
            |""".stripMargin) shouldBe "9\n"
    }
  }

  "a struct base" - {
    // The motivating case: a binding's lower layer declares the ABI struct, and the layer an
    // application imports gives it a name of its own without re-declaring it.
    "is constructed and read through the alias's name" in {
      run("""struct Point
            |    x: int
            |    y: int
            |end Point
            |
            |type P = Point
            |
            |val p: P = P(3, 4)
            |
            |print(p.x + p.y)
            |""".stripMargin) shouldBe "7\n"
    }

    // **The scope question, and it is the reason an alias is followed at the key rather than by
    // substituting the written name.** `type P = inner.Point` names `inner` in the file that wrote
    // the alias; the file below never imports `inner` and must not have to.
    "names a type the USING file has not imported" in {
      runIn(
        ("inner", "inner.sysl",
          """module inner
            |
            |struct Point
            |    x: int
            |    y: int
            |end Point
            |""".stripMargin),
        ("outer", "outer.sysl",
          """module outer
            |
            |import inner.Point
            |
            |type P = Point
            |""".stripMargin),
        ("", "main.sysl",
          """import outer.P
            |
            |val p: P = P(2, 5)
            |
            |print(p.x * p.y)
            |""".stripMargin)
      ) shouldBe "10\n"
    }
  }

  "a pointer base, which no constrained subtype may have" in {
    run("""type Handle = *u8
          |
          |first(h: Handle) -> u8 = h[0]
          |
          |var bytes = [7u8, 9u8]
          |
          |print(first(&bytes[0]))
          |""".stripMargin) shouldBe "7\n"
  }

  // **This is what the absence cost, stated as a test.** A signature could not be named once, so
  // every declaration mentioning a callback spelled the whole of it — the reason `FuncAddressTests`
  // carried a tripwire for this feature arriving.
  "a callable base lets a signature be named once" in {
    run("""type Comparison = *extern(*u8, *u8) -> i32
          |
          |compare(a: *u8, b: *u8) -> i32 = i32(a[0]) - i32(b[0])
          |
          |call(f: Comparison, a: *u8, b: *u8) -> i32 = f(a, b)
          |
          |var xs = [9u8, 4u8]
          |
          |print(str(call(&compare, &xs[0], &xs[1])))
          |""".stripMargin) shouldBe "5\n"
  }

  "a chain of aliases resolves to what the last one names" in {
    run("""type A = int
          |type B = A
          |type C = B
          |
          |val c: C = 5
          |
          |print(c + 1)
          |""".stripMargin) shouldBe "6\n"
  }

  "a cycle is refused rather than followed forever" in {
    err("""type A = B
          |type B = A
          |
          |val a: A = 1
          |
          |print(1)
          |""".stripMargin) should include("alias for itself")
  }

  "what an alias is NOT" - {
    // `new` is the whole of the difference: it makes a distinct type, and a distinct type over a
    // struct is a separate question this does not reopen.
    "'new' over a struct is still refused" in {
      err("""struct Point
            |    x: int
            |    y: int
            |end Point
            |
            |type Bad = new Point
            |
            |var b = Bad(Point(1, 2))
            |
            |print(b.x)
            |""".stripMargin) should include("must be an integer, a float, or 'char'")
    }

    "a derived type is still distinct from its base at a call" in {
      err("""type Meters = new f64
            |
            |plain(x: f64) -> f64 = x
            |
            |val m: Meters = Meters(3.0)
            |
            |print(str(plain(m)))
            |""".stripMargin) should not be empty
    }

    // A constrained subtype declares a type and so has attributes of its own; an alias has none,
    // because there is nothing there to have them.
    "a constrained subtype still declares a type, with the bounds that go with one" in {
      run("""type Age = int within 0..150
            |
            |print(str(Age::First), str(Age::Last))
            |""".stripMargin) shouldBe "0 150\n"
    }
  }
}
