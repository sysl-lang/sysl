package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Diagnostics for a misused type attribute: an unknown attribute, one that needs a range on a
 * type that has none, the wrong arity, `::` on something that is not a type, and `Range` outside a
 * `for` loop.
 */
class TypeAttrErrorTests extends AnyFreeSpec with CodegenSupport {

  private val Age  = "type Age = int within 0..150\n"
  private val Slot = "type Slot = new u8 within 0..<8\n"

  "an unknown attribute is rejected" in {
    err(Age + "print(Age::Middle)") should include("has no attribute 'Middle'")
  }

  "an attribute needing a range on a predicate-only type is rejected" in {
    err("type Even = int where value % 2 == 0\nprint(Even::First)") should include("needs a 'within' range")
  }

  /** `Min` and `Max` are the two that fall back to the base where the declaration narrows nothing —
    * an unranged subtype can hold whatever the integer can. A **predicate** is not that case: it
    * narrows the type without saying to what, so reporting the base's extreme would hand back a
    * value the produce site traps on.
    */
  "and so is a bound on a predicate-only type, which narrows without saying where to" in {
    err("type Small = int where value < 10\nprint(Small::Max)") should include("needs a 'within' range")
  }

  "a bounded attribute rejects the wrong arity" - {
    "First takes no arguments" in {
      err(Age + "print(Age::First(1))") should include("takes no arguments")
    }
    "Succ takes exactly one" in {
      err(Age + "print(Age::Succ())") should include("takes exactly one argument")
    }
  }

  "`::` on something that is not a type is rejected" in {
    err("var x = 3\nprint(x::First)") should include("left side must be a type name")
  }

  "Range outside a for loop is rejected" in {
    err(Age + "print(Age::Range)") should include("only meaningful as the iterable of a 'for' loop")
  }

  /** A **derived** subtype is nominally distinct from its base (`reference/errors.md § Constrained
   * types`), and `reference/errors.md § What the type's own name offers: :: attributes` types its
   * attributes as the subtype — so the base is what a step now refuses, which is the reverse of
   * what it refused before. `Valid` keeps the base, and so keeps refusing the subtype: a value that
   * is already a `Slot` is not something to ask about.
   */
  "on a derived subtype the base is what a step refuses" - {
    "Succ wants the subtype" in {
      err(Slot + "print(Slot::Succ(3u8))") should include("'Slot::Succ' takes a Slot, not byte")
    }

    "Pred wants the subtype" in {
      err(Slot + "print(Slot::Pred(3u8))") should include("'Slot::Pred' takes a Slot, not byte")
    }

    "and Valid, alone, wants the base" in {
      err(Slot + "print(Slot::Valid(Slot(3u8)))") should include("'Slot::Valid' takes a byte, not Slot")
    }
  }

  // Every one of these is a question about integer bounds, so a subtype over another scalar has
  // none of them (`reference/errors.md § What the type's own name offers: :: attributes`). The
  // message names the *base*, which is the part that would have to change — naming the attribute
  // would suggest a different one might work.
  "a subtype over a non-integer base has no attributes, and the message names the base" in {
    val e = err("type Meters = new f64\nprint(Meters::First)")

    e should include("needs an integer subtype, not real")
  }
}
