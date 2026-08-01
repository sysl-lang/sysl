package io.github.edadma.sysl

/** What an expression means, and which expressions denote a **place**.
 *
 * This is the dispatch every other analysis trait calls back into — `analyzeExpr` is declared
 * abstract in `AnalyzerBase` precisely so that a call, a pattern, or a statement can analyze its
 * parts without knowing which of the forms below they turn out to be. The forms that need a table
 * or a machinery of their own are delegated: a call goes to `CallAnalysis`, a `match` to
 * `PatternAnalysis`, a block to `StmtAnalysis`, and the handful the compiler resolves by name to
 * `SpecialForms`.
 *
 * **Three groups of forms live in traits of their own**, and the dispatch below sends each group
 * whole: reading a member to `MemberExprAnalysis`, building or indexing a sequence to
 * `CollectionExprAnalysis`, and control flow used as an expression to `ControlFlowExprAnalysis`.
 * Each group's own arms keep the order they had, which is what makes the split safe to read: an arm
 * can only ever be shadowed by an arm matching the same node type, and every group is a set of node
 * types nothing else here matches. What more than one group needs is in `ExprSupport`.
 *
 * What stays is the dispatch itself and the forms with nowhere else to be: literals, names,
 * operators, assignment, and places. Places are here rather than beside assignment because what
 * makes an expression a place is a property of the expression: a local, a dereference, a field of
 * either, and an element of a slice have an address, and anything computed does not.
 */
trait ExprAnalysis
    extends MemberExprAnalysis
    with CollectionExprAnalysis
    with ControlFlowExprAnalysis
    with RawStorage {

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
  protected def analyzeExpr(expr: Expr, expected: Option[Type], discarded: Boolean): TExpr = {
    // Whether *this* expression is one of the three places a result list may stand (`12 §5b`).
    // Taking the flag before anything below runs is what confines it to one expression: every
    // subexpression is analyzed through this same funnel and sees it already spent.
    val allowed = multiOk
    multiOk = false

    // A discarded expression is by definition one nothing was expected of, so there is no context
    // to push down and the conversion cases below have nothing to say — what the flag carries is
    // the *absence* of a consumer, which only the branching forms act on.
    val raw = at(expr.pos)(
      if discarded then analyzeValue(expr, None, discarded = true) else analyzeExpected(expr, expected),
    ).setPos(expr.pos)

    // A result list is unwrapped here into the tuple its parts lay out as, so nothing downstream —
    // no other analysis, no pass, no emitter — ever holds one. Where a list is *not* allowed, this
    // is the one place that knows it, and it is also the place with the whole expression to name.
    val t = raw.ty match
      case r: Type.Results if allowed => retyped(raw, r.parts)
      case r: Type.Results =>
        at(expr.pos)(err(s"this yields ${quantity(r.parts.targs.length, "result")}, and one value is " +
          "wanted here — a result list is taken apart by a binding or an assignment that names " +
          "every one of them"))
      case _ => raw

    // A value whose type could not be worked out — a name whose declaration failed, a field of a
    // type that did not resolve, a call to a function with an unusable signature — abandons this
    // statement quietly. The mistake was reported where it was made, and every consequence of it
    // reported as well would bury the one diagnostic worth reading.
    if t.ty == Type.Unknown then poisoned()

    t
  }

  /** Analyzes one expression in a place a **result list** may stand (`12 §5b`): the right side of
   * a binding, the right side of a multiple assignment, and the result of a function whose own
   * declared result is a list. The permission covers this expression and nothing inside it.
   */
  protected def analyzeMulti(expr: Expr, expected: Option[Type] = None): TExpr = {
    multiOk = true

    try analyzeExpr(expr, expected)
    finally multiOk = false
  }

  /** The same call, typed at the tuple its result list lays out as. Only a call can carry a result
   * list — nothing else reads a signature — so those are the two shapes there are.
   */
  private def retyped(t: TExpr, parts: Type.Tuple): TExpr = t match
    case c: TCall  => c.copy(ty = parts, results = true).setPos(t.pos)
    case c: TVCall => c.copy(ty = parts, results = true).setPos(t.pos)
    case other     => sys.error(s"a result list arrived on a ${other.getClass.getSimpleName}")

  private def analyzeExpected(expr: Expr, expected: Option[Type]): TExpr = expected match
    // `ptr_cast` is answered against the type that was **written**, rather than against whatever a
    // converting context would have asked an ordinary expression for. Every arm below hands the
    // expression something other than the annotation — a `&T` asks for the payload it would box, a
    // trait object asks for nothing at all — and an address read out of bytes is precisely the value
    // that may not be turned into either, so the refusal has to be able to name what the programmer
    // spelled. `null` is special-cased below for the same reason: a raw address is written at the
    // type it is expected to have rather than converted into it.
    case Some(want) if rawCast(expr) => analyzeValue(expr, Some(want))

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
        // A callable is the other exception, and for the opposite reason to `null`'s: it has no type
        // of its own to be analyzed at and then erased, since what it takes is exactly what the
        // object's arguments say (`12 §5`). So the object is pushed down, and the erasure that
        // follows boxes the struct it became. This covers a **named function** as well as a literal:
        // §5 makes the two one thing — a declared function used where a callable is wanted is the
        // capture-free closure — and asking a name that stands for a declaration to produce a value
        // with no context is asking for the one thing it cannot do.
        case _ if callableArg(expr) => coerce(analyzeValue(expr, Some(o)), o)
        case _                      => coerce(analyzeValue(expr, None), o)

    case Some(r: Type.Ref) =>
      expr match
        case NullLit() => err(s"a ${show(r)} always points at a live object — an absent one is Option[${show(r)}]")
        case _         => coerce(analyzeValue(expr, Some(r.inner)), r)

    // Unlike a `&T`, a `weak T` does not ask the expression for the payload: what it takes is a
    // reference something *else* is keeping alive, so pushing the payload type down would invite a
    // construction whose only holder is the weak edge that cannot hold it. The expression is asked
    // for whatever it is, and `coerce` decides — including refusing that case by name.
    case Some(w: Type.Weak) =>
      expr match
        case NullLit() =>
          err(s"'null' is a raw pointer, and an empty ${show(w)} is written 'None' — the same thing " +
            "'get()' hands back for one")
        case _ => coerce(analyzeValue(expr, Some(w)), w)

    // A value produced into a transparent constrained subtype is analyzed at the subtype's base — so
    // a literal and arithmetic type as that base — and then checked into the subtype. A value that
    // does not agree with the base is left unwrapped for the caller to diagnose, and one that already
    // has this exact subtype is not re-checked.
    case Some(c: Type.Constrained) if !c.derived =>
      val v = analyzeValue(expr, Some(c.base))
      if disagree(v.ty, c.base) then v
      else if v.ty == c then v
      else checkInto(v, c)

    // A slice expectation is pushed down as it is — storage the expression *makes* takes whichever
    // form was asked for, so a buffer literal needs no conversion — and `coerce` settles what comes
    // back at the other one. Both directions come here, because the direction that is refused is
    // owed a message about what it would have allowed rather than the bare mismatch a reader gets
    // from two types that merely differ.
    case Some(v: Type.Slice) => coerce(analyzeValue(expr, Some(v)), v)

    case _ => analyzeValue(expr, expected)

  /** Whether an expression is the raw-tier reinterpretation, which takes its expectation as written
   * (`03 § Reinterpreting storage`).
   */
  private def rawCast(e: Expr): Boolean = e match
    case Call(Ident("ptr_cast"), _) => true
    case _                          => false

  /** Wraps a base-typed value in the run-time check for a constrained subtype. */
  private def checkInto(v: TExpr, c: Type.Constrained): TExpr = TConstrainedCheck(v, c).setPos(v.pos)


  /** Wraps the write so that every struct the place is written *inside* re-checks its invariant the
   * moment the field changes. The wraps nest innermost-first, so the smallest struct broken is the
   * one whose diagnostic fires. `invCheckFor` — the walk that finds them — is in `Aliasing`, beside
   * the rule about which aliases could put a struct out of that walk's reach.
   */
  private def withInvCheck(place: TExpr, store: TExpr): TExpr =
    invCheckFor(place).foldLeft(store)((acc, c) => TRecheck(acc, c._1, c._2, c._3).setPos(store.pos))

  /** `Name(value)` — an explicit cast into a constrained subtype. The operand is taken at the
   * subtype's base and checked; a value whose base does not agree is a mistake the message names.
   */
  private def constrainedCast(key: String, args: List[Expr]): TExpr =
    castConstrained(resolveConstrained(key), qn(key), args)

  /** The same cast reached with the subtype in hand rather than with its name, which is what a
   * conversion written at a **type parameter** has: `T(x)` instantiated at an `Age` is the `Age(x)`
   * a reader would have written, and taking any other route would refuse it for not being a scalar
   * conversion — true, and beside the point, since the form does exist under the type's own name.
   */
  private def castConstrained(c: Type.Constrained, written: String, args: List[Expr]): TExpr = {
    if args.length != 1 then err(s"a '$written' conversion takes exactly one value")
    val v = analyzeExpr(args.head, Some(c.base))
    if disagree(v.ty, c.base) then err(s"cannot make ${show(c)} from ${show(v.ty)}")
    checkInto(v, c)
  }


  /** Whether a context of this type converts what it is given rather than simply requiring it. */
  private def converts(want: Type): Boolean =
    Type.erased(want) || want.isInstanceOf[Type.Ref] || want.isInstanceOf[Type.Weak] ||
      Type.readOnlyView(want)


  /** Whether an expression yields its value through branches rather than producing one itself. */
  private def branching(expr: Expr): Boolean = expr match
    case _: IfExpr | _: MatchExpr | _: While | _: For => true
    case _                                            => false

  /** The four conversions a context may apply to a value that does not already have its type: a
   * `T` the context wanted by reference is boxed, a `&T` the context wanted weakly is weakened,
   * something concrete where a trait object was wanted is erased into one, and a `[]T` where a
   * `[]const T` was wanted gives up the ability to write. Nothing else coerces — any other mismatch
   * is left for the caller to diagnose, where the message can name the parameter or the variable it
   * is about.
   *
   * A `T` where a `weak T` was wanted goes through both: the value is boxed and the reference that
   * comes back is weakened. What that makes is an edge to an object nothing else holds, which dies
   * before the statement ends — so it is refused rather than built, and the message says to hold
   * the object somewhere first.
   */
  protected def coerce(t: TExpr, expected: Type): TExpr = expected match
    case _ if Type.erased(expected)     => eraseTo(t, expected)
    case r: Type.Ref if t.ty == r.inner => TBox(t, r).setPos(t.pos)
    case w: Type.Weak if t.ty == w.strong => TDowngrade(t, w).setPos(t.pos)
    case w: Type.Weak if t.ty == w.inner =>
      err(s"a weak reference does not keep ${show(w.inner)} alive, and nothing else here holds this " +
        s"one — so it would be gone before it could be read. Hold it in a '&${Type.show(w.inner)}' " +
        "first, and weaken that")

    // Two references to the same type that differ only in whether the count is atomic. The
    // fall-through below would report them as the unrelated types they are, which is true and says
    // nothing about the one thing a reader wants to know: why the two do not convert (`06 § &sync T`).
    case Type.Ref(want, sync) if t.ty == Type.Ref(want, !sync) =>
      err(s"'&${show(want)}' and '&sync ${show(want)}' are distinct types, and neither converts to " +
        "the other: a count is atomic or it is not from the moment the object is allocated, and a " +
        s"conversion would put an ordinary retain beside an atomic one. Allocate ${show(want)} as a " +
        s"'&${if sync then "sync " else ""}${show(want)}' where it is constructed")

    // A view that may be written stands where one that may not was asked for, and never the other
    // way round. The safe direction is a promise the caller keeps and the callee does not need: the
    // elements are still whatever they were, and the callee has merely lost a way of changing them.
    // The unsafe direction is the whole of what the type exists to stop, and it is diagnosed below
    // rather than here so that the message can say what it would have allowed.
    case Type.Slice(want, true) if t.ty == Type.Slice(want, readOnly = false) =>
      TConstView(t).setPos(t.pos)

    case Type.Slice(want, false) if t.ty == Type.Slice(want, readOnly = true) =>
      err(s"a '[]const ${show(want)}' views elements it may not write, and a '[]${show(want)}' is a " +
        "licence to write them — so the one does not become the other. Copy what you need into a " +
        s"'[]${show(want)}' of your own, or take the parameter as '[]const ${show(want)}' if it is " +
        "only read")

    case _ => t

  private def analyzeValue(expr: Expr, expected: Option[Type], discarded: Boolean = false): TExpr =
    at(expr.pos)(analyzeValueAt(expr, expected, discarded)).setPos(expr.pos)

  protected def analyzeValueAt(expr: Expr, expected: Option[Type], discarded: Boolean): TExpr = expr match
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
        // The null callback, which several C interfaces read as "there is none, use the default" —
        // `signal(SIG_DFL)`, an `atexit` slot never filled, a `*_set_callback(0)`. It is an address
        // like any other and the same word says it is absent.
        case Some(c: Type.CFn) => TNullLit(c)
        case Some(other)       => err(s"'null' is a raw pointer, and ${show(other)} was expected here")
        case None              => err("'null' takes its type from its context, and there is none here")

    // A minus and the literal it precedes are one unit for the range check, so a signed type's
    // minimum is writable even though its magnitude overflows the positive range.
    case Unary("-", IntLit(v, suffix))   => intLiteral(-v, suffix, expected)
    case Unary("-", FloatLit(t, suffix)) => floatLiteral("-" + t, suffix, expected)

    // `result` is a contextual keyword: it names the returned value inside an `ensure`, but a
    // real binding of that name (a parameter or local) still shadows it, so the lookup comes first.
    case Ident("result") if lookupOpt("result").isEmpty =>
      ensureResultTy match
        case Some(ty) => TResult(ty)
        case None     => err("'result' is only meaningful inside an 'ensure' of a value-returning function")

    // A weak reference whose object is gone and one that never had an object are the same state,
    // so the empty weak reference is spelled the way that state reads everywhere else: `None`, the
    // very thing `get()` will hand back for it (`03`).
    case Ident("None") if lookupOpt("None").isEmpty && expected.exists(_.isInstanceOf[Type.Weak]) =>
      TZero(expected.get)

    // A declared function standing where a callable is wanted is one, with nothing captured
    // (`12 §5`). It is asked for only where the context says a callable, so a bare function name
    // anywhere else is still the mistake it was.
    case Ident(name)
        if lookupOpt(name).isEmpty && funcKey(name).isDefined &&
          expected.flatMap(callableSignature).isDefined =>
      val (ptypes, result) = expected.flatMap(callableSignature).get

      functionAsCallable(name, ptypes, result, expr.pos)

    // A nested function is **called** where it is written and is not a value (`12 §5a`). Its
    // environment is a row of addresses into the frame it was declared in, which is sound exactly
    // because nothing can carry it out of that frame — and a callable value is a way of carrying it.
    case Ident(name)
        if lookupOpt(name).isEmpty && (nestedFuncs.contains(name) || outerNested(name)) =>
      err(s"'$name' is a nested function, so it is called where it is written rather than passed — " +
        "its environment is the frame it was declared in, and a callable value is a way of carrying " +
        s"it out. Something that has to be passed is a closure of its own: 'var $name = x -> …'")

    // A declared function named where nothing wants a callable. The name is not undefined — the
    // declaration is right above — and saying so sends the reader hunting for a typo instead of at
    // what is really missing: a context that says which call trait to build the function into
    // (`§5`, `§6`). A function has no address of its own to fall back on, which is the other thing
    // a reader arriving here may have been reaching for, so the message rules it out by name.
    case Ident(name) if lookupOpt(name).isEmpty && funcKey(name).isDefined =>
      err(s"'$name' is a function, and a function becomes a value only where a callable is wanted — " +
        "a bare-arrow parameter, or a '&Fn' where a concrete type is required. Nothing here asks " +
        "for one, and a function has no address of its own to take instead")

    case Ident(name) =>
      lookupOpt(name) match
        // A captured name is a name the scope knows and the frame does not hold: what it reaches is
        // the field of the closure the body is now a member of (`12 §7`).
        case Some((u, ty)) => capturedFields.getOrElse(u, TLoad(u, ty))
        case None =>
          variantKey(name) match
            case Some(key) => constructVariant(key, Nil, expected)
            case None =>
              // A constant is folded into its use and analyzed as the literal it stands for, at the
              // type it was declared with rather than the one the context asked for. That is what
              // makes it behave like the value it names: `const n: usize = 5` used where an `int`
              // belongs is the same mismatch a `usize` variable would be, not a silent adaptation.
              constKey(name) match
                case Some(key) => analyzeExpr(constLiteral(key), Some(constType(key)))
                // A `val` is the other half of that: nothing is folded, because it is storage. The
                // name reaches the storage itself, which is why it can be indexed and iterated.
                case None =>
                  valKey(name) match
                    case Some(key) => TGlobal(key, valType(key))
                    // An `extern` variable is storage too, so it becomes the same node — under the
                    // **symbol** rather than the key, since what it names is what the linker
                    // supplies, and writable, since the storage is not this program's to promise
                    // anything about (`12 §1`).
                    case None if externVarKey(name).isDefined =>
                      val key = externVarKey(name).get

                      externVarsUsed += key
                      TGlobal(externVarDecls(key).symbol, externVarType(key), writable = true)
                    // A name the block binds further down is a different mistake from one that
                    // stands for nothing, and the difference is what the reader has to fix.
                    case None if blockDeclares(name) =>
                      err(s"'$name' is declared below this, and a name is in scope from where it " +
                        "is bound onward")
                    case None => err(s"undefined name '${qn(name)}'")

    case Binary(op @ ("&&" | "||"), l, r) =>
      TLogical(op, analyzeBool(l), analyzeBool(r))

    case Binary(op, l, r) =>
      val List(tl, provisional) = analyzeOperands(List(l, r), expected.filter(Type.isNumeric))
      val tr                    = operandRhs(op, tl, r, provisional)

      operatorCall(op, tl, tr).getOrElse(produced(TBinary(op, tl, tr, arithType(op, tl.ty, tr.ty))))

    // The operand's *base* decides which operators there are — a subtype narrows which values a type
    // has, never which operations it has — so the match reads through it and the node is typed by
    // `unaryType`, which keeps a derived result in its own type.
    case Unary("-", e) =>
      val t = analyzeExpr(e, expected.filter(Type.isNumeric))
      prefixCall("-", t).getOrElse(Type.underlying(t.ty) match
        case i: Type.Integer if i.signed => produced(TUnary("-", t, unaryType(t.ty)))
        case _: Type.Floating            => produced(TUnary("-", t, unaryType(t.ty)))
        case _: Type.Integer => err(s"unary '-' is not defined for the unsigned type ${show(t.ty)}")
        case _               => err(s"unary '-' is not defined for ${show(t.ty)}"))

    case Unary("!", e) =>
      TUnary("!", analyzeBool(e), Type.Bool)

    case Unary("~", e) =>
      val t = analyzeExpr(e, expected.filter(Type.isNumeric))
      prefixCall("~", t).getOrElse(Type.underlying(t.ty) match
        case _: Type.Integer => produced(TUnary("~", t, unaryType(t.ty)))
        case _               => err(s"unary '~' is not defined for ${show(t.ty)}"))

    // Address-of yields a *raw* pointer: a place lives in a frame or inside another object, so
    // there is no refcount to take a share of. Reaching a `&T` means being handed one.
    //
    // The one place it is refused is a place inside a struct whose invariant reads it: the pointer
    // would be typed below the promise, and `16 §6` is discharged by naming the struct.
    // A function is not a place — nothing holds it, and there is no slot to point at — so its
    // address is taken here rather than by the walk below, which asks for one (`12 §6a`). A local
    // shadowing the name is an ordinary value and keeps the ordinary reading.
    case Unary("&", Ident(name)) if lookupOpt(name).isEmpty && funcKey(name).isDefined =>
      functionAddress(name, funcKey(name).get)

    // A nested function's environment is the frame it was declared in (`12 §5a`), and an address is
    // a way of carrying it out of that frame — the same reason the name is not a value either.
    case Unary("&", Ident(name)) if lookupOpt(name).isEmpty && (nestedFuncs.contains(name) || outerNested(name)) =>
      err(s"'$name' is a nested function, so it has no address to take — what would have to travel " +
        "beside the address is the frame it reads, and a '*extern' is one word. A top-level " +
        "function is what has an address")

    case Unary("&", e) =>
      val place = analyzePlace(e, "'&'", writes = false)
      checkAddressable(place)
      // The address of a register is an address *of a register*, so the qualifier travels with it and
      // every access through the result stays an access to a device (`03 § Device memory`).
      TAddrOf(place, Type.Ptr(place.placeTy))

    case Unary("*", e) =>
      val t = analyzeExpr(e)
      Type.pointee(t.ty) match
        case Some(inner)                => TDeref(t, Type.unqualified(inner))
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

    // A parameter's default, spliced in where the argument was not written (`12 §2a`). It is
    // analyzed in the declaration's own terms and with nothing local in scope, which is what makes
    // it mean the same thing from every module that calls the function.
    case DefaultArg(owner, e) =>
      at(e.pos)(filling(e.pos)(inDefault(owner)(analyzeExpr(e, expected))))

    // Argument binding replaces every one of these before a call's arguments are looked at, so one
    // arriving here was written where nothing is being called by name — in an array literal, on the
    // right of an operator, or at a call through something that carries no parameter names.
    case NamedArg(name, _) =>
      err(s"'$name = …' names an argument, and this is not a call to a declaration that names its " +
        s"parameters — write the value on its own, or '($name = …)' for the assignment")

    // `b[i] = v` on a type with no elements of its own is `IndexSet`, and it is a call rather than a
    // store because a trait's method gives back a value and never an address — so there is no place
    // for the ordinary path to write through, and the trait says as much by taking the value.
    case Assign("=", Index(receiver, index), value) if indexes(Library.key("IndexSet"), receiver) =>
      callMethod(receiver, "index_set", List(index, value), None)

    // The compound forms would have to read the element and write it back, which means evaluating
    // the receiver and the index twice — and a container's subscript is a call, so twice is twice
    // the calls. Written out, the program says that itself.
    case Assign(op, Index(receiver, index), _) if indexes(Library.key("IndexSet"), receiver) =>
      err(s"'$op' on an element read through '${qn(Library.key("Index"))}' would evaluate the " +
        s"receiver and the index twice — write it out as 'b[i] = b[i] ${op.dropRight(1)} …'")

    case Assign("=", target, value) =>
      val place = analyzePlace(target, "assignment")
      val tv    = analyzeExpr(value, Some(place.ty))
      // A diverging value is no value to store, so it is rejected here rather than agreeing the way
      // a `never` does where one really may stand — as the value a `return` or a branch yields.
      if tv.ty == Type.Never || disagree(tv.ty, place.ty) then
        err(s"cannot assign ${show(tv.ty)} to ${describe(target)} of type ${show(place.ty)}")
      withInvCheck(place, TStore(place, tv, place.ty))

    // `p += q` on a type whose `Add` is a real implementation updates the place from the value it
    // already read, exactly as the scalar form does — the dispatch travels with the node rather
    // than becoming a call tree that would read the place twice.
    case Assign(op, target, value) =>
      val place  = analyzePlace(target, s"'$op'")
      val binSym = op.dropRight(1)
      val tv     = analyzeExpr(value, updateExpected(binSym, place.ty))
      val d      = updateDispatch(binSym, place, tv)

      // What has to hold is that the result can be stored back. A constrained place is the one case
      // where the arithmetic's type and the place's legitimately differ — a transparent subtype
      // computes at its base — so the test is on the representation the two share, and what the
      // difference costs is the check `constraintOf` asks for. `disagree` is that comparison plus
      // the suppression a poisoned type wants: a place whose type could not be worked out has been
      // complained about once already, and saying its `+=` changes a type is a second complaint
      // about the consequence.
      if d.isEmpty && disagree(arithType(binSym, place.ty, tv.ty), place.ty) then
        err(s"'$op' would change the type of ${describe(target)}")

      withInvCheck(place, TUpdate(place, op, tv, place.ty, d, constraintOf(place.ty)))

    // The forms the compiler resolves by name rather than by looking a function up: `print` and
    // its two rendering companions, which are temporary and leave once a `Display` trait can carry
    // them, and the five primitives no sysl body could implement — the unchecked byte-to-string
    // conversion and the four a variadic body needs — which stay. What each one means is in
    // `SpecialForms`; the dispatch is here so it reads in the order the match tries.
    case Call(Ident("print"), args)                         => printCall(args)
    case Call(Ident("str"), args)                           => strCall(args)
    case Call(Ident("format"), List(argExpr, StrLit(spec))) => formatCall(argExpr, spec)
    case Call(Ident("from_utf8_unchecked"), args)           => fromUtf8Unchecked(args)
    case Call(Ident("va_start"), args)                      => vaStart(args)
    case Call(Ident("va_end"), args)                        => vaEnd(args)
    case Call(Ident("va_arg"), args)                        => vaArg(args, expected)
    case Call(Ident("va_copy"), args)                       => vaCopy(args)
    case Call(Ident("ptr_cast"), args)                      => ptrCast(args, expected)

    // `sizeof(T)` / `alignof(T)` — the parser has already read the operand as a type, which is what
    // separates these from every form above: they are syntax rather than a name the analyzer knows.
    case LayoutOf(what, tr)                                 => layoutOf(what, tr)

    // `old(e)` is a contextual keyword read only while an `ensure` is being analyzed; the guard is
    // what lets `old` stay an ordinary name outside a postcondition.
    case Call(Ident("old"), args) if oldBuf.isDefined       => oldCall(args)

    // A conversion is written with call syntax, so a type name in call position is one — a built-in,
    // or a **type parameter**, which every instantiation replaces with a type this same form would
    // have accepted written out. That is what makes the two directions symmetric: `u8(x)` where `x`
    // is a `T` was always ordinary code, checked once the width is concrete, and `T(b)` is the same
    // check at the same moment.
    case Call(Ident(name), args) if lookupOpt(name).isEmpty && typeKey(name).isEmpty &&
        typeNamed(name).isDefined =>
      typeNamed(name).get match
        // An instantiation at a constrained subtype or a simple enum takes the checked cast written
        // under that type's own name, trapping on a value it does not admit, rather than the scalar
        // conversion that has no meaning for either.
        case c: Type.Constrained => castConstrained(c, name, args)
        case e: Type.Enum        => enumFromIntAt(e, name, args)
        case ty =>
          if args.length != 1 then err(s"a '$name' conversion takes exactly one value")
          convert(analyzeExpr(args.head), ty)

    // A constrained subtype's name in call position wraps a base value into the subtype, checking it
    // — `Age(n)`, `Meters(3.0)`. Unlike an implicit produce site, the cast is written, so it applies
    // even where the base would not flow in on its own.
    case Call(Ident(name), args) if lookupOpt(name).isEmpty && typeKey(name).exists(constrainedDecls.contains) =>
      constrainedCast(typeKey(name).get, args)

    // `T::Attr(x)` — a type attribute that takes an argument (`Valid`, `Succ`, `Pred`).
    case Call(TypeAttr(Ident(name), attr), args) if lookupOpt(name).isEmpty && typeKey(name).isDefined =>
      typeAttr(typeKey(name).get, attr, args)

    case Call(Ident(name), args) if lookupOpt(name).isEmpty && variantKey(name).isDefined =>
      constructVariant(variantKey(name).get, args, expected)

    case Call(Ident(name), args) if lookupOpt(name).isEmpty && typeKey(name).exists(structDecls.contains) =>
      constructStruct(typeKey(name).get, args, expected)

    // A simple enum's name in call position is a checked cast from an integer — `Color(n)` traps
    // on a value that is not a declared discriminant. Told from a data enum, which has no integer
    // to reinterpret, and from a struct constructor, which the arm above already claimed.
    case Call(Ident(name), args) if lookupOpt(name).isEmpty && typeKey(name).exists(enumDecls.contains) =>
      enumFromInt(typeKey(name).get, args)

    // A local that is callable is called, and it wins over a declaration of the same name for the
    // reason the nearest binding always does. It is asked whether it is callable rather than merely
    // whether it is a local, so a name that shadows a function with something uncallable still
    // reaches the function — which is what it did before there were closures, and no program that
    // relied on it is silently rerouted.
    // A local holding C's function pointer, called through it. It comes before the callable one
    // because a `*extern` implements no call trait: there is no receiver to pass and no table to
    // read, only an address and the signature its type carried (`12 §6a`).
    case Call(Ident(name), args) if lookupOpt(name).exists((_, t) => cfnOf(t).isDefined) =>
      callThroughAddress(analyzeExpr(Ident(name).setPos(expr.pos)), args)

    case Call(Ident(name), args) if lookupOpt(name).exists((_, t) => callableOf(t).isDefined) =>
      callCallable(analyzeExpr(Ident(name).setPos(expr.pos)), args, expected)

    // A nested function of an enclosing block (`12 §5a`), which shadows a top-level one of the same
    // name for the reason the nearest binding always wins. The environment travels as the receiver,
    // so a sibling call and a recursive call are the same call.
    case Call(Ident(name), args) if lookupOpt(name).isEmpty && nestedFuncs.contains(name) =>
      callNested(nestedFuncs(name), name, args)

    // One written below the call that names it. Its environment is formed where the first of the
    // block's nested functions is written, so a call above that point has none to pass.
    case Call(Ident(name), _) if lookupOpt(name).isEmpty && pendingNested.exists(_.name == name) =>
      err(s"'$name' is declared below this call — the nested functions of a block share an " +
        "environment formed where the first of them is written, so they may be called from there on")

    // One belonging to a body this one is written inside. A body reaches its own group and no
    // further, because what it would have to carry to reach further is the frame around it.
    case Call(Ident(name), _) if lookupOpt(name).isEmpty && outerNested(name) =>
      err(s"'$name' is a nested function of the body around this one, which reaches its own nested " +
        "functions and its own captures and no further — a top-level function is what several " +
        "bodies share")

    case Call(Ident(name), args) if funcKey(name).isDefined =>
      callFunction(funcDecls(funcKey(name).get), args, expected)

    // A name that is neither a local nor a function, holding a function pointer — a module-level
    // `val` is the one that reaches here, since it is resolved by neither of the two lookups above.
    // The general case further down would have taken it, but the complaint about an undefined
    // function comes first: a call head that is a *name* never gets that far (`12 §6a`).
    case Call(Ident(name), args)
        if lookupOpt(name).isEmpty && probe(analyzeExpr(Ident(name).setPos(expr.pos)))
          .exists(t => cfnOf(t.ty).isDefined) =>
      callThroughAddress(analyzeExpr(Ident(name).setPos(expr.pos)), args)

    // A local that is not callable, called anyway, is a different mistake from a name that stands
    // for nothing — the name was found, and what it holds is not a thing a call reaches.
    case Call(Ident(name), _) if lookupOpt(name).isDefined =>
      err(s"'$name' is ${show(lookupOpt(name).get._2)} and is not callable — a callable is a " +
        "closure, or a value of a type that implements the call trait")

    case Call(Ident(name), _) =>
      err(s"undefined function '$name'")

    // A member reached through the module it belongs to (`13 §3`): the chain is rewritten with the
    // module folded into the name it qualifies, and what is left is the ordinary form — a call, a
    // construction, an associated function — resolved exactly as one written unqualified is.
    case Call(callee, args) if throughModule(callee).isDefined =>
      analyzeValueAt(Call(throughModule(callee).get, args).setPos(expr.pos), expected)

    // Reached through the enum name: `Color.try(n)` is the fallible constructor; otherwise a
    // data-carrying variant `Shape.Circle(5)`, the qualified form of the bare `Circle(5)`, or an
    // associated function the enum declares, which resolves exactly as a struct's does.
    case Call(Field(Ident(written), mname), args)
        if lookupOpt(written).isEmpty && typeKey(written).exists(enumDecls.contains) =>
      val tname = typeKey(written).get

      if mname == "try" then enumTry(tname, args)
      else if enumDecls(tname).variants.exists(_.name == mname) then
        constructVariant(Modules.qualify(Modules.moduleOf(tname), mname), args, expected)
      else if memberDecls.contains((tname, mname)) then callAssociated(tname, mname, args, expected)
      else err(s"enum '${qn(tname)}' has no variant or associated function '$mname'")

    // `Type.name(…)` — an associated function, told from the positional constructor `Type(…)` by
    // the member selected from the type name rather than the bare name applied.
    case Call(Field(Ident(written), mname), args)
        if lookupOpt(written).isEmpty && typeKey(written).exists(structDecls.contains) =>
      callAssociated(typeKey(written).get, mname, args, expected)

    case Call(Field(Ident(written), mname), args)
        if lookupOpt(written).isEmpty && typeKey(written).exists(constrainedDecls.contains) =>
      val n = typeKey(written).get

      // A constrained subtype is a name a call reaches, so an `impl` for one may carry an associated
      // function exactly as a struct's may. Everything else selected from the name is one of the
      // mistakes `constrainedMember` has words for.
      if memberDecls.get((n, mname)).exists(_.recvMode.isEmpty) then callAssociated(n, mname, args, expected)
      else constrainedMember(n, written, mname)

    case Call(Field(Ident(written), mname), _)
        if lookupOpt(written).isEmpty && typeKey(written).isEmpty && traitKey(written).isDefined =>
      traitMember(traitKey(written).get, mname)

    // `T.f(…)` and `real.f(…)` — an associated function reached through a type that is not one of
    // the declaration tables above: a type parameter, the `Self` a member's body is analyzed under,
    // or a built-in an `impl` was written for. It is the only way a bound says anything about the
    // type rather than about a value of it (`02 § Reaching a trait's members without a value`).
    case Call(Field(Ident(written), mname), args)
        if lookupOpt(written).isEmpty && typeKey(written).isEmpty && traitKey(written).isEmpty &&
          typeNamed(written).isDefined =>
      callTypeAssociated(typeNamed(written).get, mname, args, expected)

    case Call(Field(recv, mname), args) =>
      callMethod(recv, mname, args, expected)

    // `f[T](…)` and `x.m[T](…)` — type arguments written at a call. Their absence is deliberate and
    // recorded (`10 § Open a`): a type-argument list and an index are the same grammar, so the two
    // cannot be told apart at a call head. What is left for the diagnostic is to say so and to name
    // what to write instead, since the inference that stands in for them reads the *binding* rather
    // than the call — which is exactly what somebody reaching for this syntax does not yet know.
    //
    // The name has to be a generic declaration and nothing nearer: a local shadowing one is an
    // ordinary indexed value, and telling its author about type arguments they never wrote would be
    // worse than the general complaint. That is the same shadowing test every call form above makes.
    case Call(Index(Ident(written), _), _)
        if lookupOpt(written).isEmpty && funcKey(written).exists(k => funcDecls(k).tparams.nonEmpty) =>
      err(s"'$written' cannot be given type arguments at a call; write the type on what receives the result")

    case Call(Index(Field(_, mname), _), _) if memberDecls.exists((k, d) => k._2 == mname && d.tparams.nonEmpty) =>
      err(s"'$mname' cannot be given type arguments at a call; write the type on what receives the result")

    // A special form written with type arguments. `va_arg[int](ap)` is the one this is really for:
    // it is what somebody reaches for first, and an earlier draft of `12 §9` told them to. None of
    // the forms takes any, for the reason nothing else does — square brackets in an expression are
    // indexing (`10 §2`) — and without this case the reading is the general complaint about a
    // callee that is not a name, which is the one `10 § Open a` says this case must not get.
    case Call(Index(Ident(name), _), _) if lookupOpt(name).isEmpty && specialFormNames(name) =>
      if name == "va_arg" then
        err("'va_arg' takes no type arguments; the type it reads comes from the context the value " +
          "is read into, so annotate the variable it is read into")
      else err(s"'$name' takes no type arguments")

    // Anything that *is* a callable may be called, wherever it was read from — an element of an
    // array of them, a part of a tuple, a container's item (`12 §6`). The head of a call is looked
    // at rather than required to be a name, and only what turns out not to be callable is refused.
    // A function pointer read from wherever one was kept — a struct's field, an element of a table
    // of handlers, what another call handed back (`12 §6a`).
    case Call(callee, args) if probe(analyzeExpr(callee)).exists(t => cfnOf(t.ty).isDefined) =>
      callThroughAddress(analyzeExpr(callee), args)

    case Call(callee, args) if probe(analyzeExpr(callee)).exists(t => callableOf(t.ty).isDefined) =>
      callCallable(analyzeExpr(callee), args, expected)

    case Call(_, _) =>
      err("the thing being called must be a name, or something whose type says it is callable")

    // A member read is one form with three readings — a field, a property, or an attribute of a
    // type's own name — and each reading's mistakes want their own words. `MemberExprAnalysis`.
    case e: Field    => fieldExpr(e, expected)
    case e: TypeAttr => typeAttrExpr(e)
    // Building a sequence and reaching into one, which share the question of how many elements
    // there are and whether this index is one of them. `CollectionExprAnalysis`.
    case e @ (_: ArrayLit | _: ArrayFill | _: Index) => sequenceExpr(e, expected)
    // Control flow that yields a value (`00 §10`), and the forms that carry several at once.
    // `ControlFlowExprAnalysis`.
    case e @ (_: IfExpr | _: MatchExpr | _: While | _: Loop | _: CFor | _: For | _: TryExpr |
        _: RangeExpr | _: ResultList | _: Lambda | _: Tuple) =>
      controlExpr(e, expected, discarded)

    // Reached only where an `is` was written somewhere a condition's terms are not read one by one:
    // under `||` or `!`, in a `match` guard, in a `require`, on the right of an `=`, as an argument.
    // The rule is about the binding rather than the test — a `bool` would be harmless, but a name
    // bound where the reader cannot see which paths reach it is not (`09 §12`).
    case _: IsPattern =>
      err("'is' tests a pattern in the condition of an 'if' or a 'while', and nowhere else — its " +
        "binding is live from here to the end of the condition and through the branch that " +
        "condition guards, and there is no such branch here. Chain it with '&&', or write 'match'")



  /** `++`/`--` — a step of one, which the base decides the existence of and a constrained place
   * then has to accept: the new value is checked between the addition and the store, so a counter
   * declared over a range stops at the end of it rather than walking off.
   *
   * It is a write of one field when its place is one, so it owes a struct's `invariant` the same
   * re-check the compound form owes — `s.lo++` can break `lo <= hi` exactly as `s.lo += 1` can.
   */
  private def incDec(op: String, target: Expr, pre: Boolean): TExpr = {
    val place = analyzePlace(target, s"'$op'")

    Type.underlying(place.ty) match
      case _: Type.Integer => withInvCheck(place, TIncDec(place, op, pre, place.ty, constraintOf(place.ty)))
      case _               => err(s"'$op' is not defined for ${show(place.ty)}")
  }



  // --- names reached through a module ---------------------------------------------------



  /** `old(e)` — the value `e` had at function entry. It is analyzed in the entry scope the `ensure`
   * runs in (parameters, but no body locals, which are not in scope yet), then recorded in the
   * `old` buffer so codegen snapshots it before the body runs. The position it took is what the
   * postcondition reads back.
   */
  protected def oldCall(args: List[Expr]): TExpr = {
    val e = args match
      case List(one) => one
      case _         => err(s"'old' takes exactly one argument, but got ${args.length}")

    val te = analyzeExpr(e, None)
    if te.ty == Type.Unit then err("'old' needs a value to remember, but its argument is unit")

    val buf = oldBuf.get
    val idx = buf.length
    buf += te
    TOld(idx, te.ty)
  }

  /** A comparison chain, checked link by link. A link the machine performs directly needs its
   * operands to agree and the type to have the comparison being asked of it — equality reaches
   * further than ordering (`01`); a link a trait supplies had both checked against the trait's own
   * signature when `compareLink` resolved it.
   */
  protected def compareChain(ts: List[TExpr], cmps: List[TCmp]): TExpr = {
    for i <- cmps.indices if cmps(i).dispatch.isEmpty do
      val op       = cmps(i).op
      val (a, b)   = (ts(i), ts(i + 1))
      val equality = op == "==" || op == "!="
      // Operands agree on their *representation*, which is the reading `arithType` takes: a
      // transparent subtype is the same type as its base (`16 §1`), so `a < n` between an `Age` and
      // an `int` is one comparison of two integers, while a derived subtype is its own
      // representation and so still compares only with itself.
      if Type.repr(a.ty) != Type.repr(b.ty) then err(s"cannot compare ${show(a.ty)} with ${show(b.ty)}")
      if !(if equality then Type.isEquatable(a.ty) else Type.isOrdered(a.ty)) then
        err(s"'$op' is not defined for ${show(a.ty)}")

    TCompare(ts, cmps)
  }

  /** Registers an instantiation of a generic function and returns the name codegen will emit.
   * The signature is recorded before the body is queued, so a recursive generic function
   * resolves its own call.
   */
  // --- places --------------------------------------------------------------------------

  /** Whether a typed expression denotes a **place** — something with an address, which can be
   * assigned through and pointed at. A local, a dereference, an element, and a field of any of
   * them are places; anything computed (a call result, an arithmetic result, a freshly built
   * struct) is not.
   *
   * The element is the case with a condition on it, and the arm below says which way each falls.
   */
  protected def isPlace(t: TExpr): Boolean = t match
    case _: TLoad           => true
    case _: TGlobal         => true
    case _: TDeref          => true
    case TField(recv, _, _) => isPlace(recv)
    // A slice's elements live wherever its owner keeps them, so they have an address even when
    // the slice itself is a temporary. An array's elements are the array, so they do not.
    case TIndex(recv, _, _) =>
      recv.ty match
        case _: Type.Slice => true
        case Type.Str      => false
        // What a pointer names is somewhere else, so its elements have an address whether or not
        // the pointer itself is a place — the same reason a slice's do, and the same reason `*p`
        // is a place.
        case _: Type.Ptr   => true
        case _             => isPlace(recv)
    case _ => false

  /** Analyzes something that must be a place — an assignment target or the operand of `&`.
   *
   * `writes` separates the two. Both need an address, but only one of them *is* a write: `&` hands
   * back a `*T`, and what a program does with a raw pointer is the unsafe tier's business by the
   * rule `03` states outright. So a place that may be addressed but not assigned to says no here
   * and nothing there.
   */
  protected def analyzePlace(target: Expr, what: String, writes: Boolean = true): TExpr =
    requirePlace(analyzeExpr(target), target, what, writes)

  /** The same demand made of an expression **already analyzed**, for a form that had to see the type
   * before it knew whether a place was what it wanted at all.
   */
  protected def requirePlace(t: TExpr, target: Expr, what: String, writes: Boolean = true): TExpr = {
    // A **captured** name reaches storage the walk below cannot see the binding of — a field of the
    // environment, or the frame slot one points at — so being written once is asked of the name it
    // was declared under rather than of the expression it turned into (`12 §7`).
    target match
      case Ident(n) if lookupOpt(n).exists((u, _) => readOnlyLocals(u)) =>
        err(s"a 'val' is written once, so $what has nothing to write through")
      case _ =>

    t match
      // A string is immutable, and it is worth saying so rather than reporting the absence of an
      // address: writing one byte of UTF-8 is how a string stops being UTF-8.
      case TIndex(recv, _, _) if recv.ty == Type.Str =>
        err("a string is immutable, so its bytes have no address to write through")

      // `s.bytes` reinterprets the same three words as a `[]u8` (`04`), so its elements are the
      // string's own storage and assigning to one is the line above by another route — with a
      // literal's bytes in read-only memory, a segfault out of a program containing no `*T` at all.
      //
      // `s.bytes` reinterprets the same three words as a `[]u8` (`04`), so its elements are the
      // string's own storage and assigning to one is the line above by another route — with a
      // literal's bytes in read-only memory, a segfault out of a program containing no `*T` at all.
      case TIndex(_: TBytes, _, _) if writes =>
        err("a string is immutable, and 'bytes' views the string's own storage rather than a copy " +
          "of it — so writing through one is writing the string. Bytes you may write are bytes of " +
          "your own: copy them into a '[]u8' first")

      // Any other element of a read-only view: the view bound to a name, passed to a function, or
      // sliced again, which is what the arm above cannot reach.
      //
      // Only the *write* is refused. `&` is left alone here, unlike on the `val` itself (`13`), and
      // the difference is which tier the reader is in: `&v[0]` is a `*T` the moment it is written,
      // and `03` says in as many words that the guarantees stop there and that this is how a view
      // reaches a C function taking a pointer and a length. `printf("%.*s")` is exactly that call
      // and so is `memchr`, which is what the prelude's own `find_byte` is. Refusing it would leave
      // a type that cannot do the job it was added for, while buying nothing: `*T` is greppable, and
      // a program that has none still cannot reach these bytes.
      case TIndex(recv, _, _) if writes && Type.readOnlyView(recv.ty) && recv.ty != Type.Str =>
        err(s"this element belongs to a '${show(recv.ty)}', which views elements it may not write, " +
          "so there is nothing to assign through. Elements you may write are elements of your own: " +
          s"copy them into a '[]${show(Type.element(recv.ty).getOrElse(Type.Unknown))}' first")

      case _ =>
        // The enumeration names all four kinds rather than the three it used to: an element is a
        // place too, and leaving it out told a reader of this line that `&buf[0]` — which this
        // chapter's own pointer-difference example writes — was not something they could take.
        if !isPlace(t) then
          err(s"$what needs a variable, a field, an element, or a dereference — something with an address")
        // A `val` has an address, which is the whole difference between it and a `const` — what it
        // does not have is a writable one. `&` is refused along with assignment because a `*T` is a
        // licence to write, and handing one out would make the promise unkeepable one step away
        // from where it was written.
        if readOnly(t) then err(s"a 'val' is written once, so $what has nothing to write through")

    t
  }


  /** One level of automatic dereference, so a field is selected through a `*T` or a `&T`
   * exactly as it is on the value itself. One level only: reaching through a `**T` is written.
   */
  protected def autoDeref(t: TExpr): TExpr =
    Type.pointee(t.ty) match
      case Some(inner) => TDeref(t, Type.unqualified(inner))
      case None        => t

  /** How a diagnostic names an assignment target. */
  protected def describe(target: Expr): String = target match
    case Ident(n)      => s"'$n'"
    case Field(_, f)   => s"field '$f'"
    case Unary("*", _) => "the place it points at"
    case Index(_, _)   => "this element"
    case _             => "this place"
}
