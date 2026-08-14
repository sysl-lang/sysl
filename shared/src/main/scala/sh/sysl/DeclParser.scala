package sh.sysl

/** What a declaration looks like: parameters, functions and `extern`s, structs and their members,
 * enums and their variants, constrained type declarations, traits, and `impl` blocks.
 *
 * They are one area because they share one shape. Every form here is a name, an optional generic
 * parameter list, something in parentheses or indented under it, and an optional `end` marker naming
 * what it closes — so the rules that read those parts (`boundedTypeParams`, `paramList`, `suite`,
 * `checkedEndName`) are written once and reached from all of them. A member is the clearest case:
 * the same `methodTail` serves a struct's method, an enum's, a trait's requirement, and an `impl`'s
 * definition, because in this language those differ in where they appear and not in how they read.
 */
trait DeclParser extends ExprParser {

  /** One `name: type` binding — a function parameter or a struct field.
   *
   * The name may be a **reserved word**, and what happens then is worth a rule rather than a parse
   * failure. Neither position has anything to say about the word itself: a struct's body ends, and
   * the reader is told `dedent expected` at a line that is plainly a field; a parameter list closes,
   * and the reader is told `')' expected` at a token that is plainly a parameter. Both messages are
   * about layout, which is the one thing that is not wrong.
   *
   * **The lookahead is the word AND the colon**, and that is what keeps this from claiming ground the
   * expression grammar needs. A reserved word can perfectly well begin an argument — `f(true)`,
   * `f(null)`, `f(if c then 1 else 2)` are all calls, and a declaration is only one of the readings
   * this position is tried under, so an unconditional refusal here would take `f(true)` as a
   * statement with it. Followed by a colon it can be nothing but a binding somebody attempted.
   */
  protected lazy val param: Parser[Param] =
    at((ident | guard(reservedWord ~ op(":")) ~> reservedName("a parameter's name or a field's")) ~
      (op(":") ~> typeRef) ^^ { case n ~ t => Param(n, t) })

  /** A function's parameter, which unlike a struct's field may say what a call that leaves it out
   * gets instead (`12 §2a`). The default is a full `expression`, so a call, a conditional, or
   * anything else that yields a value may stand there; whether it *may* — a suffix, naming nothing
   * local, reaching as far as the declaration does — is the analyzer's, since all three are
   * questions about meaning rather than about shape.
   */
  protected lazy val funcParam: Parser[Param] =
    at((byNameParam | param) ~ opt(op("=") ~> expression) ^^ {
      case p ~ d => p.copy(default = d.map(Placeholders.lift))
    })

  /** `x: -> T` — a parameter passed **by name** (`12 § A parameter may be passed by name`).
   *
   * The arrow with nothing on its left is the nullary case of the bare-arrow sugar a parameter
   * already has: `f: A -> B` is `[F: Fn(A) -> B](f: F)`, and this is that at arity zero. So the type
   * it builds is the one `x: () -> T` builds, and only `byName` separates them — the second is a
   * callable the caller constructs, the first is an expression the caller writes bare and the call
   * does not evaluate.
   *
   * It belongs to `funcParam` rather than to `param`, so a **struct field** cannot be written this
   * way. A field is storage, and storage holding an unevaluated expression is a different feature
   * from this one.
   */
  private lazy val byNameParam: Parser[Param] =
    at(ident ~ (op(":") ~> op("->") ~> typeRef) ^^ {
      case n ~ r => Param(n, FnType(Nil, r, bare = true), byName = true)
    })

  /** A struct's field, which has no default to declare. Said here rather than left to whatever the
   * grammar happened to want where the `= v` was written, because writing one is a reasonable thing
   * to try and the reason it is refused is not guessable from a complaint about shape.
   */
  protected lazy val fieldParam: Parser[Param] =
    param <~ (op("=") ~> err("a field declares no default — what an unwritten field gets is decided " +
      "by the constructor that builds the value, not by the field") | success(()))

  /** A function declaration, Scala-style but keyword-less: `name[T…](params) -> ret = expr` or
   * a block body, `-> ret` optional (absent ⇒ `unit`). It is tried before an expression
   * statement; a bare call `foo(1)` fails here (its arguments are not `name: type` bindings,
   * and nothing follows to open a body) and falls through to `exprStmt`.
   */
  /** A calling convention written before a definition: `interrupt handler()`, or with the privilege
   * mode a processor distinguishes, `interrupt(supervisor) handler()` (`15 §10`).
   *
   * `interrupt` is a soft keyword, and the trailing `guard(ident)` is the whole of what keeps it one.
   * Three things start with that word and only the first is a convention: `interrupt timer()`
   * declares a handler, `interrupt(n: int) -> int` declares a *function called* `interrupt`, and
   * `interrupt(4)` calls one. Requiring a name after the modifier tells them apart with no
   * backtracking a reader could feel — and consuming nothing when it fails is what lets the other
   * two go on to parse as themselves.
   */
  protected lazy val callConv: Parser[CallConv] =
    at((softWord("interrupt") ~> opt(op("(") ~> ident <~ op(")")) <~ guard(ident))
      ^^ (CallConv("interrupt", _)))

  protected lazy val funcDecl: PackratParser[Stmt] =
    opt(callConv) ~ ident ~ opt(boundedTypeParams) >> { case conv ~ name ~ tps =>
      val tp = tps.getOrElse(TypeParams.none)
      (op("(") ~> paramList <~ op(")")) ~ opt(op("->") ~> resultRef) ~ funcBody <~ endName(name) ^^ {
        case ((params, variadic)) ~ ret ~ body =>
          FuncDecl(name, tp.names, params, ret, body, tp.bounds, variadic, tdefaults = tp.defaults,
                   tvalues = tp.values, tpacks = tp.packs, conv = conv)
      }
    }

  /** `extern name(params) -> ret` — a header with no body at all, which is what tells it from a
   * function declaration — or `extern name: type`, the same seam pointed at a variable the other
   * side exports rather than a function. The result is optional and absent means `unit`, exactly as
   * for a function; `-> never` says the callee does not come back.
   *
   * What follows the name is what decides which of the two this is, so the name and its optional
   * link name are read once and both forms continue from there. That is also what makes the refusal
   * below able to name both: having got as far as an identifier, the declaration is an `extern`
   * whatever comes next, and the only question left is which kind.
   *
   * A string before the name is the *symbol*, and the name after it is what the program calls it by:
   * `extern "snprintf" fmt(…)` resolves to libc's `snprintf` without spending the name `snprintf`.
   * A leading string is unambiguous — a declaration otherwise begins with an identifier — so this
   * costs no keyword. Haskell's `foreign import ccall "snprintf" c_snprintf` is the same shape.
   */
  protected lazy val externDecl: PackratParser[Stmt] =
    op("extern") ~> opt(linkName) ~ ident ~ noTypeParams >> { case link ~ name ~ _ =>
      (op("(") ~> paramList <~ op(")")) ~ opt(op("->") ~> typeRef) ^^ {
        case ((params, variadic)) ~ ret => ExternDecl(name, params, ret, variadic, link)
      } |
        // The type is read *after* the colon has been consumed, so a mistake in it is reported as
        // the mistake it is rather than as this rule failing back to the sentence below.
        op(":") ~> (typeRef ^^ (t => ExternVarDecl(name, t, link)) |
          err(s"an 'extern' variable states the type the other side laid down — 'extern $name: T'")) |
        err(s"an 'extern' declares a function — '$name(params) -> result' — or a variable — " +
          s"'$name: type' — so what follows the name is '(' or ':'")
    }

  /** An `extern` may not declare type parameters. Monomorphization needs a body to specialize, and a
   * foreign symbol is one function at one signature however many sysl types would fit it — so the
   * bracketed list has nothing to do here, and saying that is worth more than the "'(' expected" a
   * grammar with no place for one would give. A program wanting one signature per type declares one
   * `extern` per type, each under its own name with the same link name.
   */
  protected lazy val noTypeParams: Parser[Unit] =
    op("[") ~> err("an 'extern' declares no type parameters — there is no body to monomorphize, and " +
      "a foreign symbol is one function at one signature") |
      success(())

  protected lazy val linkName: Parser[String] =
    accept("symbol name", { case t: lexical.StrLit => t.value })

  /** A declared parameter list, which may end in `...` — the C ellipsis, and the one arity a
   * declaration does not fix. Shared by `extern` and by a sysl function, which may be variadic too.
   * The `...`-only form parses so the analyzer can say why a variadic needs a named parameter before
   * it, rather than the grammar reporting a stray token.
   */
  protected lazy val paramList: Parser[(List[Param], Boolean)] =
    op("...") ^^^ (Nil, true) |
      // The variadic marker is tried before the trailing comma, so `f(a: int, ...)` still reads the
      // comma as the separator it is; `f(a: int,)` falls through to the trailing-comma case.
      repsep(funcParam, op(",")) ~ opt(op(",") ~> op("...")) <~ opt(op(",")) ^^ { case ps ~ dots =>
        (ps, dots.isDefined)
      }

  /** A function body is either an `= expr` short form (whose value is the return value) or an
   * indented block (whose trailing expression is the return value).
   */
  protected lazy val funcBody: PackratParser[List[Stmt]] =
    op("=") ~> (suite | resultValue ^^ (e => List(ExprStmt(e).setPos(e.pos)))) | suite

  /** One parsed line of a struct body, before the three kinds are sorted into their own lists. */
  private enum StructPart:
    case Fld(f: Param)
    case Mem(m: MethodDecl)
    case Inv(e: Expr)

  /** `opaque`, the modifier that withholds a struct's layout from every module but the one declaring
   * it (`15 §9`). A soft keyword: `opaque` is an ordinary word — an alpha channel's fully-`opaque`
   * end is the obvious field to want it for — and a language that spent it would be taking a name
   * away to save itself a lookahead.
   */
  protected lazy val opaqueKw: Parser[Unit] = softWord("opaque")

  /** A **type pack** stands for a list of types and there is one place to write the list out —
   * `(..A)`, the tuple of it (`10 §10`). A declaration whose parameters *are* its shape has nothing
   * to do with one: a struct of a pack would be a tuple with a name, which is the thing a program
   * writes instead when the arity stops being incidental.
   *
   * Raised where the parameter list closes rather than left to fail on the pack's use, since the use
   * is what a reader would then be sent to look at.
   */
  protected def noPacks(tp: TypeParams, what: String): Parser[Unit] =
    if tp.packs.isEmpty then success(())
    else
      err(s"'..${tp.packs.head}' is a type pack, and $what has no way to spread one over its own " +
        s"shape — a product of however many parts is a tuple, and '(..${tp.packs.head})' is how an " +
        "'impl' or a function takes one")

  protected lazy val structDecl: PackratParser[Stmt] =
    opt(opaqueKw) ~ (op("struct") ~> ident) ~ opt(boundedTypeParams) >> { case hidden ~ name ~ tps =>
      val tp     = tps.getOrElse(TypeParams.none)
      val opaque = hidden.isDefined

      // Which of the three this is **decides** how the rest is read, so it is settled by lookahead
      // and committed to with `>>`. Written as an alternation it would not work: a body whose first
      // line is bad fails deep inside the file, and a combinator choice keeps whichever alternative
      // reached furthest — so the sentence below, raised back at the declaration, would lose to it
      // and a struct with a mistake in its body would be reported as having no body. Neither guard
      // consumes, so both are asked at the same position and at most one of them can succeed.
      noPacks(tp, "a struct") ~> (opt(guard(newline ~ indent)) ~ opt(guard(onNextLine(softEnd)))) >> {
        case Some(_) ~ _ =>
          (newline ~> indent ~> skipNewlines ~> rep1sep(structItem, newlines) <~ skipNewlines <~ dedent) <~
            endName(name) ^^ { items =>
              val fields     = items.collect { case StructPart.Fld(f)  => f }
              val members    = items.collect { case StructPart.Mem(m)  => m }
              val invariants = items.collect { case StructPart.Inv(e)  => e }
              StructDecl(name, tp.names, fields, members, tp.bounds, invariants,
                         tdefaults = tp.defaults, opaque = opaque, tvalues = tp.values)
            }

        // A struct with **no fields**, whose emptiness is *written* rather than inferred from an
        // absence: an `end Name` and nothing between it and the declaration. A type like this is
        // one value carrying no data, which is what a sink standing for a fixed destination — the
        // console, a UART — has to be to be a value at all. Requiring the marker is what keeps a
        // misindented body from quietly becoming one; a lone `struct Name` is still the mistake it
        // has always been, and still says so below.
        case None ~ Some(_) =>
          endName(name) ^^^ StructDecl(name, tp.names, Nil, Nil, tp.bounds, Nil,
                                       tdefaults = tp.defaults, opaque = opaque, tvalues = tp.values)

        // A struct with **no body at all**, which only an `opaque` one may be: it is C's incomplete
        // type, `struct sqlite3;`, and it is what a handle from a C library should be declared as.
        // Nothing in sysl lays one out — the storage belongs to whoever allocated it — so the
        // declaration exists to give `*sqlite3` a type of its own that a `*u8` cannot be mistaken for.
        case _ if opaque =>
          success(StructDecl(name, tp.names, Nil, Nil, tp.bounds, Nil,
                             tdefaults = tp.defaults, opaque = true, tvalues = tp.values))

        case _ =>
          err(s"'struct $name' declares no fields — a struct's body is indented under it, a type " +
            s"with no fields at all is written 'struct $name' closed by 'end $name', and one with " +
            s"no layout of its own is written 'opaque struct $name'")
      }
    }

  /** A line inside a struct body is a member declaration, an `invariant <bool>` clause, or a
   * `name: type` field. A member is tried first — it needs a `(` (a method or associated function)
   * or a `->` (a property) after the name; an `invariant` clause next — it is the contextual word
   * `invariant` followed by an expression; and a bare field falls through to `param` (so a field
   * may still be named `invariant`, since `invariant: type` matches neither of the first two).
   *
   * A field and a member may each carry a **visibility modifier** (`08 § Visibility`), written in
   * the same place and the same spellings a top-level declaration writes one. An `invariant` clause
   * declares no name, so like an `impl` block it takes none.
   */
  private lazy val structItem: Parser[StructPart] =
    restrictedMember ^^ (StructPart.Mem(_)) |
      invariantClause ^^ (StructPart.Inv(_)) |
      visibility ~ fieldParam ^^ { case v ~ f => StructPart.Fld(f.copy(vis = v).setPos(f.pos)) }

  /** A member of a type's own body, which is the one kind that may say how far it is visible.
   *
   * It opens with `noMemberAttr` so that an annotation written above a member is answered by the
   * sentence that rule carries. A struct's body and an enum's both read their lines through here, and
   * a member is the first alternative each tries, so the refusal is reached wherever the `@` is —
   * including the position a field or a variant would have been read at.
   */
  protected lazy val restrictedMember: PackratParser[MethodDecl] =
    noMemberAttr ~> visibility ~ (noOverride ~> member) ^^ { case v ~ m =>
      m.copy(vis = v).setPos(m.pos)
    }

  /** The refusal a trait's member and an `impl`'s share (`08 § Visibility`). Both are reached at the
   * reach the *trait* has — one asks for the member and the other supplies what was asked — so
   * there is nothing here for a modifier to decide, and saying that is worth more than whatever the
   * grammar happened to want where the modifier was written.
   */
  protected lazy val noVisibility: Parser[Unit] =
    op("private") ~> err("a trait's members and an 'impl' block's carry no visibility of their own — a " +
      "trait's member is as visible as the trait, and an implementation supplies what the trait asked for") |
      success(())

  /** The refusal a **trait's** member and a **type's own** member share: neither can be replacing
   * anything, so neither may say `override` (`02 § override`).
   *
   * A trait's member is where a default body is *written*, not where one is replaced. A member of a
   * type's own body implements no trait — a name a trait also declares is a collision there and is
   * reported as one — so an implementation replaces a default inside the `impl` block that keeps the
   * promise, which is the one place the keyword belongs.
   */
  protected lazy val noOverride: Parser[Unit] =
    op("override") ~> err("'override' says a member replaces a body its trait supplied, and it is " +
      "written on that member inside the 'impl' block — a trait's own member is where a default is " +
      "written rather than replaced, and a member of a type's body implements no trait") |
      success(())

  /** `invariant <bool>` among a struct's fields: a condition every value of the struct must satisfy,
   * re-checked whenever the struct is built or one of its fields is written. Bare field names are in
   * scope. `invariant` is contextual — an ordinary identifier everywhere else. */
  protected lazy val invariantClause: Parser[Expr] = invariantKw ~> expression

  protected lazy val invariantKw: Parser[Unit] = softWord("invariant")

  /** A member of a type's body. What follows the name decides the kind: `(params)` is a method
   * (or, with no `self`, an associated function), and `-> type = body` with no parameter list is
   * a computed property.
   *
   * A member may declare **type parameters of its own**, in the same bracketed list every other
   * generic declaration writes and in the same position — directly after the name. They are the
   * member's, not the type's: a call fixes them from what it passes, while the type's own are
   * already fixed by the receiver.
   */
  protected lazy val member: PackratParser[MethodDecl] =
    at(
      ident ~ opt(boundedTypeParams) >> { case name ~ tps =>
        methodTail(name, tps.getOrElse(TypeParams.none)) |
          (if tps.isEmpty then propertyTail(name) else failure("a property takes no type parameters"))
      },
    )

  protected def methodTail(name: String, generics: TypeParams): Parser[MethodDecl] =
    (op("(") ~> methodParams <~ op(")")) ~ opt(op("->") ~> resultRef) ~ funcBody <~ endName(name) ^^ {
      case (recv, params, variadic) ~ ret ~ body =>
        MethodDecl(name, recv, isProperty = false, generics.names, params, ret, body, generics.bounds,
          generics.defaults, variadic = variadic, tvalues = generics.values, tpacks = generics.packs)
    }

  /** A property takes the same `funcBody` a method does, so `name -> T` may be answered by an
   * `= expr`, by an `=` opening an indented block, or by a block with no `=` at all. A property is a
   * function with the parameter list left off, and having only the one-expression spelling made it
   * the one member whose body could not be written out — which bit a default property in a trait
   * the same way it bit an inherent one.
   */
  protected def propertyTail(name: String): Parser[MethodDecl] =
    (op("->") ~> typeRef) ~ funcBody <~ endName(name) ^^ {
      case ret ~ body => MethodDecl(name, None, isProperty = true, Nil, Nil, Some(ret), body)
    }

  /** The parenthesised part of a method: an optional receiver shorthand (`self`, `*self`,
   * `&self`, `&sync self`) followed by ordinary `name: type` parameters. With no receiver the
   * member is an associated function.
   *
   * Either shape may end in the same trailing `...` a free function's list takes (`12 §9`), and for
   * the same reason: a member is a function with a receiver in front, so an ellipsis reaching one
   * and not the other would be a difference in the grammar with nothing behind it. With a receiver
   * the ellipsis is tried after the parameters, so `add(self, n: int, ...)` still reads its commas
   * as the separators they are.
   */
  protected lazy val methodParams: Parser[(Option[RecvMode], List[Param], Boolean)] =
    receiver ~ rep(op(",") ~> funcParam) ~ opt(op(",") ~> op("...")) <~ opt(op(",")) ^^ {
      case r ~ ps ~ dots => (Some(r), ps, dots.isDefined)
    } |
      paramList ^^ { case (ps, variadic) => (None, ps, variadic) }

  protected lazy val receiver: Parser[RecvMode] =
    op("*") ~> op("self") ^^^ RecvMode.ByPtr |
      op("&") ~> softSync ~> op("self") ^^^ RecvMode.ByRef(sync = true) |
      op("&") ~> op("self") ^^^ RecvMode.ByRef(sync = false) |
      op("self") ^^^ RecvMode.ByValue

  /** `enum Name[T…]` with indented variants, and an optional `: iN` underlying-type annotation
   * that pins a simple enum's storage. A variant is a bare name (`Empty`), a name with an
   * explicit integer value (`Blue = 10`), or a name with a payload (`Circle(radius: int)`).
   */
  protected lazy val enumDecl: PackratParser[Stmt] =
    op("enum") ~> ident ~ opt(boundedTypeParams) ~ opt(op(":") ~> typeRef) >> { case name ~ tps ~ under =>
      val tp = tps.getOrElse(TypeParams.none)

      noPacks(tp, "an enum") ~> (newline ~> indent ~> skipNewlines ~> rep1sep(enumItem, newlines) <~ skipNewlines <~ dedent) <~ endName(name) ^^ {
        items =>
          val variants = items.collect { case Left(v)  => v }
          val members  = items.collect { case Right(m) => m }
          EnumDecl(name, tp.names, under, variants, members, tp.bounds, tdefaults = tp.defaults,
                   tvalues = tp.values)
      }
    }

  /** A line inside an enum body is either a variant or a member declaration, told apart the same
   * way a struct body's lines are: a member is tried first and needs a body to follow its header,
   * so `Circle(radius: int)` — a header with nothing after it — falls through to `enumVariant`.
   */
  protected lazy val enumItem: Parser[Either[EnumVariantDecl, MethodDecl]] =
    restrictedMember ^^ (Right(_)) | enumVariant ^^ (Left(_))

  protected lazy val enumVariant: Parser[EnumVariantDecl] =
    at(
      ident ~ (op("(") ~> commaList(fieldParam) <~ op(")")) ^^ { case n ~ fs => EnumVariantDecl(n, None, fs) } |
        ident ~ (op("=") ~> expression) ^^ { case n ~ v => EnumVariantDecl(n, Some(v), Nil) } |
        ident ^^ (n => EnumVariantDecl(n, None, Nil)),
    )

  /** `type Name = [new] Base [within lo..hi] [where predicate]` — a constrained subtype (`16`).
   * `new`, `within`, and `where` are contextual: they are ordinary identifiers everywhere else, so
   * a function or field may still be named `where`, and are recognised as keywords only here.
   */
  protected lazy val typeDecl: PackratParser[Stmt] =
    op("type") ~> ident ~ (op("=") ~> opt(newKw) ~ typeRef ~ opt(withinClause) ~ opt(whereClause)) ^^ {
      case name ~ (nw ~ base ~ range ~ pred) => TypeDecl(name, base, nw.isDefined, range, pred)
    }

  /** `within lo..hi` (inclusive) or `within lo..<hi` (upper-exclusive). The `..<` token is a single
   * lexeme, so it is told from `..` by the tokenizer rather than here.
   */
  protected lazy val withinClause: Parser[RangeBound] =
    withinKw ~> boundLit ~ (op("..<") ^^^ true | op("..") ^^^ false) ~ boundLit ^^ {
      case lo ~ excl ~ hi => RangeBound(lo, hi, excl)
    }

  protected lazy val whereClause: Parser[Expr] = whereKw ~> expression

  /** A bound of a `within` range: any **constant expression**, which is the same thing an array bound
   * accepts — `within 0..<max_tasks` beside `[max_tasks]Task`, so a table's size and the range of the
   * type indexing it are one fact written once (`13 §7`, `16 § Open b`).
   *
   * The level is `bitOr`, which is deliberately the one *tighter* than a range: `rangeExpr` is built
   * out of `bitOr`, so parsing a bound at any looser level would let `0..<max_tasks` be read as a range
   * expression and swallow the very operator that separates the two bounds. That ambiguity is what kept
   * this position at a literal, and naming the level is the whole of the fix.
   *
   * Whether the expression really is constant is not a question the grammar can answer — `n + 1` is a
   * fine parse and a fine constant when `n` is one — so it is answered where the bound is turned into a
   * number, which is also where its kind is checked against the base.
   */
  protected lazy val boundLit: Parser[Expr] = bitOr

  protected lazy val newKw: Parser[Unit]    = softWord("new")
  protected lazy val withinKw: Parser[Unit] = softWord("within")
  protected lazy val whereKw: Parser[Unit]  = softWord("where")

  /** A contextual keyword: an identifier spelled exactly `word`, matched where the grammar wants the
   * keyword but the word must stay a legal identifier everywhere else (the `sync` of `&sync T`).
   */
  protected def softWord(word: String): Parser[Unit] =
    accept(s"'$word'", { case t: lexical.Identifier if t.chars == word => () })

  /** `trait Name` with indented member declarations. Each is a method header — a receiver, a
   * parameter list, and an optional result — either bare, which requires an implementation to
   * supply it, or followed by a body, which supplies a **default** every `impl` inherits unless it
   * writes its own.
   *
   * `trait Name: Super + Other` names the traits this one **requires**, spelled exactly as a bound
   * on a type parameter is — the same `:` and the same `+` — because it asks the same thing of the
   * implementing type. A generic trait writes both: `trait Word[T]: Add`, the parameters first.
   *
   * **The members may be left out entirely, and only where the trait requires another.** Such a
   * trait declares no behaviour of its own; what it says is that its implementors have the ones it
   * names, which makes it a name for a family rather than for a capability — `Integer` is the case
   * this exists for, and a blanket `impl` written over it is what reads the name.
   *
   * Requiring the supertraits is what keeps the omission from being a hole. A trait with neither
   * members nor requirements says nothing at all, and a body indented by the wrong amount would
   * become one silently — so the form that means something is allowed and the form that cannot is
   * still the missing block it was.
   */
  protected lazy val traitDecl: PackratParser[Stmt] =
    op("trait") ~> ident ~ opt(boundedTypeParams) ~ opt(op(":") ~> rep1sep(boundRef, op("+"))) >> {
      case name ~ tps ~ supers =>
        val tp = tps.getOrElse(TypeParams.none)
        val body =
          (newline ~> indent ~> skipNewlines ~> rep1sep(traitMember, newlines) <~ skipNewlines <~ dedent) <~
            endName(name)

        def decl(methods: List[MethodDecl]) =
          TraitDecl(name, tp.names, methods, tp.bounds, supers.getOrElse(Nil), tdefaults = tp.defaults)

        noPacks(tp, "a trait") ~>
          (if supers.isEmpty then body ^^ decl else opt(body) ^^ (m => decl(m.getOrElse(Nil))))
    }

  /** A line inside a trait body. A **definition** is tried first, since it is a signature with more
   * after it: `member` needs a body to follow the header, so a bare method signature falls through
   * to `methodSig` and a bare property signature to `propertySig`. A signature of either kind asks
   * an implementation for that member; one written with a body supplies a default instead.
   */
  protected lazy val traitMember: PackratParser[MethodDecl] =
    noMemberAttr ~> noVisibility ~> noOverride ~> (member | methodSig | propertySig)

  /** A trait method signature: a header with no `= body`. The receiver and parameters parse
   * exactly as a real method's do, so a signature and its implementation are compared shape for
   * shape.
   */
  protected lazy val methodSig: PackratParser[MethodDecl] =
    at(
      ident ~ opt(boundedTypeParams) ~ (op("(") ~> methodParams <~ op(")")) ~ opt(op("->") ~> resultRef) ^^ {
        case name ~ tps ~ ((recv, params, variadic)) ~ ret =>
          val tp = tps.getOrElse(TypeParams.none)
          MethodDecl(name, recv, isProperty = false, tp.names, params, ret, Nil, tp.bounds, tp.defaults,
            variadic = variadic, tvalues = tp.values, tpacks = tp.packs)
      },
    )

  /** A property signature — `name -> type` with neither a parameter list nor a body. */
  protected lazy val propertySig: PackratParser[MethodDecl] =
    at(ident ~ (op("->") ~> typeRef) ^^ { case name ~ ret =>
      MethodDecl(name, None, isProperty = true, Nil, Nil, Some(ret), Nil)
    })

  /** `impl Trait for Type` with indented method definitions — ordinary members, reusing the same
   * grammar as a method written in a struct's own body. The block is closed by an optional
   * `end Type`.
   *
   * The type is a full type reference, not a name: `impl Show for []int` is as ordinary as
   * `impl Show for Point`, and which types an `impl` may be *for* is the analyzer's to decide
   * rather than something to leave the grammar unable to express.
   *
   * The block may declare **type parameters of its own**, in the same bracketed list a generic
   * function writes and in the same position — directly after the keyword that opens the
   * declaration: `impl[T: Show] Show for Box[T]`. They are what makes the implementation cover a
   * generic type as a whole, and the bounds on them are what make it conditional.
   *
   * The body itself is optional, because a trait whose every method has a default leaves a
   * conforming type nothing to write: `impl Zero for E` on its own line is the whole of that
   * implementation, and the opt-in it states is the point of writing it.
   *
   * The block may be marked **`override`** (`02 § override`), which says it deliberately replaces a
   * more general implementation already covering the same type. The keyword goes in front of `impl`
   * rather than anywhere inside, because what it qualifies is the block as a whole.
   */
  protected lazy val implDecl: PackratParser[Stmt] =
    overrideMod ~ (op("impl") ~> opt(boundedTypeParams) ~ implTrait ~ (op("for") ~> typeRef)) >> {
      case ov ~ (tps ~ ((tname, targs)) ~ forType) =>
        val tp = tps.getOrElse(TypeParams.none)

        (implBody | success(Nil)) <~ endTypeRef(forType) ^^ { methods =>
          ImplDecl(tname, forType, methods, tp.names, tp.bounds, targs, tp.defaults, ov, tp.values,
                   tp.packs)
        }
    }

  /** The `override` keyword in front of a declaration, or nothing — which is the ordinary case and
   * writes nothing, exactly as public visibility does.
   */
  protected lazy val overrideMod: Parser[Boolean] = opt(op("override")) ^^ (_.isDefined)

  /** The trait an `impl` is of: a name and its arguments, or a callable written as one (`12 §6`).
   *
   * The arrow spelling is here so that the arity-carrying declaration behind a call trait stays out
   * of programs entirely — a type made callable by hand is written `impl Fn(int) -> int for Doubler`,
   * the same way the type of one is written everywhere else.
   */
  protected lazy val implTrait: Parser[(String, List[TypeRef])] =
    (fnWord ~> op("(") ~> commaList(typeRef) <~ op(")")) ~ (op("->") ~> typeRef) ^^ {
      case ps ~ r => (Type.Fn.base(ps.length), ps :+ r)
    } |
      qualifiedName ~ opt(typeArgs) ^^ { case n ~ args => (n, args.getOrElse(Nil)) }

  protected lazy val implBody: PackratParser[List[MethodDecl]] =
    newline ~> indent ~> skipNewlines ~> rep1sep(implMember, newlines) <~ skipNewlines <~ dedent

  /** A member of an `impl` block, which is the one place a member may say `override` — the trait it
   * implements is the only thing a member of a type can be replacing a body from.
   */
  protected lazy val implMember: PackratParser[MethodDecl] =
    noMemberAttr ~> noVisibility ~> overrideMod ~ member ^^ { case ov ~ m =>
      m.copy(overrides = ov).setPos(m.pos)
    }

  /** An optional `end Name` marker closing a declaration block, Scala-style. `end` is a soft
   * keyword; the trailing name must equal the declaration's own name, or it is a parse error.
   */
  protected def endName(name: String): Parser[Unit] =
    opt(onNextLine(softEnd) ~> checkedEndName(name)) ^^^ (())

  protected def checkedEndName(expected: String): Parser[Unit] =
    ident >> { n =>
      if n == expected then success(()) else err(s"'end $n' does not match '$expected'")
    }

  /** The same marker closing an `impl`, whose subject is a type rather than a name — `end []int`
   * as readily as `end Point`. The two references are compared as written, since nothing has
   * resolved either of them yet and matching the spelling is all this marker was ever doing.
   */
  protected def endTypeRef(expected: TypeRef): Parser[Unit] =
    opt(onNextLine(softEnd) ~> (typeRef >> { t =>
      if t == expected then success(()) else err(s"'end ${t.show}' does not match '${expected.show}'")
    })) ^^^ (())

  /** An indented block: a leading `Newline`+`Indent` (the lexer's off-side signal) wraps a
   * statement sequence closed by `Dedent`.
   */
  protected lazy val suite: PackratParser[List[Stmt]] =
    newline ~> indent ~> statements <~ dedent

  /** A single statement written on the same line as its control-flow keyword. */
  protected lazy val inlineBody: PackratParser[List[Stmt]] = inlineStatement ^^ (s => List(s))

  /** The body of a control-flow construct, Scala-style: the introducer keyword (`then` /
   * `do`) is required for a one-line body but optional before an indented block, since a
   * following `Newline`+`Indent` already marks the block unambiguously.
   */
  protected def body(keyword: String): Parser[List[Stmt]] =
    op(keyword) ~> (suite | inlineBody) | suite
}
