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
case class AnalyzerError(message: String, pos: Option[Pos]) extends RuntimeException(message)

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

  protected def err(msg: String): Nothing = throw AnalyzerError(msg, currentPos)

  /** Abandons the current region without reporting, because whatever led here already did. */
  protected def poisoned(): Nothing = throw Poisoned()

  /** Whether the analyzer is running the definition-time pass of `14 §4` — a generic body walked
   * once with its type parameters standing in for themselves.
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
    found += ((msg, currentPos))
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
      case AnalyzerError(msg, pos) =>
        found += ((msg, pos))
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
      case AnalyzerError(msg, pos) =>
        found += ((msg, pos))
        fallback
      case Poisoned() => fallback

  // --- collecting errors ----------------------------------------------------------------

  /** Every error found so far, in the order the analyzer found them. Duplicates are dropped on
   * the way in: the same complaint at the same place is one mistake however many times a pass
   * arrives at it — a generic function instantiated three times has one bad line, not three.
   */
  private val found = mutable.LinkedHashSet.empty[(String, Option[Pos])]

  /** How many distinct mistakes have been found, which is what tells a walk that reported something
   * from one that came through clean.
   */
  protected def diagnosticCount: Int = found.size

  /** The errors, rendered and ordered by where they are, so reading them top to bottom is
   * reading the file top to bottom. A diagnostic with no position sorts last, since there is
   * nowhere to file it.
   */
  protected def diagnostics: List[String] =
    found.toList
      .sortBy { case (_, pos) =>
        (pos.isEmpty, pos.map(_.source.name).getOrElse(""), pos.map(_.line).getOrElse(0), pos.map(_.col).getOrElse(0))
      }
      .map { case (msg, pos) => Diagnostic.render(msg, pos) }

  /** Runs `body`, and if it abandons its region, records the error and yields `fallback` so the
   * walk carries on to whatever comes after. A `Poisoned` region yields the same fallback and
   * records nothing.
   */
  protected def recover[T](fallback: => T)(body: => T): T =
    try body
    catch
      case AnalyzerError(msg, pos) =>
        if !abstractPass then found += ((msg, pos))
        fallback
      case Poisoned() => fallback

  /** The same, for a region that has no useful value to stand in for a failure — a function
   * whose body did not analyze is simply left out of the program.
   */
  protected def recoverOpt[T](body: => T): Option[T] = recover(None)(Some(body))
}
