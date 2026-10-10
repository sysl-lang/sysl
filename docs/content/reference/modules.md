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

### A file-private name is scoped to its file

**A bare `private` restricts the namespace as well as the reach**, so two files of one module may
each declare their own `Limit`:

```sysl
// m/one.sysl
module m
private const Limit: usize = 1
from_one() -> usize = Limit
```

```sysl
// m/two.sysl
module m
private const Limit: usize = 2
from_two() -> usize = Limit
```

Each file names its own. That is the same position Rust, C and Go take for their file- and
crate-private equivalents — a `static` in one C translation unit does not collide with a `static` of
that name in another — and it is what the modifier is *for*: the reason to keep a helper to its file
is that `Limit`, `helper`, `check` are local matters, and a rule that hid the name while still
spending it left a module of any size with `MaxCallDepth` beside `MaxHashDepth` for two bounds that
were each one file's business.

**Inside one file it is still a duplicate**, which is the ordinary case and is unchanged:

```sysl
private const Limit: usize = 1
private const Limit: usize = 2

print(Limit)
```

```error
constant 'Limit' is already declared
```

**And a private name SHADOWS a sibling file's public one of that spelling.** Inside the file that
declares it, its own declaration answers; every other file of the module, and every importer, sees
only the public one — a private declaration is never exported. It is C's `static` in one translation
unit beside an `extern` of the same name in another:

```sysl
// m/pub.sysl
module m
helper() -> string = "public"
from_pub() -> string = helper()
```

```sysl
// m/priv.sysl
module m
private helper() -> string = "private"
from_priv() -> string = s"${helper()} ${m.helper()}"
```

A program calling `m.from_pub()`, `m.from_priv()` and `m.helper()` prints `public`, `private
public` and `public`: the second file's bare `helper` is its own, and **the module's path reaches the
public one from there** when that is what the file means. Both functions are compiled, each under a
symbol of its own.

**Between two functions, a private one shadows only what a call could not tell from it.** The pairs
[Overloading](/reference/declarations/#overloading) refuses inside one file — the same parameters, or
a difference hidden behind a default — are a shadowing across two, and every other public overload
stays a candidate in the private file beside its own: a private `helper(s: string)` next to a public
`helper(n: int)` makes an overload set there, exactly as two declarations in one file would. **Any
other pairing hides the whole spelling** — a private constant beside a public function of its name
leaves nothing for a use to tell apart. A generic declared in another file is resolved in *its*
file, so a private `helper` never reaches the body of a public generic that calls `helper()`.

**What stays a duplicate is one spelling declared twice where one file sees both**: twice in one
file, whatever the two visibilities —

```sysl
private const Limit: usize = 1
const Limit: usize = 2

print(Limit)
```

```error
constant 'Limit' is already declared
```

— and two public declarations of one spelling anywhere in the module, which every file may write.

**An enum's variant is not a declaration of its spelling here, so a private struct does not shadow
one.** A struct and a variant of one name never contended — they coexist in one file, and at each use
the expected type decides which answers — so a private `struct Circle` beside a sibling file's public
`enum Shape` with a `Circle` variant leaves both as candidates in the struct's own file: `Circle(7)`
with nothing expected builds the struct, and `area(Circle(5))`, where `area` takes a `Shape`, builds
the variant. Every other file sees only the variant.

**A scoped `private[M]` spends its name across the subtree it reaches**, since that is exactly the
set of files that can write it. The five declaration forms take a modifier; an `impl` takes none,
having no name for one to restrict; and an **enum's variants carry the enum's own**, since a type
nobody outside may name is not one whose variants they may construct.

**A name a file may not reach is not a candidate for it.** Resolution passes over one and goes on
through the file's imports rather than stopping there — a file that wrote `import util.width` said
which `width` it meant, and a sibling file's private helper of that name is not an answer to it.
That holds whatever kind of declaration each one is: `import n.LIMIT` naming a variant of an enum
in `n` is what `LIMIT` means in that file, beside a sibling's `private const LIMIT`, while a sibling's
*public* `LIMIT` is the module's own name and still answers first.
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
are a **name and one type** — a `const`, a module-level `val` and an `extern` variable. It reaches everything a
caller has to be able to write: a struct's fields and a variant's payload, since neither has a
visibility of its own; a **type argument**, since `Box[Point]` names `Point` as much as a bare `Point`
does; a trait behind a memory mode; a member of a type or a trait; and a **bound**, since a trait a
caller cannot name leaves it unable to say what is being asked of it.

The `const` was not always among them: a constant was held to being a scalar, and every scalar is a
builtin nobody may restrict, so there was nothing to reach the question with. A **transparent
subtype** of a scalar is a constant's type now, and that is a declared type somebody may make private
— so the rule stated in advance for a hole that did not yet exist is the rule that closes it.

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

**That includes the name an import binds a module to.** A local, a parameter or a captured binding
spelled like a module's last segment is the nearer binding, so a dot after it reads a member of the
value — a method call, a field, a write through it — and never the module's function of that name.
Storage the file's own module declares is nearer than an import in the same way.

```sysl
import sysl.math

struct Range
    lo: int
    hi: int

    max(self) -> int = self.hi

val math = Range(1, 9)

print(math.max())
print(sysl.math.max(3, 4))
```

```output
9
4
```

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
| two wildcards both offer one name | an *unqualified* use is a compile error naming both — unless it is a variant and the expected type says whose |
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

**A variant two wildcards both offer is settled by the expected type**, exactly as two enums of one
module are ([A variant belongs to its enum](/reference/types/#a-variant-belongs-to-its-enum)).
`sysl.args` and `sysl.encoding` both have a variant called `Short`, and an annotated binding, an
argument or a declared return type says which one a bare `Short` means:

```sysl
import sysl.args.*
import sysl.encoding.*

needed(e: DecodeError) -> usize = e match
    DecodeError.Short(n) -> n
    _ -> 0

flag(a: Arg) -> char = a match
    Arg.Short(c) -> c
    _ -> '?'

val e: DecodeError = Short(4)

print(needed(e), flag(Short('v')))
```

```output
4 v
```

With nothing expected, the two offers are still the compile error the table names:

```sysl
import sysl.args.*
import sysl.encoding.*

var s = Short(4)

print(1)
```

```error
'Short' is offered by 'sysl.args.*' and 'sysl.encoding.*' — import it selectively, or write the module it comes from
```

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

**Everything written in the block below the import reads names through it — a closure's body
included**, wherever that closure is later called from. A closure is part of the block it is written
in, so its `max` is the block's:

```sysl
clamped(xs: []int, floor: int) -> []int
    import sysl.math.max
    import sysl.seq.Sequence

    xs.map((x) -> max(x, floor))

print(clamped([3, -2, 7, 0], 1))
```

```output
[3, 1, 7, 1]
```

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
module is a module like any other. A [`@tests` file](#a-tests-file-states-its-own-capabilities) is
the one file this does not reach, for the reason it is dropped by every build but `sysl test`.

The other direction is `@requires(...)`, which takes a **list** because a module often needs more
than one capability at once — the POSIX regex binding is `@requires(heap, posix)`, since a `regex_t`
is caller-allocated and `regcomp` is POSIX. **`posix` is behaviour and `libc` is something to link
against**, and a module calling C by symbol states both: `sysl.posix.time` is
`@requires(posix, libc)`, while `sysl.process`, whose every call goes through hooks a kernel may
answer, is `@requires(posix)` alone (`reference/packages.md` § [`libc`](/reference/packages/#libc-the-one-a-freestanding-target-starts-without)).

**The heap has two names, and they say different things.** The capability is `heap` and the clause
that gives it up is `@no_alloc`:

- **`heap` names a facility** — whether the machine being built for *has* one. It sits beside `os`,
  `posix` and `libc`, and it is what a project states in
  [`package.hocon`](/reference/packages/#capabilities), because whether there is a heap is a project
  engineering decision.
- **`@no_alloc` names conduct** — a promise this module's code does not *allocate*, and so does not
  need a heap to exist. A promise is about an action, which is why it reads as a verb.

So `@no_alloc` narrows away `heap`: *I do not allocate, therefore I do not need one.* Each spelling is
refused where the other belongs, naming it, since somebody who wrote one meant the other. The other
three capabilities need only one word, because for them giving the facility up and not using it are
the same act.

**What `@no_alloc` promises, exactly: no execution that begins in this module's own code makes heap
storage.** It is a *portability claim* — this module can be compiled into a project that has no heap —
and its worth is that the compiler holds you to it while you are developing on a machine that does
have one, rather than at the far end when you first build for the board.

**They are annotations rather than grammar**, which is what keeps `alloc`, `no` and `requires`
available as ordinary names; see [attributes](/reference/attributes/).

**`@link` is the header's other inhabitant and is deliberately not held to agreeing.** `@link("z")` names
a library the file's `extern`s need, and the files of a module may each name their own — because what
is being described differs. A capability is a property of the whole module, so files that disagreed
would describe different modules, while a link requirement is a property of the `extern`s in *one*
file.

**Propagation is over the module graph.** A module's effective requirement is its own uses plus the
requirements of every module it reaches, transitively, and the whole graph must fit the target. A
`@no_alloc` module importing a `@requires(heap)` module is an error **at the import**, not deep in
codegen. Because the graph is acyclic, propagation is a **single sweep in reverse topological order**
— each module's requirement set is final before any importer of it is visited — rather than an
iterated fixpoint.

Two of the capabilities are checked differently, and the difference is worth knowing:

- **`heap` is finer than the declaration**, and the standard library is why. Inferring it per module
  would put the whole of `sysl` on one side of a line that runs through the middle of it, since
  `print` allocates nothing and `from_utf8` does — so the inferred half is asked of **what a module
  calls** rather than of which modules it depends on.
- **`os`, `posix` and `libc` are exactly the declaration**, since they gate which modules *exist*
  rather than what the language allows. `libc` is the one no module gives up — there is no
  `@no_libc`, a module that calls no C saying nothing. The edge the rule is stated over is the **reference** graph rather than
  the import graph, which is load-bearing: a qualified path reaches another module with no import at
  all, so a rule about imports would have missed the shorter of the two ways to write the mistake.

### A declaration may name what reaching it needs

**A module is a coarse unit for this question, and `@needs(...)` is the finer one.** `@requires(heap)`
written on `sysl` would say something false about most of it, so a library wanting the granularity had
to split modules — which is letting an annotation decide the library's shape. `@needs` goes above one
**declaration**, and what it says is charged to whoever reaches it rather than to whoever holds it.

```sysl
module oskit.disk

@needs(os)
fetch(block: int) -> int = block
```

The two are different statements and that is why they are different words. A file's `@requires(...)`
is about the **module**: it cannot be built at all without the capability, and it is checked once,
against the target. A declaration's `@needs(...)` is about one declaration: reaching it needs the
capability, and it is checked at the **call**, in the caller's module — which is where the line a
reader can change is.

```sysl
@no_os

@needs(os)
fetch(block: int) -> int = block

print(fetch(1))
```

```error
this reaches 'fetch', which needs 'os', and this module declared '@no_os'
```

It is **transitive**, because reaching is: a module that gave a capability up may not arrive at such
a declaration through a third that has it. A function that says nothing and calls `fetch` passes the
need on, so *its* callers are refused at their call in turn.

**What the declaration's body reaches is charged the same way, and not to the module holding it.** The
module graph leaves out what such a body reaches, for the capabilities the declaration names. So a
module may hold one function that touches the filesystem beside a hundred that do not, and a `@no_os`
program imports the hundred and hears nothing — the only line refused is a call that reaches the one.
So does an import that only such declarations use: a `@needs(os)` function writing through
`import sysl.fs.write_bytes` charges `os` to its callers, not to its module.
A body covers what its own annotation names and nothing else, so a `@needs(heap)` function reaching
`sysl.fs` still makes its module require `os`.

**The declaration it exists for is `extern`.** Every other declaration has a body the compiler reads —
a function that makes heap storage is found by looking — so an `extern` was the one route by which a
module that had given up an environment capability could reach `open()`. Nothing but the declaration
itself can close that.

```sysl
module oskit.disk

@needs(os)
extern "getpid" pid() -> int
```

The module's own clause stays the **floor**: a declaration may add to what its module needs and cannot
take anything away. A declaration that says nothing costs its callers nothing, which is what makes the
annotation additive — a program that writes none is refused exactly what it was refused before.

An `extern` takes this and no other annotation. It has no body, so what `@pure`, `@tailrec` and the
rest are about is not there to describe.

### A type costs what it runs

**Naming a type runs none of its code, so it charges nothing.** A module may hold a field of a type
whose module requires `os`, carry one in a variant's payload, name it in a signature or pass it as a
type argument, and a `@no_os` program may still import that module — and may construct a value of
such a type itself, construction being nothing but laying the value out. What a module requires is
the cost of its **code**, and a type is charged where that code runs:

- **A method called on it charges its module**, exactly as calling a function of that module does.
  So does taking a method's address, and erasing a value into a trait object, which hands over the
  implementation for whoever calls through it to run.
- **A destructor runs wherever a value can die.** A type with an `impl Drop` charges its module at
  every place a value of it is named — and so does every type that holds one, through a field, a
  `&T`, a slice or an array, since the outer value's death is the inner one's. A `*T` and a
  `weak T` own nothing and stop it. The refusal says which: *"a 'store.Handle' can die here, and its
  destructor reaches 'store', which requires 'os'"*.

So the shape this exists for is a module whose error enum wraps the filesystem's error for its one
`@needs(os)` function: the enum names `sysl.fs.IoError`, the function reads a file, and a `@no_os`
program importing anything else from the module hears nothing — only a call to the function is
refused, at the call.

A `@no_os` program may name the filesystem's error, carry it in a variant and match on it, because
none of that runs a line of `sysl.fs`:

```sysl
@no_os

import sysl.fs.IoError

enum Failure
    Io(e: IoError)
    Short(n: int)

describe(f: Failure) -> string = f match
    Io(_) -> "a file operation failed"
    Short(n) -> f"short by $n%d"

print(describe(Short(3)))
```

```output
short by 3
```

Calling one of its methods runs `sysl.fs`'s code, and that is refused:

```sysl
@no_os

import sysl.fs.IoError

val e = IoError.NotFound

print(e.message())
```

```error
this reaches 'sysl.fs', which requires 'os', and this module declared 'no os'
```

A method called **inside a generic instantiation** is not charged to the generic's module: the type
was its caller's choice, made in a module of its own
([a generic answers for what it wrote](#a-generic-answers-for-what-it-wrote-not-for-what-its-caller-chose)).

### A `@tests` file states its own capabilities

A [`@tests` file](/reference/attributes/) is scaffolding: `sysl test` keeps it and every other build
drops it, so nothing it declares reaches a program that links this module — and nothing it imports
is charged to the module either. A library whose tests read a fixture with `sysl.fs` is still one a
program on a machine with no operating system can link. The module's clause is a
promise about what **ships**, and it was therefore never a promise about that file — so **the
agreement above stops at the header `@tests`, and such a file states what the module's *tests* need.**

```sysl
module oskit.digest
@no_alloc

// Everything here answers a fixed-size array and writes into slices the caller owns.
sum(bytes: []u8) -> [4]u8 = [0, 0, 0, 0]
```

```sysl
module oskit.digest
@tests
@requires(heap)

import sysl.encoding.hex_string

// Checking it against the published vectors means rendering a digest as text, and rendering
// allocates. The module still ships allocating nowhere.
@test
matches_the_vector() =
    assert(hex_string(sum([1, 2, 3])[0..<4]) == "00000000")
```

Without this the clause was unavailable to exactly the modules that most wanted it. Testing an
allocation-free primitive means rendering what it produced and building inputs of a chosen length,
and both allocate — so a module could have the promise or have tests, and not both.

**What a `@tests` file writes is an override of its module's, per capability, not a set of its own.**
A file that writes no clause is held to the module's, which is what every one of them meant before
there was another answer:

```text
error: a reference needs an allocator, and this module declared '@no_alloc' — it may hold and
release storage made elsewhere, and may make none of its own
```

A file that writes one hears about its own instead, since that is the line to go and read:

```text
error: a reference needs an allocator, and this module's '@tests' file declared '@no_alloc' — it may
hold and release storage made elsewhere, and may make none of its own
```

**A test is scaffolding wherever it is written.** A `@test` function written beside what it tests, in
an ordinary file, answers to the `@tests` file's clause too — the alternative puts a seam through the
middle of one module's tests. And a module's `@tests` files are held to agreeing with **each other**,
for the reason its shipping files are: a module has one answer to what its tests may do.

**Only the module's half of the two-level rule moves.** A `@tests` file may take back a promise the
module made about what ships; it cannot take back a facility the machine does not have. On a target
whose `package.hocon` says `heap = false`, a test that allocates is refused whatever its header says
— which is what keeps a run from reporting that vectors passed on a machine that could not have run
them. For the same reason the `@requires` on a `@tests` file refuses nothing on its own: an ordinary
`sysl build` analyzes that file and then discards it, so a requirement checked against the target
would refuse a build over a facility only the discarded file ever wanted.

### The target's half needs no clause at all

A module's effective set is the target's intersected with its own narrowing, so a capability is out of
reach whichever of the two removed it — and both are answered at the same edge. `@no_os` is the half
you can see in the file. The other half is the machine: on a target whose
[`package.hocon`](/reference/packages/#capabilities) says `os = false`, **every** module of the
program is one that may not reach `sysl.fs`, with nothing written anywhere. A `print(exists("/tmp"))`
in a program with no clause at all is refused:

```text
error: this reaches 'sysl.fs', which requires 'os', and 'aarch64-none-elf' does not provide it — a
target's capabilities are what 'package.hocon' declares, so either this reference cannot be made on
this machine or the config is understating it
```

**A target the config says nothing about provides everything, `os` included** — and so does every
target of a project with no `package.hocon` at all. A board is not assumed to lack an operating
system: a program built for `thumbv6m-freestanding` may reach `sysl.fs` as far as this check is
concerned until its config says `capabilities { os = false }`, which is the line that makes the refusal above possible.

The message names the config rather than a clause, because that is where the answer is. Where the
module *did* write `@no_os`, that is what it hears about instead — a reader sent to the config over
something they said in their own header would go and change the wrong file.

**A library's own modules are exempt, and that is what makes the rule usable.** The modules this
question is asked of are the ones the compilation is *producing*: your program's own. The standard
module's are not, and neither are a `--lib` source root's or a
[fetched package's](/reference/packages/#dependencies). A library holding one POSIX module is not a
library a POSIX-less target cannot use — it is a library one module of which your program cannot
reach, and refusing at that module's own
`@requires` would refuse your build over a file you did not write and cannot change. So the refusal
lands at the reference, which is a line somebody chose to write, and a program that never names the
module hears nothing.

**Exempt from being refused over its own clause is not exempt from being reached.** A reference into
a module of a path or git dependency is asked the same question as one into the standard module: a
program on a `posix = false` target, or one that wrote `@no_posix`, is refused at the line that
reaches a dependency's `@requires(posix)` module, naming that module by its package-qualified name.

### A generic answers for what it wrote, not for what its caller chose

A generic has no execution until a type is chosen, and whoever chose it is usually somebody else. So
the promise is asked of the generic's body **as written**, and a monomorphized instance answers for
nothing at all. An allocator-free library may therefore be instantiated at a type whose `impl`
allocates: the library promised nothing about a type it never saw.

```sysl
module lib
@no_alloc

trait Sink
    put(*self, s: string)

twice[S: Sink](s: *S, msg: string)
    s.put(msg)
    s.put(msg)
```

`put` is the caller's choice, so the program below is accepted although its `impl` allocates on every
call — and it is accepted whether or not `lib` carries the clause, which is the point of it being a
promise about `lib`'s own conduct.

```sysl
import lib.*
import sysl.text.cstring

struct Loud
    n: int

impl Sink for Loud
    put(*self, s: string)
        val c = cstring(s)
        self.n += 1

main()
    var l = Loud(0)
    twice(&l, "hi")
    print(l.n)
```

**What the generic itself constructs is unchanged by the type argument**, so it is charged where it
is written, at every instantiation:

```sysl
@no_alloc

boxed[T](x: T) -> &T = x

print(*boxed(3))
```

```error
a reference needs an allocator, and this module declared '@no_alloc'
```

The same rule answers the two cases that look like ways around it. A trait's **default** body is
written once, in the trait's own file, so a default that allocates is that module's conduct however
the implementing type is chosen. And a generic that reaches an allocator through **another generic**
is still its own module's, since a call in a generic body leads to the body that was written.

Nothing is given up where the promise is load-bearing. On a target with no heap every module is
allocator-free with no clause written anywhere, so the module that chose the type is itself checked,
and the walk from its body goes straight through the instance to whatever the type argument dragged
in. What the rule gives up is a refusal aimed at the wrong file.

## Platform selection — `__<machines>__`

A module's implementation may be **split across machines** by a directory whose name is wrapped in
double underscores. Such a directory **selects but does not name**: it is not a module, contributes no
segment to any name, and the files inside it belong to the directory that *holds* it — exactly as if
they had been written there.

```
sysl/fs/path.sysl              module sysl.fs, on every target
sysl/fs/__posix__/dirent.c     that module's C where POSIX is, absent on a bare machine
```

An importer writes `import sysl.fs` and names its members. Which files went into it is not something
they can see or have to know — **the module name is unchanged by the selection**, and that invariant
is the point of the feature.

**A selector names one or more symbols, separated by commas, and is taken when any of them holds.**

```
__macos__            one operating system
__macos,linux__      either of two
__posix__            whichever operating systems POSIX means
__bsd__              whichever of them carry a BSD libc
__hosted__           any machine with an operating system under it
```

**The vocabulary is `#if`'s** — the operating systems `macos`, `linux`, `windows` and
`freestanding`, plus the three facts that hold without naming one: `hosted`, `posix` and `bsd`. They
are the same words because they are the same idea: a machine named one way in a directory and another way in
a `#if` would be a thing to look up rather than a thing to know. A directory of the `__x__` shape
naming anything else is an **error** — a misspelled `__linx__` read as an ordinary module directory
would compile nothing on any target and be reported, much later, as a missing function. The message
names the element that is wrong rather than the whole directory.

**A processor is the one thing a selector may not name.** A directory is chosen by a walk that has an
operating system and nothing else to ask. Source that varies by processor is `#if`'s, or the C
preprocessor's inside a `.c`.

### Say why, not which

`__posix__` and `__macos,linux__` select the same two machines today and **are not the same claim.**
Which operating systems are POSIX is written in exactly one place, so the first derives from it and
the second copies it: add a third POSIX system and every `__posix__` directory covers it untouched,
while every `__macos,linux__` directory silently does not.

This is what a shim that needs an operating system should say, and it is why the form exists. A `.c`
calling `readdir` is not *different* on macOS and Linux — absorbing the difference between them is
the shim's whole job, so the file is identical on both. What it cannot do is exist where there is no
operating system at all. Two per-OS directories say the wrong thing and cost two byte-identical
copies to say it; `__posix__` says what is true, once.

Reach for the list form when the set is one no capability names — macOS and Windows but not Linux,
say. That it exists is not a reason to prefer it.

### More than one may be taken

Files sitting directly in a directory are compiled for **every** target; the folders *add* to them
rather than replacing them, so shared code stays where it is and only the part that differs moves.

```
sysl/posix/time/time.sysl              the whole API, written once
sysl/posix/time/__posix__/clock.c      the primitive that needs POSIX
sysl/posix/time/__linux__/clock.sysl   the one primitive that differs by system
sysl/posix/time/__macos__/clock.sysl
```

That layering is how to use this: **put the smallest private primitive in the folder and build the
public surface once, outside it.** A public API duplicated per operating system is two APIs, and they
will drift.

Two selectors may both answer — every POSIX machine is hosted — and both are taken. What is refused
is the two of them holding a **file of the same name** between them, which would be two files of one
name and a duplicate symbol reported a long way from the directories that caused it. The name is the
fault rather than the overlap, so there is no precedence order to remember and no tie to break.

**A selector directory may not be nested inside another.** A directory this target did not select is
never read, so nothing inside one could be taken however it were named; where a family and one of its
members both need saying, they go beside each other. Two axes — an operating system and a processor,
an operating system and a libc — are not what this is for either: the second axis is `#if` inside the
file, or the C preprocessor inside the `.c`, which is where the world already keeps that knowledge.

**A directory this target did not select is not read at all** — not compiled, not analyzed, not
parsed. That is what lets it hold a `.c` including a header this machine does not have, and it is
also the cost: an implementation only one machine selects is checked by a build on that machine and
by nothing else. Naming a family is the way to have less of that: a `__posix__` shim is compiled by
every hosted build there is, where the same file under two per-system folders is two files each
checked half as often.

### It exists for the C

Everything above is true of `.sysl` files, and they are the smaller half. A module that differs by
platform can usually say so with `#if` in one file — but **a `.c` cannot carry a sysl attribute**, and
it will not be given a sysl-shaped name. The path is the only place its selector could go.

That is what a directory buys over a filename suffix, and it is why the suffix this section used to
describe was never built: a grammar over sysl filenames would have selected everything except the one
kind of file the feature is for. [A library may carry C](/reference/ffi/) has the rest — and the
standard library's own `sysl.fs.entries` is the worked example: four lines of C under `__posix__`,
reaching a `struct dirent` whose layout no sysl file could honestly transcribe.

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
second that carries any is an error naming both. That file is the program's **entry file**, and its
top level is a **body**: what it declares is local to it, which the section below is about.

**A program starts in one place.** Statements at the top of a file and a `main` are two ways of
writing that place, so a program that writes both is refused:

```sysl
print("initialization")

main(args: []string)
    print("then main, with", args.len, "argument")
```

```error
a program starts in one place, and this 'main' is a second — whichever of the two the program means, the other belongs inside it
```

Whichever of the two the program means, the other belongs inside it. What a `main` has that statements
do not is a parameter list, so a program that wants the arguments writes `main` and puts inside it what
it would otherwise have written above:

```sysl
main(args: []string)
    print("initialization")
    print("then the work, with", args.len, "argument")
```

```output
initialization
then the work, with 1 argument
```

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
'main' is where a program starts, so there is one — a second declaration of it would overload the name, and a program has one beginning rather than a set of them
```

That is the reason C reserves the name: it is not a name the program calls, it is the name the
*platform* calls, so two would leave which one the program **is** to whichever was emitted last.
Otherwise it is an ordinary function, and may be called by the program too.

**A `main` may answer with a `Result[unit, E]`**, which is what lets `?` reach the top of a program:

```sysl
import sysl.fs.{read_text, IoError}

main() -> Result[unit, IoError]
    val text = read_text("/nonexistent/file")?

    print(text)

    Ok(())
```

That program prints nothing and exits **1**, having written `error: no such file or directory` to
stderr. Without the form, every fallible call in `main` ends in `.unwrap()`, which reports the
failure as a panic naming the line that gave up rather than the thing that went wrong.

Three parts of it are decided rather than incidental. The `unit` is not decoration — a value `main`
answered with would have nowhere to go, since what the platform takes is a status. `E` must be
[`Display`](/library/core/), because the report is the whole point and an error nobody can render
would exit non-zero having said nothing. And the status is `1` rather than something read off the
error: a status is one byte and an error is a value, so mapping one onto the other is the program's
business, and `exit` is how a program that wants to choose says so.

**Nothing else is admitted:**

```sysl
main() -> int = 0
```

```error
'main' yields nothing or a 'Result[unit, E]', so it may not result in int — a program's exit status is not something a signature can say
```

**A program in which no file carries a statement is a complete program that does nothing.** The entry
point exists, runs nothing, and succeeds. That is what a tree of pure declarations compiles to, which
is what it should compile to — a library is not an error.

### The entry file is a body, and what it declares is local to it

`val` and `var` at the top of the entry file are **locals**: initialized where they are written, in
the order the statements around them run. A function declared there is a **nested function**
([functions](../functions/)), so it reads and writes the file's bindings with nothing passed in:

```sysl
var counter = 0

bump()
    counter += 1

bump()
bump()

print(counter)
```

```output
2
```

That is the whole point of the arrangement, and it is what a script wants: a sequence that is a
sequence. A `val` bound from what the statements above it produced is ordinary here, and could never
be a module member — a module member is bound before any statement runs.

**A helper pays nothing for this unless it uses it.** Whether a function at the top of the entry file
belongs to the body is settled by whether it reads one of the body's bindings. One that reads none is
an ordinary module function: generic if it says so, addressable, passable as a value, and reachable
from another file. Only one that reads a binding is nested, and only that one takes the nested
function's limits.

Being generic is not one of those limits. A nested function takes type parameters as a top-level one
does ([generics](../generics/#a-nested-function-may-be-generic)), so a helper with type parameters
that reads a binding of the body is instantiated at each type it is called at, and every instantiation
reads the same binding:

```sysl
var failures = 0

failed[T](r: Result[T, int])
    if !r.is_ok() then failures += 1

val r: Result[string, int] = Err(1)
val q: Result[bool, int] = Err(2)
failed(r)
failed(q)
print(failures)
```

```output
2
```

What it cannot be is addressed, passed as a value, or reached from another file. Declaring the binding
`static` (below) makes it the module's, which leaves the helper reading nothing of the body's — an
ordinary module function again:

```sysl
static var failures: int = 0

failed[T](r: Result[T, int])
    if !r.is_ok() then failures += 1

val r: Result[string, int] = Err(1)
val q: Result[bool, int] = Err(2)
failed(r)
failed(q)
print(failures)
```

```output
2
```

#### It may read anything the block binds, wherever that is written

"The bindings above it" is the usual case rather than the rule. A nested function may read **anything
its block binds**, including something written below it — so helpers can sit above the data they use,
which is the natural way to lay out a script:

```sysl
var counter = 0

bump()
    counter += 1

val table: [3]int = [1, 2, 3]

first() -> int = table[0]

bump()
print(counter + first())
```

```output
2
```

The block's nested functions share **one** environment, and it is built after the last binding any of
them reads. That single environment is what lets two of them call each other in either order — a
sibling call and a recursive call are the same call, on the receiver the body already holds.

**What it costs is that none of them may be called until that point.** Calling one earlier would read
a binding whose initializer has not run, so it is refused — and all of them are refused together,
because there is one environment and it does not exist yet:

```sysl
var counter = 0

bump()
    counter += 1

first() -> int = table[0]

bump()

val table: [3]int = [1, 2, 3]

print(first())
```

```error
'bump' cannot be called here — the nested functions of this block share one environment, and it is not built until everything they read is bound. 'table' is bound below this call: move the call below it, or move it above the functions
```

Both moves the message names work, and neither is preferred: put the call after `table`, or put
`table` above the functions.

#### Beside a `main`, a `var` is the module's

**A file whose only running lines are `var`s is a body only when nothing else is the beginning.** A
`var` could be read either way — a local of the statements a script runs, or storage its module
owns — and the rest of the program chooses. With no `main` anywhere, a lone `var n = 1` is a script's
first line, as it always was. With a `main`, the program has written where it starts, so there is no
body for the `var` to be a local of, and it is the module's: exactly what a `val` beside the `main`
is, and what a `var` in a file with a `module` line always is. A program shaped like most programs —
a counter, the functions that bump it, and a `main` calling them — is therefore just that:

```sysl
var counter: int = 0

bump() =
    counter += 1

main()
    bump()
    bump()
    print(counter)
```

```output
2
```

Being the module's, it is held to what module storage is held to, and the first of those is a
stated type. That is the one line reported, on the `var` itself — every use of the name below it
still resolves:

```sysl
var counter = 0

bump() =
    counter += 1

main()
    bump()
    print(counter)
```

```error
'counter' is module storage, and module storage states its type — write 'counter: T'
```

A file that also runs a statement is a body however a `main` is written, so there the `var` is a
local and the `main` is the second beginning refused [above](#where-a-program-starts).

### `static` — asking for the module instead

A `val` or `var` in the entry file that should be the **module's** says so:

```sysl
static val table: [3]int = [1, 2, 3]

sum() -> int = table[0] + table[1] + table[2]

print(sum())
```

```output
6
```

It is then hoisted, laid into the object file, visible to every file of the module, and initialized
before any statement runs — which is also why its initializer may not call a helper that reads the
body: at that moment there is no body yet.

A **`static var`** is the same storage, written — assignment at every depth and `&`, which is what the
word `var` already means, and which a `val` refuses because a `val` promises its storage is written
once:

```sysl
static var ticks: int = 0

tick() = ticks += 1

tick()
tick()
print(ticks)
```

```output
2
```

Its initializer may be **absent**, which a `val`'s may not — the type's zero is what it starts at, and
that is the cheapest form and the one an arena wants.

**It holds a counted value, and the last one it holds is never released.** A `string`, a slice, a
`&T`, a `weak T` and anything built out of them are all module storage; every assignment during the
run gives back the count it replaces, and the only release with nowhere to go is the one at exit —
which is the release a static is *defined* by not taking.

```sysl
static var current: string = ""

set(s: string) = current = "[" + s + "]"

set("one")
set("two")
print(current)
```

```output
[two]
```

That was refused until the reason behind it was read again: storage lasting the whole run has no line
to write a release on, which is true and is a description of a static rather than an argument against
one. What the refusal cost was the shape every callback interface needs — a C function that calls
back takes an address and an opaque word, so a binding offering a sysl closure has to keep it where
the trampoline finds it again, and module storage is the only storage that outlives the call.

**What is refused instead is a type with no zero and no initializer**, which is a narrower rule about
a different thing. A `&T` has no zero, because there is no such thing as a reference to nothing; an
enum has none either, since a zeroed tag names no variant in particular. A `string` and a slice both
zero to the empty one and need nothing written. It is the same question a **local** declared with no
initializer is already held to.

```sysl
struct Cell
    v: int

static var cell: &Cell
```

```error
'cell' needs a value: storage with no initializer starts at its type's zero, and &Cell has none — the same rule a local with no initializer is held to. A 'string' and a slice both start empty and need no value written; a reference and an enum need one
```

A [destructor](/reference/memory/) takes the same ruling and for the same reason: a value in module
storage never reaches a count of zero, so its `drop` does not run when the program ends.

**`static` is meaningful only in the entry file**, since only that file has a body for a declaration
to *not* belong to. In a file with a `module` header, or a headerless file carrying no statements,
everything is the module's already and the modifier is refused rather than ignored. A function never
takes it either: settled by what it reads, the modifier would be redundant on one and impossible on
the other.

**So everywhere else the same storage is written plain `var`**, and it is the same declaration —
visibility, the shared value namespace, the initializer graph and the release rule all answer for it
exactly as above:

```sysl
module counter

var count: int = 0

bump() = count += 1
```

The two spellings never compete, because the modifier is needed precisely where there is a body to be
asking about. Reading them side by side: `static var` in the file the program starts in, `var`
anywhere else, one kind of module storage.

Visibility is the part of that worth writing out, because a module with state usually wants it — the
functions that maintain the storage are public and the storage itself is nobody's business:

```sysl
module counter

private var count: int = 0

bump() = count += 1
peek() -> int = count
```

Both spellings take one, so `private static var` says the same thing in the file the program starts
in.

**A `var` does not decide which file the program starts in.** That is settled by what a file *runs*,
and declaring storage runs nothing — a file holding a counter is no more the program's beginning than
one holding a table. Where a file carries a statement that is not a binding, it is the entry file and
every other file's top-level `var`s are their module's. Where nothing runs anywhere, a single
**headerless** file of bindings is still a body, so a one-file `var n = 1` means what it always did.

That word is load-bearing: **a file naming a module is never the program's beginning**, and it is the
sentence at the top of this section seen from the other side — a header means everything the file
declares is the module's already, so there is no body for a binding to belong to instead. A library is
the shape that tests it, being files and no beginning anywhere.

**And so is "the program's":** where a build has no beginning at all, no file is a body and every
top-level `var` is its module's. `build-c` and `build-lib` are that case — the C project supplies its
own `main`, and a library is linked into a program that has one — so a header-less file keeping state
is a module there, exactly as a headed one is everywhere. The two conditions are the same bound
reached from opposite sides: one asks what the *file* says, the other what is being *built*.

## `const` — a value

A **`const` is a module member**: hoisted, order-free, visible to the whole module and beyond it under
the ordinary rules, and never running at all.

```sysl
const capacity: usize = 512
const window: int = 1 << 15

type Slot = usize within 0..<capacity

var table: [capacity]u8 = [0; capacity]

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

**And the type is a scalar, or a transparent subtype of one.** The first half is the shape of what a
constant expression can produce rather than a restriction anybody chose — there is no aggregate
literal to fold to, and a table would be storage rather than a value. The second half follows from a
transparent subtype *being* its base, so a constant declared at one is a literal at an integer, a
float or a `char` — and its range is checked while compiling:

```sysl
type Age = int within 0..150

const oldest: Age = 122

print(oldest)
```

```output
122
```

**The refusal names which of the two halves it is applying.** An aggregate type is storage rather
than a value, so what is wrong is not that the initializer failed to fold — a `val` one keyword away
lays exactly that expression into the object file:

```sysl
struct Id
    raw: [16]u8

const nil: Id = Id([0; 16])

print(1)
```

```error
is storage — write 'nil' as a 'val', which is laid into the object file as constant data
```

```sysl
struct Id
    raw: [16]u8

val nil: Id = Id([0; 16])

print(nil.raw.len)
```

```output
16
```

That is `sysl.encoding.nil` exactly: the nil UUID is a module-level `val` for this reason and no
other. [What constant data is](/reference/ffi/#what-constant-data-is) says which initializers are
laid down that way — arrays, structs and variants over constant expressions, at any depth, and the
address of other module storage. A `const`
whose *type* is a scalar and whose *value* is genuinely not constant still gets the
older wording, which is the case it was written for and is the `size()` example below.

```sysl
type Age = int within 0..150

const oldest: Age = 200
```

```error
which Age does not admit — it holds 0 to 150
```

That check is the one thing a constant gets that a `val` of the same type gets only at run time, and
it is what a [`c const`](/reference/ffi/) is really for: a number nobody chose — a `sizeof`, a config
macro out of somebody else's header — held to what this program can actually do with it.

A **`new` type** is admitted: the value is the base's constant written through the conversion,
`const OUR_IP: Ip = Ip(0x0a00_020f)`, or a bare literal, which takes the type where it sits. It folds
as the base would and keeps its type at every use, and a range on it is settled here as on any
constrained type. A **`where` predicate** is refused, because a predicate is checked where a value is
*made* and a constant is folded into its uses rather than made anywhere — admitting one would be a
check the declaration claims and the program never gets.

```sysl
type Even = int where value % 2 == 0

const n: Even = 4
```

```error
'n' is declared Even, whose 'where' predicate is checked where a value is made
```

**A constant expression** is a literal; a `const`; a conversion; a unary `-`, `!` or `~`; a binary
arithmetic, bitwise, shift, or comparison operator applied to constant expressions; or **a call to
a [`const` function](/reference/declarations/#a-const-function-runs-while-compiling)** whose
arguments are constant expressions. Integers, floats, `bool` and `char` fold.

**A call to any other function is refused**, and a `string` constant's initializer must be a
**literal** — since `+` on strings allocates, and a compile-time concatenation would be a different
operation wearing the same spelling:

```sysl
size() -> int = 4

const called: int = size()

print(called)
```

```error
the value of 'called' is not a constant expression — 'size' is not 'const', so it runs only when the program does; mark it 'const' to call it here
```

A `const` function is the one kind of code the compiler runs while it compiles, so marking it is
all the call needs:

```sysl
const size() -> int = 4

const called: int = size()

print(called)
```

```output
4
```

**The call is run, not guessed at.** The compiler runs the function's body over the arguments, at
the target's widths — a `usize` wraps at 32 bits when the target's `usize` is 32 bits — with every
integer operation wrapping as it does at run time and every `f32` operation rounded as it is at run
time, so the constant is the bits the same call makes when the program runs. A module `val`
initialized by such a call is laid into the object file as data on every target, a freestanding
one included, rather than computed before the program's statements:

```sysl
const fact(n: int) -> int = if n <= 1 then 1 else n * fact(n - 1)

static val V: int = fact(10)

print(V)
```

```output
3628800
```

**Every run is bounded**: 16,000,000 steps — one per expression and statement the run visits — and
calls 512 deep. Running out of either is an error naming the constant, not a warning, and there is
no setting that raises them:

```sysl
const spin() -> int
    loop
        continue

const k: int = spin()

print(k)
```

```error
'k' did not finish within 16,000,000 steps while compiling — the loop at
```

**What a body may hold while it runs is narrower than what it may hold**: integers of every width,
`f32` and `real`, `bool`, `char` and `unit`, and constrained types of them, with their checks run.
A struct, an array, an enum value, a field or an element stops the run with *"a 'P' is not
evaluated while compiling yet — integers, floats, 'bool' and 'char' are"*; the program's own
run-time call is unaffected. A `const` call where a **type** is read — an array bound, a `within` —
is not evaluated yet either, and is refused with *"'four' is a 'const' function, but this value is
read while declarations are, before any body can be run"*.

**"A `const`" above means the declaration, not a spelling of it.** A constant reached by its full path
is the same constant as one reached by an import, and the two fold alike in every position below —
what decides is what the name resolves to, and the three steps above are the whole of how a name
resolves:

```sysl
import bits.byte_width

var packed: [byte_width]u8            // imported
var padded: [bits.byte_width]u8       // the same declaration, named through its module
```

It is worth saying only because the two spellings are different enough to be implemented separately,
and once were. A [`c const`](/reference/ffi/) is no different: a binding keeps its measured
constants in a sub-module of their own, so every consumer names them qualified.

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
obvious neighbour of the first, the declaration wanted was an `enum`. And not a **value generic** —
parameterizing over a length is `[const N: usize]`, which is this declaration with the initializer
left to the caller. The two are one idea in two positions, and this one had to come first: a value
cannot be passed as an argument before it can be named.

## `val` — a thing

A **`val` is a thing, where a `const` is a value.** As a module member it is read-only storage that
exists for the whole run; as a local it is the immutable counterpart of `var`, in the same frame with
the same lifetime. One keyword at both levels, because it is one idea at both — and which one it is
follows the file it is written in: the module's everywhere except the entry file, whose top level is a
body, and where `static` asks for the member.

```sysl
static val order: [8]usize = [3, 1, 4, 1, 5, 9, 2, 6]

at(i: usize) -> usize = order[i]

var i: usize = 0
var total: usize = 0

while i < order.len
    total += at(i)
    i += 1

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

var table: [limit]u8 = [0; 4]

print(table.len)
```

```error
an array length must be a constant — a literal, or a 'const' naming one
```

Naming one in a pattern with a **bare** name is an error rather than a quiet binding, for the same
reason — a bare name there would bind rather than match, and the diagnostic names what to write
instead:

```sysl
static val limit: usize = 4

check(n: usize) -> string
    n match
        limit -> "at the limit"
        else     "other"

print(check(4))
```

```error
a bare name here would bind rather than match
```

What to write is the [backticked form](/reference/patterns/#a-backticked-name-references-rather-than-binds),
which says the test was meant and compares against whatever the `val` holds when the match runs:

```sysl
static val limit: usize = 4

check(n: usize) -> string
    n match
        `limit` -> "at the limit"
        else     "other"

print(check(4), check(9))
```

```output
at the limit other
```

The rest of what a module-level `val` may hold — read-only at every depth, and the `[]const T` a
slice of one yields — is on [declarations](/reference/declarations/#a-module-member-states-its-type).

## Separate compilation

**A library is handed to a compilation as the directory its source is written in** — a
[source root](#source-roots) given to `--lib`, or a coordinate in a manifest's
[`dependencies`](/reference/packages/). There is no library artifact anybody names. What the compiler
makes from a tree it can make again, so it keeps that in its own cache and finds it there by itself;
a `--lib` naming a `.syslib` is refused, with the reason:

```text
$ sysl build main.sysl --lib mylib.syslib
sysl: error: --lib takes the directory a library's source is in, and 'mylib.syslib' is a '.syslib' — a library is handed over as the tree it is written in, and the compiler keeps its own cache of what it compiled from one, which nobody names
```

How a library shipped is therefore never the consumer's business: a program depending on one names a
directory or a coordinate and nothing else, and the rules at the top of this page do the rest — a
library is simply *more modules*.

**`sysl build-lib <dir>` checks a library for one machine** — the whole analysis, against the
standard module, for the `--target` named — and writes nothing; `checked <dir>` is its whole answer.
That is the only check a board package gets on a host, which is what the command is for. It takes no
`-o`, and says why: *"build-lib writes nothing anybody names — it checks a library, and what it
compiles is this compiler's cache, found by the compiler alone — so it takes no -o"*.

Two rules about a library hold whichever way it reaches a build:

- **A library carries no entry point.** A `main` of its own would collide with the one belonging to
  whatever links it.
- **A library may not sit in the anonymous root module.** A library is reached by naming its module,
  and the root module has no name, so nothing depending on it could write a path to what it declares.

**The standard module is the one compiled library, and it is linked by default.** It is compiled
once per machine, optimization level and allocator into an archive in the compiler's cache: a
declaration with **no type parameters** is compiled ahead of time into object code, and a program that
calls it declares the symbol and links the body, while a **generic** has nothing to compile until a
caller fixes its type arguments and is monomorphized in the consuming program. Rust's `.rlib` makes
the same split for the same reason.

Which library a compilation is compiled against is found rather than named. **A compilation that
finds no standard module at the default path builds one**, announcing it on stderr. That is not the silent substitution a compiler must
never make: a rebuild compiles against *this* library, from its own source, held to the same
fingerprint on the way back in. Nothing is substituted, so there is nothing to be misled about.

**The standard module's source ships with the compiler and is read off disk** —
`share/sysl/library` under the install prefix, found from the binary's own location the way `rustc`
finds its sysroot. Running out of a checkout, it is `library/` in the tree. You can read it, and you can edit it: a changed
file changes the library's fingerprint, so the next compilation builds an artifact of its own rather
than picking up a stale one. `SYSL_LIB` names a library root outright, which is what a broken install
needs and nothing else does. A compiler that cannot find its library names every path it tried.

The default path is keyed by a fingerprint of the library, so every compilation of the same library
on the machine finds the same artifact — and a rebuild therefore **publishes by rename**: it is
assembled beside its destination and moved onto it. Two builds may run at once; a reader gets the
whole of one artifact or the whole of the other, and a rebuild that fails leaves the one that was
already there. One that cannot be read — corrupt, truncated, built by another sysl, or built from
other sources — is not trusted: the fingerprint it carries is checked against the library's own, and
an archive that does not match is built again rather than linked.

## Source roots

<!-- Each claim below is pinned by a test in compiler/sh/sysl/compiler/: tests_lib_root.sysl
     (lbr_the_flag_is_read, lbr_every_command_takes_it, lbr_two_roots_both_called, lbr_two_roots_run,
     lbr_a_shared_module_merges, lbr_a_root_calls_the_project, lbr_a_name_declared_on_both_sides,
     lbr_a_strayed_root_file, lbr_a_roots_body_is_checked, lbr_refuses_an_empty_root,
     lbr_refuses_a_missing_root, lbr_c_beside_a_root, lbr_a_roots_defines_reach_its_c,
     lbr_a_roots_c_reaches_the_archive), tests_lib_root_manifest.sysl (lbm_a_roots_allocator_is_the_programs,
     lbm_a_root_with_no_manifest_keeps_libcs_pair, lbm_two_roots_naming_different_pairs,
     lbm_a_roots_header_requirement_is_asked, lbm_a_roots_pkg_config_is_probed,
     lbm_a_root_with_no_manifest_requires_nothing) and tests_lib_root_deps.sysl
     (lbd_two_roots_dependencies_claim_one_module, lbd_a_roots_dependency_and_the_projects_module,
     lbd_build_lib_refuses_a_roots_dependencies). A page cannot run a build of two directories, so
     the command line is a `text` block and the tests are the evidence. -->

A directory handed to `--lib` is a **source root**: a tree of modules that joins the compilation
beside the project's own. The flag may be given more than once, and `build`, `emit-typed` and
`emit-llvm` all take it:

```text
sysl build main.sysl --lib ../geom --lib ../units
```

**A root is simply more modules.** Its files join the compilation under the names they wrote, with
no prefix of their own — which is the whole difference between a root and a package, whose modules
are named by its coordinate. So a module the project and a root both declare is **one** module: the
root's code may call what only the project's files declare, and one name declared on both sides is
the ordinary duplicate.

**A root is a tree, held to the rules the project is held to.** A file whose `module` header
disagrees with the directory it sits in is refused, and a root's bodies are checked as a program's
are. A root that is not there, or that holds no sysl source, is refused.

**A root's `package.hocon` is read**, so what it declares reaches the build exactly as a dependency's
would, named as the root was given: its allocator becomes the pair the program allocates through
(two roots naming different pairs are refused rather than resolved by order), its header
requirements are asked, and the installed libraries its `pkg_config` block names are probed and
linked. A root with no manifest declares nothing. The C beside a root's modules is compiled under the
macros the root's manifest defines for it and linked with the program's — into the archive, under
`build-c`.

**A root's `dependencies` resolve in the one graph** with the project's, under the same rule: two
packages offering one module are refused, and so is a root's dependency offering a module the project
declares. `build-lib` fetches nothing, so it refuses a root that declares dependencies by name.

A root that *is* one of the project's dependencies — its manifest names the package the coordinate
does — stands in for that dependency instead;
[`reference/packages.md` § A source root stands in for the package it is](/reference/packages/#a-source-root-stands-in-for-the-package-it-is)
has that rule.

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
