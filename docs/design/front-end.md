# Front End: Lexer and Parser

**Status:** decided. This is a compiler-*architecture* decision (as distinct from the numbered
language-spec chapters). It records the lexer and parser choice for the restart and the
reasoning, so the decision is not re-litigated.

## Decision

- **Lexer:** reuse `io.github.edadma.indentation.IndentationLexical` — a published, tested,
  cross-platform library — for the off-side rule, driven by a **purpose-written sysl token
  ADT**.
- **Parser:** a **packrat combinator parser** (`scala-parser-combinators` `PackratParsers`)
  consuming the materialized `List[Token]` the lexer produces.

## Lexer: `IndentationLexical` + a proper token ADT

`IndentationLexical` already solves the genuinely hard part of an indentation-sensitive lexer,
and does it cleanly — it is tested (reentrancy + a toy grammar), cross-platform (JVM/JS/Native,
matching the sysl compiler), and published to Maven Central. It provides:

- the off-side rule as `Newline` / `Indent` / `Dedent` tokens over an indent stack;
- **line-joining inside `(` / `[` / `{`** pairs;
- **trailing-operator line continuation** (`isLineContinuationToken`) — a binary operator at
  end-of-line joins the next indented line;
- **tab/space-consistency** errors;
- line- and block-comment skipping.

Adopting it **resolves the open "indentation mechanics" question**: sysl takes this model
(`newlineBeforeIndent = true`, `newlineAfterDedent = true`, bracket line-joining,
trailing-operator continuation).

Two deliberate points, per the cautious-reuse rule (`principles.md` §1):

- **Write a proper sysl token ADT.** Override `token` to emit *structured* literal tokens —
  `IntLit(value, suffix)`, `CharLit(codepoint)`, `FloatLit(…)`, etc. — **not** the old
  `SyslLexer`'s stringly-typed hack (`NumericLit("42:u8")`, `NumericLit("…:char")`). That
  tagging was glue in the old lexer, not part of this library; it is exactly the "close but
  not exactly right" piece we rewrite rather than inherit.
- **`blockTriggerToken = None`** until/unless a closure-in-call-parens block syntax
  (`f((x) -> <newline> <body>)`) is actually chosen. That machinery (~⅓ of the library) is
  opt-in and stays inert otherwise.

Operators need no special lexer handling: because the operator set is closed (types §9 — no
custom operators), they are a fixed delimiter list `StdLexical` tokenizes by longest match.
None of the old operator-muncher / `shouldStop` / double-registration complexity applies — it
existed solely to support custom operators, which are gone.

## Parser: packrat combinator over the token list

The parser is a `PackratParsers` grammar with `type Elem = Token`, fed the `List[Token]` from
`IndentationLexical.scan`. Rationale:

- **Fast to build and change — the deciding reason.** A combinator grammar is *declarative*:
  adding a construct or reshaping the syntax is editing grammar productions, not restructuring
  a hand-written parser. During language design, when the grammar is still in flux, that
  iteration speed is the priority — it keeps effort on the language itself. This is the primary
  reason for the choice; the properties below are what make it safe to rely on.
- **Linear-time parsing.** Packrat memoizes each `(rule, position)`, eliminating the
  backtracking blow-up that makes plain combinator parsers slow. The residual cost is constant
  factors plus an `O(input × rules)` memo table — acceptable.
- **Left recursion is allowed** (Scala's packrat implements Warth's algorithm), so precedence
  grammars can be written naturally.
- **Immutable token stream.** Parsing a *materialized* `List[Token]` sidesteps packrat's
  classic gotcha — memoizing over a side-effecting lexer misbehaves when positions are
  revisited — because the tokens are fixed before parsing starts.

**The accepted cost — and its mitigation.** The real combinator weakness packrat does *not*
fix is **error-message quality and recovery**: ordered choice reports failures at confusing
positions, and there is no built-in multi-error recovery. We accept this and mitigate it where
it matters, with explicit `failure` / `err` messages and commit points on the productions
whose diagnostics users hit most (statement heads, type positions, block openers). The trade
buys a large amount of **development speed**, letting effort go to the language — types, memory
model, semantics — rather than parser plumbing.

## Source positions

Every AST node carries the position of the first token its rule consumed, and every typed node
inherits the position of the untyped node it came from — so a diagnostic from any pass names the
file, quotes the line, and puts a caret under the column:

```
error: 'b' of 'add' is int, but string was given
 --> hello.sysl:7:14
  |
7 | print(add(x, "two"))
  |              ^
```

Three decisions make this cheap enough to apply everywhere:

- **A position is a mutable field (`Positioned`), not a constructor parameter.** Putting it in a
  case class's signature would put it into `equals`, and every structural comparison — which is
  how the parser is tested — would then have to spell out positions it does not care about.
  Keeping it out leaves `Binary("+", a, b) == Binary("+", a, b)` true regardless of what file
  either side was parsed from.
- **`setPos` keeps the first position it is given.** Parsing builds bottom-up, so the innermost
  rule to claim a node is the most specific one that could: a `.field` tail records the dot, and
  the enclosing expression rule, which would have recorded the start of the whole expression,
  leaves it alone. An outer rule wrapping an inner one therefore costs nothing.
- **The analyzer keeps a cursor rather than threading a position through every check.** Each
  recursive entry point sets the cursor to the node it is about to work on and restores it after,
  so a rule that fires *after* its children are done — comparing two branch types, say — still
  points at the construct that raised it rather than at whatever was visited last.

A `Pos` holds the `Source` it points into, which is what keeps the prelude's positions and the
user's file's from being confused for one another, and what lets a diagnostic render itself
without any pass having to carry the source text alongside the message.

## Reporting every error, not just the first

A compilation reports **every mistake it can find**, rendered in source order and separated by a
blank line. The analyzer still signals an error by throwing — 150-odd call sites say `err(…)` and
mean it — but the throw is caught at a **recovery region** boundary, the error is recorded, and
the walk resumes at the next region. The regions are the boundaries a well-formed program can
resynchronize on:

- each **declaration** as it is hoisted, so a bad struct does not hide the next one;
- each **function body**, so a bad function does not hide the next one;
- each **statement**, so a bad line does not hide the rest of its block.

The parser is not among them: a combinator grammar has no recovery, so a syntax error still stops
at the first one. That is the known cost recorded above.

**Consequences are not errors.** Reporting every mistake is only useful if what gets reported is
mistakes rather than their fallout, so two mechanisms keep the count honest:

- **`Type.Unknown` and `Poisoned`.** A declaration that failed still binds its name, at
  `Type.Unknown`. Any expression that comes out with that type raises `Poisoned`, which abandons
  its statement exactly as an error does but records nothing — the mistake was reported where it
  was made. So `var a = nope` followed by five uses of `a` is one error, not six. The same applies
  to a parameter or a struct field whose type did not resolve: the function keeps its arity and
  the struct keeps its shape, so calls and field reads stay quiet instead of each inventing a
  complaint of their own.
- **Duplicates are dropped.** The same message at the same place is one mistake however many
  times a pass arrives at it — a generic function instantiated at three types has one bad line,
  not three.

**A region that is abandoned leaves nothing behind.** A statement that failed part-way may have
opened a scope or entered a loop it never closed, and the resolver may have marked a type as
in-progress; both are wound back. This is not tidiness — leaving them made the analyzer *more*
permissive after an error than before one, and made the next mention of a half-built type report
that it contained itself.

## Reversibility (why this is not a lock-in)

The seam is the **`List[Token]`**. If diagnostics or performance ever justify it, a
hand-written recursive-descent / Pratt parser can consume the exact same token list with no
change to the lexer. So this commits the lexer but keeps the parser replaceable behind a
stable token interface — consistent with "get it right, even if it means redoing" without
paying for a hand-written parser now.

## Convention note

Design docs split by kind: **numbered** docs (`00-…`) are language-spec chapters; **named**
docs (`principles.md`, `front-end.md`) are standing rules and compiler-architecture decisions.
