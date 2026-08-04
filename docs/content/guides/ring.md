---
title: ring
summary: The constrained-subtype surface — and an invariant that found a redundant field rather than a bug.
weight: 120
---

A bounded ring buffer: fixed storage, indices that cannot leave it, and the whole of what "this ring
is consistent" means written into the types and the contracts rather than into the checks.

**The axis: the constrained-subtype surface** — `within` ranges, the `::` attributes they expose,
`require`/`ensure`/`old`, and struct invariants. [kernel](/guides/kernel/) gives its tables bounded
identities and then never asks one a question; until this program nothing in the set had read an
attribute or written a contract at all. A ring buffer is the smallest subject needing all of them at
once, because every bug a ring buffer has is an index that went somewhere it should not have — and
those are the bugs a range type exists to make unwritable.

Like [kernel](/guides/kernel/) against [scheduler](/guides/scheduler/), it is **written to be
compared**: two buffers under the same scenarios, one keeping the fact of where the ring ends once and
the other keeping it twice. Every check runs both and asserts they agree, so the difference between
the two implementations is the measurement.

## What it found

**A derived subtype's attributes handed back its base**, which made the whole `::` surface unusable by
the only kind of subtype that needs it. `Slot::Last` was a `byte`, `Slot::Succ` took one, and the loop
variable of `Slot::Range` was one — so every use of an attribute had to be cast back into `Slot`,
undoing the `new` that made it a type at all. The sibling surface next door had it right all along: a
simple enum's `First`, `Last`, `Succ` and `Pred` are the enum.

It was invisible until this program because a *transparent* subtype **is** its base, and every test
the attributes had used a transparent one. Fixed in the compiler — [the attributes are the
subtype](/reference/errors/), and `Valid` alone takes the base, because asking whether a value is a
`Slot` is only a question about something that is not one yet.

**The successor of the last slot is a trap, not a wrap.** `Slot::Succ` steps within the range and
refuses at `Last`, which is exactly right for a counter and exactly wrong for a ring — the one
operation a ring buffer is *made* of is the step that goes round. So the modular successor is written
by hand out of `%`. **That is not a defect**; it is what the attributes mean. `Succ` is the step that
stays inside a range, and a ring's step does not.

**An invariant relating two fields is a claim about the representation, not about the code.** The
tracked buffer keeps head, tail and count, so `tail == (head + count) % capacity` — and a push that
writes `tail` and then `count` traps at the first of the two, on a state that was on its way to a
perfectly good one.

The reflex is to call that a wart and reach for a way to suspend the check. It is not a wart: **the
invariant is the compiler pointing out that the struct carries one fact twice.** The other buffer —
head and count, with tail computed — has no invariant to break because there is nothing left to
disagree. The mid-update trap found a redundant field, which is what it is for.

There are **two** honest two-field designs and the clause rules out neither: this one, and the
embedded classic that keeps head and tail and derives the count, paying one unused slot to tell a full
ring from an empty one. That second is what an interrupt-driven ring wants, because a producer and a
consumer then write disjoint fields and neither has to touch a shared count. What the clause refuses
is only the third design that keeps all three.

**Where the fields genuinely cannot be ordered, the way out costs the whole struct.** The only form
that moves two fields without being seen between them is whole-struct assignment, which restates the
buffer to move two bytes — thirty-two bytes copied per push against three written here, and for a ring
sized like a real one the entire storage, per element. **An invariant across two fields makes the
container's own update cost the size of the container.**

**Ordering the writes is enough more often than it looks.** A watermark relates two fields too, and
needs none of the above, because raising the ceiling before the floor keeps every intermediate state
legal. The whole-struct form is the last resort, not the first: ask whether an order exists, and only
then whether the representation is redundant.

**A program's own run cannot check its contracts; a test can.** A violated `require`
[traps](/reference/errors/), so no check in the run can be the one that breaks it — the program would
die rather than report, and the run would look truncated instead of failed. Every refusal the run
asserts therefore comes from a total operation that answers instead of trapping.

What the trap itself needs is somewhere that outlives the process it ends, and that is a
[`@test(should_trap)`](/reference/attributes/) function: it runs in a process of its own and passes by
not coming back. This is the program that first needed them, and they live beside the code they are
about rather than being restated in a language the ring is not written in.

---

[Source](https://github.com/edadma/sysl/tree/dev/guide/ring) ·
Next: [slab](/guides/slab/) — raw storage, and the one literate program in the set.
