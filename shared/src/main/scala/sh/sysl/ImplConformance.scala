package sh.sysl

import scala.collection.mutable

/** Whether an `impl` block supplies what the trait it names asked for, and what to build from what
 * it did supply.
 *
 * Conformance is **nominal and exact**: the type must supply every method the trait declares without
 * a default, with a matching kind, receiver, parameter list and result, and no method the trait does
 * not declare at all. Nothing is inferred from a shape that happens to line up, which is what makes
 * a useful diagnostic possible — a mismatch names the trait it fails to satisfy and the member it
 * fails at, rather than reporting that no implementation was found.
 *
 * The defaults are the other half of the same walk. A member the trait supplied a body for is one
 * the block did not have to write, so the pass that finds what is missing also yields what is
 * inherited, and each inherited one is synthesised into an ordinary function under the implementing
 * type's own name. A property is no different: its declaration form already carries a body, so the
 * only question was whether the receiver it never spells becomes a `self` parameter. It does.
 */
trait ImplConformance extends MemberLowering {

  /** Verifies that `impl` supplies the members `tr` declares, each with an identical resolved
   * signature, and yields the **defaults it inherits** — the members it did not write and does not
   * have to, because the trait supplied a body for them.
   *
   * A member the trait declares without a default and the block leaves out, a member the trait does
   * not declare at all, or a mismatched kind, receiver, parameter, or result is reported against the
   * trait it fails to satisfy.
   */
  protected def checkConformance(
      tr: TraitDecl,
      impl: ImplDecl,
      home: MemberHome,
      sig: Map[String, Type],
  ): List[MethodDecl] = {
    val declared  = tr.methods.map(_.name).toSet
    val inherited = mutable.ListBuffer.empty[MethodDecl]
    val shown     = traitShown(impl)

    for tm <- tr.methods do
      impl.methods.find(_.name == tm.name) match
        case Some(im) => checkSignature(home.label, shown, tm, im, sig, scopeFor(tr.name))
        case None if tm.body.nonEmpty => inherited += tm
        case None =>
          err(s"'${home.label}' does not implement '$shown': ${kind(tm)} '${tm.name}' is missing")

    for im <- impl.methods do
      if !declared.contains(im.name) then
        err(s"trait '$shown' declares no ${kind(im)} '${im.name}', so this 'impl' cannot define it")

    inherited.toList
  }

  /** What a diagnostic calls one member of a trait. The three kinds are told apart by shape rather
   * than by a keyword, so a message that names the wrong one sends the reader looking for the wrong
   * mistake.
   */
  protected def kind(m: MethodDecl): String =
    if m.isProperty then "property" else if m.receiver.isEmpty then "associated function" else "method"

  /** Compares one implementing method against the trait's signature: same receiver mode, same
   * parameter types in order, and the same result.
   *
   * Both sides are resolved with `Self` bound to the implementing type, which is what makes a
   * signature written with `Self` and one written with the concrete name the same signature
   * (`14 §1`) — the comparison is between *resolved* types, so it does not matter which spelling
   * either side chose.
   */
  /** The trait an `impl` is for, as a message spells it — which for a call trait is the arrow the
   * block was written with, not the arity-carrying declaration behind it (`12 §6`).
   */
  protected def traitShown(impl: ImplDecl): String =
    if Type.Fn.isCall(impl.traitName) && impl.traitArgs.nonEmpty then
      s"Fn(${impl.traitArgs.init.map(_.show).mkString(", ")}) -> ${impl.traitArgs.last.show}"
    else qn(impl.traitName)

  protected def checkSignature(
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
    // A `...` is part of what a caller may write, so an implementation that has one where the trait
    // has none — or the other way about — is a different promise, not a wider one.
    if tm.variadic != im.variadic then
      err(s"method '${im.name}' of 'impl $traitName for $forType' " +
        s"${if im.variadic then "takes" else "does not take"} a '...', but trait '$traitName' " +
        s"declares ${if tm.variadic then "one" else "none"}")

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
  protected def synthesize(home: MemberHome, m: MethodDecl): FuncDecl = {
    val name = s"${home.symbol}.${m.name}${home.alt}"

    FuncDecl(
      name,
      home.tparams ::: m.tparams,
      receiverParam(m, selfRefFor(name, home)).toList ::: m.params,
      m.retType,
      m.body,
      home.bounds ++ m.bounds,
      m.variadic,
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
  protected def selfRefFor(name: String, home: MemberHome): TypeRef =
    if defaultOrigin.contains(name) then NamedType(selfName) else home.selfRef

  /** The `self` parameter a member's receiver becomes, at the self type it is a member of. A
   * property's receiver is an implicit by-value read; an associated function has none.
   */
  protected def receiverParam(m: MethodDecl, selfRef: TypeRef): Option[Param] = m.recvMode.map {
    case RecvMode.ByValue     => Param("self", selfRef)
    case RecvMode.ByPtr       => Param("self", PtrType(selfRef))
    case RecvMode.ByRef(sync) => Param("self", RefType(selfRef, sync))
  }
}
