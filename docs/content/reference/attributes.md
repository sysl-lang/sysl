---
title: Attributes, annotations, and compile time
summary: `::` attributes a type answers, the four annotations a declaration takes, the three a file's header takes, and the `#if` directive that gates lines before the lexer sees them.
weight: 130
---

Three kinds of form in sysl reach into the *compilation* rather than into the running program. Each
has a name and a spelling of its own:

| written | is | read by |
|---|---|---|
| `T::Attr` | an **attribute** — a question a type's own name answers | the analyzer, at the use |
| `@test`, `@tailrec`, `@pure`, `@ghost` | an **annotation** — a fact about the declaration under it | the grammar |
| `@no_alloc`, `@requires`, `@link` | an **annotation** — a fact about the whole file, in its header | the grammar |
| `#if` | a **directive** — a gate on lines | a pass before the lexer |

**The last two are told apart by the sigil, and that is the whole rule.** An annotation is `@` and
belongs to what it is written above — a declaration, or the file itself; a directive is `#` and
gates lines. Nothing about
indentation is involved, which matters because a declaration at the margin — a `module` header — has
its annotation at the margin too, so a rule about columns would have had the two forms competing for
one position.

A directive is still written at column 1, and for its own reason: it is gone before anything counts a
column, so an indented one would look like it takes part in a block structure it has nothing to do
with. That is a rule about directives, not the thing that distinguishes them.

Annotations come in two groups, by what they attach to.

**On a function** there are four, each written on its own line above the declaration. More than one
may be stacked, and writing the same one twice is refused. `@test` and `@tailrec` are below; `@pure`
and `@ghost` belong to the specification vocabulary and are on the
[verification](/reference/verification/) page.

**On the file** there are three, in its header directly below `module` and before everything else:
`@no_alloc` and its siblings, `@requires(...)`, and `@link("...")`. These say what the whole module
may do and what its `extern`s need, so they attach to the file rather than to any declaration in it —
and writing one further down is refused with a message saying where it belongs. They are covered
under [modules](/reference/modules/) and [FFI](/reference/ffi/), where what they *mean* is.

**An annotation's name is an ordinary identifier**, which is the point of writing these as
annotations at all: nothing here is reserved, so a program may still call something `test`, `link`,
`alloc`, `no` or `requires`. `guide/slab`'s allocator calls its central function `alloc` and threads
its free list through a field called `link`.

The `@` is also read **inside a pattern**, where it binds a name to what a sub-pattern matched
([patterns](/reference/patterns/)). The two never compete: an annotation's `@` is a prefix at the
start of a line above a declaration, and a pattern's is infix between a name and a pattern, in a
position no declaration may stand.

## `::` — what a type's own name answers

A type's name is a type and not a value, so nothing is *read* from it. What it answers are
**attributes**, written with `::` rather than `.` so they stay out of the member namespace:
`Color::First` cannot be confused with a variant, an associated function, or a member an `impl`
added, and no `impl` can shadow one by declaring a member of that name.

Two kinds of type answer them, and each set is **fixed and closed**.

### A simple enum

```sysl
enum Color: u8
    Red = 2
    Green = 5
    Blue = 9

print(Color::Image(Color::First), Color::Image(Color::Last))
print(Color::Pos(Green), Color::Image(Color::Val(2)))
print(Color::Image(Color::Succ(Red)), Color::Image(Color::Pred(Blue)))
print(Color::Image(Color::Value("Blue")), int(Color::Value("Blue")))
```

```output
Red Blue
1 Blue
Green Green
Blue 9
```

| written | is | traps |
|---|---|---|
| `T::First` / `T::Last` | the first and last variant | no |
| `T::Pos(v)` | a value's **0-based position** in the declaration | no |
| `T::Val(i)` | the value at position `i` | on a position past the last, or below zero |
| `T::Succ(v)` / `T::Pred(v)` | the neighbouring value | at the last / at the first |
| `T::Image(v)` | the variant's **name**, as a `string` | no |
| `T::Value(s)` | the value a name stands for | on a name no variant has |

**Position is not the discriminant, and that distinction is why `Pos` and `Val` exist.**
Discriminants may be explicit, non-contiguous and not zero-based — `Green` above is position 1 and
discriminant 5 — so an ordinal has to be looked up rather than computed. `Pos` and `Val` are the two
directions of that lookup, and `Succ`/`Pred` walk the **declaration order** rather than adding one.
Going the other way, to a value's discriminant, is the ordinary conversion `int(c)`.

**`Image` and `Value` are the printed-name pair, and they are what makes a simple enum
observable.** The type carries no `==` and no `Display` of its own, which is why every line above
turns a value into a position or a name to look at it. A comparison is written on the discriminants,
`int(a) == int(b)`.

### A constrained subtype

The other set — `First`, `Last`, `Valid`, `Succ`, `Pred`, `Range` — belongs to a `within`-ranged
integer subtype, and is on the [errors and contracts](/reference/errors/) page beside the checking it
is the question form of.

The two sets are spelled the same way for the same reason and overlap where the questions overlap,
but they are not one set: an enum has `Pos`, `Val`, `Image` and `Value` because its values are named
and unevenly spaced, and a subtype has `Valid` and `Range` because its values are a contiguous run of
integers.

### What has no attributes

**A data enum**, because its value is a variant plus a payload, so a position, a name and a
neighbour are each questions about only half of it:

```sysl
enum Shape
    Circle(r: int)
    Rect(w: int, h: int)

print(Shape::First)
```

```error
'Shape::First' needs a simple enum, and 'Shape' carries data
```

**A generic enum**, because its name stands for no one value set until it is applied:

```sysl
enum Box[T]
    Full(v: T)
    Empty

print(Box::First)
```

```error
'Box' is generic, so 'Box::First' has no single enum to read
```

And an attribute outside the set is answered by name, since the set is closed:

```sysl
enum Color: u8
    Red
    Green

print(Color::Nonesuch)
```

```error
'Color' has no attribute 'Nonesuch'
```

## `@test` — a function with a caller nothing else has

A word after `@` that is neither `test` nor `tailrec` is answered by name rather than as grammar. The
set is closed, which is what makes a misspelling an error instead of a marker that quietly does
nothing — annotations are deliberately *not* a general mechanism yet.

The annotation goes on its own line above an ordinary function declaration, which may still be
`private`:

```sysl
add(a: int, b: int) -> int = a + b

@test
adds_two()
    assert(add(1, 1) == 2, "one and one")

@test("an empty slice has no first element")
private first_of_empty()
    assert(true, "nothing to see")

print(add(2, 3))
```

```output
5
```

That program prints `5` and runs neither test, which is the whole of the arrangement: `sysl run`
builds the program, and the tests are for `sysl test`.

Four forms of the annotation:

| written | means |
|---|---|
| `@test` | an ordinary test, named after the function |
| `@test("a sentence")` | named by the sentence instead |
| `@test(should_trap)` | the test **passes** by stopping the program |
| `@test(should_trap: "past the end")` | …and the run must have printed that text |

### What a test may be

**A test is an ordinary function with a caller nothing else has**: no parameters, no result, not
generic. All three are the same requirement from different sides, since the runner calls it with
nothing and reads the answer off whether it returned. They are checked **at the annotation**, because
the function is a perfectly good function and it is `@test` that made a promise about it:

```sysl
@test
takes_one(n: int)
    assert(n > 0, "positive")
```

```error
a '@test' function takes no parameters, and 'takes_one' takes one — 'sysl test' calls it with nothing, so there is nowhere for an argument to come from
```

```sysl
@test
generic_one[T]()
    assert(true, "yes")
```

```error
a '@test' function has no type parameters, and 'generic_one' declares 'T' — a generic is compiled for the arguments a caller fixes, and the runner supplies none
```

```sysl
@test
gives_back() -> int = 1
```

```error
a '@test' function returns nothing, and 'gives_back' returns 'int' — a test's result is whether it came back, so there is nothing to read a value with
```

### A test passes by returning

That is the whole protocol, and it is what lets a test assert **in the language it is testing**
rather than in a framework. A broken `require`, a bounds violation, an `unwrap` of a `None` — each
ends the process, and none of them had to know it was running under a test.

`should_trap` inverts the reading, for a test whose subject *is* the check. With a string it
additionally requires that the run printed it, which is what tells a trap from the **right** trap. A
silent trap satisfies `should_trap` and can satisfy no string, because a compiler-inserted check
raises a signal and says nothing — see [what stopping looks like](/reference/errors/).

### A test has one caller, and the program is not it

```sysl
run_it()
    adds_two()

@test
adds_two()
    assert(true, "yes")
```

```error
'adds_two' is a '@test' function, which 'sysl test' calls and nothing else does — every other build leaves it out, so this call would have no definition to reach. Work two tests share belongs in an ordinary function they both call
```

### What is dropped, and when

`sysl run`, `sysl build`, `sysl emit-llvm` and `sysl build-lib` all drop the tests — and drop them
**after** analysis. So a `@test` that does not compile is an error in a build that would never have
run it, and a module's capability clause reaches its tests like any other member.

That ordering is what lets a test sit beside what it tests: a library's tests do not travel in the
library, a program's do not run when it runs, and neither stops being checked.

A helper only a test calls leaves with it, because it becomes unreachable and pruning notices; a
helper the program also calls stays, because the program still calls it.

### The runner

```bash
sysl test <path>
sysl test <path> --filter <text>
sysl test <path> --fail-fast
```

**One build, one process per test.** The tree is compiled once, into a binary whose entry point takes
a test's name and runs that test alone — the program's own statements and its `main` are not run,
though its module-level `val`s are still filled, since a test reads a module's storage like any other
function. The runner then starts that binary once per test.

The process per test is not a cost being tolerated; **it is the mechanism.** A test that fails does
so by ending its process, so a run that shared one would report the first failure and nothing after
it. The compile is the slow half, and there is only ever one of it.

Exit status is 0 if and only if every test that ran passed. A tree with no tests, and a filter that
matched none of the tests there are, both exit 0 and say which happened.

### `assert` and `panic`

Both are ordinary functions in the standard module:

```sysl
panic(msg: string) -> never
    print("panic:", msg)
    exit(1)

assert(cond: bool, msg: string)
    if !cond then panic(msg)
```

They exist because `require` is a promise about a **call**, checked on entry, and a test's fifth
statement has no contract to hang a claim on — the contract was about the arguments, four statements
ago.

They stop the program the way `unwrap` does — a line naming what happened, then the hosted exit —
rather than through the trap instruction, because **a check a program makes is one the compiler
cannot see, and the message is the whole point of it.** The message is required rather than
defaulted, because the condition's source is not available to print and a failure saying only
"assertion failed" sends its reader looking for which one.

## `@tailrec` — an assertion that the frame is reused

A function whose last act is a call to itself compiles to a branch back to its own entry rather than
a second frame, so the recursion is bounded by the arithmetic and not by the stack. That happens
whether or not anything is written — see
[tail calls](/reference/declarations/#tail-calls) for what counts as the last act, and for the two
things that end a tail position.

`@tailrec` asserts the jump is there:

```sysl
@tailrec
count(n: int, acc: int) -> int =
    if n == 0 then acc else count(n - 1, acc + 1)

print(count(100000, 0))
```

```output
100000
```

What it buys is the refusal. The optimization is silent, so an edit that costs it is silent too —
until the day the recursion is deep enough to matter:

```sysl
@tailrec
count(n: int, acc: int) -> int =
    if n == 0 then acc else 1 + count(n - 1, acc)

print(count(5, 0))
```

```error
calls itself nowhere the jump can replace
```

It changes nothing about what is emitted. Write it where losing the jump silently would be a bug,
and leave it off everywhere else.

## `#if` — gating lines before the lexer

Everything a target decides is a fact the *compiler* reads about the machine. `#if` is the one place
a **program** reads one, and it exists because machines genuinely differ in ways a library cannot
paper over: a syscall number, a struct a header lays out two ways, a symbol one libc exports and the
other does not.

```sysl
#if posix
greeting() -> string = "a posix machine"
#else
greeting() -> string = "something else"
#endif

#if aarch64 && macos
width() -> int = 64
#elif x86_64
width() -> int = 32
#else
width() -> int = 0
#endif

print(greeting(), width())
```

```output
a posix machine 64
```

*(That output is what this documentation's own build produces; on another machine the same program
prints what that machine's branches say.)*

`#if` / `#elif` / `#else` / `#endif`, nesting freely, and **the branches are exclusive** — the first
whose condition holds is the one that contributes, and a group inside a branch that was not taken
contributes nothing however its own condition reads.

### It gates lines, and it gates them before anything is parsed

**A line in a branch this build is not for is replaced by an empty line, not removed**, and so is
every directive line. After the pass the file is an ordinary sysl file that happens to have some
blank lines in it, and nothing downstream knows any of this happened.

Replaced rather than removed, because **every line below a gate has to keep the number it was written
at.** Deleting them would leave the messages right and the carets somewhere else, with nothing to say
so:

```sysl
#if linux
say() -> string = "one"
extra() -> int = 1
another() -> int = 2
#endif

print(missing_name)
```

```error
:7:7
```

The caret is on line 7, which is where `print(missing_name)` is written — not line 2, where it would
be if the four gated lines had been dropped.

**A directive sits at the margin, column 1.** That is a rule and not a convention. sysl is
indentation-sensitive, and indentation is how the language reads block structure — so a gate written
*in* that channel would look like it takes part in a nesting it has nothing to do with, when in fact
the line is gone before anything counts a column. At the margin it is visibly not part of the code's
shape, which is what it is. It is also how C is written. What keeps a declaration's `@test` from ever
being mistaken for one of these is the **sigil**, not the margin — an annotation on a `module` header
sits at column 1 too.

**Why lines and not a construct wrapping declarations.** Rust spells this `#[cfg]`, an attribute on
an item, and can because Rust is brace-delimited: the attribute attaches without moving anything.
Here the equivalent would have to take an indented block, so adding or removing a platform gate would
reindent everything inside it — a one-line intent showing up as a whole-body diff. A flat marker
disturbs nothing.

### The symbols are derived from the target, and the set is closed

| kind | symbols |
|---|---|
| operating system | `macos`, `linux`, `windows`, `freestanding` |
| processor | `aarch64`, `x86_64`, `riscv64`, `x86` |
| derived | `hosted` (not `freestanding`), `posix` (`macos` or `linux`) |

That is the whole vocabulary. There is **no `#define`**, nothing a project can add, and no dependence
on a project config. A condition is a symbol, `!`, `&&`, `||`, and parentheses; `&&` binds tighter
than `||`.

`posix` is a name for the commonest disjunction rather than a replacement for writing it — `#if linux
|| macos` still says the same thing. Note that this `posix` is not the *capability* of the same name:
this one asks **is this a POSIX system**, a fact about the machine settled by the target, where the
capability asks **may this module use POSIX**, a permission a project grants and a `no posix` clause
takes away. They agree today only because nothing denies anything yet.

**A symbol nobody knows is an error, not false:**

```sysl
#if darwin
say() -> string = "one"
#else
say() -> string = "two"
#endif

print(say())
```

```error
'darwin' is not something a target says about itself — sysl knows aarch64, freestanding, hosted, linux, macos, posix, riscv64, windows, x86, x86_64
```

The set is closed, so a name outside it is a mistake rather than a fact this build happens not to
have — and a misspelling that read as *false* would gate code out of the build with nothing said.
**Silently missing code is the one failure this feature cannot be allowed to have, and it is the one
C has.**

A target's *name* is not a symbol — it has a `-` in it, which no identifier carries — and writing one
is told what to write instead, because otherwise the reader is told that `-` is not an operator,
which is true and no help:

```sysl
#if aarch64-macos
say() -> string = "one"
#else
say() -> string = "two"
#endif

print(say())
```

```error
'aarch64-macos' is a target's name rather than something a condition asks about — a condition asks about one fact of the machine at a time, so this is written 'aarch64 && macos'
```

**Every condition is checked, in the branch being taken and the ones being skipped alike.** So a
misspelling in the Linux half is caught by a macOS build, which is where it would otherwise sit until
somebody built for Linux:

```sysl
#if linux
say() -> string = "one"
#elif nosuchmachine
say() -> string = "two"
#else
say() -> string = "three"
#endif

print(say())
```

```error
'nosuchmachine' is not something a target says about itself
```

That refusal comes from a build whose `#if linux` was already false and whose `#else` is the branch
being taken. The condition on the way past was still read.

### What is given up, and what is not

**The inactive branch is never syntax-checked.** That is the price of gating text rather than trees,
it is C's price too, and a Linux branch can therefore rot while the macOS build stays green. What
finds that is a build for each target — a thing to *run*, not a thing to design around. The
conditions themselves are the part that is checked everywhere, and they are the part where a mistake
would otherwise be silent.

**The gate runs before anything knows what a string or a comment is**, so a line that begins at the
margin with a directive word is a directive even inside a text block or a block comment. Recognizing
those would mean a second copy of the lexer's rules about literals, in a place where the two could
drift with nothing to notice — a worse defect than this one. The margin rule is what keeps it rare: a
text block written anywhere but the top level is indented in the source, whatever its value turns out
to be.

### The library is subject to it too

`lib/sysl` is sysl source, so it may gate on the machine like any other — which makes "the standard
module" a question with a target in it, and the compiler's carried copy is parsed per target
accordingly.

The one thing held fixed is that **a name the compiler spells for itself is declared on every
target**. A library that gated `Option` away for Windows would be a library nothing compiles against
there, so that is refused in a registry-wide check rather than at the first `?` somebody writes.

And **an artifact records the target it was built for** and is refused by a build for another,
because the trees a library ships are now a per-target answer.

## What is deliberately absent

| absent | why |
|---|---|
| a general annotation mechanism | the set is closed: `@test`, `@tailrec`, `@pure` and `@ghost` on a declaration, `@no_<capability>`, `@requires` and `@link` on a file. What would make it general — a `packed` struct layout, an alignment annotation — is not designed |
| `#define`, or any project-supplied symbol | the `#if` vocabulary is derived from the target and closed, which is what makes an unknown symbol an error rather than a false |
| a `#if` that asks about a capability | a condition asks what the *target* says; what a project permits is a different question, left with the config that would define it |
| a test framework in the library | a test asserts in the language it is testing, and passes by returning |
| a release mode that drops a check | see [errors and contracts](/reference/errors/) — every check is in every build |

---

That is the language. What ships beside it is the [standard library](/library/), which is a section
of its own — nothing in it is a language feature, and every type in it is one a program could have
written.
