package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** `&` in front of something that has no address — a construction, a call result, a literal, an
  * arithmetic result (`reference/memory.md § Addressing a value`).
  *
  * The rule is one sentence with a scope in it: the value is written into a hidden local of the
  * scope the `&` stands in, and what comes back is that slot's address. So the suite is organized
  * around the three things that sentence claims — that it is a **real** slot (writable, one per
  * `&`, with the operand evaluated exactly once), that it lives for the **scope** and not for the
  * statement, and that the pointer is an ordinary `*T` afterwards, unchecked like every other one.
  *
  * The last section is the pair of refusals the form is bounded by, and both are about the two
  * meanings of `&`: the sigil in a type is a counted box and the operator is an address, and
  * neither is a way of writing the other.
  */
class AmpConstructionTests extends AnyFreeSpec with RunSupport with CodegenSupport {

  private val shapes =
    """trait Shape
      |    area(self) -> int
      |end Shape
      |
      |struct Rect
      |    w: int
      |end Rect
      |
      |impl Shape for Rect
      |    area(self) -> int = self.w
      |
      |""".stripMargin

  "a value with no address gets one" - {

    "a construction" in {
      run(shapes + """var s: *Shape = &Rect(2)
                     |
                     |print(s.area())
                     |""".stripMargin) shouldBe "2\n"
    }

    // `Rect(2)` and `make()` are the same node — a `Call` — which is why the rule is about any
    // rvalue rather than about constructions. A rule that took one and refused the other could only
    // be stated as "you may address a constructor call but not a function call".
    "a call result" in {
      run(shapes + """make() -> Rect = Rect(3)
                     |
                     |var s: *Shape = &make()
                     |
                     |print(s.area())
                     |""".stripMargin) shouldBe "3\n"
    }

    "a literal" in {
      run("""var p: *int = &1
            |
            |print(*p)
            |""".stripMargin) shouldBe "1\n"
    }

    "an arithmetic result" in {
      run("""val a = 3
            |val b = 4
            |
            |var p: *int = &(a + b)
            |
            |print(*p)
            |""".stripMargin) shouldBe "7\n"
    }

    "a plain '*T' of the value's own type, with no trait object in sight" in {
      run(shapes + """var s: *Rect = &Rect(4)
                     |
                     |print(s.w)
                     |""".stripMargin) shouldBe "4\n"
    }
  }

  "the storage is a real slot" - {

    // The whole claim in one program: if this were a copy handed out to be read, the write would go
    // somewhere nothing reads back.
    "writing through the pointer and reading it back" in {
      run("""var p: *int = &1
            |
            |*p = 9
            |
            |print(*p)
            |""".stripMargin) shouldBe "9\n"
    }

    "two in one statement are two slots" in {
      run("""add(a: *int, b: *int) -> int = *a + *b
            |
            |print(add(&1, &2))
            |""".stripMargin) shouldBe "3\n"
    }

    // A slot is laid down for the value; the value is not computed once per use of the pointer.
    "the operand is evaluated exactly once" in {
      run("""var calls = 0
            |
            |next() -> int
            |    calls += 1
            |    7
            |
            |var p: *int = &next()
            |
            |print(*p)
            |print(*p)
            |print(calls)
            |""".stripMargin) shouldBe "7\n7\n1\n"
    }

    // The slot belongs to the frame, so a loop reuses one rather than growing the stack — and each
    // iteration's value is what is in it while that iteration runs.
    "a loop writes the same slot each time round" in {
      run("""sum(p: *int) -> int = *p
            |
            |var t = 0
            |
            |for i in 0..<4
            |    t += sum(&(i * 2))
            |
            |print(t)
            |""".stripMargin) shouldBe "12\n"
    }
  }

  "a value that owns something is owned by the slot" - {

    // A string carries a count, so the slot takes one exactly as a `var`'s would. Reading it after
    // the statement that made it is what says the count was not given back at the semicolon.
    "a string outlives the statement that made it" in {
      run("""owner(p: *string) -> usize = p.len
            |
            |var p: *string = &("ab" + "cd")
            |
            |print(*p)
            |print(owner(p))
            |""".stripMargin) shouldBe "abcd\n4\n"
    }

    "a struct holding one" in {
      run("""struct Named
            |    name: string
            |end Named
            |
            |var p: *Named = &Named("bob")
            |
            |print(p.name)
            |""".stripMargin) shouldBe "bob\n"
    }

    // The release is emitted with the scope the value was written in, so it has to land on a path
    // the store dominates. A short-circuited operand is the case that catches getting that wrong:
    // the branch does not run, and a release emitted for it anyway would give back a count nobody
    // ever took.
    "an operand the program short-circuits past" in {
      run("""struct Named
            |    name: string
            |end Named
            |
            |wide(p: *Named) -> bool = p.name.len > 0
            |
            |val no = false
            |
            |if no && wide(&Named("never")) then print("yes") else print("no")
            |
            |print("done")
            |""".stripMargin) shouldBe "no\ndone\n"
    }

    "one made in a loop, round after round" in {
      run("""struct Named
            |    name: string
            |end Named
            |
            |width(p: *Named) -> usize = p.name.len
            |
            |var t: usize = 0
            |
            |for i in 0..<8
            |    t += width(&Named("abc"))
            |
            |print(t)
            |""".stripMargin) shouldBe "24\n"
    }
  }

  "what the pointer is afterwards" - {

    // Exactly what `03` says about every `*T`, and the reason this form adds no new hole: the same
    // pointer can be made today by naming the local first.
    "it is an ordinary '*T', so a branch's slot is that branch's" in {
      run(shapes + """val big = true
                     |
                     |var s: *Shape = if big then &Rect(5) else &Rect(1)
                     |
                     |print(s.area())
                     |""".stripMargin) shouldBe "5\n"
    }

    "taking the address of a place is unchanged" in {
      run(shapes + """var r = Rect(6)
                     |var s: *Shape = &r
                     |
                     |print(s.area())
                     |""".stripMargin) shouldBe "6\n"
    }

    // The materialization is for things with *no* address. A `val` has one, and what it does not
    // have is a writable one — so it goes on being refused rather than quietly copied into a slot
    // the program could then write through.
    "a 'val' is still refused rather than copied into a slot" in {
      err("""val v = 3
            |var p: *int = &v
            |
            |print(*p)
            |""".stripMargin) should include("written once")
    }
  }

  "module storage has no scope to hold one" - {

    // The one place the rule has nowhere to put what it makes: an initializer here runs in a
    // prologue, so the slot would be that prologue's frame and the pointer would be stale by the
    // program's first statement. A top-level `val` in the file the program starts in is a *local*
    // and is not this case, which is why the test says `static var`.
    "an initializer is refused" in {
      val e = err("""struct Rect
                    |    w: int
                    |end Rect
                    |
                    |static var p: *Rect = &Rect(2)
                    |
                    |print(p.w)
                    |""".stripMargin)

      e should include("module storage has no such scope")
    }

    "and the name it asks for instead works" in {
      run("""struct Rect
            |    w: int
            |end Rect
            |
            |static var r: Rect = Rect(2)
            |static var p: *Rect = &r
            |
            |print(p.w)
            |""".stripMargin) shouldBe "2\n"
    }
  }

  "the two meanings of '&'" - {

    // The sigil in a type is a counted box; the operator is an address. Neither is a way of writing
    // the other, and the box needs no operator at all.
    "an address is refused where a counted reference is wanted" in {
      val e = err(shapes + """var s: &Shape = &Rect(2)
                             |
                             |print(s.area())
                             |""".stripMargin)

      e should include("counted box")
      e should include("Drop the operator")
    }

    "and the spelling it names compiles" in {
      run(shapes + """var s: &Shape = Rect(2)
                     |
                     |print(s.area())
                     |""".stripMargin) shouldBe "2\n"
    }

    // What `&self` is missing is the **count**, not an address — a plain local is refused here too,
    // so an address could never have been the fix. The message therefore names the binding.
    "'&self' asks for a box, and says so" in {
      val e = err("""trait Shape
                    |    area(&self) -> int
                    |end Shape
                    |
                    |struct Rect
                    |    w: int
                    |end Rect
                    |
                    |impl Shape for Rect
                    |    area(&self) -> int = self.w
                    |
                    |print(Rect(3).area())
                    |""".stripMargin)

      e should include("counted reference")
      e should include("var r: &Rect")
    }

    "and the binding it names compiles" in {
      run("""trait Shape
            |    area(&self) -> int
            |end Shape
            |
            |struct Rect
            |    w: int
            |end Rect
            |
            |impl Shape for Rect
            |    area(&self) -> int = self.w
            |
            |var r: &Rect = Rect(3)
            |
            |print(r.area())
            |""".stripMargin) shouldBe "3\n"
    }
  }
}
