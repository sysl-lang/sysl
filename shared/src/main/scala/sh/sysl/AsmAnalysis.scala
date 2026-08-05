package sh.sysl

/** Chooses an assembly statement's architecture arm and binds its operands (`inline-assembly.md`).
 *
 * Two questions are answered here and nowhere else. **Which arm applies** is a fact about the target
 * and so belongs to the analysis rather than to the emitter, which is then handed one arm and never
 * learns there were others. **Whether every architecture has an answer** is the check the construct
 * exists for: a processor with no arm is reported while building for a different one, so the gap is
 * found by whoever wrote it instead of by whoever first builds for the machine it left out.
 *
 * Nothing here reads an instruction. The text is carried through untouched, and every check is about
 * the operands beside it — which is the whole of what the compiler is in a position to know.
 */
trait AsmAnalysis extends TypeResolution {

  /** Resolves one `asm` statement to the arm that answers for this processor.
   *
   * The order matters and is not arbitrary: the arms are validated as a *set* first — every name
   * real, no processor claimed twice, none left out — and only then is one selected. A statement
   * whose arms disagree about which processors exist has a mistake in it that is worth reporting on
   * every build, not only on the build that happens to select the broken arm.
   */
  protected def analyzeAsm(stmt: AsmStmt): TStmt = {
    checkArchNames(stmt)
    checkNoOverlap(stmt)
    checkExhaustive(stmt)

    stmt.arms.find(_.archs.contains(target.cpu.symbol)) match
      case None => TAsm(Nil, Nil, Nil)

      case Some(arm) =>
        arm.body match
          // The reason travels from the arm into the diagnostic, which is the whole of what this form
          // buys over leaving the arm out: omitting it fails every build with "no arm for aarch64",
          // and writing it lets the builds that *do* have an answer succeed while this one is told
          // why it cannot.
          case AsmUnavailable(reason) =>
            at(arm.pos)(err(s"this operation has no answer on ${target.cpu.symbol}: $reason"))
            TAsm(Nil, Nil, Nil)

          case AsmCode(lines, operands, clobbers) =>
            val bound = operands.map(bindOperand)

            checkNoDoubleBinding(operands)
            checkPlaceholders(lines, operands, arm)
            TAsm(lines, bound.flatten, clobbers)
  }

  /** A processor name outside the closed set is a mistake rather than a machine nobody has heard of.
   *
   * This is `#if`'s rule, and it is here for `#if`'s reason: a name that read as "some other
   * processor" would leave the arm unselectable and the statement silently short an architecture,
   * which is the one failure the construct is built to prevent.
   */
  private def checkArchNames(stmt: AsmStmt): Unit =
    for
      arm  <- stmt.arms
      name <- arm.archs
      if !Cpu.values.exists(_.symbol == name)
    do
      at(arm.pos)(err(s"'$name' is not a processor — " +
        s"${Cpu.values.map(c => s"'${c.symbol}'").mkString(", ")} is what there is"))

  /** One processor, one arm. Two arms claiming the same one leave no rule for which wins, and a
   * reader would have to know the answer to know what the statement does.
   */
  private def checkNoOverlap(stmt: AsmStmt): Unit = {
    val seen = collection.mutable.Set.empty[String]

    for
      arm  <- stmt.arms
      name <- arm.archs
    do
      if seen(name) then
        at(arm.pos)(err(s"'$name' already has an arm above this one, so there is no saying which " +
          "of them answers for it"))
      else seen += name
  }

  /** Every processor a target can be built for needs an answer, whether or not it is the one being
   * built for now (`inline-assembly.md §2`). An architecture with nothing to say says
   * `unavailable` and why; leaving it out is the mistake this reports.
   */
  private def checkExhaustive(stmt: AsmStmt): Unit = {
    val covered = stmt.arms.flatMap(_.archs).toSet
    val missing = Cpu.buildable.filterNot(c => covered(c.symbol))

    if missing.nonEmpty then
      err(s"this assembly has no arm for ${missing.map(c => s"'${c.symbol}'").mkString(" or ")}. " +
        "Every processor needs an answer, so that one without instructions is found here rather " +
        "than by whoever first builds for it — write the instructions, write an empty arm if none " +
        "are needed there, or write 'unavailable' with the reason there is nothing to write")
  }

  /** Binds an operand to the local it names, refusing what cannot be one.
   *
   * A `None` is a bound that could not be made, and the statement is emitted without that operand
   * rather than with a broken one — the diagnostic has already been recorded, and a later pass
   * reading a half-bound operand would be a crash on top of a message the user already has.
   */
  private def bindOperand(op: AsmOperand): Option[TAsmOperand] =
    lookupOpt(op.name) match
      case None =>
        at(op.pos)(err(s"undefined name '${op.name}'. An assembly operand names a variable that " +
          "already exists — an expression needs somewhere to be evaluated to, so bind one first"))
        None

      // A `ref` names a place rather than a slot (`03 § ref`), so there is no storage of its own to
      // load from or store to — the operand would be emitted against an address that was never
      // allocated. Refusing it by name is what keeps that from being a module the assembler
      // rejects for reasons the source does not explain.
      case Some((slot, _)) if refPlaces.contains(slot) =>
        at(op.pos)(err(s"'${op.name}' is bound by 'ref', so it names storage somewhere else rather " +
          "than a variable of its own, and there is nothing here for an operand to be. Copy it " +
          "into a 'var' first, and write that back afterwards if the instructions set it"))
        None

      case Some((slot, ty)) =>
        if op.dir == AsmDir.Out && readOnlyLocals(slot) then
          at(op.pos)(err(s"'${op.name}' cannot be written by this assembly, because it is a 'val'. " +
            "Declare it 'var' if the instructions are meant to set it"))

        if !fitsRegister(ty) then
          at(op.pos)(err(registerFitMessage(op.name, ty)))

        Some(TAsmOperand(op.dir, op.name, slot, ty, op.reg))

  /** Whether a value of this type can live in a general-purpose register, which is the only class
   * there is. A trait object is two words and so is not one of them, which is worth its own answer
   * rather than being left to look like an ordinary pointer that happens to be refused.
   */
  private def fitsRegister(ty: Type): Boolean = ty match
    case _: Type.Integer                      => true
    case Type.Bool | Type.Char                => true
    case Type.Ptr(inner)                      => !inner.isInstanceOf[Type.Trait]
    case _                                    => false

  private def registerFitMessage(name: String, ty: Type): String = ty match
    case _: Type.Floating =>
      s"'$name' is a floating-point value, and there is no floating register class yet — only " +
        "'reg', which is the general-purpose one. Move the value through an integer of the same " +
        "width, or through memory"

    case Type.Ptr(inner) if inner.isInstanceOf[Type.Trait] =>
      s"'$name' is a trait object, which is two words — a pointer to the value and a pointer to its " +
        "table — so it does not fit a register. Pass whichever half the instructions need"

    case _ =>
      s"'$name' does not fit a general-purpose register, so it cannot be an assembly operand. " +
        "Operands are the integers, the pointers and 'bool'; anything larger goes through memory"

  /** Reading and writing one variable is two operands, and two operands may be two registers.
   *
   * So the spelling that looks like it says "read this, and write the answer back to it" would
   * compile to reading one register and writing a different one, which is a wrong answer with
   * nothing to point at. It is refused until there is a single operand that means it.
   */
  private def checkNoDoubleBinding(operands: List[AsmOperand]): Unit =
    for
      (name, both) <- operands.groupBy(_.name)
      if both.map(_.dir).distinct.length > 1
      second       <- both.drop(1)
    do
      at(second.pos)(err(s"'$name' is both read and written here, which is two operands and so " +
        "possibly two registers — the instructions would read one and write another. A single " +
        "operand that does both is not something this language has yet"))

  /** Every `{name}` in the instructions has to be an operand, and every operand has to be used.
   *
   * The first is the mistake worth catching: a placeholder nothing declares would reach the
   * assembler as a literal brace and fail there, with a message about the text rather than about
   * the name. The second is not an error — an operand exists to be substituted, but declaring one
   * the instructions do not name is at worst untidy, and at best is how a value is kept alive
   * across a block that reaches it another way.
   */
  private def checkPlaceholders(lines: List[String], operands: List[AsmOperand], arm: AsmArm): Unit = {
    val declared = operands.map(_.name).toSet

    for
      line <- lines
      name <- Asm.placeholders(line)
      if !declared(name)
    do
      at(arm.pos)(err(s"'{$name}' names an operand this assembly does not declare. Add " +
        s"'in $name : reg' or 'out $name : reg', or write the brace as part of an instruction " +
        "some other way"))
  }
}
