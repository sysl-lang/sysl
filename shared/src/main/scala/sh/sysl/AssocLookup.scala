package sh.sysl

/** `T::Item` — the associated types, and the four different things a projection can be read off.
 *
 * Split out of `TraitLookup` because it is one question asked four ways rather than a section of a
 * longer list: the subject may be a **parameter**, answered by the bounds it carries; a parameter
 * covered by a **blanket** block, answered by what that block supplies; a **concrete** type, answered
 * by its own implementation through the same key ladder a member lookup climbs; or a projection whose
 * subject a substitution has since moved, which is `GenericInstantiation`'s to normalize and reaches
 * here once it has.
 *
 * **A self-type rather than an `extends`, and that is what keeps every mixin list unchanged.** The
 * bodies here read `TraitLookup`'s tables — `implsOf`, `blanketOwners`, `traitDecls`, the owner-key
 * ladder — so this cannot be its supertrait; and `TraitLookup` mixes it in, so nothing that reaches
 * `TraitLookup` has to learn a second name.
 */
trait AssocLookup { this: TraitLookup =>

  /** The associated type an implementation supplies for `member`, with the subject's own arguments
   * put in for the block's parameters — the same substitution `suppliedBound` makes over `written`,
   * and for the same reason: an associated type built out of the block's parameter is a promise
   * about a particular subject, not an open one, so `impl[T] Sequence for Buf[T]` writing
   * `type Item = T` says `int` at a `Buf[int]`.
   *
   * A block that supplies the type through a `some` result answers with what its body turned out to
   * produce, once that has been settled — and with a **stand-in** before it has, which is the state
   * conformance sees. Nothing is decided from the stand-in: the two signatures being compared are
   * the trait's `Self::Item` and the block's, so both reach here and both get the same answer
   * whichever it is.
   */
  protected def implAssoc(ti: TraitImpl, member: String, key: String, targs: List[Type]): Option[Type] = {
    val sub = ti.tparams.zip(targs).toMap

    ti.assoc.get(member).map(substParams(_, sub))
      .orElse(ti.opaque.get(member).map { fn =>
        opaqueResults.get(fn).fold(Type.Abstract(s"$key::$member", Nil))(substParams(_, sub))
      })
  }

  /** Every trait declaring an associated type of this name. It is what makes `T::Item` writable
   * without naming the trait: a type implements at most one trait declaring an `Item`, and a program
   * where that is not so is told where it asked rather than made to qualify everywhere it is.
   */
  protected def traitsDeclaring(member: String): List[String] =
    traitDecls.collect { case (n, d) if d.assocs.exists(_.name == member) => n }.toList

  /** The bounds an associated type carries — what a generic caller reaching one may do with it.
   *
   * They are the trait's, resolved in the trait's own terms: `Self` is the subject being asked
   * about, and the trait's own parameters are whatever this bound applied it to. Read in the trait's
   * scope for the reason a held-as-written bound always is — a short name means what the trait's
   * file imported, and resolving it where the question was asked reaches whatever that module
   * happens to see.
   */
  protected def assocBoundsOf(b: Type.Bound, ad: AssocDecl, subject: Type): List[Type.Bound] =
    traitDecls.get(b.name).toList.flatMap { d =>
      val subst = d.tparams.zip(b.args).toMap ++ selfBinding(subject)

      ad.bounds.map(ref => inScope(scopeFor(b.name))(resolveBound(ref, subst)))
    }

  /** `T::Item` where `T` is a type parameter standing in for itself: the projection stays abstract,
   * under a name built from the subject's, and carries exactly the bounds the trait declared for it.
   *
   * **It is a `Type.Abstract` rather than a case of its own, and that is what makes the feature
   * cheap.** An abstract type is identified by its name alone, so `V::Item` compares, substitutes,
   * mangles and refuses a layout exactly as `V` does, and nothing that walks types has a new shape
   * to learn. `substParams` is the one place that knows the name has parts, and it is where a
   * projection stops being abstract.
   */
  private def abstractAssoc(a: Type.Abstract, member: String): Option[Type] =
    a.bounds.view
      .flatMap(b => traitClosure(b, selfBinding(a)))
      .flatMap(b => traitDecls.get(b.name).toList.flatMap(_.assocs).find(_.name == member).map((b, _)))
      .headOption
      .map((b, ad) => Type.Abstract(s"${a.name}::$member", assocBoundsOf(b, ad, a)))
      .orElse(blanketAssoc(a, member))

  /** The same question answered by a **blanket** block, for a parameter whose bound is the family
   * that block was written over.
   *
   * `impl[T: Integer] Magnitude for T` says every integer implements the trait, so a `T` bounded by
   * `Integer` implements it at every instantiation and `T::Size` is exactly what the block supplies
   * — which is not something the parameter's own bounds can say, since the bound naming the family
   * is not the trait being implemented. It is the same relaxation `blanketOwners` makes for a
   * concrete type, asked one step earlier.
   *
   * **It is what lets the block's own signature be checked at all.** Conformance resolves the
   * trait's `Self::Item` with `Self` bound to the subject, and a blanket subject is the parameter —
   * so without this the block that supplies the associated type is the one place the projection
   * could not be read, and every blanket implementation of a trait declaring one was refused
   * against its own declaration.
   *
   * Read off the **bound the parameter carries** rather than through `satisfies`, for the reason
   * `blanketOwners` asks `CoreTraits` directly: this is reached from conformance, and a question
   * asked back through conformance is the same question one turn later. Silent where two blankets
   * would answer differently — `concreteAssoc` is what reports that, at an instantiation where the
   * type has a name worth printing.
   */
  private def blanketAssoc(a: Type.Abstract, member: String): Option[Type] = {
    val hits =
      for
        name         <- traitsDeclaring(member)
        (key, targs) <- blanketOwnersOf(a)
        ti           <- implsOf(name, key)
        bound        <- implAssoc(ti, member, key, targs)
      yield bound

    hits.distinct match
      case b :: Nil => Some(b)
      case _        => None
  }

  /** The blanket keys a **type parameter** is covered by, in the pairing `blanketOwners` gives a
   * concrete type: a parameter is covered where its own bounds name the family the block was
   * written over, and the one type argument is the parameter itself.
   */
  private def blanketOwnersOf(a: Type.Abstract): List[(String, List[Type])] =
    if blanketBounds.isEmpty then Nil
    else blanketBounds.toList.collect { case (key, bound) if a.bounds.exists(_.name == bound) => (key, List(a)) }

  /** The same question asked of a **concrete** subject, where the answer is the implementation's.
   *
   * Left is what to say when there is no answer, kept as a message rather than raised here so that
   * the quiet form below can ask without reporting — a substitution normalizing a projection asks
   * about a type conformance has already vouched for, and a failure there has no position worth
   * printing.
   */
  private def concreteAssoc(t: Type, member: String): Either[String, Type] = {
    val names = traitsDeclaring(member)
    // The same ladder `memberKey` climbs, and for the same reason: a `[]T` also answers to the
    // read-only view of its elements (`reference/arrays.md § []const T — a view that may not be
    // written`), so an `impl` written for a `[]const u8` supplies the projection of a `[]u8`
    // exactly as it supplies its members. Leaving the widened key out made a member reachable whose
    // associated type was not.
    val keys  = memberOwner(t) :: widened(t).toList ::: shapeOwners(t) ::: blanketOwners(t)
    val hits =
      for
        name         <- names
        (key, targs) <- keys
        ti           <- implsOf(name, key)
        bound        <- implAssoc(ti, member, key, targs)
      yield (name, bound)

    hits.map(_._1).distinct match
      case Nil if names.isEmpty =>
        Left(s"no trait declares an associated type '$member', so '${show(t)}::$member' names " +
          s"nothing — a trait declares one with 'type $member: …' among its members")
      case Nil =>
        Left(s"${show(t)} implements no trait declaring the associated type '$member' — " +
          s"${quantity(names.length, "trait")} ${if names.length == 1 then "declares" else "declare"} " +
          s"one (${names.map(qn).mkString(", ")}), and this type implements none of them")
      case one :: Nil =>
        hits.collect { case (n, b) if n == one => b }.distinct match
          case b :: Nil => Right(b)
          // One trait, more than one implementation of it for this subject, and they disagree about
          // what the associated type is. A generic trait may be implemented once per argument list
          // (`02`), and the projection names no argument list — so this is a thing that cannot be
          // said rather than one that resolves.
          case several =>
            Left(s"${show(t)} implements '${qn(one)}' more than once and the implementations give " +
              s"'$member' different types (${several.map(show).mkString(", ")}) — an associated type " +
              s"is written without the trait's arguments, so there is no way here to say which " +
              s"implementation is meant")
      case many =>
        Left(s"'$member' is declared by ${many.map(qn).mkString(" and ")}, and ${show(t)} implements " +
          s"more than one of them — an associated type is named without its trait, so a type may " +
          s"have at most one of any name")
  }

  /** `T::Item` — the associated type, or the diagnostic saying why there is none. */
  protected def assocType(subject: Type, member: String): Type = subject match
    // A subject that could not be worked out has already been reported, and a projection off it is a
    // consequence rather than a second mistake.
    case Type.Unknown     => Type.Unknown
    // **`boundErr`, not `err`** — it is a complaint about a bound, and it is right whatever the
    // parameter turns out to be, so it has to survive the definition-time pass that drops the
    // complaints an instantiation would raise again. Nothing raises this one again: an instantiation
    // resolves the projection against a concrete implementation instead.
    case a: Type.Abstract =>
      abstractAssoc(a, member).getOrElse(
        boundErr(s"'${a.name}' is not bounded by a trait declaring an associated type '$member', so " +
          s"there is nothing here to say what '${a.name}::$member' is — a bound is what gives a " +
          s"type parameter one, written '[${a.name}: Trait]' where 'Trait' declares 'type $member'"))
    // **The subject is a trait object**, which reaches here as the `Self` a slot's signature is read
    // under: the object forgot its type, and what it wrote down in its place is exactly this. A
    // projection it did not fix cannot arrive — object safety refused the type before any signature
    // was read — so the fallback is a compiler fault rather than a program's mistake.
    case t: Type.Trait =>
      t.assoc(member).getOrElse(
        err(s"'${show(t)}' does not say what '$member' is, so a slot naming 'Self::$member' " +
          s"has no signature — write it as '${show(t)}[$member = …]'"))
    case concrete => concreteAssoc(concrete, member).fold(err, identity)

  /** The same, answering `None` where the projection cannot be worked out instead of reporting.
   *
   * It is what a **substitution** asks: normalizing `V::Item` once `V` is known is not a place a
   * diagnostic can be raised usefully — there is no source position, and the projection was already
   * held to a bound where it was written, which is what guarantees the implementation exists.
   */
  protected def assocTypeOpt(subject: Type, member: String): Option[Type] = subject match
    case Type.Unknown     => Some(Type.Unknown)
    case a: Type.Abstract => abstractAssoc(a, member)
    case t: Type.Trait    => t.assoc(member)
    case concrete         => concreteAssoc(concrete, member).toOption
}
