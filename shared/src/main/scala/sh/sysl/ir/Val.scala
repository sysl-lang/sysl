package sh.sysl
package ir

/** **An operand, as data** — a register, a symbol, or a constant.
 *
 * Codegen carried these as `String` for as long as the IR was text, which meant a value's *kind* was
 * something you tested for with `startsWith("%")` and its contents were whatever had been
 * interpolated. Keeping operands as strings while making the instructions data would have moved the
 * parser one layer in rather than removing it, so this is the half that has to come with them.
 *
 * **`toString` is `render`, deliberately.** An emitter that has not yet been converted interpolates
 * a value into a line of text, and it goes on producing exactly the characters it produced before —
 * which is what lets six hundred sites be converted a file at a time instead of all at once.
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

  /** **No value at all.** A zero-sized type occupies nothing, so reading one produces nothing to
   * name, and this is what the emitters carried as the empty string.
   *
   * It is a case of its own rather than an empty `Reg` because the difference is the whole point: a
   * register with no name is a bug that renders as a syntax error, and this is a value the language
   * has and the machine does not.
   */
  case Nothing

  /** Text an emitter interpolated, for a producer that has not been converted yet. Scaffolding, and
   * deleted with `Inst.Raw` when the last of them is.
   */
  case Raw(text: String)

  def render: String = this match
    case Reg(name)    => s"%$name"
    case Global(name) => s"@$name"
    case Int(v)       => v.toString
    case Float(bits)  => f"0x$bits%016X"
    case Bool(b)      => b.toString
    case Null         => "null"
    case Undef        => "undef"
    case Zero         => "zeroinitializer"
    case Nothing      => ""
    case Raw(text)    => text

  /** Whether this is a constant rather than something computed — which is what the constant folds in
   * codegen are really asking when they test a value's first character.
   */
  def isConst: Boolean = this match
    case _: Reg | _: Global => false
    case Raw(text)          => !text.startsWith("%") && !text.startsWith("@")
    case _                  => true

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
