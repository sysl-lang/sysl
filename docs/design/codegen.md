# Front end, analyzer, and codegen (bring-up)

**Status:** bring-up slice, will be revised. This documents the first end-to-end path —
source to a running native binary — so the shortcuts taken to get there are explicit and can
be unwound deliberately rather than discovered later.

## The pipeline

`Compiler.compileToLlvm` = **parse → analyze → codegen**:

- **Parser** (`SyslParser`) — a packrat grammar over the lexer's token list, producing the
  untyped `ast.scala` tree.
- **Analyzer** (`Analyzer`) — the semantic pass. It hoists declarations, resolves names and
  types, checks every rule that can fail, monomorphizes generics, and emits the *typed* tree
  (`tast.scala`). Every diagnostic lives here; codegen trusts the tree it is handed.
- **Codegen** (`Codegen`) — a straight lowering of the typed tree to textual LLVM IR. It
  selects instructions from the types the tree carries and lays out basic blocks; it makes no
  semantic decision of its own.

The CLI (`sysl run` / `sysl build` / `sysl emit-llvm`) links the emitted IR with `clang`.

A short **prelude** (`Prelude`) of ordinary sysl source — the `Option` and `Result` enums — is
parsed once and hoisted ahead of the user's own declarations.

## What runs today

A program is a sequence of statements and declarations. Non-declaration statements become the
body of `main`; function, struct, and enum declarations are hoisted (so they may be used
before they appear and may be mutually recursive).

- **Statements:** `var name [: type] = expr`, expression statements (including assignment and
  compound assignment), `while`, `for name in a..b` / `a..<b`, and `return [expr]`. Loop and
  branch bodies follow Scala-3 style: `then`/`do` is **required for a one-line body** and
  **optional before an indented block**. Optional `end if` / `end while` / `end for` markers
  may close a block (`end` is a *soft* keyword, not reserved).
- **`if` and `match` are expressions.** They yield the value of the taken branch/arm, so
  `var label = if c then a else b` and `f() -> T = match x …` both work; in statement position
  the value is simply unused. Scalar patterns are literals, `|`-alternatives (Scala-style —
  `1 | 2 | 3`), literal ranges (`1..10`, `0..<10`), and the `_` wildcard, with optional `if`
  guards; a bare name binds the value. A scalar `match` used as a value must be exhaustive
  (have a catch-all); an enum `match` must always cover every variant or carry a catch-all.
- **Functions** are keyword-less, Scala-style: `name(params) -> ret = expr` or an indented
  block whose trailing expression is the implicit return value. A missing `-> ret` means
  `unit`. A block-bodied function may also `return` early.
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
  and lower to one LLVM cast each. The single partial one, `char(u)`, tests the value at 64
  bits and traps when it is not a Unicode scalar value.
- **Expressions:** the full settled precedence grammar (`01`) over the scalar types and
  string literals. `++`/`--`, unary `-`/`!`/`~`, chained comparison.
- **`print(a, b, …)`** — a builtin, not a user function. Arguments are printed space-separated
  followed by a newline, lowered to a single `printf`. Integers widen to what varargs promote
  to and print as `%d` / `%u` / `%lld` / `%llu`; floats widen to `double` and print as `%g`; a
  `char` is encoded to UTF-8 in a stack buffer and printed as `%s`; strings are `%s`; `bool`
  is `%s` over `"true"` / `"false"`.

## IR dialect (locked against the dev toolchain)

Textual LLVM IR with **opaque pointers** (`ptr`, never `i32*`), verified against Apple clang
on arm64. Floats are emitted as **hex doubles** (`0x…`) so the textual round-trip loses no
bits. `printf` is declared varargs; each `print` interns one format-string constant. Value
structs lower to named aggregates (`%struct.Name = type { … }`); construction is an
`insertvalue` chain, a field read is `extractvalue`, and a field assignment is a
`getelementptr` + `store` into the variable's slot. A simple enum is plain `i32`; a data enum
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
2. **`string` is a bare pointer, not the three-word owning view it is specified as.** A
   literal interns as NUL-terminated bytes and passes to `printf` as a `ptr`, so an embedded
   `\0` truncates it — the one place the implementation contradicts the design (`04`) rather
   than merely lagging it. There is no owner word, no `.len`, no slicing, no runtime string
   value, and no concatenation. The real representation waits on ARC, since it *is* an ARC
   reference with a view attached.
3. **All locals are `alloca`.** Every `var`, parameter, and loop variable gets a stack slot;
   reads `load`, writes `store`. Slots are hoisted into the entry block (names are unique per
   function, so one inside a loop does not grow the stack per iteration), but there is no
   SSA/`phi` construction — `if`/`match` values route through a stack slot.
4. **Functions are keyword-less with mandatory `(params)`.** Parameterless functions
   (`name -> T`), inner `def`, default arguments, and the pure/effect (`def` vs plain)
   distinction are all deferred. The keyword-less form is disambiguated from a call by the
   typed parameter list and a following body.
5. **Value structs and enums only.** No `new`/heap allocation, no refs (`&T`) or the
   ref-counted path, no recursive types, no nested-lvalue field assignment (`a.b.c = v`), and
   no methods. A data enum reserves storage for *every* variant's payload at once (a value
   aggregate, no size arithmetic), which is wasteful but needs no heap; a type that contains
   itself has no finite size and is rejected outright, which is what makes recursive enums
   (`Add(Expr, Expr)`) wait on references.
6. **Enum-match exhaustiveness ignores nested coverage.** An unguarded arm covers its variant
   only when every sub-pattern is irrefutable (a binding or `_`); an arm with a nested variant
   or literal sub-pattern does not count, so `Wrap(A) | Wrap(B)` covering `Wrap(Inner)` still
   needs an `else`. Guard expressions are evaluated after the pattern matches and its bindings
   are in scope.
7. **`print` is a printf shim** — the stand-in for the eventual `std` I/O surface, not a
   committed language builtin.
8. **Chained comparisons `and` their pairs eagerly.** `a < b < c` lowers to `(a<b) and (b<c)`,
   evaluating the shared middle operand once per pair and not short-circuiting, which `01` says
   it should. Bind operands to a temp and short-circuit once that matters.
9. **`for` iterates an integer range only.** Array/slice iteration, `downTo`, `step`, and
   `reverse` are not yet lowered.
10. **Generics are monomorphized with local inference only.** Type arguments come from the
    argument types and the expected type of the expression; there is no unification across a
    whole function body, no explicit type application at a call site, and no bounds or
    constraints on a type parameter. A parameter nothing determines is an error rather than a
    default. `?` is wired to the prelude's `Option` and `Result` **by name**, standing in for
    the eventual trait that will describe "can be short-circuited".

None of these are load-bearing design decisions — they are the smallest lowering that runs a
real program, chosen so the pieces above them (references, arrays, methods) can be added
without reworking the pipeline shape.
