table 0.1.2 — use sysl.term instead of its own three constants

`sysl-lang/table` (working tree `~/dev/table-sysl`) declares `BOLD`, `UNDERLINE` and `RESET` as
private constants in `sh/sysl/table/table.sysl`. It was written the same day as `sysl.term` and
before it, so the duplication is an accident of order rather than a decision.

The change is three lines: import `bold`, `underline` and `reset` from `sysl.term`, delete the
constants, and bump to 0.1.2. The 42 tests assert the exact bytes a caller gets, including the
escapes, so a wrong import fails loudly rather than quietly changing what the library emits — which
is the whole reason this is safe to do without thinking hard about it.

**The one real consequence: it raises the package's floor to a sysl 0.0.18 toolchain**, since
`sysl.term` does not exist before that. Nothing in the package's manifest expresses a minimum
compiler version, so the failure for somebody on 0.0.17 would be an unresolved import rather than a
refusal that explains itself. That is the thing to decide, not the edit.

Do this the next time the package is touched for another reason, rather than as a release of its own.
`set_ansi(false)` stays either way — see 0001, which is what would remove it.
