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
 * of its own, where `FormatSpec` is filed under `sysl$FormatSpec` and nothing spelled `FormatSpec`
 * is found — and it would stop working at every one of those sites at once, which is the failure
 * this exists to prevent. So they ask here instead, and moving a declaration is a change to this
 * file.
 *
 * **The library is now in two places at once, and that is the point.** `Std` is the standard module
 * the whole of it is headed for; `Prelude` is what has not moved yet. Every question below is
 * answered over both, so which of the two a declaration is in is a fact no caller has to hold — and
 * when the prelude is empty, what changes here is that one of the two parts goes away.
 *
 * **`CoreTraits` speaks spellings, and its consumers translate.** What that table holds is which
 * operator each trait's method *is*, which is a fact about the source language rather than about
 * where the trait lives — so it stays spelled the way a program writes it, and a consumer holding a
 * resolved key goes through `spelling` before asking it. That is the one place the two vocabularies
 * meet, and they used to meet everywhere by coinciding.
 */
object Library {

  /** The **named** modules the library contributes, which every file may write the names of without
   * importing them (`AutoImport`) and which a program may also reach by their full path.
   *
   * What the prelude contributes is not among them: its declarations are in the anonymous root
   * module, which is the module a headerless program is in too, and there is no path that names it.
   */
  val modules: List[String] = List(Std.module)

  /** The library's declarations, each with the terms its own part reads names in.
   *
   * The two parts read names in different modules and are hoisted accordingly, which is the whole
   * of what a move does: the same declaration, registered under a different key. Neither part
   * imports anything, and neither needs to — `resolveName` looks a name written in a library
   * declaration up among the library's own first, by the spelling rather than by the key, so one
   * part reaches the other with no import to write and in either direction.
   *
   * Each part carries **its own source**, which is what says a name is being read *in* the library.
   * The module cannot say it: the prelude's part is in the root module, and so is a headerless
   * program.
   *
   * `building` is the modules this compilation is **producing** rather than being supplied with,
   * which is how the library's own source gets compiled at all: pointed at `lib`, the compiler would
   * otherwise hand a copy of `sysl` to the files that declare it, and every name in them would be
   * declared twice. What is left out is exactly what the units bring, so the prelude is still here —
   * and it goes on resolving `Writer` to the same key, now answered by the file being compiled
   * rather than by the copy.
   */
  def scoped(building: Set[String] = Set.empty): List[(Scope, Stmt)] = {
    val std =
      if building(Std.module) then Nil
      else Std.parsed.flatMap(p => p.body.map((Scope(Std.module, Imports.empty, Some(p.source)), _)))

    std ::: Prelude.decls.map((Scope(Modules.root, Imports.empty, Some(Prelude.origin)), _))
  }

  /** Whether a source is one of the library's own — what tells a name being read *in* the library
   * from one being read in the program, which the module a declaration is in cannot while the
   * prelude is still in the root one alongside a headerless program's declarations.
   */
  def source(s: Source): Boolean = (s eq Prelude.origin) || Std.sources.exists(_ eq s)

  /** Every declaration the library carries, whichever part it is in. */
  def decls: List[Stmt] = Std.decls ::: Prelude.decls

  /** Whether a declaration is the library's rather than the program's.
   *
   * Asked of the **position** and not of the key, because the two disagree exactly where it matters:
   * the prelude's declarations are keyed under the root module, and so are a headerless program's,
   * so a program that declares an `Ok` of its own would be told its own type was the library's.
   */
  def owns(decl: Positioned): Boolean = Prelude.declares(decl) || Std.declares(decl)

  /** The key the library's declaration named `name` is filed under — what the compiler names one
   * by, wherever it names one rather than reading a name out of source.
   *
   * This is where a move is recorded, and it is recorded by *reading the standard module* rather
   * than by listing what has moved: the declaration is the fact, and a list beside it would be a
   * second one to keep in step. A name the standard module does not declare is the prelude's, whose
   * key is its own spelling.
   */
  def key(name: String): String = moved.getOrElse(name, Modules.qualify(Modules.root, name))

  /** The library's own spelling of a key, or `None` where the key is not the library's.
   *
   * `key`'s inverse, for the direction a resolved bound arrives in: an `impl`'s trait name and a
   * `Type.Bound`'s are keys by the time either is looked at, and the tables the compiler holds about
   * its own traits are spelled the way a program writes them.
   *
   * A key is the library's when it is the one `key` gives that spelling, which is stricter than
   * asking which module it is in: a *program's* root-module `FormatSpec` is keyed `FormatSpec`, and
   * that is not where the library's is once the standard module declares it.
   */
  def spelling(k: String): Option[String] = {
    val name = Modules.bare(k)

    Option.when(key(name) == k)(name)
  }

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
  lazy val declared: Set[String] = names(decls)

  /** Which of the library's names the **standard module** declares, and the key each is filed under.
   * What is not in here has not moved yet.
   */
  private lazy val moved: Map[String, String] =
    names(Std.decls).map(n => n -> Modules.qualify(Std.module, n)).toMap

  private def names(stmts: List[Stmt]): Set[String] = stmts.flatMap {
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
