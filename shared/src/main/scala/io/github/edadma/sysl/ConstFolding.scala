package io.github.edadma.sysl

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
}
