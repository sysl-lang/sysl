// How the extension starts the sysl language server. Kept free of the `vscode` module so the
// tests can start the very same process the extension does.

export interface ServerCommand {
    command: string
    args: string[]
    env: NodeJS.ProcessEnv
}

export const DEFAULT_SERVER_PATH = "sysl"

// The settings that decide the server's process; changing either restarts it.
export const SERVER_SETTINGS = ["sysl.server.path", "sysl.lib"]

// The command line that runs the language server on stdio, and its environment: `lib`, when set,
// is passed as SYSL_LIB; otherwise the environment is left as it is, which is what an installed
// sysl wants.
export function serverCommand(configured: string | undefined, lib?: string, base: NodeJS.ProcessEnv = process.env): ServerCommand {
    const command = configured !== undefined && configured.trim() !== "" ? configured.trim() : DEFAULT_SERVER_PATH
    const env = { ...base }
    if (lib !== undefined && lib.trim() !== "") env.SYSL_LIB = lib.trim()
    return { command, args: ["lsp", "--stdio"], env }
}

// The message shown when the executable cannot be started.
export function missingServerMessage(command: string): string {
    return `Could not start the sysl language server: '${command}' was not found. ` +
        `Install sysl 0.3.6 or later, or set "sysl.server.path" to its location.`
}
