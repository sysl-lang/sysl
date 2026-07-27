# Front end, analyzer, and codegen (bring-up)

**Status:** bring-up slice, will be revised. This documents the first end-to-end path —
source to a running native binary — so the shortcuts taken to get there are explicit and can
be unwound deliberately rather than discovered later.

## The pipeline

`Compiler.compileToLlvm` = **parse → analyze → escape-check → codegen**:

- **Parser** (`SyslParser`) — a packrat grammar over the lexer's token list, producing the
  untyped `ast.scala` tree.
- **Analyzer** (`Analyzer`) — the semantic pass. It hoists declarations, resolves names and
  types, checks every rule that can fail, monomorphizes generics, and emits the *typed* tree
  (`tast.scala`). Every diagnostic lives here; codegen trusts the tree it is handed.
- **Escape analysis** (`Escape`) — the one check that needs the whole call graph rather than one
  expression at a time, so it runs over the typed tree once the analyzer is finished (`05`).
- **Codegen** (`Codegen`, with `Emitter` / `ArcEmitter` / `ScalarEmitter` / `StringEmitter`) — a
  straight lowering of the typed tree to textual LLVM IR. It selects instructions from the types
  the tree carries and lays out basic blocks; it makes no semantic decision of its own.

The CLI (`sysl run` / `sysl build` / `sysl emit-llvm`) links the emitted IR with `clang`.

A short **prelude** (`Prelude`) of ordinary sysl source — the `Option` and `Result` enums — is
parsed once and hoisted ahead of the user's own declarations.

## What runs today

A program is a sequence of statements and declarations. Non-declaration statements become the
body of `main`; function, struct, and enum declarations are hoisted (so they may be used
before they appear and may be mutually recursive).

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
  `geom.Point`, `geom.Shape.Round(7)`. `import` does not exist yet and is only a shortening of
  this. An unqualified name is looked for in the module it is written in and then in the prelude,
  so two modules may each declare a `Point`, a `size`, or a variant `Round`. Every table is keyed
  by the **qualified** name (`Modules`), with `$` between the module and the declaration so the
  prefix can never be confused with a member (`Point.dist`) or an instantiation (`f.int`); `$` is
  legal in an LLVM symbol, so the key is still the emitted name and diagnostics read it back with
  dots. One file of the program carries the statements it runs (`13 §7`), and they are read in the
  module of the file that wrote them.

- **Statements:** `var name [: type] = expr`, expression statements (including assignment and
  compound assignment), `return [expr]`, and `break [expr]` / `continue`. Loop and branch bodies
  follow Scala-3 style: `then`/`do` is **required for a one-line body** and **optional before an
  indented block**. Optional `end if` / `end while` / `end for` markers may close a block (`end`
  is a *soft* keyword, not reserved).
- **`if`, `match`, and loops are expressions.** They yield the value of the taken branch/arm, so
  `var label = if c then a else b` and `f() -> T = match x …` both work; in statement position
  the value is simply unused.
- **Loops** — `while cond` and `for name in a..b` / `a..<b` (over a range) or `for name in seq`
  (over an array or slice) — are expressions too. A `break expr` leaves the nearest loop and
  makes `expr` its value; `continue` skips to the next iteration. An optional `else` block (after
  the body, Python-style) runs on *normal* completion — the condition turned false, or the range
  ran out, with no `break` — and its trailing expression is the loop's value on that path. With
  no `else`, normal completion yields `unit`, so a value-carrying `break` needs an `else` to give
  a matching value when the loop finishes on its own; every `break` value and the `else` value
  must share one type, which becomes the loop's. A `break`/`continue` unwinds the body's
  ownership regions on the way out, the same discipline as `return` bounded to the loop. Scalar patterns are literals, `|`-alternatives (Scala-style —
  `1 | 2 | 3`), literal ranges (`1..10`, `0..<10`), and the `_` wildcard, with optional `if`
  guards; a bare name binds the value. A scalar `match` used as a value must be exhaustive
  (have a catch-all); an enum `match` must always cover every variant or carry a catch-all.
- **Functions** are keyword-less, Scala-style: `name(params) -> ret = expr` or an indented
  block whose trailing expression is the implicit return value. A missing `-> ret` means
  `unit`. A block-bodied function may also `return` early.
- **The two valueless types are result types only** (`00 §11`, `00 §12`). `never` and `unit` both
  lower to `void`, which is not a layout, so neither may be a parameter, a field, an element, or a
  type argument — and inference will not put one in a generic slot either, so a unit or diverging
  argument to `f[T](x: T)` is refused at the call rather than instantiated into a `void` parameter.
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
  an ordinary function. The prelude uses one: `Writer.failed` defaults to `false`.
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
  type, so no overlapping implementations and no specialization rule to need. The bounds decide
  **which instantiations conform**, asked one step in so the answer composes, and they make the
  members checkable at their **definition**: a body using what no bound licenses is reported on its
  own line with nothing instantiated.
- **An `impl` for a composed shape (`02`).** `impl[T: Display] Show for []T` is the same block
  written for a type with no name to be generic over, and everything above holds unchanged. What is
  its own: a composed type is filed under the whole of itself (`[]int`), so a shape gets a key by
  dropping the arguments (`[]`, `[3]`) that a member lookup falls back to, and a symbol the same way
  (`slice.show` instantiated at `int` is `slice.show.int`, which the written `[]int`'s
  `slice.int.show` cannot be mistaken for). An array's length is part of the shape, since no
  parameter can stand for it (`10 § Open d`). A `string` is not a slice and is not covered. A shape
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
  to them. A type implements a trait **once**: the arguments are what an implementation supplies,
  not part of what it is filed under, because two of them would give the type two members of each
  name with no rule for choosing between them.
- **Rendering, through `Display` and its `Writer` sink (`14 §6`).** A value that is not a scalar
  writes itself into a sink rather than returning a string, so rendering allocates nothing and a
  `no alloc` module can still log. The sink is a `*Writer` trait object; `print` supplies one over
  standard output and `str` supplies a growable buffer whose bytes become the string, and both are
  the compiler's, because a stateless writer has no struct to be and a growable buffer is not yet
  something sysl can express. A **scalar** keeps its direct path in `print` and its own `str`, so a
  program that prints only numbers builds no sink at all. A `Writer` **borrows** the bytes it is
  written — checked by escape analysis, which is what lets a renderer pass a slice of its own stack
  buffer through a trait object. A hole's format specifier travels to the `Display` it calls and is
  **acted on** there: every prelude renderer pads through one `display_pad`, and an implementation
  applies the same call to its own complete text.
- **`Option[T]` / `Result[T, E]` and `?`.** Both come from the prelude as ordinary generic
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
  retain and release are run-time no-ops. Iterating is written `for b in s.bytes`, since a
  string has two granularities and choosing one silently would be a guess.
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
- **`print(a, b, …)`** — a **desugaring onto prelude functions**, not a builtin and not a user
  function. Each argument becomes a call to the renderer its static type reaches — `printi`,
  `printu`, `printr`, `printb`, `printc`, `prints` — widened to the width that renderer takes, with
  `printc(' ')` between and `printc('\n')` at the end. Every one of those is sysl in the prelude
  (`04`, *Printing*); the compiler knows the six names and the widening rule and emits no printing
  code of its own. They all write through one sink, `putbytes`, which walks a byte count rather
  than stopping at a terminator, because a sysl string may hold an interior NUL and every `%s`
  conversion — `%.*s` included, whose precision is a maximum rather than a count — would stop
  there.

## IR dialect (locked against the dev toolchain)

Textual LLVM IR with **opaque pointers** (`ptr`, never `i32*`), verified against Apple clang
on arm64. Floats are emitted as **hex doubles** (`0x…`) so the textual round-trip loses no
bits. An `extern` is declared under its **symbol** — its link name where it has one, its sysl name
otherwise — and each symbol is declared once however many declarations name it. Value
structs lower to named aggregates (`%struct.Name = type { … }`); construction is an
`insertvalue` chain, and a field read is `extractvalue`. A **write** instead computes the
place's address — a local's own slot, a loaded pointer value, or a `getelementptr` chain over
either — and `store`s through it, which is one mechanism for `x = v`, `s.f = v`, `*p = v`, and
`p.f = v` alike. `*T` and `&T` are both the opaque `ptr`; inside a mangled name a memory mode
is spelled as a word (`ptr.` / `ref.` / `sync.`), since a sigil is not an LLVM name character.
A `&T` addresses a **box** `%arc.T = type { i64, ptr, T }` — the count, the function that
destroys it, then the payload — so reading through one is a `getelementptr` past the header.
Both taking a share and giving one back are **type-independent** (`@arc.retain` /
`@arc.release`): reaching zero calls through the hook, which is the per-payload-type function
that releases whatever the payload held and then returns the storage. That indirection is what
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
lowers to a value aggregate `%enum.Name = type { i32 tag, payload₁, … }` with one payload slot
per data-carrying variant (each payload a named `%Name.Variant` aggregate). A pattern test is a
tag `icmp` plus `extractvalue` reads of the payload fields (pure, so nested fields are read
unconditionally and a failed outer tag simply ANDs a `false` through); bindings are stored into
fresh slots only once the arm — and its guard — is taken. An instantiated generic name is
flattened into its LLVM name — `Result[int, string]` becomes `%enum.Result.int.string` and
`id[T]` at `int` becomes `@id.int` — which stays unambiguous because every name has a fixed
arity.

## Deliberate shortcuts (unwind these as the language grows)

1. **The scalar table stops short of its widest members.** An integer wider than 64 bits and
   `f128` are diagnosed rather than lowered: printing them portably needs a runtime this
   stage does not have (`long double` is 64-bit on the arm64 Apple ABI, so `fp128` cannot go
   through `printf`). `usize` / `isize` are fixed at 64 bits by a constant rather than by a
   target description. A narrower float constant is emitted as the `double` constant rounded
   down to it, which is correctly rounded except in the rare double-rounding case.
2. **Every string is a literal or part of one.** The representation is the specified three
   words and the operations over it are real, but nothing yet *makes* bytes: `from_utf8`,
   `copy()`, concatenation, `str.builder`, `cstring`, and `string(c)` all need either an
   allocator surface or methods, and none of them exists. So every string a program can hold
   traces back to a literal, every owner word is null, and the validation `04` requires at
   construction is done by the lexer rather than at run time. `s.chars` waits on the iterator
   protocol; ordering is by byte with no collation, which is what `04` specifies.
3. **All locals are `alloca`.** Every `var`, parameter, and loop variable gets a stack slot;
   reads `load`, writes `store`. Slots are hoisted into the entry block (names are unique per
   function, so one inside a loop does not grow the stack per iteration), but there is no
   SSA/`phi` construction — `if`/`match` values route through a stack slot.
4. **Functions are keyword-less with mandatory `(params)`.** Parameterless functions
   (`name -> T`), inner `def`, default arguments, and the pure/effect (`def` vs plain)
   distinction are all deferred. The keyword-less form is disambiguated from a call by the
   typed parameter list and a following body.
5. **Reference counting is emitted where it is obviously needed, and nowhere elided.** Every
   named slot takes a count and gives it back; every temporary is released when its statement
   or branch ends; every function retains its parameters and returns a count already taken.
   That is correct but not minimal — a retain/release pair that provably cancels is still
   emitted, and a reference borrowed for the length of a call is still counted twice. Eliding
   them is a later pass over the same placement, not a change to it. `weak` does not exist yet,
   so a reference cycle is a leak; there are no methods; the deallocation hook is always the
   built-in one, since there is no way yet to write an allocator; and a data enum still
   reserves storage for *every* variant's payload at once (a value aggregate, no size
   arithmetic) rather than sizing the box to the variant it holds.
6. **Enum-match exhaustiveness ignores nested coverage.** An unguarded arm covers its variant
   only when every sub-pattern is irrefutable (a binding or `_`); an arm with a nested variant
   or literal sub-pattern does not count, so `Wrap(A) | Wrap(B)` covering `Wrap(Inner)` still
   needs an `else`. Guard expressions are evaluated after the pattern matches and its bindings
   are in scope.
7. **`print`'s renderers are the prelude's, not a `std` I/O surface**, and the desugaring picks a
   *scalar's* by static type rather than through its `Display` — deliberately, so a program that
   prints only numbers builds no sink (`14 §8 b`). A related over-approximation shows up in the
   output: making a slice turns the ARC runtime on even where the owner is statically null, so a
   program that only prints an integer still carries the allocator declarations it never calls, and
   `FormatSpec`'s layout is emitted whether or not anything renders, since a non-generic type is
   instantiated eagerly wherever it is declared.
8. **`for` iterates a range, an array, or a slice.** `downTo`, `step`, and `reverse` are not
   yet lowered, and nothing else is iterable — there is no iterator protocol, which is also why
   a string is iterated as `s.bytes` and has no `s.chars` yet.
9. **Escape analysis rejects rather than promotes.** An array whose view escapes is
   diagnosed where `05` says an allocator should silently promote it to the heap, so a program
   that means to return a view writes `&[N]T` itself. That is `05`'s `no alloc` behaviour
   applied everywhere — the safe direction to be wrong in — and `--explain-escapes` arrives
   with promotion. Two more approximations `05` allows: a call's result is treated as viewing
   *every* slice argument rather than the ones it really views, and a value's provenance is
   tracked per local rather than per field, so a struct that holds one confined view is
   confined entirely. There is also no growable array: no `append`, no capacity, no `[]T` that
   owns rather than views. A bounds failure traps with no message, exactly as `char(u)` does.
10. **Generics are monomorphized with local inference only.** Type arguments come from the
    argument types and the expected type of the expression; there is no unification across a
    whole function body and no explicit type application at a call site. A parameter nothing
    determines is an error rather than a default. `?` is wired to the prelude's `Option` and
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

    A `FormatSpec` is now **acted on** as well as delivered. Every prelude renderer ends at one
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
