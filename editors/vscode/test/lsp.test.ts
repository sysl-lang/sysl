import { test, before, after } from "node:test"
import assert from "node:assert/strict"
import { spawn, type ChildProcess } from "node:child_process"
import { mkdtempSync, writeFileSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { pathToFileURL } from "node:url"
import { createMessageConnection, StreamMessageReader, StreamMessageWriter, type MessageConnection } from "vscode-jsonrpc/node"
import { serverCommand } from "../src/server.ts"

// The server is started from the same command line the extension builds.
const { command, args } = serverCommand(process.env.SYSL_SERVER_PATH)

const source = [
    "double(x: int) -> int = x * 2",
    "",
    "main()",
    "    val n: int = \"not a number\"",
    "    print(double(n))",
    "",
].join("\n")

let child: ChildProcess
let conn: MessageConnection
let uri: string
const diagnostics: Array<{ message: string, severity?: number }> = []
let published: (() => void) | undefined
const gotDiagnostics = new Promise<void>(resolve => { published = resolve })

before(() => {
    child = spawn(command, args, { stdio: ["pipe", "pipe", "inherit"] })
    conn = createMessageConnection(new StreamMessageReader(child.stdout!), new StreamMessageWriter(child.stdin!))
    conn.onNotification("textDocument/publishDiagnostics", (p: { uri: string, diagnostics: typeof diagnostics }) => {
        if (p.uri === uri && p.diagnostics.length > 0) {
            diagnostics.push(...p.diagnostics)
            published?.()
        }
    })
    conn.listen()
    const dir = mkdtempSync(join(tmpdir(), "sysl-vscode-"))
    const file = join(dir, "broken.sysl")
    writeFileSync(file, source)
    uri = pathToFileURL(file).toString()
})

after(() => {
    conn.dispose()
    child.kill()
})

test("initialize announces sync, hover and definition", async () => {
    const result = await conn.sendRequest("initialize", { processId: process.pid, rootUri: null, capabilities: {} }) as
        { capabilities: { textDocumentSync: number, hoverProvider: boolean, definitionProvider: boolean } }
    assert.equal(result.capabilities.textDocumentSync, 1)
    assert.equal(result.capabilities.hoverProvider, true)
    assert.equal(result.capabilities.definitionProvider, true)
    await conn.sendNotification("initialized", {})
})

test("didOpen of a file with a type error publishes the compiler's diagnostic", async () => {
    await conn.sendNotification("textDocument/didOpen", {
        textDocument: { uri, languageId: "sysl", version: 1, text: source },
    })
    const timer = setTimeout(() => { throw new Error("no diagnostics within 30s") }, 30000)
    await gotDiagnostics
    clearTimeout(timer)
    assert.ok(diagnostics.length > 0)
    assert.match(diagnostics[0].message, /int/)
    assert.match(diagnostics[0].message, /string/)
})

test("hover answers a sysl code block", async () => {
    const hover = await conn.sendRequest("textDocument/hover", {
        textDocument: { uri },
        position: { line: 4, character: 12 },
    }) as { contents: { kind: string, value: string } } | null
    assert.ok(hover !== null, "hover on the call 'double' returned null")
    assert.match(hover.contents.value, /^```sysl\n[\s\S]*double[\s\S]*\n```$/)
})

test("definition of a call answers where the function is declared", async () => {
    const there = await conn.sendRequest("textDocument/definition", {
        textDocument: { uri },
        position: { line: 4, character: 12 },
    }) as { uri: string, range: { start: { line: number, character: number }, end: { line: number, character: number } } } | null
    assert.ok(there !== null, "definition on the call 'double' returned null")
    assert.equal(there.uri, uri)
    assert.deepEqual(there.range, { start: { line: 0, character: 0 }, end: { line: 0, character: 6 } })
})

test("shutdown and exit end the server", async () => {
    await conn.sendRequest("shutdown")
    const exited = new Promise<number | null>(resolve => child.once("exit", code => resolve(code)))
    await conn.sendNotification("exit")
    assert.equal(await exited, 0)
})
