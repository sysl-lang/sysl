---
title: Inline assembly
summary: Machine instructions, in an arm per architecture — where operands are values and the compiler owns the constraint string, the escaping, and the labels.
weight: 125
---

`asm` reaches the instructions no library can wrap: the privileged ones, the ones that talk to a bus
rather than to memory, and the handful that change the machine the library is running on. It is the
one construct that steps outside the language, and it is shaped so that stepping outside costs as
little as possible — you supply instructions, and nothing else.

**What you do not supply is the interesting part.** Which register an operand lands in, how the
value gets there, what the block destroys, how a label avoids colliding with its own second
expansion, how an operand is spelled in the emitted template: each is something the compiler knows,
and each is something that, written by hand, is a comment nothing checks.

sysl does **not** ship named functions for "disable interrupts" or "flush the TLB" that expand to
each machine's instruction. Assembly is the primitive; the architecture layer above it is ordinary
sysl that you write. What the language contributes is that the layer can be *checked*.

## One arm per architecture

An `asm` statement is a head with architecture arms indented under it. Exactly one is selected — the
one naming the processor being compiled for — and the others contribute nothing:

```sysl
arch_cli()
    asm
        [x86_64]  "cli"
        [aarch64] "msr daifset, #2"
        [riscv64] "csrci mstatus, 8"
```

An arm names one processor or several, spelled as [`#if`](/reference/attributes/) spells them:
`aarch64`, `x86_64`, `riscv64`, `x86`. A name outside that set is an error rather than a machine
nobody has heard of.

Here is a whole program. `yield`, `pause` and `nop` are each their machine's hint that a spin loop is
spinning, which is about as small as a real use of this construct gets:

```sysl
spin_hint()
    asm
        [x86_64]  "pause"
        [aarch64] "yield"
        [riscv64] "nop"

spin_hint()
print("hinted")
```

```output
hinted
```

**Square brackets rather than a `match` arm's `->`.** Brackets are already what sysl writes around
things resolved at compile time — a type parameter list, `Option[T]` — and the arrow is what it
writes between a runtime pattern and its body. An architecture is not a value being tested: the arms
not chosen do not exist in the output at all.

## Every architecture needs an answer

The arms must cover every processor a target can be built for, not merely the one you are building
for now. A missing arm is an error on **every** build:

```sysl
halt()
    asm
        [x86_64] "hlt"

halt()
```

```error
this assembly has no arm for 'aarch64' or 'riscv64'
```

This is the rule `#if` follows one level up, where every condition is checked in the branches being
skipped as well as the one being taken. Here it reaches past whether a branch *parses* to whether one
*exists* — so a forgotten processor is found by whoever forgot it, rather than by whoever first
builds for the machine that was left out.

### When there is genuinely no answer

Some assembly is unportable in principle rather than by omission. `outb` and `inb` are x86's;
AArch64 and RISC-V reach devices through memory and have no equivalent at all. Such an architecture
says so, and says why:

```sysl
port_out(port: u16, value: u8)
    asm
        [x86_64]
            "outb {value}, {port}"
            in port : "dx"
            in value : "al"
        [aarch64, riscv64] unavailable "port I/O is x86-only; devices are reached through memory"
```

The x86-64 build compiles that. A build for a processor the arm covers is refused, and the reason
travels into the diagnostic — which is the whole of what this form buys over leaving the arm out,
since leaving it out fails *every* build instead:

```sysl
port_out()
    asm
        [x86_64] "outb %al, %dx"
        [aarch64, riscv64] unavailable "port I/O is x86-only; devices are reached through memory"

port_out()
```

```error
port I/O is x86-only; devices are reached through memory
```

### When the answer is no instruction

An arm written with nothing under it is an answer too: this processor needs no instruction. A memory
barrier is free on a machine that never reordered the accesses in question, and `unavailable` would
be false there — the operation is available, it simply costs nothing.

```sysl
barrier()
    asm
        [x86_64]
        [aarch64] "dmb ish"
        [riscv64] "fence rw, rw"
```

An empty arm cannot be confused with a forgotten one, because a forgotten arm is not empty — it is
absent, and absent is the error above.

## Operands are values, not registers

An operand names a variable already in scope, gives its direction, and gives the register class or
the machine register it must occupy. The template refers to it by that same name in braces:

```sysl
copy(n: int) -> int
    var v: int = 0
    asm
        [x86_64]
            "movl {n}, {v}"
            in n : reg
            out v : reg
        [aarch64]
            "mov {v}, {n}"
            in n : reg
            out v : reg
        [riscv64]
            "mv {v}, {n}"
            in n : reg
            out v : reg
    v

print(copy(7))
```

```output
7
```

`reg` means *any general-purpose register the allocator likes*, and it is the only class there is.
Where an instruction demands a particular register, name it — quoted, because it is the assembler's
name and not sysl's:

```sysl
out_byte(port: u16, value: u8)
    asm
        [x86_64]
            "outb {value}, {port}"
            in port : "dx"
            in value : "al"
        [aarch64, riscv64] unavailable "port I/O is x86-only"
```

**A bare word is sysl's and a quoted word is the assembler's.** That rule decides every case in the
construct: `reg` is a class this language names, `"dx"` is a register only the assembler knows, and
instruction text is quoted because sysl does not read it.

The class slot is required even though `reg` is currently the only class. Writing it keeps every
operand line one shape, so a second class arrives as a peer rather than as the exception to an
invisible default.

**The `:` here is not a type annotation.** An operand names a variable that already has a type, so
there is nothing left to declare — the slot holds a class or a register.

### What an operand may be

An operand must be a plain variable, and its type must fit a general-purpose register: the integers,
the pointers, `bool`. A float needs a floating class, which does not exist yet.

Reading and writing the same variable is refused, because it is two operands and so possibly two
registers — the instructions would read one and write another:

```sysl
f()
    var n: int = 1
    asm
        [x86_64]
            "addl {n}, {n}"
            in n : reg
            out n : reg
        [aarch64]
            "add {n}, {n}, {n}"
            in n : reg
            out n : reg
        [riscv64]
            "add {n}, {n}, {n}"
            in n : reg
            out n : reg

f()
```

```error
both read and written here
```

Only the **selected** arm's operands are checked, since the others describe machines this build is
not for. So a mistake in an arm is reported by a build for that arm's processor — which is the same
bargain exhaustiveness makes, one level down.

## What the block destroys

An arm may name registers it destroys beyond its operands:

```sysl
f()
    asm
        [x86_64]
            "nop"
            clobbers "rax", "rdx"
        [aarch64, riscv64] unavailable "x86 only here"
```

**Memory and the condition flags are assumed clobbered, always**, and cannot currently be given
back. That is the conservative direction on purpose: assuming them costs optimization quality across
a handful of instructions, and not assuming them costs a value kept in a register the block
overwrote — a wrong answer with nothing to point at.

Registers cannot be treated the same way. "Everything is clobbered" is a legal assumption and a
useless one, so the registers an arm destroys are the one part of its effect you have to state.

## What the compiler owns

**Operand substitution and its escaping.** `$` is LLVM's own operand marker, so a `$` you write — an
x86 immediate, `movq $1, %rsi` — is doubled by the compiler rather than by you. A doubled brace is a
literal one, which is not a nicety: ARM writes register lists as `{r0-r3}`, so `push {{lr}}` is how
you spell `push {lr}`.

**Label uniqueness.** A label in an arm is local to that arm's expansion, and a block emitted twice
gets two distinct labels. A label in inline assembly is otherwise a global symbol, and the second
definition is a duplicate the assembler rejects for reasons you cannot do anything about from where
you are standing.

**Line joining.** Instructions are separate strings on separate lines, each able to carry a comment.
This is the difference between an assembly routine that can be read and a six-instruction spinlock
written on one line with `\n` between the instructions.

**The constraint string**, which you never write, because a constraint that disagrees with the
instruction text is not detectable by reading either one.

## The words this construct spends

None of them is reserved. `asm`, `unavailable`, `out`, `reg` and `clobbers` are contextual: each is
recognized in exactly one position and is an ordinary identifier everywhere else — including inside
an assembly block, in any other position.

```sysl
f() -> int
    var out = 1
    var reg = 2
    var clobbers = 3
    var asm = 4
    out + reg + clobbers + asm

print(f())
```

```output
10
```

`in` is a reserved word already, for `for x in xs`, and is reused here rather than added to.

## Where assembly may not go

**Not in a `require` or `ensure` condition** — and there is no check that says so, because there is
no way to write it: a contract's condition is an expression and assembly is a statement. A contract
is a claim the compiler reasons about and an assembly block is precisely what it cannot reason
about, so the two never meeting is a property to rely on.

**Nothing about a block's contents is understood, including whether control comes back.** The
compiler does not read the instructions, so it cannot know that a jump to a reset vector never
returns — and it does not try. A function declared `-> never` with an assembly body is taken at its
word, exactly as anything else declared not to return is:

```sysl
arch_reset() -> never
    asm
        [x86_64]
            "cli"
            "1: hlt"
            "jmp 1b"
        [aarch64]
            "msr daifset, #2"
            "1: wfi"
            "b 1b"
        [riscv64]
            "csrci mstatus, 8"
            "1: wfi"
            "j 1b"
```

The promise is yours to keep here, which is true of the instructions themselves anyway.

## What is not here yet

- **`inout`** — a read-modify-write operand. The instructions wanting one are the exchange and
  compare-exchange family, which [`sysl.sync`](/library/) already covers.
- **Giving memory and the flags back**, which is an optimization over an answer that is currently
  always correct.
- **A floating register class.** It cannot be a single one: bare-metal RISC-V has no floating
  registers to name.
