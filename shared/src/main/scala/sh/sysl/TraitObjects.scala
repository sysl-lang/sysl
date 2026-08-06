package sh.sysl

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

      // The value is **already an object**, over some other trait. It gets here because a bound on
      // `Named` is satisfied by a `&Greet`, and it would be a short step from there to reading a
      // `&Named` as the same kind of thing — but the two questions are not the same one. Satisfying a
      // bound asks what may be *called* through the value, and the table answers it. Building an
      // object asks what may be *assembled* from the value's type, and an object has no
      // implementations to assemble from.
      //
      // It is the upcast that flattening gives up (`02 § A trait may require another trait`), stated
      // where someone meets it: `Named`'s members really are slots in a `&Greet`'s table, laid out
      // there so that a required trait's method stays one indirect call — but a run of slots inside
      // another table is not a table anything can point at.
      case (_, from) if Type.erasedTrait(from).exists(_.bound.key != tr.bound.key) =>
        at(t.pos)(err(s"a ${show(from)} has forgotten which type it holds, so there is nothing for a " +
          s"${show(want)} to be built from — '${tr.bound.show}' is reachable *through* the value, and " +
          "a bound on it is satisfied by one, but its slots are not a table of their own"))

      case (Type.Ptr(_), Type.Ptr(inner))              => erase(t, tr, inner, want, boxed = false)
      case (Type.Ref(_, s), Type.Ref(inner, s2)) if s == s2 => erase(t, tr, inner, want, boxed = true)

      // A value where a counted object was expected is boxed first, exactly as a plain `&T` context
      // boxes one — the allocation is the construction, and the erasure rides on top of it.
      case (Type.Ref(_, sync), inner) if conforms(tr.bound, inner) =>
        erase(TBox(t, Type.Ref(inner, sync)).setPos(t.pos), tr, inner, want, boxed = true)

      // A concrete value where a *raw* object was expected has no address of its own to hand over,
      // and taking one silently would be putting a pointer to a temporary in a program.
      case (Type.Ptr(_), inner) if conforms(tr.bound, inner) =>
        at(t.pos)(err(s"a ${show(want)} points at a value, so it needs an address — write '&' " +
          s"in front of the ${show(inner)} to take one"))

      // A type that implements the trait at *other* arguments looks unrelated to the fall-through
      // below, and what that would report is two type names with nothing said about why they do not
      // match. So it is reported here instead, where the arguments can be named.
      case (_, inner) if implsOf(tr.name, ownerKey(inner)).nonEmpty =>
        erase(t, tr, inner, want, boxed = false)

      // A built-in that belongs to the trait by the compiler's rule rather than through an `impl`
      // (`02`, *Object safety*). The membership is real — a bound over the trait is met, and `print`
      // finds the rendering — so falling through to the caller's plain mismatch would deny the one
      // thing about this value that is true, and send the reader looking for the conformance they
      // already have. What a built-in cannot do is fill a table slot, since what the compiler
      // provides is an instruction or a rendering rather than a member anything can point at.
      //
      // **What still arrives here is the operator catalog at written arguments.** `Display` and
      // `Hash` both left, each by gaining written `impl`s over a closed `Integer` bound, and the
      // rest of the catalog is out of reach for a different reason — `Eq`, `Ord`, `Bits` and
      // `Signed` all name `Self` away from the receiver, so object safety refuses the *type* before
      // a value gets this far. An operator trait at written arguments does not: `Add[int, int]`
      // declares `add(self, rhs: int) -> int`, which is a formable object, so `3` as an
      // `&Add[int, int]` reaches this line and this is what it is told.
      //
      // `tr.name` is a key and `CoreTraits` speaks spellings, so the question goes through
      // `Library.spelling` — asking the catalog `sysl$Add` finds nothing, and the value would fall
      // through to the plain mismatch this case exists to replace.
      case (_, inner) if Library.spelling(tr.name).exists(CoreTraits.builtin(_, inner)) =>
        at(t.pos)(err(s"${show(inner)} is a '${tr.bound.show}' by the compiler's rule rather than " +
          s"through an 'impl', and a ${show(want)} holds a table of functions — what the compiler " +
          "provides for a built-in is an instruction or a rendering, and neither is a function the " +
          "table could point at"))

      case _ => t
  }

  private def erase(t: TExpr, tr: Type.Trait, inner: Type, want: Type, boxed: Boolean): TExpr = {
    // A `&sync Trait` has forgotten what it points at, so what `06` asks of the pointee cannot be
    // asked where the type is written. It is asked here, which is the one place the type is known.
    want match
      case Type.Ref(_, true) => at(t.pos)(Sharing.complaint(inner).foreach(err))
      case _                 => ()

    if !conforms(tr.bound, inner) then
      // A type an implementation covers is told what that implementation asked of it, since the
      // reason it does not conform is a condition rather than an absence.
      val why = unmetBound(tr.bound, inner).fold("")(reason => s" — $reason")

      at(t.pos)(err(s"a ${show(want)} needs a type that implements '${tr.bound.show}', and " +
        s"${show(inner)} does not$why"))
    else TErase(t, vtableFor(tr, inner, boxed), want).setPos(t.pos)
  }

  /** The method table for one type seen as one trait, registered the first time it is needed.
   *
   * Every conforming type reaches this through a source `impl`, whose methods were lowered to
   * ordinary functions — so a slot is a name that already exists, and the table is the trait's
   * members in the order `traitMembers` lays them out, which is the order a call site indexes by. A
   * slot is named by the rule a *call* uses, since a table naming a member any other way is a table
   * pointing at nothing: a member of a generic type is instantiated here as it would be at a call
   * site, so erasing a `Box[int]` brings that instantiation into the program.
   *
   * A trait that **requires** another carries the required trait's slots too, rather than a pointer
   * to a table of its own. That keeps a required trait's method the single indirect call the
   * trait's own methods are, which is what makes a supertrait worth having on an object at all; what
   * it gives up is an upcast from a `&Sub` to a `&Super`, since the slots are there but no word
   * names them as a table (`02 § A trait may require another trait`).
   */
  private def vtableFor(tr: Type.Trait, ty: Type, boxed: Boolean): String = {
    // The **member** prefix rather than the plain mangling, for the reason the members themselves
    // take it: a transparent subtype and its base have different implementations, and one table
    // holding both is the same collision one symbol holding two bodies is.
    val name = s"vt.${if boxed then "ref." else ""}${Type.mangle(tr)}.${Type.memberSymbol(ty)}"

    if !vtables.contains(name) then
      val slots = traitMembers(tr.bound, selfBinding(ty)).map { (from, m) =>
        // The trait's own membership was checked before this was reached; a **required** one is
        // checked here, where the table is being built, and the two ways it can fail want different
        // things said. A built-in satisfies by the compiler's rule (`14 §5`) and has no function to
        // name, because its operator is an instruction. Anything else simply has no implementation,
        // which the `impl` was already told — so this says what the erasure cannot do and leaves the
        // advice to the report that has it.
        if !conforms(from, ty) then
          if satisfies(from, ty) then
            err(s"${show(ty)} implements '${qn(from.name)}' by the compiler's own rule rather than " +
              s"through an 'impl', so there is no '${m.name}' for a slot of '${show(tr)}' to point at")
          else
            err(s"'${show(tr)}' requires '${from.show}', and ${show(ty)} does not implement it — so " +
              s"there is no '${m.name}' for its table to point at")

        // Named by the trait the slot is for, not by the member's own name alone: a type may
        // implement one trait at more than one argument list, and a table for `&Sink[int]` whose
        // slot pointed at the `Sink[string]` member would be a table pointing at the wrong thing.
        val fname = traitMemberName(ty, from, m.name)

        // A member whose signature did not match the trait's was reported where the `impl` is
        // written and never registered, so there is nothing here to point a slot at. Errors are
        // collected rather than thrown, so this line is reached *after* that report — and reading
        // the table straight out of the map turned a diagnostic the compiler already had into a
        // stack trace with no diagnostic at all. What is owed here is a second collected error, so
        // that the conformance report is what a reader is left holding.
        val (params, rtype) = funcInsts.getOrElse(
          fname,
          err(s"${show(ty)} has no '${m.name}' that '${show(tr)}' can point a slot at — the one it " +
            s"declares does not match what '${qn(from.name)}' asks for"),
        )

        funcsUsed += fname
        // Object safety already refused a trait with an associated function in it, so every member
        // reaching a slot has a receiver to dispatch on — a property's being the by-value one it
        // never had to write.
        TVSlot(fname, m.recvMode.get, params.tail.map(_._2), rtype)
      }

      vtables(name) = TVtable(name, tr.name, ty, boxed, slots)

    name
  }
}
