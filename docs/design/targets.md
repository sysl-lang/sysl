# Targets

**Status:** decided and built. This is the target half of the doc `capabilities.md` said was
still to be written — what a machine is, how one is named, and what naming one changes. The
project-config half of that promise — the `sysl.conf` schema, per-target capability sets, and
filename-axis platform selection — is still open, and is listed at the bottom.

A systems language cannot be vague about the machine. Two of them differ in more than speed:
they disagree about how a C function is called, and a compiler that guesses produces a module
that looks right and is not. So sysl compiles **for** a target, always, and says which one in
the module it emits.

## A target is a value, not an ambient fact

`Target` is a plain value carried from the invocation down to codegen. Nothing anywhere consults
the machine the compiler happens to be *running on* — that machine appears exactly once, as the
default an invocation that names no target gets.

That is what lets one compiler build for a machine it is not, and it is why the whole registry
can be exercised from a test suite running on one laptop: a cross-target test reads the emitted
text, which is where everything a target decides shows up.

```
sysl build hello.sysl                          # for this machine
sysl build hello.sysl --target x86_64-linux    # for another
sysl targets                                   # what there is
```

`run` is the one subcommand that refuses a cross target, because running the result is the whole
of what makes it different from `build`.

## The registry

| name | triple | `va_list` | floating registers |
|---|---|---|---|
| `aarch64-macos` | `arm64-apple-macosx` | loaded | yes |
| `x86_64-macos` | `x86_64-apple-macosx` | address | yes |
| `aarch64-linux` | `aarch64-unknown-linux-gnu` | copied | yes |
| `x86_64-linux` | `x86_64-unknown-linux-gnu` | address | yes |
| `riscv64-linux` | `riscv64-unknown-linux-gnu` | loaded | yes |
| `x86_64-windows` | `x86_64-pc-windows-msvc` | loaded | yes |
| `aarch64-freestanding` | `aarch64-none-elf` | copied | yes |
| `x86_64-freestanding` | `x86_64-unknown-none-elf` | address | yes |
| `riscv64-freestanding` | `riscv64-unknown-elf` | loaded | **no** |
| `x86-linux` | `i386-unknown-linux-gnu` | *32-bit — not yet supported* | |

**Bare-metal RISC-V is the one target with no floating registers to pass arguments in.** The hosted
triple is built for the D extension and this one is not, which is clang's default for each — and
since sysl hands its own triple to clang, the two have to make the same assumption about the same
triple or the call disagrees. It reaches exactly one decision, whether a small aggregate of floating
members is flattened into registers, and that is why it is recorded rather than derived.

**`Freestanding` is a real answer, not a missing one.** A kernel or a bare-metal program has no
operating system, and the ABI of a freestanding ELF target is fully specified; it differs from a
hosted target on the same processor only where the OS is what fixed the convention. That is the
target a `no alloc` module (`capabilities.md`) is eventually built for.

**A target sysl knows and cannot build for is listed anyway.** `x86-linux` is refused with a
message saying it is 32-bit, because a reader told the name is *unknown* would go looking for a
typo that is not there. The limit is the compiler's, not the machine's — see *What a target does
not decide*.

### Adding one

A target is not a description of a machine. It is the set of answers codegen asks for, so:

- **the way to add a field is to have something need it** — a fact nothing reads is a fact
  nothing can be wrong about;
- **the way to add a target is to measure it**, by compiling the equivalent C with
  `clang -target <triple> -S -emit-llvm` and reading what comes out. Every row above was
  established that way. An ABI document tells you what is specified; the C compiler on the other
  side of the call tells you what is *done*, and it is the second one a call has to agree with.

## How a machine names itself

The compiler runs on three platforms and each describes one machine in its own words. Observed on
one Apple-silicon laptop:

| platform | processor | system |
|---|---|---|
| JVM | `aarch64` | `Mac OS X` |
| Scala Native | `aarch64` | `darwin` |
| Node | `arm64` | `darwin` |

Three runtimes, three vocabularies, and no two of them agree on both halves — Scala Native does
not even spell the processor the way its own triple does. So the **asking** is per-platform, at
the edge (`cross-platform.md`), and the **answering** is one shared function. A machine sysl has
no entry for resolves to nothing at all rather than to half a name, and the driver then says so
and stops: a guess here is the one kind of error the output would not show.

`sysl targets` always prints the words this machine's own runtime used, recognized or not. On an
unrecognized machine that line is the whole of what a report needs, and there is nowhere else to
read it.

## What a target decides

Two things, and both of them are the same thing at bottom: **what a call to a C function looks
like**. Sysl's own calls need no target's opinion, because both sides of one are this compiler and a
convention they share is a convention by construction. A foreign callee was compiled by somebody
else against a published document, and then the document is the only thing that can make the two
agree.

### How an aggregate crosses to a C function

A scalar crosses as itself; an `i32` is one register everywhere. An aggregate does not, and **LLVM
applies no C classification to one of its own accord** — given a struct type in a signature it
assigns one register per element, which is not what any of the four conventions asks for. So a
foreign declaration names the *coerced* types the convention specifies and the call converts each
value into and out of that shape. The four:

- **AAPCS64** asks first whether the aggregate is a homogeneous floating aggregate — up to four
  members all of one floating width, however deeply nested — because those go in floating registers
  whatever their size, so four doubles are registers and five are not. Otherwise it is size: eight
  bytes or fewer in one register, sixteen or fewer in two, more than that in memory. It is the one
  convention whose two directions differ: a **result** is named by the aggregate's exact width
  (`i24` for three bytes, `i40` for five) and an **argument** by the whole register it travels in.
- **System V** classifies one eightbyte at a time: a chunk every byte of which belongs to a floating
  member goes in a floating register, anything else in an integer one, and past two chunks the whole
  thing goes in memory. Two chunks are two *separate parameters*, where AAPCS64 passed one array of
  two.
- **RISC-V** flattens the narrow floating cases: one or two floating members travel as themselves,
  and one floating member beside one integer member travels in one register of each. A pointer beside
  a float is not that case.
- **The Microsoft convention** is the simplest: one, two, four or eight bytes in one integer
  register, anything else by address. No floating case at all.

Two details are worth stating because no document states them and only the measurement finds them.
System V names an integer chunk after **the member that starts it** when that member is all the chunk
carries — the `u8` after an `i64` is an `i8`, not the register it will travel in. And both AAPCS64
and RISC-V name a sixteen-byte aggregate `i128` rather than two `i64`s once it is aligned to sixteen.

Only the boundary is affected. A struct handed over **by address** needs none of this, which is why
that was the workaround while the boundary was broken, and a sysl-to-sysl call is untouched.

### How a walk over a variadic tail reaches a C function

C's `va_list` is a different type on every target and is passed three different ways (`12 §9`), so
the address of the walk — the only thing sysl has — crosses over as:

- **loaded** — the storage holds one pointer-sized value and the call passes *that value*. Darwin
  arm64, where `va_list` *is* `char *`; Windows x64; RISC-V.
- **address** — the storage is an array of one struct, which decays, so the call passes the
  address of the storage itself. x86-64 System V.
- **copied** — the storage is a struct passed indirectly, so the call passes the address of a
  fresh copy. AAPCS64 everywhere but Darwin.

All three pass one `ptr`. That is exactly why the choice has to be recorded rather than
rediscovered: nothing downstream could tell from the IR which of the three a module was built
with, and a module built with the wrong one links and runs and reads garbage.

**The emitted module states its triple.** LLVM derives the data layout from it, so a module says
what it is rather than taking on the character of whatever reads it. The driver passes the same
triple to `clang`, which is what makes naming a cross target fail honestly at the link — for want
of a sysroot — instead of quietly producing a host binary.

## What a target does not decide

**Layout.** Every target in the registry is 64-bit, and on a 64-bit target the questions `Layout`
answers have one answer: scalars are their own width and aligned to it, an address is eight
bytes, an aggregate is laid out in declaration order with each member on its own alignment and
the whole rounded up to the widest. That is C's rule and LLVM's. `Layout` therefore takes no
`Target` — and the registry refuses a 32-bit one precisely because that is where the agreement
would end.

That is worth separating from the section above, because the two are easy to run together: **where a
member sits inside an aggregate is one question and which register the aggregate travels in is
another.** Every target answers the first identically, which is why `Layout` needs no target; all
four disagree about the second, which is why the classification does. The classification is built
*on* the layout — it asks which members share an eightbyte, and that is the layout's answer.

**The storage a `va_list` occupies.** Sysl reserves the widest any target needs (32 bytes,
AAPCS64's) for all of them. The waste is a few bytes of one stack slot in a variadic function,
and the alternative — a storage type that varies per target — would put a target-dependent
*type* into the emitted text for no gain, since nothing but `va_start` and the three lowerings
above ever looks at it. What is per-target is the number of bytes *copied* out of it, which is
the target's own `va_list` and not sysl's storage for one.

## Open

- **The project config.** `sysl.conf`, per-target capability sets (`capabilities.md`'s
  `alloc` / `os` / `posix` / `threads`), and filename-axis platform selection. The registry here
  is the fixed table a config would eventually extend, and deliberately does not try to be one:
  a target's *capabilities* are exactly the part that a project has an opinion about.
- **32-bit targets.** The emitted code assumes a 64-bit address in places nothing has been asked
  to parameterize. `x86-linux` is in the registry so the refusal has something to name.
- **Cross-linking.** Building for another machine emits a correct module and then hands it to a
  `clang` that has no sysroot for it. That is the toolchain's problem to solve and sysl's to
  report clearly, not to work around.
- **Sub-architectures.** `-mcpu` / feature levels — a target today is a processor family and a
  system, and nothing yet needs finer.
