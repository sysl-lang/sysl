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
case class Func(sig: FuncSig, blocks: List[Block])

/** One basic block: where control may arrive, what it does, and where it goes.
 *
 * `terminator` is optional and is not an admission that a block may fall through — LLVM has no such
 * thing. It is `None` only for a block nothing terminated, which is a malformed function the back
 * end will refuse; modelling it is what lets the printer write down exactly what the emitters
 * produced instead of quietly inventing a `br`.
 */
case class Block(label: String, instrs: List[Inst], terminator: Option[Inst])

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

    sb ++= f.sig.define
    sb ++= " {\n"
    f.blocks.foreach(block(_, sb))
    sb ++= "}\n"
    sb.toString
  }

  /** Writing a whole module down.
   *
   * **The blank lines are group separators, and the groups do not all behave the same** — which is
   * why this is a sequence of explicit calls rather than a fold over one list. A type group is
   * followed by a blank line only where it has something in it; the declarations, the runtime and
   * the two function groups are followed by one whether or not they do. That asymmetry is what the
   * emitters produced before any of this existed, and it is what several hundred codegen assertions
   * matching runs of instructions across a boundary were written against.
   */
  def module(m: Module): String = {
    val sb = new StringBuilder

    // The module says which machine it is for, which is what makes an invocation naming a target
    // mean anything downstream: LLVM derives the data layout from the triple, so stating it is also
    // what keeps a module built for one machine from being read as a module for whatever read it.
    sb ++= s"""target triple = "${m.triple}"\n\n"""

    lines(m.declares.map(_.declare), sb, always = true)
    lines(m.structs.map(_.render), sb)
    lines(m.enums.map(_.render), sb)
    lines(m.boxes.map(_.render), sb)
    lines(m.imports.map(_.declare), sb)
    lines(m.globals.map(_.render), sb)

    for r <- m.runtime do
      sb ++= (r match
        case Runtime.Emitted(f)     => func(f)
        case Runtime.Template(_, t) => t)
    sb ++= "\n"

    m.funcs.foreach(f => sb ++= func(f))
    sb ++= "\n"
    m.thunks.foreach(f => sb ++= func(f))
    sb ++= "\n"
    m.entry.foreach(f => sb ++= func(f))

    // The constructor and the list naming it, in that order for `@llvm.used`'s reason: the entry
    // refers to the function by name, so the definition is written first.
    for i <- m.init do
      sb ++= func(i.func)
      sb ++= i.list.render
      sb ++= "\n"

    // Last, so that every symbol it names has been written above it.
    for g <- m.used do
      sb ++= g.render
      sb ++= "\n"

    sb.toString
  }

  /** One line per item, then a blank line after the group — `always` where the emitters wrote one
   * whether or not the group had anything in it.
   */
  private def lines(items: List[String], sb: StringBuilder, always: Boolean = false): Unit = {
    for i <- items do
      sb ++= i
      sb ++= "\n"

    if always || items.nonEmpty then sb ++= "\n"
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
