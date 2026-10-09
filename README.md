# sysl

The sysl compiler, written in sysl.

**This is the compiler that ships as `sysl`.** It builds itself; the Scala bootstrap compiler
(`sysl-lang/sysl-bootstrap`) is retired and archived. The language is specified by the pages
in `docs/content/`, which [sysl.sh](https://sysl.sh) publishes from a release tag.

## The layout

| directory | what it holds |
|---|---|
| `compiler/` | the compiler: its `package.hocon`, `sysl.sum` and the module `sh.sysl.compiler` |
| `library/` | the standard library, the tree whose root module is `sysl` |
| `docs/` | the documentation site: juicer's `site.toml`, the themes, the static files, the grammar, and under `docs/content/` the pages — the reference, the library, the tour — every `sysl` block of which the compiler's suite builds and runs (`tests_docs.sysl`, with `SYSL_DOCS=1`) |
| `scripts/` | the censuses that compare this compiler with the reference |

The compiler is a project of its own in `compiler/` because a compiler reads every `.sysl` file
under a project's root as part of that project, and the library's modules are not the compiler's.

The site at [sysl.sh](https://sysl.sh) is built by [juicer](https://github.com/edadma/juicer) from
`docs/`. `.github/workflows/docs.yml` builds it on every push to `dev`
and deploys it to GitHub Pages only from a release tag, so the published site documents a release.
Building it needs juicer and nothing else — no sysl compiler or library. A local preview is
`sbt 'juicerJVM/run serve -s <this repository>/docs -L'`, run from a juicer checkout.

## Building

```
cd compiler
SYSL_LIB=../library sysl build .
SYSL_LIB=../library sysl test .
```

Both commands are the bootstrap compiler's, installed with `brew install sysl-lang/tap/sysl`. The
first produces `compiler/sysl`; the second runs every `@test` in the tree. `SYSL_LIB` names the
library the compiler under test compiles programs against; the standard library's own suite is
`SYSL_LIB=../library ./sysl test ../library --std`.

## The road to self-hosting

1. A conformance suite: programs with the output and the diagnostics they owe, in a form either
   compiler can be run against. It is green against the bootstrap before anything else is written.
2. A scanner and a parser for sysl's grammar, written by hand, producing the same tree the bootstrap
   does. **Both are written.** `lex.sysl` turns bytes into tokens and turns the shape of a file into
   `Newline`, `Indent` and `Dedent`, so that nothing above it ever looks at a column; `parse.sysl`
   and the files beside it read a whole file — every expression, type and pattern, every statement,
   every declaration, the header, the annotations, and a literate `.lsysl` document. It is checked
   against every file of this compiler's own source, of the standard library, and of the parsing
   package it is built on.
3. Analysis, then LLVM emission, phase by phase against the suite.
4. This compiler builds itself, and the compiler it builds builds the same thing again.

## License

ISC. See `LICENSE`.
