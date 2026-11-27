# A count-kind parameter

One generic declaration, instantiated with ordinary (`&T`) or atomic (`&sync T`) reference counts.
Decided as a language item by `persistent-collections.md` Q2 (b); this note fixes the surface.
Grounded in `reference/generics.md` (§ A parameter may stand for a value, § A parameter may carry a
default, § Monomorphization) and `reference/memory.md` (§ `&sync T`, § `weak T`, § Crossing a
concurrency domain, § A frozen buffer crosses, § `Send` and `Share`).

## The problem, probed on 0.14.0

`&T` and `&sync T` are distinct types and a field names one of them, so a tree node that serves both
a one-domain and a shared collection is written twice (G2, probe P11). Both candidate spellings are
free grammar today:

```
struct Node[T, count K]          error: ']' expected          (1:22)
peek[T, K](r: &K Node[T]) -> T   error: ')' expected          (at 'Node')
```

## The surface

```sysl
struct Node[T, count K]
    item: T
    kids: [2]Option[&K Node[T, K]]

depth[T, count K](n: &K Node[T, K]) -> int
    match n.kids[0]
        Some(k) -> 1 + depth(k)
        None    -> 1

val a: &Node[int, plain] = Node(1, [None, None])        // ordinary counts
val b: &sync Node[int, sync] = Node(1, [None, None])    // atomic counts

print(depth(a), depth(b))                                // K inferred: plain, sync
```

1. **The parameter is `count K`** — a marker, as `const N` marks a value parameter and `..A` a pack.
   It stands for neither a type nor a value. It takes no bound and no `where` clause.
2. **A reference whose kind is the parameter is `&K T`**, in every position `&sync T` may be written:
   a field, a parameter, a result, a local's annotation, a type argument, and a receiver
   (`&K self`). `K` may appear in exactly three places: after `&`, as the argument for another
   declaration's count parameter (forwarding: `Node[T, K]`), and in a default.
3. **The two arguments are `plain` and `sync`**, soft words read only where a count argument is
   expected: `Node[int, plain]`, `Node[int, sync]`, `depth[int, sync](b)`. At `plain`, `&K T` IS
   `&T`; at `sync` it IS `&sync T` — the same type, not a third kind, so `&Node[int, plain]` and a
   `&K Node[int, K]` at `K = plain` are interchangeable.
4. **Who may declare one**: a struct, an enum, a type alias, a function, an `impl`, a member and a
   nested function. A trait may not (decided 6).
5. **Inference** is the existing matching: `&sync X` against `&K Y` binds `K = sync` and goes on
   into `X`/`Y`; `&X` binds `plain`; `Node[int, sync]` against `Node[T, K]` binds both. Two
   bindings that disagree are refused as two disagreeing types are. A construction with no
   expected type cannot solve `K` and is refused, as an unsolved `T` is.
6. **A default** is allowed and is written, never implied: `struct PVec[T, count K = plain]` makes
   `PVec[int]` the one-domain kind. A default may name an earlier count parameter.
7. **Monomorphization**: the kind is part of what identifies an instance, as a value argument is.
   `Node[int, plain]` and `Node[int, sync]` are two types; each lowers to the IR the hand-written
   `&` / `&sync` declaration lowers to (same layout, same retain/release calls), differing only in
   symbol names.

## What a generic body may do with a `&K T`

**Everything valid for both kinds, checked once at the definition** — as an unbounded `T` permits
only what every type supports.

| operation on a `&K T` in a body generic in `K` | answer |
|---|---|
| read a field, index, `==` (through the referent), hold in an `Option` (one word), copy, store, return | yes |
| construct: a position expecting `&K T` allocates with the instance's kind | yes |
| call a `self` member, or a `&K self` member with the same `K` | yes |
| call a `&self` member, or a `&sync self` member | refused (plain-only / sync-only) |
| pass as a `&T` or `&sync T` argument, or as a `&J T` of another parameter | refused: distinct types |
| write a field, call a `*self` member | refused as through `&sync` — with `&sync`'s own exceptions (`Atomic`, `@guards`, `@unchecked(Share)`) |
| capture in a `&sync Fn`, pass to a `@crossing` parameter, meet `Send`/`Share` | refused: a `&T` may not cross |
| `weak K T`, weakening a `&K T` | refused: `weak sync T` does not exist |
| `r.is_unique()` (sibling item `lu1-is-unique`) | yes — both kinds answer it; each instance lowers its own (a plain load, an acquire load) |
| `into_raw` / `from_raw` (raw tier) | yes, the kind preserved |

So a `&K T` is the **intersection**: read-only like `&sync`, domain-bound like `&T`. Code that must
cross a thread names the kind: `publish(v: &sync PVec[int, sync])` is ordinary code over one
instance, and may capture, send and share it.

## Interactions

- **The `Share` walk waits for the instance**, as `@crossing` does: `Node[&Cell, sync]` is refused
  where it is written, with today's message (`…every count inside it has to be atomic — but its
  'item' reaches a '&Cell'…`). `Node[&Cell, plain]` is fine. At `sync`, `Node[T, sync]` is `Send`
  and `Share` wherever `T` is, by the compiler's existing walk.
- **A string stored into an object made at `K = sync`** is copied in, per instance — the rule is a
  property of the place (`memory.md § A string crosses into a shared object`).
- **`Frozen[T]`**: `&K Frozen[T]` is allowed; `buf.freeze()` where one is expected takes the
  instance's kind. This is how one node type holds a run-time-sized run in both kinds (a `&sync`
  object holds no `[]T` view — P06). `Frozen` itself gains no count parameter.
- **Trait objects**: `&K Trait` is a trait object counted by the instance's kind; calls dispatch
  through `self` slots. Coercing a `&K T` to `&K Trait` keeps the kind.
- **`&K Fn(...)`**: may be held, called and forwarded. Erasing a closure into one in a body generic
  in `K` is refused (decided 5).
- **`Drop`**: `impl[T, count K] Drop for Node[T, K]` is one destructor for both kinds; a type with a
  destructor stays a reference type, held as `&Node[T, plain]`, `&sync Node[T, sync]` or `&K …`.
  Teardown of a deep chain is the existing iterative release, per kind.
- **`@unique`**: a `@unique` value carries no count, so a kind does not apply to it; a `@unique`
  struct may declare a count parameter for references in its fields, and a `&K U` box of a `@unique`
  `U` follows the rules `&U` and `&sync U` already share.
- **`@unchecked(Share)`** above a count-generic struct means what it means today and lifts the
  field-write refusal in both kinds — the form `persistent-collections.md § 2.3` needs for the
  in-place write at count 1.
- **`if const`** does not read a count parameter in 0.15 (decided 4).
- **Printing**: diagnostics, `sysl doc` and reflection print `count K`, `&K Node[T, K]`,
  `Node[int, sync]`.

## Diagnostics (new; each pinned by a test or an `error` block)

- a plain-only or sync-only use: `'get' takes '&self', and this receiver is a '&K Node[T, K]',
  whose count is ordinary in one instance and atomic in another — a body generic in 'K' may do only
  what both kinds allow. Declare 'get' with '&K self', or with 'self' where it only reads`
- passing where a fixed kind is wanted: `'&K Cell' and '&sync Cell' are distinct types … 'K' is a
  count parameter, decided by each instance. Take the parameter as '&K Cell'`
- a write: today's `&sync` read-only message, opening `…reached through a '&K Cell', which may be a
  '&sync Cell'…`
- crossing: `…but it is a '&K Cell', whose count is ordinary where 'K' is 'plain'. Name the kind:
  '&sync Cell'`
- weak: `'weak K Cell': a weak reference has no atomic kind, so it cannot follow a count parameter`
- misuse of the name: `'K' is a count parameter: it is written after '&' or handed to another count
  parameter, and is not a type`; a type where a kind is expected: `'Node' takes a count for 'K' —
  'plain' or 'sync' — but 'int' was given`; a bound: `a count parameter takes no bound`.

## Decided (director, 2026-11-27)

1. **The parameter is `count K`**, a marker, not `K: Count` and not `&K` in the list: `[K: Count]` has
   a bounded type parameter's shape, and `K` is not a type.
2. **The arguments are `plain` and `sync`**, soft words.
3. **The reference is `&K T`**, the shape of `&sync T`. The parser tells `&K [4]int` (a kind, then an
   array type) from `&K[4]` (a type applied); a `&K` whose `K` is not a count parameter is refused,
   saying so.
4. **No kind-specific arms** (`if const K == sync`) in 0.15. `&K Frozen[T]` covers the one
   representation difference the motivating tree has; an arm can be added without breaking anything.
5. **Erasing a closure into a `&K Fn` is refused in 0.15**, naming the instance spelling to use
   (`&Fn` or `&sync Fn`). It can be allowed later under the `&sync Fn` capture check.
6. **A trait may not declare a count parameter in 0.15.** A generic type with one implements an
   ordinary trait at each instance.
7. **No implied default**: `count K = plain` only where written.
8. **`weak K T` is refused**: `weak sync T` does not exist.
