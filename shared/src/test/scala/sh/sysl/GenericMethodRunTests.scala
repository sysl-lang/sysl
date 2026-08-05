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
}
