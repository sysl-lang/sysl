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
comment, a string literal, and a character literal; identifiers are ASCII (see below).

Line endings may be LF or CRLF. A file need not end in a newline.

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

There is no documentation-comment form with special syntax. Doc text is an ordinary comment above the
declaration it describes.

## Identifiers

An identifier starts with a letter or `_` and continues with letters, digits, or `_`. Letters are
ASCII `A`–`Z` and `a`–`z`.

Case is significant. Capitalization is not enforced anywhere, but the convention the standard library
and this documentation follow is `PascalCase` for types and traits, `snake_case` for everything else —
and the syntax highlighting treats a capitalized name as a type, so following it makes code read
correctly on a page.

## Reserved words

Forty words are reserved and may not be used as identifiers:

| | | | | |
|---|---|---|---|---|
| `alignof` | `alloc` | `as` | `break` | `const` |
| `continue` | `defer` | `do` | `elif` | `else` |
| `ensure` | `enum` | `extern` | `false` | `for` |
| `if` | `impl` | `import` | `in` | `loop` |
| `match` | `module` | `no` | `null` | `private` |
| `ref` | `require` | `requires` | `return` | `self` |
| `sizeof` | `struct` | `then` | `trait` | `true` |
| `type` | `val` | `var` | `weak` | `while` |

**Type names are deliberately not among them.** `int`, `usize`, `f32`, `bool`, `string` and the rest
are *predeclared identifiers* that the analyzer resolves, exactly as in Go and Swift. That is what
lets the `iN` / `uN` / `fN` families stay open: `u12` and `i5` are types you may write without the
lexer having heard of them, and no list of widths has to be maintained anywhere.

A few words are **contextual** — special only where the grammar expects one, and ordinary identifiers
everywhere else: `is`, `not`, `invariant`, `new`, `within`, `where`. You may name a variable `where`;
you may not name one `while`.

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
var suffixed = 42u8

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

### Characters

A `char` literal is one Unicode scalar value in single quotes. Escapes include `\n`, `\t`, `\\`,
`\'`, `\"`, `\0`, and the braced `\u{...}` form.

```sysl
var ch = 'A'
var nl = '\n'
var uni = '\u{1F600}'

print(int(ch), int(nl), int(uni))
```

```output
65 10 128512
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
| `raw"…"` | no escape processing — a backslash is a backslash |

Any of them may also be written **tripled** as a text block, which spans lines and strips the common
leading indentation so the text lines up with the code around it rather than against the left margin.

The forms are told apart at the token, so nothing downstream has to remember which quote produced a
given value.

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

`#` opens an attribute, and is a declaration's only prefix that is not a word. Nothing in the
expression grammar spells `#`, so a line beginning with one can only be an attribute and needs no
lookahead to recognize.

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

- **`=` and `->`** are binary but already open an indented block — a function body, a match arm — and
  one token cannot mean both "the block starts here" and "the line goes on".
- **`++`, `--` and `?`** are postfix, so a line ending in one is already complete.
- **`..`, `..<` and `...`** can be complete too: `s[..]` is the whole range, and `int...` is a
  variadic tail.
- **`.`** would work, but the continuation style worth having for a call chain puts the dot at the
  *start* of the next line, which needs the opposite mechanism.

### The one hazard

A continuation line that is **dedented** has its dedent swallowed along with the newline, so a
trailing operator can hold a block open further than it looks. Every language that joins lines has
this, including on brackets.

It is documented rather than guarded against, and the reason is worth knowing: guarding would mean
the indentation of a continuation line carried meaning, and the entire point of continuing a line is
that it does not.
