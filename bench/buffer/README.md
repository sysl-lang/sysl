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

## The unboxed value: `Buf { slots: []T, used }`

A is the same dev 13a9b787 compiler and library; B is this branch's stage 2 with `Buf` holding its storage (`[]T` of slot storage) and its count side by side in the value, four words, with copy-on-write asked of the storage's own count. Same procedure: `-O2`, best of 5 of `/usr/bin/time -l`, quiet machine, `caffeinate`. Every program printed the same answer under both.

| program | A instr | B instr | delta | A cycles | B cycles | delta | wall A / B |
|---|---|---|---|---|---|---|---|
| push_sum | 7.492G | 5.991G | -20.0% | 1.811G | 1.154G | -36.3% | 0.40 / 0.25 |
| index_rw | 3.331G | 9.222G | +176.8% | 2.431G | 0.905G | -62.8% | 0.54 / 0.20 |
| empty_create | 17.904G | 1.400G | -92.2% | 2.576G | 0.219G | -91.5% | 0.59 / 0.05 |
| buf_of_buf | 15.077G | 13.220G | -12.3% | 2.167G | 2.027G | -6.4% | 0.50 / 0.47 |
| sort | 3.672G | 3.639G | -0.9% | 3.144G | 3.131G | -0.4% | 0.73 / 0.73 |
| struct_by_value | 17.414G | 14.414G | -17.2% | 2.409G | 2.406G | -0.1% | 0.55 / 0.54 |
| copy_modify | 8.711G | 7.241G | -16.9% | 1.943G | 1.742G | -10.3% | 0.44 / 0.39 |
| refcounted | 15.760G | 15.363G | -2.5% | 2.278G | 2.262G | -0.7% | 0.52 / 0.53 |

Geometric mean of the ratios over the eight: -24.9% instructions, -40.0% cycles. xml_tree was not re-run: its library copies because it was written for buffers that share.

**push_sum's loop keeps the whole buffer in registers.** `push` hands the storage to an out-of-line `placed_past` and takes new storage back, rather than handing it the buffer's address, so nothing takes the local's address and the optimizer holds its four words as values. A turn is one combined test (count against capacity, owner not null), one load of the storage's strong count and its compare -- the load carries the header scope, so the element store does not invalidate it -- and the element store.

**index_rw retires more instructions and far fewer cycles.** The buffer is a field of a module `var`, whose address the out-of-line `own` takes, so each turn reloads the storage's owner, data pointer and count after the element store, and the write `h.b[i] = …` asks `is_unique` (a null test, a load and a compare) besides the three tests against the count: about 25 instructions a turn against A's 9. They are independent loads from L1 and predicted branches; A's 9 reach the count and the storage through the handle and then the box, a chain of dependent loads that the cycles say cost more than B's wider turn.
