import * as vscode from "vscode"
import { LanguageClient, type LanguageClientOptions, type ServerOptions, TransportKind } from "vscode-languageclient/node"
import { missingServerMessage, serverCommand, SERVER_SETTINGS } from "./server"

let client: LanguageClient | undefined

async function start(): Promise<void> {
    const config = vscode.workspace.getConfiguration("sysl")
    const { command, args, env } = serverCommand(config.get<string>("server.path"), config.get<string>("lib"))

    const serverOptions: ServerOptions = {
        command,
        args,
        transport: TransportKind.stdio,
        options: { env },
    }
    const clientOptions: LanguageClientOptions = {
        documentSelector: [{ scheme: "file", language: "sysl" }],
        outputChannelName: "SysL",
    }

    const starting = new LanguageClient("sysl", "SysL", serverOptions, clientOptions)
    try {
        await starting.start()
        client = starting
    } catch (err) {
        void vscode.window.showErrorMessage(`${missingServerMessage(command)} (${String(err)})`)
    }
}

async function stop(): Promise<void> {
    const running = client
    client = undefined
    await running?.stop()
}

async function restart(): Promise<void> {
    await stop()
    await start()
}

export async function activate(context: vscode.ExtensionContext): Promise<void> {
    context.subscriptions.push(
        vscode.commands.registerCommand("sysl.restartServer", restart),
        vscode.workspace.onDidChangeConfiguration(async e => {
            if (SERVER_SETTINGS.some(s => e.affectsConfiguration(s))) await restart()
        }),
        { dispose: () => void stop() },
    )
    await start()
}

export async function deactivate(): Promise<void> {
    await stop()
}
