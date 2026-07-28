package io.github.edadma.sysl

import scala.collection.mutable

/** An error raised by the analyzer: an unknown name, a type mismatch, a wrong arity — any
 * rule that the structural parse cannot catch. `pos` is where in the source it was found, which
 * is absent only for a rule that fires away from any one node.
 *
 * Raising one abandons the *region* it was raised in — a statement, a function body, a
 * declaration — which is caught at the nearest recovery point so the analyzer can go on to find
 * the mistakes further down the file.
 */
case class AnalyzerError(message: String, pos: Option[Pos]) extends RuntimeException(message)

/** Raised where a value derives from something that was already reported.
 *
 * It abandons the enclosing region exactly as an error does, but records nothing: the mistake
 * was reported where it was made, and saying so again at every later use of the name would bury
 * the real diagnostic under its own consequences.
 */
case class Poisoned() extends RuntimeException

/** The shared substrate of the analyzer, mixed into the feature traits (`TypeResolution`,
 * `Literals`, `CallAnalysis`, `PatternAnalysis`) and the `Analyzer` class itself.
 *
 * It holds the mutable tables every pass reads and writes — the hoisted declarations, the
 * memoized instantiations, and the per-function naming state — plus the name-scope helpers and
 * the diagnostic sink. The recursive entry points that live elsewhere but are called from every
 * trait are declared here as abstract hooks, exactly as `Emitter` declares `genExpr` for the
 * codegen traits.
 *
 * What it deliberately does *not* hold is the reading of those tables. Asking what members a type
 * has, whether it satisfies a bound and through which implementation, and what to say when it does
 * not, is `TraitLookup` — which extends this and which everything else reaches through.
 */
trait AnalyzerBase {

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

  /** The names the prelude declares, which are in scope everywhere with no import (`13 §8`). They
   * are keyed under the root module like any other rootless declaration, and this is what tells
   * them from a *program's* root-module declarations — which are a module's like any other, and so
   * are not visible from a named module that did not name them.
   */
  protected val preludeNames = mutable.HashSet.empty[String]

  /** The key a name written in the current module resolves to, or `None` where nothing of that
   * name is declared anywhere it may be read from.
   *
   * `declared` is the table being asked — a type's, a function's, a variant's — because the same
   * spelling may name a type in one module and a function in another, and which one is in scope is
   * a question that can only be answered against the table the use site is looking in.
   *
   * The order is `13 §3`'s: **this module**, then what the file (or the block) has **imported**,
   * then the **prelude**, and a **fully-qualified path** reaches anything at all. A sibling
   * module's names are deliberately not in it — a module earns visibility by being named or
   * imported (`13 §8`), and the root module has no name, so its declarations are its own files' to
   * use.
   */
  protected def resolveName(written: String)(declared: String => Boolean): Option[String] = {
    val dot = written.lastIndexOf('.')

    // A name carrying the module separator is one the compiler built rather than one a file wrote
    // — a synthesized `Self` reference, a default's bound on its own trait — and is already the key
    // it names. Nothing in source can be spelled this way, so passing it through is unambiguous.
    val key =
      if written.indexOf(Modules.sep.toInt) >= 0 then Option.when(declared(written))(written).map(reachable)
      else if dot < 0 then
        val own = Modules.qualify(currentModule, written)

        if declared(own) && visible(own) then Some(own)
        else
          // A declaration this file may not name is not a candidate, so the search goes on rather
          // than stopping at it: a file that imported a `width` said which one it meant, and a
          // sibling file's private helper is not an answer to that. Only where nothing else answers
          // at all is the restriction worth reporting — at which point it is the whole story, and a
          // better one than an undefined name.
          importedName(written)(declared)
            .orElse(Option.when(preludeNames(written) && declared(written))(written))
            .orElse(Option.when(declared(own))(reachable(own)))
      else
        val module = modulePath(written.take(dot))
        val name   = Modules.qualify(module, written.drop(dot + 1))

        Option.when(moduleNames(module) && declared(name))(name).map(reachable)

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
    if written.indexOf(Modules.sep.toInt) < 0 then key.foreach(k => dependsOn(Modules.moduleOf(k)))
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
   * A module does not depend on itself, and nothing depends on the **root** module: the prelude
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

  // --- visibility -----------------------------------------------------------------------

  /** Where a **restricted** declaration may be named from (`13 §2`): the file that wrote it, and —
   * for a scoped-private one — the module subtree its `private[M]` widened to.
   *
   * A public declaration has no entry at all, which is what makes the unmarked default cost a
   * lookup that finds nothing rather than an entry per declaration in every program.
   */
  protected case class Access(file: Option[Source], subtree: Option[String])

  protected val declAccess = mutable.HashMap.empty[String, Access]

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

  /** A resolved key, or a diagnostic where what it names is not visible here.
   *
   * A name the current module declares but may not use is **reported** rather than passed over, so
   * that resolution does not quietly fall through to an import or the prelude and answer with
   * something else. The name was found; what is wrong is that it is not for this file to write, and
   * that is what a reader needs told.
   */
  protected def reachable(key: String): String = {
    if !visible(key) then err(s"'${qn(key)}' is ${restriction(key)}")
    key
  }

  /** Why a declaration cannot be named here, as a diagnostic says it. */
  protected def restriction(key: String): String = declAccess.get(key) match
    case Some(Access(Some(f), None)) => s"private to '${f.name}', the file that declares it"
    case Some(Access(_, Some(m)))    => s"private to module '$m'"
    case _                           => "private"

  /** Whether `name` is a module, or the first segment of one — everything a dotted reference could
   * read as the start of a module path.
   */
  protected def namesModule(name: String): Boolean =
    moduleNames(name) || moduleNames.exists(_.startsWith(s"$name."))

  /** A written module path with its leading segment read through the imports, so that the `fs` of
   * `import std.fs` names `std.fs` wherever a path can be written.
   *
   * A path whose head already begins a real module is left alone, and an import may not bind a
   * name that does (`checkImportName`) — so the two can never both apply, and which is tried first
   * decides nothing.
   */
  protected def modulePath(written: String): String = {
    val head = written.takeWhile(_ != '.')

    if namesModule(head) then written
    else importedModule(head).map(_ + written.drop(head.length)).getOrElse(written)
  }

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
  protected def importedName(written: String)(declared: String => Boolean): Option[String] =
    searchImports { imports =>
      imports.names.get(written).filter(declared).orElse {
        imports.wildcards.map(Modules.qualify(_, written)).filter(k => declared(k) && visible(k)).distinct match
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

  /** The key a written **type** name resolves to: a struct's, an enum's, or a constrained subtype's. */
  protected def typeKey(written: String): Option[String] =
    resolveName(written)(n => structDecls.contains(n) || enumDecls.contains(n) || constrainedDecls.contains(n))

  /** The key a written **trait** name resolves to. */
  protected def traitKey(written: String): Option[String] = resolveName(written)(traitDecls.contains)

  /** The key a written **function** name resolves to. */
  protected def funcKey(written: String): Option[String] = resolveName(written)(funcDecls.contains)

  /** The key a written **enum variant** name resolves to. A variant is reachable unqualified — a
   * bare `Circle(5)` — so it is a name of the module its enum was declared in, which is why two
   * modules may each have a `Circle` where one program could not have two.
   */
  protected def variantKey(written: String): Option[String] = resolveName(written)(variantOwner.contains)

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
   * — the prelude's, or one the compiler synthesized — imports nothing and is read in the module its
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

  /** Runs `body` in `module` with nothing imported — for the compiler's own declarations, which
   * name everything they use in full.
   */
  protected def inModule[T](module: String)(body: => T): T = inScope(Scope(module, Imports.empty))(body)

  /** A key as a diagnostic spells it — the module separator read back as a dot (`Modules.show`).
   * Every message that names a declaration by the key a table holds it under goes through here, so
   * that a reader is shown the path they would write rather than the compiler's spelling of it.
   */
  protected def qn(key: String): String = Modules.show(key)

  protected val structDecls = mutable.LinkedHashMap.empty[String, StructDecl]
  protected val enumDecls   = mutable.LinkedHashMap.empty[String, EnumDecl]
  protected val funcDecls   = mutable.LinkedHashMap.empty[String, FuncDecl]

  /** Declared constrained subtypes by key (`16`). Each `type Name = Base …` is registered here; the
   * resolved `Type.Constrained` it stands for is built and cached the first time the name is used.
   */
  protected val constrainedDecls = mutable.LinkedHashMap.empty[String, TypeDecl]

  /** The resolved constrained subtype for a key, built once and reused so every reference to a name
   * is the same `Type.Constrained` — and so its bounds are validated a single time.
   */
  protected val constrainedInsts = mutable.LinkedHashMap.empty[String, Type.Constrained]

  /** The function key a `where` predicate is synthesised under, derived from the subtype's key. The
   * `$` keeps it clear of any name a program could write, so it never collides with a user function.
   */
  protected def predKey(typeKey: String): String = s"$typeKey$$pred"

  /** The function key a struct's `invariant` clauses are synthesised under — the conjunction of them
   * as one `bool` function of the struct's fields. `$` keeps it clear of any user name, as `predKey`
   * does for a `where` predicate.
   */
  protected def invKey(typeKey: String): String = s"$typeKey$$inv"

  /** Declared constants by key (`13 §7`). A constant is folded into each use and has no storage, so
   * this table is read by the analyzer and never by codegen — there is nothing downstream to emit.
   */
  protected val constDecls = mutable.LinkedHashMap.empty[String, ConstDecl]

  /** Declared module-level `val`s by key (`13 §7`). Unlike a constant, this one reaches codegen: it
   * is storage, and every use of it is a read through an address rather than a copy of a literal.
   */
  protected val valDecls = mutable.LinkedHashMap.empty[String, ValDecl]

  /** The unique names of locals that were bound by a `val`, so an assignment to one can be refused.
   *
   * A set of the names codegen uses rather than a flag on the scope entry, because the check has to
   * run on an already-analyzed `TLoad`, which carries the unique name and not the source one. It is
   * cleared with the rest of a function's naming state.
   */
  protected val readOnlyLocals = mutable.HashSet.empty[String]

  /** Declared traits by name. A trait is a set of method signatures a type opts into through an
   * explicit `impl`; nothing conforms structurally.
   */
  protected val traitDecls = mutable.LinkedHashMap.empty[String, TraitDecl]

  /** Every `impl Trait for Type` as written, in source order. Kept unresolved because the type it
   * names may be declared further down the file; `hoistImpl` resolves each one after every type is
   * registered, and that is where `traitImpls` gets filled.
   */
  protected val implDecls = mutable.ListBuffer.empty[(Scope, ImplDecl)]

  /** The composed types written out in full that implement a trait, keyed by (trait name, the
   * arguments the block **wrote** for it, the **shape** each one has). It is what a shape-matched
   * `impl` consults to find that the thing it would cover has already been covered one type at a
   * time.
   *
   * `impl Display for []int` and `impl[T] Display for []T` are two implementations of `Display` for
   * a `[]int`, and sysl has no rule that picks between two — so whichever is written second is
   * refused, and this is how the one written first is found however the file ordered them.
   *
   * The trait's arguments are deliberately **not** in the key, though they are what lets one type
   * keep several implementations elsewhere. A shape's members and a written-out type's are filed
   * under two different owner keys, and a member lookup takes the type's own key or the shape's and
   * never both — so two implementations split across that boundary would leave one of them
   * unreachable by name whatever their arguments were. What makes several implementations work at
   * all is that they share a namespace to be told apart in, and here they do not.
   */
  protected val writtenShapes = mutable.LinkedHashMap.empty[(String, String), String]

  /** The members a composed type written out in full was given, keyed by (its shape, the member's
   * name) and holding the type that was written. A shape-matched block may not give a member of the
   * same name to every type of that shape.
   *
   * A type's members are one namespace whatever trait brought them (`08`), which is why two traits
   * declaring a `show` cannot both be implemented for one type. A shape covers types that may
   * already have a member of the name, so the same rule reaches across the two.
   */
  protected val composedMembers = mutable.LinkedHashMap.empty[(String, String), String]

  /** What `Self` means inside a member of a *generic* type, as the reference it was written from —
   * `Box[T]` for the members of `Box`, whichever declaration form brought them — **and the terms it
   * was written in**.
   *
   * A concrete type's members have their `Self` resolved once, at hoist, into `memberSelf`. A
   * generic type's cannot: `Box[T]` is not a type until a call fixes `T`. So the *reference* is
   * kept, and resolving it under the substitution an instantiation supplies is what gives `Self` its
   * meaning there — the same answer, one step later.
   *
   * The scope travels with it because the reference is the one part of an **inherited default** that
   * the trait did not write: the copy is read in the trait's terms (`Hoisting.defaultHome`), and the
   * subject came from the `impl` block, which may be in another module entirely. For every other
   * member the two are the same scope and carrying it changes nothing.
   */
  protected val genericSelf = mutable.LinkedHashMap.empty[String, (TypeRef, Scope)]

  /** What the **trait's** own type parameters mean inside a member of a generic `impl`, by the name
   * the member was lowered to.
   *
   * `impl[U] From[int] for Box[U]` supplies a member whose signature may be written in the trait's
   * `T`, and a default it inherits certainly is. `U` is fixed per instantiation and `T` is fixed by
   * the block, so the two answers come from different places and only this one has anywhere to wait.
   */
  protected val genericOuter = mutable.LinkedHashMap.empty[String, Map[String, Type]]

  /** The members of a generic `impl`, each as the generic function it was lowered to, for the
   * definition-time pass of `14 §4` to walk.
   *
   * A member of a generic *type* is not checked there and cannot be: it inherits the type's
   * parameters, which carry no bounds, so holding it to them would be holding it to nothing. A
   * generic `impl` is the case that changed — the block declares its own parameters and may bound
   * them — so its members are checked once, at the definition, exactly as a bounded generic function
   * is.
   */
  protected val abstractMembers = mutable.ListBuffer.empty[FuncDecl]

  /** Generic `impl` members whose body the definition-time pass reported, by the name each was
   * lowered to. The instantiations made for concrete type arguments are dropped rather than
   * analyzed, so one mistake stays one diagnostic.
   */
  protected val brokenMembers = mutable.HashSet.empty[String]

  /** The method tables the program's trait objects dispatch through, keyed by the global each is
   * emitted under and registered the first time an erasure needs one. A program that never erases a
   * type carries none.
   */
  protected val vtables = mutable.LinkedHashMap.empty[String, TVtable]

  /** A type's inherent members, keyed by (type name, member name). Methods, properties, and
   * associated functions all live here; each is also lowered to an ordinary function under the
   * mangled name `Type.member`, so calling one is a call and codegen needs no method concept.
   */
  protected val memberDecls = mutable.LinkedHashMap.empty[(String, String), MethodDecl]

  /** Every member a type has under one **source** name, where more than one implementation of one
   * trait gave it one — keyed by (type name, the name as written) and holding the names those
   * members are actually filed under, in source order.
   *
   * A type with one `mul` has no entry here and is reached by the name it was written with, which is
   * what keeps the ordinary case a lookup. A `Complex` that is both `Mul[Complex]` and `Mul[real]`
   * has two, and a call naming `mul` is answered by the one whose parameters accept the arguments —
   * a resolution the call fully determines, not an overload set to search (`08 § One name, one
   * member — and what a second implementation does to that`).
   */
  protected val memberAlts = mutable.LinkedHashMap.empty[(String, String), List[String]]

  /** Which trait default a member was copied from, keyed by the name the copy was lowered to.
   *
   * A default is materialized per implementing type (`02`), so one source body becomes several
   * functions. This says which — so a default the definition-time pass already reported is not
   * reported again by every copy of it.
   */
  protected val defaultOrigin = mutable.LinkedHashMap.empty[String, String]

  /** Trait defaults whose body the definition-time pass reported, by the `Trait.method` name each
   * was checked under. The copies made for the implementing types are dropped rather than analyzed.
   */
  protected val brokenDefaults = mutable.HashSet.empty[String]

  /** A member of a *generic* type, lowered to a function that is itself generic over the type's
   * parameters and keyed by (type name, member name). Unlike a member of a concrete type — which
   * is hoisted eagerly into `funcInsts` — a generic member is instantiated on demand at each call
   * site, once the receiver's concrete type arguments are known.
   */
  protected val genericMembers = mutable.LinkedHashMap.empty[(String, String), FuncDecl]

  /** What `Self` means inside one lowered member, keyed by the name it was lowered to.
   *
   * A member of a concrete type — its own, or one an `impl` gave it — may write `Self` for the type
   * it belongs to, in its signature and in its body alike (`14 §1`). The binding is recorded at
   * hoist and folded into the body's substitution, so the body resolves `Self` exactly as it
   * resolves a type parameter: through the one map that already answers that question.
   */
  protected val memberSelf = mutable.LinkedHashMap.empty[String, Map[String, Type]]

  /** Instantiated types, keyed by their display name (`Point`, `Option[int]`) and held in
   * dependency order — a type is inserted only after the types it contains.
   */
  protected val structInsts = mutable.LinkedHashMap.empty[String, Type.Struct]
  protected val enumInsts   = mutable.LinkedHashMap.empty[String, Type.Enum]

  /** Instantiations whose fields are still being resolved, each recorded with the indirection
   * depth at which it was entered. A type that reaches itself finds its own entry here; the
   * depth is what decides whether that is a legal cycle (see `cycleCheck`).
   */
  protected val resolving = mutable.LinkedHashMap.empty[String, Int]

  /** The same instantiations, by display name, so a recursive occurrence resolves to the object
   * whose fields are still being filled in rather than starting a second one.
   */
  protected val inProgress = mutable.LinkedHashMap.empty[String, Type]

  /** How many `*T` / `&T` wrappers the resolver is currently inside. */
  protected var indirection = 0

  /** Instantiated function signatures, keyed by the name codegen will emit. */
  protected val funcInsts = mutable.LinkedHashMap.empty[String, (List[(String, Type)], Type)]

  /** Every `extern` the program declares. A call to one resolves exactly as a call to a sysl
   * function does — the signature is in `funcInsts` like any other — so this exists only to say
   * *which* names have no body: codegen declares them instead of defining them, and the escape
   * analysis assumes the worst of them.
   */
  protected val externDecls = mutable.LinkedHashMap.empty[String, ExternDecl]

  /** The externs something in the program actually calls, in the order they were first reached.
   * An unused one is not declared in the output at all.
   */
  protected val externsUsed = mutable.LinkedHashSet.empty[String]

  /** Every function name something has called, which is what decides whether a **prelude** function
   * is worth analyzing and emitting at all: the printing surface lives there now, and a program that
   * never prints should carry none of it.
   */
  protected val funcsUsed = mutable.LinkedHashSet.empty[String]

  /** Instantiations whose body has not been analyzed yet. Queued rather than analyzed inline
   * so an instantiation discovered mid-function does not disturb the enclosing context.
   */
  protected val pending = mutable.Queue.empty[(String, FuncDecl, Map[String, Type])]

  /** Every enum variant name maps to its declaring enum, so a bare `Circle(5)` or `Empty`
   * resolves without qualification. Variant names are therefore unique across all enums.
   */
  protected val variantOwner = mutable.LinkedHashMap.empty[String, String]

  // Per-function state, reset at each function boundary.
  protected var scopes: List[mutable.LinkedHashMap[String, (String, Type)]] = Nil
  protected val used                                                        = mutable.HashSet.empty[String]
  protected var retTy: Type                                                 = Type.Unit
  protected var tsubst: Map[String, Type]                                   = Map.empty

  /** Whether the function being analyzed declared a `...`, which is what `va_start` needs: there is
   * no tail to start walking in a function that does not have one. C's rule exactly.
   */
  protected var variadicFn: Boolean = false

  /** The return type while analyzing an `ensure` postcondition, so `result` resolves to it.
   * `None` everywhere else, which is what makes `result` an ordinary identifier outside a
   * postcondition.
   */
  protected var ensureResultTy: Option[Type] = None

  /** The entry-time snapshots an `ensure` has asked for so far, present only while a postcondition
   * is being analyzed. Each `old(e)` appends its typed expression and reads back its position, so
   * codegen can capture them all at entry. `None` outside an `ensure`, which is what keeps `old`
   * an ordinary name everywhere else.
   */
  protected var oldBuf: Option[mutable.ListBuffer[TExpr]] = None

  /** The enclosing loops, innermost first, so a `break`/`continue` finds the one it leaves and a
   * `break value` records its type against that loop's result. `expected` is the type the loop's
   * context wants, pushed down so a `break`/`else` value boxes to `&T` on its own. `label` is the
   * loop's `'name`, if it has one, which a labeled `break`/`continue` resolves against.
   */
  protected class LoopCtx(val expected: Option[Type], val label: Option[String]):
    val breakTys = mutable.ListBuffer.empty[Type]
  protected var loops: List[LoopCtx] = Nil

  /** Where the analyzer currently is. Every recursive entry point (a statement, an expression, a
   * type reference, a declaration) sets this to the node it is about to work on and restores it
   * afterwards, so an error raised *after* the children are done still points at the parent that
   * raised it rather than at whatever was visited last.
   */
  protected var currentPos: Option[Pos] = None

  /** Runs `body` with diagnostics pointing at `p`, restoring the previous position after. A node
   * with no position of its own leaves the enclosing one in place, which is what keeps a
   * synthesized node's errors pointing somewhere useful.
   */
  protected def at[T](p: Option[Pos])(body: => T): T =
    if p.isEmpty then body
    else {
      val saved = currentPos

      currentPos = p
      try body
      finally currentPos = saved
    }

  protected def err(msg: String): Nothing = throw AnalyzerError(msg, currentPos)

  /** Abandons the current region without reporting, because whatever led here already did. */
  protected def poisoned(): Nothing = throw Poisoned()

  /** Whether the analyzer is running the definition-time pass of `14 §4` — a generic body walked
   * once with its type parameters standing in for themselves.
   *
   * The pass exists to report what a body does that its bounds do not license, and those
   * diagnostics go through `boundErr` — a missing bound on a method call or on an operator alike.
   * Every *other* complaint the walk raises is dropped while it is set, because the abstract pass is
   * additive: a mistake in the concrete part of a generic body is found where it always was, at each
   * instantiation, and reporting it from here as well would report it against a body no call site
   * may ever ask for.
   *
   * Every use a bound could license now reports through `boundErr`, rendering included: `Display`
   * and its `Writer` sink are built, so a `print` of a parameter names the bound that would allow
   * it rather than being dropped for want of one to name.
   */
  protected var abstractPass: Boolean = false

  /** Reports something a type parameter's bounds do not license, and abandons the region.
   *
   * It records the diagnostic itself rather than raising an `AnalyzerError`, because the abstract
   * pass drops those: this is the one kind of complaint that pass is for, and it survives whatever
   * recovery region it was raised inside.
   *
   * Rendering a parameter goes through here too, now that `Display` exists to license it: a body
   * that prints a `T` is told to write `T: Display` rather than having the complaint dropped for
   * want of a bound to name.
   */
  protected def boundErr(msg: String): Nothing = {
    found += ((msg, currentPos))
    poisoned()
  }

  /** Runs `body` with whatever it complains about recorded, rather than dropped as the abstract
   * pass otherwise drops a complaint.
   *
   * It is for the checks that only *exist* in that pass — the arity and argument types of a call on
   * a type parameter, checked against the trait's signature. Nothing else will ever check them: an
   * instantiation resolves the same call against a concrete implementation instead, so a mistake
   * here is caught at the definition or nowhere.
   */
  protected def reported[T](body: => T): T =
    try body
    catch
      case AnalyzerError(msg, pos) =>
        found += ((msg, pos))
        poisoned()

  /** `recover`, for a region whose complaint must survive the abstract pass rather than be dropped
   * by it.
   *
   * It is for the checks that are about a **declaration** rather than about a body: what a bound
   * names, and what the trait it names asks of the arguments it was applied to. Those are wrong
   * wherever they are read, so reading them during a walk that discards its tree is no reason to
   * stay quiet about them — and nothing else will ever read them if no call site turns up.
   */
  protected def recorded[T](fallback: => T)(body: => T): T =
    try body
    catch
      case AnalyzerError(msg, pos) =>
        found += ((msg, pos))
        fallback
      case Poisoned() => fallback

  /** Whether a value of type `got` genuinely cannot stand where a `want` was asked for — an
   * argument against a parameter, a returned value against a declared result.
   *
   * Two types agree for reasons of their own. A type that could not be worked out agrees with
   * everything, in either direction: the mistake that produced it has been reported, and a second
   * complaint about what it fails to match is noise about a consequence rather than a cause. A
   * `never` agrees with everything in *one* direction only — it may stand anywhere, because control
   * does not reach the place the value would have been used, but nothing may stand for it.
   */
  protected def disagree(got: Type, want: Type): Boolean =
    Type.repr(got) != Type.repr(want) && got != Type.Unknown && want != Type.Unknown && got != Type.Never

  /** The one type two alternatives meet at — the branches of an `if`, the arms of a `match`, a
   * loop's `break` values and its `else` — or `None` when they have no common type.
   *
   * The only interesting case is `never`: an alternative that does not finish constrains nothing,
   * so it takes the other side's type. Everything else must already agree, since sysl has no
   * subtyping among concrete types to widen towards.
   */
  protected def join(a: Type, b: Type): Option[Type] =
    if a == b then Some(a)
    else if a == Type.Never then Some(b)
    else if b == Type.Never then Some(a)
    // Two transparent-compatible types — a subtype and its base, or two subtypes over one base —
    // meet at that base, since either may stand where the base is asked for.
    else if Type.repr(a) == Type.repr(b) then Some(Type.repr(a))
    else None

  protected def show(t: Type): String = Type.show(t)

  /** The type parameters of a nominal type — a struct or an enum — by the name it was declared
   * under. Empty both for a non-generic type and for a name that declares no type at all, which is
   * what lets a caller ask "is this generic" without first knowing which kind it is.
   */
  protected def nominalTparams(base: String): List[String] =
    structDecls.get(base).map(_.tparams).orElse(enumDecls.get(base).map(_.tparams)).getOrElse(Nil)

  /** What a nominal type asks of its own parameters, by parameter name. Empty for a type that asks
   * nothing, which is every type that takes no parameters and most of those that do.
   */
  protected def nominalBounds(base: String): Map[String, List[BoundRef]] =
    structDecls.get(base).map(_.bounds).orElse(enumDecls.get(base).map(_.bounds)).getOrElse(Map.empty)

  // --- collecting errors ----------------------------------------------------------------

  /** Every error found so far, in the order the analyzer found them. Duplicates are dropped on
   * the way in: the same complaint at the same place is one mistake however many times a pass
   * arrives at it — a generic function instantiated three times has one bad line, not three.
   */
  private val found = mutable.LinkedHashSet.empty[(String, Option[Pos])]

  /** How many distinct mistakes have been found, which is what tells a walk that reported something
   * from one that came through clean.
   */
  protected def diagnosticCount: Int = found.size

  /** The errors, rendered and ordered by where they are, so reading them top to bottom is
   * reading the file top to bottom. A diagnostic with no position sorts last, since there is
   * nowhere to file it.
   */
  protected def diagnostics: List[String] =
    found.toList
      .sortBy { case (_, pos) =>
        (pos.isEmpty, pos.map(_.source.name).getOrElse(""), pos.map(_.line).getOrElse(0), pos.map(_.col).getOrElse(0))
      }
      .map { case (msg, pos) => Diagnostic.render(msg, pos) }

  /** Runs `body`, and if it abandons its region, records the error and yields `fallback` so the
   * walk carries on to whatever comes after. A `Poisoned` region yields the same fallback and
   * records nothing.
   */
  protected def recover[T](fallback: => T)(body: => T): T =
    try body
    catch
      case AnalyzerError(msg, pos) =>
        if !abstractPass then found += ((msg, pos))
        fallback
      case Poisoned() => fallback

  /** Runs `body` and then restores every table the emitted program is built from.
   *
   * The definition-time pass of `14 §4` walks a generic body exactly as an ordinary one is walked,
   * so it registers instantiations exactly as an ordinary one does — a `Box[T]`, a call to another
   * generic function, a prelude renderer reached by a `print`. None of those is a real
   * instantiation: `T` is not a type anything can be laid out at, and nothing at run time reaches
   * them. Dropping what the pass registered is what keeps a diagnostics-only walk from putting a
   * type parameter into the emitted module.
   */
  protected def sandboxed[T](body: => T): T = {
    val structs = structInsts.toList
    val enums   = enumInsts.toList
    val funcs   = funcInsts.toList
    val tables  = vtables.toList
    val reached = funcsUsed.toList
    val externs = externsUsed.toList
    val queued  = pending.toList

    try body
    finally
      restore(structInsts, structs)
      restore(enumInsts, enums)
      restore(funcInsts, funcs)
      restore(vtables, tables)
      funcsUsed.clear();   funcsUsed ++= reached
      externsUsed.clear(); externsUsed ++= externs
      pending.clear();     pending ++= queued
  }

  private def restore[K, V](table: mutable.LinkedHashMap[K, V], saved: List[(K, V)]): Unit = {
    table.clear()
    table ++= saved
  }

  /** The same, for a region that has no useful value to stand in for a failure — a function
   * whose body did not analyze is simply left out of the program.
   */
  protected def recoverOpt[T](body: => T): Option[T] = recover(None)(Some(body))

  // --- scopes and unique naming --------------------------------------------------------

  // An import inside a block lasts exactly as long as the block's bindings do, so the two stacks
  // are pushed and popped together — including by the unwinding a failed statement does.
  protected def pushScope(): Unit = {
    scopes = mutable.LinkedHashMap.empty[String, (String, Type)] :: scopes
    importStack = Imports.empty :: importStack
  }

  protected def popScope(): Unit = {
    scopes = scopes.tail
    importStack = importStack.tail
  }

  /** Adds what an `import` inside a block brings in to the innermost open block. */
  protected def importHere(imports: Imports): Unit = importStack = imports :: importStack.tail

  protected def resetFunction(): Unit = {
    used.clear()
    readOnlyLocals.clear()
    scopes = List(mutable.LinkedHashMap.empty[String, (String, Type)])
    importStack = List(Imports.empty)
    loops = Nil
    ensureResultTy = None
    oldBuf = None
  }

  protected def freshName(base: String): String =
    if !used(base) then { used += base; base }
    else {
      var k = 1
      while used(s"$base.$k") do k += 1
      val n = s"$base.$k"
      used += n
      n
    }

  protected def declare(name: String, ty: Type): String = {
    val unique = freshName(name)
    scopes.head(name) = (unique, ty)
    unique
  }

  /** Binds a name that may not be assigned to again — what a local `val` declares. */
  protected def declareReadOnly(name: String, ty: Type): String = {
    val unique = declare(name, ty)
    readOnlyLocals += unique
    unique
  }

  protected def lookupOpt(name: String): Option[(String, Type)] =
    scopes.collectFirst { case s if s.contains(name) => s(name) }

  protected def lookup(name: String): (String, Type) =
    lookupOpt(name).getOrElse(err(s"undefined name '$name'"))

  // --- hooks provided by the Analyzer class --------------------------------------------
  //
  // These recursive entry points live in the class (statements, expressions, places) but are
  // called across the feature traits, so they are declared abstract here for the traits to see.

  protected def resolveBound(b: BoundRef, subst: Map[String, Type]): Type.Bound
  protected def selfBinding(t: Type): Map[String, Type]
  protected def substParams(t: Type, subst: Map[String, Type]): Type
  protected def withDefaults(
      key: String,
      tparams: List[String],
      tdefaults: Map[String, TypeRef],
      targs: List[Type],
      self: Map[String, Type],
  ): List[Type]
  protected def analyzeExpr(expr: Expr, expected: Option[Type] = None, discarded: Boolean = false): TExpr
  protected def analyzeBool(e: Expr): TExpr
  protected def analyzePlace(target: Expr, what: String): TExpr
  protected def analyzeBlockBody(stmts: List[Stmt], expected: Option[Type], discarded: Boolean = false): TBlock
  protected def coerce(t: TExpr, expected: Type): TExpr
  protected def autoDeref(t: TExpr): TExpr
  protected def isPlace(t: TExpr): Boolean
  protected def instantiateFunc(f: FuncDecl, targs: List[Type]): String
}
