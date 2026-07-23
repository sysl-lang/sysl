package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Tier-1 lowering of strings: the three words a `string` is, the immortal owner a literal
 * carries, and the runtime pieces its content needs.
 */
class StringCodegenTests extends AnyFreeSpec with CodegenSupport {

  "a string is the same three words a slice is" in {
    ir("""var s = "hi"
         |print(s.len)""".stripMargin) should include("%s.addr = alloca { ptr, ptr, i64 }")
  }

  "a literal is a constant: no owner, the interned bytes, and a length that leaves out the NUL" in {
    val out = ir("""print("hi")""")

    out should include("""@.str1 = private constant [3 x i8] c"hi\00"""")
    out should include("{ ptr null, ptr @.str1, i64 2 }")
  }

  "a length is a word of the value rather than a walk over the bytes" in {
    ir("""var s = "hi"
         |print(s.len)""".stripMargin) should include regex
      raw"extractvalue \{ ptr, ptr, i64 \} %t\d+, 2"
  }

  "the bytes of a string are the string, with nothing emitted to convert them" in {
    val out = ir("""var s = "hi"
                   |var b = s.bytes
                   |print(b.len)""".stripMargin)

    out should include("%b.addr = alloca { ptr, ptr, i64 }")
    out should not include "bitcast"
  }

  "a literal's owner is null, so counting it is a run-time no-op" in {
    val out = ir("""var s = "hi"
                   |print(s.len)""".stripMargin)

    out should include("call void @arc.retain_maybe(ptr %t1)")
    out should include("define private void @arc.retain_maybe(ptr %p) {")
  }

  "printing goes by length rather than by terminator" in {
    val out = ir("""print("hi")""")

    out should include("call void @sysl.str.write(ptr %t1, i64 %t2)")
    out should include("declare i32 @putchar(i32)")
    out should not include "%s\\n"
  }

  "a run of printable arguments still shares one printf" in {
    val out = ir("""print(1, 2, "x", 3, 4)""")

    out should include("""c"%d %d \00"""")
    out should include("""c" %d %d\0A\00"""")
  }

  "comparison is a call, and every operator reads its answer" in {
    val eq = ir("""print("a" == "b")""")
    val lt = ir("""print("a" < "b")""")

    eq should include regex raw"call i32 @sysl\.str\.cmp\(ptr %t\d+, i64 %t\d+, ptr %t\d+, i64 %t\d+\)"
    eq should include regex raw"icmp eq i32 %t\d+, 0"
    lt should include regex raw"icmp slt i32 %t\d+, 0"
  }

  "a substring is checked for landing between characters, at both ends" in {
    val out = ir("""var s = "hi"
                   |var t = s[0..<1]
                   |print(t.len)""".stripMargin)

    out should include regex raw"call i1 @sysl\.str\.boundary\(ptr %t\d+, i64 %t\d+, i64 0\)"
    out should include regex raw"call i1 @sysl\.str\.boundary\(ptr %t\d+, i64 %t\d+, i64 1\)"
    out should include("%cont = icmp eq i8 %top, -128")
  }

  "slicing a string yields a string, so it is checked; slicing bytes is not" in {
    ir("""var s = "hi"
         |var b = s.bytes[0..<1]
         |print(b.len)""".stripMargin) should not include "@sysl.str.boundary"
  }

  "a module that only prints a string needs neither comparison nor boundaries" in {
    val out = ir("""print("hi")""")

    out should not include "@sysl.str.cmp"
    out should not include "@sysl.str.boundary"
  }
}
