package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Tier-1 lowering of automatic reference counting: the shape of a box, where the counts are
 * taken and given back, and that the atomic mode really is atomic. Placement is what makes ARC
 * correct, so it is checked in the emitted text rather than only through a program's output.
 */
class ArcCodegenTests extends AnyFreeSpec with CodegenSupport {

  private val point = "struct Point\n    x: int\n    y: int\n"

  "a box is the count, the deallocation hook, and the payload" in {
    val out = ir(point + "var p: &Point = Point(1, 2)")

    out should include("%arc.Point = type { i64, ptr, %struct.Point }")
    out should include("declare ptr @malloc(i64)")
    out should include("declare void @free(ptr)")
  }

  "a construction the context wants by reference goes on the heap" in {
    val out = ir(point + "var p: &Point = Point(1, 2)")

    out should include regex raw"%t\d+ = call ptr @malloc\(i64 %t\d+\)"
    out should include regex raw"store i64 1, ptr %t\d+"
    out should include("store ptr @arc.free, ptr")   // a payload holding nothing needs no destructor
  }

  "the same construction with no expectation stays a value" in {
    val out = ir(point + "var p = Point(1, 2)")

    out should not include "@malloc"
    out should include("%p.addr = alloca %struct.Point")
  }

  "a local takes a count of its own and gives it back when its scope ends" in {
    val out = ir(point + "var p: &Point = Point(1, 2)\nprint(p.x)")

    out should include regex raw"call void @arc\.retain\(ptr %t\d+\)\n  store ptr %t\d+, ptr %p\.addr"
    out should include regex raw"load ptr, ptr %p\.addr\n  call void @arc\.release\(ptr %t\d+\)\n  ret i32 0"
  }

  "assignment retains the new reference before releasing the old one" in {
    val out = ir(point + "var p: &Point = Point(1, 2)\np = Point(3, 4)")
    val store =
      raw"load ptr, ptr %p\.addr\n  call void @arc\.retain\(ptr %t\d+\)\n" +
        raw"  store ptr %t\d+, ptr %p\.addr\n  call void @arc\.release\("

    out should include regex store
  }

  "a function owns its parameters and hands back its result with a count taken" in {
    val out = ir(point + "keep(p: &Point) -> &Point = p\nvar q: &Point = Point(1, 2)\nvar r = keep(q)")

    out should include("call void @arc.retain(ptr %p.param)")
    out should include regex raw"call void @arc\.release\(ptr %t\d+\)\n  ret ptr %t\d+"
  }

  "a destructor lets go of what the payload held, then calls through the hook" in {
    val src = "struct Node\n    value: int\n    next: Option[&Node]\nvar n: &Node = Node(1, None)"
    val out = ir(src)

    out should include("define private void @arc.drop.Node(ptr %p) {")
    out should include("call void @arc.dispose.Node(%struct.Node %t2)")
    out should include regex raw"call void @arc\.dispose\.Node[^\n]*\n  call void @free\(ptr %p\)"
    out should include("store ptr @arc.drop.Node, ptr")
  }

  "copying an aggregate takes a share of every reference inside it" in {
    val src = "struct Node\n    value: int\n    next: Option[&Node]\nvar n: &Node = Node(1, None)"
    val out = ir(src)

    out should include("define private void @arc.copy.Node(%struct.Node %v) {")
    out should include("call void @arc.copy.Option.ref.Node(%enum.Option.ref.Node %t1)")
  }

  "a data enum walks only the variants that carry a reference, behind a tag test" in {
    val src = "struct Node\n    value: int\n    next: Option[&Node]\nvar n: &Node = Node(1, None)"
    val out = ir(src)

    out should include("define private void @arc.dispose.Option.ref.Node(%enum.Option.ref.Node %v) {")
    out should include regex raw"extractvalue %enum\.Option\.ref\.Node %v, 0\n  %t\d+ = icmp eq i32 %t\d+, 0"
    out should include("call void @arc.release(ptr %t4)")
  }

  "an ordinary reference counts with a plain load and store" in {
    val out = ir(point + "var p: &Point = Point(1, 2)")

    out should include("define private void @arc.release(ptr %p) {")
    out should not include "atomicrmw sub"
  }

  "an atomic reference counts atomically and fences before it frees" in {
    val out = ir(point + "var p: &sync Point = Point(1, 2)")

    out should include("%o = atomicrmw add ptr %p, i64 1 monotonic")
    out should include("atomicrmw sub ptr %p, i64 1 release")
    out should include regex raw"drop:\n  fence acquire"
  }

  "the two reference modes share one box layout, since atomicity belongs to the reference" in {
    val src = point + "var p: &Point = Point(1, 2)\nvar q: &sync Point = Point(3, 4)"
    val out = ir(src)

    out.linesIterator.count(_.startsWith("%arc.Point = type")) shouldBe 1
    out should include("define private void @arc.release(ptr %p) {")
    out should include("define private void @arc.release_sync(ptr %p) {")
  }

  "a field through a reference reaches past the header" in {
    val out = ir(point + "var p: &Point = Point(1, 2)\np.y = 9")

    out should include regex raw"getelementptr %arc\.Point, ptr %t\d+, i32 0, i32 2"
    out should include regex raw"getelementptr %struct\.Point, ptr %t\d+, i32 0, i32 1"
  }

  "a program that never allocates declares no allocator" in {
    ir("var n = 1\nprint(n)") should not include "@malloc"
  }
}
