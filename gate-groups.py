#!/usr/bin/env python3
"""Splits the test suites into the groups `run-gate.sh` runs, one `sbt` per group.

Why the suite is run in groups at all is in `run-gate.sh`'s header. This file answers what follows
from it: which suites need a group to themselves, and how the rest are divided.

**WHICH SUITES ARE HEAVY CANNOT BE DERIVED FROM THE SOURCE, and it was worth trying before
accepting that.** The property that matters is building or booting for every registered target --
parsing and compiling the standard library once per target -- and nothing in the text of a suite
says so. Iterating the registry is not the tell in either direction: `AbiAgainstClangTests` walks
`Target.all` in the same `for` as `CrossTargetBuildTests` and is perfectly cheap, because it only
emits IR and never reaches the toolchain; while `QemuRunTests` and `NoAllocEmissionTests` are heavy
and never mention `Target.all`, naming their two boards outright. Eight suites iterate the registry
and four are heavy, with no textual difference to separate them.

So `HEAVY` below is a **measured** list, and the guard against it going stale is not this file --
it is the watchdog in `run-gate.sh`. A suite that becomes heavy and is not listed here lands in a
chunk, wedges it, and is killed and named in the summary as the last suite that group announced.
That is the failure being designed for rather than prevented, which is the honest arrangement when
the classification cannot be computed.

What this file does do is print the suites that iterate the registry and are not classified either
way, as candidates worth measuring -- advisory only, and deliberately not acted on.

Emits `heavy.json` and `chunks.json` into the output directory.
"""

import json
import os
import re
import sys

# Measured on an 18-core, 64 GB machine, 2026-08-08: each of these wedges a chunk that four agents
# share at 12g, and passes alone at 24g.
HEAVY = {
    'sh.sysl.CrossTargetBuildTests',
    'sh.sysl.QemuRunTests',
    'sh.sysl.QemuHarnessTests',
    'sh.sysl.NoAllocEmissionTests',
}

# Measured and found cheap despite walking the registry. Listed so that "it iterates and is not
# heavy" is a recorded finding rather than an omission.
LIGHT_SWEEPERS = {
    'sh.sysl.AbiAgainstClangTests',
}

ITERATES = re.compile(r'for\s+\w+\s*<-\s*Target\.all')
STYLE = re.compile(r'Any(FreeSpec|FunSuite|WordSpec|FlatSpec|FunSpec|PropSpec|FeatureSpec)')

# Measured 2026-08-21, cutting 0.0.66. Both numbers moved together and the reason is one fact:
# **the chunks were contiguous slices of an alphabetically sorted list, and in this tree a shared
# prefix means a shared kind.** So one chunk held all nine `Codegen*` suites and five `*RunTests`
# beside them -- every program-compiling suite in the run, in one group of 23, sharing four agents
# at 12g. It wedged, was killed at the watchdog, and the gate read RED with zero test failures.
#
# Dealing round-robin instead of slicing is what fixes it, and raising the count is not: a bigger
# CHUNKS still puts `Codegen*` next to `Codegen*`, so the next family of twenty similarly-named
# suites clusters exactly as this one did.
#
# **Round-robin alone was measured and was not enough**, which is the second half of the finding: at
# 18 groups of 17-18 the run cleared five chunks and then wedged again on a group whose suites had
# nothing in common, so what remained was not lopsidedness but the pile itself. Growth is monotonic
# per agent within one `sbt`, so the lever that works is fewer suites per invocation and raising the
# heap cap only postpones it. 36 groups is ~9 suites each, ~2 per agent.
#
# The pile got heavier this release for a reason worth recording: `sysl.container` added five
# modules to `library/sysl`, and every program a suite compiles links the standard library. Nothing
# here could notice that, which is why the number is measured rather than derived.
CHUNKS = 36


def suites(root):
    """Every concrete ScalaTest suite under `root`, as fully qualified name -> source.

    A suite is one whose extends-clause names a ScalaTest style **or a local base that does** --
    and the second half is not a nicety. `ResolveTests` and `PackageBuildTests` both extend
    `PackageCacheSupport`, a trait in this tree that extends `AnyFreeSpec` itself, and a pattern
    matching only the styles missed both of them: the whole package manager, end to end, was outside
    the gate and nothing said so. Found 2026-08-23 when a change to it made a suite fail that the
    gate had just called green.

    So the local bases are collected first and the match is closed over them, repeatedly, until it
    stops growing -- a support trait may extend another one, and one level of following would be the
    same bug one layer down.
    """
    sources = {}

    for dirpath, _, files in os.walk(root):
        for f in files:
            if not f.endswith('.scala'):
                continue

            src = open(os.path.join(dirpath, f), errors='replace').read()
            pkg = re.search(r'^package\s+([\w.]+)', src, re.M)

            if pkg:
                sources[os.path.join(dirpath, f)] = (pkg.group(1), src)

    # Every `trait`/`abstract class`/`class` declared here whose own extends-clause is suite-shaped,
    # by name, so that a subclass of one is recognised as a suite too.
    bases = set()
    # `[^{]*?` rather than `[^\n]*?`, so a declaration whose `extends` is on the next line is seen —
    # `ArgumentTests` and `ExternVarTests` are written that way, and the class matcher below spans the
    # newline because `\s+` does. A base collected by a stricter pattern than the thing it feeds would
    # be the same bug one shape along.
    decl = re.compile(r'^(?:abstract\s+)?(?:class|trait)\s+(\w+)[^{]*?\bextends\s+([^\n{]+)', re.M)

    while True:
        before = len(bases)

        for _, src in sources.values():
            for m in decl.finditer(src):
                if STYLE.search(m.group(2)) or any(re.search(rf'\b{b}\b', m.group(2)) for b in bases):
                    bases.add(m.group(1))

        if len(bases) == before:
            break

    found = {}

    for pkg, src in sources.values():
        for m in re.finditer(r'^class\s+(\w+)\s+extends\s+([^\n{]+)', src, re.M):
            if STYLE.search(m.group(2)) or any(re.search(rf'\b{b}\b', m.group(2)) for b in bases):
                found[f'{pkg}.{m.group(1)}'] = src

    return found


def self_test():
    """The matcher, against the shape that defeated it.

    **A suite extending a base declared in this tree was invisible until 2026-08-23**, and sixteen of
    them were — the whole package manager among them. This asserts the two shapes that matter: a
    subclass of a local support trait is found, and a subclass of a base that is *not* suite-shaped is
    not. It runs before every gate because it costs nothing and the bug it pins reads as a smaller
    number in a line nobody checks.
    """
    import tempfile

    tree = tempfile.mkdtemp(prefix='gate-selftest-')
    write = lambda name, text: open(os.path.join(tree, name), 'w').write(text)

    write('Support.scala', 'package sh.probe\n\ntrait CacheSupport extends AnyFreeSpec with Matchers {\n}\n')
    write('Wrapped.scala', 'package sh.probe\n\ntrait Deeper extends CacheSupport {\n}\n')
    write('Plain.scala', 'package sh.probe\n\nclass PlainTests extends AnyFreeSpec with Matchers {\n}\n')
    write('Local.scala', 'package sh.probe\n\nclass LocalTests extends CacheSupport {\n}\n')
    write('Nested.scala', 'package sh.probe\n\nclass NestedTests extends Deeper {\n}\n')
    write('Helper.scala', 'package sh.probe\n\ntrait NotASuite extends AnyRef {\n}\n\nclass NotATest extends NotASuite {\n}\n')

    # A base whose `extends` is on the NEXT line, which is how `ArgumentTests` and `ExternVarTests`
    # are written — a base collected by a stricter pattern than the class matcher would be the
    # same bug one shape along.
    write('Wrapped2.scala', 'package sh.probe\n\ntrait Broken\n    extends AnyFreeSpec\n    with Matchers {\n}\n')
    write('Late.scala', 'package sh.probe\n\nclass LateTests extends Broken {\n}\n')

    got = set(suites(tree))
    want = {'sh.probe.PlainTests', 'sh.probe.LocalTests', 'sh.probe.NestedTests', 'sh.probe.LateTests'}

    if got != want:
        sys.exit(f'gate-groups self-test failed: found {sorted(got)}, wanted {sorted(want)}')

    print('  self-test: a suite extending a local base is found, and a non-suite is not')


def main():
    if len(sys.argv) == 2 and sys.argv[1] == '--self-test':
        return self_test()

    if len(sys.argv) != 3:
        sys.exit('usage: gate-groups.py <test-source-root> <output-dir>')

    self_test()

    root, out = sys.argv[1], sys.argv[2]
    found = suites(root)

    heavy = sorted(HEAVY & found.keys())
    light = sorted(found.keys() - set(heavy))

    for name in sorted(HEAVY - found.keys()):
        print(f'  WARNING: {name} is recorded as heavy but no longer exists -- prune it.')

    unclassified = {n for n, src in found.items() if ITERATES.search(src)} - HEAVY - LIGHT_SWEEPERS

    for name in sorted(unclassified):
        print(f'  NOTE: {name} iterates the target registry and has not been measured.')

    if unclassified:
        print('        Not acted on: iterating is not what makes a suite heavy. If a chunk times')
        print('        out, the summary names the suite, and it belongs in HEAVY.')

    # Round-robin rather than contiguous slices, so that suites sharing a prefix -- which here means
    # sharing a kind, and therefore a cost -- are spread across the groups instead of piled into one.
    chunks = [light[i::CHUNKS] for i in range(min(CHUNKS, len(light)))]

    os.makedirs(out, exist_ok=True)
    json.dump(heavy, open(os.path.join(out, 'heavy.json'), 'w'), indent=1)
    json.dump(chunks, open(os.path.join(out, 'chunks.json'), 'w'), indent=1)

    print(f'  {len(found)} suites: {len(heavy)} run alone, {len(light)} in {len(chunks)} chunks')


if __name__ == '__main__':
    main()
