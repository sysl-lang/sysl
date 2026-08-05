package sh.sysl

/** Operators, which are trait-method calls with a token for a name (`14 §3`).
 *
 * `a + b` means `Add::add(a, b)` and type-checks iff the type of `a` satisfies `Add` **at the type
 * of `b`** — one rule, applied the same way whether the operand is a scalar with a
 * compiler-provided impl, a user type with a written one, or a bounded type parameter whose impl
 * arrives at monomorphization. What differs between those three is only where the impl comes from,
 * never the rule, which is why this is one file and not three cases scattered through the analyzer.
 *
 * Two things here are not simply "look up the method". A comparison chain has to hold the *link*
 * between operands rather than a `bool`, because the middle operand of `a < b < c` is one value
 * compared twice. And the catalogue declares no method for four of the six comparisons: `>` is `<`
 * with the operands exchanged, `>=` is `<` negated, so a `TDispatch` carries a swap and a negate and
 * one written `lt` serves all four.
 */
trait OperatorCalls extends MethodCalls {

  // --- operators as trait methods --------------------------------------------------------

  /** `a ⊕ b` where `⊕`'s operands are not something the machine has an instruction for (`14 §3`).
   *
   * A built-in keeps its instruction and this yields `None`, leaving the ordinary scalar path to
   * lower it — that is `§5`'s promise that a membership changes no codegen, and it is also what
   * keeps a float comparison on IEEE semantics instead of routing it through a negated `lt`.
   *
   * Everything else resolves to the one method the operator's trait requires: a bounded type
   * parameter to the trait's own signature (checked at the definition, then bound by
   * monomorphization), a user type to the member its `impl` produced. `None` for an operand type
   * with no membership either way, so the diagnostic stays the one the scalar path already gives.
   */
  protected def operatorCall(op: String, l: TExpr, r: TExpr): Option[TExpr] =
    CoreTraits.infix.get(op).flatMap(trName => traitOperator(trName, op, Some(r), l))

  /** `-a`, `~a` — the same, for the two prefix operators. */
  protected def prefixCall(op: String, t: TExpr): Option[TExpr] =
    CoreTraits.prefix.get(op).flatMap(trName => traitOperator(trName, op, None, t))

  /** `spelling` is the trait as `CoreTraits` names it, which is how a program writes it; the key the
   * tables hold it under is the library's for that spelling.
   */
  private def traitOperator(spelling: String, op: String, rhs: Option[TExpr], recv: TExpr): Option[TExpr] = {
    val trName = Library.key(spelling)
    val tr     = opBound(trName, recv.ty, rhs.map(_.ty))

    dispatchFor(spelling, op, recv.ty, tr).map { d =>
      val self = opSubst(trName, recv.ty, tr)
      val m    = traitDecls(trName).methods.find(_.name == CoreTraits.required(spelling)._1).get

      for b <- rhs do checkOperand(op, recv.ty, b, resolveType(m.params.head.typ, self))

      val rty  = m.retType.map(resolveReturn(_, self)).getOrElse(Type.Unit)
      val args = rhs.toList
      val call = TCall(d.name, if d.swap then args :+ recv else recv :: args, rty)

      if d.negate then TUnary("!", call, Type.Bool) else call
    }
  }

  /** The trait an operator asks of its **pair** of operands: the catalog's binary arithmetic traits
   * take the right-hand type as an argument (`14 §7`), so `c * 2.0` asks for `Mul[f64]` while
   * `a * b` on one type asks for that type's own `Mul`.
   *
   * A trait that takes no parameters — `Eq`, `Ord`, and the two prefix operators — is asked for
   * bare, which is what keeps a comparison homogeneous.
   *
   * **The result is an argument too, and a use cannot supply it.** `Mul[Rhs = Self, Out = Self]`
   * declares two, and `a * b` names only the first: what `*` gives back is what the implementation
   * says it gives back, which is the whole reason `Out` is carried as an argument rather than fixed
   * to `Self`. So the operand is the **selector** and the matched implementation supplies the rest —
   * `implByOperand` — and where nothing is written the defaults fill in, which is what keeps a
   * built-in and a bounded type parameter answering exactly as they did when there was one parameter.
   */
  private def opBound(trName: String, subject: Type, rhs: Option[Type]): Type.Bound =
    traitDecls.get(trName) match
      case Some(d) if d.tparams.nonEmpty =>
        val selector = rhs.toList

        // A type parameter has no implementation to read the result off, so what supplies it is the
        // parameter's own **bound** — the same prefix rule, asked of a promise instead of a block:
        // `[T: Mul[T, real]]` is what tells a body multiplying two `T`s that it gets a `real`. The
        // defaults fill in for a parameter whose bound named no arguments, which keeps a bare
        // `[T: Mul]` the homogeneous reading it has always been.
        val fromBound = subject match
          case a: Type.Abstract =>
            a.bounds.iterator
              .flatMap(traitClosure(_, selfBinding(a)))
              .find(b =>
                b.name == trName && b.args.length == d.tparams.length &&
                  Type.Bound(trName, selector ++ b.args.drop(selector.length)).key == b.key)
          case _ => implByOperand(trName, subject, selector, d)

        val found = fromBound
          .getOrElse(Type.Bound(trName, sandboxed(withDefaults(trName, d.tparams, d.tdefaults, selector,
            selfBinding(subject)))))

        Type.Bound(found.name, found.args.map(asOperand(subject)))
      case _ => Type.Bound(trName, Nil)

  /** The subject put back where a bound named it, which is what keeps the *result* of one dispatched
   * operator usable as the operand of the next.
   *
   * A trait's own bounds are resolved with the parameter's bounds dropped one level in, so that a
   * bound reaching back around to its own parameter terminates. That leaves the `Self` in the closure
   * a bare `T` carrying no promises — and reading a result off it would make `(x & y) ^ z` in a
   * `[T: BitAnd + BitXor]` body fail at the `^`, the first operator whose operand came from another
   * one rather than from a parameter. It is the same `T` either way, so the one with its bounds is the
   * one to carry forward.
   */
  private def asOperand(subject: Type)(arg: Type): Type = (subject, arg) match
    case (s: Type.Abstract, a: Type.Abstract) if a.name == s.name && a.bounds.isEmpty => s
    case _                                                                            => arg

  /** The substitution a catalog method's signature is read under at an operand pair: `Self` is the
   * left operand's type, and the trait's parameters are the arguments the selected implementation
   * turned out to be written at — which is what binds `Out` to a result the use site never named.
   */
  private def opSubst(trName: String, lhs: Type, tr: Type.Bound): Map[String, Type] =
    selfBinding(lhs) ++ traitDecls.get(trName).toList.flatMap(_.tparams).zip(tr.args)

  /** Which method an operator on `ty` dispatches to, or `None` when the machine has an instruction
   * for it — the one dispatch rule of `14 §3`, in the form the operand-sharing lowerings need.
   *
   * A built-in keeps its instruction. A bounded type parameter answers with the **trait's** own
   * method: which implementation runs is monomorphization's to decide, and a bound the parameter does
   * not carry is reported here, at the definition, which is what `14 §4` is for. A user type answers
   * with the member its `impl` produced. A type with no membership either way answers `None`, so the
   * diagnostic stays the one the scalar path already gives.
   */
  private def dispatchFor(spelling: String, op: String, ty: Type, tr: Type.Bound): Option[TDispatch] = {
    val trName         = Library.key(spelling)
    val (swap, negate) = CoreTraits.derivation.getOrElse(op, (false, false))
    val method         = CoreTraits.required(spelling)._1
    // Naming a function is reaching it, and the record is kept **here** rather than at the three
    // callers because this is the one place a name is produced. A library's declaration is analyzed
    // and emitted only once something has reached it, so a name resolved and not recorded is a call
    // to a function the program never compiled — which the linker reports and no test does. Two of
    // the callers hand the name over as data instead of building a call around it (`TDispatch`), and
    // a record kept per caller is one each of them has to remember to keep.
    def reached(d: TDispatch): TDispatch = { funcsUsed += d.name; d }
    // The right operand as the diagnostics below name it — the selector argument, which is every
    // argument but the result the implementation supplies.
    val rhs            = tr.args.take(math.max(0, tr.args.length - 1)).headOption

    if CoreTraits.builtin(spelling, ty) then None
    else
      ty match
        case a: Type.Abstract =>
          if !satisfies(tr, a) then boundErr(s"'$op' needs '${a.name}: ${showBound(tr, a)}'")
          Some(reached(TDispatch(s"$trName.$method", swap, negate)))
        // The implementation is named by the rule a method call uses, which for a generic type is
        // the instantiation the receiver's own arguments make — `a + b` on a `Box[int]` reaches the
        // same function `a.add(b)` would. Where the type implements the trait more than once it is
        // the operand pair that says which, and the pair is what this bound was built from.
        case _ if satisfies(tr, ty) => Some(reached(TDispatch(traitMemberName(ty, tr, method), swap, negate)))
        // A type that implements the operator's trait, but not at *this* pair of operands, is worth
        // saying so about here. Falling through would leave the scalar path's "operands must match",
        // which is advice to change the operands when the answer is to implement the trait for them.
        case _ =>
          for why <- unmetBound(tr, ty) do
            val between = rhs.fold(show(ty))(r => s"${show(ty)} and ${show(r)}")

            err(s"'$op' between $between needs '${showBound(tr, ty)}' — $why")
          None
  }

  /** The other operand of a dispatched binary operator, against what the trait's signature asks for
   * — `Self` for a homogeneous trait, and the argument the bound was found at for one that takes a
   * right-hand type.
   */
  private def checkOperand(op: String, recvTy: Type, b: TExpr, want: Type): Unit =
    at(b.pos):
      if disagree(b.ty, want) then err(s"'$op' needs matching types, got ${show(recvTy)} and ${show(b.ty)}")

  /** One link of a comparison chain: the operator, plus the method performing it where the operand
   * type reaches `Eq`/`Ord` through a trait.
   *
   * What this hands codegen is a **name**, not a call tree, and that is the whole reason it exists.
   * A chain compares each middle operand against both its neighbours from a single evaluation, so a
   * dispatched comparison has to read the value codegen is already holding; a `TCall` wrapped around
   * the operand's own tree would evaluate it again.
   */
  protected def compareLink(op: String, l: TExpr, r: TExpr): TCmp = {
    val spelling = CoreTraits.infix(op)
    val trName   = Library.key(spelling)
    val tr       = opBound(trName, l.ty, Some(r.ty))

    TCmp(
      op,
      dispatchFor(spelling, op, l.ty, tr).map { d =>
        val m = traitDecls(trName).methods.find(_.name == CoreTraits.required(spelling)._1).get
        checkOperand(op, l.ty, r, resolveType(m.params.head.typ, opSubst(trName, l.ty, tr)))
        d
      },
    )
  }

  /** `place op= value` where `op` is a trait method — the same dispatch `place op value` makes,
   * handed over as a name so codegen applies it to the value it already loaded from the place
   * rather than reading the place a second time.
   *
   * The result is the place's own type by construction: the catalog declares every arithmetic method
   * as `(self, rhs: Self) -> Self`, and an `impl` conforms to that exactly, so a compound assignment
   * through one cannot change the type of what it updates.
   */
  /** The right-hand type a type parameter's own **bounds** name for an operator, where one of them
   * names that operator's trait at all.
   *
   * It is what decides a bare literal beside a parameter, during the definition-time pass, and
   * neither answer is forced by the language: `x - 1` in a `[T: Sub]` body means `T`'s own
   * subtraction, while `x * 2.0` in a `[T: Mul[f64]]` body means the `real` that bound names. The
   * bounds are the only thing in scope that knows which, so they are asked. A parameter carrying no
   * bound for the operator at all is left homogeneous, because that is the reading whose diagnostic
   * — write `T: Mul` — is the one that helps.
   */
  private def boundRhs(op: String, a: Type.Abstract): Option[Type] =
    for
      trName <- CoreTraits.infix.get(op).map(Library.key)
      b      <- a.bounds.iterator.flatMap(traitClosure(_, selfBinding(a))).find(_.name == trName)
      arg    <- b.args.headOption
    yield arg

  /** The right-hand operand of a binary operator, re-read where the left is a type parameter whose
   * bounds name a different right-hand type.
   *
   * A bare literal has already taken the parameter's own type by then, since that is what a literal
   * does beside a typed neighbour. Reading it again against the bound's argument is what gives the
   * `2.0` in a `[T: Mul[f64]]` body the `real` that bound asked for.
   */
  protected def operandRhs(op: String, l: TExpr, r: Expr, t: TExpr): TExpr =
    l.ty match
      case a: Type.Abstract if isLiteral(r) && t.ty == a =>
        boundRhs(op, a).filterNot(_ == a).fold(t)(want => analyzeExpr(r, Some(want)))
      case _ => t

  /** What a compound assignment's right-hand side is read as.
   *
   * A scalar's `+=` is homogeneous, so the place's type is what the value should be — that is what
   * makes the `1` in `n += 1` a literal of the place's own width. An operator that **dispatches**
   * may take a right-hand type of its own (`14 §7`), and there the place's type is the wrong thing
   * to read the value as: `c *= 2.0` on a complex number wants a `real`, and offering it `Complex`
   * would hand the literal a type it cannot be.
   *
   * A **transparent** subtype is homogeneous at its *base*, which `repr` is: what `t += 120` adds is
   * an ordinary integer, and reading it as the subtype would check the step against a range that
   * belongs to the total. `t += 120` on a `Temp = int within -100..100` holding -50 is 70, and
   * refusing it because 120 is not a temperature is refusing the sum for what the addend is.
   */
  protected def updateExpected(op: String, placeTy: Type): Option[Type] =
    CoreTraits.infix.get(op) match
      // A parameter is the one place-type that may be either, and its bounds are what say which.
      case Some(spelling) =>
        placeTy match
          case a: Type.Abstract                           => Some(boundRhs(op, a).getOrElse(a))
          case _ if CoreTraits.builtin(spelling, placeTy) => Some(Type.repr(placeTy))
          case _                                          => None
      case _ => Some(placeTy)

  protected def updateDispatch(op: String, place: TExpr, value: TExpr): Option[TDispatch] =
    for
      spelling <- CoreTraits.infix.get(op)
      trName = Library.key(spelling)
      tr     = opBound(trName, place.ty, Some(value.ty))
      d <- dispatchFor(spelling, op, place.ty, tr)
    yield {
      val subst = opSubst(trName, place.ty, tr)
      val m     = traitDecls(trName).methods.find(_.name == CoreTraits.required(spelling)._1).get

      checkOperand(op, place.ty, value, resolveType(m.params.head.typ, subst))

      // `a op= b` is `a = a op b` (`14 §2`), so an operator whose result is not the left operand's
      // type has nothing to assign back. The compound form is refused rather than quietly changing
      // what the place holds, and the reader is pointed at the spelling that says which type it means.
      for want <- m.retType.map(resolveReturn(_, subst)) if disagree(want, place.ty) do
        err(s"'$op=' updates ${show(place.ty)} in place, but '$op' between ${show(place.ty)} and " +
          s"${show(value.ty)} gives ${show(want)} — write the assignment out to say where it goes")

      d
    }
}
