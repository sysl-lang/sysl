'use strict';

// An end-to-end session with `sysl lsp --stdio`, spoken through vscode-jsonrpc -- the JSON-RPC
// layer vscode-languageclient itself uses -- so what is checked is what the editor would see:
// initialize, didOpen, the published diagnostics (for the opened file and for a sibling in its
// package), hover, definition, shutdown and exit.
//
//   SYSL=<sysl binary> SYSL_LIB=<library root> npm test
//
// SYSL defaults to `sysl` on the PATH; SYSL_LIB is passed through as the extension passes its
// `sysl.lib` setting.

const assert = require('assert');
const cp = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { pathToFileURL } = require('url');
const rpc = require('vscode-jsonrpc/node');

const sysl = process.env.SYSL || 'sysl';

async function main() {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'sysl-vscode-'));
  const main = path.join(root, 'main.sysl');
  const helper = path.join(root, 'helper.sysl');

  fs.writeFileSync(path.join(root, 'package.hocon'), 'package { name = "t" }\n');
  fs.writeFileSync(helper, 'twice(n: int) -> int = n * 2\n\noops() = missing()\n');

  const text = 'val limit: int = 10\n\nmain()\n    val x = twice(limit)\n    print(x, nope)\n';
  const uri = pathToFileURL(main).href;
  const helperUri = pathToFileURL(helper).href;

  const child = cp.spawn(sysl, ['lsp', '--stdio'], { stdio: ['pipe', 'pipe', 'inherit'] });
  const exited = new Promise((resolve) => child.on('exit', (code) => resolve(code)));
  const conn = rpc.createMessageConnection(
    new rpc.StreamMessageReader(child.stdout),
    new rpc.StreamMessageWriter(child.stdin),
  );

  const published = new Map();
  let notify;
  conn.onNotification('textDocument/publishDiagnostics', (p) => {
    published.set(p.uri, p.diagnostics);
    if (notify) notify();
  });
  conn.listen();

  const until = (ok) =>
    new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('no diagnostics within 30 s')), 30000);
      notify = () => {
        if (ok()) {
          clearTimeout(timer);
          resolve();
        }
      };
      notify();
    });

  const init = await conn.sendRequest('initialize', {
    processId: process.pid,
    rootUri: pathToFileURL(root).href,
    capabilities: {},
  });

  assert.strictEqual(init.capabilities.positionEncoding, 'utf-16');
  assert.strictEqual(init.capabilities.textDocumentSync, 1);
  assert.strictEqual(init.capabilities.hoverProvider, true);
  assert.strictEqual(init.capabilities.definitionProvider, true);
  assert.strictEqual(init.serverInfo.name, 'sysl');

  await conn.sendNotification('initialized', {});
  await conn.sendNotification('textDocument/didOpen', {
    textDocument: { uri, languageId: 'sysl', version: 1, text },
  });
  await until(() => published.has(uri) && published.has(helperUri));

  const mine = published.get(uri);

  assert.strictEqual(mine.length, 1, JSON.stringify(mine));
  assert.strictEqual(mine[0].message, "undefined name 'nope'");
  assert.deepStrictEqual(mine[0].range, { start: { line: 4, character: 13 }, end: { line: 4, character: 17 } });
  assert.strictEqual(mine[0].severity, 1);

  // A mistake in a file of the same package that the editor has not opened is published too.
  const theirs = published.get(helperUri);

  assert.strictEqual(theirs.length, 1, JSON.stringify(theirs));
  assert.strictEqual(theirs[0].message, "undefined function 'missing'");
  assert.deepStrictEqual(theirs[0].range.start, { line: 2, character: 9 });

  const hover = await conn.sendRequest('textDocument/hover', {
    textDocument: { uri },
    position: { line: 3, character: 12 },
  });

  assert.strictEqual(hover.contents.value, '```sysl\ntwice(n: int) -> int\n```');

  const local = await conn.sendRequest('textDocument/hover', {
    textDocument: { uri },
    position: { line: 4, character: 10 },
  });

  assert.strictEqual(local.contents.value, '```sysl\nx: int\n```');

  const there = await conn.sendRequest('textDocument/definition', {
    textDocument: { uri },
    position: { line: 3, character: 12 },
  });

  assert.strictEqual(there.uri, helperUri);
  assert.deepStrictEqual(there.range, { start: { line: 0, character: 0 }, end: { line: 0, character: 5 } });

  const binding = await conn.sendRequest('textDocument/definition', {
    textDocument: { uri },
    position: { line: 4, character: 10 },
  });

  assert.strictEqual(binding.uri, uri);
  assert.deepStrictEqual(binding.range, { start: { line: 3, character: 8 }, end: { line: 3, character: 9 } });

  // Fixing the helper in the editor clears its list.
  await conn.sendNotification('textDocument/didOpen', {
    textDocument: { uri: helperUri, languageId: 'sysl', version: 1, text: 'twice(n: int) -> int = n * 2\n' },
  });
  await until(() => published.get(helperUri).length === 0);

  await conn.sendRequest('shutdown');
  await conn.sendNotification('exit');

  assert.strictEqual(await exited, 0);

  conn.dispose();
  fs.rmSync(root, { recursive: true, force: true });
  console.log('session: initialize, didOpen, diagnostics (two files), hover, definition, shutdown, exit -- ok');
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
