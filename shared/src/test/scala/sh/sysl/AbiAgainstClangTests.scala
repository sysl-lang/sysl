package sh.sysl

import io.github.edadma.cross_platform.*
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** That sysl's calling conventions are what **clang** says they are, asked of clang rather than
 * remembered from having asked it once.
 *
 * `CAbiTests` writes down what each convention does and pins `CAbi` against it. That is worth
 * having and it has one gap that nothing inside this repository can close: **the tables were
 * written by reading clang's output, so a misreading is pinned exactly as firmly as a reading.** A
 * wrong coercion is not a compile error and not a verifier error — it is valid IR that puts a value
 * in the wrong register, and every tier below an emulator passes it.
 *
 * So this asks the authority directly. For each shape it compiles the equivalent C for the same
 * triple, reads the `declare` clang produced for a function returning the aggregate and one taking
 * it, and requires sysl's to say the same thing. **`targets.md § Adding one` says an ABI answer is
 * measured against clang and never taken from a document; this is that rule as a test rather than
 * as a practice.**
 *
 * It needs no emulator, no linker script and no hardware — only a clang with the back end, which is
 * what `Toolchain.findClang` finds. A target whose back end this machine's clang does not have is
 * **cancelled and named**, never silently passed.
 */
class AbiAgainstClangTests extends AnyFreeSpec with Matchers with CodegenSupport {

  /** One aggregate, spelled in both languages. The C has to be the same shape to the byte — same
   * member widths, same order, same array lengths — because the whole test is that two compilers
   * given the same shape choose the same coercion.
   */
  private case class Shape(what: String, sysl: String, c: String)

  private val shapes = List(
    Shape("one byte", "    a: u8", "unsigned char a;"),
    Shape("three bytes", "    a: u8\n    b: u8\n    c: u8", "unsigned char a; unsigned char b; unsigned char c;"),
    Shape("one word", "    a: i32", "int a;"),
    Shape("five bytes, aligned to one", "    a: [5]u8", "unsigned char a[5];"),
    Shape("two words", "    a: i32\n    b: i32", "int a; int b;"),
    Shape("eight bytes aligned to eight", "    a: i64", "long long a;"),
    Shape("sixteen bytes aligned to eight", "    a: i64\n    b: i32", "long long a; int b;"),
    Shape("two pointers", "    p: *u8\n    q: *u8", "char *p; char *q;"),
    Shape("sixty-four bytes", "    a: [64]u8", "unsigned char a[64];"),
    Shape("one float", "    a: f32", "float a;"),
    Shape("two floats", "    a: f32\n    b: f32", "float a; float b;"),
    Shape("four doubles", "    a: f64\n    b: f64\n    c: f64\n    d: f64", "double a; double b; double c; double d;"),
    Shape("five doubles", "    a: f64\n    b: f64\n    c: f64\n    d: f64\n    e: f64",
      "double a; double b; double c; double d; double e;"),
    Shape("a double beside an integer", "    a: f64\n    b: i64", "double a; long long b;"),
    Shape("a float beside an integer", "    a: f32\n    b: i32", "float a; int b;"),
  )

  /** Everything a `declare` line carries that is not the convention's answer.
   *
   * `dso_local` is a linkage property, `#0` an attribute-group reference, and `noundef`,
   * `dead_on_unwind`, `writable` and `dead_on_return` are **optimizer facts**: they say what the
   * callee may assume or clobber, not where a value travels. Leaving them in would make this a test
   * of how alike the two front ends are rather than of what the ABI says.
   *
   * **The line between an optimizer fact and an ABI fact is where this test earns its keep, and it
   * is not always obvious from the spelling.** `byval align N` sits right beside `dead_on_return`
   * and is the opposite kind of thing — it states the alignment of the stack slot the argument is
   * copied into, which is the convention's answer and not a hint. It is normalized away here for
   * exactly no attributes, and the first run of this test found sysl saying `align 1` where clang
   * says `align 8`.
   */
  private def normalize(line: String): String =
    List("dso_local ", " #0", " #1", " #2", "noundef ", " noundef", "dead_on_unwind ", "writable ",
      "dead_on_return ", " dead_on_return")
      .foldLeft(line.trim)((s, junk) => s.replace(junk, ""))
      .replaceAll("\\s+", " ")

  private def declaring(ir: String, symbol: String): Option[String] =
    ir.linesIterator.find(l => l.startsWith("declare") && l.contains(s"@$symbol(")).map(normalize)

  /** What clang makes of the shape, as the two `declare`s for it. The `go` body is what forces both
   * to be emitted — a declaration nothing calls is dropped.
   */
  private def clangSays(cc: String, t: Target, c: String): (Option[String], Option[String]) = {
    val src = createTempFile("sysl-abi-", ".c")

    try {
      writeFile(src,
        s"""struct S { $c };
           |extern struct S give(void);
           |extern void take(struct S v);
           |void go(void) { take(give()); }
           |""".stripMargin)

      val r = exec(Seq(cc, s"--target=${t.triple}", "-S", "-emit-llvm", "-O0", "-o", "-", src))

      withClue(s"clang refused the C for ${t.triple}:\n${r.stderr}")(r.exitCode shouldBe 0)

      (declaring(r.stdout, "give"), declaring(r.stdout, "take"))
    } finally try deleteFile(src) catch case _: Exception => ()
  }

  private def syslSays(t: Target, fields: String): (Option[String], Option[String]) = {
    val ir = irFor(t,
      s"""struct S
         |$fields
         |extern give() -> S
         |extern take(v: S)
         |take(give())""".stripMargin)

    (declaring(ir, "give"), declaring(ir, "take"))
  }

  for t <- Target.all if t.supported do
    s"${t.name} agrees with clang about" - {
      for s <- shapes do
        s.what in {
          val cc = Toolchain.findClang(t).getOrElse(cancel(s"no clang here has a back end for ${t.name}"))

          val (cGive, cTake) = clangSays(cc, t, s.c)
          val (mGive, mTake) = syslSays(t, s.sysl)

          withClue(s"returning it (${t.triple}, ${s.c}): ")(mGive shouldBe cGive)
          withClue(s"passing it (${t.triple}, ${s.c}): ")(mTake shouldBe cTake)
        }
    }
}
