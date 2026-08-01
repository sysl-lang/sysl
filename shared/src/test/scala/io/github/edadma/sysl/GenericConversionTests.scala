package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** A conversion written at a **type parameter** — `T(x)` where `T` is the parameter a body was
 * instantiated at.
 *
 * The form has always worked in one direction. `u8(x)` where `x` is a `T` is ordinary code: the
 * target is a type that exists, and whether the source converts is settled once the instantiation
 * says what `T` is. `T(b)` is the mirror of that and was refused for a reason about the *name*
 * rather than about the conversion, which is what made `guide/sha2`'s `shift_in` a trait member
 * written once per width while `top_byte` — its exact opposite — was one generic function.
 *
 * So what these assert is that the two directions are one rule, checked at the same moment and
 * saying the same thing when they fail.
 */
class GenericConversionTests extends AnyFreeSpec with RunSupport with CodegenSupport {

  private val word =
    """trait Word: Add + Sub + Shl + Shr + BitOr
      |    zero() -> Self
      |
      |impl Word for u32
      |    zero() -> u32 = 0
      |
      |impl Word for u64
      |    zero() -> u64 = 0
      |
      |""".stripMargin

  "both directions of one conversion are written generically" in {
    run(word +
      """shift_in[T: Word](x: T, b: u8) -> T = (x << 8) | T(b)
        |
        |top_byte[T: Word](x: T, w: T) -> u8 = u8(x >> (w - 8))
        |
        |main()
        |    var a = shift_in(shift_in(u32.zero(), 0xabu8), 0xcdu8)
        |    var b = shift_in(shift_in(u64.zero(), 0xabu8), 0xcdu8)
        |    print(a, b, top_byte(a, 16u32), top_byte(b, 16u64))
        |""".stripMargin) shouldBe "43981 43981 171 171\n"
  }

  "the conversion is the width's own, not the widest one's" in {
    // A narrowing at `T = u8` truncates and at `T = u32` does not, which is the whole point of the
    // conversion being resolved per instantiation rather than once at the definition.
    run(
      """narrow[T](x: int, like: T) -> T = T(x)
        |
        |main()
        |    print(narrow(300, 0u8), narrow(300, 0u32))
        |""".stripMargin) shouldBe "44 300\n"
  }

  "a float parameter converts as a float does" in {
    run(
      """half[T](x: int, like: T) -> T = T(x) / T(2)
        |
        |main()
        |    print(half(7, 0.0), half(7, 0.0f32), half(7, 0))
        |""".stripMargin) shouldBe "3.5 3.5 3\n"
  }

  "`Self` is a type parameter too, inside a member's body" in {
    run(
      """trait Scaled
        |    scaled(self, n: int) -> Self
        |
        |impl Scaled for u16
        |    scaled(self, n: int) -> u16 = self * Self(n)
        |
        |main()
        |    print((7u16).scaled(3))
        |""".stripMargin) shouldBe "21\n"
  }

  "an instantiation at a constrained subtype takes that subtype's own cast" in {
    // The scalar conversion has no meaning for a constrained type and the checked cast does, so the
    // form written at a parameter is the one written under the subtype's name — trap included.
    run(
      """type Age = int within 0..150
        |
        |into[T](x: int, like: T) -> T = T(x)
        |
        |main()
        |    var a: Age = into(30, Age(0))
        |    print(int(a))
        |""".stripMargin) shouldBe "30\n"
  }

  "and traps on a value that subtype does not admit" in {
    exits(
      """type Age = int within 0..150
        |
        |into[T](x: int, like: T) -> T = T(x)
        |
        |main()
        |    var a: Age = into(200, Age(0))
        |    print(int(a))
        |""".stripMargin)
  }

  "a string parameter encodes a char, as the written form does" in {
    run(
      """text[T](c: char, like: T) -> T = T(c)
        |
        |main()
        |    print(text('h', ""))
        |""".stripMargin) shouldBe "h\n"
  }

  // --- what is refused, and where -----------------------------------------------------------

  "an instantiation with no such conversion names the type it was made at" in {
    err(
      """struct P
        |    x: int
        |
        |make[T](b: u8, like: T) -> T = T(b)
        |
        |main()
        |    var p = make(1u8, P(0))
        |    print(p.x)
        |""".stripMargin) should include("cannot convert byte to P")
  }

  "the outward direction fails at the same moment and says the same thing" in {
    err(
      """struct P
        |    x: int
        |
        |take[T](v: T) -> u8 = u8(v)
        |
        |main()
        |    print(take(P(0)))
        |""".stripMargin) should include("cannot convert P to byte")
  }

  "a conversion still takes exactly one value" in {
    err(
      """widen[T](a: int, b: int, like: T) -> T = T(a, b)
        |
        |main()
        |    print(widen(1, 2, 0u8))
        |""".stripMargin) should include("conversion takes exactly one value")
  }

  "a local of the parameter's name is a value and shadows nothing else" in {
    run(
      """f(T: int) -> int = T + 1
        |
        |main()
        |    print(f(41))
        |""".stripMargin) shouldBe "42\n"
  }

  // --- the edges, each a type the form means something different at ---------------------------

  "an instantiation at a simple enum takes that enum's checked cast" in {
    run(
      """enum Colour
        |    Red
        |    Green
        |    Blue
        |
        |into[T](n: int, like: T) -> T = T(n)
        |
        |main()
        |    var c: Colour = into(2, Red)
        |    print(int(c), Colour::Image(c))
        |""".stripMargin) shouldBe "2 Blue\n"
  }

  "and traps on an integer that names no variant" in {
    exits(
      """enum Colour
        |    Red
        |    Green
        |    Blue
        |
        |into[T](n: int, like: T) -> T = T(n)
        |
        |main()
        |    var c: Colour = into(7, Red)
        |    print(int(c))
        |""".stripMargin)
  }

  "an enum that carries data is told which half of the rule it fails" in {
    err(
      """enum Shape
        |    Circle(r: int)
        |    Square(s: int)
        |
        |into[T](n: int, like: T) -> T = T(n)
        |
        |main()
        |    var s: Shape = into(0, Circle(1))
        |    print(int(s))
        |""".stripMargin) should include("carries data")
  }

  "there is no conversion to bool at a parameter either" in {
    err(
      """into[T](n: int, like: T) -> T = T(n)
        |
        |main()
        |    print(into(1, true))
        |""".stripMargin) should include("cannot convert int to bool")
  }

  "a pointer target is refused, since making one is not a conversion" in {
    err(
      """into[T](n: usize, like: T) -> T = T(n)
        |
        |main()
        |    var x = 1
        |    print(usize(into(0usize, &x)))
        |""".stripMargin) should include("cannot convert")
  }

  "an address reads as a number through a parameter" in {
    run(
      """address[T](p: *int, like: T) -> T = T(p)
        |
        |main()
        |    var x = 7
        |    print(address(&x, 0usize) != 0usize)
        |""".stripMargin) shouldBe "true\n"
  }

  "a member of a generic type converts at its own type's parameter" in {
    run(
      """struct Packer[T]
        |    seed: T
        |
        |    packed(self, b: u8) -> T = self.seed + T(b)
        |
        |main()
        |    var p = Packer(100u16)
        |    var q = Packer(100u64)
        |    print(p.packed(5u8), q.packed(5u8))
        |""".stripMargin) shouldBe "105 105\n"
  }

  "a closure inside a generic body converts too" in {
    run(
      """widen[T](b: u8, like: T) -> T
        |    var f = () -> T(b)
        |
        |    f()
        |
        |main()
        |    print(widen(9u8, 0u32), widen(9u8, 0.0))
        |""".stripMargin) shouldBe "9 9\n"
  }

  // --- which `T` a name means ----------------------------------------------------------------

  "a type parameter wins over a declaration of the same name, as it already does in type position" in {
    // The inconsistency this closes: `var y: T` inside a `[T]` body has always meant the parameter,
    // and `T(x)` meant the struct — so one name meant two things one line apart.
    run(
      """struct T
        |    v: int
        |
        |widen[T](b: u8, like: T) -> T = T(b)
        |
        |main()
        |    print(widen(9u8, 0u32), T(3).v)
        |""".stripMargin) shouldBe "9 3\n"
  }

  "and over a function of the same name" in {
    run(
      """T(x: int) -> int = x * 2
        |
        |widen[T](b: u8, like: T) -> T = T(b)
        |
        |main()
        |    print(widen(9u8, 0u32), T(3))
        |""".stripMargin) shouldBe "9 6\n"
  }

  "a struct instantiation is refused rather than construction being reached for" in {
    // Construction takes a field list rather than a value, and a generic body filling in an unknown
    // struct's fields by position is not something to arrive at by accident.
    err(
      """struct P
        |    a: int
        |    b: int
        |
        |make[T](x: int, like: T) -> T = T(x)
        |
        |main()
        |    print(make(1, P(0, 0)).a)
        |""".stripMargin) should include("cannot convert int to P")
  }

  "two parameters convert independently in one expression" in {
    run(
      """both[A, B](x: A, y: B) -> A = x + A(B(x) + y)
        |
        |main()
        |    print(both(10u16, 5u8), both(10u32, 5u8))
        |""".stripMargin) shouldBe "25 25\n"
  }
}
