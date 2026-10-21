---
title: Lexical structure
summary: What the compiler reads before it parses anything — tokens, literals, and the layout rules that decide where a statement ends.
weight: 10
---

sysl source is read in two passes. A **lexer** turns characters into tokens and, because the
language is indentation-sensitive, also inserts the tokens that open and close blocks. The parser
then reads those tokens. Most of what is surprising about a layout-sensitive language happens in the
first pass, so this page describes it on its own.

## Source text

Source is **UTF-8**, and the encoding is not configurable. A non-ASCII character is legal in a
comment, a string literal, a character literal — and in an identifier (see below).

Line endings may be LF or CRLF. A file need not end in a newline.

## Literate source

**A file named `.lsysl` is a Markdown document, and the part of it indented four columns is the
program.** Everything else is prose and is not compiled. The file's **name** alone decides which
kind of file it is: a `.sysl` file is never read this way, whatever its indentation, and a `.lsysl`
file with no prose in it is a `.sysl` file indented four spaces.

There is no other marker: no name on a block, no directive that opens one, and no way to say that a
block ends.

````text
# Halving

This program explains itself. Everything at the margin is prose, and the compiler
never sees it.

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

This is a form of Knuth's *WEB* without reordering. WEB let an author write the program in the order
that explains it and had a *tangler* rearrange it into the order the compiler needs, which made a
`.web` file hard to read without its tools. In a `.lsysl` file the code appears in the order it runs
and the tangler only removes prose, so the file reads correctly as it is — to a person, to a Markdown
renderer, and to `grep`. What it adds over comments is room for a full explanation between two
functions.

### The four rules

**Four columns is the threshold**, which is Markdown's own, so what a renderer shows as a code block
is exactly what the compiler reads.

**Consecutive indented blocks are one block.** A paragraph between two of them ends nothing, so a
function body may be explained a step at a time: the prose dedents to column zero and the code
resumes at the indentation it left off at.

**A fenced block is an illustration.** Both ` ``` ` and `~~~` mark code that is to be read rather
than run — a wrong version beside the right one, a shell transcript, a fragment of C — and none of
it is compiled, however it is indented. Code that runs is indented; code that is only shown is
fenced.

**What is under a bullet is prose.** An indented block inside a list item belongs to that item, so a
list may hold examples without any of them entering the program. An executable block therefore never
sits directly under a list; a line of prose separates the two.

### Source positions are preserved

**Prose is blanked, not removed.** The text handed to the lexer has exactly as many lines as the
file, and each line of program text stays on the line it was written on, so every position the
lexer records is already a position in the `.lsysl` file. No mapping table is needed, and no later
pass treats a literate file differently.

The one coordinate that moves is the **column**, by the four columns of indentation that made the
line code. They are added back when a position is reported, so a diagnostic names the line and
column of the file the reader has open.

### Two forms Markdown accepts are refused

Both would silently drop part of the program, and neither could be diagnosed later, because what
reaches the compiler is simply a smaller program than the author wrote.

**A tab in the indentation.** A tab is as wide as whatever displays it, so a tab-indented line could
be program text in one editor and prose in another. It is refused *before* the four-column test,
because failing that test would silently turn the line into prose and drop a statement from a
function.

```text
error: a tab in the indentation of a literate file — what makes a line program text is four columns of indent, and a tab is as wide as whatever happens to be displaying it, so this line is code in one editor and prose in another
```

**A fence that is never closed.** Markdown runs an unclosed fence to the end of the document. In a
literate file that would turn every declaration below it into an illustration, and the resulting
diagnostic would point somewhere far below the cause. The fence is refused at the line that opened
it.

```text
error: this fence is never closed, so everything below it is an illustration and none of it is compiled — close it, or indent the lines that are meant to run
```

### `tangle` and `weave`

The two halves of a literate system are named for what they do to the source. Every build of a
`.lsysl` file tangles it; the commands let you run each half on its own:

- **`sysl tangle`** prints the program with the prose stripped. It shows exactly what the compiler
  read, which is the first thing to check when a literate file misbehaves — a block indented that
  should not have been, or a fence that swallowed a function.
- **`sysl weave`** renders the document as HTML. A `.lsysl` file is already Markdown, but an
  indented block carries no language, so a plain renderer cannot highlight it. `weave` marks each
  indented block as sysl and passes the source through verbatim.

**`tangle` reads the file the way a build does**, so a file the compiler refuses — a tab in an
indent, a fence that is never closed — is refused with the same diagnostic, and the program it
prints is the program a build compiles.

*`weave` is not implemented in this compiler; it reports "is not a command this compiler can carry
out yet".*

Both are covered under the [CLI](/getting-started/cli/). Weaving needs no target, no standard module
and no library, so a package's documentation can be rendered on a machine that cannot build it.

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

`/*` inside a block comment opens another one, which needs its own matching `*/`. This lets you
comment out a block of code that already contains comments, which C does not allow:

```sysl
/* commented out, /* including this */ and on to here */
print(1)
```

```output
1
```

**As a consequence, `/*` and `*/` are significant inside a comment, and backticks do not protect
them**: the lexer does not read Markdown. A comment whose text contains either sequence — a glob
pattern is the common case — does not end where it appears to, and neither resulting diagnostic
mentions comments:

```sysl
/* a glob pattern: src/** */
print(1)
```

```error
unclosed comment
```

That comment opened a second comment at `c/*` and closed only the inner one, so the outer comment
ran to the end of the file. The opposite case is harder to read, because the caret lands on prose:

```sysl
/** matches "**/*.sysl" at any depth */
f() -> int = 1

print(f())
```

```error
unterminated string literal
```

There the `*/` inside `**/` closed the comment early and everything after it was read as code, so
the diagnostic is about a quote in what was meant to be a sentence.

**Write such a comment with `//`**, where neither sequence means anything:

```sysl
// matches "**/*.sysl" at any depth
f() -> int = 1

print(f())
```

```output
1
```

This constrains doc comments too: `/** … */` is the only form `sysl doc` reads, so a module about
globs documents its pattern syntax in `//` lines above the declarations rather than in their doc
comments.

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

**Only the delimiter makes a doc comment.** An implementation note above a declaration is written
with `//` and stays out of the documentation; a `/* … */` block comment is not a doc comment either.
`/**/` is an empty block comment, not the opening of a doc comment.

A `*` down the left margin is optional; where it is written, it and one following space are removed,
so a fenced block inside a doc comment keeps its own indentation.

**A doc comment cannot change what a program means.** The analyzer and code generation ignore it, and
a program compiles identically with every doc comment deleted.

### What a doc comment attaches to

The declaration below it. **Annotations and `//` lines may sit between them**, which is the usual
layout, since `@test` and `@export` are written directly above what they annotate:

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

**A blank line ends the association**, so a file can open with a comment that documents the module
rather than its first declaration:

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

A tag is `@name` **at the start of a line**, and nowhere else, so an `@` inside a sentence is plain
text. A tag's text runs to the next tag or the end of the comment; a blank line does not end it, so
the text may have several paragraphs.

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

**There is no `@throws`.** sysl has no exceptions: a fallible function's error is the `E` of its
`Result[T, E]`, which is part of the return type and is described under `@return`.

### Tags that name the signature are checked

`@param` and `@tparam` can go stale when a parameter is renamed, so one naming a parameter the
declaration does not have is refused:

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

**The check runs in one direction only.** A parameter with **no** `@param` is not an error:
documentation is optional, and a partial doc comment is better than none. The compiler checks what
is written and never requires what is absent.

A bare `@param` with no name after it is reported as its own error.

**`@param self` is accepted on a member.** The receiver is not part of the analyzer's parameter
list, but it is written in the declaration, so it may be documented. Like every other parameter, it
is accepted rather than required.

### An unrecognized `@name` is prose

`@` is also sysl's annotation sigil — `@test`, `@requires`, `@export`, `@no_alloc` — so a doc
comment may mention one in its text. The tag vocabulary above is closed, and anything outside it is
body text.

As a result, a misspelled tag is silent: `@parm` begins a paragraph rather than producing a
diagnostic.

### The first sentence is the summary

The prose before the first tag is the **body**, and its first sentence is the **summary**, which is
what an index shows.

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

print(año, área(3, 4), Círculo(2).radio)
```
```output
2026 12 2
```

This lets domain vocabulary be written in the programmer's own language rather than transliterated.
Go, Java, Scala and C# follow the same rule.

A **digit** is whatever Unicode calls one too, but only *inside* a name: `caf٣` is an identifier and
`٣` is not, because a name may not begin with a digit in any script. Number *literals* accept only
ASCII digits, so `٣` is not a number literal either.

A **combining mark** — an accent, a vowel sign, anything in Unicode category M — continues a name the
way a letter does, so a name typed on a keyboard that composes `é` as an `e` plus a combining acute
is one identifier. A combining mark cannot *begin* a name, and one written there is refused.

**Two spellings that look identical are two names.** `café` written with a precomposed `é` and
`café` written with an `e` and a combining acute are different identifiers; both are legal, and a
program may declare both without a collision. sysl applies no Unicode normalization (unlike UAX
#31's recommendation), so a name resolves only when it is spelled with the same code points as its
declaration.

**A letter above the Basic Multilingual Plane is refused.** This is not predictable from the rule
above. The letters of every living script are inside the BMP, CJK Unified Ideographs included; what
lies outside it is historic scripts and the CJK extension planes.

```sysl
val 𠮷 = 1
print(𠮷)
```
```error
illegal character
```

An **operator-shaped** character is not a letter: `×` and `÷` are refused the same way.

Case is significant. Capitalization is not enforced, but the standard library and this documentation
use `PascalCase` for types and traits and `snake_case` for everything else, and syntax highlighting
treats a capitalized name as a type.

### Quoted identifiers

**A name written between backticks may be anything the rule above refuses** — a reserved word, or a
name containing spaces and punctuation. Since a letter is any Unicode letter, `café` and `名前` need
no backticks; the quoted form is for punctuation, spaces and reserved words.

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

The backticks are not part of the name: `` `match` `` *is* the identifier `match`. A contextual word
written this way is an ordinary name — `` `end` `` names something and does not close a block.

The same holds for a name the ordinary grammar can also write, so the two spellings are one name:

```sysl
var café = 3
`café` += 1
print(café)
```
```output
4
```

Two characters may not appear inside the backticks. A **backtick** cannot, since there are no
escapes. A **`.`** cannot either, because a qualified name is stored as a dotted string and a dot
inside one part could not be told from the separator between two. A newline ends the search rather
than the name, so an unclosed backtick is reported on the line that opened it.

A **module path** is written with plain names only — `module my.mod`, never ``module `my mod` `` —
because a module names a directory and its parts must be directory names.

In a `match` arm the form has a second use: a backticked name **refers to** a variable already
declared rather than binding a new one.

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
`@requires(os)` — and an attribute's words are matched as ordinary identifiers, so no word is
reserved and an allocator may still name its function `alloc`.

**Type names are not reserved either.** `int`, `usize`, `f32`, `bool`, `string` and the rest are
*predeclared identifiers* that the analyzer resolves, as in Go and Swift. This keeps the `iN` / `uN`
/ `fN` families open: `u12` and `i5` are valid types without the lexer knowing a list of widths.

A few words are **contextual** — special only where the grammar expects one, and ordinary identifiers
everywhere else: `is`, `not`, `end`, `become`, `opaque`, `derives`, `invariant`, `new`, `set`, `some`,
`with`, `within`, `where`, [`async` and `await`](/reference/async/#async-and-await-are-contextual),
and the `c` of a [`c const` or `c type`](/reference/ffi/) block. You may name a variable `where`; you
may not name one `while`.

**A word is contextual rather than reserved wherever the grammar allows it**, because a reserved word
is removed from every program's namespace. [`become`](/reference/declarations/) needs no reservation,
because two identifiers in a row are not otherwise a statement, so a `become` of a function named
`become` parses unambiguously. Likewise `async` is a keyword only where a declaration follows it,
and `await` only where an operand does.

`set` is special only where a member declaration begins, so the word remains available as the name
of a container, a local or a method:

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

`c` is a common name in code that handles characters. It stays available because it is special only
when followed by `const` or `type`; nothing else in sysl puts a keyword after a name, so the pair is
unambiguous.

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

The shape is reserved rather than particular names, so a new built-in can never collide with a name
a program has declared. C reserves the same names but does not diagnose their use; sysl refuses the
declaration where it is written.

These are **predeclared identifiers**, like the type names above — not reserved words, and absent from
the table.

The restriction applies to sysl names only. The symbol an `extern` links to is unaffected, since a C
library's own symbols often have this shape:

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
in the place where the argument would have been written:

```sysl
where(line: int = __LINE__) -> int = line

print(where())
print(where())
```

```output
3
4
```

This is how the standard library's `assert` reports the line that failed, which is why its message
is optional. Where a default fills another default, the outermost call is the one reported.

`__DATE__` and `__TIME__` make a build non-reproducible, as C's do. They suit a firmware build stamp
and are best avoided otherwise.

### `__VERSION__` is the package's version

**`__VERSION__` is the `version` the [manifest](/reference/packages/#what-a-project-is-called)
states, so `package.hocon` is the one place a program's version is written.** A `--version` flag or
an About screen reads it instead of keeping its own `const VERSION = "…"`. In a package whose
manifest says `version = "1.4.0"`, this prints `mytool 1.4.0`. A block on this page is a lone file
with no manifest, so here it is refused, as described at the end of this section:

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

**As a default argument it reports the caller, as `__FILE__` and `__LINE__` do.** A library's
`stamp(v: string = __VERSION__)` answers the version of whichever package contains the call to
`stamp()`: the program's when the program calls it, another library's when that library does, and
the library's own only when it calls itself. A library can therefore offer a `--version` helper that
reports the program using it.

**A file that belongs to no package has no version to read**, so `__VERSION__` is refused there — a
lone `sysl run tool.sysl` with no `package.hocon` beside it, or a manifest with no `version` key. The
refusal names which: *"'__VERSION__' is the 'version' in package.hocon, and this file is not part of a
package"*, or *"… and …/package.hocon declares none"*.

### `__NAME__` is the package's name

**`__NAME__` is the `name` the [manifest](/reference/packages/#what-a-project-is-called) states, so a
program can name itself without writing its name a second time.** It follows the same rules as
`__VERSION__`. A typical use is a log tag: a logging function that defaults its tag to `__NAME__`
tags every message with the name of the calling package, because a default reports the caller. In a
package whose manifest says `name = "skitter-app"`, this prints `[ skitter-app ] started`. A block
on this page is a lone file with no manifest, so here it is refused, as described at the end of this
section:

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
of whichever package contains the call — the program's when the program calls it, another library's
when that library does. `__VERSION__` and `__FILE__` follow the same rule.

**A file that belongs to no package has no name to read**, so `__NAME__` is refused there — a lone
`sysl run tool.sysl` with no `package.hocon` beside it, or a manifest with no `name` key. The refusal
lands where `__NAME__` is read, so a `const` initialized with it is refused at the `const`:

```sysl
const TAG = __NAME__
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

Decimal, hexadecimal (`0x`), binary (`0b`), and octal (`0o`). The prefix letter may be written in
either case, so `0XFF`, `0B1010` and `0O755` are the same literals as `0xFF`, `0b1010` and `0o755`;
the hexadecimal digits are case-insensitive too. An underscore may appear between digits and is
ignored, so long numbers can be grouped. A canonical type name may be written as a **suffix**;
without one the literal takes its type from context.

```sysl
var dec = 1_000_000
var hex = 0xFF
var bin = 0b1010_1010
var oct = 0o755
var suffixed = 42u16

print(dec, hex, bin, oct, suffixed)
```

```output
1000000 255 170 493 42
```

The scan is greedy and validated afterwards, rather than stopping at the first character that does
not fit, so `42abc` is reported as one malformed literal rather than as `42` followed by an
identifier and a parse error further along.

An unsuffixed literal adopts the type its context expects, provided the value fits — `var x: u8 = 42`
makes `42` a `u8`, and `var x: u8 = 300` is a compile error. This is not a conversion: the literal
*is* that type.

**A float type is such a context too.** Where a position expects `f16`, `bf16`, `f32`, `real` or
`f128` — an initializer, an assignment, an argument, a return, an element, or the other side of an
operator from a float operand — an unsuffixed integer literal is that float, provided the float holds
its value exactly:

```sysl
val r: real = 2
val h: f32 = 0.75

print(r / 4, 3 * h, h + 1, r < 3)
```

```output
0.5 2.25 1.75 true
```

A value the format would round is refused, as a value too wide for an integer type is, since the
number written is what the program means:

```sysl
val r: real = 9007199254740993
```

```error
the literal 9007199254740993 is not exactly a real
```

A suffixed literal has said what it is: `2i32` is an `i32`, which a `real` takes by the ordinary
widening rule, and `2i64` is a `long`, which it does not.

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

A float prints in the shortest form that round-trips, so `2.5e3` prints as `2500`, without a `.0`.
Binary and octal have no floating-point form.

A float written as a whole number needs no `.0` where its type is known: [an integer literal is the
float its position expects](#integers), at any width that holds it exactly.

```sysl
val one: f16 = 1
val big: f32 = 16777216

print(one / 4, big)
```

```output
0.25 16777216
```

**A hexadecimal float follows C99**: a hex significand, an optional `.` and hex fraction, and a `p`
exponent — a power of **two**, written in decimal. The `p` is required (hex digits include letters, so
`0x1.b` is an integer and a field read), either case is accepted, and a suffix goes after the
exponent. Its type is inferred as for a decimal float: `real` unless the suffix or the context says
otherwise.

```sysl
val ln2_hi = 0x1.62e42fefa39efp-1
val pi = 0x1.921fb54442d18p1
val third: f32 = 0x1.555556p-2

print(0x1.8p3, 0x1p-2, pi, ln2_hi.bits() == 0x3fe62e42fefa39ef, third.bits() == 0x3eaaaaab)
```

```output
12 0.25 3.141592653589793 true true
```

**A hex float is exact, or it is refused.** Hex float digits specify the bits directly, which is why
math libraries use them, so a literal whose type cannot hold its value exactly is an error naming the
type rather than a silent rounding: too many significant bits, a power past the type's largest
finite value, or a bit below its smallest subnormal. The same digits are accepted at a type wide
enough to hold them.

```sysl
val x: f32 = 0x1.000001p0
```

```error
the hex float '0x1.000001p0' is not exactly a 'f32': it has 25 significant bits, and a 'f32' holds 24
```

```sysl
val tiny = 0x1p-1075
```

```error
the hex float '0x1p-1075' is not exactly a 'real': its lowest bit is 2^-1075, and the smallest 'real' is 2^-1074
```

At a [fixed-point](/reference/fixed-point/) type a hex float is exact when its lowest bit is no finer
than the type's step: `0x1p-15` is the smallest `q15`, and `0x1p-16` is refused.

A float's encoding can also be written as an integer, through `FloatBits`: `real.from_bits(b)`,
`f32.from_bits(b)` and `x.bits()` reinterpret rather than convert, and **they fold at compile
time**, so a table of constants written as bit patterns is constant data just as one written in hex
floats is — in a `const`, in a module `val` placed in `.rodata`, and inside a `const` function.

```sysl
const LN2_HI = real.from_bits(0x3fe62e42fefa39ef)
const ONE_BITS = f32(1).bits()
const next_up(x: real) -> real = real.from_bits(x.bits() + 1)
const AFTER_ONE = next_up(1)

val TABLE = [0x1.62e42fefa39efp-1, real.from_bits(0x3c7abc9e3b39803f)]

print(LN2_HI == TABLE[0], ONE_BITS == 0x3f800000, AFTER_ONE.bits() == 0x3ff0000000000001)
```

```output
true true true
```

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

The named set is C's, with no additions. The notable absence is **`\e`** for the escape character,
U+001B, which some shells and Perl accept; it is a GNU extension rather than standard C, and Rust and
Go both refuse it. Terminal code writes `'\u{1b}'` and gives it a name.

```sysl
var esc = '\e'

print(int(esc))
```

```error
unknown escape sequence '\e'
```

`\u{...}` is braced rather than a fixed four hex digits because a scalar value may need up to six.
sysl's `char` is a scalar value rather than a UTF-16 code unit, so there are no surrogate pairs.

### Byte literals

A character literal is a `char` — except **where a `u8` is wanted**, where an ASCII one (a code
point below 128) is that byte. It takes its type from its position exactly as an integer literal
does: a binding's annotation, an argument, a return, an array element, a pattern or a range in a
pattern against a `u8`, and an operand of an operator whose other operand is a `u8`, on either
side. Byte-oriented code therefore needs no `u8(...)` around each character.

```sysl
import sysl.buf.{Buf, buf}

digit(b: u8) -> int = b match
    '0'..'9' -> b - '0'
    _ -> -1

val sep: u8 = ','
var out: Buf[u8] = buf()
var fields = 1

for b in "a,b;c".bytes
    if b == sep || ';' == b then fields += 1

out.push('o')
out.push('\n')
print(sep, fields, digit('7'), digit('x'), out.len(), out.at(1))
```

```output
44 3 7 -1 2 10
```

An escape is the byte it encodes — `'\n'` is 10, `'\0'` is 0, `'\u{7f}'` is 127. Where no `u8` is
expected, a character literal is a `char`: `val c = 'a'` is a `char`, and so is each side of
`'a' == 'b'`.

A code point of 128 or above is **more than one byte** of UTF-8, so it is refused as a byte rather
than truncated. Write a string for its bytes, or `u8(0x..)` for one byte by number:

```sysl
val b: u8 = 'é'

print(b)
```

```error
the character U+00E9 is not ASCII, so it is more than one byte in UTF-8 and cannot be a u8 — write a string for its bytes, or u8(0x..) for one byte
```

The same holds one level up for a string literal: **where a `[N]u8` is wanted**, it is its UTF-8
bytes — see [a byte array from a string literal](/reference/arrays/#a-byte-array-from-a-string-literal).

Only `u8` is a byte. Every other integer type — `i8` included — is a number, and a character
literal is not one:

```sysl
val x: i8 = 'a'

print(x)
```

```error
cannot initialize 'x': declared i8 but the value is char
```

The rule applies to the **literal**, whose value is known where it is written. A `char` held in a
variable may be any code point, so it needs an explicit conversion, which keeps its low eight bits:

```sysl
val c = 'a'
val b: u8 = c

print(b)
```

```error
cannot initialize 'b': declared byte but the value is char
```

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
leading indentation, so the text can be indented with the surrounding code.

The forms are distinguished by the lexer, so later passes see only the resulting value.

**`raw` leaves everything alone**: it decodes no escapes and reads no `${…}` holes. A plain string
reads no holes but decodes escapes; `raw` is the form for text that must pass through untouched,
such as source code in another language. (`${` is common in such text: shell, Make, Kotlin, Groovy
and JavaScript template literals all use it for interpolation.)

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

The output is byte for byte what was written, with the block's common indentation stripped. **A
single-line `raw"…"` cannot contain a `"`**, since the first one ends it and there is no escape;
use the tripled form instead.

### Loop labels

A label is `'name` — an apostrophe and an identifier, as in Rust. It is distinguished from a
character literal by the **absence of a closing quote**: `'a'` is the character `a`, and `'a` is the
label `a`.

### `true`, `false`, `null`

Reserved words rather than library constants. `null` exists for `*T` and only for `*T`.

## Operators and delimiters

The operator set is **closed** — there are no user-defined operator symbols, and no way to add one.
Operators are tokenized by longest match, so `..<` wins over `..`, `<<=` over `<<`, and `+:=` is
one token rather than `+` and `:=`.

```
=  +=  -=  *=  /=  %=  &=  |=  ^=  <<=  >>=
:=  +:=  -:=  *:=  /:=  %:=  &:=  |:=  ^:=  <<:=  >>:=
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

Two of these need a note. `.*` is lexed as **one token** — the tail of a wildcard import — which is
possible because a `.` is otherwise always followed by a name. And `;` is a separator *only* inside a
three-clause `for` header. It is not a statement terminator, because the end of a line already ends
a statement.

`@` opens an annotation and `#` opens a directive; they are the only non-word prefixes a declaration
can have. The expression grammar uses neither, so neither needs lookahead to recognize. A directive
is handled by a pass that runs before the lexer and reads only column 1, so an *indented* `#`
reaches the grammar like any other token; [attributes](/reference/attributes/) describes how it is
read there.

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

### A dedent goes back to a margin that is still open

**A line less indented than the one above it must line up with a block that is still open**, and it
closes every block indented deeper than it. A line that lines up with none of them — usually a line
left between two open margins after code was moved — is refused, and the diagnostic names the two
margins:

```sysl
check(n: int)
    if n > 0
        print("positive")
      print("checked")
```

```error
a line indented 6 columns lines up with no open block: it sits between the block indented 8 and the one indented 4 — indent it to one of the two
```

It is refused rather than read at the nearer margin because the two readings are different programs:
at column 8 the second `print` runs only for a positive `n`, and at column 4 it runs for every `n`.
An `end` marker is a line like any other and follows the same rule. A tab counts as one column.

**One exception: an `else` or `elif` lined up exactly under the `if` it continues.** An `if` written
to the right of a `=` sits at a column no block opened, and lining its branches up under it is the
natural layout:

```sysl
sign(n: int) -> string
    val word = if n < 0 then
                   "negative"
               elif n == 0 then
                   "zero"
               else "positive"

    word

print(sign(-3), sign(0), sign(5))
```

```output
negative zero positive
```

It is read exactly as the same branch written at the statement's margin. This holds for an
`else if`, for an `if` nested in another's branch, and for a statement `if` after an arm's `->`. The
`if` must be the one the line continues, in the same statement, and the column must be that of the
`if` keyword itself; an `else` at any other column that no block opened is refused as above:

```sysl
sign(n: int) -> string
    val word = if n < 0 then
                   "negative"
                 else "not negative"

    word
```

```error
a line indented 17 columns lines up with no open block: it sits between the block indented 19 and the one indented 4 — indent it to one of the two
```

The branch lined up under its `if` may also sit **deeper** than the line it continues, as happens when
the `then` branch stays on the `if`'s own line. Such a line opens no block; it continues the
statement above, whether after a `=` or after an arm's `->`:

```sysl
digits(o: Option[int], wide: bool) -> int
    val n = o match
        Some(k) -> k
        None -> if wide then 8
                else 4

    val m = if n > 4 then n * 2
            else n

    m

print(digits(None, true), digits(None, false), digits(Some(3), false))
```

```output
16 4 3
```

### Brackets suspend the rule

Inside `(`, `[` or `{`, layout stops applying until the bracket closes — so an argument list, an array
literal, or a parenthesized expression may be broken across lines freely, and the indentation of the
continuation lines is ignored.

### Except where a block opens inside them

**A token that opens an indented block opens one wherever it is written, brackets included.** There
are two such tokens — `match`, which opens its arms, and `->`, which opens a closure's body or an
arm's. Inside a bracket pair, layout is restored for the extent of the block and suspended again at
its dedent, since a block's margin is what marks where it ends.

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

The same applies to an arrow, so a multi-statement closure can be passed as an argument:

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
level, and its indentation is again ignored.

**A comma at the block's own level ends the block, unless the line is a multi-assignment.** A line
that is a list of places followed by an assignment operator is a statement of the block, and every
comma on it, including those between the values, belongs to that statement:

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

**`then`, `else` and `do` do not open blocks inside brackets, and neither does the trailing block's
`:`.** An `if` written across lines puts its `else` back at the *outer* margin, which is a dedent
belonging to the bracket rather than to the block. A branch used as an argument is written in its
one-line form.

**This rule rules out one layout:** a function *type* written inside brackets may not be broken
immediately after its arrow, because that line would open a block. Break it before the arrow, or at
a comma:

```sysl
apply(f: int
    -> int, n: int) -> int = f(n)

print(apply((x) -> x + 1, 5))
```

```output
6
```

### An unbracketed line continues after an operator

**An operator that cannot finish an expression continues the line.** After `+`, `&&`, `==`, `<<` or
a prefix `!` an operand must follow, so a newline there cannot end a statement.

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

The exclusions follow from the same rule:

- **`=` and `->`** are binary but already open an indented block — a function body, a match arm, a
  [binding's value](/reference/declarations/#the-value-may-be-an-indented-block) — and one token cannot mean
  both "a block starts here" and "the line continues". A value too long for its line therefore goes
  *under* the `=` as a block rather than after it as a continuation.
- **`++`, `--` and `?`** are postfix, so a line ending in one is already complete.
- **`..`, `..<` and `...`** can be complete too: `s[..]` is the whole range, and `int...` is a
  variadic tail.
- **`.`** is not a trailing continuation, because a call chain is broken with the dot at the
  *start* of the next line, described in the next section.

### An unbracketed line continues before a dot

A method chain is commonly broken across lines with the break *before* each dot. There is no
operator at the end of the line for the rule above to see, so this rule looks at how the next line
**begins**: a line starting with a `.` followed by a name continues the line above it.

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

As with a trailing operator, the continuation line's indentation is ignored, so a chain may be laid
out however reads best.

**A name after the dot is required.** A continued line's margin is discarded, so a rule that fired on
a line that could also *begin* a statement would pull that line into the block above and move where
the block ends. Requiring a letter or `_` excludes every other reason a line could start with a dot
— `..` and `..<` are ranges, `...` is a variadic tail, `.0` is a tuple index, and `.*` is an import
wildcard.

**The line above must be one a chain could continue.** A trailing operator continues the line
because it *cannot* finish an expression; a leading dot continues one only where the line above
*could* have finished one. A line ending in a **reserved word** has nothing to call a method on, so a
block opened by one keeps its body:

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

Otherwise the first arm of the `match` would be read as a chain continuing the header. The four
reserved words that *are* values — `self`, `true`, `false` and `null` — are the exception, so `self`
on its own line with `.field` under it is an ordinary chain.

A **trailing** dot is not a continuation, and is an error:

```sysl
val n = "hi".
    len

print(n)
```

```error
tuple index expected
```

The language accepts only the leading-dot form, so every chain is written one way.

### A dedented continuation line

A continuation line that is **dedented** has its dedent absorbed along with the newline, so a
trailing operator can hold a block open further than it appears to. Every language that joins lines
has this property, including across brackets. A chain written at the outer margin is the same case
from the other direction: it is still inside the block above it, and that block ends one line later
than it appears to.

The compiler does not diagnose this, because doing so would give the indentation of a continuation
line a meaning, and a continuation line's indentation is defined to have none.
