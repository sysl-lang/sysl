---
title: scheduler
summary: OS shapes — a run queue, blocking and waking, priority inheritance, and `&T` graphs mutated through references.
weight: 80
---

A priority scheduler: tasks, locks, and the run loop that decides who gets the next tick.

**The axis: OS territory.** The first program in the set where sysl has to behave like systems code
rather than like an application. A run queue, a priority, a task that blocks and a task that wakes it
are the shapes a kernel is made of, and they are made of `&T` graphs mutated **through** references:
a lock points at its owner, the owner points back at the lock it holds, and a waiter points at what
it is waiting for while that thing points at the waiter.

The centrepiece is **priority inheritance**, because it is the one place all of it has to work at
once. A low-priority task holding a lock a high-priority task wants is lent the waiter's urgency
until it lets go — which means finding the holder in the ready queue and moving it, the decrease-key
the heap exists for. Run the same three tasks with the lending switched off and the schedule is the
classic unbounded inversion; switch it on and the urgent task waits for the critical section instead
of for an unrelated third task. Both traces can be worked out on paper, and the program asserts them
character by character.

## What it found

**The wait graph is a reference cycle.** A blocked task points at the lock and the lock's waiter list
points back at the task; an owner and the lock it holds are a second cycle. `weak` is exactly the
tool for it, and at the time this was written the compiler knew nothing but the reserved word. What
saved the program is that a scheduler takes its own graph apart — every wait ends in a wakeup and
every lock is released — so its last section asserts that nothing is left holding anything.

That is a property worth having anyway, and here it was standing in for a guarantee the language
should have been making. [`weak T` has since shipped](/reference/memory/), and a program of this
shape can now say which edge of a cycle does not own.

**A `Buf` grows and shrinks at its end and nowhere else.** Taking a waiter out of the middle of a
list is compact-then-pop, because there is no `remove` and no `truncate` to say it in one step. Every
list a scheduler keeps is one that things leave the middle of.

**A task is fifteen fields with no names at the call site and no defaults.** A helper exists only to
spell the five zeroes, the three `None`s and the empty list every new task starts life holding, and a
reader of the constructor call cannot see which argument is which. A task control block is exactly
the shape that pays for this — mostly state that starts empty and is written later by somebody else.
[Named and default arguments](/reference/declarations/) are the answer, and this is the program that
made the case.

**A nullary generic cannot be told what it is making.** `buf[&Mutex]()` is not the syntax:
call-site type arguments are deliberately absent, since a type-argument list and an index are the
same grammar. Where the value lands somewhere already typed there is nothing to do — the `buf()`
filling a task's list takes its element type from the field — so it is a `var` that has to say it.
The reach for the other spelling is natural enough that the compiler used to answer it with "the
thing being called must be a name"; it now names the rule and the annotation instead, which was this
program's one change to the compiler rather than to itself.

**No enum renders itself.** `str` on one is refused — helpfully, naming the `impl Display` that would
answer it — for a data-carrying enum and a plain one alike. So the state description is a hand-written
match from six variants to their own six names, which the compiler already knows: they are the words
the match arms are written with. Other programs write a `describe` too, but for a *message* built out
of a payload; this is the first whose rendering is nothing but the name.

**`&T` is `Eq`, and that is what identity costs — nothing.** Address equality is what "is this the
lock you actually hold" and "did the heap take its own root off the end" are written with. Worth
recording as a **positive**: a language without it forces a unique name compared as a string, which
is wrong in a way that only shows up once two objects are named alike.

---

[Source](https://github.com/edadma/sysl/tree/dev/guide/scheduler) ·
Next: [kernel](/guides/kernel/) — the same machine with no heap at all.
