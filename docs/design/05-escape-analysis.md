# Escape Analysis

**Status:** decided and implemented, **promotion included**. This specifies the analysis `03`
calls for when it gives slices an owner word: which escapes are detected, how the answer crosses a
call boundary, and what happens when something escapes that has nothing to keep it alive. A local
array a view of which gets out is now moved to the heap as described below, so the programs that
used to be refused compile and run. What is still a diagnostic is storage the body did not declare —
an array a caller passed **by value**, and an array that is a **field** of a value, the second being
the *Deferred* section's unspecified promotion-of-aggregates. Under `no alloc` every promotion
becomes an error again, which is the paragraph below; capabilities are not built, so today every
promotable escape promotes. `--explain-escapes` is specified here and not yet built. The
approximations the analysis takes are the ones *Deferred* permits.

## What it is for

A slice carries `{owner, ptr, len}` and retains its buffer, so it cannot dangle — **provided
there is an owner to retain.** The `owner` word is null exactly when nothing needs keeping
alive:

| Slice of | `owner` | Safe to keep? |
|---|---|---|
| a heap buffer | the buffer | yes — retained |
| static data, a string literal | null (immortal) | yes — never freed |
| a `*T` region | null | the programmer's problem, like every `*T` |
| **a local fixed array** | **null** | **only while the frame lives** |

The last row is the whole subject. `var buf: [64]u8` lives in a stack frame; a slice of it is
valid until that frame returns and no longer. The analysis finds the slices for which that
matters.

## No annotations

The programmer writes nothing. There is no `@escaping`, no lifetime parameter, no "this result
views that argument" marker anywhere in a signature.

This follows principle 3 — a mechanism may not put a keyword in front of one of the memory
modes — and principle 2, where Go is the precedent: Go infers escape behaviour, records it in
its export data, and heap-promotes what needs promoting, all invisibly. Rust's lifetimes and
Swift's `@escaping` are the alternative, and both charge the programmer for what a compiler
can work out.

Inference is affordable here for a reason specific to this language: **sysl monomorphizes
generics**, so a module's bodies are already available across module boundaries. A compiler
that must see a generic body to instantiate it can certainly see a body to ask whether a
parameter escapes.

## What escapes

Within one function, a slice value is tracked back to its origin. A slice whose origin is a
**local array** escapes if it can be reached after the frame returns — that is, if it is:

1. **returned**, directly or nested inside a returned struct, enum, or `Option`;
2. **stored into anything that outlives the frame** — a global, a field reached through a
   `&T`, or an aggregate that is itself stored somewhere that outlives the frame;
3. **passed as an argument that the callee keeps** (next section);
4. **captured by a closure that itself escapes**;
5. **assigned into a local that escapes** — the rule is transitive, resolved as a fixpoint
   over the function's own locals.

Everything else is fine and needs no allocation: indexing, iterating, sub-slicing into a local
that stays local, comparing, and passing to a callee that only reads.

## Crossing a call

Two facts about a function are enough to make the caller's analysis local and exact, and both
are **inferred from the body**, not written:

- **per parameter** — does the callee let this argument outlive the call?
- **for the result** — which parameters may the returned value be a view of?

They are computed bottom-up over the call graph and recorded in module metadata beside the
things monomorphization already needs. At a call site the caller then knows, with no
whole-program reasoning: an argument passed to a non-keeping parameter is safe; a result that
views a stack-backed argument is itself stack-backed and inherits its restrictions.

**Recursion** is handled by starting optimistic — assume nothing escapes — and iterating to a
fixpoint, so a self- or mutually-recursive function converges on the truth rather than on the
conservative answer.

**A call through a trait object** is the same conservative case for the same reason: which body it
reaches is a word read at run time, so there is no one summary to consult and every argument is
assumed kept. **`Writer` is the one exception** (`14 §2`), and it is earned rather than assumed: a
writer takes a borrowed view of the bytes to write — which is what lets a renderer pass a slice of
its own stack buffer, and so what keeps rendering allocation-free — and every `impl Writer` is
checked here to make sure its `write` really does let go. An implementation that keeps the bytes is
rejected, naming the function; a caller may then pass one a frame-backed slice freely.

**A function whose body is not available** — an `extern` (`12 §1`), which is the declaration form
for exactly that — gets the pessimistic assumption: every parameter is kept, and the result views
everything. A stack-backed slice therefore cannot be passed to one, which is correct: the foreign
side may retain it, and nothing here can tell. This needs no separate machinery, because "not in
this module's function table" is already the conservative case — and it covers a variadic extern's
tail for the same reason, since the assumption is about the callee rather than about any one
parameter position.

## What happens when a slice escapes

**With an allocator: the array is promoted.** The local array is allocated as an ARC buffer
instead of a stack slot, the slice's `owner` points at it, and the storage lives exactly as
long as the last slice of it. Nothing else about the program changes; the promotion is the
compiler doing what the programmer would otherwise have had to do by declaring `&[64]u8`.

**The array keeps its type, and only its storage moves.** A promoted `[64]u8` is still a `[64]u8`:
its length is still a compile-time constant, it is still a value that copies on assignment, and
every index, store and copy is emitted exactly as an unpromoted one's is. What changes is where the
name's address points — into a buffer rather than into the frame — and that a view of it carries
that buffer as its owner where an unpromoted array's view carries `null`. Rewriting the declaration
into a `[]T` would have been the other way to do it and is **wrong**: it changes the type at every
use, and an array is a value type where a view is not.

**Which array moves.** The analysis answers with the *root* of the escaping view, and an index step
is walked through while a field step is not: an element of a local array of arrays is part of that
array's storage, so the whole array moves and the element goes with it, whereas a field belongs to a
struct and moving it would be the *Deferred* section's unspecified choice between moving the field
and moving the struct. An array a caller passed **by value** is not this body's to move either — it
is the caller's layout — so both of those stay diagnostics, and the message says which it is.

Only arrays that are *both* sliced *and* escaped are promoted. An array that is only read, or
whose slices stay in the frame, keeps its stack slot.

This matches how the language already treats the other thing that outlives its scope: an
escaping closure is heap-boxed, silently, with no marker in the source. Promotion is the same
rule applied to storage.

**Without an allocator: it is a compile error.** Under `no alloc` there is nothing to promote
into, so the escape is reported where it happens. This is exactly how every other
allocation-gated feature behaves in the allocator-free subset — growable arrays, escaping
closures, and `&T` creation are all compile errors there — so it introduces no new rule to
learn.

```
tty.sysl:31: the slice returned here outlives 'buf', the array it views
   31 |     return buf[0..<n]
      |            ^^^^^^^^^^
   28 |     var buf: [64]u8
      |         --- 'buf' is a local array; its storage ends when this function returns
   this module is 'no alloc', so 'buf' cannot be promoted to the heap.
   make the storage outlive the slice — a static buffer, or one the caller supplies —
   or return the length and let the caller slice its own array
```

The last suggestion is the idiom worth reaching for first even where an allocator exists:
returning a count and letting the caller slice its own buffer is what `snprintf` does, what
Rust's buffer writers do, and what most kernel code wants.

## Promotion is silent, not hidden

Silent promotion earns the obvious objection: an allocation appears that nothing in the source
asked for. The answer is discoverability rather than ceremony — `--explain-escapes` reports
every promotion the compiler made and the route that forced it:

```
$ sysl build --explain-escapes tty.sysl
tty.sysl:28: 'buf' promoted to the heap
    because the slice at tty.sysl:31 is returned
```

This is Go's `-m`, and it is the right shape: the common case costs no reading, and the
question "why did this allocate?" always has an answer. A program that must not allocate says
so with `no alloc`, and then the compiler enforces it rather than reporting it.

## Worked patterns

**A scratch buffer — no allocation.** The slice never leaves the frame, so `buf` stays on the
stack:

```
format_status(code: int) -> unit
    var buf: [64]u8
    var n = render(buf[0..], code)
    write(buf[0..<n])
```

**Zero-copy parsing — no allocation, and the constraint is real.** `parse` returns a `Header`
holding views of its argument, so the result inherits the argument's provenance. Passing a
local array's slice keeps everything on the stack, and the compiler stops the header from
outliving the bytes it points into:

```
var packet: [1500]u8
var n = recv(packet[0..])
var h = parse(packet[0..<n])      // h views 'packet' — fine, both live here
```

**Returning a view — promotion.** Here the slice does leave, so `line` is promoted and the
returned slice owns it:

```
read_line(f: &File) -> []u8
    var line: [512]u8             // promoted: the result views it
    var n = f.read_into(line[0..])
    line[0..<n]
```

Under `no alloc` that third one is the diagnostic above, and the fix is to take the buffer as
a parameter and return `n`.

## Which array a slice is a slice of

"A local fixed array" is a statement about the **root of the place**, not about its last step.
`p.table[i].bytes` is a fixed array, but the storage it names belongs to whoever the `*T` points
at, so a view of it is no more this frame's than `*p` is; only a place rooted at a local of this
function is. The analysis therefore follows a place through its field and index steps to find out
where it started, and stops at the first dereference.

The two halves have to be kept apart, because the shapes look alike. A local array **beside** a
pointer parameter is still local, and so is an element of a local array of arrays — the pointer
has to be the route to *this* array for the storage to be somebody else's.

## Not analyzed

`*T` is outside all of this, as it is outside every other guarantee: a raw pointer into a
local array can dangle exactly as in C, and that is the opt-out the mode exists to provide.
Taking a `*T` to a local **does not** promote it — promotion follows slices, which carry
their length and their owner, not raw addresses.

## Not yet

**A fixed array inside a `&T` has no owner word to hand out.** Slicing a `&[N]T` works, because the
buffer that reference counts *is* the array; slicing a fixed array **field** of a `&Struct` does
not, because the view is built with a null owner and would dangle as soon as the last reference to
the struct went away. So it is refused as a frame-local view would be, which is sound but says the
wrong thing — the storage is on the heap, and what is missing is the walk from the field back to
the box that owns it. Until that exists, storage a view must outlive is declared `&[N]T` in its own
right rather than as a field.

## Deferred

- **Escaping closures** are stated in `capabilities.md` as heap-boxed and are governed by this
  same analysis; the closure design is now written (`12 §5–§8`, and `§5a` for the named form) and
  not yet built. When it lands, the per-parameter "does the callee keep it" bit should cover
  captured closures with no separate mechanism, and the buffer machinery promotion uses is the
  representation an escaping closure needs.
- **Precision of the result summary.** "Which parameters the result may view" is a set; the
  first implementation may collapse it to "any parameter," which costs precision only for a
  function that both takes a stack-backed slice and returns an unrelated fresh one. Refine if
  it bites.
- **Promotion of aggregates.** A local struct containing an array, where only the array's
  slice escapes, could promote the array alone or the whole struct. The cheaper choice is the
  array; not yet specified.
