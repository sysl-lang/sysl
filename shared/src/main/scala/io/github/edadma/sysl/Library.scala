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
 * Both were once answered in place, by asking a prelude directly and by spelling library keys as
 * bare names. That worked only while the library was a string inside the compiler keyed under the
 * anonymous root module, where a library key *is* its own spelling. It stopped working the moment a
 * declaration moved into a module of its own, where `FormatSpec` is filed under `sysl$FormatSpec`
 * and nothing spelled `FormatSpec` is found — and it would have stopped working at every one of
 * those sites at once, which is the failure this exists to prevent. So they ask here instead.
 *
 * **The library is now one thing: the standard module.** It was two for the length of the drain —
 * declarations moved out of the prelude a surface at a time, and every question below was answered
 * over both halves so that which one a declaration was in was a fact no caller had to hold. The
 * prelude is empty and gone, and what is left is that the answers are now about a module.
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
   */
  val modules: List[String] = List(Std.module)

  /** The library's declarations, each with the terms it reads names in.
   *
   * The library imports nothing and needs to import nothing — `resolveName` looks a name written in
   * a library declaration up among the library's own first, by the spelling rather than by the key,
   * so one file reaches another with no import to write.
   *
   * Each program carries **its own source**, which is what says a name is being read *in* the
   * library.
   *
   * `building` is the modules this compilation is **producing** rather than being supplied with,
   * which is how the library's own source gets compiled at all: pointed at `lib`, the compiler would
   * otherwise hand a copy of `sysl` to the files that declare it, and every name in them would be
   * declared twice.
   */
  def scoped(building: Set[String] = Set.empty): List[(Scope, Stmt)] =
    if building(Std.module) then Nil
    else Std.parsed.flatMap(p => p.body.map((Scope(Std.module, Imports.empty, Some(p.source)), _)))

  /** Whether a source is one of the library's own — what tells a name being read *in* the library
   * from one being read in the program.
   */
  def source(s: Source): Boolean = Std.sources.exists(_ eq s)

  /** Every declaration the library carries. */
  def decls: List[Stmt] = Std.decls

  /** Whether a declaration is the library's rather than the program's.
   *
   * Asked of the **position** rather than of the module, though the two now agree: a `Source` is a
   * stronger answer than a name, since a user file that happened to sit at `lib/sysl/display.sysl`
   * is not this one. While the prelude existed the two *disagreed* — its declarations were keyed
   * under the root module, and so are a headerless program's, so a program declaring an `Ok` of its
   * own would have been told its own type was the library's.
   */
  def owns(decl: Positioned): Boolean = Std.declares(decl)

  /** The key the library's declaration named `name` is filed under — what the compiler names one
   * by, wherever it names one rather than reading a name out of source.
   *
   * Every library declaration is in the standard module, so this is that module's qualification and
   * nothing else. It was a lookup over what had moved for as long as the drain ran; what it records
   * now is that there is nowhere else for a library declaration to be.
   */
  def key(name: String): String = Modules.qualify(Std.module, name)

  /** The library's own spelling of a key, or `None` where the key is not the library's.
   *
   * `key`'s inverse, for the direction a resolved bound arrives in: an `impl`'s trait name and a
   * `Type.Bound`'s are keys by the time either is looked at, and the tables the compiler holds about
   * its own traits are spelled the way a program writes them.
   *
   * A key is the library's when it is in the standard module, which nothing but the library may
   * declare — a *program's* own `FormatSpec` is keyed under the module its file is in, and that is
   * never this one.
   */
  def spelling(k: String): Option[String] =
    Option.when(Modules.moduleOf(k) == Std.module)(Modules.bare(k))

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
   * the library stopped declaring is an exception rather than a diagnostic. They were also the
   * checklist each move through the drain worked from, which is the other reason they are in one
   * place.
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
