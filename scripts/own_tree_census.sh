#!/bin/sh
# The own-tree census, by hand: the two compilers' `emit-llvm .` over THIS compiler's own sources,
# left on disk for whoever wants to diff a body.
#
# **The proof itself is `tests_self.sysl`'s `the_two_compilers_agree_on_this_compilers_own_tree`,
# which runs in the gate and counts.** What this adds is the two texts, which a census cannot hand
# back and which is what a divergence is actually read out of.
#
# It works on a COPY of the tree with the manifest's `sysl` floor relaxed, because the floor names
# the oldest bootstrap that may build these sources and this compiler's own version is below it --
# the floor is a fact about who may build the tree, not about what the text should say.
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

cp -R "$root/sh" "$root/sysl.sum" "$work/"

awk '/^  sysl = /{ print "  sysl = \"0.0.1\""; next } { print }' \
    "$root/package.hocon" > "$work/package.hocon"

( cd "$work" && "$theirs_bin" emit-llvm . ) > "$work/theirs.ll"
( cd "$work" && "$ours_bin"   emit-llvm . ) > "$work/ours.ll"

echo "the reference: $work/theirs.ll"
echo "this compiler: $work/ours.ll"
