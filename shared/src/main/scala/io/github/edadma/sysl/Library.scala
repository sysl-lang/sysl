package io.github.edadma.sysl

/** What is true of the standard module however a compilation was handed it.
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
 * **The two questions have since parted, and where the line falls is the point of this file.** The
 * first one is about *these* trees: whether a declaration is the library's is answered by which
 * source it came from, and which sources those are depends on whether the compilation parsed the
 * library or decoded it. It moved to `Stdlib`, which a compilation is given. The second is about the
 * standard module as such — its name, the key a declaration in it is filed under, the spelling a key
 * renders as, which names the compiler reaches for itself — and none of that turns on the carrier.
 * That is what is left here.
 *
 * **`CoreTraits` speaks spellings, and its consumers translate.** What that table holds is which
 * operator each trait's method *is*, which is a fact about the source language rather than about
 * where the trait lives — so it stays spelled the way a program writes it, and a consumer holding a
 * resolved key goes through `spelling` before asking it. That is the one place the two vocabularies
 * meet, and they used to meet everywhere by coinciding.
 */
object Library {

  /** The **named** modules the library's own source declares — what `sysl build-lib --std` is
   * producing, and the only compilation allowed to declare any of them.
   *
   * Read off the library's own headers rather than written down, because a module is a directory
   * and the library is a tree: adding a directory under `lib/sysl` is how a submodule comes into
   * being, and a list kept beside that would have to be edited in step with it or would quietly
   * leave the new module out of the build that is supposed to be producing it.
   *
   * What a given compilation was *handed* is `Stdlib.modules`, and that is the one to ask about
   * scope. This is about the source in the tree, like everything else here.
   */
  lazy val modules: List[String] = carried.modules

  /** The library modules every file may write the names of without importing them (`AutoImport`,
   * `13 §8`).
   *
   * **Only the standard module**, which is the whole of what auto-importing is for: a program
   * cannot avoid needing what the language desugars onto, so those names arriving unasked-for is
   * honest. A submodule is an offer rather than part of the language, and is reached by naming it
   * or importing it exactly as any other module is (`13 §3`).
   */
  val autoImported: List[String] = List(Std.module)

  /** Every declaration the library carries, as the compiler carries it.
   *
   * The **embedded** copy specifically, and that is the right one for the questions asked of this:
   * what the library declares, which the compiler-known names are held to, is a claim about the
   * source under `lib/sysl` rather than about whatever a given compilation was handed. A
   * compilation's own std is `Stdlib`, which it is given rather than looks up.
   */
  def decls: List[Stmt] = carried.decls

  /** The copy of the library the compiler carries, read for the machine sysl is **running** on.
   *
   * **Every question on this object is about the standard module as such** — which modules it
   * declares, which key a name is filed under, which names the compiler is allowed to spell for
   * itself — and none of them is a question a target has an opinion about. A target *could* have
   * one, since the library is sysl source and may gate on the machine (`Conditional`); what says it
   * does not is a rule, and the rule is that **a name in [[known]] is declared on every target**. A
   * library that gated one of those away would be a library the compiler could compile nothing with
   * on that target, whatever this said — so the choice is only between failing at the gated name and
   * failing here, and `LibraryTests` holds every target in the registry to declaring all of them.
   *
   * What a given *compilation* was handed is `Stdlib`, which it is given rather than looks up. This is
   * the copy in the tree, and it is the one a test asking about the library itself wants.
   */
  def carried: Stdlib = Stdlib.embedded(Target.default)

  /** The key the library's declaration named `name` is filed under — what the compiler names one
   * by, wherever it names one rather than reading a name out of source.
   *
   * A lookup, because a library declaration is in whichever of the library's modules declares it
   * and the compiler reaches some that are not in the standard one — `args_of` is what builds a
   * `main`'s argument list and lives with the other things a hosted platform hands over. Spelling
   * the standard module's qualification here instead, as this once did, is the claim that there is
   * nowhere else for a library declaration to be, and a name that moved would then be looked up
   * under a module that no longer declares it: found by nothing, and reported as a compiler
   * exception rather than as anything a reader could act on.
   *
   * A name the library does not declare falls back to the standard module's qualification, which is
   * a key that resolves to nothing — the same answer as before, at the same site. `LibraryTests`
   * is what holds `known` to being declared, so nothing reaches that fallback in a tree that passes.
   */
  def key(name: String): String = located.getOrElse(name, Modules.qualify(Std.module, name))

  /** Every spelling the library declares, against the key its own module files it under.
   *
   * One spelling in two library modules would lose one of them here. That is worth no machinery:
   * `key` is only ever asked about `known`, and `LibraryTests` holds that set to naming exactly one
   * declaration apiece.
   */
  private lazy val located: Map[String, String] =
    carried.units.flatMap { u =>
      val module = Compiler.moduleOf(u)

      names(u.body).map(n => n -> Modules.qualify(module, n))
    }.toMap

  /** The library's own spelling of a key, or `None` where the key is not the library's.
   *
   * `key`'s inverse, for the direction a resolved bound arrives in: an `impl`'s trait name and a
   * `Type.Bound`'s are keys by the time either is looked at, and the tables the compiler holds about
   * its own traits are spelled the way a program writes them.
   *
   * A key is the library's when it is in one of the library's modules, none of which anything but
   * the library may declare — a *program's* own `FormatSpec` is keyed under the module its file is
   * in, and that is never one of these.
   */
  def spelling(k: String): Option[String] =
    Option.when(modules.contains(Modules.moduleOf(k)))(Modules.bare(k))

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

  /** The spellings a set of statements declares at the top level. Reachable from a test, which is
   * what holds `key`'s lookup to naming exactly one declaration per spelling.
   */
  private[sysl] def names(stmts: List[Stmt]): Set[String] = stmts.flatMap {
    case d: ConstDecl     => List(d.name)
    case d: ValDecl       => List(d.name)
    case d: FuncDecl      => List(d.name)
    case d: ExternDecl    => List(d.name)
    case d: ExternVarDecl => List(d.name)
    case d: StructDecl    => List(d.name)
    case d: TypeDecl      => List(d.name)
    case d: TraitDecl     => List(d.name)
    case d: EnumDecl      => d.name :: d.variants.map(_.name)
    case _                => Nil
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
      // The same, for the members that are not operators and whose trait is in a submodule.
      "Signed", "Bits",
      // The orderings the atomic forms require written at the call, whose variant names become
      // keywords in the emitted instruction (`06 § The kernel tier`, `Atomics`).
      "Ordering",
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
