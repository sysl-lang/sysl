package sh.sysl

import scala.util.parsing.input.Position

/** The sysl parser: a packrat combinator grammar over the materialized token list from
 * `SyslLexical` (see design/front-end.md).
 *
 * The precedence structure mirrors `01-scalar-types-and-operators.md`: assignment (loosest)
 * → `||` → `&&` → chained comparison → range → `|` → `^` → `&` → `+ -` →
 * `* / % << >>` → prefix unary → postfix (tightest). Left-associative binary levels are
 * built with `chainl1`, which iterates rather than left-recurses; the whole grammar still
 * runs under packrat so the choice stays linear.
 *
 * The `List[Token]` is the reversibility seam: a hand-written parser could later consume the
 * same tokens with no change to the lexer.
 *
 * Every rule that builds a node wraps itself in `at`, which stamps the node with the position of
 * the first token the rule consumed. A parser is bound to one `Source` so that stamp is complete
 * — file, line, and column — the moment the node exists.
 */
/** What a `[T, U: Show, V = int]` list says, kept as one value because every generic declaration
  * parses the same list and hands all three parts to the node it builds. A parameter carrying
  * neither a bound nor a default is simply absent from both maps.
  *
  * **A parameter may stand for a value rather than a type** (`10 §9`), written `[const N: usize]`.
  * Those share `names` with the type parameters, because they share one list, one namespace and one
  * argument position — what marks one out is an entry in `values` giving the type its argument must
  * have. A value parameter carries no bound (a bound is a trait, and a value does not implement one)
  * and its default, where it has one, is an expression rather than a type.
  */
case class TypeParams(
    names: List[String],
    bounds: Map[String, List[BoundRef]] = Map.empty,
    defaults: Map[String, TypeRef] = Map.empty,
    values: Map[String, TypeRef] = Map.empty,
    valueDefaults: Map[String, Expr] = Map.empty,
    packs: Set[String] = Set.empty,
)

object TypeParams {
  val none: TypeParams = TypeParams(Nil)
}

/** One entry of a `[…]` parameter list, before the list is folded into `TypeParams`. The two shapes
  * are kept apart here rather than in maps because the grammar reading them is what tells them
  * apart, and a fold that had to guess would be the ambiguity `const` exists to remove.
  */
sealed trait ParamSpec { def name: String }

case class TypeParamSpec(name: String, bounds: List[BoundRef], default: Option[TypeRef])
    extends ParamSpec

case class ValueParamSpec(name: String, typ: TypeRef, default: Option[Expr]) extends ParamSpec

/** `..A: Display` — a parameter standing for a **list** of types (`10 §10`). Its bound distributes
  * over the members, which is why the bounds go in the same map a type parameter's do: everything
  * downstream that asks what a name is bounded by gets the same answer, and only the walk that
  * matches a subject needs to know this one is a pack.
  */
case class PackParamSpec(name: String, bounds: List[BoundRef]) extends ParamSpec

/** The sysl parser: a packrat combinator grammar over the materialized token list from
 * `SyslLexical` (see design/front-end.md).
 *
 * The grammar is split across `SyslParserBase` (the token reader, position stamping, terminals, and
 * the crossings between areas), `ExprParser` (the precedence ladder and the literals), `DeclParser`
 * (functions, structs, enums, traits, impls), and what is left here: statements, the type grammar,
 * the bindings, control flow, and patterns.
 *
 * The `List[Token]` is the reversibility seam: a hand-written parser could later consume the same
 * tokens with no change to the lexer.
 *
 * Every rule that builds a node wraps itself in `at`, which stamps the node with the position of the
 * first token the rule consumed. A parser is bound to one `Source` so that stamp is complete — file,
 * line, and column — the moment the node exists.
 */
class SyslParser(val source: Source) extends DeclParser {

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

  /** A declaration that may carry a visibility modifier (`13 §2`).
   *
   * The five forms are grouped so the modifier is written once, before whichever of them follows,
   * rather than threaded through five rules that would each have to remember it. An `impl` is not
   * among them and takes none: it declares no name, so there is nothing for a modifier to restrict.
   */
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

  protected lazy val declaration: PackratParser[Stmt] =
    attributedDecl |
      implVisibility |
      misplacedOverride |
      staticDecl |
      visibility ~ (structDecl | enumDecl | typeDecl | traitDecl | externDecl | constDecl | valDecl | funcDecl) ^^ {
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
    rep1(attribute <~ opt(newlines)) >> { as =>
      // Once an attribute has been read the statement is committed to being an attributed
      // declaration, which is what `>>` buys: everything after it is read against that, so a
      // declaration that cannot carry one is answered with the sentence below rather than with the
      // grammar's complaint about whichever alternative it went on to try.
      duplicated(as) match
        case Some(dup) =>
          err(s"'@$dup' is written twice above one declaration, and it says nothing the once does not")
        case None =>
          (visibility ~ funcDecl) ^^ {
            case Visibility.Public ~ (f: FuncDecl) => attributed(f, as)
            case v ~ (f: FuncDecl)                 => restrict(v, attributed(f, as))
            case _ ~ other                         => other
          } | err(
            "an annotation marks a function, and only a function — neither what 'sysl test' calls " +
              "nor what recurses is anything a declaration of another kind supplies",
          )
    }

  /** One annotation: what it says about the function under it. Each alternative reads the `@` for
   * itself so that the annotation's own position is the `@`, which is the line a test report names.
   */
  private lazy val attribute: PackratParser[Attr] =
    testAttr ^^ Attr.Test.apply | tailrecAttr | pureAttr | ghostAttr | unknownAttr | hashAttr

  /** `@test`, and the three things it may say about the test: the name a report gives it, that it is
   * a run which should not come back, and the text such a run should have printed on its way out.
   */
  protected lazy val testAttr: PackratParser[TestAttr] =
    at(op("@") ~> attrWord("test") ~> opt(op("(") ~> testArgs <~ op(")"))
      ^^ (_.getOrElse(TestAttr(None, false, None))))

  /** `@tailrec` — the assertion that this function's call to itself is the last thing it does
   * (`12 § Tail calls`). It takes no arguments: there is nothing to configure about a jump, and
   * what the annotation buys is the refusal when there is no jump to make.
   */
  protected lazy val tailrecAttr: PackratParser[Attr] =
    op("@") ~> attrWord("tailrec") ^^ (_ => Attr.TailRec)

  /** `@pure` — the assertion that a caller can observe nothing about this call but its result
   * (`17 §6`). Like `@tailrec` it takes no arguments: purity is not a thing to configure, and what
   * the annotation buys is the refusal when the body does something a caller could observe.
   */
  protected lazy val pureAttr: PackratParser[Attr] =
    op("@") ~> attrWord("pure") ^^ (_ => Attr.Pure)

  /** `@ghost` — the function exists for the specification alone and is erased before codegen
   * (`17 §8`).
   */
  protected lazy val ghostAttr: PackratParser[Attr] =
    op("@") ~> attrWord("ghost") ^^ (_ => Attr.Ghost)

  private lazy val unknownAttr: PackratParser[Attr] =
    op("@") ~> ident >> (n =>
      err(s"'$n' is not an annotation a declaration takes — '@test', '@tailrec', '@pure' and " +
        "'@ghost' are the four. '@no_<capability>', '@requires(...)' and '@link(\"...\")' belong in " +
        "the file's header"))

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
  private def attrWord(w: String): Parser[Unit] =
    accept(s"'$w'", { case t: lexical.Identifier if t.chars == w => () })

  /** An annotation whose name *carries* its argument — `@no_alloc` is `no_` and then the capability.
   * What comes back is the part after the prefix, so the caller never sees the joint.
   *
   * The prefix alone is not one of these: `@no_` names no capability, and matching it would hand the
   * analyzer an empty name to complain about instead of the parser refusing a word that is visibly
   * unfinished.
   */
  private def attrWordPrefixed(prefix: String): Parser[String] =
    accept(
      s"'$prefix…'",
      { case t: lexical.Identifier if t.chars.startsWith(prefix) && t.chars.length > prefix.length =>
        t.chars.drop(prefix.length)
      },
    )

  /** The attribute written twice, where one is, for the refusal above. */
  private def duplicated(as: List[Attr]): Option[String] =
    as.map(_.word).groupBy(identity).collectFirst { case (w, ws) if ws.length > 1 => w }

  private def attributed(f: FuncDecl, as: List[Attr]): FuncDecl =
    as.foldLeft(f) {
      case (d, Attr.Test(t)) => d.copy(test = Some(t))
      case (d, Attr.TailRec) => d.copy(tailrec = true)
      case (d, Attr.Pure)    => d.copy(pure = true)
      case (d, Attr.Ghost)   => d.copy(ghost = true)
    }

  private lazy val testArgs: Parser[TestAttr] =
    contractMsg ~ opt(op(",") ~> testExpectation) ^^ {
      case d ~ e => TestAttr(Some(d), e.isDefined, e.flatten)
    } | testExpectation ^^ (e => TestAttr(None, true, e))

  /** `should_trap`, alone or with the substring a trapping run must have printed. */
  private lazy val testExpectation: Parser[Option[String]] =
    accept("'should_trap'", { case t: lexical.Identifier if t.chars == "should_trap" => () }) ~>
      opt(op(":") ~> contractMsg)

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
    case l: ValDecl       => l.copy(vis = v).setPos(l.pos)
    case r: VarDecl       => r.copy(vis = v).setPos(r.pos)
    case f: FuncDecl      => f.copy(vis = v).setPos(f.pos)
    case t: TypeDecl      => t.copy(vis = v).setPos(t.pos)
    case other            => other

  /** A dotted name — a module path. */
  protected lazy val dottedName: Parser[List[String]] = rep1sep(ident, op("."))

  /** A name that may be reached through the module it belongs to: `File`, `std.fs.File`.
   *
   * A public member is always reachable fully-qualified, with no import (`13 §3`), so every
   * position that names a declaration takes this rather than a bare identifier. The dots are kept
   * in the name as written — which module the prefix is and which part of it is the declaration's
   * own name is a question only the analyzer, holding the program's module names, can answer.
   */
  protected lazy val qualifiedName: Parser[String] = dottedName ^^ (_.mkString("."))

  /** `module a.b.c`, the header naming the module this file contributes to. It is not a statement:
   * it may appear once, and only before everything else, so it is a prefix of the program rather
   * than an alternative within it.
   */
  protected lazy val moduleHeader: Parser[ModuleName] =
    at(op("module") ~> dottedName ^^ ModuleName.apply)

  /** A file-header attribute: `@no_alloc`, `@requires(os, posix)`, `@link("z")` (`13 §4`,
   * `15 §8`, `capabilities.md`).
   *
   * **These are attributes rather than grammar, and that is the point of the spelling.** A capability
   * and a library name are things said *about* a module, not constructs the language executes, so
   * they take the notation sysl already uses for that — the one `@test` and `@tailrec` are written
   * in. What it buys is the whole reason to prefer it: **no word is spent.** `alloc` was a reserved
   * word, which took the most natural name in an allocator away from the code that provides one, and
   * `guide/slab` had to call its function `take`. Every name here arrives through `attrWord`, which
   * matches an ordinary identifier, so `alloc`, `no`, `requires` and `link` are all available to a
   * program again.
   *
   * One attribute may yield several clauses, because `@requires` takes a list.
   */
  private lazy val headerAttr: Parser[List[CapabilityClause | LinkClause]] =
    op("@") ~> (noAttr | requiresAttr | linkAttr)

  /** `@no_alloc` and its siblings — the module narrowing itself below what the target offers.
   *
   * The capability is the part after `no_`, and it is **not checked here**: the set is a property of
   * the project rather than of the grammar (`Capability`), so `@no_sockets` parses and the analyzer
   * is what says there is no such capability. That keeps one message about an unknown capability
   * instead of two that differ by where it was written.
   *
   * One word per capability rather than `@no(alloc)` follows Rust's `#![no_std]`, which is the
   * nearest precedent and reads the way the thing is spoken.
   */
  private lazy val noAttr: Parser[List[CapabilityClause | LinkClause]] =
    at(attrWordPrefixed("no_") ^^ (CapabilityClause(CapabilityDirection.Narrows, _))) ^^ (List(_))

  /** `@requires(os)`, `@requires(threads, posix)` — what the module cannot be built without.
   *
   * Parenthesised and plural where the narrowing form is neither, because that is how each is used:
   * a module gives up one capability at a time and needs several at once. `sysl.thread` requires
   * both `threads` and `posix`, and writing that as two attributes would be two lines saying one
   * thing.
   */
  private lazy val requiresAttr: Parser[List[CapabilityClause | LinkClause]] =
    attrWord("requires") ~> op("(") ~>
      rep1sep(at(ident ^^ (CapabilityClause(CapabilityDirection.Requires, _))), op(",")) <~ op(")")

  /** `@link("z")` — a library the linker must be given for this file's `extern`s to resolve.
   *
   * The library is named by a **string** rather than an identifier because it is a name from outside
   * sysl, exactly as an `extern`'s symbol is, and because plenty of real ones are not identifiers at
   * all. `stdc++` is the everyday example.
   */
  private lazy val linkAttr: Parser[List[CapabilityClause | LinkClause]] =
    at(attrWord("link") ~> op("(") ~> linkName <~ op(")") ^^ LinkClause.apply) ^^ (List(_))

  /** A header attribute written where a statement goes, which is refused for the reason
   * `noVisibility` is: it has a place, and a reader who writes it in the wrong one should be told
   * which place that is rather than answered with "newline expected".
   *
   * It is a *header*, not a statement, because it is a property of the module and the module is
   * settled before anything in the file runs — and because one part-way down a file would read as
   * though the statements above it were outside its reach.
   */
  protected lazy val misplacedHeaderAttr: Parser[Nothing] =
    guard(op("@") ~ (attrWordPrefixed("no_") | attrWord("requires") | attrWord("link"))) ~> err(
      "this attribute belongs in the file's header, on the lines directly after 'module' and before " +
        "everything else — it is a property of the whole module, not of the statements below it")

  /** `import a.b.c`, `import a.b.{c, d as e}`, `import a.b.*` — the Scala forms (`13 §3`).
   *
   * The path is the greedy dotted name, so the tail forms are read off what is left after it: a
   * `.` followed by something that is not an identifier is not part of the path, which is what
   * lets one rule cover all three without lookahead. Which part of the path is the module is not
   * decided here — it is a question about the program, not about the text.
   *
   * It is a **statement** rather than a header, because an import may also appear inside a block,
   * scoped to it, for a name wanted in one function only.
   */
  protected lazy val importDecl: Parser[ImportDecl] =
    op("import") ~> dottedName ~ opt(importTail) ~ opt(op("as") ~> ident) ^^ {
      case path ~ None ~ alias          => ImportDecl(path, alias = alias)
      case path ~ Some(Left(_)) ~ alias => ImportDecl(path, wildcard = true, alias = alias)
      case path ~ Some(Right(sels)) ~ alias => ImportDecl(path, sels, alias = alias)
    } ^? (
      { case d if d.alias.isEmpty || (!d.wildcard && d.selectors.isEmpty) => d },
      _ =>
        "a rename here would have nothing to name — a wildcard brings in every member, and a " +
          "selector list carries its own 'as' per name, as 'a.b.{c as d}'",
    )

  /** The wildcard's `.*` is **one token**, which is why it is matched here rather than as a `.`
   * followed by the multiplication operator. Nothing about the written form changes; what it buys
   * is that a line never ends in a bare `*` that was really the end of a statement, so `*` can
   * carry a line like every other binary operator (`SyslLexical`).
   */
  protected lazy val importTail: Parser[Either[Unit, List[ImportSelector]]] =
    op(".*") ^^^ Left(()) |
      (op(".") ~> op("{") ~> commaList1(importSelector) <~ op("}")) ^^ (Right(_))

  protected lazy val importSelector: Parser[ImportSelector] =
    at(ident ~ opt(op("as") ~> ident) ^^ { case n ~ a => ImportSelector(n, a) })

  /** A type: a memory-mode sigil applied to a type, or a name optionally applied to type
   * arguments (`Box[int]`, `Result[T, string]`). `sync` stays a soft keyword — it is only
   * special immediately after `&`, and the `&sync T` alternative is tried first so that a
   * reference to a type actually named `sync` still parses.
   *
   * `weak` is a reserved word rather than a sigil, since a mode a program reaches for only for a
   * genuine back-reference (`03`) is better read than punctuated.
   */
  protected lazy val typeRef: Parser[TypeRef] =
    at(coreType ~ opt(op("->") ~> typeRef) ^^ {
      case t ~ None                        => t
      case TupleType(parts, false) ~ Some(r) => FnType(parts, r, bare = true)
      case t ~ Some(r)                     => FnType(List(t), r, bare = true)
    })

  /** A type with no arrow on it — everything a bare-arrow callable is written *out of*.
   *
   * The arrow is a suffix on this rather than an alternative among these, so `(A, B) -> C` reads its
   * left side as the parenthesized list it looks like and only then learns it was a parameter list.
   * That is what keeps one production for `(A, B)` whether a tuple or a callable was meant, and it
   * is why the two cannot disagree about how a comma inside parentheses is read.
   */
  protected lazy val coreType: Parser[TypeRef] =
    at(
      // `Fn(A) -> R`, the callable's type written out (`12 §6`). It comes first because `Fn` is an
      // ordinary identifier: without this the name alternative below would take it and leave the
      // parameter list stranded.
      (fnWord ~> op("(") ~> commaList(typeRef) <~ op(")")) ~ (op("->") ~> typeRef) ^^ {
        case ps ~ r => FnType(ps, r, bare = false)
      } |
        // `() -> R` — a callable of no arguments. Empty parentheses are not a type, so this is the
        // one place they may be written, and the arrow is what says so.
        (op("(") ~> op(")") ~> op("->") ~> typeRef) ^^ (r => FnType(Nil, r, bare = true)) |
        // `*extern(A) -> R`, C's function pointer (`12 §6a`). It comes before the general `*` so the
        // `extern` is read as part of this spelling rather than as a type named `extern` — which it
        // could not be anyway, the word being reserved, but the alternative below would reach the
        // name production and complain about the wrong thing.
        ((op("*") ~> op("extern") ~> op("(") ~> commaList(typeRef) <~ op(")")) ~ (op("->") ~> typeRef) ^^ {
          case ps ~ r => CFnType(ps, r)
        }) |
        op("*") ~> op("extern") ~> err("'*extern' is a foreign function's address, so it is written " +
          "with the signature that address is called at — '*extern(int) -> int', and '*extern() -> unit' " +
          "for one that takes nothing and yields nothing") |
        op("*") ~> coreType ^^ PtrType.apply |
        op("&") ~> softSync ~> coreType ^^ (t => RefType(t, sync = true)) |
        op("&") ~> coreType ^^ (t => RefType(t, sync = false)) |
        op("weak") ~> softSync ~> err("an atomic reference has no weak form yet — 'weak sync T' " +
          "wants the concurrency model of '06', which is not built") |
        op("weak") ~> coreType ^^ WeakType.apply |
        ((op("[") ~> opt(expression) <~ op("]")) ~ opt(op("const")) ~ coreType >> {
          // `const` after the brackets says the *view* refuses writes, so a length in them is a
          // contradiction: an array is storage rather than a view of it, and storage that is written
          // once is what `val` declares. Somebody reaching for one is owed that word rather than a
          // parse error, since the two spellings are a bracketed number apart.
          case Some(_) ~ Some(_) ~ t =>
            err(s"'const' says a view refuses writes, and an array is storage rather than a view of " +
              s"one — read-only storage is declared with 'val', as 'val name: [N]${t.show}'")
          case n ~ ro ~ t => success(ArrayType(n, t, readOnly = ro.isDefined))
        }) |
        // `volatile T` (`03 § Device memory`). It stays a soft word like `sync`, so it is special
        // only in front of another type — a program with a type of its own named `volatile` still
        // parses, since this alternative needs a second type after the word and the name
        // alternative below picks up what is left.
        softVolatile ~> coreType ^^ VolatileType.apply |
        tupleType |
        // A bare `..A` parses so that the analyzer can say what a pack is and where one may be
        // written (`10 §10`). Left to the grammar it would be a stray token, and the reader would
        // be told a newline was expected rather than told about the feature they were reaching for.
        op("..") ~> ident ^^ PackType.apply |
        qualifiedName ~ opt(typeArgs) ^^ { case n ~ args => NamedType(n, args.getOrElse(Nil)) },
    )

  /** `(A, B)` — a tuple type. A single part is refused rather than read as a grouping, because the
   * two spellings would then differ by a comma and mean different things; `(T)` is the shape
   * somebody writes when they mean a one-tuple, and there is no such type (`00 §13`).
   */
  protected lazy val tupleType: Parser[TypeRef] =
    packTuple | (op("(") ~> commaList1(typeRef) <~ op(")")) >> {
      case List(one) =>
        err(s"'(${one.show})' is a type in parentheses, and a tuple has two or more parts — " +
          s"a product of one thing is that thing, so write '${one.show}'")
      case parts => success(TupleType(parts))
    }

  /** `(..A)` — the tuple of a type pack (`10 §10`), which matches a tuple of any arity.
   *
   * Tried before the ordinary tuple, and the two cannot both parse: a pack is the whole of what is
   * between the parentheses. Mixing one with written-out parts — `(..A, int)` — is pack *expansion*
   * and is not built, so it is refused by name rather than left to fail as a type called `..A`.
   */
  protected lazy val packTuple: Parser[TypeRef] =
    (op("(") ~> op("..") ~> ident <~ (op(")") | op(",") ~> err(
      "a type pack is the whole of the tuple it stands for — '(..A, T)' appends to a pack, which " +
        "is not built; write '(..A)' and reach the parts with 'for const'",
    ))) ^^ { n => TupleType(List(PackType(n))) }

  /** A function's declared result: one type, or several separated by commas (`12 §5b`).
   *
   * A result list is a property of the signature and not a type, so it is spelled here rather than
   * in `typeRef` — nothing that asks for a *type* can reach one, which is what keeps `-> int, int`
   * and a field or a parameter apart with no rule of its own.
   */
  protected lazy val resultRef: Parser[TypeRef] =
    typeRef ~ rep(op(",") ~> typeRef) ^^ {
      case t ~ Nil  => t
      case t ~ more => TupleType(t :: more, results = true)
    }

  /** The `[int, string]` argument list of an applied generic name, whether the name is a type's or
   * a trait's — a trait takes its arguments the same way and in the same place.
   */
  protected lazy val typeArgs: Parser[List[TypeRef]] =
    op("[") ~> commaList1(typeArg) <~ op("]")

  /** One argument of that list, which may stand for a **value** (`10 §9`) — `Buf[4]`.
   *
   * A type is tried first and an expression only where nothing could be a type, so a bare `N` is
   * read as a name and left for the declaration to interpret: the grammar cannot tell a type
   * parameter's name from a value parameter's, and the declaration can.
   */
  protected lazy val typeArg: Parser[TypeRef] =
    typeRef | (expression ^^ ValueArgType.apply)

  protected lazy val softSync: Parser[Unit] =
    accept("'sync'", { case t: lexical.Identifier if t.chars == "sync" => () })

  protected lazy val softVolatile: Parser[Unit] =
    accept("'volatile'", { case t: lexical.Identifier if t.chars == "volatile" => () })

  /** `Fn` stays a soft word for the reason `sync` does: it is only special immediately before a
   * parenthesized parameter list, so a program with a type of its own named `Fn` still parses.
   */
  protected lazy val fnWord: Parser[Unit] =
    accept("'Fn'", { case t: lexical.Identifier if t.chars == "Fn" => () })

  /** A type-parameter list where a parameter may carry a trait bound: `[T, U: Show, V: Ord + Hash]`.
   * It yields the parameter names alongside a name-keyed map of the bounds, so an unbounded
   * parameter is simply absent from the map.
   *
   * Every declaration that may be generic over types it does not know parses this one list — a
   * function, an `impl` block, a struct, an enum, a trait — because a bound means the same thing in
   * each: it is what the declaration assumes of the parameter, and what everything applying it must
   * supply.
   *
   * A bound is a trait **applied**, so it takes type arguments where the trait declares any:
   * `[E: From[IoError]]`. The arguments are types and parse as such, which is what lets one mention
   * another of the parameters being declared.
   *
   * A parameter may also carry a **default**, `[Rhs = Self]`, which stands in where the declaration
   * is applied to fewer arguments than it declares. The bound comes first and the default last, so
   * `[R: Show = Self]` reads as the two clauses it is; both are optional and independent. Whether a
   * default means anything in the position being parsed is the analyzer's question, since only it
   * can say so with the declaration under the message.
   */
  protected lazy val boundedTypeParams: Parser[TypeParams] =
    op("[") ~> commaList1(valueParam | packParam | boundedTypeParam) <~ op("]") ^^ { ps =>
      TypeParams(
        ps.map(_.name),
        ps.collect {
          case p: TypeParamSpec if p.bounds.nonEmpty => p.name -> p.bounds
          case p: PackParamSpec if p.bounds.nonEmpty => p.name -> p.bounds
        }.toMap,
        ps.collect { case p: TypeParamSpec if p.default.nonEmpty => p.name -> p.default.get }.toMap,
        ps.collect { case p: ValueParamSpec => p.name -> p.typ }.toMap,
        ps.collect { case p: ValueParamSpec if p.default.nonEmpty => p.name -> p.default.get }.toMap,
        ps.collect { case p: PackParamSpec => p.name }.toSet,
      )
    }

  /** `[const N: usize]` — a parameter standing for a **value** (`10 §9`).
   *
   * The type is required and the marker is what makes it readable: without `const` this is
   * `ident ':' name`, which is exactly a bounded type parameter, and only name resolution could say
   * which was meant. Then a trait name misspelled into a type name would quietly change what kind of
   * parameter it is. The word is not a new one — `13 §7` already spells a compile-time constant
   * `const NAME: Type = expr`, and this is that with the initializer left to the caller.
   *
   * A **default is an expression here**, not a type, which is the second thing the marker buys: one
   * slot, two grammars, and no way to parse it without knowing which parameter is being read.
   *
   * **The missing type is refused where the `:` belongs, not by an alternative written after the
   * whole form**, and the difference is not stylistic. An alternative fails at the position it gets
   * to, and a combinator picking between two failures keeps the one that got *further*: the form
   * above reaches the `]` before it notices, so its failure outranks any error raised back at the
   * `const`, and the reader gets the generic complaint about whatever came next. Raised here the two
   * are at one position, and the one carrying a sentence wins.
   */
  protected lazy val valueParam: Parser[ValueParamSpec] =
    op("const") ~> ident ~ (op(":") ~> typeRef | err("a value parameter needs the type its " +
      "argument must have, as 'const N: usize' — the type is what says which values may stand " +
      "there")) ~ opt(op("=") ~> expression) ^^ {
      case n ~ t ~ d => ValueParamSpec(n, t, d)
    }

  /** `..A: Display` — a **type pack** (`10 §10`), whose bound distributes over its members.
   *
   * The `..` is the marker, and it is in front for the same reason `const` is: without it `A: Display`
   * is an ordinary bounded type parameter and nothing in the grammar could say which was meant. It
   * reads the way the use does — `(..A)` — so a signature says pack in both places with one spelling.
   *
   * A pack takes **no default**: a default is one type, and there is no way to write a list of them.
   * Refused here rather than by an alternative written after the form, so the sentence is raised at
   * the `=` and outranks the generic complaint about what follows it.
   */
  protected lazy val packParam: Parser[PackParamSpec] =
    op("..") ~> ident ~ opt(op(":") ~> rep1sep(boundRef, op("+"))) ~ opt(op("=") ~> err(
      "a type pack takes no default — a default is one type, and a pack stands for a list of them",
    )) ^^ { case n ~ bs ~ _ => PackParamSpec(n, bs.getOrElse(Nil)) }

  protected lazy val boundedTypeParam: Parser[TypeParamSpec] =
    ident ~ opt(op(":") ~> rep1sep(boundRef, op("+"))) ~ opt(op("=") ~> typeRef) ^^ {
      case n ~ bs ~ d => TypeParamSpec(n, bs.getOrElse(Nil), d)
    }

  protected lazy val boundRef: Parser[BoundRef] =
    at(qualifiedName ~ opt(typeArgs) ^^ { case n ~ args => BoundRef(n, args.getOrElse(Nil)) })

  protected lazy val varDecl: PackratParser[Stmt] =
    multiDecl("var", mutable = true) |
      patternDecl("var", mutable = true) |
      op("var") ~> ident ~ opt(op(":") ~> typeRef) ~ opt(op("=") ~> expression) ^^ {
        case n ~ t ~ e => VarDecl(n, t, e.map(Placeholders.lift))
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
    (op(keyword) ~> destructuring) ~ (op("=") ~> expression) ^^ {
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
   */
  protected lazy val constDecl: PackratParser[Stmt] =
    op("const") ~> ident ~ (op(":") ~> typeRef) ~ (op("=") ~> expression) ^^ {
      case n ~ t ~ v => ConstDecl(n, t, v)
    }

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
      op("val") ~> ident ~ opt(op(":") ~> typeRef) ~ (op("=") ~> expression) ^^ {
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
    softWord("asm") ~> newline ~> indent ~> opt(newlines) ~>
      repsep(asmArm, newlines) <~ opt(newlines) <~ dedent ^^ AsmStmt.apply

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
      (newline ~> indent ~> opt(newlines) ~> repsep(asmItem, newlines) <~ opt(newlines) <~ dedent ^^ gatherAsm) |
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
    opt(newlines) ~> softEnd ~> op(construct) ^^^ (())

  /** `elif cond then …` is sugar for `else if cond then …` — each one nests into the else
   * branch of the previous, so no distinct AST node is needed.
   */
  protected lazy val elifClause: Parser[(Expr, List[Stmt])] =
    opt(newlines) ~> op("elif") ~> expression ~ body("then") ^^ { case c ~ b => (c, b) }

  /** `else` sits on a fresh line after a block body, or on the same line after an inline
   * one — so any intervening `Newline` is optional.
   */
  protected lazy val elseClause: Parser[List[Stmt]] =
    opt(newlines) ~> op("else") ~> (suite | inlineBody)

  /** `while cond body [else …]` — an expression. The optional `else` reuses the same clause as
   * `if`, sitting after the body and before any `end while`.
   */
  protected lazy val whileExpr: PackratParser[Expr] =
    opt(labelRef) ~ (op("while") ~> expression) ~ body("do") ~ opt(elseClause) ~ opt(endMarker("while")) ^^ {
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
    opt(labelRef) ~ (op("do") ~> (suite | inlineBody)) ~ (opt(newlines) ~> op("while") ~> expression) ~
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
    opt(labelRef) ~ (op("loop") ~> body("do")) ~ opt(endMarker("loop")) ^^ { case lbl ~ b ~ _ => Loop(lbl, b) }

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
    opt(labelRef) ~ (op("for") ~> ident) ~ (op("in") ~> expression) ~ body("do") ~ opt(elseClause) ~ opt(
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
    opt(labelRef) ~ (op("for") ~> opt(forClause) <~ op(";")) ~ (opt(expression) <~ op(";")) ~ opt(forClause) ~
      body("do") ~ opt(elseClause) ~ opt(endMarker("for")) ^^ {
        case lbl ~ init ~ cond ~ step ~ b ~ e ~ _ => CFor(lbl, init, cond, step, b, e)
      }

  /** One clause of a three-clause `for` header: a `var` declaration or a bare expression, which for
   * the step is the assignment or increment that advances the loop.
   */
  protected lazy val forClause: PackratParser[Stmt] = at(varDecl | multiAssign | exprStmt)

  // --- match ---------------------------------------------------------------------------

  /** `scrutinee match` followed by an indented list of `pattern[, pattern…] [if guard] -> body`
   * arms — an expression yielding the taken arm's value.
   *
   * The keyword goes **after** the value, as in Scala, and for Scala's reason: a match is a
   * *transformation of the thing to its left*, so writing it there is what lets one feed another.
   * `a match … match …` reads in the order the values flow, where the prefix form would have made
   * the second one wrap the first and put the arms of each at a different distance from the value
   * they choose between.
   *
   * It is therefore repeated rather than optional, and left-associative — each arms block matches
   * whatever the chain has produced so far. An operand with no `match` after it is returned
   * unchanged, which is how this doubles as the fall-through from `expression` to `assignment`.
   */
  protected lazy val matchExpr: PackratParser[Expr] =
    assignment ~ rep(opt(newlines) ~> op("match") ~>
      (newline ~> indent ~> opt(newlines) ~> repsep(matchArm, newlines) <~ opt(newlines) <~ dedent)) ^^ {
      case scrut ~ chain => chain.foldLeft(scrut)((e, arms) => MatchExpr(e, arms).setPos(e.pos))
    }

  /** One arm. The `->` separates a *pattern* from what to do when it matches, and `else` is not a
   * pattern — it is the fallback, and it takes its body directly, exactly as an `if`'s `else` does.
   *
   * The arrow after `else` is called out by name rather than left to fail as an expression, since
   * what it fails as is a body that does not start with a `->` and the position that reports is
   * the `match` several lines above.
   */
  protected lazy val matchArm: Parser[MatchArm] =
    at(
      op("else") ~> op("->") ~> err("the 'else' arm takes its body directly, with no '->' — 'else' names no pattern to separate one from") |
        op("else") ~> (suite | inlineBody) ^^ (b => MatchArm(List(WildcardPattern), None, b)) |
        rep1sep(pattern, op("|")) ~ opt(op("if") ~> expression) ~ (op("->") ~> (suite | inlineBody)) ^^ {
          case pats ~ guard ~ b => MatchArm(pats, guard, b)
        },
    )

  /** Patterns: scalar literals and ranges, the `_` wildcard, a positional destructuring
   * `V(sub…)` (an enum variant or a struct), a named struct destructuring `S{field: sub…}`, or a
   * bare name — which the analyzer reads as a nullary-variant pattern when it names a variant of
   * the scrutinee's enum, and as a binding otherwise. A name may be qualified wherever a variant
   * may be, which is every form: a nullary variant is spelled like a name and reached like one.
   */
  /** A pattern, with the **binding** form read first so that the name before an `@` is not taken as
   * a pattern in its own right. Everything after the `@` is an ordinary pattern, which is what makes
   * the form nest: `outer @ Wrap(inner @ Val(v))` is two of these.
   */
  override protected lazy val pattern: Parser[Pattern] =
    bindPattern | unboundPattern

  /** `n @ pat` — the value bound whole, and taken apart, in one pattern.
   *
   * The name is an `ident` rather than a `qualifiedName`: what a binding introduces is a local, and
   * a name with a dot in it is not a name a program can declare. A qualified one before an `@` is
   * therefore a mistake about what is being bound rather than a pattern the grammar could go on to
   * read, and it is answered as one.
   */
  protected lazy val bindPattern: Parser[Pattern] =
    (ident <~ op("@")) ~ unboundPattern ^^ { case n ~ p => BindPattern(n, p) } |
      (qualifiedName <~ guard(op("@"))) >> (n =>
        err(s"'$n' has a dot in it, and what a binding introduces is a local — write a name a " +
          "program can declare"))

  private lazy val unboundPattern: Parser[Pattern] =
    patternLit ~ (rangeOp ~ patternLit) ^^ { case lo ~ (inc ~ hi) => RangePattern(lo, hi, inc) } |
      structPattern |
      variantPattern |
      tuplePattern |
      wildcard ^^^ WildcardPattern |
      qualifiedName ^^ IdentPattern.apply |
      patternLit ^^ LitPattern.apply

  protected lazy val variantPattern: Parser[Pattern] =
    qualifiedName ~ (op("(") ~> commaList(pattern) <~ op(")")) ^^ { case n ~ ps => VariantPattern(n, ps) }

  /** `S{field: sub, other}` — a named struct pattern. A bare `field` is shorthand for
   * `field: field`, binding the field to a variable of the same name.
   */
  protected lazy val structPattern: Parser[Pattern] =
    qualifiedName ~ (op("{") ~> commaList(fieldPattern) <~ op("}")) ^^ { case n ~ fs => StructPattern(n, fs) }

  /** `(a, b)` — a tuple pattern. Two or more sub-patterns for the same reason the type takes two
   * or more parts, and a single one is refused where the type refuses it rather than being read as
   * a pattern in parentheses, which sysl has no use for.
   */
  protected lazy val tuplePattern: Parser[Pattern] =
    (op("(") ~> commaList1(pattern) <~ op(")")) >> {
      case List(_) => err("a tuple pattern matches two or more parts — one part is not a tuple")
      case parts   => success(TuplePattern(parts))
    }

  protected lazy val fieldPattern: Parser[(String, Pattern)] =
    ident ~ opt(op(":") ~> pattern) ^^ { case n ~ p => (n, p.getOrElse(IdentPattern(n))) }

  /** A pattern literal: any scalar literal, or a negated numeric literal. */
  protected lazy val patternLit: Parser[Expr] =
    op("-") ~> (floatLit | intLit) ^^ (e => Unary("-", e)) |
      floatLit | intLit | charLit | strLit | boolLit

  protected lazy val statements: PackratParser[List[Stmt]] =
    opt(newlines) ~> repsep(statement, newlines) <~ opt(newlines)

  /** A file: an optional module header, the clauses that go with it, then its statements. A file
   * with no header contributes to the anonymous root module, which is what lets a one-file program
   * be written with no ceremony — and it may still narrow itself or name a library, since the root
   * module is a module like any other.
   */
  protected lazy val program: PackratParser[Program] =
    opt(newlines) ~> opt(moduleHeader) >> { m =>
      // An attribute goes on a line of its own, which is what both `13 §4` and `capabilities.md`
      // show and what keeps `module m @no_alloc @requires(os)` from being a line anyone has to read.
      // The exception is a file that declares no module: the root module is a module like any other,
      // and there is no header for its attributes to sit below, so there they may open the file.
      val lead = if m.isDefined then success(List.empty[CapabilityClause | LinkClause])
                 else opt(headerAttr) ^^ (_.toList.flatten)

      lead ~ rep(newlines ~> headerAttr) ~ statements ^^ {
        case first ~ rest ~ body =>
          val clauses = first ::: rest.flatten

          Program(body, m,
                  clauses.collect { case c: CapabilityClause => c },
                  clauses.collect { case l: LinkClause => l },
                  source)
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
   * wherever the longest partial match happened to stop — `print("oops` reported "newline expected"
   * at the paren, having taken `print` alone as a complete statement. The lexer already knew the
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
