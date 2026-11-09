# Buffer benchmarks: boxed `Buf` against copy-on-write `Buf`

Each directory is one program (`sysl build <dir>/main.sysl -O2`), sized to run about half a second. A is dev 13a9b787's compiler
and library (boxed handle); B is buffer-cow2's stage 2 and library. The same source builds under both. Figures are the best of
5 runs of `/usr/bin/time -l` (instructions retired, cycles elapsed), on a quiet machine under `caffeinate`; the last column is wall seconds A / B.

| program | A instr | B instr | delta | A cycles | B cycles | delta | wall A / B |
|---|---|---|---|---|---|---|---|
| push_sum | 7.493G | 9.497G | +26.7% | 1.763G | 4.026G | +128.4% | 0.39 / 0.88 |
| index_rw | 3.333G | 9.959G | +198.8% | 2.324G | 1.124G | -51.6% | 0.51 / 0.25 |
| empty_create | 17.906G | 2.691G | -85.0% | 2.545G | 0.385G | -84.9% | 0.58 / 0.08 |
| buf_of_buf | 15.079G | 16.300G | +8.1% | 2.161G | 3.798G | +75.8% | 0.49 / 0.86 |
| sort | 3.674G | 3.738G | +1.7% | 3.135G | 3.155G | +0.6% | 0.68 / 0.69 |
| struct_by_value | 17.416G | 17.416G | +0.0% | 2.415G | 2.422G | +0.3% | 0.55 / 0.55 |
| copy_modify | 8.712G | 8.811G | +1.1% | 1.927G | 1.943G | +0.8% | 0.43 / 0.44 |
| refcounted | 15.760G | 15.761G | +0.0% | 2.319G | 2.326G | +0.3% | 0.53 / 0.53 |
| xml_tree (xml reader benchmark) | 65.933G | 90.909G | +37.9% | 14.003G | 18.577G | +32.7% | 3.20 / 4.25 |
| compiler_emit_llvm (`emit-llvm -O2` of the xml benchmark; libraries differ) | 12.111G | 11.748G | -3.0% | 3.074G | 3.049G | -0.8% | 0.83 / 0.83 |

Geometric mean of the ratios: the eight microbenchmarks -5.6% instructions, -14.0% cycles; all ten rows -1.6% instructions, -8.9% cycles.

Where B is slower: `push_sum` and `buf_of_buf` (growth by `push`, then reads; `BufCore.grow` and `main` dominate the samples, with the loops inlined into `main`),
and `xml_tree`, whose sample shows `sysl.buf$Buf.copied[Name]` and more malloc/free (`_xzm_malloc_tc`, `_xzm_free_tc`) than A.

slate's interpreter was not measured: it is pinned to sysl 0.8.15 and its dependencies (brotli, hiredis, json, kairos) call `Buf.at`/`Buf.set`, which no longer exist.
