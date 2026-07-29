package io.github.edadma.sysl

import scala.scalanative.meta.LinktimeInfo

val platform = "native"

/** The machine this compiler is running on, as Scala Native reports it: `(processor, system)`, in
 * whatever words this platform uses for them. Turning the pair into a target is `Target.hostName`'s
 * job, and is shared so that the three platforms cannot disagree about what a machine is.
 *
 * A native build is compiled *for* the machine it runs on, so both halves are fixed when the
 * compiler itself is linked and are read off the triple it was built with.
 *
 * Observed on this machine: `("aarch64", "darwin")` — the processor is **not** spelled the way the
 * triple spells it (`arm64-apple-...`), and not the way Node spells it either, which is the whole
 * reason the three platforms hand over words rather than a target.
 */
def hostMachine: (String, String) = (LinktimeInfo.target.arch, LinktimeInfo.target.os)
