package io.github.edadma.sysl

import scala.collection.mutable

/** Registering declarations, before any body is looked at.
 *
 * Everything a program declares is recorded here first — types, functions, externs, members, and
 * the `impl` blocks that add members to a type — so a body may name anything the file declares
 * whatever order it appears in, and two declarations may refer to each other. Nothing in this file
 * analyzes an expression; it fills the tables that the rest of the analyzer reads.
 *
 * Two rules run through it. **A declaration that fails a check still exists**: the body analysis
 * that follows walks the source and will look the name up whatever was wrong with it, so reporting
 * a mistake must not also erase the thing the mistake is about. And **a member of a generic type
 * cannot be hoisted eagerly**, because its signature mentions parameters that have no meaning until
 * a call fixes them — it is kept generic and instantiated per call site instead.
 */
trait Hoisting extends TypeResolution {

  /** Registers one type-shaped declaration: a struct, an enum, a trait, or an `impl`. */
  protected def hoistType(stmt: Stmt): Unit = stmt match
    case s: StructDecl =>
      if typeNameTaken(s.name) then err(s"type '${s.name}' is already declared")
      structDecls(s.name) = s
    case e: EnumDecl =>
      if typeNameTaken(e.name) then err(s"type '${e.name}' is already declared")
      // A generic enum has no single storage type to pin, and it is never instantiated
      // eagerly, so the annotation is rejected here at the declaration rather than on use.
      if e.underlying.isDefined && e.tparams.nonEmpty then
        err(s"a generic enum cannot pin an underlying type — '${e.name}' takes type parameters")
      enumDecls(e.name) = e
      for v <- e.variants do
        if variantOwner.contains(v.name) then
          err(s"variant name '${v.name}' is already used by enum '${variantOwner(v.name)}'")
        variantOwner(v.name) = e.name
    case t: TraitDecl =>
      if typeNameTaken(t.name) then err(s"the name '${t.name}' is already declared")
      traitDecls(t.name) = t
      if t.tparams.nonEmpty then err(s"generic traits are not supported yet — '${t.name}'")
      for m <- t.methods do
        // A property carries its receiver without writing one, so the reading below — no receiver
        // and a body, which for a method means a default with nothing to work on — is not what a
        // property with a body is. That one is a default property, and it is allowed.
        if m.receiver.isEmpty && !m.isProperty && m.body.nonEmpty then
          at(m.pos)(err(s"'${t.name}.${m.name}' has no receiver, so a default body has no value to " +
            "work on — give it a 'self' parameter or drop the body"))
        // No implementation could supply one either, so the trait is where it is worth saying so.
        if m.tparams.nonEmpty then
          at(m.pos)(err(s"generic methods are not supported yet — '${t.name}.${m.name}'"))
    // The type an `impl` names may be declared further down the file, so it cannot be resolved here
    // — the duplicate check goes with the resolution, in `hoistImpl`.
    case i: ImplDecl => implDecls += i
    case _ =>

  /** Registers one function's name and signature.
   *
   * A parameter or result whose type does not resolve is recorded and taken as unknown rather
   * than sinking the whole declaration, so the function still exists with the right arity and a
   * call to it is not reported a second time as an undefined name.
   *
   * An `extern` is registered the same way, under a synthesized declaration with an empty body:
   * everything downstream of the name — the arity check, argument checking, the emitted call — is
   * then the ordinary path, and what makes it an extern is that codegen finds it in `externDecls`
   * and declares it instead of looking for a body to define.
   *
   * **Registration comes before every check but the duplicate-name one**, which is the same rule as
   * the paragraph above generalized: a declaration that fails a check still exists, because the body
   * analysis that follows walks the source and will look this signature up whatever was wrong with
   * it. Reporting the mistake must not also remove the thing it is about.
   */
  protected def hoistFunc(stmt: Stmt): Unit = stmt match
    case f: FuncDecl =>
      if funcDecls.contains(f.name) then err(s"function '${f.name}' is already declared")
      funcDecls(f.name) = f
      if f.tparams.isEmpty then
        funcInsts(f.name) =
          (f.params.map(p => (p.name, recover(Type.Unknown)(resolveType(p.typ, Map.empty)))),
           f.retType.map(t => recover(Type.Unknown)(resolveReturn(t, Map.empty))).getOrElse(Type.Unit))
      checkSignatureRules(f.name, f.params, f.retType, f.variadic)
      checkBoundNames(f.name, f.bounds)

    case e: ExternDecl =>
      if funcDecls.contains(e.name) then err(s"function '${e.name}' is already declared")
      funcDecls(e.name) = FuncDecl(e.name, Nil, e.params, e.retType, Nil, variadic = e.variadic).setPos(e.pos)
      externDecls(e.name) = e
      funcInsts(e.name) =
        (e.params.map(p => (p.name, recover(Type.Unknown)(resolveType(p.typ, Map.empty)))),
         e.retType.map(t => recover(Type.Unknown)(resolveReturn(t, Map.empty))).getOrElse(Type.Unit))
      checkSignatureRules(e.name, e.params, e.retType, e.variadic)
      for s <- e.link if !s.matches("[A-Za-z0-9_$.]+") do
        err(s"'$s' is not a symbol a linker can resolve")

    case _ =>

  /** Checks that every bound a declaration writes names a trait, whichever declaration form wrote it
   * — a function, a struct, or an enum. A bound is a trait and nothing else (`10 §5`), so a name
   * that is a struct, a scalar, or nothing at all is reported here rather than silently promising
   * something no type could ever be held to.
   *
   * It runs in a pass after every type is registered, so a bound may name a trait declared further
   * down the file.
   */
  protected def checkBoundNames(name: String, bounds: Map[String, List[String]]): Unit =
    for (tp, traits) <- bounds; tr <- traits if !traitDecls.contains(tr) do
      err(s"the bound on '$tp' in '$name' names '$tr', which is not a trait")

  /** The rules a declared signature must satisfy whichever declaration form it came from, checked
   * after the name is registered so a failure reports the mistake without also erasing the
   * declaration it is about.
   */
  private def checkSignatureRules(
      name: String,
      params: List[Param],
      ret: Option[TypeRef],
      variadic: Boolean,
  ): Unit = {
    // C reads a variadic call's arguments relative to the last named parameter, so there has to be
    // one; `f(...)` is not a callable declaration in any C either.
    if variadic && params.isEmpty then err(s"'$name' needs at least one named parameter before '...'")
    checkNoVaList(name, params, ret)
  }

  /** A `va_list` may be a local, which is all a function needs to walk its own tail — but not a
   * parameter or a result, because handing one to another function is C's `vprintf` shape and its
   * calling convention is not implemented (`12 §Open g`). Refused with a diagnostic rather than
   * lowered to something that would pass the wrong thing.
   */
  private def checkNoVaList(name: String, params: List[Param], ret: Option[TypeRef]): Unit = {
    def isVaList(t: TypeRef) = t match
      case NamedType(n, Nil) => scalarType(n).contains(Type.VaList)
      case _                 => false

    for p <- params if isVaList(p.typ) do
      at(p.pos)(err(s"a va_list cannot be a parameter yet — '$name' would have to pass its tail on, " +
        "which is not supported"))
    for r <- ret if isVaList(r) do
      at(r.pos)(err(s"a va_list cannot be returned — it walks '$name''s own tail, which is gone once it returns"))
  }

  /** Structs, enums, and the built-in scalars share one type namespace, so a name may name at
   * most one of them. `never` is in it too: it names a type, so nothing else may.
   */
  private def typeNameTaken(name: String): Boolean =
    structDecls.contains(name) || enumDecls.contains(name) || traitDecls.contains(name) ||
      scalarType(name).isDefined || name == neverName || name == selfName

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
        tname,
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

    if tparams.nonEmpty then abstractMembers ++= lowered
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
   */
  private case class MemberHome(
      key: String,
      label: String,
      symbol: String,
      head: Option[String],
      selfRef: TypeRef,
      tparams: List[String],
      bounds: Map[String, List[String]],
      taken: Set[String],
      noun: String,
      self: Map[String, Type],
  ) {

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
    val generic = home.tparams.nonEmpty
    val lowered = mutable.ListBuffer.empty[FuncDecl]

    for m <- members do
      currentPos = m.pos.orElse(currentPos)
      if m.tparams.nonEmpty then err(s"generic methods are not supported yet — '${home.label}.${m.name}'")

      // An associated function is reached by naming its type — `Box.of(…)` — and only a struct or
      // an enum has a name to be reached through. A block for a built-in or a composed type would
      // register one that nothing could ever call, which is worth saying at the declaration rather
      // than leaving as a member the program cannot use.
      if m.receiver.isEmpty && !m.isProperty && nominal(home.key).isEmpty then
        err(s"'${m.name}' has no receiver, and '${home.label}' is not a name a call could reach it " +
          "through — give it a 'self' parameter")

      if memberDecls.contains((home.key, m.name)) then
        err(s"type '${home.label}' already has a member named '${m.name}'")
      if home.taken.contains(m.name) then
        err(s"type '${home.label}' has both a ${home.noun} and a member named '${m.name}'")

      // A composed type's members and its shape's are one namespace, so that a name reaches one
      // member however the type came by it. Both directions are asked, since a file may write the
      // shape before the types it covers or after them.
      for h <- home.head do
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

      memberDecls((home.key, m.name)) = m
      val fd = synthesize(home, m)

      lowered += fd

      if generic then
        genericMembers((home.key, m.name)) = fd
        // `Self` here is the type applied to its own parameters, which is not a type yet. The
        // reference is what waits, and every substitution that fixes the parameters fixes it too.
        genericSelf(fd.name) = home.selfRef
      else
        out += fd
        if home.self.nonEmpty then memberSelf(fd.name) = home.self
        funcInsts(fd.name) =
          (fd.params.map(p => (p.name, resolveType(p.typ, home.self))),
           fd.retType.map(resolveReturn(_, home.self)).getOrElse(Type.Unit))

    lowered.toList
  }

  /** Checks an `impl` conforms to its trait and lowers its methods to inherent members of the
   * implementing type. Conformance is nominal and exact: the type must supply every trait method
   * with a matching signature and no method the trait does not declare. The methods are then
   * hoisted exactly as a type's own members are, so a call on a value resolves through the ordinary
   * member path with no dispatch machinery of its own.
   */
  protected def hoistImpl(impl: ImplDecl, out: mutable.ListBuffer[FuncDecl]): Unit = {
    val tr         = traitDecls.getOrElse(impl.traitName, err(s"unknown trait '${impl.traitName}'"))
    val (ty, home) = implTarget(impl)

    // A built-in's memberships come from the compiler (`14 §5`), so an `impl` for one is not adding
    // a capability but competing with the one that is already there — and the operator would keep
    // lowering to its native instruction whatever this block said.
    if CoreTraits.builtin(impl.traitName, ty) then
      err(s"'${home.label}' already implements '${impl.traitName}' — the compiler provides it")

    // Keyed by the type rather than by the spelling, so `impl Show for int` and `impl Show for i32`
    // are the one implementation they are, and so are `[]int` and `[]i32`. A generic type has one
    // key for all of its instantiations, which is what makes an implementation cover the type as a
    // whole and two of them for one generic type a collision rather than a choice.
    if traitImpls.contains((impl.traitName, home.key)) then
      err(s"'${home.label}' already implements '${impl.traitName}'")

    // A shape and a type of that shape written out in full would both implement the trait for that
    // type, and sysl has no rule that picks between two implementations — the more specific one does
    // not win, because nothing here is more specific than anything else. So the second one written is
    // refused, in whichever order the file put them.
    for h <- home.head do
      if home.shaped then
        for written <- writtenShapes.get((impl.traitName, h)) do
          err(s"'$written' already implements '${impl.traitName}', and this 'impl' would implement " +
            s"it for ${everyShape(h)} — including that one")
      else
        if traitImpls.contains((impl.traitName, h)) then
          err(s"${everyShape(h)} already implements '${impl.traitName}', so '${home.label}' has an " +
            "implementation and cannot be given a second one")
        writtenShapes((impl.traitName, h)) = home.label

    traitImpls((impl.traitName, home.key)) = impl

    if home.tparams.nonEmpty && home.bounds.nonEmpty then
      implBounds((impl.traitName, home.key)) = home.tparams.map(home.bounds.getOrElse(_, Nil))

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

    for m <- inherited do defaultOrigin(s"${home.symbol}.${m.name}") = s"${impl.traitName}.${m.name}"

    val lowered = hoistMemberList(home, impl.methods ::: inherited, out)

    // A generic block's members are checkable before anything instantiates them, against the bounds
    // the block wrote on its own parameters — the same walk a generic type's members take. An
    // inherited default is left out: it was checked at the trait, against what the trait promises,
    // which is the whole of what its body may assume wherever it is copied to.
    if home.tparams.nonEmpty then abstractMembers ++= lowered.filterNot(f => defaultOrigin.contains(f.name))
  }

  /** What a signature written inside these members resolves under.
   *
   * A concrete `impl` binds only `Self`, to the one type it is for. A generic one binds its own
   * parameters to themselves — the opaque stand-in of `14 §4` — and `Self` to the type applied to
   * them, so `-> Self` and `-> Box[T]` are the one signature conformance compares, exactly as
   * `-> Self` and `-> Point` are on a concrete implementation.
   */
  private def signatures(home: MemberHome): Map[String, Type] =
    if home.tparams.isEmpty then home.self
    else
      val abstracts: Map[String, Type] =
        home.tparams.map(tp => tp -> Type.Abstract(tp, home.bounds.getOrElse(tp, Nil))).toMap

      abstracts + (selfName -> resolveType(home.selfRef, abstracts))

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
      case NamedType(written, argRefs) if nominal(written).isDefined =>
        val (tparams, taken, noun) = nominal(written).get

        if tparams.isEmpty then
          if impl.tparams.nonEmpty then
            err(s"'$written' takes no type arguments, so an 'impl' for it has nothing to be generic over")
          if argRefs.nonEmpty then err(s"type '$written' does not take type arguments")

          val ty = concrete(written).getOrElse(Type.Unknown)

          (ty, MemberHome(written, written, written, None, ref, Nil, Map.empty, taken, noun, selfBinding(ty)))
        else
          val order = implArgs(impl, written, tparams)

          // A generic type has no one instantiation to be, and nothing that reaches this needs one:
          // an implementation covers every `Box` at once, and each member is made real per receiver.
          (Type.Unknown,
           MemberHome(written, written, written, None, ref, order, impl.bounds, taken, noun, Map.empty))

      case NamedType(n, Nil) if n == selfName =>
        err("'Self' is the type an 'impl' is for, so it cannot also be the type it names")
      case NamedType(n, Nil) if n == neverName =>
        err("'never' has no values, so nothing can be implemented for it")

      case _ =>
        // The block's parameters stand in for themselves while the subject is resolved, so a shape
        // written with one comes back as the shape it is — `[]T` as a slice of something — rather
        // than through a complaint about a name that means nothing outside this block.
        val abstracts: Map[String, Type] =
          impl.tparams.map(tp => tp -> Type.Abstract(tp, impl.bounds.getOrElse(tp, Nil))).toMap

        // Resolved rather than taken as written, so the key is the type's one canonical name and
        // two spellings of one type are one implementation.
        val ty = resolveType(ref, abstracts)

        ty match
          case t if Type.erased(t) =>
            err(s"'${show(t)}' has forgotten which type it holds, and an 'impl' says how one " +
              "particular type behaves — so it is written for that type, not for an object over it")
          case Type.Ptr(inner)       => err(modeIsNotAType("*", inner))
          case Type.Ref(inner, sync) => err(modeIsNotAType(if sync then "&sync " else "&", inner))
          case Type.Unit   => err("'unit' has one value and no behaviour — a trait for it would say nothing")
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
    if head == "[]" then "every slice" else s"every array of ${head.drop(1).dropRight(1)}"

  /** The symbol a shape's members are emitted under, which is the mangling of the types it covers
   * with the arguments left off — so an instantiation appends them and arrives back where the type
   * written out in full would have started.
   */
  private def shapeSymbol(t: Type): String = t match
    case _: Type.Slice => "slice"
    case Type.Array(n, _) => s"arr$n"
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
        case Some(im) => checkSignature(home.label, impl.traitName, tm, im, sig)
        case None if tm.body.nonEmpty => inherited += tm
        case None =>
          err(s"'${home.label}' does not implement '${impl.traitName}': ${kind(tm)} '${tm.name}' is missing")

    for im <- impl.methods do
      if !declared.contains(im.name) then
        err(s"trait '${impl.traitName}' declares no ${kind(im)} '${im.name}', so this 'impl' cannot define it")

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
    if tm.params.length != im.params.length then
      err(s"method '${im.name}' of 'impl $traitName for $forType' takes ${im.params.length} " +
        s"parameters, but the trait declares ${tm.params.length}")

    for (tp, ip) <- tm.params.zip(im.params) do
      val want = resolveType(tp.typ, self)
      val got  = resolveType(ip.typ, self)
      if want != got then
        err(s"parameter '${ip.name}' of method '${im.name}' is ${show(got)}, but trait '$traitName' declares ${show(want)}")

    val want = tm.retType.map(resolveReturn(_, self)).getOrElse(Type.Unit)
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
   */
  private def synthesize(home: MemberHome, m: MethodDecl): FuncDecl =
    FuncDecl(
      s"${home.symbol}.${m.name}",
      home.tparams,
      receiverParam(m, home.selfRef).toList ::: m.params,
      m.retType,
      m.body,
      home.bounds,
    ).setPos(m.pos)

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
      List(selfName),
      receiverParam(m, NamedType(selfName, Nil)).toList ::: m.params,
      m.retType,
      m.body,
      bounds = Map(selfName -> List(tr.name)),
    ).setPos(m.pos)
}
