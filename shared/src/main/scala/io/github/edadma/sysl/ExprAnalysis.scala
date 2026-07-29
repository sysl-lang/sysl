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
 * Places are here rather than beside assignment because what makes an expression a place is a
 * property of the expression: a local, a dereference, a field of either, and an element of a slice
 * have an address, and anything computed does not.
 */
trait ExprAnalysis extends SpecialForms with PatternAnalysis with StmtAnalysis {

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
        // A closure is the other exception, and for the opposite reason to `null`'s: it has no type
        // of its own to be analyzed at and then erased, since what it takes is exactly what the
        // object's arguments say (`12 §5`). So the object is pushed down, and the erasure that
        // follows boxes the struct the literal became.
        case _: Lambda => coerce(analyzeValue(expr, Some(o)), o)
        case _         => coerce(analyzeValue(expr, None), o)

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

    case _ => analyzeValue(expr, expected)

  /** Wraps a base-typed value in the run-time check for a constrained subtype. */
  private def checkInto(v: TExpr, c: Type.Constrained): TExpr = TConstrainedCheck(v, c).setPos(v.pos)

  /** If `place` is a field of a struct that carries `invariant` clauses, wrap the write so the
   * struct's invariant is re-checked the moment the field changes; otherwise the write stands as it
   * is. The receiver of the field is the struct to re-read — the same node covers `s.f = v`, a
   * compound `s.f op= v`, and a through-pointer `(*p).f = v`, since each analyses to a field place.
   */
  private def withInvCheck(place: TExpr, store: TExpr): TExpr =
    invCheckFor(place) match
      case Some((recv, s, fn)) => TCheckedStore(store, recv, s, fn).setPos(store.pos)
      case None                => store

  /** The struct a write through `place` obliges a re-check of, if any: what to re-read, its type,
   * and the predicate to call. Split out from the wrapper above because a multi-assignment's writes
   * are statements rather than expressions, so there is nothing there for a node to wrap.
   */
  protected def invCheckFor(place: TExpr): Option[(TExpr, Type.Struct, String)] = place match
    case TField(recv, _, _) =>
      recv.ty match
        case s: Type.Struct if structDecls.get(s.base).exists(d => d.invariants.nonEmpty && d.tparams.isEmpty) =>
          Some((recv, s, invKey(s.base)))
        case _ => None
    case _ => None

  /** `Name(value)` — an explicit cast into a constrained subtype. The operand is taken at the
   * subtype's base and checked; a value whose base does not agree is a mistake the message names.
   */
  private def constrainedCast(key: String, args: List[Expr]): TExpr = {
    val c = resolveConstrained(key)

    if args.length != 1 then err(s"a '${qn(key)}' conversion takes exactly one value")
    val v = analyzeExpr(args.head, Some(c.base))
    if disagree(v.ty, c.base) then err(s"cannot make ${show(c)} from ${show(v.ty)}")
    checkInto(v, c)
  }

  /** A type attribute `T::Attr` (`03`), with the arguments a call form supplied (empty for the
   * bare form). Dispatched on the kind of type `T` is — a constrained subtype for now.
   */
  private def typeAttr(key: String, attr: String, args: List[Expr]): TExpr =
    if constrainedDecls.contains(key) then constrainedAttr(resolveConstrained(key), key, attr, args)
    else if enumDecls.contains(key) then
      if enumDecls(key).tparams.nonEmpty then
        err(s"'${qn(key)}' is generic, so '${qn(key)}::$attr' has no single enum to read")
      enumAttr(instantiateEnum(key, Nil), key, attr, args)
    else err(s"'${qn(key)}' has no type attributes")

  /** The attributes a constrained integer subtype exposes: its bounds (`First`/`Last`), the total
   * membership test (`Valid`), and the trapping steps (`Succ`/`Pred`).
   */
  private def constrainedAttr(c: Type.Constrained, key: String, attr: String, args: List[Expr]): TExpr = {
    val base = c.base match
      case i: Type.Integer => i
      case other           => err(s"'${qn(key)}::$attr' needs an integer subtype, not ${show(other)}")

    def ranged: (BigDecimal, BigDecimal) = (c.lo, c.hi) match
      case (Some(lo), Some(hi)) => (lo, hi)
      case _                    => err(s"'${qn(key)}::$attr' needs a 'within' range")

    def noArgs(): Unit = if args.nonEmpty then err(s"'${qn(key)}::$attr' takes no arguments")

    def oneArg(): TExpr =
      if args.length != 1 then err(s"'${qn(key)}::$attr' takes exactly one argument")
      val x = analyzeExpr(args.head, Some(base))
      if disagree(x.ty, base) then err(s"'${qn(key)}::$attr' takes a ${show(base)}, not ${show(x.ty)}")
      x

    attr match
      case "First" => noArgs(); TIntLit(ranged._1.toBigInt, base)
      case "Last"  => noArgs(); val (_, hi) = ranged; TIntLit((if c.exclusiveHi then hi - 1 else hi).toBigInt, base)
      case "Valid" => val x = oneArg(); ranged; TConstrainedValid(x, c)
      case "Succ"  => val x = oneArg(); ranged; TConstrainedStep(x, c, up = true, base)
      case "Pred"  => val x = oneArg(); ranged; TConstrainedStep(x, c, up = false, base)
      case "Range" => err(s"'${qn(key)}::Range' is only meaningful as the iterable of a 'for' loop")
      case _       => err(s"'${qn(key)}' has no attribute '$attr'")
  }

  /** The attributes a simple enum exposes: its endpoints (`First`/`Last`), the ordinal maps
   * (`Pos` a value to its 0-based position, `Val` a position back to its value), the neighbouring
   * values (`Succ`/`Pred`), and the name maps (`Image` a value to its name, `Value` a name to its
   * value). All but `First`/`Last` carry an operand, and the ones that could be handed something
   * out of range (`Val`, `Succ` at the end, `Pred` at the start, `Value` with no such name) trap.
   */
  private def enumAttr(en: Type.Enum, key: String, attr: String, args: List[Expr]): TExpr = {
    if !en.simple then err(s"'${qn(key)}::$attr' needs a simple enum, and '${qn(key)}' carries data")

    def noArgs(): Unit = if args.nonEmpty then err(s"'${qn(key)}::$attr' takes no arguments")

    def oneArg(want: Type): TExpr =
      if args.length != 1 then err(s"'${qn(key)}::$attr' takes exactly one argument")
      val x = analyzeExpr(args.head, Some(want))
      if disagree(x.ty, want) then err(s"'${qn(key)}::$attr' takes a ${show(want)}, not ${show(x.ty)}")
      x

    attr match
      case "First" => noArgs(); TIntLit(BigInt(en.variants.head.tag), en)
      case "Last"  => noArgs(); TIntLit(BigInt(en.variants.last.tag), en)
      case "Pos"   => TEnumAttr("Pos", en, oneArg(en), Type.Int)
      case "Val"   => TEnumAttr("Val", en, oneArg(Type.Int), en)
      case "Succ"  => TEnumAttr("Succ", en, oneArg(en), en)
      case "Pred"  => TEnumAttr("Pred", en, oneArg(en), en)
      case "Image" => TEnumAttr("Image", en, oneArg(en), Type.Str)
      case "Value" => TEnumAttr("Value", en, oneArg(Type.Str), en)
      case _       => err(s"'${qn(key)}' has no attribute '$attr'")
  }

  /** Whether a context of this type converts what it is given rather than simply requiring it. */
  private def converts(want: Type): Boolean =
    Type.erased(want) || want.isInstanceOf[Type.Ref] || want.isInstanceOf[Type.Weak]

  /** What an array form's elements should be analyzed as, given what the form itself is expected to
   * produce. A `string` is not on the list: its elements are bytes, but writing one is a validity
   * question rather than an arrangement of elements.
   */
  private def elementWanted(want: Type): Option[Type] = want match
    case Type.Array(_, e) => Some(e)
    case Type.Slice(e)    => Some(e)
    case _                => None

  /** Whether an expression yields its value through branches rather than producing one itself. */
  private def branching(expr: Expr): Boolean = expr match
    case _: IfExpr | _: MatchExpr | _: While | _: For => true
    case _                                            => false

  /** The three conversions a context may apply to a value that does not already have its type: a
   * `T` the context wanted by reference is boxed, a `&T` the context wanted weakly is weakened, and
   * something concrete where a trait object was wanted is erased into one. Nothing else coerces —
   * any other mismatch is left for the caller to diagnose, where the message can name the parameter
   * or the variable it is about.
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
    case _ => t

  private def analyzeValue(expr: Expr, expected: Option[Type], discarded: Boolean = false): TExpr =
    at(expr.pos)(analyzeValueAt(expr, expected, discarded)).setPos(expr.pos)

  private def analyzeValueAt(expr: Expr, expected: Option[Type], discarded: Boolean = false): TExpr = expr match
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
                    case None      => err(s"undefined name '${qn(name)}'")

    case Binary(op @ ("&&" | "||"), l, r) =>
      TLogical(op, analyzeBool(l), analyzeBool(r))

    case Binary(op, l, r) =>
      val List(tl, provisional) = analyzeOperands(List(l, r), expected.filter(Type.isNumeric))
      val tr                    = operandRhs(op, tl, r, provisional)

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

    // `b[i] = v` on a type with no elements of its own is `IndexSet`, and it is a call rather than a
    // store because a trait's method gives back a value and never an address — so there is no place
    // for the ordinary path to write through, and the trait says as much by taking the value.
    case Assign("=", Index(receiver, index), value) if indexes("IndexSet", receiver) =>
      callMethod(receiver, "index_set", List(index, value), None)

    // The compound forms would have to read the element and write it back, which means evaluating
    // the receiver and the index twice — and a container's subscript is a call, so twice is twice
    // the calls. Written out, the program says that itself.
    case Assign(op, Index(receiver, index), _) if indexes("IndexSet", receiver) =>
      err(s"'$op' on an element read through 'Index' would evaluate the receiver and the index " +
        s"twice — write it out as 'b[i] = b[i] ${op.dropRight(1)} …'")

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

      if d.isEmpty && arithType(binSym, place.ty, tv.ty) != place.ty then
        err(s"'$op' would change the type of ${describe(target)}")

      withInvCheck(place, TUpdate(place, op, tv, place.ty, d))

    // The forms the compiler resolves by name rather than by looking a function up: `print` and
    // its two rendering companions, which are temporary and leave once a `Display` trait can carry
    // them, and the four primitives no sysl body could implement — the unchecked byte-to-string
    // conversion and the three a variadic body needs — which stay. What each one means is in
    // `SpecialForms`; the dispatch is here so it reads in the order the match tries.
    case Call(Ident("print"), args)                         => printCall(args)
    case Call(Ident("str"), args)                           => strCall(args)
    case Call(Ident("format"), List(argExpr, StrLit(spec))) => formatCall(argExpr, spec)
    case Call(Ident("from_utf8_unchecked"), args)           => fromUtf8Unchecked(args)
    case Call(Ident("va_start"), args)                      => vaStart(args)
    case Call(Ident("va_end"), args)                        => vaEnd(args)
    case Call(Ident("va_arg"), args)                        => vaArg(args, expected)

    // `old(e)` is a contextual keyword read only while an `ensure` is being analyzed; the guard is
    // what lets `old` stay an ordinary name outside a postcondition.
    case Call(Ident("old"), args) if oldBuf.isDefined       => oldCall(args)

    // A conversion is written with call syntax, so a scalar type name in call position is one.
    case Call(Ident(name), args) if lookupOpt(name).isEmpty && scalarType(name).isDefined =>
      if args.length != 1 then err(s"a '$name' conversion takes exactly one value")
      convert(analyzeExpr(args.head), scalarType(name).get)

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
    // to reinterpret, and from a struct constructor, which line 479 already claimed.
    case Call(Ident(name), args) if lookupOpt(name).isEmpty && typeKey(name).exists(enumDecls.contains) =>
      enumFromInt(typeKey(name).get, args)

    // A local that is callable is called, and it wins over a declaration of the same name for the
    // reason the nearest binding always does. It is asked whether it is callable rather than merely
    // whether it is a local, so a name that shadows a function with something uncallable still
    // reaches the function — which is what it did before there were closures, and no program that
    // relied on it is silently rerouted.
    case Call(Ident(name), args) if lookupOpt(name).exists((_, t) => callableOf(t).isDefined) =>
      callCallable(analyzeExpr(Ident(name).setPos(expr.pos)), args, expected)

    case Call(Ident(name), args) if funcKey(name).isDefined =>
      callFunction(funcDecls(funcKey(name).get), args, expected)

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

    case Call(_, _) =>
      err("the thing being called must be a name")

    case f: Field if throughModule(f).isDefined =>
      analyzeValueAt(throughModule(f).get, expected)

    case Field(Ident(written), f) if lookupOpt(written).isEmpty && typeKey(written).exists(enumDecls.contains) =>
      val n = typeKey(written).get

      if enumDecls(n).variants.exists(_.name == f) then
        constructVariant(Modules.qualify(Modules.moduleOf(n), f), Nil, expected)
      else
        memberDecls.get((n, f)) match
          case Some(m) if m.isProperty =>
            err(s"'$f' is a property of '${qn(n)}' — read it on a value, as 'value.$f'")
          case Some(m) if m.receiver.isDefined =>
            err(s"'$f' is a method of '${qn(n)}' — call it on a value, as 'value.$f(…)'")
          case Some(_) => err(s"'$f' is an associated function of '${qn(n)}' — call it with '$written.$f(…)'")
          case None    => err(s"enum '${qn(n)}' has no variant '$f'")

    // A struct name is not a value, so a member selected from it is one of the three that could
    // have been meant rather than a field read — which is what the name would otherwise be reported
    // as, in an undefined-name message naming the type instead of the member.
    case Field(Ident(written), f) if lookupOpt(written).isEmpty && typeKey(written).exists(structDecls.contains) =>
      val n = typeKey(written).get

      memberDecls.get((n, f)) match
        case Some(m) if m.isProperty =>
          err(s"'$f' is a property of '${qn(n)}' — read it on a value, as 'value.$f'")
        case Some(m) if m.receiver.isDefined =>
          err(s"'$f' is a method of '${qn(n)}' — call it on a value, as 'value.$f(…)'")
        case Some(_) => err(s"'$f' is an associated function of '${qn(n)}' — call it with '$written.$f(…)'")
        case None    => err(s"type '${qn(n)}' has no member '$f' — and '${qn(n)}' is a type, not a value")

    // `T::Attr` — a type attribute read with no argument (`First`, `Last`). `T::Attr(x)` is a
    // `Call` over this node, handled beside the other call forms.
    case TypeAttr(Ident(name), attr) if lookupOpt(name).isEmpty && typeKey(name).isDefined =>
      typeAttr(typeKey(name).get, attr, Nil)

    case TypeAttr(_, attr) =>
      err(s"'::$attr' is a type attribute, so its left side must be a type name")

    case Field(receiver, f) =>
      val tr = autoDeref(analyzeExpr(receiver))
      tr.ty match
        // A trait object has no fields: the layout is exactly what it forgot. What it still has is
        // whatever the trait declares, and a property is declared to be read exactly like this.
        case _ if Type.erased(tr.ty) =>
          readTraitObjectProperty(tr, Type.erasedTrait(tr.ty).get, f)

        // A tuple's parts are named for their positions, so `t.0` arrives here as an ordinary field
        // selection. An index past the end is worth its own complaint: nothing about "no property
        // '3'" tells a reader that what they wrote was one part too far.
        case t: Type.Tuple =>
          val idx = t.fieldIndex(f)
          if idx >= 0 then TField(tr, idx, t.fields(idx)._2)
          else if f.forall(_.isDigit) then
            err(s"${show(t)} has ${quantity(t.fields.length, "part")}, so there is no '.$f' — " +
              s"the parts are numbered from 0")
          else readProperty(tr, t, f)

        case s: Type.Struct =>
          val idx = s.fieldIndex(f)
          if idx >= 0 then
            checkFieldVisible(s.base, f)
            TField(tr, idx, s.fields(idx)._2)
          else readProperty(tr, s, f)

        // An enum has no fields to shadow a member, so every name read off one is a property.
        case e: Type.Enum => readProperty(tr, e, f)

        // A bound promises behaviour, and a property is behaviour spelled like a field — so this is
        // a bound's to license after all, and it is checked at the definition like every other use
        // of a parameter. What no bound reaches is a real *field*: that is layout, which is `10 §5`'s
        // rule and the complaint left when nothing declares a property of the name.
        case a: Type.Abstract => readBoundProperty(a, tr, f)
        // `len`, `bytes` and `chars` are the compiler-provided members: `len` a property on every
        // array, slice, and string, `bytes` the reinterpretation of a string's three words
        // as a `[]u8`, dropping only the validity guarantee, and `chars` a cursor over the scalar
        // values those bytes encode. `chars` is the one that cannot be a view — the decoding is
        // what makes the characters — so it is the prelude's `Chars`, positioned at the start.
        case _: Type.Array | _: Type.View if f == "len" => TLen(tr)
        case Type.Str if f == "bytes"                   => TBytes(tr)
        case Type.Str if f == "chars"                   => callPrelude("chars_of", TBytes(tr))

        // `copy` is the one compiler-provided member of a string that is a *method*, so reading it
        // without the parentheses is told what a user type's method is told. The parentheses are
        // what say it allocates and walks the bytes (`08 § Property or method`), which is exactly
        // the information this line was missing.
        case Type.Str if f == "copy" =>
          err("'copy' is a method of 'string' — call it with 'copy()', since it allocates and " +
            "copies the bytes rather than naming what is already there")

        // Everything about the object is behind `get()`, including whether there still is one, so a
        // weak reference has no fields of its own to offer and none of the referent's either.
        case w: Type.Weak =>
          err(s"a ${show(w)} may be gone, so nothing is read off one directly — 'get()' hands back " +
            s"'Option[&${Type.show(w.inner)}]', and '$f' is read off what is inside it")

        // Any other type reaches its own members too, since an `impl` may be written for one and a
        // trait may ask for a property. A name none of them supplies is the older complaint, which
        // is the better one there: nothing about `x.foo` on an `int` says a property was meant.
        case other if hasMember(other, f) => readProperty(tr, other, f)
        case other                        => err(s"cannot read field '$f' of ${show(other)}")

    case ArrayLit(elems) =>
      val elemExp = expected.flatMap(elementWanted)
      val ts      = elems.map(analyzeExpr(_, elemExp))

      for t <- ts do
        if Type.noValue(t.ty) then err(s"an array cannot hold ${show(t.ty)} values")
        if t.ty != ts.head.ty then
          err(s"an array literal needs one element type, got ${show(ts.head.ty)} and ${show(t.ty)}")

      val elemTy = ts.headOption.map(_.ty).orElse(elemExp).getOrElse(
        err("an empty array literal takes its element type from its context, and there is none here"),
      )

      expected match
        case Some(Type.Slice(_)) => TBufLit(ts, Type.Slice(elemTy))
        case _                   => TArrayLit(ts, Type.Array(ts.length, elemTy))

    // `[v; n]` — the form for an array whose element type has no zero, or has one that is not the
    // wanted starting value. The value is evaluated **once** and copied into every element, which
    // is what makes `[f(); 8]` mean one call rather than eight.
    //
    // What is being asked for decides which of the two things this is (`07 §Storage sized while
    // running`). Under a `[N]T` the count is part of the type and so a compile-time constant; under
    // a `[]T` the length is not in the type at all, so the count is an ordinary expression and the
    // elements are storage of their own that the view owns.
    case ArrayFill(value, count) =>
      val elemExp = expected.flatMap(elementWanted)
      val tv      = analyzeExpr(value, elemExp)

      if Type.noValue(tv.ty) then err(s"an array cannot hold ${show(tv.ty)} values")

      expected match
        case Some(Type.Slice(_)) =>
          val tc = analyzeExpr(count)

          // A count is an index's twin, so it takes a transparent subtype for the same reason one
          // does — and refuses a derived one for the same reason too.
          if !Type.repr(tc.ty).isInstanceOf[Type.Integer] then
            err(s"a repeat count is a number of elements, and ${show(tc.ty)} is not an integer")

          TBufFill(tv, tc, Type.Slice(tv.ty))

        case _ =>
          val n = constInt(count) match
            case Some(v) if v >= 0 && v.isValidInt => v.toInt
            case Some(v)                           => err(s"an array cannot have $v elements")
            case None =>
              err("an array's repeat count must be a constant, since it is the array's bound — a " +
                "literal, or a 'const' naming one. A count computed while running makes storage " +
                "instead, which is written where a '[]T' is expected")

          TArrayFill(tv, Type.Array(n, tv.ty))

    // A range subscript takes a view. The receiver is left *undereferenced* on purpose: for a
    // heap array the reference is both where the elements are and what keeps them alive, and
    // evaluating it once is what makes those the same object.
    case Index(receiver, RangeExpr(lo, hi, inclusive)) =>
      if !inclusive && hi.isEmpty then err("an open-ended slice is written 'a[lo..]'")

      val tr = analyzeExpr(receiver)

      // A `[]T` permits writes and records nothing about where its elements came from, so a view of
      // a `val` would be a way of writing one. Refused outright rather than allowed and policed,
      // since the view outlives the expression that made it: what this wants is a read-only slice
      // type, and that is a decision about `07`'s view types rather than about `val`.
      if readOnly(tr) then
        err("a 'val' cannot be sliced: a slice permits writes and does not record whose elements it " +
          "views, so the view would be a way of writing one")

      val elem = tr.ty match
        case Type.Ref(Type.Array(_, e), false) => e
        case Type.Ref(Type.Array(_, _), true) =>
          err("a slice does not record whether its owner's count is atomic, so a '&sync' array cannot be sliced")
        case w: Type.View               => w.elem
        case Type.Array(_, e)           => e
        case Type.Ptr(Type.Array(_, e)) => e

        // A view of a `*T` region — the shape every C function that fills a buffer hands back. The
        // pointer carries no length, so the end must be written and is taken on trust; that is the
        // same trust `*p` already asks for, and the resulting view owns nothing (`05`).
        case Type.Ptr(e) =>
          if hi.isEmpty then
            err(s"a ${show(tr.ty)} carries no length, so a view of one needs its end written — " +
              "'p[0..<n]' with the number of elements that are really there")
          e

        case other => err(s"cannot slice ${show(other)}")

      // Part of a string is a string, not a `[]u8` — the bytes between two character boundaries
      // are still well-formed UTF-8, which is what the check at those boundaries is for.
      val viewTy = if tr.ty == Type.Str then Type.Str else Type.Slice(elem)

      TSlice(tr, lo.map(bound), hi.map(bound), inclusive, viewTy)

    case Index(receiver, index) =>
      val raw = analyzeExpr(receiver)

      // `p[i]` on a raw pointer is C's, unchecked: the pointer is a bare address, so the subscript
      // is the address arithmetic C spells the same way. It is read off the *undereferenced*
      // receiver — a pointer to an array keeps the checked form below, because that one has a
      // length in its type to check against.
      val tr = raw.ty match
        case Type.Ptr(_: Type.Array) => autoDeref(raw)
        case _: Type.Ptr             => raw
        case _                       => autoDeref(raw)

      Type.element(tr.ty) match
        case Some(elem) =>
          val ti = analyzeExpr(index, Some(Type.Usize))

          // A transparent constrained subtype stands where its base does, so an `Index within 0..<n`
          // indexes without a cast. A derived one does not: `new` is nominal, and reaching the base
          // is exactly what a written conversion is for.
          Type.repr(ti.ty) match
            case _: Type.Integer => TIndex(tr, ti, elem)
            case other           => err(s"an index must be an integer, not ${show(other)}")

        // A type with no elements of its own is indexed through `Index`, whose one method the
        // subscript *is* (`14 §3`). The index is not held to being an integer here: what a
        // container is read by is the trait's own argument, and a type that indexes by something
        // else is implementing a different `Index` rather than misusing this one.
        case None if indexes("Index", tr.ty) => callMethodOn(raw, "index", List(index), expected)

        case None => err(s"cannot index ${show(tr.ty)}")

    // An `if` whose own value is unused hands that down: each branch is a block in statement
    // position, so neither is asked what it yields and the two have nothing to disagree about.
    case IfExpr(cond, thenBody, elseOpt) =>
      val tc    = analyzeBool(cond)
      val tThen = analyzeValueBlock(thenBody, expected, discarded)
      val tElse = elseOpt.map(analyzeValueBlock(_, expected, discarded))
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
      val tarms = arms.map(analyzeArm(ts.ty, _, expected, discarded))
      TMatch(ts, tarms, matchResultType(ts.ty, tarms))

    // A loop's `else` is a block like any other, so a loop in statement position discards it too.
    // Without that, Python's own idiom — walk, `break` on a hit, set a flag in the `else` when
    // nothing hit — would be refused for a disagreement between the flag and a bare `break`.
    case While(label, cond, body, elseOpt) =>
      val tc            = analyzeBool(cond)
      val (tbody, ctx)  = analyzeLoopBody(expected, label)(analyzeStmts(body))
      val telse         = elseOpt.map(analyzeValueBlock(_, expected, discarded))
      TWhile(tc, tbody, telse, loopResultType(ctx, telse))

    case Loop(label, body) =>
      val (tbody, ctx) = analyzeLoopBody(expected, label)(analyzeStmts(body))
      TLoop(tbody, endlessResultType(ctx))

    // The init's binding belongs to the loop and to nothing outside it, so the scope opens before
    // the condition — which reads that binding — and closes after the `else`, which may too.
    case CFor(label, init, cond, step, body, elseOpt) =>
      pushScope()
      val tinit        = init.toList.flatMap(recoverStmt)
      val tcond        = cond.map(analyzeBool)
      val (tbody, ctx) = analyzeLoopBody(expected, label)(body.flatMap(recoverStmt))
      val tstep        = step.toList.flatMap(recoverStmt)
      val telse        = elseOpt.map(analyzeValueBlock(_, expected, discarded))
      popScope()
      // With no condition the loop cannot finish on its own, so its type is what its `break`s
      // carry, exactly as `loop`'s is — and an `else` that can never run is a mistake worth saying.
      if tcond.isEmpty && telse.isDefined then
        err("this 'for' has no condition, so it never finishes on its own and its 'else' cannot run")
      TCFor(tinit, tcond, tstep, tbody, telse,
            if tcond.isEmpty then endlessResultType(ctx) else loopResultType(ctx, telse))

    case For(label, name, iter, body, elseOpt) =>
      iter match
        // `for i in T::Range` iterates a constrained integer subtype's range, `First` through `Last`
        // inclusive — the one place `::Range` is meaningful.
        case TypeAttr(Ident(tn), "Range") if lookupOpt(tn).isEmpty && typeKey(tn).exists(constrainedDecls.contains) =>
          val c = resolveConstrained(typeKey(tn).get)
          val i = c.base match
            case i: Type.Integer => i
            case other           => err(s"'${qn(typeKey(tn).get)}::Range' iterates an integer subtype, not ${show(other)}")
          val (lo, hi) = (c.lo, c.hi) match
            case (Some(l), Some(h)) => (l, h)
            case _                  => err(s"'${qn(typeKey(tn).get)}::Range' needs a 'within' range")
          val last      = if c.exclusiveHi then hi - 1 else hi
          pushScope()
          val u         = declare(name, i)
          val (tb, ctx) = analyzeLoopBody(expected, label)(body.flatMap(recoverStmt))
          popScope()
          val telse     = elseOpt.map(analyzeValueBlock(_, expected, discarded))
          TFor(u, i, TIntLit(lo.toBigInt, i), TIntLit(last.toBigInt, i), inclusive = true, tb, telse,
               loopResultType(ctx, telse))

        case RangeExpr(Some(lo), Some(hi), inclusive) =>
          val List(tlo, thi) = analyzeOperands(List(lo, hi), None)
          if tlo.ty != thi.ty then
            err(s"a 'for' range needs matching bounds, got ${show(tlo.ty)} and ${show(thi.ty)}")
          val vty = tlo.ty match
            case i: Type.Integer => i
            case other           => err(s"a 'for' range iterates integer bounds, not ${show(other)}")
          pushScope()
          val u            = declare(name, vty)
          val (tb, ctx)    = analyzeLoopBody(expected, label)(body.flatMap(recoverStmt))
          popScope()
          val telse        = elseOpt.map(analyzeValueBlock(_, expected, discarded))
          TFor(u, vty, tlo, thi, inclusive, tb, telse, loopResultType(ctx, telse))

        case _ =>
          val seq = autoDeref(analyzeExpr(iter))
          seq.ty match
            case Type.Array(_, elem) => forEach(label, name, seq, elem, body, elseOpt, expected, discarded)
            case Type.Slice(elem)    => forEach(label, name, seq, elem, body, elseOpt, expected, discarded)
            // A string has two granularities and no reason to prefer one silently, so which one
            // is wanted is written: `s.bytes` for the bytes, `s.chars` for the characters they
            // encode. Neither is the default, because a program that means one rarely means both.
            case Type.Str =>
              err("a string is iterated as 's.bytes' or 's.chars', " +
                "since a string has bytes and characters both")
            case ty if iterateElem(ty).isDefined =>
              iterating(label, name, seq, iterateElem(ty).get, body, elseOpt, expected, discarded)
            case other =>
              err(s"'for' iterates an integer range, an array, a slice, or a type that implements " +
                s"'Iterate', and ${show(other)} is none of those")

    case TryExpr(e) =>
      analyzeTry(analyzeExpr(e))

    case _: RangeExpr =>
      err("a range is only allowed in a 'for' loop or a 'match' pattern")

    // `a, b` where a function's result list is what is being produced. It builds the aggregate the
    // caller takes apart — the same one a tuple builds, since a result list is a tuple's layout
    // without a tuple's type.
    case ResultList(values) =>
      if !retIsList then
        err("several values separated by commas are a function's result list, and this function " +
          "declares one result — write the values it wants, or declare a result list")

      val want = retTy.asInstanceOf[Type.Tuple]

      if values.length != want.targs.length then
        err(s"this function yields ${quantity(want.targs.length, "result")}, but " +
          s"${supplied(values.length, "value")}")

      val ts = values.zip(want.targs).map((v, w) => analyzeExpr(v, Some(w)))

      for ((t, w) <- ts.zip(want.targs)) do
        if disagree(t.ty, w) then
          at(t.pos)(err(s"this result is declared ${show(w)}, but the value is ${show(t.ty)}"))

      TStructNew(want, ts)

    case l: Lambda => analyzeLambda(l, expected)

    // `(a, b)` — a tuple, built exactly as a struct is: the parts are the fields, in the order they
    // were written. What each part is *wanted* at comes from the tuple being asked for, which is
    // what lets `var p: (i8, i8) = (1, 2)` narrow its literals the way a struct's fields do.
    case Tuple(elems) =>
      // A function declaring a result list yields several things and not one tuple (`12 §5b`), so
      // the parentheses are refused where they would build the carrier the form says never exists.
      if wantsResults(expected) then
        err(s"this function yields ${quantity(elems.length, "result")} rather than a tuple — " +
          s"write the values without the parentheses")

      val wanted = expected.map(Type.underlying) match
        case Some(t: Type.Tuple) if t.targs.length == elems.length => t.targs.map(Some(_))
        case _                                                     => elems.map(_ => None)

      val ts = elems.zip(wanted).map((e, w) => analyzeExpr(e, w))

      // A `unit` part is let through for the reason a `unit` field is (`00 §12`): the layout skips
      // it. `never` is refused for the reason it is refused everywhere but a result — a part that
      // is never produced is a part nothing can give the tuple.
      for t <- ts do
        if t.ty == Type.Never then
          at(t.pos)(err("a tuple part has to be a value, and this expression never produces one"))

      TStructNew(tupleType(ts.map(_.ty)), ts)

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
    // A property takes no arguments, so where two implementations of one trait both supply one there
    // is nothing to say which is meant — which `pickOverload` reports as the call it is.
    val chosen = pickOverload(ty, base, f, Nil)

    memberDecls.get((base, chosen)) match
      case Some(m) if m.isProperty =>
        checkMemberVisible(base, chosen, m)
        val fname      = memberFuncName(ty, chosen)
        val (_, rtype) = funcInsts(fname)
        funcsUsed += fname
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

  /** `for name in seq` over storage that is already there: each element is copied out by index, and
   * the sequence is evaluated once.
   */
  private def forEach(label: Option[String], name: String, seq: TExpr, elem: Type, body: List[Stmt],
                      elseOpt: Option[List[Stmt]], expected: Option[Type], discarded: Boolean): TExpr = {
    pushScope()
    val u         = declare(name, elem)
    val (tb, ctx) = analyzeLoopBody(expected, label)(body.flatMap(recoverStmt))
    popScope()
    val telse     = elseOpt.map(analyzeValueBlock(_, expected, discarded))
    TForEach(u, elem, seq, tb, telse, loopResultType(ctx, telse))
  }

  /** `for name in cursor` over a sequence that has to be produced a value at a time (`14 §7`).
   *
   * The cursor is the loop's own: the expression is evaluated once into a slot nothing outside the
   * loop can name, and `next` takes that slot's address, so a `Chars` or any other iterator advances
   * in place while whatever was written stays a value like every other. That the slot is a *copy* is
   * the ordinary value semantics — draining `for c in it` leaves an `it` the program declared
   * untouched, exactly as passing it to a function would.
   */
  private def iterating(label: Option[String], name: String, seq: TExpr, elem: Type, body: List[Stmt],
                        elseOpt: Option[List[Stmt]], expected: Option[Type], discarded: Boolean): TExpr = {
    val cursor = freshName("iter")
    val step   = callMethodOn(TLoad(cursor, seq.ty), "next", Nil, None)
    val opt = step.ty match
      case e: Type.Enum if e.base == "Option" && e.targs == List(elem) => e
      case other =>
        err(s"'Iterate' asks its 'next' for an ${show(Type.Enum("Option", List(elem)))}, " +
          s"and this one gives back ${show(other)}")

    pushScope()
    val u         = declare(name, elem)
    val (tb, ctx) = analyzeLoopBody(expected, label)(body.flatMap(recoverStmt))
    popScope()
    val telse     = elseOpt.map(analyzeValueBlock(_, expected, discarded))
    val bind      = TVariantPattern(opt, opt.variant("Some").get, List(TBindPattern(u, elem)))
    TIterate(cursor, seq.ty, seq, step, bind, tb, telse, loopResultType(ctx, telse))
  }

  /** What a type's `Iterate` implementation yields, or `None` where it has none.
   *
   * A type may implement one parameterized trait at more than one argument list (`02`), and for
   * every other trait the call's arguments are what say which — but `next` takes none, so a second
   * `Iterate` leaves the loop nothing to decide with. That is reported here rather than left to the
   * call, because the sentence a program needs names the loop.
   */
  private def iterateElem(ty: Type): Option[Type] = {
    val (key, targs) = memberOwner(ty)
    implsOf("Iterate", key).map(suppliedBound(_, "Iterate", ty, targs).args) match
      case Nil            => None
      case List(elem) :: Nil => Some(elem)
      case several =>
        err(s"${show(ty)} implements 'Iterate' " +
          s"${conjoin(several.map(a => s"'${Type.Bound("Iterate", a).show}'"))}, and a 'for' has " +
          "nothing to say which of them it means — call 'next' yourself, with the element type written")
  }

  /** Whether a subscript on this type reaches one of the two indexing traits, asked of a type that
   * has no elements of its own. A built-in's subscript is never this: an array, a slice and a
   * string are indexed by the compiler, and nothing a program writes competes with that.
   */
  private def indexes(traitName: String, ty: Type): Boolean =
    implsOf(traitName, memberOwner(ty)._1).nonEmpty

  /** The same, asked of an assignment target before the statement has committed to being a store.
   * Whatever goes wrong while typing the receiver is left for the ordinary path to report, in the
   * place the programmer wrote it.
   */
  protected def indexes(traitName: String, receiver: Expr): Boolean =
    probe(autoDeref(analyzeExpr(receiver)).ty)
      .exists(t => Type.element(t).isEmpty && indexes(traitName, t))

  // --- names reached through a module ---------------------------------------------------

  /** A reference written as a chain of plain names: `std.fs.read` is `["std", "fs", "read"]`.
   * `None` for anything else, since a chain interrupted by a call or a subscript is a value being
   * read from rather than a path being named.
   */
  private def chain(e: Expr): Option[List[String]] = e match
    case Ident(n)    => Some(List(n))
    case Field(r, f) => chain(r).map(_ :+ f)
    case _           => None

  /** A reference reaching into a module, rewritten with the module folded into the name it
   * qualifies — `std.fs.read` becomes the one name `std.fs`'s `read` is keyed under — or `None`
   * where the chain names no module.
   *
   * That rewrite is the whole of what qualified access needs: what is left is `read(…)`,
   * `Point(…)`, `Shape.Circle(…)` — the ordinary forms, resolved by the cases that already handle
   * them, against tables that were keyed this way to begin with.
   *
   * Two rules decide it, and both are `13 §3`'s. **A local binding shadows a module name**, so a
   * chain whose head is bound to a value is a field read and nothing else — which is why this
   * cannot be a pre-pass over the tree and has to be asked where the scopes are. And the
   * **longest** module prefix wins, so a module `a.b` is reached as one rather than as `a`'s `b`.
   *
   * A head that names no module is read as an import of one, which is what makes the `fs` of
   * `import std.fs` a prefix everywhere a written path is.
   */
  protected def throughModule(e: Expr): Option[Expr] =
    for
      written <- chain(e) if written.length > 1 && lookupOpt(written.head).isEmpty
      path = if namesModule(written.head) then written
             else importedModule(written.head).fold(written)(_.split('.').toList ::: written.tail)
      k <- (path.length - 1).to(1, -1).find(n => moduleNames(path.take(n).mkString(".")))
    yield
      val module = path.take(k).mkString(".")
      val rest   = path.drop(k)

      // The key this builds is spelled the way the compiler spells its own references, so resolving
      // it says nothing about which module wrote it — but *this* is a path a file wrote, in the
      // terms of the body being read, so the dependency it makes is recorded here (`13 §6`).
      dependsOn(module)
      rest.tail.foldLeft[Expr](Ident(Modules.qualify(module, rest.head)))((acc, n) => Field(acc, n))
        .setPos(e.pos)

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
      if a.ty != b.ty then err(s"cannot compare ${show(a.ty)} with ${show(b.ty)}")
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
   * assigned through and pointed at. A local, a dereference, and a field of either are places;
   * anything computed (a call result, an arithmetic result, a freshly built struct) is not.
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
        // A `val` has an address, which is the whole difference between it and a `const` — what it
        // does not have is a writable one. `&` is refused along with assignment because a `*T` is a
        // licence to write, and handing one out would make the promise unkeepable one step away
        // from where it was written.
        if readOnly(t) then err(s"a 'val' is written once, so $what has nothing to write through")

    t
  }

  /** Whether a place bottoms out in something bound by a `val` — either a module-level one or a
   * local. Reaching *into* one keeps the property: an element of a read-only array is read-only,
   * and so is a field of a read-only struct.
   */
  protected def readOnly(t: TExpr): Boolean = t match
    case _: TGlobal         => true
    case TLoad(name, _)     => readOnlyLocals(name)
    case TField(recv, _, _) => readOnly(recv)
    // Only where the elements are the receiver's own storage. A slice's are somebody else's, and
    // whose they are is exactly what a slice does not record.
    case TIndex(recv, _, _) =>
      recv.ty match
        case _: Type.View => false
        // A `val *T` fixes the address, not what is at it — exactly as `*p = v` through one is
        // already allowed. C's `T *const p` reads the same way.
        case _: Type.Ptr  => false
        case _            => readOnly(recv)
    case _ => false

  /** One level of automatic dereference, so a field is selected through a `*T` or a `&T`
   * exactly as it is on the value itself. One level only: reaching through a `**T` is written.
   */
  protected def autoDeref(t: TExpr): TExpr =
    Type.pointee(t.ty) match
      case Some(inner) => TDeref(t, inner)
      case None        => t

  /** How a diagnostic names an assignment target. */
  protected def describe(target: Expr): String = target match
    case Ident(n)      => s"'$n'"
    case Field(_, f)   => s"field '$f'"
    case Unary("*", _) => "the place it points at"
    case Index(_, _)   => "this element"
    case _             => "this place"
}
