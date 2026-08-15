# Front end, analyzer, and codegen (bring-up)

**Status:** bring-up slice, will be revised. This documents the first end-to-end path —
source to a running native binary — so the shortcuts taken to get there are explicit and can
be unwound deliberately rather than discovered later.

## The pipeline

`Compiler.compileToLlvm` = **parse → analyze → escape-check → prune → codegen**:

- **Parser** (`SyslParser`) — a packrat grammar over the lexer's token list, producing the
  untyped `ast.scala` tree.
- **Analyzer** (`Analyzer`) — the semantic pass. It hoists declarations, resolves names and
  types, checks every rule that can fail, monomorphizes generics, and emits the *typed* tree
  (`tast.scala`). Every diagnostic lives here; codegen trusts the tree it is handed.
- **Escape analysis** (`Escape`) — the one check that needs the whole call graph rather than one
  expression at a time, so it runs over the typed tree once the analyzer is finished (`05`).
- **Reachability** (`Reachability.prune`) — drops what the program cannot reach, over a typed tree
  every other pass has already read. It is described under *What runs today* below; what matters
  here is that it sits between the checking and the lowering, so no diagnostic depends on it.
- **Codegen** (`Codegen`, and the `*Emitter` files it is split across) — a straight lowering of the
  typed tree to **an IR of case classes** (`sh.sysl.ir`), which one printer then writes down as LLVM's
  textual form. It selects
  instructions from the types the tree carries and lays out basic blocks; it makes no semantic
  decision of its own. The one thing it decides that the tree does not carry is what a call to a
  **foreign** function looks like, because that is a fact about the machine rather than about the
  program: `CAbi` classifies an aggregate crossing the boundary and `ForeignEmitter` converts each
  value into and out of the registers the convention names (`targets.md`).

The CLI (`sysl run` / `sysl build` / `sysl emit-llvm`) links the emitted IR with `clang`.

### The IR is data, and the text is one function over it

`sh.sysl.ir` holds the model and its renderers: `LType` (an LLVM type), `Val` (an operand), `Inst`
(an instruction), `FuncSig`/`Param`/`Attr` (a signature and what a convention attaches to it),
`Func`/`Block` (a function's basic blocks), and `Module` — the whole compilation, which is what
`Codegen.module` answers with and what a back end is handed. `Printer` is the only thing in the
compiler that writes LLVM's syntax, and every emitter builds values rather than lines.

**`Module` is the piece that makes the rest reachable.** Its fields are groups rather than one list
because the order is semantic: a named struct used before its `= type` line is opaque and an opaque
type cannot be passed by value, so the declarations of a library's own functions come after the type
definitions and an `external global` naming an aggregate comes after them too. Flattening them would
keep the order and lose the reason for it.

**The one place LLVM text survives is `Runtime.Template`, and it is named.** Most of the runtime is
*generated* — a destructor for a payload type, a vtable adapter, the retain and release helpers —
built by the ordinary emitters and so `Func`s like any other. The string operations and parts of the
ownership runtime are hand-written LLVM, and there is nothing to hand a back end that is not LLVM
except what the function has to do, which is what a name is: a consumer matches on `sysl.str.concat`
and supplies its own.

**It exists because a second back end has to consume what codegen produced.** Until this the IR was
characters: `emit(s"$r = add ${ty.llvm} $a, $b")`, six hundred times over, so a consumer that was not
LLVM would have had to parse the compiler's own output back into the shapes the compiler had just
finished deciding. `~/dev/craft` is a 16-bit teaching ISA LLVM cannot build for, and it is the reason
this was worth doing before anything else was built on top of the old shape.

**The set is small because the compiler's own selection is** — about forty opcodes, of which ten carry
nine tenths of the emitted lines. There is no `phi`, and that is a fact about the lowering rather
than an omission: codegen keeps every local in a stack slot and reaches it with `load` and `store`, so
what a consumer receives is memory form and may promote it or not as it likes.

**Nothing else concatenates a type, an operand, an instruction, a signature or a module-level line.**
That is what makes the model load-bearing rather than decorative — an escape hatch that let one site
interpolate would put the parser back for that site's sake, so `Inst.Raw` and `Val.Raw` existed only
while the sweep ran and were deleted with their last caller.

**Typing a thing that was text keeps turning up a claim hidden in a string.** `resize` asked
`!v.startsWith("%") && !v.startsWith("-")` to mean *a non-negative immediate*; `alignSuffix` searched
a rendered type for `"%struct."`; a vtable adapter dropped a parameter attribute with
`replace("noalias ", "")`; and `TFloatLit` carried LLVM's hexadecimal form in the **typed tree**, so
the analyzer was rendering a number into back-end syntax at parse time. Each became a pattern match
on the thing it was always about.

**The safety property was byte identity.** The codegen tier asserts on emitted IR *including its
two-space indentation*, and matches temporaries by `%t\d+` — so the order in which registers are
allocated is pinned too. The whole conversion was made with no edit to any existing test file, and
`guide/`'s seventeen programs, 117,000 lines of IR, came out character for character what they were.

That is a strong oracle and it is not a complete one: it says the *text* did not move and says
nothing about whether the data underneath is any use. `IrModuleTests` is the other half, and it
renders nothing on purpose — it asks what a back end asks, which is which functions are there, what
is in their blocks, and what the globals hold.

The library every compilation carries is the **standard module** `sysl` (`13 §8`) — ordinary sysl
source in real files under `library/sysl`, parsed once and hoisted ahead of the user's own declarations,
and auto-imported into every file so its names arrive unqualified. `Library` is the one place that
answers which key a library declaration is filed under, since the compiler names some of them for
itself rather than reading them out of source.

There was a **prelude** beside it for a time — a string literal inside the compiler, keyed under the
anonymous root module a headerless program is also in — and its declarations were drained into the
standard module one surface at a time. It is gone; `Library.key` is now that module's qualification
and nothing else.

## What runs today

A program is a sequence of statements and declarations. Non-declaration statements become the
body of `main`; function, struct, and enum declarations are hoisted (so they may be used
before they appear and may be mutually recursive).

- **The emitted `@main` is C's**, `i32 @main(i32 %argc, ptr %argv)`, because the function the
  platform's own start-up code calls is passed two values — there is no crt0 of sysl's to write, and
  a compiled program is an ordinary hosted C program that clang links libc to. The two are taken
  whether or not anything asks for them, and a program that declares `main(args: []string)`
  (`13 §7`) gets them as a slice through one call to the library's `sysl.args.args_of` — named by the
  key it is filed under rather than by resolving the word, which is why moving it into a submodule of
  the library changed nothing about what a `main` may be written as. **A sysl `main` is
  therefore emitted under a reserved symbol**, since the one it is written as is already taken by the
  function the platform starts at; the reserved name holds two `$` separators, which no key can
  (`Modules.qualify` writes one), so it cannot collide with any module's declaration.

- **A program is a tree of modules, and a module a directory of files** (`13 §1`, `13 §6`). The
  driver takes a **project root** and walks it: each directory is a module named by its path from
  the root, each file declares that name in a `module a.b` header, and a header that disagrees with
  where the file sits is an error. The files of one module share one scope — hoisting registers
  every signature before any body is checked — so a call, a type, a trait, its `impl`, and a
  generic's instantiation may each sit in a different file with no ordering and no forward
  declaration. A file with no header is in the **anonymous root module**, whose name is the empty
  path, which is why a single-file program needs none and why nothing outside the root can name
  what the root declares.

- **A module reaches another's members by naming them in full** (`13 §3`): `std.fs.read(p)`,
  `geom.Point`, `geom.Shape.Round(7)`. An unqualified name is looked for in the module it is
  written in, then among the file's imports, so two modules may each declare a
  `Point`, a `size`, or a variant `Round`. Every table is keyed by the **qualified** name
  (`Modules`), with `$` between the module and the declaration so the prefix can never be confused
  with a member (`Point.dist`) or an instantiation (`f.int`); `$` is legal in an LLVM symbol, so
  the key is still the emitted name and diagnostics read it back with dots. One file of the program
  carries the statements it runs (`13 §7`), and they are read in the module of the file that wrote
  them.

- **A module-level `val` is a `private` global, filled either by the linker or by `main`**
  (`13 §7`). A constant tree — numbers, the arrays and repeats built from them, `null`, and a
  `ptr_cast` of any of those, which lands as an `inttoptr` constant expression — is written into
  the object file as a `private constant` and nothing runs. Any other initializer is code: the
  symbol becomes a `private global zeroinitializer` and the value is computed and stored in a
  prologue `main` opens with, before the program's own statements. **Which one goes first is the
  order their dependencies describe** — a `val` is filled after every `val` its initializer reads,
  followed through the functions it calls and through a method table by taking every function the
  trait's tables put in the slot. A cycle is reported at the declaration that closes it, and can
  only ever be within one module, since a cross-module edge would need the module graph to cycle.
  The type is held to what **counts nothing** — no reference, weak reference, slice, or `string` —
  which keeps a `val` from being a count nothing releases. A raw pointer and the address of a
  function are admitted, since neither owns what it addresses.

- **Only what the program can reach is emitted** (`15 §3`). `Reachability` runs last, over a typed
  program every other pass has already read: it walks out from the statements the entry point runs,
  the `main` it runs after them, the `val` initializers that fill storage before either, and the
  method tables, and drops every function and `extern` it never arrives at. Analysis is untouched by it — a body nothing calls is still checked,
  and a slice that escapes one is still rejected — which is what fixes the pass order. A call whose
  target is settled at run time is answered with *every* function the trait's tables put in that
  slot, so the walk over-approximates in the only direction that is safe.

- **`import` shortens that path and grants nothing** (`13 §3`), in the five Scala forms:
  `import a.b.c`, `{c, d}`, `{c as e}`, `.*`, and `import a.b` for the module itself. A binding
  belongs to the **file** that wrote it — a sibling file of the same module does not get it — or to
  the **block**, where an import may also be written and where it lasts as long as the block's local
  bindings do. What travels with every declaration is therefore a `Scope` (its module *and* its
  imports), because a body means what it meant where it was written. A selector binds a name and a
  wildcard merely offers one, which is what makes an explicit import win over a wildcard, two
  wildcards offering one name an ambiguity at the use, and a name bound twice an error at the
  second import. An import may not be given a name that a module path already begins with. What a
  `Scope` carries alongside those is the **file**, which is what the visibility rule below is
  measured against.

- **`private` is the file and `private[M]` is a module subtree** (`13 §2`). Public is the unmarked
  default and records nothing; a restricted declaration records where it may be named from, and
  every resolved key is checked against where the analyzer currently is. `M` is a simple name
  matched against the enclosing modules from the declaring one outward, first hit winning, so
  `private[geom]` in `geom.mesh.geom.tri` is the nearer `geom`; a name matching none of them is
  reported at the declaration, which then stays public so one mistake stays one diagnostic. A
  restriction decides who may write a name and makes no second namespace — a private declaration
  still spends its name in its module, and an enum's variants carry its own. A name a file may not
  reach is **not a candidate**: resolution goes on through the file's imports, and
  reports the restriction only where nothing else answers. A wildcard offers only what is visible;
  a selector naming something private is refused at the import. File-private functions are not
  emitted `internal` (`13 § Open g`) — one LLVM module means it would buy nothing until separate
  compilation.

- **A declaration may not name in its signature a type that does not reach as far as it does**
  (`13 §2`). The check runs once every declaration is registered, since either of the two may be
  written below the other, and it compares **written names** rather than resolved types — a type
  parameter, `Self`, and a scalar stand for no declaration, and a name the file may not reach at all
  is a complaint the resolution that built the signature has already made. It covers a parameter, a
  result, a struct field, an enum variant's payload, a type argument, a trait behind a memory mode, a
  member of a type or trait (as visible as what it belongs to), and a **bound**. A bare-`private`
  declaration is exempt: it is read in one file, and a type it can name is visible there. An `impl`
  is outside the rule in both directions, since its members' signatures are the trait's and a
  mismatch is refused as non-conformance. The diagnostic names the type by the path a reader would
  have to be able to write, so an import alias does not hide which type is meant.

- **Two modules may not depend on each other** (`13 §6`). The graph is over **references** rather
  than imports, because a qualified path reaches another module with no import to scan for: an edge
  is recorded wherever resolution finds a name belonging elsewhere, and an import adds one whether or
  not the name it bought is ever written. Nothing depends on the **standard module** or on the root
  module — one is auto-imported everywhere and the other has no name to be written. The check runs at the end of the
  walk, since an edge can be made by a body only reached through an instantiation, and each cycle it
  reports is broken at the reference the message points at so that an unrelated one elsewhere is
  found too. The message is the chain (`'a' depends on 'b', which depends on 'a'`), turned to begin
  at the first of its modules by name so that which file the walk started from does not show.

- **Statements:** `var name [: type] = expr`, expression statements (including assignment and
  compound assignment), `return [expr]`, `break [expr]` / `continue`, and `defer stmt` — which
  emits nothing where it stands and lays its statement down at each edge that leaves the enclosing
  block, ahead of that block's releases (`03 § defer`). Loop and branch bodies
  follow Scala-3 style: `then`/`do` is **required for a one-line body** and **optional before an
  indented block**. Optional `end if` / `end while` / `end loop` / `end for` markers may close a block (`end`
  is a *soft* keyword, not reserved).
- **`if`, `match`, and loops are expressions.** They yield the value of the taken branch/arm, so
  `var label = if c then a else b` and `f() -> T = x …` both work; in statement position the value
  is simply unused. **`match` is postfix** (`09 §5`) — `scrutinee match` with the arms indented
  under the keyword, as in Scala, so one match feeds another and the scrutinee reads left to
  right. It binds looser than every operator, so `a < b match` chooses on the comparison.
- **Loops** — `while cond`, `loop` (no condition at all), and `for name in a..b` / `a..<b` (over a
  range) or `for name in seq`
  (over an array or slice) — are expressions too. A `break expr` leaves the nearest loop and
  makes `expr` its value; `continue` skips to the next iteration. An optional `else` block (after
  the body, Python-style) runs on *normal* completion — the condition turned false, or the range
  ran out, with no `break` — and its trailing expression is the loop's value on that path. With
  no `else`, normal completion yields `unit`, so a value-carrying `break` needs an `else` to give
  a matching value when the loop finishes on its own; every `break` value and the `else` value
  must share one type, which becomes the loop's. A `break`/`continue` unwinds the body's
  ownership regions on the way out, the same discipline as `return` bounded to the loop.
  **`loop` takes no `else`** — it has no normal completion for one to run on — so its `break`s
  alone decide its type, and a `loop` with no `break` is `never` (`00 §10`): the body branches
  straight back to itself and the end block closes as `unreachable`. Scalar patterns are literals, `|`-alternatives (Scala-style —
  `1 | 2 | 3`), literal ranges (`1..10`, `0..<10`), and the `_` wildcard, with optional `if`
  guards; a bare name binds the value. A scalar `match` used as a value must be exhaustive
  (have a catch-all); an enum `match` must always cover every value or carry a catch-all, and
  the arms cover it together, nested patterns included.
- **Functions** are keyword-less, Scala-style: `name(params) -> ret = expr` or an indented
  block whose trailing expression is the implicit return value. A missing `-> ret` means
  `unit`. A block-bodied function may also `return` early.
- **The two valueless types part company at the layout** (`00 §11`, `00 §12`). Both lower to
  `void` as a *result*. `unit` is **zero-sized**: a field of it is skipped in the aggregate with
  the indices behind it shifted, a parameter of it is dropped from the emitted signature, a binding
  of it takes no slot, and every read or write of one emits nothing — while its initializer, its
  argument, and its receiver chain are still evaluated for their effects. What it may not be is
  something to point at, so `&unit`, `*unit`, `[N]unit` and `[]unit` are refused. `never` is not
  zero-sized: it has no values at all, so it stays a result type only, and inference will not put a
  diverging argument into a generic slot.
- **Value structs:** `struct Name` with indented `field: type` lines; positional construction
  `Name(a, b)`, field read `p.x`, and in-place field assignment `p.x = v`. Structs pass to and
  from functions by value.
- **Enums.** A `simple` enum (`enum Color` with dataless variants) is a set of integer
  constants, auto-incrementing from an optional explicit `Blue = 10`; variants are named
  `Color.Blue` or bare `Blue`. A **data enum** (any variant carries a payload, `Circle(radius:
  int)`) is a tagged union: construct a variant with `Circle(5)` or a nullary one as `Empty`,
  and destructure it in a `match` — `Circle(r) -> …` binds the payload, sub-patterns may nest
  (`Wrap(Val(v))`), and guards may read the bindings. Enums pass by value.
- **`end` markers.** A `struct`, `enum`, or function block may optionally be closed by
  `end Name`, whose name the parser checks against the declaration's own. `end` is a soft
  keyword, so it remains usable as an identifier.
- **Generics, monomorphized.** Functions, structs, and enums may take type parameters
  (`id[T](x: T) -> T`, `struct Box[T]`, `enum Option[T]`), and a named type may be applied to
  type arguments (`Box[int]`, `Result[int, string]`). Each distinct set of type arguments is
  instantiated into its own function or aggregate under a mangled name, so codegen never sees
  a type parameter. Type arguments are **inferred** from the argument types, and from the type
  the surrounding context expects when the arguments alone do not determine them — which is
  what lets `var o: Option[int] = None` and `f() -> Result[int, string] = Ok(5)` work. There is
  no syntax for applying type arguments explicitly at a call site.
- **Traits, both ways they dispatch (`02`).** An `impl Trait for Type` lowers its methods to the
  same `Type.method` functions a method written in the type's own body produces, so **static**
  dispatch through a bound (`f[T: Shape](x: T)`) is monomorphized down to a direct call with no
  indirection. **Dynamic** dispatch is `*Trait` / `&Trait`, a two-word `{ vtable, data }` value:
  the sigil says whether the data word is the value's own address or the reference-counted box
  it sits in, and there is one table per (trait, type, sigil) so a slot can reach the receiver
  its implementation declared. A slot whose receiver already *is* the data word holds the
  implementation directly; the rest hold a small adapter. Erasure is a coercion applied wherever
  an object type is expected, so an `if` whose arms are different concrete types meets at one.
  A trait is object-safe when every member has a receiver and mentions `Self` nowhere else,
  which excludes the whole operator catalog of `14` — those traits are for bounds.
- **Default bodies (`02`).** A trait member written with a body is one an `impl` inherits
  unless it writes its own, and a trait whose every member has one needs no block at all. The body
  is checked once at the trait, as a generic function over `Self` bounded by that trait, so it may
  assume exactly what the trait declares — and then **copied per implementing type** under that
  type's own `Type.method` name, which is why a call, a vtable slot, and the escape summary all find
  an ordinary function. The library uses two: `Writer.failed` and `Reader.failed` both default to
  `false`.
- **Properties as trait members (`02`).** A trait asks for one by dropping the body from `08`'s
  property form (`size -> int`), and an `impl` supplies it. A property has a receiver it never
  spells — by value — so it needs nothing of its own at either dispatch: a bound licenses `x.size`
  the way it licenses a call, and an object reads one through a table slot beside the methods. What
  a bound still never reaches is a **field**, since a field is layout rather than behaviour.
- **An `impl` for a composed type (`02`).** The type an `impl` is for is a full type reference, so
  `impl Display for []int` and `impl Total for [3]int` are as ordinary as one for a struct: same
  member table, same bound, same vtable slots. The members are *emitted* under the type **mangled**
  (`slice.int.display`, `arr3.int.total`) rather than under the owner key a diagnostic uses, because
  `[]int` is no symbol a linker would take; the two coincide for every type that is a name. A memory
  mode (`*Point`) and a trait object (`*Show`) are refused, since an implementation for either would
  be about nothing.
- **An `impl` for a generic type, conditionally (`02`).** `impl[T: Show] Show for Box[T]` implements
  the trait for every `Box` at once; its members are monomorphized per receiver exactly as a generic
  type's own are, and are *named* as those are (`Box.show.int`), which is what a vtable slot has to
  use as well. The subject must be the type applied to the block's parameters — one key per generic
  type, so an implementation for *some* instantiations has nowhere to be filed and the question of
  choosing between two never arises here. (`02`'s `override` orders implementations by subject and
  reaches the composed shapes below, not this keying rule.) The bounds decide
  **which instantiations conform**, asked one step in so the answer composes, and they make the
  members checkable at their **definition**: a body using what no bound licenses is reported on its
  own line with nothing instantiated.
- **An `impl` for a composed shape (`02`).** `impl[T: Display] Show for []T` is the same block
  written for a type with no name to be generic over, and everything above holds unchanged. What is
  its own: a composed type is filed under the whole of itself (`[]int`), so a shape gets a key by
  dropping the arguments (`[]`, `[3]`) that a member lookup falls back to, and a symbol the same way
  (`slice.show` instantiated at `int` is `slice.show.int`, which the written `[]int`'s
  `slice.int.show` cannot be mistaken for). An array has **two** such keys, since its length may be
  a value parameter (`10 §9`): the per-length `[3]`, and one under which the length is an argument
  like the element type, whose symbol drops it the same way — `arr.display.c3.int`. The per-length
  key is asked first. A `string` is not a slice and is not covered. A shape
  and a type of that shape written out in full are **two implementations for one type**, so whichever
  is written second is refused — sysl has no rule that picks between two, and that goes for member
  *names* across the two as well, since a type's members are one namespace.
- **Bounds on a type's own type parameters (`10 §5`).** `struct SortedList[T: Ord]` and
  `enum Tagged[T: Display]` take the bounded list a function takes, in the same place. Every
  application of the type is held to it — a declared parameter, a result, a field, a payload, a
  construction — and where the argument is itself a type parameter the answer is what *its* bounds
  promise. Because the type now has somewhere to write what it assumes, its members are checked at
  their definition like a generic `impl`'s, which removes the last asymmetry between the two. The
  question is answered after every `impl` is hoisted, so a bound may be met by an implementation
  written further down the file.
- **Associated functions on generic types (`10 § Open b`).** `Box.of(41)` is a member with no
  receiver, so the type's arguments are inferred from the call the way a generic free function's are
  — from the arguments, and from the expected type where those do not settle them (`var c:
  Cursor[int] = Cursor.none()`). `Self` in the signature is the type applied to its own parameters,
  so `-> Self` and `-> Box[T]` infer alike. A parameter neither route reaches is an error naming it;
  the bound is checked against what was inferred, in the *type's* name. Only a struct or an enum is
  named in call position, so a block for a built-in or a composed shape may not declare one.
- **Members with type parameters of their own (`10 § Open b`).** `with[U](self, x: U) -> Pair[T, U]`
  carries two lists that are fixed from two places: the type's are read off the receiver, which is
  already a type and already carries them, and the member's own are solved at the call by the rule
  above. The lowered function takes the type's parameters first and the member's after, so appending
  what the call solved to what the receiver said is the whole instantiation. A type with no
  parameters may still have a member with some, since none of that goes through the receiver. Each
  list is held to its bounds in the name it was written under, a name collision between the two is
  refused at the declaration, a property may declare none, and — because a trait declares no generic
  method — neither may an `impl`.
- **Traits with type parameters of their own (`02`).** `trait Sink[T]` is a family of promises, and
  the arguments are written the same way in all three places a trait is named: a bound
  (`[X: Sink[int]]`), an implementation (`impl Sink[int] for Buffer`), and a trait object
  (`&Sink[int]`, whose table is `@vt.ref.Sink.int.Buffer`). A bound's arguments are types, so one may
  name another parameter of the same declaration and be solved at the call. The trait's parameters
  are fixed by the implementation, which puts them alongside `Self` in what a member's signature and
  body resolve under — a method written in the trait's `T` and one written in the type that `T` is
  are the same signature. A trait may bound its own parameters, and everything applying it is held
  to them. A type implements a trait **once at each argument list** — the arguments are part of what
  an implementation is filed under, so `Sink[int]` and `Sink[string]` on one type are two
  implementations and a call picks between them by the arguments written at it
  (`02 § One implementation per argument list`). What has no rule to choose by
  is a parameter appearing only in a *return* type: nothing at the call says which was meant, and the
  compiler says so rather than guessing.
- **Rendering, through `Display` and its `Writer` sink (`14 §6`).** A value that is not a scalar
  writes itself into a sink rather than returning a string, so rendering allocates nothing and a
  `no alloc` module can still log. The sink is a `*Writer` trait object; `print` supplies one over
  standard output and `str` supplies a growable buffer whose bytes become the string, and both are
  the compiler's — **not for want of a way to write them**, which is what this used to say. A
  growable buffer is ordinary sysl (`sysl.buf.Buf[T]`) and so is a sink over one
  (`sysl.buf.ByteSink`, a struct with an `impl Writer`). The reason is narrower: `str(x)` renders
  without a program naming a sink, so it cannot reach a module the program never imported, and it
  goes through storage the compiler lays out instead. `library/sysl/buf/buf.sysl` says the same thing
  from the other side, and it is why the sink sits beside the buffer it wraps rather than in the
  prelude. A **scalar** keeps its direct path in `print` and its own `str`, so a
  program that prints only numbers builds no sink at all. A `Writer` **borrows** the bytes it is
  written — checked by escape analysis, which is what lets a renderer pass a slice of its own stack
  buffer through a trait object. A hole's format specifier travels to the `Display` it calls and is
  **acted on** there: every library renderer pads through one `display_pad`, and an implementation
  applies the same call to its own complete text.
- **`Option[T]` / `Result[T, E]` and `?`.** Both come from the library as ordinary generic
  enums. The postfix `?` unwraps the success payload of one, or returns from the enclosing
  function early with the failure re-wrapped in *that* function's return type — so `?` needs
  the caller to return the same one, and to propagate the same error type.
- **Scalar types.** The integer family `iN` / `uN` for any width up to 64 bits, the
  pointer-width `usize` / `isize`, the floats `f16` / `f32` / `f64`, `char`, `bool`, and the
  friendly aliases (`int`, `byte`, `long`, `real`, …). Arithmetic wraps at the declared width
  and never promotes; signedness selects between the division, remainder, and right-shift
  instruction pairs, and between the comparison predicates. A literal takes its type from its
  suffix or from the context it appears in (`01` §Literals), and a value that does not fit
  is rejected.
- **Conversions** are written with call syntax — `u32(c)`, `byte(n)`, `real(n)`, `int(x)` —
  and lower to one LLVM cast each, with two conversions that need more than a bare instruction.
  Float-to-integer (`int(f)`, `u32(f)`, …) goes through the saturating intrinsics
  `llvm.fptosi.sat` / `llvm.fptoui.sat`: a plain `fptosi`/`fptoui` is poison when the source is
  out of the target's range or is NaN, and what the hardware then does differs by target, so the
  same program would print different numbers on different machines. Saturation pins it down
  everywhere — out of range clamps to the type's minimum or maximum, NaN becomes zero — which
  keeps `int()` total (it never traps) and matches Rust's `as`. The one *trapping* conversion is
  `char(u)`, which tests the value at 64 bits and traps when it is not a Unicode scalar value.
- **Raw pointers.** `*T` in any type position, `&place` to take an address, `*p` to read
  through one, and `null` — which takes its `*T` from context the way a numeric literal takes
  its width. A **place** (a local, a dereference, a field of either) is what `&` takes, what
  assignment writes to, and what `++`/`--` and compound assignment update, so all three forms
  work uniformly on a variable, a field, and through a pointer. Field selection dereferences
  one level automatically, on `*T` and `&T` alike, so there is no `->`. Pointers and
  references compare with `==` / `!=` by address and have no ordering; `bool` gained equality
  at the same time.
- **References, counted automatically.** `&T` and `&sync T` are distinct types that pass
  through generic inference, and a field of either kind makes a type legally recursive. A value
  written where a reference is expected is put on the heap, so there is no allocation keyword;
  the compiler emits the retain and release that keep the object alive exactly as long as
  something names it, and frees it through its own deallocation hook at zero. A reference is
  never null, so an absent one is `Option[&T]`. `&sync T` counts atomically — a relaxed
  increment, a releasing decrement, and an acquire fence before the destructor.
- **Arrays and slices.** `[N]T` is a value type with no header: a literal (`[1, 2, 3]`) fixes
  its length from how many elements were written, and a declaration with a type and no
  initializer (`var buf: [64]u8`) starts at the type's zero value — the general rule, legal for
  any type that has one, which excludes anything containing a `&T`. `[]T` is a view of elements
  someone else owns. Every subscript is checked: an index may be any integer, is widened to 64
  bits, and is compared unsigned, so a negative one fails the same test. `a[lo..hi]` takes a
  view, keeping the inclusive/exclusive meanings the range operators have everywhere else, with
  either end omittable; `a.len` reads as a field; `for x in a` binds a copy of each element.
  Taking a view retains the buffer, so a slice cannot outlive what it views — and where there
  is no buffer to retain, because the array is one this frame owns, the **escape analysis** of
  `05` is what makes the view safe: it is inferred with nothing written in the source, carried
  across calls by one bit per parameter, and iterated to a fixpoint so recursion converges
  (`07`).
- **Strings.** A `string` is an immutable validated `[]u8` and is exactly the same three words
  (`04`), so it inherits the view machinery whole: `s.len` in bytes, `s[i]` yielding one checked
  `u8`, `s[a..b]` sharing rather than copying, and `s.bytes` handing the same value to anything
  that takes a `[]u8`. What it adds is the validity invariant — a substring must land between
  characters, and a mid-codepoint cut traps like any other failed check — and immutability, so
  `s[i] = v` and `&s[i]` are rejected outright. Comparison is by bytes, which for well-formed
  UTF-8 is codepoint order, so `==` and `<` work and a literal can be a `match` pattern. A
  literal's owner is **null**, which is how `04`'s "immortal" is spelled: no allocation, and
  retain and release are run-time no-ops. Iterating is written `for b in s.bytes` or
  `for c in s.chars`, since a string has two granularities and choosing one silently would be a
  guess; the second hands back a cursor the loop drives through `Iterate` (`14 §7`).
- **Recursive types.** A cycle through a `*T` or a `&T` is legal and pointer-sized; a cycle
  every edge of which is by value is rejected as having no finite size. An instantiation is
  registered before its fields are resolved, so a field that points back at it finds it.
- **Expressions:** the full settled precedence grammar (`01`) over the scalar types and
  string literals. `++`/`--`, unary `-`/`!`/`~`/`*`/`&`, chained comparison.
- **A comparison chain short-circuits, sharing its middle operands.** `a < b < c` compares `a`
  against `b` and, only if that holds, `b` against `c` — with `b` evaluated once and used twice, so
  this is not a rewrite into `&&` over independent comparisons. Sharing is also what shapes the
  ownership bookkeeping: an operand evaluated in one block is used again in the next, so it cannot
  be released at the end of the block that made it. Each comparison opens its own region and the
  exits **unwind in reverse**, so a path that leaves the chain early passes through exactly the
  releases for the operands it built. A lone comparison has nothing to short-circuit and stays
  straight-line.
- **`print(a, b, …)`** — a **desugaring onto library functions**, not a builtin and not a user
  function. Each argument becomes a call to the renderer its static type reaches — `printi`,
  `printu`, `printr`, `printb`, `printc`, `prints` — widened to the width that renderer takes, with
  `printc(' ')` between and `printc('\n')` at the end. An integer **wider than 64 bits** is the one
  that is not widened, because there is nothing to widen it to: it renders itself with `str` and the
  string goes to `prints`. Every one of those is sysl in the library
  (`04`, *Printing*); the compiler knows the six names and the widening rule and emits no printing
  code of its own. They all write through one sink, `putbytes`, which walks a byte count rather
  than stopping at a terminator, because a sysl string may hold an interior NUL and every `%s`
  conversion — `%.*s` included, whose precision is a maximum rather than a count — would stop
  there.

## IR dialect (locked against the dev toolchain)

Textual LLVM IR with **opaque pointers** (`ptr`, never `i32*`), verified against Apple clang
on arm64. Floats are emitted as **hex doubles** (`0x…`) so the textual round-trip loses no
bits. An `extern` is declared under its **symbol** — its link name where it has one, its sysl name
otherwise — and each symbol is declared once however many declarations name it; an `extern`
*variable* is the same rule on one line of storage, `@sym = external global <ty>`, emitted after the
type definitions so a named aggregate is not opaque where it is named. Value
structs lower to named aggregates (`%struct.Name = type { … }`); construction is an
`insertvalue` chain, and a field read is `extractvalue`. A **write** instead computes the
place's address — a local's own slot, a loaded pointer value, or a `getelementptr` chain over
either — and `store`s through it, which is one mechanism for `x = v`, `s.f = v`, `*p = v`, and
`p.f = v` alike. A place whose storage is qualified `volatile` (`03 § Device memory`) takes the
marker on the instruction — `load volatile` / `store volatile` — and that is the whole of what the
qualifier costs; the field itself is laid out exactly as an unqualified one, so a register block is
the same aggregate it looks like. A qualified **field** is one exception to the `extractvalue`
read above: it is reached at its own address instead, because loading the aggregate to lift one field
out of it would read every register in the block. A whole-aggregate access is marked whenever the
type holds a qualified field anywhere in it.

A qualified **bitfield** is the same exception reached one level up, since a bitfield has no address
of its own: the *container* is what is loaded and stored, at the receiver's address, so a read is one
`load volatile` and a write one `load volatile` and one `store volatile` of it. `15 §1` states the
rule and what the read-modify-write costs a driver.

**A large aggregate is the other exception, and it is a whole lowering rather than one
instruction.** Above `Layout.DirectBytes` — 128 bytes — a value is never a first-class LLVM value
at all: it is built where it is going to live, copied with `llvm.memcpy`, read a field at a time
through its address, returned through an `sret` out-pointer the caller supplies, and passed as the
address of storage the caller holds, the callee making its own copy at entry. Nothing about the
language changes; `Name(a, b)` still constructs and a struct still passes and returns **by value**,
with the copy that promises. What changes is where the copy happens. The reason is arithmetic: an
optimizer asked to reason about a first-class value of kilobytes reasons about every byte of it, and
that does not stay linear. `guide/kernel` builds a 20 KB struct and hands it about at thirty-five
call sites, and the module took **408 seconds** to compile at `-O1` as values and **0.93 seconds**
through memory. The threshold is well clear of everything the language itself uses — a slice and a
string are twenty-four bytes, a trait object sixteen — so nothing in ordinary code changes shape.
Six places spell a sysl signature (the definition, a library declaration, a call, a variadic
callee's whole function type, a method table's adapter, and the `ret`), and they agree because they
all ask `syslSret` / `syslResult` / `syslParam`. `*T` and `&T` are both the opaque `ptr`; inside a
mangled name a memory mode is spelled as a word (`ptr.` / `ref.` / `sync.` / `volatile.`), since a
sigil is not an LLVM name character, and the qualifier is mangled for the reason a view's read-only
bit is — one layout, two sets of instructions, so no instantiation may share a body across them.
A `&T` addresses a **box** `%arc.T = type { i64, ptr, i64, T }` — a **three-word header** and then
the payload, so reading through one is a `getelementptr` past it. The words are the strong count,
the function that destroys the payload, and the **weak** count (`03`), and the header is named on its
own (`%arc.header`) because the runtime walks it without knowing the payload's type.
Both taking a share and giving one back are **type-independent** (`@arc.retain` /
`@arc.release`): reaching zero calls through the hook, which is the per-payload-type function
that releases whatever the payload held and then returns the storage — or, while a weak reference
still names the box, releases the payload and leaves the storage for the last weak share to free.
That indirection is what
lets a slice release its owner, whose payload type its own type does not name. Walking an
aggregate's reference-carrying fields is a helper emitted once per type rather than inlined,
since a data enum needs a tag test per variant, and an array is walked with a loop rather than
an unrolled chain. A **view** — a slice or a string, which differ in the type
system and not at all in the machine — is `{ ptr owner, ptr first, i64 len }` by value; its owner
is null when there is nothing to keep alive, so it counts through a null-tolerant pair while the
reference path stays branch-free. A string literal is a **constant** view of interned bytes with
a null owner, so it needs no instruction to build and no count to hold; the bytes carry a
trailing NUL that the length leaves out, for the C interop `04` wants. The atomic pair and the
null-tolerant pair are each emitted only into a module that turns out to need them, as are the
three string helpers — writing bytes by length, comparing two byte runs, and testing that an
offset is not inside a character.
A simple enum is plain `i32`; a data enum
lowers to a value aggregate `%enum.Name = type { i32 tag, [N x unit] }` — the tag and **one region
every variant shares**, sized to the widest payload and counted in whatever unit the strictest
variant must be aligned to, so `Pair(x: i64, y: i64)` beside `Small(c: u8)` gives
`{ i32, [2 x i64] }`. Each variant's payload is its own named aggregate (`%Name.Variant`), and
reading one spills the enum to a slot, walks to the region, and loads that aggregate out of it —
a union has no `extractvalue` of its own. A pattern test is a tag `icmp` plus those reads (pure, so
nested fields are read unconditionally and a failed outer tag simply ANDs a `false` through);
bindings are stored into fresh slots only once the arm — and its guard — is taken. An instantiated generic name is
flattened into its LLVM name — `Result[int, string]` becomes `%enum.Result.int.string` and
`id[T]` at `int` becomes `@id.int` — which stays unambiguous because every name has a fixed
arity.

## Deliberate shortcuts (unwind these as the language grows)

1. **The scalar table stops short of its widest member — and by now that is `f128` alone.**
   ~~An integer wider than 64 bits~~ and `f128` were both diagnosed rather than lowered: printing
   them portably needs a runtime this stage does not have (`long double` is 64-bit on the arm64
   Apple ABI, so `fp128` cannot go through `printf`). **The integer half has stopped being a
   shortcut at all.** The ceiling is LLVM's own `2^23 - 1` (`Type.MaxIntegerBits`), so there is no
   width the language names and the back end refuses — `u256`, `i1024` and `i8192` all lower, with
   division, remainder and the wrapping operators expanded inline and no compiler-rt routine behind
   them. What made it possible is that a wide integer does not need a `printf` conversion at all: it
   renders itself through `str` and goes out as the string that came back, which is the one scalar
   whose printing allocates, and the renderer is generated per width from the width itself rather
   than written once at the widest. `00 §5` records the two reasons this was believed to stop at
   128 and why neither held. `f128` has no such route, since rendering a float is `snprintf`'s job
   either way, and it stays the open question it was.
   `usize` / `isize` are fixed at 64 bits by a constant. There **is** a
   target description now (`targets.md`) and this does not read it, for the reason `Layout` does
   not: every target in the registry is 64-bit, and the registry refuses one that is not.
   A narrower float constant is emitted as the `double` constant rounded
   down to it, which is correctly rounded except in the rare double-rounding case.
2. **~~Every string is a literal or part of one.~~** No longer a shortcut — every operation `04`
   specifies is built. `from_utf8`, concatenation, `str(x)`, `s.copy()`, `string(c)`,
   `str_builder()`, and `cstring(s)` all make bytes, so a string a program holds need not trace
   back to a literal and its owner word need not be null. Only a **literal** is validated by the
   lexer; bytes a program computed are validated at run time by `from_utf8`, which is where `04`
   puts the check. `s.chars` decodes what is already valid either way, and ordering is by byte with
   no collation, which is what `04` specifies.
3. **All locals are `alloca`.** Every `var`, parameter, and loop variable gets a stack slot;
   reads `load`, writes `store`. Slots are hoisted into the entry block (names are unique per
   function, so one inside a loop does not grow the stack per iteration), but there is no
   SSA/`phi` construction — `if`/`match` values route through a stack slot.
4. **Functions are keyword-less with mandatory `(params)`.** Parameterless functions
   (`name -> T`), inner `def`, and the pure/effect (`def` vs plain) distinction are deferred.
   ~~default arguments~~ are built (`12 §2a`), together with calling by name; both are resolved in
   the analyzer, which fills the gaps and reorders the list, so a call reaches the emitter already
   positional and complete and nothing here changed. The keyword-less form is disambiguated from a
   call by the typed parameter list and a following body.
5. **Reference counting is emitted where it is obviously needed, and nowhere elided.** Every
   named slot takes a count and gives it back; every temporary is released when its statement
   or branch ends; every function retains its parameters and returns a count already taken.
   That is correct but not minimal — a retain/release pair that provably cancels is still
   emitted, and a reference borrowed for the length of a call is still counted twice. Eliding
   them is a later pass over the same placement, not a change to it. What this item used to list
   beside that has been built and is struck here rather than deleted, since the shortcut is the
   claim and the claim has narrowed: ~~`weak` does not exist yet, so a reference cycle is a leak~~
   (built — `03`, and the count is a third header word); ~~there are no methods~~ (built — `08`);
   ~~a data enum reserves storage for every variant's payload at once~~ (it is a union — the tag
   and one region sized to the widest payload). What remains is the **deallocation hook**, which is
   always the compiler's own, since there is still no way to write an allocator. It is never null on
   a box the compiler built: a payload holding nothing has no contents to walk and still has storage
   to give back, so it carries the hook that only frees.
6. **Exhaustiveness is computed over all the arms at once**, as a matrix with one row per
   unguarded pattern and one column per value still being discriminated. A column whose type has
   a finite constructor set — an enum's variants, a struct's single shape, `bool`'s two values —
   is split constructor by constructor; any other column is covered only by a wildcard, so rows
   headed by a literal or a range drop out of it. The gap is reported as the values no row
   matches, written the way a pattern is. Guard expressions are evaluated after the pattern
   matches and its bindings are in scope, and a guarded arm is left out of the matrix entirely.
7. **`print`'s renderers are the library's, not a `std` I/O surface**, and the desugaring picks a
   *scalar's* by static type rather than through its `Display` — deliberately, so a program that
   prints only numbers builds no sink (`14 §8 b`). A related over-approximation shows up in the
   output: making a slice turns the ARC runtime on even where the owner is statically null, so a
   program that only prints an integer still carries the allocator declarations it never calls, and
   `FormatSpec`'s layout is emitted whether or not anything renders, since a non-generic type is
   instantiated eagerly wherever it is declared.
8. **`for` iterates a range, an array, a slice, or a cursor.** `downTo`, `step`, and `reverse`
   are not yet lowered. A type implementing `Iterate` is the fourth thing a loop takes: the
   expression is evaluated once into a slot the loop owns, `next` takes that slot's address each
   round, and running out is normal completion so the `else` runs. A container is deliberately not
   one — a `Buf` is walked as `b.view()`, which costs an index rather than a call (`14 §7`).
9. **~~Escape analysis rejects rather than promotes.~~ No longer a shortcut.** An array whose view
   escapes is moved to the heap, silently, exactly as `05` specifies; a program that means to return
   a view writes the ordinary `var buf: [64]u8` and says nothing. Refusal survives only where there
   is nothing to promote *into* — a module that declared `no alloc` — or nothing the body owns to
   promote: an array a caller passed by value, and an array that is a field of a value on the frame.
   `--explain-escapes` arrived with it and reports every promotion the compiler made.
   What is still an approximation is the two things `05` allows: a call's result is treated as
   viewing *every* slice argument rather than the ones it really views, and a value's provenance is
   tracked per local rather than per field, so a struct that holds one confined view is
   confined entirely. **A growable sequence is no longer missing either** — `sysl.buf.Buf[T]` has
   `push`, a capacity, and storage it owns, and it is ordinary sysl rather than anything lowered
   here; a `[]T` still views and is meant to. A bounds failure traps with no message, exactly as
   `char(u)` does.
10. **Generics are monomorphized with local inference only.** Type arguments come from the
    argument types and the expected type of the expression; there is no unification across a
    whole function body and no explicit type application at a call site. A parameter nothing
    determines is an error rather than a default. `?` is wired to the library's `Option` and
    `Result` **by name**, standing in for the eventual trait that will describe "can be
    short-circuited".
11. **A generic body is checked against its bounds and no further.** All of `14` is implemented, `§4`
    included: a generic function is analyzed once more with each type parameter standing in for
    itself; a method call, an *operator*, and a rendering on one all resolve through the union of
    its bounds' traits; and `sum[T](a, b) = a + b` is diagnosed on its own line as needing
    `T: Add`, whether or not anything instantiates it. A **property** a bound declares resolves the
    same way, since a property is behaviour that happens to read like a field. Forwarding a parameter
    to a bounded callee is checked the same way too: a bound is satisfied by a bound. A trait's
    default bodies go through the same pass, bounded by their own trait, and so do the members of a
    generic `impl` — one for a shape included — bounded by what the block declares (`02`). A member
    of a generic *type* goes through it too, bounded by what the type asks of its own parameters
    (`struct SortedList[T: Ord]`, `10 §5`), and a generic type's **fields** are laid out once the
    same way — which is what catches a field applying another bounded type to this one's parameter.
    Each of those walks is sandboxed on its own: a parameter standing in for itself is remembered
    under the name it was written with, and two declarations that both spell theirs `T` bound it to
    different things.

    The pass reaches what a bound could license and no further. A mistake that needs the *concrete*
    type to settle — a result that disagrees with its declared type for reasons `Self` alone cannot
    decide — is caught where every other concrete mistake in a generic body is, at each
    instantiation. So a generic nothing calls, or a trait nothing implements, gets its bounds checked
    and nothing more. A field read is deliberately not in that category: no bound could ever license
    one — a field is layout, not behaviour — so it is settled at the definition (`10 §5`), and the
    diagnostic says so after finding that no trait declares a property of the name either.

    A `FormatSpec` is now **acted on** as well as delivered. Every library renderer ends at one
    `display_pad`, so `f"${p}%8s"` puts a type's own text in a field of eight exactly as
    `f"${5}%8d"` does, and an implementation rendering parts pads its complete text with the same
    call. A specifier names the field the whole value occupies, so the parts are handed the neutral
    one; forwarding it down is right only where the part *is* the whole rendering (`14 §2`).

    **An unbounded parameter stays perfectly legal** — this is not heading for a language where
    every `[T]` needs a bound. An unbounded `T` supports what every type supports: being passed,
    stored, returned, copied, released. `id[T](x: T) -> T`, `Pair[A, B]`, `pick[T](c, a, b)` need no
    bound now and never will. What needs one is a body that uses a *capability*: a method call, an
    operator, a rendering. That last one is the change a body written before `Display` may feel:
    `countdown[T](n: int, x: T)` that prints its parameter now says `countdown[T: Display]`, which
    is the cost of a use being written where the parameter is declared rather than discovered at
    whichever call site happened to supply a printable type.

These are the smallest lowering that runs a real program, chosen so the pieces above them (strings,
methods, escape analysis) can be added without reworking the pipeline shape.
