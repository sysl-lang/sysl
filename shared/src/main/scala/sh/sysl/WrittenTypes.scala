package sh.sysl

/** The questions asked of a type **as it was written**, before anything has resolved it: does it
 * still name a parameter being solved, and is what stands where a length goes something that may
 * stand there at all (`10 §9`).
 *
 * The two are one area because an array's length is part of whether the type is a type yet —
 * `[N]int` names no type parameter in its element and is not a type until `N` is fixed — so the
 * walk over a written type and the walk over a length expression call each other and must be
 * read together.
 *
 * Nothing here resolves anything, which is why it sits below the resolver rather than inside it:
 * every rule is a question about syntax, answerable with the substitution alone.
 */
trait WrittenTypes extends GenericInstantiation {

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
    case VectorType(lanes, elem) => lengthMentions(lanes, tps) || mentions(elem, tps)
    // A value argument is not a type and is still not *fixed* until whatever it names is:
    // `Buf[N]` inside a block declaring `N` is as unresolved as `Box[T]` is.
    case ValueArgType(e)     => lengthMentions(e, tps)
    case VolatileType(inner) => mentions(inner, tps)
    case TupleType(parts, _) => parts.exists(mentions(_, tps))
    // A pack is a parameter, so `(..A)` is no more a type than `Box[T]` is until `A` is bound.
    case PackType(n)         => tps(n)
    case f: FnType          => mentions(f.asTrait, tps)
    case CFnType(params, ret) => params.exists(mentions(_, tps)) || mentions(ret, tps)
    // A projection is fixed exactly when its subject is: `T::Item` is no more a type than `T` is
    // until `T` is one, and once it is, the implementation is what says which type it names.
    case AssocType(base, _)  => mentions(base, tps)
    // A `some` result names no type at all — what it promises is a bound — so the parameters it
    // could mention are the ones a bound's own arguments carry.
    case SomeType(bs)        => bs.exists(_.args.exists(mentions(_, tps)))

  /** Whether an array **length** names one of the parameters being solved — a value parameter
   * standing for the length itself (`10 §9`), or a type parameter reached through a measurement
   * such as `[sizeof(T)]u8`.
   *
   * It walks the shapes `fold` walks, and no others: the set of expressions a length may be is
   * closed, so anything outside it cannot name a parameter because it cannot be a length at all.
   */
  protected def lengthMentions(e: Expr, tps: Set[String]): Boolean = e match
    case Ident(n)               => tps(n)
    case LayoutOf(_, tr)        => mentions(tr, tps)
    case OffsetOf(tr, _)        => mentions(tr, tps)
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
  protected def checkLengthArithmetic(len: Expr, subst: Map[String, Type]): Unit =
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
  protected def checkLengthNotAType(len: Expr, subst: Map[String, Type]): Unit = len match
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
      case VectorType(lanes, elem) =>
        lengthArithmetic(lanes, names)
        lengthIsNotAType(lanes, tparams -- names)
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
      case AssocType(base, _)      => walk(base)
      // A `some` result declares nothing about a pack or a length; it names a bound.
      case _: SomeType             => ()

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
}
