package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Tier-2 runtime behavior of trait defaults (`02`). A trait method written with a body is a
 * definition every `impl` inherits unless it writes its own, and what it may assume of its
 * receiver is exactly what the trait declares.
 *
 * The default is materialized per implementing type, so these confirm the thing that materializing
 * is *for*: two types inheriting one default each run it against their own methods, and a type that
 * overrides it runs its own body instead.
 */
class TraitDefaultRunTests extends AnyFreeSpec with RunSupport {

  "inheriting" - {
    "an impl may leave out a method the trait supplies a body for" in {
      val src =
        """trait Greet
          |    name(self) -> string
          |    greet(self) -> string = "hello, " + self.name()
          |struct Cat
          |    n: string
          |impl Greet for Cat
          |    name(self) -> string = self.n
          |var c = Cat("mog")
          |print(c.greet())""".stripMargin

      run(src) shouldBe "hello, mog\n"
    }

    // The default is one body, but it runs against each type's own required method — so the two
    // results have to differ, which a single shared function could not produce.
    "two types inheriting one default each reach their own method" in {
      val src =
        """trait Greet
          |    name(self) -> string
          |    greet(self) -> string = "hello, " + self.name()
          |struct Cat
          |    n: string
          |struct Dog
          |    n: string
          |impl Greet for Cat
          |    name(self) -> string = self.n
          |impl Greet for Dog
          |    name(self) -> string = self.n + " the dog"
          |var c = Cat("mog")
          |var d = Dog("rex")
          |print(c.greet())
          |print(d.greet())""".stripMargin

      run(src) shouldBe "hello, mog\nhello, rex the dog\n"
    }

    "an impl that writes the method itself runs its own body" in {
      val src =
        """trait Greet
          |    name(self) -> string
          |    greet(self) -> string = "hello, " + self.name()
          |struct Cat
          |    n: string
          |struct Dog
          |    n: string
          |impl Greet for Cat
          |    name(self) -> string = self.n
          |impl Greet for Dog
          |    name(self) -> string = self.n
          |    override greet(self) -> string = "woof from " + self.n
          |var c = Cat("mog")
          |var d = Dog("rex")
          |print(c.greet())
          |print(d.greet())""".stripMargin

      run(src) shouldBe "hello, mog\nwoof from rex\n"
    }

    "a default takes parameters and returns a value like any other method" in {
      val src =
        """trait Scale
          |    unit(self) -> int
          |    by(self, k: int, plus: int) -> int = self.unit() * k + plus
          |struct Box
          |    n: int
          |impl Scale for Box
          |    unit(self) -> int = self.n
          |var b = Box(7)
          |print(b.by(3, 2))""".stripMargin

      run(src) shouldBe "23\n"
    }

    "a default may be an indented block with locals and control flow" in {
      val src =
        """trait Steps
          |    limit(self) -> int
          |    total(self) -> int
          |        var sum = 0
          |        for i in 1..self.limit() do sum += i
          |        sum
          |struct N
          |    k: int
          |impl Steps for N
          |    limit(self) -> int = self.k
          |print(N(4).total(), N(1).total(), N(0).total())""".stripMargin

      run(src) shouldBe "10 1 0\n"
    }

    // A default calling a default is the case that says the materialized copies are wired to each
    // other and not to the trait's abstract form, which has no body to run.
    "a default may call another default" in {
      val src =
        """trait Chain
          |    base(self) -> int
          |    twice(self) -> int = self.base() * 2
          |    quad(self) -> int = self.twice() * 2
          |struct V
          |    n: int
          |impl Chain for V
          |    base(self) -> int = self.n
          |print(V(3).quad())""".stripMargin

      run(src) shouldBe "12\n"
    }

    // A trait every method of which has a default leaves a conforming type nothing to write, so
    // the block is optional — the `impl` line alone is the opt-in.
    "a trait may be all defaults, with nothing left for an impl to supply" in {
      val src =
        """trait Zero
          |    zero(self) -> int = 0
          |struct E
          |    n: int
          |impl Zero for E
          |print(E(9).zero())""".stripMargin

      run(src) shouldBe "0\n"
    }

    "an impl with no block still opts in, so a bound accepts the type" in {
      val src =
        """trait Zero
          |    zero(self) -> int = 0
          |struct E
          |    n: int
          |impl Zero for E
          |end E
          |nothing[T: Zero](x: T) -> int = x.zero()
          |print(nothing(E(9)))""".stripMargin

      run(src) shouldBe "0\n"
    }
  }

  "receivers" - {
    // A default reaches its receiver's *methods* and never its layout, so mutating through `*self`
    // goes through one the trait declares — which is also what lets one default serve two types
    // that store the value differently.
    "a '*self' default mutates through a method the trait declares" in {
      val src =
        """trait Bump
          |    step(self) -> int
          |    add(*self, k: int)
          |    bump(*self) = self.add(self.step())
          |struct C
          |    n: int
          |impl Bump for C
          |    step(self) -> int = 5
          |    add(*self, k: int)
          |        self.n += k
          |var c = C(1)
          |c.bump()
          |c.bump()
          |print(c.n)""".stripMargin

      run(src) shouldBe "11\n"
    }

    "a '&self' default reads through the reference" in {
      val src =
        """trait Sized
          |    width(&self) -> int
          |    area(&self) -> int = self.width() * self.width()
          |struct S
          |    w: int
          |impl Sized for S
          |    width(&self) -> int = self.w
          |var s: &S = S(6)
          |print(s.area())""".stripMargin

      run(src) shouldBe "36\n"
    }
  }

  "reaching a default" - {
    // A bound looks the method up in the trait, which is where the default lives — so a generic
    // body may call one whether or not the type it is instantiated at wrote its own.
    "a bounded generic calls a default on a type that inherited it" in {
      val src =
        """trait Greet
          |    name(self) -> string
          |    greet(self) -> string = "hi " + self.name()
          |struct Cat
          |    n: string
          |struct Dog
          |    n: string
          |impl Greet for Cat
          |    name(self) -> string = self.n
          |impl Greet for Dog
          |    name(self) -> string = self.n
          |    override greet(self) -> string = "yo " + self.n
          |announce[T: Greet](x: T) -> string = x.greet()
          |print(announce(Cat("mog")))
          |print(announce(Dog("rex")))""".stripMargin

      run(src) shouldBe "hi mog\nyo rex\n"
    }

    "a raw trait object dispatches to an inherited default" in {
      val src =
        """trait Greet
          |    name(self) -> string
          |    greet(self) -> string = "hi " + self.name()
          |struct Cat
          |    n: string
          |struct Dog
          |    n: string
          |impl Greet for Cat
          |    name(self) -> string = self.n
          |impl Greet for Dog
          |    name(self) -> string = self.n
          |    override greet(self) -> string = "yo " + self.n
          |say(g: *Greet) = print(g.greet())
          |var c = Cat("mog")
          |var d = Dog("rex")
          |say(&c)
          |say(&d)""".stripMargin

      run(src) shouldBe "hi mog\nyo rex\n"
    }

    "a counted trait object dispatches to an inherited default" in {
      val src =
        """trait Greet
          |    name(self) -> string
          |    greet(self) -> string = "hi " + self.name()
          |struct Cat
          |    n: string
          |impl Greet for Cat
          |    name(self) -> string = self.n
          |var g: &Greet = Cat("mog")
          |print(g.greet())""".stripMargin

      run(src) shouldBe "hi mog\n"
    }

    // A built-in has no module to write an `impl` in, so its memberships are the trait's to license
    // — and a default is materialized for one exactly as it is for a struct.
    "a built-in may inherit a default" in {
      val src =
        """trait Greet
          |    name(self) -> string
          |    greet(self) -> string = "hi " + self.name()
          |impl Greet for int
          |    name(self) -> string = str(self)
          |print(7.greet())""".stripMargin

      run(src) shouldBe "hi 7\n"
    }

    "a default may return Self" in {
      val src =
        """trait Dup
          |    scale(self, k: int) -> Self
          |    twice(self) -> Self = self.scale(2)
          |struct V
          |    n: int
          |impl Dup for V
          |    scale(self, k: int) -> Self = V(self.n * k)
          |print(V(5).twice().n)""".stripMargin

      run(src) shouldBe "10\n"
    }

    // Declarations are hoisted, so the trait a default lives in may be written after the `impl`
    // that inherits it.
    "an impl may precede the trait whose default it inherits" in {
      val src =
        """struct Cat
          |    n: string
          |impl Greet for Cat
          |    name(self) -> string = self.n
          |trait Greet
          |    name(self) -> string
          |    greet(self) -> string = "hi " + self.name()
          |print(Cat("mog").greet())""".stripMargin

      run(src) shouldBe "hi mog\n"
    }

    "a default reaches a type's own inherent members, not just the trait's" in {
      val src =
        """trait Total
          |    each(self) -> int
          |    total(self) -> int = self.each() * 10
          |struct P
          |    n: int
          |    doubled -> int = self.n * 2
          |impl Total for P
          |    each(self) -> int = self.doubled
          |print(P(3).total())""".stripMargin

      run(src) shouldBe "60\n"
    }
  }

  "ownership" - {
    // A default that builds a string is a fresh owned value per call, and the materialized copy
    // carries the same ARC work a written method does — so a loop of them must not leak or
    // double-free.
    "a default returning a string neither leaks nor frees twice" in {
      val src =
        """trait Greet
          |    name(self) -> string
          |    greet(self) -> string = "hello, " + self.name() + "!"
          |struct Cat
          |    n: string
          |impl Greet for Cat
          |    name(self) -> string = self.n
          |var c = Cat("mog")
          |var total = 0usize
          |for i in 1..200000
          |    var s = c.greet()
          |    total += s.len
          |print(total)""".stripMargin

      run(src) shouldBe "2200000\n"
    }

    "a default may render its receiver through Display" in {
      val src =
        """trait Label
          |    tag(self) -> int
          |    label(self) -> string = "#" + str(self.tag())
          |struct T
          |    n: int
          |impl Label for T
          |    tag(self) -> int = self.n
          |print(T(42).label())""".stripMargin

      run(src) shouldBe "#42\n"
    }
  }
}
