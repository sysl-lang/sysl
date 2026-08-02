package io.github.edadma.sysl

import scala.collection.mutable

/** The generic machinery: building a concrete `Type` from a name and its type arguments, and
 * solving those arguments back out of a call's argument types.
 *
 * The two directions are one subject. Instantiation is the forward direction — given `Buf` and
 * `[u8]`, lay out the struct with `T` bound to `u8` — and unification is the inverse, recovering the
 * binding from what a call was actually handed so the forward direction can then run. Everything
 * that makes the forward direction correct is what makes the inverse decidable, which is why they
 * are read together rather than a file apart.
 *
 * An instantiation is registered **before** its fields are resolved, so a type that reaches itself
 * through a `*T` / `&T` / `[]T` finds the in-progress object and the recursion terminates. That is
 * also why a cycle with no indirection to break it is a separate check rather than a stack overflow.
 */
trait GenericInstantiation extends ConstFolding {

  /** Two answers about a generic declaration that `TypeResolution` gives, and which the arity check
   * below needs before that trait is mixed in: how few arguments a parameter list will accept once
   * its defaults are counted, and whether a type reached itself with no indirection to break it.
   */
  protected def leastArgs(tparams: List[String], tdefaults: Map[String, TypeRef]): Int
  protected def cycleCheck(key: String): Unit

  /** How an arity is described when it is wrong -- "1 type argument", "between 1 and 3". Shared with
   * the trait-arity check, which asks the same question of a trait's parameter list.
   */
  protected def arityPhrase(tparams: List[String], tdefaults: Map[String, TypeRef]): String = {
    val least = leastArgs(tparams, tdefaults)

    if least == tparams.length then s"takes ${quantity(tparams.length, "type argument")}"
    else s"takes between $least and ${tparams.length} type arguments"
  }

  /** Resolves a scalar type name: the named primitives and friendly aliases, or one of the
   * systematic `iN` / `uN` / `fN` width spellings.
   */
  protected def scalarType(name: String): Option[Type] =
    Type.scalars.get(name).orElse(widthType(name))

  /** `i5`, `u12`, `f32` — a family letter followed by a width. The integer family is open,
   * so it is recognised by shape rather than listed; a width the back end cannot lower is a
   * diagnostic, not an unknown name.
   *
   * **The integer limit is LLVM's own, `2^23 - 1`**, because the two reasons this once stopped at
   * 128 were both checked and neither survived:
   *
   *   - *"a division at a wider width has no compiler-rt routine behind it"* — it needs none.
   *     `udiv i256`, `udiv i1024` and `mul i8192` all compile with **no undefined symbols at all**;
   *     the back end expands a wide division inline rather than calling out to one.
   *   - *"the decimal rendering is written once at the widest width there is"* — it is written
   *     **per width**, on demand: `ScalarEmitter` asks `StringEmitter.intName(bits)` for a renderer
   *     and gets one generated at that width, with its buffer sized from the width itself.
   *
   * So the ceiling is the back end's and nothing else's. It is still not a statement about what
   * `00 §5` permits — the maximum the *language* allows is that chapter's open question, and a
   * width this large is a thing the machine can hold rather than a thing a program should want.
   *
   * **Two known costs at the extreme, accepted deliberately rather than guarded against.** A width
   * near the ceiling makes `digitCapacity` evaluate `2^bits` as a `BigInt` and take its decimal
   * length — millions of digits, at compile time, once per width a program actually renders. And a
   * value that wide is a megabyte travelling by value. Neither is reachable by accident: nothing
   * writes `i8388607` without meaning to.
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
      else if bits >= 1 && bits <= Type.MaxIntegerBits then
        Some(Type.Integer(bits, signed = name.head == 'i'))
      else err(s"'$name' is wider than the ${Type.MaxIntegerBits} bits the back end lowers")
  }

  protected def plain(name: String, targs: List[Type], ty: Type): Type =
    if targs.nonEmpty then err(s"type '$name' does not take type arguments") else ty

  protected def checkArity(
      name: String,
      tparams: List[String],
      tdefaults: Map[String, TypeRef],
      targs: List[Type],
  ): Unit =
    if targs.length > tparams.length || targs.length < leastArgs(tparams, tdefaults) then
      if tparams.isEmpty then err(s"type '${qn(name)}' does not take type arguments")
      else
        err(s"type '${qn(name)}' ${arityPhrase(tparams, tdefaults)}, " +
          s"but ${supplied(targs.length, "type argument")}")

  /** Instantiates a struct for one set of type arguments, memoized on the display name. The
   * instantiation is registered *before* its fields are resolved, so a field that points back
   * at the struct finds it and the recursion terminates; `cycleCheck` is what rejects the cycle
   * that has no indirection to break it.
   */
  protected def instantiateStruct(name: String, targs: List[Type]): Type.Struct =
    inDecl(name)(instantiateStructIn(name, targs))

  private def instantiateStructIn(name: String, written: List[Type]): Type.Struct = {
    if brokenDecls(name) then poisoned()

    val decl = structDecls(name)
    checkArity(name, decl.tparams, decl.tdefaults, written)

    // Filled before anything is keyed on it, so `Buf[int]` and `Buf[int, Heap]` are one
    // instantiation rather than two that happen to have the same fields.
    val targs = withDefaults(name, decl.tparams, decl.tdefaults, written, Map.empty)

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
        try s.fields = decl.fields.map(f => (f.name, recover(Type.Unknown)(resolveQualified(f.typ, subst))))
        finally
          resolving -= key
          inProgress -= key

        structInsts(key) = s
        s
  }

  /** The one canonical tuple over these parts, registered so codegen lays its aggregate down.
   *
   * It is memoized beside the struct instantiations because it *is* one — `(int, string)` written
   * twice is one type, for the same reason `Box[int]` written twice is, and the field list is a
   * consequence of the parts rather than something a second instantiation could disagree about.
   */
  protected def tupleType(parts: List[Type]): Type.Tuple =
    // A tuple over a type **parameter** is not registered, and that is not an optimization. The key
    // is the type's spelling, and a parameter spells the same whatever an `impl` asked of it — so a
    // registered `(A, B)` would be handed back to the next block that wrote one, carrying the first
    // block's bounds. Nothing lays a value out at one of these, so nothing needs it kept.
    if parts.exists(Type.mentionsAbstract) then new Type.Tuple(parts)
    else
      val key = Type.qualified(Type.Tuple.base(parts.length), parts)

      structInsts.get(key) match
        case Some(t: Type.Tuple) => t
        case _ =>
          val t = new Type.Tuple(parts)

          structInsts(key) = t
          t

  /** A type with type parameters replaced by what a particular instantiation was made with — the
   * substitution `resolveType` performs on a *reference*, performed on a type that is already
   * resolved.
   *
   * It exists for one question: what an `impl` written with its own parameters promises about a
   * subject those parameters are bound in. `impl[T] Index[usize, T] for Buf[T]` stored `T` as a
   * trait argument, and asking what a `Buf[int]` implements means putting `int` there. Nothing
   * unresolved is reachable from here, so this is a walk rather than a resolution — a named type is
   * rebuilt through its instantiator so the result is the one canonical instantiation, and anything
   * with no parameter inside it comes back as itself.
   */
  protected def substParams(t: Type, subst: Map[String, Type]): Type =
    if subst.isEmpty then t
    else
      t match
        case a: Type.Abstract => subst.getOrElse(a.name, a)
        case n: Type.Named if n.targs.isEmpty => n
        case t: Type.Tuple    => tupleType(t.targs.map(substParams(_, subst)))
        case n: Type.Struct   => instantiateStruct(n.base, n.targs.map(substParams(_, subst)))
        case n: Type.Enum     => instantiateEnum(n.base, n.targs.map(substParams(_, subst)))
        case Type.Ptr(inner)      => Type.Ptr(substParams(inner, subst))
        case Type.Ref(inner, syn) => Type.Ref(substParams(inner, subst), syn)
        case Type.Weak(inner)     => Type.Weak(substParams(inner, subst))
        case Type.Array(n, elem)  => Type.Array(n, substParams(elem, subst))
        case Type.Slice(elem, ro) => Type.Slice(substParams(elem, subst), ro)
        case Type.CFn(ps, r)      => Type.CFn(ps.map(substParams(_, subst)), substParams(r, subst))
        case other                => other

  /** Instantiates an enum for one set of type arguments. All-dataless variants make a *simple*
   * enum (integer constants, auto-incrementing from an optional explicit `= value`); any
   * data-carrying variant makes a *data* enum, whose variants take sequential tags and whose
   * payload-bearing variants each claim a slot in the aggregate.
   */
  protected def instantiateEnum(name: String, targs: List[Type]): Type.Enum =
    inDecl(name)(instantiateEnumIn(name, targs))

  private def instantiateEnumIn(name: String, written: List[Type]): Type.Enum = {
    if brokenDecls(name) then poisoned()

    val decl = enumDecls(name)
    checkArity(name, decl.tparams, decl.tdefaults, written)

    val targs = withDefaults(name, decl.tparams, decl.tdefaults, written, Map.empty)

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
              err(s"only a simple enum has an underlying integer type — '${qn(name)}' carries data")
            resolveType(ref, Map.empty) match
              case i: Type.Integer => i
              case other           => err(s"an enum's underlying type must be an integer, not ${show(other)}")

        val subst    = decl.tparams.zip(targs).toMap
        var nextTag = 0
        try
          en.variants = decl.variants.map { v =>
            if en.simple then
              def fitting(n: BigInt): Int =
                if !Type.fits(n, en.underlying) then
                  err(s"discriminant $n of variant '${v.name}' does not fit ${show(en.underlying)}")
                n.toInt
              val tag = v.value match
                // A discriminant is any compile-time integer, so a negative one under a signed
                // underlying type folds from the unary negation the parser gives, and a `const` may
                // stand where a literal does (`13 §7`).
                case Some(e) =>
                  fitting(constInt(e).getOrElse(
                    err(s"the value of variant '${v.name}' must be a constant integer")))
                case None => fitting(nextTag)
              nextTag = tag + 1
              Type.EnumVariant(v.name, tag, Nil, false)
            else
              if v.value.isDefined then
                err(s"variant '${v.name}' carries data, so it cannot also have an explicit value")
              val tag    = nextTag; nextTag += 1
              val fields = v.fields.map(f => (f.name, recover(Type.Unknown)(resolveType(f.typ, subst))))
              Type.EnumVariant(v.name, tag, fields, fields.nonEmpty)
          }

          // A simple enum's value *is* its identity — there is nothing else to tell two variants
          // apart — so two names for one value leave the language unable to keep its own promises:
          // `Pos` and `Val` stop being inverses, the second variant's `match` arm can never run,
          // and `Image` lowers to a switch with a repeated case that the assembler rejects outright.
          // A data enum cannot reach this, since its tags are assigned in order and it refuses an
          // explicit value. Naming a value twice on purpose is what a `const` is for.
          if en.simple then
            val seen = mutable.Map.empty[Int, String]
            for v <- en.variants do
              seen.get(v.tag) match
                case Some(first) =>
                  err(
                    s"variants '$first' and '${v.name}' both stand for ${v.tag}, and a simple enum's "
                      + "values are the whole of what tells its variants apart — a second name for one "
                      + "value is a 'const'",
                  )
                case None => seen(v.tag) = v.name
        catch
          // Nothing a *simple* enum's variants read depends on the type arguments: a discriminant is
          // a constant and the underlying type is fixed at the declaration. So a mistake found here
          // belongs to the declaration rather than to this instantiation of it, and marking it as
          // such is what keeps a generic one — which has no eager instantiation to be judged at —
          // from repeating the same complaint at every use.
          //
          // Only a pass that *reports* may mark: the definition-time walk of a generic body raises
          // errors and drops them (`14 §4`), so marking from there would retire the declaration
          // before anything had told the reader about it.
          case e: AnalyzerError if en.simple && !abstractPass =>
            brokenDecls += name
            throw e
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
    // A `weak T` parameter also accepts a `&T`, which the call weakens, so the payload type is
    // matched against either shape — the same leniency `&T` gets one case up, for the same reason.
    case WeakType(inner) =>
      actual match
        case Type.Weak(t)  => unify(inner, t, tparams, sub)
        case Type.Ref(t, _) => unify(inner, t, tparams, sub)
        case _             => ()
    // Whether the view refuses writes is not matched here, for the reason `&T` and `weak T` above
    // do not match their own modes: this is where a type *parameter* is read off an argument, and
    // whether the argument may stand in the parameter's place is the coercion's question. Refusing
    // here would report a missing instantiation for what is really a write into read-only elements.
    case ArrayType(None, elem, _) =>
      actual match
        case Type.Slice(e, _) => unify(elem, e, tparams, sub)
        case _                => ()
    case ArrayType(Some(_), elem, _) =>
      actual match
        case Type.Array(_, e) => unify(elem, e, tparams, sub)
        case _                => ()
    case VolatileType(inner) => unify(inner, Type.unqualified(actual), tparams, sub)
    case TupleType(parts, _) =>
      actual match
        case t: Type.Tuple if t.targs.length == parts.length =>
          parts.zip(t.targs).foreach { case (r, a) => unify(r, a, tparams, sub) }
        case _ => ()
    // A callable's parameters and result are matched through the trait they name, so a `&Fn(A) -> R`
    // parameter binds `A` and `R` from a `&Fn(int) -> bool` argument exactly as any other applied
    // trait binds its arguments. A closure's own type is a struct that says nothing about either, so
    // what settles a *bare* arrow's parameters is the call's own inference and not this.
    case f: FnType => unify(f.asTrait, actual, tparams, sub)
    // A function pointer's parts bind exactly as a tuple's do — position by position, and only
    // against another one of the same width, since nothing else has parts to read.
    case CFnType(ps, r) =>
      actual match
        case Type.CFn(as, ar) if as.length == ps.length =>
          ps.zip(as).foreach((pr, a) => unify(pr, a, tparams, sub))
          unify(r, ar, tparams, sub)
        case _ => ()
    // A trait never binds a type parameter. `f[T](p: *T)` handed a `*Writer` would otherwise
    // instantiate at a type with no layout, and the body could then write `var v: T` for a value
    // that cannot exist; leaving it unsolved reports the inference failure instead.
    // A qualifier is dropped on the way into a parameter, so `f[T](xs: []T)` handed a
    // `[]volatile u32` solves `T` as `u32` — and then the argument does not agree with the `[]u32`
    // that instantiation asks for, which is the message worth reading. Binding `T` to the qualified
    // type instead would let a generic body promise accesses it cannot promise: the loads and stores
    // it emits are its own, not the ones the caller wrote (`03 § Device memory`).
    case NamedType(n, Nil) if tparams(n) =>
      if !sub.contains(n) && !actual.isInstanceOf[Type.Trait] then sub(n) = Type.unqualified(actual)
    // The reference is the declaration's, written in the declaration's terms, so the name it uses
    // is matched against the resolved type by the key it names here — a `Pair[T]` parameter is its
    // own module's `Pair`, whichever module the call that supplied the argument was written in.
    case NamedType(n, argRefs) =>
      val key = typeKey(n)

      actual match
        case s: Type.Struct if key.contains(s.base) && s.targs.length == argRefs.length =>
          argRefs.zip(s.targs).foreach { case (r, t) => unify(r, t, tparams, sub) }
        case e: Type.Enum if key.contains(e.base) && e.targs.length == argRefs.length =>
          argRefs.zip(e.targs).foreach { case (r, t) => unify(r, t, tparams, sub) }
        // A trait applied to arguments binds them the way a generic type does — what a trait cannot
        // do is bind a parameter to *itself*, which is the case above.
        case t: Type.Trait if traitKey(n).contains(t.name) && t.args.length == argRefs.length =>
          argRefs.zip(t.args).foreach { case (r, a) => unify(r, a, tparams, sub) }
        case _ => ()

  /** Solves a generic declaration's type arguments from the argument types, falling back to
   * the expected type of the whole expression for parameters the arguments do not determine.
   *
   * `soft` marks the arguments that are bare literals, and they are consulted **last**. A literal
   * has no type of its own (`01`) — it takes one from where it appears — so it is the weakest thing
   * in the room to conclude a type parameter from, and letting it go first is what made
   * `pick(1, 2, 250u8)` fix `T = int` and then reject the argument that actually knew. The order is
   * the operand rule's, one level up: what is already a type settles the parameter, and the
   * literals take what it settled to. They are still consulted, because with nothing else to go on
   * `id(7)` must remain an `int` rather than an inference failure.
   */
  protected def solve(
      what: String,
      tparams: List[String],
      paramRefs: List[TypeRef],
      argTys: List[Type],
      resultRef: Option[TypeRef],
      expected: Option[Type],
      soft: List[Boolean] = Nil,
  ): List[Type] = {
    val sub   = mutable.LinkedHashMap.empty[String, Type]
    val tps   = tparams.toSet
    val pairs = paramRefs.zip(argTys).zip(soft.padTo(paramRefs.length, false))

    for ((r, t), adaptable) <- pairs if !adaptable do unify(r, t, tps, sub)
    if sub.size < tparams.length then
      for r <- resultRef; e <- expected do unify(r, e, tps, sub)
    if sub.size < tparams.length then
      for ((r, t), adaptable) <- pairs if adaptable do unify(r, t, tps, sub)

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
    // A weak reference that never had an object and one whose object is gone are the same state,
    // and a program can already reach the second — so the zeroed slot is not a value it would
    // otherwise be spared, it is the value `None` writes and `get()` reads back (`03`). This is the
    // one place `weak T` parts company with `&T`, which has no zero because there is no such thing
    // as a reference to nothing.
    case _: Type.Weak        => true
    // A zero-sized type has exactly one value, so it is trivially its own zero — there is nothing
    // to produce and nowhere to put it. Without this a struct would lose its zero value by gaining
    // a field that costs nothing, which is the opposite of what zero-sized means.
    case t if Type.zeroSized(t) => true
    case Type.Array(_, elem)    => hasZero(elem)
    case s: Type.Struct         => s.fields.forall(f => hasZero(f._2))
    case _                      => false
}
