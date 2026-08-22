package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** A member that declares type parameters of its own (`10 § Open b`).
 *
 * The type's parameters and the member's are fixed from two different places, and that is the whole
 * of what makes this its own form: the receiver already says what the type's arguments are, while
 * the member's own are solved at the call, from what it is passed and from the type the context
 * expects — exactly as a generic free function's are. A type with no parameters at all may still
 * have a member with some, since none of that inference goes through the receiver.
 */
class GenericMethodRunTests extends AnyFreeSpec with RunSupport with CodegenSupport {

  /** How many functions the module defines for `Box.one`, which is how many instantiations of it
   * the program reached.
   */
  private def defined(src: String): Int =
    ir(src).linesIterator.count(l => l.startsWith("define") && l.contains("@Box.one."))

  "the member's own parameters come from the call" - {

    "one parameter from one argument, at two different types" in {
      run(
        """struct Box[T]
          |    v: T
          |    keep[U](self, x: U) -> U = x
          |var b = Box(1)
          |print(b.keep(7))
          |print(b.keep("s"))""".stripMargin,
      ) shouldBe "7\ns\n"
    }

    "a type that declares none of its own may still have a member that does" in {
      run(
        """struct Counter
          |    n: int
          |    with[U](self, x: U) -> U = x
          |var c = Counter(3)
          |print(c.with(9))
          |print(c.with("t"))""".stripMargin,
      ) shouldBe "9\nt\n"
    }

    "two parameters, one from each argument" in {
      run(
        """struct Pair[A, B]
          |    a: A
          |    b: B
          |struct Box[T]
          |    v: T
          |    of[U, V](self, x: U, y: V) -> Pair[U, V] = Pair(x, y)
          |var b = Box(0)
          |var p = b.of(4, "e")
          |print(p.a)
          |print(p.b)""".stripMargin,
      ) shouldBe "4\ne\n"
    }

    "the type's parameter and the member's meet in one result" in {
      run(
        """struct Pair[A, B]
          |    a: A
          |    b: B
          |struct Box[T]
          |    v: T
          |    with[U](self, x: U) -> Pair[T, U] = Pair(self.v, x)
          |var b = Box(41)
          |var p = b.with("hi")
          |print(p.a)
          |print(p.b)""".stripMargin,
      ) shouldBe "41\nhi\n"
    }

    "a parameter under a composed type is solved from the element" in {
      run(
        """struct Box[T]
          |    v: T
          |    first[U](self, xs: []U) -> U = xs[0]
          |var a = [7, 8]
          |var b = Box("k")
          |print(b.first(a[0..<2]))""".stripMargin,
      ) shouldBe "7\n"
    }
  }

  "the type the context expects settles what the arguments do not" - {

    "an annotated variable fixes a parameter only the result mentions" in {
      run(
        """struct Box[T]
          |    v: T
          |    zero[U](self) -> U = 0
          |var b = Box(1)
          |var x: i8 = b.zero()
          |print(x)""".stripMargin,
      ) shouldBe "0\n"
    }

    "and so does a result the parameter appears under" in {
      run(
        """struct Box[T]
          |    v: T
          |    make[U](self) -> Box[U] = Box(0)
          |var b = Box(1)
          |var c: Box[i16] = b.make()
          |print(c.v)""".stripMargin,
      ) shouldBe "0\n"
    }

    "a declared result of the calling function does it too" in {
      run(
        """struct Box[T]
          |    v: T
          |    zero[U](self) -> U = 0
          |small(b: Box[int]) -> i8 = b.zero()
          |print(small(Box(1)))""".stripMargin,
      ) shouldBe "0\n"
    }
  }

  "the receiver still fixes the type's own parameters" - {

    "a result written 'Self' is the receiver's own type" in {
      run(
        """struct Box[T]
          |    v: T
          |    again[U](self, x: U) -> Self = self
          |var b = Box(4)
          |print(b.again("z").v)""".stripMargin,
      ) shouldBe "4\n"
    }

    "a '*self' receiver reaches the type's own parameter" in {
      run(
        """struct Box[T]
          |    v: T
          |    swap[U](*self, x: U, y: T) -> U =
          |        self.v = y
          |        x
          |var b = Box(1)
          |print(b.swap(5, 2))
          |print(b.v)""".stripMargin,
      ) shouldBe "5\n2\n"
    }

    "a '&self' receiver does as well" in {
      run(
        """struct Box[T]
          |    v: T
          |    both[U](&self, x: U) -> T = self.v
          |var b: &Box[int] = Box(6)
          |print(b.both("q"))""".stripMargin,
      ) shouldBe "6\n"
    }
  }

  "bounds hold, the type's and the member's alike" - {

    "a member may bound its own parameter" in {
      run(
        """trait Rank
          |    rank(self) -> int
          |struct P
          |    n: int
          |impl Rank for P
          |    rank(self) -> int = self.n
          |struct Box[T]
          |    v: T
          |    scored[U: Rank](self, x: U) -> int = x.rank()
          |var b = Box("w")
          |print(b.scored(P(12)))""".stripMargin,
      ) shouldBe "12\n"
    }

    "the type's bound and the member's both hold inside one body" in {
      run(
        """struct Box[T: Display]
          |    v: T
          |    both[U: Display](self, x: U) -> string = f"${self.v}-${x}"
          |var b = Box(3)
          |print(b.both("z"))""".stripMargin,
      ) shouldBe "3-z\n"
    }

    "a bounded parameter may be handed on to something that asks the same" in {
      run(
        """trait Rank
          |    rank(self) -> int
          |struct P
          |    n: int
          |impl Rank for P
          |    rank(self) -> int = self.n
          |scored[T: Rank](x: T) -> int = x.rank()
          |struct Box[T]
          |    v: T
          |    hand[U: Rank](self, x: U) -> int = scored(x)
          |var b = Box(0)
          |print(b.hand(P(5)))""".stripMargin,
      ) shouldBe "5\n"
    }
  }

  "it is an ordinary member in every other respect" - {

    "an enum declares one the same way" in {
      run(
        """enum Opt[T]
          |    Empty
          |    Full(v: T)
          |    tagged[U](self, x: U) -> U = x
          |var o: Opt[int] = Full(2)
          |print(o.tagged(9))""".stripMargin,
      ) shouldBe "9\n"
    }

    "one generic member calls another" in {
      run(
        """struct Box[T]
          |    v: T
          |    one[U](self, x: U) -> U = x
          |    two[U](self, x: U) -> U = self.one(x)
          |var b = Box(1)
          |print(b.two(8))""".stripMargin,
      ) shouldBe "8\n"
    }

    "a member calls a generic free function with its own parameter" in {
      run(
        """id[T](x: T) -> T = x
          |struct Box[T]
          |    v: T
          |    thru[U](self, x: U) -> U = id(x)
          |var b = Box(1)
          |print(b.thru(6))""".stripMargin,
      ) shouldBe "6\n"
    }

    "one recurses at a single instantiation" in {
      run(
        """struct Box[T]
          |    v: T
          |    down[U](self, x: U, k: int) -> int =
          |        if k <= 0 then 0
          |        else self.down(x, k - 1) + 1
          |var b = Box(1)
          |print(b.down("a", 3))""".stripMargin,
      ) shouldBe "3\n"
    }

    "an associated function may declare parameters of its own" in {
      run(
        """struct Counter
          |    n: int
          |    make[T](x: T) -> T = x
          |print(Counter.make(5))
          |print(Counter.make("m"))""".stripMargin,
      ) shouldBe "5\nm\n"
    }

    "and on a generic type both lists are inferred from the call" in {
      run(
        """struct Pair[A, B]
          |    a: A
          |    b: B
          |    of[U](x: A, y: B, z: U) -> U = z
          |print(Pair.of(1, "s", true))""".stripMargin,
      ) shouldBe "true\n"
    }

    "an enum's associated function does the same" in {
      run(
        """enum Opt[T]
          |    Empty
          |    Full(v: T)
          |    wrap[U](x: T, y: U) -> U = y
          |print(Opt.wrap(1, "w"))""".stripMargin,
      ) shouldBe "w\n"
    }

    "a non-generic member of the same type calls one" in {
      run(
        """struct Box[T]
          |    v: T
          |    one[U](self, x: U) -> U = x
          |    two(self) -> int = self.one(5)
          |var b = Box("q")
          |print(b.two())""".stripMargin,
      ) shouldBe "5\n"
    }

    "a generic function calls one on a receiver whose type is still a parameter" in {
      run(
        """struct Box[T]
          |    v: T
          |    one[U](self, x: U) -> U = x
          |thru[T](b: Box[T]) -> int = b.one(3)
          |print(thru(Box("z")))""".stripMargin,
      ) shouldBe "3\n"
    }

    "the member's own parameter may be held in a memory mode" in {
      run(
        """struct Box[T]
          |    v: T
          |    deref[U](self, p: *U) -> U = *p
          |var n = 12
          |var b = Box(0)
          |print(b.deref(&n))""".stripMargin,
      ) shouldBe "12\n"
    }

    "and may be returned by reference" in {
      run(
        """struct Box[T]
          |    v: T
          |    hold[U](self, x: U) -> &U = x
          |var b = Box(0)
          |var r: &int = b.hold(3)
          |print(*r)""".stripMargin,
      ) shouldBe "3\n"
    }

    "one lives alongside an 'impl' on the same type" in {
      run(
        """trait Show
          |    show(self) -> int
          |struct Box[T]
          |    v: T
          |    one[U](self, x: U) -> U = x
          |impl[T] Show for Box[T]
          |    show(self) -> int = 1
          |var b = Box(0)
          |print(b.show())
          |print(b.one("y"))""".stripMargin,
      ) shouldBe "1\ny\n"
    }

    "and does not keep its type out of a trait object" in {
      run(
        """trait Show
          |    show(self) -> int
          |struct P
          |    n: int
          |    one[U](self, x: U) -> U = x
          |impl Show for P
          |    show(self) -> int = self.n
          |var p = P(4)
          |var o: &Show = P(4)
          |print(o.show())
          |print(p.one("g"))""".stripMargin,
      ) shouldBe "4\ng\n"
    }

    "an owning receiver taken by value neither leaks nor frees twice" in {
      run(
        """struct Box[T]
          |    v: T
          |    one[U](self, x: U) -> T = self.v
          |var i = 0
          |while i < 500
          |    var b = Box(f"p${i}")
          |    var s = b.one(i)
          |    i += 1
          |print(i)""".stripMargin,
      ) shouldBe "500\n"
    }

    "an owning payload passed through one neither leaks nor frees twice" in {
      run(
        """struct Box[T]
          |    v: T
          |    keep[U](self, x: U) -> U = x
          |var b = Box(0)
          |var i = 0
          |while i < 1000
          |    var s = b.keep(f"n${i}")
          |    i += 1
          |print(i)""".stripMargin,
      ) shouldBe "1000\n"
    }
  }

  "each instantiation is its own function" - {

    "two argument types make two" in {
      defined(
        """struct Box[T]
          |    v: T
          |    one[U](self, x: U) -> U = x
          |var b = Box(1)
          |print(b.one(2))
          |print(b.one("q"))""".stripMargin,
      ) shouldBe 2
    }

    "the name carries the type's arguments and the member's, in that order" in {
      ir(
        """struct Box[T]
          |    v: T
          |    one[U](self, x: U) -> U = x
          |var b = Box(true)
          |print(b.one(2))""".stripMargin,
      ) should include("@Box.one.bool.int(")
    }

    "two calls at one instantiation make only one" in {
      defined(
        """struct Box[T]
          |    v: T
          |    one[U](self, x: U) -> U = x
          |var b = Box(1)
          |print(b.one(2))
          |print(b.one(3))""".stripMargin,
      ) shouldBe 1
    }

    "two receivers of different instantiations make two" in {
      defined(
        """struct Box[T]
          |    v: T
          |    one[U](self, x: U) -> U = x
          |var a = Box(1)
          |var b = Box(true)
          |print(a.one(2))
          |print(b.one(3))""".stripMargin,
      ) shouldBe 2
    }
  }

  /** A closure standing at a parameter written in terms of the **type's** parameter.
   *
   * The receiver has already said what that parameter is, so there is nothing left to wait for and
   * the closure is read against it — which is what the identical free function has always done. The
   * two are tested side by side below, because the whole of the defect was that they disagreed.
   *
   * A bare arrow is the same case reached by another road: it is sugar for a bound
   * (`MemberLowering.callBounds`), so `f: T -> U` on a member of a generic type writes a bound
   * naming the owner's `T`, and resolving that bound needs the receiver's arguments too. A member
   * writing one has type parameters of its own whether or not the source said so, which is why a
   * member declaring none at all reaches this path.
   */
  "a closure at a parameter naming the type's own parameter" - {

    "reads against what the receiver settled" in {
      run(
        """struct Box[T]
          |    v: T
          |    apply[U](self, f: &Fn(T) -> U) -> U = f(self.v)
          |val b = Box(3)
          |print(b.apply(n -> n + 1))""".stripMargin,
      ) shouldBe "4\n"
    }

    "the free function of the same signature agrees" in {
      run(
        """struct Box[T]
          |    v: T
          |    apply[U](self, f: &Fn(T) -> U) -> U = f(self.v)
          |free_apply[T, U](v: T, f: &Fn(T) -> U) -> U = f(v)
          |val b = Box(3)
          |print(free_apply(3, n -> n + 1))
          |print(b.apply(n -> n + 1))""".stripMargin,
      ) shouldBe "4\n4\n"
    }

    "what the closure yields is still read off its body" in {
      run(
        """struct Box[T]
          |    v: T
          |    apply[U](self, f: &Fn(T) -> U) -> U = f(self.v)
          |val b = Box(3)
          |print(b.apply(n -> s"<${n}>"))""".stripMargin,
      ) shouldBe "<3>\n"
    }

    "two of the type's parameters, at a closure taking both" in {
      run(
        """struct Pair[A, B]
          |    a: A
          |    b: B
          |    fold[R](self, f: &Fn(A, B) -> R) -> R = f(self.a, self.b)
          |print(Pair(2, "xy").fold((x, y) -> s"${x}${y}"))""".stripMargin,
      ) shouldBe "2xy\n"
    }

    "a bare arrow naming the type's parameter, on a member that declares one of its own" in {
      run(
        """struct Pair[A, B]
          |    a: A
          |    b: B
          |    arrow[R](self, f: A -> R) -> R = f(self.a)
          |print(Pair(2, "xy").arrow(x -> x * 10))""".stripMargin,
      ) shouldBe "20\n"
    }

    "a bare arrow naming it, on a member that declares none" in {
      run(
        """struct Gen[T]
          |    v: T
          |    same(self, f: T -> T) -> T = f(self.v)
          |print(Gen(4).same(n -> n * 2))""".stripMargin,
      ) shouldBe "8\n"
    }

    "the map shape it was all for" in {
      run(
        """import sysl.buf.{Buf, buf}
          |struct Seq[T]
          |    items: Buf[T]
          |    map[U](self, f: &Fn(T) -> U) -> []U
          |        var b: Buf[U] = buf()
          |        for i in 0..<self.items.len()
          |            b.push(f(self.items[i]))
          |        b.view()
          |    end map
          |end Seq
          |var names: Buf[string] = buf()
          |names.push("ab")
          |names.push("c")
          |val lens = Seq(names).map(s -> s.len)
          |print(lens[0], lens[1])""".stripMargin,
      ) shouldBe "2 1\n"
    }
  }

  "and what the receiver cannot settle is still refused" - {

    "a closure at a parameter naming the member's own unsolved parameter" in {
      err(
        """struct Box[T]
          |    v: T
          |    pick[U](self, f: &Fn(U) -> int) -> int = 0
          |print(Box(3).pick(n -> 1))""".stripMargin,
      ) should include("'n' has no type here")
    }

    /** The advice is an ellipsis and not a `T`. This is the call it fires at, and the receiver's
      * type parameter here is *called* `T` — so advising the reader to write one would name
      * something that means nothing where they are standing.
      */
    "the refusal advises a type position rather than a name that is not in scope" in {
      val out = err(
        """struct Box[T]
          |    v: T
          |    pick[U](self, f: &Fn(U) -> int) -> int = 0
          |print(Box(3).pick(n -> 1))""".stripMargin,
      )

      out should include("'(n: …) -> …'")
      out should not include "'(n: T) -> …'"
    }

    "a bound the member's own parameter does not meet is reported, once" in {
      val out = err(
        """struct Box[T]
          |    v: T
          |    show[U: Display](self, f: &Fn(T) -> U) -> string = str(f(self.v))
          |struct Opaque
          |    n: int
          |end Opaque
          |print(Box(3).show(n -> Opaque(n)))""".stripMargin,
      )

      out should include("requires its type parameter 'U' to implement")
      out.linesIterator.count(_.contains("requires its type parameter")) shouldBe 1
    }
  }

  /** A member that declares type parameters of its own, required by a **trait**.
   *
   * No table slot can hold one — it is not a function until a call names its types — but that is a
   * fact about the member, not about the trait it sits in. So the member is left out of the table,
   * the object still forms and dispatches everything else, and what is refused is the one call the
   * table cannot carry. A bound reaches it, because there the type is known.
   */
  "a trait may require a member with type parameters of its own" - {

    "an implementation supplies it and a call reaches it" in {
      run(
        """trait Applies[T]
          |    apply[U](self, f: &Fn(T) -> U) -> U
          |struct Holder
          |    v: int
          |impl Applies[int] for Holder
          |    apply[U](self, f: &Fn(int) -> U) -> U = f(self.v)
          |print(Holder(3).apply(n -> n + 1))""".stripMargin,
      ) shouldBe "4\n"
    }

    "the implementation may spell the parameter with its own letter" in {
      run(
        """trait Applies[T]
          |    apply[U](self, f: &Fn(T) -> U) -> U
          |struct Holder
          |    v: int
          |impl Applies[int] for Holder
          |    apply[V](self, f: &Fn(int) -> V) -> V = f(self.v)
          |print(Holder(3).apply(n -> s"<${n}>"))""".stripMargin,
      ) shouldBe "<3>\n"
    }

    "a bound reaches it, and solves the member's own parameter at the abstract call" in {
      run(
        """trait Applies[T]
          |    apply[U](self, f: &Fn(T) -> U) -> U
          |struct Holder
          |    v: int
          |impl Applies[int] for Holder
          |    apply[U](self, f: &Fn(int) -> U) -> U = f(self.v)
          |twice[S: Applies[int]](s: S) -> int = s.apply(n -> n * 2)
          |print(twice(Holder(21)))""".stripMargin,
      ) shouldBe "42\n"
    }

    "one bound body serves two instantiations of the member" in {
      run(
        """trait Applies[T]
          |    apply[U](self, f: &Fn(T) -> U) -> U
          |struct Holder
          |    v: int
          |impl Applies[int] for Holder
          |    apply[U](self, f: &Fn(int) -> U) -> U = f(self.v)
          |shown[S: Applies[int]](s: S) -> string = s.apply(n -> s"<${n}>")
          |doubled[S: Applies[int]](s: S) -> int = s.apply(n -> n * 2)
          |print(shown(Holder(3)), doubled(Holder(3)))""".stripMargin,
      ) shouldBe "<3> 6\n"
    }

    /** The whole point of the card: a `map` on a built-in slice, which needs a trait because a
      * slice has no inherent members, and needs this because `map`'s result type is the call's.
      */
    "a map on a plain slice, which is what none of this was possible without" in {
      run(
        """import sysl.buf.{Buf, buf}
          |trait Sequence[T]
          |    map[U](self, f: &Fn(T) -> U) -> []U
          |impl[A] Sequence[A] for []const A
          |    map[U](self, f: &Fn(A) -> U) -> []U
          |        var b: Buf[U] = buf()
          |        for i in 0..<self.len
          |            b.push(f(self[i]))
          |        b.view()
          |val a = [1, 2, 3]
          |val doubled = a[..].map(n -> n * 2)
          |val shown = a[..].map(n -> s"<${n}>")
          |print(doubled[0], doubled[2])
          |print(shown[0], shown[2])""".stripMargin,
      ) shouldBe "2 6\n<1> <3>\n"
    }
  }

  "the trait keeps its object, and the member is what is left out of the table" - {

    "the object forms, and its other members dispatch" in {
      run(
        """trait Applies[T]
          |    describe(self) -> string
          |    apply[U](self, f: &Fn(T) -> U) -> U
          |struct Holder
          |    v: int
          |impl Applies[int] for Holder
          |    describe(self) -> string = "holder"
          |    apply[U](self, f: &Fn(int) -> U) -> U = f(self.v)
          |val o: &Applies[int] = Holder(3)
          |print(o.describe())""".stripMargin,
      ) shouldBe "holder\n"
    }

    "the slot the table did not hold is the one refused, and it says which" in {
      val out = err(
        """trait Applies[T]
          |    describe(self) -> string
          |    apply[U](self, f: &Fn(T) -> U) -> U
          |struct Holder
          |    v: int
          |impl Applies[int] for Holder
          |    describe(self) -> string = "holder"
          |    apply[U](self, f: &Fn(int) -> U) -> U = f(self.v)
          |val o: &Applies[int] = Holder(3)
          |print(o.apply(n -> n + 1))""".stripMargin,
      )

      out should include("'apply' of 'Applies' declares type parameters of its own")
      out should include("reach it through a bound")
    }

    /** A bound licenses every member and an object reaches only its table, so the two lists have to
      * be the same for an object to stand at one. This is the one case where they are not.
      */
    "an object does not stand at a bound on a trait with such a member" in {
      val out = err(
        """trait Applies[T]
          |    apply[U](self, f: &Fn(T) -> U) -> U
          |struct Holder
          |    v: int
          |impl Applies[int] for Holder
          |    apply[U](self, f: &Fn(int) -> U) -> U = f(self.v)
          |twice[S: Applies[int]](s: S) -> int = s.apply(n -> n * 2)
          |val o: &Applies[int] = Holder(21)
          |print(twice(o))""".stripMargin,
      )

      out should include("a bound promises every member")
      out should include("'apply' declares type parameters of its own")
    }

    "a trait with no such member keeps standing at its own bound" in {
      run(
        """trait Named
          |    describe(self) -> string
          |struct Holder
          |    v: int
          |impl Named for Holder
          |    describe(self) -> string = "holder"
          |tell[S: Named](s: S) -> string = s.describe()
          |val o: &Named = Holder(3)
          |print(tell(o))""".stripMargin,
      ) shouldBe "holder\n"
    }
  }

  "and the shape still has to match the trait's" - {

    "an implementation declaring no parameter of its own where the trait declares one" in {
      err(
        """trait Applies[T]
          |    apply[U](self, f: &Fn(T) -> U) -> U
          |struct Holder
          |    v: int
          |impl Applies[int] for Holder
          |    apply(self, f: &Fn(int) -> int) -> int = f(self.v)""".stripMargin,
      ) should include("declares 0 type parameters, but trait 'Applies' declares 1")
    }

    "an implementation whose signature disagrees once the parameters are lined up" in {
      err(
        """trait Applies[T]
          |    apply[U](self, f: &Fn(T) -> U) -> U
          |struct Holder
          |    v: int
          |impl Applies[int] for Holder
          |    apply[U](self, f: &Fn(int) -> U) -> int = 0""".stripMargin,
      ) should include("but trait 'Applies' declares")
    }
  }
}
