package sh.sysl

/** Turning a sysl name into one LLVM will accept.
 *
 * An LLVM identifier admits `[-A-Za-z$._0-9]` and nothing else. A sysl identifier is a letter or
 * `_` followed by letters, digits and `_` — where *letter* is Unicode's answer rather than ASCII's
 * (`reference/lexical.md § Identifiers`) — and a backtick-quoted name
 * (`reference/lexical.md § Quoted identifiers`) may hold anything but a backtick, a newline and a
 * `.`. So a space, a `+`, an `á` or a `名` all reach the emitter and would produce IR that does not
 * parse.
 *
 * ==Two jobs, and keeping them apart is the whole of this file==
 *
 * A declaration's **key** is also its emitted symbol (`Modules`), so a name has to survive two
 * different readers, and each wants something the other does not:
 *
 *   - `Modules.split` recovers a module from a key by finding its first `$`, so a name that holds a
 *     `$` of its own would be read as a module boundary. [[guard]] is what stops that, and it is
 *     applied where a key is built. It touches `$` and nothing else, so a key otherwise spells the
 *     name the programmer wrote — which is what lets the analyzer compare a key's tail against a
 *     declared name, and lets a diagnostic print one.
 *   - LLVM refuses most of what a name may hold. [[safe]] is what fixes that, and it is applied
 *     where a name is written into IR text and nowhere earlier.
 *
 * **Both write `$XX`, and that is one encoding rather than two.** A `$` surviving into IR is either
 * the module separator, a mangling separator (`sysl.args$Opt`), or the marker one of these two put
 * there; `safe` therefore leaves a `$` alone, which is what makes it applicable to an
 * already-mangled name where [[guard]] is not.
 *
 * **Escaping rather than LLVM's quoted-identifier form**, which would also have been legal. The
 * emitter builds names by concatenation — `%$name.addr`, `arc.drop.$m` — and a quoted identifier
 * has to enclose the *whole* name, so `%"item count".addr` is invalid where `%"item count.addr"` is
 * what was meant. Escaping composes with concatenation; quoting does not.
 */
object LlvmName {

  /** Whether a character may stand in an LLVM name unescaped.
   *
   * This is LLVM's own set, `[-A-Za-z$._0-9]`, and the `-` in it is load-bearing rather than
   * generous: a package's canonical prefix is its coordinate with the slashes turned to dots
   * (`Packages`), so `github.com.sysl-lang.json` is a module name in every program that fetches
   * one. Refusing `-` here would rewrite every package symbol in the org to say nothing LLVM had
   * asked for.
   *
   * `$` is in the set although it is also the escape character, and that is the point rather than
   * an oversight — see the note above about the two jobs being one encoding.
   */
  private def plain(c: Char): Boolean =
    c == '.' || c == '_' || c == '$' || c == '-' ||
      (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')

  /** One character as its escape — its codepoint in hex, `$uXXXXXX` beyond one byte. */
  private def marked(c: Char): String =
    if c.toInt <= 0xff then f"$$${c.toInt}%02x" else f"$$u${c.toInt}%06x"

  /** A raw sysl identifier with its `$`s written `$24`, so that the first `$` in a key is the one
   * `Modules.qualify` put there.
   *
   * **THE ARGUMENT MUST BE ONE RAW IDENTIFIER, never an already-mangled name** — the two cannot be
   * told apart from the string, and they want opposite things from a `$`: in an identifier it can
   * only have come from a quoted name and must be marked, while in a mangled name it is a separator
   * and marking it would rename a symbol that has shipped.
   *
   * Only the ordinary identifier grammar can leave a `$` out of reach; a quoted name can hold one.
   * So this is the identity on all but a handful of names, and a key is otherwise the name as
   * written — `Otoño`, `item count`, `área` — which is what [[safe]] existing separately buys.
   */
  def guard(name: String): String =
    if !name.contains('$') then name else name.flatMap(c => if c == '$' then "$24" else c.toString)

  /** A name safe to write into IR text, with every character LLVM refuses written as `$XX`.
   *
   * **This one takes a MANGLED name and is applied at the emitter**, which is the difference from
   * [[guard]]: it leaves a `$` exactly where it found it, so a separator survives and so does a
   * mark [[guard]] already wrote. Applying it twice changes nothing, which is what lets it sit at
   * the handful of places a name becomes IR text rather than at the many places names are composed.
   *
   * **An ordinary name passes through untouched**, which is the property that matters most: every
   * symbol this compiler has ever emitted keeps the spelling it had, so `reference/modules.md §
   * Separate compilation`'s requirement that two modules instantiating one generic produce
   * byte-identical symbols is undisturbed by this existing at all.
   *
   * The encoding is injective over the names that reach it. `á` becomes `$e1`; a literal `$` was
   * written `$24` by [[guard]] before it ever arrived; so `` `a$20b` `` and `` `a b` `` stay two
   * symbols, which is the one failure mode worth spending a branch to rule out.
   */
  def safe(name: String): String =
    if name.forall(plain) then name
    else name.flatMap(c => if plain(c) then c.toString else marked(c))

  /** [[safe]] for a name that carries its sigil — `%struct.Point`, `%Shape.Circle`.
   *
   * A `%` is not a character a name may hold: the ordinary grammar cannot produce one and a quoted
   * name that does is marked `$25` on the way into its key. So a leading one is the sigil, always,
   * and it is the only character here that must survive unmarked.
   */
  def safeSigiled(name: String): String =
    if name.nonEmpty && (name.head == '%' || name.head == '@') then s"${name.head}${safe(name.tail)}"
    else safe(name)
}
