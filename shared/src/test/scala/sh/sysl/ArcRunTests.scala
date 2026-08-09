package sh.sysl

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
        |    n match
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
        |    n match
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
        |    *e match
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
        |    n match
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
        |head.next match
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

  // `break value` hands a value out of a loop the same way a branch or a return does, so a fresh
  // &Point broken out of a search must be retained past the loop and freed exactly once. Over a
  // long loop a leak grows RSS and a double-free crashes; peak RSS was separately confirmed flat.
  // find(t) breaks with Point(t, 2t), so x+y = 3t; total = sum of 3*(i%10) over 500000 = 6750000.
  "a &T value broken out of a loop is retained and freed exactly once" in {
    val src =
      """struct Point
        |    x: int
        |    y: int
        |find(target: int) -> &Point
        |    for i in 0..<10
        |        if i == target then break Point(i, i * 2)
        |    else Point(-1, -1)
        |var i = 0
        |var total = 0
        |while i < 500000
        |    var p = find(i % 10)
        |    total += p.x + p.y
        |    i++
        |print(total)""".stripMargin

    run(src) shouldBe "6750000\n"
  }

  /** The `StrBuf` a concatenation builds is a box like any other, and its storage comes back through
   * the hook its header carries.
   *
   * That hook was **null** for as long as `arc.unshare` freed every box itself, and correctly so:
   * raw bytes hold no references, so there was nothing for a destructor to walk. When the free moved
   * into the hook, a null one stopped meaning "nothing to walk" and started meaning "nothing frees
   * this" — so every string a program built would have leaked, silently, because one iteration of it
   * looks exactly like a correct one.
   *
   * Over a long loop it is not silent: a leak grows RSS without bound. `str(i % 10)` builds a second
   * box per iteration, and one whose length is not a constant, so nothing here folds away.
   */
  "a string built in a loop is freed exactly once" in {
    val src =
      """var i = 0
        |var n = 0
        |while i < 200000
        |    val s = "n=" + str(i % 10)
        |    n += int(s.len)
        |    i++
        |print(n)""".stripMargin

    run(src) shouldBe "600000\n"
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
