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
  protected def analyzeArm(scrutTy: Type, arm: MatchArm, expected: Option[Type]): TArm =
    at(arm.pos)(analyzeArmAt(scrutTy, arm, expected))

  private def analyzeArmAt(scrutTy: Type, arm: MatchArm, expected: Option[Type]): TArm = {
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
      // A literal pattern matches by equality, so any equatable type may carry one — the ordered
      // scalars and `bool`. `bool`'s two literals also make it exhaustive on their own (below).
      if !Type.isEquatable(ty) then err(s"a ${show(ty)} value cannot be matched against a literal")
      TLitPattern(t)

    case RangePattern(lo, hi, inclusive) =>
      // A range is between two endpoints of a discrete, numerically-ordered value — the integers,
      // the floats, and `char`. `string` is ordered too, but a `"a".."z"` range reads as a
      // character range while quietly matching every string whose bytes sort between them, so it is
      // excluded rather than left as a surprise.
      if !(Type.isNumeric(ty) || ty == Type.Char) then
        err(s"a range pattern needs a numeric or char value, not ${show(ty)}")
      val tl = analyzeExpr(lo, Some(ty))
      val th = analyzeExpr(hi, Some(ty))
      if tl.ty != ty || th.ty != ty then err(s"range pattern must match the ${show(ty)} value")
      TRangePattern(tl, th, inclusive)

    // A name in a pattern binds only where nothing answers to it. A variant is asked for first, then
    // a constant — which is what keeps `13 §7`'s constant patterns off the trap Rust documents,
    // where a `const` in a pattern quietly binds instead of matching. There is no second resolution
    // here to disagree with the first: a constant joins the one a variant already went through.
    case IdentPattern(name) =>
      ty match
        case en: Type.Enum if en.variant(name).exists(_.fields.isEmpty) =>
          TVariantPattern(en, en.variant(name).get, Nil)
        case en: Type.Enum if en.variant(name).isDefined =>
          err(s"variant '$name' carries data — match it as '$name(…)'")
        case _ =>
          constKey(name) match
            case Some(key) => analyzePattern(LitPattern(constLiteral(key)), ty)
            // A `val` is storage read while running, so there is no value here to compare against —
            // and letting the name bind instead is precisely the trap the paragraph above says
            // cannot arise. It is refused rather than quietly shadowed, which leaves the reader the
            // choice of a different name or an equality test.
            case None if valKey(name).isDefined =>
              err(s"'$name' is a 'val', which is read while the program runs, so a pattern cannot " +
                s"match against it — compare it in a guard, or bind a different name")
            case None => TBindPattern(declare(name, ty), ty)

    // A variant is named within the scrutinee's own enum, so a module prefix on the pattern is
    // repeating what the value already settled — it is dropped, and the type is what decides.
    case VariantPattern(written, args) =>
      val name = written.substring(written.lastIndexOf('.') + 1)

      ty match
        case en: Type.Enum =>
          en.variant(name) match
            case Some(v) if v.fields.isEmpty =>
              err(s"variant '$name' takes no arguments — match it as '$name'")
            case Some(v) =>
              if args.length != v.fields.length then
                err(s"variant '$name' has ${quantity(v.fields.length, "field")}, " +
                  s"but ${supplied(args.length, "sub-pattern")}")
              TVariantPattern(en, v, args.zip(v.fields).map { case (a, (_, fty)) => analyzePattern(a, fty) })
            case None =>
              err(s"enum '${en.name}' has no variant '$name'")
        // The positional form destructures a struct's fields in declaration order, so it must name
        // every field — adding one to the struct then turns each positional match into a checked
        // to-do, the way a new enum variant does.
        case s: Type.Struct =>
          if !typeKey(written).contains(s.base) then
            err(s"'$written(…)' does not match a ${show(s)} value")
          if args.length != s.fields.length then
            err(s"struct '${qn(s.base)}' has ${quantity(s.fields.length, "field")}, " +
              s"but ${supplied(args.length, "sub-pattern")}")
          TStructPattern(s, args.zip(s.fields).map { case (a, (_, fty)) => analyzePattern(a, fty) })
        case other =>
          err(s"'$name(…)' matches an enum variant or a struct, but the value is ${show(other)}" + heldBehind(other))

    // The named form matches a struct by field name, in any order, and may leave fields unlisted —
    // those are unconstrained. Both source forms end as one `TStructPattern` with a sub-pattern per
    // field in declaration order, a wildcard filling any field the pattern did not mention.
    case StructPattern(name, fieldPats) =>
      ty match
        case s: Type.Struct =>
          if !typeKey(name).contains(s.base) then err(s"'$name{…}' does not match a ${show(s)} value")
          fieldPats.map(_._1).groupBy(identity).collectFirst { case (n, xs) if xs.size > 1 => n }.foreach { n =>
            err(s"field '$n' is matched more than once")
          }
          for (fname, _) <- fieldPats do
            if !s.fields.exists(_._1 == fname) then err(s"struct '${qn(s.base)}' has no field '$fname'")
          val byName = fieldPats.toMap
          TStructPattern(
            s,
            s.fields.map { case (fname, fty) =>
              byName.get(fname).map(analyzePattern(_, fty)).getOrElse(TWildPattern(fty))
            },
          )
        case other =>
          err(s"'$name{…}' matches a struct, but the value is ${show(other)}" + heldBehind(other))

  /** What a "this pattern does not fit that value" complaint adds when the value is the right shape
   * held behind a memory mode.
   *
   * **Selection is the only implicit dereference** (`03`), and deliberately: `p.x` reaches through a
   * `*T` or a `&T`, and a pattern does not, so a recursive type — which has to be held behind one to
   * exist at all — is matched by writing the dereference. That is a rule a reader has to know before
   * they can guess the fix, and the fix is one character, so the one place it bites is where it is
   * worth saying.
   */
  private def heldBehind(t: Type): String = t match
    case Type.Ptr(inner) if matchable(inner)    => hint
    case Type.Ref(inner, _) if matchable(inner) => hint
    case _                                      => ""

  private def hint: String =
    " — a pattern does not reach through a memory mode the way a field selection does, so match what " +
      "it holds: '*x match'"

  private def matchable(t: Type): Boolean = t match
    case _: Type.Enum | _: Type.Struct => true
    case _                             => false

  /** Whether a pattern binds any name (directly or inside a variant's or struct's sub-patterns). */
  protected def binds(p: TPattern): Boolean = p match
    case _: TBindPattern    => true
    case v: TVariantPattern => v.args.exists(binds)
    case s: TStructPattern  => s.args.exists(binds)
    case _                  => false

  /** A pattern that always matches, so an unguarded arm carrying it is a catch-all. A struct has
   * one form, so a struct pattern is irrefutable exactly when every field's sub-pattern is.
   */
  protected def irrefutable(p: TPattern): Boolean = p match
    case _: TWildPattern | _: TBindPattern => true
    case s: TStructPattern                 => s.args.forall(irrefutable)
    case _                                 => false

  /** Checks exhaustiveness and returns the value type of a match (`unit` unless every arm
   * yields the same non-unit type). An enum match must cover every variant or carry a
   * catch-all; a scalar match need only be exhaustive when it is used for a value.
   */
  protected def matchResultType(scrutTy: Type, arms: List[TArm]): Type = {
    val bodyTys = arms.map(_.body.ty).distinct

    // An arm that does not finish — one that aborts rather than yielding — constrains nothing, so
    // it is set aside before the others are compared. That is what lets `None -> exit(1)` sit
    // beside `Some(v) -> v` and the match still have the payload's type.
    val reached = bodyTys.filterNot(_ == Type.Never)

    // Two arms that each yield a value cannot yield *different* value types — there is nothing to
    // unify them to. Diagnosed like an `if` with mismatched branches rather than collapsed to a
    // silent `unit`, which would hide the mistake. A value/unit mix is the "not every arm yields"
    // case and stays `unit`, exactly as an `if` with no `else` does.
    val valueTys = reached.filterNot(_ == Type.Unit)
    if valueTys.size > 1 then
      err(s"match arms have different types: ${valueTys.map(show).mkString(" and ")}")
    val valueTy =
      if reached.isEmpty then Type.Never
      else if reached.size == 1 && reached.head != Type.Unit then reached.head
      else Type.Unit

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
          err(s"match on '${show(en)}' is not exhaustive; missing ${missing.mkString(", ")} (add an 'else' arm)")

      // `bool` is a closed two-value type, so covering both `true` and `false` with unguarded arms
      // is exhaustive on its own — no `else` needed, exactly as for a two-variant enum.
      case Type.Bool =>
        val covered = arms
          .filter(_.guard.isEmpty)
          .flatMap(_.patterns)
          .collect { case TLitPattern(TBoolLit(b)) => b }
          .toSet
        if valueTy != Type.Unit && !hasCatchAll && covered.size < 2 then
          err("a 'match' on 'bool' that yields a value must cover both 'true' and 'false' — add the missing arm or an 'else'")

      case _ =>
        if valueTy != Type.Unit && !hasCatchAll then
          err("a 'match' that yields a value must be exhaustive — add an 'else' arm")

    valueTy
  }
}
