package io.github.edadma.sysl

/** Match arms and patterns: turning each arm and pattern into its typed form, and checking that
 * a match is exhaustive. Pattern tests are pure value reads, so the exhaustiveness rule is the
 * only semantic subtlety — an enum match must cover every variant or carry a catch-all, while a
 * scalar match need only be exhaustive when it is used for a value.
 */
trait PatternAnalysis extends TypeResolution {

  /** Analyzes one arm in its own scope so pattern bindings are visible to the guard and body.
   * Alternatives (`a | b`) may not bind, since the body cannot know which alternative matched.
   */
  protected def analyzeArm(scrutTy: Type, arm: MatchArm, expected: Option[Type]): TArm = {
    pushScope()
    val tpats = arm.patterns.map(analyzePattern(_, scrutTy))
    if tpats.length > 1 && tpats.exists(binds) then
      err("alternative patterns joined by '|' cannot bind a name")
    val tguard = arm.guard.map(analyzeBool)
    val tbody  = analyzeBlockBody(arm.body, expected)
    popScope()
    TArm(tpats, tguard, tbody)
  }

  /** Turns one pattern into its typed form, declaring any bindings into the current scope. A
   * bare name is a nullary-variant pattern when it names a variant of the scrutinee's enum, and
   * a binding otherwise.
   */
  protected def analyzePattern(p: Pattern, ty: Type): TPattern = p match
    case WildcardPattern => TWildPattern(ty)

    case LitPattern(v) =>
      val t = analyzeExpr(v, Some(ty))
      if t.ty != ty then err(s"pattern is ${show(t.ty)} but the value is ${show(ty)}")
      if !Type.isOrdered(ty) then err(s"a ${show(ty)} value cannot be matched against a literal yet")
      TLitPattern(t)

    case RangePattern(lo, hi, inclusive) =>
      if !Type.isOrdered(ty) then err(s"a range pattern needs an ordered value, not ${show(ty)}")
      val tl = analyzeExpr(lo, Some(ty))
      val th = analyzeExpr(hi, Some(ty))
      if tl.ty != ty || th.ty != ty then err(s"range pattern must match the ${show(ty)} value")
      TRangePattern(tl, th, inclusive)

    case IdentPattern(name) =>
      ty match
        case en: Type.Enum if en.variant(name).exists(_.fields.isEmpty) =>
          TVariantPattern(en, en.variant(name).get, Nil)
        case en: Type.Enum if en.variant(name).isDefined =>
          err(s"variant '$name' carries data — match it as '$name(…)'")
        case _ =>
          TBindPattern(declare(name, ty), ty)

    case VariantPattern(name, args) =>
      ty match
        case en: Type.Enum =>
          en.variant(name) match
            case Some(v) if v.fields.isEmpty =>
              err(s"variant '$name' takes no arguments — match it as '$name'")
            case Some(v) =>
              if args.length != v.fields.length then
                err(s"variant '$name' has ${v.fields.length} fields, but ${args.length} sub-patterns were given")
              TVariantPattern(en, v, args.zip(v.fields).map { case (a, (_, fty)) => analyzePattern(a, fty) })
            case None =>
              err(s"enum '${en.name}' has no variant '$name'")
        case other =>
          err(s"'$name(…)' matches an enum variant, but the value is ${show(other)}")

  /** Whether a pattern binds any name (directly or inside a variant's sub-patterns). */
  protected def binds(p: TPattern): Boolean = p match
    case _: TBindPattern    => true
    case v: TVariantPattern => v.args.exists(binds)
    case _                  => false

  /** A pattern that always matches, so an unguarded arm carrying it is a catch-all. */
  protected def irrefutable(p: TPattern): Boolean = p match
    case _: TWildPattern | _: TBindPattern => true
    case _                                 => false

  /** Checks exhaustiveness and returns the value type of a match (`unit` unless every arm
   * yields the same non-unit type). An enum match must cover every variant or carry a
   * catch-all; a scalar match need only be exhaustive when it is used for a value.
   */
  protected def matchResultType(scrutTy: Type, arms: List[TArm]): Type = {
    val bodyTys = arms.map(_.body.ty).distinct
    val valueTy = if bodyTys.size == 1 && bodyTys.head != Type.Unit then bodyTys.head else Type.Unit

    val hasCatchAll = arms.exists(a => a.guard.isEmpty && a.patterns.exists(irrefutable))

    scrutTy match
      case en: Type.Enum =>
        val covered = arms
          .filter(_.guard.isEmpty)
          .flatMap(_.patterns)
          .collect { case v: TVariantPattern if v.args.forall(irrefutable) => v.variant.tag }
          .toSet
        if !hasCatchAll && !en.variants.map(_.tag).toSet.subsetOf(covered) then
          val missing = en.variants.filterNot(v => covered(v.tag)).map(_.name)
          err(s"match on '${en.name}' is not exhaustive; missing ${missing.mkString(", ")} (add an 'else' arm)")
      case _ =>
        if valueTy != Type.Unit && !hasCatchAll then
          err("a 'match' that yields a value must be exhaustive — add an 'else' arm")

    valueTy
  }
}
