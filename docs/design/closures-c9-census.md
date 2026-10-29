# Closures C9 — the by-reference migration census

Item C9 of [`closures.md`](closures.md) §14: before 0.8.0 ships, every closure in the org whose
meaning changes now that a closure that does not escape captures its `var`s **by reference** (0.7.x
captured every one by value) is found and either fixed in its repository with an explicit copy or
recorded here as benign. Census only, run once; no diagnostic ships.

**Result: no closure in the org changes meaning.** 122 projects were walked, 11 closure literals
matched a shape, every one is benign, and no repository needed a fix.

## What was looked for

A closure held by reference sees the variable; one held by value saw a copy taken where the closure
was formed. The two differ only where the variable can change between the closure's forming and its
call, or where the closure's own write is observed by someone else. So a literal is a hit when it is
held by reference (`by_ref_closures`) and, for some capture it holds by reference:

| flag | shape | `closures.md` |
|---|---|---|
| `writes` | the closure writes the capture (`closure_env_writes`) — by value the write reached its own copy | M1, M3 |
| `in-loop` | the literal is formed inside a loop (reported only with another flag) | M1 |
| `addressed@L` | the enclosing body takes the variable's address anywhere — `&n` handed on, a `*self` method call on it, a nested function capturing it | M2 |
| `written-after@L` | the enclosing body writes the variable after the literal, or anywhere in a loop that also forms it | M2 |
| `shared-with-writer@L` | another by-reference literal in the same body captures the same variable and writes it | M1, M2 |

The flags are a superset: a write after the literal matters only if the closure is still alive and
called after it, which the census does not try to decide; each hit was read by hand.

**How.** A stage 1 of dev at `ad36c5eb` with one extra file,
[`closures-probes/c9_census.sysl`](closures-probes/c9_census.sysl), called last in
`decide_closure_modes` (after every literal's mode is settled) and keeping the run cache out of the
way, printing `C9|file:line|capture|flags` on standard error under `SYSL_C9_CENSUS=1`. It walks the
typed body of every function the compilation analysed, the entry file's statements and every
generic instantiation included. Checked first against a probe holding each shape (a `val f` called
after a write; `bump(&n)`; a `*self` method; a writer in a loop; a writer and a reader over one
variable; a nested function that writes) — each reported, and a closure handed straight to a call
over a variable written before it not reported. The tool is not in the compiler; the file is kept
beside the probes as the record. [`closures-probes/c9-sweep.zsh`](closures-probes/c9-sweep.zsh) is
the driver.

**Over what.** Every repository under `~/dev/sysl-lang` with its own `.git` directory and a
`sysl.hocon` or `package.hocon` (120, `sysl-bootstrap` and `sysl.sh` left out), plus the compiler
tree (`compiler/`, `sysl build .`) and the standard library (`sysl test ../library --std`). A
package ran `sysl test .` with a filter that matches nothing — the test build analyses the
`@tests` files too; a program ran `sysl build .`. A workspace, and a repository whose C headers
stopped the build, ran `emit-typed .` (which settles the modes as a build does); `tinyusb` ran its
suite's own features (`sim,cdc,msc`).

## The hits

| where | capture | flags | verdict |
|---|---|---|---|
| `benchtop/sh/sysl/benchtop/virt/dsi_worker.sysl:146` | `response` | in-loop, addressed@143 | **benign.** `&response` is handed to `apply_dsi_packet` on line 144, which finishes before the closure on 146 is formed; the closure runs inside `with` on that line. No write between forming and call. |
| `kfs/sh/sysl/kfs/fsck_support.sysl:101` | `sb` | addressed@95 | **benign.** `&sb` goes to `geometry_of` (a read) above the literal; `sb` is never written after. |
| `kfs/sh/sysl/kfs/fsck_pass_tests.sysl:569` | `sb` | in-loop, addressed@563 | **benign.** The same: `geometry_of(&sb)` before; the closure runs inside `disk_desc` on its own line. |
| `raytrace/obj.sysl:39`, `:40`, `:41`, `:48` | `line_no` | in-loop, written-after@32 | **benign.** `map_err(... BadNumber(line_no))` runs on its line; `line_no += 1` is at the head of the next turn, after the closure is gone. |
| `protobuf/sh/sysl/protobuf/schema/resolve.sysl:298` | `at` | in-loop, written-after@298 | **benign.** `at = at.last_index_of(".").map(d -> at[0..<d])...` — `map` calls the closure before the assignment writes `at`. |
| `sysl/compiler/sh/sysl/compiler/expr_flow.sysl:642` | `want` | in-loop, written-after@646 | **benign.** `offering(cx, want, () -> ...)` runs the closure in the call; line 646 writes `want` after the loop. |
| `keel/kernel/keel/selftest/kfs.sysl:139` | `dirs` | in-loop, written-after@134 | **benign** (keel: another session's; listed, not touched). `op(p, () -> ...)` runs the closure on its line; the write on 134 is the next turn's. |
| `keel/kernel/keel/selftest/kfs.sysl:288` | `pos` | in-loop, written-after@294 | **benign** (keel). `op(p, () -> pkg.read_dir(p, dir, pos, 4096))` runs on its line; `pos = l.next` is after. |

No literal carried `writes` or `shared-with-writer`: no closure in the org writes a capture it holds by
reference, which agrees with §11's sweep (every 0.7.x writer that compiled was a by-value closure,
and the one that escaped, webview-demo's, still does).

**Fix branches: none.**

## What the census could not see

- **Not analysed** — the build stopped before the bodies were typed: `duckdb`, `orbits` and
  `quickjs-ng` (their C headers are not on this machine), `freertos`, `zephyr` and `zephyr-demo`
  (their toolchain's headers — red by design here), and `skiff` (every member stops at a
  declaration error against dev: an annotation a member may not carry).
- **Analysed with errors** — stale against dev's library (`ptr_cast`/`str_cast` moved to
  `sysl.raw`): `forth`, `keel`, `kfs`, `libpq`, `skiff-old`. Their remaining bodies were walked
  (the kfs and keel hits above come from them), but a body that failed to type was not.
- For those twelve, a grep for the one shape most likely to bite — a closure bound to a local and
  called later — found only closures over `val`s (`kfs`' crash tests' `op`, keel's
  `closure_result`), which are copied whatever the closure's mode.
- The post-release org sweep (every repository built with the 0.8.0 binary) covers them again.
