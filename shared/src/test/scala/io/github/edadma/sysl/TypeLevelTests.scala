package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** A trait member with **no receiver**, reached through the type rather than through a value of it
 * (`02 § Reaching a trait's members without a value`).
 *
 * Two things are being pinned, and they are separate. One is that a bound can now promise something
 * about the *type* — a width, a zero, a table of constants — so a generic body no longer has to ask
 * a value it does not read. The other is that an `impl` for a **built-in** may carry such a member,
 * which the declaration rule used to refuse on the grounds that only a struct or an enum has a name
 * a call reaches through.
 */
class TypeLevelTests extends AnyFreeSpec with RunSupport with CodegenSupport {

  private val width =
    """trait Width
      |    bits() -> usize
      |    zero() -> Self
      |
      |impl Width for u32
      |    bits() -> usize = 32
      |    zero() -> u32 = 0
      |
      |impl Width for u64
      |    bits() -> usize = 64
      |    zero() -> u64 = 0
      |
      |""".stripMargin

  "a bound reaches an associated function through the type parameter" in {
    run(width +
      """howWide[T: Width](x: T) -> usize = T.bits()
        |
        |main()
        |    print(howWide(1u32), howWide(1u64))
        |""".stripMargin) shouldBe "32 64\n"
  }

  "the answer follows the instantiation, with no value of the type in the call at all" in {
    run(width +
      """start[T: Width]() -> T = T.zero()
        |
        |main()
        |    var a: u32 = start()
        |    var b: u64 = start()
        |    print(a + 7, b + 9)
        |""".stripMargin) shouldBe "7 9\n"
  }

  "a built-in's associated function is reached by naming the built-in" in {
    run(width +
      """main()
        |    print(u32.bits(), u64.bits(), u32.zero(), u64.zero())
        |""".stripMargin) shouldBe "32 64 0 0\n"
  }

  "an alias reaches the one implementation the canonical name filed" in {
    run(
      """trait Named
        |    label() -> string
        |
        |impl Named for i32
        |    label() -> string = "thirty-two bits"
        |
        |main()
        |    print(int.label(), i32.label())
        |""".stripMargin) shouldBe "thirty-two bits thirty-two bits\n"
  }

  "an associated function takes arguments, and each implementation gets its own table" in {
    run(
      """val small: [4]u32 = [10, 20, 30, 40]
        |val large: [4]u64 = [1000, 2000, 3000, 4000]
        |
        |trait Table
        |    at(i: usize) -> Self
        |
        |impl Table for u32
        |    at(i: usize) -> u32 = small[i]
        |
        |impl Table for u64
        |    at(i: usize) -> u64 = large[i]
        |
        |third[T: Table]() -> T = T.at(2)
        |
        |main()
        |    var a: u32 = third()
        |    var b: u64 = third()
        |    print(a, b, u32.at(0), u64.at(3))
        |""".stripMargin) shouldBe "30 3000 10 4000\n"
  }

  "`Self` names the implementing type inside a member's body" in {
    run(width +
      """trait Doubled: Width
        |    twice(self) -> usize = 2 * Self.bits()
        |
        |impl Doubled for u32
        |impl Doubled for u64
        |
        |main()
        |    print((1u32).twice(), (1u64).twice())
        |""".stripMargin) shouldBe "64 128\n"
  }

  "a default body reaches the associated function the trait declared beside it" in {
    run(
      """trait Bounded: Sub
        |    top() -> Self
        |    headroom(self) -> Self = Self.top() - self
        |
        |impl Bounded for u8
        |    top() -> u8 = 200
        |
        |impl Bounded for u16
        |    top() -> u16 = 60000
        |
        |main()
        |    print((50u8).headroom(), (10000u16).headroom())
        |""".stripMargin) shouldBe "150 50000\n"
  }

  "a required trait's associated function is reached through the trait that required it" in {
    run(width +
      """trait Sized: Width
        |    bytes() -> usize
        |
        |impl Sized for u32
        |    bytes() -> usize = 4
        |
        |describe[T: Sized](x: T) -> string = str(T.bits()) + " bits in " + str(T.bytes()) + " bytes"
        |
        |main()
        |    print(describe(1u32))
        |""".stripMargin) shouldBe "32 bits in 4 bytes\n"
  }

  "an associated function is emitted once per implementation and called directly" in {
    val out = ir(width +
      """howWide[T: Width](x: T) -> usize = T.bits()
        |
        |main()
        |    print(howWide(1u32), howWide(1u64))
        |""".stripMargin)

    // Two instantiations, each holding a direct call to its own implementation. A table would be a
    // load and an indirect call, and there is no table: object safety refuses a trait declaring one.
    defineOf(out, "howWide.uint") should include("call i64 @uint.bits()")
    defineOf(out, "howWide.ulong") should include("call i64 @ulong.bits()")
  }

  // --- what is refused -------------------------------------------------------------------

  "a bound that does not promise it is named" in {
    err(width +
      """howWide[T](x: T) -> usize = T.bits()
        |""".stripMargin) should include("'bits' needs 'T: Width'")
  }

  "a name no trait declares says so rather than blaming the bound" in {
    err(width +
      """howWide[T: Width](x: T) -> usize = T.girth()
        |""".stripMargin) should include("no trait declares an associated function 'girth'")
  }

  "a method is not reachable through the type" in {
    err(
      """trait Show
        |    show(self) -> string
        |
        |describe[T: Show]() -> string = T.show()
        |""".stripMargin) should include("call it on a value of 'T', not on the type itself")
  }

  "a property is not reachable through the type" in {
    err(
      """trait Wide
        |    bits -> usize
        |
        |describe[T: Wide]() -> usize = T.bits()
        |""".stripMargin) should include("read it on a value")
  }

  "the parentheses are what a read is told is missing" in {
    err(width +
      """howWide[T: Width](x: T) -> usize = T.bits
        |""".stripMargin) should include("call it with 'T.bits()'")
  }

  "the same read on a built-in is told the same thing" in {
    err(width +
      """main()
        |    print(u32.bits)
        |""".stripMargin) should include("call it with 'u32.bits()'")
  }

  "a built-in with no such member is told it is a type" in {
    err(
      """main()
        |    print(u32.girth)
        |""".stripMargin) should include("'u32' is a type, not a value, and has no associated function 'girth'")
  }

  "a call for a member the built-in does not have names the type" in {
    err(
      """main()
        |    print(uint.girth())
        |""".stripMargin) should include("uint has no associated function 'girth'")
  }

  "a member reached on a value is not reached on the type" in {
    err(
      """trait Doubler
        |    doubled(self) -> Self
        |
        |impl Doubler for u32
        |    doubled(self) -> u32 = self * 2
        |
        |main()
        |    print(u32.doubled())
        |""".stripMargin) should include("is an instance method of 'uint' — call it on a value, not the type")
  }

  "a composed type still has no name a call could reach one through" in {
    err(
      """trait Empty
        |    empty() -> int
        |
        |impl Empty for []int
        |    empty() -> int = 0
        |""".stripMargin) should include("is not a name a call could reach it through")
  }

  "a trait declaring one has no object, and the diagnostic names the member" in {
    err(width +
      """show(w: *Width) -> usize = 1
        |""".stripMargin) should include("declares the associated function 'bits'")
  }

  "an implementation that leaves one out is held to it" in {
    err(
      """trait Width
        |    bits() -> usize
        |
        |impl Width for u32
        |""".stripMargin) should include("bits")
  }

  "a value of the type does not shadow the type's own name" in {
    run(width +
      """main()
        |    var u32 = 7
        |    print(u32)
        |""".stripMargin) shouldBe "7\n"
  }
}
