#!/usr/bin/env bash
#
# Compiles and runs a sysl example through the JVM build — the fast development loop.
# Defaults to examples/hello.sysl; pass a path to run a different one.
#
#   ./run-example.sh
#   ./run-example.sh examples/hello.sysl
#
# Anything after a `--` goes to the example itself rather than to sysl, which is what a program with a
# `main(args: []string)` reads:
#
#   ./run-example.sh examples/args.sysl -- -n one two
#
# Needs `sbt` and a `clang` on the PATH (sysl emits textual LLVM IR and links it with clang).

set -euo pipefail

cd "$(dirname "$0")"

example="${1:-examples/hello.sysl}"
shift || true

if [[ ! -f "$example" ]]; then
    echo "no such example: $example" >&2
    exit 1
fi

exec sbt -batch "syslJVM/run run $example $*"
