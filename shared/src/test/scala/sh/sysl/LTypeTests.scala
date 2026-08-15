package sh.sysl

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import ir.LType

/** The LLVM type layer (`ir.LType`) and the lowering that produces it (`Type.lty`).
 *
 * Two claims are under test and they are different claims. The first is that `render` writes LLVM's
 * own spelling, spacing included — the compiler's tests assert on emitted IR by substring, so
 * `{ ptr, ptr }` is the answer and `{ptr,ptr}` is a different one, and nothing else in the tree would
 * say which had been written. The second is that the *lowering* is the erasure it is meant to be: a
 * constrained subtype, a qualifier, and the three reference modes all have to arrive at the type
 * their base or their address has, because a back end reading these values must not be able to see
 * what the language knew.
 *
 * Every assertion here is about the type layer alone. That the emitters then write these types where
 * they belong is what the codegen tier says, and it says it about the whole module rather than about
 * one type at a time.
 */
class LTypeTests extends AnyFreeSpec with Matchers {

  private given Word = Word(64)

  private val at32 = Word(32)

  private val u8  = Type.Integer(8, signed = false)
  private val i32 = Type.Integer(32, signed = true)

  "render writes LLVM's own spelling" - {

    "an integer is its width, at every width there is" in {
      LType.I(1).render shouldBe "i1"
      LType.I(8).render shouldBe "i8"
      LType.I(5).render shouldBe "i5"
      LType.I(64).render shouldBe "i64"
    }

    "a float is named rather than numbered, which is the one place LLVM is unsystematic" in {
      LType.F(16).render shouldBe "half"
      LType.F(32).render shouldBe "float"
      LType.F(64).render shouldBe "double"
    }

    "an address is opaque and nothing else is spelled like one" in {
      LType.Ptr.render shouldBe "ptr"
      LType.Void.render shouldBe "void"
    }

    "an array names its length and its element, and nests" in {
      LType.Arr(4, LType.Ptr).render shouldBe "[4 x ptr]"
      LType.Arr(2, LType.Arr(3, LType.I(8))).render shouldBe "[2 x [3 x i8]]"
    }

    // A vector and an array differ by one bracket pair and by nothing else in the text, which is
    // the whole of what tells LLVM to compute in every lane rather than to lay values out.
    "a vector is angle brackets where an array is square ones" in {
      LType.Vec(4, LType.F(32)).render shouldBe "<4 x float>"
      LType.Vec(16, LType.I(8)).render shouldBe "<16 x i8>"
      LType.Vec(4, LType.I(1)).render shouldBe "<4 x i1>"
    }

    "an anonymous aggregate carries the interior spaces the assertions match on" in {
      LType.Struct(List(LType.Ptr, LType.Ptr)).render shouldBe "{ ptr, ptr }"
      LType.Struct(List(LType.I(32))).render shouldBe "{ i32 }"
    }

    "and one with nothing in it is `{}` rather than a pair of braces with a gap" in {
      LType.Struct(Nil).render shouldBe "{}"
    }

    "a named type is its name, sigil and all" in {
      LType.Named("%struct.Point").render shouldBe "%struct.Point"
    }
  }

  "the two shapes the language names rather than builds" - {

    "a trait object is two words" in {
      LType.fat.render shouldBe "{ ptr, ptr }"
      Type.fatPointer shouldBe "{ ptr, ptr }"
    }

    // The one LLVM type in the language whose text depends on the machine, which is why it is a
    // method taking the width rather than a constant.
    "a view is two addresses and a count, and the count is the machine's" in {
      LType.view.render shouldBe "{ ptr, ptr, i64 }"
      LType.view(using at32).render shouldBe "{ ptr, ptr, i32 }"
    }
  }

  "the lowering erases what the back end must not see" - {

    "a scalar lowers to itself" in {
      u8.lty shouldBe LType.I(8)
      Type.Bool.lty shouldBe LType.I(1)
      Type.Char.lty shouldBe LType.I(32)
      Type.Floating(32).lty shouldBe LType.F(32)
    }

    "`unit`, `never` and the type a mistake leaves behind are all no value at all" in {
      Type.Unit.lty shouldBe LType.Void
      Type.Never.lty shouldBe LType.Void
      Type.Unknown.lty shouldBe LType.Void
    }

    // Where the width is settled is the point: a `usize` built for a 32-bit machine answers `i32`
    // whatever the width in scope when it is asked, because it took the answer when it was made.
    // That is the whole of what "the target is resolved at construction" means.
    "a `usize` is an address wide, and took that answer when it was built" in {
      Type.usize.lty shouldBe LType.I(64)
      Type.usize(using at32).lty shouldBe LType.I(32)
      Type.usize(using at32).lty(using Word(64)) shouldBe LType.I(32)
    }

    "a view is two addresses and a count, and the count is the machine in scope" in {
      Type.Slice(u8).lty shouldBe LType.Struct(List(LType.Ptr, LType.Ptr, LType.I(64)))
      Type.Str.lty(using at32) shouldBe LType.Struct(List(LType.Ptr, LType.Ptr, LType.I(32)))
    }

    "a qualifier says how storage is reached and nothing about what is in it" in {
      Type.Volatile(i32).lty shouldBe LType.I(32)
    }

    "a constrained subtype lowers exactly to its base, derived or not" in {
      def named(derived: Boolean) =
        Type.Constrained("Age", i32, derived, None, None, exclusiveHi = false, None)

      named(derived = false).lty shouldBe LType.I(32)
      named(derived = true).lty shouldBe LType.I(32)
    }

    "every reference mode is an address, and the three of them are indistinguishable here" in {
      Type.Ptr(i32).lty shouldBe LType.Ptr
      Type.Ref(i32, sync = false).lty shouldBe LType.Ptr
      Type.Ref(i32, sync = true).lty shouldBe LType.Ptr
      Type.Weak(i32).lty shouldBe LType.Ptr
      Type.CFn(List(i32), Type.Unit).lty shouldBe LType.Ptr
    }

    "unless it points at a trait, where the address is fat" in {
      val tr = Type.Trait("Display")

      Type.Ptr(tr).lty shouldBe LType.fat
      Type.Ref(tr, sync = false).lty shouldBe LType.fat
      Type.Weak(tr).lty shouldBe LType.fat
    }

    "an array is its length and its element's lowering, not its element" in {
      Type.Array(3, Type.Volatile(u8)).lty shouldBe LType.Arr(3, LType.I(8))
    }

    "a `va_list` is the four pointers' worth every ABI's bookkeeping fits inside" in {
      Type.VaList.lty shouldBe LType.Arr(4, LType.Ptr)
    }

    "a declared struct is a name, because LLVM declares one once and refers to it afterwards" in {
      val s = new Type.Struct("Point", Nil)

      s.fields = List(("x", i32), ("y", i32))
      s.lty shouldBe LType.Named("%struct.Point")
    }
  }

  "the four types with no representation say so rather than inventing one" - {

    "a type parameter" in {
      an[IllegalStateException] should be thrownBy Type.Abstract("T", Nil).lty
    }

    "a bare trait" in {
      an[IllegalStateException] should be thrownBy Type.Trait("Display").lty
    }

    "a value argument" in {
      an[IllegalStateException] should be thrownBy Type.ConstArg(BigInt(3), Type.usize).lty
    }

    "a type pack" in {
      an[IllegalStateException] should be thrownBy Type.Pack(List(i32)).lty
    }
  }

  "`llvm` is `lty.render` and has no second opinion" in {
    val cases: List[Type] =
      List(u8, i32, Type.Bool, Type.Char, Type.Unit, Type.usize, Type.Floating(64), Type.VaList,
           Type.Slice(u8), Type.Str,
           Type.Ptr(i32), Type.Ref(Type.Trait("Display"), sync = false), Type.Array(2, u8),
           Type.Vector(4, Type.Floating(32)),
           Type.Volatile(i32), Type.CFn(Nil, Type.Unit))

    for t <- cases do t.llvm shouldBe t.lty.render
  }

  /** **The overload suffix is a second spelling of the same type, and the two genuinely differ.**
   * A float renders as `float` and is named `f32`, so a suffix built by stripping the spaces out of
   * `render` produces `4xfloat` — which names no intrinsic, is accepted at the text level, and fails
   * in the verifier a layer past where the mistake was made. These are what keep the two apart.
   */
  "`overloadSuffix` is LLVM's intrinsic naming, not its type text" - {
    "a float is numbered where its type text is named" in {
      LType.F(32).overloadSuffix shouldBe "f32"
      LType.F(64).overloadSuffix shouldBe "f64"
      LType.F(32).render shouldBe "float"
    }

    "an integer agrees with its type text, and a mask's lane is i1" in {
      LType.I(32).overloadSuffix shouldBe "i32"
      LType.I(1).overloadSuffix shouldBe "i1"
    }

    "a vector prefixes its lane count, which is what a reduction is overloaded on" in {
      LType.Vec(4, LType.F(32)).overloadSuffix shouldBe "v4f32"
      LType.Vec(8, LType.I(32)).overloadSuffix shouldBe "v8i32"
      LType.Vec(16, LType.I(1)).overloadSuffix shouldBe "v16i1"
    }

    "an aggregate has none, because no intrinsic the compiler reaches takes one" in {
      an[RuntimeException] should be thrownBy LType.Struct(List(LType.Ptr)).overloadSuffix
    }
  }
}
