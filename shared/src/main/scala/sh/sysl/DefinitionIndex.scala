package sh.sysl

/** One name, written somewhere, standing for something declared somewhere else.
 *
 * `at` is where the name is written and `declaredAt` is where the thing it names was declared —
 * which may be in another file, and for anything the standard library supplies usually is. `name`
 * is what the declaration calls itself, which is not always how the reference spelled it: a
 * qualified name, an aliased import and a generic's instantiation all reach a declaration under a
 * name of their own.
 */
final case class Reference(at: Pos, declaredAt: Pos, name: String)

/** A typed tree together with the references it makes — what `Analyzer.indexed` answers, and the
 * only thing in the compiler that carries anything an editor wants beside anything a build does.
 *
 * The two are handed back together because the index is read off the tree and off tables the walk
 * filled, so asking for it later would mean keeping the analyzer alive to ask.
 */
final case class Indexed(tree: TProgram, references: List[Reference])

/** Where the things a program names were declared — go-to-definition, as data.
 *
 * **Most of it is derived rather than recorded, and that is what makes it cheap.** The analyzer
 * already resolves every name; what it leaves behind is a typed tree in which each node *names what
 * it resolved to* — a function by its key, module storage by its symbol — and
 * `ExprAnalysis.analyzeValue` stamps every one of those nodes with the position of the source
 * expression it came from. Both halves of those references are therefore already in the tree, and
 * this is the walk that puts them together.
 *
 * **A local is the exception, and the reason is worth knowing before trying to simplify this.** What
 * a typed node carries for one is its **unique** name, and a unique name is unique *within a
 * function* only — `resetFunction` clears the set, so every function with a parameter `n` has a
 * binding called `n`. A table keyed on that collides across the whole program and answers with
 * whichever function was compiled last, which is exactly what a first attempt at this did. So a
 * local's reference is recorded where both halves are in hand at once and unambiguous, which is
 * resolution itself: `Scoping.lookupOpt`, the one place that decides *which* binding a name means.
 *
 * The one other thing recorded is `FunctionBodies.instantiateFunc`'s note of what each instantiation
 * of a generic was made from, since its mangled name cannot be read back.
 *
 * **What is covered**: locals and parameters, module `val`s and `var`s, `extern` variables, and
 * calls, including calls to generics. **A type name, a struct field and a trait member are not**,
 * and the reason is the same for all three: they are resolved into the *shape* of a typed node
 * rather than into a name it carries, so there is nothing for this walk to look up and no single
 * seam like `lookupOpt` to record at. Reaching them means recording at each of their resolution
 * sites, which is a different piece of work from this one.
 */
trait DefinitionIndex extends Scoping {

  /** Every reference the program makes to something declared, in the order the walk reaches them
   * and with duplicates removed.
   *
   * A generic's body is walked once per instantiation and once more to check the generic itself, so
   * a name written once inside one arrives here several times over — identically, since the typed
   * nodes carry the same source position each time. Distinctness is therefore about the *walk*
   * rather than about the program: two different names at one position would both be kept, and
   * there is no such thing.
   */
  protected def referencesIn(tree: TProgram): List[Reference] =
    (references.toList ::: Locate.walk(tree).flatMap(reference)).distinct

  /** What one typed node refers to, where it refers to something declared. */
  private def reference(node: Positioned): Option[Reference] = node.pos.flatMap { at =>
    node match
      case TGlobal(symbol, _, _) => global(symbol).map((n, p) => Reference(at, p, n))
      case TCall(name, _, _, _)  => func(name).map((n, p) => Reference(at, p, n))
      case TFuncAddr(name, _, _) => func(name).map((n, p) => Reference(at, p, n))
      case _                     => None
  }

  /** The declaration a function key names, following an instantiation back to the generic it was
   * made from. An instantiation is not a declaration anybody wrote, so its name is no use to a
   * reader — what they want opened is the `[T]` the call chose an argument for.
   *
   * A declaration's position is its **anchor**, which for one carrying an annotation is the `@test`
   * or `@export` above it rather than the name. That is where a diagnostic about the declaration
   * already points, and it is the right line to open; it is worth knowing that it is not always the
   * line the name is written on.
   */
  private def func(key: String): Option[(String, Pos)] =
    funcDecls.get(key)
      .orElse(funcOrigin.get(key).flatMap(funcDecls.get))
      .flatMap(d => d.pos.map((qn(d.name), _)))

  /** The declaration behind a module-storage symbol.
   *
   * A `val` and a module `var` are found by key, which is what `TGlobal` carries for them. An
   * `extern` variable carries the **symbol the linker resolves** instead, which is a different
   * string and is deliberately not the key — so it is searched for rather than looked up. The
   * search is over the externs one program declares, which is a handful.
   */
  private def global(symbol: String): Option[(String, Pos)] =
    valDecls.get(symbol).flatMap(d => d.pos.map((qn(d.name), _)))
      .orElse(staticVarDecls.get(symbol).flatMap(d => d.pos.map((qn(d.name), _))))
      .orElse(externVarDecls.values.find(_.symbol == symbol).flatMap(d => d.pos.map((qn(d.name), _))))
}
