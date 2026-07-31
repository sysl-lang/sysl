package io.github.edadma.sysl

/** What a file's — or a block's — `import` statements make reachable by a shorter name (`13 §3`).
 *
 * An import never grants access to anything: a public member is reachable fully-qualified with no
 * import at all, so every binding here is a second, shorter spelling of a path that already works.
 * That is what keeps this a lookup table consulted between the current module and the library,
 * rather than a visibility rule woven through resolution.
 *
 * The three maps are the three things an import can bind, and they are kept apart because they are
 * consulted at different points. `names` answers an unqualified use, `modules` answers the leading
 * segment of a dotted one, and `wildcards` is asked only when neither of the first two has an
 * entry — which is what makes an explicitly imported name win over a wildcard that also offers it.
 */
case class Imports(
    names: Map[String, String] = Map.empty,
    modules: Map[String, String] = Map.empty,
    wildcards: List[String] = Nil,
) {

  /** Whether this binds nothing at all — the ordinary case, and worth asking before any of the
   * lookups below do work.
   */
  def isEmpty: Boolean = names.isEmpty && modules.isEmpty && wildcards.isEmpty

  /** Whether `name` is already bound here, by either of the two explicit forms. A wildcard is not
   * asked: it offers a name rather than binding one, and `13 §3` gives it to the more specific
   * import.
   */
  def binds(name: String): Boolean = names.contains(name) || modules.contains(name)

  /** The module `name` was imported as, if it was imported as one. */
  def moduleAs(name: String): Option[String] = modules.get(name)
}

object Imports {

  /** A scope that imports nothing: the library's, a compiler-synthesized declaration's, and every
   * file's until it writes an `import`.
   */
  val empty: Imports = Imports()
}

/** The modules whose public names every file may write **unqualified without importing them**.
 *
 * An auto-imported module is exactly a **wildcard import every file starts with**, and that is the
 * whole mechanism — `13 §3`'s rules then say everything else, with nothing written twice:
 *
 *   - **a file's own declaration wins**, because `resolveName` asks the current module before it
 *     asks the imports at all, so a program declaring its own `Pair` shadows the library's;
 *   - **an explicit import wins**, because `wildcards` is consulted only where `names` and
 *     `modules` have no entry;
 *   - **two auto-imports offering one name is ambiguous**, reported by the same message a pair of
 *     written wildcards gets;
 *   - and the **fully-qualified path always works**, since an import never granted the access.
 *
 * A module is auto-imported only where it is actually present, so a program compiled without the
 * library is unaffected rather than carrying a wildcard over a module that does not exist.
 *
 * **The dependency graph is not told.** `13 §6` puts the standard module outside the graph on the grounds
 * that it is the language rather than a module, and a library every file gets for free is in the
 * same position: an edge from every file to it would say nothing, and would make the library's own
 * files depend on themselves.
 *
 * **`sysl` is the one a shipped compiler auto-imports** — the standard module every program is
 * compiled against, which is why every unqualified name in every program goes through this. Nothing
 * else is in the list, and that is a correctness point rather than tidiness: a name here is claimed
 * for every file of every program, so a development library left in would silently wildcard-import
 * any user library that happened to declare the same module name.
 */
object AutoImport {

  /** The modules to bring in unqualified, if a compilation has them.
   *
   * The library's own auto-imported list rather than everything it contributes: a submodule of
   * `sysl` is reached by naming it, so its names arriving unasked-for would put the library's whole
   * surface back into every file — which is the thing splitting it up is for.
   */
  def modules: List[String] = Library.autoImported ::: extra

  private var extra: List[String] = Nil

  /** Runs `body` with `module` auto-imported as well — **for tests only**.
   *
   * `devlib/demo` is what proved this mechanism on a library nothing depends on, before the standard
   * module was asked to rely on it, and it goes on holding that proof. Reaching it needs a second
   * auto-imported module, which is also the only thing that says two of them leave each other alone
   * — but a development library has no business in a shipped compiler's list, so it is scoped to the
   * test that wants it instead of living in the constant.
   *
   * **This is process-global, and suites run in parallel.** A module named here is therefore visible
   * to whatever else is compiling at the time. That is inert for almost everything, because
   * `ProgramWalk.autoImported` keeps only modules the compilation actually has — a name for a module
   * that is not there contributes nothing. It is *not* inert for another suite that declares a module
   * of the same name, which must write its own references qualified rather than depend on whether
   * this happened to be set. Threading the list through the compilation would remove the hazard, and
   * is the right fix if a second caller ever wants this.
   */
  def including[T](module: String)(body: => T): T = {
    val saved = extra

    extra = extra :+ module
    try body
    finally extra = saved
  }
}

/** The terms a name is read in: the module it is written in, what that file (or block) has
 * imported, and which file it is. All three travel together everywhere a declaration's signature,
 * fields, or body is resolved, because each is a property of *where it was written* rather than of
 * where the walk arrived from (`13 §3`).
 *
 * The file is what a bare `private` is measured against (`13 §2`), and it is the `Source` itself
 * rather than its name because sources compare by identity — two files of one project may be
 * called the same thing, and a name they share must not make one visible to the other.
 */
case class Scope(module: String, imports: Imports, file: Option[Source] = None)

object Scope {

  /** The scope of a declaration that has no file behind it — the library's, and anything the
   * compiler synthesized.
   */
  val root: Scope = Scope(Modules.root, Imports.empty)
}
