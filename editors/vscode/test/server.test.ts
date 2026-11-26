import { test } from "node:test"
import assert from "node:assert/strict"
import { DEFAULT_SERVER_PATH, missingServerMessage, serverCommand } from "../src/server.ts"

test("the server command defaults to sysl lsp --stdio", () => {
    assert.deepEqual(serverCommand(undefined, undefined, {}), { command: DEFAULT_SERVER_PATH, args: ["lsp", "--stdio"], env: {} })
    assert.deepEqual(serverCommand("  ", undefined, {}), { command: "sysl", args: ["lsp", "--stdio"], env: {} })
})

test("a configured path is used", () => {
    assert.equal(serverCommand("/opt/x/sysl").command, "/opt/x/sysl")
})

test("sysl.lib is passed as SYSL_LIB, and an empty one leaves the environment alone", () => {
    assert.equal(serverCommand(undefined, "/opt/x/library", {}).env.SYSL_LIB, "/opt/x/library")
    assert.equal(serverCommand(undefined, "", { SYSL_LIB: "/kept" }).env.SYSL_LIB, "/kept")
    assert.equal(serverCommand(undefined, undefined, {}).env.SYSL_LIB, undefined)
    assert.equal(serverCommand(undefined, undefined, { HOME: "/h" }).env.HOME, "/h")
})

test("the missing-binary message names the binary and the setting", () => {
    const m = missingServerMessage("nosuch")
    assert.match(m, /'nosuch'/)
    assert.match(m, /sysl\.server\.path/)
})
