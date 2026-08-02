---
title: datetime
summary: A conversion that can succeed twice — wall clocks, timelines, daylight saving, and the operator whose result no row can name.
weight: 100
---

Instants, durations, a calendar, and zones with daylight-saving rules — the whole point being the
conversion between a wall clock and a timeline, which can fail *and* can succeed twice.

**The axis: a conversion with three answers.** A local date-time is a reading on a wall clock. It is
not a point on the timeline and does not become one until somebody says where the wall is. On the
night a clock goes back, one reading names two instants; on the night it goes forward, one reading
names none. A library that returns a single answer there is wrong twice a year.

The reference values were computed against the real tz database with Python's `zoneinfo`, and that was
worth doing for its own sake: Python subtracts two aware date-times in one zone by **wall clock**, so
the first attempt at the reference values reported the week across the spring change as 168 hours. The
confusion this program is about is not a hypothetical one.

## What it found

**The difference of two moments cannot be spelled `-`.** Every operator row is
`op(self, rhs: Rhs) -> Self`, so an implementation says what it *takes* and never what it *produces*.
`Instant + Duration`, `Instant - Duration` and `Duration ± Duration` all write themselves;
`Instant - Instant -> Duration` is refused, because `Sub`'s result is fixed to the type on the left.

Three quarters of an algebra is spellable and the quarter that is not is the operation the library
exists to provide, so it is a named function. Dispatching on the pair does not rescue it: the two
implementations would differ in what they *produce*, which no row can say. Compare
[matrix](/guides/matrix/), where the result type varies with the right operand and the trait handles
it — the difference is that there the result is named by the row, and here it would have to be named
by the row's absence.

**A derived scalar and a one-field struct are exactly complementary, and neither is what a quantity
wants.** `type Instant = new i64` inherits its base's whole catalogue for free — `==`, `<`, `+`, `str`
— and [may replace none of it](/reference/errors/), so it arrives with `Instant + Instant`, which is
nonsense, and cannot be given `Instant + Duration`, which is not. A struct of one field gets the
algebra exactly right and arrives with **nothing**: five `impl` blocks per type before anything can be
compared or printed. So a quantity type chooses between the catalogue and the meaning, and this
program pays the struct's price on every one of its six types.

**The three-way answer needed nothing new**, and that is a result rather than an absence. A data enum
already spells "one answer, or none, or two, and here they are"; the resolution type is an ordinary
enum and [coverage checking](/reference/patterns/) is what makes a caller handle all three. The axis
the problem was chosen for turns out to be covered by machinery that was already there — which is the
useful thing to know before designing a library around it.

**An enum still cannot render its own variant names**, third program to report it. Three enums here
each carry a hand-written map from variant to word, and the two that are simple enums at least convert
to their numbers and back, which is what let the weekday arithmetic drop fourteen lines of if-chain.

**A `val` could not be sliced**, third program to report that, and the report was acted on: a view of
read-only storage is a [`[]const T`](/reference/arrays/), which carries the property rather than losing
it.

**A built-in is on nobody's left.** `Duration * 2` is writable and `2 * Duration` is not, so the
scaling in a date-time library only ever reads one way round.

## Worth noticing

Both quantities are counts of **microseconds**, and the representation argument is written out in the
source rather than assumed. Packing civil fields to the nanosecond needs 30 bits for the nanosecond,
17 for the time of day and 9 for day and month — 56, leaving 8 bits of year, which is a range of ±128
years and not a calendar. A *count* fits more comfortably at the same width and costs nothing to
compute with, because comparing, subtracting and adding a length are one instruction each on a count
and an unpack-recompute-repack on packed fields.

---

[Source](https://github.com/edadma/sysl/tree/dev/guide/datetime) ·
Next: [matrix](/guides/matrix/) — an operator whose result is neither operand's type.
