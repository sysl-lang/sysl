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

  /** Whether every declared type's fields are in, which is when a `&sync T` can be held to what
   * `06` asks of `T`. It is false while the file is still being hoisted for the reason
   * `implsHoisted` is: a type that reaches itself through a `&sync` field is being resolved at the
   * moment its own field list is still empty, so asking then would find nothing in the way of
   * anything.
   */
  protected var typesHoisted: Boolean = false

  /** The pointees of `&sync T` types written before that question could be answered, each with the
   * position to report against. Drained once, as soon as the answer is available.
   */
  protected val sharedChecks = mutable.ListBuffer.empty[(Type, Option[Pos])]

  /** Holds a `&sync T` to `06`'s rule about what `T` may contain — now if the types are all in, and
   * after hoisting if they are not.
   */
  protected def checkShared(inner: Type): Unit =
    if typesHoisted then Sharing.complaint(inner).foreach(err)
    else sharedChecks += ((inner, currentPos))

  /** `Self` standing in for itself, for the checks a trait's own declaration can run before any type
   * has implemented it. A requirement's arguments have to resolve for the declaration to be checked
   * at all, and here the implementing type is precisely what is not yet known — so it is treated as
   * the one unknown type it is, which is enough to compare two requirements of the same trait.
   */
  protected def abstractSelf: Type = Type.Abstract(selfName, Nil)

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
    case WeakType(inner)                    => WeakType(spellSelf(inner, selfRef))
    case ArrayType(len, elem)               => ArrayType(len, spellSelf(elem, selfRef))
    case TupleType(parts, r)                => TupleType(parts.map(spellSelf(_, selfRef)), r)
    case f: FnType =>
      f.copy(params = f.params.map(spellSelf(_, selfRef)), ret = spellSelf(f.ret, selfRef))

  /** Whether a written type names any of the parameters being solved, and so is not yet a type.
   *
   * An array's length is an expression rather than a type, so nothing in it can name one.
   */
  protected def mentions(ref: TypeRef, tps: Set[String]): Boolean = ref match
    case NamedType(n, args) => tps(n) || args.exists(mentions(_, tps))
    case PtrType(inner)     => mentions(inner, tps)
    case RefType(inner, _)  => mentions(inner, tps)
    case WeakType(inner)    => mentions(inner, tps)
    case ArrayType(_, elem) => mentions(elem, tps)
    case TupleType(parts, _) => parts.exists(mentions(_, tps))
    case f: FnType          => mentions(f.asTrait, tps)

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
    val written = b.args.map(resolveType(_, subst))
    val key     = traitKey(b.name)
    var args    = written

    for k <- key; decl <- traitDecls.get(k) do
      checkTraitArity(b.name, decl.tparams, decl.tdefaults, written)
      // What the bound leaves out the trait supplies, and `Self` in one of those defaults is
      // whatever is being asked to implement the trait — the parameter the bound belongs to, which
      // is what the substitution being resolved under already carries.
      args = withDefaults(k, decl.tparams, decl.tdefaults, written, subst.filter(_._1 == selfName))
      deferredBounds(k, decl.tparams, decl.bounds, args)

    // A bound naming no trait at all keeps the name as written, so `checkBoundNames` reports it in
    // the words it was spelled with rather than this producing a second complaint about a key.
    Type.Bound(key.getOrElse(b.name), args)
  }

  /** How many arguments a declaration has to be given: the parameters it declares, less the ones
   * carrying a default. A default stands in only for an argument that was not written, so the
   * parameters without one are exactly what a use is obliged to supply.
   *
   * The defaults are a suffix — `checkTypeDefaults` refuses any other arrangement at the
   * declaration — so a count is enough here and no position has to be reasoned about.
   */
  protected def leastArgs(tparams: List[String], tdefaults: Map[String, TypeRef]): Int =
    tparams.count(!tdefaults.contains(_))

  /** The declarations whose defaults are being filled right now, so one that leads back to itself is
    * caught rather than recursed into. The set is small and short-lived: a fill nests only where one
    * default names another generic declaration that has defaults of its own.
    */
  private val filling = mutable.Set.empty[String]

  private def arityPhrase(tparams: List[String], tdefaults: Map[String, TypeRef]): String = {
    val least = leastArgs(tparams, tdefaults)

    if least == tparams.length then s"takes ${quantity(tparams.length, "type argument")}"
    else s"takes between $least and ${tparams.length} type arguments"
  }

  /** A trait applied to the wrong number of arguments, said in the words a trait deserves — the
   * type-level message would send the reader looking for a struct of that name.
   */
  protected def checkTraitArity(
      name: String,
      tparams: List[String],
      tdefaults: Map[String, TypeRef],
      targs: List[Type],
  ): Unit =
    if targs.length > tparams.length || targs.length < leastArgs(tparams, tdefaults) then
      if tparams.isEmpty then err(s"trait '$name' does not take type arguments")
      else
        err(s"trait '$name' ${arityPhrase(tparams, tdefaults)}, " +
          s"but ${supplied(targs.length, "type argument")}")

  /** The arguments a generic declaration was applied to, with the ones it was not given taken from
   * the defaults it declares (`10 §3`).
   *
   * They are filled left to right, each resolved under the arguments already fixed, so a later
   * default may name an earlier parameter and `[T, U = T]` means what it reads as. `self` carries
   * what `Self` stands for where there is such a thing — the implementing type at an `impl`, the
   * parameter itself at a bound — and is empty where there is not, which is what leaves a `Self`
   * default at a trait object reported rather than silently taken as something else.
   *
   * A default is written in the file that declares it, so it is resolved in **that** file's terms
   * rather than the applying one's: `[A = Heap]` names the `Heap` its author could see, whether or
   * not the program applying it can see one at all.
   */
  protected def withDefaults(
      key: String,
      tparams: List[String],
      tdefaults: Map[String, TypeRef],
      targs: List[Type],
      self: Map[String, Type],
  ): List[Type] =
    if targs.length >= tparams.length || tdefaults.isEmpty then targs
    else if !filling.add(key) then
      // A default that leads back to the declaration it belongs to would fill forever, since each
      // arrival applies the declaration to fewer arguments than it declares and so asks for the
      // defaults again. Reported at the use, because a chain of them is a property of the
      // declarations together rather than of any one of them.
      err(s"filling a type argument of '${qn(key)}' from its default leads back to '${qn(key)}' — a " +
        "default cannot stand in for a type that is still being worked out")
    else
      try
        inDecl(key) {
          val filled = mutable.ListBuffer.from(targs)
          // Where the caller knows what `Self` is it says so. Where it does not, the implementing
          // type is precisely what is not yet known, and the default stands for the one unknown type
          // it is — which is enough to compare two requirements of the same trait, and is how
          // `trait Word: Mul` resolves before anything implements `Word`. The one place that is not
          // good enough is erasure, and `traitObject` refuses it there in its own words.
          val here = (if self.contains(selfName) then self else self ++ selfBinding(abstractSelf))

          for tp <- tparams.drop(targs.length) do
            filled += tdefaults.get(tp).fold(Type.Unknown)(resolveType(_, here ++ tparams.zip(filled)))

          filled.toList
        }
      finally filling -= key

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
          // A bound asks something of the parameter it is written on, so `Self` inside it — written
          // there, or arriving from a default — is that parameter. It is taken from `inner`, whose
          // entry for it is already the bound-free stand-in that breaks the walk back around.
          val here = inner ++ selfBinding(inner(tp))

          bounds.getOrElse(tp, Nil).map(b => recorded(Type.Bound(b.name, Nil))(resolveBound(b, here))),
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
    // A result list is a property of *this* position and of no other, which is why it is read here
    // rather than by `resolveType` — one that reached anywhere else is a mistake, and says so.
    case TupleType(parts, true) => Type.Results(tupleType(parts.map(resolveType(_, subst))))
    case _                      => resolveType(t, subst)

  private def resolveTypeAt(t: TypeRef, subst: Map[String, Type]): Type = t match
    case PtrType(inner) =>
      traitObject(inner, subst, "*")
        .fold(Type.Ptr(addressable(underIndirection(resolveType(inner, subst)), "'*'")))(Type.Ptr.apply)
    // An atomic reference promises that a second domain may hold the object, which is a promise
    // about everything the object holds (`06 § &sync T`). A trait object is the one shape whose
    // contents are not known here — the type it forgot is settled where a value is erased into one,
    // and that is where it is asked.
    case RefType(inner, sync) =>
      val t = traitObject(inner, subst, "&")
        .fold(Type.Ref(addressable(underIndirection(resolveType(inner, subst)), "'&'"), sync))(Type.Ref(_, sync))

      if sync && !t.inner.isInstanceOf[Type.Trait] then checkShared(t.inner)

      t

    case WeakType(inner) =>
      traitObject(inner, subst, "weak")
        .fold(Type.Weak(addressable(underIndirection(resolveType(inner, subst)), "'weak'")))(Type.Weak.apply)

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

    // A tuple holds its parts the way a struct holds its fields, so a part is resolved exactly as a
    // field is — a `unit` part included, which the layout skips and the parts after it shift past.
    case TupleType(parts, false) => tupleType(parts.map(resolveType(_, subst)))

    // A callable is not a type: it is a trait, and a trait stands where a type does only behind a
    // mode sigil. The bare arrow is the sugar a **parameter** may use, and it never reaches here —
    // a parameter reads it before resolving one — so what is left to say is that a slot needing a
    // concrete type needs the box the `&` denotes (`12 §6`).
    case f: FnType =>
      checkFnArity(f)

      if f.bare then
        err(s"'${f.show}' is a callable a function is passed and does not keep, which only a " +
          s"parameter may be — anywhere a concrete type is required, a callable is boxed, so " +
          s"write '&Fn(${f.params.map(_.show).mkString(", ")}) -> ${f.ret.show}'")
      else
        err(s"'${f.show}' is a trait, and a trait is a type only behind a mode — write " +
          s"'&${f.show}' for the boxed callable a concrete slot takes")

    // A result list reaches here only where a *type* was asked for, which is everywhere but a
    // function's result — and the fix is the type that has the same parts.
    case TupleType(parts, true) =>
      err(s"'${parts.map(_.show).mkString(", ")}' is a result list, which a signature has and a " +
        s"value does not — write '(${parts.map(_.show).mkString(", ")})' for the type")

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

      val predFn = if d.pred.isDefined then Some(predKey(key)) else None
      Type.Constrained(key, base, d.derived, lo, hi, d.range.exists(_.exclusiveHi), predFn)
    }
  }

  /** One `within` bound as a constant, checked against the base's kind: a `char` base takes a
   * character, an integer base an integer that fits its width, a float base any number. A bound of the
   * wrong kind, or an integer out of the base's range, is an error.
   *
   * The bound is **folded first**, so a `const` — or an expression over constants — stands wherever a
   * literal does, which is what makes `within 0..<max_tasks` and `[max_tasks]Task` one fact rather than
   * two (`16 § Open b`). Folding is the same `fold` an array bound and an enum discriminant go through,
   * so the three positions accept exactly the same expressions and cannot drift apart. What does not
   * fold is reported as not being a constant, at the bound, rather than as a wrong *kind* — a name the
   * program never declared is a different mistake from a name that is not a number.
   */
  private def boundValue(e: Expr, base: Type): BigDecimal =
    fold(e) match
      case Some(folded) => boundLiteral(folded, base)
      case None =>
        err(s"a 'within' bound has to be a constant, and ${boundKind(e)} is not one — a literal, a " +
          "'const', or an expression over them")

  private def boundLiteral(e: Expr, base: Type): BigDecimal =
    Type.underlying(base) match
      case Type.Char =>
        e match
          case CharLit(cp) => BigDecimal(cp)
          case _           => err(s"a 'char' subtype needs character bounds, not ${boundKind(e)}")
      case i: Type.Integer =>
        val v = e match
          case IntLit(n, _)             => n
          case Unary("-", IntLit(n, _)) => -n
          case _                        => err(s"an integer subtype needs integer bounds, not ${boundKind(e)}")
        if !Type.fits(v, i) then err(s"the bound $v does not fit ${show(base)}")
        BigDecimal(v)
      case _ =>
        e match
          case IntLit(n, _)               => BigDecimal(n)
          case Unary("-", IntLit(n, _))   => BigDecimal(-n)
          case FloatLit(t, _)             => BigDecimal(t)
          case Unary("-", FloatLit(t, _)) => -BigDecimal(t)
          case _                          => err(s"a floating-point subtype needs numeric bounds, not ${boundKind(e)}")

  private def boundKind(e: Expr): String = e match
    case _: CharLit              => "a character"
    case _: FloatLit             => "a floating-point literal"
    case Unary("-", _: FloatLit) => "a floating-point literal"
    case _: IntLit               => "an integer"
    case Unary("-", _: IntLit)   => "an integer"
    case _: StrLit               => "a string"
    case _: BoolLit              => "a boolean"
    case Ident(n)                => s"'$n'"
    case _                       => "that"

  /** A trait named behind a memory-mode sigil, which is what makes the pointer a trait object
   * (`02`): `*Trait` raw and unmanaged, `&Trait` reference-counted. `None` for everything else,
   * including a type parameter that happens to be spelled like a trait — the substitution wins,
   * since that is what shadowing means everywhere else a name is resolved.
   */
  private def traitObject(inner: TypeRef, subst: Map[String, Type], sigil: String): Option[Type.Trait] =
    inner match
      // A callable behind a mode is a trait object of the call trait, and everything that makes one
      // — object safety, the arguments, the table — is the same question asked of the same shape.
      case f: FnType =>
        checkFnArity(f)
        at(f.pos)(traitObject(f.asTrait, subst, sigil))
      case NamedType(n, argRefs) if traitKey(n).isDefined && !(argRefs.isEmpty && subst.contains(n)) =>
        val key     = traitKey(n).get
        val decl    = traitDecls(key)
        val written = argRefs.map(resolveType(_, subst))

        at(inner.pos) {
          checkTraitArity(n, decl.tparams, decl.tdefaults, written)

          val args = withDefaults(key, decl.tparams, decl.tdefaults, written, Map.empty)

          deferredBounds(key, decl.tparams, decl.bounds, args)
          // A `Self` that arrived from one of *this* trait's own defaults is left to the check below,
          // because there the fix is a spelling: writing the argument out. That now includes the
          // operator catalog, which it did not when an operator's result was fixed to `Self` and no
          // argument could rescue it — `&Mul[real, real]` is a formable object (`14 §7`).
          checkObjectSafe(key, args, sigil, decl.tparams.drop(written.length).toSet)

          // An object has forgotten which type it holds, so a default that names one has nothing to
          // stand for, and writing the argument is the fix.
          for tp <- decl.tparams.drop(written.length); ref <- decl.tdefaults.get(tp) if mentionsSelf(ref) do
            err(s"'$tp' defaults to '${ref.show}', which names the type implementing '${qn(key)}', " +
              "and an object has forgotten which type it holds — write the argument")

          Some(Type.Trait(key, args))
        }
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
  protected def checkObjectSafe(
      name: String,
      args: List[Type],
      sigil: String,
      defaulted: Set[String] = Set.empty,
  ): Unit = {
    val obj = s"'$sigil${Type.qualified(qn(name), args)}'"

    // A trait offers what it requires as well as what it declares, so a required trait that cannot
    // be erased makes the trait that required it unerasable too — and the diagnostic names the one
    // the member came from, which for a required trait is not the one the object was written as.
    for (from, m) <- traitMembers(Type.Bound(name, args)) do
      val shown = qn(from.name)

      if m.recvMode.isEmpty then
        err(s"'$shown' declares the associated function '${m.name}', which has no receiver to " +
          s"dispatch on — so there is no $obj to form")
      if m.receiver.exists(_.isInstanceOf[RecvMode.ByRef]) && sigil == "*" then
        err(s"'${m.name}' of '$shown' takes '&self', so it needs its receiver inside a " +
          s"reference-counted box — $obj points straight at a value, so write '&${qn(name)}' instead")
      // A `Self` reaches a signature two ways and both have to be looked for. It may be **written**
      // there — `other: *Self` — or it may arrive through one of the trait's own **arguments**, which
      // is how the operator catalog carries it: `add(self, rhs: Rhs) -> Out` mentions no `Self` at
      // all, and `Add`'s two defaults *are* `Self`, so a bound written bare hands both parameters the
      // very type the object has forgotten. Reading only what was written would let `trait Word: Add`
      // erase and give the object a slot whose argument type differs per implementing type.
      // A parameter this application left to a default is not reported here: the caller says which
      // those are, and reports them itself in the words that name the fix.
      val viaArg = paramsBoundToSelf(from) -- (if from.name == name then defaulted else Set.empty)

      if (m.params.map(_.typ) ++ m.retType).exists(t => mentionsAny(t, viaArg + selfName)) then
        err(s"'${m.name}' of '$shown' mentions 'Self' away from its receiver, and an erased value " +
          s"has forgotten which type that is — so there is no $obj to form")
      // A variadic call names the callee's whole function type, which is how it says where the
      // declared parameters stop; a table slot is one word and names nothing. A bound still reaches
      // such a method, because that call knows which function it is reaching.
      if m.variadic then
        err(s"'${m.name}' of '$shown' takes a '...', and a call to one names the whole function " +
          s"type it is reaching — a slot in a method table is a word and names none, so there is " +
          s"no $obj to form")
  }

  /** Holds a written callable to an arity the prelude declares a call trait for.
   *
   * The limit is the prelude's and not the language's, so what it says is where to go next: a
   * callable this wide is a signature nobody reads, and a struct of the arguments names them.
   */
  protected def checkFnArity(f: FnType): Unit =
    if f.params.length > Type.Fn.maxArity then
      at(f.pos)(err(s"a callable takes up to ${Type.Fn.maxArity} parameters and this one takes " +
        s"${f.params.length} — a call this wide reads better with the arguments named, so pass a " +
        "struct of them"))

  /** Whether a written type names `Self` anywhere inside it. */
  protected def mentionsSelf(t: TypeRef): Boolean = mentionsAny(t, Set(selfName))

  /** Whether a written type names any of `names` anywhere inside it. `Self` is the one that matters
   * for erasure, and a trait *parameter* matters exactly when `Self` is what was passed for it.
   */
  protected def mentionsAny(t: TypeRef, names: Set[String]): Boolean = t match
    case NamedType(n, args)  => names(n) || args.exists(mentionsAny(_, names))
    case PtrType(i)          => mentionsAny(i, names)
    case RefType(i, _)       => mentionsAny(i, names)
    case WeakType(i)         => mentionsAny(i, names)
    case ArrayType(_, e)     => mentionsAny(e, names)
    case TupleType(parts, _) => parts.exists(mentionsAny(_, names))
    case f: FnType           => mentionsAny(f.asTrait, names)

  /** Which of a trait's own type parameters were given `Self` at this application — the parameters a
   * member may name and so mention the forgotten type without ever spelling it.
   */
  protected def paramsBoundToSelf(b: Type.Bound): Set[String] =
    traitDecls.get(b.name).toList
      .flatMap(_.tparams.zip(b.args))
      .collect { case (tp, Type.Abstract(n, _)) if n == selfName => tp }
      .toSet

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

  /** The rules a declared signature must satisfy whichever declaration form it came from, checked
   * after the name is registered so a failure reports the mistake without also erasing the
   * declaration it is about.
   */
  protected def checkSignatureRules(
      name: String,
      params: List[Param],
      ret: Option[TypeRef],
      variadic: Boolean,
      foreign: Boolean = false,
  ): Unit = {
    // C reads a variadic call's arguments relative to the last named parameter, so there has to be
    // one; `f(...)` is not a callable declaration in any C either.
    if variadic && params.isEmpty then err(s"'$name' needs at least one named parameter before '...'")
    checkVaListPositions(name, params, ret, foreign)
  }

  /** Where a `va_list` may stand in a signature (`12 §9`).
   *
   * In a **sysl** signature a walk is handed on **by address**: `*va_list` is the parameter type,
   * and `&ap` is what the caller writes. A bare `va_list` parameter is refused because a parameter
   * is a by-value binding (`12 §2`) and a copy of a walk is not a walk — advancing it would advance
   * nothing the caller could see, which is the one thing the form exists to do.
   *
   * An **`extern`** transcribes a C header, so it is written in C's spellings and both are allowed:
   * `va_list` is C's by-value parameter, the one `vprintf` takes, and `*va_list` is C's `va_list *`,
   * the one a function that must advance its caller's own walk takes. What a call actually hands
   * the first of those is a different thing on every target and is `TVaPass`'s business
   * (`targets.md`); the *call* writes `&ap` for either, because the address is the only thing sysl
   * has and it is what both are formed from.
   *
   * **Returning a bare `va_list` is refused everywhere**, foreign or not: sysl has no by-value
   * `va_list` at all — the type names the storage a walk lives in — so there would be nothing to
   * put the result in. A `*va_list` return is an ordinary pointer and is allowed.
   */
  private def checkVaListPositions(
      name: String,
      params: List[Param],
      ret: Option[TypeRef],
      foreign: Boolean,
  ): Unit = {
    def isVaList(t: TypeRef) = t match
      case NamedType(n, Nil) => scalarType(n).contains(Type.VaList)
      case _                 => false

    for p <- params if !foreign do
      if isVaList(p.typ) then
        at(p.pos)(err(s"a va_list is a parameter as '*va_list', not as 'va_list' — a parameter is a " +
          s"by-value binding, and a copy of a walk advances nothing '$name''s caller can see, so " +
          "the walk is handed over by address and the call writes '&ap'"))

    for r <- ret do
      if isVaList(r) then
        at(r.pos)(err(s"a va_list cannot be returned from '$name' — the type names the storage a " +
          "walk lives in, and there is no value of it to hand back"))
  }

  /** The type a foreign parameter has once it is a slot in a signature.
   *
   * Only one written type changes: a C `va_list` parameter takes the address of the walk, since
   * that is what sysl has to give and what every target's answer is formed from. So an argument is
   * checked as a `*va_list` whichever of C's two spellings the header used, and the difference
   * between them — whether the callee receives the walk or a pointer to it — is carried by
   * `TVaPass` rather than by the parameter's type.
   */
  protected def foreignParam(t: Type): Type = if t == Type.VaList then Type.Ptr(Type.VaList) else t

  /** Which of a foreign declaration's parameters were written as C's by-value `va_list`, and so
   * need what a call passes converted to the target's ABI (`targets.md`).
   */
  protected def foreignVaByValue(e: ExternDecl): Set[Int] =
    e.params.zipWithIndex.collect {
      case (Param(_, NamedType(n, Nil), _), i) if scalarType(n).contains(Type.VaList) => i
    }.toSet

  /** Resolves a scalar type name: the named primitives and friendly aliases, or one of the
   * systematic `iN` / `uN` / `fN` width spellings.
   */
  protected def scalarType(name: String): Option[Type] =
    Type.scalars.get(name).orElse(widthType(name))

  /** `i5`, `u12`, `f32` — a family letter followed by a width. The integer family is open,
   * so it is recognised by shape rather than listed; a width the back end cannot lower is a
   * diagnostic, not an unknown name.
   *
   * The integer limit is 128 rather than LLVM's own `2^23 - 1` because 128 is where the *rest* of
   * the toolchain stops agreeing: a division at a wider width has no compiler-rt routine behind it,
   * and the decimal rendering is written once at the widest width there is. It is a statement about
   * what the back end lowers, not about what `00 §5` permits — the maximum the language allows is
   * still that chapter's open question.
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
      else if bits >= 1 && bits <= 128 then Some(Type.Integer(bits, signed = name.head == 'i'))
      else err(s"'$name' is wider than the 128 bits the back end lowers")
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
        try s.fields = decl.fields.map(f => (f.name, recover(Type.Unknown)(resolveType(f.typ, subst))))
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
        case Type.Slice(elem)     => Type.Slice(substParams(elem, subst))
        case other                => other

  /** Instantiates an enum for one set of type arguments. All-dataless variants make a *simple*
   * enum (integer constants, auto-incrementing from an optional explicit `= value`); any
   * data-carrying variant makes a *data* enum, whose variants take sequential tags and whose
   * payload-bearing variants each claim a slot in the aggregate.
   */
  protected def instantiateEnum(name: String, targs: List[Type]): Type.Enum =
    inDecl(name)(instantiateEnumIn(name, targs))

  private def instantiateEnumIn(name: String, written: List[Type]): Type.Enum = {
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
            Type.EnumVariant(v.name, tag, Nil, false)
          else
            if v.value.isDefined then
              err(s"variant '${v.name}' carries data, so it cannot also have an explicit value")
            val tag    = nextTag; nextTag += 1
            val fields = v.fields.map(f => (f.name, recover(Type.Unknown)(resolveType(f.typ, subst))))
            Type.EnumVariant(v.name, tag, fields, fields.nonEmpty)
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
    // A `weak T` parameter also accepts a `&T`, which the call weakens, so the payload type is
    // matched against either shape — the same leniency `&T` gets one case up, for the same reason.
    case WeakType(inner) =>
      actual match
        case Type.Weak(t)  => unify(inner, t, tparams, sub)
        case Type.Ref(t, _) => unify(inner, t, tparams, sub)
        case _             => ()
    case ArrayType(None, elem) =>
      actual match
        case Type.Slice(e) => unify(elem, e, tparams, sub)
        case _             => ()
    case ArrayType(Some(_), elem) =>
      actual match
        case Type.Array(_, e) => unify(elem, e, tparams, sub)
        case _                => ()
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
