# Project Principles

Standing rules for how the sysl restart is built. These govern process and judgment, not any
one language feature. They are as binding as the design decisions in the numbered docs.

## 1. Be cautious about reusing old code

Reuse from the old sysl — or from any prior codebase — is treated with **suspicion, not
enthusiasm**. The default is to write each piece fresh against the current design.

- **Take the time to get it right.** There is no schedule pressure here. Getting a thing
  correct and wart-free is worth more than getting it done quickly.
- **Close-but-not-exact is a reason to rewrite, not to adopt.** If old code is close to what
  we want but not *exactly* what we want, redo it. Adopting the near-miss imports its
  assumptions and quietly makes them ours.
- **Old code is reference and lessons, not a source to copy.** Read it to see how a problem
  was solved and — just as valuable — what to avoid. Then write the version this design
  actually calls for.
- **Why this rule exists.** The whole point of the restart is to shed the old design's
  warts, including ones imposed by frameworks and tooling rather than by the language itself.
  Carried-over code drags those warts back in silently. The old sysl "got out of control" in
  part by accreting; caution about reuse is how this one stays deliberate.

This does not forbid reuse — it forbids *reflexive* reuse. When an old piece is genuinely,
exactly what the new design wants, reuse it consciously and say why. The rule is that the
burden of proof is on reuse, not on rewriting.

## 2. Default to the inspirational languages' precedent

For design decisions where an inspirational language has made a considered choice, follow its
lead by default rather than reinventing or inheriting C/C++ legacy. These are modern,
well-designed, pragmatic languages, and leaning on their decisions kills a large amount of
bikeshedding. The inspirations, roughly by influence:

- **Swift** — the closest DNA match: ARC (not GC), protocols, operator overloading,
  embedded-capable (Embedded Swift). Lean here for type-system and semantics questions.
- **Go** — minimalism and systems-pragmatism; reach for it on "is this simple enough?"
  questions. (Its structural interfaces are the one place it's the odd one out — see below.)
- **Kotlin** — pragmatic modern design and ergonomics; a strong reference for everyday syntax
  and the OO/functional blend.
- **Scala** — mostly **syntax** (expression-oriented, literate feel), possibly more.

Guidance:

- **When they conflict, weigh by fit with sysl's nature and the merits.** sysl is ARC +
  nominal + traits + embedded, aligning it with Swift/Kotlin/Scala over Go on type-system
  questions; Go is the reference for restraint. *Example:* on interface-like polymorphism,
  Swift (protocols), Kotlin (interfaces), and Scala (traits) are all **nominal, single
  mechanism** — only Go is structural — so a 3-way nominal consensus outweighs the lone Go
  outlier.
- **This is a default, not a mandate** — always sanity-checked on the merits. Shift-precedence
  followed Go over Rust/Zig because the merits agreed; the inclusive-range spelling took
  Kotlin's `..` over Swift's `...`.
- Where only one has made the decision (e.g. Go has no range operator), that one is the model.
