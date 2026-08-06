A type that was never imported reports as a failure to infer

Naming a type the file has not imported produces a diagnostic about *inference*, pointing at the
expression rather than at the name that is not in scope:

```sysl
f() -> Result[unit, IoError] = Ok(())
```

```
error: cannot infer the type argument 'E' of 'Ok' here — annotate the expected type
 --> unimported.sysl:1:32
  |
1 | f() -> Result[unit, IoError] = Ok(())
  |                                ^
```

`IoError` is `sysl.fs`'s and the file imported nothing, so the honest message is that the name is not
in scope, with the caret under `IoError`. What the reader gets instead sends them to annotate an
expression that is already as annotated as it can be — the return type says `Result[unit, IoError]`,
and adding the same words again does not help.

**It is not specific to `main` or to `Result`**, though that is where it was found (writing
`main() -> Result[unit, E]`, 2026-08-06): an ordinary function with an ordinary generic gives the
same message, so whatever resolves a written type in return position is treating an unresolvable name
as an unsolved type parameter rather than as an error of its own.

The likely shape of the fix is that resolution answers "unknown name" where today it answers
something inference then fails on — so the check belongs where the return type is resolved, before
anything is solved. Worth confirming that a *parameter* type behaves the same way before choosing
where it goes; the probe above only covers the return.

Low severity — the program does not compile either way, and the line it points at is the right line.
What it costs is the minute a reader spends looking for an inference problem that is not there.
