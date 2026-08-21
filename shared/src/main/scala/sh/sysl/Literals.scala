package sh.sysl

/** The scalar leaves of expression analysis: how a literal takes its type, how operands that
 * must share a type reconcile a bare literal with a typed neighbour, explicit conversions, and
 * the result type of an arithmetic or bitwise operator. Nothing here widens, narrows, or
 * promotes on its own — every representation change is written by the programmer.
 */
trait Literals extends TypeResolution {

  /** The scalar type a context wants, seen through a vector: a position asking for `<4>f32` wants
   * `f32` out of a bare literal, which is then splatted into every lane.
   *
   * **This is what keeps `a * 2.0` free of a suffix and free of a construction**, and it is the same
   * reading `repr` already performs for a transparent subtype — the position's type is not what the
   * literal denotes, and both cases exist because a literal has no type of its own to defend. Without
   * it a float literal under a `<4>f32` falls all the way to `real` and is then refused for being
   * one, which reads as a missing conversion rather than as a lane count nobody wrote.
   */
  private def scalarWanted(t: Type): Type = Type.repr(t) match
    case Type.Vector(_, lane) => Type.repr(lane)
    case other                => other

  /** An integer literal takes its type from its suffix, else from the type the context
   * expects, else `int`. The default is never magnitude-dependent: a value too large for the
   * type it landed in is an error asking for a suffix, not a silent widening.
   *
   * **The expected type is consulted through `repr`**, which is the same identity `disagree` uses,
   * and the two have to be the same one or a literal is refused for having exactly the type the
   * position asked for. A qualifier says how a place is reached and never what is in it, so the value
   * a literal denotes is not `volatile` even where the field it is about to fill is: `Gpio(10, 0)`
   * against `input: volatile uint` read the expected type as no integer at all, fell back to `int`,
   * and was refused with *"'input' of 'Gpio' is volatile uint, but int was given"* — while
   * `regs.input = 10` had always been fine, because assignment reaches the field's type through
   * something that strips. A transparent constrained subtype is the same shape and was refused the
   * same way whenever its base was not `int`.
   */
  protected def intLiteral(value: BigInt, suffix: Option[String], expected: Option[Type]): TExpr = {
    val ty = suffix match
      case Some(s) =>
        scalarType(s) match
          case Some(i: Type.Integer) => i
          case _                     => err(s"'$s' is not an integer type")
      case None =>
        expected.map(scalarWanted) match
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
   *
   * Read through `repr` for the reason `intLiteral` above gives: a `volatile f32` field is a place,
   * and the value going into it is an `f32`.
   */
  protected def floatLiteral(text: String, suffix: Option[String], expected: Option[Type]): TExpr = {
    val ty = suffix match
      case Some(s) =>
        scalarType(s) match
          case Some(f: Type.Floating) => f
          case _                      => err(s"'$s' is not a floating-point type")
      case None =>
        expected.map(scalarWanted) match
          case Some(f: Type.Floating) => f
          case Some(a: Type.Abstract) => a
          case _                      => Type.Real

    TFloatLit(java.lang.Double.doubleToLongBits(text.toDouble), ty)
  }

  /** Analyzes operands that must share one type. A bare literal has no type of its own, so it
   * takes the type of a non-literal neighbour — which is what lets `n + 1` work for an `n` of
   * any width without the literal needing a suffix, and `p == null` work for any `*T`. The operands
   * that have a type are analyzed first precisely so it is available to the ones that do not.
   *
   * What it takes is the neighbour's **representation**, which for a transparent subtype is its base:
   * the operator it is about to be an operand of is the base's, so the literal is a base value and
   * has no range to satisfy. Reading `120` in `t + 120` as a `Temp` would refuse it for not being a
   * temperature when what has to be one is the sum — and the sum is checked where it is stored. A
   * derived subtype is its own representation, so a literal still may not stand beside one without
   * the cast `16 §2` asks for.
   *
   * **There are three tiers rather than two, and the middle one is `typedByPosition`.** A form whose
   * type the position supplies is not a literal — it may be a whole expression, and what it is owed
   * is a shape rather than a width — but it stands where one stands: it has nothing to offer the
   * operands beside it until something has told it what it is. So it is analyzed after everything
   * that knows its own type and before everything that knows none, which is what lets
   * `xs.load(i) * by` read its lane count off `by` while `out.store(i, xs.load(i))` stays refused —
   * nothing outside that load ever says a width there, and guessing one is the thing a run of memory
   * must never do.
   *
   * **The order is why this is a tier and not another case of `isLiteral`.** A *declared* member of
   * one of those names — `sysl.sync.Atomic.load`, which the builtin stands aside for — does have a
   * type of its own, and putting it in the same tier as the literals would leave `a.load(i) + 1` with
   * nothing in it that knows anything: the literal would fall to `int` where the member answers a
   * `u8`. In the middle tier it is still asked before the literals are, so it goes on supplying the
   * type it always did.
   */
  protected def analyzeOperands(operands: List[Expr], expected: Option[Type]): List[TExpr] = {
    val own     = operands.map(e => Option.when(!isLiteral(e) && !typedByPosition(e))(analyzeExpr(e, expected)))
    val settled = own.flatten.headOption.map(t => Type.repr(t.ty)).orElse(expected)
    val told    = operands.zip(own).map((e, t) => t.orElse(Option.when(!isLiteral(e))(analyzeExpr(e, settled))))
    val ty      = told.flatten.headOption.map(t => Type.repr(t.ty)).orElse(expected)

    operands.zip(told).map {
      case (_, Some(t)) => t
      case (e, None)    => analyzeExpr(e, ty)
    }
  }

  /** Whether an expression is one whose type the position it sits in supplies, rather than one it
   * carries itself — the middle tier above.
   *
   * `xs.load(i)` and the implicit member `.red` are the set, and the first is here for the reason
   * `MethodCalls`'
   * `vectorMemory` gives: a slice has whatever length it has, so how many lanes a run of it is read
   * as is the *receiving* type's to say, and there is nothing in the receiver to read it from.
   *
   * **The test is on the spelling, and it is allowed to be**, because the middle tier is not a
   * licence to skip anything — it moves an operand one place later in the order and hands it the
   * neighbour's type as the position's, which is what an expected type is for. So a receiver
   * declaring a `load` of its own is unharmed by matching here, while the alternative — analyzing
   * every operand speculatively to find out which of them can stand alone — would put a sandbox
   * around every binary operator in the language.
   */
  protected def typedByPosition(e: Expr): Boolean = e match
    case Call(Field(_, "load"), List(_)) => true
    // `.red` is the other one, and it is the reason the tier is worth having rather than a special
    // case: `c == .Red` is what a reader writes first, and the form has no type at all until
    // something says which type is wanted. The neighbour is what says it here, exactly as it says
    // what width a literal is.
    case _: ImplicitMember               => true
    case Call(_: ImplicitMember, _)      => true
    case _                               => false

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
   *
   * `asked` is what the reader actually wrote, where that is not the target's own name: a conversion
   * into a transparent subtype is this conversion into its base, and a refusal naming the base would
   * be answering about a type the line does not mention.
   */
  protected def convert(t: TExpr, to: Type, asked: Option[String] = None): TExpr =
    if to == Type.Str then encode(t)
    else {
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
          if !e.simple then
            err(s"only a simple enum converts to an integer — ${show(e)} carries data")
          true
        // A pointer → integer is total: an address *is* a number of `usize`'s width, so reading one
        // as that number loses nothing and yields a value nothing can dereference. It goes only this
        // way; making a pointer out of an integer is `ptr_cast` in the raw tier (`03 § Reinterpreting
        // storage`). A pointer to a trait is two words rather than an address, so it has no number.
        case (Type.Ptr(_: Type.Trait), _: Type.Integer) =>
          err("a pointer to a trait is two words — the address and the table of the type it was " +
            "erased from — so it is not a number")
        case (_: Type.Ptr, i: Type.Integer) =>
          if !i.pointerWidth then
            err(s"an address is read as 'usize' or 'isize' rather than as ${show(i)} — a fixed " +
              "width is not an address's width on every target")
          true
        case _ => false

      if !allowed then
        err(asked match
          case Some(name) => s"cannot make $name from ${show(t.ty)}"
          case None       => s"cannot convert ${show(t.ty)} to ${show(to)}")

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
    case Type.Slice(Type.Byte, _) =>
      err("a 'string' conversion encodes a char — bytes are already text or are not, so " +
        s"'${Modules.show(Library.key("from_utf8"))}(b)' is what says which")
    case other =>
      err(s"a 'string' conversion encodes a char, and ${show(other)} is not one — 'str(x)' renders " +
        "a value as text")

  /** Widens a value to the one width a renderer takes, leaving one that is already there alone.
   * The library carries one rendering per *kind* rather than one per type, so every integer meets
   * `long` or `ulong` and every float meets `real` on the way in.
   */
  protected def widen(t: TExpr, to: Type): TExpr = if t.ty == to then t else TCast(t, to).setPos(t.pos)

  /** The result type of an arithmetic or bitwise binary operator. Operands must already have
   * the same type — there is no implicit promotion, so a mixed-width expression is an error
   * asking for a conversion rather than a silent widening. `+` on two strings concatenates,
   * allocating a fresh buffer; it is deliberately strict, so `s + 5` is an error asking for
   * interpolation rather than a silent `str(5)`.
   *
   * `rhs` is where the **right operand** was written, and it is where a mismatch is reported. An
   * expression's position is otherwise its start, which is what every other diagnostic here uses —
   * but a message naming two types and complaining about the second is one the reader answers by
   * editing that operand, so the caret goes under it. The dispatched path already did this
   * (`OperatorCalls.checkOperand`), and the two disagreed until this took a position: `1 + "x"`
   * pointed at the `1` while a `Box[int] + string` pointed at the `string`.
   *
   * Only the mismatch moves. "operator is not defined for" below is about the operator and the type
   * it was applied to rather than about one operand, so it keeps the expression's own position.
   */
  protected def arithType(op: String, a: Type, b: Type, rhs: Option[Pos]): Type = {
    // Operands must agree on their *representation*: a transparent subtype meets its base and other
    // subtypes over it, while a derived type meets only itself (`repr` keeps it distinct). The
    // result is that shared representation — except two values of one derived type stay in it, since
    // arithmetic on a derived numeric yields the same derived numeric.
    val ra = Type.repr(a)
    if ra != Type.repr(b) then at(rhs)(err(s"'$op' needs matching types, got ${show(a)} and ${show(b)}"))
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
      case (Type.Ptr(_), "-")                                                             => Type.isize
      // **A vector's operators are its lane's, applied to every lane.** There is no promotion here
      // either, so the two registers agree on width and lane type before this is reached and the
      // result is that same type.
      //
      // A mask — `<N>bool` — has the bitwise three and nothing else, which is what makes
      // `(a < b) & (c < d)` the way two conditions are combined. `&&` is not among them and cannot
      // be: it short-circuits, and there is no such thing per lane.
      case (Type.Vector(_, lane), _) =>
        (Type.underlying(lane), op) match
          case (_: Type.Integer, "+" | "-" | "*" | "&" | "|" | "^" | "<<" | ">>") => result
          case (_: Type.Floating, "+" | "-" | "*" | "/")                          => result
          case (Type.Bool, "&" | "|" | "^")                                       => result
          // Integer division is refused rather than unimplemented, and the reason is worth the
          // sentence: the scalar form traps on a zero divisor and on the one signed overflow, and
          // both guards reduce a lane-wise comparison to a single `i1` — so a vector would either
          // drop the guard or trap for lanes that were fine. No machine sysl targets has an integer
          // vector divide anyway, so what looks like an omission costs a scalarized loop either way.
          case (_: Type.Integer, "/" | "%") =>
            err(s"'$op' is not defined on ${show(a)} — integer division traps per lane and a " +
              s"register traps as a whole, so divide the lanes in a loop over an array")
          case _ => err(s"operator '$op' is not defined for ${show(a)}")
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
}
