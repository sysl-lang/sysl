# Inline Assembly

**Status:** designed here and built. The construct parses, selects an architecture, checks that
every architecture has an answer, resolves operands against the enclosing scope, and lowers to
LLVM's inline assembly with a constraint string the programmer never writes.

Assembly exists in sysl for the instructions a program cannot reach any other way: the privileged
ones, the ones that talk to a bus rather than to memory, and the handful that no library can wrap
because they change the machine the library is running on. Three targets in the registry
(`targets.md`) are freestanding, and `capabilities.md` offers allocator-free kernel and driver code
as a thing sysl is for; a kernel that cannot disable an interrupt is not one.

**What this is not is a portability layer.** sysl does not supply named functions for
"disable interrupts" or "flush the TLB" that expand to each machine's instruction. Assembly is the
primitive, and the architecture layer above it is ordinary sysl written by whoever needs it. What
the language contributes is that the layer can be *checked*: two architectures implementing the same
operation are held to the same signature, their operands are typed, and neither can quietly disagree
with the register allocator.

The through-line: **the programmer supplies instructions, and nothing else.** Which register an
operand lands in, how a value gets there, what the block destroys, how a label avoids colliding with
its own second expansion, how an operand is spelled in the emitted template — all of these are the
compiler's, because all of them are things it knows and the programmer would otherwise have to
restate correctly by hand.

---

## 1. One head, one arm per architecture

An `asm` statement is a head with **architecture arms** indented under it. Exactly one arm is
selected — the one naming the architecture being compiled for — and the others contribute nothing to
the output.

```
arch_cli()
    asm
        [x86_64]  "cli"
        [aarch64] "msr daifset, #2"
        [riscv64] "csrci mstatus, 8"
```

An arm names one architecture or several, from the same closed set `#if` draws on
(`targets.md § The symbols are derived from the target`): `aarch64`, `x86_64`, `riscv64`, `x86`. A
name outside that set is an error rather than an architecture nobody has heard of, for the reason
`#if` gives — a misspelling that read as "not this one" would remove code from the build with
nothing said.

**Why brackets rather than a `match` arm's `->`.** Square brackets are already what sysl writes
around things resolved at compile time: a type parameter list, `f[T: Bits]`, `Option[T]`. The arrow
is what it writes between a *runtime* pattern and its body, and `09 §` is strict enough about that
distinction to refuse `else ->` on the ground that `else` is not a pattern. An architecture is not a
value being tested; the arms not chosen do not exist in the output at all. Borrowing the arrow would
advertise a dispatch that never happens.

**The head takes nothing.** Everything that could sit on it — operands, clobbers — belongs to an
arm, because register names are per-architecture by construction and an operand's register class may
be too. A bare head also leaves the obvious room for a shared operand list later, if writing one per
arm ever proves to be repetition rather than precision.

## 2. Every architecture gets an answer

**The arms must cover every architecture, not merely the one being built.** A missing arm is an
error on *every* build, naming which architecture has no answer.

This is `targets.md`'s rule for `#if` one level up. There, every condition is checked in the
branches being skipped as well as the one being taken, so a misspelling in the Linux half is caught
by a macOS build. Here the same reasoning reaches past whether a branch *parses* to whether one
*exists*: a missing `riscv64` arm is reported while compiling for x86-64, rather than waiting for
whoever first builds for RISC-V and meets a function with no body.

Some assembly is unportable in principle rather than by omission — `outb` and `inb` are x86's, and
AArch64 and RISC-V have no equivalent because they reach devices through memory. So an architecture
with no answer says so, and says why:

```
port_out(port: u16, value: u8)
    asm
        [x86_64]
            "outb {value}, {port}"
            in port : "dx"
            in value : "al"
        [aarch64, riscv64] unavailable "port I/O is x86-only; devices are reached through memory"
```

Calling `port_out` on AArch64 is then an error **at the call**, carrying that sentence. This is the
placement `capabilities.md` argues for when a program reaches something its target does not offer:
the diagnostic belongs on the line the reader has, not on the declaration they have not opened. The
reason is required, because omitting the arm entirely is already an error and a reasonless
`unavailable` would be a worse way to say the same nothing.

**An arm with nothing after it is an answer too: this architecture needs no instruction.** A memory
barrier is free on a machine that never reordered the accesses in question, and `unavailable` would
be false there — the operation is available, it simply costs nothing.

```
asm
    [x86_64]
    [aarch64] "dmb ish"
    [riscv64] "fence rw, rw"
    [x86]     unavailable "no barrier is defined for 32-bit x86 here"
```

An empty arm cannot be confused with a forgotten one, because a forgotten arm is not empty — it is
absent, and absent is the error above. What is left is the ordinary risk of an empty body anywhere,
and it is self-limiting: an empty arm declares no operands, so a function that promised an output
meets the same unassigned-variable rule any other empty branch would.

**Coverage is checked against the architectures a target can be built for**, which is the three the
registry has processors for. `x86` is nameable — `#if` knows it — and `targets.md` records that its
one triple is not yet supported, so requiring an arm for it would be requiring an answer to a
question nobody can ask. When 32-bit arrives, the arms that do not cover it become errors, and that
list is precisely the work of supporting it.

## 3. Operands are values, not registers

An operand names a variable already in scope, gives its direction, and gives the register class or
the machine register it must occupy.

```
in  <name> : reg | "<machine register>"
out <name> : reg | "<machine register>"
```

The template refers to an operand by that same name in braces, so `{port}` is the parameter called
`port`. There is no second namespace and nothing to keep in step.

**What this replaces is the reason the feature exists.** Written as a bare string, the same
operation has to move values into the registers the instruction wants, which means knowing where the
calling convention left them, and the knowledge lives in a comment that nothing checks. Written this
way the allocator is told what the instruction needs and satisfies it; the programmer states a
requirement instead of implementing one.

**A named register is quoted and a class is not, and that is the rule everywhere in the
construct: a bare word is sysl's, a quoted word is the assembler's.** `dx` and `al` are names the
assembler knows and sysl does not; `reg` is a sysl word meaning *any register the allocator likes*.
Instruction text is quoted for the same reason it is quoted anywhere — sysl does not read it.

The class slot is required even though `reg` is currently the only class. Writing it keeps every
operand line the same shape, which is what lets a second class arrive as a peer rather than as the
exception to an invisible default.

**The colon here is not a type annotation.** Everywhere else in sysl `x: T` introduces a type, and a
reader will see this one that way once. An operand names a variable that already has a type, so
there is nothing left to declare: the slot holds a class or a register, and the operand's type is
whatever the variable's is.

**An operand must be a plain variable.** An expression would need somewhere to be evaluated to, and
that somewhere is a variable — so writing one first says the same thing without the construct
growing a place to put temporaries.

**A name bound by `ref` is not one of them.** A ref names storage somewhere else rather than a
variable of its own (`03 § ref`), so there is no slot for an input to be loaded from or an output
stored to. Copying it into a `var` first, and writing that back afterwards, is what the operand
would have had to mean anyway — and saying so is better than emitting an operand against an address
that was never allocated, which is a module the assembler rejects for reasons the source does not
explain.

**An operand's type must fit a general-purpose register**, which is the integers, the pointers, and
`bool`. A float operand needs a floating class, which does not exist yet; the diagnostic says so in
those terms, because it is a thing to be added rather than a thing to be refused. It is also a
choice that has to be made per target rather than once: `targets.md` records that bare-metal RISC-V
is the one registry entry with no floating registers at all.

**Reading and writing the same variable is an error, not an `inout`.** `in x` and `out x` become two
operands, and two operands may be allocated two registers — so the spelling that looks like it says
"read it and write it back" would compile to reading one register and writing another. The intended
form is a single `inout` operand, which is not built; until it is, the wrong spelling is refused
rather than accepted with the wrong meaning.

## 4. What the block disturbs

A block may say what it destroys beyond its operands:

```
clobbers "rax", "rdx"
```

Registers are named, quoted, because they are the assembler's names.

**Memory and the condition flags are assumed clobbered, always.** They are not listed and cannot
currently be given back. This is the conservative direction and it is deliberate: the cost of
assuming is optimization quality across a handful of instructions, and the cost of *not* assuming is
a value kept in a register the block overwrote, which is a wrong answer with nothing to point at.
Handing them back is an optimization, and one that can be added without invalidating anything
written before it — which is the reason it is not here yet.

Registers cannot be given the same treatment. "Everything is clobbered" is a legal thing to assume
and a useless one, so the registers a block destroys are the one part of its effect the programmer
has to state.

## 5. What the compiler owns

Four things that a bare string leaves to the programmer, all of which the compiler is in a better
position to get right:

**The constraint string.** LLVM is told what each operand is and where it may live — an output, an
input, a fixed register, a clobber — built from the operand list. It is never written by hand,
because a constraint that disagrees with the instruction text is not detectable by reading either
one.

**Operand substitution and its escaping.** The template names operands in braces and the compiler
translates them to the positional markers LLVM wants. Because `{}` are sysl's marker, `$` is left
alone, and an immediate is written the way the assembler spells it — `movq $1, %rsi`, not doubled.

**Label uniqueness.** A label in an arm is local to that arm's expansion, and a block that appears
twice gets two distinct labels. A global label in inline assembly is a duplicate-symbol error the
second time the function is emitted, and it is not the programmer's mistake in any useful sense.

**Line joining.** An arm's instructions are separate strings on separate lines, each able to carry a
comment, and the compiler joins them. This is the difference between an assembly routine that can be
read and a six-instruction spinlock written on one line with `\n` between the instructions.

## 6. The words this construct spends

**None of them is reserved.** `asm`, `unavailable`, `out`, `reg` and `clobbers` are contextual: each
is recognized in exactly one position, and is an ordinary identifier everywhere else, including
inside an assembly block in any other position. `in` is a reserved word already, for `for x in xs`,
and is reused here rather than added.

| word | recognized only |
|---|---|
| `asm` | at the head of a statement, followed by an indented arm list |
| `unavailable` | as the first thing after an arm's architecture list |
| `in` / `out` | as the first word of an operand line |
| `reg` | after an operand's `:`, where a machine register would otherwise be quoted |
| `clobbers` | as the first word of a clobber line |

No two of them compete for a position, and no position also admits an expression — which is what
makes the whole set free. A program may declare `var out = 3` and use it as an assembly operand in
the same function.

## 7. Where assembly may not go

**Not in a `require` or `ensure` condition** — and there is no check that says so, because there is
no way to write it. A contract's condition is an *expression* and assembly is a *statement*, so the
grammar has already answered. This is worth stating rather than leaving to be discovered: a contract
is a claim the compiler reasons about and an assembly block is precisely what it cannot reason
about, so the two never meeting is a property to rely on rather than a coincidence.

**Nothing about a block's contents is understood, including whether control comes back.** The
compiler does not read the instructions, so it cannot know that `jmp` to a reset vector never
returns, and it cannot discover that a `-> never` function's assembly body keeps that promise. So it
does not try: an assembly arm is opaque, and a function declared `-> never` with an assembly body is
taken at its word, exactly as anything else declared not to return is.

```
arch_reset() -> never
    asm
        [x86_64]
            "cli"
            "1: hlt"
            "jmp 1b"
        ...
```

The alternative reading — that `00 §11`'s rule about a `-> never` body that *could* return applies
to a body the compiler cannot read — would reject every diverging assembly routine, which is
backwards. The promise is the programmer's to keep here, which is true of the instructions
themselves anyway.

## Open

- **`inout`, and a second output.** Several outputs already work; a read-modify-write operand does
  not, and its spelling is refused rather than misread (§3). The instructions wanting one are the
  exchange and compare-exchange family, which `sysl.sync` already covers, so nothing in the library
  needs it today.
- **Giving memory and the flags back.** `no memory` and `no flags` are available spellings — `no` is
  reserved already — and both are optimizations over an answer that is currently always correct.
- **A floating register class.** Needed the first time an operand is an `f32` or an `f64`, and it
  cannot be a single class: bare-metal RISC-V has no floating registers to name.
- **A shared operand list on the head.** Rejected for now as precision rather than repetition, since
  a class may legitimately differ per architecture (§1). The case to watch is the routine whose arms
  differ only in one instruction.
