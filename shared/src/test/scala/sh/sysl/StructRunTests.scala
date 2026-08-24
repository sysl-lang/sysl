package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Tier-2 runtime behavior of value structs: positional construction, field reads, in-place
 * field assignment, and passing a struct to a function.
 */
class StructRunTests extends AnyFreeSpec with RunSupport {

  "a struct is constructed and its fields read" in {
    val src =
      """struct Point
        |    x: int
        |    y: int
        |var p = Point(10, 20)
        |print(p.x, p.y)""".stripMargin

    run(src) shouldBe "10 20\n"
  }

  "a field can be assigned in place" in {
    val src =
      """struct Point
        |    x: int
        |    y: int
        |var p = Point(1, 2)
        |p.x = 99
        |print(p.x, p.y)""".stripMargin

    run(src) shouldBe "99 2\n"
  }

  // **A type argument is not containment, and what decides is how the generic uses it.** `Buf[T]`
  // reaches its elements through a `[]T`, which breaks a cycle exactly as `*T` does — so a node whose
  // children are a growable sequence of itself is an ordinary finite type. It is the shape every
  // hand-written syntax tree has, and it was refused until the containment question was moved from
  // the argument's own resolution to the *use* of the substitution inside the instantiation;
  // `AnalyzerDeclErrorTests` holds the other half, where the parameter is held by value and the
  // refusal stands.
  "a struct whose children are a growable sequence of itself" in {
    val src =
      """import sysl.buf.{Buf, buf}
        |struct Node
        |    tag: int
        |    kids: Buf[Node]
        |var root = Node(1, buf())
        |var child = Node(3, buf())
        |child.kids.push(Node(4, buf()))
        |root.kids.push(Node(2, buf()))
        |root.kids.push(child)
        |print(root.tag, root.kids.at(0).tag, root.kids.at(1).tag, root.kids.at(1).kids.at(0).tag)""".stripMargin

    run(src) shouldBe "1 2 3 4\n"
  }

  // The same for a data enum, which is the other half of a syntax tree: a variant carrying a
  // sequence of the enum it belongs to.
  "an enum variant carrying a growable sequence of its own enum" in {
    val src =
      """import sysl.buf.{Buf, buf}
        |enum Json
        |    Num(v: int)
        |    Arr(items: Buf[Json])
        |total(j: Json) -> int
        |    j match
        |        Num(v) -> v
        |        Arr(items) ->
        |            var sum = 0
        |            for i in 0..<items.len()
        |                sum += total(items.at(i))
        |            sum
        |var xs: Buf[Json] = buf()
        |xs.push(Num(1))
        |xs.push(Num(2))
        |var inner: Buf[Json] = buf()
        |inner.push(Num(39))
        |xs.push(Arr(inner))
        |print(total(Arr(xs)))""".stripMargin

    run(src) shouldBe "42\n"
  }

  "a struct is passed to a function by value" in {
    val src =
      """struct Point
        |    x: int
        |    y: int
        |manhattan(p: Point) -> int = p.x + p.y
        |print(manhattan(Point(10, 32)))""".stripMargin

    run(src) shouldBe "42\n"
  }

  "a struct may hold mixed field types" in {
    val src =
      """struct Mix
        |    n: int
        |    r: real
        |    ok: bool
        |var m = Mix(3, 1.5, true)
        |print(m.n, m.r, m.ok)""".stripMargin

    run(src) shouldBe "3 1.5 true\n"
  }
}
