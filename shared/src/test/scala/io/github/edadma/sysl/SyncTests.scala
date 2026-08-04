package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** `sysl.sync` — `Atomic[T]` and `SpinLock`, which are the library half of `06 § The kernel tier`.
 *
 * The forms underneath have their own suite; what is asserted here is the surface built on them,
 * and the two things that surface adds. The first is the **default**: an ordering is required at
 * every raw call, and a program writes `a.add(1)`, so something in between has to be turning the
 * absence of a name into `SeqCst`. The second is the **dispatch**: an ordering arrives here as a
 * value and has to leave as one of five written names, which is a five-arm match per operation and
 * therefore five chances to write the wrong one — a mistake that computes the right answer on the
 * machine these tests run on and the wrong one on a weaker model, so it has to be read out of the
 * emitted text rather than inferred from a passing program.
 *
 * The narrowing on `load` and `store` is the third thing, and it is the only place in the module
 * where a check lands at run time: the form refuses a releasing load where the name is *written*,
 * and cannot see a name that arrived in a variable, which is exactly what a wrapper taking an
 * `Ordering` hands it.
 */
class SyncTests extends AnyFreeSpec with RunSupport with CodegenSupport {

  private val importing = "import sysl.sync.*\n\n"

  override protected def run(src: String): String = super.run(importing + src)

  override protected def ir(src: String): String = super.ir(importing + src)

  override protected def err(src: String): String = super.err(importing + src)

  override protected def exits(src: String): Unit = super.exits(importing + src)

  "what each operation answers" - {
    /** Every read-modify-write answers the value that was **there before**, which is the property
     * that makes an atomic increment usable as a ticket. A wrapper that handed back the *new* value
     * would look right in every one-thread test that only reads the counter afterwards, so the
     * before-value is what each line here is checking.
     */
    "a read-modify-write hands back what was there before it" in {
      run(
        """var a = Atomic(0i64)
          |print(a.add(5), a.add(3), a.sub(2), a.load())""".stripMargin
      ) shouldBe "0 5 8 6\n"
    }

    "a swap answers the value it displaced" in {
      run(
        """var a = Atomic(7i64)
          |print(a.swap(100), a.load())""".stripMargin
      ) shouldBe "7 100\n"
    }

    /** `cas` answers the value it **found**, so a caller comparing it against what they expected
     * learns whether it swapped — and on failure holds the value to retry against. Both outcomes are
     * here, because a `cas` that stored unconditionally would pass a test that only tried the
     * matching case.
     */
    "a compare-and-swap answers what it found, and stores only on a match" in {
      run(
        """var a = Atomic(42i64)
          |print(a.cas(42, 7), a.load())
          |print(a.cas(42, 99), a.load())""".stripMargin
      ) shouldBe "42 7\n7 7\n"
    }

    "the retry loop a caller writes out of that" in {
      run(
        """var a = Atomic(3i64)
          |
          |double(a: *Atomic[i64]) -> i64
          |    var seen = a.load()
          |
          |    while a.cas(seen, seen * 2) != seen
          |        seen = a.load()
          |
          |    seen
          |print(double(&a), a.load())""".stripMargin
      ) shouldBe "3 6\n"
    }

    "the bitwise three, each answering the word it changed" in {
      run(
        """var b = Atomic(0b1010u32)
          |print(b.or(0b0101u32), b.and(0b1100u32), b.xor(0b1111u32), b.load())""".stripMargin
      ) shouldBe "10 15 12 3\n"
    }

    "a store writes the word the reads are reading" in {
      run(
        """var a = Atomic(1i64)
          |a.store(9)
          |print(a.load())""".stripMargin
      ) shouldBe "9\n"
    }

    /** The field is not hidden, and this is the use that justifies it: a thread alone with the value
     * is entitled to the cheap read. It is also the discriminating test for the receiver — a `load`
     * that took `self` by value would read the address of a **copy**, so the two would part company
     * the moment anything wrote through the original.
     */
    "and reading the field directly agrees with a load" in {
      run(
        """var a = Atomic(4i64)
          |
          |bump(p: *Atomic[i64]) = p.add(6)
          |bump(&a)
          |print(a.v, a.load())""".stripMargin
      ) shouldBe "10 10\n"
    }
  }

  /** An ordering is a keyword in the emitted instruction, so a dispatch arm that named the wrong one
   * would still run — and on this machine would still compute the right answer, since these tests
   * are strongly ordered whatever is asked for. What catches it is the text.
   */
  "the ordering the caller wrote is the one that reaches the instruction" - {
    "an operation with no ordering written is sequentially consistent" in {
      ir("var a = Atomic(0i64)\nprint(a.add(1))") should
        include regex raw"atomicrmw add ptr %t\d+, i64 %t\d+ seq_cst"
    }

    "and every arm of the dispatch reaches the ordering it names" in {
      val out = ir("var a = Atomic(0i64)\nprint(a.add(1, Relaxed))")

      for keyword <- List("monotonic", "acquire", "release", "acq_rel", "seq_cst") do
        withClue(keyword)(out should include regex raw"atomicrmw add ptr %t\d+, i64 %t\d+ $keyword")
    }

    "a load reaches the three orderings a load has, and no others" in {
      val out = ir("var a = Atomic(0i64)\nprint(a.load(Acquire))")

      for keyword <- List("monotonic", "acquire", "seq_cst") do
        withClue(keyword)(out should include regex raw"load atomic i64, ptr %t\d+ $keyword")

      out should not include regex (raw"load atomic i64, ptr %t\d+ release")
      out should not include regex (raw"load atomic i64, ptr %t\d+ acq_rel")
    }

    "and a store the three a store has" in {
      val out = ir("var a = Atomic(0i64)\na.store(1, Release)")

      for keyword <- List("monotonic", "release", "seq_cst") do
        withClue(keyword)(out should include regex raw"store atomic i64 %t\d+, ptr %t\d+ $keyword")

      out should not include regex (raw"store atomic i64 %t\d+, ptr %t\d+ acquire")
      out should not include regex (raw"store atomic i64 %t\d+, ptr %t\d+ acq_rel")
    }

    "a compare-and-swap carries the failure ordering the form derives" in {
      ir("var a = Atomic(0i64)\nprint(a.cas(1, 2, AcqRel))") should
        include regex raw"cmpxchg ptr %t\d+, i64 %t\d+, i64 %t\d+ acq_rel acquire"
    }
  }

  /** `Ordering` answers which operations each of its names can order, because a wrapper taking one
   * as a **value** cannot have it refused where it was written. The whole truth table is here: a
   * predicate written a variant too wide refuses something valid, and one written a variant too
   * narrow lets a load reach an instruction that does not exist.
   */
  "which orderings a load and a store have" - {
    "the load half of the table" in {
      run(
        """print(Relaxed.orders_a_load(), Acquire.orders_a_load(), Release.orders_a_load(),
          |      AcqRel.orders_a_load(), SeqCst.orders_a_load())""".stripMargin
      ) shouldBe "true true false false true\n"
    }

    "the store half" in {
      run(
        """print(Relaxed.orders_a_store(), Acquire.orders_a_store(), Release.orders_a_store(),
          |      AcqRel.orders_a_store(), SeqCst.orders_a_store())""".stripMargin
      ) shouldBe "true false true false true\n"
    }

    // The contract is what stops the dispatch's catch-all arm from quietly promoting a releasing
    // load to `SeqCst`, which is sound and is not what the author asked for.
    "a releasing load traps rather than being promoted to something stronger" in {
      exits("var a = Atomic(0i64)\nprint(a.load(Release))")
      exits("var a = Atomic(0i64)\nprint(a.load(AcqRel))")
    }

    "an acquiring store likewise" in {
      exits("var a = Atomic(0i64)\na.store(1, Acquire)")
      exits("var a = Atomic(0i64)\na.store(1, AcqRel)")
    }

    "while the three each of them has run" in {
      run(
        """var a = Atomic(0i64)
          |a.store(1, Relaxed)
          |a.store(2, Release)
          |a.store(3, SeqCst)
          |print(a.load(Relaxed), a.load(Acquire), a.load(SeqCst))""".stripMargin
      ) shouldBe "3 3 3\n"
    }
  }

  "a spinlock" - {
    "is taken by the first to ask and refused to the next" in {
      run(
        """var l = SpinLock(0)
          |print(l.try_lock(), l.try_lock())""".stripMargin
      ) shouldBe "true false\n"
    }

    "and is free again once released" in {
      run(
        """var l = SpinLock(0)
          |print(l.try_lock(), l.try_lock())
          |l.unlock()
          |print(l.try_lock())""".stripMargin
      ) shouldBe "true false\ntrue\n"
    }

    // Taking a free lock does not spin, which is the case the loop has to get right: an `atomic_swap`
    // whose sense was inverted would hang here rather than fail.
    "taking a free lock returns, leaving it held" in {
      run(
        """var l = SpinLock(0)
          |l.lock()
          |print(l.held)
          |l.unlock()
          |print(l.held)""".stripMargin
      ) shouldBe "1\n0\n"
    }

    // Acquire on the way in, release on the way out. A lock's orderings are fixed by what a lock
    // means, which is why it takes no `Ordering` of its own.
    "takes with an acquire and releases with a release" in {
      val out = ir("var l = SpinLock(0)\nl.lock()\nl.unlock()")

      out should include regex raw"atomicrmw xchg ptr %t\d+, i32 1 acquire"
      out should include regex raw"store atomic i32 0, ptr %t\d+ release"
      out should include regex raw"load atomic i32, ptr %t\d+ monotonic"
    }
  }

  /** `06 § The kernel tier` puts these "in a library, available under `no alloc`", which is the whole
   * reason `sysl.sync` requires no capability. A type that reached the allocator — or the operating
   * system, through the panic a bounds check would want — would make the module useless in the place
   * it exists for, and nothing but a module that has given up both would notice.
   */
  "the module a kernel can reach" - {
    "a module with no allocator and no operating system builds both types" in {
      val out = runOf(
        "kern/counter.sysl" ->
          """module kern
            |@no_alloc
            |@no_os
            |
            |import sysl.sync.*
            |
            |struct Counter
            |    lock: SpinLock
            |    hits: Atomic[u32]
            |
            |    record(*self, n: u32) -> u32
            |        self.lock.lock()
            |        defer self.lock.unlock()
            |        self.hits.add(n, Relaxed)
            |end Counter
            |""".stripMargin,
        "main.sysl" ->
          """import kern.Counter
            |import sysl.sync.*
            |
            |var c = Counter(SpinLock(0), Atomic(0u32))
            |print(c.record(3u32), c.record(4u32), c.hits.load())""".stripMargin,
      )

      out shouldBe "0 3 7\n"
    }

    // And the contract on `load` traps rather than reporting, which is what lets it exist at all in
    // a module with no operating system to print to.
    "and the contract in there is a trap, not a message" in {
      ir("var a = Atomic(0i64)\nprint(a.load(Acquire))") should include("call void @llvm.trap()")
    }
  }

  "what is refused" - {
    // The refusal comes from the form, at the instantiation, naming the width the caller supplied
    // rather than the `T` the library was written with.
    "a width no machine has an instruction for" in {
      err("var a = Atomic(0u12)\nprint(a.load())") should include("8, 16, 32 and 64 bits")
    }

    "an aggregate, which is what a lock is for" in {
      err(
        """struct Pair
          |    a: int
          |    b: int
          |var a = Atomic(Pair(1, 2))
          |print(a.load().a)""".stripMargin
      ) should include("Pair is neither")
    }

    // A pointer is a word the machine can swap and compare-and-swap, and adding to one is a question
    // about pointer arithmetic the raw tier does not answer. The point of the test is that the
    // members part company: four of them work at `Atomic[*T]` and five do not.
    "arithmetic on an Atomic of pointers, while the exchanges on one work" in {
      run(
        """var x: i64 = 1
          |var y: i64 = 2
          |var p = Atomic(&x)
          |var was = p.swap(&y)
          |print(*was, *p.load(), *p.cas(&y, &x))""".stripMargin
      ) shouldBe "1 2 2\n"

      val out = err(
        """var x: i64 = 1
          |var p = Atomic(&x)
          |print(p.add(&x) == null)""".stripMargin
      )

      out should include("atomic_swap")
      out should include("atomic_cas")
    }
  }
}
