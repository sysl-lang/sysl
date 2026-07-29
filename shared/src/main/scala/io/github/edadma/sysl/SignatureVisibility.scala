package io.github.edadma.sysl

/** The rule that a declaration may not name, in what it declares to whoever can reach it, a type
 * that does not reach as far (`13 §2`).
 *
 * A `private struct Point` beside a public `make() -> Point` would hand every module a value of a
 * type none of them may name: they could hold it, pass it on, and read its fields, and the one
 * thing they could not do is write the type down. That is a hole in the restriction rather than a
 * use of it, so the declaration is refused rather than left to leak — and refusing it now forbids
 * nothing a later rule would have had to keep allowing.
 *
 * The rule is about **naming**, so it runs over what was written rather than over resolved types. A
 * type parameter, `Self`, and the built-in scalars are names that stand for no declaration; a name
 * the declaring file may not reach at all is a complaint the resolution that built the signature has
 * already made, and is passed over here rather than reported twice.
 *
 * An `impl` block is deliberately outside it. Its members' signatures are the trait's — a mismatch
 * is refused as non-conformance — so a type leaking through one leaks through the trait, which is
 * where it is reported. Implementing a *public* trait for a *private* type is not a leak either: the
 * promise is public, and the type it was made for stays as unnameable as it was.
 */
trait SignatureVisibility extends TypeResolution {

  /** How far a declaration may be named from, which is the one thing this rule compares.
   *
   * A file-private declaration carries the module its file contributes to as well as the file, since
   * a module-scoped type covers it exactly when that module encloses this one.
   */
  protected enum Reach:
    case Everywhere
    case Subtree(module: String)
    case OneFile(file: Source, module: String)

  /** How far one declaration reaches, read off what its modifier recorded. A declaration with no
   * entry was unmarked, and a `private` in a context with no file behind it — the prelude's, or one
   * the compiler synthesized — restricts nothing there is anywhere to restrict it to.
   */
  protected def reachOf(key: String): Reach = declAccess.get(key) match
    case Some(Access(_, Some(m)))    => Reach.Subtree(m)
    case Some(Access(Some(f), None)) => Reach.OneFile(f, scopeFor(key).module)
    case _                           => Reach.Everywhere

  /** Whether everything that may name a declaration of reach `inner` may also name one of `outer` —
   * which is the whole of the question a leak asks.
   *
   * A module-scoped declaration covers another exactly when its subtree contains the other's, and
   * `private[M]` may only name an enclosing module (`13 §2`), so every reach is a contiguous region
   * and containment settles it. A file covers only itself, compared by identity rather than by name
   * because two files of one project may be called the same thing.
   */
  protected def covers(outer: Reach, inner: Reach): Boolean = (outer, inner) match
    case (Reach.Everywhere, _)                      => true
    case (_, Reach.Everywhere)                      => false
    case (Reach.OneFile(a, _), Reach.OneFile(b, _)) => a eq b
    case (Reach.OneFile(_, _), _)                   => false
    case (Reach.Subtree(m), Reach.Subtree(n))       => within(m, n)
    case (Reach.Subtree(m), Reach.OneFile(_, n))    => within(m, n)

  private def within(outer: String, inner: String): Boolean =
    inner == outer || inner.startsWith(s"$outer.")

  /** Reports every type a declaration exposes that does not reach as far as the declaration does.
   *
   * It runs once every declaration is registered, because the question is about two of them at a
   * time and either may be written further down the file than the other.
   */
  protected def checkExposedTypes(): Unit = {
    for (key, d) <- structDecls.toList do
      val own = d.tparams.toSet

      expose(key, own, d.bounds, d.fields.map(f => (s"field '${f.name}'", f.typ, f.pos)) ::: defaults(d.tdefaults))
      for m <- d.members do exposeMember(key, own, m)

    for (key, d) <- enumDecls.toList do
      val own     = d.tparams.toSet
      val payload =
        for v <- d.variants; f <- v.fields
        yield (s"the '${f.name}' of variant '${v.name}'", f.typ, f.pos)

      expose(key, own, d.bounds, payload ::: defaults(d.tdefaults))
      for m <- d.members do exposeMember(key, own, m)

    for (key, d) <- traitDecls.toList do
      val own = d.tparams.toSet

      expose(key, own, d.bounds, defaults(d.tdefaults), d.supers)
      for m <- d.methods do exposeMember(key, own, m)

    // An `extern` is here too: it is registered as a function, and a symbol the linker resolves is
    // no less able to hand back a type the caller cannot name.
    for (key, d) <- funcDecls.toList do
      expose(key, d.tparams.toSet, d.bounds, signature(d.params, d.retType))
  }

  /** A **default** is exposed as surely as a field is, and less obviously: it is the one part of a
    * signature a use does not write, so a caller that leaves the argument out ends up holding
    * whatever the default named — a type they could not have written and cannot name. A default
    * naming one of the declaration's own parameters names nothing anyone has to reach, which is
    * what `skip` already covers.
    */
  private def defaults(tdefaults: Map[String, TypeRef]): List[(String, TypeRef, Option[Pos])] =
    tdefaults.toList.sortBy(_._1).map((tp, ref) => (s"the default for '$tp'", ref, ref.pos))

  /** One top-level declaration, which is as visible as its own modifier made it. */
  private def expose(
      key: String,
      tparams: Set[String],
      bounds: Map[String, List[BoundRef]],
      parts: List[(String, TypeRef, Option[Pos])],
      supers: List[BoundRef] = Nil,
  ): Unit = exposeIn(key, Modules.split(key)._2, reachOf(key), tparams, bounds, parts, supers)

  /** One member of a type or of a trait, which is as visible as the thing it belongs to: a member
   * carries no modifier of its own (`13 § Open f`), and there would be nothing for one to widen to
   * if it did.
   */
  private def exposeMember(owner: String, tparams: Set[String], m: MethodDecl): Unit =
    exposeIn(
      owner,
      s"${Modules.split(owner)._2}.${m.name}",
      reachOf(owner),
      tparams ++ m.tparams + selfName,
      m.bounds,
      signature(m.params, m.retType),
    )

  private def signature(params: List[Param], ret: Option[TypeRef]): List[(String, TypeRef, Option[Pos])] =
    params.map(p => (s"parameter '${p.name}'", p.typ, p.pos)) ::: ret.toList.map(r => ("its result", r, r.pos))

  /** The comparison itself, made in the terms the declaration was written in — the names in its
   * signature mean what they meant there, exactly as its body's do.
   */
  private def exposeIn(
      scope: String,
      label: String,
      reach: Reach,
      skip: Set[String],
      bounds: Map[String, List[BoundRef]],
      parts: List[(String, TypeRef, Option[Pos])],
      supers: List[BoundRef] = Nil,
  ): Unit = reach match
    // A file-private declaration reaches one file, and every type it names is visible there or the
    // signature would not have resolved — so there is nothing left for this to find.
    case _: Reach.OneFile => ()
    case _ =>
      inDecl(scope) {
        for (what, ref, pos) <- parts; (written, at1) <- namesIn(ref, skip); key <- namedDecl(written) do
          at(at1.orElse(pos))(leak(label, reach, what, key))

        // A bound is part of what a caller must satisfy, so a trait it cannot name leaves it unable
        // to say what the declaration asks of it.
        for (tp, refs) <- bounds; b <- refs if !skip(b.name); key <- quietly(traitKey(b.name)) do
          at(b.pos)(leak(label, reach, s"the bound on '$tp'", key))

        // A **required** trait is the sharpest case of the same rule: implementing the trait means
        // implementing that one as well, so a requirement the implementer cannot name leaves the
        // trait unimplementable from outside — and the diagnostic at the `impl` would tell them to
        // write a block naming a trait they may not.
        for b <- supers; key <- quietly(traitKey(b.name)) do
          at(b.pos)(leak(label, reach, "the trait it requires", key))
      }

  private def leak(label: String, reach: Reach, what: String, key: String): Unit =
    if !covers(reachOf(key), reach) then
      recover(())(err(s"'$label' is ${reachPhrase(reach)}, but $what names '${qn(key)}', which is " +
        s"${restriction(key)} — a declaration may not be more visible than the types it names"))

  /** How far the declaration itself reaches, as a diagnostic says it. `restriction` says the other
   * half — how far the type it named does — and the two together are the whole complaint.
   */
  private def reachPhrase(reach: Reach): String = reach match
    case Reach.Everywhere    => "public"
    case Reach.Subtree(m)    => s"visible throughout module '$m'"
    case Reach.OneFile(f, _) => s"private to '${f.name}'"

  /** Every name a written type mentions, with where it was written, less the names that stand for
   * something other than a declaration here — the enclosing declaration's type parameters, and
   * `Self`.
   */
  private def namesIn(t: TypeRef, skip: Set[String]): List[(String, Option[Pos])] = t match
    case n @ NamedType(name, args) =>
      (if skip(name) then Nil else List((name, n.pos))) ::: args.flatMap(namesIn(_, skip))
    case PtrType(inner)     => namesIn(inner, skip)
    case RefType(inner, _)  => namesIn(inner, skip)
    case ArrayType(_, elem) => namesIn(elem, skip)
    case TupleType(parts)   => parts.flatMap(namesIn(_, skip))

  /** The declaration a name in a type position stands for: a struct, an enum, or — behind a memory
   * mode, where a trait object writes one — a trait.
   */
  private def namedDecl(written: String): Option[String] = quietly(typeKey(written).orElse(traitKey(written)))

  /** A resolution made for the sake of the comparison, which is not the place a name that resolves
   * to nothing is worth reporting from: a scalar and a type parameter answer `None` here, and a name
   * the file may not reach was reported where the signature was resolved.
   */
  private def quietly(lookup: => Option[String]): Option[String] =
    try lookup
    catch
      case _: AnalyzerError => None
      case _: Poisoned      => None
}
