package sh.sysl

import scala.scalanative.meta.LinktimeInfo
import scala.scalanative.posix.stdlib.realpath
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

val platform = "native"

/** How long a path may be. `PATH_MAX` is 1024 on macOS and 4096 on Linux, and `realpath` writes up
 * to that many bytes into whatever it is given — so the larger of the two is the only safe size for
 * a buffer compiled once and run on either.
 */
private inline val PathMax = 4096

/** macOS answers this and nothing else does: there is no `/proc`, and the path a process was
 * launched from is something only the loader knows.
 *
 * Guarded by `LinktimeInfo.isMac`, which is resolved when the compiler is *linked* rather than when
 * it runs — so on a Linux build this declaration is unreachable and the symbol is never asked of the
 * linker. A runtime `if` would not do: the symbol would be undefined and the link would fail.
 */
@extern
private object dyld {
  def _NSGetExecutablePath(buf: CString, size: Ptr[CUnsignedInt]): CInt = extern
}

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

/** Where this compiler's own binary is, with every symlink on the way resolved — which is how an
 * installed sysl finds the library it was installed beside (`Std.root`).
 *
 * **Resolving matters, and it is the whole reason this is not `argv[0]`.** Homebrew installs a
 * binary into `/opt/homebrew/Cellar/sysl/<version>/bin` and links `/opt/homebrew/bin/sysl` at it, so
 * the path a user's shell found is a symlink in a directory that holds no library. Following it
 * lands on the real prefix, which is where the files actually are.
 *
 * The native build is the one that matters here — it is the binary that ships — and it is the only
 * one of the three that can answer exactly. macOS asks the loader through `_NSGetExecutablePath`,
 * which Apple's own documentation says to pass through `realpath`; Linux reads the symlink the
 * kernel maintains at `/proc/self/exe`, which `realpath` resolves in one step. Anything else answers
 * `None` and the search falls through to the working directory, which is what a development tree
 * wants anyway.
 */
def executablePath: Option[String] = {
  val resolved = stackalloc[Byte](PathMax)

  val raw: CString =
    if LinktimeInfo.isMac then {
      val buf  = stackalloc[Byte](PathMax)
      val size = stackalloc[CUnsignedInt]()

      !size = PathMax.toUInt

      // A nonzero answer means the buffer was too small, and `size` comes back holding what would
      // have been needed. `PATH_MAX` is that size, so there is nothing to retry with.
      if dyld._NSGetExecutablePath(buf, size) == 0 then buf else null
    } else if LinktimeInfo.isLinux then c"/proc/self/exe"
    else null

  if raw == null || realpath(raw, resolved) == null then None else Some(fromCString(resolved))
}
