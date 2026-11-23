---
title: Performance
summary: How fast sysl 0.13.0 runs, against C++, C, Rust and Zig on Are We Fast Yet and against C, Rust, Swift, Nim and D on plb2.
weight: 60
---

These are the figures for sysl 0.13.0, measured on an Apple M5 Pro (Mac17,8, 6 performance and 12
efficiency cores, 64 GiB, macOS 27.0.1) on 2026-11-22, with Apple clang 21.0.0 as sysl's backend and
as the C and C++ compiler. Both suites are published with their methodology and a script that
reproduces them in [sysl-lang/bench](https://github.com/sysl-lang/bench): `awfy/METHODOLOGY.md` and
`awfy/reproduce.zsh` for Are We Fast Yet, `plb2/METHODOLOGY.md` and `plb2/reproduce.zsh` for plb2.

## Are We Fast Yet

The [Are We Fast Yet](https://github.com/smarr/are-we-fast-yet) suite is fourteen small programs
that stress object allocation, virtual calls, collections, strings and floating point. Each language
runs the same algorithm. The run is 30 invocations of 10 iterations per benchmark and language, with
the languages alternating, on one thread. sysl is built with `--release -O3 --cpu native --lto full`;
C++ and C with `-O3 -flto -mcpu=native`; Rust with `-C target-cpu=native`; Zig with
`-O fast -mcpu=native`.

The geometric mean over the fourteen benchmarks of each language's time relative to C (lower is
faster), fastest first:

| language | geometric mean vs C |
|---|---:|
| C | 1.000 |
| Zig | 1.044 |
| C++ | 1.116 |
| Rust | 1.209 |
| sysl | 1.369 |

The per-benchmark figures follow, with C++ as the baseline.

**The geometric mean over the fourteen benchmarks, against C++ (the baseline), is 1.226 for sysl**
(95% bootstrap interval 1.223 to 1.229): sysl takes 1.23 times as long. The other languages, on the
same scale: C 0.896, Rust 1.083, Zig 0.935. Written the other way, sysl takes 1.37 times as long as
C, 1.13 times as long as Rust and 1.31 times as long as Zig. Below 1 is faster; each cell is
sysl's mean time per iteration divided by the other language's.

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
| **geometric mean** | **1.226** | **1.37** | **1.13** | **1.31** |

**Where sysl is slower.** Richards (1.81 times C++), CD (1.70), Queens (1.68), DeltaBlue (1.47) and
Json (1.30) are the largest gaps, and Havlak (1.29) and Permute (1.26) follow. sysl is faster than C++
on Bounce and NBody and level on Mandelbrot, Sieve and Storage. sysl 0.12.0 measured 1.521 against
C++ on the same suite.

## plb2

[plb2](https://github.com/attractivechaos/plb2) is four programs (nqueen, matmul, sudoku, bedcov)
written in many languages. The geometric mean of each language's time relative to C (lower is
faster), fastest first:

| language | geometric mean vs C |
|---|---:|
| C | 1.00 |
| D | 1.01 |
| Nim | 1.02 |
| sysl | 1.03 |
| Rust | 1.04 (over three programs) |
| Swift | 1.11 |

The per-program table gives mean wall time in seconds as measured by hyperfine
(`-N --warmup 1`), with the ratio to C in brackets.

| program | C | Rust | Swift | Nim | D | sysl |
|---|---:|---:|---:|---:|---:|---:|
| nqueen | 1.770 (1.00x) | 1.831 (1.03x) | 2.000 (1.13x) | 1.756 (0.99x) | 1.777 (1.00x) | 1.930 (1.09x) |
| matmul | 0.381 (1.00x) | 0.385 (1.01x) | 0.382 (1.00x) | 0.384 (1.01x) | 0.387 (1.02x) | 0.380 (1.00x) |
| sudoku | 1.102 (1.00x) | 1.169 (1.06x) | 1.155 (1.05x) | 1.090 (0.99x) | 1.085 (0.98x) | 1.097 (1.00x) |
| bedcov | 0.609 (1.00x) | 0.684 (1.12x) | 0.779 (1.28x) | 0.657 (1.08x) | 0.640 (1.05x) | 0.632 (1.04x) |
| **geometric mean vs C** | **1.00x** | **1.04x** | **1.11x** | **1.02x** | **1.01x** | **1.03x** |

Cells that are absent or need a caveat:

- **Zig** has no cells: upstream's programs do not build on Zig 0.17.1, and plb2 has no Zig sudoku or
  bedcov at all.
- **Rust bedcov** prints a wrong answer (8195479573 where C prints 8195494005), so its time is not
  evidence of anything; the Rust geometric mean is over three programs.
- **D matmul** prints -143.5, right to the one decimal place it prints, and **Nim sudoku** prints the
  same solutions without the blank line after each puzzle.

Toolchains: Apple clang 21.0.0 (`-O3 -fwrapv -flto`), rustc 1.94.0 (`opt-level=3`), Apple Swift 6.4.2
(`-Ounchecked`), Nim 2.4.2 (`-d:release -d:lto`, checks off), LDC 1.43.0 (`-O3 -release`), and sysl
0.13.0 (`--release -O3 --lto full`).

Further speed improvements are an active area of research and development.
