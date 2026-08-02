package io.github.edadma.sysl

import scala.util.parsing.input.Position

/** The sysl parser: a packrat combinator grammar over the materialized token list from
 * `SyslLexical` (see docs/design/front-end.md).
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
  */
case class TypeParams(
    names: List[String],
    bounds: Map[String, List[BoundRef]] = Map.empty,
    defaults: Map[String, TypeRef] = Map.empty,
)

object TypeParams {
  val none: TypeParams = TypeParams(Nil)
}

/** The sysl parser: a packrat combinator grammar over the materialized token list from
 * `SyslLexical` (see docs/design/front-end.md).
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
      misplacedCapability | misplacedLink | importDecl | implDecl | declaration | varDecl | refDecl | returnStmt |
        breakStmt | continueStmt | deferStmt | requireStmt | ensureStmt | multiAssign |
        resultListStmt | exprStmt,
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

  protected lazy val contractMsg: Parser[String] =
    accept("string literal", { case t: lexical.StrLit => t.value })

  /** A declaration that may carry a visibility modifier (`13 §2`).
   *
   * The five forms are grouped so the modifier is written once, before whichever of them follows,
   * rather than threaded through five rules that would each have to remember it. An `impl` is not
   * among them and takes none: it declares no name, so there is nothing for a modifier to restrict.
   */
  protected lazy val declaration: PackratParser[Stmt] =
    testDecl |
      implVisibility |
      visibility ~ (structDecl | enumDecl | typeDecl | traitDecl | externDecl | constDecl | valDecl | funcDecl) ^^ {
        case Visibility.Public ~ d => d
        case v ~ d                 => restrict(v, d)
      }

  /** A function carrying `#test`, which is a declaration with a line in front of it (`testing.md`).
   *
   * The attribute is its own line and the declaration follows on the next, which is why the newlines
   * between them are consumed here: the statement separator would otherwise end the statement at the
   * attribute, leaving a prefix with nothing to attach to. Everything about the declaration itself is
   * still `declaration`'s — a test may be `private`, and is written exactly as any other function.
   *
   * Only a function may carry it, and the refusal below is what says so. A struct or a `val` with
   * `#test` above it is a mistake about what a test *is* rather than a syntax error, so it is
   * answered with the sentence rather than with the list of forms the grammar could still have read.
   */
  protected lazy val testDecl: PackratParser[Stmt] =
    testAttr >> { a =>
      // Once the attribute has been read the statement is committed to being a test, which is what
      // `>>` buys: everything after it is read against that, so a declaration that cannot carry one
      // is answered with the sentence below rather than with the grammar's complaint about whichever
      // alternative it went on to try.
      // The newlines are consumed *before* the choice so that both arms start at the same token. A
      // combinator choice keeps whichever alternative reached furthest, so an `err` written behind
      // the newline would sit earlier than the declaration rule's own failure and lose to it —
      // leaving the reader with "identifier expected" at a line whose problem is the one above it.
      opt(newlines) ~> ((visibility ~ funcDecl) ^^ {
        case Visibility.Public ~ (f: FuncDecl) => f.copy(test = Some(a))
        case v ~ (f: FuncDecl)                 => restrict(v, f.copy(test = Some(a)))
        case _ ~ other                         => other
      } | err(
        "'#test' marks a function as a unit test, and only a function — there is nothing for " +
          "'sysl test' to call in any other declaration",
      ))
    }

  /** `#test`, and the three things it may say about the test: the name a report gives it, that it is
   * a run which should not come back, and the text such a run should have printed on its way out.
   */
  protected lazy val testAttr: PackratParser[TestAttr] =
    at(op("#") ~> testWord ~> opt(op("(") ~> testArgs <~ op(")")) ^^ (_.getOrElse(TestAttr(None, false, None))))

  /** The attribute's name. `test` stays an ordinary identifier — reserving it would spend the word
   * out of every program's namespace for the sake of one line per test, which is the trade `alloc`
   * made and the one `capabilities.md § Open` is still paying for.
   */
  protected lazy val testWord: Parser[Unit] =
    accept("'test'", { case t: lexical.Identifier if t.chars == "test" => () }) |
      ident >> (n => err(s"'$n' is not an attribute sysl knows — '#test' is the only one"))

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

  /** A capability clause: `no alloc` narrowing the module below what its target offers, or
   * `requires alloc` declaring what it cannot be built without (`13 §4`, `capabilities.md`).
   *
   * `no` and `alloc` are **reserved**, not contextual, so they are matched with `op` — a `softWord`
   * matches an identifier and these never lex as one. Every other capability's name is an ordinary
   * identifier, which is why the name is read either way and left to the analyzer to recognize: the
   * set is a property of the project rather than of the grammar.
   */
  protected lazy val capabilityClause: Parser[CapabilityClause] =
    at(
      op("no") ~> capabilityName ^^ (CapabilityClause(CapabilityDirection.Narrows, _)) |
        op("requires") ~> capabilityName ^^ (CapabilityClause(CapabilityDirection.Requires, _)),
    )

  /** The name in a capability clause. `alloc` is a reserved word, because the clause reads it, so it
   * would never arrive as an identifier; the rest of the set is spelled the ordinary way.
   */
  protected lazy val capabilityName: Parser[String] = op("alloc") ^^^ "alloc" | ident

  /** A capability clause written where a statement goes, which is refused for the reason
   * `noVisibility` is: the clause has a place, and a reader who writes it in the wrong one should be
   * told which place that is rather than answered with "newline expected".
   *
   * It is a *header*, not a statement, because it is a property of the module and the module is
   * settled before anything in the file runs — and because a clause part-way down a file would read
   * as though the statements above it were outside its reach.
   */
  protected lazy val misplacedCapability: Parser[Nothing] =
    capabilityClause ~> err(
      "a capability clause belongs in the file's header, on the lines directly after 'module' and " +
        "before everything else — it is a property of the whole module, not of the statements below it")

  /** A link directive: `link "z"`, naming a library the linker must be given for this file's
   * `extern`s to resolve (`15 §8`).
   *
   * **`link` is a soft keyword and cannot be anything else.** `guide/slab` declares a function called
   * `link` — the pointer into a free block — and reserving the word would break it, which is exactly
   * the kind of name a systems language must not spend. Nothing is lost by it: a directive is `link`
   * followed by a *string*, and no statement has that shape, so the grammar tells them apart with no
   * lookahead.
   *
   * The library is named by a string rather than by an identifier because it is a name from outside
   * sysl, exactly as an `extern`'s symbol is — and because plenty of real ones are not identifiers at
   * all. `stdc++` is the everyday example.
   */
  protected lazy val linkClause: Parser[LinkClause] = at(softWord("link") ~> linkName ^^ LinkClause.apply)

  /** One line of a file's header: either kind of clause, read in whatever order they were written.
   *
   * They interleave freely because they are about different things — what the module may do, and what
   * its `extern`s need — and demanding one group before the other would be a rule with nothing behind
   * it that every author would have to remember.
   */
  private lazy val headerClause: Parser[CapabilityClause | LinkClause] = capabilityClause | linkClause

  /** A link directive written where a statement goes, refused for the reason `misplacedCapability`
   * is: the clause has a place, and a reader who wrote it in the wrong one should be told which place
   * that is rather than answered with "newline expected".
   */
  protected lazy val misplacedLink: Parser[Nothing] =
    linkClause ~> err(
      "a link directive belongs in the file's header, on the lines directly after 'module' and " +
        "before everything else — the linker is given its libraries once for the whole build, not " +
        "at the point in the file where the directive is written")

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
        qualifiedName ~ opt(typeArgs) ^^ { case n ~ args => NamedType(n, args.getOrElse(Nil)) },
    )

  /** `(A, B)` — a tuple type. A single part is refused rather than read as a grouping, because the
   * two spellings would then differ by a comma and mean different things; `(T)` is the shape
   * somebody writes when they mean a one-tuple, and there is no such type (`00 §13`).
   */
  protected lazy val tupleType: Parser[TypeRef] =
    (op("(") ~> commaList1(typeRef) <~ op(")")) >> {
      case List(one) =>
        err(s"'(${one.show})' is a type in parentheses, and a tuple has two or more parts — " +
          s"a product of one thing is that thing, so write '${one.show}'")
      case parts => success(TupleType(parts))
    }

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
    op("[") ~> commaList1(typeRef) <~ op("]")

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
    op("[") ~> commaList1(boundedTypeParam) <~ op("]") ^^ { ps =>
      TypeParams(
        ps.map(_._1),
        ps.collect { case (n, bs, _) if bs.nonEmpty => n -> bs }.toMap,
        ps.collect { case (n, _, Some(d)) => n -> d }.toMap,
      )
    }

  protected lazy val boundedTypeParam: Parser[(String, List[BoundRef], Option[TypeRef])] =
    ident ~ opt(op(":") ~> rep1sep(boundRef, op("+"))) ~ opt(op("=") ~> typeRef) ^^ {
      case n ~ bs ~ d => (n, bs.getOrElse(Nil), d)
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
    (op(keyword) ~> (structPattern | variantPattern | tuplePattern)) ~ (op("=") ~> expression) ^^ {
      case p ~ v => PatternDecl(p, mutable, Placeholders.lift(v))
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

  /** `loop body` — a `while` with the condition left out, ended by a `break` rather than by a test.
   *
   * It takes no `else`: an `else` runs when a loop finishes on its own, and this one never does.
   * The inline `loop do …` form is there because `while c do …` has it and a one-line body should
   * not have to change shape when its condition goes away.
   */
  protected lazy val loopExpr: PackratParser[Expr] =
    opt(labelRef) ~ (op("loop") ~> body("do")) ~ opt(endMarker("loop")) ^^ { case lbl ~ b ~ _ => Loop(lbl, b) }

  protected lazy val forExpr: PackratParser[Expr] = cForExpr | forInExpr

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
  override protected lazy val pattern: Parser[Pattern] =
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
      // A clause goes on a line of its own, which is what both `13 §4` and `capabilities.md` show
      // and what keeps `module m no alloc requires os` from being a line anyone has to read. The
      // exception is a file that declares no module: the root module is a module like any other, and
      // there is no header for its clause to sit below, so there the clause may open the file.
      val lead = if m.isDefined then success(List.empty[CapabilityClause | LinkClause])
                 else opt(headerClause) ^^ (_.toList)

      lead ~ rep(newlines ~> headerClause) ~ statements ^^ {
        case first ~ rest ~ body =>
          val clauses = first ::: rest

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
