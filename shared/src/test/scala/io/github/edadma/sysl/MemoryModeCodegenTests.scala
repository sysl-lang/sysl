package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Tier-1 lowering of the memory modes: what a place's address looks like, that a pointer or a
 * reference is a plain opaque `ptr`, and that a reference type survives into a mangled name.
 */
class MemoryModeCodegenTests extends AnyFreeSpec with CodegenSupport {

  "a pointer is an opaque ptr, and address-of is the slot itself" in {
    val out = ir("var n = 1\nvar p = &n\nprint(*p)")

    out should include("%n.addr = alloca i32")
    out should include("%p.addr = alloca ptr")
    out should include("store ptr %n.addr, ptr %p.addr")
  }

  "a store through a dereference goes to the loaded address" in {
    val out = ir("var n = 1\nvar p = &n\n*p = 7")

    out should include regex raw"%t\d+ = load ptr, ptr %p\.addr"
    out should include regex raw"store i32 7, ptr %t\d+"
  }

  "a field through a pointer is one getelementptr from the pointer value" in {
    val out = ir("struct P\n    x: int\n    y: int\nvar v = P(1, 2)\nvar p = &v\np.y = 9")

    out should include regex raw"%t\d+ = load ptr, ptr %p\.addr"
    out should include regex raw"getelementptr %struct\.P, ptr %t\d+, i32 0, i32 1"
  }

  "a field of a local is addressed from its own slot, with no load" in {
    val out = ir("struct P\n    x: int\n    y: int\nvar v = P(1, 2)\nv.x = 9")

    out should include("getelementptr %struct.P, ptr %v.addr, i32 0, i32 0")
  }

  "a recursive struct lowers its self-reference to a pointer" in {
    val out = ir("struct Node\n    value: int\n    next: *Node\nvar n = Node(1, null)")

    out should include("%struct.Node = type { i32, ptr }")
    out should include("insertvalue %struct.Node %t1, ptr null, 1")
  }

  "null lowers to the LLVM null constant" in {
    ir("var p: *int = null\nprint(p == null)") should include("store ptr null, ptr %p.addr")
  }

  "pointer and bool equality use the equality predicates" in {
    ir("var n = 1\nvar p = &n\nprint(p == null)") should include regex raw"icmp eq ptr %t\d+, null"
    ir("var b = true\nprint(b == false)") should include regex raw"icmp eq i1 %t\d+, 0"
  }

  "a memory mode is mangled as a word, since a sigil is not an LLVM name" in {
    val out = ir("id[T](x: T) -> T = x\nvar n = 1\nvar p = &n\nvar q = id(p)")

    out should include("@id.ptr.int(")
  }
}
