package sh.sysl

import io.github.edadma.cross_platform.*

/** `c const` and `c type` — the values and the widths only the C compiler can work out (`15 §7`).
 *
 * ==Why the language needs them at all==
 *
 * `15 §7` already says how a binding reaches something that has no symbol: put it behind three lines
 * of C and declare the wrapper `extern`. That answer is complete for a *function* and for anything a
 * program is content to learn at run time, and it is no answer at all for a **value**, because a
 * value reached through a call is not a constant. It cannot size an array, cannot stand in a `match`
 * arm, cannot be folded into a bound, and cannot be checked by `@assert`. FreeRTOS is the case that
 * made this unavoidable: a statically allocated task is a `[sizeof(StaticTask_t)]u8` the caller
 * supplies, and there is no such thing as an array whose length is decided by a function.
 *
 * It is no answer for a **type** either, and that is the second half. A typedef whose width the
 * target or a `#define` decides — `TickType_t`, `time_t`, `off_t`, `wchar_t` — appears in
 * signatures, so getting it wrong is not a size mismatch anything can see: it is an `extern`
 * declaring a different argument width from the function it names, which links and then passes
 * garbage in the high half. `c const` can measure `sizeof(TickType_t)` and has no way to *use* the
 * answer, since nothing turns a constant into the type of a parameter. `c type` is that step.
 *
 * The alternative to both is to transcribe, which is the thing this exists to prevent. `80` is right
 * for one FreeRTOS configuration on one target, and the transcription goes on compiling after it
 * stops being true — a config macro flipped, a version bumped, a 32-bit build — and the failure is a
 * task control block one word short, at run time, on hardware.
 *
 * ==How it is answered==
 *
 * A **probe translation unit**: the file's `@include`s, then one global per constant initialized to
 * the C expression and two globals per type describing it, compiled to LLVM IR for the target and
 * never linked or run. The answers are read out of the IR. Nothing executes, so they are as correct
 * cross-compiling as they are at home — which is the whole reason for going through the IR rather
 * than through a program that prints them.
 *
 * **`AbiAgainstClangTests` is the precedent and this is the same move.** `targets.md § Adding one`
 * says an answer about a machine is measured against clang and never taken from a document; these
 * blocks are that rule handed to the programmer, for the numbers and the widths a compiler has no
 * way to know it should have asked about.
 *
 * ==What a type measurement is==
 *
 * `_Generic` over an object of the type, which answers **which** integer C thinks it is, and
 * `sizeof`, which answers how wide that is here. Three things fall out of using `_Generic` rather
 * than a cast, and all three were measured against clang rather than reasoned about:
 *
 *   - an **enum** matches its compatible integer type rather than the `default` arm, so an enum
 *     typedef is measurable and carries its signedness with it;
 *   - a **qualifier** drops out through the lvalue conversion, so `const unsigned short` needs no
 *     special case;
 *   - a float, a struct, a pointer and an array land in `default`, which is what makes the refusal
 *     for them a sysl diagnostic naming the type rather than a C error naming a generated file.
 *
 * Plain `char` is its own arm because C leaves its signedness to the implementation, and it is
 * **signed** on this machine and unsigned on others — exactly the kind of answer this feature exists
 * to stop anybody transcribing. One further global measures it, once per probe.
 *
 * ==What it costs, and when==
 *
 * One `clang` per **file that writes a block**, and nothing at all for a file that does not — which
 * is every file in this repository. The probe is per file rather than per constant because the
 * expressions of one file share its headers; it is per file rather than per compilation because two
 * files may include headers that contradict each other, and a probe that merged them would be asking
 * a question neither file asked. A file writing **both** blocks is one probe and not two, for the
 * same reason a block is one question rather than a line's worth each.
 *
 * ==What it is not==
 *
 * It reads no declarations out of the header. A `c type` names a typedef and gets an integer back; it
 * does not import the typedef, and a struct still arrives by `opaque struct` and a function by
 * `extern`, which is `15 §9`'s arrangement and is deliberately untouched — a directive that imported
 * C declarations wholesale is a different feature with a different cost.
 */
object CProbe {

  /** The prefixes a probe's globals are named with. Long and unmistakable because they share a
   * translation unit with somebody else's headers, and a collision there would be a compile error
   * blaming the user's C.
   */
  private val constMarker = "__sysl_c_const_"
  private val kindMarker  = "__sysl_c_type_kind_"
  private val sizeMarker  = "__sysl_c_type_size_"
  private val objMarker   = "__sysl_c_type_obj_"
  private val charMarker  = "__sysl_c_char_signed"

  /** The value of one numbered global, as LLVM prints it:
   * `@__sysl_c_const_3 = dso_local global i64 80`.
   *
   * `dso_local` is on some targets and off on others, and `global` becomes `constant` depending on
   * how the definition is written — so both are optional here. Reading the width as `i64` is not a
   * guess: the probe declares every global `long long` or `unsigned long long` precisely so that the
   * IR has one width on every machine and there is no per-target parsing to get wrong.
   */
  private def numbered(marker: String) = raw"@$marker(\d+) = .*?(?:global|constant) i64 (-?\d+)".r

  private val constEmitted = numbered(constMarker)
  private val kindEmitted  = numbered(kindMarker)
  private val sizeEmitted  = numbered(sizeMarker)
  private val charEmitted  = raw"@$charMarker = .*?(?:global|constant) i64 (-?\d+)".r

  /** Every file's blocks lowered to ordinary declarations, or the first refusal.
   *
   * A compilation with no `c const` and no `c type` anywhere returns its units untouched and never
   * looks for a clang, which is what keeps a feature for binding C from being a tax on programs that
   * bind none.
   */
  def lower(units: List[Program], target: Target, paths: SearchPaths = SearchPaths.none)
      : Either[String, List[Program]] =
    if !units.exists(_.body.exists(b => b.isInstanceOf[CConstBlock] || b.isInstanceOf[CTypeBlock]))
    then Right(units)
    else
      units.foldLeft[Either[String, List[Program]]](Right(Nil)) { (soFar, unit) =>
        soFar.flatMap(done =>
          if unmeasurable(unit, target) then Right(done :+ emptied(unit))
          else lowerUnit(unit, target, paths).map(done :+ _))
      }

  /** Whether this file's blocks must not be measured, because the machine being built for cannot
   * have what the file says it needs (`capabilities.md`, `15 § c type`).
   *
   * **A probe is a C compilation, so a file carrying one asks for headers.** A file that also
   * declares `@requires(posix)` has said which machines it is for, and a freestanding target is not
   * one of them — there is no `<regex.h>` for a bare Cortex-M and no reason there should be. Without
   * this the standard library could hold no probe at all: the artifact is built for every target, so
   * one POSIX module measuring `sizeof(regex_t)` would fail every freestanding build, including
   * builds of programs that never name it.
   *
   * **The question is `Target.inherentCapabilities` and deliberately not `provides`.** A project's
   * config defaults every capability to provided, so a freestanding target nominally offers `posix`
   * and gating on it would gate nothing — which is the shape this was first misdiagnosed as. What is
   * asked here is physical: is there an operating system on this machine at all.
   *
   * A file requiring nothing is measured as before, whatever the target. That is the status quo and
   * is what keeps this from being a rule about targets rather than about the files that opted in:
   * a probe with no clause is a file claiming to build anywhere, and one whose header is missing has
   * mis-stated itself rather than found a bug here.
   */
  private def unmeasurable(unit: Program, target: Target): Boolean =
    val machine = target.inherentCapabilities

    unit.capabilities.exists(c =>
      c.direction == CapabilityDirection.Requires &&
        Capability.closure(c.name).exists(cap => Capability.environment(cap) && !machine(cap)))

  /** The file with its declarations dropped and its **header kept**, which is what a skipped probe
   * leaves behind.
   *
   * The body has to go, and not merely the blocks: a `c const` is what the declarations around it are
   * written in terms of, and an unmeasured one cannot be analyzed or encoded — `AstCodec` says so
   * outright, since a block reaching it means a path skipped this pass.
   *
   * **The header stays so the module goes on being answerable.** `Capabilities.record` reads the
   * clause and `GatedModules` reports at the reference that reached it, so a program naming this
   * module on a target that cannot have it still gets *"this reaches 'x', which requires 'posix'"* —
   * which is the diagnostic a reader can act on. Dropping the file outright would answer them with
   * an undefined name instead, and send them looking for a typo.
   */
  private def emptied(unit: Program): Program = unit.copy(body = Nil)

  private def lowerUnit(unit: Program, target: Target, paths: SearchPaths)
      : Either[String, Program] = {
    val consts = unit.body.collect { case b: CConstBlock => b }.flatMap(_.consts)
    val types  = unit.body.collect { case b: CTypeBlock => b }.flatMap(_.types)

    if consts.isEmpty && types.isEmpty then Right(unit)
    else
      for
        plan    <- traverse(consts)(c => declaredType(c, unit, types, target).map(c -> _))
        answers <- measure(unit, plan, types, target, paths)
        kinds   <- traverse(plan) { case (c, d) => measuredKind(c, d, answers).map(c -> _) }
        folded  <- traverse(kinds.zip(answers.values)) { case ((c, k), raw) => literal(c, k, raw) }
        aliases <- traverse(types.zip(answers.types)) { case (t, m) => alias(t, m, answers.charSigned) }
      yield
        // Consumed **in order** rather than looked up by name. A file may declare the same name in
        // two blocks — which the analyzer refuses, and refuses on the merits — and a lookup would
        // have quietly given both lines the same answer first, so what got reported was a duplicate
        // of a declaration one of them never had.
        val remainingConsts = scala.collection.mutable.Queue.from(folded)
        val remainingTypes  = scala.collection.mutable.Queue.from(aliases)

        unit.copy(body = unit.body.flatMap {
          case b: CConstBlock => b.consts.map(_ => remainingConsts.dequeue())
          case b: CTypeBlock  => b.types.map(_ => remainingTypes.dequeue())
          case other          => List(other)
        })
  }

  /** What one probe answered: a value per constant and a measurement per type, in the order the
   * lines were written, plus the one answer the whole probe shares.
   */
  private case class Answers(values: List[BigInt], types: List[Measured], charSigned: Boolean)

  /** One C type as the probe describes it: which integer C says it is, and how wide that is here. */
  private case class Measured(kind: Int, size: Int)

  /** What the C compiler said, asked once for the whole file.
   *
   * A header that will not compile is a real possibility here — the wrong `-D`, a missing sibling
   * header — so clang's own words are quoted rather than paraphrased, and the message says which
   * kind of block was being measured so that a reader knows which lines to look at.
   */
  private def measure(unit: Program, plan: List[(CConstDecl, Declared)], types: List[CTypeDecl],
                      target: Target, paths: SearchPaths): Either[String, Answers] = {
    val src = createTempFile("sysl-cprobe-", ".c")

    try
      writeFile(src, probe(unit, plan, types))

      Toolchain.findClang(target).flatMap { cc =>
        val command = Seq(cc, s"--target=${target.triple}", "-S", "-emit-llvm", "-O0") ++
          Toolchain.machineFlags(target) ++
          Option.when(target.shortEnums)("-fshort-enums") ++ paths.defineFlags ++
          beside(unit) ++ paths.includeFlags ++ Seq("-o", "-", src)

        val result = exec(command)
        val where  = plan.headOption.flatMap(_._1.pos).orElse(types.headOption.flatMap(_.pos))

        if result.exitCode != 0 then
          Left(Diagnostic.render(
            s"the C compiler refused this file's ${blocks(plan, types)}:\n${result.stderr.trim}",
            where))
        else
          val values = read(constEmitted, result.stdout)
          val sizes  = read(sizeEmitted, result.stdout)
          val whichs = read(kindEmitted, result.stdout)

          for
            found <- traverse(plan.zipWithIndex) { case ((c, _), i) =>
              values.get(i).toRight(silent(s"'${c.c}'", c.pos))
            }
            measured <- traverse(types.zipWithIndex) { case (t, i) =>
              for
                k <- whichs.get(i).toRight(silent(s"'${t.c}'", t.pos))
                s <- sizes.get(i).toRight(silent(s"'${t.c}'", t.pos))
              yield Measured(k.toInt, s.toInt)
            }
          yield Answers(found, measured, charSigned(result.stdout))
      }
    finally try deleteFile(src) catch case _: Exception => ()
  }

  /** Whichever blocks this file actually wrote, for the refusal to name — a message about a `c const`
   * block sends a reader to the wrong lines when what the file has is a `c type`.
   */
  private def blocks(plan: List[(CConstDecl, Declared)], types: List[CTypeDecl]): String =
    if plan.isEmpty then "'c type' block"
    else if types.isEmpty then "'c const' block"
    else "'c const' and 'c type' blocks"

  /** The C compiled and the probe emitted nothing for a line, which is sysl's bug and not the
   * programmer's — so the message says so rather than sending them back to their own C.
   */
  private def silent(what: String, pos: Option[Pos]): String =
    Diagnostic.render(
      s"the C compiler accepted $what and then emitted nothing for it, so there is no answer to " +
        "read — this is a bug in sysl's probe rather than in what was written",
      pos)

  private def read(pattern: scala.util.matching.Regex, ir: String): Map[Int, BigInt] =
    pattern.findAllMatchIn(ir).map(m => m.group(1).toInt -> BigInt(m.group(2))).toMap

  /** Whether plain `char` is signed here. A probe with no `c type` in it never asks, and nothing
   * reads the answer in that case.
   */
  private def charSigned(ir: String): Boolean =
    charEmitted.findFirstMatchIn(ir).exists(_.group(1) != "0")

  /** The translation unit put to clang.
   *
   * Every constant's global is **declared at the width the answer is read at** rather than at the
   * expression's own, which is what makes one regex enough: a `sizeof` is `size_t` and a macro may
   * be anything at all, and both become `i64` here. The cast is explicit so that narrowing is the C
   * compiler's decision and shows up in its own warnings rather than in sysl's parsing.
   *
   * Signedness follows the **declared sysl type**, so a constant a program asked for as `i32` is
   * printed as the negative number it is instead of arriving as a very large positive one.
   *
   * **A constant declared at a `c type` is carried unsigned**, because the signedness that would
   * have chosen the carrier is one of the answers this same probe is being asked for. Nothing is
   * lost by it: the IR prints an `i64` bit pattern whichever carrier is written, so the reading is
   * the same number once the measurement says how to read it.
   *
   * **It is not cast through the C type**, though that reads as the faithful thing to do. C would
   * *narrow* — `(uint8_t)800` is `32` — and the value would then pass a range check it should have
   * failed, which is the one refusal this feature exists for. The declared type is a claim about the
   * value, so the value has to arrive whole enough to contradict it.
   *
   * A type is described through an **object of it**, because that is the one form that stays legal
   * whatever the typedef turns out to be: `_Generic` wants a complete type and nothing more, so a
   * struct reaches the `default` arm instead of failing to compile, while a cast — `(T)0` — would
   * have been a C error for exactly the types the refusal below is written for.
   */
  private def probe(unit: Program, plan: List[(CConstDecl, Declared)], types: List[CTypeDecl]): String = {
    val headers = unit.includes.map(i => s"#include ${quoted(i.header)}").mkString("\n")

    val constants =
      plan.zipWithIndex.map {
        case ((c, Declared.Now(k)), i) =>
          val ty = if k.signed then "long long" else "unsigned long long"

          s"$ty $constMarker$i = ($ty)(${c.c});"
        case ((c, Declared.Later(_, _, _)), i) =>
          s"unsigned long long $constMarker$i = (unsigned long long)(${c.c});"
      }

    val typedefs =
      types.zipWithIndex.flatMap { case (t, i) =>
        List(
          s"static ${t.c} $objMarker$i;",
          s"long long $kindMarker$i = _Generic($objMarker$i,$genericArms);",
          s"long long $sizeMarker$i = (long long)sizeof(${t.c});",
        )
      }

    // Asked once for the whole probe rather than once per type, since it is a fact about the machine
    // and not about any typedef — and asked at all only where a `c type` might read it.
    val plainChar = if types.isEmpty then Nil else List(s"long long $charMarker = (char)-1 < 0;")

    (List(headers) ++ constants ++ typedefs ++ plainChar).mkString("", "\n", "\n")
  }

  /** The `_Generic` association list, and the numbering the answers are read back with. `0` is the
   * `default` arm, which is every type this feature does not resolve.
   */
  private val genericArms =
    List("_Bool" -> 1, "char" -> 2, "signed char" -> 3, "unsigned char" -> 4, "short" -> 5,
      "unsigned short" -> 6, "int" -> 7, "unsigned int" -> 8, "long" -> 9, "unsigned long" -> 10,
      "long long" -> 11, "unsigned long long" -> 12)
      .map((c, n) => s" $c: $n").mkString(",") + ", default: 0"

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

  /** What a `c const`'s written type turns out to be, as far as the file alone can say — which is
   * all of it for a primitive and half of it for a measured one.
   *
   * A `c type` in the same file is what the two blocks were built to be used as a pair for, and it
   * is **circular against the probe**: the constant's global has to be written before clang runs,
   * and how wide it should be written is one of the answers clang is being asked for. `Later` is
   * that knot untied — the value is carried at the full unsigned width, and the same probe's
   * measurement says how to read it and what range to hold it to.
   */
  private enum Declared {
    case Now(kind: Kind)
    case Later(index: Int, c: String, written: String)
  }

  /** The declared type of a `c const`, held to being an integer — or a transparent subtype of one.
   *
   * **The restriction is deliberate and the diagnostic says so rather than leaving it to be found.**
   * A `string` constant is a global array in the IR and a different job entirely, and it would also
   * have to be written `"\"foo\""` — two quotings for one value, which is a form nobody would guess.
   * A float is the same shape of problem read back through a different printing. Both are worth
   * having and neither is worth guessing at, so the refusal names them.
   *
   * **A name is followed rather than refused**, because `16 §1` says a transparent subtype *is* its
   * base and this pass was the one place in the language where that was not true — which left the
   * two blocks unable to describe the case they were both built for, a typedef whose width the
   * config decides and the constants that have to be that width. What is followed is this file's own
   * declarations: a `c type` the same probe measures, or a `type` whose base reaches an integer.
   *
   * **A name from another module is refused by name.** A block is one question put to one file's
   * headers, and both halves of it are asked in the same probe; a type measured against some other
   * file's headers is not an answer this one can use.
   *
   * Whether the subtype's own constraint *admits* the value is not asked here and cannot be: a
   * `within` bound may be written over a `const` (`16`), so no bound is folded until the analyzer
   * has run. `ConstFolding.constType` is where the declared type and the measured value meet, and it
   * is where `new` and a `where` predicate are refused.
   */
  private def declaredType(c: CConstDecl, unit: Program, types: List[CTypeDecl], target: Target)
      : Either[String, Declared] = {
    given Word = target.word

    def refuse(message: String) = Left(Diagnostic.render(message, c.pos))

    val notInteger = refuse(
      s"'${c.typ.show}' is not an integer, and a 'c const' reads an integer — the value is read " +
        "back out of the C compiler's own output, where an integer is a number and a string is a " +
        "block of storage. A string or a float from C is not written this way yet")

    def follow(ref: TypeRef, seen: Set[String]): Either[String, Declared] = ref match
      case NamedType(name, Nil) =>
        Type.scalars.get(name).orElse(width(name)) match
          case Some(Type.Integer(bits, signed, _)) => Right(Declared.Now(Kind(bits, signed, name)))
          case Some(_)                             => notInteger
          case None if seen(name) =>
            refuse(s"'$name' is declared in terms of itself, so which integer it is has no answer")
          case None =>
            types.indexWhere(_.name == name) match
              case measured if measured >= 0 =>
                Right(Declared.Later(measured, types(measured).c, c.typ.show))
              case _ =>
                unit.body.collectFirst { case t: TypeDecl if t.name == name => t } match
                  case Some(t) => follow(t.base, seen + name)
                  case None =>
                    refuse(s"'$name' is not a type this file declares, and a 'c const' is measured " +
                      "against its own file — so its type is an integer, a 'c type' written beside " +
                      "it, or a subtype of one declared here")
      case _ => notInteger

    follow(c.typ, Set.empty)
  }

  /** The `Kind` a declaration turns out to have once the probe has answered, which for a primitive
   * is the one it always had.
   *
   * **A measured type that is not an integer is refused here as well as by `alias`**, and has to be:
   * the answers are consumed in written order, so a constant reading a `_Bool` typedef is told about
   * the constant rather than about a type declaration that is perfectly good on its own.
   */
  private def measuredKind(c: CConstDecl, d: Declared, answers: Answers): Either[String, Kind] =
    d match
      case Declared.Now(k) => Right(k)
      case Declared.Later(i, cType, written) =>
        integerKind(answers.types(i), answers.charSigned) match
          case Some((bits, signed)) => Right(Kind(bits, signed, written))
          case None =>
            Left(Diagnostic.render(
              s"'$written' is what the C compiler calls '$cType', which is not an integer here, and " +
                "a 'c const' reads an integer",
              c.pos))

  /** Which integer a measurement is, and how wide. `None` covers the two answers that are not one: a
   * `_Bool`, which a `c type` resolves and a constant cannot be read as, and the `default` arm,
   * which is every type this feature does not resolve at all.
   */
  private def integerKind(m: Measured, charSigned: Boolean): Option[(Int, Boolean)] = {
    val bits = m.size * 8

    m.kind match
      case 2                   => Some((bits, charSigned))
      case 3 | 5 | 7 | 9 | 11  => Some((bits, true))
      case 4 | 6 | 8 | 10 | 12 => Some((bits, false))
      case _                   => None
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

  /** One measured typedef as the declaration it becomes: a transparent subtype of the integer C says
   * it is, carrying neither a range nor a predicate, which is precisely what a typedef means — a
   * second name for one integer, interchangeable with it and checking nothing of its own.
   *
   * **A type C does not describe as an integer is refused by name rather than approximated.** A
   * pointer, a float, a struct and an array all reach the `default` arm, and each already has an
   * answer in sysl — an address, a float by name, an `opaque struct` — that is better than a
   * same-width integer standing in for it and losing what it was.
   *
   * `_Bool` is the one answer that is not an integer and is still resolved, because sysl's `bool`
   * *is* what C means by it: `CAbi` already crosses one as a single unsigned byte, widened as the
   * target's convention says. Resolving it to `u8` instead would be a wider claim than the header
   * made.
   */
  private def alias(t: CTypeDecl, m: Measured, charSigned: Boolean): Either[String, TypeDecl] = {
    def named(name: String) =
      Right(TypeDecl(t.name, NamedType(name), derived = false, None, None, t.vis, fromC = true)
        .setPos(t.pos))

    if m.kind == 1 then named("bool")
    else
      integerKind(m, charSigned) match
        case Some((bits, signed)) => named(s"${if signed then "i" else "u"}$bits")
        case None =>
          Left(Diagnostic.render(
            s"the C compiler says '${t.c}' is not an integer type, and a 'c type' names an integer " +
              "— what comes back from one is a width and a signedness, which a float, a pointer, a " +
              "struct or an array has not got. An address is written '*T', a float by name, and a " +
              "struct arrives as an 'opaque struct'",
            t.pos))
  }

  /** The first refusal, or every answer — `Either`'s `traverse`, which the standard library has no
   * name for and which this file wants four times.
   */
  private def traverse[A, B](xs: List[A])(f: A => Either[String, B]): Either[String, List[B]] =
    xs.foldLeft[Either[String, List[B]]](Right(Nil))((soFar, x) =>
      soFar.flatMap(done => f(x).map(done :+ _)))
}
