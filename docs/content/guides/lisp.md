---
title: lisp
summary: The reference cycle — the one shape a reference count cannot reclaim, with `weak T` used as both the cure and the instrument that measures it.
weight: 140
---

A Lisp small enough to read in one sitting: seven kinds of value, three special forms, nine builtins,
and a reader that knows integers, symbols and parentheses. It is not here to be a Lisp. It is here
because a Lisp **cannot avoid** the one thing reference counting cannot do.

**The axis: the reference cycle.** sysl's headline memory claim is that it counts references rather
than checking borrows, and the honest cost of that choice is a cycle — an island of objects that all
point at each other, that nothing outside can reach, and whose counts therefore never reach zero.
[`weak T`](/reference/memory/) exists in the language for this and no other reason, and until this
program nothing in the set showed why anyone would reach for it. [json](/guides/json/) is a *tree*;
[scheduler](/guides/scheduler/)'s graphs are mutated but acyclic in the direction that matters; and
[kernel](/guides/kernel/) sidesteps the question entirely by giving objects indices instead of
references.

A Lisp closes the loop on the first useful thing anyone types:

```lisp
(define (fact n) (if (= n 0) 1 (* n (fact (- n 1)))))
```

The closure has to see the environment it was written in, or the recursive call could not find
`fact`. The definition puts that closure **into** that environment. `Env → &Closure → &Env`, and
neither count will ever be zero again.

## The interpreter is built twice

`Lambda` carries the edge back to its environment in two spellings, and exactly one is ever filled:

```sysl
struct Lambda
    params: &Buf[string]
    body: &Value
    held: Option[&Env]
    seen: weak Env
end Lambda
```

An interpreter is made one way or the other. Both run the same source to the same answers — the page
below is not about a bug, and there is no wrong output anywhere in it. What differs is only what is
left over afterwards.

## How it is measured, which is the part worth stealing

sysl has **no user-facing destructor** — a type-bound one is still an open want in the
[memory model](/reference/memory/) — so a program cannot be told when an object dies. It can *ask*,
though, and that turns out to be enough. A weak reference does not hold its referent alive, so a
buffer of one witness per environment ever created is a **live-object counter that needs no runtime
support and perturbs nothing it counts**:

```sysl
after_run(src: string, owning: bool) -> &Buf[weak Env]
    var ws: &Buf[weak Env] = buf()
    var i: &Interp = interp(owning, ws)

    finish(i, src)

    ws
end after_run
```

The interpreter is a local, so it and everything it owns are released before the witnesses are
returned. **That release is the measurement.** What still answers afterwards is what the run could
not get back. Every number below is a weak reference being asked whether it still resolves.

## What it found

**What leaks is one environment per environment a definition lands in.** That is not the same as one
per program and not the same as one per call, and the difference is what decides whether a naive
interpreter is usable. Every top-level `define` binds into the same globals frame, so `(fact 10)` and
a program making five hundred more calls leave *the same single environment* alive:

| program | environments made | alive after the interpreter went |
|---|---|---|
| one definition, eleven calls | 12 | 1 |
| two definitions, five hundred more calls | 513 | 1 |
| a definition **inside** a function, called three times | 4 | 4 |

The third row is the one that was not guessed. Move the definition inside a function and the
identical rule reads the other way: each call makes a frame, each frame gets a closure pointing back
at it, and nothing is reclaimed at all. The first two rows were the prediction; the third came from
asking. That is the argument for having a count rather than an opinion.

**The cure costs more than the disease.** Making the back-edge `weak` means something else has to own
every environment, and the only owner available is the interpreter — so nothing is freed until the
*whole run* is over. The naive interpreter holds one environment forever; the careful one holds
**every** environment until it exits, which the same counter reads as 12 alive where the naive one
reads 1. ARC offers a choice between two leaks here and there is no third option, because the cycle
is the semantics rather than an artefact of how the semantics were encoded. This is the program that
says plainly what reference counting does not do.

**Breaking a cycle turns a total operation into a partial one.** `held` is an `&Env` and always
answers; `seen` is a `weak Env` and answers `Option[&Env]`. The interpreter unwraps it on a path that
cannot fail — the owner table is exactly what makes it so — and the unwrap is written anyway. That is
the honest price of `weak`, and it shows up as code rather than as a caveat.

**"Not optimized" was a statement about sysl and not a prediction about the stack.** `eval` and
`apply` are mutually recursive, which is the one kind of tail call sysl does not turn into a jump, so
`eval` carries the loop itself — a tail position reassigns `expr` and `env` and goes round again. The
claim that made that necessary turned out to need qualifying: built at the default `-O1` a mutual
recursion ten million deep returns an answer, because LLVM's sibling-call pass does what sysl
declined to, and the identical source built `--optimize 0` segfaults. The loop stays either way —
what rescued it is a back-end pass rather than a guarantee, and the first argument too large for a
register takes it away again. The [functions chapter](/reference/functions/) now says so; it did not
before this program was written.

**Three recursive walks, and one limit closes all of them.** The reader, the renderer, and the
non-tail arms of `eval` each recurse on nesting — and nothing but the reader ever *makes* a nested
value, since `cons` grows a list to the right and every walk over one is a loop. So the depth cap
belongs in the reader and nowhere else, and a form the reader accepted is one the other two are
already bounded on. That is a property of how the values are represented rather than of the code, and
it is why the cap is one constant instead of three.

**A hundred thousand cons cells come apart without a stack.** Teardown is
[iterative](/reference/memory/) — a count reaching zero drains a worklist rather than recursing — so
dropping a long list is O(1) in stack depth whatever its length. Confirmed here at a scale a
recursive release would not have survived.

---

Everything the interpreter *refuses* is stated in `tests.sysl` instead, because a refusal traps and a
trap ends the run rather than reporting into it. The split is itself a claim about where a failure
comes from: a malformed **text** arrives from outside, so the reader answers with a `Result` the run
can check; a malformed **program** is a bug in the thing being run, and the interpreter stops the way
sysl stops.
