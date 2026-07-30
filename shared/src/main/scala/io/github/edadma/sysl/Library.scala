package io.github.edadma.sysl

/** The library every compilation carries, and the one place that says where it comes from.
 *
 * Two questions run through the analyzer and both are about the same thing. **Is this declaration
 * the library's rather than the program's** — which decides whether it is in scope everywhere with
 * no import, whether it may be left unanalyzed until something reaches it, and whose `impl` rows
 * those are (`13 §8`, `02 § Coherence`). And **which key is the library's `Option` filed under** —
 * asked wherever the compiler names a library declaration itself rather than reading a name out of
 * source: `?` needs `Option`'s variants, `print` needs the renderers, a format string needs
 * `FormatSpec`.
 *
 * Both were answered in place, by asking `Prelude` directly and by spelling library keys as bare
 * names. That worked because the prelude is one `Source` keyed under the anonymous root module, so a
 * library key *is* its own spelling. It stops working the moment a declaration moves into a module
 * of its own, where `Option` is filed under `sysl$Option` and nothing spelled `Option` is found —
 * and it would stop working at every one of those sites at once, which is the failure this exists to
 * prevent. So they ask here instead, and moving a declaration is a change to this file.
 *
 * The two answers are still the prelude's, exactly as they were. That is deliberate: a seam is worth
 * having before it is worth using, and one introduced in the same change that alters what it answers
 * is a seam nothing was ever measured against.
 *
 * **`CoreTraits` speaks spellings, and its consumers translate.** What that table holds is which
 * operator each trait's method *is*, which is a fact about the source language rather than about
 * where the trait lives — so it stays spelled the way a program writes it, and a consumer holding a
 * resolved key goes through `spelling` before asking it. That is the one place the two vocabularies
 * meet, and they used to meet everywhere by coinciding.
 */
object Library {

  /** The module the library's declarations belong to.
   *
   * The **anonymous root module** while the prelude is what supplies them: its declarations are
   * keyed by their own names alone, which is what makes every library key its own spelling today.
   */
  def module: String = Modules.root

  /** The declarations every compilation carries, whatever the program said. */
  def decls: List[Stmt] = Prelude.decls

  /** Whether a declaration is the library's rather than the program's.
   *
   * Asked of the **position** and not of the key, because the two disagree exactly where it matters:
   * the library's declarations are keyed under the root module, and so are a headerless program's,
   * so a program that declares an `Ok` of its own would be told its own type was the library's.
   */
  def owns(decl: Positioned): Boolean = Prelude.declares(decl)

  /** The key the library's declaration named `name` is filed under — what the compiler names one
   * by, wherever it names one rather than reading a name out of source.
   */
  def key(name: String): String = Modules.qualify(module, name)

  /** The library's own spelling of a key, or `None` where the key is not the library's.
   *
   * `key`'s inverse, for the direction a resolved bound arrives in: an `impl`'s trait name and a
   * `Type.Bound`'s are keys by the time either is looked at, and the tables the compiler holds about
   * its own traits are spelled the way a program writes them.
   */
  def spelling(key: String): Option[String] =
    Option.when(Modules.moduleOf(key) == module)(Modules.bare(key))

  /** The enum `?` unwraps, paired with its success and failure variant names (`09 §4`).
   *
   * The base is a key and the variants are spellings, which is not an inconsistency: a variant is
   * named within its enum's own layout — `Type.Enum.variant` finds it by what was written — while the
   * enum itself is a declaration a table holds.
   */
  def tryVariants(base: String): Option[(String, String)] =
    if base == key("Result") then Some(("Ok", "Err"))
    else if base == key("Option") then Some(("Some", "None"))
    else None

  /** Every spelling the library declares, at the top level: types, functions, constants, and an
   * enum's variants, which a program reaches unqualified as names in their own right.
   *
   * This is what the compiler-known names are held to — a name spelled here that the library does
   * not declare is a call that resolves to nothing, and the only thing standing between the two is
   * that both happen to be written in this repository.
   */
  lazy val declared: Set[String] = decls.flatMap {
    case d: ConstDecl  => List(d.name)
    case d: ValDecl    => List(d.name)
    case d: FuncDecl   => List(d.name)
    case d: ExternDecl => List(d.name)
    case d: StructDecl => List(d.name)
    case d: TypeDecl   => List(d.name)
    case d: TraitDecl  => List(d.name)
    case d: EnumDecl   => d.name :: d.variants.map(_.name)
    case _             => Nil
  }.toSet

  /** The names the compiler spells for itself, gathered so that each can be held to being declared.
   *
   * A library declaration reached from source is checked by the ordinary name resolution, and one
   * reached from *inside the compiler* is checked by nothing at all: `instantiateStruct` on a name
   * the library stopped declaring is an exception rather than a diagnostic. These are also the
   * checklist a move works through, which is the other reason to have them in one place.
   *
   * `CoreTraits`' own renderer and mixer names are not here: they are chosen per type rather than
   * written down, so they are gathered by asking it, which is what `LibraryTests` does.
   */
  lazy val known: Set[String] =
    Set(
      // `?`, `for ... in`, and a weak reference's `get` all build one (`09 §4`, `03`).
      "Option", "Some", "None",
      "Result", "Ok", "Err",
      // What a format string's flags are carried in (`14 §8`).
      "FormatSpec",
      // The traits whose members a built-in has by rule rather than by an `impl` (`14 §5`).
      "Display", "Hash",
      // The sink every rendering goes through, and the one function its standard-output table ends
      // at — whose *table* codegen lays out by hand, which is why the shape is checked rather than
      // read (`SpecialForms.checkWriterShape`, `WriterEmitter`).
      "Writer", "putbytes",
      // `print` renders a scalar directly rather than through its `Display` (`14 §8 b`).
      "printi", "printu", "printr", "printb", "printc", "prints",
      // What a subscript on a type with no elements of its own reaches, and what a `for` asks of
      // what it walks — each looked up in the implementation table by name.
      "Index", "IndexSet", "Iterate",
      // `main(args: []string)`'s argument list, and `s.chars`.
      "args_of", "chars_of",
    ) ++ CoreTraits.required.keySet
}
