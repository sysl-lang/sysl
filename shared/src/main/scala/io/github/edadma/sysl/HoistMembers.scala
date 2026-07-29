package io.github.edadma.sysl

import scala.collection.mutable

/** Registering a type's **members**, and the `impl` blocks that give a type someone else's.
 *
 * A member is lowered to a function declaration under a mangled name, so a call on a value
 * resolves through the ordinary member path with no dispatch machinery of its own — and a
 * trait's methods become the implementing type's by the same route, which is what makes
 * `p.show()` one lookup whether `show` was written on `Point` or on a trait `Point` implements.
 *
 * `MemberHome` is what the two paths share: where a member belongs, what its `Self` is, and
 * which of the block's own parameters the subject binds. Both `hoistMembers` (a type's own) and
 * `hoistImpl` (a trait's, filed under the type) build one and hand it to `hoistMemberList`.
 */
trait HoistMembers extends TypeResolution {

  /** Records a type's members and lowers each to a function declaration under the mangled name
   * `Type.member`, whose signature is registered so calls resolve like ordinary ones.
   *
   * A member of a concrete type is hoisted eagerly, so an uncalled member is still type-checked at
   * its definition. A member of a *generic* type cannot be: its signature mentions the type's
   * parameters, which have no meaning until a call fixes them, so it is stored generic in
   * `genericMembers` and instantiated on demand at each call site. A method reads those arguments
   * off its receiver; an **associated function** has no receiver, so its call infers them from what
   * it is passed and from the type the context expects, exactly as a call to a generic free function
   * does. Members that introduce their own type parameters wait on later work and are rejected with
   * a clear diagnostic rather than silently mishandled.
   *
   * A generic type's members are handed to the definition-time pass of `14 §4` all the same. What
   * they may assume of the type's parameters is what the type asks of them — nothing, where it asks
   * nothing — and that is a rule a body can be held to before anything instantiates it, exactly as
   * a bounded generic function's body is.
   */
  protected def hoistMembers(tname: String, members: List[MethodDecl], out: mutable.ListBuffer[FuncDecl]): Unit = {
    val (tparams, taken, noun) = nominal(tname).get
    val bounds                 = nominalBounds(tname)

    checkBoundNames(tname, bounds)

    // A member of a concrete type may write `Self` for the type it is a member of, exactly as an
    // `impl`'s method may. A member of a *generic* one has its `Self` bound one step later, at each
    // instantiation, since `Box[T]` is not a type until `T` is one — `genericSelf` is where the
    // reference waits for that.
    val self = if tparams.nonEmpty then Map.empty else concrete(tname).fold(Map.empty[String, Type])(selfBinding)

    val lowered = hoistMemberList(
      MemberHome(
        tname,
        qn(tname),
        tname,
        None,
        NamedType(tname, tparams.map(NamedType(_, Nil))),
        tparams,
        bounds,
        taken,
        noun,
        self,
      ),
      members,
      out,
    )

    abstractMembers ++= lowered.filter(_.tparams.nonEmpty)
  }

  /** Everything hoisting a list of members needs to know about the type they belong to, whichever
   * declaration form brought them — a struct or enum body, or an `impl` block.
   *
   *   - `key` is what the members are filed under. For a block matching a **shape** it is the shape
   *     itself (`[]`), which is what a lookup falls back to when the type it is asked about has no
   *     members of its own.
   *   - `label` is what a diagnostic calls the type, which is the key everywhere the key is
   *     something a programmer would recognise — and the subject as written where it is not, since
   *     `[]` names nothing and `[]T` names what the block covers.
   *   - `symbol` is the same type mangled, which is what the lowered functions are *named*. The two
   *     differ only for a type that has no name of its own: `[]int` is a fine key and an impossible
   *     LLVM symbol, so its members are emitted under `slice.int`.
   *   - `head` is the shape these members share a namespace with, where they have one — the shape
   *     itself for a block matching one, and its own shape for a composed type written out in full.
   *     A type's members are one namespace whatever brought them, and this is what carries that rule
   *     across the two ways a composed type can come by one.
   *   - `selfRef` is the receiver's type as written, so a `self` parameter needs no reconstructing.
   *   - `tparams` are the parameters a member's signature may mention — a generic type's own, or a
   *     generic `impl`'s, **in the order the implementing type applies them**, so that instantiating
   *     a member from a receiver's type arguments substitutes them positionally. `bounds` is what
   *     was asked of them where they were declared, and it is what the members may assume.
   *   - `taken` are the names already spoken for inside the body (a struct's fields, an enum's
   *     variants), and `noun` what a diagnostic calls one of those.
   *   - `self` is what `Self` means inside these members, empty where the answer waits for an
   *     instantiation (a generic type's members) or means nothing at all.
   *   - `outer` is what the **trait's** own type parameters mean, for the members an `impl` of a
   *     generic trait brings. They are fixed by the block rather than by anything a call does, so
   *     unlike `tparams` they are answers rather than questions, and a member's signature and body
   *     read them exactly as they read `Self`.
   *   - `alt` distinguishes the members of a second implementation of one trait from the first's.
   *     It is empty everywhere else, so a type's own body and its first `impl` file their members
   *     under exactly the names they were written with.
   */
  private case class MemberHome(
      key: String,
      label: String,
      symbol: String,
      head: Option[String],
      selfRef: TypeRef,
      tparams: List[String],
      bounds: Map[String, List[BoundRef]],
      taken: Set[String],
      noun: String,
      self: Map[String, Type],
      outer: Map[String, Type] = Map.empty,
      alt: String = "",
  ) {

    /** Everything a member's signature resolves against that a call does not supply: the trait's
     * arguments, and `Self` where it is already known.
     */
    def fixed: Map[String, Type] = outer ++ self

    /** Whether this block is the one matching the shape, rather than one of the types it covers.
     * A composed type written out in full is never spelled as its own shape, so the keys settle it.
     */
    def shaped: Boolean = head.contains(key)
  }

  /** The instantiation of a non-generic struct or enum, which was made before any member was
   * hoisted. Read from the table rather than resolved again, so a type whose own declaration was
   * already reported does not report it a second time on the way to its members — the members are
   * still hoisted, with `Self` simply unbound.
   */
  private def concrete(name: String): Option[Type] =
    structInsts.get(name).orElse(enumInsts.get(name))

  private def nominal(name: String): Option[(List[String], Set[String], String)] =
    structDecls
      .get(name)
      .map(s => (s.tparams, s.fields.map(_.name).toSet, "field"))
      .orElse(enumDecls.get(name).map(e => (e.tparams, e.variants.map(_.name).toSet, "variant")))

  /** Lowers a list of members to functions, shared by a type's own body and an `impl` block. Each
   * member is registered under (type, name) and synthesized to a `Type.member` function; a member
   * of a concrete type is type-checked eagerly, while a member of a generic type waits for a
   * concrete instantiation.
   *
   * The declarations come back so that a caller with something further to do with them — a generic
   * `impl`, whose members are checked at their definition — needs no second walk to find them.
   */
  private def hoistMemberList(
      home: MemberHome,
      members: List[MethodDecl],
      out: mutable.ListBuffer[FuncDecl],
  ): List[FuncDecl] = {
    val lowered = mutable.ListBuffer.empty[FuncDecl]

    for m <- members do
      currentPos = m.pos.orElse(currentPos)

      // A member's parameters and its type's are two lists that end up in one signature, so a name
      // used by both would leave the lowered function with two parameters of that name and nothing
      // to say which one a `T` in the body meant.
      for tp <- m.tparams if home.tparams.contains(tp) do
        err(s"'${home.label}' already declares a type parameter '$tp', so member '${m.name}' " +
          "cannot declare one of that name")

      checkBoundNames(s"${home.label}.${m.name}", m.bounds)

      // An associated function is reached by naming its type — `Box.of(…)` — and only a struct or
      // an enum has a name to be reached through. A block for a built-in or a composed type would
      // register one that nothing could ever call, which is worth saying at the declaration rather
      // than leaving as a member the program cannot use.
      if m.receiver.isEmpty && !m.isProperty && nominal(home.key).isEmpty then
        err(s"'${m.name}' has no receiver, and '${home.label}' is not a name a call could reach it " +
          "through — give it a 'self' parameter")

      // The name this member is actually filed under. It is the one it was written with everywhere
      // but a second implementation of one trait, whose members would otherwise collide with the
      // first's — and the collisions asked about below are still asked about the *written* name,
      // since that is the one a program spells.
      val filed = m.name + home.alt

      if memberDecls.contains((home.key, filed)) then
        err(s"type '${home.label}' already has a member named '${m.name}'")
      if home.taken.contains(m.name) then
        err(s"type '${home.label}' has both a ${home.noun} and a member named '${m.name}'")

      // A composed type's members and its shape's are one namespace, so that a name reaches one
      // member however the type came by it. Both directions are asked, since a file may write the
      // shape before the types it covers or after them. A second implementation's members are left
      // out: the name they are filed under is one no other block can have written.
      for h <- home.head if home.alt.isEmpty do
        if home.shaped then
          for written <- composedMembers.get((h, m.name)) do
            err(s"'$written' already has a member named '${m.name}', and this 'impl' would give " +
              s"${everyShape(h)} one — a call on a '$written' could mean either")
        else
          if memberDecls.contains((h, m.name)) then
            err(s"${everyShape(h)} already has a member named '${m.name}', so '${home.label}' " +
              "cannot declare one of its own")
          composedMembers((h, m.name)) = home.label

      for ty <- home.self.get(selfName) do
        // A built-in's catalog methods are the compiler's (`14 §5`), and member lookup would find a
        // member of the same name first — so an `impl` of some *other* trait may not quietly take
        // `5.add` over from the `Add` the type is already a member of.
        for tr <- CoreTraits.declaring(m.name) if CoreTraits.builtin(tr, ty) do
          err(s"'${m.name}' is how '$tr' is implemented for ${show(ty)}, and the compiler provides " +
            s"that — a member of this name would hide it")

        // `len` and `bytes` are the same situation one step further out: a member of a built-in that
        // the compiler supplies, reached ahead of the member table rather than through it.
        if builtinMember(ty, m.name) then
          err(s"'${m.name}' is a member the compiler provides for ${show(ty)} — a member of this " +
            "name would hide it")

      memberDecls((home.key, filed)) = m

      // A name a program spells that now reaches more than one member is recorded as reaching all of
      // them, first one included, so a call has the whole set to answer from.
      if home.alt.nonEmpty then
        memberAlts((home.key, m.name)) = memberAlts.getOrElse((home.key, m.name), List(m.name)) :+ filed

      val fd = synthesize(home, m)

      // A member is filed under the type it belongs to, which an `impl` in another module may have
      // named — and an inherited default's body is the trait's source, wherever the trait is. Both
      // resolve their names where they were written, so that is what is recorded here.
      declScope(fd.name) = defaultHome(fd.name).getOrElse(currentScope)

      lowered += fd

      // A signature mentioning a type parameter — the type's own, or the member's — has no meaning
      // until something fixes it, so the member is kept as written and made real per call.
      if fd.tparams.nonEmpty then
        genericMembers((home.key, filed)) = fd
        // On a generic type `Self` is the type applied to its own parameters, which is not a type
        // yet. The reference is what waits, and every substitution that fixes the parameters fixes
        // it too; on a concrete type it is already the answer and resolves to the same thing.
        genericSelf(fd.name) = (home.selfRef, currentScope)
        if home.outer.nonEmpty then genericOuter(fd.name) = home.outer
      else
        out += fd
        if home.fixed.nonEmpty then memberSelf(fd.name) = home.fixed
        funcInsts(fd.name) =
          (fd.params.map(p => (p.name, resolveType(p.typ, home.fixed))),
           fd.retType.map(resolveReturn(_, home.fixed)).getOrElse(Type.Unit))

    lowered.toList
  }

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
  private def defaultHome(member: String): Option[Scope] =
    defaultOrigin.get(member).map(origin => scopeFor(origin.take(origin.lastIndexOf('.'))))

  /** Why a trait implemented twice **at one argument list** is refused, where the arguments might
   * have made a reader think this block asked for something else.
   *
   * A generic trait may be implemented more than once, so the complaint is not that there is already
   * an implementation but that there is already this one — and where the block wrote nothing, what
   * is worth saying is that leaving the arguments out is what made the two the same.
   */
  private def secondImplementation(tr: TraitDecl, other: TraitImpl): String =
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
  private def mentionsKey(t: Type, key: String): Boolean = t match
    case n: Type.Named       => n.base == key || n.targs.exists(mentionsKey(_, key))
    case Type.Ptr(inner)     => mentionsKey(inner, key)
    case Type.Ref(inner, _)  => mentionsKey(inner, key)
    case Type.Array(_, elem) => mentionsKey(elem, key)
    case Type.Slice(elem)    => mentionsKey(elem, key)
    case _                   => false

  /** `a Box`, `an Adder` — the article a diagnostic needs when it names a type in running prose. */
  private def aOrAn(label: String): String =
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
  private def implBound(impl: ImplDecl, tr: TraitDecl): (Type.Bound, List[Type]) = {
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
  private def signatures(home: MemberHome): Map[String, Type] =
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
  private def implTarget(impl: ImplDecl): (Type, MemberHome) = {
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

  /** What a diagnostic calls every type of a shape at once. */
  private def everyShape(head: String): String =
    if head == "[]" then "every slice"
    else if head.startsWith("(") then s"every tuple of ${head.count(_ == ',') + 1} parts"
    else s"every array of ${head.drop(1).dropRight(1)}"

  /** The symbol a shape's members are emitted under, which is the mangling of the types it covers
   * with the arguments left off — so an instantiation appends them and arrives back where the type
   * written out in full would have started.
   */
  private def shapeSymbol(t: Type): String = t match
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
  private def notGeneric(ref: TypeRef): Nothing = ref match
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
  private def implArgs(impl: ImplDecl, written: String, tparams: List[String]): List[String] = {
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
  private def shapeArgs(impl: ImplDecl, ty: Type, shape: String): List[String] = {
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

  private def modeIsNotAType(sigil: String, inner: Type): String =
    s"'$sigil${show(inner)}' is a way of holding a ${show(inner)} rather than a type of its own — " +
      s"write the 'impl' for ${show(inner)}, which a member call reaches through one '${sigil.trim}' to find"

  /** Verifies that `impl` supplies the members `tr` declares, each with an identical resolved
   * signature, and yields the **defaults it inherits** — the members it did not write and does not
   * have to, because the trait supplied a body for them.
   *
   * A member the trait declares without a default and the block leaves out, a member the trait does
   * not declare at all, or a mismatched kind, receiver, parameter, or result is reported against the
   * trait it fails to satisfy.
   */
  private def checkConformance(
      tr: TraitDecl,
      impl: ImplDecl,
      home: MemberHome,
      sig: Map[String, Type],
  ): List[MethodDecl] = {
    val declared  = tr.methods.map(_.name).toSet
    val inherited = mutable.ListBuffer.empty[MethodDecl]

    for tm <- tr.methods do
      impl.methods.find(_.name == tm.name) match
        case Some(im) => checkSignature(home.label, qn(impl.traitName), tm, im, sig, scopeFor(tr.name))
        case None if tm.body.nonEmpty => inherited += tm
        case None =>
          err(s"'${home.label}' does not implement '${qn(impl.traitName)}': ${kind(tm)} '${tm.name}' is missing")

    for im <- impl.methods do
      if !declared.contains(im.name) then
        err(s"trait '${qn(impl.traitName)}' declares no ${kind(im)} '${im.name}', so this 'impl' cannot define it")

    inherited.toList
  }

  /** What a diagnostic calls one member of a trait. The three kinds are told apart by shape rather
   * than by a keyword, so a message that names the wrong one sends the reader looking for the wrong
   * mistake.
   */
  private def kind(m: MethodDecl): String =
    if m.isProperty then "property" else if m.receiver.isEmpty then "associated function" else "method"

  /** Compares one implementing method against the trait's signature: same receiver mode, same
   * parameter types in order, and the same result.
   *
   * Both sides are resolved with `Self` bound to the implementing type, which is what makes a
   * signature written with `Self` and one written with the concrete name the same signature
   * (`14 §1`) — the comparison is between *resolved* types, so it does not matter which spelling
   * either side chose.
   */
  private def checkSignature(
      forType: String,
      traitName: String,
      tm: MethodDecl,
      im: MethodDecl,
      self: Map[String, Type],
      traitScope: Scope,
  ): Unit = {
    // Which *kind* of member it is comes first, because two of the three kinds have no receiver
    // between them: a property and an associated function both answer `None`, so the receiver
    // comparison below would let one stand for the other without ever noticing.
    if tm.isProperty != im.isProperty then
      if tm.isProperty then
        err(s"'${im.name}' is a property of trait '$traitName', so an implementation writes it as " +
          s"'${im.name} -> …' with no parameter list")
      else
        err(s"'${im.name}' is a method of trait '$traitName', so an implementation writes it with a " +
          "parameter list — a property has none")
    if tm.receiver != im.receiver then
      err(s"method '${im.name}' of 'impl $traitName for $forType' takes a different receiver than the trait declares")
    // A member of an `impl` is the trait's member supplied, so its shape is the trait's — including
    // how many types of its own it is generic over. The trait declares none today, which makes this
    // the diagnostic for writing a generic method in an `impl`.
    if tm.tparams.length != im.tparams.length then
      err(s"${kind(im)} '${im.name}' of 'impl $traitName for $forType' declares " +
        s"${quantity(im.tparams.length, "type parameter")}, but trait '$traitName' declares " +
        s"${tm.tparams.length}")
    if tm.params.length != im.params.length then
      err(s"method '${im.name}' of 'impl $traitName for $forType' takes ${im.params.length} " +
        s"parameters, but the trait declares ${tm.params.length}")

    // The two signatures were written in two files — the trait's and the block's — so each side is
    // resolved where it was written, under that file's module and its imports. A `Point` in the
    // trait's `-> Point` is the trait module's `Point`, whether or not the implementing module has
    // one of its own.
    for (tp, ip) <- tm.params.zip(im.params) do
      val want = inScope(traitScope)(resolveType(tp.typ, self))
      val got  = resolveType(ip.typ, self)
      if want != got then
        err(s"parameter '${ip.name}' of method '${im.name}' is ${show(got)}, but trait '$traitName' declares ${show(want)}")

    val want = inScope(traitScope)(tm.retType.map(resolveReturn(_, self)).getOrElse(Type.Unit))
    val got  = im.retType.map(resolveReturn(_, self)).getOrElse(Type.Unit)
    if want != got then
      err(s"method '${im.name}' returns ${show(got)}, but trait '$traitName' declares ${show(want)}")
  }

  /** Builds the function a member lowers to: the receiver becomes an ordinary first parameter
   * named `self`, carrying the memory mode its sigil asked for. A property's receiver is an
   * implicit by-value read; an associated function has no receiver. On a generic type the self
   * type is the type applied to its own parameters (`Box[T]`, not `Box`), and the lowered function
   * inherits those parameters, so instantiating it at a concrete `Box[int]` substitutes `T` in the
   * receiver, the result, and the body alike.
   *
   * A member's **own** parameters follow the type's, in that order and never interleaved, because
   * the two are fixed from different places and the order is what lets them be: a call reads the
   * type's off the receiver and appends the ones it solved, so the same positional substitution
   * serves a member that adds parameters and one that adds none.
   */
  private def synthesize(home: MemberHome, m: MethodDecl): FuncDecl = {
    val name = s"${home.symbol}.${m.name}${home.alt}"

    FuncDecl(
      name,
      home.tparams ::: m.tparams,
      receiverParam(m, selfRefFor(name, home)).toList ::: m.params,
      m.retType,
      m.body,
      home.bounds ++ m.bounds,
    ).setPos(m.pos)
  }

  /** The receiver's type as a member's lowered signature carries it.
   *
   * A member written here names it as the block did. An **inherited default** is read in the trait's
   * terms (`defaultHome`), because that is where its body and the rest of its signature came from —
   * and the subject is the one thing in it that was not written there, so a copy naming it as the
   * block spelled it would have that spelling looked for in the trait's module. The copy therefore
   * names it `Self`, which is what the trait itself calls the implementing type, and the binding for
   * `Self` is made where the subject really was written.
   */
  private def selfRefFor(name: String, home: MemberHome): TypeRef =
    if defaultOrigin.contains(name) then NamedType(selfName) else home.selfRef

  /** The `self` parameter a member's receiver becomes, at the self type it is a member of. A
   * property's receiver is an implicit by-value read; an associated function has none.
   */
  private def receiverParam(m: MethodDecl, selfRef: TypeRef): Option[Param] = m.recvMode.map {
    case RecvMode.ByValue     => Param("self", selfRef)
    case RecvMode.ByPtr       => Param("self", PtrType(selfRef))
    case RecvMode.ByRef(sync) => Param("self", RefType(selfRef, sync))
  }

  /** Every trait default, as the generic function each one is: one type parameter, `Self`, bounded
   * by the trait that declared it.
   *
   * That is what a default body *means* — it may assume of its receiver exactly what the trait
   * promises, and nothing else — so writing it down this way is what lets the definition-time pass
   * of `14 §4` check it once, at the trait, with the machinery a bounded generic already uses. The
   * declarations exist only for that walk; the body a program runs is the copy `hoistImpl` makes for
   * each implementing type.
   *
   * A **property** with a body is a default like any other, and needs nothing said about it here: its
   * declaration form already carries a body, so the only question was whether the trait was allowed
   * to write one, and the receiver it never spelled becomes a `self` parameter the same way.
   */
  protected def traitDefaults: List[FuncDecl] =
    for
      tr <- traitDecls.values.toList
      m  <- tr.methods if m.body.nonEmpty
    yield FuncDecl(
      s"${tr.name}.${m.name}",
      selfName :: tr.tparams,
      receiverParam(m, NamedType(selfName, Nil)).toList ::: m.params,
      m.retType,
      m.body,
      // A generic trait's default is generic over the trait's parameters too — they are as unknown
      // inside the body as `Self` is — and what `Self` promises is the trait *applied* to them,
      // which is the one promise every implementation of it makes.
      bounds = tr.bounds +
        (selfName -> List(BoundRef(tr.name, tr.tparams.map(NamedType(_, Nil))))),
    ).setPos(m.pos)
  /** Checks that every bound a declaration writes names a trait and applies it to as many arguments
   * as it declares, whichever declaration form wrote it — a function, a struct, an enum, a trait. A
   * bound is a trait and nothing else (`10 §5`), so a name that is a struct, a scalar, or nothing at
   * all is reported here rather than silently promising something no type could ever be held to.
   *
   * It runs in a pass after every type is registered, so a bound may name a trait declared further
   * down the file. What the *arguments* are is left to `resolveBound`, which is reached wherever the
   * substitution that gives them meaning exists; the arity is answerable here and worth saying at
   * the declaration rather than at whatever first applied it.
   */
  protected def checkBoundNames(name: String, bounds: Map[String, List[BoundRef]]): Unit =
    for (tp, traits) <- bounds; tr <- traits do
      // The whole check points at the bound rather than at the declaration carrying it, because
      // that is the text that is wrong. It also means a bound naming something the file may not
      // reach is reported at one place — this and the definition-time walk both resolve the name,
      // and two identical complaints about one bound are one mistake reported twice.
      at(tr.pos) {
        traitKey(tr.name).map(traitDecls) match
          case None       => err(s"the bound on '$tp' in '$name' names '${tr.name}', which is not a trait")
          case Some(decl) =>
            checkTraitArity(tr.name, decl.tparams, decl.tdefaults, tr.args.map(_ => Type.Unknown))
      }
}
