package sh.sysl

import io.github.edadma.cross_platform.*

/** `c const` — constants whose values only the C compiler can work out (`15 §7`).
 *
 * ==Why the language needs one at all==
 *
 * `15 §7` already says how a binding reaches something that has no symbol: put it behind three lines
 * of C and declare the wrapper `extern`. That answer is complete for a *function* and for anything a
 * program is content to learn at run time, and it is no answer at all for a **value**, because a
 * value reached through a call is not a constant. It cannot size an array, cannot stand in a `match`
 * arm, cannot be folded into a bound, and cannot be checked by `@assert`. FreeRTOS is the case that
 * made this unavoidable: a statically allocated task is a `[sizeof(StaticTask_t)]u8` the caller
 * supplies, and there is no such thing as an array whose length is decided by a function.
 *
 * The alternative was to transcribe the number, which is the thing this exists to prevent. `80` is
 * right for one FreeRTOS configuration on one target, and the transcription goes on compiling after
 * it stops being true — a config macro flipped, a version bumped, a 32-bit build — and the failure
 * is a task control block one word short, at run time, on hardware.
 *
 * ==How it is answered==
 *
 * A **probe translation unit**: the file's `@include`s, then one global per constant initialized to
 * the C expression, compiled to LLVM IR for the target and never linked or run. The value is read
 * out of the IR. Nothing executes, so the answer is as correct cross-compiling as it is at home —
 * which is the whole reason for going through the IR rather than through a program that prints it.
 *
 * **`AbiAgainstClangTests` is the precedent and this is the same move.** `targets.md § Adding one`
 * says an answer about a machine is measured against clang and never taken from a document; a `c
 * const` is that rule handed to the programmer, for the numbers a compiler has no way to know it
 * should have asked about.
 *
 * ==What it costs, and when==
 *
 * One `clang` per **file that writes a block**, and nothing at all for a file that does not — which
 * is every file in this repository. The probe is per file rather than per constant because the
 * expressions of one file share its headers; it is per file rather than per compilation because two
 * files may include headers that contradict each other, and a probe that merged them would be asking
 * a question neither file asked.
 *
 * ==What it is not==
 *
 * It reads no declarations out of the header. A type still arrives by `opaque struct` and a function
 * by `extern`, which is `15 §9`'s arrangement and is deliberately untouched — a directive that
 * imported C declarations wholesale is a different feature with a different cost, and this one is
 * about the values that arrangement cannot reach.
 */
object CConstants {

  /** The prefix a probe's globals are named with. Long and unmistakable because it shares a
   * translation unit with somebody else's headers, and a collision there would be a compile error
   * blaming the user's C.
   */
  private val marker = "__sysl_c_const_"

  /** The value of one global, as LLVM prints it: `@__sysl_c_const_3 = dso_local global i64 80`.
   *
   * `dso_local` is on some targets and off on others, and `global` becomes `constant` depending on
   * how the definition is written — so both are optional here. Reading the width as `i64` is not a
   * guess: the probe declares every global `long long` or `unsigned long long` precisely so that the
   * IR has one width on every machine and there is no per-target parsing to get wrong.
   */
  private val emitted = raw"@$marker(\d+) = .*?(?:global|constant) i64 (-?\d+)".r

  /** Every file's blocks lowered to ordinary constants, or the first refusal.
   *
   * A compilation with no `c const` anywhere returns its units untouched and never looks for a
   * clang, which is what keeps a feature for binding C from being a tax on programs that bind none.
   */
  def lower(units: List[Program], target: Target, paths: SearchPaths = SearchPaths.none)
      : Either[String, List[Program]] =
    if !units.exists(_.body.exists(_.isInstanceOf[CConstBlock])) then Right(units)
    else
      units.foldLeft[Either[String, List[Program]]](Right(Nil)) { (soFar, unit) =>
        soFar.flatMap(done => lowerUnit(unit, target, paths).map(done :+ _))
      }

  private def lowerUnit(unit: Program, target: Target, paths: SearchPaths)
      : Either[String, Program] = {
    val blocks = unit.body.collect { case b: CConstBlock => b }

    if blocks.isEmpty then Right(unit)
    else
      val consts = blocks.flatMap(_.consts)

      for
        kinds  <- traverse(consts)(c => integerType(c, target).map(c -> _))
        values <- measure(unit, kinds, target, paths)
        folded <- traverse(kinds.zip(values)) { case ((c, k), raw) => literal(c, k, raw) }
      yield
        // Consumed **in order** rather than looked up by name. A file may declare the same name in
        // two blocks — which the analyzer refuses, and refuses on the merits — and a lookup would
        // have quietly given both lines the same value first, so what got reported was a duplicate
        // of a constant one of them never had.
        val remaining = scala.collection.mutable.Queue.from(folded)

        unit.copy(body = unit.body.flatMap {
          case b: CConstBlock => b.consts.map(_ => remaining.dequeue())
          case other          => List(other)
        })
  }

  /** What the C compiler said, one value per constant, in the order they were written.
   *
   * The probe is left on disk when clang refuses so the message can name it: a header that will not
   * compile is a real possibility here — the wrong `-D`, a missing sibling header — and a diagnostic
   * quoting clang without saying what it was given sends a reader looking at their sysl.
   */
  private def measure(unit: Program, kinds: List[(CConstDecl, Kind)], target: Target,
                      paths: SearchPaths): Either[String, List[BigInt]] = {
    val src = createTempFile("sysl-cconst-", ".c")

    try
      writeFile(src, probe(unit, kinds))

      Toolchain.findClang(target).flatMap { cc =>
        val command = Seq(cc, s"--target=${target.triple}", "-S", "-emit-llvm", "-O0") ++
          Toolchain.machineFlags(target) ++
          Option.when(target.shortEnums)("-fshort-enums") ++ paths.defineFlags ++
          beside(unit) ++ paths.includeFlags ++ Seq("-o", "-", src)

        val result = exec(command)

        if result.exitCode != 0 then
          Left(Diagnostic.render(
            s"the C compiler refused this file's 'c const' block:\n${result.stderr.trim}",
            kinds.headOption.flatMap(_._1.pos)))
        else
          val found = emitted.findAllMatchIn(result.stdout)
            .map(m => m.group(1).toInt -> BigInt(m.group(2)))
            .toMap

          traverse(kinds.zipWithIndex) { case ((c, _), i) =>
            found.get(i).toRight(Diagnostic.render(
              s"the C compiler accepted '${c.c}' and then emitted nothing for it, so there is no " +
                "value to read — this is a bug in sysl's probe rather than in the expression",
              c.pos))
          }
      }
    finally try deleteFile(src) catch case _: Exception => ()
  }

  /** The translation unit put to clang.
   *
   * Every global is **declared at the width the answer is read at** rather than at the expression's
   * own, which is what makes one regex enough: a `sizeof` is `size_t` and a macro may be anything at
   * all, and both become `i64` here. The cast is explicit so that narrowing is the C compiler's
   * decision and shows up in its own warnings rather than in sysl's parsing.
   *
   * Signedness follows the **declared sysl type**, so a constant a program asked for as `i32` is
   * printed as the negative number it is instead of arriving as a very large positive one.
   */
  private def probe(unit: Program, kinds: List[(CConstDecl, Kind)]): String = {
    val headers = unit.includes.map(i => s"#include ${quoted(i.header)}").mkString("\n")

    val globals =
      kinds.zipWithIndex.map { case ((c, k), i) =>
        val ty = if k.signed then "long long" else "unsigned long long"

        s"$ty $marker$i = ($ty)(${c.c});"
      }

    s"$headers\n${globals.mkString("\n")}\n"
  }

  /** `-I` for the directory the sysl file itself sits in, which is **what makes a vendored header
   * reachable at all**.
   *
   * C resolves `#include "foo.h"` relative to the file doing the including, and the probe is a
   * temporary file somewhere else entirely — so without this, `@include("qcbor.h")` beside
   * `qcbor.sysl` cannot be found, and the package that motivated the whole feature could not use it.
   * The shim sitting in the same directory resolves that spelling with no flag, so a `c const` that
   * needed one would have been the odd member of the pair.
   *
   * It goes **before** the search paths given on the command line, so a header carried by the module
   * wins over one of the same name elsewhere on the machine — which is the C convention for a quoted
   * include, and the answer a reader of the module would expect.
   */
  private def beside(unit: Program): List[String] =
    Project.parentOf(unit.source.name).map(d => s"-I$d").toList

  /** A header as C spells it. A name already carrying its own `<…>` or `"…"` is passed through, so
   * `@include("<stdint.h>")` reaches a system header and `@include("qcbor.h")` reaches one beside
   * the module — which is the same choice a C file makes and is not sysl's to make for it.
   */
  private def quoted(header: String): String =
    if header.startsWith("<") || header.startsWith("\"") then header else "\"" + header + "\""

  /** An integer type, as this pass needs to know it: how wide, and whether signed. */
  private case class Kind(bits: Int, signed: Boolean, name: String)

  /** The declared type, held to being an integer.
   *
   * **The restriction is deliberate and the diagnostic says so rather than leaving it to be found.**
   * A `string` constant is a global array in the IR and a different job entirely, and it would also
   * have to be written `"\"foo\""` — two quotings for one value, which is a form nobody would guess.
   * A float is the same shape of problem read back through a different printing. Both are worth
   * having and neither is worth guessing at, so the refusal names them.
   */
  private def integerType(c: CConstDecl, target: Target): Either[String, Kind] = {
    given Word = target.word

    val refused = Left(Diagnostic.render(
      s"'${c.typ.show}' is not an integer, and a 'c const' reads an integer — the value is read " +
        "back out of the C compiler's own output, where an integer is a number and a string is a " +
        "block of storage. A string or a float from C is not written this way yet",
      c.pos))

    c.typ match
      case NamedType(name, Nil) =>
        Type.scalars.get(name).orElse(width(name)) match
          case Some(Type.Integer(bits, signed, _)) => Right(Kind(bits, signed, name))
          case _                                   => refused
      case _ => refused
  }

  /** `i5`, `u32` — the systematic width spellings, which are a family rather than a list.
   * `GenericInstantiation.widthType` is the analyzer's copy and answers more: a bad width is a
   * diagnostic there. Here a name that is not this shape simply is not an integer, and the refusal
   * above says the useful thing.
   */
  private def width(name: String): Option[Type] = {
    val digits = name.drop(1)

    if name.length < 2 || !"iu".contains(name.head) then None
    else if !digits.forall(c => c >= '0' && c <= '9') || digits.head == '0' then None
    else digits.toIntOption.map(Type.Integer(_, signed = name.head == 'i'))
  }

  /** One measured value as the constant it becomes, held to the range of the type it was asked for.
   *
   * A value out of range is the mistake this feature exists to catch, arriving one step earlier than
   * it used to: `portMAX_DELAY` read into a `u16` is the transcription error made against the real
   * number instead of against a remembered one. The message quotes both the C and the value, because
   * neither alone tells the reader which end was wrong.
   */
  private def literal(c: CConstDecl, k: Kind, raw: BigInt): Either[String, ConstDecl] = {
    // The IR prints an i64 as a signed decimal whatever the C type was, so a `u64` past the signed
    // ceiling comes back negative. Only that case is reinterpreted — a genuinely negative value
    // asked for as unsigned is left as it is, and refused by the range check below.
    val value = if !k.signed && raw < 0 then raw + (BigInt(1) << 64) else raw

    val (low, high) =
      if k.signed then (-(BigInt(1) << (k.bits - 1)), (BigInt(1) << (k.bits - 1)) - 1)
      else (BigInt(0), (BigInt(1) << k.bits) - 1)

    if value < low || value > high then
      Left(Diagnostic.render(
        s"'${c.c}' is $value here, which '${k.name}' cannot hold — it holds $low to $high", c.pos))
    else Right(ConstDecl(c.name, c.typ, IntLit(value, None).setPos(c.pos), c.vis).setPos(c.pos))
  }

  /** The first refusal, or every answer — `Either`'s `traverse`, which the standard library has no
   * name for and which this file wants three times.
   */
  private def traverse[A, B](xs: List[A])(f: A => Either[String, B]): Either[String, List[B]] =
    xs.foldLeft[Either[String, List[B]]](Right(Nil))((soFar, x) =>
      soFar.flatMap(done => f(x).map(done :+ _)))
}
