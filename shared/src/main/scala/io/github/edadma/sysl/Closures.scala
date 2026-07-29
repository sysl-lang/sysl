package io.github.edadma.sysl

import scala.collection.mutable

/** Closures (`12 §5`–`§8`): the arrow literal, what it captures, and the type it inhabits.
 *
 * **A closure is a struct and an `impl`, and that is the whole reduction.** The struct's fields are
 * the variables the body names from the scope it was written in; the `impl` is of the call trait for
 * the closure's arity (`Fn2[A, B, R]`, spelled `Fn(A, B) -> R`), and its one member is the body with
 * those names reaching the fields instead of the locals. Everything downstream then already works
 * and is not asked to learn anything new: layout, ARC, monomorphization, trait objects, and the
 * static and dynamic halves of `§6` are the two a bound and a trait object already were.
 *
 * What is genuinely new is here and is only this — deciding which names a body captures, and binding
 * them inside the body so that reading one reaches the field. **Nothing rewrites the body**: a
 * capture is declared in the closure's own scope like any other name, with a note saying where it
 * lives, so shadowing, assignment, and a closure inside a closure are the ordinary cases of rules
 * that already hold rather than three more things for the walk to get right.
 */
trait Closures extends CallAnalysis {

  /** How many closures have been lowered, which is what makes each one's name its own. */
  private var closureCount = 0

  /** A closure literal (`12 §5`).
   *
   * The parameter types come from the context asking for a callable and the result comes from the
   * body — never the other way round, so a closure is analyzed once and what it yields is what it
   * yields.
   */
  protected def analyzeLambda(l: Lambda, expected: Option[Type]): TExpr = {
    val want = expected.flatMap(callableSignature)

    for (ws, _) <- want if ws.length != l.params.length do
      err(s"this closure takes ${quantity(l.params.length, "parameter")}, and what it is being " +
        s"used as takes ${ws.length}")

    // An annotation is read where one is written and the context supplies the rest. A parameter with
    // neither is the one shape a closure cannot be analyzed at all, and it is reported against the
    // parameter rather than against the literal, since that is where the answer would go.
    val ptypes = l.params.zipWithIndex.map { (p, i) =>
      p.typ.map(resolveType(_, tsubst))
        .orElse(want.flatMap((ws, _) => ws.lift(i)))
        .getOrElse(at(p.pos)(err(s"'${p.name}' has no type here — nothing says what this closure " +
          s"takes, so write it: '(${p.name}: T) -> …'")))
    }

    lowerClosure(l.params.map(_.name), ptypes, want.flatMap(_._2), l.body, l.pos)
  }

  /** Builds the struct, the implementation and the body of one closure, and yields the struct value
   * that *is* the closure — its captures, in the order the fields hold them.
   */
  protected def lowerClosure(
      names: List[String],
      ptypes: List[Type],
      result: Option[Type],
      body: List[Stmt],
      pos: Option[Pos],
  ): TExpr = {
    val captured = captures(body, names.toSet)
    val fields   = captured.map(n => (n, lookupOpt(n).get._2))

    // The base name holds a `$`, which no identifier and no module name may, so nothing a program
    // can write collides with one and nothing looks one up among the declarations — the same reason
    // a tuple's base holds one (`00 §13`).
    val struct = Type.Struct(s"${Modules.sep}closure$closureCount", Nil)

    closureCount += 1
    struct.fields = fields
    structInsts(struct.base) = struct

    val name        = s"${Type.mangle(struct)}.call"
    val (func, ret) = at(pos)(analyzeNested(name, names.zip(ptypes), result, body, Some((struct, captured))))

    closureFuncs += func
    funcInsts(name) = (func.params.map((n, t) => (n, t)), ret)
    registerCallTrait(struct, ptypes, ret, pos)

    // Every capture is read where the closure is *formed* (`12 §7`), so a value is copied in and a
    // `&T` takes a share — which is what the ordinary field-by-field construction of a struct
    // already does, and the reason capture needed no rule of its own.
    TStructNew(struct, captured.map(n => analyzeExpr(Ident(n).setPos(pos)))).setPos(pos)
  }

  /** `xs.map(square)` — a declared function where a callable is wanted (`12 §5`).
   *
   * **A named function is the capture-free closure**, so it is one: the same struct with no fields,
   * whose `call` is a call to the function. There is no function-pointer type beside the call trait
   * and no wrapper for a program to write, and the degenerate case costs nothing at run time —
   * an empty environment is an empty struct.
   */
  protected def functionAsCallable(written: String, ptypes: List[Type], result: Option[Type], pos: Option[Pos])
      : TExpr = {
    // The parameters are named where no program can name them, so nothing the function's own body
    // reaches is shadowed by one of them.
    val names = ptypes.indices.map(i => s"${Modules.sep}a$i").toList
    val call  = Call(Ident(written).setPos(pos), names.map(n => Ident(n).setPos(pos))).setPos(pos)

    lowerClosure(names, ptypes, result, List(ExprStmt(call).setPos(pos)), pos)
  }

  /** Files the closure's struct as an implementation of the call trait for its arity, so a bound
   * over `Fn(A) -> R` is met by it and a `&Fn(A) -> R` may be built out of it.
   *
   * The block is synthetic and there is no source `impl` behind it, which is exactly right: a
   * closure implements the call trait by being one, and nothing about the block is a promise a
   * program made and could have made differently.
   */
  private def registerCallTrait(struct: Type.Struct, ptypes: List[Type], ret: Type, pos: Option[Pos]): Unit = {
    val trName = traitKey(Type.Fn.base(ptypes.length)).getOrElse(
      at(pos)(err(s"a callable of ${quantity(ptypes.length, "parameter")} has no call trait")))

    val impl = ImplDecl(trName, NamedType(struct.base), Nil)
    val args = ptypes :+ ret

    traitImpls((trName, struct.base)) =
      List(TraitImpl(impl, args, Type.Bound(trName, args).key, "", Nil, None))

    // The member is registered as the ordinary method it is, so calling a closure is a method call
    // and needs no path of its own — its parameters are the signature `funcInsts` already holds, so
    // what is recorded here is the shape a call reads: a method, taking its receiver by address.
    memberDecls((struct.base, "call")) =
      MethodDecl("call", Some(RecvMode.ByPtr), isProperty = false, Nil, Nil, None, Nil)
  }

  /** The call trait a value of this type implements, where it implements one — which is what makes
   * it a thing that can be called.
   *
   * The four shapes are the ones `§6` and `§8` produce: a bounded type parameter (a bare arrow's
   * sugar, monomorphized), a trait object behind either mode (the boxed callable), and a concrete
   * type with an implementation — which is a closure's own struct after monomorphization, and is
   * also any type a program writes an implementation of the call trait for.
   */
  protected def callableOf(t: Type): Option[Type.Bound] = receiverType(t) match
    case a: Type.Abstract => a.bounds.find(b => Type.Fn.parts(b.name, b.args).isDefined)
    case tr: Type.Trait   => Option.when(Type.Fn.parts(tr.name, tr.args).isDefined)(tr.bound)
    case named: Type.Named =>
      (0 to Type.Fn.maxArity).view
        .flatMap(n => traitKey(Type.Fn.base(n)))
        .flatMap(name => implsOf(name, named.base).map(ti => Type.Bound(name, ti.written)))
        .headOption
    case _ => None

  /** `f(args)` where `f` is a value rather than a name the program declared (`12 §6`).
   *
   * It is a call to the call trait's one member, so it is the ordinary method call it looks like —
   * which is what makes the bare-arrow parameter a direct call and the `&Fn` field an indirect one
   * with nothing here choosing between them. The receiver decides, exactly as it does everywhere.
   */
  protected def callCallable(recv: TExpr, args: List[Expr], expected: Option[Type]): TExpr =
    callMethodOn(recv, "call", args, expected)

  /** `b.on_click(7)` — a **field** holding a callable, called through the selection that reads it.
   *
   * A field is only reached this way where no method of that name exists, so a type never loses a
   * method to a field: the two share a spelling and the method wins, which is the order every other
   * lookup already takes. It is what makes `struct Button` with an `on_click: &Fn(int) -> unit` a
   * usable shape rather than one whose field has to be read into a local before it can be called.
   */
  protected def callableField(
      rty: Type,
      name: String,
      recv: TExpr,
      args: List[Expr],
      expected: Option[Type],
  ): Option[TExpr] = rty match
    case s: Type.Struct =>
      s.fieldType(name).filter(callableOf(_).isDefined).map { fty =>
        checkFieldVisible(s.base, name)
        callCallable(TField(autoDeref(recv), s.slot(s.fieldIndex(name)), fty), args, expected)
      }
    case _ => None

  /** The signature a type asking for a callable asks for: what it takes, and what it yields where
   * the type says so.
   *
   * The three shapes are the three `§6` names — a bounded type parameter (which is what the bare
   * arrow a parameter wrote became), a trait object (`&Fn(A) -> R`), and the trait itself, which is
   * what a bound's argument list reads as. A result is `None` where the context genuinely does not
   * fix one, which is not the same as fixing it to `unit`.
   */
  protected def callableSignature(t: Type): Option[(List[Type], Option[Type])] = t match
    case Type.Ref(tr: Type.Trait, _) => fnParts(tr)
    case Type.Ptr(tr: Type.Trait)    => fnParts(tr)
    case tr: Type.Trait              => fnParts(tr)
    case a: Type.Abstract            => a.bounds.flatMap(b => fnParts(Type.Trait(b.name, b.args))).headOption
    case _                           => None

  private def fnParts(tr: Type.Trait): Option[(List[Type], Option[Type])] =
    Type.Fn.parts(tr.name, tr.args).map((ps, r) => (ps, Option.unless(r == Type.Unknown)(r)))

  /** The names a body reads from the scope it was written in, in the order it first reads them.
   *
   * This is a walk over what was *written* rather than over what it resolved to, which is what lets
   * it run before the body is analyzed — and it has to, since the body cannot be analyzed until the
   * type holding its captures exists. A name is a capture when the enclosing scope has one and the
   * body did not declare it: a parameter, a local, or a loop variable of the body's own shadows the
   * outer name exactly as it would anywhere else, and a name that resolves to a declaration rather
   * than to a local is reached the way any other function reaches it.
   */
  private def captures(body: List[Stmt], bound: Set[String]): List[String] = {
    val found = mutable.LinkedHashSet.empty[String]

    def walk(node: Any, bound: Set[String]): Set[String] = node match
      case Ident(n) =>
        if !bound(n) && lookupOpt(n).isDefined then found += n
        bound
      // A binding is in scope for what comes *after* it, so the shadow starts at the declaration and
      // the initializer is still read outside it — `var n = n` captures the outer `n`.
      case VarDecl(n, _, init)   => init.foreach(walk(_, bound)); bound + n
      case ValDecl(n, _, v, _)   => walk(v, bound); bound + n
      case ConstDecl(n, _, v, _) => walk(v, bound); bound + n
      case FuncDecl(_, _, ps, _, b, _, _, _, _) => scoped(b, bound ++ ps.map(_.name)); bound
      // A closure inside this one captures from further out through this one, so what it reads is
      // read here too — which is what makes capture reach through a nesting (`12 §5a`).
      case Lambda(ps, b) => scoped(b, bound ++ ps.map(_.name)); bound
      case For(_, n, it, b, e) =>
        walk(it, bound)
        scoped(b, bound + n)
        e.foreach(scoped(_, bound))
        bound
      case MatchArm(ps, guard, b) =>
        val inArm = bound ++ ps.flatMap(patternNames)

        guard.foreach(walk(_, inArm))
        scoped(b, inArm)
        bound
      // A statement list threads its bindings along, since each declaration is in scope for the ones
      // after it; anything else is walked for its parts with the same bindings throughout.
      case stmts: List[?] => stmts.foldLeft(bound)((b, s) => walk(s, b))
      case p: Product     => p.productIterator.foreach(walk(_, bound)); bound
      case _              => bound

    // A block is its own scope, so what it declares is gone at its end and nothing after it sees one.
    def scoped(stmts: List[Stmt], bound: Set[String]): Unit = walk(stmts, bound)

    scoped(body, bound)
    found.toList
  }

  /** Every name a pattern binds, which shadows inside the arm it introduces. */
  private def patternNames(p: Pattern): List[String] = p match
    case IdentPattern(n)       => List(n)
    case VariantPattern(_, ps) => ps.flatMap(patternNames)
    case TuplePattern(ps)      => ps.flatMap(patternNames)
    case StructPattern(_, fs)  => fs.flatMap((_, sub) => patternNames(sub))
    case _                     => Nil
}
