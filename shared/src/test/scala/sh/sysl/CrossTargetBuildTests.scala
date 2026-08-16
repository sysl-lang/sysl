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

  // CRAFT is not here because there is no clang to assemble what it lowers to — its back end is an
  // out-of-tree `llc`, and `llvm-mc` would need it too. What this sweep does for every other target,
  // `CraftTargetTests` does for that one by reading the module instead: it is the only check
  // available, which is exactly why that file says so rather than leaving the gap to be noticed.
  for t <- Target.all if t.supported && t.buildsWithClang do
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

  /** That the reaper slot a freestanding port may define really is **weak in the object**, which is
   * the claim `06 § Letting go of the last one` rests on and the one thing the emitted text cannot
   * settle by itself.
   *
   * `define weak` is what lets a scheduler's port define `__sysl_arc_reaper` and win the link while
   * a program with no scheduler links against the module's own single slot and defines nothing. Read
   * in the IR that is an adjective; read out of the symbol table it is the linkage the linker will
   * act on, and `nm` marks it `W`. A strong definition here would make every bare-metal link that
   * also carried a port fail on a duplicate symbol — a failure at somebody else's link, months from
   * the change that caused it.
   */
  "the reaper slot a port may define is weak in the object, not merely in the text" in {
    val t  = Target.aarch64Freestanding
    val cc = Toolchain.findClang(t).getOrElse(cancel(s"no clang for ${t.name}"))

    val src = """struct Node
                |    v: int
                |var p: &sync Node = Node(1)
                |""".stripMargin

    val obj = createTempFile("sysl-reaper-", ".o")
    val ir = Compiler.compile(List(Source("p.sysl", src)), t) match
      case Right(ir) => ir
      case Left(why) => fail(s"did not compile for ${t.name}: $why")

    withClue(s"$cc, ${t.triple}: ")(Toolchain.compileObject(ir, obj, t) shouldBe Right(()))

    val listed = exec(List("nm", obj))
    deleteFile(obj)

    // Skipped rather than failed where there is no `nm`: this asserts something about the object
    // format, and a machine that cannot list symbols cannot be asked about it.
    assume(listed.exitCode == 0, "nm not available")

    val line = listed.stdout.linesIterator.find(_.contains(ArcEmitter.reaperSlot))

    withClue(listed.stdout)(line.isDefined shouldBe true)
    // `W` is a weak *definition*; `w` would be a weak reference, which is a different promise and
    // would leave the single-slot default undefined.
    withClue(line.get)(line.get should include(" W "))
  }

  /** And that a port's definition **wins**, which is the other half of the same claim and the half
   * an adjective in the IR cannot establish.
   *
   * `weak` is only worth anything if a strong definition beside it takes over, so the check is the
   * link itself: the module's object and a C object defining `__sysl_arc_reaper` outright, merged,
   * leaving one definition and that one strong. A relocatable link (`-r`) rather than a whole
   * program, because a bare-metal image would want an entry point and a script that are nothing to
   * do with what is being asked.
   */
  "and a port's own definition takes over from it at the link" in {
    val t  = Target.aarch64Freestanding
    val cc = Toolchain.findClang(t).getOrElse(cancel(s"no clang for ${t.name}"))

    val src = """struct Node
                |    v: int
                |var p: &sync Node = Node(1)
                |""".stripMargin

    val obj    = createTempFile("sysl-reaper-", ".o")
    val portC  = createTempFile("sysl-port-", ".c")
    val portO  = createTempFile("sysl-port-", ".o")
    val merged = createTempFile("sysl-merged-", ".o")

    val ir = Compiler.compile(List(Source("p.sysl", src)), t) match
      case Right(ir) => ir
      case Left(why) => fail(s"did not compile for ${t.name}: $why")

    withClue(s"$cc, ${t.triple}: ")(Toolchain.compileObject(ir, obj, t) shouldBe Right(()))

    // What an RTOS port writes: storage per task, handed back by the symbol the reaper asks. The
    // layout is the slot's — a head pointer and a flag — and this is the one place it is written in
    // the language a port is written in.
    writeFile(portC,
      s"""struct sysl_arc_reaper { void *head; unsigned char draining; };
         |static struct sysl_arc_reaper task;
         |struct sysl_arc_reaper *${ArcEmitter.reaperSlot}(void) { return &task; }
         |""".stripMargin)

    val compiled = exec(List(cc, s"--target=${t.triple}", "-c", portC, "-o", portO))
    withClue(compiled.stderr)(compiled.exitCode shouldBe 0)

    val linked = exec(List("ld.lld", "-r", obj, portO, "-o", merged))

    val listed = if linked.exitCode == 0 then exec(List("nm", merged)) else linked

    for f <- List(obj, portC, portO, merged) do deleteFile(f)

    // Skipped rather than failed without lld: this asserts something about a linker, and a machine
    // with no linker for the target cannot be asked.
    assume(linked.exitCode == 0, s"ld.lld not available: ${linked.stderr.trim}")
    assume(listed.exitCode == 0, "nm not available")

    val defs = listed.stdout.linesIterator.filter(_.contains(ArcEmitter.reaperSlot)).toList

    // One definition, and it is the port's: a `W` surviving here would mean the weak default had
    // been kept and the port's storage never used, which is the defect this whole arrangement is
    // for — and it would show up as two tasks sharing a worklist rather than as a link error.
    withClue(listed.stdout)(defs.length shouldBe 1)
    withClue(defs.head)(defs.head should include(" T "))
  }
}
