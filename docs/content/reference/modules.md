---
title: Modules
summary: A module is a directory — visibility, imports, the acyclic graph, where a program starts, and separate compilation.
weight: 100
---

**A module is a directory** of source files, and its name is that directory's path relative to the
project root, with the separators read as dots. The files under `oskit/arch/` make up the module
`oskit.arch`; those under `std/fs/` make up `std.fs`. Every declaration in every file of the
directory is a member of the one module, so **splitting a growing module into more files adds no new
module and changes no import.**

Each file states which module it contributes to, in a header, and the compiler checks the declared
name against where the file sits:

```sysl
module oskit.arch

halt()
    print("halted")
```

That is Scala's `package` and Go's directory-package. It pays off directly for the target: an OS
subsystem *is* a directory — an arch layer, a server, a driver — so the module is the subsystem, and
the subsystem is the unit an importer depends on and the unit a capability clause narrows.

**The files of one module must all name it the same way**, because the module is the directory, so
its name is a property of the directory rather than of any file in it. Each file is held to the name
its *location* gives it, which is the stronger rule: the file that strayed is reported on its own
line rather than as a disagreement with whichever sibling happened to be read first.

**A file with no header is in the anonymous root module**, whose name is the empty path. That is what
lets a program be one file with no ceremony — the one-file case is not a special form, it is a module
that happens to be unnamed. And because its name is empty, **nothing can name it**: its declarations
are visible to its own files and to nothing else. That is the right way round for the place a program
starts — the root reaches down into the modules it is built out of, and they do not reach back up.

**A module and a type of its parent may not spell one path.** A dotted reference takes the longest
prefix that names a module, so a module `geom.Point` alongside a type `Point` in `geom` would take
`geom.Point.dist` outright and leave the type's member no spelling at all. The two stay distinct
declarations either way — what collides is the path a program writes — so the second is refused with
a diagnostic rather than settled by a silent choice.

## Visibility

A top-level declaration is **public by default**. Two modifiers restrict it, and they are one keyword
with an optional scope:

| form | visible to |
|---|---|
| `private` | this file |
| `private[own_module]` | every file of this module |
| `private[ancestor]` | the named ancestor module and its whole subtree |
| *(unmarked)* | any module that imports it |

```sysl
module oskit.arch

exported() -> int = 42

private lookup(fd: int) -> int = fd

private[arch] reset(c: int) -> int = c

private[oskit] struct FrameHeader
    magic: int
end FrameHeader
```

**`M` resolves innermost-outward.** The argument is a **simple name**, not a path, matched against the
enclosing module names from the declaring module outward, first hit winning — so `private[geom]`
inside `geom/mesh/geom/tri` binds to the nearer `geom`. A name matching no enclosing module is an
error; there is no way to name an unrelated module, so a visibility scope is always a **contiguous
subtree containing the declaration**. Because the name is resolved where it is declared, moving a
subtree elsewhere does not change what its internal annotations mean.

**Why file-scoped and not module-scoped.** This is a deliberate divergence from Scala, where `private`
means the enclosing class or package. Making the bare form file-scoped costs nothing in
expressiveness — module-private is exactly `private[own_module]`, the degenerate case of the scoped
form — and it buys the one level that provably **never crosses a file boundary**, which is the level
at which a declaration can be fully inferred and LLVM `internal` linkage applies. The cost is honest:
the everyday module-internal helper is `private[arch]` rather than a bare `private`, so the common
case is the wordier one.

**There is no `pub` keyword.** Rust makes a declaration private until `pub`; Scala and Kotlin make it
public until restricted, and that is the precedent here. The low-ceremony common case — a small module
whose declarations are meant to be used — writes no modifier, and encapsulation is the deliberate act.

### A restriction is about naming, not about existence

**A modifier decides who may write a name; it never makes a second namespace.** A file-private
declaration still belongs to its module and still spends its name there, so a sibling file cannot
declare something else of that name. The five declaration forms take a modifier; an `impl` takes
none, having no name for one to restrict; and an **enum's variants carry the enum's own**, since a
type nobody outside may name is not one whose variants they may construct.

**A name a file may not reach is not a candidate for it.** Resolution passes over one and goes on
through the file's imports rather than stopping there — a file that wrote `import util.width` said
which `width` it meant, and a sibling file's private helper of that name is not an answer to it.
Where nothing else answers at all, the restriction is then reported, because at that point it is the
whole story and a better one than an undefined name.

**A wildcard offers only what is visible; a selector is refused where it is not.** A wildcard has
claimed nothing, so a name it cannot see is simply not among what it brings in, and cannot make
another module's name ambiguous either. Naming something deliberately is the opposite case, and being
told at the import that it is private is more use than an undefined name at every shorter spelling it
would have bound.

### A declaration may not be more visible than the types it names

**What a declaration says about itself has to be as nameable as the declaration is.** A private struct
beside a public function returning it would hand every module a value of a type none of them may
name: they could hold it, pass it on, and read its fields, and the one thing they could not do is
write the type down.

```sysl
private struct Point
    x: int
end Point

make() -> Point = Point(1)

print(make().x)
```

```error
'make' is public, but its result names 'Point'
```

The comparison is between two **reaches**, and every reach is a contiguous region because
`private[M]` may only name an enclosing module: a type restricted to a subtree may stand in a
signature restricted to that subtree or to anything inside it, and never the other way round. A
bare-`private` declaration is exempt in every case — it is read in one file, and a type it can name at
all is visible there.

**"Signature" is the shorter word for it, and the rule is not about signatures.** It is about
everything a declaration says about itself, so it reaches the forms that have no signature at all: a
field, an enum variant's payload, a type parameter's bound and its default, and the declarations that
are a **name and one type** — a module-level `val` and an `extern` variable. It reaches everything a
caller has to be able to write: a struct's fields and a variant's payload, since neither has a
visibility of its own; a **type argument**, since `Box[Point]` names `Point` as much as a bare `Point`
does; a trait behind a memory mode; a member of a type or a trait; and a **bound**, since a trait a
caller cannot name leaves it unable to say what is being asked of it.

A `const` is spared only because it cannot reach the question — a constant is held to being a scalar,
and every scalar is a builtin nobody may restrict.

**An `impl` block is outside the rule, in both directions.** Implementing a private trait for a public
type adds a member nobody outside can ask for by trait; implementing a public trait for a private
type makes a public promise about a type that stays unnameable. Neither leaks a name, and a private
type reaching a caller *through* a trait's signature is a leak in the trait, which is where it is
reported.

**Rust refuses this and Scala allows it; this follows Rust.** The refusal is what makes `private` mean
something a reader can rely on, and it is additive in the safe direction: forbidding it now rules out
nothing a later rule would have had to keep allowing, while allowing it and tightening later would
break programs.

### Anything visible outside its file states its types

**A declaration visible beyond the file that declares it carries explicit types.** Inference is
available only at the bare-`private` level — the one level that provably never crosses a file boundary
— which is why the two rules are really one.

Most of it the syntax already enforces: parameter and field types are mandatory, and a return type is
written or its absence *means* `unit`. What the rule genuinely binds is the two declarations that are
a name and a type — a `const` and a module-level `val`, both below.

**Why: it makes interface extraction parse-only.** A file's exported surface can be read off its
syntax tree without resolving a name, checking a body, or having compiled anything the file imports.
That is what a fast, parallel, and eventually incremental build rests on. Scala infers types for
public members and pays for it with a far heavier extraction step; this is a deliberate divergence,
and it is cheap here precisely because sysl's signatures were already explicit for other reasons.

## Imports

A module reaches another module's members two ways, and **the first needs no import at all.**

```sysl
import sysl.math.{max, min}

print(max(2, 7), min(2, 7), sysl.math.max(10, 3))
```

```output
7 2 10
```

**A member is always reachable fully-qualified** by its module path. Nothing is required to *see* a
public member; an import exists only to **shorten** the reference.

An import is a dotted path through the module tree, and **how the path ends decides what you get**:

```sysl
import sysl.math.max              // max(a, b)      — one member, unqualified
import sysl.math.{max, min}       // several
import sysl.math.*                // every public member
import sysl.math.{max as bigger}  // a member, renamed
import sysl.math.max as bigger    // the same, unbraced
import sysl.math                  // math.max(a, b) — the module itself
import sysl.math as m             // m.max(a, b)    — the module, renamed
```

Those are Scala 3's import forms unchanged. The **unbraced `as`** belongs to the bare-path form alone,
where exactly one thing is being named: after a wildcard there is nothing for one word to rename, and
a selector list carries its own `as` per name, so both are refused rather than quietly ignored. It
renames whatever the path turned out to name — a member or a module — because which of the two it is
is settled by the same longest-prefix rule everything else uses, and a reader wanting a shorter word
should not have to know the answer first.

**A module brought in by name is a prefix wherever a written path is:**

```sysl
import sysl.math as m

print(m.max(4, 9))
```

```output
9
```

`import sysl.math` makes `math.max(p)`, `math.Float`, and `[T: math.Float]` all work, because the
leading segment of a dotted reference is read through the imports where it is not already a module.
Two rules keep those from ever both applying: a module reached by the name it already has asks for
what is already true and binds nothing, and **an import may not be given a name that a module path
already begins with**. The second is a refusal rather than a precedence rule on purpose — a binding
that is both would make `fs.read` mean one thing in a file that imported `fs` and another in the file
beside it, and `as` costs one word.

### Resolution

An unqualified name is looked for **in the module it is written in, then among the file's imports,
then in the library**, and nowhere else. A sibling module's names are not in scope unqualified, and
neither are the root module's, which have no path to be reached by at all.

**Resolution is innermost-first.** A local binding shadows an imported name; the fully-qualified path
is always available to break a tie or reach a name deliberately not imported.

**The three steps rank a name by where it was written, not by what kind of thing it is.** A function,
a `const`, a module-level `val`, an `extern` variable and an enum variant are different kinds of
declaration, and a bare name may be any of them — a program's own answers before an import's, and an
import's before the library's, whichever kind each one is. The library declares a `stdout()`; a
program that declares storage of that name reaches its own, and the library's is still there under
the path that names it.

```sysl
val stdout: int = 7

sysl.stdout().write("the library's\n".bytes)
print(stdout + 1)
```

```output
the library's
8
```

| situation | result |
|---|---|
| a wildcard offers a name that is also defined locally or imported selectively | the more specific one wins |
| two wildcards both offer one name | an *unqualified* use is a compile error naming both |
| two selectors bind one name, or two statements do | reported at the second import |

**A wildcard offers a name; a selector binds one.** That is the whole of the difference: a wildcard
neither collides with a selective import of a name it also offers, nor with a second wildcard over the
same module, because it has claimed nothing.

```sysl
import sysl.math.*

max(a: int, b: int) -> int = 99

print(max(1, 2))
```

```output
99
```

Binding one name twice **is** a mistake, and is reported at the second import rather than at whichever
use first found two answers:

```sysl
import sysl.math.max
import sysl.math.max

print(max(1, 2))
```

```error
'max' is already imported
```

**The standard module counts as one of those wildcards.** `sysl` is auto-imported into every file, so
a written `import a.*` where `a` declares an `Option` of its own is the two-wildcard case, and an
unqualified `Option` in that file is a compile error naming both — it does not shadow the library's.
That is deliberate: the alternative is a precedence tier that makes the library quietly lose to
whatever a program imported, which is the same silent-capture problem an explicit conflict is being
reported to avoid.

**Every step is filtered by visibility, the library's included.** A member the library keeps to itself
is not an answer to a program's bare name, exactly as a sibling file's private helper is not.

**A dotted reference names a module by the longest prefix of it that is one.** A program holding both
`a` and `a.b` reads `a.b.f` as `a.b`'s `f` rather than as `a`'s `b`. Everything left of the module
prefix is the ordinary form — `read(…)`, `Point(…)`, `Shape.Circle(…)` — which is why qualified access
needed no second resolution path beside the unqualified one.

**An import binds a name, not a kind.** The same spelling may be a type in one module and a function
in another, so what an import records is which path a name stands for; which of them a use meant is
settled by what that position asks for. One import therefore serves a type, a function, a trait, and
an enum variant without saying which it expected to be.

### Where an import may stand

Imports normally sit just below the `module` header, but — following Scala — **an import may also
appear inside a block**, scoped to it, for the case where a name is wanted in one function only:

```sysl
widest(a: int, b: int, c: int) -> int
    import sysl.math.max

    max(max(a, b), c)

print(widest(3, 11, 7))
```

```output
11
```

A block import lasts as long as the block's local bindings do and shadows whatever the file imported
under the same name. It **takes effect where it is written**: the statements above it have imported
nothing.

**An import is not an executable statement**, whatever it looks like — it binds a name and runs
nothing, so a file may import freely without becoming the one file of the program that carries its
statements.

**A file's imports are not its dependency list.** Because a qualified reference needs no import, a
file can depend on a module without naming it in any header — the dependency appears only in a body.
Two consequences follow, and they are the price of the convenience above: building the module graph
requires **parsing** rather than a header scan, and the cycle check below needs real **resolution**
rather than a textual match, since a local named `std` makes `std.fs` a field access and not a module
reference.

## Capabilities are a module property

A **capability annotation narrows the module**, and it is written in the file header below `module`,
each on a line of its own:

```sysl
module oskit.arch
@no_alloc
```

Because the module is the directory and the capability is a property of the module, **a narrowing
must appear consistently in every file of the module** — a module whose files disagree is rejected.
The redundancy buys local legibility: you can never open a file in a `@no_alloc` module and fail to
see that it is one. A file that declares no module may still carry one, since the anonymous root
module is a module like any other.

The other direction is `@requires(...)`, which takes a **list** because a module often needs more
than one capability at once — `sysl.thread` is `@requires(threads, posix)`.

**They are annotations rather than grammar**, which is what keeps `alloc`, `no` and `requires`
available as ordinary names; see [attributes](/reference/attributes/).

**`@link` is the header's other inhabitant and is deliberately not held to agreeing.** `@link("z")` names
a library the file's `extern`s need, and the files of a module may each name their own — because what
is being described differs. A capability is a property of the whole module, so files that disagreed
would describe different modules, while a link requirement is a property of the `extern`s in *one*
file.

**Propagation is over the module graph.** A module's effective requirement is its own uses plus the
requirements of every module it imports, transitively, and the whole graph must fit the target. A
`@no_alloc` module importing a `@requires(alloc)` module is an error **at the import**, not deep in
codegen. Because the graph is acyclic, propagation is a **single sweep in reverse topological order**
— each module's requirement set is final before any importer of it is visited — rather than an
iterated fixpoint.

Two of the capabilities are checked differently, and the difference is worth knowing:

- **`alloc` is finer than the declaration**, and the standard library is why. Inferring it per module
  would put the whole of `sysl` on one side of a line that runs through the middle of it, since
  `print` allocates nothing and `from_utf8` does — so the inferred half is asked of **what a module
  calls** rather than of which modules it depends on.
- **`os` and `posix` are exactly the declaration**, since they gate which modules *exist* rather than
  what the language allows. The edge the rule is stated over is the **reference** graph rather than
  the import graph, which is load-bearing: a qualified path reaches another module with no import at
  all, so a rule about imports would have missed the shorter of the two ways to write the mistake.

## Platform selection rides the same name

A module's members may be **split across platform-specific files** — `cpu.aarch64.sysl` and
`cpu.x86_64.sysl` contributing to the same `oskit.arch` — with the active target selecting which file
contributes. **The module name is unchanged by the platform suffix**, so importers write
`import oskit.arch` and name its members regardless of which platform's file was compiled in. The
file axis lives *below* the module name, not beside it.

## The module graph is acyclic

**Two modules may not depend on each other**, directly or through a chain. The dependency graph is a
DAG, and a cycle in it is a compile error naming the modules on the cycle. This is Go's rule, and a
deliberate divergence from Scala, where a package's compilation units may depend on each other freely.

**The graph is over references, not over imports.** A member of another module is reachable by its
full path with no import at all, so a file can depend on a module its header never mentions. An edge
is whatever *resolution* found — a call, a type named in a signature or a field, a trait named as a
bound or behind a memory mode, a variant, a generic instantiated from elsewhere. An import contributes
an edge of its own on top of those, because a file's imports are meant to be readable as what it
needs, and a dependency that came and went with a use would not be.

The **anonymous root module** sits outside the graph: nothing can depend on it, having no name for
another module to write.

**The standard module does not sit outside it.** Writing `print` records an edge on `sysl` like any
other reference, and that is deliberate. For a program the edge is inert — nothing in the library can
point back at a program's module, so it can never close a cycle — but *within the library* it is the
whole of what keeps the split honest. `sysl` reaches `sysl.sys` for the C functions its printing is
built on, so `sysl.sys` may name nothing of `sysl`'s, which is why it holds the externs and nothing
else. Read as edges: `sysl.sys` needs nothing; `sysl` reaches `sysl.sys`; `sysl.buf` reaches `sysl`;
`sysl.text` reaches `sysl` and `sysl.buf`; `sysl.io` reaches all four. **Every edge runs away from the
standard module and none runs back**, which is what makes any of them removable from a program that
never asks.

Three things follow from acyclicity:

- **Modules can be compiled in dependency order, and independent modules in parallel.** A topological
  sort exists, so a module's imports are all fully known before it is checked. A cyclic graph would
  force the whole strongly-connected component to be checked as one unit — which is the same as saying
  it was never really more than one module.
- **Capability propagation is a sweep, not a fixpoint.**
- **A cycle is a design error, and the fix is cheap.** Two directories that need each other are either
  one module drawn along the wrong line — merge them, which changes no import, since a module is a
  directory and its file count is not part of its name — or they share something that belongs in a
  third module both import. Neither fix costs an importer anything.

**Within a module, cycles are free and carry no ceremony.** All files of a directory share one scope,
so mutually recursive functions and types across sibling files need no forward declaration and no
ordering: the analyzer collects **every signature in the module before it checks any body**. The
restriction is on the directory graph, never on how a module's own files refer to one another.

## Where a program starts

A top-level **statement** is not a declaration. A declaration is hoisted and belongs to the module as
a whole; a statement runs, and running happens in an order — and a module's files have no order at
all, being one unordered scope. So **one file of a program carries the statements it runs**, and a
second that carries any is an error naming both.

A program may also declare `main`, which runs **after** those statements:

```sysl
print("initialization")

main(args: []string)
    print("then main, with", args.len, "argument")
```

```output
initialization
then main, with 1 argument
```

Both halves are real and neither replaces the other. The top-level statements are the program's
**initialization**: they are where a top-level `var` lives, and they are what the `val` order below is
settled against. `main` is what runs once that is done.

**What `main` gets at that a statement cannot is the arguments.** A statement has nowhere to receive
them — it is not a call, so it has no parameter list, and a program's arguments are not a module-level
anything. There are exactly two signatures:

```sysl
main()
    print("no arguments wanted")
```

```output
no arguments wanted
```

**`args` is a slice of `string`.** What the platform hands a program is C's pair — a count and a
vector of NUL-terminated byte runs — and neither appears in a sysl signature anywhere. The library
converts the pair, finding each run's end, validating its bytes, and **copying** them into strings the
program owns, so an argument outlives the vector it came from and holds no memory the platform is
still responsible for. The zeroth element is the program's own path, because that is what the platform
passes. **An argument that is not UTF-8 stops the program**, with the offset of the byte that made it
ill-formed.

**`main` names one function in a program**, wherever it is written — even in two different modules,
where nothing else would collide, and even at the two signatures above:

```sysl
main()
    print(1)

main(args: []string)
    print(2)
```

```error
function 'main' is already declared
```

That is the reason C reserves the name: it is not a name the program calls, it is the name the
*platform* calls, so two would leave which one the program **is** to whichever was emitted last.
Otherwise it is an ordinary function, and may be called by the program too.

**A result is not yet spelled:**

```sysl
main() -> int = 0
```

```error
'main' yields nothing, so it may not result in int — a program's exit status is not something a signature can say
```

A program's exit status is a whole question — which values mean what, what a trap leaves behind,
whether a `val` initializer can set one — and reading a result type as one now would answer it by
accident. Until it is decided, a program that must choose its status calls `exit`.

**A program in which no file carries a statement is a complete program that does nothing.** The entry
point exists, runs nothing, and succeeds. That is what a tree of pure declarations compiles to, which
is what it should compile to — a library is not an error.

### A top-level `var` is a local of the entry point

It is scoped to the entry point and initialized in its order, and it is **not a member of the module**
— so a function declared in the same file cannot see it:

```sysl
var counter = 0

bump()
    counter += 1

bump()

print(counter)
```

```error
undefined name 'counter'
```

**There is no module-level `var`, when there is a module-level `val`**, and the reason is that the
keyword is taken. Every program that writes statements at the top of a file already uses `var` there
to mean a local of the entry point, and redefining it as a module member would not fail to compile —
it would silently change name resolution, those bindings becoming visible to every function in the
module. That is a breaking change that still builds, which is the worst kind.

## `const` — a value

A **`const` is a module member**: hoisted, order-free, visible to the whole module and beyond it under
the ordinary rules, and never running at all.

```sysl
const capacity: usize = 512
const window: int = 1 << 15

type Slot = usize within 0..<capacity

var table: [capacity]u8 = [0u8; capacity]

print(table.len, window, capacity)
```

```output
512 32768 512
```

**Why it exists at all**, when a nullary function already serves every use in an expression: an array
bound is a compile-time constant and a call is not. Without `const`, a program carries `[512]u8` next
to a `capacity()` function and a comment asking the reader to keep the two in step.

**The type is always written** — not because it could not be inferred from the initializer, but
because the rule that anything visible outside its file states its types is what keeps interface
extraction parse-only. Writing it is also what fixes the literal's type, so `const capacity: usize =
512` needs no suffix on the `512`.

**A constant expression** is a literal; a `const`; a conversion; a unary `-`, `!` or `~`; or a binary
arithmetic, bitwise, shift, or comparison operator applied to constant expressions. Integers, floats,
`bool` and `char` fold.

**There are no calls**, and a `string` constant's initializer must be a **literal** — since `+` on
strings allocates, and a compile-time concatenation would be a different operation wearing the same
spelling:

```sysl
size() -> int = 4

const called: int = size()

print(called)
```

```error
the value of 'called' is not a constant expression
```

A function call in a constant expression is a request for compile-time evaluation of arbitrary code,
which is a language of its own.

**Where a constant may stand:** anywhere an expression may, plus the four places a literal was
previously the only thing accepted — an **array bound**, an **enum discriminant**, a **pattern**, and
the bounds of a **`within` range**:

```sysl
const Limit: int = 3

enum Op
    Halt = Limit
    Push

classify(n: int) -> string
    n match
        Limit -> "at the limit"
        else     "other"

print(classify(3), classify(1), int(Op.Halt), int(Op.Push))
```

```output
at the limit other 3 4
```

All four go through the **same fold**, which is the property worth keeping: they accept the same
expressions because they ask the same question, rather than several grammars agreeing by coincidence.

The pattern position is the one worth saying out loud, because it is where other languages have come
to grief: a name in a pattern binds unless it resolves to something, and Rust's rule that a lowercase
`const` in a pattern *binds* instead of matching is a documented trap. Here it cannot arise — a
pattern name already resolves against the enum variants in scope before it is taken as a binding, and
a constant joins that same resolution rather than adding a second one.

**A constant has no address.** It is folded into each use and occupies no storage, which is why it
needs no initialization order, why a `no alloc` module may hold one, and why `&capacity` is not a
thing to write. That is also what rules it out for a **table**, which is indexed at a value only known
while running and therefore has to be somewhere.

**A cycle between constants is reported at the declaration**, naming the loop:

```sysl
const a: int = b
const b: int = a

print(a)
```

```error
constant 'a' is defined in terms of itself: a → b → a
```

Two things a constant is **not**. Not an **enumeration** — a set of related named values is a simple
enum, which is the type-safe replacement for a pile of constants; if a second constant would be the
obvious neighbour of the first, the declaration wanted was an `enum`. And not **const generics** —
parameterizing over a length stays deferred, though this is its prerequisite, since a value cannot be
passed as an argument before it can be named.

## `val` — a thing

A **`val` is a thing, where a `const` is a value.** Written at the top of a file it is a module member
— read-only storage that exists for the whole run — and written inside a block it is a local, the
immutable counterpart of `var`, in the same frame with the same lifetime. One keyword at both levels,
because it is one idea at both.

```sysl
val order: [8]usize = [3usize, 1usize, 4usize, 1usize, 5usize, 9usize, 2usize, 6usize]

at(i: usize) -> usize = order[i]

var i = 0usize
var total = 0usize

while i < order.len
    total += at(i)
    i += 1usize

print(total, order[2])
```

```output
31 4
```

**The whole difference from a `const` is an address.** A constant is folded into every use, which is
what lets it size an array and what stops it from being one. A `val` sits somewhere, so it may be
indexed at a value only known while running, iterated, and reached into. The rule for a reader is
short: **if it has to be indexed or pointed at, it is a `val`.**

Because it is read while running, a `val` is the one module-level name that cannot size a type or
stand in a pattern:

```sysl
val limit: usize = 4

var table: [limit]u8 = [0u8; 4]

print(table.len)
```

```error
an array length must be a constant — a literal, or a 'const' naming one
```

Naming one in a pattern is an error rather than a quiet binding, for the same reason — there is no
value at compile time for a pattern to compare against, and the diagnostic names what to write
instead:

```sysl
val limit: usize = 4

check(n: usize) -> string
    n match
        limit -> "at the limit"
        else     "other"

print(check(4usize))
```

```error
'limit' is a 'val', which is read while the program runs, so a pattern cannot match against it — compare it in a guard, or bind a different name
```

The rest of what a module-level `val` may hold — read-only at every depth, nothing that owes a
release, and the `[]const T` a slice of one yields — is on
[declarations](/reference/declarations/#val).

## Separate compilation

A library is built into one file and linked against, rather than recompiled by everything that uses
it:

```
sysl build-lib mylib -o mylib.syslib     # compile the library once
sysl run prog.sysl --lib mylib.syslib    # link a program against it
```

**`--lib` takes either an artifact or a source tree**, and which one is read off the name. How a
library shipped is the shipper's business; a program that depends on one should not have to write down
which form it got. Given a source tree the library is simply *more modules*, and the rules at the top
of this page do the rest.

**An artifact has two halves, and the split is the whole design.** A declaration with **no type
parameters** is compiled ahead of time into object code by whoever built the library, and a program
that calls it declares the symbol and links the body. A **generic** has nothing to compile until a
caller fixes its type arguments, so it crosses as the tree it was parsed into and is monomorphized in
the consuming program. Rust's `.rlib` makes the same split for the same reason.

The metadata carries **every** declaration, not only the generic ones: a call into the precompiled
half still has to be type-checked, and the tree is where the signature is. What the symbol list adds
is which of those the consumer must declare rather than emit a second time.

Five consequences, each a thing a reader would otherwise have to discover:

- **An artifact is for one machine**, and *both* halves pin it. The object half obviously does; the
  tree half does because a library may gate on the machine it is built for, which makes two artifacts
  built from one source two different sets of declarations. So an artifact records its target and is
  refused by a build for another — refused rather than left to the linker, which would eventually
  complain about object formats in a message saying nothing about which library or why.
- **A library carries no entry point.** A `main` of its own would collide with the one belonging to
  whatever links it.
- **Nothing is pruned when a library is built.** A program is lowered from `main` outwards because
  what it cannot reach is dead; a library has no `main` and every public declaration is a potential
  entry, so all of them are emitted and the *linker* discards what a given program never calls.
- **A library defines its own declarations and nobody else's.** A library that prints reaches the
  library's own printing surface exactly as a program does — but emitting *those* would put a copy in
  every artifact, so two libraries that both printed could not be linked into one program. They are
  declared in the artifact and defined in the consuming program.
- **A library may not sit in the anonymous root module.** A library is reached by naming its module,
  and the root module has no name, so nothing depending on it could write a path to what it declares.

**The container is an `ar` archive**, which is what an `.rlib` is and for the same reason: the linker
already reads one, so the compiled half needs no unwrapping and a member is pulled in only to resolve
something a program actually left undefined. The metadata rides inside it wrapped in a real object
file, as one `private` constant in a section of its own, so nothing ever gives the linker a reason to
pull it in and it costs the linked program nothing.

**The standard module is built the same way, and linked by default.** Which library a compilation is
compiled against is a *parameter* of it rather than an ambient fact — which is what lets two cores be
handed to two compilations and compared — but the parameter has a default, and the default is found
rather than named. **A compilation that finds no standard module at the default path builds one**, in
well under a second, announcing it on stderr. That is not the silent substitution a compiler must
never make: a rebuild compiles against *this* library, from its own source, held to the same
fingerprint on the way back in. Nothing is substituted, so there is nothing to be misled about.

**The standard module's source ships with the compiler and is read off disk** — `share/sysl/lib`
under the install prefix, found from the binary's own location the way `rustc` finds its sysroot.
Running out of a checkout, it is `lib/` in the tree. You can read it, and you can edit it: a changed
file changes the library's fingerprint, so the next compilation builds an artifact of its own rather
than picking up a stale one. `SYSL_LIB` names a library root outright, which is what a broken install
needs and nothing else does. A compiler that cannot find its library names every path it tried.

The default path is keyed by a fingerprint of the library, so every compilation of the same library
on the machine finds the same artifact — and a rebuild therefore **publishes by rename**: it is
assembled beside its destination and moved onto it. Two builds may run at once; a reader gets the
whole of one artifact or the whole of the other, and a rebuild that fails leaves the one that was
already there.

An artifact **named** on the command line is never rebuilt, and one that cannot be read stops the
compilation — corrupt, truncated, built by another sysl, or built from other sources. Someone who
wrote down which standard module to compile against is owed the truth about that one.

## What is deliberately absent

| absent | why |
|---|---|
| a file as a module | the file is a contribution, not a unit; there is no per-file namespace and no import of a file |
| a `pub` keyword | public is the unmarked default; its absence *is* public |
| relative or wildcard-path imports | an import names a module by its full dotted path from the project root, so a reference means the same thing wherever the importing file sits |
| auto-import beyond the standard module | a module earns visibility by being imported or fully qualified |
| implicits — Scala's `given`/`using` | a term selected by *searching* the scope would let a change anywhere alter what resolves in a file that did not change, so a module's interface would no longer bound its blast radius |

The last is the sharpest of them. Scala pays for implicits with per-file used-name tables and API
diffing, and that machinery is the **cost of the feature** rather than an implementation detail of it.
The one search sysl does perform, for a trait `impl`, is bounded to two modules by
[the coherence rule](/reference/traits/#where-an-impl-may-live) for exactly the same reason.

---

Next: [errors and contracts](/reference/errors/).
