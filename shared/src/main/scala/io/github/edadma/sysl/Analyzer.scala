package io.github.edadma.sysl

import scala.collection.mutable

/** The semantic pass: it resolves names, checks types, and turns the untyped `Program` into
 * a typed `TProgram` that codegen lowers directly. All diagnostics live here; codegen trusts
 * the tree it is handed.
 *
 * The work is split across traits mixed into this class, the way codegen is split across
 * `Emitter` and friends: `AnalyzerBase` holds the shared tables and name scopes, `TypeResolution`
 * resolves and instantiates types, `Literals` handles the scalar leaves, `CallAnalysis` handles
 * calls and construction, and `PatternAnalysis` handles `match`. What stays here is the spine —
 * the declaration-hoisting driver, statements, the expression dispatch, and places — plus the
 * recursive entry points the traits call back into through `AnalyzerBase`'s hooks.
 *
 * Declarations are hoisted, so functions, structs, and enums may be used before they appear
 * and may be mutually recursive. Each function (and the synthetic `main` around the top-level
 * statements) is its own naming context: a variable that shadows an outer one is renamed to a
 * unique register name, which keeps codegen's per-function SSA names distinct without the
 * analyzer having to understand LLVM.
 *
 * **Generics are monomorphized here.** A generic declaration is kept in its untyped form and
 * instantiated on demand: each distinct set of type arguments produces its own `Type.Struct` /
 * `Type.Enum` / `TFunc` under a mangled name, and codegen never sees a type parameter. Type
 * arguments are inferred from the argument types at a call or construction, and from the
 * *expected* type when the arguments alone do not determine them — which is what lets `None`
 * and `Ok(5)` take their type from the context they appear in.
 */
class Analyzer private (program: Program) extends CallAnalysis with PatternAnalysis with Literals {

  // --- program -------------------------------------------------------------------------

  private def analyze(): TProgram = {
    val body = Prelude.decls ::: program.body

    for stmt <- body do
      stmt match
        case s: StructDecl =>
          if typeNameTaken(s.name) then err(s"type '${s.name}' is already declared")
          structDecls(s.name) = s
        case e: EnumDecl =>
          if typeNameTaken(e.name) then err(s"type '${e.name}' is already declared")
          // A generic enum has no single storage type to pin, and it is never instantiated
          // eagerly, so the annotation is rejected here at the declaration rather than on use.
          if e.underlying.isDefined && e.tparams.nonEmpty then
            err(s"a generic enum cannot pin an underlying type — '${e.name}' takes type parameters")
          enumDecls(e.name) = e
          for v <- e.variants do
            if variantOwner.contains(v.name) then
              err(s"variant name '${v.name}' is already used by enum '${variantOwner(v.name)}'")
            variantOwner(v.name) = e.name
        case t: TraitDecl =>
          if typeNameTaken(t.name) then err(s"the name '${t.name}' is already declared")
          if t.tparams.nonEmpty then err(s"generic traits are not supported yet — '${t.name}'")
          traitDecls(t.name) = t
        case i: ImplDecl =>
          if traitImpls.contains((i.traitName, i.forType)) then
            err(s"'${i.forType}' already implements '${i.traitName}'")
          traitImpls((i.traitName, i.forType)) = i
        case _ =>

    // A non-generic type is instantiated eagerly, so it is emitted whether or not it is used;
    // a generic one only exists once something asks for a concrete instantiation.
    for (n, d) <- enumDecls if d.tparams.isEmpty do instantiateEnum(n, Nil)
    for (n, d) <- structDecls if d.tparams.isEmpty do instantiateStruct(n, Nil)

    for stmt <- body do
      stmt match
        case f: FuncDecl =>
          if funcDecls.contains(f.name) then err(s"function '${f.name}' is already declared")
          funcDecls(f.name) = f
          if f.tparams.isEmpty then
            funcInsts(f.name) =
              (f.params.map(p => (p.name, resolveType(p.typ, Map.empty))),
               f.retType.map(resolveType(_, Map.empty)).getOrElse(Type.Unit))
        case _ =>

    // A type's members lower to ordinary functions under mangled names, registered here so a
    // method call and an associated-function call resolve exactly as a free call does.
    val members = mutable.ListBuffer.empty[FuncDecl]
    for (tname, sdecl) <- structDecls do hoistMembers(tname, sdecl, members)
    for impl <- traitImpls.values do hoistImpl(impl, members)

    val tfuncs = mutable.ListBuffer.empty[TFunc]

    for f <- body.collect { case f: FuncDecl if f.tparams.isEmpty => f } do
      tfuncs += analyzeFuncBody(f.name, f, Map.empty)

    for f <- members do
      tfuncs += analyzeFuncBody(f.name, f, Map.empty)

    val mainStmts = program.body.filter {
      case _: FuncDecl | _: StructDecl | _: EnumDecl | _: TraitDecl | _: ImplDecl => false
      case _                                                                      => true
    }

    resetFunction()
    tsubst = Map.empty
    retTy = Type.Int
    val tmain = mainStmts.map(analyzeStmt(_))

    // Draining the queue may itself discover further instantiations, so it runs to a fixpoint.
    while pending.nonEmpty do
      val (mangled, decl, subst) = pending.dequeue()
      tfuncs += analyzeFuncBody(mangled, decl, subst)

    TProgram(structInsts.values.toList, enumInsts.values.filterNot(_.simple).toList, tfuncs.toList, tmain)
  }

  /** Structs, enums, and the built-in scalars share one type namespace, so a name may name at
   * most one of them.
   */
  private def typeNameTaken(name: String): Boolean =
    structDecls.contains(name) || enumDecls.contains(name) || traitDecls.contains(name) ||
      scalarType(name).isDefined

  /** Records a type's members and lowers each to a function declaration under the mangled name
   * `Type.member`, whose signature is registered so calls resolve like ordinary ones.
   *
   * A member of a concrete type is hoisted eagerly, so an uncalled member is still type-checked at
   * its definition. A member of a *generic* type cannot be: its signature mentions the type's
   * parameters, which have no meaning until a call fixes them, so it is stored generic in
   * `genericMembers` and instantiated on demand at each call site. Members that introduce their own
   * type parameters, and associated functions on a generic type — whose type arguments would have
   * to be inferred rather than read off a receiver — wait on later work and are rejected with a
   * clear diagnostic rather than silently mishandled.
   */
  private def hoistMembers(tname: String, sdecl: StructDecl, out: mutable.ListBuffer[FuncDecl]): Unit =
    hoistMemberList(tname, sdecl.tparams, sdecl.fields.map(_.name).toSet, sdecl.members, out)

  /** Lowers a list of members of `tname` to functions, shared by a type's own body and an `impl`
   * block. Each member is registered under (type, name) and synthesized to a `Type.member`
   * function; a member of a concrete type is type-checked eagerly, while a member of a generic
   * type waits for a concrete instantiation.
   */
  private def hoistMemberList(
      tname: String,
      tparams: List[String],
      fieldNames: Set[String],
      members: List[MethodDecl],
      out: mutable.ListBuffer[FuncDecl],
  ): Unit = {
    val generic = tparams.nonEmpty

    for m <- members do
      if m.tparams.nonEmpty then err(s"generic methods are not supported yet — '$tname.${m.name}'")
      if memberDecls.contains((tname, m.name)) then err(s"type '$tname' already has a member named '${m.name}'")
      if fieldNames.contains(m.name) then
        err(s"type '$tname' has both a field and a member named '${m.name}'")

      memberDecls((tname, m.name)) = m
      val fd = synthesize(tname, tparams, m)

      if generic then
        if m.receiver.isEmpty && !m.isProperty then
          err(s"associated functions on generic types are not supported yet — '$tname.${m.name}'")
        genericMembers((tname, m.name)) = fd
      else
        out += fd
        funcInsts(fd.name) =
          (fd.params.map(p => (p.name, resolveType(p.typ, Map.empty))),
           fd.retType.map(resolveType(_, Map.empty)).getOrElse(Type.Unit))
  }

  /** Checks an `impl` conforms to its trait and lowers its methods to inherent members of the
   * implementing type. Conformance is nominal and exact: the type must supply every trait method
   * with a matching signature and no method the trait does not declare. The methods are then
   * hoisted exactly as a struct's own members, so a call on a value resolves through the ordinary
   * member path with no dispatch machinery of its own.
   */
  private def hoistImpl(impl: ImplDecl, out: mutable.ListBuffer[FuncDecl]): Unit = {
    val tr = traitDecls.getOrElse(impl.traitName, err(s"unknown trait '${impl.traitName}'"))
    val sdecl = structDecls.getOrElse(
      impl.forType,
      err(s"a trait can only be implemented for a struct so far, and '${impl.forType}' is not one"),
    )
    if sdecl.tparams.nonEmpty then
      err(s"implementing a trait for a generic type is not supported yet — '${impl.forType}'")

    checkConformance(tr, impl)
    hoistMemberList(impl.forType, Nil, sdecl.fields.map(_.name).toSet, impl.methods, out)
  }

  /** Verifies that `impl` supplies exactly the methods `tr` declares, each with an identical
   * resolved signature. A missing method, an extra one, or a mismatched receiver, parameter, or
   * result is reported against the trait it fails to satisfy.
   */
  private def checkConformance(tr: TraitDecl, impl: ImplDecl): Unit = {
    val declared = tr.methods.map(_.name).toSet

    for tm <- tr.methods do
      impl.methods.find(_.name == tm.name) match
        case None     => err(s"'${impl.forType}' does not implement '${impl.traitName}': method '${tm.name}' is missing")
        case Some(im) => checkSignature(impl.forType, impl.traitName, tm, im)

    for im <- impl.methods do
      if !declared.contains(im.name) then
        err(s"trait '${impl.traitName}' declares no method '${im.name}', so this 'impl' cannot define it")
  }

  /** Compares one implementing method against the trait's signature: same receiver mode, same
   * parameter types in order, and the same result. Types are resolved with the implementing type
   * substituted for `self`, so `self` on both sides means the same concrete type.
   */
  private def checkSignature(forType: String, traitName: String, tm: MethodDecl, im: MethodDecl): Unit = {
    if tm.receiver != im.receiver then
      err(s"method '${im.name}' of 'impl $traitName for $forType' takes a different receiver than the trait declares")
    if tm.params.length != im.params.length then
      err(s"method '${im.name}' of 'impl $traitName for $forType' takes ${im.params.length} " +
        s"parameters, but the trait declares ${tm.params.length}")

    for (tp, ip) <- tm.params.zip(im.params) do
      val want = resolveType(tp.typ, Map.empty)
      val got  = resolveType(ip.typ, Map.empty)
      if want != got then
        err(s"parameter '${ip.name}' of method '${im.name}' is ${show(got)}, but trait '$traitName' declares ${show(want)}")

    val want = tm.retType.map(resolveType(_, Map.empty)).getOrElse(Type.Unit)
    val got  = im.retType.map(resolveType(_, Map.empty)).getOrElse(Type.Unit)
    if want != got then
      err(s"method '${im.name}' returns ${show(got)}, but trait '$traitName' declares ${show(want)}")
  }

  /** Builds the function a member lowers to: the receiver becomes an ordinary first parameter
   * named `self`, carrying the memory mode its sigil asked for. A property's receiver is an
   * implicit by-value read; an associated function has no receiver. On a generic type the self
   * type is the type applied to its own parameters (`Box[T]`, not `Box`), and the lowered function
   * inherits those parameters, so instantiating it at a concrete `Box[int]` substitutes `T` in the
   * receiver, the result, and the body alike.
   */
  private def synthesize(tname: String, tparams: List[String], m: MethodDecl): FuncDecl = {
    val selfRef = NamedType(tname, tparams.map(tp => NamedType(tp, Nil)))
    val selfParam = m.receiver match
      case Some(RecvMode.ByValue)     => Some(Param("self", selfRef))
      case Some(RecvMode.ByPtr)       => Some(Param("self", PtrType(selfRef)))
      case Some(RecvMode.ByRef(sync)) => Some(Param("self", RefType(selfRef, sync)))
      case None if m.isProperty       => Some(Param("self", selfRef))
      case None                       => None
    FuncDecl(s"$tname.${m.name}", tparams, selfParam.toList ::: m.params, m.retType, m.body)
  }

  private def analyzeFuncBody(name: String, f: FuncDecl, subst: Map[String, Type]): TFunc = {
    val (params, rtype) = funcInsts(name)
    resetFunction()
    tsubst = subst
    retTy = rtype
    val tparams = params.map { case (n, t) => (declare(n, t), t) }
    val tbody   = analyzeValueBlock(f.body, if rtype == Type.Unit then None else Some(rtype))

    if rtype != Type.Unit && tbody.result.isDefined && tbody.ty != rtype then
      err(s"function '${f.name}' should return ${show(rtype)}, but its body yields ${show(tbody.ty)}")

    TFunc(name, tparams, rtype, tbody)
  }

  /** Registers an instantiation of a generic function and returns the name codegen will emit.
   * The signature is recorded before the body is queued, so a recursive generic function
   * resolves its own call.
   */
  protected def instantiateFunc(f: FuncDecl, targs: List[Type]): String = {
    val name = Type.mangled(f.name, targs)

    if !funcInsts.contains(name) then
      val subst = f.tparams.zip(targs).toMap
      funcInsts(name) =
        (f.params.map(p => (p.name, resolveType(p.typ, subst))),
         f.retType.map(resolveType(_, subst)).getOrElse(Type.Unit))
      pending.enqueue((name, f, subst))

    name
  }

  // --- statements ----------------------------------------------------------------------

  /** A block whose trailing expression (if any) is its value — a function body or an if/match
   * branch. Statements share one lexical scope with the result expression.
   */
  private def analyzeValueBlock(stmts: List[Stmt], expected: Option[Type]): TBlock = {
    pushScope()
    val tb = analyzeBlockBody(stmts, expected)
    popScope()
    tb
  }

  /** The body of a value block, using whatever scope the caller has established — a match arm
   * runs this after declaring its pattern bindings, so they are visible to the body.
   */
  protected def analyzeBlockBody(stmts: List[Stmt], expected: Option[Type]): TBlock =
    stmts.reverse match
      case ExprStmt(e) :: initRev =>
        val init = initRev.reverse.map(analyzeStmt(_))
        val tr   = analyzeExpr(e, expected)
        TBlock(init, Some(tr), tr.ty)
      case _ =>
        TBlock(stmts.map(analyzeStmt(_)), None, Type.Unit)

  /** A statement sequence used only for its effects (a loop body): a fresh scope, no value. */
  private def analyzeStmts(stmts: List[Stmt]): List[TStmt] = {
    pushScope()
    val r = stmts.map(analyzeStmt(_))
    popScope()
    r
  }

  /** Analyzes a loop body with a fresh loop context on the stack, so `break`/`continue` inside it
   * resolve to this loop and each `break` value is collected for the loop's result type. Returns
   * the typed body together with the context the breaks were recorded in.
   */
  private def analyzeLoopBody(expected: Option[Type], label: Option[String])(
      body: => List[TStmt],
  ): (List[TStmt], LoopCtx) = {
    label.foreach { l =>
      if loops.exists(_.label.contains(l)) then err(s"label '$l is already in scope")
    }
    val ctx = new LoopCtx(expected, label)
    loops = ctx :: loops
    val tb = body
    loops = loops.tail
    (tb, ctx)
  }

  /** Resolves a `break`/`continue` to the loop it targets and that loop's distance out from the
   * innermost. An absent label is the nearest loop; a `'name` is the nearest loop carrying it.
   */
  private def resolveLoop(keyword: String, label: Option[String]): (LoopCtx, Int) = label match
    case None =>
      loops match
        case ctx :: _ => (ctx, 0)
        case Nil      => err(s"'$keyword' is only allowed inside a loop")
    case Some(l) =>
      loops.indexWhere(_.label.contains(l)) match
        case -1 => err(s"no enclosing loop is labeled '$l")
        case i  => (loops(i), i)

  /** The result type of a loop: every `break` value and the `else` value must agree on one type.
   * With no `else`, normal completion yields `unit`, so a value-carrying `break` has nothing to
   * meet on that path — a clear error rather than a silent mismatch.
   */
  private def loopResultType(ctx: LoopCtx, elseBlock: Option[TBlock]): Type = {
    val elseTy = elseBlock.map(_.ty).getOrElse(Type.Unit)
    val tys    = (ctx.breakTys.toList :+ elseTy).distinct
    if tys.size == 1 then tys.head
    else if elseBlock.isEmpty then
      val v = ctx.breakTys.find(_ != Type.Unit).get
      err(s"this loop breaks with a ${show(v)} but has no 'else' to give a value when it finishes normally — add an 'else'")
    else
      err(s"a loop's break values and its 'else' must have the same type, but got ${tys.map(show).mkString(" and ")}")
  }

  private def analyzeStmt(stmt: Stmt): TStmt = stmt match
    case VarDecl(name, typOpt, Some(init)) =>
      val declared = typOpt.map(rt)
      val ti       = analyzeExpr(init, declared)
      if ti.ty == Type.Unit then err(s"cannot bind '$name' to a unit value")
      val declTy = declared.getOrElse(ti.ty)
      if declared.isDefined && declTy != ti.ty then
        err(s"cannot initialize '$name': declared ${show(declTy)} but the value is ${show(ti.ty)}")
      TVarDecl(declare(name, declTy), declTy, ti)

    case VarDecl(name, typOpt, None) =>
      val ty = typOpt.map(rt).getOrElse(err(s"'$name' needs either a type or an initial value"))
      if !hasZero(ty) then err(s"${show(ty)} has no zero value, so '$name' needs an initial value")
      TVarDecl(declare(name, ty), ty, TZero(ty))

    case ExprStmt(e) =>
      TExprStmt(analyzeExpr(e))

    case Return(opt) =>
      val tv = opt.map(analyzeExpr(_, Some(retTy)))
      tv match
        case Some(_) if retTy == Type.Unit => err("cannot return a value from a function with no return type")
        case Some(t) if t.ty != retTy      => err(s"return type mismatch: expected ${show(retTy)}, got ${show(t.ty)}")
        case None if retTy != Type.Unit    => err(s"this function must return a ${show(retTy)} value")
        case _                             =>
      TReturn(tv)

    // `break value` records its type against the loop it targets — the nearest, or the one a
    // `'label` names — which unites it with that loop's other breaks and its `else` to fix the
    // loop's result type. The value is analyzed in the target loop's expected type, so a `break &T`
    // boxes the same way a `break` in a `&T` context asks.
    case Break(label, opt) =>
      val (ctx, depth) = resolveLoop("break", label)
      val tv           = opt.map(e => analyzeExpr(e, ctx.expected))
      ctx.breakTys += tv.map(_.ty).getOrElse(Type.Unit)
      TBreak(tv, depth)

    case Continue(label) =>
      val (_, depth) = resolveLoop("continue", label)
      TContinue(depth)

    case _: FuncDecl | _: StructDecl | _: EnumDecl | _: TraitDecl | _: ImplDecl =>
      err("functions, structs, enums, traits, and impls may only be declared at the top level")

  // --- expressions ---------------------------------------------------------------------

  protected def analyzeBool(e: Expr): TExpr = {
    val t = analyzeExpr(e, Some(Type.Bool))
    if t.ty != Type.Bool then err(s"condition must be bool, got ${show(t.ty)}")
    t
  }

  /** Analyzes an expression. `expected` is the type the context wants, used where the
   * expression cannot determine its own type arguments — a bare `None`, an `Ok(v)` whose error
   * type is not mentioned by its argument, a generic call whose result alone is generic — and
   * where it decides that a value belongs on the heap.
   *
   * A context expecting `&T` asks the expression for a `T` and boxes what comes back, so
   * writing the ordinary construction is the whole spelling of an allocation. An expression
   * that is already a `&T` passes through untouched.
   */
  protected def analyzeExpr(expr: Expr, expected: Option[Type]): TExpr = expected match
    case Some(r: Type.Ref) =>
      expr match
        case NullLit() => err(s"a ${show(r)} always points at a live object — an absent one is Option[${show(r)}]")
        // An `if`/`match`/loop yields its value through its branches (a loop's, through its
        // `break`s and `else`), so the `&T` context belongs to each of those, not to the
        // aggregate: every one boxes to `&T` on its own, letting a `&T` branch and a value branch
        // meet at `&T`. Boxing the whole expression instead would ask the branches for a plain
        // `T`, and a branch already holding a `&T` could not comply.
        case _: IfExpr | _: MatchExpr | _: While | _: For => analyzeValue(expr, Some(r))
        case _                        => box(analyzeValue(expr, Some(r.inner)), r)
    case _ => analyzeValue(expr, expected)

  /** Boxes a value the context wanted by reference. Nothing else coerces: a mismatch that is
   * not exactly "a `T` where `&T` was expected" is left for the caller to diagnose.
   */
  protected def box(t: TExpr, expected: Type): TExpr = expected match
    case r: Type.Ref if t.ty == r.inner => TBox(t, r)
    case _                              => t

  private def analyzeValue(expr: Expr, expected: Option[Type]): TExpr = expr match
    case IntLit(v, suffix)   => intLiteral(v, suffix, expected)
    case FloatLit(t, suffix) => floatLiteral(t, suffix, expected)
    case CharLit(cp)         => TIntLit(cp, Type.Char)
    case StrLit(s)           => TStrLit(s)
    case BoolLit(b)          => TBoolLit(b)
    case UnitLit()           => TUnitLit()

    case NullLit() =>
      expected match
        case Some(p: Type.Ptr) => TNullLit(p)
        case Some(other)       => err(s"'null' is a raw pointer, and ${show(other)} was expected here")
        case None              => err("'null' takes its type from its context, and there is none here")

    // A minus and the literal it precedes are one unit for the range check, so a signed type's
    // minimum is writable even though its magnitude overflows the positive range.
    case Unary("-", IntLit(v, suffix))   => intLiteral(-v, suffix, expected)
    case Unary("-", FloatLit(t, suffix)) => floatLiteral("-" + t, suffix, expected)

    case Ident(name) =>
      lookupOpt(name) match
        case Some((u, ty)) => TLoad(u, ty)
        case None if variantOwner.contains(name) => constructVariant(name, Nil, expected)
        case None                                => err(s"undefined name '$name'")

    case Binary(op @ ("&&" | "||"), l, r) =>
      TLogical(op, analyzeBool(l), analyzeBool(r))

    case Binary(op, l, r) =>
      val List(tl, tr) = analyzeOperands(List(l, r), expected.filter(Type.isNumeric))
      TBinary(op, tl, tr, arithType(op, tl.ty, tr.ty))

    case Unary("-", e) =>
      val t = analyzeExpr(e, expected.filter(Type.isNumeric))
      t.ty match
        case i: Type.Integer if i.signed => TUnary("-", t, i)
        case f: Type.Floating            => TUnary("-", t, f)
        case i: Type.Integer             => err(s"unary '-' is not defined for the unsigned type ${show(i)}")
        case other                       => err(s"unary '-' is not defined for ${show(other)}")

    case Unary("!", e) =>
      TUnary("!", analyzeBool(e), Type.Bool)

    case Unary("~", e) =>
      val t = analyzeExpr(e, expected.filter(Type.isNumeric))
      t.ty match
        case i: Type.Integer => TUnary("~", t, i)
        case other           => err(s"unary '~' is not defined for ${show(other)}")

    // Address-of yields a *raw* pointer: a place lives in a frame or inside another object, so
    // there is no refcount to take a share of. Reaching a `&T` means being handed one.
    case Unary("&", e) =>
      val place = analyzePlace(e, "'&'")
      TAddrOf(place, Type.Ptr(place.ty))

    case Unary("*", e) =>
      val t = analyzeExpr(e)
      Type.pointee(t.ty) match
        case Some(inner) => TDeref(t, inner)
        case None        => err(s"'*' needs a pointer or a reference, not ${show(t.ty)}")

    case Unary(op, _) =>
      err(s"unary '$op' is not supported yet")

    case PreIncDec(op, target)  => incDec(op, target, pre = true)
    case PostIncDec(op, target) => incDec(op, target, pre = false)

    case Compare(operands, ops) =>
      val ts = analyzeOperands(operands, None)
      for i <- ops.indices do
        val (a, b)  = (ts(i), ts(i + 1))
        val equality = ops(i) == "==" || ops(i) == "!="
        if a.ty != b.ty then err(s"cannot compare ${show(a.ty)} with ${show(b.ty)}")
        if !(if equality then Type.isEquatable(a.ty) else Type.isOrdered(a.ty)) then
          err(s"'${ops(i)}' is not defined for ${show(a.ty)}")
      TCompare(ts, ops)

    case Assign("=", target, value) =>
      val place = analyzePlace(target, "assignment")
      val tv    = analyzeExpr(value, Some(place.ty))
      if tv.ty != place.ty then
        err(s"cannot assign ${show(tv.ty)} to ${describe(target)} of type ${show(place.ty)}")
      TStore(place, tv, place.ty)

    case Assign(op, target, value) =>
      val place  = analyzePlace(target, s"'$op'")
      val binSym = op.dropRight(1)
      val tv     = analyzeExpr(value, Some(place.ty))
      if arithType(binSym, place.ty, tv.ty) != place.ty then
        err(s"'$op' would change the type of ${describe(target)}")
      TUpdate(place, op, tv, place.ty)

    case Call(Ident("print"), args) =>
      TPrint(args.map { a =>
        val t = analyzeExpr(a)
        t.ty match
          case Type.Unit => err("cannot print a unit value")
          case _: Type.Struct | _: Type.Enum | _: Type.Ptr | _: Type.Ref | _: Type.Array |
              _: Type.Slice =>
            err(s"cannot print a ${show(t.ty)} value")
          case _ => t
      })

    // `str(x)` is a builtin, not a conversion: it renders a value of a primitive type, and a
    // `string` is rendered as itself. Anything else — a struct, an enum, a pointer, a slice — has
    // no one string form to give, so asking for one is an error until a `Display` trait lets a
    // type name its own.
    case Call(Ident("str"), args) =>
      if args.length != 1 then err("str takes exactly one value")
      val t = analyzeExpr(args.head)
      t.ty match
        case _: Type.Integer | _: Type.Floating | Type.Bool | Type.Char | Type.Str => TStr(t)
        case _ => err(s"cannot make a string of a ${show(t.ty)} value")

    // `format(value, "%spec")` renders one value through a printf specifier. It is the desugaring
    // of an `f"…"` hole, so the specifier is always a literal here; the lexer has vetted its shape,
    // and what is left is checking the conversion against the value's type.
    case Call(Ident("format"), List(argExpr, StrLit(spec))) =>
      val t = analyzeExpr(argExpr)
      val c = FormatSpec.conversion(spec)
      val ok =
        if FormatSpec.isInt(c) then t.ty.isInstanceOf[Type.Integer]
        else if FormatSpec.isFloat(c) then t.ty.isInstanceOf[Type.Floating]
        else t.ty == Type.Str
      if !ok then err(s"format '$spec' expects ${FormatSpec.expects(c)}, but the value has type ${show(t.ty)}")
      TFormat(t, spec)

    // A conversion is written with call syntax, so a scalar type name in call position is one.
    case Call(Ident(name), args) if lookupOpt(name).isEmpty && scalarType(name).isDefined =>
      if args.length != 1 then err(s"a '$name' conversion takes exactly one value")
      convert(analyzeExpr(args.head), scalarType(name).get)

    case Call(Ident(name), args) if lookupOpt(name).isEmpty && variantOwner.contains(name) =>
      constructVariant(name, args, expected)

    case Call(Ident(name), args) if lookupOpt(name).isEmpty && structDecls.contains(name) =>
      constructStruct(name, args, expected)

    // A simple enum's name in call position is a checked cast from an integer — `Color(n)` traps
    // on a value that is not a declared discriminant. Told from a data enum, which has no integer
    // to reinterpret, and from a struct constructor, which line 479 already claimed.
    case Call(Ident(name), args) if lookupOpt(name).isEmpty && enumDecls.contains(name) =>
      enumFromInt(name, args)

    case Call(Ident(name), args) if funcDecls.contains(name) =>
      callFunction(funcDecls(name), args, expected)

    case Call(Ident(name), _) =>
      err(s"undefined function '$name'")

    // Reached through the enum name: `Color.try(n)` is the fallible constructor; otherwise a
    // data-carrying variant `Shape.Circle(5)`, the qualified form of the bare `Circle(5)`.
    case Call(Field(Ident(tname), mname), args) if lookupOpt(tname).isEmpty && enumDecls.contains(tname) =>
      if mname == "try" then enumTry(tname, args)
      else
        enumDecls(tname).variants.find(_.name == mname) match
          case Some(_) => constructVariant(mname, args, expected)
          case None    => err(s"enum '$tname' has no variant '$mname'")

    // `Type.name(…)` — an associated function, told from the positional constructor `Type(…)` by
    // the member selected from the type name rather than the bare name applied.
    case Call(Field(Ident(tname), mname), args) if lookupOpt(tname).isEmpty && structDecls.contains(tname) =>
      callAssociated(tname, mname, args, expected)

    case Call(Field(recv, mname), args) =>
      callMethod(recv, mname, args)

    case Call(_, _) =>
      err("the thing being called must be a name")

    case Field(Ident(n), f) if lookupOpt(n).isEmpty && enumDecls.contains(n) =>
      enumDecls(n).variants.find(_.name == f) match
        case Some(_) => constructVariant(f, Nil, expected)
        case None    => err(s"enum '$n' has no variant '$f'")

    case Field(receiver, f) =>
      val tr = autoDeref(analyzeExpr(receiver))
      tr.ty match
        case s: Type.Struct =>
          val idx = s.fieldIndex(f)
          if idx >= 0 then TField(tr, idx, s.fields(idx)._2)
          else
            memberDecls.get((s.base, f)) match
              // A property reads with no parentheses, so it looks exactly like a field; its
              // receiver is an implicit by-value read of the instance.
              case Some(m) if m.isProperty =>
                val fname      = memberFuncName(s.base, f, s)
                val (_, rtype) = funcInsts(fname)
                TCall(fname, List(tr), rtype)
              case Some(_) => err(s"'$f' is a method of '${s.name}' — call it with '$f(…)'")
              case None    => err(s"'${s.name}' has no field or property '$f'")
        // `len` and `bytes` are the first compiler-provided members: `len` a property on every
        // array, slice, and string, and `bytes` the reinterpretation of a string's three words
        // as a `[]u8`, dropping only the validity guarantee.
        case _: Type.Array | _: Type.View if f == "len" => TLen(tr)
        case Type.Str if f == "bytes"                   => TBytes(tr)
        case other =>
          err(s"cannot read field '$f' of ${show(other)}")

    case ArrayLit(elems) =>
      val elemExp = expected.collect { case Type.Array(_, e) => e }
      val ts      = elems.map(analyzeExpr(_, elemExp))

      for t <- ts do
        if t.ty == Type.Unit then err("an array cannot hold unit values")
        if t.ty != ts.head.ty then
          err(s"an array literal needs one element type, got ${show(ts.head.ty)} and ${show(t.ty)}")

      val elemTy = ts.headOption.map(_.ty).orElse(elemExp).getOrElse(
        err("an empty array literal takes its element type from its context, and there is none here"),
      )
      TArrayLit(ts, Type.Array(ts.length, elemTy))

    // A range subscript takes a view. The receiver is left *undereferenced* on purpose: for a
    // heap array the reference is both where the elements are and what keeps them alive, and
    // evaluating it once is what makes those the same object.
    case Index(receiver, RangeExpr(lo, hi, inclusive)) =>
      if !inclusive && hi.isEmpty then err("an open-ended slice is written 'a[lo..]'")

      val tr = analyzeExpr(receiver)
      val elem = tr.ty match
        case Type.Ref(Type.Array(_, e), false) => e
        case Type.Ref(Type.Array(_, _), true) =>
          err("a slice does not record whether its owner's count is atomic, so a '&sync' array cannot be sliced")
        case w: Type.View               => w.elem
        case Type.Array(_, e)           => e
        case Type.Ptr(Type.Array(_, e)) => e
        case other                      => err(s"cannot slice ${show(other)}")

      // Part of a string is a string, not a `[]u8` — the bytes between two character boundaries
      // are still well-formed UTF-8, which is what the check at those boundaries is for.
      val viewTy = if tr.ty == Type.Str then Type.Str else Type.Slice(elem)

      TSlice(tr, lo.map(bound), hi.map(bound), inclusive, viewTy)

    case Index(receiver, index) =>
      val tr   = autoDeref(analyzeExpr(receiver))
      val elem = Type.element(tr.ty).getOrElse(err(s"cannot index ${show(tr.ty)}"))
      val ti   = analyzeExpr(index, Some(Type.Usize))

      ti.ty match
        case _: Type.Integer => TIndex(tr, ti, elem)
        case other           => err(s"an index must be an integer, not ${show(other)}")

    case IfExpr(cond, thenBody, elseOpt) =>
      val tc    = analyzeBool(cond)
      val tThen = analyzeValueBlock(thenBody, expected)
      val tElse = elseOpt.map(analyzeValueBlock(_, expected))
      val ty = tElse match
        case Some(eb) if eb.ty == tThen.ty                                                => tThen.ty
        case Some(eb) if eb.ty != Type.Unit && tThen.ty != Type.Unit && eb.ty != tThen.ty =>
          err(s"if branches have different types: ${show(tThen.ty)} and ${show(eb.ty)}")
        case _ => Type.Unit
      TIf(tc, tThen, tElse, ty)

    case MatchExpr(scrut, arms) =>
      val ts    = analyzeExpr(scrut)
      val tarms = arms.map(analyzeArm(ts.ty, _, expected))
      TMatch(ts, tarms, matchResultType(ts.ty, tarms))

    case While(label, cond, body, elseOpt) =>
      val tc            = analyzeBool(cond)
      val (tbody, ctx)  = analyzeLoopBody(expected, label)(analyzeStmts(body))
      val telse         = elseOpt.map(analyzeValueBlock(_, expected))
      TWhile(tc, tbody, telse, loopResultType(ctx, telse))

    case For(label, name, iter, body, elseOpt) =>
      iter match
        case RangeExpr(Some(lo), Some(hi), inclusive) =>
          val List(tlo, thi) = analyzeOperands(List(lo, hi), None)
          if tlo.ty != thi.ty then
            err(s"a 'for' range needs matching bounds, got ${show(tlo.ty)} and ${show(thi.ty)}")
          val vty = tlo.ty match
            case i: Type.Integer => i
            case other           => err(s"a 'for' range iterates integer bounds, not ${show(other)}")
          pushScope()
          val u            = declare(name, vty)
          val (tb, ctx)    = analyzeLoopBody(expected, label)(body.map(analyzeStmt(_)))
          popScope()
          val telse        = elseOpt.map(analyzeValueBlock(_, expected))
          TFor(u, vty, tlo, thi, inclusive, tb, telse, loopResultType(ctx, telse))

        case _ =>
          val seq = autoDeref(analyzeExpr(iter))
          val elem = seq.ty match
            case Type.Array(_, e) => e
            case Type.Slice(e)    => e
            // A string has two granularities and no reason to prefer one silently, so which one
            // is wanted is written: `s.bytes` today, `s.chars` when there are characters.
            case Type.Str =>
              err("a string is iterated as 's.bytes', since a string has bytes and characters both")
            case other =>
              err(s"'for' iterates an integer range, an array, or a slice, not ${show(other)}")
          pushScope()
          val u         = declare(name, elem)
          val (tb, ctx) = analyzeLoopBody(expected, label)(body.map(analyzeStmt(_)))
          popScope()
          val telse     = elseOpt.map(analyzeValueBlock(_, expected))
          TForEach(u, elem, seq, tb, telse, loopResultType(ctx, telse))

    case TryExpr(e) =>
      analyzeTry(analyzeExpr(e))

    case _: RangeExpr =>
      err("a range is only allowed in a 'for' loop or a 'match' pattern")

    case _: Tuple => err("tuples are not supported yet")

  /** One end of a slice range: an index like any other, so any integer will do. */
  private def bound(e: Expr): TExpr = {
    val t = analyzeExpr(e, Some(Type.Usize))

    t.ty match
      case _: Type.Integer => t
      case other           => err(s"a slice bound must be an integer, not ${show(other)}")
  }

  private def incDec(op: String, target: Expr, pre: Boolean): TExpr = {
    val place = analyzePlace(target, s"'$op'")

    place.ty match
      case i: Type.Integer => TIncDec(place, op, pre, i)
      case other           => err(s"'$op' is not defined for ${show(other)}")
  }

  // --- places --------------------------------------------------------------------------

  /** Whether a typed expression denotes a **place** — something with an address, which can be
   * assigned through and pointed at. A local, a dereference, and a field of either are places;
   * anything computed (a call result, an arithmetic result, a freshly built struct) is not.
   */
  protected def isPlace(t: TExpr): Boolean = t match
    case _: TLoad           => true
    case _: TDeref          => true
    case TField(recv, _, _) => isPlace(recv)
    // A slice's elements live wherever its owner keeps them, so they have an address even when
    // the slice itself is a temporary. An array's elements are the array, so they do not.
    case TIndex(recv, _, _) =>
      recv.ty match
        case _: Type.Slice => true
        case Type.Str      => false
        case _             => isPlace(recv)
    case _ => false

  /** Analyzes something that must be a place — an assignment target or the operand of `&`. */
  private def analyzePlace(target: Expr, what: String): TExpr = {
    val t = analyzeExpr(target)

    t match
      // A string is immutable, and it is worth saying so rather than reporting the absence of an
      // address: writing one byte of UTF-8 is how a string stops being UTF-8.
      case TIndex(recv, _, _) if recv.ty == Type.Str =>
        err("a string is immutable, so its bytes have no address to write through")
      case _ =>
        if !isPlace(t) then err(s"$what needs a variable, a field, or a dereference — something with an address")

    t
  }

  /** One level of automatic dereference, so a field is selected through a `*T` or a `&T`
   * exactly as it is on the value itself. One level only: reaching through a `**T` is written.
   */
  protected def autoDeref(t: TExpr): TExpr =
    Type.pointee(t.ty) match
      case Some(inner) => TDeref(t, inner)
      case None        => t

  /** How a diagnostic names an assignment target. */
  private def describe(target: Expr): String = target match
    case Ident(n)      => s"'$n'"
    case Field(_, f)   => s"field '$f'"
    case Unary("*", _) => "the place it points at"
    case Index(_, _)   => "this element"
    case _             => "this place"
}

object Analyzer {

  /** Analyzes a program to a typed tree, or returns the first error message. */
  def analyze(program: Program): Either[String, TProgram] =
    try Right(new Analyzer(program).analyze())
    catch case AnalyzerError(msg) => Left(msg)
}
