package io.github.edadma.sysl

/** The named environment facts a module may narrow away or declare it needs (`capabilities.md`).
 *
 * The set is a property of the **project**, not of the grammar: a capability's name arrives from
 * the parser as whatever was written, and this is what says which spellings mean something. It is
 * fixed here rather than read from configuration because the configuration half — per-target
 * capability sets in `sysl.conf` — is not written yet, and the four below are what
 * `capabilities.md` calls the core set.
 */
object Capability {

  val Alloc   = "alloc"
  val Os      = "os"
  val Posix   = "posix"
  val Threads = "threads"

  /** Each capability, and what having it implies having. POSIX needs an operating system under it,
   * so a module that requires `posix` requires `os` whether or not it said so.
   */
  val implies: Map[String, Set[String]] =
    Map(Alloc -> Set.empty, Os -> Set.empty, Posix -> Set(Os), Threads -> Set.empty)

  /** The core set, in the order a diagnostic lists them. */
  val core: List[String] = List(Alloc, Os, Posix, Threads)

  /** `cap` together with everything it implies. */
  def closure(cap: String): Set[String] = implies.getOrElse(cap, Set.empty) + cap
}

/** Reading the capability clauses of a program's files into the module property they describe
 * (`13 §4`, `capabilities.md`).
 *
 * A clause is written per **file** and means something about the **module**, which is the whole of
 * why there is a pass here rather than a field: the files of one module have to be held to saying
 * the same thing, and the module's own set is what every later question is asked of. The redundancy
 * is deliberate — you can never open a file of a `no alloc` module and fail to see that it is one.
 *
 * What is enforced *by* the resulting set lives elsewhere: `AnalyzerBase.needsAlloc` is what every
 * construction that makes heap storage asks.
 */
trait Capabilities extends AnalyzerBase {

  protected def units: List[Program]

  /** Reads every file's clauses, refuses the ones that are not clauses about anything, holds the
   * files of a module to agreeing, and leaves the module's two sets behind.
   *
   * It runs before any name is resolved, for the reason the module names themselves are known that
   * early: a header is settled by the text of the file alone, and everything downstream — including
   * the first `&T` the walk reaches — needs the answer already.
   */
  protected def readCapabilities(): Unit = {
    for u <- units do
      for c <- u.capabilities do recover(())(at(c.pos)(checkClause(c)))
      checkRepeats(u)

    for (module, files) <- units.groupBy(declaredModule) do
      checkAgreement(module, files)

      // Only the clauses that survived are kept, so a module that wrote something the compiler
      // refused is left with the set it would have had without it — which is what makes one mistake
      // report once rather than again at every use of what the bad clause was about.
      val kept                = files.head.capabilities.filter(enforceable)
      val (narrows, requires) = kept.partition(_.direction == CapabilityDirection.Narrows)

      if narrows.nonEmpty then moduleNarrows(module) = spread(narrows)
      if requires.nonEmpty then moduleRequires(module) = spread(requires)
  }

  /** Whether a clause is one `checkClause` let through. */
  private def enforceable(c: CapabilityClause): Boolean =
    Capability.implies.contains(c.name) &&
      (c.direction == CapabilityDirection.Requires || c.name == Capability.Alloc)

  /** Refuses a file that says the same thing about one capability twice, and one that says both
   * things about it.
   *
   * The two are one check because they are one mistake: the module's set is what the clauses
   * describe, and a capability appearing twice means the file's author believed two clauses were
   * doing different work. Silently folding them would leave `no alloc` beside `requires alloc`
   * meaning whichever the code happened to fold last.
   */
  private def checkRepeats(u: Program): Unit =
    for (name, cs) <- u.capabilities.filter(enforceable).groupBy(_.name) if cs.length > 1 do
      recover(())(at(cs(1).pos) {
        if cs.map(_.direction).distinct.length > 1 then
          err(s"'$name' is both given up and required here, and a module cannot do without something " +
            "it cannot be built without")
        else err(s"'$name' is declared twice, and the second says nothing the first did not")
      })

  /** The module a file contributes to — its header, or the anonymous root module (`13 §1`). */
  private def declaredModule(u: Program): String = u.module.map(_.show).getOrElse(Modules.root)

  /** A set of clauses as the capabilities they cover, with each one's implications folded in: a
   * module requiring `posix` requires `os`, and one that gave `os` up gave `posix` up with it.
   *
   * The position kept for an implied capability is the clause that implied it, so a diagnostic
   * about `os` in a module that only ever wrote `posix` still points at a line the reader wrote.
   */
  private def spread(cs: List[CapabilityClause]): Map[String, Option[Pos]] =
    cs.flatMap(c => implied(c).map(_ -> c.pos)).toMap

  /** A clause's capability and the ones that come with it, in whichever direction it points.
   *
   * The implication runs opposite ways for the two: **requiring** `posix` requires what `posix`
   * needs, and **giving up** `os` gives up everything that needs `os`. Folding it in here is what
   * keeps the rest of the compiler from having to know that `posix` and `os` are related at all.
   */
  private def implied(c: CapabilityClause): Set[String] = c.direction match
    case CapabilityDirection.Requires => Capability.closure(c.name)
    case CapabilityDirection.Narrows =>
      Capability.implies.collect { case (k, ims) if ims(c.name) => k }.toSet + c.name

  /** Refuses a clause that names nothing, or that narrows away a capability nothing yet gates.
   *
   * The second is the honest half. `no alloc` is checked at every construction that makes heap
   * storage, so it means what it says; the other three gate **which standard-library modules
   * exist**, and the modules they would gate are not written. A clause that compiled and enforced
   * nothing would be worse than one that is refused, because it would read in a source file as a
   * guarantee the compiler never made.
   */
  private def checkClause(c: CapabilityClause): Unit = {
    if !Capability.implies.contains(c.name) then
      err(s"no capability is called '${c.name}' — the set is ${Capability.core.map(n => s"'$n'").mkString(", ")}")

    if c.direction == CapabilityDirection.Narrows && c.name != Capability.Alloc then
      err(s"'no ${c.name}' is not enforced yet, and would say something the compiler does not check: " +
        s"'${c.name}' gates which standard-library modules exist, and those modules are not written. " +
        "'no alloc' is the narrowing that means something today")
  }

  /** Holds the files of one module to declaring the same clauses (`13 §4`).
   *
   * The comparison is of the **set** of clauses rather than of the text: order between two clauses
   * says nothing, and a file that wrote the same one twice is a separate mistake reported on its
   * own line. Each disagreeing file is named against the first of them by source order, so a module
   * whose files were edited apart reports once per file that drifted rather than once per pair.
   */
  private def checkAgreement(module: String, files: List[Program]): Unit = {
    def written(u: Program): Set[(CapabilityDirection, String)] =
      u.capabilities.map(c => (c.direction, c.name)).toSet

    val first = files.head

    for u <- files.tail if written(u) != written(first) do
      recover(())(at(u.capabilities.headOption.flatMap(_.pos).orElse(u.module.flatMap(_.pos))) {
        err(s"${u.source.name} and ${first.source.name} are both files of " +
          s"${if module.isEmpty then "the same module" else s"'$module'"}, and they declare different " +
          "capabilities — a capability is a property of the module, so every one of its files states " +
          s"it: ${spell(u)} against ${spell(first)}")
      })
  }

  /** A file's clauses as a reader wrote them, for a message comparing two files. */
  private def spell(u: Program): String =
    if u.capabilities.isEmpty then "none"
    else
      u.capabilities
        .map(c => if c.direction == CapabilityDirection.Narrows then s"'no ${c.name}'" else s"'requires ${c.name}'")
        .mkString(", ")
}
