package sh.sysl

val platform = "jvm"

/** The machine this compiler is running on, as the JVM reports it: `(processor, system)`, in
 * whatever words this platform uses for them. Turning the pair into a target is `Target.hostName`'s
 * job, and is shared so that the three platforms cannot disagree about what a machine is.
 *
 * Observed on this machine: `("aarch64", "Mac OS X")`.
 */
def hostMachine: (String, String) =
  (System.getProperty("os.arch", ""), System.getProperty("os.name", ""))

/** Where this compiler's own binary is — **`None` here, and honestly so.**
 *
 * The executable a JVM run has is `java`, sitting in whatever JDK the machine happens to use, and
 * the library is nowhere near it. Answering with the JDK's path would be worse than answering with
 * nothing: `Std.root` would search a directory that cannot hold the library and report it as one of
 * the places it looked.
 *
 * Nothing is lost by it. The JVM build is the development one — `sbt syslJVM/test`, and the driver
 * run from a checkout — where the working directory is inside the repository and the search finds
 * `lib/` there. It is the *native* build that gets installed, and that one answers exactly.
 */
def executablePath: Option[String] = None
