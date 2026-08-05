package sh.sysl

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
    if isDirectory(path) then walk(path, Nil, sysl)
    else List(Source(path, readFile(path), Nil))

  /** The two suffixes a program may be written under: the ordinary one, and the literate one whose
   * program is the indented part of a Markdown document (`Literate`). A directory may hold both, and
   * which a file is decides nothing beyond how its text is read — the module it belongs to is where
   * it sits, as it is for every other file.
   */
  private val sysl: List[String] = List(".sysl", Literate.Extension)

  /** The C files of a source tree, which a `.sysl` file reaches by `extern` and the build compiles
   * alongside it (`15 §7`).
   *
   * The same walk as `collect` and deliberately so: a C file belongs to the directory it was found
   * in exactly as a sysl file does, which is what lets its object be named after a path that is
   * unique across the tree.
   *
   * It is a *separate* call rather than a second list out of one walk because the two answers are
   * wanted at different moments: the sysl decides whether there is anything to compile at all, and
   * the C is not looked at until a compilation that got that far is about to link. Which trees are
   * asked is `NativeSources`' — every tree the compilation walked, not only a library's.
   *
   * Naming a single file is not offered, and gets `Nil` rather than an error. Naming a file compiles
   * that file alone (`13 §1`), so there is no tree for C to have travelled with — and a lone C file
   * is not a program.
   */
  def cSources(path: String): List[Source] =
    if isDirectory(path) then walk(path, Nil, List(".c")) else Nil

  /** One directory of the project: its own files of the wanted kinds, then the sub-directories under
   * it. A directory holding no source is not a module and contributes nothing; it is still walked,
   * since modules further down are reached through it.
   */
  private def walk(path: String, dir: List[String], exts: List[String]): List[Source] = {
    val entries = listFiles(path).toList.sorted
    val here    = entries.filter(f => isFile(f) && exts.exists(f.endsWith)).map(f => Source(f, readFile(f), dir))

    here ::: entries.filter(isDirectory).flatMap(sub => walk(sub, dir :+ basename(sub), exts))
  }

  /** The last segment of a path, whichever separator the platform wrote it with. */
  def basename(path: String): String = {
    val slash = math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'))

    if slash >= 0 then path.substring(slash + 1) else path
  }

  /** The directory an output path sits in, where it names one — the default standard-module path
   * does, and it is a directory a fresh clone has never had, so writing the artifact has to make it.
   */
  def parentOf(path: String): Option[String] = {
    val slash = math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'))

    Option.when(slash > 0)(path.substring(0, slash))
  }

  /** Removes a temporary file, whether or not it is there.
   *
   * Cleanup runs on the paths that failed, and those are exactly the paths where the file may never
   * have been created: `createTempFile` reserves a name, and it is the toolchain that writes to it. A
   * `deleteFile` on a link that failed therefore threw, and the stack trace replaced the linker's own
   * message — the one thing the user needed to see.
   */
  def discard(path: String): Unit =
    try deleteFile(path)
    catch case _: Exception => ()
}
