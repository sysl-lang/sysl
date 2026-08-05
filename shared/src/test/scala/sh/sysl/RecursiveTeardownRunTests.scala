package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Tier-2: tearing down recursive `&T` structures. A destructor releases the references its
 * payload holds, so without an iterative reaper a long chain would recurse one C stack frame per
 * node and overflow the stack. These build structures deep and wide enough that a recursive
 * teardown would crash, and check they come apart cleanly — once each, no leak.
 */
class RecursiveTeardownRunTests extends AnyFreeSpec with RunSupport {

  private val node =
    """struct Node
      |    value: int
      |    next: Option[&Node]
      |""".stripMargin

  "a short list builds, sums, and tears down" in {
    val src =
      node +
        """sum(n: &Node) -> int
          |    n.next match
          |        Some(nx) -> n.value + sum(nx)
          |        None -> n.value
          |build3() -> &Node
          |    var a: &Node = Node(3, None)
          |    var b: &Node = Node(2, Some(a))
          |    Node(1, Some(b))
          |print(sum(build3()))""".stripMargin

    run(src) shouldBe "6\n"
  }

  // 200_000 deep: a recursive teardown overflows the stack here (was SIGSEGV, exit 139). The
  // iterative reaper unwinds it in O(1) stack.
  "a very long list tears down without overflowing the stack" in {
    val src =
      node +
        """build(n: int) -> &Node
          |    var head: &Node = Node(0, None)
          |    var i = 1
          |    while i < n
          |        head = Node(i, Some(head))
          |        i++
          |    head
          |var list = build(200000)
          |print(list.value)""".stripMargin

    run(src) shouldBe "199999\n"
  }

  // The same chain through the atomic release path, which is the one the per-thread worklist exists
  // for. Storage class is a claim a text assertion cannot finish: `thread_local` is not an ordinary
  // symbol — Darwin places it in `__thread_vars` and reaches it through `tlv_get_addr`, ELF puts it
  // in `.tbss` behind a relocation the linker relaxes — so "the IR says thread_local" and "it links,
  // and a drain still finds its list" are two things, and this is the second.
  "a long atomic chain drains through the worklist the reaper keeps per thread" in {
    val src =
      """struct Node
        |    value: int
        |    next: Option[&sync Node]
        |build(n: int) -> &sync Node
        |    var head: &sync Node = Node(0, None)
        |    var i = 1
        |    while i < n
        |        head = Node(i, Some(head))
        |        i++
        |    head
        |var list = build(200000)
        |print(list.value)""".stripMargin

    run(src) shouldBe "199999\n"
  }

  // A monomorphized generic recursive type tears down through the same iterative reaper — its
  // drop hook is generated per instantiation, so this checks Stack[int]'s hook releases the
  // `rest: Option[&Stack[int]]` field iteratively too.
  "a generic recursive type tears down without overflowing the stack" in {
    val src =
      """struct Stack[T]
        |    top: T
        |    rest: Option[&Stack[T]]
        |build(n: int) -> &Stack[int]
        |    var s: &Stack[int] = Stack(0, None)
        |    var i = 1
        |    while i < n
        |        s = Stack(i, Some(s))
        |        i++
        |    s
        |var list = build(200000)
        |print(list.top)""".stripMargin

    run(src) shouldBe "199999\n"
  }

  // A shared node (a diamond) must be freed exactly once. Dropped 100_000 times, a double-free
  // would crash and a leak would grow memory; the total confirms every build ran.
  "a shared node in a diamond is freed exactly once" in {
    val src =
      """struct Cell
        |    tag: int
        |    left: Option[&Cell]
        |    right: Option[&Cell]
        |make() -> int
        |    var d: &Cell = Cell(4, None, None)
        |    var b: &Cell = Cell(2, Some(d), None)
        |    var c: &Cell = Cell(3, None, Some(d))
        |    var a: &Cell = Cell(1, Some(b), Some(c))
        |    a.tag
        |var i = 0
        |var total = 0
        |while i < 100000
        |    total += make()
        |    i++
        |print(total)""".stripMargin

    run(src) shouldBe "100000\n"
  }

  // Two types that reach each other, one of them through a growable list — the shape a scheduler's
  // wait graph has, where a lock's waiter list holds tasks and each task points back at what it is
  // waiting for. Built and taken apart a hundred thousand times: the edge that closes the loop is
  // cleared before the objects go, so ARC reclaims them and nothing is freed twice.
  //
  // Left standing, the same two objects would be a cycle ARC cannot reclaim at all. `03 § weak T`
  // names the reference that would say so and no such type exists yet, which is why taking the
  // graph apart is a program's own responsibility rather than the language's.
  "two types that hold each other are reclaimed once the loop is cleared" in {
    val src =
      """import sysl.buf.*
        |
        |struct Waiter
        |    id: int
        |    on: Option[&Held]
        |struct Held
        |    tag: int
        |    queue: &Buf[&Waiter]
        |round() -> int
        |    var h: &Held = Held(7, buf())
        |    var w: &Waiter = Waiter(1, Some(h))
        |    var v: &Waiter = Waiter(2, Some(h))
        |    h.queue.push(w)
        |    h.queue.push(v)
        |    var n = int(h.queue.len()) + h.tag
        |    w.on = None
        |    v.on = None
        |    n
        |var i = 0
        |var total = 0
        |while i < 100000
        |    total += round()
        |    i++
        |print(total)""".stripMargin

    run(src) shouldBe "900000\n"
  }
}
