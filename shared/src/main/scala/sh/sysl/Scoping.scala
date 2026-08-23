package sh.sysl

import scala.collection.mutable

/** What a name written at a given place means.
 *
 * One question with two halves, and they meet in the middle. Outward, a name is read against the
 * **module** the file contributes to, what that file **imported**, and the library — `13 §3`'s
 * order, filtered at every step by what the writing file is allowed to reach (`13 §2`). Inward, a
 * name may be one of the **locals** an open block bound, which is nearer than any of that and is
 * why the two live together: a lookup asks the scopes first and the modules afterwards.
 *
 * The terms travel with the walk rather than with the tree. A declaration's signature and body mean
 * what they meant **where they were written**, whatever module a call arrived from, which is what
 * `inScope` and the `declScope` table are for — and it is why the module, the imports and the file
 * are three variables restored on the way out rather than three fields on a node.
 *
 * Recording a module's dependency on another (`13 §6`) happens here for the same reason: resolution
 * is where the dependency is *made*, and nothing earlier could have seen it.
 */
trait Scoping extends DeclTables {

  /** Every module the program is made of, by name, including the anonymous root one when a file
   * declared no header. It is what tells a dotted reference that names a module from one that
   * reads a field off a value (`13 §3`), and it is known before any name is resolved because a
   * file's header is the whole of what says which module it is in.
   */
  protected val moduleNames = mutable.LinkedHashSet.empty[String]

  /** The module whose terms a name is currently being read in: the module of the declaration
   * being hoisted, of the body being analyzed, or of the file that carries the statements the
   * program runs. An unqualified name is looked for here first (`13 §3`).
   */
  protected var currentModule: String = Modules.root

  /** What the file being read has imported. It travels with `currentModule` because both are
   * properties of where a declaration was *written*: a body means what it meant there, and its
   * file's imports are half of what that sentence says (`13 §3`).
   */
  protected var currentImports: Imports = Imports.empty

  /** The imports of the blocks currently open, innermost first. An import inside a block is scoped
   * to it, so these are pushed and popped with the local bindings they sit beside — and searched
   * before the file's, which is what makes an inner import shadow an outer one.
   */
  protected var importStack: List[Imports] = Nil

  /** The file whose text is currently being read. It travels with the module and the imports for
   * the same reason they travel together, and it is what a bare `private` is measured against
   * (`13 §2`) — the one visibility level that never crosses a file boundary.
   */
  protected var currentFile: Option[Source] = None

  /** The terms names are currently being read in. */
  protected def currentScope: Scope = Scope(currentModule, currentImports, currentFile)

  /** What the library declares, **as written** against the key it is filed under — the names that
   * are in scope everywhere with no import (`13 §8`).
   *
   * It is a lookup rather than a set of keys because the two differ: a declaration the standard
   * module carries is keyed `sysl$FormatSpec` and is still written `FormatSpec`, and what a use site
   * has is the spelling. Only the library's are entered here, so a *program's* `FormatSpec` is not
   * among them — it is a module's declaration like any other, and not visible from a named module
   * that did not name it.
   *
   * Filled during hoisting from `Stdlib.owns`, which is the one question about where a declaration
   * came from; this is that answer indexed by what a program writes, for the lookups that have a
   * name and no declaration to ask about.
   */
  protected val libraryNames = mutable.HashMap.empty[String, String]

  /** The key a name written in the current module resolves to, or `None` where nothing of that
   * name is declared anywhere it may be read from.
   *
   * `declared` is the table being asked — a type's, a function's, a variant's — because the same
   * spelling may name a type in one module and a function in another, and which one is in scope is
   * a question that can only be answered against the table the use site is looking in.
   *
   * The order is `13 §3`'s: **this module**, then what the file (or the block) has **imported**,
   * then the **library**, and a **fully-qualified path** reaches anything at all. A sibling
   * module's names are deliberately not in it — a module earns visibility by being named or
   * imported (`13 §8`), and the root module has no name, so its declarations are its own files' to
   * use.
   *
   * **Every step is filtered by what may be named from here**, the library's included, so that the
   * two spellings of one declaration cannot disagree: a member the library keeps to itself is out
   * of reach whether a program writes it bare or by path.
   *
   * **`quiet` turns off the two things this does beyond answering**, and it exists for the question
   * the compiler asks *itself* rather than on behalf of a name a file wrote. Resolving normally
   * reports a restriction instead of answering (`reachable` raises), and records a module
   * dependency (`13 §6`) — both right for a written name and both wrong for "could a name of this
   * spelling have meant that?". A quiet ask answers `None` where the ordinary one would complain,
   * and files no edge. `traitInScope` sidesteps the same two by asking the imports directly; this
   * is the same need where the whole search order is wanted.
   *
   * **`inReach` is how a candidate's reach is asked, and it is a parameter because `declAccess` is
   * keyed by the qualified name alone** — so a key is shared by every namespace that spells it the
   * same way. That is harmless while only one of them records access, and wrong the moment two do:
   * an **enum variant** records none and takes its reach from its enum (`09 §3`), so `visible`
   * asked about a variant's key answers about whatever *type* of that spelling recorded one. A
   * `private struct Segment` beside a public `Kind.Segment` hid the variant from every other file
   * and every importer, which is card `0220`'s second half. `variantKey` therefore asks with
   * `variantVisible`; everything else keeps `visible`, which is what its key means.
   */
  protected def resolveName(written: String, quiet: Boolean = false,
                            inReach: String => Boolean = visible)(declared: String => Boolean): Option[String] = {
    val dot = written.lastIndexOf('.')

    // A candidate this file may not name is **reported** rather than passed over, so that resolution
    // does not quietly fall through to an import or the library and answer with something else: the
    // name was found, and what is wrong is that it is not for this file to write. Where the caller
    // only wants an answer — a `quiet` ask — it is simply not a candidate.
    def reach(key: String): Option[String] =
      if quiet then Option.when(inReach(key) || contestedNames(key))(key)
      else if !inReach(key) && !contestedNames(key) then err(s"'${qn(key)}' is ${restriction(key)}")
      else Some(key)

    // A name carrying the module separator is one the compiler built rather than one a file wrote
    // — a synthesized `Self` reference, a default's bound on its own trait — and is already the key
    // it names. Nothing in source can be spelled this way, so passing it through is unambiguous.
    val key =
      if written.indexOf(Modules.sep.toInt) >= 0 then Option.when(declared(written))(written).flatMap(reach)
      else if dot < 0 then
        val own     = Modules.qualify(currentModule, written)
        val library = libraryNames.get(written).filter(declared)

        // The library's step is held to the same restriction as every other, so a helper the
        // library keeps to itself is not an answer to a program's bare name. Nothing enforced it
        // while the library declared nothing private, which is what let the unqualified spelling
        // and the qualified one disagree about the same declaration.
        val offered = library.filter(inReach)

        // Where nothing answered at all, a candidate passed over for being out of reach is the
        // whole story, and saying which restriction it was beats an undefined name. A program's own
        // declaration is reported ahead of the library's for the same reason it is searched for
        // first: it is the likelier thing to have been meant.
        def restricted = Option.when(declared(own))(own).orElse(library).flatMap(reach)

        // A name written in the library takes these same three steps, and used to take a fourth
        // order of its own: the library's names were looked for ahead of the file's own module. That
        // mattered while part of the library sat in the root module a headerless program is also in,
        // where "this module first" would have handed the library's own signatures whatever the
        // program declared under the same name. Every library file is in a library module now, so
        // `own` can only ever be the library's, and the inversion had nothing left to protect — while
        // it did cost the library the ability to import, since the inverted order had no import step
        // in it at all. Which a library of more than one module needs, the same way anyone does.
        if declared(own) && inReach(own) then Some(own)
        else
          // A declaration this file may not name is not a candidate, so the search goes on rather
          // than stopping at it: a file that imported a `width` said which one it meant, and a
          // sibling file's private helper is not an answer to that. Only where nothing else answers
          // at all is the restriction worth reporting — at which point it is the whole story, and a
          // better one than an undefined name.
          importedName(written, inReach)(declared)
            .orElse(offered)
            .orElse(restricted)
      else
        val module = modulePath(written.take(dot))
        val name   = Modules.qualify(module, written.drop(dot + 1))

        Option.when(moduleNames(module) && declared(name))(name).flatMap(reach)

    // Resolution is where a dependency between two modules is *made*, so it is where one is
    // recorded (`13 §6`). Nothing earlier could see it: a qualified path names another module's
    // declaration with no import to scan for, and which module an unqualified name reaches is the
    // whole question this answered.
    //
    // A name carrying the separator is exempt, because a dependency is something a **file** wrote
    // and one of these was written by the compiler. Each is a re-spelling of a reference already
    // resolved from source, where the edge was recorded in the module that wrote it — and recording
    // one again here would file it under whichever declaration's terms the walk is reading, which
    // for a trait's default copied into another module's type is not where the reference came from.
    // The one *source* reference that arrives already spelled this way is a qualified value path,
    // and `Analyzer.throughModule`, which spells it in the terms of the body that wrote it, records
    // that edge itself.
    if !quiet && written.indexOf(Modules.sep.toInt) < 0 then key.foreach(k => dependsOn(Modules.moduleOf(k)))
    key
  }

  // --- the module graph -------------------------------------------------------------------

  /** Which module depends on which, and where the reference that first said so was written
   * (`13 §6`).
   *
   * The **first** reference is the one kept, because a cycle is reported against one line and the
   * earliest of them is the one a reader can follow the rest of the chain from.
   */
  protected val moduleEdges = mutable.LinkedHashMap.empty[(String, String), Option[Pos]]

  /** Records that whatever is being read now depends on `to`.
   *
   * A module does not depend on itself, and nothing depends on the **root** module: the library
   * lives there and is the language rather than a module (`13 §8`), and a program's own root-module
   * declarations are its files' alone, since the root module has no name for anything else to
   * write. So the root is a module that only ever depends, and can never be depended on.
   *
   * A path that names no module is not one either: an import may be written for one that does not
   * exist, and the diagnostic for that says so far better than a graph built around it could.
   */
  protected def dependsOn(to: String): Unit =
    if to != currentModule && to != Modules.root && moduleNames(to) then
      moduleEdges.getOrElseUpdate((currentModule, to), currentPos)

  // --- capabilities ---------------------------------------------------------------------

  /** What each module gave up, and where the clause that said so was written (`13 §4`).
   *
   * It is keyed by module rather than by file because a capability is a property of the module —
   * which is why every file of one has to state the same clause, and why the position kept is
   * whichever file said it first: a use site is refused by the module's rule, and any of its files
   * is as good a place to read that rule from as another.
   *
   * A module with no clause has no entry, so the common case — every module in almost every program
   * — costs a lookup that finds nothing rather than an entry of its own.
   */
  protected val moduleNarrows = mutable.LinkedHashMap.empty[String, Map[String, Option[Pos]]]

  /** What each module declared it cannot be built without, the other direction of the same clause.
   *
   * A requirement is documentation plus an early diagnostic (`capabilities.md`): using a gated
   * feature already implies the requirement, so nothing here changes what a module may do. What it
   * changes is what a *dependent* is told, and when.
   */
  protected val moduleRequires = mutable.LinkedHashMap.empty[String, Map[String, Option[Pos]]]

  // --- visibility -----------------------------------------------------------------------

  /** Where a **restricted** declaration may be named from (`13 §2`): the file that wrote it, and —
   * for a scoped-private one — the module subtree its `private[M]` widened to.
   *
   * A public declaration has no entry at all, which is what makes the unmarked default cost a
   * lookup that finds nothing rather than an entry per declaration in every program.
   */
  protected case class Access(file: Option[Source], subtree: Option[String])

  protected val declAccess = mutable.HashMap.empty[String, Access]

  /** Names two declarations claimed. The duplicate was reported where it was written, and the key
   * goes on standing for whichever declaration reached it first — so the second one's file is now a
   * file that wrote the name and cannot reach it.
   *
   * Asking about *reach* there would answer a question the reader did not ask, and answer it with
   * something false-sounding: `private to 'g.sysl'` about a name this file declares three lines up.
   * A contested name is therefore reachable from anywhere, which costs nothing — the compilation is
   * already failing on the duplicate.
   */
  protected val contestedNames = mutable.Set.empty[String]

  /** Reports a name a second declaration claimed, marking the key contested first so that later uses
   * of it are not also told whose the name is (`contestedNames`).
   */
  protected def duplicate(key: String, msg: String): Nothing = {
    contestedNames += key
    err(msg)
  }

  /** Whether a declaration may be named from where the analyzer currently is.
   *
   * A **file**-private one is compared by source identity rather than by name, since two files of
   * one project may be called the same thing. A **scoped** one is visible across the named module
   * and everything beneath it, which is a contiguous subtree because `private[M]` can only name an
   * enclosing module (`13 §2`) — so containment is the whole of the test.
   */
  protected def visible(key: String): Boolean = declAccess.get(key).forall {
    case Access(file, None)    => file.isEmpty || file.exists(f => currentFile.exists(_ eq f))
    case Access(_, Some(m))    => currentModule == m || currentModule.startsWith(s"$m.")
  }

  /** Whether a declaration may be named only from inside the file that wrote it — `13 §2`'s bare
   * `private`, as opposed to a `private[M]` that widened to a module subtree.
   *
   * This is the one reach that provably never crosses a file boundary, and every file of a
   * compilation lands in the same LLVM module, so a symbol at this reach has all of its callers in
   * the module that defines it. That is exactly the condition `internal` linkage states, which is
   * what `Codegen` uses it for.
   */
  protected def fileLocal(key: String): Boolean = declAccess.get(key).exists {
    case Access(Some(_), None) => true
    case _                     => false
  }

  /** Whether a struct's **layout** is hidden from where the analyzer currently is — `opaque`, and
   * declared somewhere other than here (`15 §9`).
   *
   * The reach is the **declaring module exactly**, not a subtree the way `private[M]` widens. What
   * `opaque` buys is that a field may be added or reordered with nothing downstream recompiled, and
   * the set of files that must be recompiled together is the module: its files share one scope
   * (`13 §1`), so they are already one unit for this and a submodule is already not.
   *
   * This sits beside `visible` because the two are the same *kind* of question asked about different
   * things — who may say the name, and who may know the shape — and are deliberately independent. A
   * public type may be opaque, which is the whole point of one; a `private` type may be opaque too,
   * and simply has nobody left to be opaque to.
   */
  protected def layoutHidden(base: String): Boolean =
    structDecls.get(base).exists(_.opaque) && Modules.moduleOf(base) != currentModule

  /** Refuses a use of an `opaque` struct that would need its layout.
   *
   * **The one thing allowed outside is `*Name`**, so every refusal here is a position that needs a
   * size or an offset: a binding, a field of another type, an element, a by-value parameter or
   * result, a type argument, a construction, a field selection, and the `sizeof` that asks outright.
   * They are one message because they are one fact, and a reader who hits any of them needs the same
   * next step — reach it through the module's own functions.
   */
  protected def checkLayoutKnown(base: String, written: String): Unit =
    if layoutHidden(base) then
      err(s"'$written' is opaque outside '${Modules.moduleOf(base)}', so its layout is not known " +
        s"here — it may be named as '*$written' and passed to that module's own functions, but not " +
        "built, held by value, laid out inside another type, taken apart, or measured")

  /** Why a declaration cannot be named here, as a diagnostic says it. */
  protected def restriction(key: String): String = declAccess.get(key) match
    case Some(Access(Some(f), None)) => s"private to '${f.name}', the file that declares it"
    case Some(Access(_, Some(m)))    => s"private to module '$m'"
    case _                           => "private"

  // --- module paths and imports ----------------------------------------------------------

  /** Whether `name` is a module, or the first segment of one — everything a dotted reference could
   * read as the start of a module path.
   */
  protected def namesModule(name: String): Boolean =
    moduleNames(name) || moduleNames.exists(_.startsWith(s"$name."))

  /** Whether a **whole written path** leads anywhere: it is a module, begins one, or names something
   * a module declares.
   *
   * This is the question the readings below are chosen by, and asking it of the path rather than of
   * its leading segment is the difference between them working and not. A segment is shared by
   * everything a convention namespaces together — `packages.md § 9`'s reverse DNS puts every package
   * in an org under `sh` — so `namesModule(head)` answers yes for a path that names nothing at all,
   * and answers it on the strength of some unrelated tree that happens to sit under the same word.
   */
  protected def reachesModule(path: String): Boolean =
    namesModule(path) || (path.lastIndexOf('.') > 0 && moduleNames(path.take(path.lastIndexOf('.'))))

  /** A written module path with its leading segment read through the imports, so that the `fs` of
   * `import std.fs` names `std.fs` wherever a path can be written.
   *
   * An import may not bind a name that already begins a real module (`checkImportName`), so an
   * import and a module can never both answer and which is asked first decides nothing. Everything
   * after the import is `inPackage`'s to order.
   */
  protected def modulePath(written: String): String = {
    val head = written.takeWhile(_ != '.')

    importedModule(head).map(_ + written.drop(head.length)).getOrElse(inPackage(written))
  }

  /** A written module path read through the **package** layer alone, leaving a file's own imports
   * out of it.
   *
   * `import` paths take this rather than `modulePath`, because an import is what *makes* a shorter
   * spelling and reading one through the spellings already in scope would let an import be written
   * in terms of another — a rename of a rename, which no language here offers and which would make
   * the order of a file's import lines change what they mean.
   *
   * **Three readings, in order, and the first that leads anywhere wins.** A path written inside a
   * package means that package's own tree before it can mean anything else (`ownPackage`); failing
   * that it is read as written; failing that it is offered to the packages the file's own manifest
   * named (`mountedPackage`). A path that leads nowhere under any of them is handed back as written,
   * so the caller's diagnostic quotes what was there rather than a rewriting of it.
   */
  protected def inPackage(written: String): String =
    ownPackage(written)
      .orElse(Option.when(reachesModule(written))(written))
      .orElse(mountedPackage(written))
      .getOrElse(written)

  /** What a written path's leading segment means in the **package the file itself belongs to**
   * (`packages.md § 9`).
   *
   * A package's own modules sit under its canonical prefix, so a file of `github.com/e/json` writing
   * `json.Parser` means that package's `json` and nothing else — which is what makes a package's
   * source read the same whoever depends on it. The leading segment is what gets qualified, because
   * the path is already relative to that package's root and everything after the head travels
   * unchanged.
   *
   * ==This is asked BEFORE the global question, and that order is the whole of a defect==
   *
   * Whether a segment begins a module is a question about **every** module in the compilation, so a
   * package writing its own `sh.sysl.pico.externs` was answered by an unrelated source root that
   * merely happened to supply `sh.sysl.harness`: the head named a module, the path was left
   * unqualified, and the package's own sub-module was no longer reachable from its own source. The
   * convention `§ 9` recommends is what made it certain rather than unlucky — every package
   * namespaced by reverse DNS shares the segment `sh`.
   *
   * So a path written inside a package is read against that package first. It cannot mean anything
   * else: unlike an import, which a file wrote and can rename, the prefix is added on the way in and
   * the source has no spelling for it.
   *
   * **It answers only where the qualified path leads somewhere**, which is what keeps the claim from
   * being about the segment. A package laid out at `sh/sysl/mine` owns the segment `sh` and its
   * *dependencies* are namespaced under it too — so `sh.sysl.dep.open` qualifies to a path that
   * package's tree has nothing at, and must fall through to the manifest that named the dependency
   * rather than be swallowed by the ownership claim.
   *
   * The project being built has an empty prefix, so this misses and a path is read exactly as it was
   * before any of this existed.
   */
  private def ownPackage(written: String): Option[String] = {
    val prefix = currentFile.map(packages.prefixOf).getOrElse("")
    val head   = written.takeWhile(_ != '.')

    Option.when(prefix.nonEmpty)(Packages.qualify(prefix, head)).filter(namesModule)
      .map(_ + written.drop(head.length))
      .filter(reachesModule)
  }

  /** The other half: a **dependency** of the file's package, under whatever that package's manifest
   * calls it.
   *
   * **The whole path is offered and not its leading segment**, which is the other half of `§ 9`
   * reading a written path differently: what a dependency binds is a module path rather than a
   * segment — `sh.sysl.table` and not `sh`, since a directory holding no source is no module
   * (`13 §1`).
   *
   * This one is asked *after* the global question rather than before it, and the asymmetry with
   * `ownPackage` is deliberate. A package's own modules are what its source is written in terms of
   * and cannot be anything else; a dependency's are reached by a name the manifest chose, which is a
   * binding like any other and has never taken precedence over a module that is simply there.
   */
  private def mountedPackage(written: String): Option[String] =
    packages.mounted(currentFile.map(packages.prefixOf).getOrElse(""), written)

  /** The module a name was imported as, searching the open blocks before the file. */
  protected def importedModule(name: String): Option[String] = searchImports(_.moduleAs(name))

  /** The key an **imported** name stands for, or `None` where nothing imported answers to it in the
   * table being asked.
   *
   * The two forms are asked in `13 §3`'s order. A name brought in by a selector is a deliberate
   * act and wins outright; a name merely *offered* by a wildcard is taken only if nothing more
   * specific claimed it, and two wildcards offering the same name make an unqualified use of it
   * ambiguous rather than silently picking one.
   *
   * A **wildcard offers only what is visible here** (`13 §2`), which is what keeps a module's
   * private helper from either answering to its name or making a name from elsewhere ambiguous. A
   * selector is not filtered the same way: naming something deliberately and being told it cannot
   * be reached is the more useful answer than being told nothing is there, so that one is reported
   * at the import itself.
   */
  protected def importedName(written: String, inReach: String => Boolean = visible)(
      declared: String => Boolean): Option[String] =
    searchImports { imports =>
      imports.names.get(written).filter(declared).orElse {
        imports.wildcards.map(Modules.qualify(_, written)).filter(k => declared(k) && inReach(k)).distinct match
          case Nil         => None
          case key :: Nil  => Some(key)
          case keys        =>
            err(s"'$written' is offered by ${keys.map(k => s"'${Modules.moduleOf(k)}.*'").mkString(" and ")} " +
              "— import it selectively, or write the module it comes from")
      }
    }

  /** Asks each open import scope in turn, innermost first, and the file's last. */
  private def searchImports[T](ask: Imports => Option[T]): Option[T] =
    if importStack.isEmpty && currentImports.isEmpty then None
    else importStack.iterator.map(ask).collectFirst { case Some(t) => t }.orElse(ask(currentImports))

  // --- the keys the tables are asked with -------------------------------------------------

  /** The key a written **type** name resolves to: a struct's, an enum's, or a constrained subtype's.
   *
   * **An alias is followed here, once, for everything downstream.** `type FRect = c.FRect` declares
   * no type, so every table keyed on a type — the struct decls a constructor call reads, the members
   * an `impl` registers, the enum a variant is selected from — has to be asked about what the alias
   * *names* rather than about the alias. Answering that in this one place is what stops every such
   * table growing a case for aliases; `aliasedKey` is the identity for everything that is not one.
   */
  protected def typeKey(written: String): Option[String] =
    resolveName(written)(n => structDecls.contains(n) || enumDecls.contains(n) || constrainedDecls.contains(n))
      .map(followAlias)

  /** Whether a **struct** answers to this name here, asked without resolving it.
   *
   * The one caller is the call-position arm that has to choose between a variant and a same-named
   * struct (card `0220`), and it cannot use `typeKey`: that raises on a candidate the site may not
   * name and records a module dependency, neither of which belongs to a question nobody asked. A
   * variant call in a module where some *other* module happens to keep a private `Segment` would
   * otherwise be refused for naming that struct, and one that merely shares a spelling with a
   * struct next door would file an edge the program never wrote.
   */
  protected def structInScope(written: String): Boolean =
    resolveName(written, quiet = true)(n =>
      structDecls.contains(n) || enumDecls.contains(n) || constrainedDecls.contains(n))
      .map(followAlias)
      .exists(structDecls.contains)

  /** What an alias names, overridden where the constrained-type tables are in scope. It is the
   * identity here because `Scoping` sits below them, and because a compiler pass that has not yet
   * collected the declarations must not start resolving one.
   */
  protected def followAlias(key: String): String = key

  /** The key a written **trait** name resolves to. */
  protected def traitKey(written: String): Option[String] = resolveName(written)(traitDecls.contains)

  /** Whether a trait can be **named** from where the walk currently is, which is what its members'
   * reachability is measured by (`13 §2`).
   *
   * A trait declared in this module, one this file or an open block imported by name, one a
   * wildcard offers, and one an auto-imported module carries are in scope; a sibling module's and a
   * library submodule's are not. That last case is the point — a library's trait claims its member
   * names only where a file asked for the trait.
   *
   * **Asked of the imports directly rather than through `resolveName`.** The general resolver is
   * built to produce good diagnostics as well as answers: where a name is declared but out of
   * reach it reports the restriction rather than answering, which is right for a name a file wrote
   * and wrong for a question about whether the file *could* have written one. This has no name in
   * hand to report about — it is deciding which of several members a use meant — so it asks the one
   * thing it needs and cannot fail.
   */
  protected def traitInScope(key: String): Boolean = {
    val (module, name) = Modules.split(key)

    // An auto-imported module needs no case of its own: `ProgramWalk` starts every file's imports
    // with a wildcard over each one, so the standard module's traits are reached by the same clause
    // a written `import sysl.math.*` is.
    module == currentModule || currentModule.startsWith(s"$module.") ||
    searchImports { imports =>
      Option.when(imports.names.get(name).contains(key) || imports.wildcards.contains(module))(true)
    }.nonEmpty
  }

  /** The key a written **function** name resolves to. */
  protected def funcKey(written: String): Option[String] = resolveName(written)(funcDecls.contains)

  /** Whether this module declares the name as **storage, a constant, or an enum variant** — the
   * question a bare name has to be asked before it is treated as a function.
   *
   * `resolveName` ranks a program's own declaration above an import's and the library's, but it
   * ranks *within one table*, and the tables are asked one at a time. So a walk that asks the
   * function table first is asking "is there a function of this name anywhere, the library
   * included?" before "is there storage of this name right here" — and the library wins a name the
   * program declared itself, which no reader would predict from the source in front of them. A
   * program's own declaration is the likelier thing to have been meant, and unlike the library's it
   * is on the screen.
   *
   * A module that declares both is left exactly as it was, so the duplicate is still reported as a
   * duplicate rather than quietly resolved one way by this.
   */
  protected def ownValueName(written: String): Boolean = {
    val own = Modules.qualify(currentModule, written)

    !funcDecls.contains(own) && visible(own) &&
    (constDecls.contains(own) || valDecls.contains(own) || externVarDecls.contains(own) ||
      variantOwners.contains(own))
  }

  /** The key a written **enum variant** name resolves to. A variant is reachable unqualified — a
   * bare `Circle(5)` — so it is a name of the module its enum was declared in, which is why two
   * modules may each have a `Circle`.
   *
   * The key says which *module*, and not which enum: one module may declare two enums that each
   * name a variant `Circle`, and `variantOwnerOf` is what picks between them at the use site.
   *
   * **Reach is asked with `variantVisible`**, because the key a variant shares with a same-named
   * type is also the key `declAccess` is written under — see `resolveName`'s `inReach`.
   */
  protected def variantKey(written: String): Option[String] =
    resolveName(written, inReach = variantVisible)(variantOwners.contains)

  /** Whether an **enum variant** may be named from here: whether any enum offering it may be.
   *
   * A variant declares no visibility of its own and records none — it follows the enum that
   * declares it (`09 §3`), which is what makes `variantOwnerOf`'s "widest owner wins" rule work. So
   * `visible` asked about a variant's key finds no entry and answers yes, *unless* a type or value
   * of the same spelling recorded one, at which point it answers about that instead: a
   * `private struct Segment` beside a public `Kind.Segment` made the variant unreachable outside
   * the file, with `undefined name 'Segment'` and nothing naming the struct (card `0220`).
   */
  protected def variantVisible(key: String): Boolean = variantOwners.getOrElse(key, Nil).exists(visible)

  /** Which enum a variant name means here, where its module offers more than one answer (`09 §3`).
   *
   * **The expected type decides wherever there is one**, which is nearly always: an argument, a
   * declared `val`, a `return` and an annotated field all supply it, so `val e: Link = Fault(rc)` is
   * unambiguous however many other enums name a `Fault`. Failing that, the single candidate the
   * reader can *see* decides — a private enum in the same module is a candidate and one in another
   * module is not, so a name that is ambiguous inside the file declaring it can be perfectly clear
   * outside.
   *
   * **`None` means neither rule settled it, and the caller reports that rather than choosing.**
   * There is deliberately no fall back to "the first one declared": a construction that silently
   * picked an enum would be a line whose meaning changed when somebody added an unrelated enum
   * above it, which is the failure this whole arrangement exists to avoid.
   */
  protected def variantOwnerOf(key: String, expected: Option[Type]): Option[String] = {
    val owners = variantOwners.getOrElse(key, Nil)

    def named = variantEnumExpected(key, expected)

    def seen = owners.filter(visible) match
      case one :: Nil => Some(one)
      case _          => None

    owners match
      // One answer is one answer, and it is reported as itself: a lone variant that is out of reach,
      // or that disagrees with the expected type, has a message of its own further down that says
      // more than an ambiguity would.
      case one :: Nil => Some(one)
      case _          => named.orElse(seen)
  }

  /** The enum the **expected type** names, where it is one this variant belongs to.
   *
   * This is the first of `variantOwnerOf`'s two rules on its own, and it is on its own because a
   * second question needs it: where one name answers as both a variant and a struct, a call has to
   * decide which of the two it is, and "the site asked for the enum" is what settles it.
   */
  protected def variantEnumExpected(key: String, expected: Option[Type]): Option[String] = {
    val owners = variantOwners.getOrElse(key, Nil)

    expected.map(Type.repr).collect { case e: Type.Enum if owners.contains(e.base) => e.base }
  }

  /** Every enum offering a variant of this name, in declaration order — what an ambiguity message
   * lists.
   */
  protected def variantOwnerList(key: String): List[String] = variantOwners.getOrElse(key, Nil)

  // --- reading a declaration in the terms it was written in -------------------------------

  /** Where each declaration was **written** — the module its file contributes to, and what that
   * file imported.
   *
   * The module is usually readable off the key, but not always: a member is filed under the type it
   * belongs to and an `impl` in one module may be written for a type in another, and a trait's
   * default is copied into every implementing type, wherever those are. The imports are never
   * readable off a key at all. Both travel with the declaration, so its signature, its fields, and
   * its body mean what they meant where they were written.
   */
  protected val declScope = mutable.HashMap.empty[String, Scope]

  /** The terms a declaration's signature and body are read in. A declaration with no file behind it
   * — the library's, or one the compiler synthesized — imports nothing and is read in the module its
   * key names.
   */
  protected def scopeFor(name: String): Scope =
    declScope.getOrElse(name, Scope(Modules.moduleOf(name), Imports.empty))

  /** Runs `body` reading names as `scope` does, restoring the enclosing terms after.
   *
   * Everything that belongs to a declaration — its signature, its fields, its body — resolves where
   * it was **written**, whatever module the walk arrived from. A call in one module to a generic
   * function in another instantiates that function's signature, and a `Point` in it is the
   * declaring module's `Point`.
   */
  protected def inScope[T](scope: Scope)(body: => T): T = {
    val savedModule  = currentModule
    val savedImports = currentImports
    val savedStack   = importStack
    val savedFile    = currentFile

    currentModule = scope.module
    currentImports = scope.imports
    currentFile = scope.file
    // A declaration's own file is where its names are read, so whatever blocks the walk arrived
    // through are not in scope inside it.
    importStack = Nil
    try body
    finally
      currentModule = savedModule
      currentImports = savedImports
      importStack = savedStack
      currentFile = savedFile
  }

  /** Runs `body` in the terms the declaration `name` was written in. */
  protected def inDecl[T](name: String)(body: => T): T = inScope(scopeFor(name))(body)

  /** Runs `body` in `name`'s terms **and with nothing local in scope** — how a parameter's default
   * is analyzed at a call that left the argument out (`12 §2a`).
   *
   * `inDecl` alone would put the default in the declaration's module while leaving it looking at
   * the *caller's* locals, so a default of `n` would quietly find whatever the call site happened
   * to have called `n`. Emptying the stack is what makes the refusal a refusal: a default naming a
   * parameter, or anything else nothing at module level declares, is undefined here and says so.
   */
  protected def inDefault[T](name: Option[String])(body: => T): T = {
    val saved = scopes

    scopes = List(mutable.LinkedHashMap.empty[String, Binding])
    try name.fold(body)(inDecl(_)(body))
    finally scopes = saved
  }

  /** The defaults being filled right now, so one that leads back to itself is caught rather than
   * recursed into — `10 §3`'s guard, at the other argument list.
   *
   * Keyed on **where the default was written** rather than on the declaration it belongs to. The
   * declaration is too coarse: a member's default that calls a sibling member of the same type
   * would look like a cycle when it is an ordinary call. A position is exactly one written default,
   * and every copy of one — a trait's, carried onto each implementing type — shares it, which is
   * the grouping this question wants.
   */
  private val fillingDefaults = mutable.Set.empty[Pos]

  /** Runs `body`, refusing a default whose filling has led back to itself.
   *
   * The value-level form of the cycle is deferred where the type-level one is immediate: the
   * expression is spliced at the call and analyzed later, so the guard belongs where it is analyzed
   * rather than where it is placed.
   */
  protected def filling[T](where: Option[Pos])(body: => T): T = where match
    case Some(p) if fillingDefaults(p) =>
      err("filling this default calls something that asks for it again — a default cannot stand in " +
        "for an argument that is still being worked out")
    case Some(p) =>
      fillingDefaults += p
      try body
      finally fillingDefaults -= p
    case None => body

  /** Runs `body` in `module` with nothing imported — for the compiler's own declarations, which
   * name everything they use in full.
   */
  protected def inModule[T](module: String)(body: => T): T = inScope(Scope(module, Imports.empty))(body)

  /** A key as a diagnostic spells it — the module separator read back as a dot (`Modules.show`).
   * Every message that names a declaration by the key a table holds it under goes through here, so
   * that a reader is shown the path they would write rather than the compiler's spelling of it.
   *
   * **An overload's numbered segment comes off here**, which is the reason the suffix was made
   * numeric: a reader wrote `paint` and every declaration of it is `paint`, so a message naming the
   * second one `paint.2` would be naming something the source does not contain. Nothing else a key
   * can end with is only digits — a mangled type argument is a name, `arr3` or `c5` — so this takes
   * off overload suffixes and nothing besides. Where a message needs to tell two overloads apart it
   * shows their **signatures**, which is what a reader would use to tell them apart too.
   */
  protected def qn(key: String): String = {
    val cut = key.lastIndexOf('.')
    val bare =
      if cut > 0 && cut < key.length - 1 && key.drop(cut + 1).forall(_.isDigit) then key.take(cut)
      else key

    Modules.show(bare)
  }

  // --- scopes and unique naming --------------------------------------------------------

  /** The names the open blocks bound, innermost first, each mapped to the unique name codegen will
   * use for it and its type. Reset at every function boundary, since a body sees none of the locals
   * of whatever body reached it.
   */
  /** What a scope binds a name to: the unique name the frame holds it under, its type, and **where
   * the binding was written**.
   *
   * The third is read by nothing in compilation — `DefinitionIndex` reads it, so that an editor can
   * answer where a local came from. It lives in the binding rather than in a table of its own for a
   * reason worth keeping: a unique name is unique *within a function* and no further (`resetFunction`
   * clears the set), so a table keyed on one collides across functions and answers with whichever
   * function was compiled last. Carried here it cannot, because the entry is the scope's.
   */
  protected type Binding = (String, Type, Option[Pos])

  protected var scopes: List[mutable.LinkedHashMap[String, Binding]] = Nil

  /** Every unique name this function has handed out, which is what `freshName` asks to avoid
   * colliding with a name an outer block is still using.
   */
  protected val used = mutable.HashSet.empty[String]

  /** The unique names of locals that were bound by a `val`, so an assignment to one can be refused.
   *
   * A set of the names codegen uses rather than a flag on the scope entry, because the check has to
   * run on an already-analyzed `TLoad`, which carries the unique name and not the source one. It is
   * cleared with the rest of a function's naming state.
   */
  protected val readOnlyLocals = mutable.HashSet.empty[String]

  /** The place each `ref` name stands for (`03 § ref`), under the unique name codegen uses.
   *
   * This is the compile-time half of the binding, and the half that earns the feature. Codegen needs
   * only an address, which it takes once; every *check* needs the place, because a promise is
   * discharged by walking outward through one — so a write through a ref re-runs the invariants of
   * the structs it lies inside, and a ref into read-only storage is read-only, both by the ordinary
   * walks consulting this map when they reach the name.
   *
   * It is a map of unique names for the same reason `readOnlyLocals` is a set of them: the walks run
   * on already-analyzed nodes, which carry the unique name and not the one the program wrote.
   */
  protected val refPlaces = mutable.HashMap.empty[String, TExpr]

  /** What a live `ref` forbids while it is in scope (`03 § ref`): the places whose reassignment could
   * free the storage the ref found. `depth` is the scope nesting it was declared at, which is what
   * takes it out of consideration again when that block closes.
   */
  protected case class RefGuard(name: String, hazards: Set[String], depth: Int)

  protected var refGuards: List[RefGuard] = Nil

  // An import inside a block lasts exactly as long as the block's bindings do, so the two stacks
  // are pushed and popped together — including by the unwinding a failed statement does.
  protected def pushScope(): Unit = {
    scopes = mutable.LinkedHashMap.empty[String, Binding] :: scopes
    importStack = Imports.empty :: importStack
  }

  protected def popScope(): Unit = {
    scopes = scopes.tail
    importStack = importStack.tail
    // A ref's restriction lasts exactly as long as its name does, so closing the block that declared
    // it is what lifts it — the storage it found is nobody's concern once nothing can reach it.
    refGuards = refGuards.filter(_.depth <= scopes.length)
  }

  /** Adds what an `import` inside a block brings in to the innermost open block. */
  protected def importHere(imports: Imports): Unit = importStack = imports :: importStack.tail

  /** Puts the naming state back to what a function starts with — the locals, the unique names, and
   * the block-scoped imports that travel with them. What is per-*function* rather than per-block
   * lives in `AnalyzerBase`, which resets it alongside this.
   */
  protected def resetLocals(): Unit = {
    used.clear()
    readOnlyLocals.clear()
    refPlaces.clear()
    refGuards = Nil
    scopes = List(mutable.LinkedHashMap.empty[String, Binding])
    importStack = List(Imports.empty)
  }

  protected def freshName(base: String): String = {
    /* A backtick-quoted name may hold characters LLVM will not accept in an identifier, and a local
     * reaches the IR as `%name.addr` — so the escaping happens here, once, rather than at the
     * several emitters that build that string.
     *
     * A name the compiler made for itself is left alone, and is told by its leading separator:
     * `$parts` and `$env0` are already safe, and running them through the escape would rewrite
     * that separator and change IR that has not otherwise moved.
     */
    val safe = if base.nonEmpty && base.head == Modules.sep then base else LlvmName.escape(base)

    if !used(safe) then { used += safe; safe }
    else {
      var k = 1
      while used(s"$safe.$k") do k += 1
      val n = s"$safe.$k"
      used += n
      n
    }
  }

  /** The reserved names this compilation has already refused, so one mistake gets one diagnostic.
   *
   * A top-level `var` is both a declaration the hoisting pass registers and a binding the entry
   * point's statements later make, so without this it is answered twice — and the second answer is
   * the worse one, since a binding carries no position of its own and reports against wherever the
   * analyzer happened to be. The `Analyzer` is built once per compilation, so this needs no reset.
   */
  private val reservedRefused = mutable.Set.empty[String]

  /** Refuses a name for having the reserved shape, and remembers that it did (`ReservedNames`). */
  protected def refuseReserved(name: String, what: String): Nothing = {
    reservedRefused += name
    err(ReservedNames.refuseDeclaration(name, what))
  }

  /** Binds a name in the innermost scope.
   *
   * **Every local binding the language has comes through here** — a `var`, a `val`, a parameter, a
   * `for`'s element, a pattern's captures, a closure's parameters — which is what makes it the one
   * place the reserved shape has to be refused for all of them (`ReservedNames`). Doing it here
   * rather than at each binding form is also what covers the ones that are not written as
   * declarations at all: a lambda parameter buried in an argument list binds a name without any
   * statement saying so.
   *
   * A name the hoisting pass has already refused is abandoned rather than reported again: it said
   * so where the declaration is, with the position and the noun that declaration had, and this
   * would only repeat it from somewhere less useful.
   */
  protected def declare(name: String, ty: Type): String = {
    if ReservedNames.shaped(name) then
      if reservedRefused(name) then poisoned() else refuseReserved(name, "binding")

    val unique = freshName(name)

    // The position is the one a diagnostic raised here would carry — the statement or the function
    // header being walked, since the binding form itself has been left behind by the time this is
    // called and there is no node to ask. It is read by `DefinitionIndex` and by nothing else.
    scopes.head(name) = (unique, ty, currentPos)

    unique
  }

  /** Binds a name that may not be assigned to again — what a local `val` declares. */
  protected def declareReadOnly(name: String, ty: Type): String = {
    val unique = declare(name, ty)
    readOnlyLocals += unique
    unique
  }

  /** Binds a name to a **place** rather than to a value — what `ref` declares (`03 § ref`).
   *
   * The type is the place's, since a ref states nothing of its own, and the guard is what holds the
   * storage still for as long as the name can reach it.
   */
  protected def declareRef(name: String, place: TExpr, hazards: Set[String]): String = {
    val unique = declare(name, place.ty)

    refPlaces(unique) = place
    refGuards = RefGuard(unique, hazards, scopes.length) :: refGuards
    unique
  }

  /** The local a name means here, or `None` where no open block binds it.
   *
   * **It is also where a reference to a local is recorded** (`DefinitionIndex`), and this is the one
   * place it can be: resolution is what decides *which* binding a name means, and after it there is
   * nothing but a unique name — which is unique within a function and collides across them. What is
   * recorded is where the name is written, from `currentPos`, against where its binding was.
   *
   * A name asked about rather than read — the guards that ask whether a local exists before trying
   * something else — records one too, and that is right: a hit means the name at that place does
   * mean that binding, whichever rule went on to use it.
   */
  protected def lookupOpt(name: String): Option[(String, Type)] =
    scopes.collectFirst { case s if s.contains(name) => s(name) }.map { (unique, ty, bound) =>
      if recordingReferences then
        for at <- currentPos; where <- bound do references += Reference(at, where, name)

      (unique, ty)
    }

  protected def lookup(name: String): (String, Type) =
    lookupOpt(name).getOrElse(err(s"undefined name '$name'"))
}
