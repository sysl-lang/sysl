package io.github.edadma.sysl

import io.github.edadma.cross_platform.*

/** Reading a project off the filesystem: which files one invocation compiles, and what each one's
 * location says about the module it belongs to (`13 §1`).
 *
 * A module is a directory and its name is that directory's path **relative to the project root**, so
 * the root is the one thing a caller has to supply and everything else follows from where a file was
 * found. It lives apart from the driver because the driver is not the only caller: a test that
 * compiles a program written on disk asks the same question, and asking it a second way would let
 * the two disagree about what a project is.
 */
object Project {

  /** The source files one invocation compiles.
   *
   * Pointing at a **directory** makes it the root and compiles the whole tree beneath it: the files
   * directly in it are the anonymous root module, and each sub-directory is a module named by the
   * path down to it. Each file carries the segments it was found under, which is what the compiler
   * holds its `module` header to.
   *
   * Naming a single **file** compiles that file alone, as the root module with nothing else in it.
   */
  def collect(path: String): List[Source] =
    if isDirectory(path) then walk(path, Nil)
    else List(Source(path, readFile(path), Nil))

  /** One directory of the project: its own `.sysl` files, then the sub-directories under it. A
   * directory holding no source is not a module and contributes nothing; it is still walked, since
   * modules further down are reached through it.
   */
  private def walk(path: String, dir: List[String]): List[Source] = {
    val entries = listFiles(path).toList.sorted
    val here    = entries.filter(f => isFile(f) && f.endsWith(".sysl")).map(f => Source(f, readFile(f), dir))

    here ::: entries.filter(isDirectory).flatMap(sub => walk(sub, dir :+ basename(sub)))
  }

  /** The last segment of a path, whichever separator the platform wrote it with. */
  def basename(path: String): String = {
    val slash = math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'))

    if slash >= 0 then path.substring(slash + 1) else path
  }
}
