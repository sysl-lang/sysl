package io.github.edadma.sysl

val platform = "jvm"

/** The machine this compiler is running on, as the JVM reports it: `(processor, system)`, in
 * whatever words this platform uses for them. Turning the pair into a target is `Target.hostName`'s
 * job, and is shared so that the three platforms cannot disagree about what a machine is.
 *
 * Observed on this machine: `("aarch64", "Mac OS X")`.
 */
def hostMachine: (String, String) =
  (System.getProperty("os.arch", ""), System.getProperty("os.name", ""))
