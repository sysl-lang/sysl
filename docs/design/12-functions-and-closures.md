# Design Decisions: Functions and Closures

**Status:** the top-level function surface is written against the implementation that already
exists — keyword-less declarations, expression and block bodies, `return`, forward reference,
recursion, and `extern` including its variadic `...` — and ratifies it. **Closures are not yet implemented**; the sections that describe
them (§5 onward) are the design the rest of the docs already lean on — `05-escape-analysis.md`
heap-boxes an escaping closure, and `capabilities.md` gates escaping closures behind `alloc` and
inlines the non-escaping ones — written down here so that surface is decided before it is built,
not after. Where a closure section commits to a spelling or a representation it says so; the
*open* list at the end records what is deliberately left for later.

This chapter rests on `03-memory-model.md` (a captured value follows the same copy/retain rules
as any other), `02-traits.md` (a closure's type is a trait object, dispatched by the same
mechanism as every other one), `10-generics.md` (an inlined closure parameter is a bounded type
parameter), and `05-escape-analysis.md` (which closures heap-box is *inferred*, never annotated).

The through-line: a function is not a special second thing. A top-level function is the named,
capture-free case; a closure is the same callable with an environment; and the type a callable
inhabits is the `Fn` trait, so passing one is the trait machinery already written, not a new
one.

---

## 1. Declaring a function

A function is declared with **no keyword** — a name, a parenthesized parameter list, an optional
`-> return-type`, and a body — in the Scala/Haskell tradition rather than the C/Rust `fn`/`func`
one:

```
add(a: int, b: int) -> int = a + b            // expression body

square(n: int) -> int                          // block body
    var s = n * n
    s                                          // trailing expression is the result

greet(name: string)                            // no -> : returns unit
    print("hi", name)
```

Two body forms, one meaning. An **expression body** `= expr` is the whole function; a **block
body** is an indented statement list whose **trailing expression is the implicit return value**
— the same rule a block expression follows everywhere else in the language (`codegen.md`), so a
function body is not a special context. The `= expr` form is exactly the one-statement block
written inline.

**A missing `-> type` means the function returns `unit`.** `greet` above runs for its effect and
yields nothing; there is no `-> unit` to write, though writing it is legal. This mirrors how a
block whose trailing statement is not an expression has type `unit`.

### A declaration with no body — `extern`

`extern` declares a function this program does **not** define but may call, resolved by the linker
under the name it is declared with:

```
extern exit(code: int) -> never
extern abs(n: int) -> int
extern memcpy(dst: *u8, src: *u8, n: usize) -> *u8
```

It is a function header and nothing else, and the **absence of a body** is the whole difference:
everything downstream is the ordinary path. A call to an extern is checked against the declared
signature, has its arity checked, and lowers to an ordinary call; the result type is optional and
absent means `unit`, exactly as for a function. Externs live in the one function namespace, so an
extern and a function cannot share a name. An extern is never generic — there is no body to
monomorphize.

**The name is the symbol.** `extern abs` binds to the C symbol `abs`; there is no separate link
name to give (`§ Open f`). A declaration nothing calls is not emitted at all, so declaring more
than a program uses costs nothing.

**Two rules follow from having no body, and both are already written elsewhere.** The escape
analysis assumes the worst of it — every argument may be kept, and the result may view any of them
(`05`) — because nothing can tell whether the foreign side held on to what it was handed. And
`extern f() -> never` is how a program says the callee does not come back (`00 §11`), which is what
makes the exit path of a panic ordinary sysl rather than a compiler intrinsic.

**Why the language needs it at all.** Sysl has no functions built into the compiler that a program
could not have written: `Option` and `Result` are prelude enums, `unwrap` is a prelude member. The
one thing a program genuinely cannot write for itself is the *first* call out of sysl — into libc
on a hosted target, into a driver primitive on a bare one. `extern` is that seam and nothing more,
which is why it is a declaration form rather than a set of known names.

What crosses the boundary is the programmer's business. A scalar or a `*T` matches C directly; a
`string` or a `&T` is a sysl layout that C has no notion of, and handing one over is the same kind
of promise `*T` already is. Capability gating — an extern reaching libc plausibly needs `os` — is
open (`§ Open h`).

**An extern may end in `...`.** C's ellipsis is the one arity in the language a declaration does not
fix, and it exists for one reason: `printf`, `snprintf`, `execl`, `open` — the calls every C library
reserves for a variable tail — cannot be declared at all without it, and a language whose only seam
to the outside is `extern` would be unable to reach half of libc.

```
extern printf(fmt: *u8, ...) -> int
extern snprintf(buf: *u8, n: usize, fmt: *u8, ...) -> int
```

The ellipsis follows the named parameters and **there must be at least one**, because C reads a
variadic call's tail relative to the last named argument; `extern f(...)` is not a callable
declaration in any C either. A **sysl function may have one too** (§9), and the rules for what may go
in the tail are shared, so a caller need not know whether the callee it is reaching is foreign.

A call to one is checked against the declared parameters exactly as any other call is — the
ellipsis excuses nothing that comes before it, arity included, and the escape analysis still assumes
the callee keeps every argument. What the ellipsis governs is only what follows:

- **Only what C varargs can carry may be passed**: an integer, a float, a `char`, or a raw pointer.
  A `string`, a `&T`, a struct, an enum, a slice — every sysl layout C has no notion of — is refused
  *here* even though a **declared** parameter may take one, and the difference is the written type:
  a declared parameter says what the callee agreed to receive, and a tail argument has nothing to
  say it with. A `bool` is refused too, for a sharper reason: C would promote it to `int`, and sysl
  has no conversion that says so (`01`), so there is nothing to promote it *with*.
- **A tail argument is passed already widened**, by C's default argument promotions: an integer
  narrower than 32 bits becomes `i32` or `u32` following its own signedness, and an `f16` or `f32`
  becomes `f64`. This is not something the ABI can be left to do — LLVM promotes nothing on its own,
  and a narrow value handed over as written is read back out of the wrong number of bytes. The
  widening is therefore part of the call, and visible in the tree as an ordinary conversion rather
  than buried in the emitter.

**What this does not do.** Being able to declare `printf` is not the same as `print` becoming
prelude sysl. `print` takes a heterogeneous argument list and renders each value by its type, which
needs a `Display`-style trait (`02`, `14`) and a string written by length rather than to a
terminator; the ellipsis is one of the things that was missing, not all of them.

## 2. Parameters are by-value bindings

Every parameter is an ordinary **value binding**: `a: int` names a copy of the argument, and
inside the body it behaves exactly like a `var`-less local of that type. There are **no
parameter modes** — no `out`, no `inout`, no `ref` keyword — because the memory modes already
express everything those would:

- To let a function **mutate the caller's value**, the parameter takes a pointer or reference by
  *type*: `bump(p: *int)` mutates through `*p`, `push(v: &Vec, x: int)` mutates the shared `&Vec`.
  The mode is carried by `*T`/`&T` in the type (`03`), not by a keyword in front of the name.
- To **hand a value along**, a plain `T` parameter copies it in; because every sysl value is
  copyable and copying a value holding a `&T` retains it (`03`), this is always well-defined and
  never a move.

This is the same principle the receiver of a method follows (`08` §3): `self`, `*self`, `&self`
are the by-value / by-pointer / by-reference choices spelled with the mode sigil, and a plain
parameter makes the identical choice with the identical spelling. One rule covers receivers and
parameters both.

## 3. `return`, and the trailing-expression result

A function yields a value two ways, and they compose:

- **The trailing expression** of the body is the result, with no `return` keyword — the common
  case, and the only one an expression body can use.
- **`return expr`** yields early from anywhere in the body; **`return`** with no operand yields
  early from a `unit` function. It is for the guard-clause shape — bail out on a special case
  before the main path:

```
classify(n: int) -> int
    if n < 0 then return -1
    if n == 0 then return 0
    1                                          // the fall-through result
```

`return` is a jump, not an expression: it does not produce a value in place, it transfers
control out of the function. The trailing-expression result is the structured default; `return`
is the escape for the cases that would otherwise need an `else` ladder.

That `return` is not an expression does not stop a *branch* that returns from sitting where a value
is wanted. A block ending in a jump has type `never` (`00 §11`), so `var h = if ok then v else
return -1` type-checks as an `int`: the branch that leaves contributes no type of its own, and the
one that arrives decides. The distinction is worth keeping straight — the jump has no type, the
block containing it does.

## 4. Declaration order does not matter

Functions are **hoisted**: a function may call another that is declared later in the file, and a
function may be called by code that appears above it. Mutual recursion therefore needs no forward
declaration:

```
isEven(n: int) -> bool = if n == 0 then true else isOdd(n - 1)
isOdd(n: int)  -> bool = if n == 0 then false else isEven(n - 1)
```

Both directions resolve because the analyzer collects every top-level function's signature before
it checks any body. This is the Go/Scala model — the top level of a module is an unordered set of
definitions — and it is what keeps a program from being ordered by its call graph, which for
mutual recursion is impossible anyway. Ordinary recursion is the same mechanism applied to one
function: `fact` calling `fact` is a call to an already-collected signature.

Order-independence is a property of **top-level** definitions. Inside a body, a `var` must still
be declared before it is used — a local is a sequenced statement, not a hoisted definition.

---

The rest of this chapter is design for a feature **not yet built**. A closure is a function
value carrying an environment; the three questions it raises are how it is *written*, what
*type* it has, and how *capture* interacts with the memory model. Each is answered so that the
implementation, when it lands, has a target — and so that `05` and `capabilities.md`, which
already assume closures, name something defined.

## 5. Closure literals — the arrow form

A closure literal is **parameters, an arrow, and a body**, reusing the `->` the language already
spends on function return types and match arms:

```
x -> x + 1                    // one parameter: no parentheses
(x, y) -> x + y               // two or more: parenthesized
() -> next_id()               // none: empty parentheses

xs.map(x -> x * 2)            // the everyday use, at a call site
xs.each(x ->                  // block body by indentation, like any function
    log(x)
    print(x))
```

- **One parameter drops its parentheses** — `x -> …` — because that is the overwhelmingly common
  case (`map`, `filter`, `each`) and the bare name reads cleanly inside a call. **Two or more are
  parenthesized** — `(x, y) -> …` — and **zero is the empty pair** `() -> …`. The paren rule is
  the same one the function-*type* spelling uses (§6), so a closure and the type it inhabits look
  alike.
- **The body is an expression or an indented block**, exactly as a named function's is (§1); the
  block's trailing expression is the result.
- **Parameter types are inferred** from the context the closure is passed into — `xs.map(x -> x *
  2)` gives `x` the element type of `xs` — so they are written only when there is nothing to infer
  them from, in which case an annotation `(x: int) -> …` supplies them.

This does not collide with anything. A closure literal is an **expression**; the arrow function
*type* of §6 is a **type**; the two never occupy the same grammatical slot, the same
position-based disambiguation `10` §2 uses for `[]`. In particular the `->` inside a closure body
and the `->` of an enclosing function's return type are unambiguous because the closure sits
inside an expression the parser is already reading as a value.

**A named function is the capture-free closure.** A top-level function used where a callable is
expected — `xs.map(square)` — is simply a closure with an empty environment, and needs no lambda
wrapper. There is no separate "function pointer" concept to learn; the capture-free case is the
degenerate one, the way a simple enum is the degenerate data enum (`09` §1).

## 6. The type of a callable — the `Fn` trait

A callable's type is the built-in **call trait `Fn`**, written with the parameter and return
types it calls with:

```
Fn(int) -> int                Fn(Event) -> unit                Fn() -> int
```

`Fn` is a trait like any other (`02`), and that is the whole point: a callable is *used* the two
ways every trait is used, and which one is chosen is the same static-versus-dynamic decision `10`
§6 already frames — here it is what decides whether the closure allocates.

### In a parameter, the bare arrow is an inlined callable

A parameter typed with a **bare arrow** — `f: int -> int` — is a callable the function will call
but not store, and it lowers to a **bounded type parameter**:

```
map(self, f: A -> B) -> …             ≡   map[F: Fn(A) -> B](self, f: F) -> …
```

The arrow spelling is sugar for the `Fn` bound. Because it is a bound, the closure is
**monomorphized and inlinable** — one specialized copy of `map` per closure type, the call
direct, **no allocation** (`10` §7). This is the non-escaping case `capabilities.md` promises
costs nothing, and it is the spelling for the overwhelming majority of higher-order code: `map`,
`filter`, `fold`, a comparator passed to `sort`. It is Rust's `impl Fn`, written the way the
closure literal is written.

### Where a concrete type is required, the callable is a boxed `&Fn`

A bare arrow is available **only in a parameter**, because only there is a fresh type parameter
free to introduce. Anywhere a **concrete** type is required — a struct field, a return type, an
element of a collection, an explicitly annotated `var` — a callable is a **trait object**, spelled
with the mode sigil:

```
struct Button
    on_click: &Fn() -> unit           // stored: escapes, so it is boxed — the & says so

make_adder(n: int) -> &Fn(int) -> int  // returned: escapes, boxed
    x -> x + n
```

These are exactly the positions where a closure **escapes** its creating frame, and an escaping
closure is **heap-boxed** (`05`, `capabilities.md`). The `&` is mandatory there, and mandatory *is*
the design: it is the memory model keeping its promise that **every heap allocation is visible in
the type** (`03`). A callable you merely pass down is free and unmarked; a callable you keep costs
a box, and the `&` is where you see the cost. A bare arrow in a concrete slot is a compile error
that points at the `&Fn` it should have been — never a silent box.

This split is not two mechanisms. `Fn` is the trait in both; the bare arrow is a bound over it,
`&Fn` is a trait object of it, precisely the static/dynamic pair of `10` §6. The parameter
position can afford the static side because it introduces the type parameter; the stored position
must take the dynamic side because a field has one fixed layout. The language routes you to the
cheaper representation wherever it is possible and to the visible box exactly where it is not.

## 7. Capture follows the memory model

A closure body may name variables from the scope it is written in; naming one **captures** it.
Capture needs no new rule — it is the copy/retain discipline of `03` applied at the moment the
closure is formed:

- **Capturing a value copies it in.** `x -> x + n` over a local `n: int` captures a copy of `n`;
  the closure carries its own, and a later change to the outer `n` does not touch it. Because
  every value is copyable this is always well-defined — the same reason a by-value parameter or
  struct field is (`03`).
- **Capturing a `&T` retains it.** A closure that captures a `&Node` holds a counted reference
  like any other alias; the node lives at least as long as the closure, and the reference is
  released when the closure is dropped. An escaping, boxed closure that captures a `&T` therefore
  keeps its capture alive across the escape — which is exactly why it must be boxed.
- **Capturing a `*T` is the unsafe tier, unchanged.** A raw pointer captured into a closure can
  dangle precisely as it can anywhere else (`03`, `05`): the closure may outlive what the pointer
  addresses, and nothing checks it. That is the opt-out `*T` exists to provide, and a closure is
  not an exception to it.

**Capture is implicit** — a variable used in the body is captured, with no capture list to
write. This is the Swift/Kotlin default, and it fits "easier than Rust" (principle 2): the common
case is captured-by-use, the copy/retain choice is *inferred from the captured variable's type*
rather than spelled at the capture site, and there is nothing to annotate. An explicit capture
list — for the cases where the default is wrong (capture a copy of a `&T`'s *pointee*, or move
rather than share) — is a possible later addition (`§ Open a`), not a day-one need.

## 8. Escape decides representation, and it is inferred

Whether a closure is inlined or heap-boxed is **not** a property the programmer states; it is
**inferred** by the escape analysis of `05`, the same analysis that decides whether a local
array's slice may stay on the stack. The rule is uniform:

- **A closure that does not escape its frame is inlined** — no box, no allocation. Passing it to
  a `map`-shaped parameter (§6) is the canonical non-escaping use.
- **A closure that escapes is heap-boxed** — stored in a field, returned, captured by another
  escaping closure. Its type is a concrete `&Fn` (§6), and the box is ARC'd: it lives as long as
  the last reference to it, and its captured `&T`s are released when it dies.

The type system and the analysis agree by construction: the positions that *require* a concrete
`&Fn` type (§6) are exactly the positions escape analysis identifies as escaping, so the box the
`&` denotes is the box the analysis would have forced. **Under `no alloc` an escaping closure is a
compile error** — there is nothing to box into — on the same footing as `&T` creation, growable
arrays, and an escaping local-array slice, all of which `capabilities.md` gates identically. A
`no alloc` program may still use the inlined `map`-shaped closures freely, because those never
allocate.

## 9. Variadic functions

**A sysl function may be variadic**, with the same trailing `...` an `extern` takes (§1):

```
sum(n: int, ...) -> int
    var ap: va_list
    va_start(ap)
    var total = 0
    for i in 0..<n
        total += va_arg[int](ap)
    va_end(ap)
    total
end sum

print(sum(3, 10, 20, 30))
```

**Why it is here at all.** C can do this, so sysl must: a capability C has and sysl lacks is a place
sysl cannot be used, and sysl exists to be used where C is. That is the whole argument, and it
overrides the aesthetic preference for fixed arity that an earlier draft of this chapter mistook for
a decision. The concrete cases are the ones every C codebase has — a logging or formatting function
whose arity is the caller's business, and a function that must be *callable from* C at a variadic
signature or hand a tail onward to one.

**The calling side is the same rule as §1**, and deliberately so: only what varargs can carry may go
in the tail (an integer, a float, a `char`, a raw pointer), passed already widened by C's default
argument promotions. One rule for a foreign callee and a sysl one means a caller does not have to
know which it is reaching, and it means the two share their implementation rather than drifting.

**The receiving side is C's, spelled sysl's way.**

- **`va_list`** is a predeclared type, like `int` and `never` — not a struct the program could have
  written, because its layout is the target ABI's.
- **`va_start(ap)`** readies it. C also names the last fixed parameter here; sysl does not, because
  the function already knows which parameter that is and repeating it is a chance to get it wrong.
- **`va_arg[T](ap)`** takes the next argument as a `T` and advances. C writes the type as a second
  argument, which is not a thing sysl expressions can hold, so it is a type argument — the same
  position every other generic puts one in.
- **`va_end(ap)`** finishes with it.

These four are **language forms, not library functions**, in the same category as `sizeof`: each is
an ABI primitive that no sysl body could implement, so there is nothing to put in the prelude. That
is the line the "no functions built into the compiler" rule actually draws — `print` is on the wrong
side of it because a program *could* write `print`; `va_arg` is on the right side because no program
could write `va_arg`.

**It is as unsafe as C's, and for the same reason.** Nothing checks that the callee asks for the
types the caller passed, or that it stops at the right count; `va_arg` past the end reads whatever
is there. The tail carries no type information, so there is nothing to check against — this is the
one place in sysl where getting it wrong is undiagnosed, and it is why a *safe* variadic (a
homogeneous `...T` collected into a slice, or a heterogeneous `...&Show` over trait objects) is
worth adding **beside** it later (`§ Open i`), never instead of it.

## 10. What is deliberately absent

- **No `fn` / `func` / `lambda` keyword.** A function is a name and a parameter list; a closure is
  an arrow. Neither takes an introducer, consistent with the keyword-lightness of the enum,
  struct, and method forms.
- **No distinct function-pointer type.** A capture-free callable is a `Fn` with an empty
  environment, spelled the same as any other; there is no `fn(int) -> int` primitive-pointer type
  beside the trait.
- **No currying, no partial application.** `Fn(A, B) -> C` is a two-argument callable, not
  `Fn(A) -> Fn(B) -> C`; a bare arrow type is `A -> B` with a single domain, and multi-argument
  types are parenthesized `(A, B) -> C` (§6), never chained. Partial application is a library
  concern (a closure that captures the first argument), not a language one.
- *(An earlier draft of this chapter listed "no variadic parameters" here. That was wrong and is
  reversed: a sysl function may be variadic, and §9 is the feature. Anything C can do, sysl must be
  able to do — a capability C has and sysl lacks is a place sysl cannot be used, and nothing belongs
  on this list for merely being unfashionable.)*

---

## Open (not yet decided)

- **a. Explicit capture lists.** A syntax at the closure for overriding the inferred capture mode
  — capturing a copy of a `&T`'s pointee, or capturing by a distinct alias — as Swift's `[weak
  self]` / C++'s `[=]`/`[&]` do. The implicit default (§7) covers the common cases; a list is the
  escape hatch for the rest, deferred until real code needs it.
- **b. Closures over generic and trait-bound parameters.** A closure that captures a value of a
  generic function's type parameter, or that is itself generic, interacts with monomorphization
  (`10`) in ways the top-level cases do not exercise yet. The static/dynamic split of §6 is the
  frame; the corners are open.
- **c. `FnMut` / `FnOnce`-style distinctions.** Rust splits callables by how they use their
  captures (read, mutate, consume). sysl has no move semantics, so the *consume* distinction does
  not arise; whether a *mutating* closure (one that captures a `*T` or `&T` and writes through it)
  needs a separate trait, or is just an ordinary `Fn` whose captures happen to be mutable, is open
  and waits on real mutating-closure code.
- **d. The bare-arrow type in a `var` annotation.** §6 makes the bare arrow a parameter-only
  sugar and requires `&Fn` in concrete slots. Whether an explicitly annotated local `var f: int
  -> int = …` should be rejected (forcing `&Fn` or inference `var f = …`) or specially permitted
  is a small corner left until the closure implementation exercises it.
- **e. Named associated functions and methods as first-class values.** §5 lets a top-level
  function be used as a callable. Whether `Point.origin` or `p.dist` (an associated function, a
  bound method) may likewise be passed as an `Fn` — and how a bound method carries its receiver —
  joins this chapter with `08` and is not settled here.
- **f. A link name distinct from the sysl name.** Today an `extern`'s name *is* the symbol (§1), so
  reaching a C function means taking its spelling — and a prelude extern occupies that name for
  every program. An override (Rust's `#[link_name]`, a leading string) would let `extern` bind
  `snprintf` to a sysl-shaped name, and would let the prelude keep its own primitives out of the
  user's namespace. Additive; deferred until a real case needs it.
- **g. `va_copy`, and a `va_list` that crosses a call.** §9 covers reading a tail in the function
  that received it. Passing an `ap` on to another function (C's `vprintf` shape) and duplicating one
  (`va_copy`) are the rest of C's surface here, and both belong — they are simply not built yet.
- **h₂. A variadic method.** §9's ellipsis reaches a free function and an `extern`; a method's
  parameter list is a separate production (it carries a receiver) and does not take one, so
  `add(self, n: int, ...)` is a parse error. C has no methods, so no C capability is denied — but the
  inconsistency with a free function is a gap rather than a decision.
- **i. A *safe* variadic beside the C-faithful one.** A homogeneous `...T` collected into a slice
  (Go's, Swift's) or a heterogeneous `...&Show` over trait objects would be checked, which §9's
  cannot be. It is additive and wanted; the trait-object half waits on dynamic dispatch (`02`).
- **h. Capability gating for externs.** An `extern` reaching into libc is exactly the kind of thing
  `capabilities.md` exists to gate, and a freestanding `no alloc` target's externs are a different
  set from a hosted one's. Which capability an extern requires — and whether that is a property of
  the declaration or of the module — waits on capabilities being implemented at all.
