package io.github.edadma.sysl

/** The atomic operations, which are the `*T` tier of concurrency — `06 § The kernel tier`.
 *
 * These sit beside `sizeof` and `ptr_cast` rather than beside `print`, and for the same reason: each
 * is a machine instruction with no sysl body, at a place where the language's guarantees stop. An
 * atomic operation makes one access indivisible and says nothing about the object it reaches, who
 * else holds the address, or whether the algorithm around it is right. That is the tier's bargain —
 * the compiler guarantees nothing and the uses are greppable.
 *
 * **They are the plumbing rather than the surface.** `06` puts `Atomic[T]` and `SpinLock` in a
 * library, and those are ordinary sysl written on top of what is here: a struct with a field and
 * methods that take the address of it. Nothing about the tier is meant to be pleasant to write
 * directly, which is why the ordering is required at every call rather than defaulted — the default
 * belongs on the library surface, where a reader can see which one they are getting.
 *
 * **Why an ordering is not an ordinary argument.** It becomes a *keyword* in the emitted
 * instruction, not a value the instruction reads, so it has to be known where the instruction is
 * emitted. A variable holding one could not be lowered at all. So the argument is required to be
 * one of `sysl.sync.Ordering`'s variants written at the call, and anything else is refused by name
 * rather than left to fail as a type error somewhere below.
 *
 * **What the pointee may be** is decided by the machine and not by the language: LLVM's atomics take
 * an integer of a power-of-two byte width, or a pointer. sysl's integers are an open family — `i5`
 * and `u12` are types a program may name (`01`) — so the widths are checked rather than assumed, and
 * a value the machine has no atomic instruction for is refused where it is written.
 */
object Atomics {

  /** The addressed forms, against how many value operands each takes after the address and before
   * the ordering. Read by the dispatch, by the arity check, and by the name registry, so that adding
   * one is a line here rather than three that could disagree.
   */
  val operands: Map[String, Int] =
    Map(
      "atomic_load"  -> 0,
      "atomic_store" -> 1,
      "atomic_swap"  -> 1,
      "atomic_add"   -> 1,
      "atomic_sub"   -> 1,
      "atomic_and"   -> 1,
      "atomic_or"    -> 1,
      "atomic_xor"   -> 1,
      "atomic_cas"   -> 2,
    )

  /** The five that are read-modify-write arithmetic, which is the set a pointer is **not** allowed
   * at: adding to an address atomically is a question about pointer arithmetic that the raw tier has
   * no answer for, and LLVM's `atomicrmw add` takes an integer.
   */
  val arithmetic: Set[String] = Set("atomic_add", "atomic_sub", "atomic_and", "atomic_or", "atomic_xor")

  /** Every name these forms claim, including the fence, which takes no address and so is not in
   * `operands`. `SpecialForms` folds these into its own set so a mistake written *at* one of them
   * names the form — and the table is here, on an object, because that set is built one trait below
   * the one these are analyzed in.
   */
  val names: Set[String] = operands.keySet + "atomic_fence"

  /** What LLVM spells each ordering, which is the whole of the translation. They are named after
   * these to begin with, so the one that is not simply lowercased is `Relaxed` — LLVM kept C11's
   * *semantics* under its own older name, `monotonic`.
   */
  val llvm: Map[String, String] =
    Map(
      "Relaxed" -> "monotonic",
      "Acquire" -> "acquire",
      "Release" -> "release",
      "AcqRel"  -> "acq_rel",
      "SeqCst"  -> "seq_cst",
    )

  /** The ordering a failed compare-and-swap gets, given the one written for success.
   *
   * `cmpxchg` takes two, and a program writes one. A failure is a load that stored nothing, so it
   * may not carry a release — and LLVM refuses the instruction if it does. The rule is C11's: drop
   * the release half and keep whatever acquire was asked for, which is the strongest ordering the
   * failure path is allowed to have and therefore never weaker than the author expected.
   */
  def failure(success: String): String = success match
    case "Release" => "monotonic"
    case "AcqRel"  => "acquire"
    case other     => llvm(other)
}

trait Atomics extends ExprSupport {

  /** Whether the name still means the form here, rather than something the program declared.
   *
   * **Nine names is a lot to take out of a program's vocabulary**, and these do not take them: a
   * function, a local, or a nested function of the name is what a call to it reaches, and the form
   * applies only where nothing else claims the name. That is a deliberate difference from the older
   * forms beside them — `ptr_cast` and `print` win over a declaration and leave the declaration
   * unreachable — and it is the behaviour worth having, since `atomic_add` is a name a program
   * writing its own concurrency could reasonably want.
   */
  private def unclaimed(name: String): Boolean =
    lookupOpt(name).isEmpty && funcKey(name).isEmpty && !nestedFuncs.contains(name)

  /** The dispatch's guard, which is the same question asked from where the match is written. */
  protected def atomicForm(name: String): Boolean = Atomics.operands.contains(name) && unclaimed(name)

  /** The same for the fence, which takes no address and so is not among the addressed forms. */
  protected def atomicFenceForm: Boolean = unclaimed("atomic_fence")

  /** `atomic_fence(ord)` — a barrier with no address, ordering everything around it rather than one
   * access. The one form here that is about the thread instead of about a location.
   */
  protected def atomicFence(args: List[Expr]): TExpr = {
    if args.length != 1 then
      err("'atomic_fence' takes one argument, the ordering to fence at — it orders the accesses " +
        "around it rather than reaching any address of its own")

    val ord = ordering("atomic_fence", args.head)

    // A fence *is* its ordering — there is no access under it to be indivisible — so `Relaxed`
    // describes a fence that does nothing, and the machine has no instruction for one. Refused here
    // rather than left to the assembler, whose "fence cannot be monotonic" names neither the word
    // that was written nor the file it was written in.
    if ord == "Relaxed" then
      err("a fence is nothing but its ordering, so 'Relaxed' would ask for a barrier that orders " +
        "nothing — write 'Acquire', 'Release', 'AcqRel' or 'SeqCst', or drop the fence. A relaxed " +
        "*access* is the thing that is meaningful, and that is the ordering on the operation itself")

    TFence(ord)
  }

  /** One of the addressed forms: the address, then this form's value operands, then the ordering. */
  protected def atomicCall(name: String, args: List[Expr]): TExpr = {
    val values = Atomics.operands(name)

    if args.length != values + 2 then
      err(s"'$name' takes ${quantity(values + 2, "argument")} — an address, " +
        s"${if values == 0 then "no value" else quantity(values, "value")}, and an ordering — " +
        s"but ${supplied(args.length, "argument")}")

    val addr = analyzeExpr(args.head)
    val ty   = pointee(name, addr)
    val ops  = args.slice(1, 1 + values).map(a => analyzeExpr(a, Some(ty)))

    for (op, i) <- ops.zipWithIndex do
      if Type.underlying(op.ty) != ty then
        err(s"'$name' operates at the type its address points at, so argument ${i + 2} is a " +
          s"${show(ty)} — this one is a ${show(op.ty)}")

    val ord = ordering(name, args.last)
    val out = if name == "atomic_store" then Type.Unit else ty

    TAtomic(name, addr, ops, ord, ty, out)
  }

  /** The type an atomic form runs at, read off the address it was given.
   *
   * The refusals name what the machine cannot do rather than what the type is not, because the
   * reader of a `u12` counter has written something reasonable that no processor implements — and
   * being told "not an integer" would send them looking for a cast.
   */
  private def pointee(name: String, addr: TExpr): Type = Type.underlying(addr.ty) match
    case Type.Ptr(inner) =>
      val ty = Type.underlying(inner)

      ty match
        case i: Type.Integer if !Set(8, 16, 32, 64).contains(i.bits) =>
          err(s"'$name' is one machine instruction, and a machine has one for 8, 16, 32 and 64 bits " +
            s"— ${show(inner)} is ${i.bits}, so there is nothing to emit. Hold the value at a width " +
            s"the machine has and narrow it where it is read")
        case _: Type.Integer => ty
        case _: Type.Ptr if Atomics.arithmetic(name) =>
          err(s"'$name' is arithmetic, and what an address plus a number means is the question the " +
            s"raw tier does not answer — use 'atomic_swap' or 'atomic_cas' to change a pointer, or " +
            s"do the arithmetic on a 'usize' beside it")
        case _: Type.Ptr => ty
        case _ =>
          err(s"'$name' reaches a word the machine can touch indivisibly — an integer of 8, 16, 32 " +
            s"or 64 bits, or a pointer — and ${show(inner)} is neither. An aggregate is what a " +
            s"'SpinLock' or a '&sync Mutex[T]' is for (`06`)")
    case _: Type.Ref =>
      err(s"'$name' takes a raw address, and a ${show(addr.ty)} is a counted reference — atomicity " +
        s"here is about the bytes rather than about the count, which is already atomic in a " +
        s"'&sync T'. Reach the field with '&' and hand that in")
    case _ =>
      err(s"'$name' takes the address of the word to operate on, and this is a ${show(addr.ty)} — " +
        s"write '&place' to hand it one")

  /** The ordering, which has to be a variant of `sysl.sync.Ordering` written at the call.
   *
   * The complaint is deliberate rather than inherited. An ordering held in a variable is well-typed
   * sysl that cannot be lowered, so the type checker would pass it and codegen would have nothing to
   * emit; said here, the reader is told the reason — the instruction spells the ordering out — at
   * the place they wrote it.
   */
  private def ordering(name: String, arg: Expr): String = {
    val t   = analyzeExpr(arg)
    val key = Library.key("Ordering")

    t match
      case TEnumNew(en, variant, _) if en.base == key => variant.name
      case _ =>
        Type.underlying(t.ty) match
          case e: Type.Enum if e.base == key =>
            err(s"'$name' spells its ordering into the instruction, so it has to be one of " +
              s"Ordering's names written here — not a value carrying one. Where a caller chooses, " +
              s"branch on their choice and write a call per ordering")
          case _ =>
            err(s"'$name' takes an ordering last, one of Ordering's names from 'sysl.sync' — " +
              s"'SeqCst' if there is no reason to want another")
  }
}
