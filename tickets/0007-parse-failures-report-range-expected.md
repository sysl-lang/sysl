A malformed expression reports `'..' expected`, at the first statement of the block

Nearly every malformed expression in a statement body produces one message, at one position, and both
are wrong. The position is the damaging half: it is the **start of the statement list**, so the
further into a function the mistake is, the further the caret is from it.

Found on 0.0.23 while writing `sqlite-repl`. Two of these cost real time, because the caret named a
line that was perfectly good.

## The position

```sysl
main()
    val a = 1

    val b = 2

    print(a b)
```

```
error: '..' expected
 --> a.sysl:2:5
  |
2 |     val a = 1
  |     ^
```

The mistake is `print(a b)` on line 6. The caret is on line 2, on a statement with nothing wrong with
it.

## The message

Four unrelated mistakes, one message, one position. None of them involves a range:

| source | reported |
|---|---|
| `print(1 2)` | `'..' expected` at the block start |
| `val x = 1 2` | `'..' expected` at the block start |
| `print(1 +)` | `'..' expected` at the block start |
| `print(b[0] == b';')` | `'..' expected` at the block start |

That they are *identical* is the clearest evidence the diagnostic is a fallback rather than an
answer. The last one is somebody reaching for a byte literal the language does not have, told about
an operator they did not write.

It fires outside statement bodies too, where the caret lands on the right line but the message still
misleads:

```sysl
const A: string =
    "x"
```

```
error: '..' expected
 --> a.sysl:1:18
  |
1 | const A: string =
  |                  ^
```

The actual rule is that a `const` wants its value on the same line, and nothing says so.

## Where to look

The same mechanism as the parser-`err` placement rule in `CLAUDE.md`: scala-parser-combinators ranks
two failures by position and `Failure.append` keeps whichever `next.pos` is greater. If the range
form is an alternative that gets furthest before failing — or is simply last and nothing outranks it
— its message is what survives whatever was typed.

The reported position looking like the block start suggests the failure is being surfaced against the
statement-list parser's own start rather than against the furthest point any alternative reached. So
there are plausibly two separate defects here: which failure wins, and which position is reported for
it. Worth confirming they are separate before changing either.

## Not the same as 0006

`0006` is a *decision* about where a binary operator's diagnostic should point, given that an
expression's position is deliberately its start. This is a parse-time fallback whose message is about
a construct the source does not contain, and no convention is defending it.

## Why it is worth doing

It is what a newcomer meets on their first typo. A wrong message can be worked around; a caret
pointing at correct code sends them editing a line that was fine. `sqlite-repl` and `guide/` exist to
be read by people deciding whether they like the language, and this is the first thing that happens
when they mistype in one.

`DiagnosticPositionTests` pins positions, so a change here will show up as that test failing rather
than as silent drift.
