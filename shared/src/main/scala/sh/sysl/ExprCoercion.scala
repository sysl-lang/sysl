package sh.sysl

/** What the context asked for, and what makes a value fit it.
 *
 * This sits *in front of* the expression dispatch rather than beside it. `analyzeExpr` funnels every
 * expression through `analyzeExpected` first, which reads the expected type and decides what to ask
 * the expression for; whatever comes back is then handed to `coerce`, which decides what has to be
 * built around it to make it that type — a box, a downgrade, a view, a splat.
 *
 * **The two are one layer and not two**, which is why they are in one file: every arm of
 * `analyzeExpected` exists because pushing the expectation down produces a better value than
 * converting the result afterwards would, and the arms that do *not* push it down are the ones
 * `coerce` can finish on its own. Reading either alone gives half a rule.
 *
 * Conversion written by the programmer — `u8(x)`, a cast into a constrained type — is here too,
 * because it is the same question asked explicitly: the arms that answer it are the ones a call in
 * type position lands on, and they share `checkInto` and `convert` with the implicit path.
 */
trait ExprCoercion extends ExprSupport {

  protected def analyzeExpected(expr: Expr, expected: Option[Type]): TExpr = expected match
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
        // A callable is the other exception, and for the opposite reason to `null`'s: it has no
        // type of its own to be analyzed at and then erased, since what it takes is exactly what
        // the object's arguments say (`reference/expressions.md § Closures`). So the object is
        // pushed down, and the erasure that follows boxes the struct it became. This covers a
        // **named function** as well as a literal: §5 makes the two one thing — a declared function
        // used where a callable is wanted is the capture-free closure — and asking a name that
        // stands for a declaration to produce a value with no context is asking for the one thing
        // it cannot do.
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
   * (`reference/memory.md § Reinterpreting storage`).
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
  protected def named(e: Expr): Boolean = e match
    case _: Ident | _: Field | _: Index | Unary("*", _) => true
    case _                                              => false

  /** Wraps a base-typed value in the run-time check for a constrained subtype. */
  private def checkInto(v: TExpr, c: Type.Constrained): TExpr = TConstrainedCheck(v, c).setPos(v.pos)


  /** Wraps the write so that every struct the place is written *inside* re-checks its invariant the
   * moment the field changes. The wraps nest innermost-first, so the smallest struct broken is the
   * one whose diagnostic fires. `invCheckFor` — the walk that finds them — is in `Aliasing`, beside
   * the rule about which aliases could put a struct out of that walk's reach.
   */
  protected def withInvCheck(place: TExpr, store: TExpr): TExpr =
    invCheckFor(place).foldLeft(store)((acc, c) => TRecheck(acc, c._1, c._2, c._3).setPos(store.pos))

  /** `Name(value)` — an explicit cast into a constrained subtype. The operand is taken at the
   * subtype's base and checked; a value whose base does not agree is a mistake the message names.
   */
  protected def constrainedCast(key: String, args: List[Expr]): TExpr =
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
  protected def convertAt(ty: Type, written: String, args: List[Expr]): TExpr = ty match
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

    // A **transparent** subtype is its base (`reference/errors.md § new is what makes it a type`),
    // so its name converts exactly as the base's name does: `Age(n)` on an `int` base is the
    // `int(n)` a reader would otherwise write, and the range is then checked on the way in. Without
    // this the only way into one is to arrive already at the base, which is unwriteable for the
    // case the feature exists for — a `c type` measures a width nobody can name, so `Tick(xs.len)`
    // has no longhand a program could portably fall back on. A **derived** type keeps the stricter
    // rule: `new` is what makes it a distinct type, and a conversion into one is a wrap of a value
    // already at the base rather than a scalar conversion.
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
    // nothing about the one thing a reader wants to know: why the two do not convert
    // (`reference/memory.md § Crossing a concurrency domain`).
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
}
