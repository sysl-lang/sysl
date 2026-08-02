package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** `ref name = place` (`03 § ref`) — a name for a place rather than for a value.
 *
 * The chapter makes two claims that are the whole of the feature, and each needs its own kind of
 * evidence. **At run time a ref stores an address**, which is a claim about the emitted code and is
 * pinned by counting bounds checks in the IR: a `var` copies, a re-written path re-checks, and a ref
 * does neither. **At compile time it remembers a place**, which is a claim about the checks, and is
 * pinned by writing through a ref into a struct carrying an `invariant` and watching the clause fire
 * — a thing a `*T` into the same field cannot do, and the reason this is not just address-of with a
 * nicer spelling.
 *
 * The disagreement halves matter more than usual here, because almost every test in this file would
 * pass on an implementation that quietly lowered `ref` to a copy. So each run test writes through
 * the ref and then reads the *original* place back.
 */
class RefBindingTests extends AnyFreeSpec with CodegenSupport with RunSupport {

  /** The register-block shape `03 § Device memory` is written against, shared by the probes below. */
  private val uart =
    """struct Uart
      |    status: volatile u32
      |    data:   volatile u32
      |    baud:   u32
      |const UART: usize = 0x10000000
      |val regs: *Uart = ptr_cast(UART)
      |""".stripMargin

  "what the chapter claims" - {

    // The headline: a write through the name lands in the storage the place found, not in a copy.
    // Reading `t.state` back through the table is the half that fails on a copying implementation.
    "a write through a ref lands in the place it named" in {
      val src =
        """struct Task
          |    state: int
          |    prio: int
          |end Task
          |struct Kernel
          |    tasks: [4]Task
          |end Kernel
          |var k = Kernel([Task(0, 0); 4])
          |ref t = k.tasks[2]
          |t.state = 7
          |t.prio = 9
          |print(k.tasks[2].state, k.tasks[2].prio, k.tasks[1].state)""".stripMargin

      run(src) shouldBe "7 9 0\n"
    }

    // The place is evaluated once, so the name goes on meaning the element it found. A
    // re-evaluating (call-by-name) implementation prints the *third* element here, which is the
    // Algol 60 behaviour the chapter rejects by name.
    "the place is evaluated once, so a later change to the index does not move the name" in {
      val src =
        """var xs = [10, 20, 30, 40]
          |var i = 1
          |ref e = xs[i]
          |i = 3
          |e = 99
          |print(xs[0], xs[1], xs[2], xs[3])""".stripMargin

      run(src) shouldBe "10 99 30 40\n"
    }

    // A ref reads as well as writes, and reading it is reading the storage — so a write through the
    // original path is visible through the name.
    "a write through the original place is visible through the ref" in {
      val src =
        """var xs = [1, 2, 3]
          |ref e = xs[1]
          |xs[1] = 42
          |print(e)""".stripMargin

      run(src) shouldBe "42\n"
    }

    "a ref of a ref is one more name for the same place" in {
      val src =
        """var xs = [1, 2, 3]
          |ref a = xs[2]
          |ref b = a
          |b = 8
          |print(xs[2])""".stripMargin

      run(src) shouldBe "8\n"
    }

    "a ref names a field, a local, and a dereference as readily as an element" in {
      val src =
        """struct P
          |    x: int
          |    y: int
          |end P
          |var p = P(1, 2)
          |ref fx = p.x
          |fx = 5
          |var n = 3
          |ref rn = n
          |rn += 4
          |var q = P(0, 0)
          |var pq: *P = &q
          |ref dq = *pq
          |dq.y = 6
          |print(p.x, n, q.y)""".stripMargin

      run(src) shouldBe "5 7 6\n"
    }

    // `03 § ref` says a ref is not a fourth mode and introduces no representation. The observable
    // form of that: the name has an address and no storage of its own, so the whole feature is one
    // zero-offset `getelementptr` and no `alloca`.
    "a ref allocates no slot of its own" in {
      val out = ir("var xs = [1, 2, 3]\nref e = xs[1]\ne = 9\nprint(xs[1])")

      out should include("%e.addr = getelementptr i8")
      out should not include "%e.addr = alloca"
    }
  }

  "the address is taken once" - {

    // The claim that costs something to keep: a subscript owes a bounds check, and a ref pays it at
    // the binding rather than at each use.
    //
    // It is measured as a **difference between two spellings of one program**, because the count is
    // over the whole module and the prelude carries checks of its own — an absolute number would be
    // pinning the library rather than the feature. Three writes through paths check three times;
    // three writes through a ref check once; so the ref saves exactly two.
    "an element ref bounds-checks once however often the name is used" in {
      val paths =
        """var xs = [1, 2, 3, 4]
          |var i = 2
          |xs[i] = 1
          |xs[i] = 2
          |xs[i] = 3
          |print(xs[2])""".stripMargin

      val named =
        """var xs = [1, 2, 3, 4]
          |var i = 2
          |ref e = xs[i]
          |e = 1
          |e = 2
          |e = 3
          |print(xs[2])""".stripMargin

      checks(ir(paths)) - checks(ir(named)) shouldBe 2
    }

    // The disagreement half: adding uses of the *name* adds no checks at all, where adding uses of
    // the path adds one each. Without this the test above would also pass on an implementation that
    // merely happened to emit two fewer checks for some other reason.
    "and adding more uses of the name adds none" in {
      def named(writes: Int): String =
        (List("var xs = [1, 2, 3, 4]", "var i = 2", "ref e = xs[i]") :::
          List.fill(writes)("e = 1") ::: List("print(xs[2])")).mkString("\n")

      checks(ir(named(3))) shouldBe checks(ir(named(8)))
    }
  }

  "the place is remembered" - {

    // This is the test the feature exists for. `16 §6` discharges a clause by walking outward
    // through the place being written; a `*Span` severs that walk and is refused where it is made.
    // A ref keeps it, so the clause fires — and the program traps rather than storing a bad value.
    "a write through a ref re-checks the invariant of the struct it lies inside" in {
      val src =
        """struct Span
          |    lo: int
          |    hi: int
          |    invariant lo <= hi
          |end Span
          |var s = Span(1, 5)
          |ref l = s.lo
          |l = 9
          |print(s.lo)""".stripMargin

      exits(src)
    }

    "and lets a write that keeps the clause through" in {
      val src =
        """struct Span
          |    lo: int
          |    hi: int
          |    invariant lo <= hi
          |end Span
          |var s = Span(1, 5)
          |ref l = s.lo
          |l = 3
          |print(s.lo, s.hi)""".stripMargin

      run(src) shouldBe "3 5\n"
    }

    // The invariant may read *through* a field, and the walk goes outward through the whole place —
    // so a ref to the inner struct's field still reaches the outer clause two steps up.
    "the walk goes outward through the whole place, not just one step" in {
      val src =
        """struct Inner
          |    n: int
          |end Inner
          |struct Outer
          |    a: Inner
          |    b: int
          |    invariant a.n <= b
          |end Outer
          |var o = Outer(Inner(1), 5)
          |ref n = o.a.n
          |n = 9
          |print(o.a.n)""".stripMargin

      exits(src)
    }

    // The counterpart, and the reason the line above is worth anything: the same alias written as a
    // pointer is refused where it is made, because a `*Inner` names no `Outer` (`16 §6`).
    "where the same alias as a pointer is refused outright" in {
      val src =
        """struct Inner
          |    n: int
          |end Inner
          |struct Outer
          |    a: Inner
          |    b: int
          |    invariant a.n <= b
          |end Outer
          |var o = Outer(Inner(1), 5)
          |var p: *Inner = &o.a
          |print(p.n)""".stripMargin

      err(src) should include("whose invariant reads")
    }

    // A ref into read-only storage is read-only: `03 § ref` says the property survives being given
    // a shorter name, which is the same rule that makes an element of a `val` array read-only.
    "a ref into a 'val' may not be written through" in {
      err(
        """val xs: [3]int = [1, 2, 3]
          |ref e = xs[1]
          |e = 9""".stripMargin
      ) should include("written once")
    }

    // A `within` bound is the slot's, so it is still checked when the write goes through a name.
    "a ref to a constrained slot is still checked on assignment" in {
      val src =
        """type Small = new int within 0..<10
          |var xs: [3]Small = [Small(1); 3]
          |ref e = xs[0]
          |e = Small(50)
          |print(int(xs[0]))""".stripMargin

      exits(src)
    }
  }

  "what may be written" - {

    "a call result is refused, since it has no address" in {
      err("f() -> int = 3\nref x = f()\nprint(x)") should include("'ref' names a place")
    }

    "an arithmetic result is refused" in {
      err("var a = 1\nref x = a + 1\nprint(x)") should include("'ref' names a place")
    }

    "a literal is refused" in {
      err("ref x = 5\nprint(x)") should include("'ref' names a place")
    }

    "a freshly built struct is refused" in {
      err(
        """struct P
          |    x: int
          |end P
          |ref p = P(1)
          |print(p.x)""".stripMargin
      ) should include("'ref' names a place")
    }

    // The first thing a reader will try, and it is refused for a reason that has nothing to do with
    // refs: a container's subscript is a call through `Index` (`07`, `14 §7`), so it produces a value
    // and there is no address anywhere for a name to mean. Pinned because the refusal is the
    // language being consistent rather than the feature being incomplete.
    "a container's subscript is a call, not a place, so it is refused" in {
      val src =
        """import sysl.buf.{Buf, buf}
          |var b: &Buf[int] = buf()
          |b.push(1)
          |ref e = b[0]
          |print(e)""".stripMargin

      err(src) should include("'ref' names a place")
    }

    "and the message names what a place is" in {
      val msg = err("f() -> int = 3\nref x = f()\nprint(x)")

      msg should include("a local, a field, an element, or a dereference")
      msg should include("'val'")
    }
  }

  "what may move underneath it" - {

    // The rule the chapter says a language with no collector cannot avoid. Overwriting the view
    // releases the buffer the ref is standing in.
    "a view step may not be reassigned while a ref stands on it" in {
      val src =
        """struct Table
          |    cell: []int
          |end Table
          |var t = Table([0; 4])
          |ref e = t.cell[1]
          |t.cell = [0; 8]
          |e = 5""".stripMargin

      err(src) should include("standing on storage this assignment would release")
    }

    "a local view may not be reassigned either" in {
      val src =
        """var v: []int = [0; 4]
          |ref e = v[1]
          |v = [0; 8]
          |e = 5""".stripMargin

      err(src) should include("standing on storage this assignment would release")
    }

    "nor a reference step" in {
      val src =
        """struct Node
          |    n: int
          |end Node
          |struct Holder
          |    node: &Node
          |end Holder
          |var h = Holder(Node(1))
          |ref x = h.node.n
          |h.node = Node(2)
          |x = 5""".stripMargin

      err(src) should include("standing on storage this assignment would release")
    }

    // The prefix half: releasing the thing the owning step is read *out of* frees it just as surely,
    // so refusing only the assignment to the step itself would leave the shorter route open.
    "nor a prefix of an owning step" in {
      val src =
        """struct Inner
          |    cell: []int
          |end Inner
          |struct Outer
          |    a: &Inner
          |end Outer
          |var o = Outer(Inner([0; 4]))
          |ref e = o.a.cell[1]
          |o.a = Inner([0; 8])
          |e = 5""".stripMargin

      err(src) should include("standing on storage this assignment would release")
    }

    // The mutating-call half, and it is the conservative one on purpose: nothing here reads whether
    // `grow` reassigns anything, because a module compiles against signatures and not bodies.
    "a '*self' method may not be called on a prefix while a ref stands on a view" in {
      val src =
        """struct Table
          |    cell: []int
          |
          |    grow(*self)
          |        self.cell = [0; 8]
          |end Table
          |var t = Table([0; 4])
          |ref e = t.cell[1]
          |t.grow()
          |e = 5""".stripMargin

      err(src) should include("standing on storage this call could release")
    }

    // The disagreement half of the whole rule, and the case it was designed around: a chain with no
    // owning step has nothing to hold still, so a program reaching fixed tables through a `*Self`
    // receiver is asked for nothing at all.
    "a chain with no owning step refuses nothing" in {
      val src =
        """struct Task
          |    state: int
          |end Task
          |struct Kernel
          |    tasks: [4]Task
          |    n: int
          |
          |    bump(*self)
          |        self.n += 1
          |end Kernel
          |var k = Kernel([Task(0); 4], 0)
          |ref t = k.tasks[1]
          |k.bump()
          |k.n = 5
          |t.state = 3
          |print(k.tasks[1].state, k.n)""".stripMargin

      run(src) shouldBe "3 5\n"
    }

    // `guide/kernel`'s exact shape, and the one the chapter says the feature was built for: a
    // struct-typed element of a fixed array field, reached through a `*self` receiver. Each of its
    // parts is covered above; this is the combination, which is where a lowering bug would sit.
    "a struct element of a fixed table through '*self' is the motivating case" in {
      val src =
        """struct Task
          |    state: int
          |    prio: int
          |end Task
          |struct Kernel
          |    tasks: [4]Task
          |
          |    advance(*self, i: usize)
          |        ref t = self.tasks[i]
          |        t.state = 2
          |        t.prio = t.prio + 1
          |end Kernel
          |var k = Kernel([Task(0, 7); 4])
          |k.advance(2usize)
          |print(k.tasks[2].state, k.tasks[2].prio, k.tasks[0].state)""".stripMargin

      run(src) shouldBe "2 8 0\n"
    }

    // A `*T` step is excluded deliberately: a raw pointer owns nothing, so overwriting it frees
    // nothing. This is what keeps every `self.…()` call in an allocation-free program legal.
    "a pointer step is not an owning step" in {
      val src =
        """struct Kernel
          |    n: int
          |    tasks: [4]int
          |
          |    bump(*self)
          |        self.n += 1
          |end Kernel
          |work(k: *Kernel)
          |    ref t = k.tasks[1]
          |    k.bump()
          |    t = 4
          |var kk = Kernel(0, [0; 4])
          |work(&kk)
          |print(kk.tasks[1], kk.n)""".stripMargin

      run(src) shouldBe "4 1\n"
    }

    // The restriction lasts exactly as long as the name does.
    "the restriction lifts when the block declaring the ref closes" in {
      val src =
        """var v: []int = [0; 4]
          |if true
          |    ref e = v[1]
          |    e = 7
          |print(v[1])
          |v = [0; 8]
          |print(v[1])""".stripMargin

      run(src) shouldBe "7\n0\n"
    }

    // Reading is not releasing, so a `self` method that only reads is still refused (the rule is
    // about the receiver mode, not the body) while an ordinary read of the view is fine.
    "reading the view a ref stands on is left alone" in {
      val src =
        """var v: []int = [1, 2, 3, 4]
          |ref e = v[1]
          |e = 9
          |print(v.len, v[1], v[0])""".stripMargin

      run(src) shouldBe "4 9 1\n"
    }
  }

  "a ref to a slot holding a reference" - {

    // The chapter's rule: binding names the slot and takes no count. If it retained, the count it
    // took would keep the old object alive past the write that replaced it — so the observable is
    // the destructor running at the assignment rather than at the end of the block.
    "binding takes no count, and assigning through it replaces what the slot held" in {
      val src =
        """struct Node
          |    n: int
          |end Node
          |struct Holder
          |    node: &Node
          |end Holder
          |var h = Holder(Node(1))
          |ref r = h.node
          |print(r.n)
          |r = Node(2)
          |print(h.node.n, r.n)""".stripMargin

      run(src) shouldBe "1\n2 2\n"
    }

    // The count is real: the object stays alive while the struct holds it, and a second name for the
    // slot does not change how many holders there are.
    "the object a ref-named slot holds stays alive through the ref" in {
      val src =
        """struct Node
          |    n: int
          |end Node
          |struct Holder
          |    node: &Node
          |end Holder
          |var h = Holder(Node(5))
          |ref r = h.node
          |var keep = r
          |h.node = Node(6)
          |print(keep.n, h.node.n, r.n)""".stripMargin

      run(src) shouldBe "5 6 6\n"
    }
  }

  "in the shapes a program actually writes" - {

    "a ref inside a loop rebinds each iteration" in {
      val src =
        """struct Task
          |    state: int
          |end Task
          |var ts = [Task(0); 4]
          |for i in 0usize..<4
          |    ref t = ts[i]
          |    t.state = int(i) * 2
          |print(ts[0].state, ts[1].state, ts[2].state, ts[3].state)""".stripMargin

      run(src) shouldBe "0 2 4 6\n"
    }

    "a ref survives being shadowed by an inner block" in {
      val src =
        """var xs = [1, 2, 3]
          |ref e = xs[0]
          |if true
          |    ref e = xs[2]
          |    e = 30
          |e = 10
          |print(xs[0], xs[2])""".stripMargin

      run(src) shouldBe "10 30\n"
    }

    // A capture is the one construct that carries a name out of the block that bound it, which is
    // exactly what a ref may not do — so it is refused, and refused for the reason rather than as a
    // limitation. Found by writing the test that assumed the opposite: the closure read the
    // environment slot before the binding had filled it, and printed garbage.
    "a closure may not capture a ref" in {
      val src =
        """var xs = [1, 2, 3]
          |ref e = xs[1]
          |var f: &Fn() -> int = () -> e
          |print(f())""".stripMargin

      err(src) should include("cannot be captured")
    }

    "nor may a nested function" in {
      val src =
        """f() -> int
          |    var xs = [1, 2, 3]
          |    ref e = xs[1]
          |    read() -> int = e
          |    read()
          |print(f())""".stripMargin

      err(src) should include("cannot be captured")
    }

    // …and the place itself is capturable as it always was, which is what the refusal points at. The
    // borrowing form is written deliberately: an escaping closure copies its captures (`12 §8`), so
    // an `&Fn` here would read the array as it was and prove nothing about reaching the storage.
    "where capturing the place it names is ordinary" in {
      val src =
        """var xs = [1, 2, 3]
          |now(f: () -> int) -> int = f()
          |xs[1] = 42
          |print(now(() -> xs[1]))""".stripMargin

      run(src) shouldBe "42\n"
    }
  }

  /** The chapters next door, probed because a ref is a place under a new name and every rule about
    * places has to keep meaning what it did.
    */
  "the neighbouring chapters" - {

    // `03 § Device memory` puts the qualifier on the **storage**, and a ref names storage — so an
    // access through the name has to be the access the path would have been.
    //
    // This was a real defect, found by asking the question rather than by a failing program: a ref
    // has no declaration to read a qualifier off, and the emitter read it off the node. A register
    // written through a ref got an unmarked store, free to be reordered or dropped, where the direct
    // path got `store volatile`.
    "a ref to a register keeps the volatile qualifier on the store" in {
      val out = defineOf(ir(uart + "poke()\n    ref s = regs.status\n    s = 7u32\npoke()"), "poke")

      out should include("store volatile i32 7")
    }

    "and on the load" in {
      val out = defineOf(ir(uart + "peek() -> u32\n    ref s = regs.status\n    s\nprint(peek())"), "peek")

      out should include("load volatile i32")
    }

    // The disagreement half: a ref to the block's *shadow* field is ordinary memory and must not
    // acquire a qualifier it never had.
    "where a ref to an ordinary field of the same block does not" in {
      val out = defineOf(ir(uart + "poke()\n    ref b = regs.baud\n    b = 7u32\npoke()"), "poke")

      out should include("store i32 7")
      out should not include "store volatile"
    }

    // `05` promotes an array whose view escapes the frame, and the pass finds *which* array by
    // walking to the view's root. A ref is a name for storage, so the walk has to carry on through
    // it — otherwise the array would be unnameable and the escape reported instead of promoted.
    "an escape through a ref promotes the array the ref names" in {
      val src =
        """keep(v: []int) -> []int = v
          |f() -> []int
          |    var xs = [1, 2, 3]
          |    ref r = xs
          |    keep(r[..])
          |print(f()[1])""".stripMargin

      run(src) shouldBe "2\n"
    }

    // `16 §6`'s `SelfAlias`: a `*self` method may not let a pointer into its receiver outlive the
    // call. A ref is a second name for the receiver's own storage, so taking its address is the same
    // leak written one line longer — and has to be caught as one.
    "a '*self' method may not return an address taken through a ref into its receiver" in {
      val src =
        """|struct Inner
           |    n: int
           |
           |    leak(*self) -> *int
           |        ref x = self.n
           |        &x
           |struct Outer
           |    a: Inner
           |    b: int
           |    invariant a.n <= b
           |""".stripMargin

      err(src + "var o = Outer(Inner(1), 5)\nvar p = o.a.leak()") should include("outlive the call")
    }

    // `13 §2`: statements at the top of a file are the entry point's, not module members — so a ref
    // written there is an ordinary local of `main` and needs no rule of its own. Probed because the
    // opposite was assumed: `ref` is local-only, and the question was whether the top of a file
    // counts as local. It does, and it does for a reason that predates this feature.
    "a ref at the top of a file is a local of the entry point" in {
      run("var xs = [1, 2, 3]\nref e = xs[1]\ne = 9\nprint(xs[1])") shouldBe "9\n"
    }

    // `09`: a ref to an enum is matched the way the place is, since what a match reads is the value.
    "a ref to an enum is matched like the place it names" in {
      val src =
        """enum Colour
          |    Red
          |    Green
          |    Blue
          |end Colour
          |var cs = [Red, Green, Blue]
          |ref c = cs[1]
          |c match
          |    Red -> print("red")
          |    Green -> print("green")
          |    Blue -> print("blue")""".stripMargin

      run(src) shouldBe "green\n"
    }

    // `03 § defer`: a deferred statement runs where the block is left, and a ref is still in scope
    // there — the binding is a declaration of a name, not of storage that could have been released.
    "a deferred statement may write through a ref" in {
      val src =
        """var xs = [1, 2, 3]
          |if true
          |    ref e = xs[0]
          |    defer e = 9
          |    print(xs[0])
          |print(xs[0])""".stripMargin

      run(src) shouldBe "1\n9\n"
    }

    // `00 §2`: a multi-assignment writes several places at once, and a ref is a place.
    "a ref may be one place of a multi-assignment" in {
      val src =
        """var xs = [1, 2, 3]
          |ref a = xs[0]
          |ref b = xs[2]
          |a, b = b, a
          |print(xs[0], xs[1], xs[2])""".stripMargin

      run(src) shouldBe "3 2 1\n"
    }
  }

  /** How many bounds-check comparisons the emitted module contains. The check `03 § Bounds safety`
    * describes lowers to one `icmp ult` against the length, so counting them is how "checked once"
    * is measured rather than asserted.
    */
  private def checks(out: String): Int = out.linesIterator.count(_.contains("icmp ult i64"))
}
