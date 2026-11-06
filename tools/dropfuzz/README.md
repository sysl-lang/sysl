# dropfuzz

A drop-count fuzz harness for the compiler. It generates programs that make, share, store, capture,
box, match out of and drop a counted value, builds each one with the compiler under test in three
modes, runs them, and checks that every value is dropped exactly once, never read after, and in the
order `reference/memory.md` promises. A failing program is reduced on the generator's tree and
written out, so it can become a `dfz_` test.

## Running it

Build it with the compiler you are testing, from this directory:

```
SYSL_LIB=<repo>/library <stage1> build . -o dropfuzz
```

Run it through a build slot, because every program is built three times and several are built at
once:

```
SLOT_TIMEOUT=30 caffeinate -dimsu zsh ~/dev/sysl-lang/sysl/.claude/scripts/take-slot.zsh . ./dropfuzz --compiler <stage1> --lib <repo>/library --seed 1 --count 1000 --workers 4 --out failures
```

**The slot kills a run at its deadline, so set `SLOT_TIMEOUT` (in minutes) to fit the count.** With
four workers, 1,000 programs take about 8 minutes and 10,000 about 75, with another slot busy beside
it. Allow 10 minutes per 1,000 programs and 15 more, plus time for reductions if failures are
expected: 30 for 1,000 programs, 120 for 10,000. A run that reaches its deadline is hanging (a
generated program that never ends is killed by its own 30-second timeout), so read the slot's sample
rather than re-running.

| option | meaning |
|---|---|
| `--compiler` | the `sysl` that builds the generated programs |
| `--lib` | that compiler's standard library, as an absolute path |
| `--seed`, `--count` | the run covers seeds `seed` up to `seed + count`; a seed always names the same program |
| `--workers` | how many programs are built at once, each in three modes |
| `--out` | where a failing program is written, as `fail-<seed>.sysl` |
| `--no-reduce` | write a failing program as generated rather than reduced |
| `--show <seed>` | print the program a seed names, and nothing else |

A failing program's file starts with a comment for each oracle it broke, before and after reduction.
The exit status is non-zero when any program failed.

The harness's own tests are `sysl test .` here. The end-to-end ones plant a leak and a use after free
and require the oracles to catch them. They run only when `DROPFUZZ_COMPILER` and `DROPFUZZ_LIB` name
a compiler and its library.

## The oracles

1. **Dropped once.** Every `Tracked` that is made is dropped exactly once, and nothing is dropped that
   was not made.
2. **No use after drop.** No read follows the value's drop in the log, and the program's own `live`
   flag is up at every read.
3. **The heap nets zero.** The `-O0` build runs on a counted heap (`SYSL_COUNT_ALLOCS=1`), and it must
   report nothing at exit.
4. **AddressSanitizer is clean.** One build runs under ASan with `detect_stack_use_after_return=1`. The
   first program of a run is checked with `nm` to make sure it really is instrumented.
5. **The modes agree.** The output at `-O0`, under ASan, and at `-O2` (even seeds) or with thin LTO
   (odd seeds) is the same.
6. **The order is the promised one.** At the end of every block, the walk (`walk.sysl`) names the
   values only that block's locals hold, and the log must show them in the promised order:
   - locals in the reverse of their declaration, with each `defer` in its written place;
   - a pair's fields in declaration order, on the frame and on the heap;
   - a buffer's elements in index order.

   A temporary must be dropped at the end of the statement that made it.

## The grammar

The events are listed in `tree.sysl`'s `Op`:

- `Tracked` locals, both `val` and `var`, reassigned and aliased;
- temporaries;
- pairs on the frame and on the heap, with `(self)` and `*self` receivers and a field store;
- a trait object;
- `Buf` and `Map` of `&Tracked`;
- `weak`;
- closures and `&Fn` boxes;
- a payload enum, matched and taken out of;
- `defer`;
- `if`, `for`, `break`, `continue`, early `return`, `?` through fallible helpers, and helper calls;
- a `&sync Tracked` read by a thread on `sysl.threads.scope`;
- `async` tasks that are run, held, awaited, or dropped without being awaited.

Not generated yet:

- recursion;
- `async` bodies that `await` inside;
- generic functions;
- `@consumes`;
- promotion-triggering views;
- `Buffer`'s value semantics, which are not on dev yet (the name is the one constant `buf_type` in
  `tree.sysl`).
