---
title: The foreign interface
summary: `extern`, link names, intrinsics, function addresses, variadics, opaque handles, interrupt handlers, and a library that carries C.
weight: 120
---

sysl has no functions built into the compiler that a program could not have written. `Option` and
`Result` are library enums, `unwrap` is a library member, `print` is library sysl reached by a
desugaring. **The one thing a program genuinely cannot write for itself is the first call out of
sysl** — into libc on a hosted target, into a driver primitive on a bare one.

`extern` is that seam, and nothing more. It is a declaration form rather than a set of names the
compiler knows, which is why the whole of this page is about *declaring* what is on the other side
and almost nothing about what is there.

## `extern` — a declaration with no body

```sysl
extern abs(n: int) -> int
extern strlen(s: *u8) -> usize
extern memcpy(dst: *u8, src: *u8, n: usize) -> *u8

print(abs(-5), abs(7))
```

```output
5 7
```

It is a function header, and **the absence of a body is the whole difference**. Everything
downstream is the ordinary path: a call is checked against the declared signature, its arity is
checked, and it lowers to an ordinary call. The result type is optional and absent means `unit`,
exactly as for a function.

Four rules follow from having no body:

- **Externs live in the one function namespace**, so an extern and a function cannot share a name.
- **An extern is never generic.** There is no body to monomorphize.
- **The escape analysis assumes the worst of it** — every argument may be kept, and the result may
  view any of them — because nothing can tell whether the foreign side held on to what it was
  handed.
- **A declaration nothing calls is not emitted at all**, so declaring more of a C library than a
  program uses costs nothing.

The namespace rule is the one that surprises, because the name a program uses is ordinarily the
symbol:

```sysl
extern abs(n: int) -> int

abs(n: int) -> int = n
```

```error
'abs' is already declared as an 'extern', which this would overload — what tells overloads of an 'extern' apart is the symbol each names, and a sysl function declares no symbol
```

Where a program wants that name for itself, the link name below is what separates the two.

`extern f() -> never` is how a program says the callee does not come back, which is what makes the
exit path of a panic ordinary sysl rather than a compiler intrinsic.

### An `extern` also declares a variable

Written `name: type`. What follows the name is what says which of the two a declaration is — a
parameter list makes it a function, a type makes it storage:

```sysl
extern optind: i32
extern environ: **u8

print(optind)
```

```output
1
```

**It is here because a C library's interface is not only its calls.** `stdout`, `stderr` and `stdin`
are variables, and half of `stdio.h` is reached through them; so are `environ`, `optarg`, `optind`,
`tzname` and `sys_errlist`. Where C also offers a getter there is a way round — `errno` is
`__error()` on Darwin, and an ordinary `extern` reaches that — but `stdout` has none that is the
*same object*, since `fdopen(1, "w")` is a different `FILE` with its own buffer and interleaves
wrongly with anything already writing to the real one. `environ` has no way round at all.

**The symbol is the platform's, and `stdout` is the example that bites.** An `extern` variable
resolves the name it is written under unless a link name says otherwise, and a C library is free to
reach a variable through a macro. Darwin's `<stdio.h>` does exactly that for the three above —
`stdout` is `#define`d to `__stdoutp` — while glibc has a real symbol called `stdout`. So a bare
`extern stdout: *FILE` links under one libc and fails under the other with an undefined `_stdout`,
and naming `__stdoutp` explicitly fixes the first at the cost of writing one implementation's private
name into a portable file.

Nothing is wrong with the form: `environ`, `optind` and `optarg` are ordinary symbols everywhere and
this is what reaches them. What the case shows is that a `#define` in front of a variable is still a
`#define`, and [a macro is one of the three things only C can reach](#a-library-may-carry-c) — so a
program wanting `stdout` portably reaches it from a line of C beside the module, which is what
`sysl.posix.tty` does to flush a terminal.

**The type is written and never inferred.** There is no initializer to infer it from, and what the
other side laid down is not something this compiler can see. Writing the wrong one is the same kind
of promise a wrong parameter list is. The one type refused is one that occupies nothing, because a
symbol is an address and a value with no representation has nothing to put one at:

```sysl
extern nothing: unit

print(1)
```

```error
'nothing' cannot be an 'extern' variable: a unit value occupies nothing, so there is no storage for the linker to resolve the name to
```

**It is a place, and a writable one** — the one respect in which it is unlike every other global the
language has. A [module-level `val`](/reference/modules/) is read-only at every depth and counts
nothing, because it owns what it names for the whole run and promises never to change it. An
`extern` variable owns nothing and promises nothing: the storage is C's, C writes it, and
`optind = 1` before a `getopt` loop is ordinary use of the interface being reached. The type rule
does not carry over either — a `val` is refused a `&T` because nothing would ever release it, and an
`extern` variable may name whatever the other side laid down, because releasing it was never this
program's job.

### Naming the symbol separately

A string before the name is what the linker resolves; the identifier after it is what the program
calls it by:

```sysl
extern "abs" magnitude(n: int) -> int
extern "abs" absolute(n: int) -> int

print(magnitude(-3), absolute(-4))
```

```output
3 4
```

Without one the two are the same, which is the common case and stays the default. The separation
exists because **a symbol's spelling belongs to whoever exported it**: it may be shaped nothing like
sysl, it may be a name the program wants for something of its own, and — the case that forced it —
a declaration in the *library* would otherwise spend that name out of every program's namespace. The
standard library renders integers and floats through `snprintf`, and a program that declares
`snprintf` itself must not collide with it.

The case where nothing else would do at all is a header macro. What C calls `stdout` is a `#define`,
and the symbol behind it is `__stdoutp` on Darwin and `stdout` elsewhere — so `extern "__stdoutp"
stdout: *u8` is the only declaration that reaches it, and a transcription of the header's spelling
would reach nothing.

The symbol must be one a linker could resolve — letters, digits, `_`, `$`, `.`:

```sysl
extern "not a symbol!" weird(n: int) -> int

print(weird(1))
```

```error
'not a symbol!' is not a symbol a linker can resolve
```

Two declarations may share one symbol under different sysl names, as above; a module declares each
symbol once. **A link name is an `extern`'s alone** — a sysl function is *defined* here, and what it
is called is its name.

Note what this does *not* do: nothing here changes what a sysl function's own symbol is. It is the
name, unmangled, so a program that defines `abs` collides with libc's whatever else it declares.

### A link name in the `llvm.` namespace names an instruction

```sysl
extern "llvm.sqrt" root(x: f64) -> f64
extern "llvm.sqrt" rootf(x: f32) -> f32

print(root(9.0), rootf(4.0))
```

```output
3 2
```

The two kinds of `extern` differ only in **who resolves them** — the linker, or the back end — and
that needs no keyword to say which, because LLVM owns that namespace: a module defining a symbol
beginning `llvm.` is invalid IR, so no library can export one and a link name there cannot mean
anything else.

**The width is derived, not written.** LLVM overloads an intrinsic on its operand type and spells
the choice in the name — `llvm.sqrt.f64`, `llvm.sqrt.f32` — so a declaration stating the whole thing
would say the width twice and let the two disagree. What is written is the base; the suffix comes
from the signature, which is why the pair above is one name at two widths.

**The set is closed**, and the reason is not caution about the feature. An intrinsic's signature
belongs to LLVM and moves between releases, and a declaration that disagrees is not a link error —
it is a verifier failure at best and a miscompile at worst, reported against generated IR rather
than against a line someone wrote. So the compiler holds the list it supports and checks each
declaration against it:

```sysl
extern "llvm.nosuchthing" bogus(x: f64) -> f64

print(bogus(1.0))
```

```error
'llvm.nosuchthing' is not an intrinsic sysl supports — it has llvm.ceil, llvm.copysign, llvm.fabs, llvm.floor, llvm.round, llvm.sqrt, llvm.trunc
```

**What it is for is not only speed.** An intrinsic that lowers to an instruction leaves no symbol
behind, so the operation needs no library at the link — which is what lets the standard module's
roots, magnitudes, sign transfers and roundings work on a **freestanding** target, where there is no
libm to ask. Where the machine has no instruction there is nothing to gain: `llvm.sin` exists and
lowers to a call to the same `sin` an ordinary `extern` names, so the transcendentals stay linked.

### What crosses the boundary

A scalar or a `*T` matches C directly. A `string` or a `&T` is a sysl layout C has no notion of, and
handing one over is the same kind of promise `*T` already is — **what crosses is the programmer's
business.** The usual move is to convert:

```sysl
import sysl.text.cstring

extern strlen(s: *u8) -> usize

var cs = cstring("hello")

print(strlen(cs.ptr))
```

```output
5
```

`cstring` copies the text into the NUL-terminated shape C reads and *owns* that copy, which is how a
language with no manual free says who frees it.

**What crosses by value is not the programmer's business.** A struct, a tuple, a view, an enum —
every aggregate — is handed over in whichever registers the machine's C convention names, which is
not the same as the registers a sysl-to-sysl call would use and is not what LLVM does with an
aggregate left to itself. So a foreign declaration is emitted in the **coerced** types that
convention asks for, and the call converts each value into and out of them. The shape a program
wrote is unchanged, nothing about it is visible in a program, and nothing about it applies to a
struct handed over behind a `*T`.

**A [vector](/reference/vectors/) is the one shape that does not cross, in either direction.** Which
register a `<4>f32` arrives in differs by target *and* by which instruction-set extensions the other
side was compiled with — the same C source built with and without `-mavx2` does not agree — so there
is no convention to emit against and sysl declines to guess at one. Guessing would not fail to link:
it would produce a call that resolves and corrupts its arguments, which is the failure a boundary
check exists to prevent.

```sysl
extern "process_lanes" process(v: <4>f32) -> unit
```

```error
how a vector reaches a C function differs by target
```

Pass the lanes through memory instead — a `*f32` and a count, which is what C's own SIMD-taking
functions take and what has one meaning on both sides.


**A scalar narrower than a register is not quite free either.** A `u8` is a byte to both languages, so
there is nothing to coerce — but it travels in a register a whole word wide, and most conventions
require whoever hands it over to widen it to fill one first. Sysl writes that widening where the
convention asks for it: on an argument at the call, and on the result of every function it defines,
so that a `bool` answered to a C caller is a `bool` rather than one bit beside thirty-one undefined
ones. Which values are widened, and whether by sign or by zero, is the *target's* answer rather than
the language's — AArch64 outside Darwin widens nothing at all, Windows widens only `bool`, and
RISC-V 64 widens a 32-bit value even when it is unsigned. As with the coercion above, none of it is
visible in a program; it is worth stating because it is the part of the boundary a reader is likeliest
to assume away.

**`extern` implies C's convention and says nothing about any other.** The one case that needs
something else is a function the *processor* enters rather than a caller, and that is a property of
a definition rather than of a foreign declaration — see `interrupt`
below.

### Several `extern`s may share a name

**Two `extern`s of one name are two functions exactly when they name two symbols.** A C library's
naming is not sysl's, and a family C spells `_solid`/`_shaded`/`_blended` is one operation with an
option — which a binding may say directly, rather than inventing a sysl name per C symbol:

```sysl
extern "strlen" size(s: *u8) -> usize
extern "strnlen" size(s: *u8, cap: usize) -> usize
```

Which one a call means is decided by its arguments, exactly as for any other
[overloaded name](/reference/declarations/#overloading). Two declarations naming the **same** symbol
are refused: that is one C function claimed at two signatures, and the symbol is what gets emitted,
so both calls would reach the same code with different arguments. Where that is genuinely wanted — a
`void *` interface used at several types — it is written where a reader can see it, by taking the
address and casting it.

An `extern` and a sysl function do not overload each other, in either order: what tells overloads of
an `extern` apart is the symbol each names, and a sysl function declares none.

## `@export` — a definition C can call

`@export` is `extern` read the other way. An `extern` names a symbol the linker has and states the
signature the other side published; an `@export` publishes a symbol and states the signature C may
call it at. They are spelled alike because they are one mechanism pointing in two directions:

```sysl
extern exit(code: int) -> never

@export
add(a: i32, b: i32) -> i32 = a + b
```

A sysl definition ordinarily carries its module path into its symbol, so two modules may each declare
an `init` without colliding. `@export` publishes a bare, unmangled name beside it — one a C
declaration can spell and a C linker can resolve.

### Naming the symbol

`@export("mylib_add")` publishes it under that name instead, which is the same rename `extern` offers
on the importing side:

```sysl
module mylib

@export("mylib_add")
add(a: i32, b: i32) -> i32 = a + b
```

**This is the form a real C API wants rather than a convenience.** A C library's symbols share a
prefix so that linking two of them is not a coin toss, and the sysl side has a module path doing that
job already. `add` is the name to write inside sysl and `mylib_add` is the name to publish; requiring
the function to be *called* `mylib_add` everywhere inside would be spelling the module path twice.
**A sysl caller is unaffected** — it goes on naming `mylib.add`, and reaches the definition rather
than the published symbol.

`@export` implies C's convention and says nothing about any other, which is `extern`'s rule read the
other way.

### The published symbol is an entry, not a renamed definition

**A rename is not a convention**, and the difference is the whole of what `@export` is for. The
exported symbol is a function of its own: its signature is what the machine's C convention says, and
it reassembles each argument into the shape sysl's own lowering expects before calling the
definition. The definition keeps its mangled name and its own lowering.

None of that is anything to write, and it is worth knowing for three reasons:

- **an aggregate may cross**, which is the type rule below;
- **`&f` on an exported function is that entry's address**, so a C library may be handed the callback
  it asks for — which is what *A function's address* below turns on;
- **a sysl caller pays nothing for it**, since the conversion is only on the path C takes.

It is the foreign *call* path read backwards: a call out to C classifies each argument by the
convention and puts it in the registers named, and an entry in classifies the same way and reads it
back. One classifier answers both directions, so what a binding calls and what it exports cannot
disagree about the same struct.

### What an exported function may be

The boundary is a **facade**: one file whose job is the export surface, where the signatures are
written to be C-shaped on purpose. That is what makes the list below cost so little — every refusal
fires inside a file somebody wrote for this, where the restriction is the point rather than a
surprise.

| refused | because |
|---|---|
| a **generic** | an exported symbol is one function at one signature, so there is no way to say which instantiation the linker holds |
| a **member** | C has no receiver to hand it. The grammar refuses this before any rule here is reached, and says so in as many words: a member takes no annotation at all ([attributes](/reference/attributes/)), so `@test` and `@pure` are as unavailable on a method |
| a **`private`** definition | `private` gives the symbol internal linkage, which promises every caller is inside the module; an export promises the opposite |
| a **`@ghost`** | it is erased before there is a symbol at all |
| a **`@test`** | only `sysl test` builds one, and an export has to be in the artifact a C project links |
| a **variadic** | what a C caller promotes into the tail is decided by the prototype it compiled against, not by this declaration. Take a `va_list` parameter, which says the same thing and is what C's own `v` variants do |
| a symbol that is not a **C identifier** | there would be nothing a C declaration could spell |

**The types are the interesting rule, and it asks what C can *declare*.** A scalar, a `*T` and a
function pointer cross as themselves. So does a **struct** built out of those, and a **simple enum**,
which is its underlying integer and nothing else — the exported symbol is an entry lowered under the
machine's C convention, so an aggregate is handed over where C looks for it.

What is refused is what C has no declaration for whatever convention is applied: a slice or a
`string`, which is two words with a length in the second; a `&T` or a `weak`, which is a counted box
whose header C would have to know the layout of; a trait object, which is a value and a method table
together; a data enum, which is a tag beside a union sysl laid out rather than the shape a C union
has; and a bare array as a parameter or result, which C decays to a pointer and has no by-value
prototype for.

```sysl
module mylib

@export
sum(xs: []i32) -> i32 = 0
```

```error
'xs' of the exported 'mylib.sum' is []int, which C has no way to spell — an exported function takes an integer, a float, a 'bool', a 'char', a pointer, a function pointer, a simple enum, or a struct built out of those. A slice is an address and a length, so C takes them as two parameters — a pointer and a 'usize' — which is the shape its own string and buffer functions already have
```

**An aggregate is asked about its fields**, which is what keeps those two lists apart: a struct of
scalars is a struct C declares, and a struct with a `&T` in it is a counted box with a coat on. The
refusal names the *field*, since the declaration being read does not mention it:

```sysl
module mylib

struct Node
    x: i32

struct P
    x: i32
    n: &Node

@export
f(p: P) -> i32 = p.x
```

```error
'n' of mylib.P is &mylib.Node, which is what C has no declaration for — the aggregate around it is fine
```

Recurring is also why nothing here says who owns what: every type reaching the boundary is plain
data, so no count crosses it in either direction.

Every refusal names the shape to write instead, because there always is one — a slice becomes the
pointer and length C's own buffer functions already take, an array becomes a struct holding it. That
is what makes the boundary writable rather than merely restricted.

### Module storage, and who fills it

Module storage is filled before a program's own statements run, and **a C project supplies its own
`main`** — so nothing an archive holds is going to run on the way in. That is a real problem and it
has a real answer: the archive registers a **constructor** on the platform's own pre-`main` list, and
the storage is filled before any exported function can be called.

```sysl build=c
module mylib

import sysl.buf.{Buf, buf}

val squares: Buf[i32] = fill()

fill() -> Buf[i32]
    var b: Buf[i32] = buf()

    for i in 1..<4
        b.push(i32(i) * i32(i))

    b

@export("mylib_count")
count() -> i32 = i32(squares.len())

@export("mylib_at")
at(i: i32) -> i32 = squares[usize(i)]
```

A C project linking that archive sees `3`, `1`, `4`, `9`. Nothing in the sysl says where the filling
happens, and nothing needs to — the constructor is `@llvm.global_ctors`, which becomes `.init_array`
on ELF, `__mod_init_func` on Mach-O and `.CRT$XCU` on COFF. A `.so` loaded with `dlopen` runs it at
load, which is what makes this work on Android.

**Which build fills it is the whole rule, and there are three of them:**

| build | who fills the module storage |
|---|---|
| `sysl build`, `sysl test` | its own entry point, before the first statement |
| `sysl build-lib` | the **program** that links the artifact, which has an entry point |
| `sysl build-c` | a constructor the platform runs before the C project's `main` |

**A `val` whose initializer is constant data is not in the question at all**, because nothing runs to
fill it — the constant is written straight into the object file, which is the rule C already has for
a static-storage initializer:

```sysl
module mylib

val start: i32 = 7

@export
begin() -> i32 = start
```

A `const` has no storage at all and never arises.

#### The one case with nowhere to fill

**A freestanding target has no loader.** Nothing walks `.init_array` on bare metal unless the image's
own start-up calls `__libc_init_array` — newlib's does, and a hand-written reset vector may not — so
a constructor emitted there would be a function nobody calls, and the storage would read whatever the
image left. That is a silent wrong answer rather than a link error, so it is refused instead, and the
walk is transitive:

```sysl build=c target=thumbv7em-freestanding
module mylib

counter() -> i32 = 7

val start: i32 = counter()

@export
begin() -> i32 = start
```

```error
'mylib.begin' is exported and reaches 'mylib.start', which is module storage an initializer fills before the program's own statements run. This artifact has no entry point to fill it in, and 'thumbv7em-freestanding' is freestanding — there is no loader to run a constructor either, so the function would read whatever the image left. Build for a hosted target, where the storage is filled before any export can run, or make the initializer constant data, which is laid straight into the object file
```

`build=c` on that block is not decoration: the refusal is a property of the artifact a **C project**
links, and the same program built for the same machine as an ordinary program compiles, because a
program has an entry point to fill the storage in.

### What the compiler hands the C project

`sysl build-c` writes a **static archive** and a **C header** beside it:

```text
$ sysl build-c mylib -o libmylib.a
wrote libmylib.a
wrote libmylib.a.h
```

The header is a translation of the exported signatures and holds no decisions of its own:

```c
#include <stdbool.h>
#include <stdint.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

int32_t mylib_add(int32_t a, int32_t b);

#ifdef __cplusplus
}
#endif
```

It uses `<stdint.h>`'s fixed-width names because sysl's integers say what they *are* where C's say
what they are *at least*: an `i32` is `int32_t`, and writing it `int` would be right on every machine
anyone is likely to use and wrong as a claim. A `char` is a Unicode scalar value and becomes
`uint32_t`, never C's `char`, which would be wrong by a factor of four. The header assumes **C99, or
any C++**, which is what those three includes already needed.

The one fact a type name cannot carry across is divergence, since `never` and `unit` both spell as
`void`. A function returning `never` is therefore annotated:

```c
#ifndef SYSL_NORETURN
#if defined(__cplusplus) && __cplusplus >= 201103L
#define SYSL_NORETURN [[noreturn]]
#elif defined(__STDC_VERSION__) && __STDC_VERSION__ >= 201112L
#define SYSL_NORETURN _Noreturn
#else
#define SYSL_NORETURN
#endif
#endif

SYSL_NORETURN void mylib_spin(void);
```

A macro rather than the keyword, because the header serves both languages and `_Noreturn` is not
valid C++; an older compiler gets the empty definition, which is a weaker declaration rather than one
it refuses. The block appears only in a header that has something diverging in it. What it buys the
caller is real: code after the call is dead, and a path ending in it needs no return value.

### Naming a struct in the header

A struct an exported signature reaches is **defined** in the header as well as named, since a
prototype mentioning an `Id` is useless to a consumer that has not been told what one is. Left alone
its name is derived from the module path — `mylib_Id` here, and `sh_sysl_box2d_c_Id` in a package —
which makes it the one name in the file nobody chose. `@export` above the struct chooses it, exactly
as it names a function's symbol:

```sysl
module mylib

@export("mylib_vec2")
struct Vec2
    x: i32
    y: i32

@export("mylib_add_vec")
add(a: Vec2, b: Vec2) -> Vec2 = Vec2(a.x + b.x, a.y + b.y)

val v = add(Vec2(1, 2), Vec2(10, 20))

print(v.x, v.y)
```

```output
11 22
```

A sysl caller is unaffected by either name, exactly as it is by a function's symbol — it goes on
writing `Vec2` and `add`. What the two attributes decide is what the header says:

```c
typedef struct {
	int32_t x;
	int32_t y;
} mylib_vec2;

mylib_vec2 mylib_add_vec(mylib_vec2 a, mylib_vec2 b);
```

Written bare, `@export` gives the `typedef` the **declared** name — `Vec2` — which is the reading it
already has on a function. It composes with [`@packed` and `@align(n)`](/reference/attributes/),
which are three facts about one struct, and it is what a binding mirroring a C library wants: that
library's own type names rather than tidier derived ones.

**The derived name was buying uniqueness and a chosen one is a claim**, so the claim is checked. Two
things in one header answering to one name are refused — and **a function's symbol counts**, because
at file scope C puts a `typedef` name and a function name in one namespace where sysl has two:

```sysl
module mylib

@export("handle")
struct H
    x: i32

@export("handle")
make(n: i32) -> H = H(n)
```

```error
'handle' is the C name of the function 'mylib.make' and the type 'mylib.H' — a header declares both in one namespace
```

A **generic** struct is refused for the reason a generic function is, one step shorter: every
instantiation is a struct of its own, so one written name would be claimed by all of them at once.

```sysl
module mylib

@export("Box")
struct Box[T]
    v: T
```

```error
a header names one type at one shape, so 'mylib.Box' cannot be generic
```

A **`private`** struct is refused too, and not for the reason a private *definition* is — a `typedef`
has no linkage to contradict. The visibility rule gets there first: a public declaration may not name
a type less visible than itself and an export is public, so a private struct appears in no signature
a header carries and there is no name in one for it to take.

```sysl
module mylib

@export("mylib_id")
private struct Id
    index1: i32
```

```error
'mylib.Id' is private, so no exported function may name it — an export is public
```

**The chosen name reaches the header and nothing else.** The emitted aggregate keeps its mangled
name, and C links nothing on a type name — which is what makes this a spelling an author may decide
rather than a fact anything else depends on.

### Naming a type in the header

A [`type`](/reference/declarations/#type-declarations) is a second name for something that already
exists, and left alone the header writes what it stands for — a `Handle` over `u64` is `uint64_t`
there, and the C author sees a number where the sysl author wrote a handle. `@export` above the `type`
declares it as a `typedef` and spells it by that name wherever the type appears — a parameter, a
result, a struct's field, a function pointer's own signature:

```sysl build=c
module slate

@export("slate_value")
type Handle = u64

@export("slate_vm")
opaque struct Vm
    base: u64

@export("slate_int")
int_of(vm: *Vm, n: i64) -> Handle = vm.base + u64(n)
```

```c
typedef struct slate_vm slate_vm;

typedef uint64_t slate_value;

slate_value slate_int(slate_vm * vm, int64_t n);
```

Written bare, `@export` gives the `typedef` the declared name, as it does a struct's, and the name is
checked by the same rules — a C identifier, public, and one claimant per name in the header. A `type`
that is not exported keeps the old spelling, and so does a position written with the base itself: a
`u64` beside a `Handle` is still `uint64_t`, and two exported names over one base are two `typedef`s.
A `type` narrowed with `new`, `within` or `where` is exported the same way, as its base — C has no way
to state the bound, and the header says what the value is made of.

**A sysl caller is unaffected**, exactly as it is by a struct's C name: the exported `Handle` flows to
and from `u64` with no conversion, as it did before it was exported.

**What it may stand for is a scalar or a pointer**, which is what C writes a `typedef` of without a
layout — an integer, a float, `bool`, `char`, a pointer, or a function pointer, whose declarator goes
inside the `typedef` (`typedef int32_t (*on_event)(int32_t);`). A struct has a name of its own in the
header already, and a slice or a counted reference has no C spelling at all, so each is refused with
what to write instead:

```sysl
module mylib

struct Point
    x: i32

@export("point_t")
type P = Point
```

```error
'mylib.P' is exported to a C header as mylib.Point, which C has no way to spell as a typedef — a header names an integer, a float, 'bool', 'char' or a pointer this way
```

From there it is an ordinary C build:

```text
$ clang main.c libmylib.a -o app
```

**The archive is self-contained.** Whatever of the standard library the module reaches is compiled
into it, because a `.syslib` is not something a C link line can be handed — an archive referring to
one would fail at that link naming a `sysl$` symbol its author has no way to place. So `--std-lib` is
refused here and `--no-std-lib` asks for what already happens. The cost, which is accepted: two
`build-c` archives linked into one program each carry the part of the library they reach. Its members
are native objects whatever the manifest's `lto` key says — bitcode would link only under
`clang -fuse-ld=lld`, never under GNU ld or gcc — unless `--lto thin` or `full` on the `build-c`
command line asks for a bitcode archive, for a host that links with clang and lld and wants its link
to optimize across the boundary; `-O` applies as it does anywhere.

**What the archive does not hold is what the sysl side's own libraries supply** — `libm`, whatever
`@link` named, and whatever the packages in the build require under `pkg_config` — and `build-c`
says which those are rather than leaving them to be found at that link. Those are libraries the
author chose and can hand to a linker, which is exactly the distinction the standard module fails.
The two lists are said separately because they are answered differently: a `@link` name goes on the
line as `-l<name>`, while a `pkg_config` module is named so the C project can ask *its* `pkg-config`
for the flags — `pkg-config --libs libbrotlienc` — since a package binds an installed library through
its manifest and may say `@link` nowhere.

`sysl emit-header` prints the same declarations without building anything, for a project that
generates its headers as a build step.

### What a dependency's module contributes

**A module a dependency supplied contributes to your build only where your program reaches that
module.** In your own tree the four attributes below mean what they say and nothing qualifies them:
each is a root of the reachability walk, which is what lets a `build-c` compilation — with no entry
point at all — keep anything.

A dependency is different because a package's source root is compiled **whole** rather than by what
you import, so every module of every `--lib` root and every fetched package is in the compilation
whether or not you named it. For an ordinary declaration that costs nothing, since pruning drops what
no body reaches. **These four are the declarations no body reaches**, so without this rule an
unimported module's contribution landed in your build anyway:

| in a module you never reach | what it used to cost you |
|---|---|
| an `@export` | its symbol in your archive |
| an `interrupt` handler | a handler in your vector table |
| a [`@section`](/reference/attributes/) definition | bytes in your image, marked so that nothing removes them |
| a [destructor](/reference/memory/) | a function nothing can call |

**The rule is about where the declaration came from rather than about which attribute it carries**,
and that is what makes it hold. A kind left unconditional would keep whatever *else* the same
function carries — an export that is also placed, or that is also a handler, would survive for the
second reason and land its symbol anyway.

What that cost was a package **carrying its own program**. A binding whose tests have to run on real
hardware writes them as an application with an `@export("main")`, and inside the package that handed
every consumer a second `main`:

```text
$ nm -g probe.a
0000000000000008 T _main              <- yours
0000000000000000 T _main              <- the package's test application
```

Reaching is asked of the module graph, so an `import` counts as readily as a call, and it follows the
graph out — a package you reach through another package is reached. It is deliberately coarser than
the walk over function bodies: a module holding nothing but an exported C entry point has no function
anything calls, and a rule asking whether you *called* something there would drop exactly the case an
export exists for.

**If a package wants a symbol, a handler or a placed definition published regardless, it puts it in a
module its consumers import** — with the one exception the next section is about. Each is a claim
about what *your* image contains, and an image that never reaches the module has not asked for it.
That reading is at its strongest where the attributes matter most: a vector table slot and a
RAM-resident `.ramfunc` region are the scarcest things on the parts they exist for, so gaining every
unimported module's silently is the worse way to be wrong.

## A module may supply another module's `extern`

The rule above has one exception, and it is not a relaxation of it: **an `@export` whose symbol
answers an `extern` your program actually calls is kept, whether or not you reach its module.**

That is the accounting a linker already applies to an archive member, arrived at from the other side.
A definition is taken because something is going to ask for that name and left out otherwise — so
nothing is published because a package felt like publishing it, which is what the previous section
refuses.

### What it is for is a seam

A module declares a symbol and defines nothing:

```sysl
module clock

private extern "app_wall_us" c_wall_us() -> long

now() -> long = c_wall_us()
```

Whoever is linked defines it. A program importing `clock` calls `now()` and never names the supplier
— which is the point, since on an embedded target naming one is naming the chip:

```sysl
import clock.now

print(now() > 0)
```

The supplier is an ordinary `@export` in a module of its own, and the program does not import it:

```sysl
module rtc

@export("app_wall_us")
wall_us() -> long = 1700000000000000
```

**Those three blocks are three files and are shown rather than run**, which every multi-module example
on this page is: a block here is compiled as a whole program, so a seam — which needs a declaring
module, a supplier and a consumer that names only the first — has no single-file form. What *is* run
on this site is the standard library's own instance of it, on the
[time page](/library/time/#reading-a-clock-sysl-posix-time).

Without this rule that definition is pruned — nothing imports `rtc`, so by the previous section it
contributes nothing — and the build then fails at the link, naming a symbol no line of the program
contains. Which is the exact friction the seam exists to remove.

### It is narrow, and the narrowness is what keeps the previous section true

Three things it does **not** do:

- **It does not keep an export nothing asks for.** A package carrying its own test application
  exports `main`, and nothing anywhere declares `extern "main"` — so no symbol the program calls
  answers to it, and the rule never fires. That is the collision the previous section exists to
  prevent, still prevented.
- **It does not keep a supplier the program has no use for.** If your program never calls `now()`,
  the `extern` is not live, and `rtc` contributes nothing.
- **It does not choose between two suppliers.** Two modules exporting one symbol is refused, naming
  both — you get exactly one clock, and finding out which at the link is not a thing anybody should
  have to do.

**A supplier's own body is followed**, so a supplier that itself calls an `extern` some third module
answers pulls that one in as well.

### Where the declaration is compiled is where the seam is decided

The standard library ships as a prebuilt artifact on a hosted target, and it was compiled whole — so
a module in it that both declares a seam and supplies it has already been bound, and a package
supplying the same symbol later cannot displace it. `sysl.time`'s clock is the worked example: on a
host, [`sysl.posix.time`](/library/time/#reading-a-clock-sysl-posix-time) answers it out of the
artifact and you get the host clock without asking.

Where a board is served there is no such artifact: `sysl build-c` and every freestanding target
compile the library from source into your program, so the declaration and the supplier meet in one
compilation and the package's definition is the one that answers. **Overriding a seam is therefore
something a board does and not something a host does** — a host program that wants a different clock
is not asking for a seam, it is asking to call something else.

## `@link` — which library resolves the externs

`@link("z")` in a file's header names a library the linker must be given, and it sits beside the
`extern`s it supports because that is the only place that knows. An `extern` states the symbol it
wants and never where the symbol lives; a binding to `libpng` is written by whoever writes the
module, and the driver cannot carry a list of libraries it has never heard of.

```sysl
module image.png
@link("png")
@link("z")

extern "png_create_read_struct" create(ver: *u8, err: *u8, fn: *u8) -> *u8
```

**A directive names a library, and never a flag.** That is the whole of the design. Where a library
*lives* is a property of the machine being built for: the mathematics is a file of its own on ELF,
part of `libSystem` on Darwin, inside the CRT on Windows, and absent from a freestanding target that
has no libc for it to be in. A directive spelling `-lm` would be right on one of those and wrong on
the other three — and the author could not be told so by any compiler running on the machine that
wrote it, because the link that fails is somewhere else. So the file names `m`, and the driver
decides what that becomes:

| the target | what a name becomes | why |
|---|---|---|
| has the library separately | `-lname` | the ordinary case, and what every unrecognized name gets |
| already links what holds it | nothing | Darwin's `libSystem`, Windows' CRT — the driver passes those unasked |
| does not have it at all | nothing | a freestanding build has no libc, so nothing can be passed for one |

The last two both put nothing on the command line and are still written apart, because they are
different facts and a target added to the registry has to answer them separately. A freestanding
program that then calls `sqrt` fails at the link naming `sqrt`, which is the honest report: what is
missing is the function, and no `-l` would have supplied it.

**The set the compiler knows is deliberately small** — the C runtime and the mathematics, the two
whose placement actually differs. An unrecognized name is passed straight through rather than
guessed about. Being wrong in that direction produces a link error naming the *library*; being wrong
in the other produces one naming a *function*, on a platform the author does not have.

Four more rules:

- **The requirement travels in the artifact.** The clauses are part of the tree a `.syslib` carries,
  so a program depending on a prebuilt library learns to pass `-lz` without reading that library's
  source. Leaving them out would mean a binding that works from source and stops working the moment
  it ships — the worst available shape, since the build that breaks is one its author never ran.
- **A module's requirement is the union of its files', and its files are not held to agreeing.**
  This is where the directive differs from a [capability clause](/reference/modules/), which it is
  otherwise shaped like: a capability describes what the whole module may do, so files that
  disagreed would be describing different modules, where a link requirement describes what *one
  file's* externs need.
- **Order is kept rather than sorted.** A static archive is scanned once, left to right, and a
  member is pulled in only to resolve a symbol already undefined — so a library that calls into
  another has to come first: `-lpng -lz`, never the reverse. Sorting would decide it by spelling,
  which is right by accident for those two and wrong for the next pair.
- **`@link` is an annotation, not grammar** — so `link` is an ordinary identifier and a program may
  still declare a function or a field called it. That is the general rule for everything the file
  header carries: an annotation's name is read as an identifier, which is what keeps `alloc`, `no`,
  `requires` and `link` out of the reserved list. See [attributes](/reference/attributes/).

### Where the library is, where its headers are, and what they are configured with

`@link` says *which* library. Where it lives on a particular machine is not the module's to know, and
neither is what the surrounding project builds its headers with, so those are three flags on the
command line rather than three more directives:

| | |
|---|---|
| `--include-path <dir>` | where to look for a header the C beside a module includes |
| `--include-path <name>=<dir>` | the same, and it answers the header requirement a package declared under that name |
| `-D NAME` or `-D NAME=value` | a macro that C is compiled with |
| `--link-path <dir>` | where to look for the library `@link` named — at the link, **and at run time** |

**`--link-path` answers two questions rather than one**, and it is worth saying because the second is
invisible until a program has already linked. A directory named there is where the linker looks *now*
— and, on a hosted target, where the **loader** looks when the program is run, since `-Wl,-rpath` is
emitted beside the `-L`. Without that, a library whose install name is `@rpath/…` links cleanly and
then dies at startup with `Library not loaded`, naming a library sitting exactly where the flag said
it was. A directory a `pkg-config` probe answered with is left alone, because a `.pc` file supplies
its own run-time path where the library wants one; and a freestanding target gets none, having no
loader to read it.

They fail in that order, and the first two fail *before* anything reaches a linker. A binding to a
library a package manager put outside the toolchain's prefix needs the paths; a module joining an
existing C project — an SDK, a firmware build — needs the macros as well, because a header found is
not a header that compiles. pico-sdk's `pico/cyw43_arch.h` `#error`s on a build that has not said
which architecture variant it means, which is the shape to expect rather than a curiosity.

**Nothing is guessed at.** sysl does not add `/opt/homebrew/lib` and does not invent a macro: a
compiler ruling on where a platform keeps its libraries, or on how a project configures its headers,
would be wrong on a machine its author cannot reach, and the cost of being wrong is a build that
fails somewhere else. A build system that already knows these has them — from CMake, the target's
`INCLUDE_DIRECTORIES` and `COMPILE_DEFINITIONS`.

**A package may say which headers it needs, though never where they are.** A `--include-path` written
as `<name>=<dir>` answers a requirement the package declared under that name, so a build missing one
stops before clang runs and names the package rather than a header the reader has never heard of. The
declaration goes in `package.hocon` — see
[packages](/reference/packages/#headers-a-package-needs-and-does-not-carry).

## A function's address — `*extern(A, B) -> R`

[`Fn`](/reference/expressions/) is sysl's answer to "what is the type of a callable", and it is the
right one for sysl: a bound where the callable is passed down, a boxed object where it is kept, and
in both cases something with an environment beside it. **C has no notion of an environment.** What a
C interface means by a function pointer is one word holding the address of code.

That matters for more than a corner: `qsort` and `bsearch` take a comparison, `signal` and
`sigaction` take a handler, `atexit` takes a hook, `pthread_create` takes a thread body, `scandir`
takes a filter, and every library with a `_set_callback` in it takes one of these.

```sysl
extern qsort(base: *u8, n: usize, size: usize, cmp: *extern(*u8, *u8) -> i32)

compare(a: *u8, b: *u8) -> i32
    var pa: *i32 = ptr_cast(a)
    var pb: *i32 = ptr_cast(b)

    *pa - *pb

var xs = [30, 10, 20]

qsort(ptr_cast(&xs[0]), 3, 4, &compare)

print(xs[0], xs[1], xs[2])
```

```output
10 20 30
```

**`&f` is the address**, and the `&` is the same one the [memory model](/reference/memory/) gives
every other address. A bare `f` keeps its ordinary meaning — the capture-free closure — because a
spelling that meant a sysl callable in one slot and a C address in another would be choosing
silently between two representations that share nothing.

**The function may be named through its module**, and `&f[T]` may be qualified too. Under
`import shapes`, `&shapes.less` is the same address `&less` gives under `import shapes.less` — a name
means the *declaration* rather than a spelling of it, which is the rule a
[constant](/reference/declarations/) follows as well. A **local binding shadows a module name**, so
where the head of the path is bound to a value the chain is an ordinary field read and the `&`
addresses that field.

**It is its own type rather than a mode over the call trait.** `*Fn(A) -> R` was already taken, and
by the right thing: an unowned trait object over a callable, two words, a method table beside the
value. Spelling both the same would put a fat pointer where C reads one word, and the mistake would
be invisible. So the three are three:

| written | what it is | width |
|---|---|---|
| `A -> R` at a parameter | a bound over `Fn`, monomorphized and inlined | nothing |
| `&Fn(A) -> R` | a heap-boxed callable, counted | two words |
| `*extern(A) -> R` | the address of code compiled to C's convention | one word |

It is also **not `*T` of anything**. A raw pointer addresses a *value* — one that can be read
through, written through, and measured — and there is no value at the end of this one, so every
operation `*T` carries would have needed an exception. What an address of code can do is the one
thing it is for: be called, and be handed to whoever asked for it.

Three consequences:

- **A call through one goes out under C's convention**, because that is what the type said was at
  the other end. Nothing checks that the signature is the one the code at that address was compiled
  with; that is the promise the `*` announces, and it is the same promise every raw pointer makes.
- **`ptr_cast` reaches between an address of code and an address of bytes**, which is how a `*u8`
  from `dlsym` becomes callable and how one goes back to a C interface that stores callbacks as
  `void *`.
- **`null` is a `*extern`**, since "there is no callback, use the default" is a state several C
  interfaces have, and two compare by address so a program can ask whether one is installed:

```sysl
compare(a: *u8, b: *u8) -> i32 = 0

var installed: *extern(*u8, *u8) -> i32 = null

print(installed == null, &compare == null)
```

```output
true false
```

### A generic function's address — the instantiation is read off the type

A generic function is a body per set of type arguments, so an address needs them settled. **The
expected type settles them**, by the same unification a call site uses on its arguments:

```sysl
extern qsort(base: *u8, n: usize, size: usize, cmp: *extern(*u8, *u8) -> i32)

ascending[T](a: *T, b: *T) -> i32
    var pa: *i32 = ptr_cast(a)
    var pb: *i32 = ptr_cast(b)
    *pa - *pb

var xs = [30, 10, 20]

qsort(ptr_cast(&xs[0]), 3, 4, &ascending)
print(xs[0], xs[1], xs[2])
```

```output
10 20 30
```

A comparison written once over `*T` is now usable at every element type, where before a program
needed a concrete copy of it per type.

### The `void *userdata` pattern — the arguments are written

A C interface that calls back pairs the pointer with an untyped `void *`, so a trampoline for one has
the signature C fixed — `(*u8, Event) -> bool`. The state type appears nowhere in it, so nothing in
the expected type can settle it, and no annotation written anywhere else could supply it either. The
arguments are written where the address is taken, and **this is the one position in the language that
takes them**:

```sysl
extern qsort(base: *u8, n: usize, size: usize, cmp: *extern(*u8, *u8) -> i32)

compare[T: Ord](a: *u8, b: *u8) -> i32
    var x: *T = ptr_cast(a)
    var y: *T = ptr_cast(b)

    if *x < *y then -1 else if *y < *x then 1 else 0

var xs: [3]i32 = [30, 10, 20]

qsort(ptr_cast(&xs[0]), 3, 4, &compare[i32])
print(xs[0], xs[1], xs[2])
```

```output
10 20 30
```

Recovering the state is still a `ptr_cast` from `*u8`, which is a promise rather than a deduction —
that is C's shape and nothing here changes it. What is no longer needed is the shape the *language*
was forcing: a trampoline written over `*T` because it could not be written over `*u8`, a second
`ptr_cast` of the function pointer, and a `val` whose only job was to be somewhere to put the type.
Every one of those is gone, and the program above is the whole binding.

More than one argument is written the same way, `&f[A, B]`, and a value parameter takes its place in
the list like any other.

**`&f[T]` and `&xs[i]` are the same shape, and the analyzer is what separates them**: the name has to
resolve to a function declaration with no local shadowing it. A local shadowing the name keeps the
ordinary indexed reading, so a subscript is never re-read as a feature its author did not reach for.

What the brackets can hold is the *expression* grammar's reading of a type — a name, a qualified
name, a name applied to arguments, `*T`, `&T`, a tuple, and an integer for a value parameter. A
slice, a `weak`, a `volatile` and a callable have no spelling there; the annotated `val` reaches
every one of them, and the diagnostic says so.

### What has no address, and why

Each of these is refused because the address would not be an address of what its type says, and
nothing downstream could notice.

**A generic function with nothing to say which copy** — the arguments have to come from somewhere,
and a bare `var` says nothing:

```sysl
id[T](x: T) -> T = x

var a = &id

print(1)
```

```error
nothing here says what they are
```

**A type parameter the signature never mentions, left to be inferred** — nothing in the expected type
can settle it, and the message names the form that can:

```sysl
tagged[T](n: i32) -> i32 = n

val f: *extern(i32) -> i32 = &tagged

print(1)
```

```error
does not say what 'T' should be
```

Written out, `&tagged[i32]`, it is an ordinary address — that is the case the written form exists
for.

**A variadic function** — C reads a tail relative to the last named argument, and a `*extern` states
the arguments a call passes:

```sysl
varia(n: int, ...) -> int = n

var b = &varia

print(1)
```

```error
'varia' is variadic, and a '*extern' fixes the arguments a call passes — a tail has no width a signature could state, so a variadic function is reached by calling it
```

**A nested function** — its environment is the frame it was declared in, and what would have to
travel beside the address is that frame. **A closure** is the same reason with the name taken off. A
**`@test` function** is dropped by every build but `sysl test`, so its address would be of a
definition the program does not have:

```sysl
outer() -> int
    inner(x: int) -> int = x + 1

    var q = &inner

    inner(1)

print(outer())
```

```error
'inner' is a nested function, so it has no address to take — what would have to travel beside the address is the frame it reads, and a '*extern' is one word. A top-level function is what has an address
```

**An intrinsic** — there is no body for an address to name:

```sysl
extern "llvm.sqrt" root(x: f64) -> f64

var p = &root

print(1)
```

```error
'root' is an intrinsic, which the back end lowers to an instruction rather than a function anything calls — there is no body for an address to name. A sysl function that calls it is what has one
```

**A plain sysl function whose signature carries an aggregate** — a struct, a tuple, a data enum, a
view, a `string`:

```sysl
struct Pair
    a: int
    b: int

agg(p: Pair) -> int = p.a

var c = &agg

print(1)
```

```error
the 1st parameter of 'agg' is Pair, an aggregate, and an aggregate crosses to C in whichever registers that machine's convention names rather than the ones a sysl call uses
```

The test is made by **shape** rather than by asking the target's classification, so a program
accepted for one machine is accepted for every machine.

### Two kinds of function are past the aggregate question

They were once refused by it, and that cost a binding every callback a C library asks it to register
— those signatures are written in aggregates almost without exception.

**An `extern` is C.** sysl neither compiled it nor chose its convention, and its type is what the
declaration transcribed from the header, so there is no lowering here to be wrong about:

```sysl
struct Pair
    a: int
    b: int

extern "c_sum" c_sum(p: Pair) -> int
extern "c_take" c_take(f: *extern(Pair) -> int) -> int

print(c_take(&c_sum))
```

**A function carrying `@export` has a C-convention entry**, and its address is that entry's. So the
answer to the refusal above is to mark the function rather than to hand-write a wrapper around it:

```sysl
struct Pair
    a: int
    b: int

@export("c_sum")
sum(p: Pair) -> int = p.a + p.b

extern "c_take" c_take(f: *extern(Pair) -> int) -> int

print(c_take(&sum))
```

A plain sysl function is what is left, and there the refusal stands: it has no C-convention entry to
point at, and an address that is quietly wrong is worse than no address.

### Naming a signature once

A real binding mentions one callback several times — `signal` takes a handler and returns the
previous one, so its declaration says the same tokens twice, and every function that passes one
along says them again. A [type alias](/reference/declarations/#type-declarations) is what says it
once:

```sysl
type Comparison = *extern(*u8, *u8) -> i32

compare(a: *u8, b: *u8) -> i32 = i32(a[0]) - i32(b[0])

call(f: Comparison, a: *u8, b: *u8) -> i32 = f(a, b)

var xs: [2]u8 = [9, 4]

print(call(&compare, &xs[0], &xs[1]))
```

```output
5
```

The alias declares no type: `Comparison` and the signature written out are one type, so a value
crosses between the two spellings with nothing emitted and no conversion to write. That is the
difference between it and a [constrained subtype](/reference/errors/), which `type` also
introduces — a subtype's base must be a scalar, and an alias's base may be anything at all,
including the pointer and struct types a binding is made of.

## Variadic functions

C's ellipsis is the one arity in the language a declaration does not fix, and it exists for one
reason: `printf`, `snprintf`, `execl`, `open` — the calls every C library reserves for a variable
tail — cannot be declared at all without it.

```sysl
extern printf(fmt: *u8, ...) -> int
extern snprintf(buf: *u8, n: usize, fmt: *u8, ...) -> int

print(1)
```

```output
1
```

**A sysl function may have one too**, and the rules for what may go in the tail are shared, so a
caller need not know whether the callee it is reaching is foreign:

```sysl
sum(n: int, ...) -> int
    var ap: va_list

    va_start(ap)

    var total = 0

    for i in 0..<n do total += va_arg(ap)

    va_end(ap)

    total

print(sum(3, 10, 20, 30))
```

```output
60
```

**Why it is here at all.** C can do this, so sysl must: a capability C has and sysl lacks is a place
sysl cannot be used, and sysl exists to be used where C is. The concrete cases are the ones every C
codebase has — a logging or formatting function whose arity is the caller's business, and a function
that must be *callable from* C at a variadic signature, or hand a tail onward to one.

### The calling side

**The ellipsis follows the named parameters, and there must be at least one**, because C reads a
variadic call's tail relative to the last named argument. `f(...)` is not a callable declaration in
any C either:

```sysl
extern nothing(...) -> int

print(1)
```

```error
'nothing' needs at least one named parameter before '...'
```

A call is checked against the declared parameters exactly as any other call is — the ellipsis
excuses nothing that comes before it, arity included, and the escape analysis still assumes the
callee keeps every argument. What the ellipsis governs is only what follows:

**Only what C varargs can carry may go in the tail** — an integer, a float, a `char`, a raw pointer,
or the **address of a function**. What is refused there and not at a declared parameter is a `bool`:
C would promote it to `int`, and sysl has no conversion that says so, so there is nothing to promote
it *with*:

```sysl
extern printf(fmt: *u8, ...) -> int

print(printf(null, true))
```

```error
a bool cannot be passed to '...' — a variadic argument must be an integer, a float, a char, a raw pointer, or the address of a function
```

The function address is there because a variadic setter is how a C library takes a callback —
`curl_easy_setopt(h, CURLOPT_WRITEFUNCTION, f)` is the shape, and every option that registers one
goes through it. A code pointer is the width of a data pointer and travels in the same register, so
C's own rules take it exactly as they take a `void *`:

**What may be written into a tail is what may be read back out of one**, so a callee names the
signature rather than reading an address and casting it:

```sysl
doubler(n: int) -> int = n * 2

apply(x: int, ...) -> int
    var ap: va_list

    va_start(ap)

    var f: *extern(int) -> int = va_arg(ap)

    va_end(ap)

    f(x)

print(apply(21, &doubler))
```

```output
42
```

The alternative was `ptr_cast(&f)` to a `*u8`, which travels identically and throws the signature
away — so a callback registered at the wrong arity stopped being a compile error anywhere, on the
one option class where getting it wrong corrupts the stack at run time rather than failing to link.

**A tail argument is passed already widened**, by C's default argument promotions: an integer
narrower than 32 bits becomes `i32` or `u32` following its own signedness, and any float narrower
than 64 — `f16`, `bf16` or `f32` — becomes `f64`. This is not something the ABI can be left to do — LLVM promotes nothing on its own,
and a narrow value handed over as written is read back out of the wrong number of bytes. The
widening is part of the call.

**An aggregate is the one place a sysl tail is narrower than a foreign one.** A struct, an enum, a
tuple or a view crosses to a *foreign* callee under exactly the classification a declared parameter
of that type gets, which is what C does with one as well. It does not cross to a *sysl* one, because
there it is the callee's own walk that reads the tail back, and the walk reads one register at a
time:

```sysl
struct Pair
    a: int
    b: int

take(n: int, ...) -> int = n

print(take(1, Pair(1, 2)))
```

```error
a Pair cannot be passed to a sysl function's '...' — a walk over the tail reads back one register at a time and an aggregate is not one, where a foreign callee takes it because C says which registers it arrives in
```

The refusal says which callee it is about rather than calling the argument unsuitable, since the
same argument is fine one call away.

### The receiving side — C's, spelled sysl's way

| form | is |
|---|---|
| `va_list` | a **predeclared type**, like `int` and `never` — not a struct a program could have written, because its layout is the target ABI's |
| `va_start(ap)` | readies it. C also names the last fixed parameter here; sysl does not, because the function already knows which parameter that is and repeating it is a chance to get it wrong |
| `va_arg(ap)` | takes the next argument and advances |
| `va_end(ap)` | finishes with it |
| `va_copy(dst, src)` | starts `dst` where `src` has reached, so a tail can be walked twice |

These five are **language forms, not library functions**, in the same category as `sizeof`: each is
an ABI primitive that no sysl body could implement, so there is nothing to put in the library. That
is the line the "no functions built into the compiler" rule actually draws — no program could write
`va_arg`.

**`va_arg` reads its type from context, or from the brackets.** C writes the type as a second
argument, which is not a thing a sysl expression can hold; here it comes from the place the value is
read into — `var v: int = va_arg(ap)`, `total += va_arg(ap)`, `take(va_arg(ap))` — the same place
`None` and `Ok(5)` get theirs. Where a reader would rather say it at the form, `va_arg[int](ap)` is
[the written type-argument list](/reference/generics/) at a special form, and it is what a bare
`print(va_arg[int](ap))` needs. Where neither says, the form is refused rather than guessed at:

```sysl
walk(n: int, ...) -> int
    var ap: va_list

    va_start(ap)

    print(va_arg(ap))

    va_end(ap)

    n

print(walk(1, 2))
```

```error
'va_arg' reads the next argument as some type, and nothing here says which — annotate the variable it is read into
```

### Handing a walk on

C's other half of this is `vprintf`: a function receives the tail, does not read it itself, and
passes it to somebody who does. The parameter type is **`*va_list`**, and the call writes `&ap`:

```sysl
report(n: int, ap: *va_list) -> int
    var total = 0

    for i in 0..<n do total += va_arg(ap)

    total

relay(n: int, ...) -> int
    var ap: va_list

    va_start(ap)

    var t = report(n, &ap)

    va_end(ap)

    t

print(relay(2, 4, 5))
```

```output
9
```

**A bare `va_list` parameter is refused**, and the by-value parameter rule is why — a copy of a walk
is not a walk:

```sysl
borrow(ap: va_list) -> int = 0

print(borrow(1))
```

```error
a va_list is a parameter as '*va_list', not as 'va_list' — a parameter is a by-value binding, and a copy of a walk advances nothing 'borrow''s caller can see, so the walk is handed over by address and the call writes '&ap'
```

Two things follow, and both are the point. The borrower **advances the lender's own list**, so what
it consumed is gone when the lender reads on — which is what `va_copy` is for, exactly as in C. And
`va_start` still asks for a tail of the function's own while `va_arg` asks only for a walk, so a
borrower reads a tail without having one.

**Returning a `va_list` is refused outright**, foreign or not:

```sysl
give() -> va_list
    var ap: va_list

    ap

print(1)
```

```error
a va_list cannot be returned from 'give' — the type names the storage a walk lives in, and there is no value of it to hand back
```

A `*va_list` is an ordinary raw pointer and is refused nowhere — it may be returned, held in a
field, or carried in a struct, under the memory model's rules and nobody else's.

**An `extern` is written in C's spellings and takes either.** A foreign declaration transcribes a C
header, so it says what the header says: `va_list` is C's by-value parameter, the one `vprintf`
takes, and `*va_list` is C's `va_list *`. The refusal above is about a *sysl* body, which could do
nothing with a copy of a walk; a foreign body is C's, and C's `vprintf` is precisely a body that
reads one.

```sysl
extern vprintf(fmt: *u8, ap: va_list) -> i32

log(fmt: *u8, ...) -> i32
    var ap: va_list

    va_start(ap)

    var n = vprintf(fmt, &ap)

    va_end(ap)

    n

print(1)
```

```output
1
```

**The call writes `&ap` for either spelling**, because the address is the only thing sysl has and it
is what both are formed from. What actually crosses for the by-value one is a *target* question:
C's `va_list` is a different type on every machine and is passed three different ways — the value in
the storage on Darwin arm64, the storage's own address on x86-64 System V, the address of a fresh
copy on AAPCS64. All three pass one pointer, so the difference cannot be recovered from the emitted
types; the compiler reads it off the target it was told to build for.

### Where an ellipsis may go

A member is a function with a receiver in front, so a `...` reaches one under exactly these rules.
The receiver is a parameter once the member is lowered, and it is therefore what a tail anchors on —
so `only(self, ...)` is a complete declaration, while a receiverless `make(...)` has nothing named
before its ellipsis and is refused exactly as `f(...)` is. The same holds for an associated function,
a member of a generic type, a member with type parameters of its own, and a **nested function**,
whose environment holds the first parameter slot the way a receiver does.

A **trait** may declare one, and an implementation must agree about it: a `...` is part of what a
caller may write, so having one where the trait has none is a different promise rather than a wider
one. What such a trait cannot be is a [**trait object**](/reference/traits/). A call to a variadic
names the callee's whole function type — that is how it says where the declared parameters stop —
and a slot in a method table is a word that names none. A bound still reaches the method, because
that call knows which function it is reaching.

### It is as unsafe as C's

Nothing checks that the callee asks for the types the caller passed, or that it stops at the right
count; `va_arg` past the end reads whatever is there. The tail carries no type information, so there
is nothing to check against. **This is the one place in sysl where getting it wrong is
undiagnosed** — which is why a *safe* variadic (a homogeneous `...T` collected into a slice, or a
heterogeneous `...&Show` over trait objects) is worth adding beside it later, never instead of it.

## `opaque` — withholding a layout

`opaque struct Name` is known by shape only inside the module that declares it. Everywhere else the
type is **incomplete** — exactly what C's `struct foo;` is — and the only thing that may be said
about it is `*Name`.

```sysl
module net

opaque struct Conn
    fd: int
    live: bool

open() -> *Conn
close(c: *Conn)
```

**One rule, because two different wants meet in it.** A library stabilizing its surface wants to add
and reorder fields with nothing downstream recompiled. A binding wants `*sqlite3` to be a type a
`*u8` cannot be mistaken for, where nobody in sysl knows the layout at all. Both are "the shape is
not yours to know".

So an opaque struct may declare **no body at all**, which is the C-handle case:

```sysl
import sysl.text.cstring

opaque struct Dir

extern "opendir" c_opendir(path: *u8) -> *Dir
extern "closedir" c_closedir(d: *Dir) -> int

var d = c_opendir(cstring("/tmp").ptr)

print(c_closedir(d))
```

```output
0
```

Nothing in sysl lays a `Dir` out; the storage is libc's. An *ordinary* struct with no body stays an
error, and says which word to add.

**A C caller is outside every module, so a generated header keeps the same promise.** An `@export`ed
opaque struct is declared there as a tag with no body — `typedef struct mylib_handle mylib_handle;` —
and none of its fields is written, so C can hold a `mylib_handle *` and pass it back, and nothing
more.

**What is refused outside the declaring module is one list**: a binding, a field of another type, an
element, an array, a slice, a `&`, a type argument, a by-value parameter or result, construction,
reading or writing a field, a pattern naming the fields, a dereference, `sizeof`, `alignof`,
`offsetof`, and a by-value `self` method. Every one of them needs a size or an offset, which is the
single fact being withheld, so they are one diagnostic rather than fifteen.

**The by-value `self` method is the case worth stating outright**, because it looks like a call and
is not. The *function* was compiled by the library; what crosses the boundary is the **caller's
copy**, laid out to the fields as they stood when that caller was built. Adding a field would then
break it silently — precisely the failure the modifier exists to prevent. `*self` and `&self` need no
shape and stay reachable, which is what makes them the forms an opaque type's methods take.

**The reach is the declaring module exactly**, not a subtree the way `private[M]` widens. What
`opaque` buys is that a field may move with nothing downstream recompiled, and the set of files that
must recompile together is the module — its files share one scope, so they are already one unit for
this, and a submodule is already not.

**It is not a visibility, and the two are independent.** Visibility decides who may say the *name*;
`opaque` decides who may know the *shape*. A public type may be opaque, which is the whole point of
one; a `private` type may be opaque too, and simply has nobody left to be opaque to.

Codegen needs nothing for it — pointers lower to `ptr`, so a `*Opaque` downstream never asks for the
aggregate, and the check is entirely a front-end rule.

## `interrupt` — a definition the processor enters

`interrupt` before a definition says the **processor** enters it, not a caller. It is written where a
visibility modifier is, on the declaration rather than folded into `extern`, because it is about a
*definition* — the handler is code this program supplies.

```sysl
interrupt timer()               // RISC-V: takes nothing
interrupt(supervisor) trap()    // ...at a named privilege level

interrupt fault(f: *Frame)      // x86-64: the ABI requires the frame
```

**One concept, three answers**, every one of them read off clang rather than out of a document:

| processor | what `interrupt` is | the signature it demands |
|---|---|---|
| **x86-64** | an LLVM calling convention, `x86_intrcc` | a pointer to the frame the hardware pushed, optionally then an integer error code |
| **RISC-V** | a function *attribute*, `"interrupt"="machine"` | nothing at all |
| **AArch64** | it does not exist — a handler is assembly | — |
| **Arm M-profile** (`thumb`) | it does not exist — a handler is an ordinary function | — |
| **CRAFT** (`craft`) | it does not exist — one vector, and the kernel decodes `cause` | — |

So the annotation names the **concept** and the back end decides what that becomes. A directive
spelling `x86_intrcc` would put one machine's answer in a source file and be wrong on the other
two — the same shape as `@link` naming a library rather than a flag, and the same reason.

**On a processor without it, the annotation is refused rather than ignored:**

```sysl target=aarch64-macos
interrupt timer()
    print("tick")
```

```error
'interrupt' is not something aarch64 has: its exception entry goes through a vector table of fixed-size instruction slots, so a handler is assembly and there is nothing here for a convention to describe
```

Clang answers `__attribute__((interrupt))` on AArch64 with "unknown attribute ignored" and compiles
an ordinary function. That is defensible for C, where an attribute is advisory by tradition. It is
not defensible here: the handler would then return with `ret` where the machine needs `eret`, having
saved none of the registers an asynchronous entry clobbers, so the failure is silent and arrives as
corruption in whatever was interrupted.

**AArch64's absence is not an oversight to fill in later.** Its exception entry goes through a vector
table the processor indexes by cause, where each entry is a fixed-size slot of instructions — so the
entry point is assembly by construction, and there is nothing for a convention on a sysl function to
describe.

**M-profile's absence is a different absence, and the refusal says which one it is.** Every `thumb`
target is Armv6-M, Armv7-M or Armv8-M, and there an exception is entered with `xPSR`, `PC`, `LR`,
`R12` and `R3`–`R0` already stacked by the hardware and `EXC_RETURN` in the link register — so a
plain function returning normally *is* a correct handler, and the vector table holds its address
rather than its code. Nothing is needed, which is why nothing is accepted:

```sysl target=thumbv7em-freestanding
interrupt systick()
    ()
```

```error
'interrupt' is not something thumb has: a Cortex-M exception is entered with the caller-saved registers already stacked by the processor and 'EXC_RETURN' in the link register, so an ordinary function is already a correct handler and there is no prologue for a convention to arrange — write one, and give it the name the vector table holds with '@export("SysTick_Handler")'
```

So **a Cortex-M handler is an ordinary function named for the vector table with `@export`**, which is
already a reachability root — the symbol survives pruning and carries the name the table was built
against, which is the whole of what a handler needs there.

Three more rules:

- **A handler is an entry point, so it is a root of the reachability walk**, and calling one is
  refused outright: it leaves through a return-from-interrupt that would unwind a frame the call
  never pushed. A walk starting from what the program *runs* therefore cannot reach it, and dropping
  it would leave the vector table pointing at nothing. Its address is still worth taking, which is
  what fills that table.
- **The rules are about the signature, so they are checked on the declaration** rather than while a
  body is walked. A generic handler nothing instantiates has no body analyzed at all, and it is
  exactly as wrong as one that does.
- **`interrupt` is a soft keyword**, and what keeps it one is that a name must follow it. Three
  things start with that word and only the first is a convention: `interrupt timer()` declares a
  handler, `interrupt(n: int) -> int` declares a function *called* `interrupt`, and `interrupt(4)`
  calls one.

**Nothing about this is portable, and the design does not pretend otherwise.** An interrupt handler
is the least portable code there is — it is entered by a mechanism the processor defines, and even
the number of arguments differs. What the compiler owes is that the source says which machine it is
for, and that building it for another fails loudly.

## A library may carry C

**A `.c` file dropped in any module of a library's tree is compiled with it and archived beside it.**
Nothing declares it and nothing lists it: the build already walks every directory, and a C file found
in one that holds source is compiled for the same target and becomes one more member of the
`.syslib`. The sysl side reaches it through the `extern` that was already the way to name a symbol
the linker has — so the *language* gains nothing, and the whole of the feature is in the build.

**A module, and not merely a directory.** A project is not the only thing that writes into its own
tree: `cmake -B build` puts a build directory *inside* it and fills that with generated C meant for
another compiler. A directory holding no sysl was never a module, so its C was never the tree's and
the build passes it by — while still descending through it, because a module may sit any depth below
a directory holding nothing itself. **The tree's own root is the exception**, since the root is the
tree rather than a directory in it: a package namespaced by reverse DNS has its modules at
`sh/sysl/foo/` and nothing at the top, so C belonging to no single module goes there.

What this costs is a vendored C library laid out in sub-directories of its own: the ones holding no
sysl are skipped, and a link error naming the symbols is what says so. Put the `.sysl` that declares
those `extern`s in the directory and it is a module — which is where every binding written so far has
put it anyway.

**A shim can be per-machine, and that is a directory rather than a condition.** A `.c`
cannot carry a sysl attribute, so `#if` is no help to it; what selects it is where it sits. A
directory whose name is wrapped in double underscores selects source for the machines it names and
names nothing itself ([modules](/reference/modules/)), so the file belongs to the module holding the
folder and is absent everywhere else:

```
sysl/fs/path.sysl              module sysl.fs, on every target
sysl/fs/__posix__/dirent.c     compiled where POSIX is, absent on a bare machine
```

**Name the family rather than the systems, which for a shim is nearly always what you mean.** A
selector may name one operating system (`__macos__`), a comma-separated list of them
(`__macos,linux__`), or a family (`__posix__`, `__hosted__`) — and a shim is usually in the third
case without looking like it. `dirent.c` above is *identical* on macOS and Linux, because absorbing
the difference between them is the shim's whole job; what it cannot do is exist where there is no
operating system. Written as two per-system folders that says the wrong thing and costs two
byte-identical copies to say it.

**The standard library is the worked example.** `sysl.fs.entries` lists a directory, which means
reading a `struct dirent` — a shape whose name field sits at an offset the platforms disagree about,
and exactly the transcription the table below refuses. Four lines of C answer it. The folder is what
keeps that file off a freestanding target, where `<dirent.h>` does not exist and `sysl.fs` is still
compiled: **the library is compiled whole for every target**, so a shim it could not compile
everywhere had nowhere to live until the folder existed.

**It exists because a binding to a real C library cannot be written without it.** Three things are
reachable from C and from nothing else, and each blocks an ordinary POSIX interface:

| what | why `extern` cannot reach it |
|---|---|
| **a caller-allocated opaque type** | `regcomp` wants a `regex_t` the caller supplies, and its size is 32 bytes on Darwin and 64 under glibc. A program can only allocate storage whose size it knows |
| **a macro** | `REG_EXTENDED`, `O_RDONLY`, `SIGKILL` are `#define`s. They have no symbol, so there is nothing for a linker to resolve and nothing for `extern` to name |
| **a shape with no sysl spelling** | an untagged union, an inline function, and a **C bitfield struct** — sysl lays an `iN` field out in exactly N bits inside `@packed` ([attributes](/reference/attributes/)), but C leaves *its* allocation to the implementation, so the two need not agree on a size and a shim is what settles it |

Each becomes an ordinary function in three lines of C:

```c
size_t sysl_regex_t_size(void)  { return sizeof(regex_t); }
int    sysl_reg_extended(void)  { return REG_EXTENDED; }
```

Better still, a shim that *allocates* the opaque type hands back a pointer and the sysl side never
learns the size at all.

**The alternative is transcription, and transcription is silently wrong.** A hand-written
`struct regex_t` carrying one platform's header fields compiles everywhere and is correct on one
machine. Nothing checks it — sysl's own `sizeof` would report what sysl laid out, not what C did, so
even that comparison is a tautology. Getting it wrong writes past the end of the caller's storage.
The number has to come from the headers, and C is what reads headers.

Three build rules:

- **A member is named after the path it was found at**, directories included — `demo/util.c` becomes
  `demo.util.o`. A basename alone would not do: `ar r` replaces by name, so two modules each holding
  a `util.c` would have the second evict the first, and the library would ship missing whatever only
  the first defined.
- **The C files are fingerprinted with the sysl ones.** A library's shims are as much its source as
  its modules are, and an artifact that did not change when one was edited is a stale artifact
  nothing would notice was stale.
- **Cross-compiling a library that includes headers needs that target's headers.** That is not a cost
  the design imposes — it is the requirement being honest: a binding to POSIX regex cannot be built
  for a platform whose `regex_t` nobody can see. C that includes nothing cross-compiles like any
  other object. **A selector directory is how a library carries both**: the standard library does
  include a header, in a `__posix__` folder no freestanding build ever looks in, so it goes on
  building for every target the toolchain can lower for — and the file that would not compile there
  is not skipped by a rule about headers, it is simply not part of that build.

## `c const` — a value only the C compiler can work out

**A shim answers for a function, and it does not answer for a value.** A constant reached through a
call is not a constant: it has no value until the program runs, so it cannot size an array, cannot
stand in a `match` arm, cannot be folded into a bound and cannot be checked by `@assert`. The macro
row above still holds for `REG_EXTENDED` as an *argument*, and stops holding the moment the number
has to be known while compiling — which is where a statically allocated FreeRTOS task lives, being a
`[sizeof(StaticTask_t)]u8` the caller supplies.

**A `c const` block is a constant whose value the C compiler works out, for the target being built
for.** The right-hand sides are C, in quotes:

```sysl
@include("<limits.h>")

c const
    BITS: u32 = "CHAR_BIT"
    WIDEST: usize = "sizeof(long long)"

print(str(BITS) + " " + str(WIDEST))
```

```output
8 8
```

`@include` is a header clause like `@link`, written the way C writes it — `"<limits.h>"` reaches a
system header and `"qcbor.h"` one beside the module, which is the same choice a C file makes. Where
the headers are and what they are configured with is `--include-path` and `--define`'s answer,
exactly as it is for a shim. **No name from the header becomes visible in sysl**: a type still
arrives by `opaque struct` and a function by `extern`, and what the block buys is only that the
expressions compile.

Which is what the motivating case looks like written out:

```sysl
@include("FreeRTOS.h")

c const
    STATIC_TASK_SIZE: usize = "sizeof(StaticTask_t)"
    MAX_DELAY: u32          = "portMAX_DELAY"

var tcb: [STATIC_TASK_SIZE]u8 = [0; STATIC_TASK_SIZE]
```

**The value is measured from a probe translation unit that is compiled and never linked or run** —
the file's headers, one global per constant, lowered to IR for the target, and the number read back
out of the IR. Nothing executes, so the answer is the *target's* rather than the host's: a pointer
measures four bytes building for a Cortex-M and eight building for this machine, with no hardware
involved either way.

**The `c` is contextual and stays an ordinary name.** Nothing else in the language follows a name
with a keyword, so `c const` cannot be anything but this, and a program counting characters keeps its
variable `c`. The C needs no literal prefix for the same kind of reason: inside the block a string
can mean nothing else, so the header marks the language once.

**Any C constant expression, and the C compiler is the judge of which those are** — which is what
makes that an honest claim rather than a subset somebody maintains. An expression it will not settle
comes back in its own words:

```sysl
c const
    N: usize = "atoi(\"3\")"
```

```error
the C compiler refused this file's 'c const' block
```

Four more refusals go with it: a header that is not there, a value the declared type cannot hold
(naming the value and both ends of the range), a type that is not a number, and a block written
inside a body, which has no file's headers to be compiled against.

**A `string` from C is not written this way.** A number is a number in the compiler's output and
reads straight off; a string constant is a block of storage and a different job, and it would have to
be written `"\"foo\""` — two quotings for one value, which is a form nobody would guess. The refusal
says so rather than leaving it to be found.

### A float is measured too, and it is the value hand-copying gets wrong

**The declared type may be `f32` or `f64`.** That a float can be spelled by name is no answer here:
a name gives you the *width*, and what nothing was checking is the **value**. The case that makes it
worth a feature rather than a convenience is the macro written as an expression over other macros —
a graphics or physics header is full of them — because copying one of those means doing the arithmetic
by hand and writing the answer down.

```sysl
@include("<float.h>")

c const
    pi:      f32 = "3.14159265359f"
    quarter: f32 = "0.25f * 3.14159265359f"
    eps:     f32 = "FLT_EPSILON"

var one: f32 = 1.0

print(quarter * 4.0 == pi, one + eps > one, one + eps / 2.0 == one)
```

```output
true true true
```

`FLT_EPSILON` is the second reason: it is the definition of the width rather than a number about it,
and a transcription with one digit wrong still looks plausible while being the wrong tolerance for
every convergence loop that uses it.

**Rounding is allowed and silent.** Asking for `f32` is asking for the nearest `f32`, which is what C
does for `float x = M_PI;` — refusing it would leave the narrow width unable to read the
`double`-typed macros that are most of them. What is refused is the value going *missing*: a
measurement that is not finite, because the C overflowed while settling it or the macro names an
infinity or a NaN, and a finite one the declared width turns into an infinity or into a zero it was
not.

```sysl
c const
    huge: f32 = "1e300"
```

```error
which 'f32' cannot hold
```

**The widths are `f32` and `f64`, and both sixteen-bit formats are refused by name.** C writes a
constant expression as a `float`, a `double` or a `long double`, so those two are the widths a
measurement reads back at without anyone having to guess which was meant. `f16` and `bf16` are
narrower than any of the three, and narrow enough that rounding onto one is not something the value
could survive being read back through.

```sysl
c const
    h: f16 = "0.5"
```

```error
'f16' is not a width a 'c const' is measured at
```

Each is refused under the name that was written, rather than both under one of them:

```sysl
c const
    b: bf16 = "0.5"
```

```error
'bf16' is not a width a 'c const' is measured at
```

**`c type` below still refuses a float, and that is a different question.** A typedef is measured
because its *width* is the configuration's to decide, and `float` and `double` are IEEE binary32 and
binary64 on every machine sysl targets — so `f32` and `f64` by name really are the whole answer
there. It is the value that varies, never the width.

**The declared type may be a transparent subtype of a number**, which is what makes this block and
`c type` below a pair rather than two features. Without `new` such a type *is* its base, so a
constant declared at one is a constant declared at that base — and a measured type is exactly that
shape:

```sysl
@include("<stdint.h>")

c type
    Tick = "uint32_t"

c const
    forever: Tick = "0xFFFFFFFFul"

wait(ticks: Tick) -> Tick
    ticks

print(str(wait(forever)))
```

```output
4294967295
```

That is the case the two blocks exist for: a typedef whose width the configuration decides, and the
constants that have to be that width. Spelling the constant `usize` beside a `Tick` parameter is a
package that stops compiling on a port where the two disagree, which is the version of this mistake
that used to ship.

**The name is followed against the file's own declarations** — a `c type` measured beside it, or a
`type` whose base reaches an integer — and a name from anywhere else is refused. A block is one
question put to one file's headers, and a type measured against another file's is not an answer this
one can use.

**A `within` range is checked while compiling**, against the number that came back:

```sysl
type Small = u32 within 0..10

c const
    N: Small = "sizeof(long long) * 100"
```

```error
does not admit
```

which is the `@assert` a program would otherwise write underneath. A **`where` predicate** is refused
rather than checked — a predicate is a function, checked where a value is *made*, and a constant is
folded into its uses rather than made anywhere — and so is a **`new` type**, since reaching one from
its base is a written conversion and there is nowhere on the line to write it.

**The value does not travel through the C type.** C narrows, so `(uint8_t)800` is `32`; carrying it
that way would let a constant that should have been refused arrive looking like one that fits, and
the range check is the whole point of having written the type down.

**A library ships the measured number, not the expression.** The lowering happens before the artifact
is written, so a program linking a package needs neither the package's headers nor a C compiler of
its own — and could not honestly be handed the expression anyway, since an artifact is built for one
target and re-measuring it elsewhere would answer a different question under the same name.

**A file that writes no block costs nothing** and never causes a C compiler to be looked for.

### A file that says what it needs is not probed where it could not be

A probe is a C compilation, so a file carrying one **asks for headers** — and a library is built for
every target it might be used on. Without a rule here a library could hold no block at all: one module
measuring `sizeof(regex_t)` would fail every freestanding build of every program, including programs
that never name it, because there is no `<regex.h>` for a bare Cortex-M and no reason there should be.

So a file's blocks are **skipped** when the file declares
[`@requires`](/reference/modules/#capabilities-are-a-module-property) on a capability the machine
cannot have:

```
module sysl.posix.regex
@requires(posix)
@include("regex.h")

c const
    REGEX_SIZE: usize = "sizeof(regex_t)"
```

Built for a freestanding target, no C compiler is asked anything. The module's **header stays** and
its declarations go, so a program that *does* reach it is told what it needs rather than being
answered with an undefined name:

```
this reaches 'sysl.posix.regex', which requires 'posix', and this module declared 'no posix'
```

**What is asked is whether the machine can have the capability, not whether the project provides
it.** [`package.hocon`](/reference/packages/#capabilities) treats a capability it does not mention as
provided, so a freestanding target nominally offers `posix` — a gate reading that would gate nothing
at all. The question here is physical and has one answer per target: an operating system, and POSIX.
Whether there is a **heap** is not asked, because that is an engineering decision about a machine
that could have one either way.

**A file that requires nothing is measured wherever it is built.** Such a file claims to build
anywhere, so a header missing there is the file having mis-stated itself — the skip is a rule about
files that said what they need, not about which machine is in front of you.

## `c type` — a width only the C compiler can work out

**A `c const` can measure `sizeof(TickType_t)` and cannot use the answer.** Nothing turns a constant
into the type of a parameter, so a typedef whose width the target or a `#define` decides could be
measured and not *spelled* — and a binding had to pick one integer and be right by luck.

That is the version of the transcription problem with no symptom. `TickType_t` is eight bytes for
FreeRTOS's POSIX port, four on a Cortex-M and two under `configUSE_16_BIT_TICKS`, and every one of
them appears in a signature. An `extern` declaring the wrong one is not a size mismatch anything can
see: it links, and then passes garbage in the high half.

**A `c type` block is the type the C compiler says a name is**, for the target being built for:

```sysl
@include("<stddef.h>")

c type
    Size = "size_t"

val n: Size = 41

print(str(n + 1))
```

```output
42
```

A measured type **is** the integer it was measured as — a second name for it, interchangeable with
it, checking nothing of its own — so arithmetic on one needs no cast and an `extern` written against
one is an ordinary declaration:

```sysl
@include("FreeRTOS.h")

c type
    Tick  = "TickType_t"
    Stack = "configSTACK_DEPTH_TYPE"

extern "vTaskDelay" c_task_delay(ticks: Tick)
```

Everything `c const` says holds here: the same contextual `c`, the same quoting, the same `@include`
headers, the same probe compiled and never run, and the same rule that a library ships the answer
rather than the C name. **A file writing both blocks asks the C compiler once**, since the two are one
question rather than a price per line.

**A line carries no sysl type**, which is the whole difference from a `c const` line — the type is the
answer rather than the question. A program that wants to *state* a width writes `@assert` over a
`c const` holding the `sizeof`, which says the same thing where it can be checked.

A `c const` **declared at** a measured type is the other half of the pair, and is written out above.
Two blocks in one file are one question, and a binding usually writes both.

### A number your program worked out reaches one under the type's own name

A measured type is a transparent subtype, so a value of the integer it turns out to be flows in and
out with no cast at all. What a program *has*, though, is usually a `usize` — a `sizeof`, a slice's
`len`, or arithmetic over them — and that is a different type from whatever C measured. The
conversion is the type's own name:

```sysl
@include("<stddef.h>")

c type
    Size = "size_t"

take(n: Size) -> Size = n

var xs: [3]u32 = [0, 0, 0]

print(take(Size(xs.len)) + Size(sizeof(u32)))
```

```output
7
```

**That spelling is the only portable one**, which is the whole reason it exists: the width is the
target's, so writing `u32(xs.len)` would be one configuration's answer copied into the source — the
transcription a `c type` is for abolishing. It is the ordinary case in a binding rather than a corner
of one: a queue's item size and a task's stack depth are both a number sysl worked out and C has a
typedef for.

The conversion is **written**, and an unwritten one is refused even where the widths happen to agree
on the machine in front of you:

```sysl
@include("<stddef.h>")

c type
    Size = "size_t"

take(n: Size) -> Size = n

var n: usize = 3

print(take(n))
```

```error
'n' of 'take' is Size, but usize was given
```

A silent narrowing is exactly what breaks on the target where the typedef is sixteen bits, so it is
refused here where it can be seen. The [contracts](/tour/contracts/) page has the general rule, of
which this is one case.

**What comes back is a width and a signedness.** Three things follow, and each was measured against
the C compiler rather than assumed:

- an **enum** is measurable and carries the signedness the C compiler chose for it;
- a **qualifier** needs no special case, so `const unsigned short` measures as `unsigned short`;
- plain **`char`** is asked about rather than assumed, since C leaves its signedness to the
  implementation — it is signed on an Apple arm64 machine and unsigned on many others.

**A `_Bool` arrives as `bool`**, the one answer that is not an integer and is still given: C means by
`_Bool` what sysl means by `bool`, and the two already cross as a single unsigned byte.

**A type C does not describe as an integer is refused by name:**

```sysl
c type
    Handle = "void *"
```

```error
is not an integer type
```

A float, a pointer, a struct and an array each already have an answer here — a float by name, an
address as `*T`, a struct as an `opaque struct` — and each is better than a same-width integer
standing in for it and losing what it was.

## What is deliberately absent

| absent | instead |
|---|---|
| an `fn(int) -> int` type for sysl's own callables | the `Fn` trait; `*extern` exists only where the representation is somebody else's to choose |
| a header parser or a binding generator | an `extern` per declaration, and a `.c` shim for what a header hides |
| a calling convention on an export | open — `@export` implies C's, and `interrupt` already built the shape a second one would take |
| a capability gate on an extern | open — an extern reaching libc plausibly needs `os`, and does not yet say so |

Both directions now exist. `extern` calls out of sysl and `@export` calls into it, which is what an
incremental replacement of a C codebase needs: the sysl side can sit underneath an existing C
program as readily as on top of an existing C library.

---

Next: [attributes and compile-time](/reference/attributes/).
