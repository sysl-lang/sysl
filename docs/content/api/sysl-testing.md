---
title: sysl.testing
layout: api-module
headingShift: 0
slugStyle: github
module: sysl.testing
summary: "What a test may say about itself while it runs, beyond passing or failing."
---

Not part of the standard module's root: a name there is offered to every file at once, so a test
file importing `sysl.harness.*` (which has a `skip` of its own, for the on-target runner) would be
told the two collide. A test that wants this one imports it — `import sysl.testing.skip`.

## Index

[`skip`](#skip)

## Functions

### `skip`

```sysl
skip(why: string) -> never
```

Ends the test it is called from WITHOUT a verdict, because the test has found it cannot run here
— a tool it drives is not installed, a device it talks to is absent. `sysl test` reports the test
as `skip` with `why` beside it, and counts it with the ignored tests rather than the passed ones: a
test that printed a note and returned would have been a green run over nothing.

It is the run-time half of `@test(ignore: "why")`, which says the same thing before the test starts.
Nothing after the call runs, a helper may make it on the test's behalf, and an assertion that failed
before it is still the failure, since that run never reached the call.

**The protocol is one byte and a clean exit**: a `\u{3}` the runner looks for, the reason up to the
end of its line, then status 0. The clean exit is deliberate: a runner that predates `skip` reads the
test as one that returned, which is what such a test was before, rather than as a failure. Composed
of `prints` for the reason `panic` is, so a `@no_alloc` module's tests may call it.
