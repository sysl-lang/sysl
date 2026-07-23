package io.github.edadma.sysl

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
          |    match n.next
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
}
