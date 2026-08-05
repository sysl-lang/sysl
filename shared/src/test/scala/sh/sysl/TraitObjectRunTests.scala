package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Tier-2 runtime behaviour of trait objects — the dynamic half of `02`.
 *
 * The thing every test here has to establish is that dispatch is genuinely *dynamic*: a static
 * resolution could produce the right answer for one type by coincidence, so each case drives two
 * types whose implementations return values that could not be confused, through one variable, one
 * parameter, or one array element.
 */
class TraitObjectRunTests extends AnyFreeSpec with RunSupport with CodegenSupport {

  /** Two shapes whose areas are far apart, so a call that reached the wrong one is obvious. */
  private val shape =
    """trait Shape
      |    area(self) -> int
      |struct Rect
      |    w: int
      |    h: int
      |struct Sq
      |    s: int
      |impl Shape for Rect
      |    area(self) -> int = self.w * self.h
      |impl Shape for Sq
      |    area(self) -> int = self.s * self.s
      |""".stripMargin

  "a raw trait object" - {
    "dispatches to the implementation of whatever it points at" in {
      val out = run(shape +
        """report(s: *Shape) -> int = s.area()
          |var r = Rect(3, 4)
          |var q = Sq(5)
          |print(report(&r), report(&q))""".stripMargin)

      out shouldBe "12 25\n"
    }

    // One variable, two types, two answers — read through the same call site both times, so the
    // call cannot have been resolved when it was compiled.
    "changes which implementation it reaches when it is reassigned" in {
      val out = run(shape +
        """var r = Rect(3, 4)
          |var q = Sq(5)
          |var s: *Shape = &r
          |print(s.area())
          |s = &q
          |print(s.area())""".stripMargin)

      out shouldBe "12\n25\n"
    }

    "holds types of different sizes in one array" in {
      val out = run(shape +
        """var r = Rect(3, 4)
          |var q = Sq(5)
          |var all: [2]*Shape = [&r, &q]
          |var total = 0
          |for s in all
          |    total += s.area()
          |print(total)""".stripMargin)

      out shouldBe "37\n"
    }

    "reaches a '*self' method, which mutates the value it points at" in {
      val out = run(
        """trait Bump
          |    bump(*self, by: int)
          |struct C
          |    n: int
          |impl Bump for C
          |    bump(*self, by: int)
          |        self.n += by
          |var c = C(1)
          |var b: *Bump = &c
          |b.bump(41)
          |print(c.n)""".stripMargin)

      out shouldBe "42\n"
    }

    // A built-in has an owner key its members are filed under exactly as a struct does, so an
    // `impl` for one is erasable exactly as a struct's is.
    "erases a built-in type" in {
      val out = run(
        """trait Show
          |    show(self) -> string
          |impl Show for int
          |    show(self) -> string = "an int"
          |impl Show for bool
          |    show(self) -> string = "a bool"
          |var n = 5
          |var b = true
          |var x: *Show = &n
          |var y: *Show = &b
          |print(x.show(), y.show())""".stripMargin)

      out shouldBe "an int a bool\n"
    }

    "erases an enum" in {
      val out = run(
        """trait Shape
          |    area(self) -> int
          |enum E
          |    One
          |    Two
          |impl Shape for E
          |    area(self) -> int = self match
          |        One -> 1
          |        Two -> 2
          |var e = Two
          |var s: *Shape = &e
          |print(s.area())""".stripMargin)

      out shouldBe "2\n"
    }

    // Two traits over one type make two tables, and each object reaches only its own — the two
    // methods return different values, so a shared table would show up as one of them answering
    // for both.
    "one value erased to two traits reaches the right table each time" in {
      val out = run(
        """trait A
          |    a(self) -> int
          |trait B
          |    b(self) -> int
          |struct S
          |    n: int
          |impl A for S
          |    a(self) -> int = self.n
          |impl B for S
          |    b(self) -> int = self.n * 2
          |var s = S(21)
          |var x: *A = &s
          |var y: *B = &s
          |print(x.a(), y.b())""".stripMargin)

      out shouldBe "21 42\n"
    }

    "a method takes arguments and returns a value of any type" in {
      val out = run(
        """trait Talk
          |    say(self, to: string, times: int) -> string
          |struct P
          |    who: string
          |impl Talk for P
          |    say(self, to: string, times: int) -> string =
          |        if times > 1 then self.who + " to " + to + "!" else self.who + " to " + to
          |var p = P("ada")
          |var t: *Talk = &p
          |print(t.say("bob", 1))
          |print(t.say("bob", 2))""".stripMargin)

      out shouldBe "ada to bob\nada to bob!\n"
    }

    "a method returning nothing runs for its effect" in {
      val out = run(
        """trait Sink
          |    put(self, n: int)
          |struct S
          |    tag: int
          |impl Sink for S
          |    put(self, n: int)
          |        print(self.tag + n)
          |var s = S(40)
          |var k: *Sink = &s
          |k.put(2)""".stripMargin)

      out shouldBe "42\n"
    }
  }

  "a counted trait object" - {
    // The allocation is the construction, exactly as it is for a plain `&T`: writing the value
    // where a `&Trait` is expected boxes it and erases the box in one step.
    "is made by writing the value a context expects one at" in {
      val out = run(shape +
        """var a: &Shape = Rect(3, 4)
          |var b: &Shape = Sq(5)
          |print(a.area(), b.area())""".stripMargin)

      out shouldBe "12 25\n"
    }

    "is also made from a reference already in hand" in {
      val out = run(shape +
        """var r: &Rect = Rect(6, 7)
          |var s: &Shape = r
          |print(s.area())""".stripMargin)

      out shouldBe "42\n"
    }

    // `&self` needs its receiver inside the box, which is exactly what a counted object carries —
    // so the mutation is to the shared object and survives the call.
    "reaches a '&self' method, which mutates the shared object" in {
      val out = run(
        """trait Ticker
          |    tick(&self)
          |    value(self) -> int
          |struct Clock
          |    t: int
          |impl Ticker for Clock
          |    tick(&self)
          |        self.t += 1
          |    value(self) -> int = self.t
          |var c: &Ticker = Clock(0)
          |c.tick()
          |c.tick()
          |c.tick()
          |print(c.value())""".stripMargin)

      out shouldBe "3\n"
    }

    "meets two branches of different concrete types at one type" in {
      val out = run(shape +
        """make(big: bool) -> &Shape = if big then Sq(5) else Rect(3, 4)
          |var pick = 1
          |var m: &Shape = pick match
          |    1 -> Sq(6)
          |    _ -> Rect(1, 1)
          |print(make(true).area(), make(false).area(), m.area())""".stripMargin)

      out shouldBe "25 12 36\n"
    }

    "is carried through a struct field and an enum payload" in {
      val out = run(shape +
        """struct Holder
          |    it: &Shape
          |var h = Holder(Rect(6, 7))
          |var o: Option[&Shape] = Some(Sq(5))
          |var second = o match
          |    Some(s) -> s.area()
          |    None -> 0
          |print(h.it.area(), second)""".stripMargin)

      out shouldBe "42 25\n"
    }
  }

  /** Every counted object holds one count of the box it erased, so the ordinary discipline has to
   * carry over unchanged: a leak grows the heap without bound and a double free crashes, and a long
   * loop is what makes either of those show up as something other than a passing test.
   */
  "ownership" - {
    "a counted object made and dropped in a loop neither leaks nor frees twice" in {
      val out = run(shape +
        """var i = 0
          |var total = 0
          |while i < 300000
          |    var s: &Shape = Sq(5)
          |    total += s.area()
          |    i++
          |print(total)""".stripMargin)

      out shouldBe "7500000\n"
    }

    // The object's payload holds a reference of its own, so releasing the object has to release
    // what the payload held — which is the box's destructor doing its ordinary work through a
    // pointer whose static type has been forgotten.
    "a counted object over a reference-carrying payload is torn down completely" in {
      val out = run(
        """trait Held
          |    v(self) -> int
          |struct Inner
          |    n: int
          |struct Tag
          |    label: string
          |    inner: &Inner
          |impl Held for Tag
          |    v(self) -> int = self.inner.n
          |var i = 0
          |var total = 0
          |while i < 200000
          |    var h: &Held = Tag("tag", Inner(3))
          |    total += h.v()
          |    i++
          |print(total)""".stripMargin)

      out shouldBe "600000\n"
    }

    "a method returning a counted object hands over a count of its own" in {
      val out = run(shape +
        """trait Maker
          |    make(self) -> &Shape
          |struct M
          |    n: int
          |impl Maker for M
          |    make(self) -> &Shape = Sq(self.n)
          |var m = M(5)
          |var k: *Maker = &m
          |var i = 0
          |var total = 0
          |while i < 200000
          |    total += k.make().area()
          |    i++
          |print(total)""".stripMargin)

      out shouldBe "5000000\n"
    }

    // A by-value receiver is loaded out of the object and handed to the implementation, which
    // retains it on entry and releases it on return. Getting that wrong in either direction shows
    // up here: the string field would be freed out from under the object, or never freed at all.
    "a by-value receiver carrying a string leaves the object's own count alone" in {
      val out = run(
        """trait Label
          |    label(self) -> string
          |struct Tag
          |    text: string
          |impl Label for Tag
          |    label(self) -> string = self.text
          |var i = 0
          |var total: usize = 0
          |while i < 200000
          |    var t: &Label = Tag("abcd")
          |    total += t.label().len
          |    total += t.label().len
          |    i++
          |print(total)""".stripMargin)

      out shouldBe "1600000\n"
    }
  }

  /** A type that implements a trait and holds objects of it — the shape `guide/shapes` is built
   * out of, and the one that makes dispatch recursive without any recursion being written.
   */
  "a type that implements a trait and holds one" - {
    // `Group.area` calls `area` through the table, and one of the parts is another `Group`. Nothing
    // in the implementation knows that, which is the point: the recursion is in the values.
    "reaches itself through the table when it holds itself" in {
      val out = run("import sysl.buf.*\n\n" + shape +
        """struct Group
          |    parts: &Buf[&Shape]
          |impl Shape for Group
          |    area(self) -> int
          |        var t = 0
          |        for i in 0..<self.parts.len() do t += self.parts.at(i).area()
          |        t
          |var inner: &Buf[&Shape] = buf()
          |inner.push(Rect(3, 4))
          |inner.push(Sq(5))
          |var outer: &Buf[&Shape] = buf()
          |outer.push(Group(inner))
          |outer.push(Sq(10))
          |var g: &Shape = Group(outer)
          |print(g.area())""".stripMargin)

      out shouldBe "137\n"
    }

    // A wrapper holding exactly one object, which is the other half of the pattern — the answer is
    // the inner one's adjusted, and the wrapper is itself erasable so wrappers stack.
    "wraps one object, and wrappers stack" in {
      val out = run(shape +
        """struct Scaled
          |    inner: &Shape
          |    k: int
          |impl Shape for Scaled
          |    area(self) -> int = self.inner.area() * self.k * self.k
          |var once: &Shape = Scaled(Rect(3, 4), 2)
          |var twice: &Shape = Scaled(Scaled(Rect(3, 4), 2), 3)
          |print(once.area(), twice.area())""".stripMargin)

      out shouldBe "48 432\n"
    }

    // A wrapper chain built and dropped repeatedly: every link holds one count of the next, so a
    // leak or a double free anywhere down the chain shows up here rather than in a passing test.
    "releases the whole chain when the outermost is let go" in {
      val out = run(shape +
        """struct Scaled
          |    inner: &Shape
          |    k: int
          |impl Shape for Scaled
          |    area(self) -> int = self.inner.area() * self.k * self.k
          |var total = 0
          |var i = 0
          |while i < 100000
          |    var s: &Shape = Scaled(Scaled(Scaled(Sq(2), 2), 2), 2)
          |    total += s.area()
          |    i++
          |print(total)""".stripMargin)

      out shouldBe "25600000\n"
    }
  }

  /** An **operator trait** erased into an object, which the catalog could not be while an operator's
    * result was fixed to `Self` (`14 §7`). Writing both arguments out leaves `mul(self, rhs: real) ->
    * real` with no `Self` in it anywhere, so there is nothing about the signature a forgotten type
    * would have been needed for — and two types with quite different multiplications then dispatch
    * dynamically through one slot.
    *
    * The operator **token** does not reach through an object: `f * x` on a `&Mul[real, real]` is
    * refused, because the catalog's dispatch is on a pair of *types* and an object has forgotten the
    * one on the left. The method is what an object offers, so the call is written out.
    */
  /** Two multiplications far enough apart that a call reaching the wrong one is obvious. */
  private val muls =
    """struct Scale
      |    k: real
      |struct Shift
      |    d: real
      |impl Mul[real, real] for Scale
      |    mul(self, x: real) -> real = self.k * x
      |impl Mul[real, real] for Shift
      |    mul(self, x: real) -> real = x + self.d
      |""".stripMargin

  "an operator trait at written arguments" - {
    "is erasable, and the table picks the implementation" in {
      run(muls + """apply(f: &Mul[real, real], x: real) -> real = f.mul(x)
                   |var a: &Mul[real, real] = Scale(3.0)
                   |var b: &Mul[real, real] = Shift(10.0)
                   |print(apply(a, 2.0))
                   |print(apply(b, 2.0))""".stripMargin) shouldBe "6\n12\n"
    }

    "and a row of them dispatches one slot per element" in {
      run(muls + """var fs: [2]&Mul[real, real] = [Scale(2.0), Shift(1.0)]
                   |var total = 0.0
                   |for f in fs
                   |    total += f.mul(4.0)
                   |print(total)""".stripMargin) shouldBe "13\n"
    }

    "the operator token still needs both types, so it is refused on an object" in {
      err(muls + """apply(f: &Mul[real, real], x: real) -> real = f * x""") should
        include("'*' needs matching types")
    }
  }

  /** `02`, *Forming and using one*, lists the six positions the coercion applies at: an argument, a
    * declared variable, an assignment, a returned value, an array element, a struct field. Taken
    * together in one program so that the list is pinned as a list — a position dropped from the
    * coercion would otherwise be found by whichever unrelated test happened to use it.
    */
  "erasure applies at every position the chapter names" in {
    run("""trait Shape
          |    area(self) -> int
          |
          |struct Rect
          |    w: int
          |    h: int
          |
          |impl Shape for Rect
          |    area(self) -> int = self.w * self.h
          |
          |struct Holder
          |    s: &Shape
          |
          |take(s: &Shape) -> int = s.area()
          |give() -> &Shape = Rect(2, 3)
          |
          |var decl: &Shape = Rect(1, 2)
          |var assigned: &Shape = Rect(1, 1)
          |assigned = Rect(4, 5)
          |var arr: [2]&Shape = [Rect(1, 3), Rect(2, 2)]
          |var h = Holder(Rect(6, 6))
          |
          |print(take(Rect(7, 1)), give().area(), decl.area(), assigned.area(), arr[0].area(), h.s.area())
          |""".stripMargin) shouldBe "7 6 2 20 3 36\n"
  }

  /** `02`, *Object safety*: a table holds function pointers, so a type can be erased to a trait only
    * where the trait's members are functions that exist — which is what a source `impl` supplies and
    * a compiler-provided membership does not.
    *
    * **Every built-in now clears that bar for `Display`, and the open families were the last to.**
    * `bool`, `char`, `string` and the two floats are finite, so `lib/sysl/display.sysl` writes them
    * ordinary blocks. The `iN`/`uN` families admit `i5` and `u24`, so no finite list of blocks covers
    * them — what covers them is the single blanket block over `Integer`, whose buffer is measured
    * from the type it is instantiated at. An integer's `display` is therefore an ordinary lowered
    * function, and a slot can point at it.
    *
    * This block used to assert the refusal. It asserts the erasure now, and that is the change:
    * a heterogeneous `[]*Display` was the shape every use of this wanted and could not have.
    */
  "an integer's Display is written out too, so it erases like anything else" - {
    "as a counted object and as a raw one" in {
      run("""var n = 5
            |var d: &Display = 5
            |var p: *Display = &n
            |print(d)
            |print(p)
            |""".stripMargin) shouldBe "5\n5\n"
    }

    "in an array of them" in {
      run("""var xs: [3]&Display = [1, 2, 3]
            |for x in xs do print(x)
            |""".stripMargin) shouldBe "1\n2\n3\n"
    }

    // The shape the whole change is for: one array, four types, two of them integers of different
    // widths — which no list of written blocks could have covered and no rule could have erased.
    "and in a heterogeneous one, which is what an open family made impossible" in {
      run("""var n = 42
            |var w = 7u8
            |var big = 340282366920938463463374607431768211455u128
            |var s = "hi"
            |var f = 2.5
            |var five: [5]*Display = [&n, &w, &big, &s, &f]
            |for d in five do print(d)
            |""".stripMargin) shouldBe "42\n7\n340282366920938463463374607431768211455\nhi\n2.5\n"
    }

    // A width no C conversion reaches, erased and dispatched — the range that used to need a
    // `string` to render at all, so it is the one where "the slot points at a real function" is
    // saying the most.
    "at a width the machine has no instruction for" in {
      run("""var v = u256(1) << u256(200)
            |var d: *Display = &v
            |print(d)
            |""".stripMargin) shouldBe "1606938044258990275541962092341162602522202993782792835301376\n"
    }

    "though a bound over that same trait is met, and print finds the same rendering" in {
      run("""show[T: Display](x: T)
            |    print(x)
            |
            |show(5)
            |show("hi")
            |""".stripMargin) shouldBe "5\nhi\n"
    }

    // The other side of the split, and the reason it is worth having: a closed family's membership
    // is written out, so a slot in the table is a function that exists and the value erases.
    "while a closed family's, being written out, erases and dispatches" in {
      run("""var spec = FormatSpec(0, -1, false)
            |var text = "hi"
            |var flag = true
            |var ch = 'x'
            |var num = 3.5
            |var s: *Display = &text
            |var b: *Display = &flag
            |var c: *Display = &ch
            |var r: *Display = &num
            |for d in [s, b, c, r] do d.display(stdout(), spec)
            |print("")
            |""".stripMargin) shouldBe "hitruex3.5\n"
    }

    // The contrast that shows the rule is about *how* a type joined the trait and not about what it
    // is: a tuple is a built-in shape too, and it erases, because the library writes it an `impl`.
    "while a tuple, whose membership the library writes out, erases and dispatches" in {
      run("""var d: &Display = (1, 2)
            |print(d)
            |""".stripMargin) shouldBe "(1, 2)\n"
    }
  }
}
