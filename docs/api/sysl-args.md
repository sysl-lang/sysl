---
title: sysl.args
layout: api-module
headingShift: 0
slugStyle: github
module: sysl.args
summary: "How a program's arguments become a `[]string`, written here because every line of it is ordinary sysl."
---

What the platform hands the entry point is C's `argc` and `argv` -- a count and a vector of
NUL-terminated byte runs -- and what a sysl program asks for is a slice of strings, so something
has to walk the one and build the other. Doing it in the library is what keeps the pair out of
every sysl signature: a `main(args: []string)` is called with the result of this, and the two
foreign types are named in one place instead of in each program that wants its arguments.

It is a module of its own rather than part of `sysl`, and the reason is what a submodule is for.
Almost no program writes a call to this: a `main(args: []string)` is what asks for the conversion,
and the entry point the compiler lays out is what makes it. A name nearly nobody writes has no
business in the set every file gets for free, so a program that does want it -- one handed an
`argv` by something other than the platform -- names `sysl.args.args_of` and says so.

It stays public for that reason and for one more: this is the only surface on which an argument
vector's *failure* can be reached at all, since a well-formed one is all a real process will ever
hand over.

It is also why it cannot live beside the platform externs in `sysl.sys`. This calls `print` and
`exit`, which are `sysl`'s, and `sysl` reaches `sysl.sys` for its printing -- putting both here
would make the two modules depend on each other, which `13 s6` refuses. What is left in `sys` is a
leaf that needs nothing, which is what a platform module should be.

## Index

[`widest_label`](#widest_label) [`args_of`](#args_of) [`cli`](#cli) [`flag`](#flag) [`help`](#help) [`long_flag`](#long_flag) [`long_option`](#long_option) [`option`](#option) [`parse`](#parse) [`parse_or_exit`](#parse_or_exit) [`scan`](#scan) [`scan_all`](#scan_all) [`short_flag`](#short_flag) [`short_option`](#short_option) [`usage_line`](#usage_line) [`Arg`](#arg) [`ArgError`](#argerror) [`Cli`](#cli-1) [`Opt`](#opt) [`Outcome`](#outcome) [`Parsed`](#parsed) [`Scan`](#scan-1) [Display for ArgError](#display-for-argerror)

## Constants

### `widest_label`

```sysl
const widest_label: usize = 22
```

A column the description text starts at, and the widest label that may push it out.

A label longer than this takes its description on the next line instead of moving every other
description to the right, which is what one `--with-a-very-long-name <PLACEHOLDER>` would
otherwise do to a table of short ones.

## Functions

### `args_of`

```sysl
args_of(argc: i32, argv: **u8) -> []string
```

The conversion itself, from the two values the platform handed the entry point.

Each run's length is found by looking for the terminator rather than by calling `strlen`, so the
conversion asks the platform for nothing beyond the two values it was handed. The bytes are then
validated and copied: a `string` owns what it holds, so an argument outlives the vector it came
from and nothing a program does to it reaches memory the platform still owns. An argument that is
not UTF-8 stops the program the way `unwrap` does, with the offset of the byte that made it
ill-formed -- `04` puts that check at the boundary, and this is one.

### `cli`

```sysl
cli(name: string, opts: []Opt, about: string = "", version: string = "", operands: string = "") -> Cli
```

A description, with everything but the options and the name optional.

The defaults are what makes this readable at the call: a program with no version and no operands
writes neither, and one with both names them (`version = …`, `operands = …`) rather than counting
commas. `reference/declarations.md § Default parameters and named arguments` is what allows it,
and the suffix rule is why `opts` comes second.

### `flag`

```sysl
flag(short: char, long: string, help: string) -> Opt
```

An option written both ways that takes no value.

### `help`

```sysl
help(spec: Cli) -> string
```

The whole help text, as `--help` should print it.

The description column is set by the longest label that fits, so a table of short options stays
narrow and one long option does not push every description to the right; a label past
`widest_label` takes the next line instead.

Nothing here is wrapped to a terminal width, which is a deliberate limit rather than an omission:
asking how wide the terminal is means asking the platform, and this is otherwise pure string work
that a program with no `os` capability can still call. Help text is written to fit.

**The column is set in screen columns rather than in bytes**, which is `columns` and not `.len`.
A label is built out of text the *program* supplied -- its long names and its `<placeholder>`s --
so a program whose placeholder is not ASCII would otherwise have its description column go ragged
by one position per accented character -- and by a *different* amount per row, so the column comes
out ragged rather than merely narrow and there is no correction to apply afterwards. That is why a
format specifier's width is the wrong thing to reach for here: it counts bytes, deliberately, so
that it means what `snprintf` means. It costs a program that prints help the width tables, about
four kilobytes; a program
that never calls `help` reaches none of it and links none of them.

### `long_flag`

```sysl
long_flag(long: string, help: string) -> Opt
```

The same two, for an option with no one-letter spelling. A word that is rarely typed, or one
dangerous enough to be worth spelling out (`--force`, `--no-backup`), is better without one.

### `long_option`

```sysl
long_option(long: string, arg: string, help: string) -> Opt
```

### `option`

```sysl
option(short: char, long: string, arg: string, help: string) -> Opt
```

An option written both ways that takes one.

### `parse`

```sysl
parse(spec: Cli, argv: []string) -> Result[Outcome, ArgError]
```

A command line read against a description.

It neither prints nor stops the program, whatever it finds -- a failure comes back as an `Err` and
a `--help` comes back as an `Outcome`, so this is the entry point a test drives and the one a
program with something of its own to say about a bad option calls.

### `parse_or_exit`

```sysl
parse_or_exit(spec: Cli, argv: []string) -> Parsed
```

The same, doing the conventional thing with what it finds.

`--help` and `--version` are answered on standard output and the program stops successfully,
because printing them *was* what it was asked to do. A command line that could not be read goes to
standard error with the usage line and a pointer at `--help`, and the status is **2** -- the code
getopt, argp and every parser after them reserve for "you invoked me wrongly", as against 1 for
"I ran and it did not work out".

It exits, and the name is what says so: a caller who would rather decide for itself calls `parse`.

### `scan`

```sysl
scan(args: []string) -> Scan
```

A cursor over the words a program was started with, with the zeroth left out.

**It skips `args[0]` itself**, because that word is the program's own path and is never an
argument -- and forgetting to skip it is the mistake this would otherwise invite, reported as a
mysterious operand that is always there. A program wanting to read a vector that is not its own,
or wanting the zeroth as well, uses `scan_all`.

### `scan_all`

```sysl
scan_all(args: []string) -> Scan
```

The same over every word given, reading nothing as a program name.

This is what a program driving a sub-command's arguments wants, or a test, or anything handed a
vector that did not come from the platform.

### `short_flag`

```sysl
short_flag(short: char, help: string) -> Opt
```

And for an option with no whole-word spelling, which is mostly what a program keeps for
compatibility with something older.

### `short_option`

```sysl
short_option(short: char, arg: string, help: string) -> Opt
```

### `usage_line`

```sysl
usage_line(spec: Cli) -> string
```

The one line a usage error is reported with.

It is separate from `help` deliberately, and the separation is the point: this goes to standard
error when a command line could not be read, and the full help goes to standard output when
`--help` asked for it. Answering a mistyped flag with forty lines of help buries the sentence
saying what was mistyped.

## Types

### `Arg`

```sysl
enum Arg
    Short(name: char)
    Long(name: string)
    Positional(value: string)
```

What one step of a command line turned out to be.

A short option's name is a `char` rather than a byte because that is what a user typed; `-é` is a
legal thing to want and costs nothing to allow. `Positional` covers a bare `-`, which by long
convention names standard input and is an operand rather than an option with an empty name.

### `ArgError`

```sysl
enum ArgError
    MissingValue(option: string)
    UnexpectedValue(option: string)
    UnknownOption(option: string)
    BadValue(option: string, value: string, want: string)
```

Why a command line could not be read.

The first two are the scanner's own and are about *shape*: a value that was asked for and is not
there, and one that is there and was never asked for. The last two belong to the layer above,
which is the only one that knows what the options are -- they live here so that a program handling
failure has one type to match on rather than one per layer.

| Member | Signature | Description |
|---|---|---|
| `message` | `message(self) -> string` | A sentence, in the terms the user wrote rather than in the parser's. |

### `Cli`

```sysl
struct Cli
    name: string
    opts: []Opt
    about: string
    version: string
    operands: string
```

A whole command line described: the program, what it is for, and what it takes.

| Member | Signature | Description |
|---|---|---|
| `find_long` | `find_long(self, name: string) -> Option[usize]` | Where an option is in the table, or `None` for one the program does not declare. |
| `find_short` | `find_short(self, c: char) -> Option[usize]` |  |

### `Opt`

```sysl
struct Opt
    short: Option[char]
    long: Option[string]
    arg: Option[string]
    help: string
```

One option a program accepts.

Built through the six constructors below rather than by writing the fields, because what varies
between them is which parts are absent -- and a call that says `flag('v', "verbose", …)` reads as
what it is, where `Opt(Some('v'), Some("verbose"), None, …)` reads as bookkeeping.

### `Outcome`

```sysl
enum Outcome
    Ready(opts: Parsed)
    HelpRequested
    VersionRequested
```

What a command line turned out to ask for.

`--help` and `--version` are reported rather than acted on, which is what keeps `parse` a function
of its arguments: nothing here prints, and nothing here stops the program. A caller wanting the
conventional behaviour calls `parse_or_exit`, which is the one that decides.

### `Parsed`

```sysl
struct Parsed
    opts: []Opt
    counts: []int
    values: []string
    positionals: []string
```

What a command line held, asked about by the `Opt` values that described it.

| Member | Signature | Description |
|---|---|---|
| `given` | `given(self, o: Opt) -> bool` | Whether the option was given at all. |
| `count` | `count(self, o: Opt) -> int` | How many times it was given, which is what `-vvv` is for. |
| `value` | `value(self, o: Opt) -> Option[string]` | The value it was given, or `None` where it was not given at all. |
| `value_or` | `value_or(self, o: Opt, default: string) -> string` | The same with the program's own default standing in, which is what most callers want. |

### `Scan`

```sysl
struct Scan
    argv: []string
    at: usize
    bundle: string
    attached: Option[string]
    last: string
    rest_are_positional: bool
```

A cursor over a program's arguments.

It is a value like every other struct, so a copy of one is a second cursor over the same words --
which is occasionally what a program wants (looking ahead without committing) and is the reason
the fields below are what they are rather than a pointer into something shared.

| Member | Signature | Description |
|---|---|---|
| `option` | `option -> string` | The option most recently reported, as it was written -- `-o` or `--output`. |
| `next` | `next(*self) -> Result[Option[Arg], ArgError]` | The next option or operand, or `None` at the end of the line. |
| `value` | `value(*self) -> Result[string, ArgError]` | The value belonging to the option just reported, from wherever the user put it. |
| `rest` | `rest(*self) -> []string` | Every word that is left, read as operands whatever they look like. |

## Implementations

### Display for ArgError

```sysl
impl Display for ArgError
```

So that `print(e)` and `f"$e%s"` say the sentence rather than the shape of the value.
