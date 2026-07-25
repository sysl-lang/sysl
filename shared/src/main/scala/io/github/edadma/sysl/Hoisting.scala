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
      if t.tparams.nonEmpty then err(s"generic traits are not supported yet — '${t.name}'")
      traitDecls(t.name) = t
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
      scalarType(name).isDefined || name == neverName

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

    hoistMemberList(tname, tparams, taken, noun, members, out)
  }

  /** What hoisting a member needs to know about the type it is hoisting into: the type parameters
   * a member's signature may mention, the names already spoken for inside the body, and what a
   * diagnostic calls one of those names. A struct's are its fields; an enum's are its variants.
   *
   * It answers for either kind, which is what lets member hoisting and `impl` conformance be
   * written once — nothing below here knows or cares which it was handed.
   */
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
  ): Unit = {
    val generic = tparams.nonEmpty

    for m <- members do
      currentPos = m.pos.orElse(currentPos)
      if m.tparams.nonEmpty then err(s"generic methods are not supported yet — '$tname.${m.name}'")
      if memberDecls.contains((tname, m.name)) then err(s"type '$tname' already has a member named '${m.name}'")
      if taken.contains(m.name) then
        err(s"type '$tname' has both a $noun and a member named '${m.name}'")

      memberDecls((tname, m.name)) = m
      val fd = synthesize(tname, tparams, m)

      if generic then
        if m.receiver.isEmpty && !m.isProperty then
          err(s"associated functions on generic types are not supported yet — '$tname.${m.name}'")
        genericMembers((tname, m.name)) = fd
      else
        out += fd
        funcInsts(fd.name) =
          (fd.params.map(p => (p.name, resolveType(p.typ, Map.empty))),
           fd.retType.map(resolveReturn(_, Map.empty)).getOrElse(Type.Unit))
  }

  /** Checks an `impl` conforms to its trait and lowers its methods to inherent members of the
   * implementing type. Conformance is nominal and exact: the type must supply every trait method
   * with a matching signature and no method the trait does not declare. The methods are then
   * hoisted exactly as a type's own members are, so a call on a value resolves through the ordinary
   * member path with no dispatch machinery of its own.
   */
  protected def hoistImpl(impl: ImplDecl, out: mutable.ListBuffer[FuncDecl]): Unit = {
    val tr           = traitDecls.getOrElse(impl.traitName, err(s"unknown trait '${impl.traitName}'"))
    val (key, taken, noun) = implTarget(impl.forType)

    // Keyed by the type rather than by the spelling, so `impl Show for int` and `impl Show for i32`
    // are the one implementation they are.
    if traitImpls.contains((impl.traitName, key)) then
      err(s"'$key' already implements '${impl.traitName}'")
    traitImpls((impl.traitName, key)) = impl

    checkConformance(tr, impl)
    hoistMemberList(key, Nil, taken, noun, impl.methods, out)
  }

  /** The type an `impl` is for: the key its members are filed under, the names already spoken for
   * inside a body, and what a diagnostic calls one of those.
   *
   * A trait may be implemented for **any** type, built-ins included — a language whose `Show` cannot
   * cover `int` has a `Show` no library can use. A built-in has no fields or variants for a member to
   * clash with and no type parameters, so it is the simple case; a struct or an enum brings both.
   */
  private def implTarget(written: String): (String, Set[String], String) =
    nominal(written) match
      case Some((tparams, taken, noun)) =>
        if tparams.nonEmpty then
          err(s"implementing a trait for a generic type is not supported yet — '$written'")
        (written, taken, noun)
      case None =>
        // Resolved rather than taken as written, so the key is the type's one canonical name.
        val ty = scalarType(written).getOrElse(
          if written == neverName then err("'never' has no values, so nothing can be implemented for it")
          else err(s"unknown type '$written'"),
        )
        if ty == Type.Unit then err("'unit' has one value and no behaviour — a trait for it would say nothing")
        if ty == Type.VaList then err("a va_list is an ABI primitive, not something to implement a trait for")
        (ownerKey(ty), Set.empty, "field")

  /** Verifies that `impl` supplies exactly the methods `tr` declares, each with an identical
   * resolved signature. A missing method, an extra one, or a mismatched receiver, parameter, or
   * result is reported against the trait it fails to satisfy.
   */
  private def checkConformance(tr: TraitDecl, impl: ImplDecl): Unit = {
    val declared = tr.methods.map(_.name).toSet

    for tm <- tr.methods do
      impl.methods.find(_.name == tm.name) match
        case None     => err(s"'${impl.forType}' does not implement '${impl.traitName}': method '${tm.name}' is missing")
        case Some(im) => checkSignature(impl.forType, impl.traitName, tm, im)

    for im <- impl.methods do
      if !declared.contains(im.name) then
        err(s"trait '${impl.traitName}' declares no method '${im.name}', so this 'impl' cannot define it")
  }

  /** Compares one implementing method against the trait's signature: same receiver mode, same
   * parameter types in order, and the same result. Types are resolved with the implementing type
   * substituted for `self`, so `self` on both sides means the same concrete type.
   */
  private def checkSignature(forType: String, traitName: String, tm: MethodDecl, im: MethodDecl): Unit = {
    if tm.receiver != im.receiver then
      err(s"method '${im.name}' of 'impl $traitName for $forType' takes a different receiver than the trait declares")
    if tm.params.length != im.params.length then
      err(s"method '${im.name}' of 'impl $traitName for $forType' takes ${im.params.length} " +
        s"parameters, but the trait declares ${tm.params.length}")

    for (tp, ip) <- tm.params.zip(im.params) do
      val want = resolveType(tp.typ, Map.empty)
      val got  = resolveType(ip.typ, Map.empty)
      if want != got then
        err(s"parameter '${ip.name}' of method '${im.name}' is ${show(got)}, but trait '$traitName' declares ${show(want)}")

    val want = tm.retType.map(resolveReturn(_, Map.empty)).getOrElse(Type.Unit)
    val got  = im.retType.map(resolveReturn(_, Map.empty)).getOrElse(Type.Unit)
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
    val selfParam = m.receiver match
      case Some(RecvMode.ByValue)     => Some(Param("self", selfRef))
      case Some(RecvMode.ByPtr)       => Some(Param("self", PtrType(selfRef)))
      case Some(RecvMode.ByRef(sync)) => Some(Param("self", RefType(selfRef, sync)))
      case None if m.isProperty       => Some(Param("self", selfRef))
      case None                       => None
    FuncDecl(s"$tname.${m.name}", tparams, selfParam.toList ::: m.params, m.retType, m.body)
  }
}
