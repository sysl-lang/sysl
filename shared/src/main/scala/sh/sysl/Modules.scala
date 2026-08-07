package sh.sysl

/** How a module's name and the name of a declaration in it make the one key every table is
 * keyed by (`13 §1`, `13 §3`).
 *
 * A declaration belongs to a module, and two modules may each declare a `read`, so the name a
 * table holds has to say which module's it is. The alternative — a module dimension on every
 * table — would have touched every lookup in the analyzer to say something that only ever
 * travels with the name, so the name carries it instead.
 *
 * **The separator is not a dot**, and that is the whole of the design here. A dot already
 * separates a type from its member (`Point.dist`) and a generic function from the arguments it
 * was instantiated at (`f.int`), so a dotted module prefix would let `geom.Point.dist` mean
 * either a member of `geom`'s `Point` or a function `dist` of a module `geom.Point`. Two
 * declarations answering to one key is a miscompile rather than a diagnostic, so the two
 * separators are kept apart: a module name holds no `$`, and neither does an identifier, so
 * splitting at the first one always recovers the module a key belongs to.
 *
 * `$` is also a character an LLVM symbol may contain unquoted, which is what lets a key be the
 * emitted name as it always was — nothing between the analyzer and codegen has to mangle.
 */
object Modules {

  /** The character between a module's name and the name of a declaration in it. */
  val sep: Char = '$'

  /** The name of the **anonymous root module**, whose name is the empty path (`13 §1`). A
   * declaration in it is keyed by its own name alone, which is why a program of one headerless
   * file is keyed exactly as it was before modules existed.
   */
  val root: String = ""

  /** The key a declaration named `name` in `module` is filed under.
   *
   * **The name is escaped here**, which is where a backtick-quoted identifier (`09`) stops being a
   * problem for everything downstream: a key is still the emitted symbol, as the note above says,
   * so a name carrying a space had to become LLVM-safe somewhere, and this is the one place every
   * declaration passes through. `LlvmName.escape` is the identity on every name the ordinary
   * identifier grammar can produce, so no key that existed before this moves.
   *
   * **`split` still recovers the module**, although the escape's marker is the separator itself.
   * The escape can only introduce a `$` *after* the separator is written, and a module path holds
   * no quoted segment — refused where a module path is read, precisely so that the first `$` in a
   * key remains the one this function put there.
   */
  def qualify(module: String, name: String): String =
    if module.isEmpty then LlvmName.escape(name) else s"$module$sep${LlvmName.escape(name)}"

  /** The module a key belongs to, and everything after it — which for a member or an
   * instantiation is itself a dotted name (`Point.dist`, `f.int`) and is left as one.
   */
  def split(key: String): (String, String) = {
    val i = key.indexOf(sep.toInt)

    if i < 0 then (root, key) else (key.take(i), key.drop(i + 1))
  }

  /** The module a key belongs to. */
  def moduleOf(key: String): String = split(key)._1

  /** The declared name in a key, with the module it belongs to taken off. */
  def bare(key: String): String = split(key)._2

  /** A key as a diagnostic spells it: the separator read back as the dot a programmer writes.
   *
   * Two keys can share a spelling — `geom`'s `Point.dist` and `geom.Point`'s `dist` both read as
   * `geom.Point.dist` — which is a cost paid in the one place it is affordable. A message is read
   * by someone looking at the line it points at; a table is not.
   */
  def show(key: String): String = key.replace(sep, '.')
}
