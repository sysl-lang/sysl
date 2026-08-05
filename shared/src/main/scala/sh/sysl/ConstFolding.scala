package sh.sysl

import scala.collection.mutable

/** Constants, module-level `val`s, and the constant folder that gives a `const` its value.
 *
 * A constant is a scalar and nothing else (`13 §7`), which is not an arbitrary restriction but the
 * shape of what a constant expression can produce: there is no aggregate literal to fold to, and a
 * table would be storage rather than a value. That is what lets a constant be answered without any
 * of the type tables — registered in the first hoisting pass and already nameable from an array
 * bound in the second.
 *
 * The folder underneath is small on purpose. It evaluates what `13 §7` says a constant expression is
 * and nothing more, and it answers `None` rather than guessing, so the two positions that consume it
 * — an array bound and an enum discriminant — can report "not a constant expression" against the
 * expression the programmer actually wrote.
 *
 * A `val` is the other half and needs none of this: its type is **written** rather than inferred
 * (`13 §2`), so it is answered without looking at the initializer, which is what lets one `val` be
 * read from another's neighbourhood with no ordering between them — exactly as two functions may
 * call each other.
 */
trait ConstFolding extends ImportResolution {

  /** Resolving a written type, which a constant's declaration carries. Defined by `TypeResolution`,
   * which is mixed in after this: the two are mutually recursive, since an array bound is a constant
   * expression and a constant is declared with a type.
   */
  protected def resolveType(t: TypeRef, subst: Map[String, Type]): Type

  /** The same, in one of the positions that may carry a `volatile` qualifier — a struct field among
   * them, which is why this is visible from here.
   */
  protected def resolveQualified(t: TypeRef, subst: Map[String, Type]): Type

  /** A written type with any `volatile` taken off the front of it — for the one place that reads a
   * field list as a **parameter** list, where what travels is the value rather than the storage.
   */
  protected def unqualifiedRef(t: TypeRef): TypeRef = t match
    case VolatileType(inner) => unqualifiedRef(inner)
    case other               => other

  /** Recognising a scalar type name, which is all a constant may be declared as. */
  protected def scalarType(name: String): Option[Type]

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
  protected def constInt(e: Expr, subst: Map[String, Type] = Map.empty): Option[BigInt] =
    fold(e, subst).collect { case IntLit(v, _) => v }

  // --- module-level `val`s ---------------------------------------------------------------

  /** The key a written name for module storage resolves to — a `val` or a `static var`.
   *
   * The two are one lookup because they are one namespace and one kind of thing: storage the module
   * owns, reached by name. Which of the two a key names decides only whether the storage may be
   * *written*, and that is asked separately, where it matters.
   */
  protected def globalKey(written: String): Option[String] =
    resolveName(written)(k => valDecls.contains(k) || staticVarDecls.contains(k))

  /** Whether a key names storage that may be written — a `static var` rather than a `val`. */
  protected def globalWritable(key: String): Boolean = staticVarDecls.contains(key)

  /** The type a module-level `val` was declared with.
   *
   * Written rather than inferred (`13 §2`), which is what lets this be answered without looking at
   * the initializer — so one `val` may be read from another's neighbourhood with no ordering
   * between them, exactly as two functions may call each other.
   */
  protected def globalType(key: String): Type = valTypes.getOrElseUpdate(key, {
    inDecl(key)(staticVarDecls.get(key).orElse(valDecls.get(key)) match
      case Some(v: VarDecl) => v.typ.map(resolveType(_, Map.empty)).getOrElse(Type.Unknown)
      case Some(v: ValDecl) => v.typ.map(resolveType(_, Map.empty)).getOrElse(Type.Unknown)
      case _                => Type.Unknown)
  })

  // --- `extern` variables -----------------------------------------------------------------

  /** The key a written **`extern` variable** name resolves to. */
  protected def externVarKey(written: String): Option[String] =
    resolveName(written)(externVarDecls.contains)

  /** The type an `extern` variable was declared with. Written for the reason a `val`'s is and one
   * more: there is no initializer to infer it from, because the storage was laid down elsewhere.
   */
  protected def externVarType(key: String): Type = externVarTypes.getOrElseUpdate(key, {
    val decl = externVarDecls(key)

    inDecl(key)(resolveType(decl.typ, Map.empty))
  })

  /** Folds a constant expression to the literal it denotes, or `None` where it is not one.
   *
   * The set is deliberately small and closed: literals, other constants, conversions, and the
   * unary and binary operators. There are no calls — a call in a constant expression is a request
   * for compile-time evaluation of arbitrary code, which is a language of its own — and a `string`
   * folds only from a literal, since `+` on strings allocates and a compile-time concatenation
   * would be a different operation wearing the same spelling.
   */
  protected def fold(e: Expr, subst: Map[String, Type] = Map.empty): Option[Expr] = e match
    case l: IntLit   => Some(l.copy(suffix = None))
    case l: FloatLit => Some(l.copy(suffix = None))
    case l: BoolLit  => Some(l)
    case l: CharLit  => Some(l)
    case l: StrLit   => Some(l)

    case Ident(n) => constKey(n).map(k => constLiteral(k))

    case Unary("-", operand) =>
      fold(operand, subst).collect {
        case IntLit(v, _)   => IntLit(-v, None)
        case FloatLit(t, _) => FloatLit((-t.toDouble).toString, None)
      }
    case Unary("!", operand) => fold(operand, subst).collect { case BoolLit(b) => BoolLit(!b) }
    case Unary("~", operand) => fold(operand, subst).collect { case IntLit(v, _) => IntLit(~v, None) }

    // A conversion is written, so what it does at compile time is what it does at run time: a
    // narrowing wraps and a float-to-integer truncates toward zero (`01`). Silently doing something
    // gentler here would make a constant mean one thing and the same expression written out mean
    // another.
    case Call(Ident(name), List(arg)) =>
      for
        target <- scalarType(name)
        value  <- fold(arg, subst)
        out    <- convert(value, target)
      yield out

    case Binary(op, l, r) => for (a <- fold(l, subst); b <- fold(r, subst); v <- binary(op, a, b)) yield v
    case Compare(List(l, r), List(op)) =>
      for (a <- fold(l, subst); b <- fold(r, subst); v <- binary(op, a, b)) yield v

    // `sizeof(T)` and `alignof(T)` are compile-time constants (`03 § Reinterpreting storage`), so
    // they fold exactly as a literal does. That is what makes them usable in the two positions this
    // folder serves — an array bound and an enum discriminant — as well as in a `const`, which is
    // where a program names the block size a slab is laid out in.
    //
    // **The substitution is what lets the measured type be the caller's own parameter.** A generic
    // body is analyzed once per instantiation with its parameters bound to the concrete arguments
    // (`instantiateFunc`), so `sizeof(T)` inside one has a width at every point it is compiled —
    // and resolving it against an empty map instead would report the parameter as an unknown type,
    // which is a name the reader can see is declared right there.
    case LayoutOf(what, tr) => layoutBytes(what, resolveType(tr, subst)).map(n => IntLit(n, None))

    case _ => None

  /** The bytes `sizeof(T)` / `alignof(T)` answer with, or `None` where the type has no answer here.
   *
   * There are two ways to have no answer, and neither is a mistake to report. A **type parameter**
   * is concrete at every instantiation and stands in for itself only during the walk that reports
   * what a generic body's bounds do not license, so the measurement is not wrong there — it is not
   * being made yet. A **poisoned** type has already been complained about once, and saying its width
   * is unknown would be a second complaint about the same thing.
   */
  protected def layoutBytes(what: String, ty: Type): Option[Int] = Type.underlying(ty) match
    case _: Type.Abstract | Type.Unknown => None
    case t                               => Some(if what == "sizeof" then Layout.size(t) else Layout.align(t))

  /** Whether a constant expression does not fold **yet** rather than not folding at all: it measures
   * a type that is still a parameter, and every instantiation will supply one that is not.
   *
   * The distinction is the whole of what separates a deferred bound from a mistake. `[sizeof(T)]u8`
   * inside a generic is a well-formed array whose length nobody can name until the body is compiled
   * for a particular `T`; `[n]u8` over a variable is a length that will never be a constant however
   * many times it is instantiated. Both fail to fold, and only the second is worth a diagnostic.
   *
   * It walks the same shapes `fold` does, since a bound may measure a type inside arithmetic —
   * `[sizeof(T) * 3 + 1]u8` is the shape a decimal-digit buffer wants, and none of its parts folds
   * on its own either.
   */
  protected def awaitsInstantiation(e: Expr, subst: Map[String, Type]): Boolean = e match
    case LayoutOf(_, tr) =>
      Type.underlying(resolveType(tr, subst)) match
        case _: Type.Abstract => true
        case _                => false
    case Unary(_, operand)             => awaitsInstantiation(operand, subst)
    case Binary(_, l, r)               => awaitsInstantiation(l, subst) || awaitsInstantiation(r, subst)
    case Compare(List(l, r), _)        => awaitsInstantiation(l, subst) || awaitsInstantiation(r, subst)
    case Call(Ident(_), List(arg))     => awaitsInstantiation(arg, subst)
    case _                             => false

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

  /** A constant shift distance, as a number the fold can actually shift by.
   *
   * The ceiling is the widest integer the back end lowers rather than 64, because a shift past 64
   * is meaningful the moment a type is wider than that: `1 << 200` is an ordinary constant at
   * `u256`, and refusing it here would have made the fold narrower than the types it folds for. A
   * distance beyond every possible width is still refused — it cannot be a shift of anything, and
   * the `BigInt` it would produce is unbounded.
   */
  private def shiftBy(n: BigInt): Int =
    if n < 0 || n > Type.MaxIntegerBits then err(s"a constant shifted by $n places") else n.toInt

  private def compare(op: String, sign: Int): Option[Expr] = op match
    case "==" => Some(BoolLit(sign == 0))
    case "!=" => Some(BoolLit(sign != 0))
    case "<"  => Some(BoolLit(sign < 0))
    case "<=" => Some(BoolLit(sign <= 0))
    case ">"  => Some(BoolLit(sign > 0))
    case ">=" => Some(BoolLit(sign >= 0))
    case _    => None

  private val valTypes         = mutable.HashMap.empty[String, Type]
  private val externVarTypes   = mutable.HashMap.empty[String, Type]
  private val constTypes       = mutable.HashMap.empty[String, Type]
  private val constLits        = mutable.HashMap.empty[String, Expr]
  private val constsInProgress = mutable.LinkedHashSet.empty[String]
}
