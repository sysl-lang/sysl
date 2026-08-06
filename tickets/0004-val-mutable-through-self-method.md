A `val` is mutable through a `*self` method, and by no other route

Every way of reaching writable storage out of a `val` is refused except one, and the exception is the
one people actually write.

```sysl
struct Counter
    n: int

    bump(*self) = self.n += 1
end Counter

main()
    val c = Counter(0)

    c.bump()
    c.bump()

    print(c.n)
```

```
2
```

The same write, spelled any other way, is refused — and the diagnostics are good:

| spelling | today |
|---|---|
| `c.n = 5` | refused: *a 'val' is written once, so assignment has nothing to write through* |
| `poke(&c)` where `poke(c: *Counter)` | refused: *…so '&' has nothing to write through* |
| `ref r = c.n` then `r = 5` | refused: *…so assignment has nothing to write through* |
| **`c.bump()` where `bump(*self)`** | **accepted, and the mutation sticks** |

## It is a soundness hole, not an ergonomics one

A module-level `val` is emitted as `constant`, and the hole hands its address to a mutating method:

```sysl
struct Counter
    n: int

    bump(*self) = self.n += 1
end Counter

val shared: Counter = Counter(0)

main()
    print(shared.n)
    shared.bump()
    print(shared.n)
```

```
0
0
```

The increment vanished. The emitted module says why:

```llvm
@shared = private constant %struct.Counter { i32 0 }
...
call void @Counter.bump(ptr @shared)      ; the address of rodata
store i32 %t4, ptr %t2                    ; ...written through, inside bump
```

**A store into a symbol the compiler declared `constant` is undefined behaviour in LLVM's own terms**,
and LLVM is entitled to assume it never happens — which is exactly why the two loads folded to the
initial value and the program printed `0` twice. Nothing in the source is unsafe; there is no `*T`,
no `extern`, no `unsafe` anything.

So one spelling has three behaviours, none of them diagnosed: on a local it mutates, at module scope
it silently does nothing, and on a target that really protects the page it would fault. That is the
reason to close this, and it is a stronger reason than `val` meaning less than the chapter says.

## Which side is the defect

`design/03-memory-model.md`, under *What may be written*, settles it rather than leaving it to taste:

> a ref into a `val`, or into an element of one, is read-only, since reaching into read-only storage
> keeps the property

and

> A ref inherits the place's writability, and gets no modifier of its own. `ref t = self.tasks[i]`
> under a `*self` receiver may be written; a ref into a `val` may not.

A `val` is read-only storage by the chapter's own words, and a `*self` receiver is a pointer into it.
So the three refusals are correct and **the method call is the hole**: the implicit `&` that a
`*self` call takes of its receiver never asks the question the explicit `&` is asked.

## Where to look

`ExprAnalysis.requirePlace` / `analyzePlace` (~962–1016) is the check, and it is what `&` and
assignment both route through — `readOnlyLocals` for a captured name, `readOnly` for a place. The
method-call path that takes a receiver for a `*self` member does not reach it. The fix is presumably
one call, not a design change.

## The part that needs a decision, not a patch

**Code in the wild already depends on the hole**, including a demo written the same afternoon this
was found: `val t = table()` followed by `t.header(...)` and `t.add(...)`, all of which take `*self`.
That reads perfectly naturally — the *binding* is written once, and the thing it names is being
filled in — and it is exactly what the refusal would break.

So closing this is a real source change for anybody who wrote `val` where `var` was meant, and the
error message has to be worth reading when it fires. Two things worth settling together:

- whether the diagnostic should name the **method** (`'bump' takes '*self', which needs storage that
  can be written`) rather than reusing the assignment wording, since the write is not at the call;
- whether `var` is what a reader would have reached for anyway. If nearly every use of a mutable
  struct wants `var`, the hole has been hiding a papercut rather than preventing one.

## The Scala reading, raised and answered

The natural objection is Scala's: a `val` fixes the *binding*, and what it holds may still be mutable.
Three facts decide it, and the third is not a matter of taste.

- **That reading already exists here, spelled `&`.** Every Scala object is behind a reference, so the
  analogue is a reference field — and it works today with no hole involved:

  ```sysl
  val h = Holder(0, buf())

  h.xs.push(1)          // accepted, and correct: the Buf is behind a `&`
  ```

  This is why `val t = table()` mostly works. `Table`'s `cells`, `aligns` and `rules` are `&Buf`, so
  adding a row is legitimate; only `self.ncols` and `self.pending` are the struct's own bytes.

- **The disputed case has no Scala analogue.** Scala has no inline structs, so it never has to say
  what happens when the contents *are* the binding's storage. Here they are.

- **A module-level `val` is emitted as `constant` — rodata.** `val table: [4]int = [10, 20, 30, 40]`
  lowers to `@table = private constant [4 x i32]`. Permitting field writes to a `val` is therefore
  not a semantics choice at module scope, it is a write to read-only memory. Keeping the Scala
  reading would mean `val` meaning one thing for a local and another for a module-level table, which
  is worse than either.

So the chapter's reading stands — `val` is read-only storage — and `&` is how a program asks for a
fixed name over mutable content. The cost of closing the hole is one word at the call site, `var`.

Found by probe, 2026-08-06, while writing a table demo. Not fixed, deliberately — the fix is small and
the fallout is not, and nothing is unsound today beyond `val` meaning less than the chapter says.
