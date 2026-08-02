package io.github.edadma.sysl

/** How far a declaration may be named from, and — the part this file exists for — how far a **field**
 * or a **member** of a type may be (`08 § Visibility`, `13 §2`).
 *
 * The machinery is `AnalyzerBase`'s `declAccess`, which is keyed by a string and holds an entry only
 * for a declaration that restricted itself. A field and a member join it under `owner.name`, which
 * is a key nothing else can produce — a top-level name has no dot in it — and everything downstream
 * then asks `visible` about them exactly as it asks about a function.
 *
 * The one rule that is not a re-use of the top-level one: **an unmarked member sits at its type's
 * reach, not at public.** So the default is inherited rather than absent, a modifier may only narrow,
 * and one that names a wider region is refused rather than clamped — a reader of that line would
 * otherwise be told something about the member that is not true.
 */
trait MemberVisibility extends AnalyzerBase {

  /** The key a field or a member of `owner` is filed under. A declaration's own name never holds a
   * dot, so no top-level key can collide with one of these — and `Modules.split` already reads
   * everything after the separator as a possibly-dotted member name, so `geom$Point.dist` splits the
   * way the rest of the compiler expects.
   */
  protected def memberAccessKey(owner: String, name: String): String = s"$owner.$name"

  /** Records how far a declaration is visible, for the modifier it was written with (`13 §2`).
   *
   * A public declaration records nothing: the unmarked default is the common case, and a table that
   * held an entry for it would be a table with an entry per declaration in every program. What is
   * stored is the answer rather than the modifier — the file, and the module a `private[M]` resolved
   * to — because the question is asked at every use and the modifier alone cannot answer it.
   *
   * A `private[M]` naming no enclosing module is reported and then left public, so the mistake is
   * one diagnostic at the declaration rather than one at every use of it.
   */
  protected def recordAccess(key: String, vis: Visibility): Unit = vis match
    case Visibility.Public    => ()
    case Visibility.File      => declAccess(key) = Access(currentFile, None)
    case Visibility.Scoped(m) => recover(())(declAccess(key) = Access(currentFile, Some(enclosing(m))))

  /** Which module a `private[M]` names: the **innermost** enclosing one whose last segment is `M`,
   * counting the declaring module itself (`13 §2`).
   *
   * Reading outward from the declaration is what disambiguates a repeated segment — `private[geom]`
   * inside `geom.mesh.geom.tri` is the nearer `geom` — and taking the answer from where the
   * declaration sits is what keeps moving a subtree elsewhere from changing what its own
   * annotations mean. There is deliberately no way to name an unrelated module, so a visibility
   * scope is always a contiguous subtree containing the declaration.
   */
  private def enclosing(m: String): String = {
    val parts = if currentModule.isEmpty then Nil else currentModule.split('.').toList

    parts.lastIndexOf(m) match
      case -1 if parts.isEmpty =>
        err(s"this file is at the project root, whose module has no name, so there is no '$m' " +
          "to widen to — 'private' on its own is this file")
      case -1 =>
        err(s"'$m' is not '$currentModule' or one of its ancestors, and 'private[M]' widens to a " +
          "module the declaration is already inside")
      case i => parts.take(i + 1).mkString(".")
  }

  /** Records how far a **field or a member** of `owner` is visible.
   *
   * Silence is not "public" here: an unmarked member is as visible as the type it belongs to, so the
   * owner's own answer is copied onto it and a member of a restricted type is restricted without
   * having said anything. A written modifier must land inside that region, since a member reachable
   * where its type is not is a member nothing can name anyway — the claim would be false rather than
   * merely useless, so it is reported.
   */
  protected def recordMemberAccess(owner: String, name: String, vis: Visibility, label: String): Unit = {
    val key   = memberAccessKey(owner, name)
    val outer = declAccess.get(owner)

    vis match
      case Visibility.Public => for a <- outer do declAccess(key) = a
      case Visibility.File   => declAccess(key) = Access(currentFile, None)
      case Visibility.Scoped(m) =>
        recover(()) {
          val sub = enclosing(m)

          outer match
            case Some(Access(_, Some(o))) if !(sub == o || sub.startsWith(s"$o.")) =>
              tooWide(label, s"visible throughout module '$sub'", owner)
              declAccess(key) = outer.get
            case Some(a @ Access(Some(_), None)) =>
              tooWide(label, s"visible throughout module '$sub'", owner)
              declAccess(key) = a
            case _ => declAccess(key) = Access(currentFile, Some(sub))
        }
  }

  private def tooWide(label: String, claim: String, owner: String): Unit =
    err(s"'$label' is $claim, but '${qn(owner)}' is ${restriction(owner)} — a member cannot be more " +
      "visible than the type it belongs to")

  /** Reports a member of `owner` that may not be named here (`08 § Visibility`). */
  protected def checkMemberVisible(owner: String, name: String, what: String): Unit = {
    val key = memberAccessKey(owner, name)

    if !visible(key) then err(s"$what '$name' of '${qn(owner)}' is ${restriction(key)}")
  }

  /** The same, calling the member what it is — which is what a reader needs to know which line of
   * the type's body the restriction was written on.
   */
  protected def checkMemberVisible(owner: String, name: String, m: MethodDecl): Unit =
    checkMemberVisible(owner, name,
      if m.isProperty then "property"
      else if m.receiver.isDefined then "method"
      else "associated function")

  /** The same for a field, which is read by selecting it and written by assigning to the selection —
   * one modifier over both, since a field nobody outside may read is not one they may write.
   */
  protected def checkFieldVisible(owner: String, name: String): Unit = {
    // Selecting a field is reading an offset, so an `opaque` type's fields are out of reach from
    // outside however each one is marked (`15 §9`). Asked here rather than at the three call sites
    // for the reason the visibility question is: reading and writing a field are one question, and
    // every way of naming one arrives through this.
    checkLayoutKnown(owner, qn(owner))
    checkMemberVisible(owner, name, "field")
  }

  /** The **positional** forms, which name every field of a struct in order rather than one of them:
   * the constructor `Point(1, 2)` and the pattern `Point(a, b)`. Each needs every field visible, and
   * the constructor is the one that matters — a restricted field a caller could still set by
   * position would restrict nothing worth restricting.
   */
  protected def checkEveryFieldVisible(owner: String, fields: List[String], form: String, advice: String): Unit =
    for f <- fields.find(f => !visible(memberAccessKey(owner, f))) do
      err(s"$form names every field of '${qn(owner)}' in order, and '$f' is " +
        s"${restriction(memberAccessKey(owner, f))} — $advice")
}
