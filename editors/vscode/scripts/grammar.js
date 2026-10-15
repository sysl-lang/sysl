'use strict';

// The TextMate grammar is the site's (`docs/grammars/sysl.tmLanguage.json`), so the editor and the
// documentation colour sysl the same way. A packaged extension cannot reach outside its own folder,
// so the grammar is copied in before packaging and before the tests; the copy is not committed.

const fs = require('fs');
const path = require('path');

const from = path.join(__dirname, '..', '..', '..', 'docs', 'grammars', 'sysl.tmLanguage.json');
const to = path.join(__dirname, '..', 'syntaxes', 'sysl.tmLanguage.json');

const grammar = JSON.parse(fs.readFileSync(from, 'utf8'));

if (grammar.scopeName !== 'source.sysl') {
  throw new Error(`${from}: scopeName is '${grammar.scopeName}', and package.json says 'source.sysl'`);
}

fs.mkdirSync(path.dirname(to), { recursive: true });
fs.copyFileSync(from, to);
