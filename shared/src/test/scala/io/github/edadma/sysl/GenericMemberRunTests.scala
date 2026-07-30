package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Tier-2 runtime behavior of members on *generic* types: a method or property on a generic
 * struct is instantiated from the receiver's concrete type arguments, so the same member compiled
 * against two element types becomes two independent monomorphized functions. These check that the
 * receiver mode, the type parameter in the signature and body, and the per-instantiation choice of
 * `T` all behave as they do for a free generic function.
 */
class GenericMemberRunTests extends AnyFreeSpec with RunSupport {

  "a value-receiver method returns the element type" in {
    val src =
      """struct Box[T]
        |    value: T
        |    get(self) -> T = self.value
        |var a = Box(41)
        |var b = Box("boxed")
        |print(a.get(), b.get())""".stripMargin

    run(src) shouldBe "41 boxed\n"
  }

  "a method takes a parameter of the element type" in {
    val src =
      """struct Box[T: Add]
        |    value: T
        |    plus(self, other: T) -> T = self.value + other
        |var a = Box(10)
        |print(a.plus(5))""".stripMargin

    run(src) shouldBe "15\n"
  }

  "a pointer-receiver method mutates the element in place" in {
    val src =
      """struct Box[T]
        |    value: T
        |    set(*self, x: T)
        |        self.value = x
        |    get(self) -> T = self.value
        |var a = Box(1)
        |a.set(99)
        |print(a.get())""".stripMargin

    run(src) shouldBe "99\n"
  }

  "a reference-receiver method mutates the shared heap object" in {
    val src =
      """struct Box[T: Add]
        |    value: T
        |    bump(&self, by: T)
        |        self.value = self.value + by
        |    get(&self) -> T = self.value
        |var a: &Box[int] = Box(0)
        |a.bump(3)
        |a.bump(4)
        |print(a.get())""".stripMargin

    run(src) shouldBe "7\n"
  }

  "a computed property on a generic struct reads with no parentheses" in {
    val src =
      """struct Box[T: Add]
        |    value: T
        |    doubled -> T = self.value + self.value
        |var a = Box(21)
        |var b = Box(1.25)
        |print(a.doubled, b.doubled)""".stripMargin

    run(src) shouldBe "42 2.5\n"
  }

  "the same generic method is monomorphized once per element type" in {
    val out = Compiler.compileToLlvm(
      """struct Box[T]
        |    value: T
        |    get(self) -> T = self.value
        |var a = Box(1)
        |var b = Box(2.5)
        |print(a.get(), b.get())""".stripMargin
    )

    // Box.get.int and Box.get.real — the member is compiled once per element type, exactly as a
    // free generic function is, not shared across the two instantiations. Only its own definitions
    // are counted; the rest of the module is the ARC runtime and the library renderers `print`
    // reached.
    out.map(_.linesIterator.count(l => l.startsWith("define") && l.contains("@Box.get."))) shouldBe Right(2)
  }

  "a method uses both parameters of a two-parameter generic struct" in {
    val src =
      """struct Pair[A, B]
        |    first: A
        |    second: B
        |    show(self) -> B = self.second
        |var p = Pair(1, "one")
        |print(p.show())""".stripMargin

    run(src) shouldBe "one\n"
  }

  "a generic method calls another method on self" in {
    val src =
      """struct Box[T: Add]
        |    value: T
        |    raw(self) -> T = self.value
        |    twice(self) -> T = self.raw() + self.raw()
        |var a = Box(6)
        |print(a.twice())""".stripMargin

    run(src) shouldBe "12\n"
  }

  "a recursive generic type exposes a method that reaches through the pointer" in {
    val src =
      """struct Node[T]
        |    value: T
        |    next: *Node[T]
        |    head(self) -> T = self.value
        |var tail: Node[int] = Node(2, null)
        |var list = Node(1, &tail)
        |print(list.head(), tail.head())""".stripMargin

    run(src) shouldBe "1 2\n"
  }

  // A body that adds to the element needs the type to say so, and `T: Add` is where it says it.
  // The `1` is checked against `T` per instantiation rather than at the definition — a literal is
  // an `int` and `T` is not one yet — so an instantiation at a type that adds but does not add an
  // `int` is where that mismatch surfaces.
  "a method whose body needs a numeric element compiles at a numeric instantiation" in {
    val src =
      """struct Box[T: Add]
        |    value: T
        |    inc(self) -> T = self.value + 1
        |var a = Box(41)
        |print(a.inc())""".stripMargin

    run(src) shouldBe "42\n"
  }

  // `Self` inside a generic type's own body is the type applied to its parameters, which is not a
  // type until an instantiation says what they are — so it is resolved alongside them rather than
  // ahead of them, and means there exactly what it means in a concrete type's body.
  "a member may write 'Self' for the type it belongs to" in {
    val src =
      """struct Box[T]
        |    v: T
        |    same(self) -> Self = self
        |var b = Box(41)
        |print(b.same().v)""".stripMargin

    run(src) shouldBe "41\n"
  }

  // A &self method on a generic type that hands out a &T field: the returned reference is retained
  // so it outlives the receiver dropped when extract returns. Over a long loop this must free each
  // boxed value exactly once — a leak grows RSS, a double-free crashes. total = sum of i%4 = 750000.
  "a &self generic method returns a &T field that outlives the dropped receiver" in {
    val src =
      """struct Inner
        |    v: int
        |struct Holder[T]
        |    item: T
        |    peek(&self) -> T = self.item
        |extract(seed: int) -> &Inner
        |    var h: &Holder[&Inner] = Holder(Inner(seed))
        |    h.peek()
        |var i = 0
        |var total = 0
        |while i < 500000
        |    var got = extract(i % 4)
        |    total += got.v
        |    i++
        |print(total)""".stripMargin

    run(src) shouldBe "750000\n"
  }
}
