package sh.sysl

import scala.util.parsing.input.Position

/** The sysl parser: a packrat combinator grammar over the materialized token list from
 * `SyslLexical` (see design/front-end.md).
 *
 * The grammar is split by area, each area a trait: `SyslParserBase` (the token reader, position
 * stamping, terminals, and the crossings between areas), `ExprParser` (the precedence ladder and
 * the literals), `TypeParser` (types as written, and generic parameter lists), `DeclParser`
 * (functions, structs, enums, traits, impls), `AttrParser` (the annotations above a declaration),
 * `HeaderParser` (the file header and imports), `PatternParser` (`match` and patterns), and what is
 * left here: statements, the bindings, control flow, inline assembly, and the entry points.
 *
 * The `List[Token]` is the reversibility seam: a hand-written parser could later consume the same
 * tokens with no change to the lexer.
 *
 * Every rule that builds a node wraps itself in `at`, which stamps the node with the position of the
 * first token the rule consumed. A parser is bound to one `Source` so that stamp is complete — file,
 * line, and column — the moment the node exists.
 */
class SyslParser(val source: Source)
    extends DeclParser,
      TypeParser,
      AttrParser,
      HeaderParser,
      PatternParser {

  // --- statements ----------------------------------------------------------------------

  lazy val statement: PackratParser[Stmt] =
    at(
      misplacedHeaderAttr | importDecl | implDecl | declaration | varDecl | refDecl | returnStmt |
        breakStmt | continueStmt | deferStmt | asmStmt | requireStmt | ensureStmt | invariantStmt |
        variantStmt | multiAssign | resultListStmt | exprStmt,
    )

  /** A statement written on the same line as the keyword that introduces it.
   *
   * It is every statement **but** a result list, which is a whole line by construction: a branch
   * written inline is part of a larger expression, so a comma after it belongs to whatever that
   * expression is part of. Without this, `-> int, string = if c then 1 else 0, "x"` would read the
   * comma as the *branch's* result list and leave the function one value.
   */
  protected lazy val inlineStatement: PackratParser[Stmt] =
    at(
      importDecl | implDecl | declaration | varDecl | refDecl | returnStmt |
        breakStmt | continueStmt | deferStmt | requireStmt | ensureStmt | multiAssign | exprStmt,
    )

  /** `a, b = b, a` — a comma list of places, a comma list of values (`00 §2`).
   *
   * It comes before `exprStmt` and after everything else, and it needs **two or more** targets to
   * commit: with one it would be an ordinary assignment written the long way round, which
   * `expression` already reads. Nothing below a statement admits a bare comma, so the first one is
   * enough to tell the two apart with no lookahead to speak of.
   */
  protected lazy val multiAssign: PackratParser[Stmt] =
    (logicalOr <~ op(",")) ~ rep1sep(logicalOr, op(",")) ~ assignOp ~ rep1sep(expression, op(",")) ^^ {
      case first ~ rest ~ o ~ values => MultiAssign(o, first :: rest, values)
    }

  /** `require <cond> [, "message"]` / `ensure <cond> [, "message"]` — a design-by-contract
   * clause. Only meaningful at the top of a function body; the analyzer rejects one that
   * appears after ordinary statements.
   */
  protected lazy val requireStmt: PackratParser[Stmt] =
    op("require") ~> expression ~ opt(op(",") ~> contractMsg) ^^ { case c ~ m => Require(c, m) }

  protected lazy val ensureStmt: PackratParser[Stmt] =
    op("ensure") ~> expression ~ opt(op(",") ~> contractMsg) ^^ { case c ~ m => Ensure(c, m) }

  /** `invariant <cond> [, "message"]` and `variant <expr>` — the loop clauses of `17 §3`, and, for
   * `variant`, the recursion measure a function's contract block carries (`17 §4`).
   *
   * **Both words are contextual**, matched as soft words exactly as the struct `invariant` of
   * `16 §6` is — which is also where `invariant` was already being read this way, so this spends no
   * new word. The cost of that is the cost `is` and `not` already pay: a *bare statement* that calls
   * a function of the same name, `invariant(x)`, reads as a clause over `(x)`. Anywhere that is not
   * a bare statement — `val v = invariant(x)`, an argument, a condition — the call is unambiguous,
   * and a value named `invariant` is untouched. That trade buys the clause its natural spelling in
   * the position a reader writes it.
   */
  protected lazy val invariantStmt: PackratParser[Stmt] =
    invariantKw ~> expression ~ opt(op(",") ~> contractMsg) ^^ { case c ~ m => Invariant(c, m) }

  protected lazy val variantStmt: PackratParser[Stmt] =
    variantKw ~> expression ^^ Variant.apply

  protected lazy val variantKw: Parser[Unit] = softWord("variant")

  protected lazy val contractMsg: Parser[String] =
    accept("string literal", { case t: lexical.StrLit => t.value })

  /** `static val`, `static var` — a binding in the file the program starts in asking to be the
   * module's rather than that file's body's (`13 §7`).
   *
   * The modifier follows the visibility rather than preceding it, so `private static var ticks: u64`
   * reads in the order the two questions are asked: how far it reaches, then whose it is.
   *
   * **A function never takes it**, which is worth saying because it is the one form a reader expects
   * to. Whether a function at the top of the entry file belongs to the body is settled by whether it
   * reads one of the body's bindings (`Bodies.capturing`) — one that reads none is the module's
   * already and has nothing to ask for, and one that reads any is holding a frame, which is precisely
   * what a module member cannot do. So the modifier would be either redundant or impossible, and
   * neither is worth a keyword.
   *
   * A type, a `const`, an `extern` and an `import` are module members wherever they are written, so
   * `static` on one says nothing either. The sentence below covers all of them, rather than leaving
   * the grammar to complain that a declaration form was not among the two.
   */
  protected lazy val staticDecl: PackratParser[Stmt] =
    visibility ~ (op("static") ~> (valDecl | varDecl)) ^^ {
      case Visibility.Public ~ d => StaticDecl(d)
      case v ~ d                 => StaticDecl(restrict(v, d))
    } | (visibility <~ op("static")) ~> err(
      "'static' marks a 'val' or a 'var' in the file a program starts in, saying it belongs to the " +
        "module rather than to that file's body. Everything else there is the module's already: a " +
        "type, a constant, an 'extern' and an 'import' are wherever they are written, and a function " +
        "is one unless it reads a binding of the body — which is a frame to carry, and the one thing " +
        "a module member cannot have",
    )

  /** A declaration that may carry a visibility modifier (`13 §2`).
   *
   * The forms are grouped so the modifier is written once, before whichever of them follows, rather
   * than threaded through rules that would each have to remember it. An `impl` is not among them and
   * takes none: it declares no name, so there is nothing for a modifier to restrict.
   *
   * **`varDecl` is here as well as in `statement`, and that is what lets a module's storage be
   * private.** Outside the file a program starts in, a top-level `var` is the module's storage and is
   * the same declaration `static var` spells in that file (`13 §7`) — so it takes a visibility for the
   * same reason the `val` beside it does. Without this the modifier was a parse error reading
   * "identifier expected", which says the word was not followed by a name rather than that the form
   * takes none; `private static var` parsed all along, and the two spellings are one declaration.
   *
   * It changes nothing for a bare `var`: `visibility` succeeds as `Public` on the empty input, the
   * `Public` branch hands back exactly the node `varDecl` built, and `statement`'s own `varDecl` reads
   * the ones written inside a body. In the entry file the modifier restricts a local and so says
   * nothing, which is what `private val` there has always done.
   */
  protected lazy val declaration: PackratParser[Stmt] =
    assertDecl |
      attributedDecl |
      implVisibility |
      misplacedOverride |
      staticDecl |
      visibility ~ (structDecl | enumDecl | typeDecl | traitDecl | externDecl | cConstDecl |
        cTypeDecl | constDecl | valDecl | varDecl | funcDecl) ^^ {
        case Visibility.Public ~ d => d
        case v ~ d                 => restrict(v, d)
      }

  /** A function carrying annotations, which is a declaration with a line or more in front of it.
   *
   * Each annotation is its own line and the declaration follows the last of them, which is why the
   * newlines between them are consumed here: the statement separator would otherwise end the
   * statement at an annotation, leaving a prefix with nothing to attach to. Everything about the
   * declaration itself is still `declaration`'s — an annotated function may be `private`, and is
   * written exactly as any other.
   *
   * Only a function may carry one, and the refusal below is what says so. A struct or a `val` with
   * `@test` above it is a mistake about what a test *is* rather than a syntax error, so it is
   * answered with the sentence rather than with the list of forms the grammar could still have read.
   */
  protected lazy val attributedDecl: PackratParser[Stmt] =
    rep1(attribute <~ skipNewlines) >> { as =>
      // Once an attribute has been read the statement is committed to being an attributed
      // declaration, which is what `>>` buys: everything after it is read against that, so a
      // declaration that cannot carry one is answered with the sentence below rather than with the
      // grammar's complaint about whichever alternative it went on to try.
      duplicated(as) match
        case Some(dup) =>
          err(s"'@$dup' is written twice above one declaration, and it says nothing the once does not")
        // `@pure` *is* `@reads() @writes()` plus the further bans of `17 §6`, so the two together say
        // one thing twice — and worse, they could be made to disagree, which would leave nothing to
        // say which of the two claims the function was held to.
        case None if as.exists(_ == Attr.Pure) && as.exists(frame) =>
          err("'@pure' already says '@reads()' and '@writes()', so a frame beside it says one thing " +
            "twice — write the frame alone if the function touches module storage, and '@pure' alone " +
            "if it touches none")
        // `@packed` describes the arrangement of fields *within* a type and `@section` places one
        // object, so the two cannot be about the same declaration whichever kind it turns out to be:
        // a struct is not an object, and a binding has no fields.
        case None if as.exists(_ == Attr.Packed) && as.exists(places) =>
          err("'@packed' lays out a struct's fields and '@section(\"...\")' places one object, so " +
            "they cannot stand above one declaration — a type occupies no address of its own, and " +
            "the storage that holds a packed value is what a section would be about")
        // `@export` is the second attribute marking either kind of thing, and it pairs with the
        // layout attributes rather than with `@section`: a struct's C name sits beside its layout,
        // and a function's symbol beside `@pure` and the rest. This is the struct reading, and a set
        // holding nothing but `@export` reaches the function reading through `namedStruct`.
        case None if as.exists(names) && as.forall(a => layout(a) || names(a)) =>
          namedStruct(as)
        // A layout annotation and a function annotation describe different kinds of thing, so one
        // declaration cannot carry both — and saying which pair collided is more use than the
        // grammar's complaint about whichever alternative it went on to try. `@section` is in
        // neither camp: it marks whatever occupies an address, which is a binding or a function.
        case None if as.exists(layout) && as.exists(a => !layout(a) && !places(a)) =>
          err("'@packed' and '@align' describe a layout and the rest mark a function, so they " +
            "cannot stand above one declaration — a struct has no body to be tail-recursive or " +
            "pure in, and a function has no fields to lay out")
        // `@align` beside `@section` is a binding and only a binding: a struct takes the first and
        // not the second, and a function takes the second and not the first. It is also the pair a
        // statically placed stack is written with, so it is the case rather than a corner.
        case None if as.exists(layout) && as.exists(places) =>
          storageDecl(as) | err(
            "'@align(n)' beside '@section(\"...\")' marks one binding's storage — the boundary it " +
              "begins on and the section it sits in are both about one object, and this declares none",
          )
        case None if as.forall(layout) =>
          (visibility ~ structDecl) ^^ {
            case Visibility.Public ~ (s: StructDecl) => laidOut(s, as)
            case v ~ (s: StructDecl)                 => restrict(v, laidOut(s, as))
            case _ ~ other                           => other
          } | storageDecl(as) | err(
            if as.forall(aligns) then
              "'@align(n)' marks a struct or one binding's storage, and this is neither — an enum's " +
                "layout follows from its variants and a scalar's is the target's"
            else
              "'@packed' describes how a struct's fields are laid out, so it can only mark a struct " +
                "— a 'var' or a 'val' has no fields to pack, and '@align(n)' is the one of the two " +
                "that may stand above one",
          )
        // `@section` alone marks either kind, so both are read: a binding is settled by its own
        // keyword and a function by everything else, which is why the storage form goes first and
        // declines without consuming anything.
        case None if as.forall(places) =>
          storageDecl(as) | (visibility ~ funcDecl) ^^ {
            case Visibility.Public ~ (f: FuncDecl) => attributed(f, as)
            case v ~ (f: FuncDecl)                 => restrict(v, attributed(f, as))
            case _ ~ other                         => other
          } | err(
            "'@section(\"...\")' places one object in a linker section, so it marks a 'var', a " +
              "'val' or a function — a 'const' is folded into every use and has no storage to place, " +
              "and an 'extern' names something this program does not define",
          )
        case None =>
          (visibility ~ funcDecl) ^^ {
            case Visibility.Public ~ (f: FuncDecl) => attributed(f, as)
            case v ~ (f: FuncDecl)                 => restrict(v, attributed(f, as))
            case _ ~ other                         => other
          } | err(
            "an annotation marks a function, and only a function — neither what 'sysl test' calls " +
              "nor what recurses is anything a declaration of another kind supplies. '@packed', " +
              "'@align(n)' and '@export(\"...\")' are the three that mark a struct instead",
          )
    }

  /** A struct carrying `@export("…")`, and — where that is the only annotation above it — a function
   * carrying it instead.
   *
   * The two readings are here together because `@export` names *either* kind of thing: a function's
   * symbol and a struct's C name are one request made of the two declarations a generated header
   * holds. `structDecl` goes first and declines without consuming, which is how `@section` reaches
   * the function form past `storageDecl`.
   */
  private def namedStruct(as: List[Attr]): PackratParser[Stmt] =
    (visibility ~ structDecl) ^^ {
      case Visibility.Public ~ (s: StructDecl) => laidOut(s, as)
      case v ~ (s: StructDecl)                 => restrict(v, laidOut(s, as))
      case _ ~ other                           => other
    } | (if !as.forall(names) then failure("not a function's annotation")
         else
           (visibility ~ funcDecl) ^^ {
             case Visibility.Public ~ (f: FuncDecl) => attributed(f, as)
             case v ~ (f: FuncDecl)                 => restrict(v, attributed(f, as))
             case _ ~ other                         => other
           }) | err(
      if as.forall(names) then
        "'@export' names what C sees — a function's symbol, or the name a struct's 'typedef' " +
          "carries in a generated header — and this declares neither. A simple enum is spelled as " +
          "the integer it is, so it has no name in the header to choose, and an 'extern' names " +
          "something this program does not define"
      else
        "'@packed' and '@align(n)' lay out a struct and '@export(\"...\")' names one in a generated " +
          "header, so together they mark a struct — and this declares none",
    )

  /** Whether an attribute **names** what it marks to C, which is `@export` and only `@export`. It is
   * a category of its own for `@section`'s reason: it is one of the two attributes that mark either
   * a struct or a function, and the pair it may be written beside differs by which.
   */
  private def names(a: Attr): Boolean = a match
    case _: Attr.Export => true
    case _              => false

  /** Whether an attribute describes a **layout** rather than a function. */
  private def layout(a: Attr): Boolean = a match
    case Attr.Packed | _: Attr.Align => true
    case _                           => false

  /** Whether an attribute is one of the layout pair that a *binding* can carry, which is `@align`
   * and only `@align`.
   *
   * `@packed` describes the arrangement of fields *within* an aggregate, and a `var` has none — so it
   * is not merely unimplemented on a binding, it has nothing there to mean.
   */
  private def aligns(a: Attr): Boolean = a match
    case _: Attr.Align => true
    case _             => false

  /** Whether an attribute **places** what it marks rather than describing it, which is `@section` and
   * only `@section`. It is a category of its own because it is the one attribute that marks either a
   * binding or a function: both occupy an address, and placement is the same request about each.
   */
  private def places(a: Attr): Boolean = a match
    case _: Attr.Section => true
    case _               => false

  /** `@align(n)` above a `var` or a `val` — the boundary one object's storage begins on, which is C's
   * `alignas` rather than Rust's `#[repr(align)]`.
   *
   * The capability was already reachable through the type: a struct carrying the attribute aligns
   * every value of itself, and a named aligned type is reusable where a repeated attribute is not.
   * What the type form costs is at the *use* site — a buffer wrapped in a struct is read as
   * `region.bytes[i]` rather than `region[i]` — which is the whole argument for this spelling.
   *
   * All four forms take it, and they must: `static var` and `static val` are the entry file's
   * spelling of the same declaration a plain `var` and `val` are in every other file (`13 §7`), so an
   * attribute that reached one and not the other would mean different things in different files.
   * `staticDecl` is tried first because it begins the same way and is settled by its own word.
   *
   * A **pattern** or a **comma list** is read and then refused, rather than left out of the forms
   * offered. Both bind several names, so there is no one object for a boundary to be about — and a
   * grammar that simply did not accept them would report against the enclosing line instead of
   * against the binding, which is the diagnostic this whole shape exists to avoid.
   */
  private def storageDecl(as: List[Attr]): PackratParser[Stmt] =
    if !as.forall(a => aligns(a) || places(a)) then failure("not a binding's annotation")
    else
      (staticDecl | visibility ~ (valDecl | varDecl) ^^ {
        case Visibility.Public ~ d => d
        case v ~ d                 => restrict(v, d)
      }) >> { d =>
        if !oneBinding(d) then
          // Two sentences rather than one about "an annotation", because each names the thing it is
          // about — and because `@align`'s is quoted verbatim on the site, where a word inserted into
          // the middle of a diagnostic breaks the page at the next version bump and not before.
          if as.forall(aligns) then
            err("'@align(n)' is the boundary one object's storage begins on, and a binding that " +
              "names several has no one object for it to be about — declare them on lines of their own")
          else
            err("'@section(\"...\")' places one object, and a binding that names several has no one " +
              "object for it to be about — declare them on lines of their own")
        else
          success(as.foldLeft(d) {
            case (s, Attr.Align(n))   => aligned(s, n)
            case (s, Attr.Section(n)) => placed(s, n)
            case (s, _)               => s
          })
      }

  /** Whether a binding names exactly one thing, reaching through the `static` wrapper. */
  private def oneBinding(s: Stmt): Boolean = s match
    case _: VarDecl | _: ValDecl => true
    case StaticDecl(d)           => oneBinding(d)
    case _                       => false

  /** One layout attribute folded onto whichever binding it was written above, reaching through the
   * `static` wrapper to the declaration inside it.
   */
  private def aligned(s: Stmt, bound: Expr): Stmt = s match
    case d: VarDecl    => d.copy(align = Some(bound)).setPos(d.pos)
    case d: ValDecl    => d.copy(align = Some(bound)).setPos(d.pos)
    case StaticDecl(d) => StaticDecl(aligned(d, bound)).setPos(s.pos)
    case other         => other

  /** `@section("…")` folded onto whichever binding it was written above, reaching through the
   * `static` wrapper exactly as the boundary above it does.
   */
  private def placed(s: Stmt, name: String): Stmt = s match
    case d: VarDecl    => d.copy(section = Some(name)).setPos(d.pos)
    case d: ValDecl    => d.copy(section = Some(name)).setPos(d.pos)
    case StaticDecl(d) => StaticDecl(placed(d, name)).setPos(s.pos)
    case other         => other

  /** The struct these annotations describe, folded onto its declaration — the layout pair, and the
   * name a generated C header gives it.
   */
  private def laidOut(s: StructDecl, as: List[Attr]): StructDecl =
    as.foldLeft(s) {
      case (d, Attr.Packed)    => d.copy(packed = true)
      case (d, Attr.Align(n))  => d.copy(alignment = Some(n))
      case (d, Attr.Export(e)) => d.copy(cname = Some(e))
      case (d, _)              => d
    }


  /** A modifier written in front of an `impl` block, refused where it stands.
   *
   * This is the same refusal `noVisibility` gives the members *inside* one, and it is here for the
   * same reason: `impl` is not among the forms above, so without a rule of its own the reading is
   * "identifier expected" at the `impl` — the grammar's complaint that the modifier was not
   * followed by a name, which says nothing about why there is no name to follow it with.
   */
  private lazy val implVisibility: Parser[Nothing] =
    op("private") ~ opt(op("[") ~> ident <~ op("]")) ~ guard(op("impl")) ~> err(
      "an 'impl' block carries no visibility of its own — it declares no name for one to restrict, " +
        "and what it supplies is reached at the reach of the trait that asked for it",
    )

  /** `override` in front of a top-level declaration, refused where it stands and for the reason
   * `implVisibility` is refused: the word has a place, and a reader who writes it elsewhere is better
   * served by being told which place than by the grammar's complaint about the word after it.
   *
   * It is reached only after `implDecl` has declined the line, so `override impl` never arrives here.
   */
  protected lazy val misplacedOverride: Parser[Nothing] =
    op("override") ~> err(
      "'override' marks something that replaces an implementation already covering the same type, so " +
        "it goes in front of an 'impl' block or of a member inside one — a declaration of its own " +
        "replaces nothing",
    )

  /** `private`, `private[M]`, or nothing at all — which is public (`13 §2`). There is no `pub`
   * keyword; its absence *is* public, so the unmarked case is the one that writes nothing.
   */
  protected lazy val visibility: Parser[Visibility] =
    op("private") ~> opt(op("[") ~> ident <~ op("]")) ^^ {
      case Some(m) => Visibility.Scoped(m)
      case None    => Visibility.File
    } | success(Visibility.Public)

  protected def restrict(v: Visibility, d: Stmt): Stmt = d match
    case s: StructDecl    => s.copy(vis = v).setPos(s.pos)
    case e: EnumDecl      => e.copy(vis = v).setPos(e.pos)
    case t: TraitDecl     => t.copy(vis = v).setPos(t.pos)
    case e: ExternDecl    => e.copy(vis = v).setPos(e.pos)
    case e: ExternVarDecl => e.copy(vis = v).setPos(e.pos)
    case c: ConstDecl     => c.copy(vis = v).setPos(c.pos)
    case b: CConstBlock   =>
      CConstBlock(b.consts.map(c => c.copy(vis = v).setPos(c.pos))).setPos(b.pos)
    case b: CTypeBlock    =>
      CTypeBlock(b.types.map(t => t.copy(vis = v).setPos(t.pos))).setPos(b.pos)
    case l: ValDecl       => l.copy(vis = v).setPos(l.pos)
    case r: VarDecl       => r.copy(vis = v).setPos(r.pos)
    case f: FuncDecl      => f.copy(vis = v).setPos(f.pos)
    case t: TypeDecl      => t.copy(vis = v).setPos(t.pos)
    case other            => other


  protected lazy val varDecl: PackratParser[Stmt] =
    multiDecl("var", mutable = true) |
      patternDecl("var", mutable = true) |
      op("var") ~> ident ~ opt(op(":") ~> typeRef) ~ opt(op("=") ~> initializer) ^^ {
        case n ~ t ~ e => VarDecl(n, t, e.map(Placeholders.lift))
      }

  /** What a binding's `=` takes: one expression, or an indented block whose trailing expression is
   * the value (`00 § Continuing a line`).
   *
   * The block is tried first and costs nothing when there is not one — it opens on `Newline`+`Indent`,
   * which no expression can begin with, so a value written on the same line as the `=` reaches
   * `expression` having consumed nothing.
   *
   * **A block of a single expression is that expression.** The two are the same value written two
   * ways, and collapsing here is what keeps a module `val` with its value on the next line a constant
   * tree rather than a computed initializer — a distinction a reader who moved the line to fit the
   * margin never asked to make. A block that binds anything cannot collapse and does not.
   */
  protected lazy val initializer: PackratParser[Expr] =
    blockValue | expression

  /** [[blockAhead]] is what keeps the diagnostic for a value that was simply forgotten: without it
   * `val x =` is answered with `indent expected` against the following line, which describes a block
   * the writer had not begun.
   */
  private lazy val blockValue: PackratParser[Expr] =
    blockAhead ~> suite ^^ {
      case List(ExprStmt(e)) => e
      case stmts @ (h :: _)  => Block(stmts).setPos(h.pos)
      case Nil               => UnitLit()
    }

  /** `val (a, b) = …` / `var (a, b) = …` — a binding written as a **pattern** (`00 §13`).
   *
   * Two patterns may stand here, and they are the two that cannot fail to match: a **tuple**
   * pattern, and a **struct** pattern, which names a type that has exactly one shape.
   *
   * **A variant pattern is parsed here too, so that it can be refused with a reason.** It is not
   * legal — an enum has several shapes and naming one is a test — but leaving it out of the grammar
   * does not make it an error a reader can act on: the parse fails somewhere above and reports
   * against the enclosing declaration's own line, which is the diagnostic this whole form exists to
   * stop happening. Accepting it and complaining in the analyzer puts the message on the binding.
   *
   * It is tried after the comma form and before the plain one, which is all the ordering it needs:
   * a tuple pattern opens with a parenthesis, and the other two are a name followed by a brace or a
   * parenthesis, neither of which a plain binding or a comma list can begin with. The plain form
   * still reads a bare name, so nothing that parsed before this existed parses differently now.
   *
   * **The pattern is the whole of the left side, with no type annotation beside it.** That is
   * `12 §5b`'s open question again rather than an oversight — the parts of a destructuring have
   * nowhere to carry a type, and inference covers what the form is for.
   */
  protected def patternDecl(keyword: String, mutable: Boolean): PackratParser[Stmt] =
    (op(keyword) ~> destructuring) ~ (op("=") ~> initializer) ^^ {
      case p ~ v => PatternDecl(p, mutable, Placeholders.lift(v))
    }

  /** The patterns a **binding** may be written with: the two that cannot fail, the variant one that
   * is parsed to be refused, and any of those three named with `n @`.
   *
   * The name is optional rather than a fourth alternative so that `var whole @ Point{x, y} = p`
   * reads as the same form with a name on it, which is what it is. It commits on the `@`, so a plain
   * `var x = e` — which is not this production at all — is unaffected.
   */
  private lazy val destructuring: Parser[Pattern] =
    opt(ident <~ op("@")) ~ (structPattern | variantPattern | tuplePattern) ^^ {
      case Some(n) ~ p => BindPattern(n, p)
      case None ~ p    => p
    }

  /** `ref name = place` (`03 § ref`).
   *
   * Neither half of what `var` and `val` accept is offered here, and each absence is a rule rather
   * than an omission. There is **no type annotation**, because a ref is a local declaration and never
   * a type, so it states nothing to a reader elsewhere and its type is the place's by construction.
   * There is **no multiple form**, because the comma family binds several names to several *values*
   * (`00 §2`) and a place list is what a multi-assignment already is.
   *
   * The initializer is parsed as any expression and held to being a place by the analyzer, which is
   * where the question can be answered at all — `f()[i]` and `xs[i]` are the same shape until
   * something knows what `f` and `xs` are.
   */
  protected lazy val refDecl: PackratParser[Stmt] =
    op("ref") ~> ident ~ (op("=") ~> expression) ^^ { case n ~ p => RefDecl(n, Placeholders.lift(p)) }

  /** `val a, b = …` / `var a, b = …` — a binding that names several things (`00 §2`).
   *
   * Two or more names, and an initializer, are both required: one name is the ordinary form, and a
   * multiple binding with nothing to take apart names nothing. The parts carry no type annotation,
   * which is `12 §5b`'s open question rather than an oversight — inference covers what the form is
   * for, and there is no spelling yet for the case it does not.
   */
  protected def multiDecl(keyword: String, mutable: Boolean): PackratParser[Stmt] =
    (op(keyword) ~> ident <~ op(",")) ~ rep1sep(ident, op(",")) ~ (op("=") ~> rep1sep(expression, op(","))) ^^ {
      case first ~ rest ~ values => MultiDecl(first :: rest, mutable, values)
    }

  /** `const name: type = value` (`13 §7`). Both halves are mandatory, which is what tells it apart
   * from a `var` at a glance as well as to the parser: a constant with no value is not a
   * declaration of anything, and a type left off would be the one declaration in the language whose
   * interface could not be read off its syntax.
   *
   * **A block is read here and refused in the analyzer**, which is the same arrangement a variant
   * pattern in a binding gets and for the same reason: a `const` folds, a block does not, and leaving
   * the form out of the grammar answers the reader who reached for it with `expression expected`
   * rather than with the rule.
   */
  protected lazy val constDecl: PackratParser[Stmt] =
    op("const") ~> ident ~ (op(":") ~> typeRef) ~ (op("=") ~> initializer) ^^ {
      case n ~ t ~ v => ConstDecl(n, t, v)
    }

  /** `c const` and its constants, each of whose values is a C expression in quotes (`15 §7`).
   *
   * ```
   * c const
   *     STATIC_TASK_SIZE: usize = "sizeof(StaticTask_t)"
   *     MAX_DELAY: u32          = "portMAX_DELAY"
   * ```
   *
   * **`c` is contextual and stays an ordinary identifier**, which the `const` after it is what makes
   * safe: nothing else in the language may follow a name with a keyword, so the two words together
   * cannot be anything but this, and a program is free to call a variable `c` — which one counting
   * characters certainly will. It is a cheaper disambiguation than `interrupt`'s (`15 §10`), which
   * needs a lookahead past an optional parenthesized argument to find the name it qualifies.
   *
   * **The C is quoted with a plain string and carries no prefix.** A `c"…"` form would be a second
   * literal kind bought to say what the header already said: inside this block a string can mean
   * nothing else, since there is no other thing a value here could be. The quotes themselves are not
   * optional, and that is the point of them — what is inside is a different language, and a reader
   * should be able to see where it starts without knowing which words are C's.
   *
   * The block is required rather than a `c const NAME: T = "…"` one-liner being offered beside it.
   * One form is what keeps the cost legible: the constants of a file are measured by a single probe,
   * so a run of them under one header reads the way the work is actually done.
   */
  protected lazy val cConstDecl: PackratParser[Stmt] =
    softWord("c") ~> op("const") ~> (
      newline ~> indent ~> skipNewlines ~> rep1sep(cConstItem, newlines) <~ skipNewlines <~ dedent ^^
        CConstBlock.apply |
        err("'c const' is followed by its constants, indented under it, each a name and a type and a " +
          "C expression in quotes: 'SIZE: usize = \"sizeof(struct s)\"'")
    )

  /** One line of a `c const` block. The type is mandatory for `const`'s reason and one more: it is
   * what decides whether the C is read back as signed, and therefore what the probe declares.
   */
  protected lazy val cConstItem: Parser[CConstDecl] =
    at(ident ~ (op(":") ~> typeRef) ~ (op("=") ~> linkName) ^^ { case n ~ t ~ c => CConstDecl(n, t, c) })

  /** `c type` — the sysl types a file's C typedefs turn out to be (`15 §7`):
   *
   * ```
   * c type
   *     Tick  = "TickType_t"
   *     Stack = "configSTACK_DEPTH_TYPE"
   * ```
   *
   * The same two words in the same order as `c const`, and for the same reason: `c` marks which
   * language the right-hand sides are written in and stays an ordinary identifier everywhere else,
   * which the keyword after it is what makes safe.
   *
   * **A line carries no sysl type**, which is the whole difference from a `c const` line — the type
   * is the answer rather than the question, and writing one would be asserting what the measurement
   * is for. What a program wanting to *assert* a width writes is `@assert`, against a `c const`
   * holding the `sizeof`, which says the same thing where it can be checked.
   */
  protected lazy val cTypeDecl: PackratParser[Stmt] =
    softWord("c") ~> op("type") ~> (
      newline ~> indent ~> skipNewlines ~> rep1sep(cTypeItem, newlines) <~ skipNewlines <~ dedent ^^
        CTypeBlock.apply |
        err("'c type' is followed by its types, indented under it, each a name and a C type name in " +
          "quotes: 'Tick = \"TickType_t\"'")
    )

  /** One line of a `c type` block: the sysl name, and the C type it stands for. */
  protected lazy val cTypeItem: Parser[CTypeDecl] =
    at(ident ~ (op("=") ~> linkName) ^^ { case n ~ c => CTypeDecl(n, c) })

  /** `@assert(cond)`, `@assert(cond, "why")` — a condition checked while compiling.
   *
   * It reads its own `@` and stands where a declaration stands, which is what tells it apart from
   * the annotations of `AttrParser`: those describe the function written under them, and this
   * describes nothing but itself. That is also why it must be tried **before** `attributedDecl` —
   * that rule ends in a refusal saying an annotation marks a function, which is exactly the wrong
   * thing to say about this one.
   *
   * The message is raised **inside** the parentheses rather than after them, by the rule a dead
   * `err` taught: a form that reaches further along the line outranks one that failed earlier, so a
   * sentence written past the point of divergence is never the one reported.
   */
  protected lazy val assertDecl: PackratParser[Stmt] =
    at(op("@") ~> attrWord("assert") ~> (missingAssertParens |
      op("(") ~>
      (expression ~ opt(op(",") ~> strLit) | err(
        "'@assert' takes a condition the compiler can settle, and an optional message: " +
          "'@assert(sizeof(T) == 16, \"why\")'")) <~ op(")") ^^ {
      case (e: Expr) ~ (m: Option[?]) =>
        AssertDecl(e, m.collect { case StrLit(s) => s })
    }))

  /** `@assert cond` — the parentheses left off, which without this rule is answered by a sentence
   * saying `@assert` is not an annotation.
   *
   * That message comes from `unknownAttr`, and it is reached because this rule declines at the `(`
   * and `attributedDecl` is tried next: the two land on the same token, and at equal positions the
   * later one wins. What it then prints is a roster of every annotation the language has, with this
   * one absent from it — so a reader comparing their line against the list concludes there is no
   * `@assert`, when the whole of their mistake is a missing bracket.
   *
   * The refusal is raised **before** the `(` rather than after it, so that it is this rule's `Error`
   * that survives: an `Error` outranks whatever the alternatives reach, but only while nothing has
   * consumed past the point they diverge at.
   */
  private lazy val missingAssertParens: Parser[Stmt] =
    not(op("(")) ~> err("'@assert' takes its condition in parentheses — " +
      "'@assert(sizeof(T) == 16)', with an optional message after a comma. It is the parentheses " +
      "that make it this declaration rather than an annotation about the one under it")

  /** `val name [: type] = value` — a binding that is written once (`07`, `13 §7`).
   *
   * The **value is mandatory** and the type is not, which is the opposite arrangement from `const`
   * and for the opposite reason: a `val` with nothing to hold is not a declaration of anything,
   * while its type is readable off the value it was given. Whether a type may be left off is
   * nevertheless a question about *where* it was written — a module member states its interface —
   * and where is something only the analyzer knows, so the syntax accepts either and the rule is
   * applied there.
   */
  protected lazy val valDecl: PackratParser[Stmt] =
    multiDecl("val", mutable = false) |
      patternDecl("val", mutable = false) |
      op("val") ~> ident ~ opt(op(":") ~> typeRef) ~ (op("=") ~> initializer) ^^ {
        case n ~ t ~ v => ValDecl(n, t, Placeholders.lift(v))
      }

  /** A statement-level expression is the last of the three places a placeholder closes at
   * (`12 §5c`) — it is what stops one from reaching past the statement it was written in.
   */
  protected lazy val exprStmt: PackratParser[Stmt] =
    expression ^^ (e => ExprStmt(Placeholders.lift(e)).setPos(e.pos))

  /** `a, b` standing alone — a function's result list as its trailing expression. It is tried
   * after the assignment form, which starts the same way and is settled by its `=`.
   */
  protected lazy val resultListStmt: PackratParser[Stmt] =
    expression ~ rep1(op(",") ~> expression) <~ endOfStatement ^^ { case e ~ more =>
      ExprStmt(ResultList(e :: more).setPos(e.pos))
    }

  /** That the list just parsed really was a whole line.
   *
   * Without it the form is greedy across a comma that belongs to something outside: an inline
   * `else` body is a statement, so `f(if c then a else b, x, y)` would read `b, x, y` as a result
   * list and leave the call one argument. A result list is the last thing on its line by
   * construction, so requiring that is exact rather than a heuristic.
   */
  protected lazy val endOfStatement: Parser[Unit] =
    guard(newline) | guard(dedent) | Parser(in =>
      if in.atEnd then Success((), in) else Failure("end of statement expected", in))

  protected lazy val returnStmt: PackratParser[Stmt] =
    op("return") ~> opt(resultValue) ^^ Return.apply

  /** What a function hands back: one expression, or the several its result list declares. */
  protected lazy val resultValue: PackratParser[Expr] =
    expression ~ rep(op(",") ~> expression) ^^ {
      case e ~ Nil  => Placeholders.lift(e)
      case e ~ more => ResultList((e :: more).map(Placeholders.lift)).setPos(e.pos)
    }

  protected lazy val breakStmt: PackratParser[Stmt] =
    op("break") ~> opt(labelRef) ~ opt(expression) ^^ { case lbl ~ v => Break(lbl, v) }

  protected lazy val continueStmt: PackratParser[Stmt] =
    op("continue") ~> opt(labelRef) ^^ (lbl => Continue(lbl))

  /** `defer stmt` — what to run on the way out of this block (`03 § defer`).
   *
   * What follows is an inline statement, so the whole form is one line: the deferred thing is a
   * release, and a release that needs a block of its own is a function worth naming. Reading it as
   * `inlineStatement` rather than `expression` is what lets `defer xs.close()` and
   * `defer n = 0` both be written, without a result list's comma reaching across the `defer`.
   */
  protected lazy val deferStmt: PackratParser[Stmt] =
    op("defer") ~> inlineStatement ^^ Defer.apply

  /** `asm` with an architecture arm per line under it (`inline-assembly.md §1`).
   *
   * Every word the construct spends is contextual, `asm` included: each is recognized in one
   * position and is an ordinary identifier everywhere else, so a program may still call a variable
   * `out` or `clobbers` — and use it as an operand in the same function. What commits this rule is
   * the indented arm list, which a bare mention of a variable named `asm` does not have, so the
   * fall-through to an expression statement is exact rather than a matter of ordering.
   */
  protected lazy val asmStmt: PackratParser[Stmt] =
    softWord("asm") ~> newline ~> indent ~> skipNewlines ~>
      repsep(asmArm, newlines) <~ skipNewlines <~ dedent ^^ AsmStmt.apply

  /** `[x86_64, aarch64]` and what answers for them. The architecture names are not checked here —
   * the grammar has no idea which processors exist, and a name outside the set is a diagnostic
   * about a target rather than a parse error about a token.
   */
  protected lazy val asmArm: Parser[AsmArm] =
    at((op("[") ~> rep1sep(ident, op(",")) <~ op("]")) ~ asmArmBody ^^ { case archs ~ body =>
      AsmArm(archs, body)
    })

  /** The four things an arm may be: no answer, one instruction inline, an indented block, or
   * nothing at all. Nothing at all is last because it consumes no input and would otherwise take
   * every arm; it is the architecture on which the operation costs no instruction.
   */
  protected lazy val asmArmBody: Parser[AsmBody] =
    (softWord("unavailable") ~> asmText ^^ AsmUnavailable.apply) |
      (asmText ^^ (line => AsmCode(List(line), Nil, Nil))) |
      (newline ~> indent ~> skipNewlines ~> rep1sep(asmItem, newlines) <~ skipNewlines <~ dedent ^^ gatherAsm) |
      success(AsmCode(Nil, Nil, Nil))

  /** A line inside an arm: an instruction, an operand, or what the arm destroys. They are collected
   * by kind rather than kept in order, because only the instructions have an order that matters.
   */
  protected lazy val asmItem: Parser[AsmItem] =
    (asmText ^^ AsmItem.Line.apply) |
      (asmOperand ^^ AsmItem.Operand.apply) |
      (softWord("clobbers") ~> rep1sep(asmText, op(",")) ^^ AsmItem.Clobber.apply)

  /** `in name : reg` / `out name : "dx"`.
   *
   * The class slot is required even though `reg` is the only class there is, so that every operand
   * line has one shape and a second class arrives as a peer rather than as the exception to an
   * invisible default. Its `:` is not a type annotation — the operand names a variable that already
   * has a type — so what follows is a class or a machine register and never a type.
   */
  protected lazy val asmOperand: Parser[AsmOperand] =
    at((asmDir ~ ident <~ op(":")) ~ asmPlace ^^ { case dir ~ name ~ place =>
      AsmOperand(dir, name, place)
    })

  /** `in` is a reserved word already, for `for x in xs`, and is reused rather than added to. */
  protected lazy val asmDir: Parser[AsmDir] =
    (op("in") ^^^ AsmDir.In) | (softWord("out") ^^^ AsmDir.Out)

  /** A bare word is sysl's and a quoted one is the assembler's, which is the rule everywhere in the
   * construct: `reg` is a class this language names, and `"dx"` is a register only the assembler
   * knows about.
   */
  protected lazy val asmPlace: Parser[Option[String]] =
    (softWord("reg") ^^^ None) | (asmText ^^ Some.apply)

  protected lazy val asmText: Parser[String] =
    accept("string literal", { case t: lexical.StrLit => t.value })

  private def gatherAsm(items: List[AsmItem]): AsmCode =
    AsmCode(
      items.collect { case AsmItem.Line(t) => t },
      items.collect { case AsmItem.Operand(o) => o },
      items.collect { case AsmItem.Clobber(rs) => rs }.flatten,
    )

  /** A `'name` label reference, as used before a loop and after `break`/`continue`. */
  protected lazy val labelRef: Parser[String] =
    accept("label", { case t: lexical.Label => t.name })

  /** The label a loop may carry, which is written immediately before the loop's own keyword and
   * nowhere else.
   *
   * Reading it only where one of those follows is what keeps a stray label from being reported as a
   * loop nobody was writing. Without the lookahead, every labelled form takes the label and then asks
   * for its keyword on the token after it — so `break 'a 'b`, whose real problem is the second label,
   * was told `'for' expected` against the end of the line. The lookahead is `asOneToken` for the same
   * reason: what it crossed to find out is the label, and a refusal recorded past that would be the
   * same complaint one token along.
   */
  protected lazy val loopLabel: Parser[Option[String]] =
    opt(asOneToken(labelRef <~ guard(op("for") | op("while") | op("loop") | op("do"))))

  /** `if cond then a else b` — an expression. Its branches are statement lists whose trailing
   * expression is the branch value; `elif` nests into the else branch, and the `else` is
   * optional (a missing one gives an open branch that only the analyzer's unit rule allows).
   */
  protected lazy val ifExpr: PackratParser[Expr] =
    op("if") ~> expression ~ body("then") ~ rep(elifClause) ~ opt(elseClause) ~ opt(endMarker("if")) ^^ {
      case c ~ t ~ elifs ~ e ~ _ =>
        val elseChain = elifs.foldRight(e) { case ((ec, eb), acc) => Some(List(ExprStmt(IfExpr(ec, eb, acc)))) }
        IfExpr(c, t, elseChain)
    }

  /** `end` is a soft keyword — an ordinary identifier everywhere except immediately before a
   * construct keyword, where `end if` / `end while` / `end for` close the preceding block
   * Scala-style. It is optional; matching it here (rather than reserving `end`) keeps `end`
   * usable as a name.
   */
  protected lazy val softEnd: Parser[Unit] =
    accept("'end'", { case t: lexical.Identifier if t.chars == "end" => () })

  protected def endMarker(construct: String): Parser[Unit] =
    onNextLine(softEnd) ~> op(construct) ^^^ (())

  /** `elif cond then …` is sugar for `else if cond then …` — each one nests into the else
   * branch of the previous, so no distinct AST node is needed.
   */
  protected lazy val elifClause: Parser[(Expr, List[Stmt])] =
    onNextLine(op("elif")) ~> expression ~ body("then") ^^ { case c ~ b => (c, b) }

  /** `else` sits on a fresh line after a block body, or on the same line after an inline
   * one — so any intervening `Newline` is optional.
   */
  protected lazy val elseClause: Parser[List[Stmt]] =
    onNextLine(op("else")) ~> (suite | inlineBody)

  /** `while cond body [else …]` — an expression. The optional `else` reuses the same clause as
   * `if`, sitting after the body and before any `end while`.
   */
  protected lazy val whileExpr: PackratParser[Expr] =
    loopLabel ~ (op("while") ~> expression) ~ body("do") ~ opt(elseClause) ~ opt(endMarker("while")) ^^ {
      case lbl ~ c ~ b ~ e ~ _ => While(lbl, c, b, e)
    }

  /** `do body while cond [else …]` — the post-test loop (`00 §10`).
   *
   * **`do` is unambiguous by position, which is what lets the same word do both jobs.** Everywhere
   * else it is a body *introducer*, and it only ever appears there after a loop header on the same
   * line — `while c do …`, `loop do …`, `for x in xs do …` — so it is never the first token of a
   * statement. Here it is the head, so a `do` that starts a line can only open this form.
   *
   * The tail needs no rule of its own for the same reason: this loop is *incomplete* without its
   * `while`, and there is no bare `do` block in the language for that `while` to have belonged to
   * instead. So a `while` found where the body ends is this loop's test and nothing else can want
   * it. Both bodies are written: `do total += 1 while more()` on one line, or an indented block with
   * the `while` on the line that closes it.
   *
   * There is no `end do`: the tail already names where the body stopped, and a marker after it would
   * be closing a block that has just been closed.
   */
  protected lazy val doWhileExpr: PackratParser[Expr] =
    loopLabel ~ (op("do") ~> (suite | inlineBody)) ~ (skipNewlines ~> op("while") ~> expression) ~
      opt(elseClause) ^^ {
        case lbl ~ b ~ c ~ e => DoWhile(lbl, b, c, e)
      }

  /** `loop body` — a `while` with the condition left out, ended by a `break` rather than by a test.
   *
   * It takes no `else`: an `else` runs when a loop finishes on its own, and this one never does.
   * The inline `loop do …` form is there because `while c do …` has it and a one-line body should
   * not have to change shape when its condition goes away.
   */
  protected lazy val loopExpr: PackratParser[Expr] =
    loopLabel ~ (op("loop") ~> body("do")) ~ opt(endMarker("loop")) ^^ { case lbl ~ b ~ _ => Loop(lbl, b) }

  /** `for all i in 0..<n do a[i] > 0`, `for some k in 0..n do a[k] == t` — a quantifier over an
   * integer range (`17 §2`).
   *
   * **`all` and `some` stay ordinary identifiers**, matched here as soft words, so nothing a program
   * already names is spent on this. Telling the form from a counted loop takes one token: a
   * quantifier is `for` `all`/`some` `name` `in`, and a loop is `for` `name` `in`, so `for all in
   * 0..<n do …` is still a loop over a variable called `all`. This rule is tried before `forExpr` in
   * `expression` and backtracks into it when the name is missing.
   *
   * The separator is `do` — the word every loop header already uses to introduce what it does with
   * each element — rather than Ada's `=>`, which is not in the operator set and would have been
   * added for this one form. The predicate is a full `expression`, so the body extends as far to the
   * right as one can: `for all i in r do P(i) && Q(i)` quantifies over the conjunction, which is the
   * reading a specification wants and the one a reader of the line already expects from `->`.
   */
  protected lazy val quantifier: PackratParser[Expr] =
    (op("for") ~> quantifierKind) ~ ident ~ (op("in") ~> expression) ~ (op("do") ~> expression) ^^ {
      case univ ~ n ~ it ~ p => Quantifier(univ, n, it, p)
    }

  private lazy val quantifierKind: Parser[Boolean] =
    softWord("all") ^^^ true | softWord("some") ^^^ false

  protected lazy val forExpr: PackratParser[Expr] = constForExpr | cForExpr | forInExpr

  /** `for const i in 0..<A.len` — the loop the compiler unrolls (`10 §10`).
   *
   * Tried first, and unambiguous against the other two: neither of them may have `const` after the
   * `for`. There is no label and no `else` clause, because there is no loop at run time for a
   * `break` to leave or for an `else` to follow — the copies are what the analyzer produces.
   */
  protected lazy val constForExpr: PackratParser[Expr] =
    (op("for") ~> op("const") ~> ident) ~ (op("in") ~> expression) ~ body("do") ~ opt(endMarker("for")) ^^ {
      case n ~ it ~ b ~ _ => ConstFor(n, it, b)
    }

  protected lazy val forInExpr: PackratParser[Expr] =
    loopLabel ~ (op("for") ~> ident) ~ (op("in") ~> expression) ~ body("do") ~ opt(elseClause) ~ opt(
      endMarker("for"),
    ) ^^ {
      case lbl ~ n ~ it ~ b ~ e ~ _ => For(lbl, n, it, b, e)
    }

  /** `for init; cond; step` — the three-clause loop (`00` §10).
   *
   * Tried before `for x in …`, and unambiguous against it: this one needs two `;` in its header and
   * that one has none, so whichever the reader wrote is the one that parses. No parentheses, as in
   * Go — every other header in the language is without them, and a parenthesized one here would be
   * the only place they were structure rather than grouping.
   *
   * Each clause may be empty. An absent condition is `true`, which makes `for ; ;` a `loop` spelled
   * the long way; `loop` says it better and this does not forbid it.
   */
  protected lazy val cForExpr: PackratParser[Expr] =
    loopLabel ~ (op("for") ~> opt(forClause) <~ op(";")) ~ (opt(expression) <~ op(";")) ~ opt(forClause) ~
      body("do") ~ opt(elseClause) ~ opt(endMarker("for")) ^^ {
        case lbl ~ init ~ cond ~ step ~ b ~ e ~ _ => CFor(lbl, init, cond, step, b, e)
      }

  /** One clause of a three-clause `for` header: a `var` declaration or a bare expression, which for
   * the step is the assignment or increment that advances the loop.
   */
  protected lazy val forClause: PackratParser[Stmt] = at(varDecl | multiAssign | exprStmt)


  protected lazy val statements: PackratParser[List[Stmt]] =
    skipNewlines ~> repsep(statement, newlines) <~ skipNewlines

  /** A file: an optional module header, the clauses that go with it, then its statements. A file
   * with no header contributes to the anonymous root module, which is what lets a one-file program
   * be written with no ceremony — and it may still narrow itself or name a library, since the root
   * module is a module like any other.
   */
  protected lazy val program: PackratParser[Program] =
    skipNewlines ~> maybe(moduleHeader) >> { m =>
      // An attribute goes on a line of its own, which is what both `13 §4` and `capabilities.md`
      // show and what keeps `module m @no_alloc @requires(os)` from being a line anyone has to read.
      // The exception is a file that declares no module: the root module is a module like any other,
      // and there is no header for its attributes to sit below, so there they may open the file.
      val lead = if m.isDefined then success(List.empty[HeaderClause])
                 else maybe(headerAttr) ^^ (_.toList.flatten)

      lead ~ repeatedly(asOneToken(newlines ~> headerAttr)) ~ statements ^^ {
        case first ~ rest ~ body =>
          val clauses = first ::: rest.flatten

          Program(body, m,
                  clauses.collect { case c: CapabilityClause => c },
                  clauses.collect { case l: LinkClause => l },
                  source,
                  clauses.exists(_.isInstanceOf[TestsClause]),
                  clauses.collect { case i: IncludeClause => i })
      }
    }

  // --- entry points --------------------------------------------------------------------

  /** Parses this parser's source as a single expression (used by the expression test tier). */
  def parseExpression: ParseResult[Expr] =
    phrase(expression <~ rep(newline))(reader(source.text))

  /** Parses this parser's source as a whole program. */
  def parseProgram: ParseResult[Program] =
    phrase(program)(reader(source.text))

  /** The first token the lexer could make nothing of, and where it sits.
   *
   * A lexical error is reported ahead of whatever the grammar made of the tokens around it, because
   * the parser's expectation is a *reaction* to the damage rather than a description of it: an
   * unterminated string literal is a token the grammar has no rule for, so the failure surfaces
   * wherever the longest partial match happened to stop — for `print("oops`, a complaint about the
   * argument list, having taken `print` alone as much as it could read. The lexer already knew the
   * answer and said so; this is what carries it out.
   */
  def firstLexicalError: Option[(String, Position)] =
    lexical.scanPositioned(source.text).collectFirst { case (lexical.ErrorToken(msg), at) => (msg, at) }
}

object SyslParser {

  /** Parses a program, returning either the AST or a rendered diagnostic. */
  def parse(src: String, name: String = "<input>"): Either[String, Program] =
    parse(Source(name, src))

  /** Parses a file for a target.
   *
   * **The target reaches the parser because conditional compilation does** (`Conditional`): the
   * lines of a branch this build is not for are blanked before the lexer is handed anything, so what
   * is parsed is already the file this target sees. Every other seam is unaffected — the gate is not
   * applied to an expression fragment or to a string interpolation's contents, neither of which is a
   * file, and a source with no directives in it is not even copied.
   *
   * **Tangling comes first, and the order is the whole of what makes the two features independent.**
   * A literate file's directives are written in its program, which is indented (`Literate`) — so
   * gating first would be gating a file whose `#if` lines are four columns in, and `Conditional`
   * would have to learn about a format that is none of its business. Tangled first, what the gate
   * receives is ordinary sysl and it behaves exactly as it does for a file that was never literate.
   */
  def parse(source: Source, target: Target): Either[String, Program] =
    Literate.tangle(source).flatMap(Conditional.gate(_, target)).flatMap(parsed)

  /** The same, for the machine a caller that names none would get. Spelled as its own overload
   * rather than as a default argument because only one of these alternatives may carry defaults, and
   * that one is the `String` form the parser tests are written against.
   */
  def parse(source: Source): Either[String, Program] = parse(source, Target.default)

  private def parsed(source: Source): Either[String, Program] = {
    val p = new SyslParser(source)

    p.parseProgram match {
      case p.Success(prog, _) => Right(prog)
      case ns: p.NoSuccess =>
        p.firstLexicalError match {
          case Some((msg, at)) => Left(Pos(source, at.line, at.column).render(msg))
          case None            => Left(failedAt(source, ns.next.pos).render(ns.msg))
        }
    }
  }

  /** Where a parse failed. Running out of tokens leaves no position at all, and pointing at the
   * end of the last line is more use than pointing at nothing — an unclosed block is exactly the
   * case that reports there.
   */
  protected def failedAt(source: Source, at: scala.util.parsing.input.Position): Pos =
    if at.line > 0 then Pos(source, at.line, at.column)
    else {
      val last = math.max(1, source.lines.length)

      Pos(source, last, source.line(last).length + 1)
    }
}
