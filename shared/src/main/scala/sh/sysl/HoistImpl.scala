package sh.sysl

import scala.collection.mutable

/** Lowering an `impl` block: deciding what it is an implementation *of*, checking that it is allowed
 * to exist, and hoisting its methods to inherent members of the implementing type.
 *
 * The lowering itself is the small part — a conforming block's methods are hoisted exactly as a
 * type's own are, so a call on a value resolves through the ordinary member path with no dispatch
 * machinery of its own. That is the whole reason a trait costs nothing until something is erased.
 *
 * The large part is the two questions asked first. **What is the subject?** — a written type may be
 * a name, an applied generic, a memory mode over either, or a shape like `*Trait` standing for every
 * type at once, and each answers to a different member home. **May this block exist?** — coherence,
 * which forbids a second implementation of one trait for one type, and forbids implementing a
 * foreign trait for a foreign type, since either would make which implementation applies depend on
 * what happened to be compiled alongside.
 *
 * Both of those questions, and the vocabulary a coherence failure needs, are `ImplTarget`. What is
 * left here is the lowering itself and the conformance around it: the methods a trait's defaults
 * fill in, the ones an `impl` opens a result type for, and the refusal a second implementation gets.
 */
trait HoistImpl extends ImplTarget {

  /** Checks an `impl` conforms to its trait and lowers its methods to inherent members of the
   * implementing type. Conformance is nominal and exact: the type must supply every trait method
   * with a matching signature and no method the trait does not declare. The methods are then
   * hoisted exactly as a type's own members are, so a call on a value resolves through the ordinary
   * member path with no dispatch machinery of its own.
   */
  protected def hoistImpl(block: ImplDecl, out: mutable.ListBuffer[FuncDecl]): Unit = {
    // The trait is named in the module the block was written in, and everything filed below is
    // filed under the key that names — so the block carries the resolved name from here on and
    // nothing downstream has to resolve it a second time.
    val tr   = traitKey(block.traitName).map(traitDecls).getOrElse(err(s"unknown trait '${block.traitName}'"))
    // A setter's parameter takes the property's type, and the property may be the **trait's** — an
    // `impl` supplying only the write half is supplying exactly what the trait left open. Paired
    // here, before conformance reads a signature, since an unpaired one resolves to nothing.
    val impl = block
      .copy(traitName = tr.name,
            methods = pairSetters(openedResults(tr, block), block.forType.show, tr.methods))
      .setPos(block.pos)

    val (ty, target)     = implTarget(impl)
    val (bound, written) = implBound(impl, tr)
    val outer            = target.copy(outer = tr.tparams.zip(bound.args).toMap)

    // A built-in's memberships come from the compiler (`reference/expressions.md § Operator
    // dispatch`), so an `impl` for one is not adding a capability but competing with the one that
    // is already there — and the operator would keep lowering to its native instruction whatever
    // this block said. What the block *wrote* is what decides: a catalog trait's arguments default
    // to the implementing type, so an `impl Mul for int` that wrote none of them is the one the
    // compiler already provides, while one that wrote an argument is asking for something else
    // entirely. **The one type reaching this that a program declared is a simple enum**, whose `Eq`
    // the compiler supplies for the reason it supplies the open integer family's: the value *is*
    // its discriminant, so there is one thing equality could mean. The rule is the same and the
    // reader is different — somebody who wrote the enum four lines up is owed why their own type is
    // on the compiler's side of this line, not only that it is.
    if written.isEmpty && Library.spelling(impl.traitName).exists(CoreTraits.builtin(_, ty)) then
      val because = ty match
        case e: Type.Enum if e.simple =>
          " — no variant of it carries anything, so its value is its discriminant and '==' is that " +
            "comparison. Delete the block; a variant that needs an equality of its own has to carry " +
            "something for it to be about"
        case _ => " — the compiler provides it"

      err(s"'${outer.label}' already implements '${qn(impl.traitName)}'$because")

    // A **closed** trait names a family rather than a promise, so there is nothing for a block to
    // supply: it declares no member, and an `impl` of it could only be a claim to belong to the
    // family. Which types belong is the compiler's answer, and it has to stay that way — a blanket
    // `impl` written over the bound covers exactly the types in it, so a program able to join would
    // acquire an implementation written for something else with no block naming either.
    if Library.spelling(impl.traitName).exists(CoreTraits.closed) then
      err(s"'${qn(impl.traitName)}' names a family of types the compiler settles, so nothing " +
        s"implements it — a type is one of them or it is not, and '${outer.label}' is not")

    // **The overriding side is always a type written out in full**, which is what makes `override` a
    // rule about two blocks rather than a rule about every pair of keys. A shape is one key and so is
    // a generic type: `impl[T] Show for [][]T` and `impl Show for Box[int]` are both refused outright
    // (`shapeArgs`, `implArgs`), so neither a shape nor a generic type has anything below it to be
    // more specific *than*. That leaves the written-out type as the only block that can be the more
    // specific of a pair, and saying so here is worth more than letting the ordering find nothing.
    if impl.overrides && (outer.shaped || outer.tparams.nonEmpty) then
      err(s"'override' says this block replaces a more general one, and '${outer.label}' is the " +
        s"general kind — an implementation for a shape or for a generic type covers every type it " +
        s"matches at once, so there is nothing below it. The override is written on the block for " +
        s"one type spelled out in full")

    // What this block's own promise is read against: the type where it has one, and the type applied
    // to the block's own parameters where it is generic — the same subject `superChecks` uses, and
    // the one every comparison below has to be made under for the two sides to mean the same thing.
    val subject =
      if impl.tparams.isEmpty then ty
      else sandboxed(resolveType(impl.forType, abstractSubst(impl.tparams, impl.bounds, impl.tvalues, impl.tpacks)))

    // Keyed by the type rather than by the spelling, so `impl Show for int` and `impl Show for i32`
    // are the one implementation they are, and so are `[]int` and `[]i32`. A generic type has one
    // key for all of its instantiations, which is what makes an implementation cover the type as a
    // whole rather than one instantiation of it.
    //
    // A trait that takes arguments is a family of promises, and a type may keep more than one of
    // them — what it may not keep is two of the *same* one, since nothing would pick between them.
    // So the argument lists are what is compared, under this block's own subject so that a defaulted
    // list and a written one are read in the same terms.
    val already = implsOf(impl.traitName, outer.key)

    // Asked with **this** block's parameters standing in for the subject's arguments, so a block
    // already filed under them is read in the names this one wrote: `impl[U] Index[usize, U] for
    // Buf[U]` and an `impl[T] Index[usize, T] for Buf[T]` before it are the one promise they are,
    // rather than two that differ in the letter their author chose.
    //
    // **They carry the block's bounds, and it is reading the OTHER block that needs them.** A
    // comparison re-resolves the already-filed block's arguments under these stand-ins, so an
    // argument that is a bounded generic type — `impl[T: Scalar] Mul[Vector[T], T] for Vector[T]`,
    // where `Vector` asks `Scalar` of its own parameter — applies `Vector` to whatever stands here.
    // A bound-free stand-in makes that application one the type's own declaration refuses, and the
    // complaint then lands on *this* block, which is neither where it was written nor wrong.
    val mine = outer.tparams.map(abstractSubst(outer.tparams, outer.bounds, outer.tvalues, outer.tpacks))

    for other <- already.find(ti => suppliedBound(ti, impl.traitName, subject, mine).key == bound.key) do
      err(s"'${outer.label}' already implements '${showBound(bound, subject)}'" + secondImplementation(tr, other))

    // **A blanket block covering the subject is an implementation the subject already has**, and the
    // list above cannot see it: a blanket is filed under its bound's key rather than under any type's,
    // which is what lets one block stand for a whole family.
    //
    // The case is a derived subtype of a built-in — `type Stamp = new int` is the program's own
    // type, so coherence gives an `impl` here a home, and `reference/errors.md § A derivation
    // inherits its base's behaviour and may replace none of it` gives it its base's memberships, so
    // the library's blanket covers it too. Two blocks would then cover one type with nothing to
    // pick between them, and the one that answered would be whichever key the lookup tried first.
    for
      (key, targs) <- blanketOwners(ty)
      _            <- implAt(bound, key, ty, targs)
      if !impl.overrides
    do
      // The subtype half is said only where there is one, and it says "subtype" rather than
      // "derived" because both kinds reach here: a transparent one *is* its base
      // (`reference/errors.md § Constrained types`) and a derived one has the base's catalogue
      // (`reference/errors.md § A derivation inherits its base's behaviour and may replace none of
      // it`), so either way the block covering it was written for a type the reader did not name.
      // On a built-in written out in full there is no such type, and the clause would be a sentence
      // about something that is not there.
      val inherited =
        if Type.underlying(ty) == ty then ""
        else ", and a subtype has its base's memberships"

      err(s"'${outer.label}' already implements '${showBound(bound, subject)}' — " +
        s"${everyShape(key)} does, through one block written over the family$inherited")

    // **A result is not a selector.** An operator trait's last argument is what the operator gives
    // back (`library/core.md § Walking a type of your own`), and `a * b` supplies the operands and
    // nothing else — so two implementations that agree on the operands and differ only in the
    // result leave a use with nothing to choose by. Refused here rather than at the use, because
    // the use is where it would be too late to say which of the two the program meant.
    val catalog = Library.spelling(impl.traitName).filter(CoreTraits.required.contains)

    if catalog.exists(CoreTraits.selectsByOperand) && bound.args.length > 1 then
      val operands = bound.args.dropRight(1)

      for
        other <- already.find { ti =>
          val theirs = suppliedBound(ti, impl.traitName, subject, mine)

          theirs.args.length == bound.args.length && theirs.args.dropRight(1) == operands
        }
        theirs = suppliedBound(other, impl.traitName, subject, mine)
      do
        err(s"'${outer.label}' already implements '${showBound(theirs, subject)}', and this one differs " +
          s"only in what it gives back — '${CoreTraits.required(catalog.get)._2}' between " +
          s"${show(subject)} and ${conjoin(operands.map(show))} would have two results to choose from " +
          "and nothing at the use to choose with")

    // On a **generic** subject a defaulted argument list is not one promise but one per
    // instantiation, since the trait's own default names the type being asked about. A written
    // argument built out of that same type at *one* instantiation would coincide with the default
    // there and nowhere else, which is a choice between implementations rather than a lookup — so
    // `impl[T] Mul[Box[int]] for Box[T]` is refused here, where the block that would need choosing
    // between is the one being read.
    //
    // **The subject itself is not that case**, and this is the whole of what the comparison below
    // is for: `impl[T] Mul[Vec[T], T] for Vec[T]` writes the block's own parameters, so it says the
    // same thing at every instantiation rather than colliding at one. That is the shape a dot
    // product has — an operand of `Self` with a result that is not — and it has no other spelling,
    // since trait arguments are positional and reaching `Out` means writing `Rhs`. Refusing it also
    // refused something the non-generic path allows: `impl Mul[Vector, real] for Vector` is exactly
    // this block with the parameter resolved.
    //
    // What a *duplicate* costs is still charged, by the two checks above rather than by this one —
    // a second block at the same operands is caught by the argument-list comparison, and one
    // differing only in its result by the rule that a result is not a selector. Both wait until
    // there are two blocks, which is what this one now does too.
    if outer.tparams.nonEmpty && tr.tdefaults.values.exists(mentionsSelf) then
      for a <- written if mentionsKey(a, outer.key) && a != subject do
        err(s"'${show(a)}' is ${aOrAn(outer.label)}, and a '${qn(impl.traitName)}' whose arguments " +
          s"default names the type it is written for — so at one ${outer.label} this block and a " +
          "defaulted one would promise the same thing")

    // A shape and a type of that shape written out in full would both implement the trait for that
    // type. By default the second one written is refused, in whichever order the file put them,
    // because two overlapping blocks are usually a mistake — a duplicate, or one put in the wrong
    // module — and refusing them is how that gets found.
    //
    // The arguments do not rescue this one, though they are what lets a type keep several
    // implementations of a trait elsewhere. Several work because they share a namespace to be told
    // apart in; a shape's members and a written-out type's are filed under two different owner keys
    // and a lookup takes one or the other, so a second implementation across that boundary would be
    // one a program could not name however it was written.
    //
    // **What lifts the refusal is `override` on the written-out side** (`02 § override`), which says
    // the overlap is deliberate: `[]Point` beats `[]T` at the position they differ, so the ordering
    // has an answer and the keyword says an answer was wanted. It is asked of the *written-out*
    // block whichever order the two were hoisted in, which is why the flag travels in
    // `writtenShapes` rather than being read off whichever block reaches here second.
    val wkey = if written.isEmpty then "" else Type.Bound(impl.traitName, written).key

    for h <- outer.head do
      if outer.shaped then
        for (one, overridden) <- writtenShapes.get((impl.traitName, h)) if !overridden do
          err(s"'$one' already implements '${qn(impl.traitName)}', and this 'impl' would implement " +
            s"it for ${everyShape(h)} — including that one")
      else
        if implsOf(impl.traitName, h).nonEmpty && !impl.overrides then
          err(s"${everyShape(h)} already implements '${qn(impl.traitName)}', so '${outer.label}' has " +
            "an implementation and cannot be given a second one — write 'override impl' to say that " +
            "replacing it for this one type is what was meant")
        writtenShapes((impl.traitName, h)) = (outer.label, impl.overrides)

    // Last of the checks about the block as a whole, because every one above it is more specific:
    // a block with no home is often also one the library has already written, and being told which
    // implementation already covers the type is the more useful half of that.
    checkCoherence(impl, outer.label)

    // The first implementation of a trait for a type files its members under the names they were
    // written with; each one after it under names that differ, since a type's members are one
    // namespace whatever brought them (`08`). Nothing outside the hoist reads the suffix: every way
    // of reaching one of these members arrives with something that says which is meant — the
    // argument list for two implementations of one trait, and which trait is in scope for two
    // different traits.
    //
    // A **different trait** holding one of these names takes a suffix too, rather than the refusal
    // it used to take. A trait's member is reachable only where the trait can be named
    // (`reference/modules.md § Visibility`), so two traits declaring one name for a type are two
    // members a use site tells apart — which is what lets a program declare its own `Zero` for a
    // float width the library has already given a `zero`. A name held by the type's **own** body is
    // a real collision and is reported per member, so the search stops at one rather than stepping
    // over it.
    //
    // A **call** trait's member is the other thing that has no scope to be told apart by, and for
    // the same reason an inherent member has none: `t(1)` reaches it through the call syntax, which
    // names no trait for a file to have imported or left out. Its members are therefore recorded
    // with no provenance, which keeps them reachable wherever a value is and makes a second call
    // trait for one type the collision it has to be — `callableOf` answers with the first arity it
    // finds, so two would be a silent choice rather than an ambiguity anything could speak to.
    val callTrait = (0 to Type.Fn.maxArity).exists(n => traitKey(Type.Fn.base(n)).contains(impl.traitName))

    val floor = if already.isEmpty then 1 else already.length + 1

    def heldByAnother(a: String) =
      !callTrait && tr.methods.exists(tm =>
        memberTrait.get((outer.key, tm.name + a)).exists(_ != impl.traitName))

    val nth = LazyList.from(floor).find(i => !heldByAnother(if i == 1 then "" else s".$i")).get
    val home =
      outer.copy(alt = if nth == 1 then "" else s".$nth", fromTrait = Option.unless(callTrait)(impl.traitName),
        overrides = impl.overrides)

    // **An `override` that overrides nothing is refused**, and the question is held for the same
    // reason a required trait's is: the block being replaced may be written below this one, or in a
    // module hoisted after it. Held rather than answered here, so the answer never depends on the
    // order the files happened to arrive in.
    if impl.overrides && ty != Type.Unknown then
      overrideChecks += ((outer.label, bound, ty, impl.pos, currentScope))

    // The associated types the block supplies, in its own terms — the same substitution `subject`
    // is built under, so an argument built out of the block's parameter means one thing per
    // instantiation and the subject settles which.
    val assocSubst = abstractSubst(home.tparams, home.bounds, home.tvalues, home.tpacks) ++
      selfBinding(subject)

    for a <- impl.assocs do
      at(a.pos) {
        if !tr.assocs.exists(_.name == a.name) then
          err(s"trait '${qn(tr.name)}' declares no associated type '${a.name}', so this 'impl' has " +
            s"nothing to supply — the trait is where one is declared, as 'type ${a.name}: …'")
        if impl.assocs.count(_.name == a.name) > 1 then
          err(s"'${a.name}' is supplied twice by this 'impl', and a type has one associated " +
            s"'${a.name}' — the trait leaves it open once and the implementation fills it once")
      }

    // **A type implements at most one trait declaring an associated type of any one name.** The
    // projection is written without its trait — `Box::Item` and never `Box::Seq.Item` — so a second
    // trait bringing the same name to one type would make every projection of it a thing that cannot
    // be said. It is refused here, at the block that creates the collision, because that is where a
    // reader can do something about it; the alternative is the projection failing wherever anybody
    // writes one, including inside the traits' own declarations.
    for
      a               <- tr.assocs
      ((other, _), _) <- traitImpls.toList.filter((k, _) => k._2 == home.key && k._1 != impl.traitName)
      d               <- traitDecls.get(other) if d.assocs.exists(_.name == a.name)
    do
      at(impl.pos)(err(s"'${home.label}' already implements '${qn(other)}', which declares an " +
        s"associated type '${a.name}' — and so does '${qn(tr.name)}'. An associated type is named " +
        s"without its trait, so one type cannot have two of one name"))

    val writtenAssoc =
      impl.assocs.map(a => a.name -> at(a.pos)(resolveType(a.typ, assocSubst))).toMap

    // Which associated type each `some` result settles, and the member that settles it.
    val opaqueMembers = opaqueBindings(tr, block)

    // The lowered function each of those becomes. The name is built here rather than read back,
    // because it is the same name `synthesize` gives the member and the two must not be able to
    // disagree.
    val opaqueBind = opaqueMembers.map((aname, m) => aname -> s"${home.symbol}.${m.name}${home.alt}")

    for a <- tr.assocs do
      if !writtenAssoc.contains(a.name) && !opaqueBind.contains(a.name) then
        at(impl.pos)(err(s"'${home.label}' does not implement '${qn(tr.name)}': the associated type " +
          s"'${a.name}' is missing — supply it with 'type ${a.name} = …', or give the member that " +
          s"produces it the result 'some ${a.bounds.headOption.fold("Trait")(_.show)}' and let the " +
          "body say what it is"))

    // What the trait asked of the type supplying it, held to here for a written binding. A `some`
    // one is held to the same bounds once its body has been analyzed, which is the only moment its
    // type exists (`settleOpaqueResults`).
    for
      a  <- tr.assocs
      ty <- writtenAssoc.get(a.name)
      b  <- assocBoundsOf(bound, a, subject)
      if !satisfies(b, ty)
    do
      at(impl.assocs.find(_.name == a.name).flatMap(_.pos).orElse(impl.pos))(
        err(s"trait '${qn(tr.name)}' asks that its associated type '${a.name}' implement " +
          s"'${showBound(b, ty)}', and ${show(ty)} does not"))

    traitImpls((impl.traitName, home.key)) =
      already :+ TraitImpl(impl, written, wkey, home.alt, home.tparams,
        Option.when(home.tparams.nonEmpty && home.bounds.nonEmpty)((home.tparams, home.bounds)),
        currentScope, writtenAssoc, opaqueBind)

    // What the trait **requires** is asked of the implementing type here, at the block that makes
    // the promise, rather than at each bound that relies on it. Two reasons, and the second decides
    // it: the diagnostic belongs on the declaration that cannot keep its word, and a `&Sub` object's
    // table needs a slot for every required trait's method — so if the requirement were only checked
    // where the trait is *used*, a table could be asked for that has nothing to point at. The
    // question is held until every `impl` is registered, since the one that supplies a required
    // trait may be written below the one that needs it.
    // A **generic** block covers a type it never instantiates, so `ty` is unknown for it and the
    // subject of the question is the type applied to the block's own parameters — each standing in
    // for itself and carrying what the block asked of it. That is what makes
    // `impl[T: Named] Greet for Box[T]` answerable against `impl[T: Named] Named for Box[T]`: the
    // condition on one side is met by the condition on the other.
    for s <- tr.supers do
      superChecks += ((home.label, qn(tr.name),
        resolveBound(s, tr.tparams.zip(bound.args).toMap ++ selfBinding(subject)), subject, impl.pos))

    // A default the block left out is hoisted for this type exactly as a written method is, from the
    // body the trait supplied — so everything downstream (a call, a vtable slot, the escape summary)
    // finds an ordinary `Type.method` and needs to know nothing about where it came from. What is
    // recorded is where each copy came *from*, which is only ever read to keep one bad default from
    // being reported once per implementing type.
    //
    // Conformance is checked with the block's parameters standing in for themselves, which resolves
    // a signature mentioning one — and `Self`, which for a generic `impl` is the type applied to
    // them. Those instantiations are diagnostic only, so the walk is sandboxed the way
    // `reference/generics.md § Bounds`'s is.
    val inherited = sandboxed(checkConformance(tr, impl, home, signatures(home)))

    for m <- inherited do
      defaultOrigin(s"${home.symbol}.${m.name}${home.alt}") = s"${impl.traitName}.${m.name}"

    // The trait's defaults travel onto the block's own methods, so that a call to a known type
    // fills them from the same place a call through an object does (`reference/declarations.md §
    // Default parameters and named arguments`). Doing it here rather than at the call is what keeps
    // every downstream path — the concrete call, the vtable slot, the generic body checked against
    // a bound — from having to know a member came from an `impl`.
    val supplied = impl.methods.map(withTraitDefaults(tr, _))
    val lowered  = hoistMemberList(home, supplied ::: inherited, out)

    // Each `some` result becomes a job for the pass that runs once every declaration is in: a body
    // cannot be analyzed until then, and the block has to be registered before that for the body to
    // be able to name anything at all.
    for
      (aname, fname) <- opaqueBind
      decl           <- tr.assocs.find(_.name == aname)
      fd             <- lowered.find(_.name == fname)
      m              <- opaqueMembers.get(aname)
      promised       <- m.retType.collect { case SomeType(bs) => bs }
    do
      opaqueJobs += OpaqueJob(fname, fd, promised, assocBoundsOf(bound, decl, subject), home.label,
        DeclParser.sourceName(m.name), aname, currentScope, m.pos)

    // A generic block's members are checkable before anything instantiates them, against the bounds
    // the block wrote on its own parameters — the same walk a generic type's members take. An
    // inherited default is left out: it was checked at the trait, against what the trait promises,
    // which is the whole of what its body may assume wherever it is copied to.
    abstractMembers ++= lowered.filter(_.tparams.nonEmpty).filterNot(f => defaultOrigin.contains(f.name))
  }

  /** The block's members with every `some` result replaced by the projection it stands for —
   * `Self::Body` — and the two ways of writing one that are not this refused by name.
   *
   * The rewrite is the whole of the mechanism. Once the result reads `Self::Body`, the member's
   * signature is the one the trait declared, so conformance compares two identical things and every
   * later pass resolves an ordinary projection: what `some` bought was not having to write the type,
   * and it costs nothing after this line.
   */
  protected def openedResults(tr: TraitDecl, block: ImplDecl): List[MethodDecl] =
    block.methods.map { im =>
      im.retType match
        case Some(SomeType(bs)) =>
          at(im.pos) {
            val shown = s"some ${bs.map(_.show).mkString(" + ")}"

            tr.methods.find(_.name == im.name).flatMap(_.retType) match
              case Some(AssocType(NamedType(sn, Nil), a)) if sn == selfName && tr.assocs.exists(_.name == a) =>
                im.copy(retType = Some(AssocType(NamedType(selfName), a).setPos(im.pos))).setPos(im.pos)
              case Some(other) =>
                err(s"'$shown' says the trait left this result open for the implementation to " +
                  s"settle, and '${qn(tr.name)}' declares '${DeclParser.sourceName(im.name)}' " +
                  s"returning '${other.show}' — which is already the answer, so write it")
              case None =>
                err(s"trait '${qn(tr.name)}' declares no ${kind(im)} " +
                  s"'${DeclParser.sourceName(im.name)}', so there is no associated type for " +
                  s"'$shown' to settle")
          }
        case _ => im
    }

  /** Which associated type each `some` member settles, read off the **trait's** declaration of that
   * member. A member whose trait counterpart is not a projection has already been refused above, so
   * what reaches here is exactly the members that settle one.
   */
  protected def opaqueBindings(tr: TraitDecl, block: ImplDecl): Map[String, MethodDecl] =
    (for
      im  <- block.methods
      _   <- im.retType.collect { case s: SomeType => s }
      tm  <- tr.methods.find(_.name == im.name)
      ref <- tm.retType.collect { case AssocType(NamedType(sn, Nil), a) if sn == selfName => a }
    yield ref -> im).toMap

  /** One of an `impl` block's methods, carrying whatever defaults the trait declared for it.
   *
   * An `impl` may declare none of its own — a call through an object has no implementation to read
   * one off, so the trait is the only place they can be written — which is what makes this a copy
   * rather than a merge, and what leaves nothing to decide when the two disagree.
   *
   * The copy carries the **trait's** key with it, in a `DefaultArg` wrapped around the expression
   * itself. A default is analyzed in the terms it was written in, and those are the trait's however
   * far from it the implementing type sits — the same rule an inherited *body* follows
   * (`defaultHome`), one declaration part further in. Binding wraps it a second time in the
   * implementing type's key, and the inner wrapper is the one that decides, which is what makes a
   * default written in one module mean the same thing implemented in another.
   *
   * A method the trait does not declare, or one whose parameters do not line up with it, is left
   * exactly as written: conformance is what says so, in the words that mistake deserves, and
   * quietly reshaping the member here would only make that report harder to read.
   */
  private def withTraitDefaults(tr: TraitDecl, im: MethodDecl): MethodDecl =
    tr.methods.find(tm => tm.name == im.name && tm.params.length == im.params.length) match
      case Some(tm) if tm.params.exists(_.default.isDefined) =>
        val carried = im.params.zip(tm.params).map { (p, t) =>
          p.copy(default = t.default.map(d => DefaultArg(Some(tr.name), d).setPos(d.pos)))
        }

        im.copy(params = carried).setPos(im.pos)
      case _ => im

  /** Where a copied default's body was written, for a member that is one.
   *
   * `defaultOrigin` records the trait method a copy came from, as the trait's key and the method's
   * name — so the trait itself is everything left of the last dot, a member name having none and a
   * key's module separator not being one.
   */
  protected def defaultHome(member: String): Option[Scope] =
    defaultOrigin.get(member).map(origin => scopeFor(origin.take(origin.lastIndexOf('.'))))

  /** Why a trait implemented twice **at one argument list** is refused, where the arguments might
   * have made a reader think this block asked for something else.
   *
   * A generic trait may be implemented more than once, so the complaint is not that there is already
   * an implementation but that there is already this one — and where the block wrote nothing, what
   * is worth saying is that leaving the arguments out is what made the two the same.
   */
  protected def secondImplementation(tr: TraitDecl, other: TraitImpl): String =
    if tr.tparams.isEmpty then ""
    else if other.written.isEmpty || other.wkey.isEmpty then
      s" — arguments left out are the ones '${qn(tr.name)}' declares them to default to, so the two " +
        "blocks implement the same trait at the same arguments"
    else
      s" — a trait's members become the type's, so a second '${tr.methods.head.name}' at these " +
        "arguments would have a call no way to say which was meant"

  /** Whether a type is built out of one particular owner key — what tells an argument that names the
   * type an `impl` is written for from one that names something else.
   */
  protected def mentionsKey(t: Type, key: String): Boolean = t match
    case n: Type.Named       => n.base == key || n.targs.exists(mentionsKey(_, key))
    case Type.Ptr(inner)     => mentionsKey(inner, key)
    case Type.Ref(inner, _)  => mentionsKey(inner, key)
    case Type.Array(_, elem) => mentionsKey(elem, key)
    case Type.Slice(elem, _) => mentionsKey(elem, key)
    case _                   => false

  /** `a Box`, `an Adder` — the article a diagnostic needs when it names a type in running prose. */
  protected def aOrAn(label: String): String =
    s"${if "aeiouAEIOU".contains(label.head) then "an" else "a"} $label"

}
