#!/bin/sh
# The own-tree census, by hand: the two compilers' `emit-llvm .` over THIS compiler's own sources,
# left on disk for whoever wants to diff a body.
#
# **The proof itself is `tests_self.sysl`'s `the_two_compilers_agree_on_this_compilers_own_tree`,
# which runs in the gate and counts.** What this adds is the two texts, which a census cannot hand
# back and which is what a divergence is actually read out of.
#
# It works on the tree IN PLACE. The manifest states no `sysl` floor, so neither compiler has a
# version the other's manifest can turn away, and what is lowered is the working tree itself.
#
#   scripts/own_tree_census.sh <this compiler's binary> [<reference binary>]
#
# `SYSL_LIB` must name the standard module's source, as it must for every whole-program sweep.

set -e

ours_bin=$1
theirs_bin=${2:-sysl}

if [ -z "$ours_bin" ]; then
    echo "usage: scripts/own_tree_census.sh <binary> [<reference binary>]" >&2
    exit 2
fi

if [ -z "$SYSL_LIB" ]; then
    echo "SYSL_LIB must name the standard module's source" >&2
    exit 2
fi

root=$(cd "$(dirname "$0")/.." && pwd)
work=$(mktemp -d "${TMPDIR:-/tmp}/sysl-census.XXXXXX")

( cd "$root" && "$theirs_bin" emit-llvm . ) > "$work/theirs.ll"
( cd "$root" && "$ours_bin"   emit-llvm . ) > "$work/ours.ll"

echo "the reference: $work/theirs.ll"
echo "this compiler: $work/ours.ll"
