# `r.is_unique()` — uniqueness is asked of a place

The rule the compiler implements, and what it supersedes in `persistent-collections.md`. The
reference text is `docs/content/reference/memory.md § Asking whether a reference is the only one`.

## The rule

`r.is_unique() -> bool` is a compiler-supplied member of every counted reference (`&T`, `&sync T`, a
trait object, a `&Frozen[T]`). It is `true` when the strong count is one and no `weak` reference is
live.

**It is asked of a place that holds a count of its own, never of a lent value** — the shape of Rust's
`Rc::get_mut(&mut Rc)` and Swift's `isKnownUniquelyReferenced(&x)`:

- accepted: a local (`val`, `var`, `ref`, a pattern's or a `for`'s binding, a closure's capture) and
  what is reached from one; module storage; anything reached through a `*T` or a view at any depth —
  a field through `*self`, the place a `*&T` parameter points at, a `Buf`'s element;
- refused: a parameter and anything read through one (a `(self)` member's `self` included), and a
  value in no place (a call's answer).

A function owning its parameter does not make the parameter askable. Ownership is inferred from the
body by the lowering, differs between a direct call and one through a table, and is invisible in the
signature; a rule that read it would accept or refuse a program after an unrelated edit to a body. A
parameter copied into a `var` is asked there.

Asking makes the counts exact where they are observed: a local asked about holds a count of its own,
a local read out of storage keeps its count across a call that may ask, a retain is not cancelled
across the question, and a frame box asked about stays counted. The answer is the same at every
optimization level and wherever the box lies.

## Why not the bare count

A lent argument takes no count. Measured on 0.14.0, reading the count through the raw tier:

```
val w = v.push(2)        // push(self) reads the count of self.root
print(v.root.n, w.root.n)
```

```
w = v.push(2), v read afterwards:
  inside push(self): 1
```

A `push(self)` that wrote in place on a count of one would change `v`, which the caller still reads.

## What this supersedes in `persistent-collections.md`

- **§ 1, "No transient type … a builder is just a `var`"** and **§ 2.3, "the old version is never
  written"** assumed `v = v.push(x)` through `push(self)` could write in place at count one. It
  cannot ask. **`push(self)` always path-copies**; the in-place path is a **`*self` member called on a
  `var`** (`v.push_mut(x)`), which asks each node's own slot on the way down and copies the nodes
  something else holds.
- **P03's conclusion** ("`v = v.push(x)` on an unshared vector would never copy a node") holds for
  the count it read, not for a sound in-place write: it never read the receiver after the call.
- **Q4 (a)** still stands in substance — no builder type, no edit token — with the builder spelled
  `var w = v` followed by `w.push_mut(x)`: the first push copies one path, every later one writes
  nodes only `w` holds.

## A possible later item

Making `v = v.push(x)` through a `(self)` member write in place would take two ownership changes:
a function that asks about a parameter takes it owned, and an argument whose own variable the same
statement overwrites is handed over rather than retained (today the caller retains, so an owned
`self` reads two in `v = v.kept(x)`). Neither is part of this rule.
