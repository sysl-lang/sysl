package sh.sysl

import io.github.edadma.cross_platform.*

/** A directory copied whole, and one thrown away — which is what any suite asking *where* a library
 * was read from needs before it can ask anything else.
 *
 * It lives here rather than in either of the two that use it because both ask the same question from
 * opposite ends. `StdLibraryTests` copies the library to prove that where it was found does not
 * reach its fingerprint; `LibraryBuildCliTests` copies it to make a tree that is deliberately *not*
 * the one this machine resolves, and watches what the driver says about that. Neither is the natural
 * owner of a file copy.
 */
trait TreeSupport {

  protected def copyTree(from: String, to: String): Unit =
    for entry <- listFiles(from) do
      val there = s"$to/${Project.basename(entry)}"

      if isDirectory(entry) then
        createDirectory(there)
        copyTree(entry, there)
      else copyFile(entry, there)

  protected def discardTree(path: String): Unit = {
    for entry <- listFiles(path) do
      if isDirectory(entry) then discardTree(entry) else Project.discard(entry)

    Project.discard(path)
  }
}
