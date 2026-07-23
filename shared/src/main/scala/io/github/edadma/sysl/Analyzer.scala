package io.github.edadma.sysl

import scala.collection.mutable

/** An error raised by the analyzer: an unknown name, a type mismatch, a wrong arity — any
 * rule that the structural parse cannot catch.
 */
case class AnalyzerError(message: String) extends RuntimeException(message)

/** The semantic pass: it resolves names, checks types, and turns the untyped `Program` into
 * a typed `TProgram` that codegen lowers directly. All diagnostics live here; codegen trusts
 * the tree it is handed.
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
class Analyzer private (program: Program) {

  private val structDecls = mutable.LinkedHashMap.empty[String, StructDecl]
  private val enumDecls   = mutable.LinkedHashMap.empty[String, EnumDecl]
  private val funcDecls   = mutable.LinkedHashMap.empty[String, FuncDecl]

  /** Instantiated types, keyed by their display name (`Point`, `Option[int]`) and held in
   * dependency order — a type is inserted only after the types it contains.
   */
  private val structInsts = mutable.LinkedHashMap.empty[String, Type.Struct]
  private val enumInsts   = mutable.LinkedHashMap.empty[String, Type.Enum]

  /** Instantiations whose fields are still being resolved, each recorded with the indirection
   * depth at which it was entered. A type that reaches itself finds its own entry here; the
   * depth is what decides whether that is a legal cycle (see `cycleCheck`).
   */
  private val resolving = mutable.LinkedHashMap.empty[String, Int]

  /** The same instantiations, by display name, so a recursive occurrence resolves to the object
   * whose fields are still being filled in rather than starting a second one.
   */
  private val inProgress = mutable.LinkedHashMap.empty[String, Type]

  /** How many `*T` / `&T` wrappers the resolver is currently inside. */
  private var indirection = 0

  /** Instantiated function signatures, keyed by the name codegen will emit. */
  private val funcInsts = mutable.LinkedHashMap.empty[String, (List[(String, Type)], Type)]

  /** Instantiations whose body has not been analyzed yet. Queued rather than analyzed inline
   * so an instantiation discovered mid-function does not disturb the enclosing context.
   */
  private val pending = mutable.Queue.empty[(String, FuncDecl, Map[String, Type])]

  /** Every enum variant name maps to its declaring enum, so a bare `Circle(5)` or `Empty`
   * resolves without qualification. Variant names are therefore unique across all enums.
   */
  private val variantOwner = mutable.LinkedHashMap.empty[String, String]

  // Per-function state, reset at each function boundary.
  private var scopes: List[mutable.LinkedHashMap[String, (String, Type)]] = Nil
  private val used                                                        = mutable.HashSet.empty[String]
  private var retTy: Type                                                 = Type.Unit
  private var tsubst: Map[String, Type]                                   = Map.empty

  private def err(msg: String): Nothing = throw AnalyzerError(msg)

  private def show(t: Type): String = Type.show(t)

  // --- scopes and unique naming --------------------------------------------------------

  private def pushScope(): Unit = scopes = mutable.LinkedHashMap.empty[String, (String, Type)] :: scopes
  private def popScope(): Unit  = scopes = scopes.tail

  private def resetFunction(): Unit = {
    used.clear()
    scopes = List(mutable.LinkedHashMap.empty[String, (String, Type)])
  }

  private def freshName(base: String): String =
    if !used(base) then { used += base; base }
    else {
      var k = 1
      while used(s"$base.$k") do k += 1
      val n = s"$base.$k"
      used += n
      n
    }

  private def declare(name: String, ty: Type): String = {
    val unique = freshName(name)
    scopes.head(name) = (unique, ty)
    unique
  }

  private def lookupOpt(name: String): Option[(String, Type)] =
    scopes.collectFirst { case s if s.contains(name) => s(name) }

  private def lookup(name: String): (String, Type) =
    lookupOpt(name).getOrElse(err(s"undefined name '$name'"))

  // --- type resolution -----------------------------------------------------------------

  /** Resolves a type in the current function's substitution — the identity map outside a
   * generic instantiation.
   */
  private def rt(t: TypeRef): Type = resolveType(t, tsubst)

  /** Resolves a type reference under `subst`, which maps the enclosing declaration's type
   * parameters to the arguments it was instantiated with.
   */
  private def resolveType(t: TypeRef, subst: Map[String, Type]): Type = t match
    case PtrType(inner)       => Type.Ptr(underIndirection(resolveType(inner, subst)))
    case RefType(inner, sync) => Type.Ref(underIndirection(resolveType(inner, subst)), sync)

    // An array holds its elements, so it is no indirection at all and a type cannot contain an
    // array of itself. A slice only points at them, so it breaks a cycle exactly as `*T` does.
    case ArrayType(None, elem) => Type.Slice(underIndirection(resolveType(elem, subst)))
    case ArrayType(Some(len), elem) =>
      val n = len match
        case IntLit(v, _) if v >= 0 && v.isValidInt => v.toInt
        case IntLit(v, _)                           => err(s"an array cannot have $v elements")
        case _ => err("an array length must be an integer literal")
      Type.Array(n, resolveType(elem, subst))

    case NamedType(n, argRefs) =>
      if argRefs.isEmpty && subst.contains(n) then subst(n)
      else
        val targs = argRefs.map(resolveType(_, subst))
        scalarType(n) match
          case Some(s)                        => plain(n, targs, s)
          case None if structDecls.contains(n) => instantiateStruct(n, targs)
          case None if enumDecls.contains(n)   => instantiateEnum(n, targs)
          case None                            => err(s"unknown type '$n'")

  /** Resolves the pointee of a `*T` / `&T`, which is one level further from the layout of
   * whatever type is currently being laid out.
   */
  private def underIndirection(resolve: => Type): Type = {
    indirection += 1
    try resolve
    finally indirection -= 1
  }

  /** Whether reaching an in-progress instantiation again is a legal cycle. It is exactly when
   * at least one indirection was crossed on the way back to it: a `Node` holding a `*Node` is
   * pointer-sized, while a `Node` holding a `Node` by value has no finite size.
   */
  private def cycleCheck(key: String): Unit =
    if indirection <= resolving(key) then err(s"type '$key' contains itself, so it has no finite size")

  /** Resolves a scalar type name: the named primitives and friendly aliases, or one of the
   * systematic `iN` / `uN` / `fN` width spellings.
   */
  private def scalarType(name: String): Option[Type] =
    Type.scalars.get(name).orElse(widthType(name))

  /** `i5`, `u12`, `f32` — a family letter followed by a width. The integer family is open,
   * so it is recognised by shape rather than listed; a width the back end cannot lower is a
   * diagnostic, not an unknown name.
   */
  private def widthType(name: String): Option[Type] = {
    val digits = name.drop(1)

    if name.length < 2 || !"iuf".contains(name.head) then None
    else if digits.head == '0' || !digits.forall(c => c >= '0' && c <= '9') then None
    else
      val bits = digits.toIntOption.getOrElse(err(s"'$name' is far wider than anything can hold"))

      if name.head == 'f' then
        if bits == 16 || bits == 32 || bits == 64 then Some(Type.Floating(bits))
        else if bits == 128 then err("'f128' is not lowered yet — the widest float is 'f64'")
        else err(s"'$name' is not an IEEE floating-point width; they are f16, f32, and f64")
      else if bits >= 1 && bits <= 64 then Some(Type.Integer(bits, signed = name.head == 'i'))
      else err(s"'$name' is wider than the 64 bits the back end lowers")
  }

  private def plain(name: String, targs: List[Type], ty: Type): Type =
    if targs.nonEmpty then err(s"type '$name' does not take type arguments") else ty

  private def checkArity(name: String, tparams: List[String], targs: List[Type]): Unit =
    if tparams.length != targs.length then
      if tparams.isEmpty then err(s"type '$name' does not take type arguments")
      else err(s"type '$name' takes ${tparams.length} type arguments, but ${targs.length} were given")

  /** Instantiates a struct for one set of type arguments, memoized on the display name. The
   * instantiation is registered *before* its fields are resolved, so a field that points back
   * at the struct finds it and the recursion terminates; `cycleCheck` is what rejects the cycle
   * that has no indirection to break it.
   */
  private def instantiateStruct(name: String, targs: List[Type]): Type.Struct = {
    val decl = structDecls(name)
    checkArity(name, decl.tparams, targs)
    val key = Type.qualified(name, targs)

    structInsts.get(key) match
      case Some(s) => s
      case None if inProgress.contains(key) =>
        cycleCheck(key)
        inProgress(key).asInstanceOf[Type.Struct]
      case None =>
        val s = new Type.Struct(name, targs)
        inProgress(key) = s
        resolving(key) = indirection
        val subst = decl.tparams.zip(targs).toMap
        s.fields = decl.fields.map(f => (f.name, resolveType(f.typ, subst)))
        resolving -= key
        inProgress -= key
        structInsts(key) = s
        s
  }

  /** Instantiates an enum for one set of type arguments. All-dataless variants make a *simple*
   * enum (integer constants, auto-incrementing from an optional explicit `= value`); any
   * data-carrying variant makes a *data* enum, whose variants take sequential tags and whose
   * payload-bearing variants each claim a slot in the aggregate.
   */
  private def instantiateEnum(name: String, targs: List[Type]): Type.Enum = {
    val decl = enumDecls(name)
    checkArity(name, decl.tparams, targs)
    val key = Type.qualified(name, targs)

    enumInsts.get(key) match
      case Some(en) => en
      case None if inProgress.contains(key) =>
        cycleCheck(key)
        inProgress(key).asInstanceOf[Type.Enum]
      case None =>
        val en = new Type.Enum(name, targs)
        en.simple = decl.variants.forall(_.fields.isEmpty)
        inProgress(key) = en
        resolving(key) = indirection

        val subst    = decl.tparams.zip(targs).toMap
        var nextTag  = 0
        var nextSlot = 1
        en.variants = decl.variants.map { v =>
          if en.simple then
            val tag = v.value match
              case Some(IntLit(n, _)) => n.toInt
              case Some(_)            => err(s"the value of variant '${v.name}' must be an integer literal")
              case None               => nextTag
            nextTag = tag + 1
            Type.EnumVariant(v.name, tag, Nil, None)
          else
            if v.value.isDefined then
              err(s"variant '${v.name}' carries data, so it cannot also have an explicit value")
            val tag    = nextTag; nextTag += 1
            val fields = v.fields.map(f => (f.name, resolveType(f.typ, subst)))
            val slot   = if fields.nonEmpty then { val s = nextSlot; nextSlot += 1; Some(s) } else None
            Type.EnumVariant(v.name, tag, fields, slot)
        }

        resolving -= key
        inProgress -= key
        enumInsts(key) = en
        en
  }

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
          enumDecls(e.name) = e
          for v <- e.variants do
            if variantOwner.contains(v.name) then
              err(s"variant name '${v.name}' is already used by enum '${variantOwner(v.name)}'")
            variantOwner(v.name) = e.name
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

    val tfuncs = mutable.ListBuffer.empty[TFunc]

    for f <- body.collect { case f: FuncDecl if f.tparams.isEmpty => f } do
      tfuncs += analyzeFuncBody(f.name, f, Map.empty)

    val mainStmts = program.body.filter {
      case _: FuncDecl | _: StructDecl | _: EnumDecl => false
      case _                                         => true
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
    structDecls.contains(name) || enumDecls.contains(name) || scalarType(name).isDefined

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
  private def instantiateFunc(f: FuncDecl, targs: List[Type]): String = {
    val name = Type.mangled(f.name, targs)

    if !funcInsts.contains(name) then
      val subst = f.tparams.zip(targs).toMap
      funcInsts(name) =
        (f.params.map(p => (p.name, resolveType(p.typ, subst))),
         f.retType.map(resolveType(_, subst)).getOrElse(Type.Unit))
      pending.enqueue((name, f, subst))

    name
  }

  /** Matches a declaration's type reference against an actual type, binding whatever type
   * parameters it determines. Deliberately lenient: a structural mismatch simply leaves the
   * parameter unbound, and the argument is type-checked properly against the instantiated
   * signature afterwards, where the message can name both types.
   */
  private def unify(
      ref: TypeRef,
      actual: Type,
      tparams: Set[String],
      sub: mutable.Map[String, Type],
  ): Unit = ref match
    case PtrType(inner) =>
      actual match
        case Type.Ptr(t) => unify(inner, t, tparams, sub)
        case _           => ()
    // A `&T` parameter also accepts a bare `T`, which the call boxes, so the payload type is
    // matched against either shape.
    case RefType(inner, sync) =>
      actual match
        case Type.Ref(t, s) if s == sync => unify(inner, t, tparams, sub)
        case _: Type.Ref                 => ()
        case t                           => unify(inner, t, tparams, sub)
    case ArrayType(None, elem) =>
      actual match
        case Type.Slice(e) => unify(elem, e, tparams, sub)
        case _             => ()
    case ArrayType(Some(_), elem) =>
      actual match
        case Type.Array(_, e) => unify(elem, e, tparams, sub)
        case _                => ()
    case NamedType(n, Nil) if tparams(n) =>
      if !sub.contains(n) then sub(n) = actual
    case NamedType(n, argRefs) =>
      actual match
        case s: Type.Struct if s.base == n && s.targs.length == argRefs.length =>
          argRefs.zip(s.targs).foreach { case (r, t) => unify(r, t, tparams, sub) }
        case e: Type.Enum if e.base == n && e.targs.length == argRefs.length =>
          argRefs.zip(e.targs).foreach { case (r, t) => unify(r, t, tparams, sub) }
        case _ => ()

  /** Solves a generic declaration's type arguments from the argument types, falling back to
   * the expected type of the whole expression for parameters the arguments do not determine.
   */
  private def solve(
      what: String,
      tparams: List[String],
      paramRefs: List[TypeRef],
      argTys: List[Type],
      resultRef: Option[TypeRef],
      expected: Option[Type],
  ): List[Type] = {
    val sub = mutable.LinkedHashMap.empty[String, Type]
    val tps = tparams.toSet

    for (r, t) <- paramRefs.zip(argTys) do unify(r, t, tps, sub)
    if sub.size < tparams.length then
      for r <- resultRef; e <- expected do unify(r, e, tps, sub)

    tparams.map(tp =>
      sub.getOrElse(tp, err(s"cannot infer the type argument '$tp' of '$what' here — annotate the expected type")),
    )
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
  private def analyzeBlockBody(stmts: List[Stmt], expected: Option[Type]): TBlock =
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

    case While(cond, body) =>
      TWhile(analyzeBool(cond), analyzeStmts(body))

    case For(name, iter, body) =>
      iter match
        case RangeExpr(Some(lo), Some(hi), inclusive) =>
          val List(tlo, thi) = analyzeOperands(List(lo, hi), None)
          if tlo.ty != thi.ty then
            err(s"a 'for' range needs matching bounds, got ${show(tlo.ty)} and ${show(thi.ty)}")
          val ty = tlo.ty match
            case i: Type.Integer => i
            case other           => err(s"a 'for' range iterates integer bounds, not ${show(other)}")
          pushScope()
          val u  = declare(name, ty)
          val tb = body.map(analyzeStmt(_))
          popScope()
          TFor(u, ty, tlo, thi, inclusive, tb)

        case _ =>
          val seq = autoDeref(analyzeExpr(iter))
          val elem = Type.element(seq.ty).getOrElse(
            err(s"'for' iterates an integer range, an array, or a slice, not ${show(seq.ty)}"),
          )
          pushScope()
          val u  = declare(name, elem)
          val tb = body.map(analyzeStmt(_))
          popScope()
          TForEach(u, elem, seq, tb)

    case Return(opt) =>
      val tv = opt.map(analyzeExpr(_, Some(retTy)))
      tv match
        case Some(_) if retTy == Type.Unit => err("cannot return a value from a function with no return type")
        case Some(t) if t.ty != retTy      => err(s"return type mismatch: expected ${show(retTy)}, got ${show(t.ty)}")
        case None if retTy != Type.Unit    => err(s"this function must return a ${show(retTy)} value")
        case _                             =>
      TReturn(tv)

    case _: FuncDecl | _: StructDecl | _: EnumDecl =>
      err("functions, structs, and enums may only be declared at the top level")

  // --- expressions ---------------------------------------------------------------------

  private def analyzeBool(e: Expr): TExpr = {
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
  private def analyzeExpr(expr: Expr, expected: Option[Type] = None): TExpr = expected match
    case Some(r: Type.Ref) =>
      expr match
        case NullLit() => err(s"a ${show(r)} always points at a live object — an absent one is Option[${show(r)}]")
        case _         => box(analyzeValue(expr, Some(r.inner)), r)
    case _ => analyzeValue(expr, expected)

  /** Boxes a value the context wanted by reference. Nothing else coerces: a mismatch that is
   * not exactly "a `T` where `&T` was expected" is left for the caller to diagnose.
   */
  private def box(t: TExpr, expected: Type): TExpr = expected match
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

    // A conversion is written with call syntax, so a scalar type name in call position is one.
    case Call(Ident(name), args) if lookupOpt(name).isEmpty && scalarType(name).isDefined =>
      if args.length != 1 then err(s"a '$name' conversion takes exactly one value")
      convert(analyzeExpr(args.head), scalarType(name).get)

    case Call(Ident(name), args) if lookupOpt(name).isEmpty && variantOwner.contains(name) =>
      constructVariant(name, args, expected)

    case Call(Ident(name), args) if lookupOpt(name).isEmpty && structDecls.contains(name) =>
      constructStruct(name, args, expected)

    case Call(Ident(name), args) if funcDecls.contains(name) =>
      callFunction(funcDecls(name), args, expected)

    case Call(Ident(name), _) =>
      err(s"undefined function '$name'")

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
          if idx < 0 then err(s"struct '${s.name}' has no field '$f'")
          TField(tr, idx, s.fields(idx)._2)
        // A length is a property of the value rather than a computation over it, so it reads as
        // a field. It becomes an ordinary method the day methods exist.
        case _: Type.Array | _: Type.Slice if f == "len" => TLen(tr)
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
        case Type.Slice(e) => e
        case _: Type.Array | Type.Ptr(_: Type.Array) =>
          err(
            "a slice of an array this frame owns cannot yet be shown to outlive it — " +
              "put the storage on the heap as '&[N]T', or index the array directly",
          )
        case other => err(s"cannot slice ${show(other)}")

      TSlice(tr, lo.map(bound), hi.map(bound), inclusive, Type.Slice(elem))

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

    case TryExpr(e) =>
      analyzeTry(analyzeExpr(e))

    case _: RangeExpr =>
      err("a range is only allowed in a 'for' loop or a 'match' pattern")

    case _: Tuple => err("tuples are not supported yet")

  /** Whether a type has a zero value, which is what a declaration with no initializer starts
   * at. A reference has none — it always points at a live object — and neither does an enum,
   * whose zeroed tag names no variant in particular.
   */
  private def hasZero(t: Type): Boolean = t match
    case _: Type.Integer | _: Type.Floating | Type.Char | Type.Bool | _: Type.Ptr => true
    // A zeroed slice owns nothing and names no elements, which is exactly the empty slice.
    case _: Type.Slice       => true
    case Type.Array(_, elem) => hasZero(elem)
    case s: Type.Struct      => s.fields.forall(f => hasZero(f._2))
    case _                   => false

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
  private def isPlace(t: TExpr): Boolean = t match
    case _: TLoad           => true
    case _: TDeref          => true
    case TField(recv, _, _) => isPlace(recv)
    // A slice's elements live wherever its owner keeps them, so they have an address even when
    // the slice itself is a temporary. An array's elements are the array, so they do not.
    case TIndex(recv, _, _) => recv.ty.isInstanceOf[Type.Slice] || isPlace(recv)
    case _                  => false

  /** Analyzes something that must be a place — an assignment target or the operand of `&`. */
  private def analyzePlace(target: Expr, what: String): TExpr = {
    val t = analyzeExpr(target)
    if !isPlace(t) then err(s"$what needs a variable, a field, or a dereference — something with an address")
    t
  }

  /** One level of automatic dereference, so a field is selected through a `*T` or a `&T`
   * exactly as it is on the value itself. One level only: reaching through a `**T` is written.
   */
  private def autoDeref(t: TExpr): TExpr =
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

  // --- literals ------------------------------------------------------------------------

  /** An integer literal takes its type from its suffix, else from the type the context
   * expects, else `int`. The default is never magnitude-dependent: a value too large for the
   * type it landed in is an error asking for a suffix, not a silent widening.
   */
  private def intLiteral(value: BigInt, suffix: Option[String], expected: Option[Type]): TExpr = {
    val ty = suffix match
      case Some(s) =>
        scalarType(s) match
          case Some(i: Type.Integer) => i
          case _                     => err(s"'$s' is not an integer type")
      case None =>
        expected match
          case Some(i: Type.Integer) => i
          case _                     => Type.Int

    if !Type.fits(value, ty) then err(s"the literal $value does not fit ${show(ty)}")

    TIntLit(value, ty)
  }

  /** A float literal takes its type from its suffix, else from the expected type when that is
   * a float, else `real`. An integer literal never becomes a float on its own — writing `1`
   * where a `real` is wanted is a type error, not a silent conversion.
   */
  private def floatLiteral(text: String, suffix: Option[String], expected: Option[Type]): TExpr = {
    val ty = suffix match
      case Some(s) =>
        scalarType(s) match
          case Some(f: Type.Floating) => f
          case _                      => err(s"'$s' is not a floating-point type")
      case None =>
        expected match
          case Some(f: Type.Floating) => f
          case _                      => Type.Real

    TFloatLit(hexDouble(text), ty)
  }

  /** Analyzes operands that must share one type. A bare literal has no type of its own, so it
   * takes the type of a non-literal neighbour — which is what lets `n + 1` work for an `n` of
   * any width without the literal needing a suffix, and `p == null` work for any `*T`. The
   * non-literals are analyzed first precisely so their type is available to the literals.
   */
  private def analyzeOperands(operands: List[Expr], expected: Option[Type]): List[TExpr] = {
    val fixed = operands.map(e => Option.when(!isLiteral(e))(analyzeExpr(e, expected)))
    val ty    = fixed.flatten.headOption.map(_.ty).orElse(expected)

    operands.zip(fixed).map {
      case (_, Some(t)) => t
      case (e, None)    => analyzeExpr(e, ty)
    }
  }

  /** Whether an expression is a literal with no type of its own. A suffixed numeric literal
   * has already said what it is, so it counts as fixed rather than adaptable.
   */
  private def isLiteral(e: Expr): Boolean = e match
    case IntLit(_, None) | FloatLit(_, None) => true
    case NullLit()                           => true
    case Unary("-", operand)                 => isLiteral(operand)
    case _                                   => false

  /** An explicit scalar conversion. Every pair that has a meaning is listed; nothing widens,
   * narrows, or changes representation without being written.
   */
  private def convert(t: TExpr, to: Type): TExpr = {
    val allowed = (t.ty, to) match
      case (_: Type.Integer, _: Type.Integer)   => true
      case (_: Type.Integer, _: Type.Floating)  => true
      case (_: Type.Floating, _: Type.Integer)  => true
      case (_: Type.Floating, _: Type.Floating) => true
      case (Type.Char, _: Type.Integer)         => true // total: every char is an integer
      case (_: Type.Integer, Type.Char)         => true // partial: traps on a non-scalar value
      case (Type.Char, Type.Char)               => true
      case _                                    => false

    if !allowed then err(s"cannot convert ${show(t.ty)} to ${show(to)}")

    TCast(t, to)
  }

  // --- calls and construction ----------------------------------------------------------

  /** Type-checks positional arguments against a resolved parameter list. `pre` holds arguments
   * already analyzed during type-argument inference, so they are not analyzed twice.
   */
  private def checkArgs(
      what: String,
      params: List[(String, Type)],
      args: List[Expr],
      pre: Option[List[TExpr]],
  ): List[TExpr] = {
    // An argument analyzed during inference was analyzed without an expected type, so a value
    // headed for a `&T` parameter is boxed here instead of at its own analysis.
    val ts = pre match
      case Some(provisional) => provisional.zip(params).map { case (t, (_, pty)) => box(t, pty) }
      case None              => args.zip(params).map { case (a, (_, pty)) => analyzeExpr(a, Some(pty)) }

    for (t, (pname, pty)) <- ts.zip(params) do
      if t.ty != pty then err(s"'$pname' of '$what' is ${show(pty)}, but ${show(t.ty)} was given")

    ts
  }

  private def callFunction(f: FuncDecl, args: List[Expr], expected: Option[Type]): TExpr = {
    if args.length != f.params.length then
      err(s"function '${f.name}' takes ${f.params.length} arguments, but ${args.length} were given")

    val (name, pre) =
      if f.tparams.isEmpty then (f.name, None)
      else
        val provisional = args.map(analyzeExpr(_))
        val targs =
          solve(f.name, f.tparams, f.params.map(_.typ), provisional.map(_.ty), f.retType, expected)
        (instantiateFunc(f, targs), Some(provisional))

    val (params, rtype) = funcInsts(name)
    TCall(name, checkArgs(f.name, params, args, pre), rtype)
  }

  private def constructStruct(name: String, args: List[Expr], expected: Option[Type]): TExpr = {
    val decl = structDecls(name)

    if args.length != decl.fields.length then
      err(s"struct '$name' has ${decl.fields.length} fields, but ${args.length} were given")

    val (targs, pre) =
      if decl.tparams.isEmpty then (Nil, None)
      else
        expected match
          case Some(s: Type.Struct) if s.base == name => (s.targs, None)
          case _ =>
            val provisional = args.map(analyzeExpr(_))
            val targs =
              solve(name, decl.tparams, decl.fields.map(_.typ), provisional.map(_.ty), None, expected)
            (targs, Some(provisional))

    val s = instantiateStruct(name, targs)
    TStructNew(s, checkArgs(s.name, s.fields, args, pre))
  }

  private def constructVariant(name: String, args: List[Expr], expected: Option[Type]): TExpr = {
    val ename = variantOwner(name)
    val decl  = enumDecls(ename)
    val vdecl = decl.variants.find(_.name == name).get

    if vdecl.fields.isEmpty && args.nonEmpty then
      err(s"variant '$name' takes no arguments — write it as '$name'")
    if vdecl.fields.nonEmpty && args.isEmpty then
      err(s"variant '$name' carries data — construct it with '$name(…)'")
    if args.length != vdecl.fields.length then
      err(s"variant '$name' has ${vdecl.fields.length} fields, but ${args.length} were given")

    val (targs, pre) =
      if decl.tparams.isEmpty then (Nil, None)
      else
        expected match
          case Some(e: Type.Enum) if e.base == ename => (e.targs, None)
          case _ =>
            val provisional = args.map(analyzeExpr(_))
            val targs =
              solve(name, decl.tparams, vdecl.fields.map(_.typ), provisional.map(_.ty), None, expected)
            (targs, Some(provisional))

    val en = instantiateEnum(ename, targs)
    val v  = en.variant(name).get
    TEnumNew(en, v, checkArgs(name, v.fields, args, pre))
  }

  /** `expr?` — unwraps an `Option`/`Result`, or returns the enclosing function early with the
   * failure re-wrapped in *its* return type. The two enums must agree, and a propagated error
   * must be the one the function returns.
   */
  private def analyzeTry(t: TExpr): TExpr = {
    val en = t.ty match
      case e: Type.Enum if Prelude.tryVariants(e.base).isDefined => e
      case other => err(s"'?' needs an Option or Result value, not ${show(other)}")

    val (okName, failName) = Prelude.tryVariants(en.base).get

    retTy match
      case ret: Type.Enum if ret.base == en.base =>
        if en.base == "Result" && ret.targs(1) != en.targs(1) then
          err(s"'?' propagates a ${show(en.targs(1))} error, but this function returns ${show(ret.targs(1))}")
        TTry(t, en.variant(okName).get, en.variant(failName).get, ret, ret.variant(failName).get, en.targs.head)
      case other =>
        err(s"'?' may only be used in a function returning ${en.base}, not ${show(other)}")
  }

  // --- match arms and patterns ---------------------------------------------------------

  /** Analyzes one arm in its own scope so pattern bindings are visible to the guard and body.
   * Alternatives (`a | b`) may not bind, since the body cannot know which alternative matched.
   */
  private def analyzeArm(scrutTy: Type, arm: MatchArm, expected: Option[Type]): TArm = {
    pushScope()
    val tpats = arm.patterns.map(analyzePattern(_, scrutTy))
    if tpats.length > 1 && tpats.exists(binds) then
      err("alternative patterns joined by '|' cannot bind a name")
    val tguard = arm.guard.map(analyzeBool)
    val tbody  = analyzeBlockBody(arm.body, expected)
    popScope()
    TArm(tpats, tguard, tbody)
  }

  /** Turns one pattern into its typed form, declaring any bindings into the current scope. A
   * bare name is a nullary-variant pattern when it names a variant of the scrutinee's enum, and
   * a binding otherwise.
   */
  private def analyzePattern(p: Pattern, ty: Type): TPattern = p match
    case WildcardPattern => TWildPattern(ty)

    case LitPattern(v) =>
      val t = analyzeExpr(v, Some(ty))
      if t.ty != ty then err(s"pattern is ${show(t.ty)} but the value is ${show(ty)}")
      if !Type.isOrdered(ty) then err(s"a ${show(ty)} value cannot be matched against a literal yet")
      TLitPattern(t)

    case RangePattern(lo, hi, inclusive) =>
      if !Type.isOrdered(ty) then err(s"a range pattern needs an ordered value, not ${show(ty)}")
      val tl = analyzeExpr(lo, Some(ty))
      val th = analyzeExpr(hi, Some(ty))
      if tl.ty != ty || th.ty != ty then err(s"range pattern must match the ${show(ty)} value")
      TRangePattern(tl, th, inclusive)

    case IdentPattern(name) =>
      ty match
        case en: Type.Enum if en.variant(name).exists(_.fields.isEmpty) =>
          TVariantPattern(en, en.variant(name).get, Nil)
        case en: Type.Enum if en.variant(name).isDefined =>
          err(s"variant '$name' carries data — match it as '$name(…)'")
        case _ =>
          TBindPattern(declare(name, ty), ty)

    case VariantPattern(name, args) =>
      ty match
        case en: Type.Enum =>
          en.variant(name) match
            case Some(v) if v.fields.isEmpty =>
              err(s"variant '$name' takes no arguments — match it as '$name'")
            case Some(v) =>
              if args.length != v.fields.length then
                err(s"variant '$name' has ${v.fields.length} fields, but ${args.length} sub-patterns were given")
              TVariantPattern(en, v, args.zip(v.fields).map { case (a, (_, fty)) => analyzePattern(a, fty) })
            case None =>
              err(s"enum '${en.name}' has no variant '$name'")
        case other =>
          err(s"'$name(…)' matches an enum variant, but the value is ${show(other)}")

  /** Whether a pattern binds any name (directly or inside a variant's sub-patterns). */
  private def binds(p: TPattern): Boolean = p match
    case _: TBindPattern    => true
    case v: TVariantPattern => v.args.exists(binds)
    case _                  => false

  /** A pattern that always matches, so an unguarded arm carrying it is a catch-all. */
  private def irrefutable(p: TPattern): Boolean = p match
    case _: TWildPattern | _: TBindPattern => true
    case _                                 => false

  /** Checks exhaustiveness and returns the value type of a match (`unit` unless every arm
   * yields the same non-unit type). An enum match must cover every variant or carry a
   * catch-all; a scalar match need only be exhaustive when it is used for a value.
   */
  private def matchResultType(scrutTy: Type, arms: List[TArm]): Type = {
    val bodyTys = arms.map(_.body.ty).distinct
    val valueTy = if bodyTys.size == 1 && bodyTys.head != Type.Unit then bodyTys.head else Type.Unit

    val hasCatchAll = arms.exists(a => a.guard.isEmpty && a.patterns.exists(irrefutable))

    scrutTy match
      case en: Type.Enum =>
        val covered = arms
          .filter(_.guard.isEmpty)
          .flatMap(_.patterns)
          .collect { case v: TVariantPattern if v.args.forall(irrefutable) => v.variant.tag }
          .toSet
        if !hasCatchAll && !en.variants.map(_.tag).toSet.subsetOf(covered) then
          val missing = en.variants.filterNot(v => covered(v.tag)).map(_.name)
          err(s"match on '${en.name}' is not exhaustive; missing ${missing.mkString(", ")} (add an 'else' arm)")
      case _ =>
        if valueTy != Type.Unit && !hasCatchAll then
          err("a 'match' that yields a value must be exhaustive — add an 'else' arm")

    valueTy
  }

  /** The result type of an arithmetic or bitwise binary operator. Operands must already have
   * the same type — there is no implicit promotion, so a mixed-width expression is an error
   * asking for a conversion rather than a silent widening.
   */
  private def arithType(op: String, a: Type, b: Type): Type = {
    if a != b then err(s"'$op' needs matching types, got ${show(a)} and ${show(b)}")
    (a, op) match
      case (_: Type.Integer, "+" | "-" | "*" | "/" | "%" | "<<" | ">>" | "&" | "|" | "^") => a
      case (_: Type.Floating, "+" | "-" | "*" | "/")                                      => a
      case _ => err(s"operator '$op' is not defined for ${show(a)}")
  }

  /** Renders a decimal float literal as an LLVM hex double — the textual form that survives
   * the round-trip without losing bits.
   */
  private def hexDouble(text: String): String =
    f"0x${java.lang.Double.doubleToLongBits(text.toDouble)}%016X"
}

object Analyzer {

  /** Analyzes a program to a typed tree, or returns the first error message. */
  def analyze(program: Program): Either[String, TProgram] =
    try Right(new Analyzer(program).analyze())
    catch case AnalyzerError(msg) => Left(msg)
}
