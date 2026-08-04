package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** A trait that **requires** another (`02 § A trait may require another trait`).
 *
 * There are two customers and they ask for different things. A **bound** wants economy: `[T: Word]`
 * rather than `[T: Word + Add + BitXor + …]` at every declaration. A **trait object** wants
 * something otherwise unwritable: a bound may name several traits because it is a list, and an
 * object names one because it is a type, so a required trait is the only way an erased value can
 * still answer a second trait's members. Both halves are here, and the object half is what the
 * table layout was chosen for.
 */
class SupertraitTests extends AnyFreeSpec with RunSupport with CodegenSupport {

  /** A named thing that greets, with the greeting a default written in terms of the required
   * trait's method — which is the shape a supertrait exists to allow.
   */
  private val greet =
    """trait Named
      |    name(self) -> string
      |trait Greet: Named
      |    greet(self) -> string = "hello, " + self.name()
      |struct P
      |    n: string
      |impl Named for P
      |    name(self) -> string = self.n
      |impl Greet for P
      |""".stripMargin

  "what a bound reaches" - {
    "a bound licenses the members of the traits its trait requires" in {
      run(greet + "f[T: Greet](x: T) -> string = x.name()\nprint(f(P(\"ed\")))") shouldBe "ed\n"
    }

    "and its own, so the two are one promise" in {
      run(greet + "f[T: Greet](x: T) -> string = x.greet()\nprint(f(P(\"ed\")))") shouldBe "hello, ed\n"
    }

    /** **Entailment**, which is a stronger claim than the two above and the one they read as if they
     * had made. Licensing a *member* says the bound can reach `name` through `T`. This says the
     * bound satisfies another **bound**: `T: Greet` is accepted where `U: Named` is asked for, so a
     * requirement is a fact about `T` that the whole constraint system knows, not a lookup rule for
     * member names.
     *
     * Worth its own test because the difference is invisible until something depends on it. A
     * language could plausibly do the first and not the second — resolve `x.name()` by searching the
     * requirement closure while leaving the bound tables ignorant of it — and every test above would
     * still pass.
     */
    "and a bound on a required trait is satisfied by one on the trait that requires it" in {
      run(
        greet +
          """g[U: Named](y: U) -> string = y.name()
            |f[T: Greet](x: T) -> string = g(x)
            |print(f(P("ed")))""".stripMargin) shouldBe "ed\n"
    }

    // The point of a supertrait rather than a bound alias: the default body may use what the trait
    // required, because the trait itself made that promise.
    "a default body may call a required trait's method" in {
      run(greet + "print(P(\"ed\").greet())") shouldBe "hello, ed\n"
    }

    "a required trait's operator works on a bounded parameter" in {
      run(
        """trait Word: Add + Mul
          |    width(self) -> int
          |f[T: Word](a: T, b: T) -> T = a * b + a
          |impl Word for i32
          |    width(self) -> int = 32
          |print(f(3i32, 4i32))""".stripMargin,
      ) shouldBe "15\n"
    }

    // What the bound half buys, stated as a type-checking fact rather than as an ergonomic one: a
    // `[T: Word]` is accepted where a `[U: Add]` was asked for, with no second bound written.
    "a parameter bounded by the requiring trait satisfies the required one" in {
      run(
        """trait Word: Add
          |    width(self) -> int
          |twice[U: Add](x: U) -> U = x + x
          |f[T: Word](x: T) -> T = twice(x)
          |impl Word for i32
          |    width(self) -> int = 32
          |print(f(21i32))""".stripMargin,
      ) shouldBe "42\n"
    }

    "the requirement is transitive" in {
      run(
        """trait A
          |    a(self) -> int
          |trait B: A
          |    b(self) -> int
          |trait C: B
          |    c(self) -> int
          |struct S
          |    v: int
          |impl A for S
          |    a(self) -> int = 1
          |impl B for S
          |    b(self) -> int = 2
          |impl C for S
          |    c(self) -> int = 4
          |f[T: C](x: T) -> int = x.a() + x.b() + x.c()
          |print(f(S(0)))""".stripMargin,
      ) shouldBe "7\n"
    }

    "a required trait may take arguments the requiring trait fixes" in {
      run(
        """trait Into[U]
          |    into(self) -> U
          |trait Doubling: Into[int]
          |    scale(self) -> int
          |struct N
          |    v: int
          |impl Into[int] for N
          |    into(self) -> int = self.v
          |impl Doubling for N
          |    scale(self) -> int = 2
          |f[T: Doubling](x: T) -> int = x.into() * x.scale()
          |print(f(N(21)))""".stripMargin,
      ) shouldBe "42\n"
    }

    // A generic trait passes its own parameter down, which is the only reason the arguments of a
    // required trait are resolved late rather than at the declaration.
    "and the requiring trait may pass its own parameter down" in {
      run(
        """trait Into[U]
          |    into(self) -> U
          |trait Convert[U]: Into[U]
          |    tag(self) -> int
          |struct N
          |    v: int
          |impl Into[int] for N
          |    into(self) -> int = self.v
          |impl Convert[int] for N
          |    tag(self) -> int = 7
          |f[T: Convert[int]](x: T) -> int = x.into() + x.tag()
          |print(f(N(35)))""".stripMargin,
      ) shouldBe "42\n"
    }
  }

  private val shape =
    """trait Shape: Display
      |    area(self) -> int
      |struct Rect
      |    w: int
      |    h: int
      |impl Shape for Rect
      |    area(self) -> int = self.w * self.h
      |impl Display for Rect
      |    display(self, out: *Writer, fmt: FormatSpec) = display_pad("a rect".bytes, out, fmt)
      |""".stripMargin

  /** A bound is satisfied by an object, which is the other half of "what an object reaches" and the
   * half that used to be missing. A `&Shape` reaches `Shape`'s members by *name* because the table
   * holds them; these say the same table answers a **constraint**, so one generic function takes
   * both a `Rect` and a `&Shape`.
   *
   * Every one of these runs rather than merely compiling, because the interesting failure is not the
   * bound being refused — it is the bound being accepted and the body then calling nothing, or
   * calling the wrong slot. Only a value coming back says which slot was reached.
   */
  "what a bound reaches through an object" - {
    "a bound on the object's own trait is satisfied by the object" in {
      run(
        shape +
          """area_of[T: Shape](x: T) -> int = x.area()
            |var o: &Shape = Rect(3, 4)
            |print(area_of(o))""".stripMargin) shouldBe "12\n"
    }

    // The raw sigil is a separate type and gets its own test for that reason: what licenses both is
    // the erasure they share, not anything about counting.
    "and by a raw one" in {
      run(
        shape +
          """area_of[T: Shape](x: T) -> int = x.area()
            |var r = Rect(3, 4)
            |var o: *Shape = &r
            |print(area_of(o))""".stripMargin) shouldBe "12\n"
    }

    /** The whole point of the rule: **one** function body, reached by a concrete type and by the
     * object erased from it, printing the same answer twice. Written as one program rather than two
     * so that nothing but the argument differs between the two calls.
     */
    "so one generic function takes both a concrete type and the object erased from it" in {
      run(
        shape +
          """area_of[T: Shape](x: T) -> int = x.area()
            |var o: &Shape = Rect(3, 4)
            |print(area_of(Rect(3, 4)))
            |print(area_of(o))""".stripMargin) shouldBe "12\n12\n"
    }

    /** A bound on a **required** trait, which needs no rule of its own — the object satisfies its own
     * trait, and entailment already carries a requirement from there. That it falls out is the claim,
     * so the test asks for `Display` rather than `Shape`.
     */
    "a bound on a required trait is satisfied by the object too" in {
      run(
        shape +
          """show[T: Display](x: T)
            |    print(x)
            |end show
            |var o: &Shape = Rect(3, 4)
            |show(o)""".stripMargin) shouldBe "a rect\n"
    }

    "and by a raw object, through the same requirement" in {
      run(
        shape +
          """show[T: Display](x: T)
            |    print(x)
            |end show
            |var r = Rect(3, 4)
            |var o: *Shape = &r
            |show(o)""".stripMargin) shouldBe "a rect\n"
    }

    // A bound is also what a *type* asks of its parameters (`10 §5`), and it is the one check, so an
    // object is a legal argument there as well.
    "a type's own bound takes an object" in {
      run(
        shape +
          """struct Holder[T: Shape]
            |    held: T
            |var o: &Shape = Rect(3, 4)
            |var h = Holder(o)
            |print(h.held.area())""".stripMargin) shouldBe "12\n"
    }

    /** Entailment from the object, which is the composition of the two rules rather than either. The
     * object satisfies `Shape`; `f`'s bound is `Shape`; `f` hands its parameter to `g`, which asks
     * for `Display`. Nothing here knows an object is involved — that is what makes it the test.
     */
    "an object handed on to a bound that only the requirement satisfies" in {
      run(
        shape +
          """g[U: Display](y: U)
            |    print(y)
            |end g
            |f[T: Shape](x: T)
            |    g(x)
            |end f
            |var o: &Shape = Rect(3, 4)
            |f(o)""".stripMargin) shouldBe "a rect\n"
    }

    // A default body is written against the trait's own promises, and reaching one through an object
    // exercises a slot the implementer never wrote.
    "a bounded call reaches a default body through the object" in {
      run(
        greet +
          """hail[T: Greet](x: T) -> string = x.greet()
            |var o: &Greet = P("ed")
            |print(hail(o))""".stripMargin) shouldBe "hello, ed\n"
    }

    /** The rule answers a bound and **nothing writes an `impl` that could answer it too**, so there
     * is one answer rather than two that might disagree. Coherence is what stops the second: an
     * object type names the trait and nothing of the program's, so a block written for it has no
     * module it belongs in — the same reason `impl Display for []int` has none.
     */
    "no program may write an implementation for the object type itself" in {
      err(
        shape +
          """impl Display for &Shape
            |    display(self, out: *Writer, fmt: FormatSpec) = display_pad("o".bytes, out, fmt)""".stripMargin,
      ) should include("&Shape")
    }

    /** The **iteration protocol** is a trait, so a `for` over an object is the bound rule and not a
     * feature of its own. Worth pinning because it is the first place someone meets the rule without
     * having written a bound: `for x in it` is `Iterate`'s member called through whatever `it` is.
     */
    "a for loop walks an erased iterator, the protocol being a trait like any other" in {
      run(
        """struct Countdown
          |    n: int
          |
          |impl Iterate[int] for Countdown
          |    next(*self) -> Option[int]
          |        if self.n <= 0 then None else
          |            self.n -= 1
          |            Some(self.n)
          |end Countdown
          |
          |var c = Countdown(3)
          |var it: *Iterate[int] = &c
          |var total = 0
          |
          |for x in it do total += x
          |
          |print(total)""".stripMargin) shouldBe "3\n"
    }

    /** A **multi-trait** bound is a list and every entry is asked separately, so an object meets it
     * exactly when it meets each — which for a required trait it does, and for an unrelated one it
     * does not. Both halves here, because the interesting failure would be a list answered by its
     * first entry.
     */
    "a multi-trait bound is met entry by entry" in {
      run(
        shape +
          """both[T: Shape + Display](x: T) -> int
            |    print(x)
            |    x.area()
            |end both
            |var o: &Shape = Rect(3, 4)
            |print(both(o))""".stripMargin) shouldBe "a rect\n12\n"

      err(
        shape +
          """trait Weighed
            |    weight(self) -> int
            |both[T: Shape + Weighed](x: T) -> int = x.area() + x.weight()
            |var o: &Shape = Rect(3, 4)
            |print(both(o))""".stripMargin) should
        include("'both' requires its type parameter 'T' to implement 'Weighed', but &Shape does not")
    }

    /** A **weak** reference to an object does not satisfy the bound, and should not: a `weak` has to
     * be upgraded before anything may be called through it, so the members the bound names are not
     * available on the value as it stands. The rule keys on the two modes that carry a table, which
     * is what makes this fall out rather than needing to be excluded.
     */
    "a weak reference to an object does not satisfy the bound" in {
      err(
        shape +
          """area_of[T: Shape](x: T) -> int = x.area()
            |var o: &Shape = Rect(3, 4)
            |var w: weak Shape = o
            |print(area_of(w))""".stripMargin) should
        include("requires its type parameter 'T' to implement 'Shape'")
    }

    // The bound licenses behaviour and nothing about layout, so a body reaching for layout is refused
    // at its definition whatever it is instantiated at — the object is not a special case of that.
    "a bounded body may still not read a field, and the object changes nothing about it" in {
      err(shape + "wide[T: Shape](x: T) -> int = x.w") should
        include("'T' is a type parameter, so it has no fields to read")
    }

    // Recursion instantiates at a fixed set of arguments, so an object recurses like anything else.
    "a bounded generic recurses at the object" in {
      run(
        shape +
          """repeat[T: Shape](x: T, n: int) -> int
            |    if n <= 0 then 0 else x.area() + repeat(x, n - 1)
            |end repeat
            |var o: &Shape = Rect(3, 4)
            |print(repeat(o, 3))""".stripMargin) shouldBe "36\n"
    }

    // The object is an ordinary argument, so it is an ordinary result too — the parameter appearing
    // in the return type is not a second question.
    "an object comes back out of a bounded function" in {
      run(
        shape +
          """same[T: Shape](x: T) -> T = x
            |var o: &Shape = Rect(3, 4)
            |print(same(o).area())""".stripMargin) shouldBe "12\n"
    }

    /** **Conditional conformance** reached through an object: a `Box` implements `Display` when its
     * element implements `Shape`, and the element here is an object. The condition is `satisfies`
     * asked one step in, so this says the new answer is available to the recursive question and not
     * only to the outermost one.
     */
    "a conditional implementation's condition is met by an object" in {
      run(
        shape +
          """struct Box[T]
            |    v: T
            |impl[T: Shape] Display for Box[T]
            |    display(self, out: *Writer, fmt: FormatSpec) = display_pad(str(self.v.area()).bytes, out, fmt)
            |var o: &Shape = Rect(3, 4)
            |print(Box(o))""".stripMargin) shouldBe "12\n"
    }

    /** A trait that takes arguments is a **family** of promises, and erasing one of them erases one
     * of them. The object satisfies the bound at the argument list it was written at and no other,
     * which is the same rule an `impl` is held to — the erasure changes what is known about the
     * receiver, never what the trait was applied to.
     */
    "an object over a generic trait satisfies that trait at its own arguments" in {
      val sink =
        """trait Sink[T]
          |    take(self, v: T) -> int
          |struct S
          |    v: int
          |impl Sink[int] for S
          |    take(self, v: int) -> int = self.v + v
          |""".stripMargin

      run(
        sink +
          """feed[U: Sink[int]](x: U) -> int = x.take(5)
            |var o: &Sink[int] = S(2)
            |print(feed(o))""".stripMargin) shouldBe "7\n"

      err(
        sink +
          """feed[U: Sink[string]](x: U) -> int = 0
            |var o: &Sink[int] = S(2)
            |print(feed(o))""".stripMargin) should
        include("'feed' requires its type parameter 'U' to implement 'Sink[string]', but &Sink[int] does not")
    }
  }

  "what an object reaches" - {
    "an object answers the trait's own members" in {
      run(shape + "var o: &Shape = Rect(3, 4)\nprint(o.area())") shouldBe "12\n"
    }

    // The finding this feature was taken for: a value that implements `Display` used to stop being
    // printable at the moment it was erased.
    "an object printed through print" in {
      run(shape + "var o: &Shape = Rect(3, 4)\nprint(o)") shouldBe "a rect\n"
    }

    "an object rendered by str" in {
      run(shape + "var o: &Shape = Rect(3, 4)\nprint(str(o) + \"!\")") shouldBe "a rect!\n"
    }

    // The specifier describes the field the *whole* value occupies (`14 §2`), and it reaches an
    // implementation through the table exactly as it reaches one through a name.
    "an object padded by a format specifier" in {
      run(shape + "var o: &Shape = Rect(3, 4)\nprint(f\"${o}%10s|\")") shouldBe "    a rect|\n"
    }

    "a raw object renders the same way" in {
      run(shape + "var r = Rect(3, 4)\nvar o: *Shape = &r\nprint(o)") shouldBe "a rect\n"
    }

    // A required trait's method is a slot in the same table, so calling one explicitly is the same
    // indirect call the trait's own methods are.
    "a required trait's method is callable on the object by name" in {
      run(
        "import sysl.buf.byte_sink\nimport sysl.text.from_utf8\n\n" + shape +
          """var o: &Shape = Rect(3, 4)
            |var g = byte_sink()
            |o.display(&g, FormatSpec(0, -1, false))
            |print(from_utf8(g.text()).unwrap())""".stripMargin,
      ) shouldBe "a rect\n"
    }

    "a required trait's property is a slot too" in {
      run(
        """trait Sized
          |    size -> int
          |trait Boxed: Sized
          |    label(self) -> string
          |struct B
          |    v: int
          |impl Sized for B
          |    size -> int = 9
          |impl Boxed for B
          |    label(self) -> string = "b"
          |var o: &Boxed = B(0)
          |print(o.size)
          |print(o.label())""".stripMargin,
      ) shouldBe "9\nb\n"
    }

    // Dispatch has to pick the implementation, not the static type: two shapes behind one object
    // type must render differently.
    "two implementations behind one object type render differently" in {
      run(
        "import sysl.buf.*\n\n" + shape +
          """struct Dot
            |    v: int
            |impl Shape for Dot
            |    area(self) -> int = 0
            |impl Display for Dot
            |    display(self, out: *Writer, fmt: FormatSpec) = display_pad("a dot".bytes, out, fmt)
            |var xs: &Buf[&Shape] = buf()
            |xs.push(Rect(3, 4))
            |xs.push(Dot(0))
            |for i in 0..<xs.len()
            |    print(xs.at(i))""".stripMargin,
      ) shouldBe "a rect\na dot\n"
    }

    "the required trait's slots come before the requiring trait's own" in {
      // `Display` is required, so its `display` is slot 0 and `Shape`'s `area` is slot 1 — the
      // property the table and every call site have to agree on.
      val out = ir(shape + "var o: &Shape = Rect(3, 4)\nprint(o.area())\nprint(o)")

      out should include("@vt.ref.Shape.Rect = private constant [2 x ptr] [ptr @vt.adapt.ref.Rect.display, ptr @vt.adapt.ref.Rect.area]")
    }
  }

  "what the declarations have to satisfy" - {
    "a trait cannot require something that is not a trait" in {
      err(
        """struct S
          |    v: int
          |trait T: S
          |    m(self) -> int""".stripMargin,
      ) should include("requires 'S', which is not a trait")
    }

    "a required trait is held to its arity" in {
      err(
        """trait Into[U]
          |    into(self) -> U
          |trait T: Into
          |    m(self) -> int""".stripMargin,
      ) should include("takes 1 type argument")
    }

    // A trait promises this of every type that implements it, and `Self` is the name of that type —
    // so writing it in a requirement is asking for something that is only known at the `impl`.
    /** `Self` in a requirement's arguments is the type implementing the requiring trait, exactly as
      * it is in a method signature — `trait T: Into[Self]` asks for a conversion into whatever
      * implements `T`. It was once refused as meaningless, which it is not: a trait's parameter can
      * be an operand type, and naming the implementing type there is the ordinary thing to want.
      */
    "a requirement may name the implementing type" in {
      run(
        """trait Into[U]
          |    into(self) -> U
          |trait T: Into[Self]
          |    m(self) -> int
          |struct P
          |    v: int
          |impl Into[P] for P
          |    into(self) -> P = P(self.v)
          |impl T for P
          |    m(self) -> int = self.v
          |print(P(7).into().m())""".stripMargin,
      ) shouldBe "7\n"
    }

    /** And it is the *same* requirement however it is spelled, which is what keeps the flattened
      * table from laying out two slots for one member — the rule that a trait is required once, met
      * here by two spellings rather than two argument lists.
      */
    "written out or left to the default, it is one requirement" in {
      err(
        """trait Into[U = Self]
          |    into(self) -> U
          |trait T: Into[Self] + Into[int]
          |    m(self) -> int""".stripMargin,
      ) should include("a type implements one trait once")
    }

    "a trait may not require itself" in {
      err("trait T: T\n    m(self) -> int") should include("requires itself")
    }

    "nor around a cycle" in {
      err(
        """trait A: C
          |    a(self) -> int
          |trait B: A
          |    b(self) -> int
          |trait C: B
          |    c(self) -> int""".stripMargin,
      ) should include("requires itself")
    }

    // The coherence rule one level up: a trait's members become the implementing type's, and a
    // type's members are one namespace.
    "two traits that declare one name cannot both be required" in {
      err(
        """trait L
          |    len(self) -> int
          |trait R
          |    len(self) -> int
          |trait Both: L + R
          |    m(self) -> int""".stripMargin,
      ) should include("both declare 'len'")
    }

    "and neither may a required trait collide with the requiring trait's own member" in {
      err(
        """trait L
          |    len(self) -> int
          |trait Own: L
          |    len(self) -> int""".stripMargin,
      ) should include("both declare 'len'")
    }

    // The same rule as the name collision above, seen from the other side: `Into[int]` and
    // `Into[bool]` would be two `into`s for one type, so nothing could ever satisfy both. Refused at
    // the declaration, because the `impl` would otherwise be told to write a block that is itself
    // refused.
    "one trait cannot be required at two argument lists" in {
      err(
        """trait Into[U]
          |    into(self) -> U
          |trait Both: Into[int] + Into[bool]
          |    tag(self) -> int""".stripMargin,
      ) should include("requires both 'Into[int]' and 'Into[bool]', and a type implements one trait once")
    }

    // Whether two requirements are the same promise is a question about types, not about spellings:
    // `int` and `i32` are one type, so this is one requirement written twice and is accepted.
    "and the two are compared as types, not as they were written" in {
      run(
        """trait Into[U]
          |    into(self) -> U
          |trait Both: Into[int] + Into[i32]
          |    tag(self) -> int
          |struct S
          |    v: int
          |impl Into[int] for S
          |    into(self) -> int = self.v
          |impl Both for S
          |    tag(self) -> int = 1
          |print(S(7).into() + S(0).tag())""".stripMargin,
      ) shouldBe "8\n"
    }

    // Required directly *and* through another requirement, at the same arguments: one slot.
    "a trait required directly and transitively is carried once" in {
      run(
        """trait Base
          |    base(self) -> int
          |trait L: Base
          |    l(self) -> int
          |trait D: L + Base
          |    d(self) -> int
          |struct T
          |    v: int
          |impl Base for T
          |    base(self) -> int = 1
          |impl L for T
          |    l(self) -> int = 2
          |impl D for T
          |    d(self) -> int = 4
          |var p: &D = T(0)
          |print(p.base() + p.l() + p.d())""".stripMargin,
      ) shouldBe "7\n"
    }

    // The diamond is not a collision: one trait reached by two routes is taken once.
    "a trait required by two routes is carried once" in {
      run(
        """trait Base
          |    base(self) -> int
          |trait L: Base
          |    l(self) -> int
          |trait R: Base
          |    r(self) -> int
          |trait D: L + R
          |    d(self) -> int
          |struct S
          |    v: int
          |impl Base for S
          |    base(self) -> int = 1
          |impl L for S
          |    l(self) -> int = 2
          |impl R for S
          |    r(self) -> int = 4
          |impl D for S
          |    d(self) -> int = 8
          |var o: &D = S(0)
          |print(o.base() + o.l() + o.r() + o.d())""".stripMargin,
      ) shouldBe "15\n"
    }
  }

  "what an implementation has to supply" - {
    // Checked at the `impl` rather than at the bound: that is where the promise is made, and a
    // table for a `&Sub` needs a slot for every required trait's method.
    "an impl of a requiring trait needs the required one too" in {
      err(
        """trait Named
          |    name(self) -> string
          |trait Greet: Named
          |    greet(self) -> string
          |struct P
          |    v: int
          |impl Greet for P
          |    greet(self) -> string = "hi"""".stripMargin,
      ) should include("'Greet' requires 'Named', so 'P' has to implement that too — write 'impl Named for P'")
    }

    "the implementation that supplies it may be written below the one that needs it" in {
      run(
        """trait Named
          |    name(self) -> string
          |trait Greet: Named
          |    greet(self) -> string = "hello, " + self.name()
          |struct P
          |    v: int
          |impl Greet for P
          |impl Named for P
          |    name(self) -> string = "ed"
          |print(P(0).greet())""".stripMargin,
      ) shouldBe "hello, ed\n"
    }

    "a requirement reaches through a generic impl's own condition" in {
      err(
        """trait Named
          |    name(self) -> string
          |trait Greet: Named
          |    greet(self) -> string
          |struct Box[T]
          |    v: T
          |impl[T: Named] Named for Box[T]
          |    name(self) -> string = self.v.name()
          |impl[T] Greet for Box[T]
          |    greet(self) -> string = "hi"""".stripMargin,
      ) should include("has to implement that too")
    }

    "and is met when the conditions agree" in {
      run(
        """trait Named
          |    name(self) -> string
          |trait Greet: Named
          |    greet(self) -> string = "hello, " + self.name()
          |struct Box[T]
          |    v: T
          |impl[T: Named] Named for Box[T]
          |    name(self) -> string = self.v.name()
          |impl[T: Named] Greet for Box[T]
          |struct P
          |    v: int
          |impl Named for P
          |    name(self) -> string = "ed"
          |print(Box(P(0)).greet())""".stripMargin,
      ) shouldBe "hello, ed\n"
    }

    // A built-in's membership is a rule rather than an `impl`, so it satisfies the requirement —
    // `impl Word for i32` is asking `i32` for arithmetic it has always had.
    "a built-in meets a requirement the compiler already provides" in {
      run(
        """trait Word: Add
          |    width(self) -> int
          |impl Word for i32
          |    width(self) -> int = 32
          |print(3i32.width())""".stripMargin,
      ) shouldBe "32\n"
    }

    "a required trait may be supplied by an impl for a shape" in {
      run(
        """trait Show
          |    show(self) -> string
          |trait Pretty: Show
          |    pretty(self) -> string = "pretty " + self.show()
          |impl[T] Show for []T
          |    show(self) -> string = "slice"
          |impl[T] Pretty for []T
          |var xs: []int = [1, 2, 3]
          |print(xs.pretty())""".stripMargin,
      ) shouldBe "pretty slice\n"
    }

    // A trait whose members are all defaults still has to be implemented: the promise is that the
    // type is a member, and an empty block is how a type says so.
    "an empty impl satisfies a requirement whose members are all defaults" in {
      run(
        """trait Greeting
          |    hello(self) -> string = "hi"
          |trait Polite: Greeting
          |    please(self) -> string = self.hello() + ", please"
          |struct P
          |    v: int
          |impl Greeting for P
          |impl Polite for P
          |print(P(0).please())""".stripMargin,
      ) shouldBe "hi, please\n"
    }
  }

  "what erasure still refuses" - {
    /** **Erasing an object again**, which is the case the bound rule could have opened by accident and
     * must not. A `&Greet` now satisfies a bound on `Named`, and it would be an easy step from there
     * to accepting it where a `&Named` is wanted — but a table is laid out from a *type*'s
     * implementations, and an object has none to lay a second one out from. Satisfying a bound is a
     * question about what can be called; building an object is a question about what can be
     * assembled, and only the first of those an object can answer.
     */
    "an object may not be erased a second time, into the object of a trait it requires" in {
      val out = err(
        greet +
          """var o: &Greet = P("ed")
            |var q: &Named = o""".stripMargin,
      )

      out should include("a &Greet has forgotten which type it holds, so there is nothing for a " +
        "&Named to be built from")
      out should include("a bound on it is satisfied by one, but its slots are not a table of their own")

      // And the reason is erasure rather than the requirement, so it reads the same for a trait the
      // object's own trait says nothing about.
      err(
        greet +
          """trait Other
            |    other(self) -> int
            |var o: &Greet = P("ed")
            |var q: &Other = o""".stripMargin,
      ) should include("a &Greet has forgotten which type it holds, so there is nothing for a " +
        "&Other to be built from")
    }


    // A required trait that cannot be erased makes the trait that required it unerasable too, and
    // the message names the trait the offending member came from.
    "a trait that requires an unerasable one has no object" in {
      err(
        """trait Word: Add
          |    width(self) -> int
          |var w: &Word = 1i32""".stripMargin,
      ) should include(s"'add' of '${lib("Add")}' mentions 'Self' away from its receiver")
    }

    "an object over a trait that requires nothing printable is still refused" in {
      err(
        """trait Shape
          |    area(self) -> int
          |struct Rect
          |    w: int
          |    h: int
          |impl Shape for Rect
          |    area(self) -> int = self.w * self.h
          |impl Display for Rect
          |    display(self, out: *Writer, fmt: FormatSpec) = display_pad("r".bytes, out, fmt)
          |var o: &Shape = Rect(3, 4)
          |print(o)""".stripMargin,
      ) should include("write 'trait Shape: sysl.Display'")
    }

    // A table holds function pointers and a compiler-provided membership has no function — the
    // sentence `02` already carried, now reachable through a required trait rather than only
    // through the operator catalog.
    "a built-in that satisfies a required trait by rule cannot be erased" in {
      err(
        """trait Tagged: Display
          |    tag(self) -> int
          |impl Tagged for i32
          |    tag(self) -> int = 7
          |var o: &Tagged = 1i32""".stripMargin,
      ) should include("implements 'sysl.Display' by the compiler's own rule rather than through an 'impl'")
    }

    // The object-safety diagnostic has two names to get right and they are different names: the
    // trait the offending member came from, and the object type the programmer actually wrote.
    "a required trait's '&self' method names both the trait and the sigil to write" in {
      val out = err(
        """trait Counted
          |    bump(&self) -> int
          |trait Thing: Counted
          |    id(self) -> int
          |struct S
          |    v: int
          |impl Counted for S
          |    bump(&self) -> int = self.v
          |impl Thing for S
          |    id(self) -> int = 1
          |var s = S(2)
          |var o: *Thing = &s""".stripMargin,
      )

      out should include("'bump' of 'Counted' takes '&self'")
      out should include("'*Thing' points straight at a value, so write '&Thing' instead")
    }

    // A type that does not implement a required trait at all is a different failure from one whose
    // membership the compiler provides, and saying the second where the first is true is a lie.
    "a type missing a required trait is told that, not told about the compiler's rules" in {
      val out = err(
        """trait Named
          |    name(self) -> string
          |trait Greet: Named
          |    greet(self) -> string
          |struct P
          |    v: int
          |impl Greet for P
          |    greet(self) -> string = "hi"
          |var o: &Greet = P(1)""".stripMargin,
      )

      out should include("'Greet' requires 'Named', and P does not implement it — so there is no " +
        "'name' for its table to point at")
      out should not include "by the compiler's own rule"
    }

    "a method no trait in the closure declares is still refused" in {
      err(shape + "var o: &Shape = Rect(3, 4)\nprint(o.volume())") should
        include("declares no method 'volume'")
    }

    /** `13 §2`'s rule, and a requirement is the sharpest case of it: implementing the trait means
     * implementing the required one too, so a requirement the implementer cannot name leaves the
     * trait unimplementable from outside. Reported at the declaration that made the promise, not at
     * the `impl` that cannot keep it.
     */
    "a trait may not require one that reaches less far than it does" in {
      errIn(
        ("m", "m.sysl",
         """module m
           |private trait Hidden
           |    secret(self) -> int
           |trait Open: Hidden
           |    shown(self) -> int""".stripMargin),
        ("", "main.sysl", "import m.Open\nprint(1)"),
      ) should include("'Open' is public, but the trait it requires names 'm.Hidden', which is private")
    }
  }

  "what it costs a program that requires nothing" - {
    // A trait with no requirements lays its table out exactly as before, which is what keeps this
    // feature free for every program that does not use it.
    "a trait with no requirements has the table it always had" in {
      val out = ir(
        """trait Shape
          |    area(self) -> int
          |    perimeter(self) -> int
          |struct Rect
          |    w: int
          |    h: int
          |impl Shape for Rect
          |    area(self) -> int = self.w * self.h
          |    perimeter(self) -> int = 2 * (self.w + self.h)
          |var o: &Shape = Rect(3, 4)
          |print(o.area())""".stripMargin,
      )

      out should include("@vt.ref.Shape.Rect = private constant [2 x ptr] [ptr @vt.adapt.ref.Rect.area, ptr @vt.adapt.ref.Rect.perimeter]")
    }
  }
}
