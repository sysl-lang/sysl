---
title: matrix
summary: An operator whose result is neither operand's type — one type carrying three implementations of one trait.
weight: 110
---

A vector space and the matrices over it, then Gaussian elimination on top.

**The axis: an operator whose result is neither operand's type.** `A * v` gives a vector and `A * B`
gives a matrix, so one type carries three implementations of one trait — `Mul[Vector, Vector]`,
`Mul[Matrix, Matrix]` and `Mul[real, Matrix]`. Each is selected by the type of the right operand, and
each declares what it hands back.

**Nothing here is a method that wanted to be an operator**, which is the whole point of the exercise.
This is the program that demonstrates [parameterized traits](/reference/traits/) carrying real weight:
a type implements a trait once at each argument list, so three multiplications on one type are
ordinary rather than a conflict, and the argument list is what tells a call which it meant.

Contrast [datetime](/guides/datetime/), where the same mechanism does *not* rescue
`Instant - Instant -> Duration`. The difference is exactly which position the varying type is in: here
the result is named by the row that was selected, and there it would have had to be named by a row
that could not exist.

## What it exercises

**A matrix is a handle exactly as a vector is.** The cells are stored row-major in one `&Buf[real]`,
so the operators build fresh values and `copy` is how a caller stops sharing. That makes the memory
question visible in a place people usually do not think about it: a linear-algebra type is a
*container*, and whether `B = A` shares or copies is a decision the language makes you write down.

**Gaussian elimination is where the numerics land.** Pivoting, and the fact that a comparison against
zero is the wrong test for a float, are what the second half is about — and they are ordinary sysl,
because the operator work in the first half means the algorithm reads as the algorithm.

---

[Source](https://github.com/edadma/sysl/tree/dev/guide/matrix) ·
Next: [ring](/guides/ring/) — ranges, attributes, contracts, and invariants.
