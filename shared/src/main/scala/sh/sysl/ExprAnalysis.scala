package sh.sysl

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
    with RawStorage
    with Atomics {

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
    // A default filled at a call arrives **wrapped**, and a wrapper is not a shape. Every arm below
    // asks what the expression *is* — a `ptr_cast`, a branching form, a closure literal, `null` —
    // and each of them would answer about the wrapper rather than about what was written inside it,
    // so the expression would be reached having already lost the expectation those arms exist to
    // push down. A closure default at a callable parameter is where that showed: `apply(g: &Fn(int)
    // -> int = y -> y * 2)` was accepted at its declaration and refused at the first call that took
    // it, for `y` having no type.
    //
    // The wrapper is entered first instead, with the expectation intact: it puts the declaration's
    // scope back and comes straight back here with the expression that was actually written.
    case Some(_) if expr.isInstanceOf[DefaultArg] => analyzeValue(expr, expected)

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

    // **The `&` in a type and the `&` in front of a value are different things**, and this is the
    // one place both are written at once. `&T` is a counted box, which owns what is in it; the
    // operator takes an address, which owns nothing. So an address is not a way of making a box —
    // and the box needs no operator at all, since the arms below put the value in one wherever it
    // stands. Said here rather than left to the mismatch, because that reports two spellings of `&`
    // at somebody who has just written the other one on purpose.
    //
    // It sits **above** the trait-object arm because `&Shape` is both — a counted box and erased —
    // and the arm that catches it first is the one that answers. A `*Shape` is erased and is not a
    // box, which is why the guard asks for the box rather than for erasure.
    case Some(r: Type.Ref) if addressOf(expr) =>
      err(s"'&' in front of a value takes its address, and the '&' in ${show(r)} is a counted box " +
        "— the two are different things, and an address is not a way of making a box. Drop the " +
        s"operator: a value read into a ${show(r)} is put in one where it stands")

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

    // A value produced into a transparent constrained subtype is analyzed at the **subtype**, and
    // then checked into it. The expectation is passed down whole rather than as the base because a
    // type parameter is solved from it: `val a: Age = widest()` binds `T` to what the reader wrote,
    // agreeing with the two routes that already do — a written argument, and an argument's own type.
    // Nothing else needs the base, since every reading of an expectation that must see through a
    // transparent subtype already goes through `repr`, which is the identity `disagree` uses.
    case Some(c: Type.Constrained) if !c.derived =>
      val v = analyzeValue(expr, Some(c))
      if disagree(v.ty, c.base) then v
      else if v.ty == c then v
      else checkInto(v, c)

    // A slice expectation is pushed down as it is — storage the expression *makes* takes whichever
    // form was asked for, so a buffer literal needs no conversion — and `coerce` settles what comes
    // back at the other one. Both directions come here, because the direction that is refused is
    // owed a message about what it would have allowed rather than the bare mismatch a reader gets
    // from two types that merely differ.
    case Some(v: Type.Slice) => coerce(analyzeValue(expr, Some(v)), v)

    // A vector expectation is pushed down as it is, so a literal fills the lanes and a repeat
    // splats, and `coerce` settles the case where what came back is one lane's worth — a scalar
    // broadcast into every lane.
    //
    // **It belongs here rather than at each consuming site, and that is what this dispatch is
    // for.** The splat was originally applied only where a `val` bound one and where an operator
    // balanced its operands, so `f() -> <8>f32 = 1.0` was refused for yielding an `f32` while the
    // `val` beside it took the same expression — two positions that ask the same question giving
    // different answers. Every site that pushes an expected type reaches this line.
    case Some(v: Type.Vector) => coerce(analyzeValue(expr, Some(v)), v)

    case _ => analyzeValue(expr, expected)

  /** Whether an expression is the raw-tier reinterpretation, which takes its expectation as written
   * (`03 § Reinterpreting storage`).
   */
  private def rawCast(e: Expr): Boolean = e match
    case Call(Ident("ptr_cast"), _) => true
    case _                          => false

  /** Whether an expression is the address **operator** — the half of `&`'s two meanings that a
   * counted-box expectation cannot take.
   */
  private def addressOf(e: Expr): Boolean = e match
    case Unary("&", _) => true
    case _             => false

  /** Whether an expression **names** something, as written: a name, a selection, an element, or a
   * dereference. `&` answers one of these about the thing it names, and refuses where that has no
   * address; anything else it is written in front of is a value, and `&` makes storage for it.
   *
   * The question is asked of the *written* form rather than of what it analyzed to, because that is
   * what a reader wrote and what a diagnostic has to be about. `x.f` is one spelling whether `f` is
   * a field or a property, and the two must not quietly become an address and a copy.
   */
  private def named(e: Expr): Boolean = e match
    case _: Ident | _: Field | _: Index | Unary("*", _) => true
    case _                                              => false

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
    // An alias's name in call position is the base's name in call position — `Tick(n)` where `Tick`
    // is a second spelling for `u32` is the `u32(n)` a reader would otherwise write, with nothing
    // checked on the way in because there is no constraint to check. `convertAt` is the same
    // dispatch a type in hand goes through, so a base that is an enum or a subtype gets its own
    // form rather than a scalar conversion.
    if plainAlias(key) then convertAt(resolveAlias(key), qn(key), args)
    else castConstrained(resolveConstrained(key), qn(key), args)

  /** `T(x)` — a conversion whose target is a type in hand rather than a name that was written out.
   *
   * The three forms a type's own name reaches in call position, chosen by what the type turns out
   * to be: the checked cast into a constrained subtype, the checked cast from an integer into a
   * simple enum, and the scalar conversions. **Construction is deliberately not among them** —
   * a struct's positional constructor takes a field list rather than a value, and a generic body
   * filling in an unknown struct's fields by position is not something to arrive at by accident. A
   * `T` that is a struct is refused here, naming the struct, exactly as `u8(x)` at one is.
   */
  private def convertAt(ty: Type, written: String, args: List[Expr]): TExpr = ty match
    case c: Type.Constrained => castConstrained(c, written, args)
    case e: Type.Enum        => enumFromIntAt(e, written, args)
    case other =>
      if args.length != 1 then err(s"a '$written' conversion takes exactly one value")
      convert(analyzeExpr(args.head), other)

  /** The same cast reached with the subtype in hand rather than with its name, which is what a
   * conversion written at a **type parameter** has: `T(x)` instantiated at an `Age` is the `Age(x)`
   * a reader would have written, and taking any other route would refuse it for not being a scalar
   * conversion — true, and beside the point, since the form does exist under the type's own name.
   */
  private def castConstrained(c: Type.Constrained, written: String, args: List[Expr]): TExpr = {
    if args.length != 1 then err(s"a '$written' conversion takes exactly one value")

    val v = analyzeExpr(args.head, Some(c.base))

    // A **transparent** subtype is its base (`16 §2`), so its name converts exactly as the base's
    // name does: `Age(n)` on an `int` base is the `int(n)` a reader would otherwise write, and the
    // range is then checked on the way in. Without this the only way into one is to arrive already
    // at the base, which is unwriteable for the case the feature exists for — a `c type` measures
    // a width nobody can name, so `Tick(xs.len)` has no longhand a program could portably fall back
    // on. A **derived** type keeps the stricter rule: `new` is what makes it a distinct type, and a
    // conversion into one is a wrap of a value already at the base rather than a scalar conversion.
    if !disagree(v.ty, c.base) then checkInto(v, c)
    else if c.derived then err(s"cannot make ${show(c)} from ${show(v.ty)}")
    else checkInto(convert(v, Type.underlying(c.base), Some(show(c))), c)
  }


  /** Whether a context of this type converts what it is given rather than simply requiring it. */
  private def converts(want: Type): Boolean =
    Type.erased(want) || want.isInstanceOf[Type.Ref] || want.isInstanceOf[Type.Weak] ||
      Type.readOnlyView(want)


  /** Whether an expression yields its value through branches rather than producing one itself.
   *
   * A block is one of them though it has a single path: what a converting context has to reach is the
   * expression the value actually comes from, and for a block that is its trailing expression rather
   * than the block. Boxing or erasing the block instead would ask the whole statement list for
   * something only its last line can give.
   */
  private def branching(expr: Expr): Boolean = expr match
    case _: IfExpr | _: MatchExpr | _: While | _: DoWhile | _: For | _: Block => true
    case _                                                                    => false

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

    // The mode written twice — `weak &Node` against a `&Node`. `weak T` is *already* the weak edge
    // to a counted `T`, so the value on the right is the right shape and the type on the left says
    // the same word twice. What this must not do is fall into the case below and advise holding it
    // in a `&&Node`, which is a spelling the parser does not take: advice that cannot be typed is
    // worse than none, because the reader spends the time before finding that out.
    case Type.Weak(inner: Type.Ref) if t.ty == inner =>
      err(s"'weak ${show(inner.inner)}' is already a weak edge to a counted ${show(inner.inner)}, so " +
        s"the '&' says the mode a second time — write 'weak ${Type.show(inner.inner)}'")

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

    // An array where a view of it was asked for, which is `a[..]` performed by the position rather
    // than written by hand. The array is the one thing that already knows both halves a view is made
    // of — where the elements are, and how many — so nothing is taken on trust and no bound is
    // guessed. Written out, it is the conversion an array *literal* has always had here; a name for
    // the same array had to say `[..]`, and a page of caller-supplied storage read as ceremony
    // because of it.
    case v: Type.Slice => arrayView(t, v).map(coerce(_, v)).getOrElse(t)

    // A scalar where a vector was asked for — the splat, which puts the one value in every lane.
    // It is a coercion rather than a spelling because it is what `a * 2.0` means and what
    // `val v: <4>f32 = 0.0` means, and a construction written at each of those would be a word in
    // front of the commonest line in any kernel that uses this.
    //
    // The lane type has to match exactly, by the identity everything else here uses: `<4>f32` takes
    // an `f32` and not a `real`, for the same reason `f32` arithmetic does not take one. What makes
    // that painless is that a bare literal has already been read at the lane type (`Literals`'
    // `scalarWanted`), so the only values refused here are ones that were refused as scalars too.
    case v: Type.Vector if Type.repr(t.ty) == Type.repr(v.elem) => TSplat(t, v).setPos(t.pos)

    case _ => t

  /** The whole-array view an array coerces to, where its elements are the ones the slice wants.
   *
   * It is exactly what `a[..]` builds, by the same rules and through the same node: a view of
   * read-only storage is read-only, a view asked for as `[]const T` is made read-only rather than
   * made writable and then given up, and one that may be written is refused where storage inside an
   * invariant-carrying struct is what it would view. Anything else is left alone for the caller to
   * diagnose — where the elements differ, the message naming the array says more than one naming a
   * view built from it would.
   *
   * A **heap** array converts on the same terms as a frame one, and the reference is what the view
   * is built over rather than the array behind it: for a heap array the reference is both where the
   * elements are and what keeps them alive, which is the same reason `a[..]` leaves its receiver
   * undereferenced. A `&sync` array is not among them, since a view records nothing about whether
   * its owner's count is atomic; nor is a `*[N]T`, whose whole tier is written out.
   */
  private def arrayView(t: TExpr, want: Type.Slice): Option[TExpr] =
    val elem = t.ty match
      case Type.Array(_, e)                  => Some(e)
      case Type.Ref(Type.Array(_, e), false) => Some(e)
      case _                                 => None

    elem.filter(_ == want.elem).map { e =>
      val viewTy = Type.Slice(e, readOnly = readOnly(t) || want.readOnly)

      checkSliceable(t, viewTy)
      TSlice(t, None, None, inclusive = true, viewTy).setPos(t.pos)
    }

  /** What a reserved identifier stands for, folded into the use as the literal it names
   * (`ReservedNames`).
   *
   * The location three of them report is `reportedPos`, which is the node's own place everywhere
   * except while a parameter's default is being filled in — there it is the **call**, because a
   * default stands exactly where the argument would have been written (`12 §2a`). That one
   * substitution is the whole mechanism behind a checking function that names its caller's line
   * without any caller having written one down, and it is why sysl needs no `#[track_caller]`: the
   * call-site behaviour falls out of what a default already was.
   *
   * `__LINE__` and `__COLUMN__` go through `intLiteral`, so each takes the integer type its context
   * asks for and is range-checked like any other literal — a parameter declared `i32` gets an `i32`,
   * and one declared `u8` is told where a line number will not fit rather than wrapping.
   */
  private def builtin(name: String, expected: Option[Type]): TExpr = {
    def where: Pos = reportedPos.getOrElse(
      err(s"'$name' reports where it is written, and this is a node with no place in any file"))

    name match
      case "__FILE__" => TStrLit(where.source.name)
      case "__LINE__" => intLiteral(BigInt(where.line), None, expected)
      // The column of the **file**, which is not the one the lexer counted when the text it lexed had
      // its left margin taken off: a literate program's code sits four columns in (`Source`).
      // `Pos.location` adds the offset back for the same reason, and the two have to agree — a
      // diagnostic and a program that disagreed about one place would be worse than either alone.
      case "__COLUMN__" => intLiteral(BigInt(where.col + where.source.columnOffset), None, expected)
      // **Empty outside any body, rather than an error.** A module's storage is filled before any
      // function runs, so there is genuinely no function to name there — and refusing it would also
      // refuse a *default* of `__FUNCTION__`, which is checked once at its declaration where there
      // is no caller yet and is the one place this is most worth writing. An empty string is the
      // honest answer to "which function is this"; a stale one would not be, and was the bug.
      case "__FUNCTION__" => TStrLit(currentFunctionName)
      case "__DATE__" => TStrLit(ReservedNames.date(ReservedNames.stamp))
      case "__TIME__" => TStrLit(ReservedNames.time(ReservedNames.stamp))
      case _          => err(ReservedNames.unknown(name))
  }

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

    // A reserved identifier is the compiler's to answer and no scope is consulted: the shape may not
    // be declared at all (`ReservedNames`), so there is nothing a lookup could find and nothing that
    // could shadow one. That is the difference between these and `result` below, which is a
    // *contextual* keyword precisely because an ordinary binding of that name is allowed to win.
    case Ident(name) if ReservedNames.shaped(name) => builtin(name, expected)

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
        if lookupOpt(name).isEmpty && !ownValueName(name) && funcKey(name).isDefined &&
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
    // (`§5`, `§6`).
    //
    // **The other thing it may be is an address**, and the two are deliberately different spellings:
    // a bare name is the capture-free closure and `&name` is the address of code compiled to C's
    // convention (`§6a`). Where the context asks for one of those outright — a `pthread_create`, a
    // `qsort`, any interface that calls back — the missing `&` is the whole of the mistake, so the
    // message names it rather than the two callable forms the reader did not want.
    case Ident(name) if lookupOpt(name).isEmpty && !ownValueName(name) && funcKey(name).isDefined =>
      // **The name is quoted through `qn`, because it may be a key rather than what anybody wrote.**
      // A qualified path is folded into one name before it reaches here (`throughModule`), so a
      // reader who wrote `c.less` was being shown `c$less` — and then told to write `'&c$less'`,
      // which carries the module separator and is not sysl. `qn` is where every message naming a
      // declaration by its key turns it back into the path a reader would type.
      val shown = qn(name)

      err(
        if expected.exists(t => cfnOf(t).isDefined) then
          s"'$shown' is a function, and what is wanted here is the address of one — write '&$shown'. " +
            "A bare name is the capture-free closure, which has no address a C interface could call"
        else
          s"'$shown' is a function, and a function becomes a value only where a callable is wanted — " +
            "a bare-arrow parameter, or a '&Fn' where a concrete type is required. Nothing here asks " +
            s"for one; where the address of code is what is wanted, that is written '&$shown'",
      )

    /** A **value parameter** (`10 §9`), folded into its use exactly as a declared constant is —
     * which is what it is, a `const` whose value the instantiation supplied. The substitution holds
     * a `ConstArg` for it wherever the body is walked: the real argument at an instantiation, and a
     * zero placeholder during the walk that checks the generic body, where there is no argument yet
     * and the tree built is discarded. An array length written `[sizeof(T)]u8` already stands at
     * zero for that same walk and for the same reason.
     *
     * **A local of the same name still wins**, which is why the scope is asked first: a parameter is
     * the outermost binding of its name, not the only one.
     */
    case Ident(name)
        if lookupOpt(name).isEmpty && tsubst.get(name).exists(_.isInstanceOf[Type.ConstArg]) =>
      val c = tsubst(name).asInstanceOf[Type.ConstArg]

      c.ty match
        // A **simple enum's** argument travels as its tag, and what the body wants back is the
        // variant — so the name is handed to the ordinary variant path rather than reconstructed
        // here, and everything a written `Fast` gets (its type, its scope, its exhaustiveness)
        // follows from that.
        case en: Type.Enum =>
          en.variants.find(_.tag == c.value.toInt) match
            case Some(v) => analyzeExpr(Ident(v.name), Some(en))
            case None    => err(s"'$name' stands for no variant of ${show(en)}")
        case ty => analyzeExpr(constArgLiteral(c), Some(ty))

    case Ident(name) =>
      lookupOpt(name) match
        // A by-name parameter is read by **calling** it: the caller wrote an expression and the
        // compiler made it a nullary closure, so naming it here is where that closure runs. Doing it
        // at the read is what gives the feature its defining behaviour — each use is an evaluation,
        // because each use is a call — and it costs nothing else, since a call on a callable is the
        // ordinary method call `callCallable` already performs.
        case Some((u, ty)) if byNameLocals(u) =>
          callCallable(capturedFields.getOrElse(u, TLoad(u, ty)), Nil, expected)
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
                  globalKey(name) match
                    case Some(key) => TGlobal(key, globalType(key), globalWritable(key))
                    // An `extern` variable is storage too, so it becomes the same node — under the
                    // **symbol** rather than the key, since what it names is what the linker
                    // supplies, and writable, since the storage is not this program's to promise
                    // anything about (`12 §1`).
                    case None if externVarKey(name).isDefined =>
                      val key = externVarKey(name).get

                      externVarsUsed += key
                      TGlobal(externVarDecls(key).symbol, externVarType(key), writable = true)
                    // A name the block binds is a different mistake from one that stands for
                    // nothing, and the difference is what the reader has to fix.
                    case None if blockDeclares.contains(name) => notYetBound(name)
                    case None => unresolvedErr(s"undefined name '${qn(name)}'")

    case Binary(op @ ("&&" | "||"), l, r) =>
      TLogical(op, analyzeBool(l), analyzeBool(r))

    case Binary(op, l, r) =>
      val List(tl0, provisional) = analyzeOperands(List(l, r), expected.filter(Type.computesNumerically))
      val (tl, tr)               = balanceLanes(tl0, operandRhs(op, tl0, r, provisional))

      operatorCall(op, tl, tr).getOrElse(produced(TBinary(op, tl, tr, arithType(op, tl.ty, tr.ty, tr.pos))))

    // The operand's *base* decides which operators there are — a subtype narrows which values a type
    // has, never which operations it has — so the match reads through it and the node is typed by
    // `unaryType`, which keeps a derived result in its own type.
    case Unary("-", e) =>
      val t = analyzeExpr(e, expected.filter(Type.computesNumerically))
      prefixCall("-", t).getOrElse(Type.opSubject(t.ty) match
        case i: Type.Integer if i.signed => produced(TUnary("-", t, unaryType(t.ty)))
        case _: Type.Floating            => produced(TUnary("-", t, unaryType(t.ty)))
        case _: Type.Integer => err(s"unary '-' is not defined for the unsigned type ${show(t.ty)}")
        case _               => err(s"unary '-' is not defined for ${show(t.ty)}"))

    case Unary("!", e) =>
      TUnary("!", analyzeBool(e), Type.Bool)

    case Unary("~", e) =>
      val t = analyzeExpr(e, expected.filter(Type.computesNumerically))
      prefixCall("~", t).getOrElse(Type.opSubject(t.ty) match
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
    // The expected type is handed on because it is what settles a *generic* function's arguments:
    // there is no written form for them here, and a `*extern` being asked for already fixes the
    // signature (`12 §6a`).
    case Unary("&", Ident(name)) if lookupOpt(name).isEmpty && !ownValueName(name) && funcKey(name).isDefined =>
      functionAddress(name, funcKey(name).get, expected)

    // `&f[T]` and `&f[A, B]` — the address of an *instantiation*, with the arguments written out
    // (`12 §6a`). This is the one position in the language where type arguments are written rather
    // than inferred, and what earns it is the shape every C callback has: the interface fixes the
    // signature to untyped pointers, so a trampoline mentions its own type parameter nowhere and
    // there is nothing for the expected type to solve.
    //
    // **The grammar gives this the same shape as `&xs[i]`, and the discrimination is here rather
    // than there.** The name has to be a generic declaration and nothing nearer: a local shadowing
    // one is an ordinary indexed value, and reading its author's subscript as a type argument would
    // be worse than any message. That is the same shadowing test every call form above makes.
    // The test is that the name is a *function*, not that it is a generic one: a function cannot be
    // indexed, so there is no second reading to protect, and `&plain[i32]` is owed the message that
    // `plain` has no type arguments rather than a general complaint about callables.
    case Unary("&", Index(Ident(name), targ)) if lookupOpt(name).isEmpty && funcKey(name).isDefined =>
      functionAddress(name, funcKey(name).get, expected, List(targ))

    // More than one thing in the brackets was never an index, so this needs no shadowing test to be
    // sure of the reading — only to say something useful about a name that is not a generic
    // function, which `functionAddress` does with the declaration in hand.
    case Unary("&", TypeArgs(Ident(name), targs)) if lookupOpt(name).isEmpty && funcKey(name).isDefined =>
      functionAddress(name, funcKey(name).get, expected, targs)

    // The same three, written **qualified**. A function is not a place, so an address is taken from
    // the name above rather than by the walk below — and that walk is the only thing a module path is
    // folded into the name by (`throughModule`). So a qualified spelling reached neither: it fell
    // through to the walk, which found a function where it wanted storage and said so, quoting the
    // key it had by then rewritten the path into.
    //
    // Which is the shape `0104` had at a constant expression, and the answer is that one's: a name
    // means the declaration rather than a spelling of it, so the qualified form resolves wherever the
    // unqualified one does. `qualifiedFunc` hands back the spelling that was written beside the key,
    // because the message a refused address prints has to be something a reader can type.
    case Unary("&", e) if qualifiedFunc(e).isDefined =>
      val (written, key) = qualifiedFunc(e).get

      functionAddress(written, key, expected)

    case Unary("&", Index(e, targ)) if qualifiedFunc(e).isDefined =>
      val (written, key) = qualifiedFunc(e).get

      functionAddress(written, key, expected, List(targ))

    case Unary("&", TypeArgs(e, targs)) if qualifiedFunc(e).isDefined =>
      val (written, key) = qualifiedFunc(e).get

      functionAddress(written, key, expected, targs)

    // The same node anywhere else. A subscript takes one index, so what was written is a
    // type-argument list — and `12 §6a` is the only place one may be written, which is what this
    // says rather than complaining that a comma was unexpected.
    case TypeArgs(_, args) =>
      err(s"a subscript takes one index, and ${args.length} were written — a list of types in " +
        "brackets is a type-argument list, and the only place one is written is at an address, as " +
        "'&f[A, B]'. Everywhere else a generic's arguments are inferred, from the arguments at a " +
        "call or from the type the result is read into")

    // A nested function's environment is the frame it was declared in (`12 §5a`), and an address is
    // a way of carrying it out of that frame — the same reason the name is not a value either.
    case Unary("&", Ident(name)) if lookupOpt(name).isEmpty && (nestedFuncs.contains(name) || outerNested(name)) =>
      err(s"'$name' is a nested function, so it has no address to take — what would have to travel " +
        "beside the address is the frame it reads, and a '*extern' is one word. A top-level " +
        "function is what has an address")

    case Unary("&", e) =>
      val t = analyzeExpr(e)

      // **A name is asking for THAT thing's address, so it is answered rather than given a copy.**
      // The line is what the reader wrote and not what it turned out to be, which is exactly what a
      // diagnostic is for: `&t.f` at a property and `&t.g` at a field are one spelling, and handing
      // the first a pointer into a copy nothing else can see would make the two silently different.
      // A constant is the same shape — `&capacity` reads as storage and there is none. Everything
      // below is written as something computed, and asks for storage rather than for an address.
      if isPlace(t) || named(e) then
        val place = requirePlace(t, e, "'&'", writes = false)
        checkAddressable(place)
        // The address of a register is an address *of a register*, so the qualifier travels with it and
        // every access through the result stays an access to a device (`03 § Device memory`).
        TAddrOf(place, Type.Ptr(place.placeTy))
      // Something computed has no address of its own, so one is made for it: the value is written
      // into a hidden local of this scope and what comes back is that slot's address (`TTempAddr`).
      // The pointer is then exactly as good as one taken of a `var` the program wrote itself, which
      // is what `03` already says about every `*T`.
      else
        // A value nothing occupies has no storage to point at, and a pointer to it could only ever
        // be read through to produce nothing — so the address is refused rather than handed back
        // aimed at a slot of no bytes.
        if Type.noValue(t.ty) then
          err(s"${show(t.ty)} is not a value, so there is nothing to make an address of")

        // An opaque type's shape is the C side's, so there is no slot to lay down for one here —
        // the same reason `*c` is refused where `c` itself was fine (`15 §9`).
        Type.underlying(t.ty) match
          case s: Type.Struct => checkLayoutKnown(s.base, s.name)
          case _              => ()

        TTempAddr(t, Type.Ptr(t.ty))

    case Unary("*", e) =>
      val t = analyzeExpr(e)
      Type.pointee(t.ty) match
        case Some(inner) =>
          // Reading through the pointer produces the **value**, which is the one thing an opaque
          // type has no shape for out here — so `*c` is refused where `c` itself was fine (`15 §9`).
          Type.underlying(inner) match
            case s: Type.Struct => checkLayoutKnown(s.base, s.name)
            case _              => ()

          TDeref(t, Type.unqualified(inner))
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

      if ts.exists(t => Type.repr(t.ty).isInstanceOf[Type.Vector]) then vecCompare(ts, ops)
      else compareChain(ts, ops.indices.map(i => compareLink(ops(i), ts(i), ts(i + 1))).toList)

    // A parameter's default, spliced in where the argument was not written (`12 §2a`). It is
    // analyzed in the declaration's own terms and with nothing local in scope, which is what makes
    // it mean the same thing from every module that calls the function.
    case d @ DefaultArg(owner, e) =>
      atCallSite(d.pos)(at(e.pos)(filling(e.pos)(inDefault(owner)(analyzeExpr(e, expected)))))

    // Argument binding replaces every one of these before a call's arguments are looked at, so one
    // arriving here was written where nothing is being called by name — in an array literal, on the
    // right of an operator, or at a call through something that carries no parameter names.
    case NamedArg(name, _) =>
      err(s"'$name = …' names an argument, and this is not a call to a declaration that names its " +
        s"parameters — write the value on its own, or '($name = …)' for the assignment")

    // Argument binding replaces every one of these too, and for the same reason it needs a
    // parameter to replace it against: a block is an array of its lines at a collection parameter
    // and a closure over them at a callable one, and a callee with no parameters at all — a value
    // being called through, a variadic's tail — has neither to offer.
    case _: BlockArg =>
      err("a trailing block stands at a parameter of the declaration being called, and this call " +
        "reaches nothing that declares any — write the argument in the parentheses instead")

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

    // `p.count = v` where `count` is a settable property is a **call**, exactly as `b[i] = v` on a
    // container is (`14 §7`, `00 §2`): a property computes rather than naming storage, so there is
    // no place for a store to write through, and the setter takes the value instead.
    case Assign("=", Field(receiver, name), value) if settable(receiver, name) =>
      checkNotOwnSetter(receiver, name)
      callMethod(receiver, DeclParser.setterName(name), List(value), None)

    // The compound forms, which is where a property parts company with `IndexSet`. `14 §7` refuses
    // `b[i] += v` because the receiver *and the index* would each be evaluated twice; a property has
    // no index, so taking the receiver's address once is the whole of what the form needs — and it
    // is the line the feature exists for, `count += 1` rather than the two calls written out.
    //
    // It is desugared into source rather than built here, so every rule arrives through its ordinary
    // spelling: `&` refuses a receiver with no address, the setter's own `*self` refuses a `val`,
    // and the arithmetic is whatever `+` means for that type. The temporary holds the **address**,
    // since a copy of the receiver would be written and thrown away.
    case a @ Assign(op, Field(receiver, name), value) if settable(receiver, name) =>
      // Asked of the receiver as **written**, and before the desugaring below replaces it with the
      // temporary holding its address — after that there is no `self` left to recognize.
      checkNotOwnSetter(receiver, name)

      if !addressable(receiver) then
        err(s"'$op' reads '$name' and writes it back, so it needs a receiver it can reach twice — " +
          "this one is computed, and has no address. Bind it to a 'var' first")

      val tmp                    = s"${Modules.sep}recv"
      def here[T <: Positioned](e: T): T = e.setPos(a.pos)
      def through: Expr          = here(Field(here(Unary("*", here(Ident(tmp)))), name))

      analyzeExpr(
        here(Block(List[Stmt](
          here(ValDecl(tmp, None, here(Unary("&", receiver)))),
          here(ExprStmt(here(Assign("=", through, here(Binary(op.dropRight(1), through, value)))))),
        ))),
        expected,
      )

    // A property with no setter. The ordinary path would report an expression with no address, which
    // is true and is not what the reader needs to know: what is missing is the member, and the
    // sentence that says so also says what to write.
    case Assign(op, Field(receiver, name), _) if readOnlyProperty(receiver, name).isDefined =>
      val p   = readOnlyProperty(receiver, name).get
      val msg = s"'$name' is a property of '${p.of}', which computes rather than naming storage, " +
        s"so ${if op == "=" then "there is nothing to assign through" else s"'$op' has nothing to write back"} " +
        s"— write 'set $name(…)' ${p.where} to give it a setter"

      if p.viaBound then boundErr(msg) else err(msg)

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
      // `v += 1.0` is `v = v + 1.0`, so a scalar splats here exactly as it does in the binary form.
      // The two spellings reach the same instruction and have to agree about it, which is the rule
      // this whole branch is written around.
      val tv     = balanceLanes(TZero(place.ty), analyzeExpr(value, updateExpected(binSym, place.ty)))._2
      val d      = updateDispatch(binSym, place, tv)

      // What has to hold is that the result can be stored back. A constrained place is the one case
      // where the arithmetic's type and the place's legitimately differ — a transparent subtype
      // computes at its base — so the test is on the representation the two share, and what the
      // difference costs is the check `constraintOf` asks for. `disagree` is that comparison plus
      // the suppression a poisoned type wants: a place whose type could not be worked out has been
      // complained about once already, and saying its `+=` changes a type is a second complaint
      // about the consequence.
      if d.isEmpty && disagree(arithType(binSym, place.ty, tv.ty, tv.pos), place.ty) then
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

    // The atomic tier, which is the raw one — an address, values, and an ordering the call spells
    // out. The fence is separate because it reaches no address at all (`06 § The kernel tier`).
    // Unlike the forms above, these stand aside for a declaration of the same name — nine names is
    // too much of a program's vocabulary to take outright (`Atomics.unclaimed`).
    case Call(Ident("atomic_fence"), args) if atomicFenceForm => atomicFence(args)
    case Call(Ident(name), args) if atomicForm(name)          => atomicCall(name, args)

    // `sizeof(T)` / `alignof(T)` / `offsetof(T, f)` — the parser has already read the operand as a
    // type, which is what separates these from every form above: they are syntax rather than a name
    // the analyzer knows.
    case LayoutOf(what, tr)                                 => layoutOf(what, tr)
    case OffsetOf(tr, field)                                => offsetOf(tr, field)

    // `old(e)` is a contextual keyword read only while an `ensure` is being analyzed; the guard is
    // what lets `old` stay an ordinary name outside a postcondition.
    case Call(Ident("old"), args) if oldBuf.isDefined       => oldCall(args)

    // A conversion is written with call syntax, so a type name in call position is one — a built-in,
    // or a **type parameter**, which every instantiation replaces with a type this same form would
    // have accepted written out. That is what makes the two directions symmetric: `u8(x)` where `x`
    // is a `T` was always ordinary code, checked once the width is concrete, and `T(b)` is the same
    // check at the same moment.
    //
    // A **parameter is asked about first**, ahead of every declaration table below, because it is
    // the nearer binding: `var y: T` inside a `[T]` body already means the parameter whatever else
    // is called `T`, and a name cannot mean the parameter in type position and a declaration in
    // call position. A built-in is asked about only where no declaration claims the name, which is
    // where it was asked before.
    case Call(Ident(name), args) if lookupOpt(name).isEmpty &&
        (tsubst.contains(name) || (typeKey(name).isEmpty && scalarType(name).isDefined)) =>
      convertAt(typeNamed(name).get, name, args)

    // A constrained subtype's name in call position wraps a base value into the subtype, checking it
    // — `Age(n)`, `Meters(3.0)`. Unlike an implicit produce site, the cast is written, so it applies
    // even where the base would not flow in on its own.
    case Call(Ident(name), args) if lookupOpt(name).isEmpty && typeKey(name).exists(constrainedDecls.contains) =>
      constrainedCast(typeKey(name).get, args)

    // A **type parameter** in the same position, which reaches `Min` and `Max` and is told what the
    // rest are asked on. It comes first for the reason it does in `typeAttrExpr`: a body's own
    // parameter shadows anything a surrounding scope declares under that name.
    case Call(TypeAttr(Ident(name), attr), args) if lookupOpt(name).isEmpty && tsubst.contains(name) =>
      parameterAttr(name, tsubst(name), attr, args)

    // `T::Attr(x)` — a type attribute that takes an argument (`Valid`, `Succ`, `Pred`).
    case Call(TypeAttr(Ident(name), attr), args) if lookupOpt(name).isEmpty && typeKey(name).isDefined =>
      typeAttr(typeKey(name).get, attr, args)

    // An integer's attributes take no argument, so this reaches `integerAttr` only to be refused
    // there by name — which is a better answer than the generic "not callable" this would fall to.
    case Call(TypeAttr(Ident(name), attr), args) if lookupOpt(name).isEmpty && builtinInteger(name).isDefined =>
      integerAttr(builtinInteger(name).get, name, attr, args)

    // A bare variant name in call position — `Circle(3)` — with the enum taken from the expected
    // type, exactly as `Ident` above takes it for a nullary one.
    //
    // **A struct of the same name is asked about first**, which is what the guard is for. The two
    // are in different namespaces — a variant is a value name and a struct is a type name — so a
    // module may declare both, and only the *call* has to choose between them. It chooses the way a
    // bare variant is resolved everywhere else: the expected type decides where it names the
    // variant's enum, and the struct wins where it does not.
    //
    // **The asymmetry is the argument, rather than a preference for structs.** A variant always has
    // the qualified `Enum.Variant` spelling, so standing aside costs it nothing it cannot get back;
    // a struct constructor is named by the struct's own name and has no second spelling at all. The
    // arm used to come first unguarded, which left such a struct impossible to construct by any
    // spelling — found from `box2d`, whose `ShapeKind` names five of the shapes it also declares
    // (card `0220`).
    // **The struct is asked about with `structInScope` rather than `typeKey`**, because this is the
    // compiler asking itself a question rather than resolving a name a file wrote — `typeKey` would
    // raise on a candidate the site may not name, and would file a module dependency for a
    // declaration the program never reached.
    case Call(Ident(name), args)
        if lookupOpt(name).isEmpty && variantKey(name).isDefined &&
          (!structInScope(name) || variantEnumExpected(variantKey(name).get, expected).isDefined) =>
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

    // A nested function of this block whose environment does not exist yet, for one of two reasons —
    // and they are different mistakes, so they get different sentences (`0224`).
    case Call(Ident(name), _) if lookupOpt(name).isEmpty && pendingNested.exists(_.name == name) =>
      // The group is waiting on a binding written below this call. The functions themselves are
      // above it, so "declared below this call" would be flatly false — what is below is the data.
      //
      // **All of them wait, not only the one that reads it**, and that is forced rather than
      // conservative: a block's nested functions share **one** environment, which is what lets them
      // call each other in either order, so there is nothing to pass to any of them until it is
      // built.
      if awaitingNeeds then
        val waiting = pendingNeeds.toList.sorted
        val which   = waiting.map(n => s"'$n'").mkString(", ")
        val it      = if waiting.length == 1 then "it" else "them"

        err(s"'$name' cannot be called here — the nested functions of this block share one " +
          s"environment, and it is not built until everything they read is bound. $which " +
          s"${if waiting.length == 1 then "is" else "are"} bound below this call: move the call " +
          s"below $it, or move $it above the functions")
      // The ordinary case: the call is written above the functions themselves.
      else
        err(s"'$name' is declared below this call — the nested functions of a block share an " +
          "environment formed where the first of them is written, so they may be called from there on")

    // One belonging to a body this one is written inside. A body reaches its own group and no
    // further, because what it would have to carry to reach further is the frame around it.
    case Call(Ident(name), _) if lookupOpt(name).isEmpty && outerNested(name) =>
      err(s"'$name' is a nested function of the body around this one, which reaches its own nested " +
        "functions and its own captures and no further — a top-level function is what several " +
        "bodies share")

    case Call(Ident(name), args) if funcKey(name).isDefined =>
      callOverloaded(funcKey(name).get, args, expected)

    // A name that is neither a local nor a function, holding a function pointer — a module-level
    // `val` is the one that reaches here, since it is resolved by neither of the two lookups above.
    // The general case further down would have taken it, but the complaint about an undefined
    // function comes first: a call head that is a *name* never gets that far (`12 §6a`).
    case Call(Ident(name), args)
        if lookupOpt(name).isEmpty && probe(analyzeExpr(Ident(name).setPos(expr.pos)))
          .exists(t => cfnOf(t.ty).isDefined) =>
      callThroughAddress(analyzeExpr(Ident(name).setPos(expr.pos)), args)

    // The same, for a name holding a **callable** rather than an address. Module storage may hold one
    // (`13 §7`), which is what a binding keeping a callback does — so `pending(n)` has to mean what
    // it would mean if `pending` were a local, and the general case below is unreachable from here
    // because the complaint about an undefined function comes first.
    case Call(Ident(name), args)
        if lookupOpt(name).isEmpty && probe(analyzeExpr(Ident(name).setPos(expr.pos)))
          .exists(t => callableOf(t.ty).isDefined) =>
      callCallable(analyzeExpr(Ident(name).setPos(expr.pos)), args, expected)

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

    // `.Circle(5)`, `.make(2)` — the forms below with the type's own name left off, resolved
    // against what the context expects (`reference/expressions.md § Implicit member`).
    case Call(ImplicitMember(f), args) => implicitCall(f, args, expected)

    // Reached through the enum name: `Color.try(n)` is the fallible constructor; otherwise a
    // data-carrying variant `Shape.Circle(5)`, the qualified form of the bare `Circle(5)`, or an
    // associated function the enum declares, which resolves exactly as a struct's does.
    case Call(Field(Ident(written), mname), args)
        if lookupOpt(written).isEmpty && typeKey(written).exists(enumDecls.contains) =>
      val tname = typeKey(written).get

      if mname == "try" then enumTry(tname, args)
      else if enumDecls(tname).variants.exists(_.name == mname) then
        constructVariant(Modules.qualify(Modules.moduleOf(tname), mname), args, expected, Some(tname))
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
      //
      // **An alias is not one of those and is answered by its base** (`16 §1` — a transparent alias
      // is the same type as its base), which is what the *read* form already does one line into
      // `constrainedMember`. Without it here the call fell through to the read's complaint, and for
      // an alias to a type with a written `impl` that complaint is *"call it with 'F.zero()'"* under
      // a line already reading `F.zero()`. An alias to a **declared** type never arrives: those are
      // followed at the key by `aliasedKey`, so only one naming a scalar, a pointer, an array or a
      // callable reaches this.
      if plainAlias(n) then callTypeAssociated(resolveAlias(n), written, mname, args, expected)
      else if memberDecls.get((n, mname)).exists(_.recvMode.isEmpty) then callAssociated(n, mname, args, expected)
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
      callTypeAssociated(typeNamed(written).get, written, mname, args, expected)

    // `Maybe[int].Just(1)` — the arguments written on the type a variant or an associated function is
    // selected *from*, which is the same rule one step to the left of the constructor below. Nothing
    // read it as a type, so the walk analyzed the brackets as an ordinary subscript and reported the
    // type's own name undefined: the one reading guaranteed not to help, since the name is defined
    // and is a type. Both spellings arrive as different nodes, one argument as an `Index` and a list
    // as a `TypeArgs`.
    case Call(Field(Index(Ident(written), targ), sel), args) if genericTypeName(written) =>
      typeArgsAtSelection(written, List(targ), sel, args)

    case Call(Field(TypeArgs(Ident(written), targs), sel), args) if genericTypeName(written) =>
      typeArgsAtSelection(written, targs, sel, args)

    case Call(Field(recv, mname), args) =>
      callMethod(recv, mname, args, expected)

    // `f[T](…)` — type arguments written at a call (`10 §2`). The list and a subscript are one
    // grammar, so what tells them apart is not the parser: the name is resolved, and a **function**
    // is not a thing that can be indexed, so there is no second reading of the brackets to protect.
    // That is the discrimination `&f[T]` already made in order to refuse this by name, turned from a
    // refusal into a solve.
    //
    // The name has to be a declaration and nothing nearer: a local shadowing one is an ordinary
    // indexed value called through, and reading its author's subscript as a type argument would be
    // worse than any message. That is the same shadowing test every call form above makes. It is
    // tested on being a *function* rather than a generic one, so `plain[i32](3)` is owed the message
    // that `plain` has no type arguments rather than a general complaint about callables.
    //
    // Both spellings arrive, and as different nodes: one argument is an ordinary `Index` and a list
    // is a `TypeArgs`, which is the split `&f[T]` against `&f[A, B]` already lives with.
    case Call(Index(Ident(name), targ), args) if lookupOpt(name).isEmpty && funcKey(name).isDefined =>
      callOverloaded(funcKey(name).get, args, expected, List(targ))

    case Call(TypeArgs(Ident(name), targs), args) if lookupOpt(name).isEmpty && funcKey(name).isDefined =>
      callOverloaded(funcKey(name).get, args, expected, targs)

    // The same, written **qualified**. A module path is folded into the name by `qualifiedFunc`,
    // exactly as it is at an address, so `mod.f[T](x)` resolves wherever the unqualified spelling
    // does rather than falling to the general complaint about a callee that is not a name.
    case Call(Index(e, targ), args) if qualifiedFunc(e).isDefined =>
      callOverloaded(qualifiedFunc(e).get._2, args, expected, List(targ))

    case Call(TypeArgs(e, targs), args) if qualifiedFunc(e).isDefined =>
      callOverloaded(qualifiedFunc(e).get._2, args, expected, targs)

    // `x.m[T](…)` — the same list on a **method**, and the one head where the second reading is
    // live: `x.handlers[i](…)` is a field holding a table of callables, indexed and called, which
    // is an ordinary thing to write. So the discrimination is stricter than the free function's — the
    // receiver is analyzed and asked whether it declares a *method* of that name, rather than a name
    // being looked for among the declarations of every type in the program. A field wins, because a
    // field is what the subscript would have been reaching into.
    case Call(Index(Field(recv, mname), targ), args) if methodWritten(recv, mname) =>
      callMethod(recv, mname, args, expected, List(targ))

    case Call(TypeArgs(Field(recv, mname), targs), args) if methodWritten(recv, mname) =>
      callMethod(recv, mname, args, expected, targs)

    // `Pair[K, V](…)` — the same list at a **constructor**, which is the other half of the call head.
    // A type applied to arguments is what a type argument list *is* everywhere else in the language,
    // so this is the spelling with the least to learn, and it means what the annotation means: the
    // instantiation is fixed and the arguments are checked against it rather than solving it.
    //
    // Both spellings arrive, and they arrive as different nodes: one argument is an ordinary `Index`
    // and a list is a `TypeArgs`, which is the split `&f[T]` against `&f[A, B]` already lives with.
    // The shadowing test is every other call form's — a local standing over the type's name is an
    // ordinary indexed value, and reading its author's subscript as a type argument would be worse
    // than any message.
    // Two cases rather than one alternative, because a pattern alternative may bind no variable.
    case Call(Index(Ident(written), targ), args) if genericTypeName(written) =>
      constructWritten(written, List(targ), args)

    case Call(TypeArgs(Ident(written), targs), args) if genericTypeName(written) =>
      constructWritten(written, targs, args)

    // A special form written with type arguments. **`va_arg[int](ap)` is the one this is for**, and
    // `12 §9` named it as the strongest case for the syntax: everywhere else the annotation that
    // stands in is a word on a binding that was going to be written anyway, and a variadic body
    // reading its tail straight into `print` has no binding at all. `ptr_cast[T](p)` is the same
    // shape from the raw tier — both take their type from what receives the value, so writing it
    // here is writing it where the value is made.
    //
    // The rest take none, for the reason a non-generic function takes none: there is nothing for an
    // argument to be an argument *of*.
    case Call(Index(Ident(name), targ), args) if lookupOpt(name).isEmpty && specialFormNames(name) =>
      val written = Some(rt(typeArgWritten(targ, atCall = true)))

      name match
        case "va_arg"   => vaArg(args, written)
        case "ptr_cast" => ptrCast(args, written)
        case _          => err(s"'$name' takes no type arguments")

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
    // The same selection with nothing called — `Maybe[int].Nothing`, a variant that carries no
    // payload. It reaches `fieldExpr` rather than any call form, so it needs the case said again
    // here; without it the reader gets the same `undefined name` about a type that is declared.
    case Field(Index(Ident(written), targ), sel) if genericTypeName(written) =>
      typeArgsAtSelection(written, List(targ), sel, Nil)

    case Field(TypeArgs(Ident(written), targs), sel) if genericTypeName(written) =>
      typeArgsAtSelection(written, targs, sel, Nil)

    case e: Field    => fieldExpr(e, expected)
    case e: TypeAttr => typeAttrExpr(e)

    // `.red` — the same selection with the type's own name left off, taken from what the context
    // expects (`reference/expressions.md § Implicit member`). The call form is up with the other
    // call arms, since a call is matched before its callee is looked at.
    case ImplicitMember(f) => implicitMember(f, expected)

    // `base with { bg = ACCENT }` — the value again with some fields changed, which is a member
    // form because every rule it obeys is an assignment's. `MemberExprAnalysis`.
    case w: WithExpr => withExpr(w, expected)
    // Building a sequence and reaching into one, which share the question of how many elements
    // there are and whether this index is one of them. `CollectionExprAnalysis`.
    case e @ (_: ArrayLit | _: ArrayFill | _: Index) => sequenceExpr(e, expected)
    // Control flow that yields a value (`00 §10`), and the forms that carry several at once.
    // `ControlFlowExprAnalysis`.
    case e @ (_: IfExpr | _: MatchExpr | _: While | _: DoWhile | _: Loop | _: CFor | _: For |
        _: ConstFor | _: Quantifier | _: TryExpr | _: RangeExpr | _: ResultList | _: Lambda |
        _: Tuple | _: Block) =>
      controlExpr(e, expected, discarded)

    // Reached only where an `is` was written somewhere a condition's terms are not read one by one:
    // under `||` or `!`, in a `match` guard, in a `require`, on the right of an `=`, as an argument.
    // The rule is about the binding rather than the test — a `bool` would be harmless, but a name
    // bound where the reader cannot see which paths reach it is not (`09 §12`).
    case _: IsPattern =>
      err("'is' tests a pattern in the condition of an 'if' or a 'while', and nowhere else — its " +
        "binding is live from here to the end of the condition and through the branch that " +
        "condition guards, and there is no such branch here. Chain it with '&&', or write 'match'")



  /** Whether a written name is a **generic** nominal type — a struct's or an enum's — and is not
   * standing behind something nearer.
   *
   * The shadowing test is the one every call form makes: a local holding a value of that name is an
   * ordinary subscript, and its author never wrote a type argument to be told about.
   */
  private def genericTypeName(written: String): Boolean =
    lookupOpt(written).isEmpty && typeKey(written).exists(k => nominalTparams(k).nonEmpty)

  /** Whether `recv.mname` names a **declared method** of the receiver's own type, which is what
   * decides that a bracket after it is a type-argument list rather than a subscript.
   *
   * It is the strictest of the four guards, and it has to be: a field may hold an array of callables,
   * so `x.handlers[i](…)` is a reading the language already gives and this must not take. Asking the
   * receiver settles it — a field is not a member — where asking whether *any* type in the program
   * declares a generic member of that name, which is what the refusal this replaces did, would have
   * answered yes for a field whose name some unrelated type happened to share.
   *
   * A receiver reached through a bound, a trait object or a weak reference is left out: each has a
   * dispatch of its own that never sees a written list, so a guard that admitted one would accept
   * the brackets and silently drop them.
   */
  private def methodWritten(recv: Expr, mname: String): Boolean =
    probe(analyzeExpr(recv)).map(t => receiverType(t.ty)).exists {
      case _: Type.Abstract | _: Type.Trait | _: Type.Weak => false
      case rty =>
        val (base, _) = memberKey(rty, mname)

        memberDecls.get((base, mname)).exists(_.receiver.isDefined)
    }

  /** `Pair[K, V](…)` — a construction whose instantiation is written rather than inferred.
   *
   * It is the annotation's meaning moved to the constructor: the type is resolved from the name and
   * the arguments in the brackets, and the ordinary construction is then asked for exactly that
   * type. So `Pair[int, real](1, 2)` and `var p: Pair[int, real] = Pair(1, 2)` build the same value
   * and refuse the same mistakes, and a literal in the arguments is read at the parameter the
   * written instantiation gave it rather than at its own default.
   *
   * An **enum** reaches here too, since a name applied to arguments is one grammar — and a bare enum
   * name is not a constructor at all, so what it is owed is the sentence about variants rather than
   * a type it cannot build.
   */
  private def constructWritten(written: String, targs: List[Expr], args: List[Expr]): TExpr = {
    val ty = rt(NamedType(written, targs.map(typeArgWritten(_, atCall = true))))

    typeKey(written) match
      case Some(k) if structDecls.contains(k) => constructStruct(written, args, Some(ty))
      case _ =>
        err(s"'$written' is an enum, so it is not built by calling its name — a variant is what " +
          s"carries a value, as '$written[…].Name(…)'")
  }

  /** The same list one step to the left: written on the type something is selected *from*, which is
   * what a reader writes to say which instantiation a **variant** belongs to — `Maybe[int].Just(1)`,
   * and `Maybe[int].Nothing` with nothing called at all.
   *
   * A variant is a construction of the type it belongs to, so the written arguments mean here what
   * they mean at a constructor: the instantiation is fixed and the payload is checked against it.
   *
   * **An associated function is not that**, and keeps the refusal. Its instantiation is solved from
   * the call — the type's parameters and its own arrive in one list and are read together (`10 §4`)
   * — so honouring the brackets would mean settling half of that list and solving the rest, which is
   * a different question from the one this form asks. The annotation on the binding reaches it, and
   * unlike the corner a call head could not reach, it is always there: an associated function has a
   * result, and the result is what its type arguments are read off.
   */
  private def typeArgsAtSelection(
      written: String,
      targs: List[Expr],
      sel: String,
      args: List[Expr],
  ): TExpr = {
    val tname = typeKey(written).get

    if enumDecls.get(tname).exists(_.variants.exists(_.name == sel)) then
      constructVariant(Modules.qualify(Modules.moduleOf(tname), sel), args,
        Some(rt(NamedType(written, targs.map(typeArgWritten(_, atCall = true))))), Some(tname))
    else
      err(s"'$written' cannot be given type arguments where '$sel' is selected from it; write the " +
        s"type on what receives the result — 'var x: $written[…] = …' — and select '$sel' from the " +
        s"plain name")
  }

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
  /** A comparison where either side is a vector: one link, and a mask rather than a `bool`.
   *
   * **A chain is refused rather than lowered.** `a < b < c` on scalars is two comparisons joined by
   * `&&`, and `&&` short-circuits — which is a thing no register does, since every lane is computed
   * either way. Reading the chain as a lane-wise `&` would give it the shape of the scalar spelling
   * and a different meaning, so the reader is told to write the `&` themselves and see it.
   */
  private def vecCompare(ts: List[TExpr], ops: List[String]): TExpr = {
    if ts.length > 2 then
      err("a comparison chain joins its links with '&&', which short-circuits and so has no " +
        "lane-wise form — compare two vectors at a time and combine the masks with '&'")

    val (l, r) = balanceLanes(ts.head, ts(1))
    val op     = ops.head

    if Type.repr(l.ty) != Type.repr(r.ty) then
      err(s"cannot compare ${show(l.ty)} with ${show(r.ty)}")

    val lane = Type.repr(l.ty) match
      case v: Type.Vector => Type.underlying(v.elem)
      case other          => other

    val equality = op == "==" || op == "!="

    // A mask is `<N>bool`, which is what makes `(a < b).select(x, y)` and `(a < b) & (c < d)`
    // ordinary values rather than a comparison's private business. `bool` lanes have equality and
    // no ordering, exactly as a scalar `bool` does.
    lane match
      case _: Type.Integer | _: Type.Floating =>
      case Type.Bool if equality              =>
      case _ => err(s"'$op' is not defined for ${show(l.ty)}")

    val n = Type.repr(l.ty).asInstanceOf[Type.Vector].length

    TVecCompare(op, l, r, Type.Vector(n, Type.Bool))
  }

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

      // `s.bytes` reinterprets the same three words as a `[]const u8` (`04`), so its elements are
      // the string's own storage and assigning to one is the line above by another route — with a
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
      // and so is `memchr`, which is what `sysl.io`'s own `find_byte` is. Refusing it would leave
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

    // A live `ref` stands on storage, and an assignment that would release it leaves the name aimed
    // at freed memory (`03 § ref`). Only a write is asked about: `&` produces a pointer and takes
    // nothing away, so the storage a ref found is exactly where it was.
    if writes then checkRefGuards(t)

    t
  }


  /** Refuses a setter that writes the property it is defining, which calls itself.
   *
   * The mirror of the read `MemberExprAnalysis` refuses, and it is asked of the receiver as the
   * program **wrote** it rather than of the analyzed one: a compound form is rewritten into a
   * temporary holding the receiver's address before anything is analyzed, so `self` has to be
   * recognized while it is still there.
   *
   * Reading `self.count` inside `set count` is left alone, and deliberately: that calls the
   * *getter*, which is a different member and terminates. Only the write comes back here.
   */
  protected def checkNotOwnSetter(receiver: Expr, name: String): Unit =
    receiver match
      case Ident("self") | Unary("*", Ident("self")) if enclosingMember == DeclParser.setterName(name) =>
        err(s"'$name' writes the property it is defining, so it calls itself — a setter is what " +
          "writing the property means, and there is nothing further in for it to reach. What a body " +
          "like this means to write is the field it is in front of")
      case _ =>

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
