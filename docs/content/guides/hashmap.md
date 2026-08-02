---
title: hashmap
summary: The trait system under load — bounds, what they promise, and ownership all having to agree at once.
weight: 20
---

A hash map, generic in its key and its value. Chained: a bucket is a list of entries, an entry is a
`&Entry` so the list can hold itself, and the bucket array grows by doubling with everything rehashed.

**The axis: the trait system under load.** A container is where bounds, the behaviour they promise,
and ownership all have to agree at the same time — the map hashes and compares a key it knows nothing
else about, it holds keys and values it did not make, and it hands entries back out while still
owning them. Nothing else in the set asks for all three at once.

## What it found

**There was no `Hash` in the core catalogue.** The prelude had `Eq` and `Ord` — enough to sort with,
not enough to key on — so the map declared its own, with an `impl` for every key type it wanted to
support. That was never a style problem. [Coherence](/reference/traits/) lets an `impl` live only
with its trait or with its type, so two libraries that each declare a `Hash` can **never** share a
key type's implementation, and a program using both writes the same hash twice.

`Hash` is now a [core trait](/library/core/), which deleted a trait, two `impl`s and an avalanche
function from the program. The built-in mixing is the same splitmix64 finalizer the map used to
carry, in the one place every container can reach it.

**An enum has no zero value, so an array of them could not be declared.**
`var cells: [16]Option[&Entry[K, V]]` is refused. Answered by the repeat form `[None; n]`, which
fills storage from a *value* instead of from a zero — before that, it was sixteen `None`s written
out.

**Generically it was worse, and the reason has since narrowed.** A repeat needs a value in its value
position and a bound could promise none, so a generic container could not make its own storage unless
it already held something to fill it with. A trait member may now have no receiver, so a bound *can*
promise a value and `[K.blank(); n]` is ordinary code.

What is left is worth being exact about, because it is a real remaining limit rather than a fixed
one: no trait sysl ships declares such a member, so a container over *every* `K` still cannot make a
`[16]K`. A container over the types a program names can, by declaring the trait itself. The map's
table stays `Option[…]` — and now because `None` needs nothing of `K` and `V`, rather than because
nothing else was available.

**Storage could not be asked for at a size worked out while running.** The table was a fixed
directory of fixed blocks with its capacity in its own type: it doubled up to `max_blocks` and then
stopped, staying correct with lengthening chains. Answered by an array form written where a `[]T` is
expected making [storage of its own](/reference/arrays/) that the view owns — so the table became one
flat run of buckets that doubles for as long as there are keys. That deleted a directory, a block,
two constructors, two constants, and the ceiling.

**A bucket is named rather than re-indexed.** `ref bucket = self.cell[self.index(k)]` names the slot,
so `put` and `remove` each hash once and walk the table once instead of writing the path out at every
read and write. That is [`ref`](/reference/memory/) doing the job it exists for, in the first program
that wanted it.

---

[Source](https://github.com/edadma/sysl/tree/dev/guide/hashmap) ·
Next: [bytecode](/guides/bytecode/) — the module system, end to end.
