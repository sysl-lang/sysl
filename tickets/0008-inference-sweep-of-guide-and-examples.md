Sweep guide/ and examples/ for types that inference already gives

`guide/` carries **452** `<digit>usize` suffixes and `examples/` carries **2**. Nearly all of them are
noise: a literal takes its type from where it sits, so the suffix says nothing the compiler did not
already know and costs the reader a moment wondering what subtlety they missed.

This matters more here than anywhere else in the tree, because `guide/` and `examples/` are the
**reading material** — the programs somebody opens while deciding whether they like the language.
User, 2026-08-07, on seeing it in `sqlite-repl`: *"i keep seeing things like `1usize` instead of just
writing `1`. you do that a lot. it'll turn people right off."*

The standing rule is in `~/dev/CLAUDE.md` under *Writing sysl — lean on inference*. This ticket is the
backlog that predates it. `sqlite-repl` has been done already and is the worked example of the result.

## What is removable, verified against 0.0.23

Each of these was compiled before being written down:

```sysl
reseal(src[..], 8usize)         →  reseal(src[..], 8)          -- literal adapts to the parameter
mangles(…, 0usize, 0x79u8, …)   →  mangles(…, 0, 0x79, …)      -- same, including hex
for i in 0usize..<4usize        →  for i in 0..<4              -- and still indexes a slice
if args.len > 1usize            →  if args.len > 1
b[0usize] / b[0usize..<2usize]  →  b[0] / b[0..<2]
c == 59u8                       →  c == u8(';')                -- readable, and a char literal will
                                                                  not compare to a byte directly
```

Arithmetic propagates too: with `i: usize`, `var j = i + 1` is a `usize`.

## What must stay

- **A `var` with no context.** A bare integer literal alone infers `int`, so `var i = 0` followed by
  `while i < b.len` fails with *"cannot compare int with usize"*. The fix is `var i: usize = 0` —
  said **once, on the declaration**, not on every literal after it.
- **An annotation the compiler asks for.** `var head: Buf[&Display] = buf()` is required; `buf()` has
  nothing to infer its element type from and says so.

The test is mechanical: delete it and see whether it still compiles.

## The second axis — shapes, not just suffixes

**18 files in `guide/` have a hand-rolled counter loop.** Where the loop is a straight walk, replacing

```sysl
var i = 0usize
while i < b.len
    …
    i += 1usize
```

with `for i in 0..<b.len` removes the suffix *and* the counter *and* the chance of forgetting the
increment. Do this where it is a plain walk; leave the `while` where the index moves irregularly, as
in a scanner that skips ahead.

## Worst files first

```
guide/png/main.sysl          56      guide/slab/main.sysl        19
guide/datetime/main.sysl     50      guide/bytecode/vm/vm.sysl   18
guide/table/main.sysl        34      guide/lisp/lisp.lsysl       15
guide/png/png.sysl           20      guide/table/table.sysl      13
guide/kernel/main.sysl       20      guide/scheduler/sched.sysl  13
```

## Scope and gate

**`lib/` is deliberately out of scope** and carries another 324. It is read, but it is not what a
newcomer opens first, and mixing it in makes one reviewable diff into an unreviewable one. It wants
its own ticket.

`GuideTests` and `ExampleTests` compile this tree, so the sweep owes **those two suites and not the
full gate** — nothing here touches `shared/src/main` or `lib/`, so the blast-radius rule says the
suites that can observe the change and nothing else. Run the `git diff --stat origin/dev <branch> --
shared/src/main lib/` guard to confirm it is empty before deciding otherwise.

**A literate file (`.lsysl`) has prose around the code**, so a suffix removed from a snippet may also
be quoted in the paragraph beside it. Read the prose, not only the code.
