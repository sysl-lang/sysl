package sh.sysl

/** The annotations written above a declaration — `@test`, `@tailrec`, `@pure`, `@ghost`.
 *
 * Each alternative reads its own `@`, and the two misspellings a reader arrives with — an
 * unknown word, and `#` where `@` was meant — are rules here rather than parse failures, because
 * what such a reader is missing is which sigil marks what.
 *
 * The fold onto `FuncDecl` lives here too: an attribute exists to say something about the
 * declaration under it, so what it says and how it is applied are one area.
 */
trait AttrParser extends ExprParser {

  /** One annotation: what it says about the function under it. Each alternative reads the `@` for
   * itself so that the annotation's own position is the `@`, which is the line a test report names.
   */
  protected lazy val attribute: PackratParser[Attr] =
    testAttr ^^ Attr.Test.apply | tailrecAttr | pureAttr | ghostAttr | readsAttr | writesAttr |
      crossingAttr | packedAttr | alignAttr | exportAttr | sectionAttr | unknownAttr | hashAttr

  /** An `@` — or a `#` — where a member was wanted, which the four member blocks — a struct's body,
   * an enum's, a trait's and an `impl`'s — each open their lines with.
   *
   * `attribute` is read at **statement** position and nowhere else, so no member block ever tries it
   * and an annotation written above a method reaches whichever rule was going to complain about the
   * line: `dedent expected` where a member had already been read, `identifier expected` where none
   * had. Both are about indentation and about names, which is the one thing that is not wrong, and
   * the reader is left with nothing to act on — while the reader most likely to write it is the one
   * arriving from a language where `#[test]` on a method is ordinary.
   *
   * That no annotation marks a member is `06 § What it does not reach`, so this is a sentence rather
   * than a form the grammar could still have read. `@assert` is told apart and answered separately:
   * it stands *where* a declaration stands rather than saying anything about one, so the sentence
   * about what annotations mark is exactly the wrong thing to say about it — the same distinction
   * `assertDecl` is ordered before `attributedDecl` for.
   *
   * **`#` is read here as well**, and it is the spelling the reader this rule is for actually writes:
   * `#[test]` above a method is Rust's, and an indented `#` never reaches the directive pass, which
   * takes only what sits at the margin. Answering it with `hashAttr`'s sentence alone would send them
   * to write `@test`, which a member is refused all the same — so the sigil is named and the member
   * rule is what the sentence is about.
   *
   * **The cases are told apart inside one lookahead rather than by separate alternatives**, and that
   * is the whole reason the word is read through `opt(ident)` instead of `attrWord`. Separate
   * alternatives fail at separate positions: `guard(op("@") ~ attrWord("assert"))` gets past the `@`
   * before it declines, and a `Failure` one token further along **outranks** an `Error` raised back
   * at the `@` — so the sentence a reader was meant to get lost to `'assert' expected` every time.
   * Written this way the lookahead cannot fail past the sigil, and the one refusal it then raises
   * points at the sigil itself, which is the line the reader has to delete.
   */
  protected lazy val noMemberAttr: Parser[Unit] =
    guard((op("@") | op("#")) ~ opt(ident)) >> {
      case "@" ~ Some("assert") =>
        err("'@assert' stands where a declaration stands, and a type's body holds its members — " +
          "write it beside the type rather than inside it, where 'sizeof' and 'offsetof' still name " +
          "what it is about")
      case sigil ~ _ =>
        err("an annotation marks a function, and a member is not one — no annotation in this " +
          "language marks a method, a property, a field or a variant, so it goes above a free " +
          "function instead: what 'sysl test' calls is a function calling the member, and a " +
          "'@crossing' is written on the wrapper a caller already goes through ('06')" +
          (if sigil == "#" then ". An annotation is written '@' in any case — '#' opens a directive, " +
             "which gates lines before the lexer sees them and sits at the margin"
           else ""))
    } | success(())

  /** `@packed` — fields at their declared offsets with no interior padding, and an aggregate that
   * needs no alignment of its own (`reference/types.md § Structs`). It takes no arguments: there is
   * nothing to configure about the absence of a gap.
   */
  protected lazy val packedAttr: PackratParser[Attr] =
    op("@") ~> attrWord("packed") ^^ (_ => Attr.Packed)

  /** `@align(n)` — the boundary storage of this type must begin on, which may only be raised.
   *
   * The bound is an expression because it is folded rather than lexed: `@align(CACHE_LINE)` is the
   * form worth writing, and a program that had to repeat the number would be stating the same fact
   * in two places. What it may be is the constant set of `reference/modules.md § Platform
   * selection`.
   */
  protected lazy val alignAttr: PackratParser[Attr] =
    op("@") ~> attrWord("align") ~> (op("(") ~> expression <~ op(")") ^^ Attr.Align.apply | alignErr)

  private def alignErr: Parser[Attr] =
    err("'@align' names the boundary in parentheses — '@align(64)', or '@align(CACHE_LINE)' for a " +
      "constant that says what the number is for. There is no bare form: an alignment with no " +
      "number is not a weaker claim, it is no claim")

  /** `@export` and `@export("mylib_parse")` — the definition is C-callable, under its own name or
   * under the symbol named (`reference/ffi.md § @export`).
   *
   * The parenthesised form takes a **string** rather than an identifier, exactly as `extern`'s link
   * name does, and for the same reason: it is a symbol the other side chose and it is not required to
   * be anything sysl could lex. The two are one mechanism read in opposite directions, so they are
   * spelled alike.
   *
   * The refusal is raised **inside** the parentheses, per the rule a dead `err` taught: an
   * alternative that fails at the `(` is outranked by one that got past it, so a sentence written
   * after the closing parenthesis would never be the one reported.
   */
  protected lazy val exportAttr: PackratParser[Attr] =
    at(op("@") ~> attrWord("export") ~> opt(op("(") ~> (linkName | exportErr) <~ op(")"))
      ^^ (s => ExportAttr(s))) ^^ Attr.Export.apply

  private def exportErr: Parser[String] =
    err("'@export' names the C symbol as a string — '@export(\"mylib_parse\")' — or takes no " +
      "parentheses at all, which exports the function under its own name")

  /** `@section(".vectors")` — the linker section this object or definition is placed in
   * (`reference/attributes.md § @section("...")`).
   *
   * The name is a **string** for the reason `@export`'s symbol is one: it is the target's spelling
   * rather than sysl's, and `.vectors`, `__DATA,__mysection` and `.text.boot` are none of them things
   * sysl could lex. Nothing here checks what is in it beyond its being there at all — a character set
   * chosen in this file would refuse a section some target requires.
   *
   * The empty string is refused, and it is the one refusal the string form owes: every other spelling
   * is somebody's, and `""` is nobody's.
   *
   * Both refusals are raised **inside** the parentheses, per the rule a dead `err` taught — an
   * alternative that fails at the `(` is outranked by one that got past it.
   */
  protected lazy val sectionAttr: PackratParser[Attr] =
    op("@") ~> attrWord("section") ~> (op("(") ~> (sectionName | sectionErr) <~ op(")") | sectionErr)

  private lazy val sectionName: Parser[Attr] =
    linkName >> (s =>
      if s.isEmpty then
        err("'@section' names a section, and \"\" is not the name of one — a section's spelling is " +
          "the target's, so there is nothing an empty one could resolve to")
      else success(Attr.Section(s)))

  private def sectionErr: Parser[Attr] =
    err("'@section' names the linker section as a string — '@section(\".vectors\")' on the storage " +
      "or the definition that goes there. The spelling is the target's: '.noinit' and '.ramfunc' " +
      "are ELF's, '__DATA,__mysection' is Mach-O's")

  /** `@test`, and the three things it may say about the test: the name a report gives it, that it is
   * a run which should not come back, and the text such a run should have printed on its way out.
   */
  protected lazy val testAttr: PackratParser[TestAttr] =
    at(op("@") ~> attrWord("test") ~> opt(emptyTestErr | testArgList)
      ^^ (_.getOrElse(TestAttr(None, false, None))))

  /** The parenthesized part of `@test`, which **commits at the `(`**.
   *
   * Everything inside is a rule rather than a parse failure because of where a failure otherwise
   * lands. `opt` declines without consuming when what is between the parentheses is not a
   * description, so the `(` is left unread and the statement rule goes on to look for a declaration
   * there — and refuses the *function below*, with the sentence about an annotation marking a
   * function and only a function. That sends a reader to a declaration which is perfectly ordinary,
   * with a caret on a `(` the message never mentions.
   *
   * **All four spellings did that, not only the empty one**: `@test(3)`, `@test("x"` and
   * `@test(should_trap` gave the same unrelated sentence as `@test()`. Having read the `(` there is
   * nothing else the reader could have been writing, so from here on every road ends in a sentence
   * about the argument list.
   */
  private lazy val testArgList: Parser[TestAttr] =
    op("(") ~> (testArgs | badTestArgErr) <~ (op(")") | unclosedTestErr)

  /** `@test()` — parentheses with nothing between them.
   *
   * **The form stays illegal, which is the decision rather than the easy road.** `@test()` and bare
   * `@test` would mean the same thing, and accepting the first costs a line — but an empty argument
   * list is not a shorter way of saying nothing. It reads as a description that *was* going to be
   * there, and a reader who sees it accepted cannot tell whether the author meant to write one and
   * lost it. The language already has the spelling for saying nothing, and it is `@test`.
   *
   * The lookahead takes in both parentheses so that the refusal is raised at the `(` — the caret
   * then sits on the pair the reader has to delete, rather than one character past it.
   */
  private def emptyTestErr: Parser[TestAttr] =
    guard(op("(") ~ op(")")) ~> err("'@test' takes a description or nothing at all, and '()' is " +
      "neither — drop the parentheses, and the function's own name becomes its description")

  private def badTestArgErr: Parser[TestAttr] =
    err("'@test' takes the description a report shows it under, written as a string — " +
      "'@test(\"an index past the end is refused\")' — and 'should_trap' for a run that is meant " +
      "not to come back, optionally with the text such a run must have printed. The two compose, " +
      "in that order")

  private def unclosedTestErr: Parser[Unit] =
    err("'@test' closes what it opened — the description and 'should_trap' go between parentheses, " +
      "and there is no ')' here to end them")

  /** `@tailrec` — the assertion that this function's call to itself is the last thing it does
   * (`reference/declarations.md § Tail calls`). It takes no arguments: there is nothing to
   * configure about a jump, and what the annotation buys is the refusal when there is no jump to
   * make.
   */
  protected lazy val tailrecAttr: PackratParser[Attr] =
    op("@") ~> attrWord("tailrec") ^^ (_ => Attr.TailRec)

  /** `@pure` — the assertion that a caller can observe nothing about this call but its result
   * (`reference/verification.md § @pure`). Like `@tailrec` it takes no arguments: purity is not a
   * thing to configure, and what the annotation buys is the refusal when the body does something a
   * caller could observe.
   */
  protected lazy val pureAttr: PackratParser[Attr] =
    op("@") ~> attrWord("pure") ^^ (_ => Attr.Pure)

  /** `@ghost` — the function exists for the specification alone and is erased before codegen
   * (`reference/verification.md § @ghost — what costs nothing to say`).
   */
  protected lazy val ghostAttr: PackratParser[Attr] =
    op("@") ~> attrWord("ghost") ^^ (_ => Attr.Ghost)

  /** `@reads(a, b)` and `@writes(c)` — which module-level variables the function may touch
   * (`reference/verification.md § @reads and @writes — what a call may touch`). The parentheses are
   * mandatory and may be empty, because `@reads()` is a real and different claim from writing
   * nothing at all: the first says the function reads no module storage, the second says nobody has
   * written down what it does.
   *
   * The argument list is raised **inside** the parentheses rather than after the closing one, per
   * the rule a dead `err` taught: a form that gets further along the line outranks an alternative
   * that failed earlier, so a message written past the point of divergence is never the one
   * reported. Here the only way to fail after `@reads` is the parenthesis, so that is where the
   * sentence goes — otherwise `unknownAttr` below wins by position and says `reads` is not an
   * annotation, which is both wrong and unhelpful.
   */
  protected lazy val readsAttr: PackratParser[Attr] =
    op("@") ~> attrWord("reads") ~> (frameNames ^^ Attr.Reads.apply | frameErr("reads"))

  protected lazy val writesAttr: PackratParser[Attr] =
    op("@") ~> attrWord("writes") ~> (frameNames ^^ Attr.Writes.apply | frameErr("writes"))

  private lazy val frameNames: Parser[List[String]] =
    op("(") ~> repsep(ident, op(",")) <~ op(")")

  private def frameErr(w: String): Parser[Attr] =
    err(s"'@$w' names the module variables it covers, in parentheses — '@$w(count)', or '@$w()' " +
      "for none. The parentheses are what tell a frame that covers nothing from a function that " +
      "never said")

  /** `@crossing(state)` — the parameters through which a value reaches another concurrency domain
   * (`reference/memory.md § @crossing — where the rule is asked`).
   *
   * The parentheses are mandatory and, unlike a frame's, may **not** be empty. `@reads()` is a real
   * claim — the function reads no module storage — while a function that hands nothing to another
   * domain says so by not writing the annotation, so `@crossing()` would be a line that means what
   * its absence already means.
   *
   * The refusal is raised **inside** the parentheses, per the rule a dead `err` taught: an
   * alternative that fails at the `(` is outranked by one that got past it, so a sentence written
   * after the closing parenthesis would never be the one reported.
   */
  protected lazy val crossingAttr: PackratParser[Attr] =
    op("@") ~> attrWord("crossing") ~> (op("(") ~> (crossingNames | crossingErr) <~ op(")") |
      crossingErr)

  private lazy val crossingNames: Parser[Attr] =
    rep1sep(ident, op(",")) ^^ Attr.Crossing.apply

  private def crossingErr: Parser[Attr] =
    err("'@crossing' names the parameters a value reaches another concurrency domain through, in " +
      "parentheses — '@crossing(state)'. There is no empty form: a function that hands nothing " +
      "across a boundary says so by not writing the annotation")

  private lazy val unknownAttr: PackratParser[Attr] =
    op("@") ~> ident >> (n =>
      err(s"'$n' is not an annotation a declaration takes — '@test', '@tailrec', '@pure', " +
        "'@ghost', '@export', '@reads(...)', '@writes(...)' and '@crossing(...)' mark a function, " +
        "'@packed' and " +
        "'@align(n)' mark a struct's layout, '@export(\"...\")' names a struct in a generated C " +
        "header, and '@section(\"...\")' marks either a binding or a " +
        "function. '@no_<capability>', " +
        "'@requires(...)', '@link(\"...\")' and '@tests' belong in the file's header"))

  /** `#test` where `@test` was meant — the sigil a reader arriving from Rust or C reaches for first.
   *
   * It is answered here rather than left to the lexer because the two sigils mark two different
   * kinds of thing, and saying which is which is the whole of what the reader is missing: `#` gates
   * lines before the lexer sees them and sits at the margin, `@` says something about the
   * declaration under it. A directive word is *not* named here, since `#if` at the margin never
   * reaches the grammar at all.
   */
  private lazy val hashAttr: PackratParser[Attr] =
    op("#") ~> ident >> (n =>
      err(s"an annotation is written '@$n' — '#' opens a directive, which gates lines before the " +
        "lexer sees them and sits at the margin"))

  /** An annotation's name. Each stays an ordinary identifier — reserving them would spend the words
   * out of every program's namespace for the sake of one line apiece, which is the trade `alloc`
   * made and the one `capabilities.md § Open` is still paying for.
   */
  protected def attrWord(w: String): Parser[Unit] =
    accept(s"'$w'", { case t: lexical.Identifier if t.chars == w => () })

  /** An annotation whose name *carries* its argument — `@no_alloc` is `no_` and then the capability.
   * What comes back is the part after the prefix, so the caller never sees the joint.
   *
   * The prefix alone is not one of these: `@no_` names no capability, and matching it would hand the
   * analyzer an empty name to complain about instead of the parser refusing a word that is visibly
   * unfinished.
   */
  protected def attrWordPrefixed(prefix: String): Parser[String] =
    accept(
      s"'$prefix…'",
      { case t: lexical.Identifier if t.chars.startsWith(prefix) && t.chars.length > prefix.length =>
        t.chars.drop(prefix.length)
      },
    )

  /** Whether an attribute is one half of a frame, for the refusal of `@pure` beside one. */
  protected def frame(a: Attr): Boolean = a match
    case _: Attr.Reads | _: Attr.Writes => true
    case _                              => false

  /** The attribute written twice, where one is, for the refusal above. */
  protected def duplicated(as: List[Attr]): Option[String] =
    as.map(_.word).groupBy(identity).collectFirst { case (w, ws) if ws.length > 1 => w }

  protected def attributed(f: FuncDecl, as: List[Attr]): FuncDecl =
    as.foldLeft(f) {
      case (d, Attr.Test(t)) => d.copy(test = Some(t))
      case (d, Attr.TailRec) => d.copy(tailrec = true)
      case (d, Attr.Pure)    => d.copy(pure = true)
      case (d, Attr.Ghost)   => d.copy(ghost = true)
      case (d, Attr.Reads(ns))  => d.copy(reads = Some(ns))
      case (d, Attr.Writes(ns)) => d.copy(writes = Some(ns))
      case (d, Attr.Export(e))  => d.copy(exported = Some(e))
      // `@section` is the one attribute that marks either kind of thing, so it reaches this fold and
      // the binding's alike — a `.ramfunc` is a definition placed somewhere exactly as a vector table
      // is storage placed somewhere.
      case (d, Attr.Section(s)) => d.copy(section = Some(s))
      case (d, Attr.Crossing(ns)) => d.copy(crossing = ns)
      // A layout attribute never reaches here: the grammar routes a declaration carrying one to a
      // struct, and refuses the mix. Listed so that a new attribute makes this fold fail to compile
      // rather than silently drop what it was asked to record.
      case (d, Attr.Packed | _: Attr.Align) => d
    }

  private lazy val testArgs: Parser[TestAttr] =
    contractMsg ~ opt(op(",") ~> testExpectation) ^^ {
      case d ~ e => TestAttr(Some(d), e.isDefined, e.flatten)
    } | testExpectation ^^ (e => TestAttr(None, true, e))

  /** `should_trap`, alone or with the substring a trapping run must have printed. */
  private lazy val testExpectation: Parser[Option[String]] =
    accept("'should_trap'", { case t: lexical.Identifier if t.chars == "should_trap" => () }) ~>
      opt(op(":") ~> contractMsg)
}
