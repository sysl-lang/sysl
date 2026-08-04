package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** The atomic tier — `06 § The kernel tier`, `Atomics`.
 *
 * Three questions, and they need three kinds of test. What instruction comes out is a claim about
 * the *text*, because an ordering is a keyword and a keyword that came out wrong still runs. What
 * the operations compute is a claim about a running program. And what is refused is most of the
 * surface: the raw tier states its ordering at every call and reaches only what the machine has an
 * instruction for, so nearly every way of writing one of these wrong has a complaint of its own.
 */
class AtomicTests extends AnyFreeSpec with CodegenSupport with RunSupport {

  private val cell = "import sysl.sync.*\n\nvar n: i64 = 0\nvar p = &n\n"

  /** The compiler spells five variant names for itself and turns each into a keyword. Nothing else
   * checks that the library still declares those five: a renamed variant would leave the name
   * unresolvable at the call, and a *sixth* would be a name that analyzes and then has no keyword to
   * become. Both are silent until a program writes the one that moved.
   */
  "the orderings the compiler knows are the ones the library declares" in {
    val src = "import sysl.sync.*\n\nname(o: Ordering) -> int = o match\n" +
      Atomics.llvm.keys.map(v => s"    $v -> 1\n").mkString

    ir(src + "print(name(SeqCst))") should include("define")
    Atomics.llvm.keySet shouldBe Set("Relaxed", "Acquire", "Release", "AcqRel", "SeqCst")
    Library.key("Ordering") shouldBe "sysl.sync$Ordering"
  }

  "what comes out" - {
    "a load and a store name their ordering and state their alignment" in {
      val out = ir(cell + "atomic_store(p, 7i64, SeqCst)\nprint(atomic_load(p, Acquire))")

      out should include regex raw"store atomic i64 7, ptr %t\d+ seq_cst, align 8"
      out should include regex raw"%t\d+ = load atomic i64, ptr %t\d+ acquire, align 8"
    }

    // LLVM kept C11's `relaxed` semantics under its own older name, so this is the one ordering
    // whose spelling is a translation rather than a lowercasing — and the one that would go wrong
    // silently, since a stronger ordering still computes the right answer.
    "'Relaxed' is emitted as LLVM's own name for it" in {
      val out = ir(cell + "print(atomic_load(p, Relaxed))")

      out should include("load atomic i64, ptr")
      out should include("monotonic")
      out should not include "relaxed"
    }

    "every ordering reaches the instruction it names" in {
      val emitted = List("Relaxed" -> "monotonic", "Acquire" -> "acquire", "SeqCst" -> "seq_cst")

      for (written, keyword) <- emitted do
        withClue(written)(ir(cell + s"print(atomic_load(p, $written))") should
          include regex raw"load atomic i64, ptr %t\d+ $keyword,")

      ir(cell + "print(atomic_add(p, 1i64, Release))") should include("atomicrmw add ptr")
      ir(cell + "print(atomic_add(p, 1i64, Release))") should include("release")
      ir(cell + "print(atomic_add(p, 1i64, AcqRel))") should include("acq_rel")
    }

    "each read-modify-write is the one instruction that means it" in {
      val ops = List("add" -> "add", "sub" -> "sub", "and" -> "and", "or" -> "or", "xor" -> "xor",
                     "swap" -> "xchg")

      for (written, kind) <- ops do
        withClue(written)(ir(cell + s"print(atomic_$written(p, 1i64, SeqCst))") should
          include regex raw"%t\d+ = atomicrmw $kind ptr %t\d+, i64 %?\w+ seq_cst")
    }

    // `cmpxchg` answers a pair, and what the form hands back is the value that was found — the same
    // information the flag carries, since a caller compares it against what they expected.
    "a compare-and-swap takes the value out of the pair the instruction answers" in {
      val out = ir(cell + "print(atomic_cas(p, 1i64, 2i64, SeqCst))")

      out should include regex raw"%t\d+ = cmpxchg ptr %t\d+, i64 1, i64 2 seq_cst seq_cst"
      out should include regex raw"%t\d+ = extractvalue \{ i64, i1 \} %t\d+, 0"
    }

    /** A failed compare-and-swap stored nothing, so its ordering may not carry a release — LLVM
     * refuses the instruction outright if it does. The rule applied is C11's: drop the release half
     * and keep whatever acquire was asked for, which is never weaker than the author wrote.
     */
    "and the ordering its failure path gets can never be a release" in {
      val pairs = List("Release" -> "release monotonic", "AcqRel" -> "acq_rel acquire",
                       "Acquire" -> "acquire acquire", "Relaxed" -> "monotonic monotonic")

      for (written, keywords) <- pairs do
        withClue(written)(ir(cell + s"print(atomic_cas(p, 1i64, 2i64, $written))") should
          include regex raw"cmpxchg ptr %t\d+, i64 1, i64 2 $keywords")
    }

    // No `syncscope`, which is the default and means the whole system — what a program sharing
    // memory with another thread, or with a device, needs.
    "a fence is a fence and reaches no address" in {
      val out = ir(cell + "atomic_fence(SeqCst)")

      out should include("fence seq_cst")
      out should not include "syncscope"
    }

    "the width comes from the address rather than from the value written" in {
      val out = ir("import sysl.sync.*\n\nvar f: u32 = 0u32\nvar q = &f\natomic_store(q, 1u32, SeqCst)")

      out should include regex raw"store atomic i32 1, ptr %t\d+ seq_cst, align 4"
    }

    // A pointer is a word the machine can exchange, and it is what a lock-free list's head is. The
    // alignment is the target's pointer width rather than the pointee's.
    "a pointer is a word like any other, and swaps as one" in {
      val out = ir("""import sysl.sync.*
                     |
                     |var n: i64 = 1
                     |var head: *i64 = &n
                     |var slot = &head
                     |print(atomic_swap(slot, null, SeqCst) == null)""".stripMargin)

      out should include regex raw"%t\d+ = atomicrmw xchg ptr %t\d+, ptr null seq_cst"
    }
  }

  "what it computes" - {
    // Every read-modify-write answers the value that was there *before*, which is the property that
    // makes an atomic increment usable as a ticket. Asserted as a sequence rather than one at a
    // time, because "returns the previous value" is a claim about the pair of numbers.
    "a read-modify-write answers what was there before, and leaves what it computed" in {
      val src =
        """import sysl.sync.*
          |
          |var n: i64 = 7
          |var p = &n
          |print(atomic_add(p, 5i64, SeqCst))
          |print(atomic_load(p, SeqCst))
          |print(atomic_sub(p, 2i64, SeqCst))
          |print(atomic_load(p, SeqCst))
          |print(atomic_swap(p, 100i64, SeqCst))
          |print(atomic_load(p, SeqCst))""".stripMargin

      run(src) shouldBe "7\n12\n12\n10\n10\n100\n"
    }

    "the bitwise three run at the width the address gives them" in {
      val src =
        """import sysl.sync.*
          |
          |var flags: u32 = 0b1010u32
          |var q = &flags
          |print(atomic_or(q, 0b0101u32, SeqCst))
          |print(atomic_and(q, 0b1100u32, SeqCst))
          |print(atomic_xor(q, 0b1111u32, SeqCst))
          |print(atomic_load(q, SeqCst))""".stripMargin

      run(src) shouldBe "10\n15\n12\n3\n"
    }

    // Both outcomes of a compare-and-swap, since the interesting one is the failure: it answers what
    // it found and leaves the word alone, which is what a retry loop reads to know it must go again.
    "a compare-and-swap swaps when it matches and reports what it found when it does not" in {
      val src =
        """import sysl.sync.*
          |
          |var n: i64 = 100
          |var p = &n
          |print(atomic_cas(p, 100i64, 42i64, SeqCst))
          |print(atomic_load(p, SeqCst))
          |print(atomic_cas(p, 99i64, 1i64, SeqCst))
          |print(atomic_load(p, SeqCst))""".stripMargin

      run(src) shouldBe "100\n42\n42\n42\n"
    }

    // The shape the tier exists for: a ticket taken out of a counter with no lock anywhere. Single
    // threaded here — what is being checked is that the loop of increments is the arithmetic it
    // looks like — and the concurrent form is what `sysl.thread` will be able to state.
    "a counter incremented in a loop lands where the arithmetic says" in {
      val src =
        """import sysl.sync.*
          |
          |var n: i64 = 0
          |var p = &n
          |var i = 0
          |var last: i64 = -1
          |while i < 1000
          |    last = atomic_add(p, 1i64, Relaxed)
          |    i++
          |print(atomic_load(p, SeqCst), last)""".stripMargin

      run(src) shouldBe "1000 999\n"
    }

    // A retry loop, which is what every lock-free algorithm is made of, and the one program shape
    // that reads the result of a failed exchange rather than discarding it.
    "a compare-and-swap retry loop reaches its value" in {
      val src =
        """import sysl.sync.*
          |
          |var n: i64 = 5
          |var p = &n
          |double(cell: *i64) -> i64
          |    var seen = atomic_load(cell, Acquire)
          |    while atomic_cas(cell, seen, seen * 2, AcqRel) != seen
          |        seen = atomic_load(cell, Acquire)
          |    seen * 2
          |print(double(p), atomic_load(p, SeqCst))""".stripMargin

      run(src) shouldBe "10 10\n"
    }

    "a fence runs and orders nothing a single thread could notice" in {
      val src =
        """import sysl.sync.*
          |
          |var n: i64 = 1
          |var p = &n
          |atomic_store(p, 2i64, Release)
          |atomic_fence(SeqCst)
          |print(atomic_load(p, Acquire))""".stripMargin

      run(src) shouldBe "2\n"
    }
  }

  /** The tier is the raw one, which means it is what other code is *written on*: a generic wrapper
   * over `*T`, and a module that has given up the allocator. Both are `06 § The kernel tier`'s own
   * claims about it, and neither follows from the forms working at a top-level statement.
   */
  "the shapes it is meant to be used from" - {
    // The shape `Atomic[T]` will be: a body generic in the word's type, with the address the only
    // thing it holds. A generic body is walked once with its parameters standing in for themselves
    // and that tree thrown away, so what this pins is that the check happens at the instantiation.
    "a generic body over '*T' lowers at whatever it is instantiated with" in {
      val src =
        """import sysl.sync.*
          |
          |bump[T](p: *T, by: T) -> T = atomic_add(p, by, SeqCst)
          |var n: i64 = 1
          |var w: u32 = 10u32
          |print(bump(&n, 4i64), bump(&w, 1u32), n, w)""".stripMargin

      run(src) shouldBe "1 10 5 11\n"
    }

    // And the refusal arrives there too, naming the type the caller supplied rather than the
    // parameter the body was written with.
    "and a type with no atomic instruction is refused at the instantiation" in {
      val out = err("""import sysl.sync.*
                      |
                      |struct Pair
                      |    a: int
                      |    b: int
                      |bump[T](p: *T, by: T) -> T = atomic_add(p, by, SeqCst)
                      |var v = Pair(1, 2)
                      |print(bump(&v, Pair(1, 1)).a)""".stripMargin)

      out should include("Pair is neither")
    }

    /** `06 § The kernel tier` puts these "in a library, available under `no alloc`", which is the
     * whole reason the ordering enum went into a module that requires nothing. A form that reached
     * the allocator — or that needed a module which did — would make the tier useless in the place
     * it exists for.
     */
    "a module that has given up the allocator and the operating system still reaches them" in {
      val out = irOf(
        "spin/lock.sysl" ->
          """module spin
            |@no_alloc
            |@no_os
            |
            |import sysl.sync.*
            |
            |acquire(flag: *i32) -> unit
            |    while atomic_swap(flag, 1i32, Acquire) != 0i32
            |        atomic_fence(Acquire)
            |release(flag: *i32) -> unit = atomic_store(flag, 0i32, Release)
            |""".stripMargin,
        "main.sysl" ->
          """var flag: i32 = 0
            |spin.acquire(&flag)
            |spin.release(&flag)
            |print(flag)""".stripMargin,
      )

      out should include("atomicrmw xchg ptr")
      out should include("store atomic i32 0")
    }

    "and the same module runs" in {
      val out = runOf(
        "spin/lock.sysl" ->
          """module spin
            |@no_alloc
            |@no_os
            |
            |import sysl.sync.*
            |
            |acquire(flag: *i32) -> unit
            |    while atomic_swap(flag, 1i32, Acquire) != 0i32
            |        atomic_fence(Acquire)
            |release(flag: *i32) -> unit = atomic_store(flag, 0i32, Release)
            |""".stripMargin,
        "main.sysl" ->
          """var flag: i32 = 0
            |spin.acquire(&flag)
            |print(flag)
            |spin.release(&flag)
            |print(flag)""".stripMargin,
      )

      out shouldBe "1\n0\n"
    }

    "the narrow widths the machine has, as well as the wide ones" in {
      val src =
        """import sysl.sync.*
          |
          |var b: u8 = 200u8
          |var h: i16 = -3i16
          |print(atomic_add(&b, 1u8, SeqCst), atomic_load(&b, SeqCst))
          |print(atomic_sub(&h, 4i16, SeqCst), atomic_load(&h, SeqCst))""".stripMargin

      run(src) shouldBe "200 201\n-3 -7\n"
    }

    // A constrained subtype is an integer with a promise about the values put into it, and the
    // instruction runs at the integer — the same resolution `Bits` makes (`14 §5`). The promise is
    // the writer's, since an atomic write is not a place the compiler can insert a check.
    "a constrained subtype runs at the integer underneath it" in {
      val out = ir("""import sysl.sync.*
                     |
                     |type Level = i32 within 0..10
                     |var v: Level = 3
                     |print(atomic_load(&v, SeqCst))""".stripMargin)

      out should include regex raw"load atomic i32, ptr \S+ seq_cst, align 4"
    }
  }

  "what is refused" - {
    // The open family of integer widths is what makes this a real case rather than a defensive one:
    // `u12` is a type a program may name (`01`) and no machine has an atomic instruction for it.
    "a width no machine has an instruction for, saying so" in {
      val out = err("import sysl.sync.*\n\nvar n: u12 = 0u12\nvar p = &n\nprint(atomic_load(p, SeqCst))")

      out should include("8, 16, 32 and 64 bits")
      out should include("u12 is 12")
    }

    "an aggregate, pointing at what holds one instead" in {
      val out = err("""import sysl.sync.*
                      |
                      |struct Pair
                      |    a: int
                      |    b: int
                      |var v = Pair(1, 2)
                      |var p = &v
                      |print(atomic_load(p, SeqCst))""".stripMargin)

      out should include("indivisibly")
      out should include("SpinLock")
    }

    // A counted reference is the mistake worth its own complaint: the reader is thinking about the
    // right problem and reaching for the wrong tier, since a `&sync T`'s count is already atomic.
    "a counted reference, since what is atomic about one is already atomic" in {
      val out = err("""import sysl.sync.*
                      |
                      |struct Cell
                      |    v: i64
                      |var c: &sync Cell = Cell(1)
                      |print(atomic_load(c, SeqCst))""".stripMargin)

      out should include("counted reference")
      out should include("already atomic")
    }

    "a value that is not an address at all" in {
      val out = err("import sysl.sync.*\n\nvar n: i64 = 0\nprint(atomic_load(n, SeqCst))")

      out should include("takes the address of the word")
      out should include("'&place'")
    }

    // Arithmetic on an address is a question the raw tier does not answer, and the two forms that
    // *can* change a pointer are named so the reader is not left with only a refusal.
    "arithmetic on a pointer, naming the two forms that do change one" in {
      val out = err("""import sysl.sync.*
                      |
                      |var n: i64 = 1
                      |var head: *i64 = &n
                      |var slot = &head
                      |print(atomic_add(slot, 1, SeqCst))""".stripMargin)

      out should include("atomic_swap")
      out should include("atomic_cas")
    }

    /** The refusal the whole design turns on. An ordering held in a variable is well-typed sysl that
     * cannot be lowered — the instruction spells its ordering as a keyword — so without this the
     * type checker passes it and codegen has nothing to emit.
     */
    "an ordering that is a value rather than a name written at the call" in {
      val out = err("""import sysl.sync.*
                      |
                      |var n: i64 = 0
                      |var p = &n
                      |var how = Relaxed
                      |print(atomic_load(p, how))""".stripMargin)

      out should include("spells its ordering into the instruction")
      out should include("branch on their choice")
    }

    // And the same position filled with something that is not an ordering at all, which is a
    // different mistake and gets the answer that names where the orderings live.
    "something that is not an ordering, naming where they come from" in {
      val out = err("import sysl.sync.*\n\nvar n: i64 = 0\nvar p = &n\nprint(atomic_load(p, 3))")

      out should include("one of Ordering's names from 'sysl.sync'")
      out should include("SeqCst")
    }

    "too few arguments, counting what the form takes" in {
      val out = err("import sysl.sync.*\n\nvar n: i64 = 0\nvar p = &n\nprint(atomic_load(p))")

      out should include("'atomic_load' takes 2 arguments")
      out should include("an address, no value, and an ordering")
    }

    "and a compare-and-swap says it takes two values" in {
      val out = err("import sysl.sync.*\n\nvar n: i64 = 0\nvar p = &n\nprint(atomic_cas(p, 1i64, SeqCst))")

      out should include("'atomic_cas' takes 4 arguments")
      out should include("2 values")
    }

    "a value of a type the address does not point at" in {
      val out = err("import sysl.sync.*\n\nvar n: i64 = 0\nvar p = &n\natomic_store(p, true, SeqCst)")

      out should include("operates at the type its address points at")
    }

    /** The half of an ordering an operation has nothing to do with. A release publishes the writes
     * that came before it and a load makes none; an acquire sees what a release published and a
     * store reads nothing. Found by writing `Atomic[T].load`'s five-arm dispatch, whose `Release`
     * arm assembled into `atomic load cannot use Release ordering` against a temporary `.ll`.
     */
    "a load asked to release, which is not an instruction" in {
      for written <- List("Release", "AcqRel") do
        withClue(written) {
          val out = err(cell + s"print(atomic_load(p, $written))")

          out should include("a load has nothing to release")
          out should include(s"'$written'")
          out should include("'Acquire'")
        }
    }

    "a store asked to acquire, likewise" in {
      for written <- List("Acquire", "AcqRel") do
        withClue(written) {
          val out = err(cell + s"atomic_store(p, 1i64, $written)")

          out should include("a store reads nothing")
          out should include(s"'$written'")
          out should include("'Release'")
        }
    }

    // The halves each of them *does* have, which is what would go unnoticed if either refusal above
    // were written a variant too wide. Every read-modify-write takes all five, so `atomic_add` is
    // here to pin that the narrowing is these two forms' and not the tier's.
    "while the orderings each of them does have are all accepted" in {
      for written <- List("Relaxed", "Acquire", "SeqCst") do
        withClue(s"load $written")(ir(cell + s"print(atomic_load(p, $written))") should include("load atomic"))

      for written <- List("Relaxed", "Release", "SeqCst") do
        withClue(s"store $written")(ir(cell + s"atomic_store(p, 1i64, $written)") should include("store atomic"))

      for written <- Atomics.llvm.keys do
        withClue(s"add $written")(ir(cell + s"print(atomic_add(p, 1i64, $written))") should include("atomicrmw add"))
    }

    /** A fence is nothing but its ordering, so a relaxed one orders nothing and the machine has no
     * instruction for it. Found by writing a spin loop that fenced relaxed between attempts: it
     * analyzed and lowered, and what refused it was the assembler, with "fence cannot be monotonic"
     * — a message naming neither the word written nor the file it was in.
     */
    "a fence that would order nothing, before the assembler says so less clearly" in {
      val out = err("import sysl.sync.*\n\natomic_fence(Relaxed)")

      out should include("nothing but its ordering")
      out should include("'Acquire', 'Release', 'AcqRel' or 'SeqCst'")
    }

    // The other four are what a fence may be, which is the half that would go unnoticed if the
    // refusal above were written too broadly.
    "while the four a fence may take are all accepted" in {
      for written <- List("Acquire", "Release", "AcqRel", "SeqCst") do
        withClue(written)(ir(s"import sysl.sync.*\n\natomic_fence($written)") should include("fence "))
    }

    "a fence given an address it has nothing to do with" in {
      val out = err("import sysl.sync.*\n\nvar n: i64 = 0\nvar p = &n\natomic_fence(p, SeqCst)")

      out should include("'atomic_fence' takes one argument")
      out should include("reaching any address of its own")
    }

    // The ordering names live in a module, so a program that never imported it cannot write one —
    // which is what makes the tier reachable only on purpose, without the forms needing a gate of
    // their own.
    "and without the module, there is no ordering to write" in {
      err("var n: i64 = 0\nvar p = &n\nprint(atomic_load(p, SeqCst))") should include("SeqCst")
    }
  }

  /** Nine names is a lot to take out of a program's vocabulary, and these do not take them. This is
   * a deliberate difference from the forms beside them: a program declaring `ptr_cast` gets the
   * form anyway and its own declaration is unreachable, which is a wart these did not copy.
   */
  "the names are the compiler's only where a program has not taken them" - {
    "a program's own function of the name is the one that is called" in {
      val src =
        """atomic_add(a: int, b: int) -> int = a + b
          |print(atomic_add(2, 3))""".stripMargin

      run(src) shouldBe "5\n"
    }

    "a local holding a closure is too, for the reason the nearest binding always is" in {
      val src =
        """var atomic_load = (n: int) -> n * 3
          |print(atomic_load(4))""".stripMargin

      run(src) shouldBe "12\n"
    }

    "and the fence is no different" in {
      val src =
        """atomic_fence() -> int = 9
          |print(atomic_fence())""".stripMargin

      run(src) shouldBe "9\n"
    }

    // The other direction of the same rule: a program that declared one of these names still had
    // the form available everywhere it did not, since what stands aside is the name and not the tier.
    "while a form the program did not claim still means what it means" in {
      val src =
        """import sysl.sync.*
          |
          |atomic_add(a: int, b: int) -> int = a + b
          |var n: i64 = 4
          |var p = &n
          |print(atomic_add(2, 3), atomic_swap(p, 8i64, SeqCst), atomic_load(p, SeqCst))""".stripMargin

      run(src) shouldBe "5 4 8\n"
    }
  }
}
