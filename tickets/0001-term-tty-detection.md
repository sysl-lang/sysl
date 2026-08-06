Answer "should escapes be emitted at all" for sysl.term

`sysl.term` shipped in 0.0.18 as constants only. What it deliberately left out is the question every
program using it has to answer before writing a single escape: is the output a terminal, is
`NO_COLOR` set, is `TERM` dumb. Every program re-derives that, and most get it wrong by not asking —
which is why a build log full of escape sequences is such a common sight.

**The constraint that shaped the module, and still binds.** A capability requirement is module-wide.
`isatty` needs `posix`, so putting the check beside the constants would take `sysl.term` away from
every `@no_alloc`/`@no_os` program that only wanted to name a colour — which is the case the module
was arranged for. So the answer cannot live in `sysl.term` as it stands.

Three shapes, and the choice is the ticket:

- **A second module** — `sysl.term.tty` or similar, `requires posix`, holding one function. The
  constants stay reachable from anywhere; a hosted program imports both. Cheapest, and the split is
  visible in the import, which is honest.
- **A `Style` value that renders to nothing when colour is off** — the caller asks once, keeps a
  value, and writes it unconditionally. Nicer at the call site, but it is a type where today there is
  a string, and a string concatenates with anything.
- **Leave it to the caller entirely** — document the recipe and stop. Defensible, and it is what the
  page currently says.

Whatever wins, `sysl-lang/table`'s `set_ansi(false)` is the customer: it exists precisely because
nothing in the library answers this.
