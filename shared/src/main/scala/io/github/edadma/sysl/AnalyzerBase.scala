package io.github.edadma.sysl

import scala.collection.mutable

/** The shared substrate of the analyzer, mixed into the feature traits (`TypeResolution`,
 * `Literals`, `CallAnalysis`, `PatternAnalysis`) and the `Analyzer` class itself.
 *
 * Three layers are underneath it, each holding one concern of what "the analyzer's state" means:
 * `Reporting`, which is how a mistake is raised and recovered from; `DeclTables`, which is what the
 * program is made of; and `Scoping`, which is what a name written at a given place means. This one
 * adds the state of the **body being analyzed right now** — its result type, its type substitution,
 * the loops it is inside, the nested functions and captures it can see — all of which is put back
 * at every function boundary, and none of which outlives the body it belongs to.
 *
 * It also declares the recursive entry points that live in the `Analyzer` class but are called from
 * every trait, as abstract hooks — exactly as `Emitter` declares `genExpr` for the codegen traits.
 *
 * What none of the four holds is the reading of the tables. Asking what members a type has, whether
 * it satisfies a bound and through which implementation, and what to say when it does not, is
 * `TraitLookup` — which extends this and which everything else reaches through.
 */
trait AnalyzerBase extends Scoping {

  /** Inside a closure's body, the captured names — keyed by the unique name the scope gave each —
   * paired with the field read that reaches one (`12 §7`).
   *
   * A capture is declared in the closure's scope like any other name, so shadowing and assignment
   * need no rule of their own; what this adds is where the storage is. Reading a name consults it
   * after the scope has answered, which is why an inner binding of the same name still wins.
   */
  protected var capturedFields: Map[String, TExpr] = Map.empty

  /** The nested functions in scope, by the name a program calls one by (`12 §5a`).
   *
   * A block's nested functions are lowered as a group, so what is in here is every one of them from
   * the moment the first is written until the block ends — which is the "names are hoisted per
   * block" half of §5a. It follows the block, so it is put aside and given back at a scope boundary
   * the way the scope itself is.
   */
  protected var nestedFuncs: Map[String, Nested] = Map.empty

  /** The nested functions this block has still to lower, which is what lets the group be found from
   * the first of them and what lets a call written above them all say so.
   */
  protected var pendingNested: List[FuncDecl] = Nil

  /** Nested functions of a body this one is written *inside*, kept so that naming one is refused for
   * the reason it is refused rather than as an undefined name.
   *
   * A body nested in another reaches its own group and its own captures, and no further: a closure
   * may outlive the frame around it, and a nested function's environment is that frame.
   */
  protected var outerNested: Set[String] = Set.empty

  /** Every name the block being analyzed binds, reached or not, so that a use written above the
   * declaration is told which of the two mistakes it is.
   */
  protected var blockDeclares: Set[String] = Set.empty

  // Per-function state, reset at each function boundary.
  protected var retTy: Type               = Type.Unit
  protected var tsubst: Map[String, Type] = Map.empty

  /** What the body's type parameters were **bounded** by, alongside what they were substituted with.
   *
   * `tsubst` says a parameter has become `int` at this instantiation; this says the signature asked
   * it for `Zero`. Both are needed to reach a member: the substitution finds the type's table, and
   * the bound says which of the members that table holds under one name was promised — a question
   * the instantiation would otherwise have no way to answer, since two traits may declare one name
   * for one type and neither the call's arguments nor its scope is the thing that settled it.
   */
  protected var tbounds: Map[String, List[BoundRef]] = Map.empty

  /** Which type parameter each **parameter written as a bare one** was written as. `tbounds` says
   * what `T` was asked for; this says which of the body's names hold a `T`.
   *
   * Both halves are needed because the two ways of reaching a member arrive with different things.
   * An associated function is written through the parameter's own name — `T.zero()` still has the
   * `T` in it — but a **method** is written through a value, and by the time an instantiation
   * analyzes `x.id()` the receiver carries the type the substitution produced. Nothing in it
   * remembers being written as `T`, so without this the body would be refused for an ambiguity its
   * own signature settled.
   */
  protected var pbounds: Map[String, String] = Map.empty

  /** The traits a type parameter was bounded by, as keys — what a use inside the body may reach
   * through it whether or not the file also imported them, since the signature has named them.
   */
  protected def boundTraits(written: String): Set[String] =
    tbounds.getOrElse(written, Nil).flatMap(b => traitKey(b.name)).toSet

  /** Whether the function being analyzed declared its result as a **list** (`12 §5b`) rather than
   * as one type. `retTy` is the tuple its parts lay out as either way; this is what says whether
   * the body writes `a, b` or `(a, b)`, and whether a call yielding a list may stand in its result.
   */
  protected var retIsList: Boolean = false

  /** Set for exactly one expression by `analyzeMulti`, and consumed by the funnel the moment that
   * expression is analyzed — so a call yielding several results is allowed where the form asked for
   * one, and nowhere inside it.
   */
  protected var multiOk: Boolean = false

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

  protected def resetFunction(): Unit = {
    resetLocals()
    loops = Nil
    ensureResultTy = None
    oldBuf = None
    multiOk = false
    capturedFields = Map.empty
    nestedFuncs = Map.empty
    pendingNested = Nil
    outerNested = Set.empty
    blockDeclares = Set.empty
    tbounds = Map.empty
    pbounds = Map.empty
  }

  /** Runs `body` and then restores every table the emitted program is built from.
   *
   * The definition-time pass of `14 §4` walks a generic body exactly as an ordinary one is walked,
   * so it registers instantiations exactly as an ordinary one does — a `Box[T]`, a call to another
   * generic function, a library renderer reached by a `print`. None of those is a real
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

  /** Analyzes one expression in a place a **result list** may stand (`12 §5b`). */
  protected def analyzeMulti(expr: Expr, expected: Option[Type] = None): TExpr

  /** Whether a value produced *here* is the enclosing function's own result, which is the third
   * place a result list may stand — and so covers a branch or a block that ends in one.
   */
  protected def wantsResults(expected: Option[Type]): Boolean = retIsList && expected.contains(retTy)
  protected def analyzeBool(e: Expr): TExpr
  protected def analyzePlace(target: Expr, what: String, writes: Boolean = true): TExpr
  protected def requirePlace(t: TExpr, target: Expr, what: String, writes: Boolean = true): TExpr
  protected def invCheckFor(place: TExpr): List[(TExpr, Type.Struct, String)]
  protected def describe(target: Expr): String
  protected def indexes(traitName: String, receiver: Expr): Boolean
  protected def arithType(op: String, a: Type, b: Type): Type
  protected def constraintOf(t: Type): Option[Type.Constrained]
  protected def updateExpected(op: String, placeTy: Type): Option[Type]
  protected def updateDispatch(op: String, place: TExpr, value: TExpr): Option[TDispatch]
  protected def analyzeBlockBody(stmts: List[Stmt], expected: Option[Type], discarded: Boolean = false): TBlock
  protected def coerce(t: TExpr, expected: Type): TExpr
  protected def autoDeref(t: TExpr): TExpr
  protected def isPlace(t: TExpr): Boolean
  protected def instantiateFunc(f: FuncDecl, targs: List[Type]): String

  /** The call trait a value of this type implements, where it implements one (`12 §6`). */
  protected def callableOf(t: Type): Option[Type.Bound]

  /** The nested functions of one block, lowered together (`12 §5a`). */
  protected def lowerNestedGroup(group: List[FuncDecl]): List[TStmt]

  /** `value.name(args)` where `name` is a field holding a callable rather than a method (`12 §6`). */
  protected def callableField(
      rty: Type,
      name: String,
      recv: TExpr,
      args: List[Expr],
      expected: Option[Type],
  ): Option[TExpr]

  /** Analyzes a body inside the analysis of another one, giving back what it yields (`12 §5`). */
  protected def analyzeNested(
      name: String,
      params: List[(String, Type)],
      declaredResult: Option[Type],
      body: List[Stmt],
      environment: Option[Environment] = None,
      siblings: Map[String, Nested] = Map.empty,
      variadic: Boolean = false,
  ): (TFunc, Type)
}
