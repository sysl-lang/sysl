---
title: sysl.harness
layout: api-module
headingShift: 0
slugStyle: github
module: sysl.harness
summary: "A test framework that runs on the target."
---

`sysl test` is the host's answer and cannot be this one: it starts a process per test, selects one
by argv, and reads the verdict out of an exit status. A board has none of those. So the checking a
program does about itself has to be written in the language and linked into the image, which is
what this is -- the same shape as C's Unity, and for the same reason.

**Nothing here allocates, opens anything, or asks the operating system for a service.** Its state
is a fixed number of bytes laid into the image, and every value it prints renders itself into a
sink rather than into a fresh string.

What it deliberately does not do is stop a failing test. Unity aborts with `longjmp`; sysl has
none, and an unwind that skipped a scope would skip the releases that scope owes. So a failed check
is recorded and the test runs on. Only the first failure in a test is reported, because the ones
after it are usually the same failure seen again through worse state.

## Index

[`attach`](#attach) [`check`](#check) [`check_eq`](#check_eq) [`check_slice_eq`](#check_slice_eq) [`failures`](#failures) [`finish`](#finish) [`reset`](#reset) [`run`](#run) [`skip`](#skip) [`skipped`](#skipped) [`tests_run`](#tests_run)

## Functions

### `attach`

```sysl
attach(w: *Writer)
```

Send the report somewhere other than standard output, which is what a board does before it runs
anything:

attach(console())          // a `*Writer` of the board's own

A UART is a volatile store through a pointer, which is a language feature, so a board reaches its
console with no `asm`, no semihosting, and no C library underneath.

### `check`

```sysl
check(cond: bool, msg: string = "", file: string = __FILE__, line: long = __LINE__)
```

A condition that has to hold, with an optional word about what it was.

**`file` and `line` are passed on explicitly** to everything downstream. Letting `failing` fill its
own defaults would report a line inside this file for every check in every program, which is the
one mistake this shape of API invites (`sysl/check.sysl` says the same thing about `panic`).

### `check_eq`

```sysl
check_eq[T: Eq + Display](got: T, want: T, file: string = __FILE__, line: long = __LINE__)
```

Two values that have to be equal, reported with both of them when they are not.

One generic member stands in for Unity's several hundred assertion macros, and does more than they
can: `TEST_ASSERT_EQUAL_*` covers a fixed list of C's own types and falls back to a hex dump of the
bytes for anything a program defined, while a bound reaches any type at all that can say whether
two of it are equal and write itself down.

Rendering costs no allocation: a `Display` writes into a sink rather than returning a fresh string
(`sysl/display.sysl`).

**`got` and `want`, in that order** -- the computed value first, which is `assert_eq`'s order in
the module next door and the order a failure is read in. The package this came from took
`(actual, expected)` and said so in its README; one library speaking with one voice is worth more
than that sentence was.

### `check_slice_eq`

```sysl
check_slice_eq[T: Eq + Display](got: []const T, want: []const T, file: string = __FILE__, line: long = __LINE__)
```

The same for a slice, which needs more than `==` to be useful.

A report saying two slices differ sends its reader to find out *where*, and finding out where is a
loop nobody wants to write at each call -- least of all on a board, where the way to look is a
debugger and a stopped core. Length first, since a length mismatch explains every index after the
shorter one.

### `failures`

```sysl
failures() -> int
```

### `finish`

```sysl
finish() -> i32
```

Print the tally and answer with the code a hosted program would exit on: zero when everything
passed, one when anything did not. A skipped test is not a failure.

**What to do with it is the board's**, which is why this returns rather than exits. A hosted
program writes `exit(finish())`; semihosting has `SYS_EXIT`, riscv's `virt` has the `sifive_test`
register, and a bare board has whatever pin somebody soldered. None of that belongs in a framework
that would then only build for the target it knew about.

### `reset`

```sysl
reset()
```

Zero the counters, for a suite that runs more than once -- a board looping over its self-test, or
this module's own tests, which need each `@test` to start from nothing.

The sink is deliberately left alone: a board sets it once at startup, and a `reset` that undid
that would send the second pass of a looping self-test to a console the board does not have.

### `run`

```sysl
run(name: string, body: *extern() -> unit, file: string = __FILE__, line: long = __LINE__)
```

Run one test, under a name to report it by.

**The body is a `*extern() -> unit` rather than a bare arrow**, which is one word and one copy of
`run` for the whole suite. A bare arrow would be a bound over `Fn` (`reference/types.md §
Function types`), monomorphized per test -- thirty tests, thirty copies -- and flash is the thing
a target has least of. The cost is the `&` at the call, and the restriction that a test body
captures nothing, which is what a test body is anyway:

run("adds_two", &adds_two)

`file` and `line` are never passed. They are defaults, and a default is evaluated at the call
(`reference/declarations.md § Default parameters and named arguments`), so they hold the line of
the `run` that named this test.

### `skip`

```sysl
skip(name: string, why: string = "", file: string = __FILE__, line: long = __LINE__)
```

A test that was not run, and the reason it was not.

**A board is where the third verdict finally earns its place.** On the host a test that cannot run
is usually a test that should be deleted; on a target it is routine -- the part is not fitted, the
bus is wired for the other variant, the flash is the smaller one. Counting those as passes says
something false and counting them as failures says something worse, so they are counted as
themselves and named in the tally.

It takes the name rather than the body, because the body is exactly what is not going to be called.

### `skipped`

```sysl
skipped() -> int
```

### `tests_run`

```sysl
tests_run() -> int
```

How many tests have run, how many failed, and how many were skipped. A board that reports its
verdict through a pin or a register rather than a console reads these.
