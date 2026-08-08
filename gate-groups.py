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
import math
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

CHUNKS = 14


def suites(root):
    """Every concrete ScalaTest suite under `root`, as fully qualified name -> source."""
    found = {}

    for dirpath, _, files in os.walk(root):
        for f in files:
            if not f.endswith('.scala'):
                continue

            src = open(os.path.join(dirpath, f), errors='replace').read()
            pkg = re.search(r'^package\s+([\w.]+)', src, re.M)

            if not pkg:
                continue

            for m in re.finditer(r'^class\s+(\w+)\s+extends\s+([^\n{]+)', src, re.M):
                if STYLE.search(m.group(2)):
                    found[f'{pkg.group(1)}.{m.group(1)}'] = src

    return found


def main():
    if len(sys.argv) != 3:
        sys.exit('usage: gate-groups.py <test-source-root> <output-dir>')

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

    size = math.ceil(len(light) / CHUNKS)
    chunks = [light[i:i + size] for i in range(0, len(light), size)]

    os.makedirs(out, exist_ok=True)
    json.dump(heavy, open(os.path.join(out, 'heavy.json'), 'w'), indent=1)
    json.dump(chunks, open(os.path.join(out, 'chunks.json'), 'w'), indent=1)

    print(f'  {len(found)} suites: {len(heavy)} run alone, {len(light)} in {len(chunks)} chunks')


if __name__ == '__main__':
    main()
