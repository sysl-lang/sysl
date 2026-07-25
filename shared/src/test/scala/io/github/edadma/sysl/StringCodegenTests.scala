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

  // A sysl string may hold an interior NUL, so every shortcut through C — `puts`, `%s`, even the
  // length-bounded `%.*s` — would stop early. The sink walks the byte count instead.
  "printing goes by length rather than by terminator" in {
    val out  = ir("""print("hi")""")
    val sink = defineOf(out, "putbytes")

    mainOf(out) should include regex raw"call void @prints\(\{ ptr, ptr, i64 \} .+\)"
    sink should include regex raw"extractvalue \{ ptr, ptr, i64 \} %t\d+, 2"
    sink should include regex raw"call i32 @putchar\(i32 %t\d+\)"
    out.linesIterator.filter(_.startsWith("@.str")).foreach(_ should not include "%s")
  }

  // Every value is its own call to the renderer its type reaches, with a space between and a
  // newline at the end — and the separators go out as characters rather than one-character
  // strings, so printing a number never reaches the string runtime at all.
  "each argument is rendered by its own call" in {
    val out = irMain("""print(1, 2, "x", 3, 4)""")

    out.linesIterator.map(_.trim).filter(_.startsWith("call void @print")).map(_.takeWhile(_ != '(')).toList shouldBe
      List(
        "call void @printi",
        "call void @printc",
        "call void @printi",
        "call void @printc",
        "call void @prints",
        "call void @printc",
        "call void @printi",
        "call void @printc",
        "call void @printi",
        "call void @printc",
      )

    out should include("call void @printc(i32 32)") // the separator
    out should include("call void @printc(i32 10)") // the terminator
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

  "concatenation is one call that returns the joined view" in {
    val out = ir("""print("a" + "b")""")

    out should include regex
      raw"call \{ ptr, ptr, i64 \} @sysl\.str\.concat\(ptr %t\d+, i64 %t\d+, ptr %t\d+, i64 %t\d+\)"
    out should include("define private { ptr, ptr, i64 } @sysl.str.concat")
  }

  "a joined string owns a heap buffer, so the module links an allocator and counts references" in {
    val out = ir("""print("a" + "b")""")

    out should include("declare ptr @malloc(i64)")
    out should include("store ptr @arc.free")
    out should include("define private void @arc.release_maybe(ptr %p) {")
  }

  "a module that never joins strings emits no concatenation helper" in {
    ir("""print("hi")""") should not include "@sysl.str.concat"
  }

  "rendering an integer is one call that allocates a buffer" in {
    val out = ir("""print(str(42))""")

    out should include regex raw"call \{ ptr, ptr, i64 \} @sysl\.str\.int\(i64 %t\d+, i1 1\)"
    out should include("define private { ptr, ptr, i64 } @sysl.str.from_bytes")
    out should include("declare ptr @malloc(i64)")
  }

  "rendering a string is the identity: no call, no allocation of its own" in {
    val out = ir("""var s = "hi"
                   |print(str(s))""".stripMargin)

    out should not include "@sysl.str.int"
    out should not include "@sysl.str.from_bytes"
  }

  "rendering a bool selects an immortal word rather than allocating" in {
    val out = ir("""var b = true
                   |print(str(b))""".stripMargin)

    out should include regex raw"select i1 %t\d+, ptr @\.true, ptr @\.false"
    out should include regex raw"select i1 %t\d+, i64 4, i64 5"
    out should not include "@sysl.str.from_bytes"
  }

  "rendering a char goes through the same UTF-8 encoder printing one does" in {
    val out = ir("""print(str('A'))""")

    out should include("define private { ptr, ptr, i64 } @sysl.str.char")
    out should include("call ptr @sysl.utf8(i32 %cp, ptr %buf)")
    out should include("define private ptr @sysl.utf8")
  }

  "rendering a float matches print's %g through snprintf" in {
    val out = ir("""print(str(1.5))""")

    out should include("define private { ptr, ptr, i64 } @sysl.str.float")
    out should include("declare i32 @snprintf(ptr, i64, ptr, ...)")
    out should include("""c"%g\00"""")
  }

  "a module that never renders a value emits no str helpers" in {
    val out = ir("""print("hi")""")

    out should not include "@sysl.str.int"
    out should not include "@sysl.str.char"
    out should not include "@sysl.str.float"
    out should not include "@sysl.str.from_bytes"
  }

  "an f-string hole formats through snprintf with the C form of the specifier" in {
    val out = ir("""var n = 42
                   |print(f"${n}%04d")""".stripMargin)

    out should include("""c"%04lld\00"""")
    out should include regex raw"call \{ ptr, ptr, i64 \} @sysl\.str\.fmt_i\(ptr @\.str\d+, i64 %t\d+\)"
    out should include("declare i32 @snprintf(ptr, i64, ptr, ...)")
  }

  "an unsigned integer conversion zero-extends, a signed one sign-extends" in {
    val hex = ir("""var n: i32 = -1
                   |print(f"${n}%x")""".stripMargin)
    val dec = ir("""var n: i32 = -1
                   |print(f"${n}%d")""".stripMargin)

    hex should include("""c"%llx\00"""")
    hex should include regex raw"zext i32 %t\d+ to i64"
    dec should include("""c"%lld\00"""")
    dec should include regex raw"sext i32 %t\d+ to i64"
  }

  "a float format passes the double straight through its specifier" in {
    val out = ir("""var x = 1.5
                   |print(f"${x}%.2f")""".stripMargin)

    out should include("""c"%.2f\00"""")
    out should include regex raw"call \{ ptr, ptr, i64 \} @sysl\.str\.fmt_f\(ptr @\.str\d+, double %t\d+\)"
  }

  "a string format copies a NUL-terminated argument for %s" in {
    val out = ir("""var s = "hi"
                   |print(f"${s}%-8s")""".stripMargin)

    out should include("""c"%-8s\00"""")
    out should include("define private { ptr, ptr, i64 } @sysl.str.fmt_s")
    out should include("call void @free(ptr %cstr)")
  }

  "snprintf is declared once even when a float str and a format share it" in {
    val out = ir("""var x = 1.5
                   |print(str(x), f"${x}%.1f")""".stripMargin)

    out.sliding("declare i32 @snprintf".length).count(_ == "declare i32 @snprintf") shouldBe 1
  }
}
