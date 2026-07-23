package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Tier-1 lowering of slices: the three-word layout, where the owner comes from, and that
 * taking a view takes a share of what it views.
 */
class SliceCodegenTests extends AnyFreeSpec with CodegenSupport {

  private val buf = "var buf: &[8]u8 = [1, 2, 3, 4, 5, 6, 7, 8]\n"

  "a slice is the owner, the first element, and the count" in {
    val out = ir(buf + "var s = buf[..]\nprint(s.len)")

    out should include("%s.addr = alloca { ptr, ptr, i64 }")
    out should include regex raw"insertvalue \{ ptr, ptr, i64 \} zeroinitializer, ptr %t\d+, 0"
    out should include regex raw"insertvalue \{ ptr, ptr, i64 \} %t\d+, ptr %t\d+, 1"
    out should include regex raw"insertvalue \{ ptr, ptr, i64 \} %t\d+, i64 %t\d+, 2"
  }

  "the owner of a view of a heap array is the reference to it" in {
    val out = ir(buf + "var s = buf[..]\nprint(s.len)")

    out should include regex raw"getelementptr %arc\.arr8\.byte, ptr %t\d+, i32 0, i32 2"
    out should include regex raw"call void @arc\.retain_maybe\(ptr %t\d+\)"
  }

  "a view of a view inherits the owner rather than the buffer" in {
    val out = ir(buf + "var s = buf[2..<6]\nvar t = s[1..<3]\nprint(t.len)")

    out should include regex raw"extractvalue \{ ptr, ptr, i64 \} %t\d+, 0"
  }

  "an owner may be absent, so its count is taken null-tolerantly" in {
    val out = ir(buf + "var s = buf[..]\nprint(s.len)")

    out should include("define private void @arc.retain_maybe(ptr %p) {")
    out should include("%null = icmp eq ptr %p, null")
  }

  "a slice's length is a word of the value, not a constant" in {
    ir(buf + "var s = buf[..]\nprint(s.len)") should include regex
      raw"extractvalue \{ ptr, ptr, i64 \} %t\d+, 2"
  }

  "indexing a slice starts from the pointer it carries" in {
    val out = ir(buf + "var s = buf[..]\nvar i = 0\nprint(s[i])")

    out should include regex raw"extractvalue \{ ptr, ptr, i64 \} %t\d+, 1"
    out should include regex raw"getelementptr i8, ptr %t\d+, i64 %t\d+"
  }

  "a slice held in a local gives its count back when the scope ends" in {
    val out = ir(buf + "var s = buf[..]\nprint(s.len)")

    out should include regex raw"load \{ ptr, ptr, i64 \}, ptr %s\.addr"
    out should include regex raw"call void @arc\.release_maybe\(ptr %t\d+\)"
  }

  "an exclusive high end is checked against the length, an inclusive one against the last" in {
    val exclusive = ir(buf + "var n = 3\nvar s = buf[0..<n]\nprint(s.len)")
    val inclusive = ir(buf + "var n = 3\nvar s = buf[0..n]\nprint(s.len)")

    exclusive should include regex raw"icmp ule i64 %t\d+, 8"
    inclusive should include regex raw"icmp ult i64 %t\d+, 8"
    inclusive should include regex raw"%t\d+ = add i64 %t\d+, 1"
  }

  "the two ends are checked against each other as well" in {
    ir(buf + "var s = buf[2..<6]\nprint(s.len)") should include("icmp ule i64 2, 6")
  }
}
