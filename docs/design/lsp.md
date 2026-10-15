# `sysl lsp` — design

The Language Server Protocol, served by the compiler itself over standard input and output. Every
semantic answer comes from the analysis `sysl build` makes; nothing re-implements the language.
Status: stage 2 — the slice, the VS Code client (`editors/vscode`), diagnostics for every file an
analysis touches, each binding's name recorded where it is written, and the standard module kept
across analyses. The questions at the end are answered (Q2–Q5 by the user; Q1 is the move to
`sysl.encoding.json`, a release after it ships).

## What exists in the compiler, and what was missing

| need | what was there | what the slice added |
|---|---|---|
| analyze one file or package | `indexed_file(path)` (`definition_index.sysl`): the compilation `emit-typed` reads, analyzed **without stopping at the first error**, returning every diagnostic (`Placed`) and every resolved reference | nothing — used as is |
| text nobody has saved | none: `collect` read the disk | `hold_editor_text` / `release_editor_text` (`project.sysl`): module storage consulted by `collect` and the tree walk before `read_bytes` |
| diagnostics with ranges | `Placed { src, diag.span }` — byte spans | conversion to LSP positions (`lsp_position`) |
| syntax errors with ranges | a parse failure surfaces as `Failure.Reported(text)` — the span is lost | the server parses the document itself first and publishes `Parsed.report` spans |
| name at a position | `locate.sysl` (`locate`, `innermost`): the constructs covering a line/column — written as the seed of this server | not needed yet: hover/definition read the reference index directly |
| resolve a name | `DefinitionIndex`: every local, parameter, module `val`/`var`, `extern` variable and call, recorded where the analyzer resolves it | `Reference` now carries both byte spans and a `detail` (a local's type, a function's signature, storage's type) captured at resolution |
| JSON | **none in the std** (`sysl.encoding` has base64/hex/binary/uuid; `hocon.sysl` has no `\u` escapes and no numbers) | `lsp_json.sysl`, a compiler-private reader/writer — see Q1 |
| stdio framing | `sysl.io.stdin()`, `Reader`, `stdout()`, `flush()` | `lsp_read` / `lsp_frame` |

## Architecture

**One process, one thread, one document table.** The server is a loop: read one framed message, answer
it, write the answers, until `exit`. Each `didOpen`/`didChange` (full sync) holds the text as the
file's editor text, re-runs the whole analysis, keeps that document's references, and publishes its
diagnostics. Hover and definition read the kept references: they never analyze.

**What is analyzed** is the compilation the file belongs to: the nearest directory above it with a
`package.hocon`, or the file alone. Diagnostics are published for every file the analysis found a
mistake in — the document first, then each sibling (or library file) under its URI, the one the
editor opened it by where it is open (Q4). The server remembers which files of each compilation it
last published a non-empty list for, and sends an empty list when that compilation's next whole
analysis finds a file clean. A syntax error stops before the other files are read, so their lists
stand.

**Positions.** The compiler keeps byte offsets; the protocol counts lines from zero and characters in
UTF-16 code units (the default and the one encoding every client speaks; the server announces
`positionEncoding: "utf-16"`). `lsp_position` / `lsp_offset` convert both ways, counting a 4-byte
UTF-8 sequence as two units. A library file is named `library/sysl/…` inside the compilation and is
mapped back to the real path under `std_root()` for a definition's URI.

**Cost, measured** (stage 1 compiler, M-series Mac, hosted target): a whole re-analysis of a
1,002-line file — std parsed and measured from source, the whole program analyzed — is **~0.4 s**,
the same as for a 3-line file: the fixed cost (the std) dominates and the file's own cost is noise.
`sysl emit-typed` on the same files agrees (0.42 s vs 0.44 s). A scripted session of three
analyses plus eight queries runs in 0.85 s. The `lsv` suite prints the number on every run.

**Caching plan, in order of payoff**:
1. *Keep the parsed and measured std* — **done**: `std_prepared` (`std.sysl`) keeps
   `std_usable_units` per library root and target once `keep_std_units(true)` is set, which only
   `sysl lsp` does; a build prepares it afresh as before. While an editor holds unsaved text under
   the library's root the module is prepared afresh and not kept. `lsv` prints both times.
2. *Debounce* `didChange` (analyze 150–250 ms after the last keystroke) once the loop reads
   messages on a second thread; until then the client's own queue does it.
3. *Keep each package's parsed units* keyed by the file's bytes; only the edited file is re-parsed.
4. Incremental *analysis* is not planned: the analyzer's tables are whole-program and the measured
   cost does not ask for it.

**Threading.** Single-threaded now. When analysis gets long enough to matter (big packages), a reader
thread queues messages, the analysis drops superseded `didChange`s, and `$/cancelRequest` is honoured.
The compiler's module storage (search paths, editor texts, pipeline levers) is per process, which is
one more reason to keep exactly one analysis running at a time.

## Features, in order

1. **Slice (done):** framing; `initialize`/`initialized`/`shutdown`/`exit`; `didOpen`/`didChange`
   (full)/`didClose`; `publishDiagnostics`; `hover` (type of a local, signature of a function, type of
   module storage); `definition` (locals, parameters, module functions, `val`/`var`, into the
   library).
2. **Next:** hover and definition on a *declaration* itself and on types, fields and trait members
   (the index does not record these yet — the reference compiler's index did not either); doc comments
   in hover (`doc_comments.sysl` already keeps them). Diagnostics for the other files of a package:
   done.
3. `documentSymbol` (outline) from the parse alone; `references` (the index inverted);
   `selectionRange` from `locate`.
4. `completion` (scope + members of the receiver's type) — needs an analyzer entry point that answers
   "what is in scope here" and "members of this type"; the largest piece of new compiler surface.
5. `rename`, `signatureHelp`, `formatting` (once a formatter exists in `sysl-lang/parsing`), semantic
   tokens.

## Editor plugins are thin clients

- **VS Code first — built, `editors/vscode`:** `extension.js` (plain JavaScript, so there is no build
  step) runs `sysl lsp --stdio` through `vscode-languageclient`; the TextMate grammar is the site's
  (`docs/grammars/sysl.tmLanguage.json`), copied in by `scripts/grammar.js` before packaging so the
  two never drift; settings `sysl.path`, `sysl.lib` (→ `SYSL_LIB`), `sysl.trace.server`; a
  restart command. No language logic in the extension. `npm test` runs `test/session.js`: a real
  `sysl lsp --stdio` process driven through `vscode-jsonrpc`, the client's own JSON-RPC layer —
  initialize, didOpen, diagnostics for the document and an unopened sibling, hover, definition into
  the sibling, the sibling mended and cleared, shutdown, exit 0. The `lsv` suite runs the same
  binary through a pipe when `SYSL_STAGE2` names one.
- **Zed second:** an extension declaring the language server command and using the tree-sitter grammar
  (`sysl-lang/tree-sitter`) for highlighting, indentation and folding — Zed's own model.
- tree-sitter serves *syntax* (colour, outline before analysis, folding); every *semantic* answer comes
  from the server.

## Messages out of the ordinary, and what they are answered with

| message | answer |
|---|---|
| not JSON | `-32700`, id `null`, the reader's reason with its byte offset |
| JSON with no `method` that is not a response | `-32600 a request names a method` |
| a request before `initialize` | `-32002 '<m>' before 'initialize'` |
| a request after `shutdown` | `-32600 '<m>' after 'shutdown'` |
| an unknown request | `-32601 '<m>' is not a method sysl lsp answers` |
| an unknown notification | ignored, as the protocol says |
| broken framing (no/garbled `Content-Length`, input cut short) | the server stops with status 1 and says why on stderr — there is no resynchronising |
| `exit` | status 0 after `shutdown`, 1 without |

## Known limits of the slice

- A **binding records its name's own span** (Q3) for a parameter (a function's or a nested
  function's), a pattern's name, a `val`/`var`/`ref` and a `for`/quantifier variable
  (`written_name`, `name_after_keyword` in `definition_index.sysl`, read only while an index is
  being filled). A closure parameter, a `val a, b = …` name and a binding statement that starts with
  an attribute keep the statement as their place, which definition searches for the name as a word.
- A **new unsaved file** in a package is part of the tree walk (`with_unsaved`, `project.sysl`), so
  its siblings see it as they will once it is saved.
- A **literate** file's columns are the tangled program's; the server does not yet add
  `literate_shift` back.
- Hover on a reference only; no doc comments yet.

## Questions, and what the user decided

Decided: Q1 — `sysl.encoding.json`, the compiler moving onto it a release after it ships; Q2 — in the
binary; Q3 — yes; Q4 — every file the analysis touched; Q5 — after the client works end to end
(done). The questions as they were put:

**Q1. JSON in the std.** There is no JSON reader/writer in the library. Recommendation: add
`sysl.encoding.json` (value tree, reader with byte-offset errors, compact writer — `lsp_json.sysl`
is already that, 380 lines, tested) in the next alpha, then move the compiler onto it the release
after and delete the private copy. Until then the server keeps its own.

**Q2. Where does the server live?** In the compiler binary (`sysl lsp`, as built) or a separate
`sysl-lsp` program depending on the compiler as a package? Recommendation: in the binary — one
install, the server is exactly the compiler the user builds with, and every analysis improvement
reaches the editor for free.

**Q3. Record the name's own span at a binding** (`Local.declared` → the name, not the declaration)?
Recommendation: yes, in the next alpha; it also sharpens "unused variable" style diagnostics later.

**Q4. Which diagnostics to publish.** Only the open document's (as now), or every file of the package
the analysis touched (VS Code shows them in Problems)? Recommendation: every file of the package, as
`rust-analyzer` does with `cargo check` results, clearing a file's list when it goes clean.

**Q5. Std caching before or after the plugins?** Recommendation: after the VS Code client works end
to end — 0.4 s per change is usable, and the cache is a self-contained change behind the same API.
