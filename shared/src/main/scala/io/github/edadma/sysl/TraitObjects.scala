package io.github.edadma.sysl

/** Trait objects: turning a value into one, and the method table it dispatches through (`02`).
 *
 * A trait object is a fat pointer — the table for the type it forgot, and the value itself — and
 * both halves are decided here. The table is a per-(trait, type, mode) constant the analyzer
 * registers on demand, so a program that never erases a type carries none of them; the erasure is
 * a coercion, applied wherever a `*Trait` or a `&Trait` is expected and something concrete arrives.
 *
 * Calling *through* one lives with the other calls, in `CallAnalysis`. What is here is everything
 * that has to know how the two words are built.
 */
trait TraitObjects extends TypeResolution {

  /** Coerces a concrete value into the trait object a context asked for, or reports why it cannot.
   *
   * The four shapes that reach here are the four a programmer would write: the object itself
   * (nothing to do), a pointer or a reference to a type that implements the trait (erase it), and a
   * plain value where a counted object was wanted, which is the ordinary "write the construction
   * and it is allocated" rule with an erasure on the end.
   *
   * Anything else is left alone, so the caller's own mismatch diagnostic — which names the
   * parameter or the variable — is the one that gets reported.
   */
  protected def eraseTo(t: TExpr, want: Type): TExpr = {
    val tr = Type.erasedTrait(want).get

    (want, t.ty) match
      case (_, from) if from == want => t

      case (Type.Ptr(_), Type.Ptr(inner))              => erase(t, tr, inner, want, boxed = false)
      case (Type.Ref(_, s), Type.Ref(inner, s2)) if s == s2 => erase(t, tr, inner, want, boxed = true)

      // A value where a counted object was expected is boxed first, exactly as a plain `&T` context
      // boxes one — the allocation is the construction, and the erasure rides on top of it.
      case (Type.Ref(_, sync), inner) if implements(tr.name, inner) =>
        erase(TBox(t, Type.Ref(inner, sync)).setPos(t.pos), tr, inner, want, boxed = true)

      // A concrete value where a *raw* object was expected has no address of its own to hand over,
      // and taking one silently would be putting a pointer to a temporary in a program.
      case (Type.Ptr(_), inner) if implements(tr.name, inner) =>
        at(t.pos)(err(s"a ${show(want)} points at a value, so it needs an address — write '&' " +
          s"in front of the ${show(inner)} to take one"))

      case _ => t
  }

  /** Whether a type may be erased to a trait: an `impl` written for it, and not a membership the
   * compiler provides.
   *
   * The difference matters because a table holds function pointers, and a compiler-provided
   * membership has no functions — a scalar's `add` is an instruction. Nothing is lost by the
   * distinction: object safety already refuses every trait in the catalog, so the only traits that
   * reach here are ones a program declared and implemented itself.
   */
  private def implements(traitName: String, t: Type): Boolean =
    traitImpls.contains((traitName, ownerKey(t)))

  private def erase(t: TExpr, tr: Type.Trait, inner: Type, want: Type, boxed: Boolean): TExpr =
    if !implements(tr.name, inner) then
      at(t.pos)(err(s"a ${show(want)} needs a type that implements '${tr.name}', and " +
        s"${show(inner)} does not"))
    else TErase(t, vtableFor(tr.name, inner, boxed), want).setPos(t.pos)

  /** The method table for one type seen as one trait, registered the first time it is needed.
   *
   * Every conforming type reaches this through a source `impl`, whose methods were lowered to
   * ordinary functions under `Type.method` — so a slot is a name that already exists, and the table
   * is the trait's methods in declaration order, which is the order a call site indexes by.
   */
  private def vtableFor(traitName: String, ty: Type, boxed: Boolean): String = {
    val name = s"vt.${if boxed then "ref." else ""}$traitName.${Type.mangle(ty)}"

    if !vtables.contains(name) then
      val slots = traitDecls(traitName).methods.map { m =>
        val fname           = s"${Type.mangle(ty)}.${m.name}"
        val (params, rtype) = funcInsts(fname)

        funcsUsed += fname
        // Object safety already refused a trait with an associated function in it, so every member
        // reaching a slot has a receiver to dispatch on — a property's being the by-value one it
        // never had to write.
        TVSlot(fname, m.recvMode.get, params.tail.map(_._2), rtype)
      }

      vtables(name) = TVtable(name, traitName, ty, boxed, slots)

    name
  }
}
