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
          // A type **parameter**, during the definition-time pass of `14 §4`. It is opaque, so there
          // is no width to check against and no representation to pick — but it is still the type
          // the literal has, since what the instantiation makes of `T` is what the literal is read
          // as when the body is lowered. Taking `int` instead would make the `1` in a `[T: Sub]`
          // body's `x - 1` ask for `Sub[int]` where what the body means is `T`'s own subtraction.
          case Some(a: Type.Abstract) => a
          case _                      => Type.Int

    ty match
      case i: Type.Integer if !Type.fits(value, i) => err(s"the literal $value does not fit ${show(i)}")
      case _                                       =>

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
          case Some(a: Type.Abstract) => a
          case _                      => Type.Real

    TFloatLit(hexDouble(text), ty)
  }

  /** Analyzes operands that must share one type. A bare literal has no type of its own, so it
   * takes the type of a non-literal neighbour — which is what lets `n + 1` work for an `n` of
   * any width without the literal needing a suffix, and `p == null` work for any `*T`. The
   * non-literals are analyzed first precisely so their type is available to the literals.
   *
   * What it takes is the neighbour's **representation**, which for a transparent subtype is its base:
   * the operator it is about to be an operand of is the base's, so the literal is a base value and
   * has no range to satisfy. Reading `120` in `t + 120` as a `Temp` would refuse it for not being a
   * temperature when what has to be one is the sum — and the sum is checked where it is stored. A
   * derived subtype is its own representation, so a literal still may not stand beside one without
   * the cast `16 §2` asks for.
   */
  protected def analyzeOperands(operands: List[Expr], expected: Option[Type]): List[TExpr] = {
    val fixed = operands.map(e => Option.when(!isLiteral(e))(analyzeExpr(e, expected)))
    val ty    = fixed.flatten.headOption.map(t => Type.repr(t.ty)).orElse(expected)

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

  /** The type an adaptable literal falls back to, worked out from how it is written rather than
   * by analyzing it.
   *
   * Inference needs a type for every argument before it can decide what any parameter is, and a
   * literal at a parameter still being solved has nothing to give it but this default — while
   * *analyzing* the literal against that default is a range check it should not have to pass. A
   * `5000000000` headed for a `u64` is a fine argument, and asking it to be an `int` first is how
   * it would be refused for being one. So the fallback is read off the spelling, and the literal
   * is analyzed once, later, against the parameter the solution gave it.
   */
  protected def literalDefault(e: Expr): Option[Type] = e match
    case IntLit(_, None)     => Some(Type.Int)
    case FloatLit(_, None)   => Some(Type.Real)
    case Unary("-", operand) => literalDefault(operand)
    case _                   => None

  /** An explicit scalar conversion. Every pair that has a meaning is listed; nothing widens,
   * narrows, or changes representation without being written.
   */
  protected def convert(t: TExpr, to: Type): TExpr = if to == Type.Str then encode(t) else {
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

  /** `string(c)` — one scalar value encoded as the bytes that spell it (`04`).
   *
   * It is the one conversion whose result is not a scalar, so it produces fresh bytes rather than a
   * reinterpretation of the ones already there, and it allocates where every other conversion does
   * not. The encoding is the one `str(c)` performs, which is why the two agree to the byte and why
   * this needs nothing of its own underneath.
   *
   * Nothing else converts. A number has a *rendering* rather than an encoding — there is no one
   * answer, as `f"…%x…"` shows — so it is sent to the form that renders, and a value already made of
   * bytes is sent to the form that validates them.
   */
  private def encode(t: TExpr): TExpr = Type.underlying(t.ty) match
    case Type.Char => TStr(t)
    case Type.Str  => err("a 'string' conversion encodes a char, and this value is already a string")
    case Type.Slice(Type.Byte) =>
      err("a 'string' conversion encodes a char — bytes are already text or are not, so " +
        s"'${Modules.show(Library.key("from_utf8"))}(b)' is what says which")
    case other =>
      err(s"a 'string' conversion encodes a char, and ${show(other)} is not one — 'str(x)' renders " +
        "a value as text")

  /** Widens a value to the one width a renderer takes, leaving one that is already there alone.
   * The prelude carries one rendering per *kind* rather than one per type, so every integer meets
   * `long` or `ulong` and every float meets `real` on the way in.
   */
  protected def widen(t: TExpr, to: Type): TExpr = if t.ty == to then t else TCast(t, to).setPos(t.pos)

  /** The same, for the value a `Display` renderer is handed.
   *
   * One kind of value does not *widen* into its renderer: an integer past 64 bits has no width to
   * widen to that would still hold it, so it arrives as the digits `str` writes and the prelude is
   * left with the padding. Rendering it any other way would truncate silently, which is the one
   * outcome a renderer must not have — the number would simply print as its low half.
   */
  protected def rendered(t: TExpr, to: Type): TExpr = Type.underlying(t.ty) match
    case i: Type.Integer if i.bits > 64 => TStr(t)
    case _                              => widen(t, to)

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
      // C's `ptrdiff_t`: the one arithmetic two pointers have, and the only operator whose result is
      // neither operand's type nor their shared representation. It counts *elements*, so it is the
      // inverse of `&p[n]` (`03`) — which is why the pointee decides the stride and why two pointers
      // into different objects are the programmer's business, exactly as `p[i]` already is.
      case (Type.Ptr(_), "-")                                                             => Type.Isize
      case _ => err(s"operator '$op' is not defined for ${show(a)}")
  }

  /** The type a unary operator yields at `a`, by the rule `arithType` uses for a binary one: a
   * transparent subtype's arithmetic happens at its base and yields the base, while a derived one's
   * happens at itself and yields itself (`16 §3`).
   */
  protected def unaryType(a: Type): Type = a match
    case c: Type.Constrained if c.derived => a
    case _                                => Type.repr(a)

  /** What a value has to satisfy to come to have type `t`, where `t` is a constrained subtype that
   * asks anything of its values. A subtype with neither a range nor a predicate — `type Stamp = new
   * i64`, which exists to be a distinct name — asks nothing, so producing one costs no instruction.
   *
   * This is the question `16 §4` is about, asked of a type rather than of a syntactic form: every
   * site that gives a value this type consults it, so the set of checked sites is the set of sites
   * that produce one, by construction rather than by a list somebody kept up to date.
   */
  protected def constraintOf(t: Type): Option[Type.Constrained] = t match
    case c: Type.Constrained if c.lo.nonEmpty || c.hi.nonEmpty || c.predFn.nonEmpty => Some(c)
    case _                                                                          => None

  /** An operation whose *result* is a constrained subtype, checked where it is made. Only a derived
   * subtype's own arithmetic produces one — a transparent subtype computes at its base, so its
   * results are base values and the check waits for the store that gives one the subtype again.
   *
   * Without this, `16 §3` (a derivation's operators produce itself) and `16 §4` (every produce site
   * is checked) could not both be true: `Slot(199) + Slot(1)` would be a `Slot` holding 200.
   */
  protected def produced(t: TExpr): TExpr = constraintOf(t.ty) match
    case Some(c) => TConstrainedCheck(t, c).setPos(t.pos)
    case None    => t

  /** Renders a decimal float literal as an LLVM hex double — the textual form that survives
   * the round-trip without losing bits.
   */
  protected def hexDouble(text: String): String =
    f"0x${java.lang.Double.doubleToLongBits(text.toDouble)}%016X"
}
