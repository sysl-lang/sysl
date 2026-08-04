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
function 'abs' is already declared
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

print(root(9.0), rootf(4.0f32))
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

**`extern` implies C's convention and says nothing about any other.** The one case that needs
something else is a function the *processor* enters rather than a caller, and that is a property of
a definition rather than of a foreign declaration — see `interrupt`
below.

## `link` — which library resolves the externs

`link "z"` in a file's header names a library the linker must be given, and it sits beside the
`extern`s it supports because that is the only place that knows. An `extern` states the symbol it
wants and never where the symbol lives; a binding to `libpng` is written by whoever writes the
module, and the driver cannot carry a list of libraries it has never heard of.

```sysl
module image.png
link "png"
link "z"

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
- **`link` is a soft keyword** — special only in a header, an ordinary identifier everywhere else, so
  a program may still declare a function called `link`. A directive is `link` followed by a
  *string*, and no statement has that shape.

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

var xs = [30i32, 10i32, 20i32]

qsort(ptr_cast(&xs[0]), 3usize, 4usize, &compare)

print(xs[0], xs[1], xs[2])
```

```output
10 20 30
```

**`&f` is the address**, and the `&` is the same one the [memory model](/reference/memory/) gives
every other address. A bare `f` keeps its ordinary meaning — the capture-free closure — because a
spelling that meant a sysl callable in one slot and a C address in another would be choosing
silently between two representations that share nothing.

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

### What has no address, and why

Each of these is refused because the address would not be an address of what its type says, and
nothing downstream could notice.

**A generic function** — it is a body per set of type arguments, so there is no one body to name:

```sysl
id[T](x: T) -> T = x

var a = &id

print(1)
```

```error
'id' is generic, so it is not one function but a copy per set of type arguments, and an address names one body — a wrapper that calls it at the arguments wanted is what has an address
```

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

**Any signature carrying an aggregate** — a struct, a tuple, a data enum, a view, a `string`:

```sysl
struct Pair
    a: int
    b: int

agg(p: Pair) -> int = p.a

var c = &agg

print(1)
```

```error
the 1st parameter of 'agg' is Pair, an aggregate, and an aggregate crosses to C in whichever registers that machine's convention names rather than the ones a sysl call uses — so this address would be of a function C cannot call correctly. A wrapper taking the parts behind a '*T' is what has an address
```

That last one is **a restriction rather than a consequence**, and the only one on the list that is.
What would close it is an adapter emitted beside such a function, entered under the C classification
and calling the sysl one; until there is one, the refusal names the parameter, because an address
that is quietly wrong is worse than no address. A callback taking the parts behind a `*T` is the
shape that works today, and it is what C interfaces overwhelmingly use anyway.

The test is made by **shape** rather than by asking the target's classification, so a program
accepted for one machine is accepted for every machine.

### What it costs today

**A signature cannot be named once.** Every declaration mentioning a callback spells the whole of
it, and a real binding mentions one several times — `signal` takes a handler and returns the
previous one, so its declaration says the same eight tokens twice. This is not the foreign
interface's restriction: `type` declares a
[constrained subtype](/reference/errors/), whose base must be a scalar, so a name
for a pointer type is refused in the same words:

```sysl
type Handle = *u8

print(1)
```

```error
a constrained subtype's base must be an integer, a float, or 'char', not *byte
```

What would fix it is an alias that is not a subtype.

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

**Only what C varargs can carry may go in the tail** — an integer, a float, a `char`, or a raw
pointer. What is refused there and not at a declared parameter is a `bool`: C would promote it to
`int`, and sysl has no conversion that says so, so there is nothing to promote it *with*:

```sysl
extern printf(fmt: *u8, ...) -> int

print(printf(null, true))
```

```error
a bool cannot be passed to '...' — a variadic argument must be an integer, a float, a char, or a raw pointer
```

**A tail argument is passed already widened**, by C's default argument promotions: an integer
narrower than 32 bits becomes `i32` or `u32` following its own signedness, and an `f16` or `f32`
becomes `f64`. This is not something the ABI can be left to do — LLVM promotes nothing on its own,
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

**`va_arg` reads its type from context.** C writes the type as a second argument, which is not a
thing a sysl expression can hold; here it comes from the place the value is read into —
`var v: int = va_arg(ap)`, `total += va_arg(ap)`, `take(va_arg(ap))` — the same place `None` and
`Ok(5)` get theirs. Where the context says nothing, the form is refused rather than guessed at:

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

**What is refused outside the declaring module is one list**: a binding, a field of another type, an
element, an array, a slice, a `&`, a type argument, a by-value parameter or result, construction,
reading or writing a field, a pattern naming the fields, a dereference, `sizeof`, `alignof`, and a
by-value `self` method. Every one of them needs a size or an offset, which is the single fact being
withheld, so they are one diagnostic rather than fourteen.

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
| **AArch64** | it does not exist | — |

So the annotation names the **concept** and the back end decides what that becomes. A directive
spelling `x86_intrcc` would put one machine's answer in a source file and be wrong on the other
two — the same shape as `link` naming a library rather than a flag, and the same reason.

**On a processor without it, the annotation is refused rather than ignored:**

```sysl
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

**A `.c` file dropped anywhere in a library's tree is compiled with it and archived beside it.**
Nothing declares it and nothing lists it: the build already walks every directory, and a C file found
there is compiled for the same target and becomes one more member of the `.syslib`. The sysl side
reaches it through the `extern` that was already the way to name a symbol the linker has — so the
*language* gains nothing, and the whole of the feature is in the build.

**It exists because a binding to a real C library cannot be written without it.** Three things are
reachable from C and from nothing else, and each blocks an ordinary POSIX interface:

| what | why `extern` cannot reach it |
|---|---|
| **a caller-allocated opaque type** | `regcomp` wants a `regex_t` the caller supplies, and its size is 32 bytes on Darwin and 64 under glibc. A program can only allocate storage whose size it knows |
| **a macro** | `REG_EXTENDED`, `O_RDONLY`, `SIGKILL` are `#define`s. They have no symbol, so there is nothing for a linker to resolve and nothing for `extern` to name |
| **a shape with no sysl spelling** | an untagged union, a bitfield, an inline function |

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
  other object, which is why the standard module goes on building for any target the toolchain can
  lower for.

## What is deliberately absent

| absent | instead |
|---|---|
| an `fn(int) -> int` type for sysl's own callables | the `Fn` trait; `*extern` exists only where the representation is somebody else's to choose |
| a header parser or a binding generator | an `extern` per declaration, and a `.c` shim for what a header hides |
| a way to export a sysl function to C | open — the annotation half is built, and the mangling question is not settled |
| a capability gate on an extern | open — an extern reaching libc plausibly needs `os`, and does not yet say so |
| an alias for a pointer signature | open — what is missing is a type alias that is not a constrained subtype |

The export direction is the one worth watching. A C codebase is replaced incrementally, so calling
*into* sysl matters as much as calling out of it — and `interrupt` already built the half that
decides how a definition states the convention it is entered under.

---

Next: [attributes and compile-time](/reference/attributes/).
