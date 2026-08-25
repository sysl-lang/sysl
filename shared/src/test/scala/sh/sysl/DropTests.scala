package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** A type-bound destructor — `reference/memory.md § A destructor`.
 *
 * **The claim is that a value's `drop` runs wherever the last reference to it goes, including where
 * no program could have written a call.** That is the whole argument for the feature over `defer`,
 * which covers every site a program can *name*: a resource inside a container, or inside a struct
 * inside a container, dies at a point with no expression in the source, so `defer` cannot reach it
 * and a leak there is not something a reader could have prevented.
 *
 * Each test prints from the destructor, so the *order and count* of the lines is the assertion. A
 * destructor that ran twice, ran early, or did not run shows up as different output rather than as a
 * failure to compile — none of which an accident produces.
 */
class DropTests extends AnyFreeSpec with RunSupport with CodegenSupport with TestFrameworkSupport {

  /** A type whose destructor announces itself. `id` is read *in* the destructor, which is what says
   * the value is still intact when it runs.
   */
  private val handle =
    """struct Handle
      |    id: int
      |
      |impl Drop for Handle
      |    drop(self) = print("drop", self.id)
      |""".stripMargin

  "a destructor runs when the last reference goes" - {
    "at the end of the scope that held the only one" in {
      run(s"""$handle
             |hold()
             |    var a: &Handle = Handle(1)
             |    print("in", a.id)
             |
             |hold()
             |print("out")""".stripMargin) shouldBe "in 1\ndrop 1\nout\n"
    }

    // Two references to one value: the first to go must not run it, and the second must.
    "once, however many references there were" in {
      run(s"""$handle
             |hold()
             |    var a: &Handle = Handle(7)
             |    var b = a
             |    print("two")
             |
             |hold()
             |print("out")""".stripMargin) shouldBe "two\ndrop 7\nout\n"
    }

    // Handing it back keeps it alive past the frame that made it, which is the case a destructor
    // placed at the end of every scope rather than at the count would get wrong.
    "and not while a reference is still held elsewhere" in {
      run(s"""$handle
             |make() -> &Handle = Handle(3)
             |
             |var kept = make()
             |print("kept", kept.id)
             |print("end")""".stripMargin) shouldBe "kept 3\nend\ndrop 3\n"
    }
  }

  "including where no 'defer' could have been written" - {
    // The case the card was decided on. Nothing in this program names the moment each element dies:
    // the slice goes, and its elements go with it.
    //
    // **The elements come apart last-to-first, and that is a consequence rather than a choice.**
    // Teardown is iterative (`reference/memory.md § A destructor`): a count reaching zero pushes
    // the object onto a worklist threaded through its own dead refcount slot, and the first release
    // to hit zero drains it. A worklist threaded that way is a stack, so a walk that releases the
    // elements in order pushes them in order and drains them in reverse. `03` states that no order
    // among siblings is promised; this pins what it does today, so a change to the drain is noticed
    // rather than silently altering the order every program sees.
    "an element of a container that goes out of scope" in {
      run(s"""$handle
             |hold()
             |    var xs: []&Handle = [Handle(1), Handle(2)]
             |    print("held", xs.len)
             |
             |hold()
             |print("out")""".stripMargin) shouldBe "held 2\ndrop 2\ndrop 1\nout\n"
    }

    "and one inside a struct inside a container" in {
      run(s"""$handle
             |struct Slot
             |    it: &Handle
             |
             |hold()
             |    var xs: []Slot = [Slot(Handle(4)), Slot(Handle(5))]
             |    print("held")
             |
             |hold()
             |print("out")""".stripMargin) shouldBe "held\ndrop 5\ndrop 4\nout\n"
    }

    // A destructor that lets go of a reference of its own: the inner value's destructor has to run
    // too, and after the outer one, since the outer is handed a value that is still intact.
    "and one whose own destructor releases the next" in {
      run(s"""$handle
             |struct Chain
             |    next: &Handle
             |
             |impl Drop for Chain
             |    drop(self) = print("chain", self.next.id)
             |
             |hold()
             |    var c: &Chain = Chain(Handle(9))
             |    print("made")
             |
             |hold()
             |print("out")""".stripMargin) shouldBe "made\nchain 9\ndrop 9\nout\n"
    }
  }

  "what it is handed, and what it may not do" - {
    // It runs *before* the value's own references are released, so a field may be read to close what
    // it names. That is the whole reason the order in the hook is what it is.
    "reads its own fields, which are intact" in {
      run("""struct Pair
            |    a: int
            |    b: string
            |
            |impl Drop for Pair
            |    drop(self) = print(self.a, self.b)
            |
            |hold()
            |    var p: &Pair = Pair(2, "two")
            |
            |hold()
            |print("out")""".stripMargin) shouldBe "2 two\nout\n"
    }

    "and answers nothing, so a result is refused" in {
      err("""struct P
            |    x: int
            |
            |impl Drop for P
            |    drop(self) -> int = 1""".stripMargin) should include("drop")
    }
  }

  "a value that never reaches the heap has no single death, so nothing runs" in {
    // `reference/memory.md § A destructor` states this limit outright rather than leaving it to be
    // discovered: a value type is copied, and a copy is not a second resource. The test pins the
    // *documented* behaviour, which is what makes widening it later a deliberate change rather than
    // a surprise.
    run(s"""$handle
           |hold()
           |    var h = Handle(1)
           |    print("value", h.id)
           |
           |hold()
           |print("out")""".stripMargin) shouldBe "value 1\nout\n"
  }

  "and module storage never drops, since it is never let go of" in {
    // `reference/modules.md § val — a thing`'s ruling, seen from this side: storage that lasts the whole run has nothing to write a
    // release on, so the count never reaches zero and the destructor never runs. Stated in the
    // chapter and pinned here, because the alternative — an exit pass — is what this refuses.
    run(s"""$handle
           |static var kept: &Handle = Handle(5)
           |print("live", kept.id)""".stripMargin) shouldBe "live 5\n"
  }

  "and a test build keeps it, which is the build with no other way to reach it" in {
    // A test build replaces the roots — the tests stand in for the `main` that is no longer there —
    // so a destructor, which no reachable body names, was reachable from nothing at all and went
    // with everything else unreached. The release hook still called it, and the failure was `use of
    // undefined value '@Handle.drop'` at the *link*: a package with a destructor could not compile
    // its own suite, whatever the suite said.
    testIr(s"""$handle
              |@test("a counted handle drops")
              |counted()
              |    var a: &Handle = Handle(1)
              |    assert_eq(a.id, 1)
              |""".stripMargin) should include("define void @Handle.drop(")
  }
}
