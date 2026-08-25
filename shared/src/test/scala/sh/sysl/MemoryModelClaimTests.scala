package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** What `03-memory-model.md` claims, run rather than read.
 *
 * The chapter's mechanisms already have suites of their own — `ArcRunTests` for reference
 * semantics, `PointerRunTests` for the raw tier, `RecursiveTeardownRunTests` for the iterative
 * teardown, `WeakReferenceTests` for `weak`, `SharedObjectTests` for `&sync`. What none of them
 * covers is the chapter's *connective* prose: the sentences that enumerate something, or that say
 * where a rule stops. Those are the sentences that go stale without a test failing, and two of them
 * had.
 *
 * The first was the definition of a **place**, which named three kinds and left out the element —
 * while the same chapter's pointer-difference example writes `&buf[0]`, and the diagnostic for a
 * non-place recited the same short list back at the reader. The second was the **one level** limit
 * on automatic dereference: the rule holds, but a receiver with more indirection than that fell
 * through to "cannot read field 'x' of *Point", naming what was left after the one dereference
 * rather than what the reader wrote, and reporting a missing field instead of a shorthand that
 * stops short.
 */
class MemoryModelClaimTests extends AnyFreeSpec with RunSupport with CodegenSupport {

  private val point =
    """struct Point
      |    x: int
      |    y: int
      |""".stripMargin

  "a construction goes on the heap wherever a '&T' is what was asked for, and the positions are" - {
    "a declared local, with no expectation making it an ordinary value" in {
      run(s"${point}var p: &Point = Point(1, 2)\nvar q = Point(3, 4)\nprint(p.x, q.x)") shouldBe "1 3\n"
    }

    "a parameter, a return type, a struct field, and an enum variant's payload" in {
      val src =
        s"""$point
           |struct Holder
           |    p: &Point
           |enum Maybe
           |    Has(p: &Point)
           |    Nothing
           |take(p: &Point) -> int = p.x
           |make() -> &Point = Point(3, 4)
           |var h = Holder(Point(5, 6))
           |var m = Maybe.Has(Point(7, 8))
           |print(take(Point(1, 2)))
           |print(make().x)
           |print(h.p.x)
           |m match
           |    Has(q) -> print(q.y)
           |    Nothing -> print(0)
           |""".stripMargin

      run(src) shouldBe "1\n3\n5\n8\n"
    }

    "whatever produced it, and something already a '&T' passes through, leaving two names on one object" in {
      val src =
        s"""$point
           |id(p: Point) -> Point = p
           |var a: &Point = id(Point(1, 2))
           |var already: &Point = Point(9, 9)
           |var same: &Point = already
           |same.x = 4
           |print(a.x, already.x)
           |""".stripMargin

      run(src) shouldBe "1 4\n"
    }

    "a branch at a time, so a value branch and an already-'&T' branch meet at '&T'" in {
      val src =
        s"""$point
           |struct Holder
           |    origin: &Point
           |var q = Holder(Point(9, 9))
           |var c = true
           |var p: &Point = if c then Point(1, 2) else q.origin
           |var d = false
           |var r: &Point = if d then Point(1, 2) else q.origin
           |print(p.x, r.x)
           |""".stripMargin

      run(src) shouldBe "1 9\n"
    }

    "including through a loop's breaks, which is how a loop yields a value at all" in {
      val src =
        s"""$point
           |var p: &Point = for i in 0..<3 do
           |    if i == 1 then break Point(i, 0)
           |else
           |    Point(7, 7)
           |print(p.x)
           |""".stripMargin

      run(src) shouldBe "1\n"
    }

    "and a scalar is referenced without 'int' needing a constructor of its own" in {
      run("var n: &int = 0\nn = 5\nvar m: &int = 7\nprint(*n, *m + *n)") shouldBe "5 12\n"
    }

    // The chapter names five positions and then generalizes past them — "the rule is about the
    // types, not about the syntax: a T written where a &T is expected goes on the heap, whatever
    // produced it". These are the positions the list does not name, kept together because each is
    // reached by a different part of the analyzer: an element type, a tuple part, and a place being
    // assigned into rather than a binding being introduced.
    "an array element and a tuple part, neither of which the chapter's list names" in {
      val src =
        s"""$point
           |var arr: [2]&Point = [Point(1, 2), Point(3, 4)]
           |var tu: (&Point, int) = (Point(5, 6), 7)
           |print(arr[1].x, tu.0.y, tu.1)
           |""".stripMargin

      run(src) shouldBe "3 6 7\n"
    }

    "a place being assigned into, for an element, a field, and one arm of a multi-assignment" in {
      val src =
        s"""$point
           |struct Holder
           |    p: &Point
           |var arr: [2]&Point = [Point(1, 1), Point(2, 2)]
           |arr[0] = Point(8, 0)
           |var h = Holder(Point(1, 1))
           |h.p = Point(9, 0)
           |var a: &Point = Point(1, 1)
           |var b: &Point = Point(2, 2)
           |a, b = Point(3, 0), Point(4, 0)
           |print(arr[0].x, h.p.x, a.x, b.x)
           |""".stripMargin

      run(src) shouldBe "8 9 3 4\n"
    }

    // A default is written once at the declaration and produced at each call that omits the
    // argument, so the expectation has to reach it there rather than at the call — and a named
    // argument reaches the parameter it names rather than the one in its position. Both spellings
    // arrived after the positions above were pinned, and nothing had asked whether a construction
    // standing in either of them is boxed.
    "a default parameter value, produced per call that omits it" in {
      val src =
        s"""$point
           |from(p: &Point = Point(11, 12)) -> int = p.x + p.y
           |print(from(), from(Point(1, 2)), from())
           |""".stripMargin

      run(src) shouldBe "23 3 23\n"
    }

    "a named argument, which reaches the parameter it names rather than its position" in {
      val src =
        s"""$point
           |pair(n: int, p: &Point) -> int = n + p.x
           |print(pair(p = Point(5, 0), n = 1))
           |""".stripMargin

      run(src) shouldBe "6\n"
    }
  }

  "a value copies, and a reference inside one is shared by the copy rather than duplicated" in {
    val src =
      s"""$point
         |struct Holder
         |    p: &Point
         |    n: int
         |var h = Holder(Point(1, 2), 5)
         |var copy = h
         |copy.n = 9
         |copy.p.x = 42
         |print(h.n, copy.n)
         |print(h.p.x, copy.p.x)
         |""".stripMargin

    run(src) shouldBe "5 9\n42 42\n"
  }

  "a place is a local, a dereference, an element, or a field of any of them" - {
    "an element of a fixed array has an address, which is what the chapter's own example takes" in {
      val src =
        """var buf = [10, 20, 30]
          |var hit = &buf[1]
          |var at = usize(hit - &buf[0])
          |print(at)
          |*hit = 99
          |print(buf[1])
          |""".stripMargin

      run(src) shouldBe "1\n99\n"
    }

    "so does an element of a slice, and of a view taken out of one" in {
      run("var buf: []int = [10, 20, 30]\nvar p = &buf[2]\nprint(*p)") shouldBe "30\n"
      run("var buf = [10, 20, 30]\nvar s = buf[0..<2]\nvar p = &s[1]\nprint(*p)") shouldBe "20\n"
    }

    """a slice's element is a place even when the slice is a temporary, because the element is in
      |the buffer rather than in the view — which is what makes a write through a call land""".stripMargin in {
      val src =
        """struct Grid
          |    cells: []int
          |row(g: &Grid) -> []int = g.cells
          |var g: &Grid = Grid([1, 2, 3])
          |row(g)[1] = 99
          |print(g.cells[1])
          |var p = &row(g)[2]
          |*p = 42
          |print(g.cells[2])
          |""".stripMargin

      run(src) shouldBe "99\n42\n"
    }

    // Nothing computed is a place — and `&` in front of one no longer asks it to be. It writes the
    // value into a hidden local of the scope it stands in and hands back that slot's address, so
    // the three forms below produce a pointer to storage the frame owns rather than a refusal.
    // `AmpConstructionTests` is where that rule is pinned; here it is the claim about *places* that
    // is being kept honest, which is that these are not any.
    "and anything computed is not one — but '&' gives it a slot rather than refusing" in {
      run("f() -> int = 3\nvar p = &f()\nprint(*p)") shouldBe "3\n"
      run("var a = 1\nvar b = 2\nvar p = &(a + b)\nprint(*p)") shouldBe "3\n"
      run(s"${point}var p = &Point(1, 2)\nprint(p.x)") shouldBe "1\n"
    }

    // The enumeration is now the *assignment* target's, which is where a place is still the only
    // thing that will do — and it still names the element, since leaving it out denied a form the
    // page's own example writes.
    "the complaint names the element too, since leaving it out denied a form the chapter uses" in {
      err("var a = 1\nvar b = 2\na + b = 4") should
        include("a variable, a field, an element, or a dereference")
    }

    // And a place the program may not *write* through is a different question again, so `&` goes on
    // refusing a `val` rather than quietly copying it into a slot the program could write.
    "a 'val' is refused rather than given a slot of its own" in {
      err("val v = 3\nvar p = &v") should include("written once")
    }

    "a string's own subscript is refused for immutability rather than for having no address" in {
      err("var s = \"hi\"\ns[0] = 74u8") should
        include("a string is immutable, so its bytes have no address to write through")
    }
  }

  /** `03`'s headline guarantee is that a program containing no `*T` cannot segfault, and `04` says a
   * `string` is immutable. `s.bytes` reinterprets a string's three words as a `[]u8` without copying,
   * so its elements are the string's storage — and a `[]u8` permits writes. Writing one byte of a
   * **literal** wrote into read-only memory and killed the process (exit 138) out of a program with
   * no `*T` in it; writing one byte of a **heap** string silently mutated a value the language calls
   * immutable, and can leave a `string` that is not valid UTF-8.
   *
   * The hole is closed: `s.bytes` is a `[]const u8`, so the property travels with the view instead
   * of expiring with the expression that made it — the last two tests below are the ones that could
   * not be made until a view could record it.
   *
   * `&s.bytes[i]` is deliberately still open — it is a `*T` the moment it is written, which is the
   * tier the guarantee excludes, and it is how a string reaches `printf("%.*s")`. The licence is
   * for that form as *written*: the same view under a name is an ordinary `[]const u8` and may not
   * have its address taken, because there the raw tier is no longer visible to a reader.
   */
  "a string's bytes are the string's own storage, so writing one is writing the string" - {
    "the subscript is refused, and the message says the view is not a copy" in {
      val e = err("var s = \"hi\"\ns.bytes[0] = 74u8")

      e should include("'bytes' views the string's own storage rather than a copy of it")
      e should include("copy them into a '[]u8' first")
    }

    """taking the address of one is still allowed, because that is a '*T' and the guarantee is
      |about programs that have none — it is also how a string reaches printf's '%.*s'""".stripMargin in {
      val src =
        """extern printf(fmt: *u8, ...) -> int
          |var s = "hello"
          |printf(c"[%.*s]\n", int(s.bytes.len), &s.bytes[0])
          |""".stripMargin

      run(src) shouldBe "[hello]\n"
    }

    "reading them is untouched, since reading is what the view is for" in {
      run("var s = \"hi\"\nprint(s.bytes[0], s.bytes.len)") shouldBe "104 2\n"
    }

    "a copy of the string gives bytes that may be read the same way" in {
      run("var s = \"hi\"\nvar c = s.copy()\nprint(c.bytes[1])") shouldBe "105\n"
    }

    "the view refuses a write once it has been bound to a name" in {
      err("var s = \"hello\"\nvar b = s.bytes\nb[0] = 74u8") should
        include("views elements it may not write")
    }

    // Refused at the call rather than at the write, and that is the better place: the callee is
    // written against a `[]u8` and is not wrong, so what is wrong is handing it these bytes.
    "and once it has been handed to something that takes a '[]u8'" in {
      errOf(
        "main.sysl" ->
          """poke(b: []u8)
            |    b[0] = 74u8
            |var s = "hello"
            |poke(s.bytes)
            |""".stripMargin,
      ) should include("does not become the other")
    }

    // The address stays available under a name, and deliberately: `&` is a `*T`, which is the tier
    // this guarantee excludes, and a read-only view that could not yield one could not reach the C
    // functions it exists to reach. The library's own `find_byte` is `memchr` over exactly this.
    "while its address may still be taken under a name, since that is the raw tier either way" in {
      val src =
        """extern printf(fmt: *u8, ...) -> int
          |var s = "hello"
          |var b = s.bytes
          |printf(c"[%.*s]\n", int(b.len), &b[0])
          |""".stripMargin

      run(src) shouldBe "[hello]\n"
    }
  }

  "selection dereferences one level and only one" - {
    "so a field is reached through a '*T' and through a '&T' with nothing written" in {
      run(s"${point}var v = Point(1, 2)\nvar p = &v\nvar r: &Point = Point(3, 4)\nprint(p.x, r.y)") shouldBe "1 4\n"
    }

    "and a '**T' is told that the shorthand stops short, not that the field does not exist" in {
      val e = err(s"${point}var v = Point(1, 2)\nvar p = &v\nvar pp = &p\nprint(pp.x)")

      e should include("selection reaches through one level of indirection and **Point has more")
      e should include("'(*x).x'")
      e should not include "cannot read field"
    }

    "while the written dereference gets through" in {
      run(s"${point}var v = Point(1, 2)\nvar p = &v\nvar pp = &p\nprint((*pp).x)") shouldBe "1\n"
    }

    "and a type that genuinely has no such field keeps the plain complaint" in {
      err("var n = 5\nprint(n.x)") should include("cannot read field 'x' of int")
    }
  }

  "a match does not reach through a mode, so matching a reference to an enum writes the '*'" - {
    val shape =
      """enum Shape
        |    Circle(r: int)
        |    Square(s: int)
        |""".stripMargin

    "through a '&T'" in {
      run(s"${shape}var e: &Shape = Shape.Circle(3)\n*e match\n    Circle(r) -> print(\"circle\", r)\n" +
        "    Square(s) -> print(\"square\", s)") shouldBe "circle 3\n"
      err(s"${shape}var e: &Shape = Shape.Circle(3)\ne match\n    Circle(r) -> print(r)\n" +
        "    Square(s) -> print(s)") should include("'*x match'")
    }

    "and through a '*T'" in {
      run(s"${shape}var v = Shape.Square(4)\nvar e = &v\n*e match\n    Circle(r) -> print(\"circle\", r)\n" +
        "    Square(s) -> print(\"square\", s)") shouldBe "square 4\n"
      err(s"${shape}var v = Shape.Square(4)\nvar e = &v\ne match\n    Circle(r) -> print(r)\n" +
        "    Square(s) -> print(s)") should include("'*x match'")
    }
  }

  "a cycle is legal per cycle rather than per field" - {
    "so two types reaching each other through one pointer edge have a finite size" in {
      run("struct A\n    b: B\nstruct B\n    a: *A\nvar x: A\nprint(x.b.a == null)") shouldBe "true\n"
    }

    "and one with no indirection anywhere on it is refused, naming the type" in {
      err("struct A\n    b: B\nstruct B\n    a: A\nvar x: A\nprint(1)") should
        include("type 'A' contains itself, so it has no finite size")
    }
  }

  /** *"Whether `Wrap[Node]` contains a `Node` is `Wrap`'s business, not something the argument list
    * can say."* The page says that of one type reaching itself, and the rule above says a cycle is
    * legal per *cycle* — so the two together say a mutual cycle through a type argument is finite
    * as well. It was not: the pair was refused, and swapping the two declarations made it compile.
    *
    * Every case here is a pair rather than an assertion, because only the comparison says anything.
    * `Buf` reaches its elements through a `[]T` and `Wrap` holds its parameter by value, so the two
    * differ in exactly the thing the argument list cannot tell you — and each is written in both
    * declaration orders, since the order is what used to decide.
    */
  "a type argument is not containment, and which type was declared first does not decide it" - {

    /** The generic that holds its parameter **by value**, so that what stands between the cycle and
      * an infinite type really is the argument position and nothing else.
      */
    val wrap =
      """struct Wrap[T]
        |    x: T
        |""".stripMargin

    val bufs = "import sysl.buf.{Buf, buf}\n\n"

    "a type whose children are a growable sequence of itself is finite" in {
      run(s"${bufs}struct A\n    xs: Buf[A]\nvar n: Buf[A] = buf()\nprint(A(n).xs.len())") shouldBe "0\n"
    }

    "and so is a mutual cycle through one, whichever of the two is written first" in {
      run(s"${bufs}struct A\n    xs: Buf[B]\nstruct B\n    a: A\n" +
        "var n: Buf[B] = buf()\nprint(A(n).xs.len())") shouldBe "0\n"
      run(s"${bufs}struct B\n    a: A\nstruct A\n    xs: Buf[B]\n" +
        "var n: Buf[B] = buf()\nprint(A(n).xs.len())") shouldBe "0\n"
    }

    // The shape a syntax tree has, and the one that has no order to escape through: the enum is the
    // type holding the `Buf`, so it is the one in progress whichever way round the two are written.
    "an enum reaching itself through a struct in a Buf is finite, in both orders" in {
      val json =
        """enum Json
          |    Null
          |    Obj(ms: Buf[Member])
          |""".stripMargin
      val member =
        """struct Member
          |    key: string
          |    value: Json
          |""".stripMargin
      val use = "var ms: Buf[Member] = buf()\nms.push(Member(\"a\", Null))\nprint(ms.at(0).key)"

      run(s"$bufs$json$member$use") shouldBe "a\n"
      run(s"$bufs$member$json$use") shouldBe "a\n"
    }

    "a plain slice was never order-dependent, which is the comparison that made this a defect" in {
      run("struct A\n    xs: []B\nstruct B\n    a: A\nvar n = A([])\nprint(n.xs.len)") shouldBe "0\n"
      run("struct B\n    a: A\nstruct A\n    xs: []B\nvar n = A([])\nprint(n.xs.len)") shouldBe "0\n"
    }

    "while a generic holding its parameter by value is refused" in {
      err(s"${wrap}struct Node\n    w: Wrap[Node]\nvar n: Node\nprint(1)") should
        include("type 'Node' contains itself, so it has no finite size")
    }

    // The deferral has to survive nesting: the inner `Wrap[Node]` is itself an argument, so nothing
    // may be condemned while it is being resolved and the answer comes at the outer substitution.
    "including at one remove, where the generic is itself a type argument" in {
      err(s"${wrap}struct Node\n    w: Wrap[Wrap[Node]]\nvar n: Node\nprint(1)") should
        include("contains itself")
    }

    // **The case a deferred question must not lose.** `B` finishes long before `Wrap`'s `x: T`
    // substitutes it, so asking whether the argument *is* in progress answers no and lets an
    // unbounded type through; what has to be asked is what the argument reaches.
    "and around a mutual cycle, in both orders" in {
      err(s"${wrap}struct A\n    w: Wrap[B]\nstruct B\n    a: A\nvar x: A\nprint(1)") should
        include("contains itself")
      err(s"${wrap}struct B\n    a: A\nstruct A\n    w: Wrap[B]\nvar x: A\nprint(1)") should
        include("contains itself")
    }

    // A tuple holds its parts the way a struct holds its fields, so it is a by-value edge and the
    // walk has to cross it. Nothing else here does: every shape above reaches through a named type,
    // and a tuple carries its parts without one. Both directions are asserted, since a walk that
    // simply did not descend into tuples would pass the first of these for the wrong reason.
    "a tuple is a by-value edge, so the walk crosses it in both directions" in {
      run(s"${bufs}enum Json\n    Null\n    Obj(ms: Buf[(string, Json)])\n" +
        "var ms: Buf[(string, Json)] = buf()\nms.push((\"a\", Null))\nprint(ms.at(0).0)") shouldBe "a\n"
      err(s"${wrap}struct A\n    w: Wrap[(int, B)]\nstruct B\n    a: A\nvar x: A\nprint(1)") should
        include("contains itself")
    }

    "a generic holding a Buf of the type is finite again, since the Buf is where the edge is" in {
      run(s"$bufs${wrap}struct Node\n    w: Wrap[Buf[Node]]\n" +
        "var n: Buf[Node] = buf()\nprint(Node(Wrap(n)).w.x.len())") shouldBe "0\n"
    }

    // **The indirection on the far side of the loop, which is the placement the two above do not
    // reach.** Every shape so far puts the pointer between the substituted type and the one still
    // being resolved; here it is between the two in the other direction — `R` points at `S`, and
    // everything from `S` back round to `R` is by value. The cycle is finite, and what says so is
    // not the walk but the depth each type was *entered* at: `R` was entered outside the pointer
    // and the substitution is inside it, so the comparison does not fire.
    "and a cycle whose one pointer is on the way DOWN to the generic is finite too" in {
      run(s"${wrap}struct R\n    s: *S\nstruct S\n    c: Wrap[C]\nstruct C\n    r: R\n" +
        "var r: R\nprint(r.s == null)") shouldBe "true\n"
    }

    // **And the same walk reaching two of them at once, which is why it may not stop at the first.**
    // `R` was entered outside the pointer and `Q` inside it, so `C` reaching `R` is excused and `C`
    // reaching `Q` is not — `Q` holds a `Wrap[C]` by value and `C` holds a `Q` by value, with
    // nothing pointing anywhere between them. Field order is the only thing that decides which the
    // walk sees first, and it must not be the thing that decides whether this compiles.
    "so a second one further along the same fields is still refused, in either field order" in {
      err(s"${wrap}struct R\n    q: *Q\nstruct Q\n    w: Wrap[C]\nstruct C\n    r: R\n    q: Q\n" +
        "var r: R\nprint(1)") should include("type 'Q' contains itself, so it has no finite size")
      err(s"${wrap}struct R\n    q: *Q\nstruct Q\n    w: Wrap[C]\nstruct C\n    q: Q\n    r: R\n" +
        "var r: R\nprint(1)") should include("type 'Q' contains itself, so it has no finite size")
    }

    // **A type reached first as an argument is still judged on its own account.** The argument
    // position excuses a path that came *through* it and says nothing about what the argument does
    // to itself, so `Bad` holding a `Bad` is refused wherever `Bad` was first mentioned. `Phantom`
    // never uses its parameter in a field, which is what makes this the case nothing downstream
    // would catch: there is no substitution to ask the question at.
    "a type that contains itself is refused even where it was first reached as a type argument" in {
      val phantom = "struct Phantom[T]\n    n: int\n"

      err(s"${phantom}struct Use\n    p: Phantom[Bad]\nstruct Bad\n    b: Bad\nvar u: Use\nprint(1)") should
        include("type 'Bad' contains itself, so it has no finite size")
      err(s"${phantom}struct Bad\n    b: Bad\nstruct Use\n    p: Phantom[Bad]\nvar u: Use\nprint(1)") should
        include("type 'Bad' contains itself, so it has no finite size")
    }
  }

  "null exists only for the raw tier" - {
    "so a reference refuses it, and names what an absent one is written as" in {
      err("var r: &int = null\nprint(*r)") should
        include("a &int always points at a live object — an absent one is Option[&int]")
    }

    "and pointers have equality with no ordering, since an address answers nothing else" in {
      err("var a = 1\nvar b = 2\nvar p = &a\nvar q = &b\nprint(p < q)") should
        include("'<' is not defined for *int")
    }
  }

  /** What reaching *through* a mode gets you, asked of every mode at once. A value, a `&T` and a
    * `*T` all reach the thing they point at; a `weak T` is the one that does not, because it may be
    * gone — and that is `03`'s design rather than a gap, so the refusal is pinned beside the three
    * that work.
    */
  "reaching through the modes" - {
    "a field is read through a value, a counted reference and a raw pointer alike" in {
      run("""struct P
            |    x: int
            |
            |var owned = P(7)
            |var r: &P = P(8)
            |var p: *P = &owned
            |print(owned.x, r.x, p.x)
            |""".stripMargin) shouldBe "7 8 7\n"
    }

    "and a method is called the same three ways" in {
      run("""struct P
            |    x: int
            |
            |    get(self) -> int = self.x
            |
            |var owned = P(7)
            |var r: &P = P(8)
            |var p: *P = &owned
            |print(owned.get(), r.get(), p.get())
            |""".stripMargin) shouldBe "7 8 7\n"
    }

    "an array is indexed and sliced through them too" in {
      run("""var arr = [1, 2, 3]
            |var p: *[3]int = &arr
            |var r: &[3]int = [4, 5, 6]
            |print(arr[0], p[0], r[0], arr[1..].len, p[1..].len, r[1..].len)
            |""".stripMargin) shouldBe "1 1 4 2 2 2\n"
    }

    "but nothing is read off a weak reference, which may be gone" in {
      err("""struct P
            |    x: int
            |
            |var r: &P = P(8)
            |var w: weak P = r
            |print(w.x)
            |""".stripMargin) should include("may be gone, so nothing is read off one directly")
    }
  }
}
