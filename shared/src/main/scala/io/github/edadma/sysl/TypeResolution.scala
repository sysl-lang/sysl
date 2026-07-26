package io.github.edadma.sysl

import scala.collection.mutable

/** Resolving a `TypeRef` to a `Type`, instantiating generic structs and enums on demand, and the
 * generics machinery that solves a declaration's type arguments from a call's argument types.
 *
 * A named type is memoized on its display name, and an instantiation is registered *before* its
 * fields are resolved, so a type that reaches itself through a `*T` / `&T` / `[]T` finds the
 * in-progress object and the recursion terminates; `cycleCheck` is what rejects a cycle that has
 * no indirection to break it.
 */
trait TypeResolution extends AnalyzerBase {

  /** Resolves a type in the current function's substitution — the identity map outside a
   * generic instantiation.
   */
  protected def rt(t: TypeRef): Type = resolveType(t, tsubst)

  /** Resolves a type reference under `subst`, which maps the enclosing declaration's type
   * parameters to the arguments it was instantiated with.
   */
  protected def resolveType(t: TypeRef, subst: Map[String, Type]): Type =
    at(t.pos)(resolveTypeAt(t, subst))

  /** The name of the bottom type. It is a predeclared identifier rather than a reserved word, like
   * every other type name, and it is deliberately *not* one of the scalars: there is no value of
   * it, so it is not something a variable, a field, or a type argument can be.
   */
  protected val neverName = "never"

  /** The name of the type a trait is being implemented for (`14 §1`).
   *
   * It is a substitution key rather than a type of its own: a trait declaration and an `impl` are
   * both resolved with `Self` bound to the implementing type, so `add(self, rhs: Self) -> Self` and
   * `add(self, rhs: Point) -> Point` are the one signature conformance compares. Outside those two
   * places it is bound to nothing and resolving it says so.
   *
   * Distinct from the lowercase receiver `self`, which is the *value*; this is its type.
   */
  protected val selfName = "Self"

  /** The substitution that gives `Self` its meaning, for the one type it currently stands for. */
  protected def selfBinding(t: Type): Map[String, Type] = Map(selfName -> t)

  /** `subst`, extended with whatever `Self` means inside `fname` — which is a question only a
   * member of a *generic* type leaves open.
   *
   * A concrete type's members had their `Self` resolved at hoist. A generic type's could not be:
   * `Box[T]` is not a type until `T` is one, so what was kept is the reference, and resolving it
   * under the very substitution that fixed the parameters is what fixes `Self` alongside them.
   * Both places that build such a substitution — an instantiation and the definition-time pass —
   * come through here, so `Self` means the same thing in a checked body and in a run one.
   */
  protected def withSelf(fname: String, subst: Map[String, Type]): Map[String, Type] =
    genericSelf.get(fname).fold(subst)(ref => subst + (selfName -> resolveType(ref, subst)))

  /** Resolves a **result** type, which is the one position `never` may appear in — a function's,
   * a member's, or an `extern`'s declared result, saying that it does not return. Everywhere else
   * a type is resolved through `resolveType`, which rejects it.
   */
  protected def resolveReturn(t: TypeRef, subst: Map[String, Type]): Type = t match
    case NamedType(n, Nil) if n == neverName && !subst.contains(n) => Type.Never
    case _                                                         => resolveType(t, subst)

  private def resolveTypeAt(t: TypeRef, subst: Map[String, Type]): Type = t match
    case PtrType(inner) =>
      traitObject(inner, subst, "*").fold(Type.Ptr(underIndirection(resolveType(inner, subst))))(Type.Ptr.apply)
    case RefType(inner, sync) =>
      traitObject(inner, subst, "&")
        .fold(Type.Ref(underIndirection(resolveType(inner, subst)), sync))(Type.Ref(_, sync))

    // An array holds its elements, so it is no indirection at all and a type cannot contain an
    // array of itself. A slice only points at them, so it breaks a cycle exactly as `*T` does.
    case ArrayType(None, elem) => Type.Slice(underIndirection(resolveType(elem, subst)))
    case ArrayType(Some(len), elem) =>
      val n = len match
        case IntLit(v, _) if v >= 0 && v.isValidInt => v.toInt
        case IntLit(v, _)                           => err(s"an array cannot have $v elements")
        case _ => err("an array length must be an integer literal")
      Type.Array(n, resolveType(elem, subst))

    case NamedType(n, argRefs) =>
      if argRefs.isEmpty && subst.contains(n) then subst(n)
      else
        val targs = argRefs.map(resolveType(_, subst))
        scalarType(n) match
          case Some(s)                        => plain(n, targs, s)
          case None if structDecls.contains(n) => instantiateStruct(n, targs)
          case None if enumDecls.contains(n)   => instantiateEnum(n, targs)
          case None if n == neverName =>
            err("'never' is the type of an expression that does not finish, so it can only be a result type")
          case None if n == selfName =>
            err("'Self' names the type a trait is implemented for, so it is only meaningful inside " +
              "a 'trait' or an 'impl'")
          // A trait describes what a value can do, not how one is laid out, so it stands where a
          // type is asked for only behind a sigil — and the two ways of reaching it are named here,
          // since which one a program wants is the whole of the choice it has to make.
          case None if traitDecls.contains(n) =>
            err(s"'$n' is a trait, so it describes behaviour rather than a layout — write '*$n' or " +
              s"'&$n' for a trait object, or bound a type parameter with '[T: $n]'")
          case None                            => err(s"unknown type '$n'")

  /** A trait named behind a memory-mode sigil, which is what makes the pointer a trait object
   * (`02`): `*Trait` raw and unmanaged, `&Trait` reference-counted. `None` for everything else,
   * including a type parameter that happens to be spelled like a trait — the substitution wins,
   * since that is what shadowing means everywhere else a name is resolved.
   */
  private def traitObject(inner: TypeRef, subst: Map[String, Type], sigil: String): Option[Type.Trait] =
    inner match
      case NamedType(n, Nil) if traitDecls.contains(n) && !subst.contains(n) =>
        at(inner.pos)(checkObjectSafe(n, sigil))
        Some(Type.Trait(n))
      case _ => None

  /** Whether a trait may be erased into an object at all, and what to say when it may not (`02`).
   *
   * An erased value has forgotten its type, so a method may promise nothing that depends on knowing
   * it. `Self` may stand for the receiver and nowhere else: a second `Self` would have to be the
   * same forgotten type as the first, which is exactly the fact a trait object no longer has, and a
   * `Self` result has no size to hand back. That excludes every trait in the operator catalog —
   * `add(self, rhs: Self) -> Self` first among them — which is why those traits are for bounds.
   *
   * An associated function has no receiver to dispatch on. A **property** does — it just never spells
   * one — so a trait that asks for a property is as object-safe as one that asks for a method, and
   * the slot it gets is the same slot. And `&self` asks for its receiver to be inside a
   * reference-counted box, which only the counted object has: `&Trait` carries one, so it accepts
   * such a method, and `*Trait` points straight at a value and does not.
   */
  protected def checkObjectSafe(name: String, sigil: String): Unit = {
    val obj = s"'$sigil$name'"

    def mentionsSelf(t: TypeRef): Boolean = t match
      case NamedType(n, args) => n == selfName || args.exists(mentionsSelf)
      case PtrType(i)         => mentionsSelf(i)
      case RefType(i, _)      => mentionsSelf(i)
      case ArrayType(_, e)    => mentionsSelf(e)

    for m <- traitDecls(name).methods do
      if m.recvMode.isEmpty then
        err(s"'$name' declares the associated function '${m.name}', which has no receiver to " +
          s"dispatch on — so there is no $obj to form")
      if m.receiver.exists(_.isInstanceOf[RecvMode.ByRef]) && sigil == "*" then
        err(s"'${m.name}' of '$name' takes '&self', so it needs its receiver inside a " +
          s"reference-counted box — $obj points straight at a value, so write '&$name' instead")
      if m.params.exists(p => mentionsSelf(p.typ)) || m.retType.exists(mentionsSelf) then
        err(s"'${m.name}' of '$name' mentions 'Self' away from its receiver, and an erased value " +
          s"has forgotten which type that is — so there is no $obj to form")
  }

  /** Resolves the pointee of a `*T` / `&T`, which is one level further from the layout of
   * whatever type is currently being laid out.
   */
  protected def underIndirection(resolve: => Type): Type = {
    indirection += 1
    try resolve
    finally indirection -= 1
  }

  /** Whether reaching an in-progress instantiation again is a legal cycle. It is exactly when
   * at least one indirection was crossed on the way back to it: a `Node` holding a `*Node` is
   * pointer-sized, while a `Node` holding a `Node` by value has no finite size.
   */
  protected def cycleCheck(key: String): Unit =
    if indirection <= resolving(key) then err(s"type '$key' contains itself, so it has no finite size")

  /** Resolves a scalar type name: the named primitives and friendly aliases, or one of the
   * systematic `iN` / `uN` / `fN` width spellings.
   */
  protected def scalarType(name: String): Option[Type] =
    Type.scalars.get(name).orElse(widthType(name))

  /** `i5`, `u12`, `f32` — a family letter followed by a width. The integer family is open,
   * so it is recognised by shape rather than listed; a width the back end cannot lower is a
   * diagnostic, not an unknown name.
   */
  protected def widthType(name: String): Option[Type] = {
    val digits = name.drop(1)

    if name.length < 2 || !"iuf".contains(name.head) then None
    else if digits.head == '0' || !digits.forall(c => c >= '0' && c <= '9') then None
    else
      val bits = digits.toIntOption.getOrElse(err(s"'$name' is far wider than anything can hold"))

      if name.head == 'f' then
        if bits == 16 || bits == 32 || bits == 64 then Some(Type.Floating(bits))
        else if bits == 128 then err("'f128' is not lowered yet — the widest float is 'f64'")
        else err(s"'$name' is not an IEEE floating-point width; they are f16, f32, and f64")
      else if bits >= 1 && bits <= 64 then Some(Type.Integer(bits, signed = name.head == 'i'))
      else err(s"'$name' is wider than the 64 bits the back end lowers")
  }

  protected def plain(name: String, targs: List[Type], ty: Type): Type =
    if targs.nonEmpty then err(s"type '$name' does not take type arguments") else ty

  protected def checkArity(name: String, tparams: List[String], targs: List[Type]): Unit =
    if tparams.length != targs.length then
      if tparams.isEmpty then err(s"type '$name' does not take type arguments")
      else
        err(s"type '$name' takes ${quantity(tparams.length, "type argument")}, " +
          s"but ${supplied(targs.length, "type argument")}")

  /** Instantiates a struct for one set of type arguments, memoized on the display name. The
   * instantiation is registered *before* its fields are resolved, so a field that points back
   * at the struct finds it and the recursion terminates; `cycleCheck` is what rejects the cycle
   * that has no indirection to break it.
   */
  protected def instantiateStruct(name: String, targs: List[Type]): Type.Struct = {
    val decl = structDecls(name)
    checkArity(name, decl.tparams, targs)
    checkTypeBounds(name, decl.tparams, targs)
    val key = Type.qualified(name, targs)

    structInsts.get(key) match
      case Some(s) => s
      case None if inProgress.contains(key) =>
        cycleCheck(key)
        inProgress(key).asInstanceOf[Type.Struct]
      case None =>
        val s = new Type.Struct(name, targs)
        inProgress(key) = s
        resolving(key) = indirection
        val subst = decl.tparams.zip(targs).toMap

        // A field whose type does not resolve is recorded and taken as unknown, so the struct
        // still has the shape the programmer wrote: the right fields, in the right order, with
        // the right count. Abandoning the instantiation instead would leave every later mention
        // of the type reporting something about a struct that never finished being built.
        //
        // The `finally` is what keeps the resolver's own bookkeeping honest whatever happens
        // here: an entry left in `inProgress` would make the next mention of this type look
        // like a cycle, which is a diagnostic about nothing at all.
        try s.fields = decl.fields.map(f => (f.name, recover(Type.Unknown)(resolveType(f.typ, subst))))
        finally
          resolving -= key
          inProgress -= key

        structInsts(key) = s
        s
  }

  /** Instantiates an enum for one set of type arguments. All-dataless variants make a *simple*
   * enum (integer constants, auto-incrementing from an optional explicit `= value`); any
   * data-carrying variant makes a *data* enum, whose variants take sequential tags and whose
   * payload-bearing variants each claim a slot in the aggregate.
   */
  protected def instantiateEnum(name: String, targs: List[Type]): Type.Enum = {
    val decl = enumDecls(name)
    checkArity(name, decl.tparams, targs)
    checkTypeBounds(name, decl.tparams, targs)
    val key = Type.qualified(name, targs)

    enumInsts.get(key) match
      case Some(en) => en
      case None if inProgress.contains(key) =>
        cycleCheck(key)
        inProgress(key).asInstanceOf[Type.Enum]
      case None =>
        val en = new Type.Enum(name, targs)
        en.simple = decl.variants.forall(_.fields.isEmpty)
        inProgress(key) = en
        resolving(key) = indirection

        // The `: iN` annotation pins a simple enum's storage; it is meaningless on a generic or
        // data enum, so those reject it rather than silently ignore it. Where present, every
        // discriminant is range-checked against it, which is the whole point of pinning the type.
        en.underlying = decl.underlying match
          case None => Type.Int
          case Some(ref) =>
            if !en.simple then
              err(s"only a simple enum has an underlying integer type — '$name' carries data")
            resolveType(ref, Map.empty) match
              case i: Type.Integer => i
              case other           => err(s"an enum's underlying type must be an integer, not ${show(other)}")

        val subst    = decl.tparams.zip(targs).toMap
        var nextTag  = 0
        var nextSlot = 1
        try en.variants = decl.variants.map { v =>
          if en.simple then
            def fitting(n: BigInt): Int =
              if !Type.fits(n, en.underlying) then
                err(s"discriminant $n of variant '${v.name}' does not fit ${show(en.underlying)}")
              n.toInt
            val tag = v.value match
              // A discriminant may be negative under a signed underlying type, where the parser
              // gives the literal as a unary negation rather than a signed constant.
              case Some(IntLit(n, _))                     => fitting(n)
              case Some(Unary("-", IntLit(n, _)))         => fitting(-n)
              case Some(_)                                => err(s"the value of variant '${v.name}' must be an integer literal")
              case None                                   => fitting(nextTag)
            nextTag = tag + 1
            Type.EnumVariant(v.name, tag, Nil, None)
          else
            if v.value.isDefined then
              err(s"variant '${v.name}' carries data, so it cannot also have an explicit value")
            val tag    = nextTag; nextTag += 1
            val fields = v.fields.map(f => (f.name, recover(Type.Unknown)(resolveType(f.typ, subst))))
            val slot   = if fields.nonEmpty then { val s = nextSlot; nextSlot += 1; Some(s) } else None
            Type.EnumVariant(v.name, tag, fields, slot)
        }
        finally
          resolving -= key
          inProgress -= key

        enumInsts(key) = en
        en
  }

  /** Matches a declaration's type reference against an actual type, binding whatever type
   * parameters it determines. Deliberately lenient: a structural mismatch simply leaves the
   * parameter unbound, and the argument is type-checked properly against the instantiated
   * signature afterwards, where the message can name both types.
   */
  protected def unify(
      ref: TypeRef,
      actual: Type,
      tparams: Set[String],
      sub: mutable.Map[String, Type],
  ): Unit = ref match
    case PtrType(inner) =>
      actual match
        case Type.Ptr(t) => unify(inner, t, tparams, sub)
        case _           => ()
    // A `&T` parameter also accepts a bare `T`, which the call boxes, so the payload type is
    // matched against either shape.
    case RefType(inner, sync) =>
      actual match
        case Type.Ref(t, s) if s == sync => unify(inner, t, tparams, sub)
        case _: Type.Ref                 => ()
        case t                           => unify(inner, t, tparams, sub)
    case ArrayType(None, elem) =>
      actual match
        case Type.Slice(e) => unify(elem, e, tparams, sub)
        case _             => ()
    case ArrayType(Some(_), elem) =>
      actual match
        case Type.Array(_, e) => unify(elem, e, tparams, sub)
        case _                => ()
    // A trait never binds a type parameter. `f[T](p: *T)` handed a `*Writer` would otherwise
    // instantiate at a type with no layout, and the body could then write `var v: T` for a value
    // that cannot exist; leaving it unsolved reports the inference failure instead.
    case NamedType(n, Nil) if tparams(n) =>
      if !sub.contains(n) && !actual.isInstanceOf[Type.Trait] then sub(n) = actual
    case NamedType(n, argRefs) =>
      actual match
        case s: Type.Struct if s.base == n && s.targs.length == argRefs.length =>
          argRefs.zip(s.targs).foreach { case (r, t) => unify(r, t, tparams, sub) }
        case e: Type.Enum if e.base == n && e.targs.length == argRefs.length =>
          argRefs.zip(e.targs).foreach { case (r, t) => unify(r, t, tparams, sub) }
        case _ => ()

  /** Solves a generic declaration's type arguments from the argument types, falling back to
   * the expected type of the whole expression for parameters the arguments do not determine.
   */
  protected def solve(
      what: String,
      tparams: List[String],
      paramRefs: List[TypeRef],
      argTys: List[Type],
      resultRef: Option[TypeRef],
      expected: Option[Type],
  ): List[Type] = {
    val sub = mutable.LinkedHashMap.empty[String, Type]
    val tps = tparams.toSet

    for (r, t) <- paramRefs.zip(argTys) do unify(r, t, tps, sub)
    if sub.size < tparams.length then
      for r <- resultRef; e <- expected do unify(r, e, tps, sub)

    tparams.map(tp =>
      sub.getOrElse(tp, err(s"cannot infer the type argument '$tp' of '$what' here — annotate the expected type")),
    )
  }

  /** Whether a type has a zero value, which is what a declaration with no initializer starts
   * at. A reference has none — it always points at a live object — and neither does an enum,
   * whose zeroed tag names no variant in particular.
   */
  protected def hasZero(t: Type): Boolean = t match
    case _: Type.Integer | _: Type.Floating | Type.Char | Type.Bool | _: Type.Ptr => true
    // A `va_list` has no meaningful starting value — `va_start` is what makes it usable — but
    // `var ap: va_list` with no initializer is exactly how one is declared, so it zeroes like any
    // other slot and `va_start` overwrites whatever was there.
    case Type.VaList => true
    // A zeroed view owns nothing and names no elements, which is exactly the empty slice — and,
    // for a string, the empty string, which is well-formed UTF-8 the way anything empty is.
    case _: Type.View        => true
    case Type.Array(_, elem) => hasZero(elem)
    case s: Type.Struct      => s.fields.forall(f => hasZero(f._2))
    case _                   => false
}
