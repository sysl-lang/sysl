package io.github.edadma.sysl

/** A function's address, and a call through one — the two halves of `12 §6a`.
 *
 * This is the seam a C library reaches back through. `extern` (`12 §1`) lets sysl call outward, and
 * that was enough while every foreign interface was one a program calls; it is not enough for the
 * ones that call *back*. `qsort` wants a comparison, `signal` wants a handler, `pthread_create`
 * wants a thread body, and every one of them takes it as an address. Symmetrically, `dlsym` hands an
 * address *in*, and until something could be called through one that whole interface was declarable
 * and unusable.
 *
 * Both directions are the same value — one word holding the address of code compiled to the
 * machine's C convention — so both are `Type.CFn`, and neither is a sysl callable. A `Fn` is a trait
 * (`12 §6`): a bound at a parameter, a two-word object where a concrete type is required, and in
 * both cases something with an environment beside it. C has no notion of an environment, which is
 * why this is a separate type rather than a third mode of that one, and it is why nothing here can
 * take the address of a closure.
 */
trait FuncAddress extends CallCore {

  /** `&f` — the address of a declared function.
   *
   * The `&` is the same one `03` gives every other address, and it is deliberate that no bare `f`
   * reaches here: a bare function name already means the capture-free closure (`12 §5`), and a
   * spelling that meant a sysl callable in one slot and a C address in another would be a silent
   * choice between two representations that share nothing.
   */
  protected def functionAddress(written: String, key: String): TExpr = {
    val decl = funcDecls(key)

    // Which copy of it? A generic function is not code until a call names its arguments (`10 §7`),
    // so there is no single body an address could name.
    if decl.tparams.nonEmpty then
      err(s"'$written' is generic, so it is not one function but a copy per set of type arguments, " +
        "and an address names one body — a wrapper that calls it at the arguments wanted is what " +
        "has an address")

    // A `...` is read relative to the last named argument, so a caller has to know where the named
    // ones stop. A `*extern` says only what it is called with, which is what makes it callable at
    // all, and there is nothing in that to hold a tail.
    if decl.variadic then
      err(s"'$written' is variadic, and a '*extern' fixes the arguments a call passes — a tail has " +
        "no width a signature could state, so a variadic function is reached by calling it")

    if decl.test.isDefined then
      err(s"'$written' is a '#test' function, which 'sysl test' calls and nothing else does — every " +
        "other build leaves it out, so its address would be of a definition the program does not have")

    // An intrinsic is a name the back end recognises and lowers, not a function that exists to be
    // pointed at: there is no body anywhere for an address to name, and LLVM refuses a module that
    // takes one. The wrapper the message asks for *is* a real function and does have an address.
    if externDecls.get(key).exists(e => Intrinsics.declared(e.symbol)) then
      err(s"'$written' is an intrinsic, which the back end lowers to an instruction rather than a " +
        "function anything calls — there is no body for an address to name. A sysl function that " +
        "calls it is what has one")

    val (params, ret) = funcInsts(key)
    val ptypes        = params.map(_._2)

    // The address is of *code*, and the code has to be the code C would call. Where every part of
    // the signature crosses as itself the two conventions agree and the function's own symbol is the
    // answer; where one does not, sysl emitted the aggregate its own way and C would read the wrong
    // registers. Refusing is the honest half of that — an adapter is what closes it, and until there
    // is one this says which parameter is the problem rather than handing over an address that
    // silently means something else.
    for (t, i) <- (ptypes :+ ret).zipWithIndex do
      if !crossesAsItself(t) then
        val which = if i < ptypes.length then s"the ${ordinal(i + 1)} parameter" else "the result"

        err(s"$which of '$written' is ${show(t)}, an aggregate, and an aggregate crosses to C in " +
          "whichever registers that machine's convention names rather than the ones a sysl call " +
          "uses — so this address would be of a function C cannot call correctly. A wrapper taking " +
          "the parts behind a '*T' is what has an address")

    funcsUsed += key
    if externDecls.contains(key) then externsUsed += key

    TFuncAddr(key, key, Type.CFn(ptypes, ret))
  }

  /** Whether a type is handed over to C as the thing it is, so a sysl definition of that signature
   * is already one the other side can call.
   *
   * The test is by **shape** rather than by asking `CAbi`, and deliberately: the classification is a
   * property of the target and this runs before one is chosen, so a rule that consulted it would let
   * a program compile for one machine and not another. What every convention agrees about is the
   * boundary drawn here — a value that occupies one register crosses as itself everywhere, and an
   * aggregate is what each of them has its own reading of (`targets.md`).
   */
  private def crossesAsItself(t: Type): Boolean = Type.underlying(t) match
    case _: Type.Integer | _: Type.Floating | Type.Bool | Type.Char => true
    case Type.Unit | Type.Never                                     => true
    case _: Type.CFn                                                => true
    // A trait object is a pair of words on either mode, so it is an aggregate however it is spelled.
    case Type.Weak(inner)                                           => !inner.isInstanceOf[Type.Trait]
    case p @ (_: Type.Ptr | _: Type.Ref)                            => !Type.erased(p)
    // A simple enum is its underlying integer and nothing else; a data enum is a tag beside a union.
    case e: Type.Enum                                               => e.simple
    case _                                                          => false

  /** `p(args)` where `p` holds a `*extern`.
   *
   * There is no declaration to check against — the callee is a value — so what the arguments are
   * held to is the signature the *type* carries. That is the whole of the promise: nothing here can
   * see the function at the other end, and the `*` is where the language says so.
   */
  protected def callThroughAddress(callee: TExpr, args: List[Expr]): TExpr = {
    val Type.CFn(params, ret) = cfnOf(callee.ty).get: @unchecked

    if args.length != params.length then
      at(callee.pos)(err(s"a ${show(callee.ty)} is called with ${quantity(params.length, "argument")}, " +
        s"and ${args.length} were given"))

    TCallPtr(callee, checkArgs(show(callee.ty), positions(params), args, None, positional = true), params, ret)
  }

  /** The parameters of a `*extern`, in the shape `checkArgs` reads. A function pointer's parameters
   * have no names — the type is the only thing that describes them — so a mismatch names the
   * position, which is the same thing a callable's does (`checkArgs`).
   */
  private def positions(params: List[Type]): List[(String, Type)] =
    params.zipWithIndex.map((t, i) => (s"${i + 1}", t))

  /** The C function pointer a type is, seeing through a transparent subtype the way every other
   * question about a representation does.
   */
  protected def cfnOf(t: Type): Option[Type.CFn] = Type.underlying(t) match
    case c: Type.CFn => Some(c)
    case _           => None
}
