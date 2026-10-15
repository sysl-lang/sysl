'use strict';

// The sysl extension is a thin client: it starts `sysl lsp --stdio` and lets
// vscode-languageclient carry the protocol. Every diagnostic, hover and definition is the
// compiler's own answer; nothing about the language is decided here.

const vscode = require('vscode');
const { LanguageClient } = require('vscode-languageclient/node');

let client;

// The server's command and environment, from the `sysl.*` settings.
function serverOptions() {
  const config = vscode.workspace.getConfiguration('sysl');
  const command = config.get('path') || 'sysl';
  const lib = config.get('lib') || '';
  const env = Object.assign({}, process.env);

  if (lib !== '') env.SYSL_LIB = lib;

  return { command, args: ['lsp', '--stdio'], options: { env } };
}

async function start() {
  const clientOptions = {
    documentSelector: [{ scheme: 'file', language: 'sysl' }],
    outputChannelName: 'sysl',
  };

  client = new LanguageClient('sysl', 'sysl', serverOptions(), clientOptions);
  await client.start();
}

async function stop() {
  if (client === undefined) return;

  const running = client;

  client = undefined;
  await running.stop();
}

async function activate(context) {
  context.subscriptions.push(
    vscode.commands.registerCommand('sysl.restartServer', async () => {
      await stop();
      await start();
    }),
    vscode.workspace.onDidChangeConfiguration(async (e) => {
      if (e.affectsConfiguration('sysl.path') || e.affectsConfiguration('sysl.lib')) {
        await stop();
        await start();
      }
    }),
  );

  try {
    await start();
  } catch (e) {
    vscode.window.showErrorMessage(
      `sysl: the language server could not be started (${e.message}). ` +
        "Set 'sysl.path' to the sysl compiler.",
    );
  }
}

function deactivate() {
  return stop();
}

module.exports = { activate, deactivate, serverOptions };
