# Design Decisions: Functions and Closures

**Status:** the top-level function surface is written against the implementation that already
exists — keyword-less declarations, expression and block bodies, `return`, forward reference,
recursion, and `extern` including its variadic `...` — and ratifies it. **Closures and the nested
functions of §5a are built too**, so every section here describes something that exists; the rest of
the docs lean on them — `05-escape-analysis.md` heap-boxes an escaping closure, and
`capabilities.md` gates escaping closures behind `alloc` and inlines the non-escaping ones. Where a
closure section commits to a spelling or a representation it says so; the *open* list at the end
records what is deliberately left for later.

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
block whose trailing statement is not an expression has type `unit`. A result is not the only
position `unit` may stand in: it is the language's **zero-sized** type, so a parameter or a field of
it is legal and simply occupies nothing, and what is refused — a pointer or reference to it, an array
or a slice of it — is refused for wanting something to point at rather than for the absence of a
value (`00 §12`). `f(x: unit)` is dropped from the emitted signature, which is what makes
`Result[unit, E]` writable.

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
could not have written: `Option` and `Result` are library enums, `unwrap` is a library member. The
one thing a program genuinely cannot write for itself is the *first* call out of sysl — into libc
on a hosted target, into a driver primitive on a bare one. `extern` is that seam and nothing more,
which is why it is a declaration form rather than a set of known names.

What crosses the boundary is the programmer's business. A scalar or a `*T` matches C directly; a
`string` or a `&T` is a sysl layout that C has no notion of, and handing one over is the same kind
of promise `*T` already is. Capability gating — an extern reaching libc plausibly needs `os` — is
open (`§ Open h`).

**What crosses it *by value* is not.** A struct, a tuple, a view, an enum — every aggregate — is
handed over in whichever registers the machine's C convention names, which is not the same as the
registers a sysl-to-sysl call would use and is not what LLVM does with an aggregate left to itself.
So a foreign declaration is emitted in the *coerced* types that convention asks for and the call
converts each value into and out of them; the shape a program wrote is unchanged, and where the
coerced form is wider than the value the surplus is what the convention leaves unspecified. The four
conventions and how each was measured are `targets.md`. Nothing about this is visible in a program,
and nothing about it applies to a struct handed over behind a `*T`.

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
  An **aggregate** — a struct, an enum, a tuple, a view — may go there too, and travels under exactly
  the classification a declared parameter of that type gets (`targets.md`), which is what C does with
  one as well. What is refused *here* and not at a declared parameter is a `bool`: C would promote it
  to `int`, and sysl has no conversion that says so (`01`), so there is nothing to promote it *with*.

  **A sysl function's tail is narrower than a foreign one's, and only in this one respect.** An
  aggregate crosses to a foreign callee because whoever compiled the other side applies the same
  classification; it does not cross to a sysl one, because there it is the callee's *own* walk (§9)
  that reads the tail back, and the walk reads one register at a time. The refusal says so rather than
  saying the argument is unsuitable, since the same argument is fine one call away.
- **A tail argument is passed already widened**, by C's default argument promotions: an integer
  narrower than 32 bits becomes `i32` or `u32` following its own signedness, and an `f16` or `f32`
  becomes `f64`. This is not something the ABI can be left to do — LLVM promotes nothing on its own,
  and a narrow value handed over as written is read back out of the wrong number of bytes. The
  widening is therefore part of the call, and visible in the tree as an ordinary conversion rather
  than buried in the emitter.

**An extern may name its symbol separately.** A string before the name is what the linker resolves;
the identifier after it is what the program calls it by.

```
extern "snprintf" fmt(buf: *u8, n: usize, fmt: *u8, ...) -> int
extern "abs" magnitude(n: int) -> int
```

Without one the two are the same, which is the common case and stays the default. The separation
exists because a symbol's spelling belongs to whoever exported it: it may be shaped nothing like
sysl, it may be a name the program wants for something of its own, and — the case that forced it —
a declaration in the **library** would otherwise spend that name out of every program's namespace.
The library renders integers and floats through `snprintf`, and a program that declares `snprintf`
itself must not collide with it.

The symbol must be one a linker could resolve — letters, digits, `_`, `$`, `.` — and a string that
is not is rejected at the declaration rather than emitted as malformed IR. Two declarations may
share one symbol under different sysl names; the module declares each symbol once.

A link name is an `extern`'s alone. A sysl function is *defined* here, and what it is called is its
name.

**What this does not do.** Nothing here changes what a *sysl* function's own symbol is: it is the
name, unmangled, so a program that defines `abs` collides with libc's whatever else it declares.
That is a question for the module system (`13`) rather than for this seam.

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

The rest of this chapter is closures, and all of it is **built**. A closure is a function value
carrying an environment; the three questions it raises are how it is *written*, what *type* it
has, and how *capture* interacts with the memory model. The answers below are what the
implementation does, and `05` and `capabilities.md`, which already assumed closures, name
something that exists.

## 5. Closure literals — the arrow form

A closure literal is **parameters, an arrow, and a body**, reusing the `->` the language already
spends on function return types and match arms:

```
x -> x + 1                    // one parameter: no parentheses
(x, y) -> x + y               // two or more: parenthesized
() -> next_id()               // none: empty parentheses

xs.map(x -> x * 2)            // the everyday use, at a call site

var log_both = x ->           // block body by indentation, like any function
    log(x)
    print(x)
```

- **One parameter drops its parentheses** — `x -> …` — because that is the overwhelmingly common
  case (`map`, `filter`, `each`) and the bare name reads cleanly inside a call. **Two or more are
  parenthesized** — `(x, y) -> …` — and **zero is the empty pair** `() -> …`. The paren rule is
  the same one the function-*type* spelling uses (§6), so a closure and the type it inhabits look
  alike.
- **The body is an expression or an indented block**, exactly as a named function's is (§1); the
  block's trailing expression is the result. An **indented block reaches as far as the off-side rule
  does**, which is to say not inside an argument list: a bracket suspends indentation until it closes
  (`00` §9), so there is no block for a body to be there and a closure passed straight to a call
  takes the expression form. Binding it to a name first is what a multi-statement one is written as.
  *(An earlier draft of this chapter showed `xs.each(x -> …)` over three indented lines. That does
  not parse and never could have under `00` §9; the example above is the shape that does.)*
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

This holds at **both** of §6's representations, and the two are worth naming separately because a
name reaching one is no evidence it reaches the other: a function goes to a bare-arrow parameter,
where it monomorphizes like any other closure, and it is **erased into a `&Fn`** wherever a concrete
type is required — a field, a return type, an element of a collection, an annotated `var`. A
generic function and an `extern` both go, the object's arguments being what says which instantiation
is meant. A **nested** function is the one exception, and §5a gives the reason it has to be.

## 5a. Nested functions — a closure with a name

A function declaration may stand inside a function body, spelled exactly as a top-level one:

```
sort(xs: []int)
    swap(i: usize, j: usize)                  // sees xs
        var t = xs[i]
        xs[i] = xs[j]
        xs[j] = t

    part(lo: usize, hi: usize) -> usize       // may call itself and may call swap
        ...

    quick(lo: usize, hi: usize)
        if lo < hi
            var p = part(lo, hi)
            quick(lo, p)
            quick(p + 1, hi)

    quick(0, xs.len)
```

**This is not a second mechanism.** A nested function *is* a closure — capture follows §7 and
representation follows §8, both unchanged. One that captures nothing and does not escape is an
ordinary static function with a private name; one that captures is a closure; one that escapes is
heap-boxed and is a compile error under `no alloc`. Nothing here needs a rule that a closure literal
did not already need.

**What the name buys is the reason the form exists**, because a `var f = x -> …` does not give it:

- **Recursion.** A closure literal's name is not in scope in its own initializer, so an anonymous
  closure cannot call itself. A nested function's name is in scope in its own body.
- **Mutual recursion**, which falls out of the hoisting rule below.
- **Written types.** A nested function states its parameters and its result the way every function
  does, so it needs no context to be inferred from — which sidesteps rather than settles the
  annotation awkwardness of `§ Open d`.
- **A body of statements reads as one.** An indented block under a name is the shape the rest of the
  language already uses for "here is a thing that does something"; an arrow bound to a `var` is the
  shape for "here is a value".

**Names are hoisted; captures are not.** These are two different scopes and the difference is
load-bearing:

- **Every nested function in a block is in scope throughout that block**, so two may call each other
  and one may be used above where it is written — the same rule §4 gives top-level declarations, and
  the reason `quick` above may call `part` whichever order they are written in.
- **A nested function may capture only locals declared above it.** A capture takes the variable's
  value, or a share of its reference, at the moment the function is *formed* (§7), and a local
  written below it does not exist yet.

So **name resolution is per block and capture is per position.** Splitting them is what lets mutual
recursion work without smuggling in a read of an uninitialized local, and both halves are checkable
where they are written.

A nested function may contain another, and capture reaches through: an inner one may name the outer
one's locals and parameters as well as its own. Nothing outside the body can name a nested function,
so `13`'s visibility levels do not apply to one — there is nothing for `private` to restrict, and
writing it is an error rather than a no-op.

It states its own signature, so it may also take a `...` and walk its own tail (§9): the environment
holds the first parameter slot the way a method's receiver does, and the tail anchors after what the
program wrote.

### One environment per block, and the three things that follow from it

**The nested functions of a block share one environment**, and each is a member of it. That is what
makes both halves of the rule above true at once: every name is in scope throughout the block,
because they are members of one thing, while what may be *captured* is settled where the group is
written.

- **A sibling call and a recursive call are the same call** — a call on the receiver the body
  already has — so neither takes a share of anything and no cycle can form.
- **The environment holds the *addresses* of the block's variables, not copies of them.** This is the
  one place a nested function parts company with a closure literal, and the reason is that it may
  not escape: a value nothing outside can name is a value nothing can store or return, so a row of
  pointers into the frame is sound. It is also the whole point of the form — `swap` in the example
  above assigns to `xs`, and a body of statements that could not assign to the variable it was
  written beside would not be worth the name it has.
- **Everything the group captures must be declared above it**, since that is where the environment
  is formed. A call written above the group is told so.

**A nested function is called where it is written and is not a value.** §5 lets a *top-level*
function stand where a callable is expected, because it captures nothing and there is nothing to
carry; a nested function's environment is the frame it was declared in, and a callable value is a
way of carrying that frame out of itself. For the same reason a body reaches its **own** group and
its own captures and no further: a closure written beside a nested function cannot call it, and
neither can a nested function of an inner block. What several bodies share is a top-level function.

**Open here:**

- ~~**A nested function that is recursive, capturing *and* escaping is a reference cycle.**~~
  **Cannot arise.** Nothing outside the body can name one, so it cannot be stored or returned, and
  the shared environment means a recursive call takes no share of anything. `weak` is not needed
  here after all.
- ~~**Whether one shadows a top-level function of the same name.**~~ **Yes** — ordinary lexical
  scoping, the same answer every other name in the language gets. A rule making functions the
  exception would be a rule to remember for no gain.
- ~~**Whether a nested function may be generic.**~~ **No.** Its type arguments would have nowhere to
  come from: nothing outside the body calls it, so every call site is inside the body and the
  declaration is already as specific as its uses. `§ Open b` still holds the closure half.
- **Whether a nested function should be reachable from a closure or an inner block written beside
  it.** Refused today, for the escape reason above. Both could be allowed by having the inner
  environment carry the outer one, which is the same widening in each case; what it costs is that
  a closure's captures would no longer all be values it owns.

## 5b. Several results — a list, not a tuple

A function may declare more than one result, and a binding may take them apart:

```
divmod(a: int, b: int) -> int, int
    a / b, a % b

val q, r = divmod(7, 2)                   // q = 3, r = 1

civil_from_days(d: int) -> int, int, int  // year, month, day
    ...

var y, m, day = civil_from_days(20520)
```

**This is a property of the signature, not a new type**, and that is the whole design. There is no
tuple value anywhere: nothing may store one, put one in a field, hold one in an `Option`, or pass one
along except by forwarding it whole. A multi-result call may appear in exactly three places — the
right-hand side of a binding, the right-hand side of a multi-assignment (`00 §2`), and a `return` or
trailing expression of a function whose own result list matches.

**Sysl has tuples as well** (`00 §13`), and the two coexist on purpose. The discriminator is whether
the carrier ever exists: a result list is the light form for several things travelling from callee to
caller and nothing afterwards, and a tuple is the type for several things that must be *held* — in a
variable, a field, an `Option`, a container, or a generic argument. `-> int, int` and
`-> (int, int)` therefore look alike and mean different things, which is the price of having both;
what blunts it is that `val a, b = f(x)` takes either apart, so the choice is the callee's and the
call site does not change when it changes.

**Destructuring is `00 §2`'s multi-assignment, not a second mechanism.** `val a, b = f(x)` binds; `a,
b = f(x)` assigns to places that already exist; and the evaluation rule is the one already written
there — the call happens once, in full, before anything is bound or stored. A right-hand side is one
of exactly three things, and `00 §13` lists them together: a list of expressions, a call with a
result list, or a tuple.

**Arity is checked and there is no partial take.** `val a = divmod(7, 2)` is an error rather than a
binding of the first result: a function that says it yields two things yields two things, and
silently dropping one is how a caller ends up reading the wrong number. A result that is genuinely
optional to the caller is a sign the function wants a struct.

**The three places are enforced at one point, and the rest follows.** A result list is a type only
between a signature and the expression that uses it: the moment a call is used somewhere a list is
allowed, it becomes the tuple its parts lay out as, and everywhere else it is refused. So "nothing
may store one" needs no rule about fields or containers or `Option` — a field cannot be *declared*
as one (that position asks for a type, and a result list is not one), and a value cannot be held as
one because the call was refused before anything could hold it. The permission covers one
expression and nothing inside it, so `val a, b = f(g())` refuses `g` exactly as any other position
would.

**The callee writes the values without parentheses**, and `-> int, int = (1, 2)` is refused. It is
the same claim from the other side: if the parentheses were allowed here they would build the
carrier the form says never exists, and a reader would be entitled to ask what happened to it.

**A comma binds to the whole line.** A result list is the last thing on its line by construction,
which is what tells it apart from every other comma: an inline branch is part of a larger
expression, so `-> int, string = if c then 1 else 0, "x"` reads as two results rather than as a
branch whose `else` swallowed the comma. A one-line branch that wants to yield a list of its own
writes the branch over several lines instead.

**When to want a struct instead.** A result list is at its best where the components are few and
obviously ordered — `divmod`, a quotient and a remainder, a value and a count. It is at its worst
where they are several and alike, which is where the names carry the meaning: `guide/datetime` gets
a year, a month and a day out of a day number, and `Civil { year, month, day }` reads better at every
call site than `-> int, int, int` does, because `c.month` says what `1` does not. The list is the
lightweight answer, not the general one.

**Open here:**

- **How a result list appears in a callable type.** §6 makes the bare arrow parameter-only sugar and
  requires `&Fn` elsewhere; `(int) -> int, int` is ambiguous about where the parameter list ends, so
  a callable yielding several results needs a spelling — probably `(int) -> (int, int)`, which
  reintroduces the parentheses this section otherwise avoids. Closures are built, so nothing blocks
  it but the choice: today `&Fn(int, int) -> (int, int)` is read as yielding one **tuple**, which is
  a callable a program can already write and pass, and what has no spelling is the *list*.
- **Whether a binding may annotate its parts.** `var a: int, b: int = f(x)` is noisy and
  `var a, b: int = f(x)` reads as though it types only `b`. Inference covers the ordinary case; the
  spelling for the case it does not is unsettled.
- **Whether an `extern` may declare one.** C returns one value, so a multi-result extern would have
  to mean a struct return, and that is an ABI question rather than a language one. Until it is
  settled, an `extern` result is one type and a comma there does not parse.

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

**A program may implement the call trait itself**, which follows from `Fn` being a trait like any
other and is worth saying because it is useful: a struct with an `impl Fn(int) -> int` is callable
with `d(5)`, may be passed to a bare-arrow parameter, and may be erased into a `&Fn`. It is written
with the arrow, the same way the type of a callable is written everywhere else. **One arity per
type**, since each would give it a member named `call` and a type's members are one namespace (`08`)
— sysl has no overloading, so this falls out rather than being a rule of its own.

**Anything whose type says it is callable may be called**, wherever it was read from: an element of
an array of them, a part of a tuple, an item of a container, the result of another call. The head of
a call is looked at rather than required to be a name.

This split is not two mechanisms. `Fn` is the trait in both; the bare arrow is a bound over it,
`&Fn` is a trait object of it, precisely the static/dynamic pair of `10` §6. The parameter
position can afford the static side because it introduces the type parameter; the stored position
must take the dynamic side because a field has one fixed layout. The language routes you to the
cheaper representation wherever it is possible and to the visible box exactly where it is not.

## 6a. A function's address — `*extern(A, B) -> R`

`Fn` is sysl's answer to "what is the type of a callable", and it is the right one for sysl: a bound
where the callable is passed down, a boxed object where it is kept, and in both cases something with
an environment beside it. **C has no notion of an environment.** What a C interface means by a
function pointer is one word holding the address of code, and a program that could not produce one
could not be given to any C interface that calls back.

That is not a small set. `qsort` and `bsearch` take a comparison, `signal` and `sigaction` take a
handler, `atexit` takes a hook, `pthread_create` takes a thread body, `scandir` takes a filter, and
every library with a `_set_callback` in it takes one of these. All of them could be *declared* — the
parameter is a `void *` and `*u8` spells that — and none of them could be **called**, because there
was nothing to pass. The same absence shut the other direction: an address `dlsym` hands back could
not be called either, so the whole dynamic-loading interface was declarable and useless.

```
extern qsort(base: *u8, n: usize, size: usize, cmp: *extern(*u8, *u8) -> i32)

compare(a: *u8, b: *u8) -> i32
    var pa: *i32 = ptr_cast(a)
    var pb: *i32 = ptr_cast(b)
    *pa - *pb

var xs = [30i32, 10i32, 20i32]

qsort(ptr_cast(&xs[0]), 3usize, 4usize, &compare)
```

**`&f` is the address, and the `&` is the same one `03` gives every other address.** A bare `f` keeps
the meaning §5 gives it — the capture-free closure — because a spelling that meant a sysl callable in
one slot and a C address in another would be choosing silently between two representations that share
nothing. Where a `*extern` is wanted, the `&` is written; where a callable is wanted, it is not.

**It is its own type rather than a mode over the call trait.** `*Fn(A) -> R` was already taken, and
by the right thing: an unowned trait object over a callable, two words, a method table beside the
value (`02`). Spelling both the same would put a fat pointer where C reads one word, and the mistake
would be invisible. So the three are three:

| written | what it is | width |
|---|---|---|
| `A -> R` at a parameter | a bound over `Fn`, monomorphized and inlined (§6) | nothing |
| `&Fn(A) -> R` | a heap-boxed callable, counted (§6) | two words |
| `*extern(A) -> R` | the address of code compiled to C's convention | one word |

It is also not `*T` of anything. A raw pointer addresses a **value** — one that can be read through,
written through, and measured — and there is no value at the end of this one, so every operation `*T`
carries would have needed an exception. What an address of code can do is the one thing it is for: be
called, and be handed to whoever asked for it.

**A call through one goes out under C's convention**, because that is what the type said was at the
other end — the same lowering an `extern` call gets (§1), aggregates and all. Nothing checks that the
signature is the signature the code at that address was compiled with; that is the promise the `*`
announces, and it is the same promise every raw pointer makes.

**`ptr_cast` reaches between an address of code and an address of bytes**, which is how a `*u8` from
`dlsym` becomes callable and how one goes back to a C interface that stores callbacks as `void *`.

**`null` is a `*extern`**, since "there is no callback, use the default" is a state several C
interfaces have, and two compare by address so a program can ask whether one is installed.

### What has no address, and why

Each of these is refused because the address would not be an address of what its type says, and
nothing downstream could notice:

- **A generic function.** It is a body per set of type arguments (`10 §7`), so there is no one body
  to name. A wrapper calling it at the arguments wanted is what has an address.
- **A variadic function.** C reads a tail relative to the last named argument, and a `*extern` states
  the arguments a call passes. A signature that fixed the tail would not be describing a variadic
  function.
- **A nested function.** Its environment is the frame it was declared in (§5a), and what would have
  to travel beside the address is that frame.
- **A closure.** The same reason with the name taken off: a closure is a struct and an
  implementation (§8), and one word has nowhere to put the struct.
- **A `#test` function.** Every build but `sysl test` drops it (`testing.md`), so its address would
  be of a definition the program does not have.
- **Any signature carrying an aggregate** — a struct, a tuple, a data enum, a view, a `string`. An
  aggregate crosses to C in whichever registers that machine's convention names (`targets.md`), and
  a sysl definition did not put it there. This is the one refusal that is a **restriction rather than
  a consequence**: what closes it is an adapter emitted beside such a function, entered under the C
  classification and calling the sysl one. Until there is one, the refusal names the parameter, since
  an address that is quietly wrong is worse than no address. A callback taking the parts behind a
  `*T` is the shape that works today, and it is what C interfaces overwhelmingly use anyway.

The test is made by **shape** rather than by asking the target's classification, so a program
accepted for one machine is accepted for every machine.

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

**Building it settled that no analysis is needed here at all**, and the sentence above is why: the
positions requiring `&Fn` and the positions that escape are the same set, so the *type* already says
which representation was chosen. A closure at a bare-arrow parameter is a struct passed by value; a
closure reaching a slot that spells `&Fn` is boxed by the same coercion that boxes anything into a
counted reference, and erased by the same one that makes any other trait object. Escape analysis is
not consulted, and adding it would be asking a second mechanism to agree with the first.

### What a closure is, underneath

A closure literal is **a struct and an implementation** — nothing else, and nothing new:

- its **fields are its captures**, in the order the body first names them, so a value is copied and
  a `&T` is retained by the ordinary field-by-field construction of a struct, which is why §7 needed
  no rule of its own;
- its **`impl` is of the call trait for its arity**, and the body becomes that implementation's one
  member, `call(*self, …)`.

The receiver is `*self` rather than `self` so that a call neither copies the environment nor retains
every `&T` in it. It also decides `§ Open c` in passing: a closure that writes through a captured
name writes to its own field, so the write is still there at the next call, and a *mutating* closure
is an ordinary `Fn` whose captures happen to be mutable rather than a trait of its own.

The call traits are declared one per arity — a declaration cannot promise a `call` of an arity it
does not know — which is the same reason a tuple's arity is part of its name (`00` §13). Nothing
about that is visible in a program: every one of them is spelled `Fn(A, B) -> R`, in a written type
and in a diagnostic alike.

## 9. Variadic functions

**A sysl function may be variadic**, with the same trailing `...` an `extern` takes (§1):

```
sum(n: int, ...) -> int
    var ap: va_list
    va_start(ap)
    var total = 0
    for i in 0..<n
        total += va_arg(ap)
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

The **one** place the two differ is an aggregate, and the difference is a consequence of this section
rather than a second rule: a foreign callee's aggregate is classified by its machine's C convention
and read back by a compiler that applies the same one, where a sysl callee reads its tail with the
walk below and the walk takes one register at a time. So the tail of a sysl variadic is scalars, and
the refusal names the walk as the reason.

**The receiving side is C's, spelled sysl's way.**

- **`va_list`** is a predeclared type, like `int` and `never` — not a struct the program could have
  written, because its layout is the target ABI's.
- **`va_start(ap)`** readies it. C also names the last fixed parameter here; sysl does not, because
  the function already knows which parameter that is and repeating it is a chance to get it wrong.
- **`va_arg(ap)`** takes the next argument and advances. C writes the type it is reading as a second
  argument, which is not a thing a sysl expression can hold; here it comes from the **context the
  value is read into** — `var v: int = va_arg(ap)`, `total += va_arg(ap)`, `take(va_arg(ap))` — the
  same place `None` and `Ok(5)` get theirs. Where the context says nothing the form is refused rather
  than guessed at, and the diagnostic names the annotation to write.

  *(An earlier draft of this section spelled it `va_arg[T](ap)` and called the type argument "the
  same position every other generic puts one in". There is no such position: `10` §2 gives square
  brackets in an expression to indexing, and call-site type arguments are refused language-wide,
  `10 § Open a`. What that leaves is context and an annotation, which is what the implementation
  does. `va_arg` is the strongest customer that open item has — the annotation costs a whole
  statement in the one position, a bare `print(va_arg(ap))`, where the surrounding expression says
  nothing.)*
- **`va_end(ap)`** finishes with it.
- **`va_copy(dst, src)`** starts `dst` where `src` has reached, so a tail can be walked twice.

These five are **language forms, not library functions**, in the same category as `sizeof`: each is
an ABI primitive that no sysl body could implement, so there is nothing to put in the library. That
is the line the "no functions built into the compiler" rule actually draws — `va_arg` is on the
right side of it because no program could write `va_arg`. `print` was on the wrong side, and is
where it belongs now: library sysl, reached by a desugaring (`04`, *Printing*).

**It is as unsafe as C's, and for the same reason.** Nothing checks that the callee asks for the
types the caller passed, or that it stops at the right count; `va_arg` past the end reads whatever
is there. The tail carries no type information, so there is nothing to check against — this is the
one place in sysl where getting it wrong is undiagnosed, and it is why a *safe* variadic (a
homogeneous `...T` collected into a slice, or a heterogeneous `...&Show` over trait objects) is
worth adding **beside** it later (`§ Open i`), never instead of it.

### Handing a walk on

C's other half of this is `vprintf`: a function receives the tail and does not read it itself but
passes it to somebody who does. The parameter type is **`*va_list`**, and the call writes `&ap`:

```
report(n: int, ap: *va_list) -> int
    var total = 0
    for i in 0..<n
        total += va_arg(ap)
    total
end report

log(n: int, ...) -> int
    var ap: va_list
    va_start(ap)
    var t = report(n, &ap)
    va_end(ap)
    t
end log
```

**A bare `va_list` parameter is refused, and §2 is why.** A parameter is a value binding, and a copy
of a walk is not a walk: the callee would advance its own copy and the caller would see nothing
happen — which is the one thing the form exists to do. §2 already says how a function is given
something to advance: it takes it by *type*, as a `*T`. So this needs no rule of its own, and the
diagnostic on `ap: va_list` names the spelling that works.

Two things follow, and both are the point. The borrower **advances the lender's own list**, so what
it consumed is gone when the lender reads on — which is what `va_copy` is for, exactly as in C. And
`va_start` still asks for a tail of the function's own while `va_arg` asks only for a walk, so a
borrower reads a tail without having one.

**Returning a `va_list` is refused outright**, foreign or not: the type names the storage a walk
lives in, and there is no value of it to hand back. A `*va_list` is an ordinary raw pointer and is
refused nowhere — it may be returned, held in a field, or carried in a struct, under `03`'s rules
and nobody else's.

**An `extern` is written in C's spellings, and takes either.** A foreign declaration transcribes a C
header, so it says what the header says: `va_list` is C's by-value parameter, the one `vprintf`
takes, and `*va_list` is C's `va_list *`, the one a function that must advance its caller's own walk
takes. The refusal above is about a *sysl* body, which could do nothing with a copy of a walk; a
foreign body is C's, and C's `vprintf` is precisely a body that reads one.

```
extern vprintf(fmt: *u8, ap: va_list) -> i32

log(fmt: *u8, ...) -> i32
    var ap: va_list
    va_start(ap)
    var n = vprintf(fmt, &ap)
    va_end(ap)
    n
end log
```

**The call writes `&ap` for either spelling**, because the address is the only thing sysl has and it
is what both are formed from. What actually crosses over for the by-value one is a **target**
question: C's `va_list` is a different type on every machine and is passed three different ways — the
value in the storage on Darwin arm64, the storage's own address on x86-64 System V, the address of a
fresh copy on AAPCS64. All three pass one pointer, so the difference cannot be recovered from the
emitted types; the compiler reads it off the target it was told to build for (`targets.md`).

### The ellipsis reaches a member, and a nested function

A member is a function with a receiver in front, so a `...` reaches one under exactly these rules:

```
struct Log
    prefix: int

    note(self, n: int, ...) -> int
        var ap: va_list
        va_start(ap)
        …
    end note
end Log
```

The receiver is a parameter once the member is lowered, and it is therefore what a tail anchors on —
so `only(self, ...)` is a complete declaration while a receiverless `make(...)` has nothing named
before its ellipsis and is refused, exactly as `f(...)` is. The same holds for an associated
function, a member of a generic type, a member with type parameters of its own, and a **nested
function** (`§5a`), whose environment holds the first parameter slot the way a receiver does.

A **trait** may declare one, and an implementation must agree about it: a `...` is part of what a
caller may write, so having one where the trait has none is a different promise rather than a wider
one. What such a trait cannot be is a **trait object**. A call to a variadic names the callee's whole
function type — that is how it says where the declared parameters stop — and a slot in a method table
is a word that names none. A bound still reaches the method, because that call knows which function
it is reaching. This is object safety in the shape `02` already gives it, alongside the `Self` rule.

## 10. What is deliberately absent

- **No `fn` / `func` / `lambda` keyword.** A function is a name and a parameter list; a closure is
  an arrow. Neither takes an introducer, consistent with the keyword-lightness of the enum,
  struct, and method forms.
- ~~**No distinct function-pointer type.**~~ **Corrected, and only at the C boundary.** For sysl's
  own callables the sentence stands: a capture-free callable is a `Fn` with an empty environment,
  spelled the same as any other, and there is no `fn(int) -> int` beside the trait. What it was wrong
  about is the seam — a C function pointer is one word and a `&Fn` is a trait object, so a language
  that had only the trait could be given to no C interface that calls back. `*extern(A) -> R` is that
  word and nothing else (§6a). The general rule and the exception are both about representation, and
  the exception is where the representation is somebody else's to choose.
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
- ~~**b. Closures over generic and trait-bound parameters.**~~ **Closed, and neither half needed a
  decision.** The first half — a closure that captures a value of a generic function's type parameter
  — follows from §8's representation with nothing added: the closure is a struct whose field has that
  type, so it is monomorphized with the body that holds it, once per instantiation, and the bound the
  *enclosing* declaration carries is what an operator in the body dispatches through. An escaping one
  is a `&Fn(T) -> T` returned out of a generic body, which is the same erasure §6 already describes.
  The second half — a closure that is **itself** generic — is answered by `02`: a callable's type is
  the library's `FnN` trait, and a trait's member may not declare type parameters of its own, because
  no table slot can hold a function that does not exist until a call names its types. So there is
  nothing for an arrow to declare them *for*, and this is `§10`'s currying situation again: a rule
  elsewhere already decides it. This item was mis-filed as open — it asked whether the implementation
  reached the corners, not what the language should do about them.
- ~~**c. `FnMut` / `FnOnce`-style distinctions.**~~ **Settled by §8's representation.** Rust splits
  callables by how they use their captures; sysl has no move semantics, so the *consume* distinction
  never arose, and the *mutating* one falls out: a capture is a field, `call` takes its receiver by
  address, so a closure that writes through a captured name writes to its own field and the write is
  still there at the next call. A mutating closure is an ordinary `Fn`, and there is no second trait.
- ~~**d. The bare-arrow type in a `var` annotation.**~~ **Rejected**, as §6 said it should be: an
  annotated local is a concrete slot like any other, so it takes `&Fn`, and the diagnostic names the
  box the arrow should have been. Permitting it would have made the same spelling mean an inlined
  callable in one position and a boxed one in another.
- **e. Named associated functions and methods as first-class values.** §5 lets a top-level
  function be used as a callable. Whether `Point.origin` or `p.dist` (an associated function, a
  bound method) may likewise be passed as an `Fn` — and how a bound method carries its receiver —
  joins this chapter with `08` and is not settled here. *(The half of this that was about the C
  boundary is closed by §6a; what is left is the sysl-callable question, which is what it was always
  really asking.)*
- **f. A symbol for a sysl *definition*.** *(Untouched by §6a, which is worth saying because the two
  look alike: a callback travels as an address at run time and never needs a name, so nothing about
  giving C a function required deciding what that function is called.)* §1 lets an `extern` name the symbol it resolves to; the
  other direction — a sysl function exported under a chosen symbol, C's side of the same seam — has
  no spelling. It is the same question as how a sysl function's symbol is decided at all, and `13`
  has since settled that, so what is left here is the **spelling** rather than anything to wait for.
- ~~**g. `va_copy`, and a `va_list` that crosses a call.**~~ **Built** (§9, *Handing a walk on*).
  `va_copy` is a fifth language form, and a walk is handed on as a `*va_list` — which turned out to
  need no rule of its own, because §2 already says a function is given something to advance by
  *type*. What is left of it is `g₂` below.
- ~~**g₂. A `va_list` across the foreign boundary.**~~ **Built** (§9, and `targets.md`). It was an
  ABI question rather than a language one, and what it waited on was a target: an `extern` is now
  written in C's own spellings, and what a call hands a by-value `va_list` is read off the machine
  being built for. So `vprintf` is reachable from sysl, and C's varargs surface has nothing left in
  it sysl cannot say.
- ~~**h₂. A variadic method.**~~ **Built** (§9, *The ellipsis reaches a member*). A member is a
  function with a receiver in front, so the ellipsis reaches one under the rules a free function's
  tail already follows, and the receiver is what the tail anchors on. A trait may declare one; what
  it cannot then be is a trait object, for the reason object safety gives.
- **i. A *safe* variadic beside the C-faithful one.** A homogeneous `...T` collected into a slice
  (Go's, Swift's) or a heterogeneous `...&Show` over trait objects would be checked, which §9's
  cannot be. It is additive and wanted, and nothing blocks it any longer — the trait objects its
  heterogeneous half wanted are built (`02`), so what remains is a spelling and a decision to take
  it.
- **h. Capability gating for externs.** An `extern` reaching into libc is exactly the kind of thing
  `capabilities.md` exists to gate, and a freestanding `no alloc` target's externs are a different
  set from a hosted one's. Which capability an extern requires — and whether that is a property of
  the declaration or of the module — waits on capabilities being implemented at all.
