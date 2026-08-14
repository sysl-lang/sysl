package sh.sysl

/** The named environment facts a module may narrow away or declare it needs (`capabilities.md`).
 *
 * The set is a property of the **project**, not of the grammar: a capability's name arrives from
 * the parser as whatever was written, and this is what says which spellings mean something.
 *
 * The **names** are fixed here and the **answers** are not: `package.hocon` says which of them each
 * target provides (`PackageConfig`), and a name it uses that is not one of these is refused there
 * for the same reason a clause naming one is refused here. Adding a capability is therefore still an
 * edit to this file — what the config decides is what a machine offers, never what a capability is.
 */
object Capability {

  val Heap  = "heap"
  val Os    = "os"
  val Posix = "posix"

  /** The word a **module** writes to give a capability up, and the capability it gives up.
   *
   * Two of the three are the same word and the heap is not, deliberately. `package.hocon` states
   * whether a facility **exists** — `heap`, which is a noun beside `os` and `posix` — while a
   * narrowing clause is a module's promise about its own **conduct**, and a promise is about an
   * action. So a module writes `@no_alloc`: *I do not allocate, so I do not need a heap to exist.*
   * For the other two, giving the facility up and not using it are the same act, so one word does.
   *
   * A mapping rather than one shared string is what keeps each statement to exactly one spelling.
   * `@no_heap` is not a clause and `requires { alloc = true }` is not a capability, and each is
   * refused naming the other — somebody who wrote one of them meant the other.
   */
  val narrowedBy: Map[String, String] =
    Map("alloc" -> Heap, Os -> Os, Posix -> Posix)

  /** The word a narrowing is written with, given the capability — `narrowedBy` read backwards, for a
   * diagnostic that has a capability in hand and has to tell a reader what to type.
   */
  def narrowWord(cap: String): String = narrowedBy.collectFirst { case (w, c) if c == cap => w }.getOrElse(cap)

  /** Each capability, and what having it implies having. POSIX needs an operating system under it,
   * so a module that requires `posix` requires `os` whether or not it said so.
   */
  val implies: Map[String, Set[String]] =
    Map(Heap -> Set.empty, Os -> Set.empty, Posix -> Set(Os))

  /** The core set, in the order a diagnostic lists them. */
  val core: List[String] = List(Heap, Os, Posix)

  /** The capabilities that gate **which standard-library modules exist**, as against `heap`, which
   * changes what the language allows (`capabilities.md § Two kinds of capability`).
   *
   * The distinction is what decides where each is enforced. `heap` is asked of every construction
   * that makes heap storage and of every call that reaches one, because the standard module is one
   * module and half of it allocates. These are asked of the module **graph**, because what they gate
   * is a whole module: a program either may name `sysl.fs` or may not.
   */
  val environment: Set[String] = Set(Os, Posix)

  /** The ones a module may give up today, which is now all three.
   *
   * A capability is added here when something starts enforcing it, and not before: a clause that
   * compiled and enforced nothing would read in a source file as a guarantee the compiler never
   * made. `os` and `posix` earned their place when the first module requiring one was written. The
   * set stays because that is the order the next capability will arrive in — declared, then gated,
   * then narrowable.
   *
   * **A fourth, `threads`, was here and is gone.** It gated `sysl.thread` and nothing else, and what
   * that module actually needs is pthreads — so it moved to `sysl.posix.threads`, requires `posix`,
   * and the capability had nothing left to say. A capability is removed for the mirror of the reason
   * one is added: it claimed the compiler tracked whether a scheduler exists, and nothing in the
   * library was ever gated on that. A target running FreeRTOS has threads and no POSIX, and reaches
   * them through a package binding its own kernel.
   */
  val narrowable: Set[String] = Set(Heap, Os, Posix)

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
      // A handed-over library is asked the first of these and not the second, and the split is over
      // *whose mistake it could be*. Whether a clause names a capability at all, and whether the
      // files of one module agree, are facts about the **files** — a typo is a typo wherever it is
      // written, and nobody else has checked these, since a `--lib` source root arrives as text. What
      // the **machine** provides is the program author's business and not the library author's: a
      // library holding one POSIX module is not a library that cannot be used on a target without
      // POSIX, it is a library one module of which that program cannot reach.
      checkAgreement(module, files)
      if ownModule(module) then checkAgainstTarget(module, files.head)
      record(module, files.head)

    // The library's own headers, read but **not checked**. They were checked when the library was
    // built — and where this compilation is the one building it, those files are in `units` above
    // and `contributed` leaves them out — so a complaint here would point at a file the program's
    // author did not write and could not change.
    //
    // Reading them is what makes an environment capability answerable at all: `no os` is refused
    // where it reaches `sysl.fs`, and there is no such thing as reaching `sysl.fs` unless
    // `sysl.fs`'s own `requires os` has been read off the library this compilation was handed.
    for (module, files) <- std.contributed(building).groupBy(declaredModule) do
      record(module, files.head)
  }

  /** What a module's clauses leave behind, which is the whole output of this pass.
   *
   * Only the clauses that survived `checkClause` are kept, so a module that wrote something the
   * compiler refused is left with the set it would have had without it — which is what makes one
   * mistake report once rather than again at every use of what the bad clause was about.
   */
  private def record(module: String, first: Program): Unit = {
    val kept                = first.capabilities.filter(enforceable)
    val (narrows, requires) = kept.partition(_.direction == CapabilityDirection.Narrows)

    if narrows.nonEmpty then moduleNarrows(module) = spread(narrows)
    if requires.nonEmpty then moduleRequires(module) = spread(requires)
  }

  /** Refuses a module that requires something the target does not provide (`capabilities.md
   * § Two levels`).
   *
   * This is the whole point of writing `requires` rather than relying on use sites: the chapter's
   * own words are *"one clean error if built for a no-alloc target, instead of one error at every
   * `&T`"*, and this is that error. It is reported per **module** rather than per file, since the
   * clause is a property of the module and its files have already been held to agreeing.
   *
   * Only the modules this compilation contributes are asked, which is `ownModule` above. A library
   * module requiring `os` is not a mistake on a target without one — it is a module that program
   * cannot reach, which is a different diagnostic in a different pass (`GatedModules`, which reports
   * at the reference that reached it rather than at a clause in a file the reader did not write).
   *
   * The standard module has always been exempt, by never reaching this at all: `readCapabilities`
   * only `record`s what `std.contributed` hands back. A `--lib` source root and a fetched package are
   * in the same position and took a different road, which is what `ownModule` is here to close: one
   * POSIX module in a library made the whole library unusable on a target without POSIX, even for a
   * program that never named it.
   */
  private def checkAgainstTarget(module: String, first: Program): Unit =
    for
      c <- first.capabilities if c.direction == CapabilityDirection.Requires && enforceable(c)
      // The least by name, so a clause whose implications are both absent reports one thing rather
      // than a pair whose order came out of a set.
      cap <- Capability.closure(c.name).filterNot(provides).toList.sorted.headOption
    do
      recover(())(at(c.pos)(err(s"${moduleLabel(module)} requires '$cap', and '${target.name}' does " +
        s"not provide it — a target's capabilities are what '${PackageConfig.FileName}' declares, so " +
        "either the module cannot be built for this machine or the config is understating it")))

  /** How a module refers to itself in a diagnostic. The root module has no name to print, and a
   * program's own files are in it, so the common case reads as a sentence rather than as an empty
   * pair of quotes.
   */
  private def moduleLabel(module: String): String =
    if module.isEmpty then "this module" else s"'$module'"

  /** Whether a clause is one `checkClause` let through.
   *
   * The two directions are asked of different vocabularies, which is what `Capability.narrowedBy`
   * exists for: a `requires` names a **facility** and a narrowing names the **conduct** a module gives
   * up, and `alloc` and `heap` are the one pair where those are different words.
   */
  private def enforceable(c: CapabilityClause): Boolean = c.direction match
    case CapabilityDirection.Requires => Capability.implies.contains(c.name)
    case CapabilityDirection.Narrows  => Capability.narrowedBy.get(c.name).exists(Capability.narrowable)

  /** Refuses a file that says the same thing about one capability twice, and one that says both
   * things about it.
   *
   * The two are one check because they are one mistake: the module's set is what the clauses
   * describe, and a capability appearing twice means the file's author believed two clauses were
   * doing different work. Silently folding them would leave `no alloc` beside `requires alloc`
   * meaning whichever the code happened to fold last.
   */
  private def checkRepeats(u: Program): Unit =
    for (cap, cs) <- u.capabilities.filter(enforceable).groupBy(capabilityOf) if cs.length > 1 do
      recover(())(at(cs(1).pos) {
        if cs.map(_.direction).distinct.length > 1 then
          // Grouped by the **capability** and not by the word, which is the whole reason this reads
          // the way it does: `@no_alloc` beside `requires heap` is one contradiction written in the
          // two vocabularies, and grouping by what was typed would have let it through.
          err(s"'$cap' is both given up and required here, and a module cannot do without something " +
            "it cannot be built without" +
            (if cs.map(_.name).distinct.length > 1 then
               s" — '@no_${Capability.narrowWord(cap)}' and 'requires $cap' are the two directions of " +
                 "one capability"
             else ""))
        else err(s"'${cs.head.name}' is declared twice, and the second says nothing the first did not")
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
      val cap = capabilityOf(c)

      Capability.implies.collect { case (k, ims) if ims(cap) => k }.toSet + cap

  /** The capability a clause is about, whichever vocabulary it was written in.
   *
   * Everything that reasons about *what a module said* has to go through this rather than through
   * `c.name`, because the two directions spell the heap differently — `@no_alloc` against
   * `requires heap`. Only a diagnostic quoting the reader's own text uses `c.name` directly.
   */
  private def capabilityOf(c: CapabilityClause): String = c.direction match
    case CapabilityDirection.Requires => c.name
    case CapabilityDirection.Narrows  => Capability.narrowedBy.getOrElse(c.name, c.name)

  /** Refuses a clause that names nothing, or that narrows away a capability nothing yet gates.
   *
   * The second half now refuses nothing, and that is the state it was built to reach rather than
   * dead weight. `no alloc` is checked at every construction that makes heap storage; the other two
   * are checked against the module graph, which neither could be until a module requiring one
   * existed — `sysl.fs` for `os`, `sysl.posix.tty` for `posix`. The check stays
   * because the next capability to be declared will arrive before whatever gates it, and in that
   * window a clause that compiled and enforced nothing would read in a source file as a guarantee
   * the compiler never made.
   */
  private def checkClause(c: CapabilityClause): Unit = c.direction match {
    case CapabilityDirection.Requires =>
      if !Capability.implies.contains(c.name) then
        // Naming what a module *does* where a facility belongs. `@requires(alloc)` is the one that
        // happens, since the narrowing beside it is spelled that way and the pair look like a pair.
        if Capability.narrowedBy.contains(c.name) then
          err(s"'${c.name}' is what a module does, not something a machine has — a 'requires' names " +
            s"the facility, so this is '@requires(${Capability.narrowedBy(c.name)})'")
        else unknown(c)

    case CapabilityDirection.Narrows =>
      if !Capability.narrowedBy.contains(c.name) then
        // The mirror image: naming the facility where the conduct belongs. `@no_heap` is what a reader
        // writes once, having seen `heap` in the config.
        if Capability.implies.contains(c.name) then
          err(s"'@no_${c.name}' says a machine has no ${c.name}, which is the project's to say and " +
            s"not this module's — what a module promises is what it does, so this is " +
            s"'@no_${Capability.narrowWord(c.name)}'")
        else unknown(c)
      else if !Capability.narrowable(Capability.narrowedBy(c.name)) then
        err(s"'@no_${c.name}' is not enforced yet, and would say something the compiler does not " +
          s"check. The narrowings that mean something today are " +
          Capability.core.filter(Capability.narrowable).map(n => s"'@no_${Capability.narrowWord(n)}'").mkString(", "))
  }

  /** A clause naming nothing at all, said the same way in either direction. */
  private def unknown(c: CapabilityClause): Unit =
    err(s"no capability is called '${c.name}' — the set is " +
      Capability.core.map(n => s"'$n'").mkString(", "))

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
        .map(c =>
          if c.direction == CapabilityDirection.Narrows then s"'@no_${c.name}'" else s"'@requires(${c.name})'")
        .mkString(", ")
}
