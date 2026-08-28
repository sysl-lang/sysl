package sh.sysl
package ir

/** **An operand, as data** — a register, a symbol, or a constant.
 *
 * Codegen carried these as `String` for as long as the IR was text, which meant a value's *kind* was
 * something you tested for with `startsWith("%")` and its contents were whatever had been
 * interpolated. Keeping operands as strings while making the instructions data would have moved the
 * parser one layer in rather than removing it, so this is the half that has to come with them.
 *
 * **`toString` is `render`, deliberately.** A value written into a message, a log, or one of the
 * function headers that is still assembled as text comes out as the LLVM the reader is looking for,
 * rather than as a case-class dump. It is also what let six hundred sites be converted a file at a
 * time while the sweep ran: a converted producer and an unconverted consumer went on agreeing about
 * every character between them.
 */
enum Val {

  /** A local register. The name carries no `%`: the sigil is how LLVM writes one down, not part of
   * what it is called.
   */
  case Reg(name: String)

  /** A module-level symbol — a function, a string constant, a vtable. No `@`, for the same reason.
   */
  case Global(name: String)

  /** An integer constant of whatever width the instruction says. The width is the instruction's
   * because that is where LLVM puts it, and a constant that carried its own could disagree.
   */
  case Int(value: BigInt)

  /** A floating-point constant, held as **the bits** rather than as a decimal.
   *
   * LLVM's textual form takes a hexadecimal bit pattern (`0x400921FB54442D18`) and the compiler has
   * always written one, because a decimal that has to round-trip through a printer is a decimal that
   * eventually does not. A wider float narrowed for storage narrows here too, exactly as it did.
   */
  case Float(bits: Long)

  case Bool(value: Boolean)

  /** A null address. */
  case Null

  /** A value that is about to be built up member by member, and whose bits mean nothing until it
   * is — what an `insertvalue` chain starts from.
   */
  case Undef

  /** Every bit zero, at whatever type the instruction names — an aggregate's zero, which has no
   * literal to write out.
   */
  case Zero

  /** A value whose bits are not merely unknown but may **differ between two reads of it**, which is
   * the whole difference from `Undef` and the reason LLVM grew a second word for it.
   *
   * It is what a vector is built out of: `insertelement` into `poison` says every lane not yet
   * written is one nobody may look at, so a lane the splat leaves alone costs no instruction to
   * initialize. `Undef` in the same place is weaker and stops some of the folds that make a splat
   * one broadcast instruction.
   */
  case Poison

  /** Every lane of a vector holding the same constant — `splat (i32 -1)`, which is the all-ones mask
   * a lane-wise `~` inverts against.
   *
   * The lane type is here and the vector's width is not, because that is how LLVM writes one: the
   * count comes from the type the instruction already names, so a splat that carried its own could
   * disagree with it.
   */
  case Splat(lane: LType, value: Val)

  /** **No value at all.** A zero-sized type occupies nothing, so reading one produces nothing to
   * name, and this is what the emitters carried as the empty string.
   *
   * It is a case of its own rather than an empty `Reg` because the difference is the whole point: a
   * register with no name is a bug that renders as a syntax error, and this is a value the language
   * has and the machine does not.
   */
  case Nothing

  /** A constant aggregate written out member by member — `{ ptr null, ptr @.str1, i64 5 }`, which
   * is how a string literal reaches an instruction. Each member carries its own type, because that
   * is how LLVM writes a constant aggregate and because nothing else says what the members are.
   */
  case Agg(fields: List[Arg])

  /** A constant **array**, which is the same idea between square brackets — LLVM spells the two
   * differently and means the same thing by both.
   */
  case Array(elems: List[Arg])

  /** A device address written as a number. `inttoptr` is a **constant expression**, so this is a
   * pointer that sits in the object file rather than one a prologue has to store: `reference/modules.md § val — a thing` admits
   * exactly the integer case, and a pointer reinterpreted as another pointer is a name rather than
   * a literal and never arrives here.
   */
  case IntToPtr(from: LType, value: Val)

  /** A run of bytes — a string constant's contents, terminator included.
   *
   * **The bytes rather than LLVM's escaping of them**, which is the distinction the whole package is
   * about: `c"h\\C3\\A9\\00"` is one back end's spelling and what a consumer wants is the three
   * bytes. The escaping happens in `render` and nowhere else, so a back end emitting a `.byte`
   * directive never has to undo it.
   */
  case Bytes(bytes: List[Byte])

  def render: String = this match
    case Reg(name)    => s"%${LlvmName.safe(name)}"
    case Global(name) => s"@${LlvmName.safe(name)}"
    case Int(v)       => v.toString
    case Float(bits)  => f"0x$bits%016X"
    case Bool(b)      => b.toString
    case Null         => "null"
    case Undef        => "undef"
    case Zero         => "zeroinitializer"
    case Poison       => "poison"
    case Splat(l, v)  => s"splat (${l.render} ${v.render})"
    case Nothing      => ""
    case Agg(fields)  => fields.map(_.render).mkString("{ ", ", ", " }")
    case Array(elems) => elems.map(_.render).mkString("[", ", ", "]")
    case IntToPtr(f, v) => s"inttoptr (${f.render} ${v.render} to ptr)"
    case Bytes(bs)    => bs.foldLeft(new StringBuilder("c\""))(escapeInto).append('"').toString

  /** Whether this is a constant rather than something computed.
   *
   * It used to be a test of a value's first character, which is the shape a string operand forces:
   * anything not beginning with `%` or `@` was taken for a literal. Reading it off the case instead
   * is the same answer with nothing to get wrong — and a register named `@` is now unrepresentable
   * rather than merely unlikely.
   */
  def isConst: Boolean = this match
    case _: Reg | _: Global => false
    case _                  => true

  /** LLVM's own escaping: a quote, a backslash and anything outside printable ASCII go as `\\XX`,
   * and everything else stands for itself.
   */
  private def escapeInto(sb: StringBuilder, b: Byte): StringBuilder =
    val u = b & 0xff

    if u == '"'.toInt || u == '\\'.toInt || u < 0x20 || u >= 0x7f then sb ++= f"\\$u%02X"
    else sb += u.toChar

  override def toString: String = render
}

object Val {

  /** The float constant a `Double` is, at the width it will be stored. An `f32` is narrowed first
   * and then widened back, so what is written down is the bit pattern the machine will hold rather
   * than the one the source happened to spell.
   */
  def float(d: Double): Float = Float(java.lang.Double.doubleToLongBits(d))

  def float32(d: Double): Float = Float(java.lang.Double.doubleToLongBits(d.toFloat.toDouble))
}
