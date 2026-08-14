package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Compile-time diagnostics for a malformed `type` declaration: a bound outside the base's range, an
 * inverted range, a non-scalar base, a bound of the wrong kind, and a cast of the wrong arity. Each
 * is caught at the declaration or the use, before anything runs.
 */
class SubtypeErrorTests extends AnyFreeSpec with CodegenSupport {

  "an out-of-range literal bound is rejected" in {
    err("type T = byte within 0..300\nvar x: T = 1\nprint(int(x))") should include("does not fit")
  }

  "an inverted range is rejected" in {
    err("type T = int within 10..5\nvar x: T = 7\nprint(int(x))") should include("above its upper bound")
  }

  "an empty exclusive range is rejected" in {
    err("type T = int within 5..<5\nvar x: T = 1\nprint(int(x))") should include("above its upper bound")
  }

  "a non-scalar base is rejected" in {
    err(
      """struct P
        |    x: int
        |end P
        |type T = P within 0..1
        |var t: T = 1
        |print(1)""".stripMargin
    ) should include("must be an integer, a float, or 'char'")
  }

  // The messages say "integer bounds" rather than "integer-literal bounds", because a bound is a
  // constant expression and need not be a literal at all (`16 § Open b`) — the kind is what is wrong
  // here, not the spelling.
  "bounds of the wrong kind" - {
    "character bounds on an integer base are rejected" in {
      err("type T = int within 'a'..'z'\nvar x: T = 1\nprint(int(x))") should include("needs integer bounds")
    }
    "integer bounds on a character base are rejected" in {
      err("type L = char within 0..25\nvar x: L = 'a'\nprint(x)") should include("needs character bounds")
    }
  }

  "a cast with the wrong number of arguments is rejected" in {
    err("type Age = int within 0..150\nprint(int(Age(1, 2)))") should include("takes exactly one value")
  }

  "a bare transparent alias with no constraint is rejected" in {
    err("type T = int\nvar x: T = 1\nprint(int(x))") should include("has no constraint")
  }

  "a non-bool where predicate is rejected without leaking the synthesised function's name" in {
    val e = err("type T = int where value + 1\nvar x: T = 1\nprint(int(x))")
    e should include("a 'where' predicate must be a 'bool'")
    e should not include "$pred"
  }

  /** The other half of the rule `SubtypeRunTests` pins: a transparent subtype's name converts what
    * its base's name converts, and a pair with no meaning is still refused — naming the type the
    * reader wrote rather than the base they did not.
    */
  "a transparent subtype converts only what its base does" in {
    err("type Age = int within 0..150\nvar s = \"x\"\nprint(Age(s))") should
      include("cannot make Age from string")
  }

  "a derived type is nominally distinct" - {
    val Meters = "type Meters = new f64\n"

    "mixing it with its base in arithmetic is rejected" in {
      err(Meters + "print(f64(Meters(3.0) + 1.0))") should include("needs matching types")
    }

    /** `new` is what makes the type distinct, so a conversion into one is a **wrap** of a value
      * already at the base rather than the scalar conversion a transparent subtype's name performs.
      * This is the case that must NOT move with that rule, and it is here because the two look
      * identical on the line: `Meters(x)` and `Age(x)` differ only in what was declared.
      */
    "a value that is not already at the base is rejected" in {
      err(Meters + "var n: int = 3\nprint(f64(Meters(n)))") should
        include("cannot make Meters from int")
    }

    "an implicit conversion from the base is rejected" in {
      err(Meters + "var m: Meters = 3.0\nprint(f64(m))") should include("declared Meters but the value is real")
    }

    "two derived types over one base do not mix" in {
      err(
        Meters + "type Feet = new f64\n" +
          "sum(a: Meters, b: Feet) -> f64\n    f64(a + b)\nprint(1)"
      ) should include("needs matching types")
    }
  }

  /* A pointer is where a constraint would be lost if the type system let go of it: an `Age` flows
   * freely as an `int` by value (§1), but a `*int` into an `Age`'s storage would be a door to a
   * write no range check sees. Both spellings are refused where the alias is created — value
   * transparency does not extend one level up. */
  "a pointer does not shed the constraint" - {
    "a transparent subtype's address does not coerce to its base's pointer" in {
      err("type Age = int within 0..150\nvar x: Age = 50\nvar q: *int = &x\n*q = 999\nprint(x)") should
        include("declared *int but the value is *Age")
    }
    "nor does a derived subtype's" in {
      err("type Slot = new u8 within 0..<200\nvar x = Slot(50)\nvar q: *u8 = &x\n*q = 255\nprint(u8(x))") should
        include("declared *byte but the value is *Slot")
    }
  }

  /** A constrained cast traps rather than reporting, and `16 §4` says why: a value outside the range
   * is a bug in the code that made it, not a condition to handle. So there is no `T.try(x)` — but a
   * simple enum has one, which makes it the first thing anyone writes here. The whole of what the
   * name answers instead is pinned in `TypeNameMemberTests`; here is only that the absence is real.
   */
  "a subtype has no fallible cast" in {
    err("type Age = int within 0..150\nprint(Age.try(200).is_some())") should
      include("'Age' is a constrained type and has no 'try'")
  }

  /** A `within` bound is a constant expression, so what it refuses is a bound that is not constant —
    * and the three cases differ in *why*, which is why each gets its own message. A name nothing
    * declared, a name that is storage rather than a constant, and a constant of the wrong kind are
    * three different mistakes, and telling a reader "needs integer bounds" about an undeclared name
    * would send them to fix the wrong thing.
    */
  "a within bound has to be a constant" - {
    "a name nothing declared is not one" in {
      val e = err("type A = new int within 0..<nowhere")

      e should include("has to be a constant")
      e should include("'nowhere' is not one")
    }

    // A module-level `val` is read-only *storage* with an address (`13 §7`), which is exactly what a
    // `const` is not — so it cannot size a type, and the message must not suggest it could.
    "a module-level 'val' is storage, not a constant" in {
      err("""val n: int = 4
            |type B = new int within 0..<n""".stripMargin) should include("'n' is not one")
    }

    // Here the bound *is* constant and the complaint is about its kind, which is the other branch.
    "a constant of the wrong kind is a different complaint" in {
      val e = err("""const label: string = "hi"
                    |type C = new int within 0..<label""".stripMargin)

      e should include("needs integer bounds")
      e should include("a string")
    }

    "a char subtype says so about a numeric bound" in {
      err("""const n: int = 5
            |type D = new char within 'a'..n""".stripMargin) should include("needs character bounds")
    }

    // The ordering check runs on the folded values, so two constants in the wrong order are caught
    // exactly as two literals are.
    "and two constants in the wrong order are still refused" in {
      err("""const lo: int = 9
            |const hi: int = 2
            |type E = new int within lo..hi""".stripMargin) should
        include("lower bound of 'E' is above its upper bound")
    }
  }

  /** A derived scalar takes its base's whole catalog and may replace none of it.
   *
   * That is two decisions in one, and they pull opposite ways. Inheriting is what makes a `new u8`
   * cheap to declare — it compares, orders and adds without a line of support code, which is the
   * whole reason `guide/kernel`'s three bounded identities were worth having. Not being able to
   * *replace* any of it is what puts a ceiling on the technique: a derived type gets exactly the
   * behaviour its representation happens to have, including the operations that are meaningless for
   * what it now means, and it cannot be given one operation the representation does not have.
   */
  "a derived type inherits behaviour it cannot replace" - {
    val Stamp = "type Stamp = new i64\ntype Span = new i64\n"

    "an operator implementation collides with the one the compiler provides" in {
      err(Stamp + "impl Add[Span] for Stamp\n    add(self, s: Span) -> Stamp = self\nprint(1)") should
        include("'add' is how 'Add' is implemented for Stamp, and the compiler provides that")
    }

    "and so does any other row of the catalog" in {
      err(Stamp + "impl Display for Stamp\n    display(self, out: *Writer, fmt: FormatSpec)\n" +
        "        display_str(\"x\", out, fmt)\nprint(1)") should
        // `Display` is no longer a membership the compiler hands out, so the refusal is the
        // library's blanket block rather than a rule — but a derived subtype still cannot replace
        // what its base has, which is the claim this block is about.
        include("'Stamp' already implements 'sysl.Display' — every 'sysl.Integer' does")
    }

    "even where the base's meaning does not survive the derivation" in {
      err(Stamp + "impl Eq for Stamp\n    eq(self, o: Stamp) -> bool = true\nprint(1)") should
        include("the compiler provides")
    }
  }
}
