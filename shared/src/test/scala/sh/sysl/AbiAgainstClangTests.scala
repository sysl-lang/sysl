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
  private def normalize(t: Target, line: String): String =
    List("dso_local ", " #0", " #1", " #2", "noundef ", " noundef", "dead_on_unwind ", "writable ",
      "dead_on_return ", " dead_on_return")
      .foldLeft(line.trim)((s, junk) => s.replace(junk, ""))
      .replaceAll("\\s+", " ")
      // A **coercion array of addresses** is spelled `[N x ptr]` by one LLVM and `[N x iW]` by
      // another, and the two are the same argument: W is the pointer width, so both say "N
      // pointer-sized pieces, in registers". Which one a clang prints is a fact about its LLVM
      // version rather than about the convention — this is the same line the attribute list above
      // draws, met from the other side. Only inside the brackets, so a real `ptr` argument (a
      // `byval` slot, a `sret` address) is untouched, and only against **this target's** width, so
      // `[2 x i32]` on a 32-bit machine is still a difference.
      //
      // CI went red on this and no local run did: `struct { char *p; char *q; }` at the three
      // aarch64 targets, sysl saying `[2 x ptr]` and the runner's older clang `[2 x i64]`.
      .replaceAll("""\[(\d+) x ptr\]""", s"[$$1 x ${t.word.llvm}]")

  private def declaring(t: Target, ir: String, symbol: String): Option[String] =
    ir.linesIterator.find(l => l.startsWith("declare") && l.contains(s"@$symbol(")).map(normalize(t, _))

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

      (declaring(t, r.stdout, "give"), declaring(t, r.stdout, "take"))
    } finally try deleteFile(src) catch case _: Exception => ()
  }

  private def syslSays(t: Target, fields: String): (Option[String], Option[String]) = {
    val ir = irFor(t,
      s"""struct S
         |$fields
         |extern give() -> S
         |extern take(v: S)
         |take(give())""".stripMargin)

    (declaring(t, ir, "give"), declaring(t, ir, "take"))
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

      "every scalar width, and which of them the convention widens" in scalarsAgree(t)
    }

  // --- the narrow scalars ------------------------------------------------------------------
  //
  // An aggregate's answer is a *type*; a scalar's is an **attribute** — `signext`, `zeroext`, or
  // nothing — and until a `u8` reached a C function as a different number nothing here asked about
  // one at all. `CAbi`'s own docstring said a scalar crossed as itself and needed no decision, which
  // is true of its type and false of its bits.
  //
  // All of them go in **one** translation unit and one sysl module, unlike the shapes above, because
  // the answer is per width rather than per layout: eight widths asked separately would be eight
  // clang invocations per target for a question one answers.

  /** One scalar, spelled in both languages, and the pair of symbols it is asked about. */
  private case class Scalar(what: String, sysl: String, c: String) {
    def give: String = s"g_$what"
    def take: String = s"t_$what"
  }

  private val scalars = List(
    Scalar("bool", "bool", "_Bool"),
    Scalar("i8", "i8", "signed char"),
    Scalar("u8", "u8", "unsigned char"),
    Scalar("i16", "i16", "short"),
    Scalar("u16", "u16", "unsigned short"),
    Scalar("i32", "i32", "int"),
    Scalar("u32", "u32", "unsigned int"),
    Scalar("i64", "i64", "long long"),
    // A `char` is a Unicode scalar in 32 bits, so what it must agree with is `unsigned int` — which
    // is the one width RISC-V 64 widens against its own signedness, and the only reason this row is
    // worth a line of its own.
    Scalar("char", "char", "unsigned int"),
  )

  private def scalarsAgree(t: Target): Unit = {
    val cc  = Toolchain.findClang(t).getOrElse(cancel(s"no clang here has a back end for ${t.name}"))
    val src = createTempFile("sysl-abi-scalar-", ".c")

    val fromClang =
      try {
        writeFile(src,
          scalars.map(s => s"extern ${s.c} ${s.give}(void); extern void ${s.take}(${s.c} v);\n").mkString +
            s"void go(void) {\n${scalars.map(s => s"  ${s.take}(${s.give}());\n").mkString}}\n")

        val r = exec(Seq(cc, s"--target=${t.triple}", "-S", "-emit-llvm", "-O0", "-o", "-", src))

        withClue(s"clang refused the C for ${t.triple}:\n${r.stderr}")(r.exitCode shouldBe 0)
        r.stdout
      } finally try deleteFile(src) catch case _: Exception => ()

    val fromSysl = irFor(t,
      scalars.map(s => s"extern ${s.give}() -> ${s.sysl}\nextern ${s.take}(v: ${s.sysl})\n").mkString +
        scalars.map(s => s"${s.take}(${s.give}())\n").mkString)

    for s <- scalars do
      withClue(s"returning a ${s.sysl} (${t.triple}, ${s.c}): ")(
        declaring(t, fromSysl, s.give) shouldBe declaring(t, fromClang, s.give))
      withClue(s"passing a ${s.sysl} (${t.triple}, ${s.c}): ")(
        declaring(t, fromSysl, s.take) shouldBe declaring(t, fromClang, s.take))
  }
}
