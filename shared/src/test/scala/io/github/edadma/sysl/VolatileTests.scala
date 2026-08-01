package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** `volatile` (`03 § Device memory`): storage whose reads and writes are effects rather than value
 * computations, so the compiler emits exactly the accesses the source wrote and no others.
 *
 * The suite is arranged around the one distinction the whole feature rests on. `volatile` qualifies
 * **storage**, and a value read out of it is an ordinary value — so the qualifier lives in the
 * composites that name somebody else's storage (a field, an element, a pointee) and is gone by the
 * time anything holds the result. Everything else follows: which spellings are refused, why an
 * address carries the qualifier with it, and why one field of a register block is reached at its own
 * address rather than by reading the block.
 */
class VolatileTests extends AnyFreeSpec with RunSupport with CodegenSupport {

  /** The shape every device header has: a block of registers reached through a pointer at a fixed
   * address, with one field that is a shadow in ordinary memory rather than a register.
   */
  private val uart =
    """struct Uart
      |    status: volatile u32
      |    data:   volatile u32
      |    baud:   u32
      |const UART: usize = 0x10000000
      |val regs: *Uart = ptr_cast(UART)
      |""".stripMargin

  "a register block is a struct whose registers are qualified" - {
    "reading one is a load marked volatile" in {
      defineOf(ir(uart + "read() -> u32 = regs.status\nprint(read())"), "read") should
        include("load volatile i32")
    }

    // The load-bearing half, and the reason a field of a register block cannot be reached the way an
    // ordinary field is: lowering `regs.status` as "read the block, take field 0" would read the
    // data register as a side effect of looking at the status register, which on real hardware pops
    // a FIFO.
    "and the block itself is not read on the way to it" in {
      val out = defineOf(ir(uart + "read() -> u32 = regs.status\nprint(read())"), "read")

      out should include("getelementptr %struct.Uart")
      out should not include "load volatile %struct.Uart"
      out should not include "extractvalue"
    }

    "writing one is a store marked volatile" in {
      defineOf(ir(uart + "go() = regs.data = 65u32\ngo()"), "go") should
        include("store volatile i32 65")
    }

    // A reserved word or a shadow value in RAM sits in the middle of nearly every real block, and
    // qualifying per field rather than per struct is exactly what lets it stay ordinary.
    "while a field that is not a register keeps the ordinary instruction" in {
      val out = defineOf(ir(uart + "go() = regs.baud = 115200u32\ngo()"), "go")

      out should include("store i32 115200")
      out should not include "store volatile"
    }

    "a compound assignment is one volatile read and one volatile write" in {
      val out = defineOf(ir(uart + "go() = regs.data += 1u32\ngo()"), "go")

      out should include("load volatile i32")
      out should include("store volatile i32")
    }

    "and so is an increment" in {
      val out = defineOf(ir(uart + "go() = regs.data++\ngo()"), "go")

      out should include("load volatile i32")
      out should include("store volatile i32")
    }
  }

  "a lone register is the pointee of a '*volatile T'" - {
    "read through the '*'" in {
      defineOf(ir("read(p: *volatile u32) -> u32 = *p\nprint(read(ptr_cast(usize(4096))))"), "read") should
        include("load volatile i32")
    }

    "read through a subscript, which is the same address arrived at the other way" in {
      defineOf(ir("read(p: *volatile u32) -> u32 = p[0]\nprint(read(ptr_cast(usize(4096))))"), "read") should
        include("load volatile i32")
    }

    "written through either" in {
      val src =
        """set(p: *volatile u32, v: u32) -> unit
          |    *p = v
          |    p[1] = v
          |set(ptr_cast(usize(4096)), 1u32)""".stripMargin

      defineOf(ir(src), "set").linesIterator.count(_.contains("store volatile i32")) shouldBe 2
    }

    // The pointer is an ordinary value: what it *addresses* is a device, and copying the address
    // touches no device at all.
    "while the pointer holding the address is read like any other value" in {
      val out = defineOf(ir(uart + "read() -> u32 = regs.status\nprint(read())"), "read")

      out should include("load ptr, ptr @regs")
      out should not include "load volatile ptr"
    }
  }

  "the qualifier travels with an address taken of a register" - {
    "'&' of a qualified field yields a '*volatile T'" in {
      val src = uart +
        """at() -> *volatile u32 = &regs.status
          |print(*at())""".stripMargin

      ir(src) should include("define ptr @at()")
    }

    // The point of the previous test: it is not that the spelling was accepted, it is that every
    // access through the result is still an access to a register.
    "and reading through the result is still volatile" in {
      val src = uart +
        """at() -> *volatile u32 = &regs.status
          |read() -> u32 = *at()
          |print(read())""".stripMargin

      defineOf(ir(src), "read") should include("load volatile i32")
    }

    // The other side of the same rule: an address taken of ordinary storage is an ordinary address,
    // so the qualifier is not something `&` invents.
    "while '&' of an unqualified field yields an ordinary '*T'" in {
      val src = uart +
        """at() -> *u32 = &regs.baud
          |read() -> u32 = *at()
          |print(read())""".stripMargin

      defineOf(ir(src), "read") should not include "load volatile"
    }
  }

  "a read of a register hands back an ordinary value" - {
    // The whole design in one test. If the qualifier survived the read, this binding would be a
    // volatile one and every mention of `s` below would be a volatile load of a stack slot.
    "so what it is bound to is ordinary storage" in {
      val src = uart +
        """go() -> u32
          |    var s = regs.status
          |    s += 1u32
          |    s
          |print(go())""".stripMargin

      val out = defineOf(ir(src), "go")

      out.linesIterator.count(_.contains("load volatile")) shouldBe 1
      out should include("store i32")
    }

    "and it stands where an ordinary value is asked for" in {
      val src = uart +
        """take(v: u32) -> u32 = v + 1u32
          |go() -> u32 = take(regs.status)
          |print(go())""".stripMargin

      ir(src) should include("define i32 @take(i32 %v.param)")
    }

    // Said directly rather than inferred from the lowering: the type a diagnostic names for a read
    // of a register is the unqualified one.
    "which is what a diagnostic calls it" in {
      val message = err(uart + "go() -> string = regs.status\nprint(go())")

      message should include("yields uint")
      message should not include "volatile"
    }
  }

  "a bank of registers is an array of them" - {
    "indexing one is a volatile load at the element" in {
      val src =
        """struct Gpio
          |    bank: [4]volatile u32
          |val gpio: *Gpio = ptr_cast(usize(0x40000000))
          |read(i: usize) -> u32 = gpio.bank[i]
          |print(read(0))""".stripMargin

      defineOf(ir(src), "read") should include("load volatile i32")
    }

    // A bank is still storage with a length, so the index is checked exactly as any other is — the
    // qualifier changes the instruction and nothing about the rules around it.
    "and the index is checked, as it is for any other array" in {
      val src =
        """struct Gpio
          |    bank: [4]volatile u32
          |val gpio: *Gpio = ptr_cast(usize(0x40000000))
          |read(i: usize) -> u32 = gpio.bank[i]
          |print(read(0))""".stripMargin

      defineOf(ir(src), "read") should include("icmp ult i64")
    }

    // A view is three words that say where the elements are, so what it is a view *of* is still
    // where the qualifier lives — and reaching through one lands on the same instruction.
    "and a view of registers indexes volatile too" in {
      val src =
        """struct Gpio
          |    bank: [4]volatile u32
          |val gpio: *Gpio = ptr_cast(usize(0x40000000))
          |read(xs: []volatile u32) -> u32 = xs[0]
          |print(read(gpio.bank[0..4]))""".stripMargin

      defineOf(ir(src), "read") should include("load volatile i32")
    }

    // The two qualifiers are about different things and compose: `const` says this handle may not
    // write, `volatile` says the elements are a device's. A read-only device register wants both.
    "and 'const' composes with it, being a property of the view rather than of the element" in {
      val src =
        """struct Gpio
          |    bank: [4]volatile u32
          |val gpio: *Gpio = ptr_cast(usize(0x40000000))
          |peek(xs: []const volatile u32) -> u32 = xs[0]
          |print(peek(gpio.bank[0..4]))""".stripMargin

      defineOf(ir(src), "peek") should include("load volatile i32")
    }
  }

  // A copy of a whole register block reads every register in it, and that is as much an effect as
  // reading one of them would be — so the aggregate access is marked too rather than being the one
  // hole in the rule.
  "copying a whole block is an access to every register in it" in {
    val src = uart +
      """snapshot() -> u32
        |    var u = *regs
        |    u.status
        |print(snapshot())""".stripMargin

    defineOf(ir(src), "snapshot") should include("load volatile %struct.Uart")
  }

  "what it refuses" - {
    // Each of these is a *value* rather than storage, and a value read out of a volatile place is an
    // ordinary value — so there is nothing left for the qualifier to promise by the time it is
    // written here.
    "a variable, which holds a value" in {
      err("var x: volatile u32 = 1u32\nprint(x)") should
        include("is the type of *storage*, and this is a value")
    }
    "a parameter, which receives one" in {
      err("f(p: volatile u32) -> unit = ()\nf(1u32)") should include("the type of *storage*")
    }
    "a result, which hands one back" in {
      err("f() -> volatile u32 = 1u32\nprint(f())") should include("the type of *storage*")
    }
    "a 'val', whose storage is this program's own" in {
      err("val v: volatile u32 = 1u32\nprint(v)") should include("the type of *storage*")
    }
    // An enum's payload is lifted out of a loaded aggregate rather than reached at an address, so
    // there is no single access for the qualifier to describe.
    "an enum's payload" in {
      err("enum E\n    V(x: volatile u32)\n    W\nprint(0)") should include("the type of *storage*")
    }

    // The message names the fix, which for a register block is per field — and the reason is not
    // fussiness: it is what lets `baud` above stay ordinary.
    "an aggregate as a whole, with the per-field spelling named" in {
      val src =
        """struct T
          |    a: int
          |struct S
          |    b: volatile T
          |print(0)""".stripMargin

      err(src) should include("a register block is qualified one field at a time")
    }

    "a counted reference, whose accesses come with retains this could not hold still" in {
      err("struct S\n    r: volatile &int\nprint(0)") should
        include("a counted value's accesses come with retains and releases")
    }
    "a string, which is one of those" in {
      err("struct S\n    s: volatile string\nprint(0)") should
        include("a counted value's accesses come with retains and releases")
    }
    "a view, which is another" in {
      err("struct S\n    v: volatile []u32\nprint(0)") should
        include("a counted value's accesses come with retains and releases")
    }

    // Two words, so one written access is two machine accesses whichever way it is spelled — and the
    // table beside the value was emitted by this compiler, which no device put there.
    "a trait object, which is a pair of words" in {
      val src =
        """trait Show
          |    show(self) -> unit
          |struct S
          |    a: volatile *Show
          |print(0)""".stripMargin

      err(src) should include("a pair of words")
    }

    "a type that occupies nothing, since there is no access to describe" in {
      err("struct S\n    a: volatile unit\nprint(0)") should
        include("'volatile' qualifies a scalar or a raw pointer")
    }

    // A constrained subtype is the claim that a value has been checked, and a device did not check
    // anything — so reading a register at one would hand back the claim through a field selection
    // that looks like any other.
    "a constrained subtype, whose whole content is that a value was checked" in {
      val src =
        """type Level = int within 0..7
          |struct Ctl
          |    lvl: volatile Level
          |print(0)""".stripMargin

      err(src) should include("Declare the register at int and convert what you read")
    }

    // The other side of that line: `new` alone changes a type's identity and promises nothing about
    // which values it has, so there is nothing a register could arrive holding that it contradicts.
    "while a bare 'new' derivation, which constrains no value, is admitted" in {
      val src =
        """type Raw = new u32
          |struct Ctl
          |    raw: volatile Raw
          |val c: *Ctl = ptr_cast(usize(4096))
          |get() -> Raw = c.raw
          |print(u32(get()))""".stripMargin

      defineOf(ir(src), "get") should include("load volatile i32")
    }

    // The qualifier is not a modifier that stacks, and writing it twice is a mistake worth naming
    // rather than an idempotent no-op.
    "and writing it twice" in {
      err("struct S\n    a: volatile volatile u32\nprint(0)") should include("the type of *storage*")
    }
  }

  "a raw pointer may itself sit in device memory" - {
    "so 'volatile *T' is a field, distinct from '*volatile T'" in {
      val src =
        """struct S
          |    p: volatile *u32
          |val s: *S = ptr_cast(usize(4096))
          |read() -> *u32 = s.p
          |print(*read())""".stripMargin

      defineOf(ir(src), "read") should include("load volatile ptr")
    }

    "and so may the address of a function" in {
      val src =
        """struct S
          |    hook: volatile *extern() -> unit
          |val s: *S = ptr_cast(usize(4096))
          |fire() -> unit = s.hook()
          |fire()""".stripMargin

      defineOf(ir(src), "fire") should include("load volatile ptr")
    }
  }

  // The word is not reserved, for the reason `sync` is not: it is special only in front of another
  // type, and a program that had a `volatile` of its own before this existed still compiles.
  "'volatile' stays an ordinary name everywhere else" - {
    "as a type's name" in {
      val src =
        """type volatile = new int
          |f(v: volatile) -> int = int(v)
          |print(f(volatile(3)))""".stripMargin

      run(src) shouldBe "3\n"
    }
    "as a variable's name" in {
      run("var volatile = 3\nvolatile += 1\nprint(volatile)") shouldBe "4\n"
    }
    "as a field's name" in {
      run("struct S\n    volatile: int\nprint(S(7).volatile)") shouldBe "7\n"
    }
    "and as a function's name" in {
      run("volatile(n: int) -> int = n * 2\nprint(volatile(21))") shouldBe "42\n"
    }
  }

  // A qualifier that changed what a program computed would be a bug, not a feature: the accesses are
  // the same accesses, emitted where the source put them. Heap storage stands in for a device here
  // because it is the one kind this suite can point a `*volatile T` at and then read back.
  "the accesses are the ones the source wrote, and they do what they say" - {
    "a register block written and read through a pointer" in {
      val src =
        """extern malloc(n: usize) -> *u8
          |struct Uart
          |    status: volatile u32
          |    data:   volatile u32
          |    baud:   u32
          |go() -> unit
          |    val regs: *Uart = ptr_cast(malloc(sizeof(Uart)))
          |    regs.status = 1u32
          |    regs.data = 65u32
          |    regs.baud = 115200u32
          |    print(regs.status, regs.data, regs.baud)
          |go()""".stripMargin

      run(src) shouldBe "1 65 115200\n"
    }

    "a lone register incremented in place" in {
      val src =
        """extern malloc(n: usize) -> *u8
          |go() -> unit
          |    val p: *volatile u32 = ptr_cast(malloc(4))
          |    *p = 0u32
          |    p[0] += 5u32
          |    p[0]++
          |    print(p[0])
          |go()""".stripMargin

      run(src) shouldBe "6\n"
    }

    "a bank of them, indexed" in {
      val src =
        """extern malloc(n: usize) -> *u8
          |struct Gpio
          |    bank: [4]volatile u32
          |go() -> unit
          |    val g: *Gpio = ptr_cast(malloc(sizeof(Gpio)))
          |    for i in 0..<4 do g.bank[i] = u32(i) * 10u32
          |    print(g.bank[0], g.bank[3])
          |go()""".stripMargin

      run(src) shouldBe "0 30\n"
    }

    "and one reached through an address taken of a field" in {
      val src =
        """extern malloc(n: usize) -> *u8
          |struct Uart
          |    status: volatile u32
          |    data:   volatile u32
          |go() -> unit
          |    val regs: *Uart = ptr_cast(malloc(sizeof(Uart)))
          |    val p: *volatile u32 = &regs.data
          |    *p = 9u32
          |    print(regs.data)
          |go()""".stripMargin

      run(src) shouldBe "9\n"
    }
  }

  // The customer the whole feature is for, as a whole driver rather than as a fragment: no OS, no
  // prologue that has run, and every access to the device is one the source wrote.
  "a driver on a freestanding target, which is what this is for" - {
    val riscv = Target.all.find(_.name == "riscv64-freestanding").get

    /** A UART driver of the shape a bring-up actually has: a shadow field configured once, a status
     * register polled, a data register written per byte.
     */
    val driver = uart +
      """init(rate: u32) -> unit
        |    regs.baud = rate
        |putc(c: u32) -> unit
        |    while regs.status & 0x20u32 == 0u32 do ()
        |    regs.data = c
        |puts(s: []const u8) -> unit
        |    for b in s do putc(u32(b))
        |boot() -> unit
        |    init(115200u32)
        |    puts("hi".bytes)
        |boot()""".stripMargin

    "the register block is readable before anything has run" in {
      irFor(riscv, driver) should
        include("@regs = private constant ptr inttoptr (i64 268435456 to ptr)")
    }

    // The poll is the reason the qualifier exists: an unmarked load here is a loop that spins on the
    // value it saw first.
    "the status poll is a volatile load inside the loop" in {
      val body = defineOf(irFor(riscv, driver), "putc")

      body should include("load volatile i32")
      body should include("store volatile i32")
    }

    // Configuring the shadow field touches no register at all, which is what per-field qualifying
    // buys and what a qualifier on the whole struct would have cost.
    "while configuring the shadow field touches no device" in {
      defineOf(irFor(riscv, driver), "init") should not include "volatile"
    }
  }

  // A register block is a type like any other, so it may be declared in one module and driven from
  // another — which means the qualifier has to survive the codec a library artifact is written with.
  "a register block crosses a module boundary" in {
    val out = irOf(
      "dev.sysl" ->
        """module dev
          |struct Uart
          |    status: volatile u32
          |    baud:   u32
          |""".stripMargin,
      "main.sysl" ->
        """import dev
          |val regs: *dev.Uart = ptr_cast(usize(0x10000000))
          |read() -> u32 = regs.status
          |set() -> unit = regs.baud = 1u32
          |print(read())
          |set()""".stripMargin,
    )

    defineOf(out, "read") should include("load volatile i32")
    defineOf(out, "set") should not include "store volatile"
  }

  "the written tree survives being written out and read back" in {
    val src =
      """struct Uart
        |    status: volatile u32
        |    bank:   [4]volatile u32
        |    baud:   u32
        |read(p: *volatile u32) -> u32 = *p
        |""".stripMargin

    val parsed = SyslParser.parse(Source("<t>", src)) match
      case Right(p) => p
      case Left(e)  => fail(s"the fixture does not parse: $e")

    AstCodec.decode(AstCodec.encode(List(parsed)), Map.empty) match
      case Right(List(back)) => back.body shouldBe parsed.body
      case Right(other)      => fail(s"expected one program, got ${other.length}")
      case Left(e)           => fail(s"decode failed: $e")
  }

  // `16 §6`'s rule and this one meet at a struct that holds registers, and the answer is that such a
  // struct carries no invariant. A check is a call taking every field, so it reads the whole block
  // however few fields the clause names — which at a device is not a redundant read.
  "a struct that holds a register carries no invariant" - {
    "even where the clause reads only the ordinary fields beside it" in {
      val src =
        """struct Uart
          |    status: volatile u32
          |    baud:   u32
          |    invariant baud > 0u32
          |print(0)""".stripMargin

      err(src) should include("holds the register 'status', so it carries no invariant")
    }

    "while the same struct without the clause is ordinary" in {
      ir(uart + "go() = regs.baud = 1u32\ngo()") should include("store i32 1")
    }

    // The rule is about the register, not about invariants: a struct with no registers keeps them.
    "and a struct with no registers keeps its invariants" in {
      val src =
        """struct Account
          |    balance: int
          |    invariant balance >= 0
          |print(Account(1).balance)""".stripMargin

      ir(src) should include("@Account$inv")
    }
  }

  // A generic body's loads and stores are its own rather than the ones a caller wrote, so a type
  // parameter never binds to a qualified type — and what the caller is told names both types rather
  // than reporting an inference that failed.
  "a type parameter does not carry the qualifier into a generic body" in {
    val src =
      """first[T](xs: []T) -> T = xs[0]
        |read(bank: []volatile u32) -> u32 = first(bank)
        |print(0)""".stripMargin

    val message = err(src)

    message should include("[]volatile uint")
    message should include("[]uint")
  }

  // A qualifier is about the instruction, never about the layout: a block of registers is laid out
  // exactly as the same fields unqualified would be, which is what lets a device header be
  // transcribed field for field.
  "it costs no storage" in {
    val src =
      """struct Regs
        |    a: volatile u32
        |    b: u32
        |struct Plain
        |    a: u32
        |    b: u32
        |print(sizeof(Regs), alignof(Regs), sizeof(Plain))""".stripMargin

    run(src) shouldBe "8 4 8\n"
  }
}
