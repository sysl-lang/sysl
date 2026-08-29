package sh.sysl

import scala.collection.mutable

/** An error raised by the analyzer: an unknown name, a type mismatch, a wrong arity — any
 * rule that the structural parse cannot catch. `pos` is where in the source it was found, which
 * is absent only for a rule that fires away from any one node.
 *
 * Raising one abandons the *region* it was raised in — a statement, a function body, a
 * declaration — which is caught at the nearest recovery point so the analyzer can go on to find
 * the mistakes further down the file.
 */
case class AnalyzerError(message: String, pos: Option[Pos], unresolved: Boolean = false)
    extends RuntimeException(message)

/** Raised where a value derives from something that was already reported.
 *
 * It abandons the enclosing region exactly as an error does, but records nothing: the mistake
 * was reported where it was made, and saying so again at every later use of the name would bury
 * the real diagnostic under its own consequences.
 */
case class Poisoned() extends RuntimeException

/** How the analyzer says something is wrong, and how it carries on afterwards.
 *
 * Three things live here and they are one concern: where the analyzer currently **is**, so a
 * complaint can name a line; the **raising** of one, which abandons the region it was raised in
 * rather than handing back a value nothing could be built from; and the **recovery points**, which
 * catch that abandonment, record what it said, and yield something the walk can go on with — since
 * a compilation that stopped at the first mistake would report one of them per run.
 *
 * It is the layer under everything else in the analyzer. It names no table and asks nothing about
 * types, which is what lets every other trait reach it.
 */
trait Reporting {

  /** The machine this compilation is **for** (`getting-started/cli.md § targets`), which reaches
   * the analyzer and not only codegen because two of the language's types are answers about it:
   * `usize` and `isize` are pointer-width by definition, so what they resolve to is the target's
   * business.
   *
   * It sits at the root of the analyzer's trait chain for the same reason the diagnostics do — every
   * layer needs it, and a layer handed it separately is a layer that could be handed a different one.
   */
  protected def target: Target

  /** How wide an address is, given to every `Type.llvm` and `Type.usize` below this point. */
  protected given Word = target.word

  /** What this machine's types cost. */
  protected given layout: Layout = Layout(target)

  /** Where the analyzer currently is. Every recursive entry point (a statement, an expression, a
   * type reference, a declaration) sets this to the node it is about to work on and restores it
   * afterwards, so an error raised *after* the children are done still points at the parent that
   * raised it rather than at whatever was visited last.
   */
  protected var currentPos: Option[Pos] = None

  /** Runs `body` with diagnostics pointing at `p`, restoring the previous position after. A node
   * with no position of its own leaves the enclosing one in place, which is what keeps a
   * synthesized node's errors pointing somewhere useful.
   */
  protected def at[T](p: Option[Pos])(body: => T): T =
    if p.isEmpty then body
    else {
      val saved = currentPos

      currentPos = p
      try body
      finally currentPos = saved
    }

  /** Where the **call** is, while a parameter's default is being filled in at one
   * (`reference/declarations.md § Default parameters and named arguments`).
   *
   * A default is analyzed in the declaration's terms and evaluated at the call, and those are two
   * different places — which nothing had to tell apart until a built-in could report where it was
   * written (`ReservedNames`). Diagnostics still point at the default itself, because a default that
   * does not typecheck is wrong where it was written; `__FILE__` and `__LINE__` read this instead,
   * because a default "stands exactly where the argument would have been written" and that is the
   * caller's line.
   *
   * Absent everywhere else, which is what makes a built-in in an ordinary body report its own line.
   */
  protected var callSite: Option[Pos] = None

  /** Runs `body` with `p` as the call a default is being filled at.
   *
   * **The outermost call wins.** A default that itself calls something and leaves *that* argument
   * out is a second filling, whose call site is inside the first default — a position in the
   * declaration's file, which is precisely the answer this exists to avoid giving. So a filling
   * already under way is left alone, and every built-in in the whole nest reports the one place a
   * reader actually wrote a call.
   */
  protected def atCallSite[T](p: Option[Pos])(body: => T): T =
    if p.isEmpty || callSite.nonEmpty then body
    else {
      val saved = callSite

      callSite = p
      try body
      finally callSite = saved
    }

  /** Where a built-in that reports a source location should say it is: the call a default is being
   * filled at, or failing that the node itself.
   */
  protected def reportedPos: Option[Pos] = callSite.orElse(currentPos)

  protected def err(msg: String): Nothing = throw AnalyzerError(msg, currentPos)

  /** Reports a name — of a value or of a type — that names nothing at all.
   *
   * It is an ordinary `AnalyzerError` in every respect but one: the abstract pass keeps it. That
   * pass drops a body's complaints because each is found again at every instantiation, and the trade
   * is a good one for anything whose answer depends on what the parameters turn out to be. **A name
   * that names nothing does not depend on them.** It is wrong at every instantiation and wrong at
   * none, so a declaration nothing instantiates is the one place the mistake could hide — and it
   * did: `f[T](y: T) = nosuchthing` compiled while the same line one word away from being generic
   * did not.
   *
   * Raised by throwing rather than recorded outright, unlike `boundErr`, because several callers
   * resolve a name speculatively and catch the failure to mean *absent* — a member lookup falling
   * back to the next candidate, an `impl` deciding a subject is not one it covers. Those still catch
   * it and still say nothing; what changes is only what `recover` does with one that reaches it.
   */
  protected def unresolvedErr(msg: String): Nothing =
    throw AnalyzerError(msg, currentPos, unresolved = true)

  /** Abandons the current region without reporting, because whatever led here already did. */
  protected def poisoned(): Nothing = throw Poisoned()

  /** Whether the analyzer is running the definition-time pass of `reference/generics.md § Bounds` —
   * a generic body walked once with its type parameters standing in for themselves.
   *
   * The pass exists to report what a body does that its bounds do not license, and those
   * diagnostics go through `boundErr` — a missing bound on a method call or on an operator alike.
   * Every *other* complaint the walk raises is dropped while it is set, because the abstract pass is
   * additive: a mistake in the concrete part of a generic body is found where it always was, at each
   * instantiation, and reporting it from here as well would report it against a body no call site
   * may ever ask for.
   *
   * Every use a bound could license now reports through `boundErr`, rendering included: `Display`
   * and its `Writer` sink are built, so a `print` of a parameter names the bound that would allow
   * it rather than being dropped for want of one to name.
   */
  protected var abstractPass: Boolean = false

  /** Reports something a type parameter's bounds do not license, and abandons the region.
   *
   * It records the diagnostic itself rather than raising an `AnalyzerError`, because the abstract
   * pass drops those: this is the one kind of complaint that pass is for, and it survives whatever
   * recovery region it was raised inside.
   *
   * Rendering a parameter goes through here too, now that `Display` exists to license it: a body
   * that prints a `T` is told to write `T: Display` rather than having the complaint dropped for
   * want of a bound to name.
   */
  protected def boundErr(msg: String): Nothing = {
    found += Diagnostic(msg, currentPos)
    poisoned()
  }

  /** Runs `body` with whatever it complains about recorded, rather than dropped as the abstract
   * pass otherwise drops a complaint.
   *
   * It is for the checks that only *exist* in that pass — the arity and argument types of a call on
   * a type parameter, checked against the trait's signature. Nothing else will ever check them: an
   * instantiation resolves the same call against a concrete implementation instead, so a mistake
   * here is caught at the definition or nowhere.
   */
  protected def reported[T](body: => T): T =
    try body
    catch
      case AnalyzerError(msg, pos, _) =>
        found += Diagnostic(msg, pos)
        poisoned()

  /** `recover`, for a region whose complaint must survive the abstract pass rather than be dropped
   * by it.
   *
   * It is for the checks that are about a **declaration** rather than about a body: what a bound
   * names, and what the trait it names asks of the arguments it was applied to. Those are wrong
   * wherever they are read, so reading them during a walk that discards its tree is no reason to
   * stay quiet about them — and nothing else will ever read them if no call site turns up.
   */
  protected def recorded[T](fallback: => T)(body: => T): T =
    try body
    catch
      case AnalyzerError(msg, pos, _) =>
        found += Diagnostic(msg, pos)
        fallback
      case Poisoned() => fallback

  // --- collecting errors ----------------------------------------------------------------

  /** Every error found so far, in the order the analyzer found them. Duplicates are dropped on
   * the way in: the same complaint at the same place is one mistake however many times a pass
   * arrives at it — a generic function instantiated three times has one bad line, not three.
   */
  private val found = mutable.LinkedHashSet.empty[Diagnostic]

  /** How many distinct mistakes have been found, which is what tells a walk that reported something
   * from one that came through clean.
   */
  protected def diagnosticCount: Int = found.size

  /** Warnings, kept apart from errors because they answer a different question — *this compiles and
   * is probably not what you meant* — and because mixing them into `found` would make every walk
   * that asks "did I report anything?" answer yes for a program that is fine.
   *
   * They are **not** taken back by `restoreComplaints`, and that is deliberate: a speculative walk
   * undoes what it *said about a mistake*, because the reading was thrown away. A warning here is
   * raised by a whole-program check over declarations, not from inside a speculative reading, so
   * there is nothing for a rewind to be about.
   */
  private val warned = mutable.LinkedHashSet.empty[Diagnostic]

  /** Says something about a declaration without refusing it. */
  protected def warn(msg: String, pos: Option[Pos]): Unit =
    warned += Diagnostic(msg, pos, Severity.Warning)

  /** The warnings, in source order, as the driver prints them. */
  protected def warnings: List[Diagnostic] = Diagnostic.inSourceOrder(warned.toList)

  /** What has been complained about so far, and putting it back — which is what a **speculative**
   * walk needs and `sandboxed` does not supply.
   *
   * A complaint is not always raised: `recorded`, `reported` and `boundErr` put one straight into
   * the set, and the region carries on with a fallback. So a walk that is allowed to fail cannot
   * tell whether it failed by catching alone — it has to ask whether anything was said — and a walk
   * whose answer is thrown away has to take back what it said, or the reader is told about a
   * reading nobody kept.
   */
  protected def complaints: List[Diagnostic] = found.toList

  protected def restoreComplaints(saved: List[Diagnostic]): Unit = {
    found.clear()
    found ++= saved
  }

  /** The errors, ordered by where they are, so reading them top to bottom is reading the file top
   * to bottom. A diagnostic with no position sorts last, since there is nowhere to file it.
   *
   * **They are not rendered here**, which is the difference between this and what it used to be: a
   * caller wanting them as text asks `Diagnostic.report`, and one wanting them as data — an editor,
   * `api.Sysl.check` — has them without a paragraph to take apart.
   */
  protected def diagnostics: List[Diagnostic] = Diagnostic.inSourceOrder(found.toList)

  /** Runs `body`, and if it abandons its region, records the error and yields `fallback` so the
   * walk carries on to whatever comes after. A `Poisoned` region yields the same fallback and
   * records nothing.
   */
  protected def recover[T](fallback: => T)(body: => T): T =
    try body
    catch
      // A name that names nothing is kept even here, because the reason the rest are dropped does
      // not reach it: it is wrong whatever the parameters turn out to be, so no instantiation will
      // find it again — and a declaration nothing instantiates would otherwise never be told.
      case AnalyzerError(msg, pos, unresolved) =>
        if !abstractPass || unresolved then found += Diagnostic(msg, pos)
        fallback
      case Poisoned() => fallback

  /** The same, for a region that has no useful value to stand in for a failure — a function
   * whose body did not analyze is simply left out of the program.
   */
  protected def recoverOpt[T](body: => T): Option[T] = recover(None)(Some(body))
}
