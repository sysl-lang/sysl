import { test } from "node:test"
import assert from "node:assert/strict"
import { readFileSync } from "node:fs"
import { createRequire } from "node:module"
import vsctm from "vscode-textmate"
import oniguruma from "vscode-oniguruma"

const require = createRequire(import.meta.url)
const wasm = readFileSync(require.resolve("vscode-oniguruma/release/onig.wasm"))
const grammarText = readFileSync(new URL("../syntaxes/sysl.tmLanguage.json", import.meta.url), "utf8")

const onig = oniguruma.loadWASM(wasm.buffer.slice(wasm.byteOffset, wasm.byteOffset + wasm.byteLength) as ArrayBuffer).then(() => ({
    createOnigScanner: (p: string[]) => new oniguruma.OnigScanner(p),
    createOnigString: (s: string) => new oniguruma.OnigString(s),
}))

const registry = new vsctm.Registry({
    onigLib: onig,
    loadGrammar: async scope => scope === "source.sysl" ? vsctm.parseRawGrammar(grammarText, "sysl.tmLanguage.json") : null,
})

// The scopes of the token covering `needle` on the one line `line`.
async function scopesOf(line: string, needle: string): Promise<string[]> {
    const grammar = await registry.loadGrammar("source.sysl")
    assert.ok(grammar, "grammar did not load")
    const { tokens } = grammar.tokenizeLine(line, vsctm.INITIAL)
    const at = line.indexOf(needle)
    assert.ok(at >= 0, `'${needle}' is not in the line`)
    const token = tokens.find(t => t.startIndex <= at && at < t.endIndex)
    assert.ok(token, "no token covers the needle")
    return token.scopes
}

// The scopes of the token covering `needle` on line `at` of `lines`, tokenized in order so a
// construct spanning lines carries its state.
async function scopesAcross(lines: string[], at: number, needle: string): Promise<string[]> {
    const grammar = await registry.loadGrammar("source.sysl")
    assert.ok(grammar, "grammar did not load")
    let state = vsctm.INITIAL
    for (let i = 0; i < at; i++) state = grammar.tokenizeLine(lines[i], state).ruleStack
    const line = lines[at]
    const { tokens } = grammar.tokenizeLine(line, state)
    const col = line.indexOf(needle)
    assert.ok(col >= 0, `'${needle}' is not in the line`)
    const token = tokens.find(t => t.startIndex <= col && col < t.endIndex)
    assert.ok(token, "no token covers the needle")
    return token.scopes
}

test("a block comment nests: the text after an inner close is still comment", async () => {
    const scopes = await scopesOf("/* outer /* inner */ still if */ val x = 1", "still")
    assert.ok(scopes.includes("comment.block.sysl"))
    assert.ok(!scopes.includes("keyword.control.sysl"))
    const after = await scopesOf("/* outer /* inner */ still */ val x = 1", "val")
    assert.ok(!after.some(s => s.startsWith("comment.")), "the comment did not end at its own close")
})

test("a text block is a string across its lines, keywords and all", async () => {
    const lines = ["val t = \"\"\"", "    if then // not a comment", "    \"\"\"", "val y = 2"]
    const inside = await scopesAcross(lines, 1, "if")
    assert.ok(inside.includes("string.quoted.triple.sysl"))
    assert.ok(!inside.includes("keyword.control.sysl"))
    assert.ok((await scopesAcross(lines, 1, "not a comment")).includes("string.quoted.triple.sysl"))
    assert.ok(!(await scopesAcross(lines, 3, "val")).some(s => s.startsWith("string.")), "the text block did not close")
})

test(":= and its compound forms are the yielding assignment", async () => {
    assert.ok((await scopesOf("total := total + 1", ":=")).includes("keyword.operator.assignment.yield.sysl"))
    assert.ok((await scopesOf("total +:= 1", "+:=")).includes("keyword.operator.assignment.yield.sysl"))
    assert.ok(!(await scopesOf("total = 1", "=")).includes("keyword.operator.assignment.yield.sysl"))
})

test("a line comment is a comment", async () => {
    assert.ok((await scopesOf("val x = 1 // trailing note", "trailing")).includes("comment.line.double-slash.sysl"))
})

test("a string literal is a string", async () => {
    assert.ok((await scopesOf("val s = \"hello\"", "hello")).includes("string.quoted.double.sysl"))
})

test("an interpolated string is an interpolated string", async () => {
    assert.ok((await scopesOf("val s = s\"a ${x} b\"", " b")).includes("string.quoted.double.interpolated.sysl"))
})

test("a keyword is a keyword", async () => {
    assert.ok((await scopesOf("if x then y", "if")).includes("keyword.control.sysl"))
})

test("a number is a number", async () => {
    assert.ok((await scopesOf("val n = 12345", "12345")).includes("constant.numeric.integer.sysl"))
})

test("a keyword inside a string stays a string", async () => {
    const scopes = await scopesOf("val s = \"if then\"", "if")
    assert.ok(scopes.includes("string.quoted.double.sysl"))
    assert.ok(!scopes.includes("keyword.control.sysl"))
})
