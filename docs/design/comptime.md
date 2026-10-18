# comptime — design

sysl already runs code while it compiles. A `const` function's body is evaluated wherever a constant
is wanted (`const_eval.sysl`, 3.4k lines over the typed tree). Value parameters `[const N: usize]`
and the unrolled `for const` over a pack give generic code its compile-time values. For 0.3.0 the
aim is **general compile-time execution of ordinary sysl code**: what the compiler can run while
compiling should be limited only by what does not exist yet (I/O, addresses, the machine), and not
by markers, missing library support or the phase the compiler happens to be in.

Nothing here is built yet. The note settles what the language says, so the implementation is
mechanical, and lists what the user still decides. Reflection (item 2) is a separate note. This one
sets up the three things it needs: evaluation where types are read, `if const`, and `@assert` per
instance.

## 1. What exists, and the gaps (probed 2026-10-18 on 0.2.4)

| works today | probe |
|---|---|
| `const K: int = sq(4)`, `static val NAMES: [3](string, int) = [...]` | prints `16`, reference examples pass |
| `[K]int` where `K` is a `const` | `a.len` = 16 |
| explicit value argument `f[3]()`, inferred `g(b)` for `[N]int` | `3 4` |
| strings, structs, enums, slices, recursion, `require`, `*self` in a `const` body | declarations.md § A `const` function |

| gap | what happens today |
|---|---|
| **G1** every callee must be marked `const` | `const P: int = plain(2)` → *'plain' is not 'const' … mark it 'const' to call it here* |
| **G2** the standard library marks nothing `const` | `buf()`, `push`, `len`, `sysl.math.max` all refused (*the standard library marks nothing 'const' yet*) |
| **G3** no heap while evaluating | *it boxes a value, which allocates — a heap made while compiling has nowhere to go* |
| **G4** a `const` call cannot stand where a **type** is read | `@assert(sq(3) == 9, …)` and `[sq(2)]int` → *this value is read while declarations are, before any body can be run* |
| **G5** a value argument cannot be an expression | `f[sq(3)]()` → *this is not a type …*, and a follow-on *undefined name 'N'* cascades from it (a diagnostic bug, fixed with this item) |
| **G6** no way to force evaluation inside a body | `const L: int = sq(6)` in a body → *a constant is a module member …* |
| **G7** no compile-time branch in generic code | `for const` exists, but an `if` on `N` or on `T` types both arms for every instance |
| **G8** smaller holes in the evaluator | vectors, atomics, `f16`/`bf16`, calls through a closure or function value |

## 2. Decisions

| # | question | recommendation | why, in one line |
|---|---|---|---|
| 1 | must a function be marked to run while compiling? | **No. Any function may be called where a constant is wanted, and it is evaluated if its body can be.** The refusal names the first thing in the call chain that cannot run (*'K' calls 'plain', which calls 'print' …*). `const f(...)` stays, as a **promise checked at the declaration**: the body must be evaluable, as `const_check` holds it today, so a library can commit to it | D's CTFE and Zig need no marker; Rust and C++ do. Their reason is semver, and the checked promise keeps that. Pleasantness decides it (G1, and G2 disappears with it) |
| 1b | does std then mark its functions? | **Std marks `const` only on functions it promises**: math, `Buf`, `Map`, `Set`, string building and slices. Nothing else needs a mark to be callable | the mark is an API promise; under (1) callability needs none |
| 2 | heap while evaluating | **Allowed.** `&T`, `Buf`, `Map`, `Set` and closures work during evaluation as at run time. **What the evaluation answers is frozen into constant data**: a `[]const T` viewing a `Buf` becomes a static array plus a view of it with a null owner word, the way a string literal is laid down; a `&sync Frozen[T]` (`b.freeze()`) becomes the same array behind static storage; a `&T` to a struct becomes static storage with a null owner word | D lays `new` results into static data; C++20 allows only transient allocation. A null owner word already means "never freed" for literals, so nothing new is needed at run time (G3) |
| 2b | answering something writable | **Refused where it is written**: an evaluation answers heap data only through a read-only type (`[]const T`, `string`, `&sync Frozen[T]`, a `&T` to a struct with no `var` path). A `Buf[T]` or `Map` answered whole gets *answer `b.view()` or `b.freeze()` — what is made while compiling is laid into the object file, and nothing writes it there* | the types already say "nobody writes this", so there is no copy-on-write path and no run-time trap |
| 3 | evaluation where a type is read | **The declaration pass evaluates on demand.** A call in an array length, a value argument, `@assert` or a default parameter is evaluated when that declaration is resolved, after the callee's body is checked. A cycle (*`K`'s length needs `f`, whose body needs `K`*) is reported naming both | Zig and D evaluate lazily, in dependency order. Without it, reflection cannot compute a type (G4, G5) |
| 4 | an expression as a value argument | **`f[sq(3)]()`, `Ring[int, cap(8)]`**: the bracket reads an expression when the parameter is `const` | needed by (3). The parser already reads brackets out of the expression grammar |
| 5 | forcing evaluation inside a body | **A local `const NAME: T = e`** (the type is required, as at module level). It is folded like a module `const`, scoped to its block. `val` stays as it is: a `val` over a call is evaluated at run time unless the optimiser folds it | reuses a spelling sysl already has. Zig writes `comptime e`, D writes `enum x = e`. No new word (G6) |
| 6 | compile-time branch | **`if const cond`**, pairing with `for const`. Only the arm taken is type-checked for each instance, and `cond` must be known while compiling (a `const`, a value parameter, a reflection query). `else if const` chains; a plain `else` is the remainder | the one construct reflection-driven generic code cannot do without. Zig's `if` on a comptime value and D's `static if` both prune (G7) |
| 7 | a compile-time error raised by the program | **`@assert(cond, "why")` is admitted as a statement in a body**, checked for each instance of a generic and in each unrolled copy of a `for const`. Inside an `if const` arm it is Zig's `@compileError` | `@assert` already exists at module level. One construct, not two |
| 8 | types as values (Zig's `fn List(comptime T: type) type`) | **Excluded, not deferred.** sysl has generics and traits for building types; reflection gives *queries* on a `T`. Nothing produces a type from a value | keeps one way to make a type, and inference stays decidable |
| 8b | value parameter kinds | **Unchanged for 0.3.0** (integers, `bool`, `char`, simple enums). A `string` value parameter is a possible addition, opened if reflection needs one | mangling and identity are settled for these only |
| 9 | the remaining evaluator holes | **Fill them**: vectors, `f16`/`bf16` (soft float already exists), and calls through a closure or function value created *during* evaluation. Atomics on a value the evaluation owns are plain loads and stores. The C library's maths stays refused, for the same reason as today | each is a refusal of something the design says is just a value (G8) |
| 10 | a runaway evaluation | the existing step limit and depth 512 stay, and **the step limit becomes `--const-steps N`** for a table generator that legitimately runs long | today a large table hits a fixed ceiling |
| 11 | I/O while compiling (Zig's `@embedFile`) | **Out of scope for this note**; belongs to build configuration (item 6). Nothing outside the program exists while compiling | keeps builds reproducible from sources |

## 3. How it reads

```sysl
import sysl.buf.{Buf, buf}

// no mark: callable while compiling because its body can run
primes_below(n: int) -> []const int
    var out = buf[int]()

    for i in 2..<n
        var p = true
        for d in 2..<i
            if i % d == 0 then p = false
        if p then out.push(i)

    out.view()

static val PRIMES = primes_below(100)
@assert(PRIMES.len == 25, "there are 25 primes below 100")

ring_for[T, const N: usize]() -> usize
    if const N > 64
        @assert(sizeof(T) * N <= 4096, "a ring this large belongs on the heap")
    N

main()
    const SMALL: int = primes_below(10).len
    print(SMALL, PRIMES[24])
```

`PRIMES` is laid into the object file as 25 integers, viewed with a null owner word. `SMALL` is folded to 4.
The `@assert` inside `if const` is checked only for instances where `N > 64`.

## 4. What the analogy checks say

| toolchain | marker | heap | forcing | branch | error |
|---|---|---|---|---|---|
| Zig | none | no allocator at comptime; arrays only | `comptime e` | `if` on comptime value prunes | `@compileError` |
| D | none (CTFE) | `new` allowed, result goes into static data | `enum x = e` | `static if` | `static assert` |
| C++20 | `constexpr`/`consteval` | transient only: must be freed before the end | `constexpr` variable | `if constexpr` | `static_assert` |
| Rust | `const fn` | none | `const` item / `const {}` block | none (cfg only) | `panic!` in const |
| **sysl** | optional, a checked promise | allowed, frozen into data | local `const` | `if const` | `@assert` in a body |

sysl takes D's model for the marker and the heap, and C++'s for the branch spelling. Freezing a
`Buf` into a view of a static array is D's behaviour, and the null owner word is sysl's existing
mechanism for a literal.

## 5. Open questions — the user decides

1. **Decision 1, dropping the required marker.** The alternative keeps `const` required and marks std
   instead (Rust's model). That is more std churn, and a user's ordinary helper still needs editing
   before it can run at compile time.
2. **Decision 2b, refusing a writable answer.** The alternative lets a `Buf` be answered whole and copies it on its first run-time write. That is more convenient but needs a copy-on-write path in `Buf` that every program pays for.
3. **Decision 5, the local `const` spelling.** The alternative is a `const e` prefix expression, as
   Zig's `comptime e`. That form is shorter, but its type comes only from inference.
4. Whether **item 2's reflection note** should be written before any of this is built, so the two
   land in one release cycle with one gate. Recommendation: yes. Build this first, behind its own
   tests, and gate them together.

## 6. Implementation plan

Each step lands with its tests (a `cte_` prefix) and its row in `scripts/fix_tests.tsv`.

1. **Unmarked callees (1).** `const_check` stops refusing a call to an unmarked function that is
   evaluable. The decision moves to evaluation time, with a chain-naming refusal. A function marked
   `const` keeps today's declaration-time check.
2. **Heap and freezing (2, 2b).** Add evaluator values for a box, a `Buf`, a `Map` and a closure, and
   a freeze pass that lays a read-only answer into constant data with null owner words. The std's `Buf`,
   `Map` and `Set` bodies must evaluate: their `extern` allocator calls get evaluator intrinsics.
3. **Declaration-pass evaluation (3, 4).** The declaration pass calls the evaluator on demand, with
   cycle detection. The bracket parser reads an expression for a `const` parameter, and the
   `undefined name 'N'` cascade is fixed.
4. **Local `const` (5), `if const` (6), `@assert` in a body (7)**: parser plus analyzer. `if const`
   reuses `for const`'s per-copy checking.
5. **Evaluator holes (9) and `--const-steps` (10).**
6. **Std marks its promises (1b)**, and the reference pages are updated: declarations.md § A `const`
   function, generics.md, attributes.md § `@assert`.

Steps 1, 4 and 5 are independent. Steps 2 and 3 are the large ones.
