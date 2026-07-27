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
trait TypeResolution extends ImportResolution {

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

  /** The name of the type an expression run only for its effect has. It *is* a scalar, because a
   * function may declare it as its result and a block may have it, but it is no more a layout than
   * `never` is: its one value occupies nothing, so there is nothing for a parameter, a field, an
   * element, or a type argument to hold.
   */
  protected val unitName = "unit"

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
    genericOuter.getOrElse(fname, Map.empty) ++
      genericSelf.get(fname).fold(subst) { (ref, scope) =>
        // Read where the subject was written, which for an inherited default is the `impl` block
        // rather than the trait the rest of the declaration came from. The substitution itself is
        // resolved types and means the same thing anywhere.
        subst + (selfName -> inScope(scope)(resolveType(ref, subst)))
      }

  /** Rewrites `Self` in a written type reference to the reference it stands for.
   *
   * `withSelf` above is the same idea one stage later, on resolved types; this one is needed where
   * a signature is read *before* anything is resolved — inferring a generic associated function's
   * type arguments, where `-> Self` has to be seen as the `Box[T]` it means for `T` to be solved
   * from the expected type. Everywhere else the resolved binding is enough.
   */
  protected def spellSelf(ref: TypeRef, selfRef: TypeRef): TypeRef = ref match
    case NamedType(n, Nil) if n == selfName => selfRef
    case NamedType(n, args)                 => NamedType(n, args.map(spellSelf(_, selfRef)))
    case PtrType(inner)                     => PtrType(spellSelf(inner, selfRef))
    case RefType(inner, sync)               => RefType(spellSelf(inner, selfRef), sync)
    case ArrayType(len, elem)               => ArrayType(len, spellSelf(elem, selfRef))

  /** Resolves one bound — a trait, with whatever arguments it was applied to — under the
   * substitution the declaration that wrote it is being read at.
   *
   * A bound is resolved late for the same reason a signature is: its arguments may name the
   * parameters of the declaration it belongs to, so `[T: From[U], U]` is a different promise at
   * every call, and the substitution that fixes `U` is what says which.
   *
   * What the trait asks of its *own* parameters is checked here too, since a bound is one of the
   * three places a trait is applied — the others being an `impl` and a trait object.
   */
  protected def resolveBound(b: BoundRef, subst: Map[String, Type]): Type.Bound = at(b.pos) {
    val args = b.args.map(resolveType(_, subst))
    val key  = traitKey(b.name)

    for k <- key; decl <- traitDecls.get(k) do
      checkTraitArity(b.name, decl.tparams, args)
      deferredBounds(k, decl.tparams, decl.bounds, args)

    // A bound naming no trait at all keeps the name as written, so `checkBoundNames` reports it in
    // the words it was spelled with rather than this producing a second complaint about a key.
    Type.Bound(key.getOrElse(b.name), args)
  }

  /** A trait applied to the wrong number of arguments, said in the words a trait deserves — the
   * type-level message would send the reader looking for a struct of that name.
   */
  protected def checkTraitArity(name: String, tparams: List[String], targs: List[Type]): Unit =
    if tparams.length != targs.length then
      if tparams.isEmpty then err(s"trait '$name' does not take type arguments")
      else
        err(s"trait '$name' takes ${quantity(tparams.length, "type argument")}, " +
          s"but ${supplied(targs.length, "type argument")}")

  /** A declaration's type parameters as its own body sees them: each standing in for itself, and
   * carrying what the declaration asked of it (`14 §4`).
   *
   * The bounds are resolved with the *siblings* standing in for themselves too, which is what lets
   * `f[T: Iter[U], U: Display]` know that what `T`'s iterator yields is something printable. A bound
   * that reaches back around to the parameter it belongs to is broken by dropping that parameter's
   * own bounds one level in — the promise stays, and only the walk stops.
   */
  protected def abstractSubst(tparams: List[String], bounds: Map[String, List[BoundRef]]): Map[String, Type] = {
    def build(tp: String, seen: Set[String]): Type.Abstract =
      Type.Abstract(
        tp,
        if seen(tp) then Nil
        else
          val inner: Map[String, Type] = tparams.map(p => p -> build(p, seen + tp)).toMap
          bounds.getOrElse(tp, Nil).map(b => recorded(Type.Bound(b.name, Nil))(resolveBound(b, inner))),
      )

    tparams.map(tp => tp -> build(tp, Set.empty)).toMap
  }

  /** Resolves a **result** type, which is the one position the two valueless types may appear in —
   * a function's, a member's, or an `extern`'s declared result, saying that it hands back nothing
   * (`unit`) or does not return at all (`never`). Everywhere else a type is resolved through
   * `resolveType`, which rejects both.
   */
  protected def resolveReturn(t: TypeRef, subst: Map[String, Type]): Type = t match
    case NamedType(n, Nil) if n == neverName && !subst.contains(n) => Type.Never
    case NamedType(n, Nil) if n == unitName && !subst.contains(n)  => Type.Unit
    case _                                                         => resolveType(t, subst)

  private def resolveTypeAt(t: TypeRef, subst: Map[String, Type]): Type = t match
    case PtrType(inner) =>
      traitObject(inner, subst, "*")
        .fold(Type.Ptr(addressable(underIndirection(resolveType(inner, subst)), "'*'")))(Type.Ptr.apply)
    case RefType(inner, sync) =>
      traitObject(inner, subst, "&")
        .fold(Type.Ref(addressable(underIndirection(resolveType(inner, subst)), "'&'"), sync))(Type.Ref(_, sync))

    // An array holds its elements, so it is no indirection at all and a type cannot contain an
    // array of itself. A slice only points at them, so it breaks a cycle exactly as `*T` does.
    case ArrayType(None, elem) => Type.Slice(addressable(underIndirection(resolveType(elem, subst)), "a slice"))
    // A bound is a compile-time constant, which a `const` is and a call is not (`13 §7`).
    case ArrayType(Some(len), elem) =>
      val n = constInt(len) match
        case Some(v) if v >= 0 && v.isValidInt => v.toInt
        case Some(v)                           => err(s"an array cannot have $v elements")
        case None => err("an array length must be a constant — a literal, or a 'const' naming one")
      Type.Array(n, addressable(resolveType(elem, subst), "an array"))

    case NamedType(n, argRefs) =>
      if argRefs.isEmpty && subst.contains(n) then subst(n)
      else
        val targs = argRefs.map(resolveType(_, subst))
        scalarType(n) match
          case Some(s) => plain(n, targs, s)
          // A declared type is named in this module's terms — its own, or a module's it names in
          // full (`13 §3`) — so what the tables are asked for is the key that resolves to.
          case None =>
            typeKey(n) match
              case Some(key) if structDecls.contains(key) => instantiateStruct(key, targs)
              case Some(key) if constrainedDecls.contains(key) =>
                if targs.nonEmpty then err(s"'$n' is a constrained subtype and takes no type arguments")
                resolveConstrained(key)
              case Some(key)                              => instantiateEnum(key, targs)
              case None if n == neverName =>
                err("'never' is the type of an expression that does not finish, so it can only be a result type")
              case None if n == selfName =>
                err("'Self' names the type a trait is implemented for, so it is only meaningful inside " +
                  "a 'trait' or an 'impl'")
              // A trait describes what a value can do, not how one is laid out, so it stands where a
              // type is asked for only behind a sigil — and the two ways of reaching it are named here,
              // since which one a program wants is the whole of the choice it has to make.
              case None if traitKey(n).isDefined =>
                err(s"'$n' is a trait, so it describes behaviour rather than a layout — write '*$n' or " +
                  s"'&$n' for a trait object, or bound a type parameter with '[T: $n]'")
              case None => err(s"unknown type '$n'")

  /** The `Type.Constrained` a subtype name stands for, built once and cached. Building resolves the
   * base, evaluates the `within` bounds to constants, and validates them — an out-of-range or
   * inverted bound is caught here, at the declaration, rather than at any use.
   */
  protected def resolveConstrained(key: String): Type.Constrained =
    constrainedInsts.getOrElseUpdate(key, buildConstrained(key))

  private def buildConstrained(key: String): Type.Constrained = {
    val d = constrainedDecls(key)

    at(d.pos) {
      val base = resolveType(d.base, Map.empty)

      val scalar = Type.underlying(base) match
        case _: Type.Integer | _: Type.Floating | Type.Char => true
        case _                                              => false
      if !scalar then
        err(s"a constrained subtype's base must be an integer, a float, or 'char', not ${show(base)}")

      val (lo, hi) = d.range match
        case Some(r) =>
          val loV = boundValue(r.lo, base)
          val hiV = boundValue(r.hi, base)
          val ordered = if r.exclusiveHi then loV < hiV else loV <= hiV
          if !ordered then
            err(s"the lower bound of '${qn(key)}' is above its upper bound")
          (Some(loV), Some(hiV))
        case None => (None, None)

      // A transparent subtype with neither a range nor a predicate would be a plain alias, which is
      // not a form this cut accepts; `new` alone is enough, since it still changes the type's identity.
      if lo.isEmpty && d.pred.isEmpty && !d.derived then
        err(s"'${qn(key)}' has no constraint — add a 'within' range or a 'where' predicate, or 'new' to " +
          "make it a distinct type")

      if d.pred.isDefined then err("'where' predicates are not supported yet")
      if d.derived then err("'new' derived types are not supported yet")

      Type.Constrained(key, base, d.derived, lo, hi, d.range.exists(_.exclusiveHi), predFn = None)
    }
  }

  /** One `within` bound as a constant, checked against the base's kind: a `char` base takes a
   * character literal, an integer base an integer literal that fits its width, a float base any
   * numeric literal. A literal of the wrong kind, or an integer out of the base's range, is an error.
   */
  private def boundValue(e: Expr, base: Type): BigDecimal =
    Type.underlying(base) match
      case Type.Char =>
        e match
          case CharLit(cp) => BigDecimal(cp)
          case _           => err(s"a 'char' subtype needs character-literal bounds, not ${boundKind(e)}")
      case i: Type.Integer =>
        val v = e match
          case IntLit(n, _)             => n
          case Unary("-", IntLit(n, _)) => -n
          case _                        => err(s"an integer subtype needs integer-literal bounds, not ${boundKind(e)}")
        if !Type.fits(v, i) then err(s"the bound $v does not fit ${show(base)}")
        BigDecimal(v)
      case _ =>
        e match
          case IntLit(n, _)               => BigDecimal(n)
          case Unary("-", IntLit(n, _))   => BigDecimal(-n)
          case FloatLit(t, _)             => BigDecimal(t)
          case Unary("-", FloatLit(t, _)) => -BigDecimal(t)
          case _                          => err(s"a floating-point subtype needs numeric-literal bounds, not ${boundKind(e)}")

  private def boundKind(e: Expr): String = e match
    case _: CharLit                      => "a character"
    case _: FloatLit                     => "a floating-point literal"
    case Unary("-", _: FloatLit)         => "a floating-point literal"
    case _                               => "an integer"

  /** A trait named behind a memory-mode sigil, which is what makes the pointer a trait object
   * (`02`): `*Trait` raw and unmanaged, `&Trait` reference-counted. `None` for everything else,
   * including a type parameter that happens to be spelled like a trait — the substitution wins,
   * since that is what shadowing means everywhere else a name is resolved.
   */
  private def traitObject(inner: TypeRef, subst: Map[String, Type], sigil: String): Option[Type.Trait] =
    inner match
      case NamedType(n, argRefs) if traitKey(n).isDefined && !(argRefs.isEmpty && subst.contains(n)) =>
        val key  = traitKey(n).get
        val decl = traitDecls(key)
        val args = argRefs.map(resolveType(_, subst))

        at(inner.pos) {
          checkTraitArity(n, decl.tparams, args)
          deferredBounds(key, decl.tparams, decl.bounds, args)
          checkObjectSafe(key, args, sigil)
        }
        Some(Type.Trait(key, args))
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
  protected def checkObjectSafe(name: String, args: List[Type], sigil: String): Unit = {
    val shown = qn(name)
    val obj   = s"'$sigil${Type.qualified(shown, args)}'"

    def mentionsSelf(t: TypeRef): Boolean = t match
      case NamedType(n, args) => n == selfName || args.exists(mentionsSelf)
      case PtrType(i)         => mentionsSelf(i)
      case RefType(i, _)      => mentionsSelf(i)
      case ArrayType(_, e)    => mentionsSelf(e)

    for m <- traitDecls(name).methods do
      if m.recvMode.isEmpty then
        err(s"'$shown' declares the associated function '${m.name}', which has no receiver to " +
          s"dispatch on — so there is no $obj to form")
      if m.receiver.exists(_.isInstanceOf[RecvMode.ByRef]) && sigil == "*" then
        err(s"'${m.name}' of '$shown' takes '&self', so it needs its receiver inside a " +
          s"reference-counted box — $obj points straight at a value, so write '&$shown' instead")
      if m.params.exists(p => mentionsSelf(p.typ)) || m.retType.exists(mentionsSelf) then
        err(s"'${m.name}' of '$shown' mentions 'Self' away from its receiver, and an erased value " +
          s"has forgotten which type that is — so there is no $obj to form")
  }

  /** Resolves the pointee of a `*T` / `&T`, which is one level further from the layout of
   * whatever type is currently being laid out.
   */
  /** Holds a type to being one that can be *pointed at* or laid out in a row.
   *
   * A zero-sized type may be a field, a parameter, or a binding — none of those needs an address —
   * but a `&T`, a `*T`, a slice, and an array all reach their contents through one, and there is
   * nothing to reach. An array is the sharper case: every element would be at the same address, so
   * a bounds check would be the only thing `a[i]` did.
   */
  protected def addressable(t: Type, what: String): Type =
    if Type.zeroSized(t) then
      err(s"${show(t)} occupies no storage, so there is nothing for $what to point at")
    else t

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
    if indirection <= resolving(key) then err(s"type '${qn(key)}' contains itself, so it has no finite size")

  // --- constants -------------------------------------------------------------------------

  /** The key a written **constant** name resolves to (`13 §7`). */
  protected def constKey(written: String): Option[String] = resolveName(written)(constDecls.contains)

  /** The type a constant was declared with.
   *
   * A constant is a scalar and nothing else, which is not an arbitrary restriction but the shape of
   * what a constant expression can produce: there is no aggregate literal to fold to, and a table
   * would be storage rather than a value (`13 §7`). Resolving it needs none of the type tables,
   * which is what lets a constant be registered in the first hoisting pass and named from an array
   * bound in the second.
   */
  protected def constType(key: String): Type = constTypes.getOrElseUpdate(key, {
    val decl = constDecls(key)

    inDecl(key)(resolveType(decl.typ, Map.empty)) match
      case t @ (_: Type.Integer | _: Type.Floating | Type.Bool | Type.Char | Type.Str) => t
      case other =>
        at(decl.pos)(err(s"a constant is a scalar, and ${show(other)} is not — '${qn(key)}'"))
  })

  /** A constant's value, as the literal every use of it is folded to.
   *
   * Memoized, and guarded against a constant defined in terms of itself. The cycle is reported once,
   * at whichever of them the walk reached first, naming the loop in the order it was followed —
   * which is the same account `13 §6` gives of a cycle between modules.
   */
  protected def constLiteral(key: String): Expr = constLits.getOrElseUpdate(key, {
    val decl = constDecls(key)

    if constsInProgress(key) then
      val loop = constsInProgress.dropWhile(_ != key).map(qn).mkString(" → ")

      at(decl.pos)(err(s"constant '${qn(key)}' is defined in terms of itself: $loop → ${qn(key)}"))

    constsInProgress += key
    try
      val value = inDecl(key)(fold(decl.value).getOrElse(
        at(decl.value.pos)(err(s"the value of '${qn(key)}' is not a constant expression"))))

      checkFits(value, constType(key), s"'${qn(key)}'", decl.pos)
      value
    finally constsInProgress -= key
  })

  /** Whether a folded value fits the type it was declared at. A constant is written with its type
   * (`13 §7`), so this is the one place the two meet, and a value that does not fit is the mistake
   * a suffix-less literal would otherwise make silently.
   */
  private def checkFits(value: Expr, ty: Type, what: String, pos: Option[Pos]): Unit = (value, ty) match
    case (IntLit(v, _), i: Type.Integer) if !Type.fits(v, i) => at(pos)(err(s"$what does not fit ${show(i)}: $v"))
    case (IntLit(_, _), _: Type.Integer)                     => ()
    case (FloatLit(_, _), _: Type.Floating)                  => ()
    case (BoolLit(_), Type.Bool)                             => ()
    case (CharLit(_), Type.Char)                             => ()
    case (StrLit(_), Type.Str)                               => ()
    case _ => at(pos)(err(s"$what is declared ${show(ty)} but its value is ${literalKind(value)}"))

  private def literalKind(e: Expr): String = e match
    case _: IntLit   => "an integer"
    case _: FloatLit => "a float"
    case _: BoolLit  => "a boolean"
    case _: CharLit  => "a character"
    case _: StrLit   => "a string"
    case _           => "not a constant"

  /** A compile-time integer, for the two positions where a literal was previously the only thing
   * accepted: an array bound and an enum discriminant (`13 §7`).
   */
  protected def constInt(e: Expr): Option[BigInt] = fold(e).collect { case IntLit(v, _) => v }

  // --- module-level `val`s ---------------------------------------------------------------

  /** The key a written module-level **`val`** name resolves to. */
  protected def valKey(written: String): Option[String] = resolveName(written)(valDecls.contains)

  /** The type a module-level `val` was declared with.
   *
   * Written rather than inferred (`13 §2`), which is what lets this be answered without looking at
   * the initializer — so one `val` may be read from another's neighbourhood with no ordering
   * between them, exactly as two functions may call each other.
   */
  protected def valType(key: String): Type = valTypes.getOrElseUpdate(key, {
    val decl = valDecls(key)

    inDecl(key)(decl.typ.map(resolveType(_, Map.empty)).getOrElse(Type.Unknown))
  })

  /** Folds a constant expression to the literal it denotes, or `None` where it is not one.
   *
   * The set is deliberately small and closed: literals, other constants, conversions, and the
   * unary and binary operators. There are no calls — a call in a constant expression is a request
   * for compile-time evaluation of arbitrary code, which is a language of its own — and a `string`
   * folds only from a literal, since `+` on strings allocates and a compile-time concatenation
   * would be a different operation wearing the same spelling.
   */
  protected def fold(e: Expr): Option[Expr] = e match
    case l: IntLit   => Some(l.copy(suffix = None))
    case l: FloatLit => Some(l.copy(suffix = None))
    case l: BoolLit  => Some(l)
    case l: CharLit  => Some(l)
    case l: StrLit   => Some(l)

    case Ident(n) => constKey(n).map(k => constLiteral(k))

    case Unary("-", operand) =>
      fold(operand).collect {
        case IntLit(v, _)   => IntLit(-v, None)
        case FloatLit(t, _) => FloatLit((-t.toDouble).toString, None)
      }
    case Unary("!", operand) => fold(operand).collect { case BoolLit(b) => BoolLit(!b) }
    case Unary("~", operand) => fold(operand).collect { case IntLit(v, _) => IntLit(~v, None) }

    // A conversion is written, so what it does at compile time is what it does at run time: a
    // narrowing wraps and a float-to-integer truncates toward zero (`01`). Silently doing something
    // gentler here would make a constant mean one thing and the same expression written out mean
    // another.
    case Call(Ident(name), List(arg)) =>
      for
        target <- scalarType(name)
        value  <- fold(arg)
        out    <- convert(value, target)
      yield out

    case Binary(op, l, r)   => for (a <- fold(l); b <- fold(r); v <- binary(op, a, b)) yield v
    case Compare(List(l, r), List(op)) => for (a <- fold(l); b <- fold(r); v <- binary(op, a, b)) yield v

    case _ => None

  private def convert(value: Expr, target: Type): Option[Expr] = (value, target) match
    case (IntLit(v, _), i: Type.Integer) => Some(IntLit(Type.wrap(v, i), None))
    case (IntLit(v, _), _: Type.Floating) => Some(FloatLit(v.toDouble.toString, None))
    case (IntLit(v, _), Type.Char) if v >= 0 && v <= 0x10FFFF && !(v >= 0xD800 && v <= 0xDFFF) =>
      Some(CharLit(v.toInt))
    case (IntLit(v, _), Type.Char)        => err(s"$v is not a Unicode scalar value")
    case (FloatLit(t, _), i: Type.Integer) => Some(IntLit(Type.wrap(BigInt(t.toDouble.toLong), i), None))
    case (FloatLit(t, _), _: Type.Floating) => Some(FloatLit(t, None))
    case (CharLit(c), i: Type.Integer)    => Some(IntLit(Type.wrap(BigInt(c), i), None))
    case _                                 => None

  private def binary(op: String, l: Expr, r: Expr): Option[Expr] = (l, r) match
    case (IntLit(a, _), IntLit(b, _)) =>
      op match
        case "+"  => Some(IntLit(a + b, None))
        case "-"  => Some(IntLit(a - b, None))
        case "*"  => Some(IntLit(a * b, None))
        case "/"  => if b == 0 then err("a constant divided by zero") else Some(IntLit(a / b, None))
        case "%"  => if b == 0 then err("a constant divided by zero") else Some(IntLit(a % b, None))
        case "&"  => Some(IntLit(a & b, None))
        case "|"  => Some(IntLit(a | b, None))
        case "^"  => Some(IntLit(a ^ b, None))
        case "<<" => Some(IntLit(a << shiftBy(b), None))
        case ">>" => Some(IntLit(a >> shiftBy(b), None))
        case _    => compare(op, a.compare(b))
    case (FloatLit(a, _), FloatLit(b, _)) =>
      val (x, y) = (a.toDouble, b.toDouble)

      op match
        case "+" => Some(FloatLit((x + y).toString, None))
        case "-" => Some(FloatLit((x - y).toString, None))
        case "*" => Some(FloatLit((x * y).toString, None))
        case "/" => Some(FloatLit((x / y).toString, None))
        case _   => compare(op, x.compare(y))
    case (BoolLit(a), BoolLit(b)) =>
      op match
        case "&&" => Some(BoolLit(a && b))
        case "||" => Some(BoolLit(a || b))
        case "==" => Some(BoolLit(a == b))
        case "!=" => Some(BoolLit(a != b))
        case _    => None
    case (CharLit(a), CharLit(b)) => compare(op, a.compare(b))
    case (StrLit(a), StrLit(b)) =>
      op match
        case "==" => Some(BoolLit(a == b))
        case "!=" => Some(BoolLit(a != b))
        case _    => None
    case _ => None

  private def shiftBy(n: BigInt): Int =
    if n < 0 || n > 64 then err(s"a constant shifted by $n places") else n.toInt

  private def compare(op: String, sign: Int): Option[Expr] = op match
    case "==" => Some(BoolLit(sign == 0))
    case "!=" => Some(BoolLit(sign != 0))
    case "<"  => Some(BoolLit(sign < 0))
    case "<=" => Some(BoolLit(sign <= 0))
    case ">"  => Some(BoolLit(sign > 0))
    case ">=" => Some(BoolLit(sign >= 0))
    case _    => None

  private val valTypes         = mutable.HashMap.empty[String, Type]
  private val constTypes       = mutable.HashMap.empty[String, Type]
  private val constLits        = mutable.HashMap.empty[String, Expr]
  private val constsInProgress = mutable.LinkedHashSet.empty[String]

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
      if tparams.isEmpty then err(s"type '${qn(name)}' does not take type arguments")
      else
        err(s"type '${qn(name)}' takes ${quantity(tparams.length, "type argument")}, " +
          s"but ${supplied(targs.length, "type argument")}")

  /** Instantiates a struct for one set of type arguments, memoized on the display name. The
   * instantiation is registered *before* its fields are resolved, so a field that points back
   * at the struct finds it and the recursion terminates; `cycleCheck` is what rejects the cycle
   * that has no indirection to break it.
   */
  protected def instantiateStruct(name: String, targs: List[Type]): Type.Struct =
    inDecl(name)(instantiateStructIn(name, targs))

  private def instantiateStructIn(name: String, targs: List[Type]): Type.Struct = {
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
  protected def instantiateEnum(name: String, targs: List[Type]): Type.Enum =
    inDecl(name)(instantiateEnumIn(name, targs))

  private def instantiateEnumIn(name: String, targs: List[Type]): Type.Enum = {
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
              err(s"only a simple enum has an underlying integer type — '${qn(name)}' carries data")
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
              // A discriminant is any compile-time integer, so a negative one under a signed
              // underlying type folds from the unary negation the parser gives, and a `const` may
              // stand where a literal does (`13 §7`).
              case Some(e) =>
                fitting(constInt(e).getOrElse(
                  err(s"the value of variant '${v.name}' must be a constant integer")))
              case None => fitting(nextTag)
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
    // A zero-sized type has exactly one value, so it is trivially its own zero — there is nothing
    // to produce and nowhere to put it. Without this a struct would lose its zero value by gaining
    // a field that costs nothing, which is the opposite of what zero-sized means.
    case t if Type.zeroSized(t) => true
    case Type.Array(_, elem)    => hasZero(elem)
    case s: Type.Struct         => s.fields.forall(f => hasZero(f._2))
    case _                      => false
}
