package sh.sysl

/** Calling a method, which is four different resolutions wearing one syntax.
 *
 * `x.m(a)` reaches its definition by a different route depending on what `x` is: a concrete type
 * looks the member up under its mangled name, a bounded type parameter finds it in one of its
 * bounds, a trait object reads it out of the table it carries, and a builtin — `str`, `hash`,
 * a weak reference's `get` — has no declaration at all and is recognised here. An overload set adds
 * a fifth step in front of the other four, since which member is meant depends on the arguments.
 *
 * They are one file because the *choice between them* is the interesting part and it is made in one
 * place. Once made, each route ends in the same free call `CallCore` already knows how to check —
 * the receiver passed in the mode its `self` sigil declared, and nothing else method-specific.
 */
trait MethodCalls extends FuncAddress {

  /** `value.method(args)` — resolves `method` as an inherent member of the receiver's type and
   * calls the function it lowered to, passing the receiver as the first argument in whatever
   * memory mode the method's `self` asked for.
   *
   * The receiver may be of *any* type: every type has an owner key its members are filed under, so
   * `5.show()` takes this path exactly as `p.show()` does.
   *
   * A method with type parameters **of its own** is the one shape the receiver does not settle, and
   * `callGenericMethod` is where the rest of the answer comes from.
   */
  protected def callMethod(recv: Expr, mname: String, args: List[Expr], expected: Option[Type]): TExpr =
    callMethodOn(analyzeExpr(recv), mname, args, expected, receiverBound(recv))

  /** The traits the receiver's own **bound** promised, read off the expression as written.
   *
   * This is the one thing the analyzed receiver cannot be asked for. `x` of a `[T: Zero]` body has
   * become an `int` by the time an instantiation looks at it, so the question has to be put to the
   * source — where the name is still `x`, and `pbounds` still knows `x` was written as a `T`.
   */
  protected def receiverBound(recv: Expr): Set[String] = recv match
    case Ident(n) => pbounds.get(n).map(boundTraits).getOrElse(Set.empty)
    case _        => Set.empty

  /** The same, for a receiver already analyzed — what a form that had to look at the receiver's type
   * to know it was a method call uses, so the receiver is analyzed once and the analysis that
   * decided is the one that runs.
   */
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
  private def vectorMethod(v: Type.Vector, tr: TExpr, mname: String, args: List[Expr]): TExpr = {
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

  /** `xs.load(i)` and `xs.store(i, v)`, checked and lowered.
   *
   * **The two are asymmetric in where the width comes from, and deliberately.** A store is told by
   * the vector handed to it. A load has nothing to be told by — a slice has whatever length it has
   * — so the width is the *expected* type's, exactly as `buf()`'s element type is. That is what
   * makes the load writable from a `[const W: usize]` body, where the annotation is `<W>f32` and no
   * literal could stand in for the parameter.
   */
  private def vectorMemory(
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
        case i: Type.Integer => checkedIndexWidth(ti, i)
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

  protected def callMethodOn(
      tr: TExpr,
      mname: String,
      args: List[Expr],
      expected: Option[Type],
      via: Set[String] = Set.empty,
  ): TExpr = {
    receiverType(tr.ty) match
      case a: Type.Abstract => callBoundMethod(a, tr, mname, args)
      case t: Type.Trait    => callTraitObject(tr, t, mname, args)
      case w: Type.Weak     => weakGet(w, tr, mname, args)

      // `s.copy()` is the operation that stops a substring holding its parent buffer alive (`04`):
      // the bytes are copied into a string that owns them, so what the result keeps is its own.
      // Parentheses because it allocates and walks the bytes — `08 § Property or method` puts the
      // O(1) projections on the other side of that line, and this is the case the line was drawn for.
      case Type.Str if mname == "copy" =>
        if args.nonEmpty then err("'copy' takes no arguments — it copies the string it is read off")
        TFromBytes(TBytes(tr))

      // The vector's own methods, which are the compiler's for the reason `Intrinsics`' table is
      // closed: each is one LLVM operation and none of them can be written in sysl. `select` is an
      // instruction, and the reductions are intrinsics overloaded on the register's whole type — so
      // an `impl` block would have nothing to put in its bodies.
      case v: Type.Vector if vectorMethods(mname) => vectorMethod(v, tr, mname, args)

      // The two that move lanes between a register and memory. They are the slice's rather than the
      // vector's because the receiver is what supplies the address and the length — a vector has
      // neither, which is the whole reason these had to exist.
      case rty @ (_: Type.Array | _: Type.Slice) if vectorMemoryMethods(mname) =>
        vectorMemory(rty, autoDeref(tr), mname, args, expected)

      // A `*T` is a bare address, and `03`'s unchecked tier is where a program that has one already
      // is. What it does not have is a length, so there is nothing for a run to be checked against
      // — and a load that silently skipped the check would be the one vector operation that could
      // read past the end of an object while every other way of reaching those elements cannot.
      case _ if vectorMemoryMethods(mname) && Type.repr(tr.ty).isInstanceOf[Type.Ptr] =>
        err(s"a '${show(tr.ty)}' carries no length, so a run of its elements has nothing to be " +
          "checked against — take a slice of them first, with 'p[0..<n]'")

      // A string is a view of bytes that are valid UTF-8 (`04`), and a run of them is bytes rather
      // than lanes of anything the string promises — so the answer is where a program says it wants
      // the bytes, not a second spelling that quietly means the same.
      case Type.Str if vectorMemoryMethods(mname) =>
        err(if mname == "store" then
          "a string is immutable, so there is nothing to write lanes into"
        else
          "a string is a view of UTF-8 rather than of lanes — 's.bytes.load(i)' says that a run of " +
            "bytes is what is wanted, and is what a '<N>u8' is a run of")

      case rty =>
        val (base, _) = memberKey(rty, mname)
        val chosen    = pickOverload(rty, base, mname, args, via)
        val targs     = memberKey(rty, chosen)._2

        memberDecls.get((base, chosen)) match
          case Some(m) if m.receiver.isDefined && m.tparams.nonEmpty =>
            checkMemberVisible(base, chosen, m)
            callGenericMethod(genericMembers((base, chosen)), m, targs, tr,
              bindArgs(s"method '$base.$chosen'", Some(base), m.params, args, m.variadic), expected)
          case Some(m) if m.receiver.isDefined =>
            checkMemberVisible(base, chosen, m)
            val fname = memberFuncName(rty, chosen)
            // **A member whose own signature did not resolve is registered and has no lowered
            // form**, which is `MemberLowering`'s deliberate order — the declaration is filed
            // before the signature is built, so that a mistake in the signature does not also erase
            // the member it is about. What that leaves is a call whose callee has nothing to be
            // checked against, and the mistake has already been reported at the declaration where
            // it belongs. So the call is abandoned in silence rather than complained about twice —
            // and rather than, as it did until this line, taking the compiler down with a missing
            // key. Which of the two happened used to depend on declaration order alone: a caller
            // written *above* its callee reached here before the signature failed to be recorded.
            val (params, rtype) = funcInsts.getOrElse(fname, poisoned())
            // A closure's `call` is not a method a program wrote, so a complaint about one names
            // the callable and the argument's position rather than the member behind it (`12 §6`).
            val callable = mname == "call" && callableOf(rty).isDefined
            // Named by the **receiver**, not by the symbol the member is emitted under. The two
            // agree for a type with a name of its own and part company for everything reached
            // through a shape or a blanket — `bound.Integer.display.int` is a fine symbol and an
            // answer to a question nobody asked, where `int.display` is what the call site wrote.
            val shown = if callable then "this callable" else s"method '${show(rty)}.$chosen'"
            // The receiver is not among the parameters a call writes arguments for, so it is not
            // among the ones a name may reach either — `m.params` starts where the arguments do.
            val bound = bindArgs(shown, Some(base), m.params, args, m.variadic)

            checkArity(shown, params.length - 1, m.variadic, bound.length)
            // A member's tail begins where its declared parameters stop, and the receiver holds one
            // of the slots — so the cut is one past what a free function's would be.
            val (declared, tail) = bound.splitAt(params.length - 1)
            val recvArg  = buildReceiver(m.receiver.get, tr, mname)
            val restArgs = declared.zip(params.tail).map { case (a, (_, pty)) => analyzeExpr(a, Some(pty)) }
            funcsUsed += fname
            recheckAfter(recvArg,
              TCall(fname, checkArgs(if callable then shown else fname, params, declared,
                                     Some(recvArg :: restArgs), callable) ::: tail.map(variadicArg(_)), rtype))
          // Neither of the two remaining kinds takes a receiver, and they are not the same mistake:
          // a property is this call with the parentheses dropped, an associated function is not
          // reached through a value at all.
          case Some(m) if m.isProperty =>
            err(s"'$mname' is a property of '$base' — read it as 'value.$mname', without '()'")
          case Some(_) => err(s"'$mname' is an associated function of '$base' — call it with '$base.$mname(…)'")
          case None =>
            builtinMethod(rty, mname, tr, args)
              .orElse(builtinNumeric(rty, mname, tr, args))
              .orElse(builtinHash(rty, mname, tr, args))
              .orElse(callableField(rty, mname, tr, args, expected))
              .getOrElse {
                // A call reaches through one level of indirection and only one, exactly as selection
                // does (`08 § Calling a method`). A receiver still carrying a mode after that has
                // more than the shorthand walks, and the complaint below would name what is *left*
                // — a type the reader never wrote — and report a missing method, when the method is
                // there and what stopped short is the reach.
                rty match
                  case _: Type.Ptr | _: Type.Ref =>
                    err(s"a method call reaches through one level of indirection and ${show(tr.ty)} " +
                      s"has more, so the rest is written: '(*x).$mname(…)' calls '$mname' on the " +
                      s"${show(rty)} it leaves")
                  // A member the compiler provides, written with parentheses it does not take. A
                  // property a program *declares* has said so since properties existed; a provided
                  // one fell through to the line below and denied the member outright — the one
                  // answer that is not true of it, since `len` is exactly what a slice has. Anything
                  // reaching here failed to resolve as a call, so the provided members that really
                  // are called — `copy`, and a weak reference's `get` — are already gone above.
                  case _ if builtinMember(rty, mname) =>
                    err(s"'$mname' is a property the compiler provides for '$base' — read it as " +
                      s"'value.$mname', without '()'")
                  case _ => err(s"type '$base' has no method '$mname'")
              }
  }

  /** Which of a type's members a written name means, where more than one implementation of one trait
   * gave the type a member of that name.
   *
   * A name that reaches one member — everything a program writes until a type implements a trait
   * twice — comes straight back, so the ordinary call is the lookup it always was. Where there are
   * several, the arguments decide: each candidate's parameters are compared against the types the
   * arguments already have, and exactly one candidate accepting them is the answer.
   *
   * This is **not** overloading, and the difference is that the answer is determined rather than
   * chosen. A trait's argument list is what tells its implementations apart, and a call carries the
   * argument list in the values it passes — so a call that does not determine one is a call whose
   * arguments name no implementation, which is reported rather than resolved by a preference rule.
   * The arguments are analyzed here with nothing expected of them, and a literal has no type of its
   * own to be matched by, so `c.mul(2)` where the candidates take a `Complex` and a `real` is one of
   * the calls that determines nothing (`08 § One name, one member`).
   */
  protected def pickOverload(
      owner: String,
      mname: String,
      args: List[Expr],
      subject: String,
      via: Set[String] = Set.empty,
  )(
      params: String => Option[List[Type]],
  ): String =
    memberAlts.get((owner, mname)) match
      // One member of the name is still a member of *some* trait, and the rule is about the trait
      // rather than about there being a choice: a name a file cannot reach is not answered by there
      // being nothing else it might have meant.
      case None =>
        if reachable(owner, mname, via) then mname else outOfScope(owner, mname, List(mname), subject)
      case Some(everything) =>
        // **A bound answers ahead of everything else.** Inside a generic body `T.zero()` means the
        // `zero` the bound promised, and at an instantiation the parameter has become an ordinary
        // type whose table may hold several — so the traits the parameter was bounded by are what
        // the candidates are narrowed to first. Without this a body is refused for an ambiguity its
        // own signature already settled, and which the caller has no way to speak to.
        val all =
          if via.isEmpty then everything
          else
            val named = everything.filter(c => memberTrait.get((owner, c)).exists(via))

            if named.isEmpty then everything else named

        // **Scope decides before the arguments do**, because the two axes answer different
        // questions and only this one can answer its own. Two implementations of one trait differ
        // in their argument lists and are told apart by a call's values; two *different traits*
        // may declare the same name with the same parameters — `zero()` and `zero()` — and nothing
        // in the call could ever tell those apart. What tells them apart is that a trait's member
        // is reachable only where the trait can be named (`13 §2`), so a file reaching one of them
        // has said which by what it imported.
        val cands = all.filter(reachable(owner, _, via))

        if cands.isEmpty then outOfScope(owner, mname, all, subject)

        // One survivor is the whole answer, and it is the ordinary case once two libraries have each
        // implemented something for a built-in: the arguments are never consulted, which is what
        // makes a nullary `zero()` resolvable at all.
        if cands.length == 1 then return cands.head

        val supplied = args.map(probeType)
        val from = s"'$mname' comes from ${quantity(cands.length, "implementation")} of one trait on $subject"

        // Which candidate a call means is read off the arguments it wrote, in the order it wrote
        // them — so a name, which is exactly a refusal to say what an argument's position is, has
        // nothing here to choose between them. Said outright, because the alternative is the
        // "none of them takes (?, ?)" below, which points at the wrong thing.
        for n <- args.collectFirst { case n: NamedArg => n } do
          at(n.pos)(err(s"$from, and which is meant is read off the arguments as written — so an " +
            "argument given by name leaves nothing to tell them apart. Write them in declared order"))

        // A candidate that takes a `...` is answered for by its declared parameters alone: the tail
        // stands at none, so what it may be told apart by stops where they do (`12 §9`). A default
        // widens the same count downwards, and for the same reason — a call may stop where the
        // defaults begin, so what is compared is the prefix the two lists share.
        val fits = cands.filter { c =>
          params(c).exists { ps =>
            val counted =
              if variadicMember(owner, c) then supplied.length >= ps.length
              else supplied.length <= ps.length && supplied.length >= leastMember(owner, c, ps.length)

            counted && ps.zip(supplied).forall((p, s) => s.contains(p))
          }
        }

        if fits.length == 1 then return fits.head

        // Nothing has settled it. Which complaint that is depends on what is left standing: several
        // **traits** is a use that reached more than one of them, and no argument could have told
        // those apart — a `zero()` and a `zero()` take the same nothing. Several implementations of
        // one trait is the older situation, where the arguments were the question and did not
        // answer it. The set to describe is what the arguments left, or all of them where they left
        // none, since that is the set the reader has to choose from either way.
        val left   = if fits.isEmpty then cands else fits
        val traits = left.flatMap(c => memberTrait.get((owner, c))).distinct

        if traits.length > 1 then
          err(s"'$mname' on $subject comes from ${conjoin(traits.map(qn))}, and each is in scope " +
            "here — nothing in the call says which was meant")

        if fits.isEmpty then
          err(s"$from, and none of them takes (${supplied.map(_.fold("?")(show)).mkString(", ")}) — " +
            "write the argument at the type of the implementation that was meant")

        err(s"$from, and the arguments do not say which was meant")

  /** Whether a use site here can reach this member at all (`13 §2`).
   *
   * Three ways it can, and the first is why the table records provenance at all. A member the type's
   * **own** body declared has no trait to be gated by and is reachable wherever the type is. One an
   * `impl` brought is reachable where its trait can be **named** — and also wherever a **bound**
   * asked for that trait, since a signature naming a trait has said so at least as plainly as an
   * import would.
   */
  protected def reachable(owner: String, cand: String, via: Set[String]): Boolean =
    memberTrait.get((owner, cand)).forall(tr => via(tr) || traitInScope(tr))

  /** What to say where a type has the member under every spelling and the file may reach none of
   * them — which is the whole of what an import would have changed, so the message is the import.
   */
  protected def outOfScope(owner: String, mname: String, cands: List[String], subject: String): Nothing = {
    val traits = cands.flatMap(c => memberTrait.get((owner, c))).distinct.map(qn)

    err(s"$subject has '$mname' from ${conjoin(traits)}, and " +
      (if traits.length == 1 then s"that trait is not in scope here — import it to reach the member"
       else "none of those traits is in scope here — import the one that was meant"))
  }

  /** Whether one of a type's members takes a `...`, asked of the member table rather than of the
   * lowered signature — a tail is not something a parameter list records.
   */
  private def variadicMember(owner: String, mname: String): Boolean =
    memberDecls.get((owner, mname)).exists(_.variadic)

  /** How few arguments one of a type's members may be called with — its parameters, less the ones a
   * default stands in for. Asked of the member table for the reason above: a default is not
   * something a lowered signature records, and `declared` is what to fall back on where the member
   * itself cannot be found.
   */
  private def leastMember(owner: String, mname: String, declared: Int): Int =
    memberDecls.get((owner, mname)).fold(declared)(m => m.params.count(_.default.isEmpty))

  /** `value.m(…)`, where the receiver's type names each candidate's instantiation. */
  protected def pickOverload(
      rty: Type,
      base: String,
      mname: String,
      args: List[Expr],
      via: Set[String],
  ): String =
    pickOverload(base, mname, args, show(rty), via)(c =>
      probe(funcInsts(memberFuncName(rty, c))._1.tail.map(_._2)))

  /** `Type.f(…)` — an associated function, which has no receiver to drop off the front. */
  protected def pickAssociated(tname: String, mname: String, args: List[Expr], via: Set[String] = Set.empty): String =
    pickOverload(tname, mname, args, qn(tname), via)(c => funcInsts.get(s"$tname.$c").map(_._1.map(_._2)))

  /** The type an argument already has, with nothing expected of it and nothing said about whatever
   * goes wrong — the ordinary analysis that follows reports that, in the place it belongs.
   */
  private def probeType(e: Expr): Option[Type] = probe(analyzeExpr(e).ty)

  /** `value.m(…)` where `m` declares type parameters of its own, at the instantiation this call
   * resolves to.
   *
   * The two lists of parameters the lowered function carries are fixed from two different places,
   * which is the whole of what makes this its own path. The **type's** are already settled — the
   * receiver is a type, and a type has its arguments — so they are read off it and held to nothing
   * further here, having been checked where the receiver's type was made. The **member's own** are
   * solved from the arguments and from the type the context expects, exactly as a generic free
   * function's are, and held to the bounds the member wrote, reported in the member's name.
   *
   * Appending the solved arguments to the receiver's is what `synthesize` laid the two lists out in
   * that order for, so the substitution instantiating the function needs to know nothing about
   * which parameter came from where.
   */
  private def callGenericMethod(
      fd: FuncDecl,
      m: MethodDecl,
      ownerArgs: List[Type],
      recv: TExpr,
      args: List[Expr],
      expected: Option[Type],
  ): TExpr = {
    val shown = qn(fd.name)

    checkArity(s"method '$shown'", fd.params.length - 1, m.variadic, args.length)

    // The tail stands at no parameter, so it is kept out of the inference as well as out of the
    // check: what solves the member's own type parameters is the arguments that have one to solve.
    val (passed, tail) = args.splitAt(fd.params.length - 1)
    val spell       = genericSelf.get(fd.name).fold((r: TypeRef) => r)((ref, _) => spellSelf(_, ref))
    val ptypes      = fd.params.tail.map(p => spell(p.typ))
    val provisional = provisionalArgs(fd.name, fd.tparams, ptypes, passed, m.bounds)
    val own = inDecl(fd.name)(solve(
      shown,
      m.tparams,
      ptypes,
      provisional.map(_.ty),
      fd.retType.map(spell),
      expected,
      passed.map(isLiteral),
    ))

    inDecl(fd.name)(checkParamBounds(shown, m.tparams, fd.bounds, own))

    val name            = instantiateFunc(fd, ownerArgs ::: own)
    val (params, rtype) = funcInsts(name)
    val recvArg         = buildReceiver(m.receiver.get, recv, m.name)

    recheckAfter(recvArg,
      TCall(name, checkArgs(shown, params, passed, Some(recvArg :: provisional)) ::: tail.map(variadicArg(_)), rtype))
  }

  /** `k.hash()` — the mixing a built-in's `Hash` membership provides (`14 §5`).
   *
   * `5.add(3)`'s sibling, and built the same way for the same reason: a built-in has no
   * `impl` block, so the lowering is a library function named here. Where `Display` writes into a
   * sink this returns a number, so what the widening is for is the law rather than the signature —
   * every integer, `char`, and `bool` reaches one mixer at 64 bits, so two values that compare
   * equal across widths hash equal too.
   */
  private def builtinHash(rty: Type, mname: String, recv: TExpr, args: List[Expr]): Option[TExpr] =
    for
      _           <- Option.when(mname == "hash")(traitDecls.get(Library.key("Hash"))).flatten
      (fname, to) <- CoreTraits.hash(rty)
    yield {
      val key = Library.key(fname)

      if args.nonEmpty then
        err(s"method 'Hash.hash' takes no arguments, but ${supplied(args.length, "argument")}")

      val self = widen(buildReceiver(RecvMode.ByValue, recv), to)

      funcsUsed += key
      TCall(key, List(self), Type.Integer(64, signed = false))
    }

  /** A member of a trait the compiler supplies membership for, but which is not an operator —
   * `n.abs()`, `n.count_ones()`, `n.rotate_left(3)` (`14 §5`, `CoreTraits.numeric`).
   *
   * **Gated on the trait being in scope**, which is what keeps a compiler-provided membership from
   * being a way around `13 §2`. The two questions are different and both have to be answered: a
   * membership settles which *types* have the member, and scope settles which *files* may write it.
   * `Add` and `Display` are unaffected because they are in the standard module, which every file
   * auto-imports; `Signed` and `Bits` are in `sysl.math`, so a program asks for one exactly as it
   * asks for `Float`.
   *
   * **Every signature is read off the trait's own declaration**, the way `builtinMethod` reads
   * `Add`'s, rather than being restated here. That is what makes `rotate_left`'s amount, and the
   * `u32` a count answers, facts stated once — in the library source a reader can see — instead of
   * twice in places that could disagree. `Self` binds to the receiver's **underlying** type, so the
   * magnitude of a constrained subtype is an ordinary integer: a range is a promise about the values
   * that were put in, and nothing says a range that holds `-128` also holds `128`.
   */
  private def builtinNumeric(rty: Type, mname: String, recv: TExpr, args: List[Expr]): Option[TExpr] =
    for
      trName <- CoreTraits.numeric.get(mname)
      if CoreTraits.builtin(trName, rty)
      key = Library.key(trName)
      if traitInScope(key)
      decl <- traitDecls.get(key)
      m    <- decl.methods.find(_.name == mname)
      // Every one of these answers something, and the answer is where the result type comes from. A
      // declaration that stated none would leave nothing to build the node's type out of, so it is
      // matched here rather than assumed: the member then simply does not resolve, which reports
      // the ordinary missing-method complaint instead of failing inside the compiler.
      declared <- m.retType
    yield {
      val width = Type.underlying(rty)
      val self  = selfBinding(width)
      val (params, ret) = inDecl(decl.name) {
        (m.params.map(p => (p.name, resolveType(p.typ, self))), resolveType(declared, self))
      }

      val bound = bindArgs(s"method '$trName.$mname'", Some(decl.name), m.params, args)

      if bound.length != params.length then
        err(s"method '$trName.$mname' takes ${quantity(params.length, "argument")}, " +
          s"but ${supplied(bound.length, "argument")}")

      val recvd = buildReceiver(RecvMode.ByValue, recv)
      val ts    = checkArgs(s"$trName.$mname", params, bound, None)

      TIntOp(mname, recvd, ts.headOption, width, ret)
    }

  /** `w.get()` — the one thing a `weak T` can be asked (`03`).
   *
   * It yields an ordinary `Option[&T]`, so everything downstream of it — matching, `unwrap`, `?` —
   * is the surface `Option` already has, and nothing about a weak reference reaches further into
   * the language than this call.
   */
  private def weakGet(w: Type.Weak, recv: TExpr, mname: String, args: List[Expr]): TExpr = {
    if mname != "get" then
      err(s"a ${show(w)} has no method '$mname' — a weak reference may be gone, so 'get()' is the " +
        s"only thing to ask one, and what it hands back is what has methods")
    if args.nonEmpty then err(s"'get' takes no arguments")

    val optTy = instantiateEnum(Library.key("Option"), List(w.strong))
    TUpgrade(recv, optTy, optTy.variant("Some").get, optTy.variant("None").get)
  }

  /** `5.add(3)`, `x.lt(y)` — a core-trait method on a type whose membership the compiler provides.
   *
   * A built-in has no `impl` block and so no lowered `int.add` to call; what it has is the operator
   * the trait method *means*, and that is what this builds. The result is the same tree the operator
   * itself would have produced, which is `14 §5`'s promise that a membership changes no codegen —
   * and it is what a monomorphized `[T: Add]` body lands on once `T` is known to be a scalar.
   */
  private def builtinMethod(rty: Type, mname: String, recv: TExpr, args: List[Expr]): Option[TExpr] =
    for
      trName <- CoreTraits.declaring(mname)
      if CoreTraits.builtin(trName, rty)
      decl   <- traitDecls.get(Library.key(trName))
      m      <- decl.methods.find(_.name == mname)
    yield {
      // A built-in's membership is homogeneous (`14 §5`), so the trait's own parameter is the
      // receiver's own type: `5.add(3)` is the `Add[int]` an `int` has, and it has no other.
      //
      // The signature is the **trait's**, so it is resolved in the trait's terms however far from it
      // the call was written — the same rule an instantiated function's signature follows. Reading
      // it here would let a program that declares a type the trait's signature names mean its own by
      // the word, and `Display.display`'s specifier is exactly such a name.
      val params = inDecl(decl.name) {
        m.params.map(p => (p.name, resolveType(p.typ, selfBinding(rty) ++ decl.tparams.map(_ -> rty))))
      }

      val bound = bindArgs(s"method '$trName.$mname'", Some(decl.name), m.params, args)

      if bound.length != params.length then
        err(s"method '$trName.$mname' takes ${quantity(params.length, "argument")}, " +
          s"but ${supplied(bound.length, "argument")}")

      val self             = buildReceiver(RecvMode.ByValue, recv)
      val ts               = checkArgs(s"$trName.$mname", params, bound, None)
      val (_, op, kind)    = CoreTraits.required(trName)

      kind match
        case CoreTraits.Kind.Arith   => produced(TBinary(op, self, ts.head, arithType(op, self.ty, ts.head.ty, ts.head.pos)))
        case CoreTraits.Kind.Compare => TCompare(List(self, ts.head), List(TCmp(op)))
        case CoreTraits.Kind.Prefix  => produced(TUnary(op, self, unaryType(self.ty)))
    }

  /** `x.m(…)` where `x` is a type parameter, during the definition-time pass of `14 §4`.
   *
   * The method is looked up in the traits the parameter's bounds name, and the call checked against
   * the **trait's** signature rather than any implementation's. That is what makes one walk stand in
   * for every instantiation: an `impl` conforms to the trait exactly (`Hoisting.checkConformance`),
   * so a call the trait signature accepts is one every implementation accepts.
   *
   * The tree it builds is discarded with the rest of the pass — the name it carries is the trait's,
   * and which implementation runs is monomorphization's to decide once a concrete type is known.
   */
  private def callBoundMethod(a: Type.Abstract, recv: TExpr, mname: String, args: List[Expr]): TExpr =
    boundMember(a, mname) match
      case None => unlicensed(a, mname)
      case Some((tr, self, m)) =>
        reported {
          val fname = s"${tr.name}.$mname"
          if m.isProperty then
            err(s"'$mname' is a property of '${tr.show}' — read it as 'value.$mname', without '()'")
          if m.receiver.isEmpty then
            err(s"'$mname' is an associated function of '${tr.show}', so it has no receiver — a value " +
              "cannot be the thing it is called on")
          val params = m.params.map(p => (p.name, resolveType(p.typ, self)))
          // Checked against the trait's signature, so it is the trait's defaults and the trait's
          // parameter names that a call inside a generic body may reach for — the same ones every
          // instantiation will present, which is what makes one walk stand in for all of them.
          val bound = bindArgs(s"method '$fname'", Some(tr.name), m.params, args, m.variadic)

          checkArity(s"method '$fname'", params.length, m.variadic, bound.length)
          val (declared, tail) = bound.splitAt(params.length)
          val ts    = declared.zip(params).map { case (arg, (_, pty)) => analyzeExpr(arg, Some(pty)) }
          val rtype = m.retType.map(resolveReturn(_, self)).getOrElse(Type.Unit)
          TCall(fname, recv :: (checkArgs(fname, params, declared, Some(ts)) ::: tail.map(variadicArg(_))), rtype)
        }

  /** `T.f(…)` where `T` is a type parameter and `f` is an associated function one of its bounds
   * declares — what `callBoundMethod` is to a value, asked of the type itself.
   *
   * This is the whole of what a bound can say **about the type** rather than about a value of it.
   * A parameter is not a name anything else can be written through, so without it every fact a
   * generic body needs — a width, a zero, a table of constants — has to arrive as a member carrying
   * a receiver nothing reads, which is the workaround `02` describes and this replaces.
   *
   * Checked against the trait's signature and discarded, exactly as a bound method call is: the name
   * it carries is the trait's, and which implementation runs is settled once a concrete type is
   * known. Static dispatch only — a trait declaring one has no object (`checkObjectSafe`), because
   * there is no receiver for a table slot to be selected by.
   */
  protected def callBoundAssociated(a: Type.Abstract, mname: String, args: List[Expr]): TExpr =
    boundMember(a, mname) match
      case None => unlicensedAssociated(a, mname)
      case Some((tr, self, m)) =>
        reported {
          val fname = s"${tr.name}.$mname"
          if m.isProperty then
            err(s"'$mname' is a property of '${tr.show}' — read it on a value, as 'value.$mname'")
          if m.receiver.isDefined then
            err(s"'$mname' is a method of '${tr.show}' — call it on a value of '${a.name}', not on " +
              "the type itself")
          val params = m.params.map(p => (p.name, resolveType(p.typ, self)))
          val bound  = bindArgs(s"associated function '$fname'", Some(tr.name), m.params, args, m.variadic)

          checkArity(s"associated function '$fname'", params.length, m.variadic, bound.length)
          val (declared, tail) = bound.splitAt(params.length)
          val ts    = declared.zip(params).map { case (arg, (_, pty)) => analyzeExpr(arg, Some(pty)) }
          val rtype = m.retType.map(resolveReturn(_, self)).getOrElse(Type.Unit)
          TCall(fname, checkArgs(fname, params, declared, Some(ts)) ::: tail.map(variadicArg(_)), rtype)
        }

  /** The trait a bound reaches an associated function of that name through, where one does — asked
   * by a *read* that has to decide between telling the reader to add the parentheses and reporting
   * whatever else the name turned out to be.
   */
  protected def boundAssociated(a: Type.Abstract, mname: String): Option[String] =
    boundMember(a, mname).filter(_._3.recvMode.isEmpty).map(_._1.show)

  /** The diagnostic for `T.f(…)` that no bound licenses, which is the associated-function half of
   * `unlicensed`: with a trait declaring one of that name the fix is the bound, and naming it is
   * what checking at the definition is for.
   */
  private def unlicensedAssociated(a: Type.Abstract, mname: String): Nothing =
    traitDecls.values
      .filter(_.methods.exists(m => m.name == mname && m.recvMode.isEmpty))
      .map(t => qn(t.name))
      .toList match
      case Nil =>
        boundErr(s"'${a.name}' is a type parameter, and no trait declares an associated function " +
          s"'$mname' that a bound could promise")
      case one :: Nil => boundErr(s"'$mname' needs '${show(a)}: $one'")
      case many =>
        boundErr(s"'$mname' needs a bound on '${a.name}' — it is declared by ${many.mkString("'", "', '", "'")}")

  /** The first member of that name one of a parameter's bounds declares, with the substitution its
   * signature is read under.
   *
   * That substitution is the whole of what a bound's *arguments* buy. `Self` is the parameter
   * itself, which is what makes `Add::add` yield a `T` and `Ord::lt` a `bool` inside a body that has
   * not met a concrete type yet (`14 §4`); the trait's own parameters are the arguments the bound
   * applied it to, so a `T: From[int]` has a `from` that takes an `int` and one bounded by
   * `From[U]` has one that takes whatever `U` turns out to be.
   */
  private def boundMember(a: Type.Abstract, mname: String): Option[(Type.Bound, Map[String, Type], MethodDecl)] =
    a.bounds.iterator
      .flatMap(traitClosure(_, selfBinding(a)))
      .flatMap(b => traitDecls.get(b.name).map((b, _)))
      .flatMap { case (b, decl) =>
        // Padded rather than zipped, because a bound whose own resolution failed is recorded with
        // whatever survived of its arguments — and reading a member against a short list would
        // report the trait's signature as unresolvable, which is a second complaint about the one
        // mistake already being reported.
        decl.methods
          .find(_.name == mname)
          .map(m => (b, selfBinding(a) ++ decl.tparams.zipAll(b.args, "", Type.Unknown).filter(_._1.nonEmpty), m))
      }
      .nextOption()

  /** `x.p` where `x` is a type parameter and `p` is a property one of its bounds declares — the read
   * `callBoundMethod` is to a call, checked the same way and for the same reason.
   *
   * A field would be refused here whatever the bounds said, because a field is layout and no promise
   * about behaviour reaches one (`10 §5`). A property is the opposite case: it is behaviour that
   * happens to be spelled like a field, so a trait can promise it, and a bounded body may read one
   * exactly as it may call a method.
   */
  protected def readBoundProperty(a: Type.Abstract, recv: TExpr, name: String): TExpr =
    boundMember(a, name) match
      case None => unlicensedProperty(a, name)
      case Some((tr, self, m)) =>
        reported {
          if !m.isProperty then
            err(s"'$name' is a method of '${tr.show}' — call it with '$name(…)'")
          val rtype = m.retType.map(resolveReturn(_, self)).getOrElse(Type.Unit)
          TCall(s"${tr.name}.$name", List(recv), rtype)
        }

  /** The diagnostic for a property read that no bound licenses.
   *
   * With no trait declaring one of that name there is nothing a bound could be, and the honest
   * complaint is the older one about a field: a type parameter has no layout to read. Where a trait
   * *does* declare it, the fix is the bound, and naming it is what checking at the definition is for.
   */
  private def unlicensedProperty(a: Type.Abstract, name: String): Nothing =
    traitDecls.values.filter(_.methods.exists(m => m.name == name && m.isProperty)).map(t => qn(t.name)).toList match
      case Nil =>
        boundErr(s"'${a.name}' is a type parameter, so it has no fields to read — a field is layout, " +
          s"and no trait declares a property '$name' that a bound could promise instead")
      case one :: Nil =>
        boundErr(s"'$name' needs '${show(a)}: $one'")
      case many =>
        boundErr(s"'$name' needs a bound on '${a.name}' — it is declared by ${many.mkString("'", "', '", "'")}")

  /** `obj.m(…)` on a `*Trait` or a `&Trait` — the dynamic half of `02`.
   *
   * The signature checked against is the **trait's**, because the implementation is exactly what an
   * erased value no longer says: which function runs is a word read out of the object's table at
   * run time, and what stands in for knowing it is that every `impl` conforms to the trait exactly
   * (`Hoisting.checkConformance`). `Self` is not resolved at all — object safety already refused
   * any trait that mentions it away from the receiver, so no signature reaching here contains one.
   */
  protected def callTraitObject(recv: TExpr, t: Type.Trait, mname: String, args: List[Expr]): TExpr = {
    // A trait offers the members of the traits it requires as well as its own, and both kinds are
    // one slot in one table — so this is the whole list, in the order the table was laid out.
    val members = traitMembers(Type.Bound(t.name, t.args))

    members.zipWithIndex.find(_._1._2.name == mname) match
      case None =>
        err(s"trait '${t.name}' declares no method '$mname' — it has " +
          members.map(_._2.name).mkString("'", "', '", "'"))
      case Some(((_, m), _)) if m.isProperty =>
        err(s"'$mname' is a property of '${t.name}' — read it as 'value.$mname', without '()'")
      case Some(((from, m), slot)) =>
        // A signature is read under the parameters of the trait that *declared* it, at the arguments
        // the object's type fixed for it — an object over `Sink[int]` takes an `int`. `Self` is not
        // in the substitution at all: object safety already refused any trait that mentions one away
        // from its receiver, so no signature reaching here contains one.
        val subst: Map[String, Type] = traitDecls(from.name).tparams.zip(from.args).toMap
        val params                   = m.params.map(p => (p.name, resolveType(p.typ, subst)))
        // A boxed callable's `call` is the same non-member the inlined one's is, and reads the same
        // way in a message — the arity-carrying trait behind it is the compiler's business.
        val callable = Type.Fn.isCall(from.name)
        val fname    = if callable then "this callable" else s"method '${from.name}.$mname'"

        // The **trait's** declaration is what a call through an object names, so the trait's
        // defaults are what fill it — which is why they are filled here, before the slot is
        // reached, and mean the same thing as they do at a call to a known type (`12 §2a`). A
        // boxed callable is the one member here with no names to give: `Fn`'s parameters are the
        // compiler's, so `bindArgs` is not asked and a name written at one is refused as it is at
        // an inlined callable.
        val bound =
          if callable then args else bindArgs(fname, Some(from.name), m.params, args)

        if bound.length != params.length then
          err(s"$fname takes ${quantity(params.length, "argument")}, " +
            s"but ${supplied(bound.length, "argument")}")

        val ts    = bound.zip(params).map { case (a, (_, pty)) => analyzeExpr(a, Some(pty)) }
        val rtype = m.retType.map(resolveReturn(_, subst)).getOrElse(Type.Unit)

        TVCall(recv, slot, checkArgs(fname, params, bound, Some(ts), callable), rtype)
  }

  /** `obj.p` on a `*Trait` or a `&Trait` — a property read through the table, which is the same
   * indirect call a method is with no arguments to pass and no parentheses to write.
   *
   * An object has no fields at all: the layout is exactly what erasure forgot. So every name read off
   * one is either a property the trait declares or a mistake, and the second half of this says which.
   */
  protected def readTraitObjectProperty(recv: TExpr, t: Type.Trait, name: String): TExpr = {
    traitMembers(Type.Bound(t.name, t.args)).zipWithIndex.find(_._1._2.name == name) match
      case Some(((from, m), slot)) if m.isProperty =>
        val subst: Map[String, Type] = traitDecls(from.name).tparams.zip(from.args).toMap

        TVCall(recv, slot, Nil, m.retType.map(resolveReturn(_, subst)).getOrElse(Type.Unit))
      case Some(_) =>
        err(s"'$name' is a method of '${t.name}' — call it with '$name(…)'")
      case None =>
        err(s"a ${show(recv.ty)} has no fields, and trait '${t.name}' declares no '$name'")
  }

  /** The diagnostic for a method no bound licenses. It names the bound that *would* license it,
   * since the fix is to write that bound rather than to stop making the call — which is the whole
   * point of checking at the definition instead of at some caller three files away.
   */
  private def unlicensed(a: Type.Abstract, mname: String): Nothing =
    traitDecls.values.filter(_.methods.exists(_.name == mname)).map(t => qn(t.name)).toList match
      case Nil =>
        boundErr(s"no trait declares a method '$mname', so no bound on '${a.name}' could license this call")
      case one :: Nil =>
        boundErr(s"'$mname' needs '${show(a)}: $one'")
      case many =>
        boundErr(s"'$mname' needs a bound on '${a.name}' — it is declared by ${many.mkString("'", "', '", "'")}")
}
