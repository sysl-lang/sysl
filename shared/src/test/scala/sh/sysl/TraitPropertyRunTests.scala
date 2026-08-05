package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Tier-2 runtime behaviour of a property declared in a trait (`02`).
 *
 * A property is a member with no parameter list and an implicit receiver, read as `value.name` with
 * no parentheses (`08`). A trait may now ask for one, and the point of these is that asking is the
 * *only* thing that was missing: the read reaches an implementation through a bound and through a
 * trait object's table, a trait may supply a default one, and none of that needed a mechanism of its
 * own — a property carries the receiver it never spells, so it is the instance member it always was.
 */
class TraitPropertyRunTests extends AnyFreeSpec with RunSupport {

  "an impl supplies one" - {
    "a property a trait asks for is read on a value" in {
      val src =
        """trait Sized
          |    size -> int
          |struct Box
          |    n: int
          |impl Sized for Box
          |    size -> int = self.n * 2
          |print(Box(4).size)""".stripMargin

      run(src) shouldBe "8\n"
    }

    // The trait's promise is what a bound spends, so a type implementing it has to be usable through
    // both spellings — the read on a concrete value above, and the read on a parameter below.
    "two types implementing one property each read their own" in {
      val src =
        """trait Named
          |    label -> string
          |struct Cat
          |    n: string
          |struct Dog
          |    n: string
          |impl Named for Cat
          |    label -> string = "cat " + self.n
          |impl Named for Dog
          |    label -> string = "dog " + self.n
          |print(Cat("mog").label)
          |print(Dog("rex").label)""".stripMargin

      run(src) shouldBe "cat mog\ndog rex\n"
    }

    "a property may sit alongside the methods a trait declares" in {
      val src =
        """trait Shape
          |    area -> int
          |    scaled(self, k: int) -> int = self.area * k
          |struct Sq
          |    w: int
          |impl Shape for Sq
          |    area -> int = self.w * self.w
          |var s = Sq(3)
          |print(s.area, s.scaled(2))""".stripMargin

      run(src) shouldBe "9 18\n"
    }

    // A built-in has no module to write an `impl` in, so its memberships are the trait's to license
    // — and a property is filed under its owner key exactly as a method is.
    "a built-in may implement a property" in {
      val src =
        """trait Doubled
          |    twice -> int
          |impl Doubled for int
          |    twice -> int = self * 2
          |print(21.twice)""".stripMargin

      run(src) shouldBe "42\n"
    }

    // An enum's members are filed under its own name exactly as a struct's are, and the property's
    // receiver is the value — which the inherent method it calls is there to show.
    "an enum may implement a property" in {
      val src =
        """trait Sized
          |    size -> int
          |enum Shape
          |    Dot
          |    Line
          |    tick(self) -> int = 3
          |impl Sized for Shape
          |    size -> int = self.tick() * 2
          |print(Dot.size, Line.size)""".stripMargin

      run(src) shouldBe "6 6\n"
    }
  }

  "a bound reads one" - {
    // The whole point of declaring a property in a trait: a generic body may read it, checked once at
    // the definition against the trait rather than at each type that turns up.
    "a bounded generic reads a property off its parameter" in {
      val src =
        """trait Sized
          |    size -> int
          |struct Box
          |    n: int
          |struct Bag
          |    k: int
          |impl Sized for Box
          |    size -> int = self.n
          |impl Sized for Bag
          |    size -> int = self.k * 10
          |total[T: Sized](a: T, b: T) -> int = a.size + b.size
          |print(total(Box(3), Box(4)))
          |print(total(Bag(3), Bag(4)))""".stripMargin

      run(src) shouldBe "7\n70\n"
    }

    "a property read mixes with a method call in one bounded body" in {
      val src =
        """trait Sized
          |    size -> int
          |    grow(self, k: int) -> int
          |struct Box
          |    n: int
          |impl Sized for Box
          |    size -> int = self.n
          |    grow(self, k: int) -> int = self.n + k
          |both[T: Sized](x: T) -> int = x.grow(x.size)
          |print(both(Box(6)))""".stripMargin

      run(src) shouldBe "12\n"
    }

    "a trait default may read a property the same trait declares" in {
      val src =
        """trait Sized
          |    size -> int
          |    doubled -> int = self.size * 2
          |struct Box
          |    n: int
          |impl Sized for Box
          |    size -> int = self.n
          |print(Box(5).doubled)""".stripMargin

      run(src) shouldBe "10\n"
    }

    // The bound looks the property up in the trait, which is where a default lives — so a generic
    // body reads one whether or not the type it is instantiated at wrote its own.
    "a bounded generic reads a default property off a type that inherited it" in {
      val src =
        """trait Tagged
          |    tag -> string = "none"
          |struct A
          |    n: int
          |struct B
          |    n: int
          |impl Tagged for A
          |impl Tagged for B
          |    tag -> string = "bee"
          |name[T: Tagged](x: T) -> string = x.tag
          |print(name(A(1)))
          |print(name(B(1)))""".stripMargin

      run(src) shouldBe "none\nbee\n"
    }

    "a trait may supply a default property outright, leaving an impl nothing to write" in {
      val src =
        """trait Tagged
          |    tag -> string = "none"
          |struct A
          |    n: int
          |struct B
          |    n: int
          |impl Tagged for A
          |impl Tagged for B
          |    tag -> string = "bee"
          |print(A(1).tag)
          |print(B(1).tag)""".stripMargin

      run(src) shouldBe "none\nbee\n"
    }
  }

  "an object dispatches one" - {
    "a raw trait object reads a property through its table" in {
      val src =
        """trait Sized
          |    size -> int
          |struct Box
          |    n: int
          |struct Bag
          |    k: int
          |impl Sized for Box
          |    size -> int = self.n
          |impl Sized for Bag
          |    size -> int = self.k * 10
          |show(s: *Sized) = print(s.size)
          |var b = Box(3)
          |var g = Bag(3)
          |show(&b)
          |show(&g)""".stripMargin

      run(src) shouldBe "3\n30\n"
    }

    "a counted trait object reads a property through its table" in {
      val src =
        """trait Sized
          |    size -> int
          |struct Box
          |    n: int
          |impl Sized for Box
          |    size -> int = self.n + 1
          |var s: &Sized = Box(9)
          |print(s.size)""".stripMargin

      run(src) shouldBe "10\n"
    }

    // The slot a property occupies is one of the trait's own, in declaration order — so a method
    // either side of it has to keep dispatching correctly, which a mis-indexed table would break.
    "a property between two methods keeps every slot straight" in {
      val src =
        """trait Full
          |    first(self) -> int
          |    middle -> int
          |    last(self) -> int
          |struct V
          |    n: int
          |impl Full for V
          |    first(self) -> int = self.n
          |    middle -> int = self.n * 2
          |    last(self) -> int = self.n * 3
          |show(f: *Full) = print(f.first(), f.middle, f.last())
          |var v = V(5)
          |show(&v)""".stripMargin

      run(src) shouldBe "5 10 15\n"
    }

    "an object dispatches to an inherited default property" in {
      val src =
        """trait Tagged
          |    tag -> string = "none"
          |struct A
          |    n: int
          |impl Tagged for A
          |var t: &Tagged = A(1)
          |print(t.tag)""".stripMargin

      run(src) shouldBe "none\n"
    }
  }

  "ownership" - {
    // A property returning a string is a fresh owned value per read, and the read through a table
    // goes through an adapter that borrows its receiver — so a loop of them must neither leak the
    // result nor release the object it was read from.
    "a property returning a string neither leaks nor frees twice" in {
      val src =
        """trait Named
          |    label -> string
          |struct Cat
          |    n: string
          |impl Named for Cat
          |    label -> string = "the " + self.n + "!"
          |var c = Cat("mog")
          |var o: *Named = &c
          |var total = 0usize
          |for i in 1..200000
          |    var s = o.label
          |    total += s.len
          |print(total, c.n.len)""".stripMargin

      run(src) shouldBe "1600000 3\n"
    }
  }
}
