package io.github.edadma.sysl

import scala.collection.mutable

/** An error raised by the analyzer: an unknown name, a type mismatch, a wrong arity — any
 * rule that the structural parse cannot catch. `pos` is where in the source it was found, which
 * is absent only for a rule that fires away from any one node.
 */
case class AnalyzerError(message: String, pos: Option[Pos]) extends RuntimeException(message)

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

  /** Every `impl Trait for Type`, keyed by (trait name, type name). The key catches a duplicate
   * implementation, and it is what a trait bound will consult to decide whether a type conforms.
   */
  protected val traitImpls = mutable.LinkedHashMap.empty[(String, String), ImplDecl]

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

  protected def show(t: Type): String = Type.show(t)

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
  protected def analyzeBlockBody(stmts: List[Stmt], expected: Option[Type]): TBlock
  protected def box(t: TExpr, expected: Type): TExpr
  protected def autoDeref(t: TExpr): TExpr
  protected def isPlace(t: TExpr): Boolean
  protected def instantiateFunc(f: FuncDecl, targs: List[Type]): String
}
