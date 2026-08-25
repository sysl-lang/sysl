package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Diagnostics for a misused integer attribute: an unknown one, the enum/range pair asked of a
 * number, arguments where none are taken, and the types that have no bounds to report.
 */
class IntegerAttrErrorTests extends AnyFreeSpec with CodegenSupport {

  "an unknown attribute names the two that exist" in {
    err("print(u8::Middle)") should include("has no attribute 'Middle'")
    err("print(u8::Middle)") should include("'u8::Min'")
  }

  "First and Last are refused, and say what they are for" - {
    // The whole reason these are `Min`/`Max` and not `First`/`Last`: the latter name the ends of a
    // declared sequence, and on an enum with explicit discriminants the first-declared variant need
    // not carry the smallest one. A reader arriving from `reference/errors.md § What the type's own
    // name offers: :: attributes` writes `First` and is redirected.
    "First points at Min" in {
      err("print(u8::First)") should include("'u8::Min'")
      err("print(u8::First)") should include("not a declared sequence")
    }
    "Last points at Max" in {
      err("print(i16::Last)") should include("'i16::Max'")
    }
  }

  "they take no arguments" in {
    err("print(u8::Max(1))") should include("takes no arguments")
  }

  "a type with no bounds to report says so" - {
    "a float has none" in {
      err("print(f64::Max)") should include("f64")
    }
    "nor does bool" in {
      err("print(bool::Max)") should include("bool")
    }
  }
}
