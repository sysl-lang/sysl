package sh.sysl

/** Whether a set of match arms leaves any value unhandled, and if so what one of those values
 * looks like.
 *
 * `09 §8` states the rule as a question about **values**: a match is exhaustive when every value
 * of the scrutinee's type is guaranteed handled. Reading a single arm at a time cannot answer
 * that, because arms cover a type *together* — `Some(Halt)`, `Some(Push)` and `None` leave nothing
 * of an `Option[Op]` behind even though not one of them covers a variant on its own.
 *
 * So coverage is computed over all the arms at once, as a matrix: one row per unguarded pattern
 * (an arm's alternatives are separate rows, since either may match), one column per value still
 * being discriminated. A column whose type has a **known, finite** set of constructors — an enum's
 * variants, a struct's single shape, `bool`'s two values — is split constructor by constructor,
 * each split narrowing the matrix and widening the row by that constructor's fields. A column of
 * any other type has no set to complete, so only a wildcard covers it and the rows headed by
 * literals or ranges drop out.
 *
 * The recursion answers with **witnesses**: values the matrix does not match, written the way a
 * pattern is, so the diagnostic can name what is missing rather than say only that something is.
 */
object Exhaustiveness {

  /** How many witnesses are worth reporting. Nested splits multiply — a struct of three enums
   * leaves a gap for every combination — so the search stops once there is plainly enough to act
   * on. One more than this is computed, which is what lets the report say it stopped.
   */
  private val Limit = 8

  /** A cap on how deep the split descends. Every constructible sysl type is finite — a type that
   * contains itself does so through a memory mode, which has no constructor set and therefore ends
   * the descent — so this only ever bounds a diagnostic's precision, never its correctness.
   */
  private val MaxDepth = 12

  /** One position of a witness. */
  private enum Wit {

    /** Every value of this position's type is unmatched. */
    case Any

    /** Some value is unmatched, but not all of them: the column carried literals or ranges, and
     * the complement of a set of literals has no name to write.
     */
    case Unknown

    case Ctor(name: String, args: List[Wit])
  }

  /** What a witness is called. An argument list that says nothing is left off, so a variant no arm
   * mentions reads as `Circle` rather than `Circle(_, _)`, and one that has been narrowed reads as
   * `Some(Push)`.
   */
  private def show(w: Wit): String = w match
    case Wit.Any | Wit.Unknown => "_"
    case Wit.Ctor(name, args) =>
      if args.forall(a => a == Wit.Any || a == Wit.Unknown) then name
      else s"$name(${args.map(show).mkString(", ")})"

  /** A constructor of a closed type: the thing a column is split on. */
  private enum Con {
    case Variant(name: String, tag: Int)
    case Whole
    case Bool(value: Boolean)

    /** A literal or a range, which stands for one value of a type with no finite constructor set.
     * It never equals a constructor a split enumerates, so a row headed by one is dropped from
     * every split — which is the conservative direction, and the one `09 §8` already takes when it
     * says `Some(0)` does not discharge `Some`.
     */
    case Opaque
  }

  /** The constructors of a closed type, each with what it is called and the types of its fields;
   * `None` for a type whose values cannot be enumerated.
   */
  private def space(t: Type): Option[List[(Con, String, List[Type])]] = Type.repr(t) match
    case en: Type.Enum =>
      Some(en.variants.map(v => (Con.Variant(v.name, v.tag), v.name, v.fields.map(_._2))))
    case s: Type.Struct =>
      Some(List((Con.Whole, Modules.show(s.base).split('.').last, s.fields.map(_._2))))
    case Type.Bool =>
      Some(List((Con.Bool(true), "true", Nil), (Con.Bool(false), "false", Nil)))
    case _ => None

  /** A pattern with its bindings taken off — what it *tests for*, which is the only thing coverage
   * is about.
   *
   * `n @ Circle(r)` covers exactly what `Circle(r)` covers, so an arm written either way leaves the
   * same rows uncovered and produces the same witness. Stripping here rather than adding a case to
   * each function below is what keeps that true by construction: a binding is invisible to this
   * whole analysis, which is the claim, and there is one place it could stop being.
   */
  private def unbound(p: TPattern): TPattern = p match
    case a: TAtPattern => unbound(a.inner)
    case other         => other

  /** The constructor a pattern tests for, or `None` when it matches whatever is there. */
  private def head(p: TPattern): Option[Con] = unbound(p) match
    case _: TWildPattern | _: TBindPattern => None
    case v: TVariantPattern                => Some(Con.Variant(v.variant.name, v.variant.tag))
    case _: TStructPattern                 => Some(Con.Whole)
    case TLitPattern(TBoolLit(b))          => Some(Con.Bool(b))
    case _                                 => Some(Con.Opaque)

  /** The row a constructor's split leaves behind, or `None` where the row tests for a different
   * one. A wildcard fits every constructor and passes its fields on unconstrained.
   */
  private def specialize(c: Con, argTys: List[Type], row: List[TPattern]): Option[List[TPattern]] =
    unbound(row.head) match
      case _: TWildPattern | _: TBindPattern              => Some(argTys.map(TWildPattern.apply) ++ row.tail)
      case v: TVariantPattern if head(v).contains(c)      => Some(v.args ++ row.tail)
      case s: TStructPattern if c == Con.Whole            => Some(s.args ++ row.tail)
      case l @ TLitPattern(_: TBoolLit) if head(l).contains(c) => Some(row.tail)
      case _                                              => None

  /** Witnesses for a matrix: value vectors of the given types that no row matches. An empty list
   * means the rows cover the types between them.
   */
  private def witnesses(rows: List[List[TPattern]], tys: List[Type], depth: Int): List[List[Wit]] =
    if tys.isEmpty then (if rows.isEmpty then List(Nil) else Nil)
    else if rows.isEmpty then List(tys.map(_ => Wit.Any))
    else
      val rest = tys.tail

      space(tys.head).filter(_ => depth < MaxDepth) match
        case Some(cons) =>
          cons.iterator
            .flatMap { case (c, name, argTys) =>
              witnesses(rows.flatMap(specialize(c, argTys, _)), argTys ++ rest, depth + 1)
                .map(w => Wit.Ctor(name, w.take(argTys.length)) :: w.drop(argTys.length))
            }
            .take(Limit + 1)
            .toList

        // Nothing here can be completed by listing, so what survives is the rows that match
        // anything. A column that carried a literal leaves a gap with no name — `Unknown` — which
        // is what keeps the diagnostic from claiming the literal's own value is unhandled.
        case None =>
          val gap = if rows.exists(r => head(r.head).isDefined) then Wit.Unknown else Wit.Any

          witnesses(rows.flatMap(r => Option.when(head(r.head).isEmpty)(r.tail)), rest, depth + 1)
            .map(gap :: _)

  /** What a match leaves uncovered: the values named the way a pattern is written, and whether
   * those names are the whole truth.
   *
   * A name is **exact** when the split reached the bottom on constructors alone, so the name and
   * the gap are the same set of values. Where a column carried literals they are a generalization
   * instead — `Some` stands for "every `Some` but `Some(0)`", which is honest as a to-do list and
   * misleading as a pattern to paste in. The caller decides what to do with that; the algorithm
   * only reports it.
   */
  case class Gap(names: List[String], exact: Boolean)

  /** The gap in a match's coverage, or `None` when the arms cover the type between them. A guarded
   * arm is left out: `09 §7` does not let one discharge anything, since the compiler cannot prove
   * the guard holds.
   */
  def gap(scrutTy: Type, arms: List[TArm]): Option[Gap] = {
    val ws = witnesses(arms.filter(_.guard.isEmpty).flatMap(_.patterns.map(List(_))), List(scrutTy), 0)

    Option.when(ws.nonEmpty)(
      Gap(ws.map(w => show(w.head)).distinct.take(Limit + 1), ws.forall(w => exact(w.head)))
    )
  }

  private def exact(w: Wit): Boolean = w match
    case Wit.Unknown       => false
    case Wit.Any           => true
    case Wit.Ctor(_, args) => args.forall(exact)

  /** How a list of missing values reads in a diagnostic. A list long enough to have been cut off
   * says so, rather than presenting a prefix as the whole of what is left to do.
   */
  def describe(names: List[String]): String =
    if names.sizeIs > Limit then names.take(Limit).mkString(", ") + ", and more"
    else names.mkString(", ")
}
