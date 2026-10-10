---
title: Inline assembly
summary: Machine instructions, in an arm per architecture — where operands are values and the compiler owns the constraint string, the escaping, and the labels.
weight: 125
---

**Inline assembly is EXPERIMENTAL, and outside the [0.1.0 promise](/getting-started/stability/).**
Everything below is shipped, documented and checked against the compiler like every other page here.
What it does not have is a settled surface: a read-modify-write operand, a floating register class,
and the two spellings that hand memory and the flags back are all unbuilt, and each would add to the
notation rather than only to what it can reach. Build on it where it earns its place — but do not
expect it to hold unchanged across releases, and prefer a construct from the promised part of the
language where one will do.

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
        [x86_64]           "cli"
        [aarch64]          "msr daifset, #2"
        [riscv64, riscv32] "csrci mstatus, 8"
        [thumb]            "cpsid i"
        [craft]
            "csrr t0, status"
            "li t1, -3"
            "and t0, t0, t1"
            "csrw status, t0"
            clobbers "t0", "t1"
        [wasm32]           unavailable "a wasm module has no interrupts to disable"
```

An arm names one processor or several, spelled as [`#if`](/reference/attributes/) spells them:
`aarch64`, `x86_64`, `riscv64`, `riscv32`, `thumb`, `x86`, `wasm32`, `craft`. A name outside that
set is an error rather than a machine nobody has heard of.

**`wasm32` is the one that is not a processor**, and it will not carry instructions at all: a wasm
module has no registers to name and no assembler behind it. Its arm is therefore always empty or
`unavailable` — both forms are below — which makes it the standing reminder that these arms are
about *targets* rather than about chips.

**`craft` is 16-bit, and its arms are the ones worth reading twice.** It has three-operand
arithmetic and eight registers, so `mv` and `add` are spelled as RISC-V spells them — but it has no
logical immediate and no bit-clear on a control register, so clearing one bit of `status` is a
read-modify-write through two scratch registers rather than the one instruction every other machine
here writes. That is what a deliberately small instruction set costs, stated where somebody can see
it.

**`riscv32` and `thumb` are one board's two halves**, which is why they are usually written together
in what follows. The RP2350 boots either a pair of Cortex-M33s or a pair of RV32IMAC cores, and a
microcontroller is what inline assembly is mostly for. `thumb` rather than `arm` names the Arm one
because a Cortex-M executes Thumb only — an arm written for A32 would assemble for a machine that
cannot run it, and the name is what says so.

Here is a whole program. `yield`, `pause` and `nop` are each their machine's hint that a spin loop is
spinning, which is about as small as a real use of this construct gets:

```sysl
spin_hint()
    asm
        [x86_64]           "pause"
        [aarch64, thumb]   "yield"
        [riscv64, riscv32] "nop"
        [craft]            "nop"
        [wasm32]

spin_hint()
print("hinted")
```

```output
hinted
```

A program wanting the hint itself calls [`sysl.sync.spin_hint()`](/library/sync/#spin-hint), which is
this function with RISC-V's real `pause` in place of the `nop`.

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
this assembly has no arm for 'aarch64', 'riscv64', 'riscv32', 'thumb', 'wasm32' or 'craft'
```

This is the rule `#if` follows one level up, where every condition is checked in the branches being
skipped as well as the one being taken. Here it reaches past whether a branch *parses* to whether one
*exists* — so a forgotten processor is found by whoever forgot it, rather than by whoever first
builds for the machine that was left out.

### When there is genuinely no answer

Some assembly is unportable in principle rather than by omission. `outb` and `inb` are x86's; every
other processor here reaches devices through memory and has no equivalent at all. Such an
architecture says so, and says why:

```sysl
port_out(port: u16, value: u8)
    asm
        [x86_64]
            "outb {value}, {port}"
            in port : "dx"
            in value : "al"
        [aarch64, riscv64, riscv32, thumb, craft, wasm32] unavailable "port I/O is x86-only; devices are reached through memory"
```

The x86-64 build compiles that. A build for a processor the arm covers is refused, and the reason
travels into the diagnostic — which is the whole of what this form buys over leaving the arm out,
since leaving it out fails *every* build instead:

```sysl target=aarch64-macos
port_out()
    asm
        [x86_64] "outb %al, %dx"
        [aarch64, riscv64, riscv32, thumb, craft, wasm32] unavailable "port I/O is x86-only; devices are reached through memory"

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
        [aarch64]          "dmb ish"
        [thumb]            "dmb sy"
        [riscv64, riscv32] "fence rw, rw"
        [craft]
        [wasm32]
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
        [aarch64, thumb]
            "mov {v}, {n}"
            in n : reg
            out v : reg
        [riscv64, riscv32, craft]
            "mv {v}, {n}"
            in n : reg
            out v : reg
        [wasm32] unavailable "there are no registers for an operand to land in"
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
        [aarch64, riscv64, riscv32, thumb, craft, wasm32] unavailable "port I/O is x86-only"
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

**A name bound by `ref` is not an operand.** It names a place somewhere else rather than a variable
of its own, so there is no slot for the register to be loaded from or stored back to:

```sysl
f()
    var xs: [3]int = [1, 2, 3]
    ref r = xs[1]
    var v: int = 0
    asm
        [x86_64]
            "movl {r}, {v}"
            in r : reg
            out v : reg
        [aarch64, thumb]
            "mov {v}, {r}"
            in r : reg
            out v : reg
        [riscv64, riscv32, craft]
            "mv {v}, {r}"
            in r : reg
            out v : reg
        [wasm32] unavailable "there are no registers for an operand to land in"

f()
```

```error
'r' is bound by 'ref', so it names storage somewhere else rather than a variable of its own, and there is nothing here for an operand to be. Copy it into a 'var' first, and write that back afterwards if the instructions set it
```

Copy it into a `var` and hand the instructions that, writing the result back through the `ref` if
they set it:

```sysl
f()
    var xs: [3]int = [1, 2, 3]
    ref r = xs[1]
    var n: int = r
    var v: int = 0
    asm
        [x86_64]
            "movl {n}, {v}"
            in n : reg
            out v : reg
        [aarch64, thumb]
            "mov {v}, {n}"
            in n : reg
            out v : reg
        [riscv64, riscv32, craft]
            "mv {v}, {n}"
            in n : reg
            out v : reg
        [wasm32] unavailable "there are no registers for an operand to land in"
    r = v + 40
    print(xs[0], xs[1], xs[2])

f()
```

```output
1 42 3
```

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
        [aarch64, thumb, riscv64, riscv32, craft]
            "add {n}, {n}, {n}"
            in n : reg
            out n : reg
        [wasm32] unavailable "there are no registers for an operand to land in"

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
        [aarch64, riscv64, riscv32, thumb, craft, wasm32] unavailable "x86 only here"
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
        [thumb]
            "cpsid i"
            "1: wfi"
            "b 1b"
        [riscv64, riscv32]
            "csrci mstatus, 8"
            "1: wfi"
            "j 1b"
        [craft]
            "csrr t0, status"
            "li t1, -3"
            "and t0, t0, t1"
            "csrw status, t0"
            "wfi"
            clobbers "t0", "t1"
        [wasm32] unavailable "a wasm module cannot halt its host"
```

The promise is yours to keep here, which is true of the instructions themselves anyway.

## Assembly at the top of a file

Some code has to run before there is a function to run it in. The first instructions a processor
executes after reset have no stack yet, and a function — even one whose body is a single `asm`
block — may save a register on the stack before its first instruction. An exception vector table is
not a function at all: it is sixteen 128-byte slots on a 2 KiB boundary. Both are written as an
`asm` block at the **top of a file**, outside every function:

```sysl build=c target=aarch64-freestanding
@section(".text.boot")
asm
    [aarch64]
        ".globl _start"
        "_start:"
        "adrp x0, __stack_top"
        "add x0, x0, :lo12:__stack_top"
        "mov sp, x0"
        "b kmain"
    [x86_64, thumb, riscv64, riscv32, craft, wasm32] unavailable "this kernel boots on aarch64 only"

@align(2048)
@section(".text.vectors")
asm
    [aarch64]
        ".globl vectors"
        "vectors:"
        ".rept 16"
        "b ."
        ".balign 128"
        ".endr"
    [x86_64, thumb, riscv64, riscv32, craft, wasm32] unavailable "this kernel boots on aarch64 only"

extern "vectors" vectors()

@export("kmain")
kmain() -> int = 0
```

The block has the same arms as one inside a function, chosen the same way, and every processor still
needs an answer. What changes is that **nothing surrounds it**: the assembler lays the instructions
down by themselves, once, in the order the files were read.

- **A label is a symbol, as written.** A block at the top of a file is emitted exactly once, so its
  labels are not renamed — which is the point: `_start` and `vectors` above are the names a linker
  script, the processor and sysl code reach. sysl reaches one through an `extern`.
- **`$` is an ordinary character** — there are no operands for it to mark — and **a doubled brace
  is still a literal one**, so `push {{lr}}` means the same thing at the top of a file as inside a
  function. A single brace pair is a constant, below.
- **It is never left out.** Nothing in the program names a block, so reachability has nothing to
  ask of one: every block in every file of the build is laid down, including in a `build-c` archive.
  A block in a [`@tests` file](/reference/attributes/) is scaffolding, and only a test build carries
  it.
- **It belongs to the module even in the file a program starts in**, so a file holding nothing but a
  block and declarations does not become that file.

`@section("...")` places the instructions: they are bracketed by `.pushsection` and `.popsection`,
so whatever follows is back where it was. As everywhere else the name is the target's spelling, and
the assembler gives the section its flags from that name — `.text.boot` is code — so a section of
code is named under `.text`. `@align(n)` begins the block on an `n`-byte boundary, inside the section
when there is one; `n` is a constant power of two. Those two are the only annotations a block takes.

### A constant is written into the text

There are no operands at the top of a file, but there are constants: **`{NAME}` names a `const` in
scope, and the compiler writes its value into the instructions as a decimal number.** That is how a
layout the assembly shares with a struct is stated once. The entry code of an exception reserves a
frame and saves registers at its offsets; written with `sizeof` and `offsetof`, a field added to
`Frame` moves the assembly with it:

```sysl build=c target=aarch64-freestanding
struct Frame
    regs: [30]u64
    elr: u64
    spsr: u64

const FRAME: usize = sizeof(Frame)
const ELR: usize = offsetof(Frame, elr)

@section(".text.vectors")
asm
    [aarch64]
        ".globl trap_entry"
        "trap_entry:"
        "sub sp, sp, #{FRAME}"
        "stp x0, x1, [sp]"
        "mrs x0, elr_el1"
        "str x0, [sp, #{ELR}]"
        "b ."
    [x86_64, thumb, riscv64, riscv32, craft, wasm32] unavailable "this kernel boots on aarch64 only"

extern "trap_entry" trap_entry()

@export("kmain")
kmain() -> int = 0
```

The assembler reads `sub sp, sp, #256` and `str x0, [sp, #240]`. **Only an integer constant is
written** — a literal, a `const`, `sizeof`, `alignof`, `offsetof`, or the arithmetic over them,
anything a `const` may be — and a name that is anything else is refused, in every arm. Storage has a
value only while the program runs:

```sysl target=aarch64-freestanding
static var depth: u64 = 0

asm
    [aarch64] "mov x0, #{depth}"
    [x86_64, thumb, riscv64, riscv32, craft, wasm32] unavailable "aarch64 only"

print(depth)
```

```error
'{depth}' is not a constant: it names storage, whose value exists only while the program runs
```

A string, a `bool`, a `char` or a float is not a number the instructions can take, and a name that
reaches nothing is most likely a brace meant for the assembler, so the refusal offers the doubled
one:

```sysl target=aarch64-freestanding
asm
    [aarch64] "nop"
    [thumb] "push {lr}"
    [x86_64, riscv64, riscv32, craft, wasm32] "nop"
```

```error
'{lr}' names no constant: an 'asm' block at the top of a file writes a 'const' in scope as '{NAME}', and nothing here is called that. Write '{{' and '}}' for a brace the assembler is to see
```

**A symbol needs no placeholder.** A function or storage is reached by the name the linker knows —
`b kmain` above, through `@export("kmain")`, or a label the block defines and sysl declares
`extern` — so `{NAME}` naming one is refused with that advice rather than read as its address.

**A function only the block calls may stay `private`.** `private` is a promise about the sysl *name*
and `@export` one about a *symbol*, so together they are a function no other file can name whose
symbol the block still reaches. The symbol is published **hidden**: every object of the image being
linked resolves it, and a shared library built from it does not offer it to a loader
([a private export](/reference/ffi/#a-private-export)).

```sysl build=c target=aarch64-freestanding
@section(".text.boot")
asm
    [aarch64]
        ".globl thread_start"
        "thread_start:"
        "mov x0, x19"
        "bl keel_thread_entry"
    [x86_64, thumb, riscv64, riscv32, craft, wasm32] unavailable "aarch64 only"

@export("keel_thread_entry")
private thread_entry(arg: u64) -> u64 = arg + 1
```

**What only a block inside a function can mean is refused**, on every arm and not only on the one
being built, since the mistake does not depend on the machine. An operand has no variable to be:

```sysl target=aarch64-freestanding
var ticks: u64 = 0

asm
    [aarch64]
        "mrs {ticks}, cntvct_el0"
        out ticks : reg
    [x86_64, thumb, riscv64, riscv32, craft, wasm32] unavailable "aarch64 only"

print(ticks)
```

```error
'ticks' cannot be an operand here: an 'asm' block at the top of a file is outside every function
```

A `clobbers` line tells surrounding code what the block destroys, and there is no surrounding code:

```sysl target=aarch64-freestanding
asm
    [aarch64]
        "nop"
        clobbers "x0"
    [x86_64, thumb, riscv64, riscv32, craft, wasm32] unavailable "aarch64 only"
```

```error
'clobbers' tells the code around an 'asm' block which registers it destroys, and a block at the top of a file has no code around it
```

And a block inside a function is that function's code, so it cannot be placed apart from it — place
the function, or move the block to the top of the file:

```sysl target=aarch64-freestanding
halt()
    @section(".text.boot")
    asm
        [aarch64] "wfi"
        [x86_64, thumb, riscv64, riscv32, craft, wasm32] unavailable "aarch64 only"
```

```error
an 'asm' block inside a function is part of that function's code
```

### A file's bytes in the instructions

**`.incbin "file"` reads its file from the directory of the source the block is written in**, as
[`embed`](/reference/arrays/) does, and never from the directory the build was started in — the
compiler writes the absolute path into the instructions, so the same tree assembles from anywhere.
Where the data needs labels of its own or a section a linker script gathers, this is the form; where
it needs only to be read, `embed("file")` is shorter. This block carries its own source:

```sysl build=c target=aarch64-freestanding
@section(".rodata.blobs")
asm
    [aarch64]
        ".globl blob_start, blob_end"
        "blob_start:"
        ".incbin \"main.sysl\""
        "blob_end:"
    [x86_64, thumb, riscv64, riscv32, craft, wasm32] unavailable "this kernel boots on aarch64 only"

extern blob_start: u8
extern blob_end: u8

@export("kmain")
kmain() -> int = int(usize(&blob_end) - usize(&blob_start))
```

The file's bytes are in the build's keys as an embedded file's are, so an edit to it reassembles the
block. Only the arm being built reads its file — another machine's arm may name one this build never
made — and a file that is not there is refused while compiling, naming the path that was tried:

```sysl target=aarch64-freestanding
asm
    [aarch64] ".incbin \"user/hello.elf\""
    [x86_64, thumb, riscv64, riscv32, craft, wasm32] unavailable "aarch64 only"
```

```error
cannot include 'user/hello.elf' with '.incbin': there is no file at '
```

## System registers and barriers

On AArch64 most of a kernel's conversation with the processor is one instruction long: `mrs` to read
a system register, `msr` to write one, and a barrier. Each would be a function of its own wrapped
around an `asm` block with an `unavailable` arm for every other processor, since the register's name
is part of the instruction text. Five built-in forms say it directly instead:

```sysl build=c target=aarch64-freestanding
@export("kmain")
kmain(vectors: u64) -> u64
    write_sysreg("vbar_el1", vectors)
    isb()
    write_sysreg("daifclr", 2)
    dsb(sy)
    dmb(ish)
    read_sysreg("cntfrq_el0")
```

- **`read_sysreg(name) -> u64`** is `mrs`, and **`write_sysreg(name, value)`** is `msr`. The value is
  a `u64`; a narrower one is converted with `u64(...)`.
- **`dsb(option)`, `dmb(option)` and `isb()`** are the barriers. The option is one of the
  architecture's twelve words — `sy`, `st`, `ld`, `ish`, `ishst`, `ishld`, `nsh`, `nshst`, `nshld`,
  `osh`, `oshst`, `oshld` — and `isb` has only `sy`, so it takes none.

They are not assembly: each becomes the LLVM intrinsic for the instruction
(`llvm.read_volatile_register`, `llvm.write_register`, `llvm.aarch64.dsb`/`dmb`/`isb`), so the
optimizer knows what each one does. **A read is never merged with another read or moved past a
store**: reading `icc_iar1_el1` acknowledges an interrupt, and two reads have to be two `mrs`.

**The name is spelled into the instruction, so it is known while compiling** — a string literal or a
`const` string. It is the Arm architecture's name in any case (`CurrentEL`, `cntv_ctl_el0`), or the
generic `s<op0>_<op1>_c<n>_c<m>_<op2>` (`s3_3_c14_c0_0`). The compiler asks the back end that will
build the program whether it has that register **in the direction it is used** — `CurrentEL` can be
read and not written, `icc_eoir1_el1` written and not read — so a name it does not know is refused
here rather than by LLVM:

```sysl target=aarch64-freestanding
print(read_sysreg("esr_el9"))
```

```error
LLVM's AArch64 back end has no system register 'esr_el9' it can read
```

```sysl target=aarch64-freestanding
val which = "esr_el1"
print(read_sysreg(which))
```

```error
'read_sysreg' spells the register into the instruction, so its name has to be known while compiling
```

**A PSTATE field takes a constant.** `daifset`, `daifclr`, `spsel`, `pan`, `uao`, `dit`, `ssbs` and
`tco` are written by `msr` from an immediate inside the instruction, so the value is a constant from 0
to 15 (`allint` and `pm` take 0 or 1):

```sysl target=aarch64-freestanding
mask(bits: u64)
    write_sysreg("daifset", bits)

mask(2)
```

```error
'daifset' is a PSTATE field, which 'msr' writes from an immediate inside the instruction — the value has to be a constant from 0 to 15
```

A barrier's option is a word of the instruction, not a value, so it is written at the call:

```sysl target=aarch64-freestanding
dsb(full)
```

```error
'dsb' takes one of the architecture's options written here — sy, st, ld, ish, ishst, ishld, nsh, nshst, nshld, osh, oshst, oshld — and this is not one
```

### Cache, TLB and translation maintenance

Changing a translation table, or writing code into memory, takes the maintenance instructions as
well as the barriers. Four more forms issue them:

```sysl build=c target=aarch64-freestanding
@export("remap")
remap(entry: *u64, page: u64, va: u64) -> u64
    dc(civac, entry)
    dsb(ish)
    tlbi(vae1is, page)
    dsb(ish)
    ic(iallu)
    isb()
    at(s1e1r, va)
```

- **`tlbi(op)` and `tlbi(op, value)`** invalidate TLB entries: `vmalle1` and `vmalle1is` every
  entry, taking no operand; `vae1`, `vale1`, `aside1`, `vaae1` and `vaale1`, each also with an `is`
  suffix for the inner shareable domain, read a `u64` packing the page number, the ASID or both, as
  the architecture lays them out.
- **`dc(op, addr)`** is the data cache by address: `civac`, `cvac`, `cvau`, `ivac`, and `zva`, which
  zeroes a block.
- **`ic(op)` and `ic(op, addr)`** are the instruction cache: `iallu` and `ialluis` invalidate it
  whole, `ivau` by address.
- **`at(op, va) -> u64`** asks the MMU to translate `va` — `s1e1r`, `s1e1w`, `s1e0r` or `s1e0w`, a
  stage-1 translation as EL1 or EL0 would read or write — and answers PAR_EL1, with bit 0 set where
  the translation faulted. It is `at`, then `isb()`, then `read_sysreg("par_el1")`, issued together
  so the barrier the read needs cannot be left out.

An address may be a `u64` or a raw pointer; `tlbi`'s operand is not an address, so it is a `u64`
only. Each form is one block of inline assembly (LLVM has intrinsics for almost none of them), and it
clobbers memory: **it is never moved past a load or store, and two identical ones are never merged
into one**.

The operation is a word of the instruction, written at the call as a barrier's option is, and
whether it reads a register is the operation's. Leaving the operand off one that reads it, or adding
one to an operation that takes none, is refused:

```sysl target=aarch64-freestanding
tlbi(vae1is)
```

```error
'tlbi(vae1is)' takes a register operand, the u64 naming the page, the ASID or both — tlbi(vae1is, page)
```

```sysl target=aarch64-freestanding
dc(cisw, 0)
```

```error
'dc' takes one of the operations written here — civac, cvac, cvau, ivac, zva — and this is not one
```

### What the compiler orders, and what the processor still needs

**The compiler keeps program order around every one of these forms.** No load or store written
before a `read_sysreg`, a `write_sysreg`, a barrier or a maintenance form is moved after it, none
written after is moved before it, and a store is not dropped as dead because a second one to the same
word follows — each is treated as touching all of memory. That is the whole of what the compiler can
promise, because it is the whole of what the compiler controls.

**The processor is another matter.** An `msr` is not a memory access, so no
[`Ordering`](/library/sync/#ordering) — `Release`, `SeqCst`, a fence — orders a store before it: the
C11 orderings relate memory accesses to memory accesses, and an AArch64 core may let the write to
`icc_sgi1r_el1` raise its interrupt on another core before that core can see the store the interrupt
is about. What orders memory against a register, or against the MMU, is a `dsb`, and its option says
what has to be complete and where:

```sysl build=c target=aarch64-freestanding
import sysl.sync.*

var work: u64 = 0

@export("hand_over")
hand_over(job: u64, target: u64)
    atomic_store(&work, job, Release)
    dsb(ishst)
    write_sysreg("icc_sgi1r_el1", target)
    isb()
```

| before | the barrier | why |
|---|---|---|
| a store another core reads once an interrupt this write raises arrives (an SGI) | `dsb(ishst)`, then the `msr` | the store completes in the inner shareable domain before the register is written |
| a translation-table write the MMU will walk | `dsb(ishst)`, then `isb()` | the walker reads memory, not this core's store buffer |
| a TLB invalidate | `tlbi(...)`, then `dsb(ish)` | the invalidation completes on every core |
| Normal memory a device must see before a write to the device | `dmb(osh)`, or `dsb(osh)` where the write is to a register | a device sits in the outer shareable domain, which `ish` does not reach |
| a write that changes the context — `vbar_el1`, `ttbr0_el1`, `sctlr_el1` | `isb()` after it | the instructions already fetched were fetched under the old context |

**So the orderings and the barriers are not two tiers of one tool.** An `Ordering` is the portable
answer between two threads touching memory; a barrier is the answer wherever one side is not a memory
access, and only AArch64 spells it, which is why these forms are refused on every other processor.
The forms take no `Ordering` argument for the same reason: the instruction that does the ordering is
the barrier, and which barrier depends on what the register's effect observes, which no ordering
names.

### On other processors

**Every other processor refuses all nine forms**, naming the target, so a module that uses them on
AArch64 and is also built elsewhere puts those lines behind `#if aarch64`:

```sysl target=riscv64-freestanding
print(read_sysreg("cntfrq_el0"))
```

```error
'read_sysreg' is an AArch64 instruction, and 'riscv64-freestanding' is riscv64 — put the code that uses it behind '#if aarch64'
```

The nine names are taken only where nothing else claims them: a function or a local called `isb` or
`at` is what `isb()` or `at(...)` calls.

## What is not here yet

- **`inout`** — a read-modify-write operand. The instructions wanting one are the exchange and
  compare-exchange family, which [`sysl.sync`](/library/) already covers.
- **Giving memory and the flags back**, which is an optimization over an answer that is currently
  always correct.
- **A floating register class.** It cannot be a single one: bare-metal RISC-V has no floating
  registers to name.
