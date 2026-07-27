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
