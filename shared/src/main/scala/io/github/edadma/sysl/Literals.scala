package io.github.edadma.sysl

/** The scalar leaves of expression analysis: how a literal takes its type, how operands that
 * must share a type reconcile a bare literal with a typed neighbour, explicit conversions, and
 * the result type of an arithmetic or bitwise operator. Nothing here widens, narrows, or
 * promotes on its own — every representation change is written by the programmer.
 */
trait Literals extends TypeResolution {

  /** An integer literal takes its type from its suffix, else from the type the context
   * expects, else `int`. The default is never magnitude-dependent: a value too large for the
   * type it landed in is an error asking for a suffix, not a silent widening.
   */
  protected def intLiteral(value: BigInt, suffix: Option[String], expected: Option[Type]): TExpr = {
    val ty = suffix match
      case Some(s) =>
        scalarType(s) match
          case Some(i: Type.Integer) => i
          case _                     => err(s"'$s' is not an integer type")
      case None =>
        expected match
          case Some(i: Type.Integer) => i
          case _                     => Type.Int

    if !Type.fits(value, ty) then err(s"the literal $value does not fit ${show(ty)}")

    TIntLit(value, ty)
  }

  /** A float literal takes its type from its suffix, else from the expected type when that is
   * a float, else `real`. An integer literal never becomes a float on its own — writing `1`
   * where a `real` is wanted is a type error, not a silent conversion.
   */
  protected def floatLiteral(text: String, suffix: Option[String], expected: Option[Type]): TExpr = {
    val ty = suffix match
      case Some(s) =>
        scalarType(s) match
          case Some(f: Type.Floating) => f
          case _                      => err(s"'$s' is not a floating-point type")
      case None =>
        expected match
          case Some(f: Type.Floating) => f
          case _                      => Type.Real

    TFloatLit(hexDouble(text), ty)
  }

  /** Analyzes operands that must share one type. A bare literal has no type of its own, so it
   * takes the type of a non-literal neighbour — which is what lets `n + 1` work for an `n` of
   * any width without the literal needing a suffix, and `p == null` work for any `*T`. The
   * non-literals are analyzed first precisely so their type is available to the literals.
   */
  protected def analyzeOperands(operands: List[Expr], expected: Option[Type]): List[TExpr] = {
    val fixed = operands.map(e => Option.when(!isLiteral(e))(analyzeExpr(e, expected)))
    val ty    = fixed.flatten.headOption.map(_.ty).orElse(expected)

    operands.zip(fixed).map {
      case (_, Some(t)) => t
      case (e, None)    => analyzeExpr(e, ty)
    }
  }

  /** Whether an expression is a literal with no type of its own. A suffixed numeric literal
   * has already said what it is, so it counts as fixed rather than adaptable.
   */
  protected def isLiteral(e: Expr): Boolean = e match
    case IntLit(_, None) | FloatLit(_, None) => true
    case NullLit()                           => true
    case Unary("-", operand)                 => isLiteral(operand)
    case _                                   => false

  /** An explicit scalar conversion. Every pair that has a meaning is listed; nothing widens,
   * narrows, or changes representation without being written.
   */
  protected def convert(t: TExpr, to: Type): TExpr = {
    // A written conversion is licensed to reach a constrained value's base representation, so the
    // source kind is read through `underlying`: `f64(m)` unwraps a derived `Meters`, `int(age)` an
    // `Age`. The target of a scalar conversion is always a plain scalar, so only the source strips.
    val allowed = (Type.underlying(t.ty), to) match
      case (_: Type.Integer, _: Type.Integer)   => true
      case (_: Type.Integer, _: Type.Floating)  => true
      case (_: Type.Floating, _: Type.Integer)  => true
      case (_: Type.Floating, _: Type.Floating) => true
      case (Type.Char, _: Type.Integer)         => true // total: every char is an integer
      case (_: Type.Integer, Type.Char)         => true // partial: traps on a non-scalar value
      case (Type.Char, Type.Char)               => true
      // Enum → integer is total: every enum value is one of its declared discriminants. Only a
      // simple enum has an integer value to give; a data enum is a tagged union, not a number.
      case (e: Type.Enum, _: Type.Integer) =>
        if !e.simple then err(s"only a simple enum converts to an integer — ${show(e)} carries data")
        true
      case _                                    => false

    if !allowed then err(s"cannot convert ${show(t.ty)} to ${show(to)}")

    TCast(t, to)
  }

  /** Widens a value to the one width a renderer takes, leaving one that is already there alone.
   * The prelude carries one rendering per *kind* rather than one per type, so every integer meets
   * `long` or `ulong` and every float meets `real` on the way in.
   */
  protected def widen(t: TExpr, to: Type): TExpr = if t.ty == to then t else TCast(t, to).setPos(t.pos)

  /** The result type of an arithmetic or bitwise binary operator. Operands must already have
   * the same type — there is no implicit promotion, so a mixed-width expression is an error
   * asking for a conversion rather than a silent widening. `+` on two strings concatenates,
   * allocating a fresh buffer; it is deliberately strict, so `s + 5` is an error asking for
   * interpolation rather than a silent `str(5)`.
   */
  protected def arithType(op: String, a: Type, b: Type): Type = {
    // Operands must agree on their *representation*: a transparent subtype meets its base and other
    // subtypes over it, while a derived type meets only itself (`repr` keeps it distinct). The
    // result is that shared representation — except two values of one derived type stay in it, since
    // arithmetic on a derived numeric yields the same derived numeric.
    val ra = Type.repr(a)
    if ra != Type.repr(b) then err(s"'$op' needs matching types, got ${show(a)} and ${show(b)}")
    val result = a match
      case c: Type.Constrained if c.derived && a == b => a
      case _                                          => ra
    (Type.underlying(a), op) match
      case (_: Type.Integer, "+" | "-" | "*" | "/" | "%" | "<<" | ">>" | "&" | "|" | "^") => result
      case (_: Type.Floating, "+" | "-" | "*" | "/")                                      => result
      case (Type.Str, "+")                                                                => result
      case _ => err(s"operator '$op' is not defined for ${show(a)}")
  }

  /** Renders a decimal float literal as an LLVM hex double — the textual form that survives
   * the round-trip without losing bits.
   */
  protected def hexDouble(text: String): String =
    f"0x${java.lang.Double.doubleToLongBits(text.toDouble)}%016X"
}
