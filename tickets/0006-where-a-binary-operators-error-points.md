Decide where a binary operator's diagnostic should point

An operator mismatch names the operator and points at the start of the expression:

```sysl
print(1 + "x")
```

```
error: '+' needs matching types, got int and string
 --> a.sysl:1:7
  |
1 | print(1 + "x")
  |       ^
```

The caret is under `1`. The message is about `+`, and the operand that is wrong is `"x"` — so the
one thing it points at is the one thing nobody is complaining about.

## Why this is a decision and not a bug

**An expression's position is its start, deliberately.** `SyslParserBase.at` sets a node's position
to where its parse *began*, and every precedence level is wrapped in it
(`at(chainl1(additive, binOp("+") | binOp("-")))`). `DiagnosticTests` encodes the consequences on
purpose — one of its cases exists to keep an `if` reporting at the column of its keyword rather than
at an operand deep inside it.

So moving the caret to the operator is a change to that convention, and it lands on every binary
operator in the language at once. That is worth deciding rather than doing in passing, which is why
it was left alone while the *member* diagnostics were fixed in the same session (those were
unambiguous: the message named an identifier and the caret sat on the punctuation beside it).

## The three candidates

- **Keep the start.** One rule, already implemented, and it reads as "this expression is wrong".
  The cost is that the caret and the message disagree about what the subject is.
- **Point at the operator.** Matches the message exactly. Costs: `binOp` must capture `here` at the
  operator and the enclosing `at(...)` must stop overwriting it, at every precedence level, and
  whatever `DiagnosticTests` pins would have to be re-read case by case.
- **Point at the offending operand.** The most informative — `"x"` is the thing to change — and the
  hardest, since which operand is at fault is known to the analyzer and not to the parser. It would
  mean the *analyzer* choosing the position, which is what the index fix in this session did locally
  (`at(index.pos)`), so there is precedent for doing it per-diagnostic rather than per-node.

The third is probably right for this diagnostic specifically, and the second is probably wrong as a
blanket rule — but a spot check of the other operator messages should come before either.

`DiagnosticPositionTests` pins the current behaviour, so whichever way this goes, the change will
show up as that test failing rather than as a silent drift.
