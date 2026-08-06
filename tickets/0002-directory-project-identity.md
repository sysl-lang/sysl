Decide whether a directory project needs a package.hocon

Raised while fixing where a build writes (0.0.18): the suggestion was that a directory project should
be *required* to carry a `package.hocon`, so the output could be named from the package rather than
from the path.

**It would not have fixed that bug** — the collision was about *where* the file went, not what it was
called, and a package named `test` in a directory named `test` collides exactly as before. That is
settled and the fix shipped without it.

**But the question underneath is real and unanswered: is a directory a project because somebody said
so, or because it happens to hold `.sysl` files?** Today it is the second. `Project.collect` walks
whatever it is pointed at, so any directory is a project, and a project has no identity of its own
unless a `package.hocon` gives it one.

What requiring one would buy: an identity to name outputs, artifacts and diagnostics after; one place
that says what a project *is* rather than inferring it; and a refusal when somebody points the
compiler at the wrong directory, which today compiles whatever was in there.

What it would cost, and this is the part to weigh honestly: **15 directories in this repo have no
`package.hocon`** — every `guide/` and `examples/` project — and neither does a scratch directory
anybody makes in thirty seconds. Requiring one turns the cheapest thing in the toolchain into a
two-file ceremony, and the guide programs are supposed to be read as ordinary sysl.

A middle answer exists and is probably the right one: keep bare directories buildable, and let a
`package.hocon` *name* the output when there is one. That composes with what 0.0.18 already does and
costs nobody anything.
