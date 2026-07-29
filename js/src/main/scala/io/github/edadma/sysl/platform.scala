package io.github.edadma.sysl

import scala.scalajs.js

val platform = "js"

/** The machine this compiler is running on, as Node reports it: `(processor, system)`, in whatever
 * words this platform uses for them. Turning the pair into a target is `Target.hostName`'s job, and
 * is shared so that the three platforms cannot disagree about what a machine is.
 *
 * A build with no `process` at all — a browser — has no machine to report, so it answers a pair of
 * empty strings and every invocation there has to name its target.
 *
 * Observed under Node 24 on this machine: `("arm64", "darwin")`.
 */
def hostMachine: (String, String) = {
  val process = js.Dynamic.global.selectDynamic("process")

  if js.isUndefined(process) then ("", "")
  else (process.arch.asInstanceOf[String], process.platform.asInstanceOf[String])
}
