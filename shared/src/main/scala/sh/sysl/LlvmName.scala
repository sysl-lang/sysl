package sh.sysl

/** Turning a sysl identifier into one LLVM will accept.
 *
 * An LLVM identifier admits `[A-Za-z$._0-9]` and nothing else. Every sysl identifier used to be a
 * subset of that by construction — `isIdentPart` allows letters, digits and `_` — so a name reached
 * the IR untouched and this had no reason to exist. A backtick-quoted name (`09`) may hold anything
 * but a backtick, a newline and a `.`, so a space, a `-` or a `+` now reaches the emitter and would
 * produce IR that does not parse.
 *
 * **Escaping rather than LLVM's quoted-identifier form**, which would also have been legal. The
 * emitter builds names by concatenation — `%$name.addr`, `arc.drop.$m` — and a quoted identifier
 * has to enclose the *whole* name, so `%"item count".addr` is invalid where `%"item count.addr"` is
 * what was meant. Escaping composes with concatenation; quoting does not.
 */
object LlvmName {

  /** Whether a character may stand in an LLVM name unescaped.
   *
   * `$` is in the set although it is also the escape character, and that is safe rather than
   * sloppy: a `$` reaching here is either one the *mangler* inserted as a separator (`sysl.args$Opt`)
   * or one escaped below, and a **bare** sysl identifier can never contribute one, since
   * `SyslLexical.isIdentPart` does not admit it. Only a quoted name can, and `escape` writes that
   * one as `$24`.
   */
  private def plain(c: Char): Boolean =
    c == '.' || c == '_' || c == '$' ||
      (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')

  /** A name safe to write into IR, with every character LLVM refuses written as `$XX` — its
   * codepoint in hex, `$uXXXXXX` beyond one byte.
   *
   * **THE ARGUMENT MUST BE ONE RAW SYSL IDENTIFIER, never an already-mangled name.** The two cannot
   * be told apart from the string, and they want opposite things from a `$`: in an identifier it
   * can only have come from a quoted name and must be escaped, while in a mangled name it is the
   * separator the mangler put there (`sysl.args$Opt`) and escaping it would rename a symbol that
   * has shipped. So this is applied where a user's identifier first becomes part of an emitted
   * name, and never to the result.
   *
   * **An ordinary identifier passes through untouched**, which is the property that matters most:
   * every symbol this compiler has ever emitted keeps the spelling it had, so `15 §2`'s requirement
   * that two modules instantiating one generic produce byte-identical symbols is undisturbed by
   * this existing at all.
   *
   * A literal `$` is written `$24`, without which the encoding would not be injective — `` `a b` ``
   * and `` `a$20b` `` would be one symbol, and a silent collision between two names is the one
   * failure mode worth spending a branch to rule out.
   */
  def escape(name: String): String =
    if name.forall(c => plain(c) && c != '$') then name
    else
      name.flatMap { c =>
        if c == '$' then "$24"
        else if plain(c) then c.toString
        else if c.toInt <= 0xff then f"$$${c.toInt}%02x"
        else f"$$u${c.toInt}%06x"
      }
}
