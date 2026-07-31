package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Tier-2 runtime behavior of traits. An `impl` method lowers to the same member function a
 * method written in the type's body would, so a trait call runs like any other member call;
 * these tests confirm each receiver mode, several traits and types together, and that
 * declaration order does not matter.
 */
class TraitRunTests extends AnyFreeSpec with RunSupport {

  "a value-receiver trait method runs on the implementing type" in {
    val src =
      """trait Area
        |    area(self) -> int
        |struct Rect
        |    w: int
        |    h: int
        |impl Area for Rect
        |    area(self) -> int = self.w * self.h
        |var r = Rect(3, 4)
        |print(r.area())""".stripMargin

    run(src) shouldBe "12\n"
  }

  // Two types implement one trait; the method called on each is that type's own impl, so the two
  // results must differ — a coincidence of names could not produce 12 and 30.
  "two types implementing one trait each dispatch to their own impl" in {
    val src =
      """trait Area
        |    area(self) -> int
        |struct Rect
        |    w: int
        |    h: int
        |struct Box
        |    w: int
        |    h: int
        |    d: int
        |impl Area for Rect
        |    area(self) -> int = self.w * self.h
        |impl Area for Box
        |    area(self) -> int = 2 * (self.w * self.h + self.w * self.d + self.h * self.d)
        |var r = Rect(3, 4)
        |var b = Box(1, 2, 3)
        |print(r.area(), b.area())""".stripMargin

    run(src) shouldBe "12 22\n"
  }

  // One type implements two different traits; both methods are reachable on the same value and
  // read different fields, so neither can be standing in for the other.
  "one type implementing two traits exposes both methods" in {
    val src =
      """trait Named
        |    name(self) -> string
        |trait Aged
        |    age(self) -> int
        |struct Person
        |    who: string
        |    years: int
        |impl Named for Person
        |    name(self) -> string = self.who
        |impl Aged for Person
        |    age(self) -> int = self.years
        |var p = Person("Ada", 36)
        |print(p.name())
        |print(p.age())""".stripMargin

    run(src) shouldBe "Ada\n36\n"
  }

  "a pointer-receiver trait method mutates the instance in place" in {
    val src =
      """trait Bumpable
        |    bump(*self, by: int)
        |struct Counter
        |    n: int
        |impl Bumpable for Counter
        |    bump(*self, by: int)
        |        self.n += by
        |var c = Counter(10)
        |c.bump(5)
        |c.bump(7)
        |print(c.n)""".stripMargin

    run(src) shouldBe "22\n"
  }

  "a reference-receiver trait method mutates the shared heap object" in {
    val src =
      """trait Ticker
        |    tick(&self)
        |    value(self) -> int
        |struct Clock
        |    t: int
        |impl Ticker for Clock
        |    tick(&self)
        |        self.t += 1
        |    value(self) -> int = self.t
        |var c: &Clock = Clock(0)
        |c.tick()
        |c.tick()
        |c.tick()
        |print(c.value())""".stripMargin

    run(src) shouldBe "3\n"
  }

  // A trait method takes an ordinary parameter alongside self, and its body calls another impl
  // method on self — so the lowered function resolves a further member call, not just field reads.
  "a trait method takes parameters and calls another method on self" in {
    val src =
      """trait Scale
        |    unit(self) -> int
        |    scaled(self, factor: int) -> int
        |struct Ruler
        |    base: int
        |impl Scale for Ruler
        |    unit(self) -> int = self.base
        |    scaled(self, factor: int) -> int = self.unit() * factor
        |var r = Ruler(7)
        |print(r.scaled(6))""".stripMargin

    run(src) shouldBe "42\n"
  }

  // Declarations are hoisted, so an impl written before either the trait or the struct it names
  // still resolves. The value it computes proves the impl was wired up, not merely parsed.
  "an impl may appear before the trait and struct it names" in {
    val src =
      """impl Doubler for Cell
        |    double(self) -> int = self.v * 2
        |trait Doubler
        |    double(self) -> int
        |struct Cell
        |    v: int
        |var c = Cell(21)
        |print(c.double())""".stripMargin

    run(src) shouldBe "42\n"
  }

  // An impl method lowers to the same function a hand-written member does, so its ARC bookkeeping
  // must match: a &self trait method returning a &T field hands out the reference the receiver
  // holds, retained so it outlives the receiver dropped when extract returns. Over a long loop this
  // frees each Inner exactly once — a leak grows RSS, a double-free crashes. total = sum of i%4 = 750000.
  "a &self trait method returning a &T field neither leaks nor frees twice" in {
    val src =
      """trait Peek
        |    peek(&self) -> &Inner
        |struct Inner
        |    v: int
        |struct Outer
        |    inner: &Inner
        |impl Peek for Outer
        |    peek(&self) -> &Inner = self.inner
        |extract(seed: int) -> &Inner
        |    var o: &Outer = Outer(Inner(seed))
        |    o.peek()
        |var i = 0
        |var total = 0
        |while i < 500000
        |    var got = extract(i % 4)
        |    total += got.v
        |    i++
        |print(total)""".stripMargin

    run(src) shouldBe "750000\n"
  }

  // A free function bounded to a concrete type still calls a trait method on it — the method is an
  // ordinary member, so it works in any context a hand-written method would.
  "a trait method is callable from an ordinary function" in {
    val src =
      """trait Length
        |    length(self) -> int
        |struct Word
        |    n: int
        |impl Length for Word
        |    length(self) -> int = self.n
        |total(a: Word, b: Word) -> int = a.length() + b.length()
        |print(total(Word(3), Word(4)))""".stripMargin

    run(src) shouldBe "7\n"
  }

  // A generic bounded by a trait dispatches statically: it is monomorphized once per concrete
  // type, and each copy calls that type's own impl. Two types compute different values through the
  // same bound, so the copies are genuinely distinct, not one shared by coincidence.
  "a trait-bounded generic dispatches to each concrete type's impl" in {
    val src =
      """trait Doubler
        |    double(self) -> int
        |struct Cell
        |    v: int
        |struct Twin
        |    a: int
        |    b: int
        |impl Doubler for Cell
        |    double(self) -> int = self.v * 2
        |impl Doubler for Twin
        |    double(self) -> int = self.a + self.b
        |apply[T: Doubler](x: T) -> int = x.double()
        |print(apply(Cell(21)))
        |print(apply(Twin(10, 15)))""".stripMargin

    run(src) shouldBe "42\n25\n"
  }

  // A multi-bound parameter may use a method from either trait it names. The type implements both,
  // so both bounded generics resolve; the two results read different fields, so neither stands in
  // for the other.
  "a multi-bound generic may call methods from every trait in the bound" in {
    val src =
      """trait Named
        |    name(self) -> string
        |trait Aged
        |    age(self) -> int
        |struct Person
        |    who: string
        |    years: int
        |impl Named for Person
        |    name(self) -> string = self.who
        |impl Aged for Person
        |    age(self) -> int = self.years
        |label[T: Named + Aged](x: T) -> string = x.name()
        |tally[T: Named + Aged](x: T) -> int = x.age()
        |var p = Person("Ada", 36)
        |print(label(p))
        |print(tally(p))""".stripMargin

    run(src) shouldBe "Ada\n36\n"
  }

  // A bounded generic may forward its own type parameter to another bounded generic. When the
  // outer is monomorphized at a concrete type, the inner call is checked against that same type, so
  // the two bounds compose and the innermost impl is what finally runs.
  "a bounded generic forwards its type parameter into another bounded generic" in {
    val src =
      """trait Show
        |    show(self) -> string
        |struct Tag
        |    label: string
        |impl Show for Tag
        |    show(self) -> string = self.label
        |inner[U: Show](y: U) -> string = y.show()
        |outer[T: Show](x: T) -> string = inner(x)
        |print(outer(Tag("hi")))""".stripMargin

    run(src) shouldBe "hi\n"
  }

  // A bound threads through a reference receiver: the parameter is `&T`, T is inferred from the
  // argument's `&Clock`, and the bound's mutating `&self` method drives the shared heap object.
  "a trait-bounded generic works over a reference receiver" in {
    val src =
      """trait Ticker
        |    tick(&self)
        |    value(self) -> int
        |struct Clock
        |    t: int
        |impl Ticker for Clock
        |    tick(&self)
        |        self.t += 1
        |    value(self) -> int = self.t
        |run3[T: Ticker](c: &T) -> int
        |    c.tick()
        |    c.tick()
        |    c.tick()
        |    c.value()
        |var c: &Clock = Clock(0)
        |print(run3(c))""".stripMargin

    run(src) shouldBe "3\n"
  }

  /** A trait may be implemented for a **built-in** type, not only a struct or an enum.
   *
   * A language whose `Show` cannot cover `int` has a `Show` no library can use, so this is what
   * makes a trait worth having at all — and it is also what a library `Show` needs before `str` and
   * `print` can stop being compiler builtins. Nothing here is special-cased: every type has an owner
   * key its members are filed under, so `5.show()` takes the same path `p.show()` does.
   */
  /** A `Show` implemented for each built-in, as a preamble the tests below build on. */
  private val showBuiltins =
    """trait Show
      |    show(self) -> string
      |impl Show for int
      |    show(self) -> string = if self == 0 then "zero" else "an int"
      |impl Show for bool
      |    show(self) -> string = if self then "yes" else "no"
      |impl Show for char
      |    show(self) -> string = "a char"
      |impl Show for string
      |    show(self) -> string = self
      |impl Show for real
      |    show(self) -> string = "a real"
      |""".stripMargin

  "traits over the built-in types" - {
    "a method call resolves on a value of a built-in type" in {
      val src = s"$showBuiltins\nprint(5.show(), 0.show(), true.show(), 'x'.show(), \"me\".show(), 2.5.show())"

      run(src) shouldBe "an int zero yes a char me a real\n"
    }

    "it resolves on a variable, an expression, and a literal alike" in {
      val src = s"$showBuiltins\nvar n = 3\nprint(n.show(), (n - 3).show(), 7.show())"

      run(src) shouldBe "an int zero an int\n"
    }

    "a trait bound accepts a built-in type" in {
      val src =
        s"""$showBuiltins
           |render[T: Show](x: T) -> string = x.show()
           |print(render(1), render(true), render("s"))""".stripMargin

      run(src) shouldBe "an int yes s\n"
    }

    "a built-in and a struct satisfy one bound together" in {
      val src =
        s"""$showBuiltins
           |struct Point
           |    x: int
           |impl Show for Point
           |    show(self) -> string = "a point"
           |render[T: Show](x: T) -> string = x.show()
           |print(render(1), render(Point(2)))""".stripMargin

      run(src) shouldBe "an int a point\n"
    }

    // Distinct widths are distinct types, so each carries its own implementation and a call picks
    // the one for the receiver's actual type rather than the nearest.
    "each width is its own type with its own impl" in {
      val src =
        """trait Show
          |    show(self) -> string
          |impl Show for int
          |    show(self) -> string = "32"
          |impl Show for long
          |    show(self) -> string = "64"
          |impl Show for byte
          |    show(self) -> string = "8"
          |var a: long = 1i64
          |var b: byte = 2u8
          |print(1.show(), a.show(), b.show())""".stripMargin

      run(src) shouldBe "32 64 8\n"
    }

    "a built-in's impl method may take parameters and return anything" in {
      val src =
        """trait Repeat
          |    times(self, n: int) -> int
          |impl Repeat for int
          |    times(self, n: int) -> int = self * n
          |print(6.times(7))""".stripMargin

      run(src) shouldBe "42\n"
    }

    // Reached through a reference and through a pointer, which is the receiver-seeing-through rule
    // applying to a built-in exactly as it does to a struct.
    "it is reached through a reference and a pointer too" in {
      val src =
        s"""$showBuiltins
           |var r: &int = 5
           |var v = 6
           |var p = &v
           |print(r.show(), p.show())""".stripMargin

      run(src) shouldBe "an int an int\n"
    }

    "an impl for a built-in may appear before the trait" in {
      val src =
        """impl Show for int
          |    show(self) -> string = "late"
          |trait Show
          |    show(self) -> string
          |print(1.show())""".stripMargin

      run(src) shouldBe "late\n"
    }
  }
}
