package sh.sysl

/** The six methods a vector answers to, and the two a run of them in memory does.
 *
 * Split out of `MethodCalls`, whose receiver-driven choice sends a `<N>T` here. It is a group of its
 * own because none of it is a *lookup*: nothing declares `select` or `sum`, so there is no member to
 * find and no overload set to narrow — the name is recognised, the lanes are checked, and one node
 * is built. That is a different shape of work from every other route beside it, and the checking is
 * where the length of this file goes.
 *
 * The reductions divide on what they reduce rather than on their names, and the two memory forms —
 * `load` and `store` — divide on which side of the slice the vector is. Both divisions are written
 * out at the members below.
 */
trait VectorMethods extends FuncAddress {

  /** The names a vector answers to. Kept as a set so that a *different* name on a vector falls
   * through to the ordinary complaint, which names the type and says no member of that name exists
   * — rather than being caught here and answered with a list.
   */
  protected val vectorMethods: Set[String] = Set("select", "sum", "min", "max", "any", "all")

  /** One of the six, checked and lowered.
   *
   * The reductions divide on what they reduce rather than on their names: `sum`, `min` and `max`
   * are arithmetic and belong to a numeric lane, while `any` and `all` are a mask's and belong to a
   * `bool` one. `select` is the only one that is not a reduction at all — it is the lane-wise `if`,
   * so it is a *mask's* method and yields a vector rather than a scalar.
   */
  protected def vectorMethod(v: Type.Vector, tr: TExpr, mname: String, args: List[Expr]): TExpr = {
    val lane   = Type.underlying(v.elem)
    val isMask = lane == Type.Bool

    def noArgs(): Unit =
      if args.nonEmpty then
        err(s"'$mname' takes no arguments — it reduces the ${show(v)} it is read off")

    (mname, lane) match
      case ("select", _) if !isMask =>
        err(s"'select' chooses between two vectors lane by lane, so it is read off a mask — the " +
          s"'<${v.length}>bool' a comparison yields, as in '(a < b).select(a, b)'. ${show(v)} is not one")

      case ("select", _) =>
        if args.length != 2 then
          err(s"'select' takes the two vectors to choose between — 'm.select(whenTrue, whenFalse)', " +
            s"and ${args.length} argument${if args.length == 1 then " was" else "s were"} given")

        // **Either side may be a scalar, and nearly always one of them is** — `(v > hi).select(hi,
        // v)` is what clamping looks like, and requiring a written construction on the constant
        // would be a word in front of the commonest use this method has. So the pair is read the
        // way `analyzeOperands` reads a literal beside a typed neighbour: whichever side turns out
        // to be a vector supplies the lane type, the other is re-read at it, and `balanceLanes`
        // splats. Without the re-read the `4.0` lands as `real` and is refused for being one.
        val first  = analyzeExpr(args.head, None)
        val second = analyzeExpr(args(1), None)

        def isVec(t: TExpr) = Type.repr(t.ty).isInstanceOf[Type.Vector]

        val (a, b) = List(first, second).find(isVec).map(t => Type.repr(t.ty).asInstanceOf[Type.Vector]) match
          case Some(vec) =>
            balanceLanes(
              if isVec(first) then first else analyzeExpr(args.head, Some(vec.elem)),
              if isVec(second) then second else analyzeExpr(args(1), Some(vec.elem)),
            )
          case None => (first, second)

        // Both sides are the same vector, at the mask's width: a mask of four lanes cannot choose
        // between registers of eight, and the answer's type is the type being chosen between.
        (Type.repr(a.ty), Type.repr(b.ty)) match
          case (x: Type.Vector, y: Type.Vector) if x == y && x.length == v.length =>
            TSelect(tr, a, b, a.ty)
          case (x: Type.Vector, y: Type.Vector) if x == y =>
            err(s"a ${show(v)} chooses between ${v.length} lanes, and ${show(a.ty)} has ${x.length}")
          case _ =>
            err(s"'select' chooses between two vectors of one type, and this pair is " +
              s"${show(a.ty)} and ${show(b.ty)}")

      case ("any" | "all", Type.Bool) =>
        noArgs()
        TReduce(if mname == "any" then "or" else "and", tr, Type.Bool)

      case ("any" | "all", _) =>
        err(s"'$mname' asks whether any lane is true, so it is read off a mask — the " +
          s"'<${v.length}>bool' a comparison yields. ${show(v)} is not one")

      case (_, Type.Bool) =>
        err(s"'$mname' adds or orders its lanes, and a mask's are 'bool' — 'any()' and 'all()' " +
          s"are what a ${show(v)} reduces with")

      case ("sum", _: Type.Floating) => noArgs(); TReduce("fadd", tr, v.elem)
      case ("sum", _: Type.Integer)  => noArgs(); TReduce("add", tr, v.elem)

      // The float minimum is `fmin` rather than `fminimum`: it is the one that matches `sysl.math`'s
      // `min`, answering the other operand at a NaN instead of propagating it, so a program does not
      // get two different answers depending on whether it reduced or folded.
      case ("min", _: Type.Floating) => noArgs(); TReduce("fmin", tr, v.elem)
      case ("max", _: Type.Floating) => noArgs(); TReduce("fmax", tr, v.elem)
      case ("min", i: Type.Integer)  => noArgs(); TReduce(if i.signed then "smin" else "umin", tr, v.elem)
      case ("max", i: Type.Integer)  => noArgs(); TReduce(if i.signed then "smax" else "umax", tr, v.elem)

      case _ => err(s"'$mname' is not defined for ${show(v)}")
  }

  /** The two names a slice or an array answers with a vector's lanes.
   *
   * They are the *receiver's* methods and not the vector's, because what a load needs is an address
   * and a length and a vector has neither. That is also why they are here rather than in `library/`:
   * each is one LLVM instruction with the subscript's bounds check in front of it, and no sysl body
   * could write either.
   */
  protected val vectorMemoryMethods: Set[String] = Set("load", "store")

  /** Whether `load` or `store` on this receiver is the compiler's rather than something a program
   * wrote.
   *
   * **A declared member wins, and the gate is what settled that.** These are two ordinary words,
   * not spellings only the compiler could mean — `sysl.sync.Atomic.load` was in the library before
   * either of these existed, and a builtin that claimed the name unconditionally refused a retry
   * loop somebody had already written. So the builtin answers only where nothing else does, which
   * is also the precedence a reader would assume: their `impl` block is theirs.
   */
  protected def claimsLanes(rty: Type, mname: String): Boolean =
    vectorMemoryMethods(mname) && {
      val (base, _) = memberKey(rty, mname)
      !memberDecls.contains((base, mname)) && !memberAlts.contains((base, mname))
    }

  /** A receiver that is a pointer at something a lane could be — the shape somebody reaching for a
   * vector load would have, and nothing else. A `*Atomic[long]` points at a struct and so is not
   * one.
   */
  protected def pointsAtALane(t: Type): Boolean = Type.repr(t) match
    case Type.Ptr(inner) => Type.Vector.lanes(Type.underlying(inner))
    case _               => false

  /** `xs.load(i)` and `xs.store(i, v)`, checked and lowered.
   *
   * **The two are asymmetric in where the width comes from, and deliberately.** A store is told by
   * the vector handed to it. A load has nothing to be told by — a slice has whatever length it has
   * — so the width is the *expected* type's, exactly as `buf()`'s element type is. That is what
   * makes the load writable from a `[const W: usize]` body, where the annotation is `<W>f32` and no
   * literal could stand in for the parameter.
   */
  protected def vectorMemory(
      rty: Type,
      tr: TExpr,
      mname: String,
      args: List[Expr],
      expected: Option[Type],
  ): TExpr = {
    val declared = Type.element(rty).get
    val elem     = Type.unqualified(declared)

    // **A run of `volatile` elements is refused, and this is the one qualifier that matters here.**
    // `volatile` says every access happens exactly as written, and a whole-register load is a
    // different number of accesses from the `W` the elements were declared with — LLVM has no way
    // to promise per-lane ordering out of one instruction. Dropping the qualifier silently is what
    // the naive reading does, which is a program that reads a device once where it said `W` times.
    // `volatile <N>T` is the shape that *can* be honoured, and it is spelled the other way round.
    if declared != elem then
      err(s"a run of '${show(declared)}' is one access per element and a vector reaches memory " +
        "once, so the qualifier cannot be kept — read the elements one at a time, or hold them in " +
        s"a '[]${show(elem)}' where a whole register is what the program means to move")

    def arity(n: Int, form: String): Unit =
      if args.length != n then
        err(s"'$mname' takes $n argument${if n == 1 then "" else "s"} — '$form', and " +
          s"${quantity(args.length, "argument")} ${if args.length == 1 then "was" else "were"} given")

    /** The index, held to what a subscript holds one to — this *is* a subscript, of `W` elements. */
    def runStart(e: Expr): TExpr = {
      val ti = analyzeExpr(e, Some(Type.usize))
      Type.repr(ti.ty) match
        case _: Type.Integer => ti
        case other           => err(s"the index a run starts at must be an integer, not ${show(other)}")
    }

    /** What the elements have to be for a run of them to be a vector at all. The lane rule is the
     * one the type's own syntax is held to, asked here of a type nobody wrote as a vector.
     */
    def lanesOrErr(v: Type.Vector): Unit =
      if Type.repr(v.elem) != Type.repr(elem) then
        err(s"a run of ${show(rty)} is a run of ${show(elem)}, and ${show(v)} holds ${show(v.elem)}")
      else if !Type.Vector.lanes(Type.underlying(elem)) then
        err(s"${show(elem)} cannot be a lane, so a run of ${show(rty)} is not a vector")

    mname match
      case "load" =>
        arity(1, "xs.load(i)")

        expected.map(Type.repr) match
          case Some(v: Type.Vector) =>
            lanesOrErr(v)
            TVecLoad(tr, runStart(args.head), v)

          // Nothing in the expression says how wide the answer is, and guessing a width is the one
          // thing this must not do — a kernel's whole correctness is which run it took. So the
          // complaint names the place the width belongs, which is where the reader would have put
          // it anyway.
          case Some(other) =>
            err(s"'load' answers a vector, and ${show(other)} is what is wanted here")
          case None =>
            err("'load' answers a vector, and how many lanes it takes is the vector type's to say " +
              "— nothing here says which. Write it where the value is bound: " +
              s"'val v: <4>${show(elem)} = xs.load(i)'")

      case _ =>
        arity(2, "xs.store(i, v)")

        // Written before the index is looked at, because the index is the second thing a reader
        // fixes and the first is that this run may not be written at all.
        val probe = TIndex(tr, TIntLit(BigInt(0), Type.usize), elem)

        if Type.readOnlyView(rty) then
          err(s"this run belongs to a '${show(rty)}', which views elements it may not write, so " +
            s"there is nothing to store through. Lanes you may write are elements of your own: " +
            s"copy them into a '[]${show(elem)}' first")
        if !isPlace(probe) then
          err(s"'store' writes into the elements it is read off, and ${show(rty)} has no address " +
            "to write through")
        if readOnly(probe) then
          err("a 'val' is written once, so 'store' has nothing to write through")

        val index = runStart(args.head)
        val value = analyzeExpr(args(1), None)

        Type.repr(value.ty) match
          case v: Type.Vector => lanesOrErr(v); TVecStore(tr, index, value)
          case other =>
            err(s"'store' writes a vector's lanes, and ${show(other)} is not one — " +
              s"'val v: <4>${show(elem)} = …' is what a run of ${show(rty)} is stored from")
  }
}
