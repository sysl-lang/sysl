---
title: bytecode
summary: The module system, and the set's one end-to-end assertion — source in, bytecode out, run it, compare what it printed.
weight: 30
---

A compiler for a small language and a machine that runs what it emits, over one shared instruction
set.

**The axis: the module system**, and this is the only program in the set with an **end-to-end
assertion**. Source text goes in, the compiler emits bytecode, the machine runs it, and what it
printed is compared against what the program should print. No golden file stands in the middle, and
the one place an intermediate form is inspected is the section that is *about* the intermediate form.

Three modules, and their shape is the point. `isa` is what the other two agree on — the opcodes, the
buffer a program is emitted into, and how to read one back. `compiler` and `vm` each depend on it and
neither depends on the other, which is a DAG rather than a rule anybody had to remember. `compiler`
is two files because it grew to two, and nothing about either says so: they name each other's
declarations with no ordering and no forward declaration, which is what [a module being a
directory](/reference/modules/) buys.

It is also the program that carries a [capability clause](/reference/modules/) — `no alloc` on the VM
half — which is the declaration `guide/slab` and `guide/kernel` both wanted and neither could use,
because a capability is a property of the whole directory.

## What it found

**A simple enum narrower than `int` could not be matched at all.** `enum Op: u8` is the natural way
to write an instruction set — the opcode *is* a byte — and every `match` on one emitted a comparison
at the wrong width, which the assembler refused. A **compiler bug** rather than a language one, found
by the first program that wanted a narrow enum for the reason narrow enums exist, fixed at the source,
and now covered by tests at `i8`, `u8` and `u16`.

**`Result[unit, E]` could not be written**, and a compiler front end is *all* valueless fallible
steps. Every parse step here emits code and yields nothing, so every one used to say
`Result[bool, Fault]` with a `bool` that meant nothing — a placeholder payload keeping `?` working.

Answered by making `unit` a **zero-sized type**: it has a layout, the empty one, so a field or a
parameter of it is skipped rather than refused. Every one of those signatures now says what it means,
and `Ok(())` costs a `getelementptr` fewer than `Ok(true)` did. The finding earned its own entry
because two unrelated programs paid for it — see [png](/guides/png/), which has nothing in common
with a parser.

**An assignment is an expression, so a two-branch `if` whose branches each end in one was checked as
though those were its result.** The `unit`-branch rule saved the case where one side was a statement
and saved nothing where both sides assigned: `full = true` against `len += 1` is a `bool` against a
`usize`. That sharpened what [hashmap](/guides/hashmap/) had found about `match` — the trouble was
never that `match` lacked a rule `if` had, it was that assignment is an expression and *any*
two-armed form inherited it. Answered by taking the **position** instead: a block whose own value is
unused has none.

**A line could not be continued.** There was no trailing-operator continuation, so a condition that
outgrew its line had to become a sequence of early returns. Answered — and the rule that landed
carries [four exclusions](/reference/lexical/), because an operator at the end of a line is not
always a promise that the expression goes on.

---

[Source](https://github.com/edadma/sysl/tree/dev/guide/bytecode) ·
Next: [png](/guides/png/) — somebody else's format, byte by byte.
