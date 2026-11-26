# SysL for Visual Studio Code

Syntax highlighting and language-server support for [sysl](https://sysl.sh) in Visual Studio Code.
Files ending in `.sysl` get highlighting, bracket and comment handling, and, through `sysl lsp`,
the compiler's own diagnostics as you type, hover and go-to-definition.

*An independent sysl extension for Visual Studio Code. It is not affiliated with or endorsed by Microsoft or the Visual Studio Code project.*

## Requirements

`sysl` 0.3.6 or later on your `PATH` (`brew install sysl-lang/tap/sysl`). The extension runs
`sysl lsp --stdio`.

## Install

Download `sysl-<version>.vsix` from the [releases page](https://github.com/sysl-lang/vscode-extension/releases),
then:

```
code --install-extension sysl-0.1.1.vsix
```

or use *Extensions: Install from VSIX...* in the command palette. The extension is not published to
the Marketplace.

## Settings

| setting | default | meaning |
|---|---|---|
| `sysl.server.path` | `sysl` | the sysl executable; run as `<path> lsp --stdio` |
| `sysl.lib` | empty | the standard library root, passed to the server as `SYSL_LIB`; an installed sysl needs none |
| `sysl.trace.server` | `off` | `messages` or `verbose` traces the protocol in the *SysL* output channel |

If the executable cannot be started, the extension says so and names `sysl.server.path`. Changing
`sysl.server.path` or `sysl.lib` restarts the server, and so does *SysL: Restart Language Server*.

## Not in this release

Tasks, a test explorer, debugging and Marketplace publishing. The language features are whatever
`sysl lsp` implements: diagnostics, hover and definition.

## Grammar

`syntaxes/sysl.tmLanguage.json` is a byte-identical copy of the compiler's
`docs/grammars/sysl.tmLanguage.json` at sysl commit
[`faf3b48a`](https://github.com/sysl-lang/sysl/commit/faf3b48a1a50cd8a3a184c8d6db00b876d2c37b0) —
the grammar the documentation site renders with, and which a sysl release ships as
`share/sysl/editors/sysl.tmLanguage.json`. A change to it is made there and copied here. It is
tested against the language's lexical rules in
[`sysl-lang/highlighter`](https://github.com/sysl-lang/highlighter) (`sysl_grammar_tests.sysl`):
nested block comments, text blocks and their holes, number suffixes, `:=`, the contextual words.

## Development

```
npm install
npm run typecheck
npm test
npm run build
npm run package
```

`npm test` runs without a window: it starts `sysl lsp --stdio` through the same command-line code
the extension uses, and tokenizes samples with `vscode-textmate`. Set `SYSL_SERVER_PATH` to test a
different `sysl`.

## License

ISC, see `LICENSE`.
