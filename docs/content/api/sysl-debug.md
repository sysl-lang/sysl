---
title: sysl.debug
layout: api-module
headingShift: 0
slugStyle: github
module: sysl.debug
summary: "The error return trace: where a failure was made, and each `?` that handed it one frame further up on its way to the code that reported it."
---

**Every `?` that takes its failure arm records its own site**, after any `From` conversion, and so
does each `Err(...)` a function hands back as its result -- written as the body's value, in a
`return`, or as an arm of an `if` or a `match` in either place. A site is `file:line:col in
function`, a constant the compiler lays down beside the code, so a trace prints the same at every
optimization level and on a board with nothing to symbolize against. A `?` on an `Option` records
nothing: `None` is absence rather than failure.

**A failure that is handled leaves the trace**: a statement that calls something answering a
`Result` and does not hand that answer on with `?` or `return` puts the trace back as it found it
when it completes. So a `match` that deals with one error does not leave its frames in front of the
next one's.

**One trace per thread**, of thirty-two sites and a count, allocated nowhere: the first thirty-two
are kept, since the origin end is where the bug is, and the rest are counted. A task records into
the trace of the thread running it. On a freestanding target there is one trace for the program.

`main` returning an `Err` prints the trace under its `error:` line, and so do `unwrap` and `expect`
of an `Err` and a `@test` that returns one. A build with `error_traces = false` in its manifest, or
`--no-error-traces`, records nothing and reports nothing, and every function here answers as though
no failure had happened.

## Index

[`ErrorTraceSites`](#errortracesites) [`clear_error_trace`](#clear_error_trace) [`error_trace`](#error_trace) [`print_error_trace`](#print_error_trace) [`ErrorSite`](#errorsite) [`ErrorTrace`](#errortrace) [`ErrorTraceWalk`](#errortracewalk) [Iterate for ErrorTraceWalk](#iterate-for-errortracewalk) [Walk for ErrorTrace](#walk-for-errortrace)

## Constants

### `ErrorTraceSites`

```sysl
const ErrorTraceSites: usize = 32
```

How many sites a trace keeps.

## Functions

### `clear_error_trace`

```sysl
clear_error_trace()
```

Empties the current thread's trace -- what a loop that logs each failure it handles does before
the next.

### `error_trace`

```sysl
error_trace() -> ErrorTrace
```

The current thread's trace, as it stands.

### `print_error_trace`

```sysl
print_error_trace(w: *Writer)
```

Writes the current thread's trace to `w` in the form `main` reports it in, or nothing where it is
empty.

## Types

### `ErrorSite`

```sysl
struct ErrorSite
    file: string
    line: int
    col: int
    function: string
```

One site of a trace: where a failure was made or handed on, and the function it was in. A closure
is named after the function it was written in.

### `ErrorTrace`

```sysl
struct ErrorTrace
    count: usize
    sites: [32]*u8
```

A copy of the current thread's trace, origin first. It holds the sites by address -- they are
constants -- so taking one allocates nothing, and reading a site makes its strings.

| Member | Signature | Description |
|---|---|---|
| `len` | `len(self) -> usize` | How many sites the trace kept. |
| `dropped` | `dropped(self) -> usize` | How many it recorded past the ones it kept. |
| `is_empty` | `is_empty(self) -> bool` | Whether nothing was recorded. |
| `at` | `at(self, i: usize) -> ErrorSite` | The `i`th site, the origin being `0`. |

### `ErrorTraceWalk`

```sysl
struct ErrorTraceWalk
    trace: ErrorTrace
    at: usize
```

The cursor `for site in trace` walks.

## Implementations

### Iterate for ErrorTraceWalk

```sysl
impl Iterate for ErrorTraceWalk
```

### Walk for ErrorTrace

```sysl
impl Walk for ErrorTrace
```

`for site in trace` visits each kept site in turn, origin first.
