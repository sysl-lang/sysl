package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** A trait may take type parameters of its own, so what it promises is fixed by the implementation
 * rather than by the trait alone: `trait Sink[T]` is a different promise for every `T`, and an
 * `impl Sink[int] for A` says which one an `A` makes.
 *
 * The three places a trait is named all take the arguments the same way — a bound (`[X: Sink[int]]`),
 * an implementation (`impl Sink[int] for A`), and a trait object (`&Sink[int]`) — and these tests
 * run programs through each of them.
 */
class GenericTraitRunTests extends AnyFreeSpec with RunSupport with CodegenSupport {

  "an implementation says which promise it makes" - {
    "a method takes the type the implementation fixed" in {
      run(
        """trait Sink[T]
          |    put(self, x: T) -> string
          |struct A
          |    tag: int
          |impl Sink[int] for A
          |    put(self, x: int) -> string = f"A${x}"
          |print(A(0).put(3))""".stripMargin,
      ) shouldBe "A3\n"
    }

    "two types implement one trait at two different arguments" in {
      run(
        """trait Sink[T]
          |    put(self, x: T) -> string
          |struct A
          |    tag: int
          |struct B
          |    tag: int
          |impl Sink[int] for A
          |    put(self, x: int) -> string = f"A${x}"
          |impl Sink[string] for B
          |    put(self, x: string) -> string = f"B${x}"
          |print(A(0).put(3))
          |print(B(0).put("z"))""".stripMargin,
      ) shouldBe "A3\nBz\n"
    }

    "a trait takes more than one parameter" in {
      run(
        """trait Pairing[A, B]
          |    left(self) -> A
          |    right(self) -> B
          |struct P
          |    n: int
          |impl Pairing[int, string] for P
          |    left(self) -> int = self.n
          |    right(self) -> string = "r"
          |var p = P(4)
          |print(p.left())
          |print(p.right())""".stripMargin,
      ) shouldBe "4\nr\n"
    }

    "the argument may itself be a generic type" in {
      run(
        """trait Get[T]
          |    get(self) -> T
          |struct Box[U]
          |    v: U
          |struct W
          |    tag: int
          |impl Get[Box[int]] for W
          |    get(self) -> Box[int] = Box(11)
          |print(W(0).get().v)""".stripMargin,
      ) shouldBe "11\n"
    }

    "a member may write its signature in the trait's own parameter name" in {
      run(
        """trait Tag[T]
          |    tag(self, x: T) -> T
          |struct C
          |    v: int
          |impl Tag[int] for C
          |    tag(self, x: T) -> T = x + 1
          |print(C(0).tag(4))""".stripMargin,
      ) shouldBe "5\n"
    }

    "a property is asked for at the implementation's argument" in {
      run(
        """trait Sized[T]
          |    size -> T
          |struct S
          |    v: int
          |impl Sized[int] for S
          |    size -> int = 3
          |print(S(0).size)""".stripMargin,
      ) shouldBe "3\n"
    }
  }

  "a bound names the trait at the arguments it wants" - {
    "a bounded body calls the method at those arguments" in {
      run(
        """trait Into[T]
          |    into(self) -> T
          |struct P
          |    v: int
          |impl Into[int] for P
          |    into(self) -> int = self.v
          |take[X: Into[int]](x: X) -> int = x.into()
          |print(take(P(7)))""".stripMargin,
      ) shouldBe "7\n"
    }

    "a bounded body reads a property through one" in {
      run(
        """trait Sized[T]
          |    size -> T
          |struct S
          |    v: int
          |impl Sized[int] for S
          |    size -> int = 3
          |big[X: Sized[int]](x: X) -> int = x.size + 1
          |print(big(S(0)))""".stripMargin,
      ) shouldBe "4\n"
    }

    "one of the declaration's own parameters may be the trait's argument" in {
      run(
        """trait Into[T]
          |    into(self) -> T
          |struct P
          |    v: int
          |impl Into[int] for P
          |    into(self) -> int = self.v
          |conv[X: Into[Y], Y](x: X) -> Y = x.into()
          |var n: int = conv(P(5))
          |print(n)""".stripMargin,
      ) shouldBe "5\n"
    }

    "what a bound's argument yields carries its own bounds" in {
      run(
        """trait Into[T]
          |    into(self) -> T
          |struct P
          |    v: int
          |impl Into[int] for P
          |    into(self) -> int = self.v
          |show[X: Into[Y], Y: Display](x: X) -> Y
          |    var y = x.into()
          |    print(y)
          |    y
          |var n: int = show(P(6))
          |print(n)""".stripMargin,
      ) shouldBe "6\n6\n"
    }

    "a type's own parameters may be bounded by a generic trait" in {
      run(
        """trait Into[T]
          |    into(self) -> T
          |struct P
          |    v: int
          |impl Into[int] for P
          |    into(self) -> int = self.v
          |struct Holder[X: Into[int]]
          |    item: X
          |    number(self) -> int = self.item.into()
          |print(Holder(P(8)).number())""".stripMargin,
      ) shouldBe "8\n"
    }
  }

  "a trait object carries the arguments too" - {
    "a method dispatches through the table at the implementation's argument" in {
      run(
        """trait Sink[T]
          |    put(self, x: T) -> string
          |struct A
          |    tag: int
          |impl Sink[int] for A
          |    put(self, x: int) -> string = f"A${x}"
          |var u: &Sink[int] = A(0)
          |print(u.put(1))""".stripMargin,
      ) shouldBe "A1\n"
    }

    "two objects at two arguments live side by side" in {
      run(
        """trait Sink[T]
          |    put(self, x: T) -> string
          |struct A
          |    tag: int
          |struct B
          |    tag: int
          |impl Sink[int] for A
          |    put(self, x: int) -> string = f"A${x}"
          |impl Sink[string] for B
          |    put(self, x: string) -> string = f"B${x}"
          |var u: &Sink[int] = A(0)
          |var v: &Sink[string] = B(0)
          |print(u.put(1))
          |print(v.put("q"))""".stripMargin,
      ) shouldBe "A1\nBq\n"
    }

    "a property is read through the table" in {
      run(
        """trait Sized[T]
          |    size -> T
          |struct S
          |    v: int
          |impl Sized[int] for S
          |    size -> int = 3
          |var o: &Sized[int] = S(0)
          |print(o.size)""".stripMargin,
      ) shouldBe "3\n"
    }

    "an object over a generic trait in a loop neither leaks nor frees twice" in {
      run(
        """trait Sink[T]
          |    put(self, x: T) -> int
          |struct A
          |    base: int
          |impl Sink[int] for A
          |    put(self, x: int) -> int = self.base + x
          |var total = 0
          |for i in 0..<100
          |    var s: &Sink[int] = A(i)
          |    total = total + s.put(1)
          |print(total)""".stripMargin,
      ) shouldBe "5050\n"
    }
  }

  "a default answers as well as a signature does" - {
    "a default body calls the trait's own method" in {
      run(
        """trait Tag[T]
          |    tag(self) -> T
          |    twice(self) -> T = self.tag()
          |struct Q
          |    v: int
          |impl Tag[int] for Q
          |    tag(self) -> int = self.v
          |print(Q(9).twice())""".stripMargin,
      ) shouldBe "9\n"
    }

    "a default's parameters are the trait's, at the implementation's arguments" in {
      run(
        """trait Tag[T]
          |    tag(self) -> T
          |    pick(self, other: T) -> T = other
          |struct Q
          |    v: int
          |impl Tag[int] for Q
          |    tag(self) -> int = self.v
          |print(Q(9).pick(2))""".stripMargin,
      ) shouldBe "2\n"
    }

    "a generic type inherits one" in {
      run(
        """trait Tag[T]
          |    tag(self) -> T
          |    twice(self) -> T = self.tag()
          |struct Box[U]
          |    v: U
          |impl[U] Tag[int] for Box[U]
          |    tag(self) -> int = 5
          |print(Box("x").twice())
          |print(Box(1).twice())""".stripMargin,
      ) shouldBe "5\n5\n"
    }
  }

  "every kind of subject may implement one" - {
    "a generic type, as a whole" in {
      run(
        """trait Get[T]
          |    get(self) -> T
          |struct Box[U]
          |    v: U
          |impl[U] Get[int] for Box[U]
          |    get(self) -> int = 7
          |print(Box("x").get())""".stripMargin,
      ) shouldBe "7\n"
    }

    "a composed shape" in {
      run(
        """trait Count[T]
          |    howMany(self) -> T
          |impl[E] Count[int] for []E
          |    howMany(self) -> int = int(self.len)
          |var a = [1, 2, 3]
          |print(a[0..<3].howMany())""".stripMargin,
      ) shouldBe "3\n"
    }

    "a built-in" in {
      run(
        """trait Widen[T]
          |    wide(self) -> T
          |impl Widen[long] for int
          |    wide(self) -> long = long(self) + long(self)
          |var n = 21
          |print(n.wide())""".stripMargin,
      ) shouldBe "42\n"
    }

    "a generic type conditionally, on a bound of its own" in {
      run(
        """trait Show
          |    show(self) -> int
          |trait Get[T]
          |    get(self) -> T
          |struct Box[U]
          |    v: U
          |impl[U: Show] Get[int] for Box[U]
          |    get(self) -> int = self.v.show()
          |struct P
          |    v: int
          |impl Show for P
          |    show(self) -> int = self.v
          |print(Box(P(3)).get())""".stripMargin,
      ) shouldBe "3\n"
    }

    "'Self' and the trait's parameter stand side by side" in {
      run(
        """trait Dup[T]
          |    dup(self) -> Self
          |    key(self) -> T
          |struct Box[U]
          |    v: U
          |impl[U] Dup[int] for Box[U]
          |    dup(self) -> Self = self
          |    key(self) -> T = 8
          |print(Box(2).dup().key())""".stripMargin,
      ) shouldBe "8\n"
    }
  }

  "what the arguments reach in the emitted module" - {
    "a table is named for the trait at its arguments" in {
      ir(
        """trait Sink[T]
          |    put(self, x: T) -> int
          |struct A
          |    tag: int
          |impl Sink[int] for A
          |    put(self, x: int) -> int = x
          |var u: &Sink[int] = A(0)
          |print(u.put(1))""".stripMargin,
      ) should include("@vt.ref.Sink.int.A = private constant")
    }

    "the members themselves are named for the type alone, since a type implements a trait once" in {
      val out = ir(
        """trait Sink[T]
          |    put(self, x: T) -> int
          |struct A
          |    tag: int
          |impl Sink[int] for A
          |    put(self, x: int) -> int = x
          |print(A(0).put(1))""".stripMargin,
      )

      out should include("define i32 @A.put(")
      out.linesIterator.count(_.startsWith("define i32 @A.put(")) shouldBe 1
    }

    "one implementation per generic type, whatever its arguments" in {
      val out = ir(
        """trait Get[T]
          |    get(self) -> T
          |struct Box[U]
          |    v: U
          |impl[U] Get[int] for Box[U]
          |    get(self) -> int = 7
          |print(Box(1).get())
          |print(Box("x").get())""".stripMargin,
      )

      out.linesIterator.count(_.startsWith("define i32 @Box.")) shouldBe 2
    }
  }
}
