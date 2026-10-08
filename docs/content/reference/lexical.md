---
title: Lexical structure
summary: What the compiler reads before it parses anything — tokens, literals, and the layout rules that decide where a statement ends.
weight: 10
---

sysl is read in two passes that are worth keeping separate in your head. A **lexer** turns characters
into tokens and, because the language is indentation-sensitive, also inserts the tokens that open and
close blocks. Only then does a parser see anything. Most of the surprises in a layout-sensitive
language happen in the first pass, which is why it gets its own page.

## Source text

Source is **UTF-8**, and the encoding is not configurable. A non-ASCII character is legal in a
comment, a string literal, a character literal — and in an identifier (see below).

Line endings may be LF or CRLF. A file need not end in a newline.

## Literate source

**A file named `.lsysl` is a Markdown document, and the part of it indented four columns is the
program.** Everything else is prose and is not compiled. Which of the two a file is, its **name**
decides and nothing else: a `.sysl` file is never read this way whatever its indentation happens to
look like, and a `.lsysl` file with no prose in it is a `.sysl` file with four spaces down the left.

There is no other marker — no name on a block, no directive opening one, no way to say that a block
ends.

````text
# Halving

The interesting part of this program is that it explains itself. Everything at the
margin is prose, and the compiler never sees it.

    half(n: int) -> int = n / 2

A paragraph between two indented blocks does not end anything, so the program can be
explained a step at a time and picked up again where it left off.

    print(half(9))

What a reader should *not* run gets a fence instead of an indent:

~~~
half(n) = n / 2      // the wrong version, with no types
~~~

That block is an illustration and is not compiled.
````

The program that file holds is the two indented lines, and it is an ordinary sysl program:

```sysl
half(n: int) -> int = n / 2

print(half(9))
```

```output
4
```

This is Knuth's *WEB* with its most famous half deliberately left out. WEB let an author write the
program in the order that explains it and had a *tangler* put it back into the order the compiler
needs, which is the feature that made a `.web` file unreadable without its tools. Here the code
appears in the order it runs and the tangler only removes prose, so the file is legible as it sits —
by a reader, by a Markdown renderer, and by `grep`. What is kept is the part that pays: room for an
argument between two functions, in a place a comment cannot hold it.

### The four rules

**Four columns is the threshold**, which is Markdown's own, so what a renderer shows as a code block
is exactly what the compiler reads.

**Consecutive indented blocks are one block.** A paragraph between two of them ends nothing, so a
function body may be explained a step at a time: the prose dedents to column zero and the code
resumes at the indentation it left off at. Without this the format would only be good for examples,
which is the length at which it is not needed.

**A fenced block is an illustration.** Both ` ``` ` and `~~~` mark code that is to be *looked at*
rather than run — the wrong version beside the right one, a shell transcript, a fragment of C — and
none of it is compiled however it is indented. Code that runs is indented; code that is shown is
fenced.

**What is under a bullet is prose.** An indented block inside a list item belongs to that item, so a
list may hold examples without any of them entering the program. An executable block therefore never
sits directly under a list; a line of prose between them is what separates the two.

### Positions survive

**Prose is blanked, not removed.** The text handed to the lexer has exactly as many lines as the
file, and each line of program text is on the line it was written on — so every position the lexer
records is already a position in the `.lsysl` file. There is no mapping table, and no pass below the
parser knows that any of this happened.

The one coordinate that does move is the **column**, by the four that made the line code, and it is
added back where a position is *reported*. A diagnostic therefore names the line and column of the
file the reader has open.

### Two things Markdown would accept are refused

Both are the same failure — a program silently missing a piece of itself — and neither could be
diagnosed later, because what reaches the compiler afterwards is a program that is merely smaller
than the author's.

**A tab in the indentation.** A tab is as wide as whatever is displaying it, so a tab-indented line
is program text in one editor and prose in another. It is refused *before* the four-column test
rather than failing it, since failing it is the silent outcome — the line becomes prose, and a
function quietly loses a statement.

```text
error: a tab in the indentation of a literate file — what makes a line program text is four columns of indent, and a tab is as wide as whatever happens to be displaying it, so this line is code in one editor and prose in another
```

**A fence that is never closed.** Markdown runs an unclosed fence to the end of the document, which
for a document is harmless. Here it turns every declaration below it into an illustration, and the
diagnostic the author would otherwise get is about something incomplete much further down — their
missing half never enters the story. It is refused at the line that opened it, which is the line to
go and look at.

```text
error: this fence is never closed, so everything below it is an illustration and none of it is compiled — close it, or indent the lines that are meant to run
```

### `tangle` and `weave`

The two halves of a literate system are named for what they do to the source, and a build has been
tangling all along — that is how a `.lsysl` file compiles at all. What the commands add is a way to
ask for each half by name:

- **`sysl tangle`** prints the program with the prose stripped. It answers the question a misbehaving
  literate file always raises — *what did the compiler actually read* — which a block indented that
  should not have been, or a fence that swallowed a function, otherwise leaves unanswerable.
- **`sysl weave`** renders the document as HTML. A `.lsysl` file is already Markdown, so the one
  thing a renderer would otherwise lose is the highlighting: an indented block carries no language,
  so nothing can colour it and nothing scanning for the code can find it. `weave` tells the renderer
  that an indented block is sysl and passes the source through verbatim.

**`tangle` reads the file the way a build does**, so a file the compiler refuses — a tab in an
indent, a fence that is never closed — is refused by it with the same diagnostic, and the program it
prints is the program a build compiles.

*`weave` is not in this compiler yet — it answers "is not a command this compiler can carry out
yet".*

Both are covered under the [CLI](/getting-started/cli/). Weaving asks for no target, no standard
module and no library, which is what makes a package's prose worth reading on a machine that could
not build it.

## Comments

```sysl
// A line comment runs to the end of the line.

/* A block comment
   may span lines. */

var x = 1 /* and may sit inside a line */ + 2

print(x)
```

```output
3
```

### A block comment **nests**

`/*` inside a block comment opens another one, and it takes a matching `*/` to close. That is what
lets a block of code with comments in it be commented out, which is the reason most modern languages
do it and C does not:

```sysl
/* commented out, /* including this */ and on to here */
print(1)
```

```output
1
```

**What it costs is that `/*` and `*/` mean something inside a comment, and backticks do not protect
them.** The lexer has no idea it is reading Markdown. So a comment about anything whose text contains
those two characters — a glob pattern above all — is a comment that does not end where it looks like
it does, and neither diagnostic mentions comments:

```sysl
/* a glob pattern: src/** */
print(1)
```

```error
unclosed comment
```

That one opened a second comment at `c/*` and closed only that, so the outer ran to the end of the
file. The other direction is worse, because the caret lands on prose:

```sysl
/** matches "**/*.sysl" at any depth */
f() -> int = 1

print(f())
```

```error
unterminated string literal
```

There the `*/` inside `**/` closed the comment early, and everything after it was read as code — so
the complaint is about a quote in what the writer believed was a sentence.

**Write such a comment with `//`**, where neither sequence means anything:

```sysl
// matches "**/*.sysl" at any depth
f() -> int = 1

print(f())
```

```output
1
```

That is a real constraint on a doc comment rather than a curiosity: `/** … */` is the only form
`sysl doc` reads, so a module whose subject is globs documents its grammar in `//` lines above the
declarations rather than in the doc comments on them.

## Documentation comments

A comment opening `/**` is a **doc comment**: prose about the declaration below it, which `sysl doc`
reads to generate an API reference.

```sysl
/** Answers the first element of a slice.
 *
 * Traps on an empty slice, as any other subscript does.
 *
 * @param xs the slice to read
 * @return the element at index 0
 */
first(xs: []const int) -> int = xs[0]

print(first([7, 8, 9]))
```

```output
7
```

**The delimiter is the whole of what makes one.** An implementation note above a declaration stays an
implementation note by being written `//`, so nothing has to guess at intent — and a `/* … */` block
comment is not a doc comment either. `/**/` is an empty block comment rather than a doc comment
opening.

A `*` down the left margin is optional; where it is written, it and one following space are removed,
so a fenced block inside a doc comment keeps its own indentation.

**A doc comment is trivia and cannot change what a program means.** Nothing in the analyzer reads
one, no lowering sees one, and a program compiles identically with every doc comment in it deleted.

### What a doc comment attaches to

The declaration below it. **Annotations and `//` lines may sit between** — which is the ordinary
shape, since `@test` and `@export` are written directly above what they annotate:

```sysl
/** Answers twice its argument. */
@export("twice")
// An implementation note, which is not part of the documentation.
twice(n: int) -> int = n * 2

print(twice(21))
```

```output
42
```

**A blank line ends the association.** That is what lets a file open with prose belonging to the
module rather than to whatever happens to be declared first:

```sysl
module demo.text

/** Text manipulation over UTF-8.
 *
 * This comment documents the module, because the blank line below stops the declaration adopting it.
 */

/** Answers the length in bytes. */
byte_length(s: []const u8) -> usize = s.len
```

### Tags

A tag is `@name` **at the start of a line**, and nowhere else — so an `@` inside a sentence opens
nothing. Its text runs to the next tag or the end of the comment, and a blank line does not end it,
because a tag's text is prose and prose has paragraphs.

| Tag | Takes | Means |
|---|---|---|
| `@param` | a parameter's name | what that argument is |
| `@tparam` | a type parameter's name | what that type stands for |
| `@return` | — | what the result is |
| `@see` | — | something to read next |
| `@note` | — | an aside |
| `@example` | — | a worked use |
| `@since` | — | the version it appeared in |
| `@deprecated` | — | what to use instead |

**There is no `@throws`**, and its absence is not an oversight: sysl has no exceptions, and what a
fallible function answers with is the `E` of its `Result[T, E]` — which `@return` describes, because
it is part of the return type rather than a second channel out of the function.

### A tag naming the signature is checked; one that does not is prose

`@param` and `@tparam` are the two that can go stale, because a rename leaves the paragraph
describing something that is no longer there. So one naming a parameter the declaration does not have
is refused:

```sysl
/** Adds two numbers.
 *
 * @param a the first
 * @param c the third
 */
add(a: int, b: int) -> int = a + b

print(add(1, 2))
```

```error
error: 'c' is not a parameter of this declaration — it has a, b
```

**The check runs in one direction only, and that is deliberate.** A parameter with **no** `@param` is
not an error and must never become one: documentation is optional, a partial doc comment is better
than none, and a rule requiring the full set is what makes people write `@param n the n` to silence
it. Check what is written, never require what is absent — which is also what lets a library be
converted a file at a time.

A bare `@param` with no name after it is its own mistake and is named as one.

**`@param self` is admitted on a member.** The receiver is not an entry in the parameter list as far
as the analyzer is concerned, but it is spelled in the declaration, and refusing the one tag a reader
is most likely to reach for on a method would be pedantry about a real part of the signature. It is
admitted rather than required, like every other parameter.

### An unrecognised `@name` is prose

`@` is sysl's annotation sigil — `@test`, `@requires`, `@export`, `@no_alloc` — so a doc comment
discussing one is discussing the language rather than tagging itself. The vocabulary above is closed
and anything outside it is body text.

What that costs is that a typo is silent: `@parm` is a paragraph beginning with a word, not a tag the
compiler will complain about.

### The first sentence is the summary

The prose before the first tag is the **body**, and its first sentence is the **summary** — which is
what an index with one column to spend shows.

A sentence ends at `.`, `!` or `?` **followed by a space or the end of the text**, so a dotted module
name mid-sentence does not end one: *"Reads a `sysl.text` value and answers it."* is a single
sentence. Where the body ends no sentence at all, the whole of it is the summary.

## Identifiers

An identifier starts with a letter or `_` and continues with letters, digits, combining marks, or
`_`. **A letter is whatever Unicode calls one**, so a name may be written in any script:

```sysl
val año = 2026
área(ancho: real, altura: real) -> real = ancho * altura

struct Círculo
    radio: real

print(año, área(3.0, 4.0), Círculo(2.0).radio)
```
```output
2026 12 2
```

This is a rule about *reading* rather than about internationalization. A language whose identifiers
are ASCII asks everybody who does not think in English to transliterate their own vocabulary, and the
words that suffer are the domain ones — the names a reader most needs to recognize. Go, Java, Scala
and C# all draw the line here.

A **digit** is whatever Unicode calls one too, but only *inside* a name: `caf٣` is an identifier and
`٣` is not, because a name may not begin with a digit in any script. The number *literal* grammar is
deliberately unmoved — a literal is a value the arithmetic has to read, and `٣` is not one.

A **combining mark** — an accent, a vowel sign, anything Unicode files under category M — continues
a name the way a letter does, so a name typed on a keyboard that composes `é` as an `e` and a
combining acute is one identifier rather than an illegal character. It cannot *begin* a name, having
nothing to combine with, and one written there is refused as exactly that.

**Two spellings that look identical are two names**, and this is the one edge worth stating outright:
`café` written with a precomposed `é` and `café` written with an `e` and a combining acute are
different identifiers — both legal, and a program may declare the two side by side without a
collision. sysl applies no normalization, which is a deliberate refusal of UAX #31's
rule — a declaration is what a caller has to spell, and folding them would make a name that looks
right refuse to resolve for a reason nothing on the screen shows.

**A letter above the Basic Multilingual Plane is refused**, which is the other edge and is not
predictable from the rule above. Every living script's letters are inside the BMP, CJK Unified
Ideographs included; what is outside is the historic scripts and the CJK extension planes.

```sysl
val 𠮷 = 1
print(𠮷)
```
```error
illegal character
```

An **operator-shaped** character is not a letter and never was: `×` and `÷` are refused the same way.

Case is significant. Capitalization is not enforced anywhere, but the convention the standard library
and this documentation follow is `PascalCase` for types and traits, `snake_case` for everything else —
and the syntax highlighting treats a capitalized name as a type, so following it makes code read
correctly on a page.

### Quoted identifiers

**A name written between backticks may be anything the rule above refuses** — a reserved word, or a
name carrying spaces and punctuation. That set is narrower than it looks: since a letter is Unicode's
letter, `café` and `名前` need no backticks, and what is left for the quoted form is punctuation,
spaces, and the words the grammar has taken for itself.

```sysl
var `item count` = 3
val `match`: int = 5

struct `Grid Cell`
    `row index`: int
end `Grid Cell`
```

Written bare, a reserved word in a name's place is refused at the word, and the refusal names the
quoted form:

```sysl
match() -> int = 1

print(1)
```
```error
'match' is a reserved word, so it cannot stand as a function's name — write it '`match`'
```

It is a name and nothing more: `` `match` `` *is* the identifier `match`. A contextual word written
this way is an ordinary name rather than the word — `` `end` `` names something, and does not close a
block.

That holds for a name the ordinary grammar can also write, so the two spellings are one name:

```sysl
var café = 3
`café` += 1
print(café)
```
```output
4
```

Two characters may not appear inside, and a newline ends the search rather than the name, so an
unclosed backtick is reported on the line that opened it. A **backtick** cannot appear at all, since
there are no escapes inside. A **`.`** cannot either: a qualified name is carried as a dotted string,
so a dot inside one part could not be told from the separator between two.

A **module path** is written with plain names only, for the same reason — `module my.mod`, never
``module `my mod` ``. A module names a directory, and its parts are what the file system holds.

The form does a second job in a `match` arm, where a backticked name **references** a variable
already declared rather than binding a new one.

## Reserved words

Forty words are reserved and may not be used as identifiers:

| | | | | |
|---|---|---|---|---|
| `alignof` | `as` | `break` | `const` | `continue` |
| `defer` | `do` | `elif` | `else` | `ensure` |
| `enum` | `extern` | `false` | `for` | `if` |
| `impl` | `import` | `in` | `loop` | `match` |
| `module` | `null` | `offsetof` | `override` | `private` |
| `ref` | `require` | `return` | `self` | `sizeof` |
| `static` | `struct` | `then` | `trait` | `true` |
| `type` | `val` | `var` | `weak` | `while` |

**`alloc`, `no` and `requires` are *not* among them**, though they read like keywords where they
appear. A capability is written as an [attribute](/reference/attributes/) — `@no_alloc`,
`@requires(os)` — and an attribute's words are matched as ordinary identifiers, which is the point of
spelling capabilities that way: no word is spent, so an allocator may still call its function
`alloc`.

**Type names are deliberately not among them.** `int`, `usize`, `f32`, `bool`, `string` and the rest
are *predeclared identifiers* that the analyzer resolves, exactly as in Go and Swift. That is what
lets the `iN` / `uN` / `fN` families stay open: `u12` and `i5` are types you may write without the
lexer having heard of them, and no list of widths has to be maintained anywhere.

A few words are **contextual** — special only where the grammar expects one, and ordinary identifiers
everywhere else: `is`, `not`, `end`, `become`, `opaque`, `derives`, `invariant`, `new`, `set`, `some`,
`with`, `within`, `where`, [`async` and `await`](/reference/async/#async-and-await-are-contextual),
and the `c` of a [`c const` or `c type`](/reference/ffi/) block. You may name a variable `where`; you
may not name one `while`.

**A word is contextual rather than reserved when it can be**, and the trade is the same every time: a
reserved word is spent out of every program's namespace for the sake of one line apiece.
[`become`](/reference/declarations/) needs no reservation at all, because two identifiers in a row
are not otherwise a statement — so a `become` of a function called `become` reads as exactly what it
is. `async` is the same case, being a keyword only where a declaration follows it, and `await` only
where an operand does.

`set` is read only where a member declaration begins, which is what keeps the word available for
everything else it is wanted for — a container, a local, a method:

```sysl
struct Cell
    set: int
    get -> int = self.set

var set = Cell(3)

print(set.get)
```

```output
3
```

The last of those is the clearest case for why the language spends so few words. `c` is the most
common one-letter name in code that handles characters, and what keeps it available is the keyword
that follows it: nothing else in sysl puts one after a name, so each pair can only be the one thing,
and the word costs nobody anything.

## Reserved identifiers

**An identifier that begins and ends with `__`, holding only capitals and underscores in between,
belongs to the language.** Nothing may declare one — not a function, a type, a `val`, a field, a
parameter, a type parameter, or a local.

```
__FILE__        reserved
__MY_THING__    reserved — and not a built-in, which is a different thing from being available
____            reserved — the middle may be empty
___             not reserved — the markers may not overlap, so four characters is the shortest
__file__        not reserved — the middle is not capitals
__FILE_         not reserved — one underscore short, and so an ordinary name
```

The shape is reserved rather than the names in it, which is what makes every future addition
non-breaking: a release that adds a new built-in cannot collide with a name you already declared,
because the shape was never yours to declare. C reserves the same territory and diagnoses nothing in
it; here taking it is refused where it is written.

These are **predeclared identifiers**, like the type names above — not reserved words, and absent from
the table.

The restriction is on sysl names only. The string an `extern` links to is untouched, which matters
because a C library's own symbols live in exactly this space:

```sysl
private extern "__errno_location" errno_location() -> *i32
```

### The built-ins

| identifier | type | value |
|---|---|---|
| `__FILE__` | `string` | the file's name, as a diagnostic prints it |
| `__LINE__` | integer | 1-based line |
| `__COLUMN__` | integer | 1-based column |
| `__FUNCTION__` | `string` | the enclosing function's name |
| `__DATE__` | `string` | build date, `Mmm dd yyyy` (UTC) |
| `__TIME__` | `string` | build time, `hh:mm:ss` (UTC) |
| `__NAME__` | `string` | the `name` in the `package.hocon` of the package the file belongs to — as a default, the caller's |
| `__VERSION__` | `string` | the `version` in the `package.hocon` of the package the file belongs to — as a default, the caller's |

`__LINE__` and `__COLUMN__` are ordinary integer literals, so each takes the type its context asks
for and is range-checked with it.

**A built-in written as a default argument reports the caller.** A default is evaluated at the call,
standing where the argument would have been written, so this needs no special mechanism:

```sysl
where(line: int = __LINE__) -> int = line

print(where())
print(where())
```

```output
3
4
```

That is how the standard library's `assert` names the line it failed on, and why its message is
optional. Where a default fills another default, the outermost call is the one reported.

`__DATE__` and `__TIME__` make a build non-reproducible, in the way C's do. They are worth having for
a firmware build stamp and worth not reaching for otherwise.

### `__VERSION__` is the package's version

**`__VERSION__` is the `version` the [manifest](/reference/packages/#what-a-project-is-called)
states, so `package.hocon` is the one place a program's version is written.** A `--version` flag or an
About screen reads it rather than keeping a `const VERSION = "…"` of its own that has to be
remembered at every release. In a package whose manifest says `version = "1.4.0"`, this prints
`mytool 1.4.0` — and a block on this page is a lone file with no manifest beside it, so here it is
refused, for the reason the last paragraph of this section gives:

```sysl
print("mytool ", __VERSION__)
```

```error
'__VERSION__' is the 'version' in package.hocon, and this file is not part of a package
```

It is a string literal like `__FILE__`, folded where it is written, so a `const` or a module-level
`val` may be initialized with it.

**It belongs to the package whose file it is written in.** A library's `__VERSION__` is the library's
own version wherever it is called from, never its consumer's.

**Except as a default argument, where it reports the caller — exactly as `__FILE__` and `__LINE__`
do.** A default stands where the argument would have been written, so a library's
`stamp(v: string = __VERSION__)` answers the version of whichever package the call to `stamp()` sits
in: the program's when the program calls it, a second library's when that library does, and the
library's own only when it calls itself. That is what lets a library offer a `--version` helper that
reports the program using it rather than itself.

**A file that belongs to no package has no version to read**, so `__VERSION__` is refused there — a
lone `sysl run tool.sysl` with no `package.hocon` beside it, or a manifest with no `version` key. The
refusal names which: *"'__VERSION__' is the 'version' in package.hocon, and this file is not part of a
package"*, or *"… and …/package.hocon declares none"*.

### `__NAME__` is the package's name

**`__NAME__` is the `name` the [manifest](/reference/packages/#what-a-project-is-called) states, so a
program can name itself without writing its name a second time.** It is `__VERSION__`'s twin: the
same manifest, read the same way, with the same rules. The motivating use is a log tag: a logging
function that defaults its tag to `__NAME__` tags every message with the name of the package that
called it, because a default reports the caller. In a package whose manifest says
`name = "skitter-app"`, this prints `[ skitter-app ] started` — and a block on this page is a lone
file with no manifest beside it, so here it is refused, for the reason the last paragraph of this
section gives:

```sysl
logcat(msg: string, tag: string = __NAME__)
    print("[", tag, "] ", msg)

logcat("started")
```

```error
'__NAME__' is the 'name' in package.hocon, and this file is not part of a package — no package.hocon stands at the root of its tree
```

It is a string literal, folded where it is written, so a `const` or a module-level `val` may be
initialized with it.

**It belongs to the package whose file it is written in, except as a default argument.** A library's
`__NAME__` is the library's own name wherever it is called from; written as a default, it is the name
of whichever package the call sits in — the program's when the program calls it, a second library's
when that library does. That is the rule `__VERSION__` and `__FILE__` follow, for the same reason.

**A file that belongs to no package has no name to read**, so `__NAME__` is refused there — a lone
`sysl run tool.sysl` with no `package.hocon` beside it, or a manifest with no `name` key. The refusal
lands where `__NAME__` is read, so a `const` initialized with it is refused at the `const`:

```sysl
const TAG: string = __NAME__
print(TAG)
```

```error
'__NAME__' is the 'name' in package.hocon, and this file is not part of a package
```

A manifest with no `name` key is refused with *"'__NAME__' is the 'name' in package.hocon, and
…/package.hocon declares none"* instead.

A manifest may state either field without the other: one with a `name` and no `version` answers
`__NAME__` and refuses `__VERSION__`.

## Literals

### Integers

Decimal, hexadecimal (`0x`), binary (`0b`), and octal (`0o`). An underscore may appear between digits
and is ignored, so long groupings stay readable. A canonical type name may be written as a **suffix**;
without one the literal takes its type from context.

```sysl
var dec = 1_000_000
var hex = 0xFF
var bin = 0b1010_1010
var oct = 0o755
var suffixed = 42

print(dec, hex, bin, oct, suffixed)
```

```output
1000000 255 170 493 42
```

The scan is greedy and validated afterwards rather than stopping at the first character that does not
fit. That is why `42abc` is *one bad literal* and a clear diagnostic, rather than `42` followed by an
identifier and a confusing parse error further along.

An unsuffixed literal adopts the type its context expects, provided the value fits — `var x: u8 = 42`
makes `42` a `u8`, and `var x: u8 = 300` is a compile error. This is not implicit promotion: the
literal simply *is* that type.

### Floating point

A literal containing a `.` with a digit on each side, or an exponent, is floating point. Its default
type is `real` (`f64`); a suffix selects another width.

```sysl
var f1 = 3.14
var f2 = 2.5e3
var f3 = 1e-3

print(f1, f2, f3)
```

```output
3.14 2500 0.001
```

Note the rendering: a float prints in the shortest form that round-trips, so `2.5e3` shows as `2500`
and carries no `.0`. Hexadecimal, binary and octal have no floating-point form.

A [fixed-point](/reference/fixed-point/) suffix (`0.6i32q31`, `1i32q16`) is the one integer-family
suffix a fraction may carry. An unsuffixed decimal literal written where a fixed-point type is
expected adopts it, converted exactly from its digits; outside the range it is a compile error.

### Characters

A `char` literal is one Unicode scalar value in single quotes. Nine escapes are named, and anything
else is written with the braced `\u{...}` form.

| escape | meaning |
|---|---|
| `\n` | line feed, U+000A |
| `\t` | tab, U+0009 |
| `\r` | carriage return, U+000D |
| `\b` | backspace, U+0008 |
| `\f` | form feed, U+000C |
| `\0` | NUL, U+0000 |
| `\\` | backslash |
| `\'` | single quote |
| `\"` | double quote |
| `\u{H…}` | one to six hex digits, any Unicode scalar value |

```sysl
var ch = 'A'
var nl = '\n'
var bs = '\b'
var uni = '\u{1F600}'

print(int(ch), int(nl), int(bs), int(uni))
```

```output
65 10 8 128512
```

The named set is C's, and is deliberately no larger: the escape a programmer reaches for should be
the one every language they might arrive from has. The notable absence is **`\e`** for the escape
character, U+001B, which some shells and Perl accept — it is a GNU extension rather than standard C,
and Rust and Go both refuse it. Terminal code writes `'\u{1b}'` and gives it a name.

```sysl
var esc = '\e'

print(int(esc))
```

```error
unknown escape sequence '\e'
```

`\u{...}` is braced rather than a fixed four hex digits because a scalar value may need up to six —
the fixed-width form is what forces surrogate pairs into source text, and sysl's `char` is a scalar
value rather than a UTF-16 code unit, so there is nothing to pair.

### Strings

A plain string is double-quoted and decodes the same escapes a `char` does.

There are several other quote forms, each marked by a prefix on the opening quote:

| form | what it is |
|---|---|
| `"…"` | a `string` — validated UTF-8, with escapes decoded |
| `c"…"` | a C string — the same value with the terminator C expects, read as `*u8` |
| `s"…"` | interpolated: `${expr}` holes rendered with `str` |
| `f"…"` | interpolated with a printf specifier allowed after each hole |
| `raw"…"` | left exactly alone — no escapes decoded and no `${…}` read |

Any of them may also be written **tripled** as a text block, which spans lines and strips the common
leading indentation so the text lines up with the code around it rather than against the left margin.

The forms are told apart at the token, so nothing downstream has to remember which quote produced a
given value.

**`raw` means raw, which is one rule rather than two thirds of one.** Scala's `raw` is an
interpolator that happens to leave backslashes alone, and copying that left the one combination
nobody could write: a plain string reads no `${…}` and *does* decode escapes, and Scala's `raw` does
the opposite, so *leave all of it alone* had no spelling at all. That is the only thing a literal
carrying another language's source can use — and `${` is not exotic, since shell, Make, Kotlin,
Groovy and JavaScript template literals all spell interpolation with it.

```sysl
val program = raw"""
    fn main() {
        let re = "\d+\.\d+";
        println!("{}", re);
    }
    """

print(program)
```

```output
fn main() {
    let re = "\d+\.\d+";
    println!("{}", re);
}

```

Byte for byte what was written, with the block's indentation still stripped so it sits at the margin
of the code around it. **On one line a `raw"…"` cannot hold a `"`** — the first one ends it, because
there is no escape left to write one with — which is what the tripled form is for.

### Loop labels

A label is `'name` — an apostrophe and an identifier, the form Rust uses. It is told from a character
literal by the **absence of a closing quote**: `'a'` is the character `a`, and `'a` is the label `a`.

### `true`, `false`, `null`

Reserved words rather than library constants. `null` exists for `*T` and only for `*T`.

## Operators and delimiters

The operator set is **closed** — there are no user-defined operator symbols, and no way to add one.
Operators are tokenized by longest match, so `..<` wins over `..`, and `<<=` over `<<`.

```
=  +=  -=  *=  /=  %=  &=  |=  ^=  <<=  >>=
||  &&  !
==  !=  <  >  <=  >=
..  ..<  ...
|  ^  &  ~
+  -  *  /  %  <<  >>
++  --
(  )  [  ]  {  }  .  ?
.*
,  ::  :  ->
#  ;
```

Two of these are worth a note. `.*` is lexed as **one token** — the tail of a wildcard import — which
is possible because a `.` is otherwise only ever followed by a name. And `;` is *only* a separator
inside a three-clause `for` header: it is deliberately not a statement terminator, because a line
already ends a statement and a token that could also end one would give the language two answers to
the same question.

`@` opens an annotation and `#` opens a directive, and between them they are a declaration's only
prefixes that are not words. Nothing in the expression grammar spells either, so neither needs
lookahead to recognize. The two are told apart by the sigil and not by the margin, though a directive
is taken by a pass that runs before the lexer and reads only column 1 — so an *indented* `#` reaches
the grammar like any other token, and [attributes](/reference/attributes/) says what it is told there.

## Layout

sysl is indentation-sensitive. Indenting opens a block and dedenting closes it; there are no braces
around statement blocks, and a newline ends a statement.

```sysl
count(n: int) -> int
    var total = 0

    for i in 1..n
        total += i

    total

print(count(4))
```

```output
10
```

### Brackets suspend the rule

Inside `(`, `[` or `{`, layout stops applying until the bracket closes — so an argument list, an array
literal, or a parenthesized expression may be broken across lines however you like, and the
indentation of the continuation lines means nothing.

### Except where a block opens inside them

**A token that opens an indented block opens one wherever it is written, brackets included.** There
are two of them — `match`, which opens its arms, and `->`, which opens a closure's body or an arm's
— and inside a bracket pair the layout the bracket suspended is put back for the block's extent and
taken away again at its dedent. Without that a block's own margin, which is the only thing saying
where it ends, would have meant nothing.

```sysl
name(n: int)
    print(n match
        0 -> "none"
        1 -> "one"
        else "many")

name(0)
name(1)
name(7)
```

```output
none
one
many
```

The same for an arrow, which is what a multi-statement closure passed as an argument needs:

```sysl
apply(f: int -> int, n: int) -> int = f(n)

print(apply((x) ->
    val doubled = x * 2

    doubled + 1, 5))
```

```output
11
```

The bracket rule resumes at the dedent, so an argument written after the block is read at the outer
level and its own margin means nothing again.

**A comma on the block's own level ends the block — unless the line is a multi-assignment.** A line
that is a list of places followed by an assignment operator is a statement of the block, and every
comma on it, the values' included, is the statement's:

```sysl
call(f: () -> unit) = f()

var a = 1
var b = 2

call(() ->
    a, b = 5, 6
    print(a + b))
```

```output
11
```

**`then`, `else` and `do` are not among the two, and neither is the trailing block's `:`.** An `if`
written across lines puts its `else` back at the *outer* margin, which is a dedent belonging to the
bracket rather than to the block — a different mechanism rather than more of this one. A branch used
as an argument has its one-line form, which is what an argument wants anyway.

**What the rule costs is one layout, and it is the whole of it:** a function *type* written inside
brackets may not be broken immediately after its arrow, since that line now opens a block instead of
joining. Break it before the arrow, or at a comma, and it joins as it always did.

```sysl
apply(f: int
    -> int, n: int) -> int = f(n)

print(apply((x) -> x + 1, 5))
```

```output
6
```

### An unbracketed line continues after an operator

The rule is the narrowest one that works: **an operator that cannot finish an expression continues the
line.** After `+`, `&&`, `==`, `<<` or a prefix `!` something must follow, so a newline there cannot
have been the end of a statement and there is nothing ambiguous to resolve.

```sysl
var total = 1 +
    2 +
    3

var ok = total > 5 &&
    total < 10

print(total, ok)
```

```output
6 true
```

What is *excluded* follows from the same rule rather than from taste:

- **`=` and `->`** are binary but already open an indented block — a function body, a match arm, a
  [binding's value](/reference/declarations/#the-value-may-be-an-indented-block) — and one token cannot mean
  both "the block starts here" and "the line goes on". A value too long for its line therefore goes
  *under* the `=` as a block rather than after it as a continuation.
- **`++`, `--` and `?`** are postfix, so a line ending in one is already complete.
- **`..`, `..<` and `...`** can be complete too: `s[..]` is the whole range, and `int...` is a
  variadic tail.
- **`.`** would work, but the continuation style worth having for a call chain puts the dot at the
  *start* of the next line — which is the opposite mechanism, and is the next section.

### An unbracketed line continues before a dot

A chain is the one expression people habitually break across lines, and the break goes *before* the
dot rather than after it. There is no operator at the end of `text(label)` for the rule above to see,
so this one looks at how the next line **begins**: a line starting with a `.` followed by a name
continues the line above it.

```sysl
import sysl.text.Search

val trimmed = "  a line with room around it  "
    .trim()
    .len

print(trimmed)
```

```output
26
```

As with a trailing operator, the continuation line's own indentation means nothing, so a chain may be
laid out however reads best.

**A name after the dot is required, and it is what makes the rule safe.** A continued line's margin is
discarded, so a rule that fired on anything which could also *begin* a statement would pull that line
into the block above and move where the block ends. Requiring a letter or `_` excludes everything else
a line could start with a dot for — `..` and `..<` are ranges, `...` is a variadic tail, `.0` is a
tuple index, and `.*` is an import wildcard.

**And the line above has to be one a chain could continue.** This is the exact dual of the rule
before it: a trailing operator carries the line because it *cannot* finish an expression, and a
leading dot continues one only where the line above *could* have. A **reserved word** could not —
there is nothing to call a method on — so a block opened by one keeps its body:

```sysl
enum Colour
    Red
    Green

val n = Colour.Red match
    Red -> 1
    Green -> 2

print(n)
```

```output
1
```

Without that half, the first arm of the `match` would read as a chain hanging off the header. The
four reserved words that *are* values — `self`, `true`, `false` and `null` — are the exception, so
`self` on its own line with `.field` under it is an ordinary chain.

A **trailing** dot is not a continuation, and is an error:

```sysl
val n = "hi".
    len

print(n)
```

```error
tuple index expected
```

Two ways of writing one chain is a style argument in every file that has one, so the language admits
exactly the one.

### The one hazard

A continuation line that is **dedented** has its dedent swallowed along with the newline, so a
trailing operator can hold a block open further than it looks. Every language that joins lines has
this, including on brackets. A chain written at the outer margin is the same hazard from the other
end: it is still inside the block above it, and that block ends one line later than it appears to.

It is documented rather than guarded against, and the reason is worth knowing: guarding would mean
the indentation of a continuation line carried meaning, and the entire point of continuing a line is
that it does not.
