---
title: shapes
summary: Dynamic dispatch — a collection whose element types are forgotten, and combinators that hold what they cannot name.
weight: 70
---

A catalogue of shapes behind one trait: the dynamic half of the trait system.

**The axis: forgetting the type.** Every other program in the set knows the type of everything it
computes with. This one does not, and that is the whole exercise — a `&Shape` is a [fat
pointer](/reference/traits/) that has forgotten what it points at, and the only thing left to do with
it is ask the trait's questions and believe the answers.

## Why it needs two families

The shapes come in two kinds, and the second is what makes the problem worth writing.

Four of them are **leaves** — a circle, a rectangle, a triangle, a polygon — and each answers out of
its own fields. The other three are **combinators**: a group is a shape made of shapes, and a move
and a scale each wrap one shape and adjust what it says.

A combinator holds `&Shape`, so it holds something it **cannot name**, and a group of groups is
dynamic dispatch calling itself. A trait-object design that only ever holds leaves has not been
tested; one that holds arbitrary other objects of the same trait has.

## What it exercises

**Erasure is a coercion, applied per position.** Each concrete shape is constructed, boxed because a
`&Shape` was expected, and then erased — the ordinary "write the construction and it is allocated"
rule with one more step. Because the coercion applies per branch, an `if` or a `match` whose arms are
different concrete types meets at one trait object, which is the point of having them.

**Object safety is what makes the trait usable this way.** A trait can be made into an object when
every member has a receiver and mentions `Self` nowhere but there. `Shape`'s members answer questions
*about* the shape and never hand another one back at its own type, so it qualifies — where the whole
[operator catalogue](/reference/traits/) does not, `add(self, rhs: Self) -> Self` being the clearest
case. That exclusion is the right answer rather than a limitation: an operator over two values of one
type is a question about types known while compiling.

**A bounding box is a plain struct rather than a shape.** It is the answer to a question *about* a
shape and not another thing to dispatch on — a distinction that is easy to lose when everything in
sight is a trait object, and the reason the file says so out loud.

---

[Source](https://github.com/edadma/sysl/tree/dev/guide/shapes) ·
Next: [scheduler](/guides/scheduler/) — OS shapes, and reference graphs mutated through references.
