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

  protected val structDecls = mutable.LinkedHashMap.empty[String, StructDecl]
  protected val enumDecls   = mutable.LinkedHashMap.empty[String, EnumDecl]
  protected val funcDecls   = mutable.LinkedHashMap.empty[String, FuncDecl]

  /** Declared traits by name. A trait is a set of method signatures a type opts into through an
   * explicit `impl`; nothing conforms structurally.
   */
  protected val traitDecls = mutable.LinkedHashMap.empty[String, TraitDecl]

  /** Every `impl Trait for Type` as written, in source order. Kept unresolved because the type it
   * names may be declared further down the file; `hoistImpl` resolves each one after every type is
   * registered, and that is where `traitImpls` gets filled.
   */
  protected val implDecls = mutable.ListBuffer.empty[ImplDecl]

  /** Every `impl Trait for Type`, keyed by (trait name, the implementing type's **owner key**). The
   * key catches a duplicate implementation, and it is what a trait bound consults to decide whether
   * a type conforms.
   *
   * Keying by the owner key rather than by the name as written is what makes `impl Show for int` and
   * `impl Show for i32` the one implementation they are — the same type reached by two spellings.
   */
  protected val traitImpls = mutable.LinkedHashMap.empty[(String, String), ImplDecl]

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
    got != want && got != Type.Unknown && want != Type.Unknown && got != Type.Never

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
    else None

  protected def show(t: Type): String = Type.show(t)

  /** The type parameters of a nominal type — a struct or an enum — by the name it was declared
   * under. Empty both for a non-generic type and for a name that declares no type at all, which is
   * what lets a caller ask "is this generic" without first knowing which kind it is.
   */
  protected def nominalTparams(base: String): List[String] =
    structDecls.get(base).map(_.tparams).orElse(enumDecls.get(base).map(_.tparams)).getOrElse(Nil)

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

  /** Whether a type implements a trait, which is the one question a bound asks.
   *
   * There are two ways to answer yes and they are not interchangeable. A **user** type opts in with
   * an explicit `impl`, filed under its owner key — nominal conformance, never structural. A
   * **built-in** is a member by the compiler's own rule (`14 §5`), because it has no module to write
   * an `impl` in and the integer family has no finite list of types to write one for.
   */
  protected def satisfies(traitName: String, t: Type): Boolean =
    traitImpls.contains((traitName, ownerKey(t))) || CoreTraits.builtin(traitName, t)

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

  protected def pushScope(): Unit = scopes = mutable.LinkedHashMap.empty[String, (String, Type)] :: scopes
  protected def popScope(): Unit  = scopes = scopes.tail

  protected def resetFunction(): Unit = {
    used.clear()
    scopes = List(mutable.LinkedHashMap.empty[String, (String, Type)])
    loops = Nil
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

  protected def lookupOpt(name: String): Option[(String, Type)] =
    scopes.collectFirst { case s if s.contains(name) => s(name) }

  protected def lookup(name: String): (String, Type) =
    lookupOpt(name).getOrElse(err(s"undefined name '$name'"))

  // --- hooks provided by the Analyzer class --------------------------------------------
  //
  // These recursive entry points live in the class (statements, expressions, places) but are
  // called across the feature traits, so they are declared abstract here for the traits to see.

  protected def analyzeExpr(expr: Expr, expected: Option[Type] = None): TExpr
  protected def analyzeBool(e: Expr): TExpr
  protected def analyzePlace(target: Expr, what: String): TExpr
  protected def analyzeBlockBody(stmts: List[Stmt], expected: Option[Type]): TBlock
  protected def coerce(t: TExpr, expected: Type): TExpr
  protected def autoDeref(t: TExpr): TExpr
  protected def isPlace(t: TExpr): Boolean
  protected def instantiateFunc(f: FuncDecl, targs: List[Type]): String
}
