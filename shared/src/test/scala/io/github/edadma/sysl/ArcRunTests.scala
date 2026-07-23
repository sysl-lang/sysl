package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Tier-2 runtime behaviour of reference counting: heap objects that outlive the expression
 * that made them, aliases that see each other's writes, and recursive structures that the
 * counting has to take apart in the right order.
 */
class ArcRunTests extends AnyFreeSpec with RunSupport {

  private val node =
    """struct Node
      |    value: int
      |    next: Option[&Node]
      |""".stripMargin

  "a reference outlives the expression that constructed it" in {
    val src =
      """struct Point
        |    x: int
        |    y: int
        |var p: &Point = Point(3, 4)
        |print(p.x, p.y)""".stripMargin

    run(src) shouldBe "3 4\n"
  }

  "every alias of a reference sees the same object" in {
    val src =
      """struct Counter
        |    n: int
        |var a: &Counter = Counter(0)
        |var b = a
        |a.n = 7
        |print(b.n)
        |b.n = b.n + 1
        |print(a.n)""".stripMargin

    run(src) shouldBe "7\n8\n"
  }

  "a value struct still copies, so its fields are independent" in {
    val src =
      """struct Counter
        |    n: int
        |var a = Counter(0)
        |var b = a
        |a.n = 7
        |print(a.n, b.n)""".stripMargin

    run(src) shouldBe "7 0\n"
  }

  "a function returns a reference it made itself" in {
    val src =
      """struct Point
        |    x: int
        |    y: int
        |origin() -> &Point = Point(0, 0)
        |shifted(by: int) -> &Point
        |    var p: &Point = Point(by, by)
        |    p
        |var a = origin()
        |var b = shifted(5)
        |print(a.x, b.x, b.y)""".stripMargin

    run(src) shouldBe "0 5 5\n"
  }

  "a reference passes into a function, which may mutate the caller's object" in {
    val src =
      """struct Counter
        |    n: int
        |bump(c: &Counter)
        |    c.n = c.n + 1
        |var c: &Counter = Counter(0)
        |bump(c)
        |bump(c)
        |print(c.n)""".stripMargin

    run(src) shouldBe "2\n"
  }

  "a linked list is built out of references and walked back down" in {
    val src = node +
      """total(n: Option[&Node]) -> int
        |    match n
        |        Some(node) -> node.value + total(node.next)
        |        None -> 0
        |var a: &Node = Node(3, None)
        |var b: &Node = Node(2, Some(a))
        |var c: &Node = Node(1, Some(b))
        |print(total(Some(c)))""".stripMargin

    run(src) shouldBe "6\n"
  }

  "a list is rebuilt in a loop, so each link is dropped as the head moves on" in {
    val src = node +
      """digits(n: Option[&Node], acc: int) -> int
        |    match n
        |        Some(node) -> digits(node.next, acc * 10 + node.value)
        |        None -> acc
        |var head: Option[&Node] = None
        |for i in 1..5 do
        |    head = Some(Node(i, head))
        |
        |print(digits(head, 0))""".stripMargin

    run(src) shouldBe "54321\n"
  }

  "a recursive enum evaluates through the references it holds" in {
    val src =
      """enum Expr
        |    Num(n: int)
        |    Add(l: &Expr, r: &Expr)
        |    Mul(l: &Expr, r: &Expr)
        |eval(e: &Expr) -> int
        |    match *e
        |        Num(n) -> n
        |        Add(l, r) -> eval(l) + eval(r)
        |        Mul(l, r) -> eval(l) * eval(r)
        |var tree: &Expr = Mul(Add(Num(2), Num(3)), Num(4))
        |print(eval(tree))""".stripMargin

    run(src) shouldBe "20\n"
  }

  "an absent reference is an Option, matched like any other enum" in {
    val src = node +
      """first(n: Option[&Node]) -> int
        |    match n
        |        Some(node) -> node.value
        |        None -> -1
        |var lone: &Node = Node(9, None)
        |print(first(Some(lone)), first(None))""".stripMargin

    run(src) shouldBe "9 -1\n"
  }

  "two references to one object compare equal, and to different objects do not" in {
    val src =
      """struct Point
        |    x: int
        |    y: int
        |var a: &Point = Point(1, 1)
        |var b = a
        |var c: &Point = Point(1, 1)
        |print(a == b, a == c)""".stripMargin

    run(src) shouldBe "true false\n"
  }

  "an atomic reference behaves exactly like an ordinary one" in {
    val src =
      """struct Counter
        |    n: int
        |var a: &sync Counter = Counter(1)
        |var b = a
        |b.n = 41
        |print(a.n + 1)""".stripMargin

    run(src) shouldBe "42\n"
  }

  "a field holding a reference releases the old one when it is overwritten" in {
    val src = node +
      """var tail: &Node = Node(2, None)
        |var head: &Node = Node(1, Some(tail))
        |head.next = None
        |head.next = Some(Node(3, None))
        |var out = 0
        |match head.next
        |    Some(n) -> out = n.value
        |    None -> out = 0
        |print(out)""".stripMargin

    run(src) shouldBe "3\n"
  }

  "allocating in a long loop neither over-releases nor runs away" in {
    val src = node +
      """var i = 0
        |var out = 0
        |while i < 300000 do
        |    var t: &Node = Node(1, Some(Node(2, None)))
        |    out = t.value
        |    i = i + 1
        |
        |print(out, i)""".stripMargin

    run(src) shouldBe "1 300000\n"
  }

  "a reference survives being handed through a generic function" in {
    val src =
      """struct Point
        |    x: int
        |    y: int
        |id[T](x: T) -> T = x
        |var p: &Point = Point(6, 7)
        |var q = id(p)
        |print(q.x, q.y)""".stripMargin

    run(src) shouldBe "6 7\n"
  }

  // A deep field-path store a.b.c through two levels of &T indirection must reach the innermost
  // reference and release the C it replaced — not the wrong hop, and not nothing. Over a long
  // loop a missed release grows RSS; peak RSS was separately confirmed flat. seed = i%7, new =
  // seed + 100, so residues 0..5 occur 57143 times and residue 6 occurs 57142 across 400000.
  "a deep field-path store through nested references releases the innermost old one" in {
    val src =
      """struct C
        |    v: int
        |struct B
        |    c: &C
        |struct A
        |    b: &B
        |run(seed: int) -> int
        |    var a: &A = A(B(C(seed)))
        |    a.b.c = C(seed + 100)
        |    a.b.c.v
        |var i = 0
        |var total = 0
        |while i < 400000
        |    total += run(i % 7)
        |    i++
        |print(total)""".stripMargin

    run(src) shouldBe "41199997\n"
  }

  // An early return must release exactly the &T locals live at that point — the ones already
  // allocated, not the ones a later line would have made. Three cut points leave different sets
  // live (x; x and the nested Pair; then y as well), and a nested Pair of &Inner exercises a
  // struct field's own references. Over a long loop a skipped release grows RSS and a stray
  // release of an unassigned slot crashes; peak RSS was separately confirmed flat. The period is
  // lcm(3,5)=15, summing to 105 per block, times 20000 blocks.
  "an early return releases exactly the references live at that point" in {
    val src =
      """struct Inner
        |    v: int
        |struct Pair
        |    a: &Inner
        |    b: &Inner
        |build(cut: int, seed: int) -> int
        |    var x: &Inner = Inner(seed)
        |    if cut == 1 then return x.v
        |    var p: &Pair = Pair(Inner(seed + 1), Inner(seed + 2))
        |    if cut == 2 then return x.v + p.a.v
        |    var y: &Inner = Inner(seed + 3)
        |    x.v + p.a.v + p.b.v + y.v
        |var i = 0
        |var total = 0
        |while i < 300000
        |    total += build(i % 3, i % 5)
        |    i++
        |print(total)""".stripMargin

    run(src) shouldBe "2100000\n"
  }
}
