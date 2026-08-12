package sh.sysl

/** Holds a definition carrying a calling convention to what the **processor being built for** allows
 * (`15 §10`).
 *
 * Every rule here is the machine's rather than the language's, which is why the target is a
 * parameter of the analysis at all. There is no portable answer to check against: `interrupt` is a
 * calling convention on x86-64, a function attribute on RISC-V, and absent from AArch64, and the two
 * that have it disagree about the signature — x86-64's ABI requires the frame pointer the hardware
 * pushed, RISC-V's requires no parameters whatsoever.
 *
 * **The refusals are the point.** Clang answers an interrupt attribute on AArch64 with a warning and
 * compiles an ordinary function, which is defensible for C and is not defensible here: the handler
 * then returns with `ret` where the machine needs `eret`, and having not saved the registers an
 * asynchronous entry clobbers, it corrupts whatever it interrupted. Nothing later in the build says
 * a word about it. So a convention the target does not have stops the compilation, exactly as a
 * mistyped one does.
 */
trait ConventionCheck extends TypeResolution {

  /** Holds every declaration carrying a convention to what this processor allows.
   *
   * **A declaration pass, not part of the body walk**, and that is the whole reason it is written
   * here rather than beside the code that checks a function's statements. Every rule is about the
   * *signature*, and the signature exists whether or not the body is ever analyzed — a generic
   * handler nothing instantiates has no body walked at all, and it is exactly as wrong as one that
   * does. Checking where the declarations are enumerated is what makes the answer independent of
   * whether anything reached it.
   */
  protected def checkConventions(): Unit =
    for
      (_, f) <- funcDecls
      c      <- f.conv
    do recover(())(at(c.pos)(checkConvention(c, f)))

  /** Refuses a convention that is not one, that this processor does not have, that was written with
   * a mode where there are no modes, or that is on a function whose shape the ABI will not take.
   */
  private def checkConvention(c: CallConv, f: FuncDecl): Unit = {
    if !Conventions.known.contains(c.name) then
      err(s"'${c.name}' is not a calling convention — " +
        s"${Conventions.known.map(n => s"'$n'").mkString(", ")} is what there is")

    Conventions.interruptForm(target.cpu) match
      // The reason comes from the table rather than from here, because the processors without an
      // interrupt form are without one for unrelated reasons and a shared sentence can be true of
      // only one of them.
      case Left(why) =>
        err(s"'${c.name}' is not something ${target.cpu.toString.toLowerCase} has: $why")

      case Right(form) =>
        checkMode(c)

        // Generic is refused before anything is resolved, because a type parameter has nothing to
        // resolve *to* out here — the refusal is about the declaration, and asking what `T` is would
        // be asking a question the declaration has made meaningless.
        if f.tparams.nonEmpty then
          err(s"an interrupt handler is a single entry point the processor jumps to, so '${f.name}' " +
            "cannot be generic — there would be no way to say which instantiation the vector holds")

        val params = f.params.map(p => (p.name, recover(Type.Unknown)(resolveType(p.typ, Map.empty))))
        val result = f.retType.map(t => recover(Type.Unknown)(resolveReturn(t, Map.empty))).getOrElse(Type.Unit)

        checkShape(form, f, result, params)
  }

  /** The parenthesised word, which only RISC-V has anything to put in.
   *
   * A mode on a processor with none is refused rather than dropped, because a reader who wrote
   * `interrupt(supervisor)` believed it was saying something — and on x86-64 the convention
   * describes the frame the hardware pushed, which has no privilege level in it to name.
   */
  private def checkMode(c: CallConv): Unit =
    (c.arg, Conventions.takesMode(target.cpu)) match
      case (Some(mode), true) if !Conventions.riscvModes.contains(mode) =>
        err(s"'$mode' is not a privilege mode — RISC-V has " +
          s"${Conventions.riscvModes.map(m => s"'$m'").mkString(", ")}, and an unwritten one is " +
          s"'${Conventions.defaultRiscvMode}'")

      case (Some(mode), false) =>
        err(s"'${c.name}($mode)' names a privilege mode, and ${target.cpu.toString.toLowerCase} has " +
          "none to name — its interrupt convention describes the frame the processor pushed, not " +
          "the level it was pushed at. Write 'interrupt' alone")

      case _ => ()

  /** The signature the ABI demands, which the two processors that have interrupts disagree about.
   *
   * Neither rule is sysl's invention and neither is negotiable: clang refuses an x86-64 handler
   * without the frame pointer in as many words, and a RISC-V handler is entered with nothing in the
   * argument registers that a parameter could have come from.
   */
  private def checkShape(form: Conventions.Form, f: FuncDecl, result: Type,
                         params: List[(String, Type)]): Unit = {
    // Nothing returns to. A handler is left through the processor's own return-from-interrupt, which
    // restores the interrupted context and has nowhere to put a value.
    if !Type.noValue(result) then
      err(s"an interrupt handler returns to whatever it interrupted, so it yields nothing — " +
        s"'${f.name}' declares ${show(result)}")

    if f.variadic then
      err(s"an interrupt handler is entered by the processor with a fixed frame, so '${f.name}' " +
        "cannot be variadic")

    form match
      // RISC-V enters the handler with the interrupted program's registers still live; there is no
      // argument to read, and a parameter would be whatever the interrupted code left behind.
      case Conventions.Form.Attribute(_) =>
        if params.nonEmpty then
          err(s"a RISC-V interrupt handler is entered with no arguments, so '${f.name}' takes none " +
            "— what the handler needs is read from the machine's own registers")

      // x86-64 pushes a frame and the ABI hands the handler its address; some vectors push an error
      // code after it. Clang refuses any other shape, so this refuses it here where the diagnostic
      // can say what the shape is for.
      case Conventions.Form.Convention(_) =>
        params.map(_._2) match
          case (_: Type.Ptr) :: rest if rest.length <= 1 && rest.forall(_.isInstanceOf[Type.Integer]) => ()
          case Nil =>
            err(s"an x86-64 interrupt handler is handed the frame the processor pushed, so " +
              s"'${f.name}' takes a pointer to it — 'interrupt ${f.name}(frame: *Frame)'")
          case _ =>
            err(s"an x86-64 interrupt handler takes a pointer to the frame the processor pushed, " +
              "optionally followed by the error code some vectors push after it — so at most " +
              s"'*T' and one integer, which is not what '${f.name}' declares")
  }
}
