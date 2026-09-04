package sh.sysl.doc

import sh.sysl.*

/** The symbol graph a reference page is written from: modules, the declarations in them, and the
 * prose each declaration carries.
 *
 * **The unit is a MODULE and not a type.** Scaladoc gives a page to every class because a Java-shaped
 * program is a tree of them; sysl's importable thing is a module, so that is what a reader arrives
 * wanting and what a page should be about. It also maps one-to-one onto the hand-written
 * per-module `library/` pages that already exist, which lets a generated listing sit beside written
 * prose rather than replacing it.
 *
 * **This is built from PARSED units and asks for nothing analyzed.** Every question it answers —
 * what is declared, what is public, what the signature says, what the prose above it was — is
 * answered by the syntax. That is worth the discipline: a package whose dependencies are missing, or
 * which does not currently compile, still documents, and a doc tool that fell over on a broken tree
 * would be useless exactly when somebody is trying to read their way out of trouble.
 */
object ApiModel {

  /** What kind of thing a symbol is, which is also the grouping a page uses.
   *
   * The order of the cases is the order the groups appear on a page, and it is deliberate: a reader
   * arriving at a module wants the functions it offers before the types those functions mention, and
   * the trait implementations last because they are facts *about* the types above rather than things
   * to call.
   */
  enum Kind:
    case Const, Function, Type, Trait, Implementation

  /** Where a symbol may be seen from.
   *
   * `private` in sysl is file-private and `private[module]` is module-wide, so the two are different
   * answers to "should this be on the page" and are kept apart rather than collapsed to a boolean.
   */
  enum Access:
    case Public, Module, File

  /** One documented declaration.
   *
   * `signature` is already rendered — the page has no business walking types, and keeping the AST
   * out of the model is what makes the writer testable against a string.
   *
   * `members` carries a type's methods and a trait's, since those are documented under the type they
   * belong to rather than as symbols of their own. An `impl` block's methods are deliberately absent:
   * their signatures are the trait's, written where the trait is.
   */
  case class Symbol(
      name: String,
      kind: Kind,
      access: Access,
      signature: String,
      doc: Option[DocComments.Doc],
      members: List[Symbol] = Nil,
      /** The line it was declared on, which is what orders a page when nothing else does. */
      line: Int = 0,
  ) {

    /** The first sentence of the prose, for an index that has one column to spend. */
    def summary: String = doc.map(_.summary).getOrElse("")

    /** Whether the author said anything at all. A page says so rather than pretending. */
    def documented: Boolean = doc.exists(d => d.body.nonEmpty || d.tags.nonEmpty)
  }

  /** One module's page.
   *
   * `capabilities` is carried because it is the first thing somebody choosing a module wants: a
   * `requires {}` says it will run on a microcontroller, and that is a headline rather than a
   * footnote.
   */
  case class Module(
      name: String,
      symbols: List[Symbol],
      doc: Option[DocComments.Doc],
      capabilities: List[String],
  ) {

    def summary: String = doc.map(_.summary).getOrElse("")

    /** The symbols of one kind, in the order they will be listed. */
    def of(kind: Kind): List[Symbol] = symbols.filter(_.kind == kind)
  }

  /** Build the model from parsed units.
   *
   * `includePrivate` decides whether file- and module-private declarations appear. The default is
   * off, because the customer is somebody who has imported the module and can only call what it
   * exports — but it is offered rather than hardcoded, since a maintainer reading their own package
   * wants the whole of it.
   */
  def build(units: List[Program], includePrivate: Boolean = false): List[Module] = {
    // A `@tests` file is SCAFFOLDING AND NOT API, and dropping it is not a nicety.
    //
    // Every build but `sysl test` strips those files before analysis, so their functions are not in
    // the artifact anybody links and cannot be called by anybody's program. Documenting them puts
    // `split_cuts` and `lossy_truncated_is_one` on the page beside `split` and `from_utf8_lossy`,
    // where a reader has no way to tell which of the four they may use.
    //
    // Measured before this line existed: `sysl.text` came out with 74 functions, of which 30 were
    // its own tests — the module's index was 40% noise and its most-repeated entry was a name no
    // caller can spell.
    val documented = units.filterNot(_.testOnly)
    val byModule   = documented.filter(_.module.isDefined).groupBy(_.module.get.show)

    byModule.toList.map { (name, us) =>
      // A module's files have no order of their own — they are whatever the directory listing gave —
      // so symbols are sorted by name within each kind rather than left in an order that would
      // change when a file is renamed. Source order within ONE file is meaningful and is what the
      // line number preserves for the tie.
      val symbols =
        us.flatMap(unit => unit.body.flatMap(symbolOf(unit, _)))
          .filter(s => includePrivate || s.access == Access.Public)
          .sortBy(s => (s.kind.ordinal, s.name.toLowerCase, s.line))

      // The module's own doc comment is the one at the top of a file that nothing adopted — the
      // `above` rule ends an association at a blank line precisely so this can exist. WHICH file
      // may carry it is `headlineFile`'s rule, and a module whose headline file has none has no
      // summary rather than borrowing a sibling's.
      val header = headlineFiles(name, us).flatMap(unit => moduleDoc(unit)).headOption

      Module(
        name = name,
        symbols = symbols,
        doc = header,
        capabilities = us.flatMap(_.capabilities.map(capabilityText)).distinct.sorted,
      )
    }.sortBy(_.name)
  }

  /** The files of a module that may carry its headline: the one **named for the module's last path
   * segment**, and no other.
   *
   * `sysl.path` is `path.sysl`, `sysl.math.bigint` is `bigint.sysl`, `sysl.regex` is `regex.lsysl` —
   * a literate module is a module, so both suffixes count. A module whose headline file is absent,
   * or carries no unclaimed doc comment, has **no** summary.
   *
   * **A rule rather than an order, and that is the whole point.** This used to be "the first file by
   * path that has one", which cannot be wrong loudly: `sysl` is fourteen files and `check.sysl` sorts
   * first, so the prelude's index row read *"What a program does when something it was sure of turns
   * out not to hold"* — a sentence about `assert` and `panic`, standing for the module a newcomer
   * meets first. Nothing failed, because nothing could: a sibling's sentence is well-formed prose
   * about the right module's neighbourhood. **A blank row is a defect somebody can see; a plausible
   * wrong one is not**, which is why the absent case is left blank rather than filled in.
   *
   * **The library follows this rule for 23 of its 34 modules and no library anywhere uses an index
   * file**, which is what chose it over a `module.sysl` convention that would have had to be invented
   * for every module at once. Where the eponymous file exists it holds real declarations, so the
   * headline sits above code rather than alone in a file that exists only to carry prose.
   *
   * **A module of ONE file is its own headline file, whatever it is called.** There is no ambiguity
   * to resolve there and nothing to borrow from, so the rule has nothing to do — and applying it
   * anyway would silently drop the summary of `sysl doc <one file>`, which is a whole usage rather
   * than an edge.
   */
  private def headlineFiles(module: String, us: List[Program]): List[Program] = us match
    case _ :: Nil => us
    // Sorted so that a module carrying both `x.sysl` and `x.lsysl` — which nothing does, and which
    // the format permits — answers the same way every time rather than by directory order.
    case several  => several.filter(u => stem(u.source.name) == module.split('.').last)
                            .sortBy(_.source.name)

  /** A file's name with its directory and its one extension taken off: `lib/sysl/path/glob.sysl` is
   * `glob`. Both separators are handled because a `Source` carries the path the driver walked, and
   * on Windows that is a backslash.
   */
  private def stem(path: String): String = {
    val base = path.replace('\\', '/').split('/').last
    val dot  = base.lastIndexOf('.')

    if dot > 0 then base.substring(0, dot) else base
  }

  /** A capability clause as a reader would write it.
   *
   * One clause names one capability, so a module that both requires and gives up something has two
   * of them — which is why this renders a clause rather than a set, and why the caller collects.
   */
  private def capabilityText(c: CapabilityClause): String = c.direction match
    case CapabilityDirection.Requires => s"requires { ${c.name} }"
    case CapabilityDirection.Narrows  => s"no ${c.name}"

  /** A file's leading doc comment, where nothing below adopted it.
   *
   * The test is exactly the `above` rule run backwards: a doc comment is the module's if no
   * declaration in the file claims it. That is cheaper than it sounds and, more to the point, it
   * cannot disagree with the attachment the rest of this file does — the same function answers both
   * questions.
   */
  private def moduleDoc(unit: Program): Option[DocComments.Doc] = {
    val claimed =
      unit.body.flatMap(declLine).flatMap(l => DocComments.above(unit.source, unit.docs, l)).toSet

    unit.docs.headOption.filterNot(claimed.contains)
  }

  /** The line a declaration sits on, for the ones that can carry prose. */
  private def declLine(stmt: Stmt): Option[Int] = stmt match
    case f: FuncDecl   => f.pos.map(_.line)
    case s: StructDecl => s.pos.map(_.line)
    case e: EnumDecl   => e.pos.map(_.line)
    case t: TraitDecl  => t.pos.map(_.line)
    case c: ConstDecl  => c.pos.map(_.line)
    case i: ImplDecl   => i.pos.map(_.line)
    case _             => None

  /** How a declaration's visibility reads as an access level. */
  private def access(vis: Visibility): Access = vis match
    case Visibility.Public    => Access.Public
    case Visibility.Scoped(_) => Access.Module
    case Visibility.File      => Access.File

  /** One top-level statement as a symbol, or nothing where it is not a declaration.
   *
   * A `var` at the top of a file is deliberately absent: in an entry file it is a local of the
   * program's body rather than module storage, and in a library module a mutable global is not an
   * API a caller should be shown. A `const` is here because it is a module member proper.
   */
  private def symbolOf(unit: Program, stmt: Stmt): Option[Symbol] = {
    def docFor(pos: Option[Int]): Option[DocComments.Doc] =
      pos.flatMap(l => DocComments.above(unit.source, unit.docs, l))

    def member(m: MethodDecl): Symbol =
      Symbol(
        name = m.name,
        kind = Kind.Function,
        access = access(m.vis),
        signature = Signature.method(m),
        doc = docFor(m.pos.map(_.line)),
        line = m.pos.map(_.line).getOrElse(0),
      )

    stmt match
      case f: FuncDecl =>
        Some(Symbol(f.name, Kind.Function, access(f.vis), Signature.func(f), docFor(f.pos.map(_.line)),
          line = f.pos.map(_.line).getOrElse(0)))

      case s: StructDecl =>
        Some(Symbol(s.name, Kind.Type, access(s.vis), Signature.struct(s), docFor(s.pos.map(_.line)),
          s.members.map(member), s.pos.map(_.line).getOrElse(0)))

      case e: EnumDecl =>
        Some(Symbol(e.name, Kind.Type, access(e.vis), Signature.enumDecl(e), docFor(e.pos.map(_.line)),
          e.members.map(member), e.pos.map(_.line).getOrElse(0)))

      case t: TraitDecl =>
        Some(Symbol(t.name, Kind.Trait, access(t.vis), Signature.traitDecl(t), docFor(t.pos.map(_.line)),
          t.methods.map(member), t.pos.map(_.line).getOrElse(0)))

      case c: ConstDecl =>
        Some(Symbol(c.name, Kind.Const, access(c.vis), Signature.const(c), docFor(c.pos.map(_.line)),
          line = c.pos.map(_.line).getOrElse(0)))

      // An `impl` is named for what it says — this type has this trait — rather than for a
      // declaration name it does not have. That name is also what a reader would search for.
      case i: ImplDecl =>
        Some(Symbol(s"${i.traitName} for ${Signature.typeText(i.forType)}", Kind.Implementation,
          Access.Public, Signature.implHead(i), docFor(i.pos.map(_.line)),
          line = i.pos.map(_.line).getOrElse(0)))

      case _ => None
  }
}
