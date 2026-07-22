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

## 2. Default to Swift/Go precedent

For design decisions where **Swift and/or Go** have made a considered choice, follow their
lead by default rather than reinventing or inheriting C/C++ legacy. They are modern,
well-designed, pragmatic languages, and leaning on their decisions kills a large amount of
bikeshedding.

- **When they conflict, weigh by fit with sysl's nature.** sysl's DNA aligns more with
  **Swift**: ARC (Swift), not GC (Go); operator overloading and traits (Swift has both, Go
  has neither); embedded-capable (Embedded Swift exists). So for type-system and semantics
  questions, lean Swift. **Go is the reference for minimalism and systems-pragmatism** — reach
  for it on "is this simple enough?" questions.
- **This is a default, not a mandate.** It is always sanity-checked on the merits. Sometimes
  the merits point elsewhere — e.g. shift-precedence followed Go over Rust/Zig *because* the
  merits agreed (and Swift happened to concur). The principle removes the need to argue every
  small choice from first principles; it does not override a clear better answer.
- Where only one of the two has made the decision (e.g. Go has no range operator), the one
  that has is the model.
