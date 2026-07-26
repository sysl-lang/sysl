package io.github.edadma.sysl

import scala.collection.mutable

/** The semantic pass: it resolves names, checks types, and turns the untyped `Program` into
 * a typed `TProgram` that codegen lowers directly. All diagnostics live here; codegen trusts
 * the tree it is handed.
 *
 * The work is split across traits mixed into this class, the way codegen is split across
 * `Emitter` and friends: `AnalyzerBase` holds the shared tables and name scopes, `TypeResolution`
 * resolves and instantiates types, `Literals` handles the scalar leaves, `Hoisting` registers
 * declarations, `StmtAnalysis` handles statements and blocks, `CallAnalysis` handles calls and
 * construction, `PatternAnalysis` handles `match`, and `SpecialForms` holds the handful of call
 * forms the compiler resolves by name. What stays here is the spine — the driver that runs the
 * passes in order, function bodies, the expression dispatch, and places — plus the recursive entry
 * points the traits call back into through `AnalyzerBase`'s hooks.
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
class Analyzer private (program: Program)
    extends CallAnalysis
    with PatternAnalysis
    with Literals
    with Hoisting
    with StmtAnalysis
    with SpecialForms {

  /** Every error the walk found, rendered and in source order. */
  def errors: List[String] = diagnostics

  // --- program -------------------------------------------------------------------------

  private def analyze(): TProgram = {
    val body = Prelude.decls ::: program.body

    // Each declaration, each function body, and each statement is a **recovery region**: a
    // failure inside one is recorded and the region abandoned, and the walk resumes at the next.
    // That is what turns one error per compilation into one error per mistake.
    //
    // Hoisting runs at the top level, where there is no enclosing position to return to, so each
    // pass simply moves the cursor to the declaration it is registering.
    for stmt <- body do
      currentPos = stmt.pos
      recover(())(hoistType(stmt))

    // A non-generic type is instantiated eagerly, so it is emitted whether or not it is used;
    // a generic one only exists once something asks for a concrete instantiation.
    for (n, d) <- enumDecls if d.tparams.isEmpty do recover(())(instantiateEnum(n, Nil))
    for (n, d) <- structDecls if d.tparams.isEmpty do recover(())(instantiateStruct(n, Nil))

    for stmt <- body do
      currentPos = stmt.pos
      recover(())(hoistFunc(stmt))

    // A type's members lower to ordinary functions under mangled names, registered here so a
    // method call and an associated-function call resolve exactly as a free call does.
    val members = mutable.ListBuffer.empty[FuncDecl]
    for (tname, sdecl) <- structDecls do at(sdecl.pos)(recover(())(hoistMembers(tname, sdecl.members, members)))
    for (tname, edecl) <- enumDecls do at(edecl.pos)(recover(())(hoistMembers(tname, edecl.members, members)))
    for impl <- implDecls do at(impl.pos)(recover(())(hoistImpl(impl, members)))

    // Every generic body is checked once here, against its bounds alone, before any instantiation
    // is looked at. That is what makes `sum[T](a: T, b: T) = a.plus(b)` fail on its own line
    // instead of at whichever call site first supplied a type without a `plus`.
    checkAbstractBodies(body)

    val tfuncs = mutable.ListBuffer.empty[TFunc]

    // A function whose body did not analyze is left out of the program rather than stood in for:
    // there is nothing to emit, and nothing will be emitted at all while an error stands.
    //
    // The prelude's own functions are held back: they are analyzed below, and only if something
    // reaches them. That keeps a program that never prints from carrying the printing surface.
    val (fromPrelude, ours) =
      body.collect { case f: FuncDecl if f.tparams.isEmpty => f }.partition(Prelude.declares)

    for f <- ours do
      tfuncs ++= recoverOpt(analyzeFuncBody(f.name, f, Map.empty))

    for f <- members if !defaultOrigin.get(f.name).exists(brokenDefaults) do
      tfuncs ++= recoverOpt(analyzeFuncBody(f.name, f, Map.empty))

    val mainStmts = program.body.filter {
      case _: FuncDecl | _: StructDecl | _: EnumDecl | _: TraitDecl | _: ImplDecl | _: ExternDecl => false
      case _                                                                                      => true
    }

    resetFunction()
    tsubst = Map.empty
    retTy = Type.Int
    val tmain = mainStmts.map(recoverStmt)

    // Draining the queue may itself discover further instantiations, so it runs to a fixpoint. An
    // instantiation of a member the definition-time pass already reported is dropped rather than
    // analyzed, for the reason that pass exists: the diagnostic naming the missing bound is the one
    // worth reading, and every instantiation would add another about a consequence of it.
    def drain(): Unit =
      while pending.nonEmpty do
        val (mangled, decl, subst) = pending.dequeue()
        val reported = brokenMembers(decl.name) || defaultOrigin.get(decl.name).exists(brokenDefaults)

        if !reported then tfuncs ++= recoverOpt(analyzeFuncBody(mangled, decl, subst))

    drain()

    // A prelude function is analyzed only once something has called it, and analyzing one may call
    // another — `printi` reaches `putbytes`, `printb` reaches `prints` — so this runs to a fixpoint
    // too. Nothing reaches them in a program that never prints, and none of them is emitted.
    val available = fromPrelude.map(f => f.name -> f).toMap
    val analyzed  = mutable.HashSet.empty[String]
    var reached   = true

    while reached do
      reached = false
      for name <- funcsUsed.toList if available.contains(name) && !analyzed(name) do
        analyzed += name
        reached = true
        tfuncs ++= recoverOpt(analyzeFuncBody(name, available(name), Map.empty))
      drain()

    val externs = externsUsed.toList.map { name =>
      val (params, rtype) = funcInsts(name)
      val e               = externDecls(name)
      TExtern(name, e.symbol, params.map(_._2), rtype, e.variadic)
    }

    TProgram(
      structInsts.values.toList,
      enumInsts.values.filterNot(_.simple).toList,
      vtables.values.toList,
      externs,
      tfuncs.toList,
      tmain,
    )
  }

  // --- definition-checked bounds -------------------------------------------------------

  /** Checks every generic body once, at its definition, with each type parameter opaque except for
   * what its bounds promise (`14 §4`). This is the mechanism `10 §5` committed to, and what tells
   * sysl's generics apart from a C++ template: a body that assumes more than it declared is wrong
   * whether or not anything ever instantiates it, and this is where it is told so.
   *
   * Only a declaration that carries its own type parameters is walked. A member of a generic *type*
   * inherits the type's, and those carry no bounds — there is nowhere to write one — so holding such
   * a member to its bounds would be holding it to nothing at all.
   *
   * **A generic `impl`'s members are walked**, and that is the difference a block of its own makes:
   * `impl[T: Show] Show for Box[T]` states what it assumes of `T`, so its methods are checkable
   * before anything instantiates them, exactly as a bounded generic function's body is. That is what
   * conditional conformance buys beyond deciding whether a `Box[int]` conforms.
   *
   * **A trait's default bodies are walked here too**, each as the generic function it is: one
   * parameter, `Self`, bounded by its own trait (`Hoisting.traitDefaults`). A default may assume of
   * its receiver exactly what the trait promises, which is the same rule this pass already enforces
   * — so it is checked at the trait, once, rather than once per implementing type, and a trait with
   * no implementations at all still has its defaults checked.
   */
  private def checkAbstractBodies(body: List[Stmt]): Unit = {
    val generics = body.collect { case f: FuncDecl if f.tparams.nonEmpty => f }
    val members  = abstractMembers.toList
    val defaults = traitDefaults

    if generics.nonEmpty || members.nonEmpty || defaults.nonEmpty then
      sandboxed {
        abstractPass = true

        try
          for f <- generics do
            currentPos = f.pos
            recover(())(checkAbstractBody(f))

          // A member reported here has been reported against the body as written, naming the bound
          // that would license what it does. Every instantiation would fail the same way and say so
          // in terms of whatever type it was made at, so those are dropped instead — one mistake,
          // one diagnostic, in the words that name the fix.
          for f <- members do
            currentPos = f.pos
            val before = diagnosticCount
            recover(())(checkAbstractBody(f))
            if diagnosticCount > before then brokenMembers += f.name

          // A default that fails here has been reported, at the trait, against the body a
          // programmer actually wrote. The copies made for each implementing type would fail the
          // same way — against the same source line, blaming a type the line does not mention — so
          // they are dropped rather than analyzed, and one mistake stays one diagnostic.
          for f <- defaults do
            currentPos = f.pos
            val before = diagnosticCount
            recover(())(checkAbstractBody(f))
            if diagnosticCount > before then brokenDefaults += f.name
        finally abstractPass = false
      }
  }

  /** One generic body, analyzed with each of its type parameters substituted by itself. */
  private def checkAbstractBody(f: FuncDecl): Unit = at(f.pos) {
    val subst: Map[String, Type] =
      withSelf(f.name, f.tparams.map(tp => tp -> Type.Abstract(tp, f.bounds.getOrElse(tp, Nil))).toMap)
    val params = f.params.map(p => (p.name, recover(Type.Unknown)(resolveType(p.typ, subst))))
    val rtype  = f.retType.map(t => recover(Type.Unknown)(resolveReturn(t, subst))).getOrElse(Type.Unit)

    analyzeBodyWith(f.name, f, subst, params, rtype)
  }

  // --- function bodies -----------------------------------------------------------------

  private def analyzeFuncBody(name: String, f: FuncDecl, subst: Map[String, Type]): TFunc =
    at(f.pos)(analyzeFuncBodyAt(name, f, subst))

  private def analyzeFuncBodyAt(name: String, f: FuncDecl, subst: Map[String, Type]): TFunc = {
    val (params, rtype) = funcInsts(name)

    analyzeBodyWith(name, f, subst, params, rtype)
  }

  /** Analyzes one body against a signature it is handed, rather than one looked up in `funcInsts`.
   * An instantiation's signature is registered there; the definition-time pass of `14 §4` resolves
   * its own, since a generic declaration has no entry until something instantiates it.
   */
  private def analyzeBodyWith(
      name: String,
      f: FuncDecl,
      subst: Map[String, Type],
      params: List[(String, Type)],
      rtype: Type,
  ): TFunc = {
    resetFunction()
    // A member's body sees `Self` alongside whatever type parameters it was instantiated with, so
    // the one substitution answers both questions and nothing downstream has to know the difference.
    tsubst = subst ++ memberSelf.getOrElse(name, Map.empty)
    retTy = rtype
    variadicFn = f.variadic
    val tparams = params.map { case (n, t) => (declare(n, t), t) }
    val tbody   = analyzeValueBlock(f.body, if rtype == Type.Unit then None else Some(rtype))

    if rtype != Type.Unit && tbody.result.isDefined && disagree(tbody.ty, rtype) then
      err(s"function '${f.name}' should return ${show(rtype)}, but its body yields ${show(tbody.ty)}")

    TFunc(name, tparams, rtype, tbody, f.variadic)
  }

  /** A comparison chain, checked link by link. A link the machine performs directly needs its
   * operands to agree and the type to have the comparison being asked of it — equality reaches
   * further than ordering (`01`); a link a trait supplies had both checked against the trait's own
   * signature when `compareLink` resolved it.
   */
  private def compareChain(ts: List[TExpr], cmps: List[TCmp]): TExpr = {
    for i <- cmps.indices if cmps(i).dispatch.isEmpty do
      val op       = cmps(i).op
      val (a, b)   = (ts(i), ts(i + 1))
      val equality = op == "==" || op == "!="
      if a.ty != b.ty then err(s"cannot compare ${show(a.ty)} with ${show(b.ty)}")
      if !(if equality then Type.isEquatable(a.ty) else Type.isOrdered(a.ty)) then
        err(s"'$op' is not defined for ${show(a.ty)}")

    TCompare(ts, cmps)
  }

  /** Registers an instantiation of a generic function and returns the name codegen will emit.
   * The signature is recorded before the body is queued, so a recursive generic function
   * resolves its own call.
   */
  protected def instantiateFunc(f: FuncDecl, targs: List[Type]): String = {
    val name = Type.mangled(f.name, targs)

    if !funcInsts.contains(name) then
      val subst = withSelf(f.name, f.tparams.zip(targs).toMap)
      funcInsts(name) =
        (f.params.map(p => (p.name, resolveType(p.typ, subst))),
         f.retType.map(resolveReturn(_, subst)).getOrElse(Type.Unit))
      pending.enqueue((name, f, subst))

    name
  }

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
  protected def analyzeExpr(expr: Expr, expected: Option[Type]): TExpr = {
    val t = at(expr.pos)(analyzeExpected(expr, expected)).setPos(expr.pos)

    // A value whose type could not be worked out — a name whose declaration failed, a field of a
    // type that did not resolve, a call to a function with an unusable signature — abandons this
    // statement quietly. The mistake was reported where it was made, and every consequence of it
    // reported as well would bury the one diagnostic worth reading.
    if t.ty == Type.Unknown then poisoned()

    t
  }

  private def analyzeExpected(expr: Expr, expected: Option[Type]): TExpr = expected match
    // An `if`/`match`/loop yields its value through its branches — a loop's, through its `break`s
    // and its `else` — so a context that *converts* belongs to each of those rather than to the
    // aggregate: every branch boxes or erases on its own. That is what lets a `&T` branch and a
    // plain-value branch meet at `&T`, and two branches of different concrete types meet at one
    // trait object. Converting the whole expression instead would ask each branch for something it
    // may already be past being able to supply.
    case Some(want) if converts(want) && branching(expr) => analyzeValue(expr, Some(want))

    // A trait object asks the expression for nothing in particular: what may be erased into one is
    // whatever implements the trait, and pushing the object's own type down would be asking for a
    // value of a type that has no layout. `null` is the exception — a raw address is written at
    // the type it is expected to have, rather than converted into it.
    case Some(o) if Type.erased(o) =>
      expr match
        case NullLit() => analyzeValue(expr, Some(o))
        case _         => coerce(analyzeValue(expr, None), o)

    case Some(r: Type.Ref) =>
      expr match
        case NullLit() => err(s"a ${show(r)} always points at a live object — an absent one is Option[${show(r)}]")
        case _         => coerce(analyzeValue(expr, Some(r.inner)), r)
    case _ => analyzeValue(expr, expected)

  /** Whether a context of this type converts what it is given rather than simply requiring it. */
  private def converts(want: Type): Boolean = Type.erased(want) || want.isInstanceOf[Type.Ref]

  /** Whether an expression yields its value through branches rather than producing one itself. */
  private def branching(expr: Expr): Boolean = expr match
    case _: IfExpr | _: MatchExpr | _: While | _: For => true
    case _                                            => false

  /** The two conversions a context may apply to a value that does not already have its type: a
   * `T` the context wanted by reference is boxed, and something concrete where a trait object was
   * wanted is erased into one. Nothing else coerces — any other mismatch is left for the caller to
   * diagnose, where the message can name the parameter or the variable it is about.
   */
  protected def coerce(t: TExpr, expected: Type): TExpr = expected match
    case _ if Type.erased(expected)     => eraseTo(t, expected)
    case r: Type.Ref if t.ty == r.inner => TBox(t, r).setPos(t.pos)
    case _                              => t

  private def analyzeValue(expr: Expr, expected: Option[Type]): TExpr =
    at(expr.pos)(analyzeValueAt(expr, expected)).setPos(expr.pos)

  private def analyzeValueAt(expr: Expr, expected: Option[Type]): TExpr = expr match
    case IntLit(v, suffix)   => intLiteral(v, suffix, expected)
    case FloatLit(t, suffix) => floatLiteral(t, suffix, expected)
    case CharLit(cp)         => TIntLit(cp, Type.Char)
    case StrLit(s)           => TStrLit(s)
    // A C callee finds the end by the terminator, so an interior NUL would hide everything written
    // after it. Refused outright rather than silently truncated — an ordinary `"a\0b"` is unaffected,
    // since carrying a length is exactly what lets it hold one.
    case CStrLit(s) =>
      if s.indexOf(0) >= 0 then
        err("a C string ends at its first NUL, so it cannot contain one — the bytes after it could never be read")
      TCStrLit(s)
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
        case Some((u, ty))                       => TLoad(u, ty)
        case None if variantOwner.contains(name) => constructVariant(name, Nil, expected)
        case None                                => err(s"undefined name '$name'")

    case Binary(op @ ("&&" | "||"), l, r) =>
      TLogical(op, analyzeBool(l), analyzeBool(r))

    case Binary(op, l, r) =>
      val List(tl, tr) = analyzeOperands(List(l, r), expected.filter(Type.isNumeric))
      operatorCall(op, tl, tr).getOrElse(TBinary(op, tl, tr, arithType(op, tl.ty, tr.ty)))

    case Unary("-", e) =>
      val t = analyzeExpr(e, expected.filter(Type.isNumeric))
      prefixCall("-", t).getOrElse(t.ty match
        case i: Type.Integer if i.signed => TUnary("-", t, i)
        case f: Type.Floating            => TUnary("-", t, f)
        case i: Type.Integer             => err(s"unary '-' is not defined for the unsigned type ${show(i)}")
        case other                       => err(s"unary '-' is not defined for ${show(other)}"))

    case Unary("!", e) =>
      TUnary("!", analyzeBool(e), Type.Bool)

    case Unary("~", e) =>
      val t = analyzeExpr(e, expected.filter(Type.isNumeric))
      prefixCall("~", t).getOrElse(t.ty match
        case i: Type.Integer => TUnary("~", t, i)
        case other           => err(s"unary '~' is not defined for ${show(other)}"))

    // Address-of yields a *raw* pointer: a place lives in a frame or inside another object, so
    // there is no refcount to take a share of. Reaching a `&T` means being handed one.
    case Unary("&", e) =>
      val place = analyzePlace(e, "'&'")
      TAddrOf(place, Type.Ptr(place.ty))

    case Unary("*", e) =>
      val t = analyzeExpr(e)
      Type.pointee(t.ty) match
        case Some(inner)                => TDeref(t, inner)
        // A trait object points somewhere, but it has forgotten what is there, so there is no type
        // to read out — its methods are the whole of what it still offers.
        case None if Type.erased(t.ty) =>
          err(s"a ${show(t.ty)} has forgotten what it points at, so there is no value to read " +
            "through it — call one of the trait's methods instead")
        case None                       => err(s"'*' needs a pointer or a reference, not ${show(t.ty)}")

    case Unary(op, _) =>
      err(s"unary '$op' is not supported yet")

    case PreIncDec(op, target)  => incDec(op, target, pre = true)
    case PostIncDec(op, target) => incDec(op, target, pre = false)

    // Each link of the chain is resolved on its own — an instruction where the operand type has
    // one, the method its `Eq`/`Ord` supplies otherwise (`14 §2`) — so a chain of user types reads
    // and behaves exactly as a chain of scalars does, sharing each middle operand between the two
    // comparisons that use it.
    case Compare(operands, ops) =>
      val ts = analyzeOperands(operands, None)

      compareChain(ts, ops.indices.map(i => compareLink(ops(i), ts(i), ts(i + 1))).toList)

    case Assign("=", target, value) =>
      val place = analyzePlace(target, "assignment")
      val tv    = analyzeExpr(value, Some(place.ty))
      if tv.ty != place.ty then
        err(s"cannot assign ${show(tv.ty)} to ${describe(target)} of type ${show(place.ty)}")
      TStore(place, tv, place.ty)

    // `p += q` on a type whose `Add` is a real implementation updates the place from the value it
    // already read, exactly as the scalar form does — the dispatch travels with the node rather
    // than becoming a call tree that would read the place twice.
    case Assign(op, target, value) =>
      val place  = analyzePlace(target, s"'$op'")
      val binSym = op.dropRight(1)
      val tv     = analyzeExpr(value, Some(place.ty))
      val d      = updateDispatch(binSym, place, tv)

      if d.isEmpty && arithType(binSym, place.ty, tv.ty) != place.ty then
        err(s"'$op' would change the type of ${describe(target)}")

      TUpdate(place, op, tv, place.ty, d)

    // The forms the compiler resolves by name rather than by looking a function up: `print` and
    // its two rendering companions, which are temporary and leave once a `Display` trait can carry
    // them, and the three ABI primitives of a variadic body, which stay. What each one means is in
    // `SpecialForms`; the dispatch is here so it reads in the order the match tries.
    case Call(Ident("print"), args)                         => printCall(args)
    case Call(Ident("str"), args)                           => strCall(args)
    case Call(Ident("format"), List(argExpr, StrLit(spec))) => formatCall(argExpr, spec)
    case Call(Ident("va_start"), args)                      => vaStart(args)
    case Call(Ident("va_end"), args)                        => vaEnd(args)
    case Call(Ident("va_arg"), args)                        => vaArg(args, expected)

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
    // data-carrying variant `Shape.Circle(5)`, the qualified form of the bare `Circle(5)`, or an
    // associated function the enum declares, which resolves exactly as a struct's does.
    case Call(Field(Ident(tname), mname), args) if lookupOpt(tname).isEmpty && enumDecls.contains(tname) =>
      if mname == "try" then enumTry(tname, args)
      else if enumDecls(tname).variants.exists(_.name == mname) then constructVariant(mname, args, expected)
      else if memberDecls.contains((tname, mname)) then callAssociated(tname, mname, args)
      else err(s"enum '$tname' has no variant or associated function '$mname'")

    // `Type.name(…)` — an associated function, told from the positional constructor `Type(…)` by
    // the member selected from the type name rather than the bare name applied.
    case Call(Field(Ident(tname), mname), args) if lookupOpt(tname).isEmpty && structDecls.contains(tname) =>
      callAssociated(tname, mname, args)

    case Call(Field(recv, mname), args) =>
      callMethod(recv, mname, args)

    case Call(_, _) =>
      err("the thing being called must be a name")

    case Field(Ident(n), f) if lookupOpt(n).isEmpty && enumDecls.contains(n) =>
      if enumDecls(n).variants.exists(_.name == f) then constructVariant(f, Nil, expected)
      else
        memberDecls.get((n, f)) match
          case Some(m) if m.isProperty        => err(s"'$f' is a property of '$n' — read it on a value, as 'value.$f'")
          case Some(m) if m.receiver.isDefined =>
            err(s"'$f' is a method of '$n' — call it on a value, as 'value.$f(…)'")
          case Some(_) => err(s"'$f' is an associated function of '$n' — call it with '$n.$f(…)'")
          case None    => err(s"enum '$n' has no variant '$f'")

    case Field(receiver, f) =>
      val tr = autoDeref(analyzeExpr(receiver))
      tr.ty match
        // A trait object has no fields: the layout is exactly what it forgot. What it still has is
        // whatever the trait declares, and a property is declared to be read exactly like this.
        case _ if Type.erased(tr.ty) =>
          readTraitObjectProperty(tr, Type.erasedTrait(tr.ty).get, f)

        case s: Type.Struct =>
          val idx = s.fieldIndex(f)
          if idx >= 0 then TField(tr, idx, s.fields(idx)._2)
          else readProperty(tr, s, f)

        // An enum has no fields to shadow a member, so every name read off one is a property.
        case e: Type.Enum => readProperty(tr, e, f)

        // A bound promises behaviour, and a property is behaviour spelled like a field — so this is
        // a bound's to license after all, and it is checked at the definition like every other use
        // of a parameter. What no bound reaches is a real *field*: that is layout, which is `10 §5`'s
        // rule and the complaint left when nothing declares a property of the name.
        case a: Type.Abstract => readBoundProperty(a, tr, f)
        // `len` and `bytes` are the first compiler-provided members: `len` a property on every
        // array, slice, and string, and `bytes` the reinterpretation of a string's three words
        // as a `[]u8`, dropping only the validity guarantee.
        case _: Type.Array | _: Type.View if f == "len" => TLen(tr)
        case Type.Str if f == "bytes"                   => TBytes(tr)

        // Any other type reaches its own members too, since an `impl` may be written for one and a
        // trait may ask for a property. A name none of them supplies is the older complaint, which
        // is the better one there: nothing about `x.foo` on an `int` says a property was meant.
        case other if hasMember(other, f) => readProperty(tr, other, f)
        case other                        => err(s"cannot read field '$f' of ${show(other)}")

    case ArrayLit(elems) =>
      val elemExp = expected.collect { case Type.Array(_, e) => e }
      val ts      = elems.map(analyzeExpr(_, elemExp))

      for t <- ts do
        if Type.noValue(t.ty) then err(s"an array cannot hold ${show(t.ty)} values")
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
      // The branches meet at one type, and a branch that does not finish takes the other's. A
      // branch used only for its effect is a different thing: one `unit` branch makes the whole
      // `if` a statement, whose value is nobody's, exactly as a missing `else` does.
      val ty = tElse match
        case Some(eb) =>
          join(tThen.ty, eb.ty).getOrElse {
            if eb.ty == Type.Unit || tThen.ty == Type.Unit then Type.Unit
            else err(s"if branches have different types: ${show(tThen.ty)} and ${show(eb.ty)}")
          }
        case None => Type.Unit
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
          val (tb, ctx)    = analyzeLoopBody(expected, label)(body.map(recoverStmt))
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
          val (tb, ctx) = analyzeLoopBody(expected, label)(body.map(recoverStmt))
          popScope()
          val telse     = elseOpt.map(analyzeValueBlock(_, expected))
          TForEach(u, elem, seq, tb, telse, loopResultType(ctx, telse))

    case TryExpr(e) =>
      analyzeTry(analyzeExpr(e))

    case _: RangeExpr =>
      err("a range is only allowed in a 'for' loop or a 'match' pattern")

    case _: Tuple => err("tuples are not supported yet")

  /** `value.name` where `name` is not a field: a computed property, which reads with no
   * parentheses and so is spelled exactly as a field is, with an implicit by-value receiver.
   *
   * The receiver may be of **any** type, because every type has an owner key its members are filed
   * under and a trait may declare a property for an `impl` to supply — so `21.twice` reads one
   * exactly as `p.twice` does, through the member the implementation was lowered to.
   *
   * The absent-member wording is the one difference between the kinds: a struct's `x` could have
   * been either a field or a property, while an enum and a built-in have no fields to have meant.
   */
  private def readProperty(tr: TExpr, ty: Type, f: String): TExpr = {
    val (base, _) = memberKey(ty, f)

    memberDecls.get((base, f)) match
      case Some(m) if m.isProperty =>
        val fname      = memberFuncName(ty, f)
        val (_, rtype) = funcInsts(fname)
        TCall(fname, List(tr), rtype)
      case Some(_) => err(s"'$f' is a method of '${show(ty)}' — call it with '$f(…)'")
      case None =>
        ty match
          case _: Type.Struct => err(s"'${show(ty)}' has no field or property '$f'")
          case _              => err(s"'${show(ty)}' has no property '$f'")
  }

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
  protected def analyzePlace(target: Expr, what: String): TExpr = {
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

  /** Analyzes a program to a typed tree, or returns every error it found, rendered and in source
   * order.
   *
   * The walk itself never stops at the first mistake — each declaration, function body, and
   * statement is a recovery region — so what comes back on the left is the whole list. An error
   * escaping the regions entirely is still caught here, since a diagnostic that reaches the user
   * beats a stack trace.
   */
  def analyze(program: Program): Either[String, TProgram] = {
    val analyzer = new Analyzer(program)

    val outcome =
      try Right(analyzer.analyze())
      catch
        case AnalyzerError(msg, pos) => Left(List(Diagnostic.render(msg, pos)))
        // A poisoned region carries no message of its own: it means an error was already
        // recorded, and those are what the caller is told about.
        case Poisoned() => Left(Nil)

    val found = analyzer.errors

    outcome match
      case Right(tree) if found.isEmpty => Right(tree)
      case Right(_)                     => Left(Diagnostic.report(found))
      case Left(escaped) =>
        val all = found ::: escaped

        // Reaching here with nothing to say would mean the analyzer gave up without recording
        // why, which is a bug in the analyzer rather than in the program it was handed.
        if all.isEmpty then Left(Diagnostic.render("the analyzer stopped without reporting why", None))
        else Left(Diagnostic.report(all))
  }
}
