package sh.sysl

/** Holds a definition marked `@export` to what a C caller can actually name and call (`15 §12`).
 *
 * **`@export` is `extern` read the other way**, and that symmetry is what decides every rule here.
 * An `extern` names a symbol the linker has and states the signature the other side published; an
 * `@export` publishes a symbol and states the signature C may call it at. Neither invents a shape the
 * other end could not spell, so what this pass refuses is exactly what C has no way to say.
 *
 * **A declaration pass, not part of the body walk**, for the reason `ConventionCheck` gives: every
 * rule is about the *signature*, and a signature exists whether or not a body is ever analyzed. A
 * generic marked `@export` has no instantiation and so no body to walk, and it is exactly as wrong as
 * one that does.
 *
 * **The refusals are the point, and they are cheap because of where they land.** A restriction that
 * fired while somebody wrote ordinary sysl would be a language that keeps saying no. These fire only
 * on a definition the author marked, in the boundary layer they wrote on purpose — the same facade a
 * Scala program exposing itself to Java or JS grows — so the diagnostic reads as a reminder of what
 * the boundary is for rather than as an obstruction. There are simply some things a function cannot
 * be and stay C-callable, and saying which, where somebody tries, is the whole of the work.
 *
 * **What is here is what a declaration answers on its own**, which turns out to be everything about
 * the *shape* of the function and nothing about its types. Two kinds of rule are in `Exports`
 * instead, and both for the same reason — they need something a declaration does not have:
 *
 *   - **the parameter and result types**, because resolving a short name needs the scope the
 *     declaration sits in, and a `P` written inside `module demo` means `demo.P` only to a walk that
 *     is standing there. The typed tree has already answered that, so asking it again here would be
 *     re-deriving a resolution that exists — and getting it wrong for exactly the modules a real
 *     binding is written in;
 *   - **whether an export reaches a computed module `val`**, which is a question about the call
 *     graph and so exists only once every body has been analyzed.
 */
trait ExportCheck extends TypeResolution {

  /** Holds every declaration carrying `@export`.
   *
   * **Free functions are the whole of it, and that is the grammar's doing rather than a choice made
   * here.** `SyslParser.attributedDecl` reads attributes at statement position only, so no attribute
   * reaches a struct member, an enum member or an `impl` member — `@test` and `@pure` are as
   * unavailable there as this one. A rule refusing `@export` on a method would therefore be a rule
   * nothing could reach, and the refusal a reader actually meets comes from the parser.
   */
  protected def checkExports(): Unit =
    for
      f <- funcDecls.values.toList
      e <- f.exported
    do recover(())(at(e.pos)(checkExport(e, f)))

  /** Refuses an export whose symbol C could not name, or whose function C could not call. */
  private def checkExport(e: ExportAttr, f: FuncDecl): Unit = {
    for s <- e.symbol if !ExportCheck.cIdentifier(s) do
      err(s"'$s' is not a name C can call — an exported symbol is a C identifier, so it is a " +
        "letter or '_' followed by letters, digits and '_'")

    // Checked against the *declared* name, since that is what an unwritten symbol becomes. A
    // backtick-quoted name (`09`) is the case: it may hold anything the reader wanted, and the
    // escape that makes it an LLVM label is not something a C header could declare.
    if e.symbol.isEmpty && !ExportCheck.cIdentifier(Modules.bare(f.name)) then
      err(s"'${Modules.show(f.name)}' is not a name C can call, so exporting it under its own name " +
        "would publish a symbol no C declaration could spell — name the symbol instead, " +
        "'@export(\"...\")'")

    // Generic is refused before anything is resolved, for `ConventionCheck`'s reason: a type
    // parameter has nothing to resolve *to* out here, and asking what `T` is would be asking a
    // question the declaration has already made meaningless.
    if f.tparams.nonEmpty then
      err(s"an exported symbol is one function at one signature, so '${f.name}' cannot be generic — " +
        "there would be no way to say which instantiation the linker holds. Export a wrapper per " +
        "instantiation, each naming its own symbol")

    // `private` emits the symbol `internal` (`13 §2`), which is the promise that every caller is
    // inside this module. An export is the opposite promise, and a definition cannot make both.
    if f.vis != Visibility.Public then
      err(s"'${Modules.show(f.name)}' is private, which promises every caller is inside its own " +
        "module — and an export promises the opposite. A definition cannot make both claims")

    if f.ghost then
      err(s"'${Modules.show(f.name)}' is '@ghost', so it is erased before codegen and there is no " +
        "symbol for C to link against (`17 §8`)")

    if f.test.isDefined then
      err(s"'${Modules.show(f.name)}' is a '@test', which only 'sysl test' builds — an exported " +
        "symbol has to be in the artifact a C project links, and a test is not")

    // A variadic sysl definition reads its tail through `va_start`/`va_arg`, which is C's own
    // mechanism — but the *promotions* a C caller applies to the tail are the caller's, and nothing
    // here has seen that caller's prototype. `extern` is safe the other way round because the
    // published prototype is what the declaration transcribes.
    if f.variadic then
      err(s"'${Modules.show(f.name)}' is variadic, and what a C caller promotes into the tail is " +
        "decided by the prototype it compiled against rather than by this declaration — take a " +
        "'va_list' parameter instead, which states the same thing and is what C's own 'v' " +
        "variants do")

  }
}

object ExportCheck {

  /** The sentence every signature refusal ends with, written once so the two cannot drift apart. */
  val spellable: String =
    "an integer, a float, a 'bool', a 'char', a pointer or a function pointer"

  /** Whether a type crosses to C **as itself**, which is a narrower question than whether it has a
   * meaning over there.
   *
   * A scalar and a pointer are one register on every machine sysl lowers for, and the answer needs
   * nothing decided. An aggregate is the opposite: each ABI says which registers a struct arrives
   * in, LLVM applies no such rule of its own, and `CAbi` exists because sysl's own lowering and C's
   * published one genuinely differ. Passing one by value would be a corrupt call rather than a link
   * error, which is why it is refused here rather than lowered hopefully.
   *
   * A slice and a `string` are two words with a length in the second, a `&T` is a counted box whose
   * header C would have to know the shape of, and a trait object is a pair with a method table.
   * Each is a real sysl value and none of them is a C declaration.
   */
  def crosses(t: Type): Boolean = Type.repr(t) match
    case _: Type.Integer | _: Type.Floating => true
    case Type.Bool | Type.Char              => true
    case _: Type.Ptr | _: Type.CFn          => true
    // A constrained subtype is its base with a check at the point a value is *made*, so a derived
    // one crosses exactly as its base does — nothing about the representation differs, and the
    // C side is not making the value.
    case c: Type.Constrained                => crosses(c.base)
    case _                                  => false

  /** The one line of help the refusal can honestly give, which differs by what was written. Anything
   * with no specific advice gets none rather than a guess.
   */
  def advice(t: Type): String = Type.repr(t) match
    case _: Type.Slice | Type.Str =>
      "A slice is an address and a length, so C takes them as two parameters — a pointer and a " +
        "'usize' — which is the shape its own string and buffer functions already have"
    case _: Type.Ref | _: Type.Weak =>
      "A counted reference has a header C would have to know the layout of. Hand out a raw pointer " +
        "and keep the counted one on this side"
    case _: Type.Trait =>
      "A trait object is a value and a method table together. Export one function per " +
        "implementation, or take a function pointer"
    case _: Type.Struct | _: Type.Array =>
      "An aggregate by value is passed differently by every ABI and sysl's lowering is not C's, so " +
        "take a pointer to it instead"
    case _ => ""

  /** Whether a string is a name C could declare. ISO C also reserves a leading underscore at file
   * scope, which is a rule about *which* names a program may take rather than about what is a name —
   * so it is left to the author, exactly as C leaves it.
   */
  def cIdentifier(s: String): Boolean =
    s.nonEmpty && (s.head.isLetter && s.head < 128 || s.head == '_') &&
      s.forall(c => c < 128 && (c.isLetterOrDigit || c == '_'))
}
