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

## Reversibility (why this is not a lock-in)

The seam is the **`List[Token]`**. If diagnostics or performance ever justify it, a
hand-written recursive-descent / Pratt parser can consume the exact same token list with no
change to the lexer. So this commits the lexer but keeps the parser replaceable behind a
stable token interface — consistent with "get it right, even if it means redoing" without
paying for a hand-written parser now.

## Convention note

Design docs split by kind: **numbered** docs (`00-…`) are language-spec chapters; **named**
docs (`principles.md`, `front-end.md`) are standing rules and compiler-architecture decisions.
