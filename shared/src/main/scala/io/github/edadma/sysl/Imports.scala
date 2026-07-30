package io.github.edadma.sysl

/** What a file's — or a block's — `import` statements make reachable by a shorter name (`13 §3`).
 *
 * An import never grants access to anything: a public member is reachable fully-qualified with no
 * import at all, so every binding here is a second, shorter spelling of a path that already works.
 * That is what keeps this a lookup table consulted between the current module and the prelude,
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

  /** A scope that imports nothing: the prelude's, a compiler-synthesized declaration's, and every
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
 * **The dependency graph is not told.** `13 §6` puts the prelude outside the graph on the grounds
 * that it is the language rather than a module, and a library every file gets for free is in the
 * same position: an edge from every file to it would say nothing, and would make the library's own
 * files depend on themselves.
 *
 * **`sysl` is the real one** — the standard module every program is compiled against, which is why
 * every unqualified name in every program now goes through this. `demo` is beside it deliberately,
 * as the useless development library under `devlib/`: it is what proved the mechanism on a library
 * nothing depends on before the standard module was asked to rely on it, and it goes on holding that
 * proof rather than being retired the moment it worked. It is also what puts **two** wildcards in
 * front of every file `DevLibraryTests` compiles, which is the only thing that says a second
 * auto-import leaves the first alone.
 */
object AutoImport {

  /** The modules to bring in unqualified, if a compilation has them. */
  val modules: List[String] = "demo" :: Library.modules
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

  /** The scope of a declaration that has no file behind it — the prelude's, and anything the
   * compiler synthesized.
   */
  val root: Scope = Scope(Modules.root, Imports.empty)
}
