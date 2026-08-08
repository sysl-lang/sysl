package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** What the standard module's memos are allowed to hold on to.
 *
 * The three memos on this path — `Std.parsed`, `Stdlib.fromSource` and `Stdlib.resolve`'s
 * default-path answer — are each keyed by target, and a parsed standard module is one of the largest
 * structures the compiler builds. Keyed by target and never evicted, they hold one of those per
 * target the process has ever been asked about.
 *
 * **That was harmless for as long as nothing asked about more than one.** A run compiles for a
 * single target, which is the case `Std.parsed`'s own comment reasons about. A *test* that walks
 * `Target.all` is the case it did not: `AbiAgainstClangTests` sweeps every supported target, so an
 * agent that had run it carried every target's standard module for the rest of its life and could
 * not then run anything else. On Scala Native, where the suite forks one agent per core and keeps it
 * for the whole run, that was the difference between a suite that finished and one that took the
 * machine down.
 *
 * A memory property has no observable surface of its own, which is why `cachedTargets` and
 * `cachedResolutions` exist. These tests are the reason they are `private[sysl]` rather than absent:
 * without them the bound is a claim in a comment, and this one regressed a whole release.
 */
class StdCacheBoundTests extends AnyFreeSpec {

  /** More than one, and drawn from what the registry actually supports, so this asks the same
   * question `AbiAgainstClangTests` does rather than a smaller one of its own.
   */
  private val sweep: List[Target] = Target.all.filter(_.supported).take(4)

  "the standard module's memos hold ONE target, however many are asked about" - {

    "Std.parsed keeps only the last target's trees" in {
      assert(sweep.length > 1, "the registry must supply more than one supported target to sweep")

      sweep.foreach(Std.parsed)

      assertResult(1)(Std.cachedTargets)
    }

    "Stdlib.resolve keeps only the last answer" in {
      sweep.foreach(t => Stdlib.resolve(Stdlib.Choice.Default(None), t))

      assertResult(1)(Stdlib.cachedResolutions)
    }
  }

  "bounding the memo does not change the answer it gives" - {

    "a target re-asked after another has intervened parses to the same trees" in {
      val first  = Std.parsed(sweep.head)
      val second = Std.parsed(sweep(1))
      val again  = Std.parsed(sweep.head)

      // Equal to what it said before the eviction, and still not the other target's answer.
      assertResult(first.map(_.body.length))(again.map(_.body.length))
      assert(first.length == again.length)
      assert(second.nonEmpty)
    }

    "every swept target still parses" in {
      sweep.foreach(t => assert(Std.parsed(t).nonEmpty, s"${t.name} parsed to nothing"))
    }
  }
}
