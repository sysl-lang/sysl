# Front end, analyzer, and codegen (bring-up)

**Status:** bring-up slice, will be revised. This documents the first end-to-end path —
source to a running native binary — so the shortcuts taken to get there are explicit and can
be unwound deliberately rather than discovered later.

## The pipeline

`Compiler.compileToLlvm` = **parse → analyze → codegen**:

- **Parser** (`SyslParser`) — a packrat grammar over the lexer's token list, producing the
  untyped `ast.scala` tree.
- **Analyzer** (`Analyzer`) — the semantic pass. It hoists declarations, resolves names and
  types, checks every rule that can fail, and emits the *typed* tree (`tast.scala`). Every
  diagnostic lives here; codegen trusts the tree it is handed.
- **Codegen** (`Codegen`) — a straight lowering of the typed tree to textual LLVM IR. It
  selects instructions from the types the tree carries and lays out basic blocks; it makes no
  semantic decision of its own.

The CLI (`sysl run` / `sysl build` / `sysl emit-llvm`) links the emitted IR with `clang`.

## What runs today

A program is a sequence of statements and declarations. Non-declaration statements become the
body of `main`; function and struct declarations are hoisted (so they may be used before they
appear and may be mutually recursive).

- **Statements:** `var name [: type] = expr`, expression statements (including assignment and
  compound assignment), `while`, `for name in a..b` / `a..<b`, and `return [expr]`. Loop and
  branch bodies follow Scala-3 style: `then`/`do` is **required for a one-line body** and
  **optional before an indented block**. Optional `end if` / `end while` / `end for` markers
  may close a block (`end` is a *soft* keyword, not reserved).
- **`if` and `match` are expressions.** They yield the value of the taken branch/arm, so
  `var label = if c then a else b` and `f() -> T = match x …` both work; in statement position
  the value is simply unused and no `else`/catch-all is required. A `match` *used as a value*
  must be exhaustive (have an `else`). Patterns are literals, comma-alternatives, literal
  ranges (`1..10`, `0..<10`), and the `_` wildcard, with optional `if` guards.
- **Functions** are keyword-less, Scala-style: `name(params) -> ret = expr` or an indented
  block whose trailing expression is the implicit return value. A missing `-> ret` means
  `unit`. A block-bodied function may also `return` early.
- **Value structs:** `struct Name` with indented `field: type` lines; positional construction
  `Name(a, b)`, field read `p.x`, and in-place field assignment `p.x = v`. Structs pass to and
  from functions by value.
- **Expressions:** the full settled precedence grammar (`01`), over `int` (i32), `real` (f64),
  `bool`, and string literals. `++`/`--`, unary `-`/`!`/`~`, chained comparison.
- **`print(a, b, …)`** — a builtin, not a user function. Arguments are printed space-separated
  followed by a newline, lowered to a single `printf`. `int`→`%d`, `real`→`%g`, string→`%s`,
  `bool`→`%s` over `"true"`/`"false"`.

## IR dialect (locked against the dev toolchain)

Textual LLVM IR with **opaque pointers** (`ptr`, never `i32*`), verified against Apple clang
on arm64. Floats are emitted as **hex doubles** (`0x…`) so the textual round-trip loses no
bits. `printf` is declared varargs; each `print` interns one format-string constant. Value
structs lower to named aggregates (`%struct.Name = type { … }`); construction is an
`insertvalue` chain, a field read is `extractvalue`, and a field assignment is a
`getelementptr` + `store` into the variable's slot.

## Deliberate shortcuts (unwind these as the language grows)

1. **`int` is hard-lowered to i32 and `real` to f64.** Literal suffixes are parsed but not yet
   honoured; the arbitrary-width `iN`/`uN` family, the floats other than `f64`, and
   `usize`/`isize` are not lowered. `char` and byte-level types are not yet present.
2. **All locals are `alloca`.** Every `var`, parameter, and loop variable gets a stack slot;
   reads `load`, writes `store`. Lexical scopes are real (a shadowing name is renamed to a
   unique register within its function), but there is no SSA/`phi` construction — `if`/`match`
   values route through a stack slot.
3. **Functions are keyword-less with mandatory `(params)`.** Parameterless functions
   (`name -> T`), inner `def`, default arguments, generics, and the pure/effect (`def` vs
   plain) distinction are all deferred. The keyword-less form is disambiguated from a call by
   the typed parameter list and a following body.
4. **Value structs only.** No `new`/heap allocation, no refs (`&T`) or the ref-counted path,
   no nested-lvalue field assignment (`a.b.c = v`), no methods, no generics. Field assignment
   targets a local variable's field directly.
5. **`match` is scalar-only.** Literal / range / wildcard patterns with guards; no binding
   patterns, no enum/struct destructuring (those arrive with enums). A value match must be
   exhaustive; guard expressions are evaluated as part of the arm test.
6. **`print` is a printf shim** — the stand-in for the eventual `std` I/O surface, not a
   committed language builtin.
7. **Chained comparisons `and` their pairs eagerly.** `a < b < c` lowers to `(a<b) and (b<c)`,
   evaluating the shared middle operand once per pair and not short-circuiting, which `01` says
   it should. Bind operands to a temp and short-circuit once that matters.
8. **`for` iterates an integer range only.** Array/slice iteration, `downTo`, `step`, and
   `reverse` are not yet lowered.

None of these are load-bearing design decisions — they are the smallest lowering that runs a
real program, chosen so the pieces above them (wider types, enums, methods) can be added
without reworking the pipeline shape.
