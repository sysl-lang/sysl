package sh.sysl
package ir

/** **A function's body, as data** — the blocks it is made of and the instructions in each.
 *
 * The structure was always here and was thrown away at the moment it was printed. `Emitter` has
 * exactly three ways to write an instruction down — an ordinary one, a terminator, and a label —
 * and a flag that already means *this block is closed*, so what the emitters build is a list of
 * basic blocks whether or not anything keeps it. This keeps it.
 *
 * A consumer could have reconstructed the same thing from the printed labels and terminators. That
 * reconstruction is a parser, which is the thing this exists to remove: a second back end reading
 * what codegen produced should be handed the shape rather than the characters.
 */
case class Func(header: String, blocks: List[Block])

/** One basic block: where control may arrive, what it does, and where it goes.
 *
 * `terminator` is optional and is not an admission that a block may fall through — LLVM has no such
 * thing. It is `None` only for a block nothing terminated, which is a malformed function the back
 * end will refuse; modelling it is what lets the printer write down exactly what the emitters
 * produced instead of quietly inventing a `br`.
 */
case class Block(label: String, instrs: List[Inst], terminator: Option[Inst])

/** One instruction.
 *
 * `Raw` is the whole of it for now and is temporary scaffolding: it carries the text an emitter
 * interpolated, so the block structure above can be captured and proved byte-identical before any of
 * the six hundred emit sites are converted. It is deleted when the last of them is, and an escape
 * hatch that dies with its last caller is not a hatch left open.
 */
enum Inst {
  case Raw(text: String)

  /** The instruction as LLVM writes it, without the indentation a body gives it. */
  def render: String = this match
    case Raw(text) => text
}

/** Writing a function down. **The output is byte-identical to what the emitters printed directly**,
 * which is not a nicety: the compiler's codegen tier asserts on emitted IR by substring, including
 * the two-space indentation and the runs of `\n  store …` between instructions, so the printer
 * either matches or the tests say so.
 */
object Printer {

  /** The two spaces every instruction in a body carries. A label carries none, which is what makes
   * one findable in the text.
   */
  private val indent = "  "

  def func(f: Func): String = {
    val sb = new StringBuilder

    sb ++= f.header
    sb ++= " {\n"
    f.blocks.foreach(block(_, sb))
    sb ++= "}\n"
    sb.toString
  }

  private def block(b: Block, sb: StringBuilder): Unit = {
    sb ++= b.label
    sb ++= ":\n"

    for i <- b.instrs do
      sb ++= indent
      sb ++= i.render
      sb ++= "\n"

    for t <- b.terminator do
      sb ++= indent
      sb ++= t.render
      sb ++= "\n"
  }
}
