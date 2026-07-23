# Codegen and the `print` builtin (bring-up)

**Status:** bring-up slice, will be revised. This documents the first end-to-end path —
source to a running native binary — so the shortcuts taken to get there are explicit and can
be unwound deliberately rather than discovered later.

## What runs today

A program is a sequence of statements that becomes the body of `main`. Supported:

- **Statements:** `var name [: type] = expr`, expression statements (including assignment and
  compound assignment), `if`/`elif`/`else`, `while`. Bodies follow Scala-3 style: `if cond
  then …` and `while cond do …`, where the introducer keyword (`then`/`do`) is **required for
  a one-line body** and **optional before an indented block** (a following `Newline`+`Indent`
  already marks the block). `else` likewise takes an inline statement or an indented block.
  `elif cond then …` is parsed as sugar for `else if cond then …`, nesting into the else
  branch — no distinct AST node.
- **Expressions:** the full settled precedence grammar (`01`), over `int` (i32), `real` (f64),
  `bool`, and string literals. `++`/`--`, unary `-`/`!`/`~`, chained comparison.
- **`print(a, b, …)`** — a builtin, not a user function. Arguments are printed
  space-separated followed by a newline, lowered to a single `printf`. `int`→`%d`,
  `real`→`%g`, string→`%s`, `bool`→`%s` over `"true"`/`"false"`. The space separator is a
  friendly default, not a fixed language rule.

The pipeline is `Compiler.compileToLlvm` = parse → `Codegen`. The CLI (`sysl run` /
`sysl build` / `sysl emit-llvm`) links the emitted IR with `clang`.

## IR dialect (locked against the dev toolchain)

Textual LLVM IR with **opaque pointers** (`ptr`, never `i32*`), verified against Apple clang
21 on arm64. Floats are emitted as **hex doubles** (`0x…`) so the textual round-trip loses no
bits. `printf` is declared varargs; each `print` interns one format-string constant.

## Deliberate shortcuts (unwind these as the language grows)

1. **No separate analyzer.** `Codegen` carries a small `Ty` environment and types expressions
   as it emits. `testing.md` calls for a distinct analyzer (typed AST) as its own Tier-1 pass;
   split it out before the type system grows past the four scalar categories here.
2. **`int` is hard-lowered to i32 and `real` to f64.** Literal suffixes are parsed but not yet
   honoured by codegen; the arbitrary-width family and `usize`/`isize` are not lowered.
3. **Flat scope, all `alloca`.** Every `var` is an `alloca`; reads `load`, writes `store`. No
   block scoping — a name declared inside an `if`/`while` stays visible after it, and
   re-declaring a name is an error. Proper lexical scopes come with the analyzer.
4. **No functions yet.** The whole program is `main`. `fn` declarations, parameters, and
   `return` are the next stage; `print` is wired in as a builtin until a real function/FFI
   story exists.
5. **`print` is a printf shim.** It is the stand-in for the eventual `std` I/O surface, not a
   committed language builtin.
6. **Chained comparisons `and` their pairs eagerly.** `a < b < c` lowers to
   `(a<b) and (b<c)` — correct for the ordinary case of side-effect-free operands, but it
   evaluates the shared middle operand once per pair and does not short-circuit, which `01`
   says it should. Once operands can be bound to a temp (analyzer/typed-AST), lower it to
   evaluate each operand once and short-circuit.

None of these are load-bearing design decisions — they are the smallest lowering that runs a
real program, chosen so the pieces above them (analyzer, functions, types) can be added
without reworking what is here.
