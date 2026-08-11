# Reserved Identifiers

**Status:** decided and built. A short chapter, because the feature is small — but it settles a
question that had been answered by a workaround, and it reserves a space the language will keep
spending.

## The gap this closes

**A callee could not see where it was called from.** That is the whole of what was missing, and it
is worth stating in those terms rather than as "sysl has no `__LINE__`", because the second sounds
like a missing constant and the first is a missing capability.

The cost was visible in the standard module. `library/sysl/check.sysl` declared

```
assert(cond: bool, msg: string)
```

with the message **required**, and said why: *"The message is required rather than defaulted because
the condition's source is not available to print — a failure that says only 'assertion failed' sends
its reader looking for which one."* That reasoning was correct and the rule it produced was a
workaround wearing a principle's clothes. A checking function that can name its caller's line does
not need to be told in prose what it is checking.

## The rule: a shape, not a list

**An identifier that begins and ends with `__`, holding nothing but capitals and underscores in
between, belongs to the language.** No declaration may take one — not a function, a type, a `val`, a
field, a parameter, a type parameter, or a local.

```
__FILE__        reserved
__LINE__        reserved
__MY_THING__    reserved — and not a built-in, which is a different thing from being available
____            reserved — the middle may be empty
___             not reserved — the two markers may not overlap, so four characters is the shortest
__file__        not reserved — the middle is not capitals
__FILE_         not reserved — one underscore short, and therefore an ordinary name
```

**Reserving the shape rather than the six names is the point of the feature, and it is the half that
had to land first.** A language that reserves only what it currently uses breaks somebody every time
it grows: the release that adds `__COLUMN__` breaks the program that declared one. Reserving the
space up front makes every future addition non-breaking forever, and costs one check at each place a
name is bound. The built-ins are what presently occupies the space; the space is the feature.

It is C's rule made honest. C reserves this territory too — and diagnoses nothing in it, so a
program may declare `__FILE_` or `__foo__` and collide with a future implementation silently. Here
the shape is either yours or the language's, and taking the language's is refused where it is
written.

It is also the same decision `targets.md` made for the `#if` vocabulary: a closed set, and **a name
outside it is an error rather than false**. Silently meaning nothing is the failure mode worth
refusing, in both places, for the same reason.

## They are not keywords

`__FILE__` lexes as an ordinary identifier and is resolved by the analyzer, exactly as `int`,
`usize` and `f32` are — `SyslLexical` calls those *"predeclared identifiers resolved by the analyzer,
as in Go and Swift"*, and this is one more of them.

That is not an implementation detail; it is what keeps the space cheap to extend. A reserved *word*
here owes three things in two repositories — the lexer's `reserved` set, the reserved-word table on
the site's `lexical.md` whose prose states a **count**, and the highlighting grammar that
`GrammarTests` reconciles against that set. A predeclared identifier owes none of them, so a seventh
built-in is one entry in one table.

## Why not a preprocessor

Unity, the C test framework this was first wanted for, gets its line numbers from `__LINE__` inside a
macro. The obvious inference is that sysl needs macros. It does not, and the languages that solved
this without a preprocessor are the majority:

| language | mechanism |
|---|---|
| **Swift** | `#file` / `#line` as ordinary expressions, used as **default arguments** — XCTest is built on it |
| **D** | `__FILE__` / `__LINE__` as default arguments |
| **C#** | `[CallerLineNumber]` on an optional parameter |
| **Zig** | `@src()`, a builtin returning a struct |
| **Rust** | `#[track_caller]` — a calling-convention feature, *not* the macro half of `assert!` |

Nobody who had the choice reached for macros. Macros are a whole subsystem — hygiene, expansion
order, and above all diagnostics *inside* an expansion, which every macro language is worse at than
its own core language — bought for a problem that two intrinsics solve. And two of this language's
existing decisions already argue against them: `testing.md` says the `@` attribute set is *"not a
general extension mechanism"*, and `targets.md` says of `#if` that there is *"no `#define`, nothing a
project can add"*. A macro system contradicts both on grounds those chapters chose deliberately.

The honest limit is worth recording too: **macros would not have solved the problem that prompted
this either.** Enumerating a module's tests to build a runner table is not a local transformation,
which is exactly why Unity ships an external Ruby script rather than a macro. That want, if it
returns, is compile-time reflection over declarations, and it is a much larger conversation.

## Why not `#file`

Swift's spelling is the prettiest and was very nearly taken. It was dropped because **`#` already
means directive** here (`targets.md`), and although the collision is not mechanical — `Conditional`
only ever looks at column 1, and its own documentation says the sigil rather than the margin is what
separates a directive from an annotation — a reader seeing `#` would have to work out which of two
unrelated things it was. The margin rule exists precisely so that a directive is *visibly* not part
of the code's shape, and overloading the sigil cuts against the reason that rule was made.

`@` was unavailable for the same kind of reason: it opens an annotation, and `SyslLexical` notes that
nothing in the expression grammar spells one, which is what lets a line beginning with `@` be read
without lookahead.

`__NAME__` collides with neither, needs no lexical support at all, and carries its own warning label:
it does not look like ordinary sysl, and it should not, because it is not.

## The six

| built-in | type | value |
|---|---|---|
| `__FILE__` | `string` | the file's name, as a diagnostic prints it |
| `__LINE__` | integer | 1-based line |
| `__COLUMN__` | integer | 1-based column, in the **file** |
| `__FUNCTION__` | `string` | the enclosing function's name, as written |
| `__DATE__` | `string` | the build date, `Mmm dd yyyy` |
| `__TIME__` | `string` | the build time, `hh:mm:ss` |

`__LINE__` and `__COLUMN__` are analyzed as ordinary integer literals, so each takes the type its
context asks for and is range-checked with it: a parameter declared `i32` receives an `i32`, and one
declared `u8` is told where a line number will not fit rather than wrapping.

**`__FUNCTION__` is empty outside any body, and that is an answer rather than an error.** A module's
`val` and `var` storage is filled before any function runs, so there is genuinely no function to
name. Refusing it there would also refuse a *default* of `__FUNCTION__` — a default is analyzed once
at its declaration, where no caller exists yet, and that is the single place it is most worth
writing. In a **closure** it names the function the closure is written in, since a closure has no
name a reader chose.

Getting this wrong was the one real bug in the feature: left uncleared, the field held whichever
function the definition-time pass of `14 §4` had walked last, so a module `val`'s `__FUNCTION__`
silently reported an unrelated library function. A stale answer is worse than either an error or an
empty string, and it is the failure mode a "current thing" variable invites.

`__COLUMN__` reports the column of the **file**, which is not the column the lexer counted: a
literate program's code sits four columns in, and `Source.columnOffset` is added back. `Pos.location`
does the same for the same reason, and the two have to agree — a diagnostic and a program that
disagreed about one place would be worse than either alone.

`__FILE__` is the file **as the compiler was told about it** — the same string a diagnostic prints,
which is `Source.name`, which is the path the driver was given. So it is emphatically *not*
guaranteed to be short: `sysl build /Users/me/proj/blink.sysl` puts that whole path into `__FILE__`,
and therefore into the binary, once per use.

**That is a known sharp edge rather than a settled answer**, and it is the one place this feature is
behind Swift, which split `#file` from `#fileID` and `#filePath` for exactly this reason: absolute
paths bloat binaries and leak build-machine paths into shipped code. The pressure is sharper here
than it was there, because the first customer is a checking function called from hundreds of sites in
an image that may have 64K of flash. See *Open*.

## How the caller's line falls out of a default

**Nothing here knows what a caller is.** These are expressions that report where they are written,
and the call-site behaviour is entirely inherited from what a default argument already was:

> **A default is an expression and it is evaluated at the call**, standing exactly where the argument
> would have been written. — `12 §2a`

So

```
check(cond: bool, file: string = __FILE__, line: int = __LINE__)
```

reports the *caller's* file and line, because the default stands where the caller's argument would
have. That is the entire mechanism, and it is why sysl needs no `#[track_caller]`: Rust bolted a
call-site feature onto the call path because its `assert!` is a macro; sysl composes two things it
already had.

**What this made explicit is a distinction `12 §2a` had left implicit.** That section says a default
is *analyzed* in the declaration's scope, not the caller's — which is about **name resolution** and
remains exactly true; a default naming `n` finds the declaration's `n` and never a caller's. It never
separately said where a default *is*, because until a built-in could ask, nothing needed the answer.
The two are now stated apart: **analyzed in the declaration's scope, positioned at the call site.**

For a default that itself calls something and leaves *that* argument out, the **outermost** call wins.
The alternative would report a position inside the first default — a line in the declaration's file,
which is the answer this exists to avoid giving.

## `__DATE__` and `__TIME__` break reproducible builds

They are the canonical reason a build is not reproducible; distributions patch C's out. They are here
because a firmware build stamp answers *"what is actually on this board"*, which is a question asked
constantly on an embedded target and otherwise unanswerable — but a program that uses one has given
up bit-identical rebuilds, and that is worth knowing before rather than after.

Both are **UTC**. The compiler builds on three platforms and `java.time` is whole on exactly one of
them, so a local answer would be right on the JVM and a guess elsewhere. A stamp that means the same
thing wherever the build ran is also the more useful of the two, since it is read to identify a
build.

One stamp is taken per compilation, so two `__TIME__`s in one program cannot disagree with each other
— which a per-use reading would allow across a second boundary, rarely, and therefore in a way nobody
would ever reproduce.

## Open

- **The argument's source text.** C# added `[CallerArgumentExpression]` so that `check(x > 3)` can
  report `x > 3` and not merely a line. It needs no macros either — the compiler has the text — and
  it is the natural next thing to want here. Not built.
- **A basename variant of `__FILE__`** — `__FILE_NAME__`, say. Today `__FILE__` is whatever path the
  driver was handed, so an absolutely-invoked build embeds absolute paths, once per use. That is
  fine on a hosted target and is a real cost on a small one, which is where this feature is headed.
  The reserved space means adding it later breaks nothing, which is exactly the property the shape
  rule was for — so this is a thing to do when it bites, not something to guess at now.
- **Digits in the shape.** The rule admits capitals and underscores only, so `__F16__` is an ordinary
  name. Nothing wants one yet, and widening the shape later is the direction that stays compatible.
