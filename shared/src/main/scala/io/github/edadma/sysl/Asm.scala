package io.github.edadma.sysl

/** The two things sysl does to an assembly template, and the one thing it builds beside it
 * (`inline-assembly.md §5`).
 *
 * A program writes `{name}` and never writes a constraint. Both halves of that promise live here,
 * together, because they are one agreement seen from two sides: an operand's position in the
 * constraint string is the number the placeholder becomes, and the two disagreeing is precisely the
 * bug the construct exists to make impossible. Nothing in the codebase should build one without the
 * other.
 */
object Asm {

  /** The operand names a template line refers to, in the order they appear.
   *
   * A doubled brace is a literal one and names nothing — which is not a nicety: ARM writes register
   * lists as `{r0-r3}`, so a template language without an escape could not spell `push {lr}`.
   */
  def placeholders(line: String): List[String] = {
    val out = List.newBuilder[String]
    var i   = 0

    while i < line.length do
      if line(i) == '{' && i + 1 < line.length && line(i + 1) == '{' then i += 2
      else if line(i) == '{' then
        val close = line.indexOf('}', i)

        if close < 0 then i = line.length
        else
          out += line.substring(i + 1, close)
          i = close + 1
      else i += 1

    out.result()
  }

  /** Rewrites one line into what LLVM's inline assembly reads: `$0`-style operand markers, and
   * everything else left alone.
   *
   * The compiler owns every escape in here, which is the point of the exercise. `$` is LLVM's own
   * operand marker, so a `$` the program wrote — an x86 immediate, `movq $1, %rsi` — is doubled
   * *here* rather than in the source, where doubling it would be the language's syntax leaking into
   * a place the reader thinks is assembly. Doubled braces collapse to single ones for the same
   * reason, at the same time, so the text a program wrote is the text the assembler sees.
   */
  def render(line: String, slotOf: String => Option[Int]): String = {
    val out = new StringBuilder
    var i   = 0

    while i < line.length do
      val c = line(i)

      if c == '$' then
        out ++= "$$"
        i += 1
      else if c == '{' && i + 1 < line.length && line(i + 1) == '{' then
        out += '{'
        i += 2
      else if c == '}' && i + 1 < line.length && line(i + 1) == '}' then
        out += '}'
        i += 2
      else if c == '{' then
        val close = line.indexOf('}', i)

        if close < 0 then
          out += c
          i += 1
        else
          val name = line.substring(i + 1, close)

          // A name with no slot has already been reported by the analysis; leaving the text as it
          // stands keeps the emitter from inventing an operand number that indexes nothing.
          slotOf(name) match
            case Some(n) => out ++= s"$$$n"
            case None    => out ++= line.substring(i, close + 1)

          i = close + 1
      else
        out += c
        i += 1

    out.result()
  }

  /** LLVM's constraint string, in the order the call's operands are passed.
   *
   * Outputs come first because that is the order LLVM reads them in, and it is why the operand
   * numbering in a template counts outputs before inputs — a fact a programmer never has to know,
   * since they wrote names.
   *
   * **Memory and the condition flags are always clobbered**, and that is the conservative direction
   * on purpose (`inline-assembly.md §4`). Assuming them costs optimization quality across a handful
   * of instructions; not assuming them costs a value kept in a register the block overwrote, which
   * is a wrong answer with nothing to point at.
   */
  def constraints(operands: List[TAsmOperand], clobbers: List[String]): String = {
    val outs = operands.filter(_.dir == AsmDir.Out).map(o => s"=${place(o)}")
    val ins  = operands.filter(_.dir == AsmDir.In).map(place)

    (outs ::: ins ::: clobbers.map(r => s"~{$r}") ::: List("~{memory}", "~{cc}")).mkString(",")
  }

  /** Where one operand has to live: a named machine register, or any general-purpose one. */
  private def place(o: TAsmOperand): String = o.reg match
    case Some(r) => s"{$r}"
    case None    => "r"

  /** A label defined at the start of a line, which is the only place assembly puts one. The leading
   * dot and the dollar are allowed because both begin ordinary label names, and a colon anywhere
   * else on the line — an x86 segment override, `%fs:0x28` — is not matched by construction.
   */
  private val LabelDef = """^\s*([A-Za-z_.$][A-Za-z0-9_.$]*):""".r

  /** Gives every label an arm defines a name of its own, so a block that is emitted twice does not
   * define the same symbol twice.
   *
   * A label written in inline assembly is a global symbol, and the assembler rejects the second
   * definition — which happens the second time the enclosing function is emitted, for reasons that
   * have nothing to do with the code and that the programmer cannot do anything about from where
   * they are standing. Renaming is the compiler's job because uniqueness is the compiler's fact.
   *
   * Only a whole occurrence is rewritten: the definition, and every reference to it in the same
   * arm. A label is local to its arm, so there is nothing outside one to keep in step.
   */
  def uniquifyLabels(lines: List[String], id: Int): List[String] = {
    val defined = lines.flatMap(l => LabelDef.findFirstMatchIn(l).map(_.group(1))).distinct

    defined.foldLeft(lines) { (acc, name) =>
      val bounded = s"(?<![A-Za-z0-9_.$$])${java.util.regex.Pattern.quote(name)}(?![A-Za-z0-9_.$$])".r

      acc.map(l => bounded.replaceAllIn(l, java.util.regex.Matcher.quoteReplacement(s"$name.$id")))
    }
  }

  /** The operand numbers a template's names stand for, outputs first, matching `constraints`. */
  def numbering(operands: List[TAsmOperand]): Map[String, Int] = {
    val outs = operands.filter(_.dir == AsmDir.Out).map(_.name)
    val ins  = operands.filter(_.dir == AsmDir.In).map(_.name)

    (outs ::: ins).zipWithIndex.toMap
  }
}
