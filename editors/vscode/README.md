# sysl for Visual Studio Code

Highlighting for `.sysl` files, and diagnostics, hover and go-to-definition answered by the sysl
compiler itself (`sysl lsp`): every answer is the one `sysl build` would give.

## Settings

| setting | default | |
|---|---|---|
| `sysl.path` | `sysl` | the compiler, found on the `PATH` or given as an absolute path |
| `sysl.lib` | empty | the standard library root, passed as `SYSL_LIB`; an installed sysl needs none |
| `sysl.trace.server` | `off` | log the protocol to the `sysl` output channel |

`sysl: Restart Language Server` restarts it; changing either path restarts it too.

## Developing

```
npm install
npm run grammar
SYSL=/path/to/sysl SYSL_LIB=/path/to/library npm test
```

`npm run grammar` copies the documentation's grammar (`docs/grammars/sysl.tmLanguage.json`) into
`syntaxes/`. `npm test` drives a real `sysl lsp --stdio` through a whole session. `npx @vscode/vsce
package` makes the `.vsix`.
