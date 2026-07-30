package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** `Hash` — the catalog trait a keyed container needs (`14 §2`, `§5`).
 *
 * It is here because its absence was **structural rather than stylistic**. `02`'s coherence rule
 * lets an `impl` live only with its trait or with its type, so two libraries that each declared
 * their own `Hash` could never share a key type's implementation, and a program using both wrote
 * the same hash twice. One trait in the catalog is what makes a key type's `impl` mean the same
 * thing to every container.
 *
 * The law it exists under is `a == b` ⟹ `hash(a) == hash(b)`, and that law is what picks the
 * built-in memberships: `Eq`'s, minus the floats (`NaN != NaN`, and `-0.0 == 0.0` across two bit
 * patterns) and minus the pointer modes (whose `==` is address equality).
 */
class HashTraitTests extends AnyFreeSpec with CodegenSupport with RunSupport {

  "a built-in hashes without an impl" - {
    "an integer" in {
      run("print(str(7.hash() == 7.hash()))") shouldBe "true\n"
    }

    "a string" in {
      run("""print(str("abc".hash() == "abc".hash()))""") shouldBe "true\n"
    }

    "a char" in {
      run("print(str('x'.hash() == 'x'.hash()))") shouldBe "true\n"
    }

    "a bool" in {
      run("print(str(true.hash() == true.hash()))") shouldBe "true\n"
    }

    // The interesting half: the hash has to *separate* as well as agree, or a table of it is a
    // linked list. Consecutive keys are the case that catches an unmixed hash.
    "and different values hash differently" in {
      run("print(str(7.hash() == 8.hash()))") shouldBe "false\n"
      run("""print(str("abc".hash() == "abd".hash()))""") shouldBe "false\n"
      run("print(str(false.hash() == true.hash()))") shouldBe "false\n"
    }

    // A table indexes by the *low* bits, and `0, 1, 2, …` have none worth indexing by until they
    // have been spread. So the built-in mixes rather than handing the value back — which is the
    // property to assert, since a hash promises scattering and not a permutation of three bits.
    // Zero is the case worth pinning: splitmix64's finalizer alone is a fixed point at 0, so the
    // key `0` would always land in bucket 0. Adding the gamma constant before mixing is what the
    // generator itself does, and is what removes it.
    "having actually mixed, rather than handed the value back" in {
      run("print(str(5.hash() == 5u64))") shouldBe "false\n"
      run("print(str(0.hash() == 0u64))") shouldBe "false\n"
      run("print(str(0.hash() == 0u64 || 0.hash() % 8u64 == 0u64))") shouldBe "false\n"
    }

    "and scattering consecutive keys across a table's buckets" in {
      run(
        """var seen: [8]bool
          |var filled = 0
          |for i in 0..<64
          |    var b = usize(i.hash()) % 8usize
          |    if !seen[b]
          |        seen[b] = true
          |        filled += 1
          |print(str(filled))""".stripMargin,
      ) shouldBe "8\n"
    }
  }

  // The law, at the one place it is not free: two integer types compare equal across widths, so
  // they have to hash equal too. That is what the widening in the lowering is for.
  "widths agree, because equality does" - {
    "across integer widths" in {
      run("print(str(1u8.hash() == 1i64.hash()))") shouldBe "true\n"
      run("print(str(300u16.hash() == 300i32.hash()))") shouldBe "true\n"
    }

    "and across signedness where the value is the same" in {
      run("print(str(5u32.hash() == 5i32.hash()))") shouldBe "true\n"
    }
  }

  "a user type opts in with an ordinary impl" - {
    "and satisfies the bound with it" in {
      val src =
        """struct P
          |    x: int
          |    y: int
          |impl Hash for P
          |    hash(self) -> u64 = self.x.hash() ^ self.y.hash() * 31u64
          |keyed[K: Hash](k: K) -> u64 = k.hash()
          |print(str(keyed(P(1, 2)) == keyed(P(1, 2))))
          |print(str(keyed(P(1, 2)) == keyed(P(2, 1))))""".stripMargin

      run(src) shouldBe "true\nfalse\n"
    }

    // `10 §5`'s definition-time check: the missing bound is reported at the line that writes the
    // call, not at whichever instantiation first supplied a type without one.
    "and without the bound the call is refused where it is written" in {
      err("keyed[K](k: K) -> u64 = k.hash()\nprint(1)") should include("hash")
    }

    "while a type with no impl does not satisfy it" in {
      val src =
        """struct P
          |    x: int
          |keyed[K: Hash](k: K) -> u64 = k.hash()
          |print(str(keyed(P(1))))""".stripMargin

      err(src) should include("Hash")
    }
  }

  "a built-in satisfies the bound, which is what the membership is for" - {
    "an integer" in {
      run("keyed[K: Hash](k: K) -> u64 = k.hash()\nprint(str(keyed(3) == keyed(3)))") shouldBe "true\n"
    }

    "a string, reaching a different lowering through the same bound" in {
      run("""keyed[K: Hash](k: K) -> u64 = k.hash()
            |print(str(keyed("a") == keyed("a")))""".stripMargin) shouldBe "true\n"
    }

    "and `Hash + Eq` together, which is what a map actually asks for" in {
      run(
        """same[K: Hash + Eq](a: K, b: K) -> bool = a == b && a.hash() == b.hash()
          |print(str(same(4, 4)))
          |print(str(same("x", "y")))""".stripMargin,
      ) shouldBe "true\nfalse\n"
    }
  }

  "what is deliberately not a member" - {
    // Rust's reason, and it is sysl's: `NaN != NaN` breaks the reflexivity a lookup assumes, and
    // `-0.0 == 0.0` holds across two different bit patterns.
    "a float, whose equality a hash over its bits would contradict" in {
      err("keyed[K: Hash](k: K) -> u64 = k.hash()\nprint(str(keyed(1.5)))") should include("Hash")
      err("print(str(1.5.hash()))") should include("hash")
    }

    // Asked through the *bound*, because `p.hash()` written directly is the one-level auto-deref
    // of `03` reaching the `u8` behind the pointer — which is a member, and a different question.
    "a pointer or a reference, whose equality is its address" in {
      err("keyed[K: Hash](k: K) -> u64 = k.hash()\nvar p: *u8 = null\nprint(str(keyed(p)))") should
        include("Hash")
      err(
        """struct P
          |    x: int
          |keyed[K: Hash](k: K) -> u64 = k.hash()
          |var r: &P = P(1)
          |print(str(keyed(r)))""".stripMargin,
      ) should include("Hash")
    }
  }

  "the trait itself is ordinary" - {
    // Which mixer `k.hash()` lowers to is chosen by the *compiler* from `CoreTraits`, not resolved
    // from source, so every symbol here is a name the compiler spells — and one that goes stale
    // silently unless it is read off the seam. The **negative** below is the one that would go
    // quiet: it happens to survive a move, because `hash_u64` is a substring of `sysl$hash_u64`,
    // and that is luck rather than design — the same assertion written `"@hash_u64"` would pass
    // vacuously the moment the declaration crossed.
    "declared in the library, so a program may name it in a bound" in {
      ir("keyed[K: Hash](k: K) -> u64 = k.hash()\nprint(str(keyed(1)))") should
        include(s"@${Library.key("hash_u64")}")
    }

    // Nothing is emitted for a program that hashes nothing, which is the library's standing rule.
    "and costs a program that hashes nothing exactly nothing" in {
      ir("print(1)") should not include Library.key("hash_u64")
    }

    "while a bool reaches its own lowering, one bit not being a number here" in {
      ir("print(str(true.hash()))") should include(s"@${Library.key("hash_bool")}")
    }

    "and a string reaches the byte-wise one" in {
      ir("""print(str("a".hash()))""") should include(s"@${Library.key("hash_str")}")
    }

    "and a wide integer reaches the one that folds it in two" in {
      // The fourth branch of the table, which nothing had asked about — and the one whose symbol a
      // move would change with nothing to notice.
      ir("print(str(u128(1).hash()))") should include(s"@${Library.key("hash_u128")}")
    }
  }

  "what it refuses" - {
    "arguments, there being none to take" in {
      err("print(str(7.hash(1)))") should include("takes no arguments")
    }

    "and a second impl for a type that already has one" in {
      val src =
        """struct P
          |    x: int
          |impl Hash for P
          |    hash(self) -> u64 = 1u64
          |impl Hash for P
          |    hash(self) -> u64 = 2u64
          |print(1)""".stripMargin

      err(src) should include("already")
    }

    "and an impl whose signature does not match the trait's" in {
      val src =
        """struct P
          |    x: int
          |impl Hash for P
          |    hash(self) -> int = 1
          |print(1)""".stripMargin

      err(src) should include("hash")
    }
  }
}
