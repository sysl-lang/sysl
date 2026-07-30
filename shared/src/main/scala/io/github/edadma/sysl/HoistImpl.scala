package io.github.edadma.sysl

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
 * A coherence failure is the one diagnostic here worth its own vocabulary, and much of this file is
 * that vocabulary. The programmer has to be told *whose* trait and *whose* type in the words the
 * declaration used, which is why the phrasing is built from the written reference rather than from
 * the resolved type.
 */
trait HoistImpl extends ImplConformance {

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
    val impl = block.copy(traitName = tr.name).setPos(block.pos)

    val (ty, target)     = implTarget(impl)
    val (bound, written) = implBound(impl, tr)
    val outer            = target.copy(outer = tr.tparams.zip(bound.args).toMap)

    // A built-in's memberships come from the compiler (`14 §5`), so an `impl` for one is not adding
    // a capability but competing with the one that is already there — and the operator would keep
    // lowering to its native instruction whatever this block said. What the block *wrote* is what
    // decides: a catalog trait's arguments default to the implementing type, so an `impl Mul for
    // int` that wrote none of them is the one the compiler already provides, while one that wrote
    // an argument is asking for something else entirely.
    if written.isEmpty && CoreTraits.builtin(impl.traitName, ty) then
      err(s"'${outer.label}' already implements '${qn(impl.traitName)}' — the compiler provides it")

    // What this block's own promise is read against: the type where it has one, and the type applied
    // to the block's own parameters where it is generic — the same subject `superChecks` uses, and
    // the one every comparison below has to be made under for the two sides to mean the same thing.
    val subject =
      if impl.tparams.isEmpty then ty
      else sandboxed(resolveType(impl.forType, abstractSubst(impl.tparams, impl.bounds)))

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
    val mine = outer.tparams.map(Type.Abstract(_, Nil))

    for other <- already.find(ti => suppliedBound(ti, impl.traitName, subject, mine).key == bound.key) do
      err(s"'${outer.label}' already implements '${showBound(bound, subject)}'" + secondImplementation(tr, other))

    // **A result is not a selector.** An operator trait's last argument is what the operator gives
    // back (`14 §7`), and `a * b` supplies the operands and nothing else — so two implementations that
    // agree on the operands and differ only in the result leave a use with nothing to choose by.
    // Refused here rather than at the use, because the use is where it would be too late to say which
    // of the two the program meant.
    if CoreTraits.selectsByOperand(impl.traitName) && bound.args.length > 1 then
      val operands = bound.args.dropRight(1)

      for
        other <- already.find { ti =>
          val theirs = suppliedBound(ti, impl.traitName, subject, mine)

          theirs.args.length == bound.args.length && theirs.args.dropRight(1) == operands
        }
        theirs = suppliedBound(other, impl.traitName, subject, mine)
      do
        err(s"'${outer.label}' already implements '${showBound(theirs, subject)}', and this one differs " +
          s"only in what it gives back — '${CoreTraits.required(impl.traitName)._2}' between " +
          s"${show(subject)} and ${conjoin(operands.map(show))} would have two results to choose from " +
          "and nothing at the use to choose with")

    // On a **generic** subject a defaulted argument list is not one promise but one per
    // instantiation, since the trait's own default names the type being asked about. A written
    // argument built out of that same type would coincide with it at one instantiation and not at
    // others, which is a choice between implementations rather than a lookup — so it is refused
    // here, where the block that would need choosing between is the one being read.
    if outer.tparams.nonEmpty && tr.tdefaults.values.exists(mentionsSelf) then
      for a <- written if mentionsKey(a, outer.key) do
        err(s"'${show(a)}' is ${aOrAn(outer.label)}, and a '${qn(impl.traitName)}' whose arguments " +
          s"default names the type it is written for — so at one ${outer.label} this block and a " +
          "defaulted one would promise the same thing")

    // A shape and a type of that shape written out in full would both implement the trait for that
    // type, and sysl has no rule that picks between two implementations — the more specific one does
    // not win, because nothing here is more specific than anything else. So the second one written is
    // refused, in whichever order the file put them.
    //
    // The arguments do not rescue this one, though they are what lets a type keep several
    // implementations of a trait elsewhere. Several work because they share a namespace to be told
    // apart in; a shape's members and a written-out type's are filed under two different owner keys
    // and a lookup takes one or the other, so a second implementation across that boundary would be
    // one a program could not name however it was written.
    val wkey = if written.isEmpty then "" else Type.Bound(impl.traitName, written).key

    for h <- outer.head do
      if outer.shaped then
        for one <- writtenShapes.get((impl.traitName, h)) do
          err(s"'$one' already implements '${qn(impl.traitName)}', and this 'impl' would implement " +
            s"it for ${everyShape(h)} — including that one")
      else
        if implsOf(impl.traitName, h).nonEmpty then
          err(s"${everyShape(h)} already implements '${qn(impl.traitName)}', so '${outer.label}' has " +
            "an implementation and cannot be given a second one")
        writtenShapes((impl.traitName, h)) = outer.label

    // Last of the checks about the block as a whole, because every one above it is more specific:
    // a block with no home is often also one the prelude has already written, and being told which
    // implementation already covers the type is the more useful half of that.
    checkCoherence(impl, outer.label)

    // The first implementation of a trait for a type files its members under the names they were
    // written with; each one after it under names that differ, since a type's members are one
    // namespace whatever brought them (`08`). Nothing outside the hoist reads the suffix: every way
    // of reaching one of these members arrives with the argument list that says which is meant.
    val home = outer.copy(alt = if already.isEmpty then "" else s".${already.length + 1}")

    traitImpls((impl.traitName, home.key)) =
      already :+ TraitImpl(impl, written, wkey, home.alt, home.tparams,
        Option.when(home.tparams.nonEmpty && home.bounds.nonEmpty)((home.tparams, home.bounds)))

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
    // them. Those instantiations are diagnostic only, so the walk is sandboxed the way `14 §4`'s is.
    val inherited = sandboxed(checkConformance(tr, impl, home, signatures(home)))

    for m <- inherited do
      defaultOrigin(s"${home.symbol}.${m.name}${home.alt}") = s"${impl.traitName}.${m.name}"

    val lowered = hoistMemberList(home, impl.methods ::: inherited, out)

    // A generic block's members are checkable before anything instantiates them, against the bounds
    // the block wrote on its own parameters — the same walk a generic type's members take. An
    // inherited default is left out: it was checked at the trait, against what the trait promises,
    // which is the whole of what its body may assume wherever it is copied to.
    abstractMembers ++= lowered.filter(_.tparams.nonEmpty).filterNot(f => defaultOrigin.contains(f.name))
  }

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
    case Type.Slice(elem)    => mentionsKey(elem, key)
    case _                   => false

  /** `a Box`, `an Adder` — the article a diagnostic needs when it names a type in running prose. */
  protected def aOrAn(label: String): String =
    s"${if "aeiouAEIOU".contains(label.head) then "an" else "a"} $label"

  /** Which promise an `impl` block supplies: the trait, at the arguments this block writes for it.
   *
   * An argument may name one of the block's own parameters — `impl[T] Index[usize, T] for Buf[T]`
   * says a `Buf` of anything is read by a `usize` and gives back whatever it holds. That is one
   * promise per instantiation, exactly as a defaulted argument list on a generic subject is: a
   * `Buf[int]` implements `Index[usize, int]` and nothing else.
   *
   * Nothing has to be checked here for that to hold, because a generic block's parameters are
   * already exactly the arguments of the type it is for — `implArgs` and `shapeArgs` require each
   * one to appear in the subject and to appear once. So a parameter an argument can name is one the
   * subject settles, and the open case those checks refuse (`impl[V, T] From[T] for Wrapper[V]`,
   * where nothing would ever fix `T`) never reaches this far.
   *
   * What makes it safe beyond that is that sysl has no specialization: an `impl` for a generic type
   * covers every instantiation, and `impl Index[usize, int] for Buf[int]` is refused outright
   * (`implTarget`). So one block per (trait-at-arguments, generic type) still holds, and a parameter
   * the subject settles is a key that matches one thing per subject rather than many.
   */
  protected def implBound(impl: ImplDecl, tr: TraitDecl): (Type.Bound, List[Type]) = {
    checkTraitArity(qn(impl.traitName), tr.tparams, tr.tdefaults, impl.traitArgs.map(_ => Type.Unknown))

    // The block's parameters resolve to themselves so that naming one here is caught as the thing it
    // is, rather than reported as an unknown type — which would send the reader looking for a
    // declaration rather than at the argument they meant to fix.
    val declared = abstractSubst(impl.tparams, impl.bounds)
    val written  = impl.traitArgs.map(resolveType(_, declared))
    val subject  = sandboxed(resolveType(impl.forType, declared))

    // What the block leaves out the trait supplies, and `Self` in one of those defaults is the type
    // this block implements the trait for — which is what makes `impl Mul for Point` the
    // `impl Mul[Point] for Point` it reads as.
    val args =
      if written.length == tr.tparams.length then written
      else withDefaults(impl.traitName, tr.tparams, tr.tdefaults, written, selfBinding(subject))

    // Asked of what the block **wrote**, not of what the defaults filled in. A conditional block's
    // subject is the type applied to its own parameters, so a default of `Self` names them by
    // construction — `impl[T: Named] Pairable for Box[T]` supplies `Pairable[Box[T]]`, which is one
    // implementation and not a family of them, and only an argument the author left open is.
    for tp <- impl.tparams if tr.tparams.contains(tp) do
      err(s"trait '${qn(impl.traitName)}' already declares a type parameter '$tp', so this 'impl' " +
        "cannot declare one of that name")

    deferredBounds(impl.traitName, tr.tparams, tr.bounds, args)

    (Type.Bound(impl.traitName, args), written)
  }

  /** What a signature written inside these members resolves under.
   *
   * A concrete `impl` binds only `Self`, to the one type it is for. A generic one binds its own
   * parameters to themselves — the opaque stand-in of `14 §4` — and `Self` to the type applied to
   * them, so `-> Self` and `-> Box[T]` are the one signature conformance compares, exactly as
   * `-> Self` and `-> Point` are on a concrete implementation.
   *
   * The trait's own parameters are bound either way, since the block fixed them: a method written in
   * the trait's `T` and one written in the type that `T` is are the same signature.
   */
  protected def signatures(home: MemberHome): Map[String, Type] =
    home.outer ++ {
      if home.tparams.isEmpty then home.self
      else
        val abstracts = abstractSubst(home.tparams, home.bounds)

        abstracts + (selfName -> resolveType(home.selfRef, abstracts))
    }

  /** The type an `impl` is for, and where its members belong.
   *
   * A trait may be implemented for **any** type it makes sense to name: built-ins included — a
   * language whose `Show` cannot cover `int` has a `Show` no library can use — the composed types
   * too, so `impl Display for []int` says how a slice of ints renders, a **generic** struct or enum
   * as a whole, which is what an `impl` with type parameters of its own is for, and a composed
   * **shape**, which is that same block written for a type that has no name to be generic over. A
   * struct or an enum brings its own fields or variants for a member to collide with; nothing else
   * has any.
   *
   * Two shapes are refused outright, each because an `impl` for it would be about nothing. A
   * **memory mode** is a way of holding a type rather than a type, and a member call already sees
   * through one. A **trait object** has forgotten which type it holds, which is the one thing an
   * `impl` is written about.
   */
  protected def implTarget(impl: ImplDecl): (Type, MemberHome) = {
    val ref = impl.forType

    ref match
      // A name declaring a struct or an enum is the one shape that may be generic, so it is settled
      // here rather than by resolving a reference whose arguments are the block's own parameters.
      // Its key is the name it was declared under, which stands even where the declaration itself
      // failed to resolve and there is no instantiation to read one off.
      case NamedType(written, argRefs) if typeKey(written).exists(k => nominal(k).isDefined) =>
        val key                    = typeKey(written).get
        val (tparams, taken, noun) = nominal(key).get

        if tparams.isEmpty then
          if impl.tparams.nonEmpty then
            err(s"'$written' takes no type arguments, so an 'impl' for it has nothing to be generic over")
          if argRefs.nonEmpty then err(s"type '$written' does not take type arguments")

          val ty = concrete(key).getOrElse(Type.Unknown)

          (ty, MemberHome(key, qn(key), key, None, ref, Nil, Map.empty, taken, noun, selfBinding(ty)))
        else
          val order = implArgs(impl, written, tparams)

          // A generic type has no one instantiation to be, and nothing that reaches this needs one:
          // an implementation covers every `Box` at once, and each member is made real per receiver.
          (Type.Unknown,
           MemberHome(key, qn(key), key, None, ref, order, impl.bounds, taken, noun, Map.empty))

      case NamedType(n, Nil) if n == selfName =>
        err("'Self' is the type an 'impl' is for, so it cannot also be the type it names")
      case NamedType(n, Nil) if n == neverName =>
        err("'never' has no values, so nothing can be implemented for it")
      // Both valueless types are named here rather than left to `resolveType`, which refuses them
      // for standing anywhere but a result — true, and beside the point when what the block asks
      // is whether the type can behave.
      case NamedType(n, Nil) if n == unitName =>
        err("'unit' has one value and no behaviour — a trait for it would say nothing")

      case _ =>
        // The block's parameters stand in for themselves while the subject is resolved, so a shape
        // written with one comes back as the shape it is — `[]T` as a slice of something — rather
        // than through a complaint about a name that means nothing outside this block.
        val abstracts = abstractSubst(impl.tparams, impl.bounds)

        // Resolved rather than taken as written, so the key is the type's one canonical name and
        // two spellings of one type are one implementation.
        val ty = resolveType(ref, abstracts)

        ty match
          case t if Type.erased(t) =>
            err(s"'${show(t)}' has forgotten which type it holds, and an 'impl' says how one " +
              "particular type behaves — so it is written for that type, not for an object over it")
          case Type.Ptr(inner)       => err(modeIsNotAType("*", inner))
          case Type.Ref(inner, sync) => err(modeIsNotAType(if sync then "&sync " else "&", inner))
          // The third mode, refused for the same reason with a sharper consequence: a member call
          // reaches through a `*T` and a `&T`, and through a `weak T` it reaches nothing at all, so
          // a member written here would have no way to be called.
          case Type.Weak(inner) =>
            err(s"'weak ${show(inner)}' is a way of holding a ${show(inner)} rather than a type of " +
              s"its own — and nothing goes through one but 'get()', so a member written here could " +
              s"never be called. Write the 'impl' for ${show(inner)}, which 'get()' hands back")
          case Type.VaList => err("a va_list is an ABI primitive, not something to implement a trait for")
          // The subject is the block's own parameter, which stands for any type at all — so the block
          // would be saying how every type behaves, which is what a trait's own defaults are for.
          case a: Type.Abstract =>
            err(s"'${a.name}' is a type parameter of this 'impl', so it stands for every type at " +
              "once — an 'impl' says how one kind of type behaves")
          case _ =>

        val head = shapeOwner(ty).map(_._1)

        if impl.tparams.isEmpty then
          (ty,
           MemberHome(ownerKey(ty), show(ty), Type.mangle(ty), head, ref, Nil, Map.empty, Set.empty, "field",
             selfBinding(ty)))
        else
          val shape = head.getOrElse(notGeneric(ref))
          val order = shapeArgs(impl, ty, shape)

          // Like a generic type's, the members are made real per receiver — so there is no one
          // instantiation to be, and the shape rather than any type of it is what they are filed
          // under. The symbol drops the arguments the same way: `slice.show` instantiated at `int`
          // is `slice.show.int`, which the written `[]int`'s `slice.int.show` cannot collide with.
          (Type.Unknown,
           MemberHome(shape, ref.show, shapeSymbol(ty), head, ref, order, impl.bounds, Set.empty, "field",
             Map.empty))
  }

  /** Where an `impl` may be written (`02 § Coherence`): **the module that declares the trait, or one
   * that declares a type named in the subject**, and nowhere else.
   *
   * An `impl` is unnamed, so resolving a bound means *searching* for one — and this is the rule that
   * bounds the search to two modules, both of which anything naming the trait and the type already
   * depends on. What it forbids is the case with no home, a foreign trait for a foreign type, where
   * two unrelated modules could each supply a different implementation and no rule picks one.
   *
   * The prelude is a module of its own for this purpose even though its declarations are keyed under
   * the root like a rootless program's, so which file a declaration came from is what decides rather
   * than the key. A program at the project root is therefore as foreign to `Eq` as any named module
   * is.
   */
  protected def checkCoherence(impl: ImplDecl, label: String): Unit = {
    val home     = if Prelude.declares(impl) then None else Some(currentModule)
    val declarer = declaringModule(impl.traitName)
    val subject  = subjectHomes(impl.forType)

    if home != declarer && !subject(home) then
      err(s"an 'impl' may be written only in the module that declares the trait or in one that " +
        s"declares a type named in the subject, and '${qn(impl.traitName)}' ${whose(declarer)} " +
        s"while ${subjectPhrase(subject, label)} — so this one has no home. A trait of your own, or " +
        "a type of your own in what it is written for, gives it one")
  }

  protected def subjectPhrase(homes: Set[Option[String]], label: String): String =
    if homes == Set(None) then s"nothing in '$label' is declared outside the prelude"
    else s"'$label' names only what ${homes.toList.map(whose).sorted.mkString(" and ")}"

  /** Which module licenses what a key names, or `None` for the prelude's.
   *
   * Asked of the **declaration** rather than of `preludeNames`, which holds a prelude enum's variant
   * names beside its type names — so a program declaring a `struct Ok` of its own would have been
   * told its own type was the prelude's.
   */
  protected def declaringModule(key: String): Option[String] = {
    val decl: Option[Positioned] = structDecls.get(key)
      .orElse(enumDecls.get(key))
      .orElse(traitDecls.get(key))
      .orElse(constrainedDecls.get(key))

    decl match
      case Some(d) if Prelude.declares(d) => None
      case Some(_)                        => Some(Modules.moduleOf(key))
      // A name nothing declares is a built-in, which has no module of its own and is the prelude's.
      case None                           => None
  }

  /** Every module the **subject** of an `impl` belongs to: its own where it is a declared type, and
   * every one its parts belong to where it is composed (`02 § Coherence`).
   *
   * A composed type is the module's when anything named in it is — `[]Point` belongs where `Point`
   * does, `[]int` to nobody. Without that a module could not so much as print a slice of its own
   * struct, and the compiler's own advice ("write an `impl Display for []Point`") would name a block
   * the rule then refused.
   *
   * It reads the subject **as written** rather than as resolved, because a generic subject has no
   * one instantiation to be: `implTarget` answers `Unknown` for `Box[T]`, and the arguments — which
   * are exactly what may carry a local type — would be lost with it.
   */
  protected def subjectHomes(ref: TypeRef): Set[Option[String]] = ref match
    case NamedType(written, args) =>
      Set(quietly(typeKey(written)).flatMap(declaringModule)) ++ args.flatMap(subjectHomes)
    case PtrType(inner)      => subjectHomes(inner)
    case RefType(inner, _)   => subjectHomes(inner)
    case WeakType(inner)     => subjectHomes(inner)
    case ArrayType(_, elem)  => subjectHomes(elem)
    case TupleType(parts, _) => Set(None) ++ parts.flatMap(subjectHomes)
    case f: FnType           => subjectHomes(f.asTrait)

  /** A resolution made only to ask where a name lives, which is not the place a name that resolves
   * to nothing is worth reporting from — a scalar and a block's own type parameter both answer
   * `None` here, and a name the file may not reach was reported where the subject was resolved.
   */
  protected def quietly(lookup: => Option[String]): Option[String] =
    try lookup
    catch
      case _: AnalyzerError => None
      case _: Poisoned      => None

  protected def whose(module: Option[String]): String = module match
    case None                         => "belongs to the prelude"
    case Some(m) if m == Modules.root => "is declared at the project root"
    case Some(m)                      => s"is declared in module '$m'"

  /** What a diagnostic calls every type of a shape at once. */
  protected def everyShape(head: String): String =
    if head == "[]" then "every slice"
    else if head.startsWith("(") then s"every tuple of ${head.count(_ == ',') + 1} parts"
    else s"every array of ${head.drop(1).dropRight(1)}"

  /** The symbol a shape's members are emitted under, which is the mangling of the types it covers
   * with the arguments left off — so an instantiation appends them and arrives back where the type
   * written out in full would have started.
   */
  protected def shapeSymbol(t: Type): String = t match
    case _: Type.Slice    => "slice"
    case Type.Array(n, _) => s"arr$n"
    case t: Type.Tuple    => s"tuple${t.targs.length}"
    case other            => Type.mangle(other)

  /** Why a block declaring type parameters has nothing to apply them to: the subject is a type that
   * takes no arguments and has no shape either, so there is nothing for the parameters to fix.
   *
   * A composed type does have a shape, and matching it is what `shapeArgs` checks instead. What is
   * left here is a name — and by the time one reaches this it has already resolved, so a type that
   * does not exist or a trait was reported as itself rather than through a complaint about the
   * parameters, which would send the reader looking at the one part of the line written correctly.
   */
  protected def notGeneric(ref: TypeRef): Nothing = ref match
    case NamedType(n, _) =>
      err(s"'$n' takes no type arguments, so an 'impl' for it has nothing to be generic over")
    case _ =>
      err(s"'${ref.show}' has no shape for an 'impl' to match — a slice and an array do, and a " +
        "generic struct or enum has a name of its own to be generic over")

  /** The block's type parameters **in the order the implementing type applies them**, which is what
   * lets a member be instantiated from a receiver's own type arguments by position.
   *
   * An `impl` covers a generic type as a whole, so its subject must be that type applied to the
   * block's parameters and nothing else: each argument one parameter, each parameter used once, all
   * of them spoken for. Anything narrower — `Box[int]`, `Pair[T, int]` — is an implementation for
   * *some* instantiations, which is a second implementation for a key that holds one.
   */
  protected def implArgs(impl: ImplDecl, written: String, tparams: List[String]): List[String] = {
    val declared = impl.tparams.toSet
    val args     = impl.forType match
      case NamedType(_, as) => as
      case _                => Nil
    val spelled = s"impl[${tparams.mkString(", ")}] ${impl.traitName} for $written[${tparams.mkString(", ")}]"

    if impl.tparams.isEmpty then
      err(s"'$written' is generic, so an 'impl' for it covers every instantiation at once — " +
        s"write '$spelled'")
    if args.length != tparams.length then
      err(s"'$written' takes ${quantity(tparams.length, "type argument")}, but " +
        s"${supplied(args.length, "type argument")}")

    val names = args.map {
      case NamedType(n, Nil) if declared(n) => n
      case other =>
        err(s"'${other.show}' fixes an argument of '$written' to one type, and an 'impl' covers " +
          s"the whole of a generic type — write one of the block's own parameters here")
    }

    if names.distinct.length != names.length then
      err(s"each argument of '$written' takes a type parameter of its own, and this 'impl' " +
        s"names '${names.diff(names.distinct).head}' twice")
    if declared != names.toSet then
      err(s"'${(declared -- names).mkString("', '")}' is declared by this 'impl' but does not " +
        s"appear in '${impl.forType.show}', so nothing would ever fix it")

    names
  }

  /** The same, for a **shape**: the block's parameters in the order the shape applies them, which
   * for a slice or an array is the one element type.
   *
   * The rule is `implArgs`' rule, and it is the same rule because the reason is the same. A shape is
   * one key, so a block that fixed part of what it matched would be implementing the trait for
   * *some* of the types the shape covers — a second implementation for a key that holds one.
   */
  protected def shapeArgs(impl: ImplDecl, ty: Type, shape: String): List[String] = {
    val declared = impl.tparams.toSet
    val names    = shapeOwner(ty).get._2.map {
      case Type.Abstract(n, _) if declared(n) => n
      case other =>
        err(s"'${show(other)}' fixes the element type, and an 'impl' with type parameters covers " +
          s"${everyShape(shape)} — write one of the block's own parameters here")
    }

    if declared != names.toSet then
      err(s"'${(declared -- names).mkString("', '")}' is declared by this 'impl' but does not " +
        s"appear in '${impl.forType.show}', so nothing would ever fix it")

    names
  }

  protected def modeIsNotAType(sigil: String, inner: Type): String =
    s"'$sigil${show(inner)}' is a way of holding a ${show(inner)} rather than a type of its own — " +
      s"write the 'impl' for ${show(inner)}, which a member call reaches through one '${sigil.trim}' to find"
}
