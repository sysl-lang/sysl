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
      for (tp, traits) <- f.bounds; tr <- traits do
        if !traitDecls.contains(tr) then
          err(s"the bound on '$tp' in '${f.name}' names '$tr', which is not a trait")

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
   * `genericMembers` and instantiated on demand at each call site. Members that introduce their own
   * type parameters, and associated functions on a generic type — whose type arguments would have
   * to be inferred rather than read off a receiver — wait on later work and are rejected with a
   * clear diagnostic rather than silently mishandled.
   */
  protected def hoistMembers(tname: String, members: List[MethodDecl], out: mutable.ListBuffer[FuncDecl]): Unit = {
    val (tparams, taken, noun) = nominal(tname).get

    // A member of a concrete type may write `Self` for the type it is a member of, exactly as an
    // `impl`'s method may. A member of a *generic* one may not: `Self` there is `Box[T]`, whose
    // meaning waits for an instantiation, and binding it to anything else would be a lie.
    val self = if tparams.nonEmpty then Map.empty else concrete(tname).fold(Map.empty)(selfBinding)

    hoistMemberList(tname, tparams, taken, noun, members, out, self)
  }

  /** What hoisting a member needs to know about the type it is hoisting into: the type parameters
   * a member's signature may mention, the names already spoken for inside the body, and what a
   * diagnostic calls one of those names. A struct's are its fields; an enum's are its variants.
   *
   * It answers for either kind, which is what lets member hoisting and `impl` conformance be
   * written once — nothing below here knows or cares which it was handed.
   */
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

  /** Lowers a list of members of `tname` to functions, shared by a type's own body and an `impl`
   * block. Each member is registered under (type, name) and synthesized to a `Type.member`
   * function; a member of a concrete type is type-checked eagerly, while a member of a generic
   * type waits for a concrete instantiation.
   */
  private def hoistMemberList(
      tname: String,
      tparams: List[String],
      taken: Set[String],
      noun: String,
      members: List[MethodDecl],
      out: mutable.ListBuffer[FuncDecl],
      self: Map[String, Type],
  ): Unit = {
    val generic = tparams.nonEmpty

    for m <- members do
      currentPos = m.pos.orElse(currentPos)
      if m.tparams.nonEmpty then err(s"generic methods are not supported yet — '$tname.${m.name}'")
      if memberDecls.contains((tname, m.name)) then err(s"type '$tname' already has a member named '${m.name}'")
      if taken.contains(m.name) then
        err(s"type '$tname' has both a $noun and a member named '${m.name}'")

      // A built-in's catalog methods are the compiler's (`14 §5`), and member lookup would find a
      // member of the same name first — so an `impl` of some *other* trait may not quietly take
      // `5.add` over from the `Add` the type is already a member of.
      for
        ty <- self.get(selfName)
        tr <- CoreTraits.declaring(m.name)
        if CoreTraits.builtin(tr, ty)
      do
        err(s"'${m.name}' is how '$tr' is implemented for ${show(ty)}, and the compiler provides " +
          s"that — a member of this name would hide it")

      memberDecls((tname, m.name)) = m
      val fd = synthesize(tname, tparams, m)

      if generic then
        if m.receiver.isEmpty && !m.isProperty then
          err(s"associated functions on generic types are not supported yet — '$tname.${m.name}'")
        genericMembers((tname, m.name)) = fd
      else
        out += fd
        if self.nonEmpty then memberSelf(fd.name) = self
        funcInsts(fd.name) =
          (fd.params.map(p => (p.name, resolveType(p.typ, self))),
           fd.retType.map(resolveReturn(_, self)).getOrElse(Type.Unit))
  }

  /** Checks an `impl` conforms to its trait and lowers its methods to inherent members of the
   * implementing type. Conformance is nominal and exact: the type must supply every trait method
   * with a matching signature and no method the trait does not declare. The methods are then
   * hoisted exactly as a type's own members are, so a call on a value resolves through the ordinary
   * member path with no dispatch machinery of its own.
   */
  protected def hoistImpl(impl: ImplDecl, out: mutable.ListBuffer[FuncDecl]): Unit = {
    val tr                     = traitDecls.getOrElse(impl.traitName, err(s"unknown trait '${impl.traitName}'"))
    val (ty, key, taken, noun) = implTarget(impl.forType)

    // A built-in's memberships come from the compiler (`14 §5`), so an `impl` for one is not adding
    // a capability but competing with the one that is already there — and the operator would keep
    // lowering to its native instruction whatever this block said.
    if CoreTraits.builtin(impl.traitName, ty) then
      err(s"'$key' already implements '${impl.traitName}' — the compiler provides it")

    // Keyed by the type rather than by the spelling, so `impl Show for int` and `impl Show for i32`
    // are the one implementation they are.
    if traitImpls.contains((impl.traitName, key)) then
      err(s"'$key' already implements '${impl.traitName}'")
    traitImpls((impl.traitName, key)) = impl

    val self = selfBinding(ty)

    // A default the block left out is hoisted for this type exactly as a written method is, from the
    // body the trait supplied — so everything downstream (a call, a vtable slot, the escape summary)
    // finds an ordinary `Type.method` and needs to know nothing about where it came from. What is
    // recorded is where each copy came *from*, which is only ever read to keep one bad default from
    // being reported once per implementing type.
    val inherited = checkConformance(tr, impl, self)

    for m <- inherited do defaultOrigin(s"$key.${m.name}") = s"${impl.traitName}.${m.name}"

    hoistMemberList(key, Nil, taken, noun, impl.methods ::: inherited, out, self)
  }

  /** The type an `impl` is for: the key its members are filed under, the names already spoken for
   * inside a body, and what a diagnostic calls one of those.
   *
   * A trait may be implemented for **any** type, built-ins included — a language whose `Show` cannot
   * cover `int` has a `Show` no library can use. A built-in has no fields or variants for a member to
   * clash with and no type parameters, so it is the simple case; a struct or an enum brings both.
   */
  private def implTarget(written: String): (Type, String, Set[String], String) =
    nominal(written) match
      case Some((tparams, taken, noun)) =>
        if tparams.nonEmpty then
          err(s"implementing a trait for a generic type is not supported yet — '$written'")
        (concrete(written).getOrElse(Type.Unknown), written, taken, noun)
      case None =>
        // Resolved rather than taken as written, so the key is the type's one canonical name.
        val ty = scalarType(written).getOrElse(
          if written == neverName then err("'never' has no values, so nothing can be implemented for it")
          else if written == selfName then
            err("'Self' is the type an 'impl' is for, so it cannot also be the type it names")
          else err(s"unknown type '$written'"),
        )
        if ty == Type.Unit then err("'unit' has one value and no behaviour — a trait for it would say nothing")
        if ty == Type.VaList then err("a va_list is an ABI primitive, not something to implement a trait for")
        (ty, ownerKey(ty), Set.empty, "field")

  /** Verifies that `impl` supplies the members `tr` declares, each with an identical resolved
   * signature, and yields the **defaults it inherits** — the members it did not write and does not
   * have to, because the trait supplied a body for them.
   *
   * A member the trait declares without a default and the block leaves out, a member the trait does
   * not declare at all, or a mismatched kind, receiver, parameter, or result is reported against the
   * trait it fails to satisfy.
   */
  private def checkConformance(tr: TraitDecl, impl: ImplDecl, self: Map[String, Type]): List[MethodDecl] = {
    val declared  = tr.methods.map(_.name).toSet
    val inherited = mutable.ListBuffer.empty[MethodDecl]

    for tm <- tr.methods do
      impl.methods.find(_.name == tm.name) match
        case Some(im) => checkSignature(impl.forType, impl.traitName, tm, im, self)
        case None if tm.body.nonEmpty => inherited += tm
        case None =>
          err(s"'${impl.forType}' does not implement '${impl.traitName}': ${kind(tm)} '${tm.name}' is missing")

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
  private def synthesize(tname: String, tparams: List[String], m: MethodDecl): FuncDecl = {
    val selfRef = NamedType(tname, tparams.map(tp => NamedType(tp, Nil)))
    FuncDecl(s"$tname.${m.name}", tparams, receiverParam(m, selfRef).toList ::: m.params, m.retType, m.body)
  }

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
