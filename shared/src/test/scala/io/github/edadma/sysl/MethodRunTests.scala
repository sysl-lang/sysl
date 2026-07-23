package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Tier-2 runtime behavior of methods, properties, and associated functions: each receiver
 * mode passes the instance correctly, and a member lowered to a function runs like any call.
 */
class MethodRunTests extends AnyFreeSpec with RunSupport {

  "a value-receiver method reads the instance" in {
    val src =
      """struct Point
        |    x: int
        |    y: int
        |    sum(self) -> int = self.x + self.y
        |var p = Point(3, 4)
        |print(p.sum())""".stripMargin

    run(src) shouldBe "7\n"
  }

  "a pointer-receiver method mutates a stack value in place" in {
    val src =
      """struct Point
        |    x: int
        |    y: int
        |    shift(*self, dx: int, dy: int)
        |        self.x += dx
        |        self.y += dy
        |var p = Point(3, 4)
        |p.shift(10, 20)
        |print(p.x, p.y)""".stripMargin

    run(src) shouldBe "13 24\n"
  }

  "a reference-receiver method mutates the shared heap object" in {
    val src =
      """struct Counter
        |    n: int
        |    bump(&self)
        |        self.n += 1
        |    value(self) -> int = self.n
        |var c: &Counter = Counter(0)
        |c.bump()
        |c.bump()
        |c.bump()
        |print(c.value())""".stripMargin

    run(src) shouldBe "3\n"
  }

  "a computed property reads with no parentheses, on a value and on a reference" in {
    val src =
      """struct Counter
        |    n: int
        |    doubled -> int = self.n * 2
        |var v = Counter(21)
        |var r: &Counter = Counter(5)
        |print(v.doubled, r.doubled)""".stripMargin

    run(src) shouldBe "42 10\n"
  }

  "an associated function is called through the type name" in {
    val src =
      """struct Point
        |    x: int
        |    y: int
        |    sum(self) -> int = self.x + self.y
        |    diagonal(n: int) -> Point = Point(n, n)
        |var p = Point.diagonal(6)
        |print(p.sum())""".stripMargin

    run(src) shouldBe "12\n"
  }

  "a method may call another method on self" in {
    val src =
      """struct Rect
        |    w: int
        |    h: int
        |    area(self) -> int = self.w * self.h
        |    describe(self) -> int = self.area() + 1
        |var r = Rect(3, 4)
        |print(r.describe())""".stripMargin

    run(src) shouldBe "13\n"
  }

  "a method is reached through a pointer to the receiver" in {
    val src =
      """struct Point
        |    x: int
        |    y: int
        |    sum(self) -> int = self.x + self.y
        |bump(p: *Point)
        |    p.x += 100
        |var p = Point(1, 2)
        |bump(&p)
        |print(p.sum())""".stripMargin

    run(src) shouldBe "103\n"
  }

  // A &self method that returns a &T field hands out the reference the receiver holds; the
  // returned &Inner is retained so it outlives the receiver being dropped when extract returns.
  // Over a long loop this must free each Inner exactly once — a leak grows RSS, a double-free
  // crashes. Peak RSS was separately confirmed flat. total = sum of i%4 over 500000 = 750000.
  "a &self method returns a &T field that outlives the dropped receiver" in {
    val src =
      """struct Inner
        |    v: int
        |struct Outer
        |    inner: &Inner
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

  // A &self method that overwrites a &T field must release the old reference and retain the new
  // one. Building a fresh Outer, replacing its inner, and reading it back, over a long loop, must
  // free every Inner once. total = sum of (i%3 + 1) over 500000 = 999999.
  "a &self method that swaps a &T field releases the old and retains the new" in {
    val src =
      """struct Inner
        |    v: int
        |struct Outer
        |    inner: &Inner
        |    replace(&self, x: &Inner)
        |        self.inner = x
        |    val(&self) -> int = self.inner.v
        |mk(seed: int) -> int
        |    var o: &Outer = Outer(Inner(seed))
        |    o.replace(Inner(seed + 1))
        |    o.val()
        |var i = 0
        |var total = 0
        |while i < 500000
        |    total += mk(i % 3)
        |    i++
        |print(total)""".stripMargin

    run(src) shouldBe "999999\n"
  }

  // A &self method may return the receiver itself as a &Outer, so calls chain. Each bump mutates
  // the one shared heap object and returns it; after three, both the original alias and the chain
  // result read 3 — every link points at the same object, retained and released without a leak.
  "a fluent &self method returns the receiver so calls chain on one object" in {
    val src =
      """struct Outer
        |    n: int
        |    bump(&self) -> &Outer
        |        self.n += 1
        |        self
        |var o: &Outer = Outer(0)
        |var p = o.bump().bump().bump()
        |print(o.n, p.n)""".stripMargin

    run(src) shouldBe "3 3\n"
  }

  // A *self pointer-receiver reaches a &T field through a raw pointer, where the release of the
  // old reference on overwrite is easy to skip. Building a value Outer, swapping its inner, and
  // reading it back over a long loop must free every Inner once. seed = i%3, new = seed + 10;
  // total = sum of (i%3 + 10) over 500000 = 5499999.
  "a *self method overwriting a &T field releases the old reference" in {
    val src =
      """struct Inner
        |    v: int
        |struct Outer
        |    inner: &Inner
        |    swap(*self, x: &Inner)
        |        self.inner = x
        |    val(*self) -> int = self.inner.v
        |mk(seed: int) -> int
        |    var o = Outer(Inner(seed))
        |    o.swap(Inner(seed + 10))
        |    o.val()
        |var i = 0
        |var total = 0
        |while i < 500000
        |    total += mk(i % 3)
        |    i++
        |print(total)""".stripMargin

    run(src) shouldBe "5499999\n"
  }

  // The raw-pointer read of a &T field must still retain it on return, so the reference outlives
  // the value Outer being dropped when extract returns. total = sum of i%4 over 500000 = 750000.
  "a *self method returns a &T field that outlives the dropped receiver" in {
    val src =
      """struct Inner
        |    v: int
        |struct Outer
        |    inner: &Inner
        |    peek(*self) -> &Inner = self.inner
        |extract(seed: int) -> &Inner
        |    var o = Outer(Inner(seed))
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

  "the built-in len property is unchanged by user members" in {
    val src =
      """var a = [10, 20, 30]
        |print(a.len)""".stripMargin

    run(src) shouldBe "3\n"
  }
}
