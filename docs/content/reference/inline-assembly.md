---
title: Inline assembly
summary: Machine instructions, in an arm per architecture — where operands are values and the compiler owns the constraint string, the escaping, and the labels.
weight: 125
---

**Inline assembly is experimental, and outside the [stability promise](/getting-started/stability/).**
Everything below works and is checked against the compiler like every other page here. What it does
not have yet is a settled surface: a read-modify-write operand, a floating-point register class, and
the two forms that hand memory and the flags back to the compiler are not built, and each would add
to the notation. Use it where it is needed, expect it to change between releases, and prefer a
construct from the stable part of the language where one will do.

`asm` reaches the instructions no library can wrap: privileged instructions, instructions that talk
to a bus rather than to memory, and the few that reconfigure the machine the program is running on.
It is the one construct that steps outside the language, and it is designed so that doing so costs
as little as possible: you supply the instructions, and nothing else.

**The compiler supplies the rest.** Which register an operand lands in, how the value gets there,
what the block destroys, how a label avoids colliding with a second expansion of the same block, and
how an operand is spelled in the emitted template are all things the compiler knows. Written by
hand, each would be a comment that nothing checks.

sysl does **not** ship named functions such as "disable interrupts" or "flush the TLB" that expand
to each machine's instruction. Assembly is the primitive; the architecture layer above it is ordinary
sysl that you write, and the language makes that layer *checkable*.

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
`aarch64`, `x86_64`, `riscv64`, `riscv32`, `thumb`, `x86`, `wasm32`, `craft`. Any other name is an
error.

**An arm selects a processor; differences between cores of one processor are handled with `#if`.**
Every Arm target is `thumb`, from a Cortex-M0+ with no floating-point registers to a Cortex-M7 with
double-precision ones. The arm stays `[thumb]`, and the lines that touch those registers sit behind
[`#if fpu`](/reference/attributes/#the-symbols-are-derived-from-the-target-and-the-set-is-closed)
inside it, resolved before the statement is parsed.

**`wasm32` is not a processor**, and its arm never carries instructions: a wasm module has no
registers to name and no assembler behind it. Its arm is always empty or `unavailable` (both forms
are shown below), which is a reminder that the arms select *targets* rather than chips.

**`craft` is a 16-bit processor with a deliberately small instruction set.** It has three-operand
arithmetic and eight registers, so `mv` and `add` are spelled as on RISC-V. It has no logical
immediate and no bit-clear on a control register, so clearing one bit of `status` takes a
read-modify-write through two scratch registers rather than the single instruction the other
machines use.

**`riscv32` and `thumb` are the two halves of one board**, which is why they usually appear
together below. The RP2350 boots either a pair of Cortex-M33 cores or a pair of RV32IMAC cores, and
microcontrollers are where inline assembly is used most. The Arm arm is named `thumb` rather than
`arm` because a Cortex-M executes only Thumb code; an arm written for A32 would assemble for a
machine that cannot run it.

Here is a complete program. `yield`, `pause` and `nop` are each their machine's hint that a loop is
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

**Arms use square brackets rather than a `match` arm's `->`.** sysl writes brackets around things
resolved at compile time, such as a type parameter list or `Option[T]`, and writes the arrow between
a runtime pattern and its body. An architecture is not a value being tested: the arms not chosen do
not appear in the output at all.

## Every architecture needs an answer

The arms must cover every processor a target can be built for, not only the one the current build
targets. A missing arm is an error on **every** build:

```sysl
halt()
    asm
        [x86_64] "hlt"

halt()
```

```error
this assembly has no arm for 'aarch64', 'riscv64', 'riscv32', 'thumb', 'wasm32' or 'craft'
```

`#if` follows the same principle: every condition is checked in the skipped branches as well as the
taken one. Here the check goes beyond whether a branch *parses* to whether one *exists*, so a
forgotten processor is reported to the author who forgot it, not to whoever first builds for the
machine that was left out.

### When there is genuinely no answer

Some assembly is unportable in principle rather than by omission. `outb` and `inb` exist only on
x86; every other processor here reaches devices through memory and has no equivalent. Such an arm
says so, and says why:

```sysl
port_out(port: u16, value: u8)
    asm
        [x86_64]
            "outb {value}, {port}"
            in port : "dx"
            in value : "al"
        [aarch64, riscv64, riscv32, thumb, craft, wasm32] unavailable "port I/O is x86-only; devices are reached through memory"
```

An x86-64 build compiles that. A build for a processor the `unavailable` arm covers is refused, and
the reason appears in the diagnostic. Leaving the arm out instead would fail *every* build:

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

An arm with nothing under it is also an answer: this processor needs no instruction. A memory
barrier is free on a machine that never reorders the accesses in question, and `unavailable` would
be wrong there, because the operation is available and simply costs nothing.

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

An empty arm cannot be confused with a forgotten one: a forgotten arm is absent, and an absent arm
is the error above.

## Operands are values, not registers

An operand names a variable already in scope, gives its direction, and gives the register class or
the machine register it must occupy. The template refers to it by that same name in braces:

```sysl
copy(n: int) -> int
    var v = 0
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

`reg` means *any general-purpose register the allocator chooses*, and it is the only class. Where an
instruction requires a particular register, name it, in quotes, because it is the assembler's name
and not sysl's:

```sysl
out_byte(port: u16, value: u8)
    asm
        [x86_64]
            "outb {value}, {port}"
            in port : "dx"
            in value : "al"
        [aarch64, riscv64, riscv32, thumb, craft, wasm32] unavailable "port I/O is x86-only"
```

**A bare word belongs to sysl and a quoted word belongs to the assembler.** That rule settles every
case in the construct: `reg` is a class the language names, `"dx"` is a register only the assembler
knows, and instruction text is quoted because sysl does not read it.

The class slot is required even though `reg` is currently the only class. Writing it keeps every
operand line the same shape, so a second class can be added alongside `reg` rather than as an
exception to a hidden default.

**The `:` here is not a type annotation.** An operand names a variable that already has a type, so
there is nothing to declare; the slot holds a class or a register.

### What an operand may be

An operand must be a plain variable whose type fits a general-purpose register: an integer, a
pointer or `bool`. A float would need a floating-point register class, which does not exist yet.

**A name bound by `ref` is not an operand.** It names storage somewhere else rather than a variable
of its own, so there is no slot for the register to be loaded from or stored back to:

```sysl
f()
    var xs = [1, 2, 3]
    ref r = xs[1]
    var v = 0
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

Copy it into a `var`, give the instructions that, and write the result back through the `ref` if
they set it:

```sysl
f()
    var xs = [1, 2, 3]
    ref r = xs[1]
    var n = r
    var v = 0
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

Reading and writing the same variable is refused, because that is two operands and possibly two
registers; the instructions would read one and write the other:

```sysl
f()
    var n = 1
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

Only the **selected** arm's operands are checked, since the others describe machines this build does
not target. A mistake in an arm is therefore reported by a build for that arm's processor.

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

**Memory and the condition flags are always assumed clobbered**, and there is not yet a way to state
otherwise. This is deliberately conservative: assuming them clobbered costs some optimization across
a few instructions, while wrongly assuming them preserved leaves a stale value in a register the
block overwrote, which is a wrong answer that is very hard to trace.

Registers cannot be handled the same way. "Everything is clobbered" is a legal assumption but a
useless one, so the registers an arm destroys are the one part of its effect you must state.

## What the compiler owns

**Operand substitution and its escaping.** `$` is LLVM's operand marker, so a `$` you write, such as
an x86 immediate in `movq $1, %rsi`, is doubled by the compiler. A doubled brace is a literal brace,
which matters on ARM, where register lists are written `{r0-r3}`: `push {{lr}}` emits `push {lr}`.

**Label uniqueness.** A label in an arm is local to that arm's expansion, and a block emitted twice
gets two distinct labels. In plain inline assembly a label is a global symbol, and a second copy of
the block is a duplicate definition that the assembler rejects.

**Line joining.** Instructions are separate strings on separate lines, and each line can carry a
comment. A routine of several instructions stays readable instead of being written on one line with
`\n` between the instructions.

**The constraint string**, which you never write. A constraint that disagrees with the instruction
text cannot be detected by reading either one.

**Every output gets a register of its own.** The compiler does not read the instructions, so it
cannot know whether they finish reading the inputs before they write an output, and an output that
shares an input's register gives a wrong answer exactly when they do not. No output is ever given
the register an input arrives in (LLVM's *early-clobber*, `=&r`), so an arm may write its output
first and read an input afterwards:

```sysl
seven_plus(level: int) -> int
    var old = 0
    asm
        [aarch64]
            "mov {old}, #7"
            "add {old}, {old}, {level}"
            in level : reg
            out old : reg
        [x86_64]
            "movq $7, {old}"
            "addq {level}, {old}"
            in level : reg
            out old : reg
        [riscv64, riscv32]
            "li {old}, 7"
            "add {old}, {old}, {level}"
            in level : reg
            out old : reg
        [thumb, craft, wasm32] unavailable "not shown here"
    old

print(seven_plus(1))
```

```output
8
```

The one exception is an output bound to the same machine register as an input, as in
`in n : "eax"` and `out v : "eax"`, which states that the two share it.

**An output is a fresh value, made of none of the inputs.** For the same reason — the compiler does
not read the instructions — it does not guess that an output is an input copied through, and so the
check that keeps a frame's address from being turned back into a pointer after the frame has ended
([memory](/reference/memory/#where-no-body-is-in-sight-the-call-borrows)) takes a system call's
answer as the kernel's own number, not as the arguments handed in. An arm that does copy an address
from an input to an output takes that address out of the check's sight, as `ptr_cast` arithmetic
does.

## The words this construct uses

None of them is reserved. `asm`, `unavailable`, `out`, `reg` and `clobbers` are contextual: each is
recognized in exactly one position and is an ordinary identifier everywhere else, including in any
other position inside an assembly block.

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

`in` is already a reserved word, used in `for x in xs`, and is reused here.

## Where assembly may not go

**Not in a `require` or `ensure` condition.** No check is needed, because there is no way to write
it: a contract's condition is an expression and assembly is a statement. A contract is a claim the
compiler reasons about, and an assembly block is exactly what it cannot reason about, so you can
rely on the two never meeting.

**The compiler does not interpret a block's contents, including whether control returns.** It does
not read the instructions, so it cannot know that a jump to a reset vector never returns, and it
does not try. A function declared `-> never` with an assembly body is taken at its word, like any
other function declared not to return:

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

Keeping that promise is the author's responsibility, as is the correctness of the instructions
themselves.

## Assembly at the top of a file

Some code has to run before there is a function to run it in. The first instructions a processor
executes after reset have no stack yet, and a function, even one whose body is a single `asm` block,
may save a register on the stack before its first instruction. An exception vector table is not a
function at all: it is sixteen 128-byte slots on a 2 KiB boundary. Both are written as an `asm`
block at the **top of a file**, outside every function:

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

The block has the same arms as one inside a function, selected the same way, and every processor
still needs an answer. The difference is that **nothing surrounds it**: the assembler emits the
instructions on their own, once, in the order the files were read.

- **A label is a symbol, exactly as written.** A block at the top of a file is emitted exactly once,
  so its labels are not renamed. `_start` and `vectors` above are the names that a linker script,
  the processor and sysl code refer to; sysl code reaches one through an `extern`.
- **`$` is an ordinary character**, since there are no operands for it to mark, and **a doubled
  brace is still a literal brace**, so `push {{lr}}` means the same at the top of a file as inside a
  function. A single brace pair names a constant, described below.
- **It is never omitted.** Nothing in the program refers to a block, so reachability does not apply:
  every block in every file of the build is emitted, including in a `build-c` archive. A block in a
  [`@tests` file](/reference/attributes/) is test scaffolding, and only a test build carries it.
- **It belongs to the module even in the file a program starts in**, so a file holding nothing but a
  block and declarations does not become the program's entry file.

`@section("...")` places the instructions: they are bracketed by `.pushsection` and `.popsection`,
so whatever follows returns to the previous section. As elsewhere, the name is spelled as the target
spells it, and the assembler derives the section's flags from the name (`.text.boot` is code), so a
code section is named under `.text`. `@align(n)` starts the block on an `n`-byte boundary, inside the
section when there is one; `n` is a constant power of two. These are the only two annotations a block
accepts.

### A constant is written into the text

There are no operands at the top of a file, but there are constants: **`{NAME}` names a `const` in
scope, and the compiler writes its value into the instructions as a decimal number.** This lets a
layout shared between the assembly and a struct be stated once. The entry code of an exception
handler reserves a frame and saves registers at fixed offsets; written with `sizeof` and `offsetof`,
the assembly follows any field added to `Frame`:

```sysl build=c target=aarch64-freestanding
struct Frame
    regs: [30]u64
    elr: u64
    spsr: u64

const FRAME = sizeof(Frame)
const ELR = offsetof(Frame, elr)

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
written**: a literal, a `const`, `sizeof`, `alignof`, `offsetof`, or arithmetic over them, which is
anything a `const` may be. A name that is anything else is refused, in every arm. Storage has a value
only while the program runs:

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

A string, a `bool`, a `char` or a float is not a number the instructions can take. A name that
refers to nothing is most likely a brace meant for the assembler, so the diagnostic suggests the
doubled form:

```sysl target=aarch64-freestanding
asm
    [aarch64] "nop"
    [thumb] "push {lr}"
    [x86_64, riscv64, riscv32, craft, wasm32] "nop"
```

```error
'{lr}' names no constant: an 'asm' block at the top of a file writes a 'const' in scope as '{NAME}', and nothing here is called that. Write '{{' and '}}' for a brace the assembler is to see
```

**A symbol needs no placeholder.** A function or storage is reached by the name the linker knows:
`b kmain` above, through `@export("kmain")`, or a label the block defines and sysl declares
`extern`. A `{NAME}` that names one is refused with that advice rather than read as its address.

**A function that only the block calls may stay `private`.** `private` concerns the sysl *name* and
`@export` concerns the *symbol*, so together they give a function no other file can name but whose
symbol the block can still reach. The symbol is published **hidden**: every object in the image
being linked resolves it, and a shared library built from it does not offer it to a loader
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

**Anything that only makes sense inside a function is refused**, on every arm and not only on the
one being built, since the mistake does not depend on the machine. An operand has no variable to
bind to:

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

A `clobbers` line tells the surrounding code what the block destroys, and here there is no
surrounding code:

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

A block inside a function is part of that function's code, so it cannot be placed in a section of
its own. Place the function instead, or move the block to the top of the file:

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
[`embed`](/reference/arrays/) does, and never from the directory the build was started in. The
compiler writes the absolute path into the instructions, so the same tree assembles from anywhere.
Use this form when the data needs labels of its own or a section a linker script collects; when it
only needs to be read, `embed("file")` is shorter. This block includes its own source file:

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

The file's bytes are part of the build's cache key, as an embedded file's are, so editing the file
reassembles the block. Only the arm being built reads its file, since another machine's arm may name
one this build never produced. A missing file is refused at compile time, with the path that was
tried:

```sysl target=aarch64-freestanding
asm
    [aarch64] ".incbin \"user/hello.elf\""
    [x86_64, thumb, riscv64, riscv32, craft, wasm32] unavailable "aarch64 only"
```

```error
cannot include 'user/hello.elf' with '.incbin': there is no file at '
```

## System registers and barriers

On AArch64 most of a kernel's interaction with the processor is a single instruction: `mrs` to read
a system register, `msr` to write one, and a barrier. Written with `asm`, each would need its own
function around a block with an `unavailable` arm for every other processor, since the register's
name is part of the instruction text. Five built-in forms express them directly instead:

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
  a `u64`; a narrower unsigned value converts to it implicitly.
- **`dsb(option)`, `dmb(option)` and `isb()`** are the barriers. The option is one of the
  architecture's twelve words (`sy`, `st`, `ld`, `ish`, `ishst`, `ishld`, `nsh`, `nshst`, `nshld`,
  `osh`, `oshst`, `oshld`); `isb` has only `sy`, so it takes none.

These are not assembly: each becomes the LLVM intrinsic for the instruction
(`llvm.read_volatile_register`, `llvm.write_register`, `llvm.aarch64.dsb`/`dmb`/`isb`), so the
optimizer knows what each one does. **A read is never merged with another read or moved past a
store**: reading `icc_iar1_el1` acknowledges an interrupt, so two reads must remain two `mrs`
instructions.

**The name is part of the instruction, so it must be known at compile time**: a string literal or a
`const` string. It is the Arm architecture's name in any letter case (`CurrentEL`, `cntv_ctl_el0`),
or the generic `s<op0>_<op1>_c<n>_c<m>_<op2>` (`s3_3_c14_c0_0`). The compiler asks the back end that
will build the program whether it supports that register **in the direction it is used**
(`CurrentEL` can be read but not written, `icc_eoir1_el1` written but not read), so an unknown name
is refused by sysl rather than by LLVM:

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
`tco` are written by `msr` from an immediate inside the instruction, so the value must be a constant
from 0 to 15 (`allint` and `pm` take 0 or 1):

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

Changing a translation table, or writing code into memory, needs the maintenance instructions as
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
- **`at(op, va) -> u64`** asks the MMU to translate `va` (`s1e1r`, `s1e1w`, `s1e0r` or `s1e0w`: a
  stage-1 translation as an EL1 or EL0 read or write) and returns PAR_EL1, with bit 0 set when the
  translation faulted. It issues `at`, then `isb()`, then `read_sysreg("par_el1")` together, so the
  barrier the read needs cannot be left out.

An address may be a `u64` or a raw pointer; `tlbi`'s operand is not an address, so it is a `u64`
only. Each form is one block of inline assembly (LLVM has intrinsics for almost none of them), and it
clobbers memory: **it is never moved past a load or store, and two identical ones are never merged
into one**.

The operation is a word of the instruction, written at the call like a barrier's option, and the
operation determines whether a register operand is read. Leaving the operand off an operation that
reads one, or adding one to an operation that takes none, is refused:

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
written after is moved before it, and a store is not dropped as dead because a second store to the
same word follows: each form is treated as touching all of memory. That is everything the compiler
can promise, because it is everything the compiler controls.

**A floating-point operation is not a memory access, so this order does not hold it.** An ordinary
`x + y` may be computed while compiling, merged with an identical one, or moved past a
`write_sysreg("fpcr", …)` that changes the rounding mode, and its flags read through
`read_sysreg("fpsr")` may never be raised. A function whose floating point has to follow those
writes is marked [`@strict_fp`](/reference/attributes/#strict-fp-floating-point-that-follows-the-environment-it-runs-in),
and [`sysl.fenv`](/library/fenv/) reads and sets the environment on every machine that has one.

**The processor is a separate matter.** An `msr` is not a memory access, so no
[`Ordering`](/library/sync/#ordering) (`Release`, `SeqCst`, a fence) orders a store before it. The
C11 orderings relate memory accesses to other memory accesses, and an AArch64 core may let the write
to `icc_sgi1r_el1` raise its interrupt on another core before that core can see the store the
interrupt refers to. What orders memory against a register, or against the MMU, is a `dsb`, and its
option says what must be complete and where:

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

**The orderings and the barriers are therefore not two levels of one tool.** An `Ordering` is the
portable answer between two threads accessing memory; a barrier is the answer wherever one side is
not a memory access. The forms take no `Ordering` argument for the same reason: the barrier is the instruction
that does the ordering, and which barrier is right depends on what the register's effect observes,
which no ordering expresses.

### Barriers on an M-profile core

A Cortex-M core (Thumb: `thumbv6m`, `thumbv7m`, `thumbv7em` and `thumbv8m` targets) has `dsb`,
`dmb` and `isb` too, and the same three forms build them, as `llvm.arm.dsb`/`dmb`/`isb`. The
architecture defines one option there, `sy`, so that is the only word a barrier takes on Thumb:

```sysl build=c target=thumbv6m-freestanding
var vectors: u32 = 0

@export("relocate")
relocate(table: u32)
    vectors = table
    dsb(sy)
    isb()
```

```sysl target=thumbv6m-freestanding
dmb(ish)
```

```error
an M-profile core's 'dmb' has only the option 'sy' — the architecture reserves every other one there — so it is written dmb(sy)
```

## Hints: waiting and signalling

Five more forms are the hint instructions a core uses to sleep until something happens and to wake
the others. Each takes nothing:

| form | instruction | AArch64 | M-profile Thumb | RISC-V |
|---|---|---|---|---|
| `wfe()` | wait for an event (or an interrupt) | yes | yes | — |
| `wfi()` | wait for an interrupt | yes | yes | yes |
| `sev()` | send an event to every core | yes | yes | — |
| `sevl()` | send an event to this core only | yes | — | — |
| `nop()` | nothing, taking one slot | yes | yes | yes |

The busy-wait hint, `yield` on Arm and `pause` elsewhere, is
[`sysl.sync.spin_hint()`](/library/sync/#spin-hint), which every processor has an answer for.

On AArch64 each form is `llvm.aarch64.hint` and on Thumb `llvm.arm.hint`, with the instruction's
number in the architecture's hint space (0 `nop`, 2 `wfe`, 3 `wfi`, 4 `sev`, 5 `sevl`). RISC-V's
`wfi` and `nop` have no LLVM intrinsic, so each is the instruction as `sideeffect` assembly that
clobbers memory. This is what clang's `__builtin_arm_wfe()` and Rust's `core::arch::arm::__wfe()` are
too.

**Each hint keeps program order, like a barrier.** No load or store is moved across one, and none is
deleted because it looks unused; a `nop()` written twice is two `nop`s. That is what makes the loop
a core waits in correct: the flag is read again after every `wfe`, rather than once before the loop.

```sysl build=c target=thumbv6m-freestanding
var ready: u32 = 0

@export("wait_for_other_core")
wait_for_other_core()
    while ready == 0
        wfe()

@export("release_other_core")
release_other_core()
    ready = 1
    dsb(sy)
    sev()
```

The `dsb(sy)` there is the processor's half: it makes the store visible before the event goes out,
which keeping program order in the compiler does not do on its own.

```sysl build=c target=aarch64-freestanding
@export("park")
park()
    sevl()
    loop
        wfe()
        wfi()
```

```sysl build=c target=riscv32-freestanding
@export("idle")
idle()
    loop
        wfi()
        nop()
```

### On other processors

**A processor without an instruction refuses its form**, naming the target and the processors that
have it, so a module that uses one and is also built for other processors puts those lines behind an
`#if` for one of them. The system registers and maintenance forms are AArch64's alone:

```sysl target=riscv64-freestanding
print(read_sysreg("cntfrq_el0"))
```

```error
'read_sysreg' is an AArch64 instruction, and 'riscv64-freestanding' is riscv64 — put the code that uses it behind '#if aarch64'
```

```sysl target=riscv64-freestanding
wfe()
```

```error
'wfe' is an instruction of AArch64 and M-profile Thumb, and 'riscv64-freestanding' is riscv64 — put the code that uses it behind '#if aarch64' or '#if thumb'
```

```sysl target=thumbv6m-freestanding
sevl()
```

```error
'sevl' is an AArch64 instruction, and 'thumbv6m-freestanding' is thumb — put the code that uses it behind '#if aarch64'
```

The fourteen names apply only where nothing else claims them: if a function or a local is called
`isb`, `at` or `nop`, then `isb()`, `at(...)` or `nop()` calls it.

## What is not here yet

- **`inout`**, a read-modify-write operand. The instructions that need one are the exchange and
  compare-exchange family, which [`sysl.sync`](/library/) already covers.
- **Declaring that memory and the flags are preserved**, which would be an optimization over the
  current behaviour, which is always correct.
- **A floating-point register class.** It cannot be a single class: bare-metal RISC-V has no
  floating-point registers to name.
