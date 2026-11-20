# PARKED: fix-scoped-join (sysl 0.12.1 patch, branch off origin/release-0.12)

## The bug
`ScopedThread[R].join` refused any non-`Share` R (string, Buf, &T, struct/Option holding one):
`self.out.value.with((v) -> v.unwrap())` is checked by the compiler's guarded-result rule
(compiler/sh/sysl/compiler/guarded.sysl), which refuses a counted value leaving a `Mutex.with`.
Shortest input:
```
import sysl.threads.scope
val n = 7
print(scope((s) -> s.spawn(() -> f"worker-$n").join()))
```

## Done (uncommitted work is in this WIP commit)
- `library/sysl/threads/scope.sysl`: the slot is now `Mutex[Answer[R]]` with a private
  `enum Answer[R] { Pending, Ready(value: R), Taken }`. `join` branches with `if const R: Share`:
  - `Share` R: unchanged behaviour (answer a share through `with`; a second join answers the same).
  - otherwise: `lock_raw`, `slot_take` the answer out, `slot_put(held, Taken)`, `unlock_raw`; a
    second join panics "sysl.threads: a scoped thread's answer was moved out by an earlier join".
  - `is_finished` = slot is not `Pending`.
  Docstring of `ScopedThread` updated to say this.
- Why not "move always": handles are COPYABLE and may be joined on several threads
  (tests `scope_join_twice`, `scope_handle_joined_by_a_sibling`), and the docs promise a second join
  answers the same value. For counted R that is a non-atomic-count race (and Out's last drop may
  happen on another thread), so it can only hold for `Share` R. `if const R: Share` keeps every
  program that compiled before unchanged, so it is patch-safe. FLAG FOR THE USER: the trap on a
  second join of a non-Share answer is a design choice (alternatives: answer Option, or make the
  handle non-copyable for non-Share R).
- Other APIs checked: `Thread.join` answers bool (no slot); `Channel.receive` moves out of a Ring
  (no `with`); fibers have no results; `Mutex`/`Guarded` used with `.with` only in scope.sysl.
  Nothing else has the pattern.
- Tests added in `library/sysl/threads/tests.sysl` (prefix `sjm_`, 10 tests): string; struct holding
  a string; Buf[u8] grown by the caller; Option[string] Some/None; six strings joined in reverse
  order; &T with Drop dropped exactly once; unjoined &T answers dropped once each at scope end;
  string handle joined by a sibling; should_trap second join of a string; should_trap a panicking
  closure.
- Docs: `docs/content/library/threads.md` (~line 850, prose only, no new block) and
  `docs/content/api/sysl-threads.md` (ScopedThread paragraph mirrored from the docstring by hand).

## Verified
- The shortest input prints `worker-7` (and an int answer `42`) with installed sysl 0.12.0 and
  `SYSL_LIB=<worktree>/library` (marshal job 8166).

## Not verified / next steps
1. Build stage 1 (killed mid-build when parked):
   `cd compiler`; `caffeinate -dimsu marshal run --kind build --mem 30G --deadline 2h --dir <worktree>/compiler --label fix-scoped-join -- env -u SYSL_LIB /opt/homebrew/bin/sysl build . -o <scratch>/stage1`
2. Library tests: `SYSL_LIB=<worktree>/library <stage1> test ../library --std --filter sjm_`
   (check each `sjm_` ok line), then the whole threads file / `scope_` tests.
   The tests have never compiled yet: check `static var ...: Atomic[int]`, the Drop impls in a
   `@tests` file, `should_trap` on a test with no `-> unit`.
3. Compiler tests naming scope/spawn/join (`--file` each): tests_abl_atomic_bool, tests_bdd_bounded,
   tests_cad_capture_address, tests_cca_closure_captured_address, tests_cdm_closure_domain,
   tests_cfp_capture_field_path, tests_cqt_closure_try, tests_cvc_captured_val_copy,
   tests_cvw_capture_view_write, tests_cwl_capture_write_local, tests_fno_fanout,
   tests_fsw_fiber_switch, tests_hbl_heap_buffer_literal, tests_jnd_joined, tests_kps_keeps,
   tests_kso_keeps_sound, tests_lsw_lent_struct_views, tests_mdp_memory_doc_pins, tests_rcp_run_cache,
   tests_rkd_receiver_kind, tests_sbe_scope_borrow_escape, tests_sbt_scope_borrows_thread,
   tests_sca_scoped_capture_address, tests_sce_scoped_emission, tests_scn_scoped, tests_sev_scope_env,
   tests_shs_shareable_strings, tests_sin_scope_in, tests_sjn_scoped_joined_names,
   tests_snd_send_share, tests_swr_sync_write_readonly, tests_thk_thread_hooks, tests_thx_threads,
   tests_tsp_thread_scope; plus guarded: tests_mgr_mutex_guard, tests_mgd_mutex_guarded,
   tests_gdr_guarded_records, tests_fgb_fresh_guarded_buf.
4. Docs sweep: `SYSL_DOCS=1 SYSL_DOCS_ONLY=library/threads.md` with `--file tests_docs.sysl`.
5. Add the `scripts/fix_tests.tsv` row; squash the WIP commits into one clean commit (no trailer),
   delete PARKED.md; push; READY report.

Release-notes line (draft): "A scoped thread's `join` answers any value its closure answers — a
`string`, a `Buf`, a `&T`, a struct or `Option` holding one — moving a non-`Share` answer out (a
second join of such a handle stops the program); 0.12.0 refused these inside `scope.sysl`."
