package sh.sysl

import io.github.edadma.cross_platform.*
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** That every target in the registry produces an **object file**, not merely IR text.
 *
 * The other cross-target tests read the emitted module and check what it says. That catches a wrong
 * coercion in a table, and it cannot catch a module that is internally inconsistent — an `icmp`
 * between an `i64` and a value that is an `i32` on this machine is text like any other, and only
 * something that *verifies* the module will say so. So this tier hands each module to clang and
 * insists on an object coming back.
 *
 * **It is the cheapest test that could have caught the 32-bit width bugs**, and it is cheap because
 * it needs no emulator, no linker script and no hardware: the module either verifies for the triple
 * or it does not. What it deliberately does **not** check is whether the program computes the right
 * answer on that machine — that is the QEMU tier's job, and this one runs everywhere clang does.
 *
 * The programs are chosen for the shapes whose width is a target's answer rather than a constant: a
 * slice (three words, the last of them a `usize`), an index and its bounds check, a `usize` local, a
 * heap value and so a `malloc`, and a foreign call taking an aggregate.
 */
class CrossTargetBuildTests extends AnyFreeSpec with Matchers {

  private val programs = List(
    "a slice and its length" ->
      """var total: usize = 0
        |add(xs: []const usize)
        |    for x in xs
        |        total += x
        |add([1, 2, 3])
        |""".stripMargin,
    "an index, and the bounds check around it" ->
      """pick(xs: []const int, i: usize) -> int
        |    xs[i]
        |var got = pick([4, 5, 6], 1)
        |""".stripMargin,
    "a string, which is a view with a usize in it" ->
      """var n: usize = 0
        |count(s: string)
        |    n = s.len
        |count("hello")
        |""".stripMargin,
    "an aggregate crossing to a C function" ->
      """struct Pair
        |    a: int
        |    b: int
        |extern "take" take(p: Pair) -> int
        |var r = take(Pair(1, 2))
        |""".stripMargin,
    // A string that exists at run time rather than as a literal, which is a different half of the
    // compiler: a literal is three words the emitter writes down, and every one of these reaches a
    // *runtime* helper instead — `sysl.str.concat`, `sysl.str.from_bytes`, a per-width integer
    // renderer, `sysl.str.cmp`, and the three `snprintf` wrappers behind a format specifier. Those
    // helpers are IR templates rather than generated code, so a `usize` in one of them is a
    // hardwired `i64` until something makes it verify for a machine that has not got one.
    "a string built at run time, and the renderers behind it" ->
      """var greeting = "he" + "llo"
        |var counted = str(greeting.len) + str('!')
        |var padded = f"${greeting}%8s ${greeting.len}%d ${0.5}%6.2f"
        |var same = greeting == "hello"
        |var back = from_utf8_unchecked(greeting.bytes)
        |""".stripMargin,
    // Rendering into a growable buffer, which is the one sink the compiler writes rather than the
    // library: `str` of anything that is not a primitive writes itself through its `Display` into a
    // stack slot that grows on the heap, and what landed there becomes the string. Nothing above
    // reaches it, because every value above is a primitive or already a string.
    "a value that renders itself into a growable buffer" ->
      """struct P
        |    x: int
        |impl Display for P
        |    display(self, w: *Writer, spec: FormatSpec) = str(self.x).display(w, spec)
        |var s = str(P(6))
        |""".stripMargin,
  )

  for t <- Target.all if t.supported do
    s"a module for ${t.name}" - {
      for (what, src) <- programs do
        s"verifies and assembles: $what" in {
          // A clang without this target's back end cannot answer, and saying so is better than
          // passing. Apple's clang has no RISC-V at all, which is why `findClang` looks further.
          val cc = Toolchain.findClang(t).getOrElse(cancel(s"no clang for ${t.name}"))

          val obj = createTempFile("sysl-cross-", ".o")
          val ir  = Compiler.compile(List(Source("p.sysl", src)), t) match
            case Right(ir) => ir
            case Left(why) => fail(s"did not compile for ${t.name}: $why")

          withClue(s"$cc, ${t.triple}: ")(Toolchain.compileObject(ir, obj, t) shouldBe Right(()))
        }
    }
}
