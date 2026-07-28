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
 * the diagnostic sink. The recursive entry points that live in the `Analyzer` class but are
 * called from the traits are declared here as abstract hooks, exactly as `Emitter` declares
 * `genExpr` for the codegen traits.
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

  /** Declared constrained subtypes by key (`03`). Each `type Name = Base …` is registered here; the
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

  /** Every `impl Trait for Type`, keyed by (trait name, the implementing type's **owner key**). The
   * key catches a duplicate implementation, and it is what a trait bound consults to decide whether
   * a type conforms.
   *
   * Keying by the owner key rather than by the name as written is what makes `impl Show for int` and
   * `impl Show for i32` the one implementation they are — the same type reached by two spellings.
   *
   * Keying by the trait's **name** rather than by the arguments it was applied to is the decision a
   * generic trait forced. A trait's members become the type's members, and a type's members are one
   * namespace (`08`), so a second implementation of one trait — however different its arguments —
   * would give the type two members of each name with no rule for choosing between them. One per
   * (trait, type) is therefore the rule, and `implFor` is where the arguments that one supplies are
   * recorded.
   */
  protected val traitImpls = mutable.LinkedHashMap.empty[(String, String), ImplDecl]

  /** Which promise each implementation actually makes — the trait at the arguments the block wrote
   * for it — keyed exactly as `traitImpls` is.
   *
   * `impl Sink[int] for Adder` files an `Adder` under `Sink`, and this is what says the `Sink` it
   * implements is `Sink[int]`: a bound asking for `Sink[string]` is not met by it, and the
   * diagnostic can say what the type does implement instead of only what it does not.
   */
  protected val implFor = mutable.LinkedHashMap.empty[(String, String), String]

  /** What a **generic** `impl` asks of the type arguments its subject is applied to — the block's
   * own parameters in the subject's argument order, and the bounds it wrote on them — keyed exactly
   * as `traitImpls` is.
   *
   * `impl[T: Show] Show for Box[T]` is **conditional conformance**: a `Box` implements `Show` when
   * its element does, and this is the condition. The bounds are kept as written rather than resolved
   * because one may name another of the block's parameters, and only the arguments a particular
   * `Box` was made with say what that means. An unconditional `impl` — generic or not — has no
   * entry, which is what makes the ordinary case a lookup that asks nothing further.
   */
  protected val implBounds =
    mutable.LinkedHashMap.empty[(String, String), (List[String], Map[String, List[BoundRef]])]

  /** Whether an implementation of `tr.name` supplies exactly the promise `tr` asks for. Trivially
   * true for a trait that takes no arguments, which is why nothing outside a generic trait ever
   * has to think about it.
   */
  protected def suppliesBound(tr: Type.Bound, key: String): Boolean =
    implFor.get((tr.name, key)).forall(_ == tr.key)

  /** The composed types written out in full that implement a trait, keyed by (trait name, the
   * **shape** each one has). It is what a shape-matched `impl` consults to find that the thing it
   * would cover has already been covered one type at a time.
   *
   * `impl Display for []int` and `impl[T] Display for []T` are two implementations of `Display` for
   * a `[]int`, and sysl has no rule that picks between two — so whichever is written second is
   * refused, and this is how the one written first is found however the file ordered them.
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

  /** Whether the `impl` blocks have all been registered, which is what makes a bound answerable.
   *
   * A type's bounds are checked wherever it is applied, and the earliest applications — a function's
   * declared parameters, a field of another type — are resolved while the file is still being
   * hoisted. Asking then would report a `Point` for not implementing a trait it implements six lines
   * further down, so the question is held until every `impl` is in.
   */
  protected var implsHoisted: Boolean = false

  /** Type applications whose bounds could not be answered where they were written, each with the
   * position to report it against. Drained once, as soon as the answer is available.
   */
  protected val boundChecks =
    mutable.ListBuffer.empty[(String, List[String], Map[String, List[BoundRef]], List[Type], Option[Pos])]

  /** `impl` blocks whose trait requires other traits, each with the type it was written for and the
   * position to report against. Held for the same reason a bound is: the implementation that
   * supplies a required trait may be written below the one that needs it.
   */
  protected val superChecks = mutable.ListBuffer.empty[(String, String, Type.Bound, Type, Option[Pos])]

  /** Checks the arguments a *type* was applied to against what it asks of its parameters, now if
   * that can be answered and after hoisting if it cannot.
   */
  protected def checkTypeBounds(name: String, tparams: List[String], targs: List[Type]): Unit =
    deferredBounds(name, tparams, nominalBounds(name), targs)

  /** The same, for any generic declaration applied to arguments while the file is still being
   * hoisted — a type, or a **trait**, which is applied in a bound, in an `impl`, and in a trait
   * object alike.
   */
  protected def deferredBounds(
      what: String,
      tparams: List[String],
      bounds: Map[String, List[BoundRef]],
      targs: List[Type],
  ): Unit =
    if bounds.nonEmpty && tparams.length == targs.length then
      if implsHoisted then checkParamBounds(what, tparams, bounds, targs)
      else boundChecks += ((what, tparams, bounds, targs, currentPos))

  /** Whether the type arguments a generic declaration was applied to implement what it asked of its
   * parameters — the one rule, wherever the parameters came from: a function's, an `impl` block's,
   * or a type's own.
   *
   * An argument that could not be worked out is passed over. The mistake that produced it has been
   * reported, and a bound it fails to meet is a consequence of that rather than a second mistake.
   */
  protected def checkParamBounds(
      what: String,
      tparams: List[String],
      bounds: Map[String, List[BoundRef]],
      targs: List[Type],
  ): Unit =
    if bounds.nonEmpty then
      val subst = tparams.zip(targs).toMap

      for (tp, traits) <- bounds; ref <- traits do
        subst.get(tp) match
          case Some(Type.Unknown) =>
          case Some(arg) =>
            // The bound is resolved under the very arguments being checked, since one may name
            // another of the parameters: `[T: From[U], U]` asks a different thing of `T` at every
            // call, and `U`'s argument is what says which.
            val tr = resolveBound(ref, subst)

            arg match
              // A type parameter standing in for itself, during the definition-time pass. It is not
              // a type anything has an `impl` for, so what it can promise is exactly what its own
              // bounds promise — those traits and whatever they require: a bound is satisfied by a
              // bound.
              case a: Type.Abstract =>
                if !satisfies(tr, a) then
                  boundErr(s"'$what' requires its type parameter '$tp' to implement '${tr.show}', " +
                    s"but '${a.name}' is not bounded by it")
              case concrete =>
                if !satisfies(tr, concrete) then
                  // A type an implementation covers is told what that implementation asked of it, so
                  // the reader is sent one step in — to the argument that fails — rather than to a
                  // block that is already written.
                  val why = unmetBound(tr, concrete).fold("")(reason => s" — $reason")

                  err(s"'$what' requires its type parameter '$tp' to implement '${tr.show}', " +
                    s"but ${show(concrete)} does not$why")
          case None =>

  /** Where a type's members are registered: the key they are filed under, and the type arguments a
   * generic type was instantiated with.
   *
   * **Every** type has one. A struct or an enum is filed under the name it was declared with; every
   * other type under the name a diagnostic gives it, which is canonical — one name per type, and the
   * same one whichever alias reached it (`int` and `i32` are one key, not two). That is what lets
   * `5.show()` resolve exactly as `p.show()` does, with no separate machinery for the built-ins.
   */
  protected def memberOwner(t: Type): (String, List[Type]) = t match
    case n: Type.Named => (n.base, n.targs)
    case other         => (Type.show(other), Nil)

  /** The key alone — what an `impl` is filed under, and what a trait bound looks up. */
  protected def ownerKey(t: Type): String = memberOwner(t)._1

  /** The **shape** of a composed type, where it has one: the key a block matching every type of that
   * shape is filed under, and the type arguments this particular one matched at.
   *
   * A composed type is filed under the whole of itself — `[]int`, not `[]` — because that is the
   * type a written `impl` is for and the name a diagnostic gives it. A shape-matched block is for
   * something else, so it needs a key of its own, and dropping the arguments is exactly what makes
   * one: every slice shares `[]`, and every array shares its length with the arrays of that length,
   * since without const generics (`10 § Open d`) the length is part of the shape rather than an
   * argument to it.
   *
   * A `string` is not a slice and has no shape here. It is a view of bytes that are valid UTF-8, and
   * that invariant is the whole difference between it and a `[]u8` — a block written for every slice
   * has said nothing about it.
   */
  protected def shapeOwner(t: Type): Option[(String, List[Type])] = t match
    case Type.Slice(elem)    => Some(("[]", List(elem)))
    case Type.Array(n, elem) => Some((s"[$n]", List(elem)))
    case _                   => None

  /** Where a member of that name is filed for a type: under the type's own key, or — when only a
   * shape-matched block supplies it — under the shape's, with the arguments this type matched at.
   *
   * The type's own key is asked first, and nothing is ever reached both ways: a block covering a
   * shape and a written implementation may not give one name to one type (`hoistMemberList`), so the
   * order settles which table holds the member rather than which of two answers wins.
   */
  protected def memberKey(t: Type, mname: String): (String, List[Type]) = {
    val own = memberOwner(t)

    if memberDecls.contains((own._1, mname)) then own
    else shapeOwner(t).filter(s => memberDecls.contains((s._1, mname))).getOrElse(own)
  }

  /** Whether a type has a member of that name at all, however it came by it. */
  protected def hasMember(t: Type, mname: String): Boolean =
    memberDecls.contains((memberKey(t, mname)._1, mname))

  /** The name codegen emits for one of a type's members. A member of a concrete type was hoisted
   * eagerly under `Type.member`; a member of a generic type is instantiated here, from the
   * receiver's own type arguments, and its body queued for analysis — so both answer with a name
   * that `funcInsts` holds.
   *
   * The prefix is the type **mangled**, not the key its members are filed under, because a type an
   * `impl` may name is not always a name: `[]int` is a fine key and an impossible LLVM symbol. The
   * two coincide for every type that *is* named.
   *
   * It lives here rather than with the calls because a vtable slot asks the same question a call
   * does, and a slot naming a member by any other rule is a slot pointing at nothing.
   *
   * A member with type parameters **of its own** is not named by its receiver: the arguments that
   * fix those come from the call, so the call is where it is named. Nothing that asks here can
   * reach one — a vtable slot, an operator, a property read are all members a trait declares, and
   * a trait declares no generic method — so the case is reported rather than answered with a name
   * built from too few arguments.
   */
  protected def memberFuncName(ty: Type, mname: String): String = {
    val (base, targs) = memberKey(ty, mname)

    genericMembers.get((base, mname)) match
      case Some(fd) if fd.tparams.length > targs.length =>
        err(s"'$mname' has type parameters of its own, so ${show(ty)} alone does not say which " +
          "instantiation is meant — call it, and the arguments will")
      case Some(fd) => instantiateFunc(fd, targs)
      case None     => s"${Type.mangle(ty)}.$mname"
  }

  /** Whether a member of that name is one the *compiler* provides for the type (`08`).
   *
   * `len` and `bytes` are members of built-ins that have no source body to declare them in, so they
   * are reached ahead of the member table rather than through it. An `impl` for such a type could
   * otherwise register a member of the same name that nothing would ever find, which is why this is
   * asked at the declaration.
   */
  protected def builtinMember(t: Type, name: String): Boolean = (t, name) match
    case (_: Type.Array | _: Type.View, "len") => true
    case (Type.Str, "bytes")                   => true
    case _                                     => false

  /** Every trait a bound promises: the traits it **requires**, transitively, and then itself
   * (`02 § A trait may require another trait`).
   *
   * The order is what the rest of this depends on — a required trait comes before the trait that
   * required it — because it is the order a method table is laid out in, and a table and the call
   * sites that index into it have to agree. Depth-first with each trait taken once, so a diamond
   * (`D: A + C`, both `A: B` and `C: B`) carries `B`'s members once rather than twice.
   *
   * A required trait's arguments are read under the requiring trait's own parameters, which is what
   * makes `trait Pair[T]: Sink[T]` mean the `Sink` of whatever the pair was applied to. A cycle
   * terminates here rather than looping — it is reported once, at the declarations, by
   * `checkTraitSupers`.
   *
   * **A trait is taken once by name, not once per argument list**, which is the same rule
   * `checkTraitSupers` enforces at the declaration: a type implements one trait once, so a closure
   * holding `Into[int]` and `Into[bool]` would be laying out two slots for one member. Keying this
   * walk any other way is how the table and the declaration check come to disagree.
   */
  protected def traitClosure(b: Type.Bound): List[Type.Bound] = {
    val seen = mutable.Set.empty[String]
    val acc  = mutable.ListBuffer.empty[Type.Bound]

    def walk(b: Type.Bound): Unit =
      if seen.add(b.name) then
        for decl <- traitDecls.get(b.name) do
          val subst = decl.tparams.zip(b.args).toMap

          for s <- decl.supers do walk(resolveBound(s, subst))
        acc += b

    walk(b)
    acc.toList
  }

  /** Everything a trait offers — each member, with the bound of the trait that declared it.
   *
   * A trait that requires another offers that one's members too, so this is what a trait object
   * dispatches through and what a bound licenses, and both read it from here so that neither can
   * disagree with the other about which slot is which.
   */
  protected def traitMembers(b: Type.Bound): List[(Type.Bound, MethodDecl)] =
    traitClosure(b).flatMap(sb => traitDecls.get(sb.name).toList.flatMap(_.methods.map((sb, _))))

  /** Whether a type implements a trait, which is the one question a bound asks.
   *
   * There are three ways to answer yes and they are not interchangeable. A **user** type opts in
   * with an explicit `impl`, filed under its owner key — nominal conformance, never structural. A
   * **built-in** is a member by the compiler's own rule (`14 §5`), because it has no module to write
   * an `impl` in and the integer family has no finite list of types to write one for. And a **type
   * parameter** implements exactly what its own bounds promise, which is what lets a bounded body
   * hand its parameter on to something that asks the same of it.
   *
   * What a bound promises includes what the traits it names require, which is the whole point of a
   * supertrait: a `[T: Word]` may be handed to something asking `[U: Add]` without writing `Add`
   * again.
   */
  protected def satisfies(tr: Type.Bound, t: Type): Boolean = t match
    case a: Type.Abstract => a.bounds.exists(b => traitClosure(b).exists(_.key == tr.key))
    // A compiler-provided membership is a rule about one trait rather than a family of them, so a
    // trait applied to arguments is never one: `Add` is an instruction, `Add[int]` is not a thing.
    case _ => conforms(tr, t) || (tr.args.isEmpty && CoreTraits.builtin(tr.name, t))

  /** The same question about a trait that takes no arguments, which is every trait the compiler
   * knows by name and most of the ones a program declares.
   */
  protected def satisfies(traitName: String, t: Type): Boolean = satisfies(Type.Bound(traitName, Nil), t)

  /** Whether a type implements a trait **through a source `impl`** — conformance a table can point
   * at, as against a membership the compiler provides by rule.
   *
   * A generic `impl` may be conditional, and this is where the condition is answered: the
   * implementing type's own arguments must satisfy what the block asked of them, which is the same
   * question one step in. So `Box[int]` implements `Show` under `impl[T: Show] Show for Box[T]`
   * exactly when `int` does, and a `Box[Point]` over a `Point` with no `Show` does not. A slice
   * covered by `impl[T: Show] Show for []T` is the same question asked of its element.
   */
  protected def conforms(tr: Type.Bound, t: Type): Boolean =
    implKey(tr, t).exists { case (key, targs) =>
      implBounds.get((tr.name, key)).forall { case (tps, bounds) =>
        val subst = tps.zip(targs).toMap

        targs.length == tps.length &&
        tps.zip(targs).forall { case (tp, arg) =>
          bounds.getOrElse(tp, Nil).forall(b => satisfies(resolveBound(b, subst), arg))
        }
      }
    }

  /** The same, for a trait that takes no arguments. */
  protected def conforms(traitName: String, t: Type): Boolean = conforms(Type.Bound(traitName, Nil), t)

  /** Which implementation of a trait a type is covered by, if any: the one written for the type,
   * or the one written for its shape, with the arguments it matched at.
   *
   * At most one of the two exists — a shape and a type written out in full may not both implement
   * one trait (`hoistImpl`) — so this is a lookup rather than a choice between implementations, and
   * sysl needs no rule saying which of two would be the more specific.
   */
  protected def implKey(tr: Type.Bound, t: Type): Option[(String, List[Type])] = {
    val own    = memberOwner(t)
    def has(k: String) = traitImpls.contains((tr.name, k)) && suppliesBound(tr, k)

    if has(own._1) then Some(own)
    else shapeOwner(t).filter(s => has(s._1))
  }

  /** Why a type an implementation *covers* still does not implement the trait — which is a different
   * answer from having no implementation at all, and the one a diagnostic should give when there is
   * one.
   *
   * Told to write an `impl Display for []Point`, a programmer would find it refused: an
   * `impl[T: Display] Display for []T` already covers every slice, and what the slice of points
   * fails is that block's condition. So the condition is what is worth saying, and this is the first
   * argument that does not meet it. `None` where nothing covers the type, leaving the caller's own
   * advice — write one — the right advice.
   */
  protected def unmetBound(tr: Type.Bound, t: Type): Option[String] =
    // A type that implements the trait at *other* arguments is a different situation from one that
    // does not implement it at all, and the advice differs with it: there is no second `impl` to
    // write, since one trait is implemented once per type.
    val wrongArgs =
      for
        key      <- List(memberOwner(t)._1) ::: shapeOwner(t).map(_._1).toList
        supplied <- implFor.get((tr.name, key)) if supplied != tr.key
      yield s"it implements '$supplied', and one trait is implemented once per type"

    val unmetCondition =
      for
        (key, targs)  <- implKey(tr, t)
        (tps, bounds) <- implBounds.get((tr.name, key))
        if targs.length == tps.length
        subst = tps.zip(targs).toMap
        (arg, unmet) <- tps
          .zip(targs)
          .flatMap((tp, a) =>
            bounds.getOrElse(tp, Nil).map(resolveBound(_, subst)).filterNot(satisfies(_, a)).map((a, _)))
          .headOption
      yield s"the 'impl' that covers it asks '${unmet.show}' of ${show(arg)}, which does not implement it"

    wrongArgs.headOption.orElse(unmetCondition)

  /** The same, for a trait that takes no arguments. */
  protected def unmetBound(traitName: String, t: Type): Option[String] =
    unmetBound(Type.Bound(traitName, Nil), t)

  /** The type a member is looked up on, seeing through one level of `*T` / `&T` so a method may be
   * called on a value, a pointer to it, or a reference to it alike.
   */
  protected def receiverType(t: Type): Type = t match
    case Type.Ptr(inner)    => inner
    case Type.Ref(inner, _) => inner
    case other              => other

  /** `1 argument`, `2 arguments` — a count with its noun agreeing. A diagnostic that misspells
   * English reads like a bug in the thing reporting it, which is not the impression a compiler
   * wants to give at the moment the programmer is already annoyed.
   */
  protected def quantity(n: Int, noun: String): String = if n == 1 then s"1 $noun" else s"$n ${noun}s"

  /** `1 argument was given`, `2 arguments were given` — the same, with the verb agreeing too. */
  protected def supplied(n: Int, noun: String): String =
    s"${quantity(n, noun)} ${if n == 1 then "was" else "were"} given"

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
  protected def analyzeExpr(expr: Expr, expected: Option[Type] = None, discarded: Boolean = false): TExpr
  protected def analyzeBool(e: Expr): TExpr
  protected def analyzePlace(target: Expr, what: String): TExpr
  protected def analyzeBlockBody(stmts: List[Stmt], expected: Option[Type], discarded: Boolean = false): TBlock
  protected def coerce(t: TExpr, expected: Type): TExpr
  protected def autoDeref(t: TExpr): TExpr
  protected def isPlace(t: TExpr): Boolean
  protected def instantiateFunc(f: FuncDecl, targs: List[Type]): String
}
