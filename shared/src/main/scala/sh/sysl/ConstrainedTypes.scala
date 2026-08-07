package sh.sysl

/** Building a constrained subtype from its declaration (`16`) — the `within` bounds folded to
 * constants, and the diagnostics for a bound that is not one.
 *
 * The bounds are their own area because what may stand in one is narrower than what may stand
 * anywhere else a constant is wanted: a literal, or a negated literal, of the base type. Saying
 * which kind of thing was written instead is most of what the code here does.
 */
trait ConstrainedTypes extends GenericInstantiation {

  protected def resolveConstrained(key: String): Type.Constrained =
    constrainedInsts.getOrElseUpdate(key, buildConstrained(key))

  private def buildConstrained(key: String): Type.Constrained = {
    val d = constrainedDecls(key)

    at(d.pos) {
      val base = resolveType(d.base, Map.empty)

      val scalar = Type.underlying(base) match
        case _: Type.Integer | _: Type.Floating | Type.Char => true
        case _                                              => false
      if !scalar then
        err(s"a constrained subtype's base must be an integer, a float, or 'char', not ${show(base)}")

      val (lo, hi) = d.range match
        case Some(r) =>
          val loV = boundValue(r.lo, base)
          val hiV = boundValue(r.hi, base)
          val ordered = if r.exclusiveHi then loV < hiV else loV <= hiV
          if !ordered then
            err(s"the lower bound of '${qn(key)}' is above its upper bound")
          (Some(loV), Some(hiV))
        case None => (None, None)

      // A transparent subtype with neither a range nor a predicate would be a plain alias, which is
      // not a form this cut accepts; `new` alone is enough, since it still changes the type's identity.
      if lo.isEmpty && d.pred.isEmpty && !d.derived then
        err(s"'${qn(key)}' has no constraint — add a 'within' range or a 'where' predicate, or 'new' to " +
          "make it a distinct type")

      val predFn = if d.pred.isDefined then Some(predKey(key)) else None
      Type.Constrained(key, base, d.derived, lo, hi, d.range.exists(_.exclusiveHi), predFn)
    }
  }

  /** One `within` bound as a constant, checked against the base's kind: a `char` base takes a
   * character, an integer base an integer that fits its width, a float base any number. A bound of the
   * wrong kind, or an integer out of the base's range, is an error.
   *
   * The bound is **folded first**, so a `const` — or an expression over constants — stands wherever a
   * literal does, which is what makes `within 0..<max_tasks` and `[max_tasks]Task` one fact rather than
   * two (`16 § Open b`). Folding is the same `fold` an array bound and an enum discriminant go through,
   * so the three positions accept exactly the same expressions and cannot drift apart. What does not
   * fold is reported as not being a constant, at the bound, rather than as a wrong *kind* — a name the
   * program never declared is a different mistake from a name that is not a number.
   */
  private def boundValue(e: Expr, base: Type): BigDecimal =
    fold(e) match
      case Some(folded) => boundLiteral(folded, base)
      case None =>
        err(s"a 'within' bound has to be a constant, and ${boundKind(e)} is not one — a literal, a " +
          "'const', or an expression over them")

  private def boundLiteral(e: Expr, base: Type): BigDecimal =
    Type.underlying(base) match
      case Type.Char =>
        e match
          case CharLit(cp) => BigDecimal(cp)
          case _           => err(s"a 'char' subtype needs character bounds, not ${boundKind(e)}")
      case i: Type.Integer =>
        val v = e match
          case IntLit(n, _)             => n
          case Unary("-", IntLit(n, _)) => -n
          case _                        => err(s"an integer subtype needs integer bounds, not ${boundKind(e)}")
        if !Type.fits(v, i) then err(s"the bound $v does not fit ${show(base)}")
        BigDecimal(v)
      case _ =>
        e match
          case IntLit(n, _)               => BigDecimal(n)
          case Unary("-", IntLit(n, _))   => BigDecimal(-n)
          case FloatLit(t, _)             => BigDecimal(t)
          case Unary("-", FloatLit(t, _)) => -BigDecimal(t)
          case _                          => err(s"a floating-point subtype needs numeric bounds, not ${boundKind(e)}")

  private def boundKind(e: Expr): String = e match
    case _: CharLit              => "a character"
    case _: FloatLit             => "a floating-point literal"
    case Unary("-", _: FloatLit) => "a floating-point literal"
    case _: IntLit               => "an integer"
    case Unary("-", _: IntLit)   => "an integer"
    case _: StrLit               => "a string"
    case _: BoolLit              => "a boolean"
    case Ident(n)                => s"'$n'"
    case _                       => "that"

  /** A trait named behind a memory-mode sigil, which is what makes the pointer a trait object
   * (`02`): `*Trait` raw and unmanaged, `&Trait` reference-counted. `None` for everything else,
   * including a type parameter that happens to be spelled like a trait — the substitution wins,
   * since that is what shadowing means everywhere else a name is resolved.
   */
}
