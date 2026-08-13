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
   * **C belongs to a module or to the tree's own root, so a directory that is neither contributes
   * none of its own.** The walk still descends through such a directory, since a module may sit any
   * depth below one — what it does not do is take the C sitting *in* it. That is `15 §5` step 1 read
   * for the other half of the walk: a directory containing sources is a module, and `modules` below
   * already applies the same rule to the sysl. The two disagreeing is how a project came to compile
   * C nobody wrote for it — `cmake -B build` puts a build directory *inside* the project, and a
   * Zephyr build fills it with generated C meant for a different compiler.
   *
   * The cost, which is accepted rather than fixed: a vendored C library laid out in sub-directories
   * of its own loses the ones holding no sysl, and the signal is a link error naming the symbols.
   * Every binding in the org puts its C flat beside the module that declares it, so the rule is the
   * house pattern rather than a new constraint on it; a package needing the nested form makes the
   * directory a module by putting the `.sysl` that declares those `extern`s in it.
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
    if isDirectory(path) then walkModules(path, Nil, List(".c")) else Nil

  /** The modules a tree offers to something outside it: the shallowest directories under `root` that
   * hold source, as dotted paths (`13 §1`).
   *
   * **A directory holding no source is not a module**, which is the same rule `walk` applies and is
   * why this lives here rather than beside its caller. A package namespaced by reverse DNS puts its
   * source at `sh/sysl/table/`, so `sh` and `sh/sysl` hold nothing and the module it offers is
   * `sh.sysl.table` — one name, three segments. Reading the top-level *directories* instead would
   * answer `sh` for that package and for every other one namespaced the same way, which is not a
   * name any of them declares and is the same name for all of them.
   *
   * **Shallowest and not every module**, because what a binding has to cover is a name and everything
   * under it: a consumer writing `sh.sysl.table.sub` is answered by the entry for `sh.sysl.table`,
   * and an entry of its own would say the same thing twice. It also keeps the guarantee the collision
   * check rests on — no path here is inside another, so at most one can answer any written path.
   *
   * A file sitting directly in `root` belongs to the anonymous root module, which has no name to
   * import and so is not offered.
   */
  def modules(root: String): Set[String] = {
    def under(path: String, dir: List[String]): List[String] = {
      val entries = try listFiles(path).toList.sorted catch case _: Exception => Nil

      if dir.nonEmpty && entries.exists(f => isFile(f) && sysl.exists(f.endsWith)) then List(dir.mkString("."))
      else
        entries.filter(isDirectory).filterNot(d => basename(d).startsWith("."))
          .flatMap(d => under(d, dir :+ basename(d)))
    }

    under(root, Nil).toSet
  }

  /** One directory of the project: its own files of the wanted kinds, then the sub-directories under
   * it. A directory holding no source is not a module and contributes nothing; it is still walked,
   * since modules further down are reached through it.
   */
  private def walk(path: String, dir: List[String], exts: List[String]): List[Source] = {
    val entries = listFiles(path).toList.sorted
    val here    = entries.filter(f => isFile(f) && exts.exists(f.endsWith)).map(f => Source(f, readFile(f), dir))

    here ::: entries.filter(isDirectory).flatMap(sub => walk(sub, dir :+ basename(sub), exts))
  }

  /** `walk`, taking a directory's own files only where that directory is a **module** — where it
   * holds a sysl file of its own — or is the tree's own root. Sub-directories are still descended
   * into, because a module may sit any depth below a directory that holds nothing.
   *
   * This is what `cSources` wants and `collect` does not: the sysl walk finds the modules, so a
   * directory it takes nothing from has by definition contributed nothing, while the C walk would
   * otherwise take files out of a directory the project never claimed.
   *
   * **The root is exempt because the root is the tree rather than a directory in it**, which is what
   * `LibraryBuildCliTests` and `PackageBuildTests` mean by *"as well as beside a module"*: a package
   * namespaced by reverse DNS has no sysl at its root, and the C belonging to no single module goes
   * there. `dir` is empty at exactly one place and that is the place.
   */
  private def walkModules(path: String, dir: List[String], exts: List[String]): List[Source] = {
    val entries = listFiles(path).toList.sorted
    val files   = entries.filter(isFile)
    val mine    = dir.isEmpty || files.exists(f => sysl.exists(f.endsWith))
    val here    = if mine then files.filter(f => exts.exists(f.endsWith)).map(f => Source(f, readFile(f), dir))
                  else Nil

    here ::: entries.filter(isDirectory).flatMap(sub => walkModules(sub, dir :+ basename(sub), exts))
  }

  /** The last segment of a path, whichever separator the platform wrote it with. */
  def basename(path: String): String = {
    val slash = math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'))

    if slash >= 0 then path.substring(slash + 1) else path
  }

  /** What a project rooted at this path is *called*, which is not the same question as what the
   * caller typed to reach it.
   *
   * `.`, `..`, a trailing separator and a path that doubles back through `..` all name a directory
   * whose name is somewhere other than the end of the string, so the segments are resolved against
   * the working directory and the last one that survives is the answer. `basename` cannot do this
   * and should not try: it answers about the *text*, which is what its other callers want.
   *
   * A path that resolves to the root has no last segment and so has no name; `a.out` is the same
   * fallback the driver has always used for a name it could not work out.
   */
  def nameOf(path: String): String = {
    val absolute = if path.startsWith("/") || path.startsWith("\\") then path
                   else s"${getCurrentDirectory}/$path"

    val resolved = absolute.split("[/\\\\]").foldLeft(List.empty[String]) {
      case (segments, "" | ".") => segments
      case (segments, "..")     => segments.dropRight(1)
      case (segments, segment)  => segments :+ segment
    }

    resolved.lastOption.getOrElse("a.out")
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
