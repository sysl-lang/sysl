package sh.sysl

import scala.collection.mutable

/** What a type's members are, and what its traits promise.
 *
 * Everything here answers a question *about a type* that the tables `AnalyzerBase` holds are
 * the raw material for: which key a member is filed under, whether a name reaches one, whether
 * a type satisfies a bound and through which implementation, and — when it does not — what to
 * say about it. The hoist fills the tables; this is how the rest of the analyzer reads them.
 *
 * It sits between `AnalyzerBase` and everything else so that every trait mixed into the
 * analyzer sees these without having to name them.
 */
trait TraitLookup extends MemberVisibility {

  /** One `impl Trait for Type` block, as everything downstream of the hoist reads it.
   *
   *   - `written` are the trait arguments the block **wrote**, as against the ones the trait's own
   *     defaults filled in. They may name the block's own type parameters, but only ones the
   *     **subject** binds: `impl[T] Index[usize, T] for Buf[T]` writes an argument that every
   *     particular `Buf` settles, and a parameter the subject does *not* bind never gets this far,
   *     since `implArgs` requires each one to appear in the type the block is for. The distinction
   *     is what a defaulted block means — `impl Mul for
   *     Box[T]` does not promise `Mul` at one particular `Box` but whatever the default gives for
   *     the type being asked about, which at a `Box[string]` is `Mul[Box[string]]`.
   *   - `wkey` renders `written`, and is empty where the block wrote nothing. It is what tells a
   *     block that named its trait from one that let it default, without a subject in hand.
   *   - `alt` is the suffix this block's members carry, empty for the first implementation of a
   *     trait for a type and distinct for each one after it.
   *   - `tparams` are the block's own type parameters **in the subject's argument order**, which is
   *     what lets a subject's arguments be substituted into `written` positionally. Empty for a
   *     block written for one type.
   *   - `bounds` is what a **generic** block asks of the type arguments its subject is applied to:
   *     the block's own parameters in the subject's argument order, and the bounds it wrote on
   *     them. `impl[T: Show] Show for Box[T]` is **conditional conformance** — a `Box` implements
   *     `Show` when its element does — and this is the condition. It is kept as written rather than
   *     resolved because one bound may name another of the block's parameters, and only the
   *     arguments a particular `Box` was made with say what that means. An unconditional block has
   *     `None`, which is what makes the ordinary case a lookup that asks nothing further.
   *   - `scope` is the terms the block was **written** in, which is what a held-as-written bound has
   *     to be resolved under. Whether a type conforms is asked wherever the trait is used, and a
   *     bound naming a trait by a short name means what the *block's* file imported — so resolving
   *     it where the question was asked reaches whatever that module happens to see, which for a
   *     program that imported only the type is nothing at all.
   */
  protected case class TraitImpl(
      decl: ImplDecl,
      written: List[Type],
      wkey: String,
      alt: String,
      tparams: List[String],
      bounds: Option[(List[String], Map[String, List[BoundRef]])],
      scope: Scope,
  )

  /** Every `impl Trait for Type` for one (trait name, the implementing type's **owner key**), in the
   * order the source wrote them. It is what a trait bound consults to decide whether a type
   * conforms, and where a duplicate implementation is caught.
   *
   * Keying by the owner key rather than by the name as written is what makes `impl Show for int` and
   * `impl Show for i32` the one implementation they are — the same type reached by two spellings.
   *
   * There is a **list** of them because a trait that takes arguments is a family of promises rather
   * than one, and a type may keep more than one: a `Complex` that is `Mul[Complex]` and `Mul[real]`
   * multiplies by another complex number and scales by a real, and refusing the second would be
   * refusing the arithmetic rather than resolving an ambiguity. What picks between them is the
   * argument list, which every use — an operator, a bound, a named call — already has in hand
   * (`02 § A trait may be implemented at more than one argument list`).
   */
  protected val traitImpls = mutable.LinkedHashMap.empty[(String, String), List[TraitImpl]]

  /** Every implementation of a trait for one owner key, in source order. */
  protected def implsOf(traitName: String, key: String): List[TraitImpl] =
    traitImpls.getOrElse((traitName, key), Nil)

  /** The synthetic owner key a **blanket** `impl` is filed under: one key per closed bound, standing
   * for every type that meets it.
   *
   * A blanket is not a new kind of storage and deliberately does not get one. A block matching every
   * slice is already filed under `[]` with its members instantiated per receiver (`shapeOwner`), and
   * a block matching every integer is the same arrangement with a different key — so the whole of
   * this is a name that no type's own key can collide with.
   */
  protected def blanketKey(boundTrait: String): String = s"@bound:$boundTrait"

  /** Which closed bound each registered blanket `impl` was written over, by the key it is filed
   * under. Small, and usually empty outside the library.
   */
  protected val blanketBounds = mutable.LinkedHashMap.empty[String, String]

  /** The blanket keys a type is covered by, each paired with the type itself — the `(key, targs)`
   * pair `shapeOwner` gives for a shape, since a blanket's one type argument is the receiver.
   *
   * **The membership is asked of `CoreTraits` directly and never through `satisfies`, and that is
   * load-bearing twice over.** It is what terminates: `satisfies` reaches `conforms`, which reaches
   * `implKey`, which reaches here — so a question asked back through `satisfies` would be the same
   * question one turn later. And it is what keeps the relaxation sound, since a bound the compiler
   * answers about is one nothing outside the compiler can join (`CoreTraits.closed`). The two are
   * one argument, so widening the relaxation past a closed bound needs both re-derived.
   *
   * What this does **not** carry is the block's own condition — `conforms` re-checks that through
   * `ti.bounds`, exactly as it does for `impl[T: Show] Show for []T`. The filter here is what tells
   * *which* blanket, and it is needed for that alone: `implAt` selects on the trait's argument list,
   * and a trait taking no arguments would hand the first blanket to every type asking.
   */
  protected def blanketOwners(t: Type): List[(String, List[Type])] =
    if blanketBounds.isEmpty then Nil
    else
      blanketBounds.toList.collect {
        case (key, bound) if Library.spelling(bound).exists(CoreTraits.builtin(_, t)) => (key, List(t))
      }

  /** The promise one implementation makes **about this subject**: what the block wrote, with the
   * subject's own type arguments put in for the block's parameters and the trait's own defaults
   * supplying the rest under a `Self` of the type being asked about.
   *
   * The substitution is what makes an argument built out of a parameter a promise about a
   * particular type rather than an open one. `impl[T] Index[usize, T] for Buf[T]` wrote
   * `Index[usize, T]`, and what a `Buf[int]` gets from it is `Index[usize, int]` — the block's
   * parameter is not free here, it is whatever the subject was made with.
   */
  protected def suppliedBound(ti: TraitImpl, name: String, subject: Type, targs: List[Type]): Type.Bound = {
    val written = ti.written.map(substParams(_, ti.tparams.zip(targs).toMap))

    traitDecls.get(name) match
      case Some(d) if written.length < d.tparams.length =>
        Type.Bound(name, sandboxed(withDefaults(name, d.tparams, d.tdefaults, written, selfBinding(subject))))
      case _ => Type.Bound(name, written)
  }

  /** Which of a type's implementations of a trait supplies exactly the promise `tr` asks for. A
   * trait that takes no arguments has one promise to make, so the search is a lookup for everything
   * outside a generic trait.
   */
  protected def implAt(tr: Type.Bound, key: String, subject: Type, targs: List[Type]): Option[TraitImpl] =
    implsOf(tr.name, key).find(ti => suppliedBound(ti, tr.name, subject, targs).key == tr.key)

  /** The whole argument list an implementation was written at, found from the arguments a *use*
   * supplies rather than from all of them (`14 §7`).
   *
   * An operator is the one use that cannot name every argument: `a * b` fixes the right-hand type and
   * says nothing about the result, because the result is what it is asking to be told. So the
   * arguments a use has are a **prefix** — the selector — and the implementation supplies the tail.
   * Two implementations agreeing on the selector are refused where they are written, so at most one
   * is found here and this stays a lookup rather than a choice.
   */
  protected def implByOperand(trName: String, subject: Type, selector: List[Type], d: TraitDecl): Option[Type.Bound] = {
    def matching(key: String, targs: List[Type]) =
      implsOf(trName, key).view
        .map(ti => suppliedBound(ti, trName, subject, targs))
        .find(b =>
          b.args.length == d.tparams.length &&
            Type.Bound(trName, selector ++ b.args.drop(selector.length)).key == b.key)

    if selector.length >= d.tparams.length then None
    else
      val (key, targs) = memberOwner(subject)

      matching(key, targs)
        .orElse(shapeOwners(subject).view.flatMap((k, ta) => matching(k, ta)).headOption)
        .orElse(blanketOwners(subject).view.flatMap((k, ta) => matching(k, ta)).headOption)
  }

  /** Whether the `impl` blocks have all been registered, which is what makes a bound answerable.
   *
   * A type's bounds are checked wherever it is applied, and the earliest applications — a function's
   * declared parameters, a field of another type — are resolved while the file is still being
   * hoisted. Asking then would report a `Point` for not implementing a trait it implements six lines
   * further down, so the question is held until every `impl` is in.
   */
  protected var implsHoisted: Boolean = false

  /** One type application whose bounds could not be answered where it was written: what was applied,
   * to what, the bounds to hold it to, the position to report against — and **the terms the bounds
   * were written in**.
   *
   * The scope is carried because the check is run later, from a walk that is nowhere near the file
   * that wrote the bound. A bound names a trait the way anything else does, so resolving `[T: Scale]`
   * from wherever the drain happens to be reaches whatever *that* module can see, which for an
   * imported name is nothing at all — and a bound that resolved to nothing is compared against the
   * same bound that resolved properly, so the type is told its own parameter is not bounded by the
   * trait it is bounded by.
   */
  protected case class DeferredBound(
      what: String,
      tparams: List[String],
      bounds: Map[String, List[BoundRef]],
      targs: List[Type],
      pos: Option[Pos],
      scope: Scope,
  )

  /** Type applications whose bounds could not be answered where they were written. Drained once, as
   * soon as the answer is available.
   */
  protected val boundChecks = mutable.ListBuffer.empty[DeferredBound]

  /** `impl` blocks whose trait requires other traits, each with the type it was written for and the
   * position to report against. Held for the same reason a bound is: the implementation that
   * supplies a required trait may be written below the one that needs it.
   */
  protected val superChecks = mutable.ListBuffer.empty[(String, String, Type.Bound, Type, Option[Pos])]

  /** Every block marked `override`, with the type it is for and the promise it makes, waiting to be
   * asked whether anything more general actually covers that type (`02 § override`).
   *
   * Held for the reason `superChecks` is held: the block being replaced may be written below this one
   * or in a module hoisted after it, and an answer that depended on which would make the keyword mean
   * different things in two files that differ only in their order.
   */
  protected val overrideChecks = mutable.ListBuffer.empty[(String, Type.Bound, Type, Option[Pos], Scope)]

  /** Checks the arguments a *type* was applied to against what it asks of its parameters, now if
   * that can be answered and after hoisting if it cannot.
   */
  protected def checkTypeBounds(name: String, tparams: List[String], targs: List[Type]): Unit =
    deferredBounds(name, tparams, nominalBounds(name), targs)

  /** The same, for any generic declaration applied to arguments while the file is still being
   * hoisted — a type, or a **trait**, which is applied in a bound, in an `impl`, and in a trait
   * object alike.
   */
  protected def deferredBounds(
      what: String,
      tparams: List[String],
      bounds: Map[String, List[BoundRef]],
      targs: List[Type],
  ): Unit =
    if bounds.nonEmpty && tparams.length == targs.length then
      if implsHoisted then checkParamBounds(what, tparams, bounds, targs)
      else boundChecks += DeferredBound(what, tparams, bounds, targs, currentPos, currentScope)

  /** Whether the type arguments a generic declaration was applied to implement what it asked of its
   * parameters — the one rule, wherever the parameters came from: a function's, an `impl` block's,
   * or a type's own.
   *
   * An argument that could not be worked out is passed over. The mistake that produced it has been
   * reported, and a bound it fails to meet is a consequence of that rather than a second mistake.
   */
  protected def checkParamBounds(
      what: String,
      tparams: List[String],
      bounds: Map[String, List[BoundRef]],
      targs: List[Type],
  ): Unit =
    if bounds.nonEmpty then
      val subst = tparams.zip(targs).toMap

      for (tp, traits) <- bounds; ref <- traits do
        subst.get(tp) match
          case Some(Type.Unknown) =>
          case Some(arg) =>
            // The bound is resolved under the very arguments being checked, since one may name
            // another of the parameters: `[T: From[U], U]` asks a different thing of `T` at every
            // call, and `U`'s argument is what says which.
            val tr = resolveBound(ref, subst ++ selfBinding(arg))

            arg match
              // A type parameter standing in for itself, during the definition-time pass. It is not
              // a type anything has an `impl` for, so what it can promise is exactly what its own
              // bounds promise — those traits and whatever they require: a bound is satisfied by a
              // bound.
              case a: Type.Abstract =>
                if !satisfies(tr, a) then
                  boundErr(s"'$what' requires its type parameter '$tp' to implement '${showBound(tr, a)}', " +
                    s"but '${a.name}' is not bounded by it")
              case concrete =>
                if !satisfies(tr, concrete) then
                  // A type an implementation covers is told what that implementation asked of it, so
                  // the reader is sent one step in — to the argument that fails — rather than to a
                  // block that is already written.
                  val why = unmetBound(tr, concrete).fold("")(reason => s" — $reason")

                  err(s"'$what' requires its type parameter '$tp' to implement '${showBound(tr, concrete)}', " +
                    s"but ${show(concrete)} does not$why")
          case None =>

  /** Where a type's members are registered: the key they are filed under, and the type arguments a
   * generic type was instantiated with.
   *
   * **Every** type has one. A struct or an enum is filed under the name it was declared with; every
   * other type under the name a diagnostic gives it, which is canonical — one name per type, and the
   * same one whichever alias reached it (`int` and `i32` are one key, not two). That is what lets
   * `5.show()` resolve exactly as `p.show()` does, with no separate machinery for the built-ins.
   */
  protected def memberOwner(t: Type): (String, List[Type]) = t match
    // A tuple is filed under the whole of itself, exactly as `[]int` is: the type an `impl` written
    // out in full is for. Its *shape* — every tuple of that arity — is the separate key below.
    case t: Type.Tuple => (Type.show(t), Nil)
    case n: Type.Named => (n.base, n.targs)
    case other         => (Type.show(other), Nil)

  /** The key alone — what an `impl` is filed under, and what a trait bound looks up. */
  protected def ownerKey(t: Type): String = memberOwner(t)._1

  /** The **shapes** of a composed type: the keys a block matching every type of that shape is filed
   * under, each with the type arguments this particular one matched at, **most specific first**.
   *
   * A composed type is filed under the whole of itself — `[]int`, not `[]` — because that is the
   * type a written `impl` is for and the name a diagnostic gives it. A shape-matched block is for
   * something else, so it needs a key of its own, and dropping the arguments is exactly what makes
   * one: every slice shares `[]`.
   *
   * There is a **list** of them because an array has two, and the order between them is the whole
   * reason this is not a single answer. Every argument a shape drops is one a block may be generic
   * over, and value generics (`10 §9`) made an array's length one of those — so `[3]T` and `[N]T`
   * are both blocks covering a `[3]int`, and the first covers less. Asking the keys in order is how
   * the more specific one answers, which is the same ordering `memberKey` applies between a type's
   * own key and its shape.
   *
   * A `string` is not a slice and has no shape here. It is a view of bytes that are valid UTF-8, and
   * that invariant is the whole difference between it and a `[]u8` — a block written for every slice
   * has said nothing about it.
   */
  protected def shapeOwners(t: Type): List[(String, List[Type])] = t match
    // Both views share the one shape, and for the reason the shape exists: a block written for
    // every slice is written against what a slice *is* — a pointer and a count of `T` — and whether
    // this one may be written through is not part of that. A block that does write is caught where
    // it writes, which is a better place to say so than a missing implementation.
    case Type.Slice(elem, _) => List(("[]", List(elem)))
    // The length is dropped from the second key and handed back as an **argument**, exactly as the
    // element type is: a `[3]int` matches `[N]T` at `N = 3` and `T = int`, and the block's members
    // are instantiated from that pair the way a slice's are from one.
    case Type.Array(n, elem) =>
      List((s"[$n]", List(elem)), (Type.Array.shape, List(Type.ConstArg(n, Type.usize), elem)))
    // A tuple has **two** shapes, and the order between them is what makes a block written for one
    // arity beat one written for every arity — the same ladder an array gained one line up, one
    // kind further out. The arity's shape hands the parts back as separate arguments; the pack's
    // hands them back as one list, which is what `(..A)` binds `A` to (`10 §10`).
    case t: Type.Tuple =>
      List((Type.Tuple.shape(t.targs.length), t.targs), (Type.Tuple.pack, List(Type.Pack(t.targs))))
    case _ => Nil

  /** Where a member of that name is filed for a type: under the type's own key, or — when only a
   * shape-matched block supplies it — under the shape's, with the arguments this type matched at.
   *
   * **The type's own key is asked first, and that order is what makes an override work** (`02 §
   * override`). Two blocks may give one name to one type only where the written-out one says
   * `override` (`hoistMemberList`), and then asking the type's key before the shape's is exactly the
   * "written-out beats a parameter" half of the ordering — the more specific block answers because it
   * is the first place looked. Everywhere else nothing is reached both ways, and the order merely
   * settles which table holds the member.
   */
  protected def memberKey(t: Type, mname: String): (String, List[Type]) = {
    val own = memberOwner(t)

    if memberDecls.contains((own._1, mname)) then own
    else
      widened(t).filter(w => memberDecls.contains((w._1, mname)))
        .orElse(shapeOwners(t).find(s => memberDecls.contains((s._1, mname))))
        // A blanket's members are filed under its key exactly as a shape's are under `[]`, and this
        // is what a *call* finds them by. Without it a blanket would conform and have nothing
        // callable: `memberFuncName` reads the pair this returns and instantiates from it, so a
        // receiver whose member lives here would be looked up under its own key and found missing.
        .orElse(blanketOwners(t).find(b => memberDecls.contains((b._1, mname))))
        .getOrElse(own)
  }

  /** The key a **writable** view also answers to: the read-only view of the same elements.
   *
   * `07 § Read-only views` settles this and states it without qualification — "it is one type with a
   * bit, not two types", and "a `[]T` is accepted wherever a `[]const T` is wanted". A receiver is
   * such a place, so `impl Search for []const u8` is reachable on a `[]u8`. Without this the lookup
   * files the two under the names a diagnostic gives them, `[]byte` and `[]const byte`, and denies
   * the member outright — which is the compiler treating them as the two types that sentence exists
   * to deny.
   *
   * **It goes one way only, which is the other half of the same sentence: "and never the other way
   * round."** A member written for a `[]T` may write through its receiver, and a `[]const T` that
   * reached it would be a read-only view with a writing member — the one thing the bit is for. So a
   * read-only view widens to nothing and keeps only what was written for it.
   *
   * A `string` is deliberately not here. It is a view of bytes that are valid UTF-8, and that
   * invariant is the whole difference between it and a `[]const u8` (`04`) — a block written for
   * every read-only byte view has said nothing about it, exactly as `shapeOwner` says of slices.
   */
  private def widened(t: Type): Option[(String, List[Type])] = widenedType(t).map(memberOwner)

  private def widenedType(t: Type): Option[Type] = t match
    case Type.Slice(elem, false) => Some(Type.Slice(elem, readOnly = true))
    case _                       => None

  /** The type a member of that name was actually found at — the receiver's own, or the read-only
   * view it widened to.
   *
   * The **symbol** has to follow the key and not the receiver. `Type.memberSymbol` mangles the
   * read-only bit deliberately (`slice.byte` against `constslice.byte`), so a member found under
   * `[]const u8` and named from a `[]u8` receiver would be a call to a function nothing emitted.
   */
  protected def memberReceiverType(t: Type, mname: String): Type =
    if memberDecls.contains((memberOwner(t)._1, mname)) then t
    else
      widenedType(t).filter(w => memberDecls.contains((memberOwner(w)._1, mname))).getOrElse(t)

  /** Whether a type has a member of that name at all, however it came by it. */
  protected def hasMember(t: Type, mname: String): Boolean =
    memberDecls.contains((memberKey(t, mname)._1, mname))

  /** The name codegen emits for one of a type's members. A member of a concrete type was hoisted
   * eagerly under `Type.member`; a member of a generic type is instantiated here, from the
   * receiver's own type arguments, and its body queued for analysis — so both answer with a name
   * that `funcInsts` holds.
   *
   * The prefix is the type **mangled**, not the key its members are filed under, because a type an
   * `impl` may name is not always a name: `[]int` is a fine key and an impossible LLVM symbol. The
   * two coincide for every type that *is* named.
   *
   * It lives here rather than with the calls because a vtable slot asks the same question a call
   * does, and a slot naming a member by any other rule is a slot pointing at nothing.
   *
   * A member with type parameters **of its own** is not named by its receiver: the arguments that
   * fix those come from the call, so the call is where it is named. Nothing that asks here can
   * reach one — a vtable slot, an operator, a property read are all members a trait declares, and
   * a trait declares no generic method — so the case is reported rather than answered with a name
   * built from too few arguments.
   */
  protected def memberFuncName(ty: Type, mname: String): String = {
    val (base, targs) = memberKey(ty, mname)

    genericMembers.get((base, mname)) match
      case Some(fd) if fd.tparams.length > targs.length =>
        err(s"'$mname' has type parameters of its own, so ${show(ty)} alone does not say which " +
          "instantiation is meant — call it, and the arguments will")
      case Some(fd) => instantiateFunc(fd, targs)
      case None     => s"${Type.memberSymbol(memberReceiverType(ty, mname))}.$mname"
  }

  /** Whether a member of that name is one the *compiler* provides for the type (`08`).
   *
   * `len`, `bytes`, `chars` and `copy` are members of built-ins that have no source body to declare
   * them in, so they are reached ahead of the member table rather than through it. An `impl` for such
   * a type could otherwise register a member of the same name that nothing would ever find, which is
   * why this is asked at the declaration.
   */
  protected def builtinMember(t: Type, name: String): Boolean = (t, name) match
    case (_: Type.Array | _: Type.View, "len")  => true
    case (Type.Str, "bytes" | "chars" | "copy") => true
    case (_: Type.Weak, "get")                  => true
    case _                                      => false

  /** The same question asked of a **shape** rather than of a type.
   *
   * A block matching every slice or every array at once has no `Self` to ask the type-keyed test
   * above about — the element is still a parameter when the members are hoisted — so the one block
   * that most needs asking is the one it cannot see. `impl[T] Sized for []T` declaring a `len` was
   * accepted and then never found, since `xs.len` is answered ahead of the member table, which is
   * exactly the outcome `08 § Built-in members` says is refused at the declaration.
   *
   * `len` is the whole list: it is the only member the compiler provides for a sequence, and the
   * shapes that have one are the two written with brackets. A tuple's shape is its arity and has
   * no compiler-provided member at all.
   */
  protected def builtinShapeMember(head: String, name: String): Boolean =
    name == "len" && head.startsWith("[")

  /** Every trait a bound promises: the traits it **requires**, transitively, and then itself
   * (`02 § A trait may require another trait`).
   *
   * The order is what the rest of this depends on — a required trait comes before the trait that
   * required it — because it is the order a method table is laid out in, and a table and the call
   * sites that index into it have to agree. Depth-first with each trait taken once, so a diamond
   * (`D: A + C`, both `A: B` and `C: B`) carries `B`'s members once rather than twice.
   *
   * A required trait's arguments are read under the requiring trait's own parameters, which is what
   * makes `trait Pair[T]: Sink[T]` mean the `Sink` of whatever the pair was applied to. A cycle
   * terminates here rather than looping — it is reported once, at the declarations, by
   * `checkTraitSupers`.
   *
   * **A trait is taken once by name, not once per argument list**, which is the same rule
   * `checkTraitSupers` enforces at the declaration: a type implements one trait once, so a closure
   * holding `Into[int]` and `Into[bool]` would be laying out two slots for one member. Keying this
   * walk any other way is how the table and the declaration check come to disagree.
   */
  protected def traitClosure(b: Type.Bound, self: Map[String, Type] = Map.empty): List[Type.Bound] = {
    val seen = mutable.Set.empty[String]
    val acc  = mutable.ListBuffer.empty[Type.Bound]

    def walk(b: Type.Bound): Unit =
      if seen.add(b.name) then
        for decl <- traitDecls.get(b.name) do
          // `Self` in a required trait's arguments is the type implementing the requiring one, which
          // is the same type all the way down a chain of requirements — so it is carried in rather
          // than rebound at each step.
          val subst = self ++ decl.tparams.zip(b.args)

          for s <- decl.supers do walk(resolveBound(s, subst))
        acc += b

    walk(b)
    acc.toList
  }

  /** Everything a trait offers — each member, with the bound of the trait that declared it.
   *
   * A trait that requires another offers that one's members too, so this is what a trait object
   * dispatches through and what a bound licenses, and both read it from here so that neither can
   * disagree with the other about which slot is which.
   */
  protected def traitMembers(b: Type.Bound, self: Map[String, Type] = Map.empty): List[(Type.Bound, MethodDecl)] =
    traitClosure(b, self).flatMap(sb => traitDecls.get(sb.name).toList.flatMap(_.methods.map((sb, _))))

  /** Whether a type implements a trait, which is the one question a bound asks.
   *
   * There are three ways to answer yes and they are not interchangeable. A **user** type opts in
   * with an explicit `impl`, filed under its owner key — nominal conformance, never structural. A
   * **built-in** is a member by the compiler's own rule (`14 §5`), because it has no module to write
   * an `impl` in and the integer family has no finite list of types to write one for. And a **type
   * parameter** implements exactly what its own bounds promise, which is what lets a bounded body
   * hand its parameter on to something that asks the same of it.
   *
   * What a bound promises includes what the traits it names require, which is the whole point of a
   * supertrait: a `[T: Word]` may be handed to something asking `[U: Add]` without writing `Add`
   * again.
   */
  protected def satisfies(tr: Type.Bound, t: Type): Boolean = t match
    case a: Type.Abstract => a.bounds.exists(b => traitClosure(b, selfBinding(a)).exists(_.key == tr.key))
    // **A bound on a pack distributes over its members** (`10 §10`), which is the whole of the bound
    // syntax a pack needs: `[..A: Display]` is the ordinary `[T: Display]` read over a list. It is
    // what makes the membership answerable before instantiation — a tuple implements `Display` when
    // every part does, so a call is refused where it is written rather than inside a body that has
    // already been committed to.
    //
    // Empty is vacuously true and cannot arise: there is no zero-tuple (`00 §13`), so a pack bound
    // by matching one always has parts.
    case Type.Pack(elems) => elems.forall(satisfies(tr, _))
    // A compiler-provided membership is **homogeneous**, and that is `01`'s rule about the scalars
    // rather than a limitation of this one: no operator promotes, so an `int` is `Mul[int]` and is
    // not `Mul` at anything else. The arguments are compared the way an assignment compares two
    // types, so a transparent subtype is its base's member exactly as it is its base's operand.
    case _ =>
      conforms(tr, t) || erasedSatisfies(tr, t) ||
        (tr.args.forall(!disagree(_, t)) && Library.spelling(tr.name).exists(CoreTraits.builtin(_, t)))

  /** Whether a **trait object** satisfies a bound, which it does for the trait it dispatches through
   * and for every trait that one requires.
   *
   * A `&Shape` has forgotten which type it holds, so there is no `impl` filed for it and nothing for
   * `conforms` to find. What it has instead is a table, and the table is the thing a bound was going
   * to be used for: a `[T: Shape]` body calls `Shape`'s members on its parameter, and every one of
   * them is a slot. So the object implements the trait by dispatching, and the members a bound
   * licenses are exactly the members the table lays out — the two lists are `traitMembers` read
   * twice, which is what makes this an identity rather than a coincidence.
   *
   * **It is total, and that is a property of sysl's object safety rather than of this rule.** A trait
   * with a member that cannot be dispatched has no object at all, so a `&Shape` existing is already
   * the proof that every member of `Shape` is reachable through it. There is no partial case to
   * exclude here, and none of the "this method is unavailable on the object" apparatus a language
   * with per-member object safety needs.
   *
   * The required traits come for free from the same closure the table is laid out from, so a bound on
   * a trait the object's own trait requires needs no rule of its own (`02 § Requiring another
   * trait`).
   */
  protected def erasedSatisfies(tr: Type.Bound, t: Type): Boolean =
    Type.erasedTrait(t).exists(o => traitClosure(Type.Bound(o.name, o.args), selfBinding(t)).exists(_.key == tr.key))

  /** The same question about a trait that takes no arguments, which is every trait the compiler
   * knows by name and most of the ones a program declares.
   */
  protected def satisfies(traitName: String, t: Type): Boolean = satisfies(Type.Bound(traitName, Nil), t)

  /** How a bound is spelled back to a programmer: **without** the arguments a default would have
   * supplied, because that is how the program writes it.
   *
   * `[T: Mul]` already means `Mul[T]`, so a diagnostic that told someone to write `T: Mul[T]` would
   * be telling them to write out what they may leave out. The arguments are shown only where they
   * are something the default would not have given — which is exactly the case the reader needs to
   * see, since it is the one a bare bound does not cover.
   */
  protected def showBound(tr: Type.Bound, self: Type): String = {
    def filled(written: List[Type]) =
      for d <- traitDecls.get(tr.name) if d.tparams.nonEmpty
      yield Type.Bound(tr.name,
        sandboxed(withDefaults(tr.name, d.tparams, d.tdefaults, written, selfBinding(self))))

    // Trailing arguments a default would have supplied are dropped one at a time, longest first, so a
    // bound is spelled as short as it can be and still mean what it means. `Mul[real]` on a type whose
    // result is itself prints as written rather than as `Mul[real, Vec]`, and a `Mul` whose every
    // argument is its default prints bare — the same rule, applied at each position instead of only
    // to the whole list.
    val shortest =
      (0 to tr.args.length).find(n => filled(tr.args.take(n)).exists(_.key == tr.key))

    shortest match
      case Some(0) => Modules.show(tr.name)
      case Some(n) => Type.Bound(tr.name, tr.args.take(n)).show
      case None    => tr.show
  }

  /** Whether a type implements a trait **through a source `impl`** — conformance a table can point
   * at, as against a membership the compiler provides by rule.
   *
   * A generic `impl` may be conditional, and this is where the condition is answered: the
   * implementing type's own arguments must satisfy what the block asked of them, which is the same
   * question one step in. So `Box[int]` implements `Show` under `impl[T: Show] Show for Box[T]`
   * exactly when `int` does, and a `Box[Point]` over a `Point` with no `Show` does not. A slice
   * covered by `impl[T: Show] Show for []T` is the same question asked of its element.
   */
  protected def conforms(tr: Type.Bound, t: Type): Boolean =
    implKey(tr, t).exists { case (targs, ti) =>
      ti.bounds.forall { case (tps, bounds) =>
        val subst = tps.zip(targs).toMap

        targs.length == tps.length &&
        // In the block's own terms, because the condition is the block's: a `[T: Display]` written in
        // a library file means that file's `Display`, and this question is asked wherever the trait
        // is used — in a program that may have imported neither.
        inScope(ti.scope)(tps.zip(targs).forall { case (tp, arg) =>
          bounds.getOrElse(tp, Nil).forall(b => satisfies(resolveBound(b, subst ++ selfBinding(arg)), arg))
        })
      }
    }

  /** The same, for a trait that takes no arguments. */
  protected def conforms(traitName: String, t: Type): Boolean = conforms(Type.Bound(traitName, Nil), t)

  /** Which implementation of a trait **at these arguments** a type is covered by, if any: the one
   * written for the type, or the one written for its shape, with the arguments it matched at.
   *
   * The argument list is what selects, and it selects exactly one *per key*. A type may implement a
   * trait more than once, but never twice at one argument list; and a shape and a type written out in
   * full may both implement a trait only where the written-out block says `override` (`hoistImpl`).
   * That is the one case where two answers exist, and the order below is the ordering `02 §` states:
   * the type's own key before the shape's before a blanket's, which is "written-out beats a
   * parameter" read as a sequence of lookups.
   */
  protected def implKey(tr: Type.Bound, t: Type): Option[(List[Type], TraitImpl)] = {
    def at(k: (String, List[Type])) = implAt(tr, k._1, t, k._2).map((k._2, _))

    at(memberOwner(t))
      .orElse(shapeOwners(t).view.flatMap(at).headOption)
      .orElse(blanketOwners(t).view.flatMap(at).headOption)
  }

  /** The suffix the members of a type's implementation of one particular trait-at-arguments carry,
   * empty for the first implementation of that trait and distinct for each one after it.
   *
   * It is what makes a second implementation possible at all: a trait's members become the type's,
   * and a type's members are one namespace (`08`), so two implementations of one trait would give
   * the type two members of each name. They are filed under names that differ, and every way of
   * reaching one — an operator, a vtable slot, a named call — arrives with the argument list that
   * says which is meant.
   */
  protected def implAlt(tr: Type.Bound, t: Type): String = implKey(tr, t).map(_._2.alt).getOrElse("")

  /** The name codegen emits for the member a type got from one particular implementation of a
   * trait — what `memberFuncName` is for a call that already knows which trait it is dispatching.
   */
  protected def traitMemberName(t: Type, tr: Type.Bound, mname: String): String =
    memberFuncName(t, mname + implAlt(tr, t))

  /** Why a type an implementation *covers* still does not implement the trait — which is a different
   * answer from having no implementation at all, and the one a diagnostic should give when there is
   * one.
   *
   * Told to write an `impl Display for []Point`, a programmer would find it refused: an
   * `impl[T: Display] Display for []T` already covers every slice, and what the slice of points
   * fails is that block's condition. So the condition is what is worth saying, and this is the first
   * argument that does not meet it. `None` where nothing covers the type, leaving the caller's own
   * advice — write one — the right advice.
   */
  protected def unmetBound(tr: Type.Bound, t: Type): Option[String] =
    // A type that implements the trait at *other* arguments is a different situation from one that
    // does not implement it at all, and the advice differs with it: what is missing is one more
    // `impl`, at the arguments the bound asked for, beside the ones already there.
    val wrongArgs =
      for
        (key, targs) <- List(memberOwner(t)) ::: shapeOwners(t) ::: blanketOwners(t)
        impls = implsOf(tr.name, key)
        if impls.nonEmpty && implAt(tr, key, t, targs).isEmpty
      yield s"it implements ${conjoin(impls.map(ti => s"'${showBound(suppliedBound(ti, tr.name, t, targs), t)}'"))}"

    val unmetCondition =
      for
        (targs, ti)   <- implKey(tr, t)
        (tps, bounds) <- ti.bounds
        if targs.length == tps.length
        subst = tps.zip(targs).toMap
        // Read in the block's terms, as the condition itself is — so the name in the message is the
        // one the block wrote resolved against the block's imports, and not a bare word this module
        // happens not to know.
        (arg, unmet) <- inScope(ti.scope)(tps
          .zip(targs)
          .flatMap((tp, a) =>
            bounds.getOrElse(tp, Nil).map(resolveBound(_, subst ++ selfBinding(a))).filterNot(satisfies(_, a))
              .map((a, _)))
          .headOption)
      yield s"the 'impl' that covers it asks '${unmet.show}' of ${show(arg)}, which does not implement it"

    // A **tuple** used to get a sentence of its own after these two, saying which arity the library
    // stopped writing rows at and that a product wider than that wants a struct with names. There is
    // no arity to stop at now: the rows are written over a type pack and cover every tuple
    // (`10 §10`), so what is left to say about one is what is said about every other type — which of
    // its parts does not implement the trait, which `unmetCondition` says by name.
    wrongArgs.headOption.orElse(unmetCondition)

  /** The same, for a trait that takes no arguments. */
  protected def unmetBound(traitName: String, t: Type): Option[String] =
    unmetBound(Type.Bound(traitName, Nil), t)

  /** The type a member is looked up on, seeing through one level of `*T` / `&T` so a method may be
   * called on a value, a pointer to it, or a reference to it alike.
   */
  protected def receiverType(t: Type): Type = t match
    case Type.Ptr(inner)    => inner
    case Type.Ref(inner, _) => inner
    case other              => other

  /** `1 argument`, `2 arguments` — a count with its noun agreeing. A diagnostic that misspells
   * English reads like a bug in the thing reporting it, which is not the impression a compiler
   * wants to give at the moment the programmer is already annoyed.
   */
  protected def quantity(n: Int, noun: String): String = if n == 1 then s"1 $noun" else s"$n ${noun}s"

  /** A position as a message spells it: `1st`, `2nd`, `3rd`, `4th`. */
  protected def ordinal(n: Int): String = n match
    case 1 => "1st"
    case 2 => "2nd"
    case 3 => "3rd"
    case _ => s"${n}th"

  /** `a`, `a and b`, `a, b and c` — a list read aloud, for a diagnostic naming everything a type
   * does implement when it does not implement the one that was asked for.
   */
  protected def conjoin(xs: List[String]): String = xs match
    case Nil      => ""
    case one :: Nil => one
    case _        => s"${xs.init.mkString(", ")} and ${xs.last}"

  /** `1 argument was given`, `2 arguments were given` — the same, with the verb agreeing too. */
  protected def supplied(n: Int, noun: String): String =
    s"${quantity(n, noun)} ${if n == 1 then "was" else "were"} given"

}
