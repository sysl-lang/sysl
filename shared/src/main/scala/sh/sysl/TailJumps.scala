package sh.sysl

import TreeWalk.forEachStmt

/** `become f(…)` — what a call that **replaces** the frame has to be true of
 * (`reference/declarations.md § become — a call that replaces the frame`).
 *
 * `TailCalls` recognizes a function's calls to *itself* and lowers them as a jump back to its own
 * entry. That is a real optimization and it is not this: what a chain of calls to **different**
 * functions needs is a tail call that is *guaranteed*, because the whole program is one chain of
 * them and an un-eliminated call is an immediate stack overflow rather than a slowdown. LLVM's
 * `musttail` is that guarantee and LLVM verifies it, which is why the rules below are refusals
 * rather than conditions on an optimization: a `become` either compiles or is reported.
 *
 * **The forcing case is threaded dispatch.** The modern shape for a bytecode interpreter is one
 * function per opcode, each ending in a tail call to the handler for the next one, chosen out of a
 * table of function addresses. It beats a `switch`-in-a-loop for a reason that is about register
 * allocation rather than the branch — LLVM allocates per handler instead of spilling across one
 * enormous switch, and each handler gets its own branch-prediction history. CPython 3.14 shipped
 * exactly this. It is broadly useful beyond interpreters: state machines where each state is a
 * function, continuation-passing code, trampolines, protocol and lexer scanners, and mutual
 * recursion at any depth.
 *
 * ==The rules, and where each comes from==
 *
 * Two of them are LLVM's and are not negotiable. **The prototypes must match** — the same parameter
 * types in the same order and the same result — because that is what makes replacing the frame a
 * thing the machine can do at all; and the caller must not be **variadic**, since the tail a first
 * call was passed is in the frame being replaced.
 *
 * Two are `TailCalls`' own, arrived at there for the same reasons and stated the same way. An
 * **`ensures`** is checked when a call returns, and this one never returns; a **`defer`** runs on the
 * way out of a scope, and the way out is the jump.
 *
 * **And one is ARC's, which is the half the card called the real design work.** A frame's locals are
 * released along the return edge, and `musttail` replaces the frame *before* that edge is reached —
 * so the releases have to happen before the jump, while the arguments being handed over may be owned
 * by exactly those locals. The order codegen uses is the one the self-jump already uses: compute the
 * arguments, then release, then go. What makes that sound here and not there is the restriction
 * below: **no parameter may carry a reference count**. A counted argument read out of a slot the
 * release is about to let go of would be a use-after-free, and the callee retains its parameters for
 * itself, so retaining before the release would leak instead. Refusing the case is exact and
 * checkable; the alternative is a convention two frames have to agree on and only one of them can
 * see.
 */
object TailJumps {

  /** Every refusal a program's `become` statements earn. */
  def check(program: TProgram, target: Target): Either[List[Diagnostic], Unit] = {
    val funcs   = program.funcs.map(f => f.name -> f).toMap
    val externs = program.externs.map(_.name).toSet
    val layout  = Layout(target.word)
    val found   = program.funcs.flatMap(f => inBody(f, funcs, externs, layout))

    if found.nonEmpty then Left(found) else Right(())
  }

  private def inBody(f: TFunc, funcs: Map[String, TFunc], externs: Set[String],
                     layout: Layout): List[Diagnostic] = {
    val out = collection.mutable.ListBuffer.empty[Diagnostic]

    def refuse(at: Option[Pos], why: String): Unit =
      out += Diagnostic(s"'become' replaces this frame with the call's, and $why", at)

    // A `defer` anywhere in the body puts every `become` under it out of reach, exactly as it puts a
    // tail position out of reach: the deferred statement runs on the way out of the scope, and the
    // jump *is* the way out. Bounded to the body rather than to the block, which is coarser than
    // `TailCalls`' rule and deliberately so — a refusal that is easy to state is worth more here
    // than one that is exact, because what a reader does about it is the same either way.
    var deferred = false

    forEachStmt(f.body.stmts) {
      case TDefer(_)     => deferred = true
      case _             => ()
    }

    forEachStmt(f.body.stmts) {
      case TBecome(call) =>
        val at = position(call)

        if f.variadic then
          refuse(at, "this function is variadic — the tail a first call was passed lives in the " +
            "frame being replaced, and nothing can rebind it")
        else if f.ensures.nonEmpty then
          refuse(at, "this function has an 'ensures', which is checked when a call returns — a " +
            "'become' never returns, so the postcondition would go unchecked")
        else if f.variant.isDefined then
          refuse(at, "this function has a 'decreases' measure, which is checked against the frame " +
            "it was taken in — and that frame is the one being replaced")
        else if deferred then
          refuse(at, "this function has a 'defer', which runs on the way out of its scope — and " +
            "the jump is the way out, so there would be nowhere left to run it")
        else if f.params.exists((_, ty) => Type.containsCounted(ty)) then
          refuse(at, s"'${shown(f)}' takes a counted value — the frame's own references are let go " +
            "before the jump, so an argument read out of one of them would outlive nothing. Pass " +
            "what the callee needs through a raw pointer or a value that carries no count")
        else
          call match
            case c: TCall if externs(c.name) =>
              refuse(at, s"'${Modules.show(c.name)}' is an 'extern' — its frame is C's, and nothing " +
                "here knows what C does with one")
            case c: TCall =>
              funcs.get(c.name) match
                case None =>
                  refuse(at, s"'${Modules.show(c.name)}' has no body here, so there is no frame to " +
                    "replace this one with")
                case Some(g) => mismatch(f, g, layout).foreach(refuse(at, _))
            // A call through a function address is the case threaded dispatch is *for* — the next
            // handler comes out of a table. Nothing names the callee, so the prototype it is held
            // to is the pointer's own type, and that is what the analyzer already checked the
            // arguments against.
            case _: TCallPtr => shapeOf(f, layout).foreach(refuse(at, _))
            case _ =>
              refuse(at, "what follows it is not a call — 'return' is what hands a value back " +
                "without replacing anything")

      case _ => ()
    }

    out.toList
  }

  /** Why one function's frame cannot be replaced with another's, or nothing where it can.
   *
   * The prototype is the whole of it: same parameter types in order, same result. LLVM says so and
   * the reason it says so is the machine's — the arguments have to land where the replaced frame's
   * were, and a result has to come back the way this frame's caller is waiting for.
   */
  private def mismatch(f: TFunc, g: TFunc, layout: Layout): Option[String] =
    if g.variadic then
      Some(s"'${shown(g)}' is variadic and this function is not, so the two do not have the same shape")
    else if f.params.length != g.params.length then
      Some(s"'${shown(g)}' takes ${g.params.length} argument${plural(g.params.length)} and this " +
        s"function takes ${f.params.length} — a frame can only be replaced by one of the same shape")
    else if f.params.map(_._2) != g.params.map(_._2) then
      Some(s"'${shown(g)}' takes ${f.params.map((_, t) => Type.show(t)).mkString(", ")} where this " +
        s"function takes ${g.params.map((_, t) => Type.show(t)).mkString(", ")} — a frame can only " +
        "be replaced by one of the same shape")
    else if f.retTy != g.retTy then
      Some(s"'${shown(g)}' answers ${Type.show(g.retTy)} and this function answers " +
        s"${Type.show(f.retTy)} — the result comes back the way this frame's caller is waiting for it")
    else shapeOf(f, layout)

  /** What is true of the enclosing function alone, whichever callee it names.
   *
   * A **large result** travels through a pointer the caller supplied, and a frame being replaced is
   * a frame whose caller is somebody else. It could be passed through — the prototypes match, so the
   * pointer means the same thing on both sides — and it is refused instead because nothing has
   * measured it and a wrong answer here is a write into a frame that has gone.
   */
  private def shapeOf(f: TFunc, layout: Layout): Option[String] =
    Option.when(layout.indirect(f.retTy))(
      s"this function answers ${Type.show(f.retTy)}, which comes back through a pointer its caller " +
        "supplied — a result that large is not something a replaced frame can hand on. Answer " +
        "something that fits in a register, or a '&T'")

  private def shown(f: TFunc): String = Modules.show(f.name)

  private def plural(n: Int): String = if n == 1 then "" else "s"

  private def position(e: TExpr): Option[Pos] = e.pos
}
