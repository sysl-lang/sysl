package sh.sysl

/** What a `ref` binding is, and the one thing it asks of the code around it (`reference/memory.md §
 * ref — a name for a place`).
 *
 * A ref binds a name to a **place**. At run time that is an address, taken once where the binding is
 * written; at compile time it is the place expression itself, kept so that every walk which
 * discharges a promise still has a whole place to walk. Both halves live here: `refPlaces` (in
 * `Scoping`) is the memory, and the see-through helpers below are how the ordinary predicates reach
 * it without any of them growing a case about refs.
 *
 * The rule this file enforces is the one a language with no collector cannot avoid. C# has ref
 * locals and needs nothing like it, because a tracing collector keeps the old array alive when the
 * variable is pointed at a new one; here, overwriting a `&T` or a view **releases** what it held, and
 * that release may be the last. So while a ref is live, the steps of its place that own what they
 * point at are held still.
 *
 * The discriminator is ownership rather than indirection, which is what keeps the rule from being
 * ruinous: a `*T` step owns nothing, so `p = q` frees nothing, and a program reaching its tables
 * through a `*Self` receiver — which is every allocation-free program in the guide set — has no
 * hazard at all and is asked for nothing.
 */
trait RefBindings extends AnalyzerBase {

  /** A place spelled as a string, so two places can be compared for being the same storage.
   *
   * Indices are **wildcarded**: `xs[i]` and `xs[j]` are one key, because whether the two agree is a
   * run-time question and the answer that costs nothing is the conservative one. What this is used
   * for makes that the right direction — an index that differs means the assignment lands elsewhere
   * and would have been allowed, so wildcarding refuses a little more and never a little less.
   *
   * A ref name resolves to the place it stands for, so a guard set built from one ref sees a second
   * ref that reached the same storage by name.
   */
  protected def placeKey(t: TExpr): Option[String] = t match
    case TLoad(name, _)         => Some(refPlaces.get(name).flatMap(placeKey).getOrElse(name))
    case g: TGlobal             => Some(s"@${g.symbol}")
    case TDeref(operand, _)     => placeKey(operand).map(_ + ".*")
    case TField(recv, index, _) => placeKey(recv).map(k => s"$k.$index")
    case TIndex(recv, _, _)     => placeKey(recv).map(_ + "[]")
    case _                      => None

  /** Whether overwriting a step of this type could free what it named.
   *
   * A `&T` releases the object it held; a view releases the buffer it owned, and nothing in the type
   * says whether it owns one, so every view counts. A `*T` owns nothing and is deliberately absent —
   * see the chapter, which states the exclusion and why it is what makes the feature usable.
   */
  private def owning(t: Type): Boolean = t match
    case _: Type.Ref | _: Type.View => true
    case _                          => false

  /** Every step of a place's chain **before** the place itself, outermost first. */
  private def prefixes(e: TExpr): List[TExpr] = e match
    case TField(recv, _, _) => recv :: prefixes(recv)
    case TIndex(recv, _, _) => recv :: prefixes(recv)
    case TDeref(operand, _) => operand :: prefixes(operand)
    case _                  => Nil

  /** The places a ref over `place` must hold still: the **owning steps the path goes through**.
   *
   * Two words carry the rule. *Owning*, because an assignment can only strand a ref by freeing the
   * storage the ref is standing in, and only a `&T` or a view releases anything when it is
   * overwritten — a value struct assigned in place keeps its address, so nothing moved.
   * *Through*, because the storage an assignment frees is what that step pointed **at**, so it holds
   * the ref only when the path carried on past it.
   *
   * The place itself is therefore never in the set, and that is not an exception to work around: an
   * assignment to it is what writing through the ref *is*. `ref r = h.node` names the slot, so
   * `r = other` and `h.node = other` are one statement written two ways, and neither is refused.
   */
  protected def refHazards(place: TExpr): Set[String] =
    prefixes(place).filter(step => owning(step.ty)).flatMap(placeKey).toSet

  /** Refuses an assignment that would move storage a live ref is standing on.
   *
   * The message names the ref, because the line being refused is not the mistake — it is ordinary
   * code, and what makes it a mistake is a binding somewhere above it.
   */
  protected def checkRefGuards(target: TExpr): Unit =
    for
      key   <- placeKey(target)
      guard <- refGuards.find(_.hazards(key))
    do
      err(s"'${sourceName(guard.name)}' is a 'ref' standing on storage this assignment would " +
        s"release, so the name would be left pointing at freed memory. Write the assignment before " +
        s"the 'ref', or put the 'ref' in a block that closes first")

  /** The same refusal for the other way the storage can move: a method that may write the place from
   * the inside.
   *
   * A `*self` receiver is the one place a callee is handed somewhere to write without an `&` in the
   * caller's own source (`reference/errors.md § Struct invariants`), so it is the one call that has
   * to be asked about. It is refused whether or not that particular method reassigns anything,
   * because deciding otherwise means reading the body, and a module compiles against its imports'
   * signatures and never their bodies (`15`).
   */
  protected def checkRefCall(place: TExpr): Unit =
    for
      key   <- placeKey(place)
      guard <- refGuards.find(_.hazards.exists(reaches(key, _)))
    do
      err(s"'${sourceName(guard.name)}' is a 'ref' standing on storage this call could release, " +
        s"since a '*self' method may write its receiver — so the name could be left pointing at " +
        s"freed memory. Make the call before the 'ref', or put the 'ref' in a block that closes first")

  /** Whether a method handed the receiver at `recv` could write the place at `hazard`.
   *
   * A `*self` method may write any part of its receiver, so the receiver reaches a hazard when the
   * hazard is the receiver or lies inside it — `t.grow()` reaches `t.cell`, which is the case the
   * rule exists for, and which asking for an exact match would miss entirely.
   *
   * The comparison is on whole steps rather than characters, since `t` must not be read as a prefix
   * of `t2`: a step ends where the next one's separator begins, and `placeKey` writes only those two.
   */
  private def reaches(recv: String, hazard: String): Boolean =
    hazard == recv || hazard.startsWith(s"$recv.") || hazard.startsWith(s"$recv[")

  /** The name a program wrote, recovered from the unique one codegen uses. `freshName` only ever
   * appends `.<n>`, so the source name is what precedes the first one it added.
   */
  private def sourceName(unique: String): String =
    unique.lastIndexOf('.') match
      case -1 => unique
      case i  => if unique.drop(i + 1).forall(_.isDigit) then unique.take(i) else unique
}
