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

## Performance

Measured for sysl 0.13.0 on an Apple M5 Pro (6 performance and 12 efficiency cores, 64 GiB, macOS
27.0.1) on 2026-11-22, with Apple clang 21.0.0 as the backend and as the C and C++ compiler.

**Are We Fast Yet**, 14 benchmarks, 30 invocations of 10 iterations, one thread. Geometric mean of
each language's time relative to C (lower is faster), fastest first:

| language | vs C |
|---|---:|
| C | 1.000 |
| Zig | 1.044 |
| C++ | 1.116 |
| Rust | 1.209 |
| sysl | 1.369 |

Per benchmark, with C++ as the baseline: the geometric mean
of sysl against C++ is **1.226**: sysl takes 1.23 times as long (C 0.896, Rust 1.083, Zig 0.935 on
the same scale; sysl is 1.37 times C, 1.13 times Rust and 1.31 times Zig). Each cell is sysl's mean
time per iteration divided by the other language's; below 1 is faster.

| benchmark | sysl / C++ | sysl / C | sysl / Rust | sysl / Zig |
|---|---:|---:|---:|---:|
| DeltaBlue | 1.47 | 1.75 | 1.70 | 1.74 |
| Richards | 1.81 | 2.15 | 1.77 | 2.56 |
| Json | 1.30 | 1.92 | 1.47 | 0.77 |
| CD | 1.70 | 1.74 | 1.56 | 1.73 |
| Havlak | 1.29 | 1.80 | 1.67 | 1.82 |
| Bounce | 0.96 | 1.00 | 0.70 | 1.03 |
| List | 1.09 | 1.09 | 1.02 | 1.09 |
| Mandelbrot | 0.99 | 0.99 | 1.00 | 1.00 |
| NBody | 0.90 | 1.04 | 0.51 | 0.94 |
| Permute | 1.26 | 1.25 | 0.78 | 1.23 |
| Queens | 1.68 | 1.72 | 1.44 | 1.53 |
| Sieve | 1.01 | 1.02 | 0.95 | 1.12 |
| Storage | 0.99 | 1.16 | 1.12 | 1.45 |
| Towers | 1.15 | 1.27 | 1.13 | 1.29 |

sysl is slower than C++ on Richards, CD, Queens, DeltaBlue, Json, Havlak and Permute; it is faster on
Bounce and NBody and level on Mandelbrot, Sieve and Storage.

**plb2**, four programs, geometric mean of mean wall time relative to C (lower is faster), fastest
first:

| language | vs C |
|---|---:|
| C | 1.00 |
| D | 1.01 |
| Nim | 1.02 |
| sysl | 1.03 |
| Rust | 1.04 (three programs) |
| Swift | 1.11 |

sysl per program: nqueen 1.09x, matmul 1.00x, sudoku 1.00x, bedcov 1.04x. Zig has no cells (upstream's programs do not build on Zig 0.17.1), and Rust's bedcov prints a
wrong answer, so Rust's figure is over three programs.

The full tables, the methodology and the scripts that reproduce both runs are in
[sysl-lang/bench](https://github.com/sysl-lang/bench) (`awfy/METHODOLOGY.md`, `awfy/reproduce.zsh`,
`plb2/METHODOLOGY.md`, `plb2/reproduce.zsh`); the same figures are on the
[Performance](docs/content/getting-started/performance.md) page.

Further speed improvements are an active area of research and development.

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
