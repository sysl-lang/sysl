---
title: The harness module
summary: "`sysl.harness` — a test framework that runs on the target: named tests, a located failure with both values rendered, three verdicts and a tally, with no allocator, no operating system and no debug host underneath."
weight: 95
---

**Every declaration in `sysl.harness`, with its signature:** [the generated API page](/api/sysl-harness/#index). This page is the argument — what the module is for, and how its pieces fit; that one is the list.

`sysl.harness` is how a program checks itself **on the machine it was built for**. It is the same
shape as C's [Unity](https://www.throwtheswitch.org/unity), and it exists for the same reason: the
host's test runner cannot follow the code onto a board.

**[`sysl test`](/getting-started/cli/) is the host's answer and cannot be this one.** It starts a
process per test, selects one by `argv`, and reads the verdict out of an exit status. A
microcontroller has no processes, no `argv` and nobody to read an exit status. So the checking has to
be written in the language and linked into the image, and what comes out is a report on a wire.

The two are not rivals. Use `sysl test` for everything that can run on the machine you are typing on
— it is faster, it isolates each test in its own process, and it needs no arrangements. Reach for
this when the thing you want to check only exists on the target: a driver against real registers, a
timing loop, arithmetic at a width the host does not have.

**Nothing here allocates, opens anything, or asks the system for a service.** Its state is a fixed
number of bytes laid into the image, and every value it prints renders itself into a sink rather than
into a fresh string.

## A suite

```sysl
import sysl.harness.*

adds()
    check_eq(2 + 2, 4)

holds()
    check(1 < 2, "one is less than two")

run("adds", &adds)
run("holds", &holds)
finish()
```

```output
<page>:9:adds:PASS
<page>:10:holds:PASS
2 tests, 0 failed
```

The file is `<page>` because this example is a block on a web page; where you run it, it is your
file's name.

A test is an ordinary function of no arguments, and `run` names it and calls it. **The name is
written twice on purpose** — once as the function and once as the string — because the string is what
the report says, and a report that said `adds` when the interesting thing was *addition after the
clock was reconfigured* would be worse for having been derived automatically.

**`&adds` is a pointer to a function, not a closure.** `run` takes `*extern() -> unit`, which is one
word and one copy of `run` for the whole suite; a bare arrow would be a bound over `Fn`, monomorphized
per test — thirty tests, thirty copies of the runner — and flash is what a target has least of. The
cost is the `&`, and the restriction that a test body captures nothing, which is what a test body is
anyway.

## A failure says where, and what the values were

```sysl
import sysl.harness.*

adds()
    check_eq(2 + 2, 5)

run("adds", &adds)
finish()
```

```output
<page>:4:adds:FAIL: got 4, want 5
1 tests, 1 failed
```

**A passing test reports the line of the `run` that named it and a failing one the line of the check
that failed** — 6 and 4 here. Both fall out of the same rule and neither is a special case: `run`,
`skip` and every check take `file` and `line` as defaulted parameters, and a default is evaluated at
the call, so `__FILE__` and `__LINE__` written once in each declaration hold whatever line the reader
wrote. You never pass either.

The line is `file:line:test:verdict`, which is Unity's order, so the editors and CI filters that
already read that format read this one.

That is the whole difference between this and a program printing characters as it goes. A board that
says `abcdyx4` has told you something went wrong and nothing about which check, on which line, with
which values.

**`got` then `want`**, which is the order [`assert_eq`](/library/core/) takes next door and the order
a failure is read in.

**A failing check does not stop the test.** Unity aborts with `longjmp`; sysl has none, and an unwind
that skipped a scope would skip the releases that scope owes. So a failure is recorded and the test
runs on — and only the **first** failure in each test is reported, because the ones after it are
usually the same failure seen again through worse state.

### The checks

| | |
|---|---|
| `check(cond, msg = "")` | a condition that has to hold |
| `check_eq[T: Eq + Display](got, want)` | two values that have to be equal, both rendered when they are not |
| `check_slice_eq[T: Eq + Display](got, want)` | the same for a `[]const T`, naming the index that differs |

One generic member stands in for Unity's several hundred assertion macros and does more than they
can: `TEST_ASSERT_EQUAL_*` covers a fixed list of C's own types and falls back to a hex dump of the
bytes for anything a program defined, while a bound reaches **any** type at all that can say whether
two of it are equal and write itself down.

`check_slice_eq` says where, because a report that two slices differ sends its reader to find out —
and finding out is a loop nobody wants to write at each call, least of all on a board where the way
to look is a debugger and a stopped core:

```sysl
import sysl.harness.*

bytes()
    check_slice_eq([u8(1), u8(2), u8(3)], [u8(1), u8(9), u8(3)])

run("bytes", &bytes)
finish()
```

```output
<page>:4:bytes:FAIL: got 2, want 9 at index 1
1 tests, 1 failed
```

Length first, since a length mismatch explains every index after the shorter one.

## Three verdicts, because a board needs the third

```sysl
import sysl.harness.*

reads_adc()
    check(true)

run("reads_adc", &reads_adc)
skip("reads_thermocouple", "no part fitted")
finish()
```

```output
<page>:6:reads_adc:PASS
<page>:7:reads_thermocouple:SKIP: no part fitted
1 tests, 0 failed, 1 skipped
```

**On the host a test that cannot run is usually a test that should be deleted; on a target it is
routine** — the part is not fitted, the bus is wired for the other variant, the flash is the smaller
one. Counting those as passes says something false and counting them as failures says something
worse, so they are counted as themselves.

`skip` takes the *name* rather than the body, because the body is exactly what is not going to be
called.

## Saying where the report goes

`attach` points the framework at a writer of your own, which is what a board does before it runs
anything:

```sysl
import sysl.harness.*
import board.*

attach(console())
```

Given none, it writes to standard output — resolved on **first use**, which matters to every program
that never mentions testing: a module's storage is initialized in every image the library is linked
into, and a sink stored eagerly would put a trait object through the reference counter, whose release
path names `free`. A program on a bare board would acquire a call to an allocator it has not got. A
null pointer is a stored zero and reaches nothing.

**A board's console is ordinary sysl.** A UART is a volatile store through a pointer, which is a
[language feature](/reference/statements/), so reaching one needs no `asm`, no semihosting and no C
library:

```sysl
module board

struct Uart
    data: volatile u8

val UART: usize = 0x10000000
val regs: *Uart = ptr_cast(UART)

putc(c: u8)
    regs.data = c

struct Console
end Console

impl Fallible for Console

impl Writer for Console
    write(*self, bytes: []const u8)
        for b in bytes do putc(b)

val uart: *Console = ptr_cast(0usize)

console() -> *Writer = uart
```

That is a real one — QEMU's `virt` puts a 16550 at `0x10000000`, and the compiler's own board tests
use these lines.

**`Writer` requires `Fallible`, so the empty `impl Fallible` is not a formality** — it takes the
default `failed`, which answers false. A UART that cannot fail has nothing to add.

**`Console` has no fields, so its receiver is a null pointer.** A trait object is two words, a method
table and a datum, and `write` here never looks at the datum — everything it needs is the module's
own `regs`. Given nothing to point at, `ptr_cast(0usize)` is the honest spelling; the alternative
would be to take the address of a temporary, which is a dangling pointer with better manners.

**And it has to be a module of its own rather than the file your statements are in.** An `impl`
member cannot see a root file's top-level bindings, so `write` could reach neither `regs` nor `putc`.
One directory down it is ordinary code.

## The verdict, and what to do with it

| | |
|---|---|
| `tests_run() -> int` | how many ran |
| `failures() -> int` | how many failed |
| `skipped() -> int` | how many were skipped |
| `finish() -> i32` | print the tally, and answer `0` if everything passed |
| `quiet: bool` | when true, nothing is printed and only the counters move |
| `reset()` | zero the counters, for a suite that runs more than once |

**`finish` returns rather than exits, and that is the whole of what makes this framework portable.**
A hosted program writes `exit(finish())`. A board has no `exit`: semihosting has `SYS_EXIT`, RISC-V's
`virt` has a magic register, a real one has whatever pin somebody soldered. None of that belongs
inside a framework that would then only build for the target it had heard of — so `finish` hands the
number back and the board signals it however it signals things.

`reset` deliberately leaves the sink alone. A board attaches once at startup, and a reset that undid
that would send the second pass of a looping self-test to a console the board does not have.

## What it is not

- **It does not discover tests.** `run` is a call, so the suite is a list you wrote. On a host `sysl
  test` collects `@test` functions for you; here there is no runner to do the collecting, and a list
  in source is the honest form.
- **It has no fixtures of its own.** A test body is a function; what it needs, it calls first.
  `@setup`, `@teardown`, `@setup_all` and `@teardown_all` are `sysl test`'s, and they work by giving
  each test a process of its own — which is exactly what a board does not have. Here `run` is a call
  inside a program that has already started, so there is no moment outside a test for a hook to be
  scheduled at.
- **It does not time anything.** A clock is the board's and there is no portable one to ask.
