package sh.sysl

import scala.collection.mutable

/** Resolving a `TypeRef` to a `Type`, instantiating generic structs and enums on demand, and the
 * generics machinery that solves a declaration's type arguments from a call's argument types.
 *
 * A named type is memoized on its display name, and an instantiation is registered *before* its
 * fields are resolved, so a type that reaches itself through a `*T` / `&T` / `[]T` finds the
 * in-progress object and the recursion terminates; `cycleCheck` is what rejects a cycle that has
 * no indirection to break it.
 */
trait TypeResolution extends GenericInstantiation with Aliasing {

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
    case ArrayType(len, elem, ro)           => ArrayType(len, spellSelf(elem, selfRef), ro)
    // A value argument names no type, so there is no `Self` in it to spell.
    case v: ValueArgType                    => v
    case VolatileType(inner)                => VolatileType(spellSelf(inner, selfRef))
    case TupleType(parts, r)                => TupleType(parts.map(spellSelf(_, selfRef)), r)
    // A pack names a parameter of the block it is declared by, which `Self` never is.
    case p: PackType                        => p
    case f: FnType =>
      f.copy(params = f.params.map(spellSelf(_, selfRef)), ret = spellSelf(f.ret, selfRef))
    case CFnType(params, ret) => CFnType(params.map(spellSelf(_, selfRef)), spellSelf(ret, selfRef))

  /** Whether a written type names any of the parameters being solved, and so is not yet a type.
   *
   * **An array's length counts** (`10 §9`). It used to be true that nothing in a length could name a
   * parameter — the comment here said so — and value generics made it false: `[N]int` names no type
   * parameter in its element and is still not a type until `N` is fixed. Missing that is not a
   * missing feature but a wrong answer, since a caller then resolves the signature under an empty
   * substitution and reports the length as not constant.
   */
  protected def mentions(ref: TypeRef, tps: Set[String]): Boolean = ref match
    case NamedType(n, args) => tps(n) || args.exists(mentions(_, tps))
    case PtrType(inner)     => mentions(inner, tps)
    case RefType(inner, _)  => mentions(inner, tps)
    case WeakType(inner)    => mentions(inner, tps)
    case ArrayType(len, elem, _) =>
      len.exists(lengthMentions(_, tps)) || mentions(elem, tps)
    // A value argument is not a type and is still not *fixed* until whatever it names is:
    // `Buf[N]` inside a block declaring `N` is as unresolved as `Box[T]` is.
    case ValueArgType(e)     => lengthMentions(e, tps)
    case VolatileType(inner) => mentions(inner, tps)
    case TupleType(parts, _) => parts.exists(mentions(_, tps))
    // A pack is a parameter, so `(..A)` is no more a type than `Box[T]` is until `A` is bound.
    case PackType(n)         => tps(n)
    case f: FnType          => mentions(f.asTrait, tps)
    case CFnType(params, ret) => params.exists(mentions(_, tps)) || mentions(ret, tps)

  /** Whether an array **length** names one of the parameters being solved — a value parameter
   * standing for the length itself (`10 §9`), or a type parameter reached through a measurement
   * such as `[sizeof(T)]u8`.
   *
   * It walks the shapes `fold` walks, and no others: the set of expressions a length may be is
   * closed, so anything outside it cannot name a parameter because it cannot be a length at all.
   */
  private def lengthMentions(e: Expr, tps: Set[String]): Boolean = e match
    case Ident(n)               => tps(n)
    case LayoutOf(_, tr)        => mentions(tr, tps)
    case Unary(_, operand)      => lengthMentions(operand, tps)
    case Binary(_, l, r)        => lengthMentions(l, tps) || lengthMentions(r, tps)
    case Compare(List(l, r), _) => lengthMentions(l, tps) || lengthMentions(r, tps)
    case Call(_, args)          => args.exists(lengthMentions(_, tps))
    case _                      => false

  /** Refuses arithmetic on a **value parameter** inside a type (`10 §9`) — `[N + 1]int`.
   *
   * A value parameter may *stand* as a length, and a body may compute with it as freely as with any
   * other `usize`. What neither may do is put the result of a computation in a type. A type carrying
   * `N + 1` needs the compiler to decide when two *expressions* denote one length — that `N + 1` and
   * `1 + N` are one type, and that `2 * N` and `N + N` are — which is type-level arithmetic and a
   * feature of its own. Rust draws the line in the same place and has kept `generic_const_exprs`
   * unstable long after const generics shipped.
   *
   * A length measuring a **type** parameter is untouched and stays legal: `[sizeof(T) * 3 + 1]u8` is
   * arithmetic on a number the type argument fixes outright, not on a length anything has to solve
   * an equation for.
   *
   * Refusing is the whole point rather than a limitation admitted reluctantly. Left alone the length
   * resolves to whatever the placeholder made it, so the array is silently the wrong size — and a
   * wrong answer is worse than the refusal that names the feature it would need.
   */
  private def checkLengthArithmetic(len: Expr, subst: Map[String, Type]): Unit =
    lengthArithmetic(len, subst.collect { case (n, _: Type.ConstArg) => n }.toSet)

  /** Refuses a **type** parameter written where an array's length belongs — `f[T](xs: [T]int)` and
   * `impl[N, T] Tag for [N]T`.
   *
   * A length is a value, and a parameter standing for one is declared `const` (`10 §9`). Before
   * that spelling existed, the only thing here that could legitimately fail to fold was a
   * measurement *over* a type parameter — `[sizeof(T)]u8` — and that one reaches the length through
   * `sizeof` rather than as a bare name. So `awaitsInstantiation` stood a bare name at zero, which
   * was harmless while no bare name could mean anything, and became a silent wrong answer the day
   * one could: `impl[N, T] Tag for [N]T` quietly became an implementation for `[0]T`, and
   * `f[T](xs: [T]int)` compiled with a length nobody wrote.
   *
   * A bare name bound to a **type** has no reading at all, which is what makes this a sentence
   * rather than a fallback.
   */
  private def checkLengthNotAType(len: Expr, subst: Map[String, Type]): Unit = len match
    case Ident(n) =>
      for a <- subst.get(n).collect { case a: Type.Abstract => a } do
        err(s"'${a.name}' is a type parameter, and an array's length is a value rather than a " +
          s"type — a parameter that stands for a length is declared 'const ${a.name}: usize'")
    case _ => ()

  /** The same refusal made at the **declaration**, from the names alone and with no substitution —
   * which is where the mistake is, and the only place one position can be told about it at all.
   *
   * `[N + 1]int` as a **parameter** never reaches the resolution above: nothing unifies with it, so
   * the call fails first and the reader is told the argument cannot be inferred. That is true and
   * unhelpful, since what cannot be inferred is not something the caller can fix. Asked here, the
   * declaration is what reports, and a declaration nothing ever calls reports too.
   */
  protected def checkValueParamArithmetic(
      names: Set[String],
      types: List[TypeRef],
      tparams: Set[String] = Set.empty,
      packs: Set[String] = Set.empty,
  ): Unit = {
    def declaredPack(n: String): Unit =
      if !packs(n) then
        if tparams(n) then
          err(s"'$n' stands for one type, and '(..$n)' spreads a list of them — a parameter that " +
            s"stands for a list is declared '..$n'")
        else
          err(s"'..$n' names no type pack — a pack is declared in the parameter list, as '[..$n]'")

    def walk(t: TypeRef): Unit = t match
      case ArrayType(len, elem, _) =>
        len.foreach { l =>
          lengthArithmetic(l, names)
          lengthIsNotAType(l, tparams -- names)
        }
        walk(elem)
      case NamedType(_, args)      => args.foreach(walk)
      // A written value argument is held to the same rule a length is, and for the same reason:
      // `Buf[N + 1]` puts a computation where an identity belongs.
      case ValueArgType(e) =>
        lengthArithmetic(e, names)
        lengthIsNotAType(e, tparams -- names)
      case PtrType(inner)          => walk(inner)
      case RefType(inner, _)       => walk(inner)
      case WeakType(inner)         => walk(inner)
      case VolatileType(inner)     => walk(inner)
      // A name spread as a pack has to have been **declared** as one, and this is the only place
      // that can be said (`10 §10`). Left to resolution it is never said at all: inference binds
      // whatever `(..T)` matched, so a `T` declared as one type quietly starts standing for a list
      // and the mistake becomes an implementation for a shape nobody wrote. That is `[N]T`'s bare
      // name one kind up, and it is caught here for the same reason.
      case TupleType(List(p: PackType), _) => declaredPack(p.name)
      case TupleType(parts, _)             => parts.foreach(walk)
      // A pack reached anywhere *else* is one written outside the tuple that is the only place for
      // it. Said here rather than at resolution, which a signature nothing ever calls never reaches.
      case PackType(n) =>
        declaredPack(n)
        err(s"'..$n' is a type pack and not a type — the only place one may be written is inside " +
          s"a tuple, as '(..$n)'")
      case f: FnType               => f.params.foreach(walk); walk(f.ret)
      case CFnType(params, ret)    => params.foreach(walk); walk(ret)

    // Walked unconditionally, because a *pack* spelled where none was declared is a mistake in a
    // signature that declares no parameters at all — the two rules above have nothing to say about
    // one, and skipping the walk is what would leave it unsaid.
    types.foreach(walk)
  }

  /** The declaration-time half of `checkLengthNotAType`, asked of the parameter *names* rather than
   * of a substitution — which is what reaches the case resolution cannot.
   *
   * `f[T](xs: [T]int)` is solved before it is resolved: `unify` reads a length off the argument's
   * type and binds `T` to the 2 it found there, so by the time the signature resolves, `T` is a
   * value and looks like one that was declared `const`. The declaration is where the two are still
   * distinguishable, so the declaration is where this is asked.
   */
  private def lengthIsNotAType(len: Expr, tparams: Set[String]): Unit = len match
    case Ident(n) if tparams(n) =>
      err(s"'$n' is a type parameter, and an array's length is a value rather than a type — a " +
        s"parameter that stands for a length is declared 'const $n: usize'")
    case _ => ()

  private def lengthArithmetic(len: Expr, names: Set[String]): Unit = {
    def valueParams(e: Expr): List[String] = e match
      case Ident(n) if names(n)   => List(n)
      case Unary(_, operand)      => valueParams(operand)
      case Binary(_, l, r)        => valueParams(l) ::: valueParams(r)
      case Compare(List(l, r), _) => valueParams(l) ::: valueParams(r)
      case Call(_, args)          => args.flatMap(valueParams)
      case _                      => Nil

    len match
      // The length that *is* the parameter, which is the whole of what a type may say about one.
      case _: Ident =>
      case other =>
        for n <- valueParams(other).distinct.headOption do
          err(s"this length does arithmetic on '$n', and a type may name a value parameter but not " +
            s"compute with one — deciding that two such lengths are one type is type-level " +
            s"arithmetic, which is a separate feature. A body may compute with '$n' freely")
  }

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
   *
   * **It must be called in the terms of the declaration whose parameters these are** — under an
   * `inDecl` or an `inScope` for it — because a bound names a trait the way anything else does, and
   * what a short name means is what the *declaring* file imported. Read from anywhere else the bound
   * silently keeps the name as written, and the parameter goes on carrying a promise that no
   * conformance check can match against the same bound resolved properly.
   */
  protected def abstractSubst(
      tparams: List[String],
      bounds: Map[String, List[BoundRef]],
      values: Map[String, TypeRef] = Map.empty,
      packs: Set[String] = Set.empty,
  ): Map[String, Type] = {
    def build(tp: String, seen: Set[String]): Type.Abstract = named(tp, tp, seen)

    /** The stand-in for one parameter, under a name that may differ from it — which is what a pack's
      * members need, since two of them stand for one parameter and two types that compared equal
      * would defeat the walk they exist for (`Abstract` is identified by its name).
      */
    def named(as: String, tp: String, seen: Set[String]): Type.Abstract =
      Type.Abstract(
        as,
        if seen(tp) then Nil
        else
          val inner: Map[String, Type] = tparams.map(p => p -> build(p, seen + tp)).toMap
          // A bound asks something of the parameter it is written on, so `Self` inside it — written
          // there, or arriving from a default — is that parameter. It is taken from `inner`, whose
          // entry for it is already the bound-free stand-in that breaks the walk back around.
          val here = inner ++ selfBinding(inner(tp))

          bounds.getOrElse(tp, Nil).map(b => recorded(Type.Bound(b.name, Nil))(resolveBound(b, here))),
      )

    // A **value parameter** stands in as a zero rather than as an `Abstract` (`10 §9`). It is not a
    // type, so nothing may ask what it implements — and standing at a value is what lets the one
    // walk that checks the generic body read `[N]T` as an array and `N` as a `usize` without a
    // second mechanism. Zero is the same placeholder `[sizeof(T)]u8` already resolves to for this
    // walk, whose tree is discarded; every real length is built per instantiation.
    //
    // A **pack** stands at **two** types (`10 §10`), each carrying the pack's own bounds, because an
    // unrolled loop has no fixed length for this walk to run at. Two rather than one is what makes
    // the walk worth doing: the body of `for const` is one piece of source repeated, so a copy that
    // checks at one position checks at every position — and at two the *between* is covered as
    // well, which is where a separator is emitted and where a body that only works on the first part
    // gives itself away. Two is also the smallest tuple there is (`00 §13`), so the shape being
    // checked is one that really exists.
    //
    // The members are named with a `#`, which no identifier may hold, so nothing a program writes
    // can collide with one and a diagnostic naming `A#0` is plainly the compiler's own stand-in.
    tparams.map(tp =>
      tp -> (
        if packs(tp) then Type.Pack(List(named(s"$tp#0", tp, Set.empty), named(s"$tp#1", tp, Set.empty)))
        else
          values.get(tp).fold[Type](build(tp, Set.empty))(tr =>
            Type.ConstArg(0, recover(Type.Unknown)(resolveType(tr, Map.empty))))
      )).toMap
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

  /** Resolves a type in one of the three positions a `volatile` qualifier may stand: the pointee of
   * a `*T`, an element, and a struct field (`03 § Device memory`).
   *
   * What the three have in common is that the storage being named is **somebody else's** — a device's
   * registers, reached through an address the program was handed. Everywhere else a type is resolved
   * through `resolveType`, whose own `VolatileType` case says why the qualifier means nothing there.
   */
  protected def resolveQualified(t: TypeRef, subst: Map[String, Type]): Type = t match
    case VolatileType(inner) => at(t.pos)(Type.Volatile(volatileScalar(resolveType(inner, subst))))
    case other               => resolveType(other, subst)

  /** Holds what a `volatile` was written on to a scalar or a raw pointer.
   *
   * The qualifier says the compiler must emit exactly the accesses the source wrote, which is a
   * promise about **one** load and **one** store — so it is only meaningful where an access is one
   * instruction. An aggregate is qualified by qualifying the fields of it that are registers, which
   * is what every device header does and what lets a shadow field opt out; anything counted is
   * refused outright, since a retain that may not be elided is not a request anybody can act on.
   */
  private def volatileScalar(t: Type): Type = Type.underlying(t) match
    // A constrained subtype is the claim that a value **has been checked** (`16 §4`), and a register
    // holds whatever the device put there. Reading one at such a type would hand back that claim
    // unchecked, through a field selection that looks like any other — and the `ptr_cast` that made
    // the pointer is too far away to read as the licence for it. Declaring the register at the base
    // and converting what comes back puts the check where the value arrives, which is one written
    // conversion and the whole of the fix.
    case _ if constrains(t) =>
      err(s"'volatile ${show(t)}' is not a type: a register holds whatever the device put in it, and " +
        s"${show(t)} is the claim that a value has been checked. Declare the register at " +
        s"${show(Type.underlying(t))} and convert what you read")
    case _: Type.Integer | _: Type.Floating | Type.Char | Type.Bool => t
    // A trait object is two words, so an access to one is two accesses however it is written — which
    // is the one promise the qualifier makes, and the one it could not keep here. It is also nothing
    // a device could have put there: what the second word points at is a method table this compiler
    // emitted.
    case _ if Type.erased(t) =>
      err(s"'volatile ${show(t)}' is not a type: an object over a trait is a pair of words, so " +
        "touching one is two accesses whatever the source says — and the table beside the value is " +
        "this program's, not a device's")
    case _: Type.Ptr | _: Type.CFn                                  => t
    case _: Type.Ref | _: Type.Weak | _: Type.View =>
      err(s"'volatile ${show(t)}' is not a type: the qualifier says every access is emitted exactly " +
        "as written, and a counted value's accesses come with retains and releases that no rule " +
        "here could hold still — device memory is reached with a raw pointer")
    case _: Type.Struct =>
      err(s"'volatile ${show(t)}' is not a type: a register block is qualified one field at a time, " +
        s"so write 'volatile' on the fields of '${show(t)}' that are registers — which is also what " +
        "lets a shadow field in the middle of one stay ordinary")
    case _ =>
      err(s"'volatile ${show(t)}' is not a type: 'volatile' qualifies a scalar or a raw pointer, " +
        "since it promises the one load or the one store the source wrote and nothing else")

  /** Whether a type constrains which **values** it has, as against merely having an identity of its
   * own. A bare `new` derivation is nominal and asserts nothing about a value (`16 §2`), so there is
   * nothing for a register to arrive holding that the type would have promised was checked.
   */
  private def constrains(t: Type): Boolean = t match
    case c: Type.Constrained => c.lo.isDefined || c.hi.isDefined || c.predFn.isDefined || constrains(c.base)
    case _                   => false

  /** Whether the type about to be resolved is what a `*` points at, rather than a value in its own
   * right. It is the one thing an `opaque` struct may be outside the module declaring it (`15 §9`).
   *
   * Set for exactly **one** level and cleared the moment any resolution begins, which is what keeps
   * it honest under nesting: `*Outer` sets it, `Outer`'s own by-value field clears it before
   * resolving, so a layout reached through a pointer is not itself excused by that pointer. The
   * `indirection` counter next door answers a different question — whether a *cycle* is finite — and
   * is deliberately not reused, since it stays raised for the whole subtree.
   */
  private var pointee = false

  private def asPointee(resolve: => Type): Type = {
    pointee = true
    try resolve
    finally pointee = false
  }

  private def resolveTypeAt(t: TypeRef, subst: Map[String, Type]): Type = {
    val behindPointer = pointee
    pointee = false

    resolveShape(t, subst, behindPointer)
  }

  private def resolveShape(t: TypeRef, subst: Map[String, Type], behindPointer: Boolean): Type = t match
    case PtrType(inner) =>
      traitObject(inner, subst, "*")
        .fold(Type.Ptr(addressable(asPointee(underIndirection(resolveQualified(inner, subst))), "'*'")))(Type.Ptr.apply)

    // Everywhere but a field, an element, and a pointee, what is being named is a **value** — what a
    // binding holds, what a parameter receives, what a call hands back — and a value read out of a
    // volatile place is an ordinary value. There is nothing left for the qualifier to say by then,
    // so writing it there would read as a promise about accesses that have already happened.
    case VolatileType(inner) =>
      err(s"'volatile ${inner.show}' is the type of *storage*, and this is a value — what a read of a " +
        s"volatile place hands back is an ordinary '${inner.show}'. The qualifier goes where the " +
        s"storage is named: a struct field, an element, or the pointee of a '*T', as " +
        s"'*volatile ${inner.show}'")
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
    case ArrayType(None, elem, ro) =>
      Type.Slice(addressable(underIndirection(resolveQualified(elem, subst)), "a slice"), readOnly = ro)
    // A bound is a compile-time constant, which a `const` is and a call is not (`13 §7`).
    //
    // **The substitution reaches the bound as well as the element**, so a length may measure the
    // block's own type parameter: `[sizeof(T) * 3 + 1]u8` is a buffer sized for whatever `T` turns
    // out to be, which is what a generic renderer needs and what no fixed number can express.
    //
    // A bound over a parameter has no value during the walk that checks the generic body itself,
    // because there is no `T` yet — and that is not a mistake to report, since the body is analyzed
    // again for each instantiation with the parameter bound to a real type. The array stands at
    // length zero for that one walk; every array the program actually gets is built by the later
    // pass, where the measurement answers.
    case ArrayType(Some(len), elem, _) =>
      checkLengthArithmetic(len, subst)
      checkLengthNotAType(len, subst)

      val n = constInt(len, subst) match
        case Some(v) if v >= 0 && v.isValidInt => v.toInt
        case Some(v)                           => err(s"an array cannot have $v elements")
        case None if awaitsInstantiation(len, subst) => 0
        case None => err("an array length must be a constant — a literal, or a 'const' naming one")
      Type.Array(n, addressable(resolveQualified(elem, subst), "an array"))

    // `(..A)` — the tuple of a pack (`10 §10`). The pack is looked up rather than resolved, because
    // what stands for it in the substitution is already the list of parts: for the walk that checks
    // a generic body that is the two stand-ins, and for an instantiation it is the parts the subject
    // matched. Either way the tuple built here is an ordinary one, so nothing downstream of this
    // point knows a pack was written.
    case TupleType(List(PackType(n)), _) =>
      subst.get(n) match
        case Some(Type.Pack(elems)) => tupleType(elems)
        case Some(other) =>
          err(s"'..$n' is written as a type pack, and '$n' stands for ${show(other)} — a pack is " +
            "declared '[..A]' and a single type '[A]'")
        case None =>
          err(s"'..$n' names no type pack — a pack is declared in the parameter list, as '[..$n]'")

    // A tuple holds its parts the way a struct holds its fields, so a part is resolved exactly as a
    // field is — a `unit` part included, which the layout skips and the parts after it shift past.
    case TupleType(parts, false) => tupleType(parts.map(resolveType(_, subst)))

    // A pack outside the one place it may be written. `(..A)` is caught by the case above, so
    // reaching here means a bare `..A` stood where a type belongs.
    case PackType(n) =>
      err(s"'..$n' is a type pack and not a type — the only place one may be written is inside a " +
        s"tuple, as '(..$n)'")

    // C's function pointer, which is a type wherever any other is: it is one word, it is copied by
    // being copied, and nothing about it is counted. Its parts are held to exactly what an `extern`
    // holds its own to, which is nothing — what crosses the boundary is the programmer's business
    // (`12 §1`), and a signature written here is the same kind of promise the `*` already is.
    case CFnType(params, ret) =>
      Type.CFn(params.map(resolveType(_, subst)), resolveReturn(ret, subst))

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

    // A written value argument that reached here is one in a position where no parameter is a value
    // parameter — `int[4]`, or an argument of a type whose declaration takes types. The declaration
    // is what decides, and `resolveArgs` has already read it, so what is left is to say so.
    case ValueArgType(_) =>
      err("a value stands here, and this argument is a type — a value argument belongs where the " +
        "declaration wrote 'const', and nowhere else")

    case NamedType(n, argRefs) =>
      if argRefs.isEmpty && subst.contains(n) then subst(n)
      else
        val targs = resolveArgs(n, argRefs, subst)
        scalarType(n) match
          case Some(s) => plain(n, targs, s)
          // A declared type is named in this module's terms — its own, or a module's it names in
          // full (`13 §3`) — so what the tables are asked for is the key that resolves to.
          case None =>
            typeKey(n) match
              case Some(key) if structDecls.contains(key) =>
                if !behindPointer then checkLayoutKnown(key, n)
                instantiateStruct(key, targs)
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
              case None => unresolvedErr(s"unknown type '$n'")

  /** A written argument list, resolved **against the declaration whose parameters they fill** —
   * because which argument is a type and which is a value is the declaration's fact and nothing the
   * grammar can see.
   *
   * `Buf[4]` and `Buf[int]` are the same shape to a parser, and so are `Buf[N]` and `Box[T]`: a bare
   * name is a type as far as the syntax goes. So a name standing in a value position is read here as
   * the expression it is, which is what lets one value parameter be passed straight to another —
   * `Buf[N]` inside a block declaring `const N`. Rust resolves a bare path in a const-argument
   * position the same way, for the same reason.
   *
   * An argument that could not be a type at all arrives as a `ValueArgType` and is folded. One in a
   * position the declaration made a *type* parameter is refused by `resolveShape`, which is where
   * every other "this is not a type" is said.
   */
  private def resolveArgs(n: String, argRefs: List[TypeRef], subst: Map[String, Type]): List[Type] = {
    val values = typeKey(n).fold(Map.empty[String, TypeRef])(nominalValues)

    if values.isEmpty then argRefs.map(resolveType(_, subst))
    else
      val tparams = typeKey(n).fold(List.empty[String])(nominalTparams)

      argRefs.zipWithIndex.map { (ref, i) =>
        tparams.lift(i).filter(values.contains) match
          case Some(tp) => valueArg(ref, recover(Type.Unknown)(resolveType(values(tp), Map.empty)), subst)
          case None     => resolveType(ref, subst)
      }
  }

  /** One **value** argument: the expression it was written as, folded to the constant that goes in
   * the type's identity.
   *
   * A value parameter of the *enclosing* declaration stands at zero here for the walk that checks a
   * generic body, exactly as an array length written over one does — there is no argument yet, and
   * the tree that walk builds is discarded.
   */
  private def valueArg(ref: TypeRef, ty: Type, subst: Map[String, Type]): Type = {
    val written = ref match
      case ValueArgType(e)      => Some(e)
      case NamedType(name, Nil) => Some(Ident(name))
      case _                    => None

    written match
      case None =>
        err(s"this argument stands where the declaration wrote 'const', so a value belongs here " +
          s"rather than a type — one of ${show(ty)}")
      case Some(e) =>
        constArgValue(e, subst).orElse(variantTag(e, ty)) match
          case Some(v)                               => Type.ConstArg(v, ty)
          case None if awaitsInstantiation(e, subst) => Type.ConstArg(0, ty)
          // A name that turns out to be a **type** is the likely mistake in this position, and it
          // gets its own sentence: `Buf[int]` reads as an argument list of types until the
          // declaration says otherwise, so the reader is told which of the two this slot is.
          case None =>
            e match
              case Ident(n) if typeKey(n).isDefined || scalarType(n).isDefined =>
                err(s"'$n' is a type, and this argument stands where the declaration wrote " +
                  s"'const' — so a value of ${show(ty)} belongs here")
              case _ =>
                err("a value argument must be a constant — a literal, or a 'const' naming one")
  }

  /** The tag a **simple enum's variant** stands for, where the value parameter's declared type is
   * that enum (`10 §9`).
   *
   * A simple enum's value *is* its identity — there is nothing else telling two of its variants
   * apart (`09`) — so its tag is exactly the number a type's identity wants. It is read off the
   * instantiated enum rather than recomputed from the declaration, so an explicit discriminant and
   * the gap after it are the ones the rest of the compiler already agreed on.
   *
   * A **data** enum needs no case and gets none: its variants carry values, so a number does not
   * tell two of them apart and there is no identity to be made of one.
   */
  private def variantTag(e: Expr, ty: Type): Option[BigInt] = (e, ty) match
    case (Ident(n), en: Type.Enum) if en.simple =>
      variantKey(n)
        .filter(k => variantOwner.get(k).contains(en.base))
        .flatMap(_ => en.variant(n.split('.').last))
        .map(v => BigInt(v.tag))
    case _ => None

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

  /** Holds a written callable to an arity the library declares a call trait for.
   *
   * The limit is the library's and not the language's, so what it says is where to go next: a
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
    case ArrayType(_, e, _)  => mentionsAny(e, names)
    // The question this answers is about erasure — whether a **type** is named — and a value
    // argument names none, so it can hold no forgotten one.
    case _: ValueArgType     => false
    case VolatileType(i)     => mentionsAny(i, names)
    case TupleType(parts, _) => parts.exists(mentionsAny(_, names))
    // A pack names one of the block's own parameters, so it is named here exactly as `T` would be.
    case PackType(n)         => names(n)
    case f: FnType           => mentionsAny(f.asTrait, names)
    case CFnType(ps, r)      => ps.exists(mentionsAny(_, names)) || mentionsAny(r, names)

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
      case (Param(_, NamedType(n, Nil), _, _), i) if scalarType(n).contains(Type.VaList) => i
    }.toSet

}
