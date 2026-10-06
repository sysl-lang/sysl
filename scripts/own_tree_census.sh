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
# **Each compiler is run against its OWN library.** This compiler takes `SYSL_LIB`, defaulting to this
# repository's `library/`; the reference takes `SYSL_ORACLE_LIB` where it is set and otherwise runs
# with `SYSL_LIB` removed from its environment, so it finds the library installed beside it. The tree
# lowered is `compiler/`, the compiler's own project.

set -e

ours_bin=$1
theirs_bin=${2:-sysl}

if [ -z "$ours_bin" ]; then
    echo "usage: scripts/own_tree_census.sh <binary> [<reference binary>]" >&2
    exit 2
fi

root=$(cd "$(dirname "$0")/.." && pwd)
project=$root/compiler
work=$(mktemp -d "${TMPDIR:-/tmp}/sysl-census.XXXXXX")

SYSL_LIB=$(cd "${SYSL_LIB:-$root/library}" && pwd)
export SYSL_LIB

case $ours_bin in
    /*) ;;
    *) ours_bin=$(cd "$(dirname "$ours_bin")" && pwd)/$(basename "$ours_bin") ;;
esac

# The reference is also handed, as a source root, the modules `library/` adds (`reference_overlay.sh`):
# the tree imports `sysl.testing`, which no reference library carries.
theirs_lib=${SYSL_ORACLE_LIB:-$(cd "$(dirname "$(command -v "$theirs_bin")")/../share/sysl/library" && pwd)}
overlay=$(sh "$root/scripts/reference_overlay.sh" "$theirs_lib" "$work/overlay")

( cd "$project" && SYSL_LIB=$theirs_lib "$theirs_bin" emit-llvm . --lib "$overlay" ) > "$work/theirs.ll"

( cd "$project" && "$ours_bin" emit-llvm . ) > "$work/ours.ll"

echo "the reference: $work/theirs.ll"
echo "this compiler: $work/ours.ll"
