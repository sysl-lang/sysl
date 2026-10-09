#!/bin/sh
# The source root the REFERENCE compiler is handed (`--lib`) to compile this compiler's tree: a copy of
# every file `scripts/library_patches.tsv` lists that the reference's own library has not got -- a
# module `library/` ADDS (today `sysl.testing`, which the tree's tests import) rather than one it
# changes. The reference compiles against its own library for everything else, which is the only
# library it can compile: `library/`'s changed modules use forms only this compiler has (`sysl.buf`'s
# slot forms). `tests_oracle_support.sysl`'s `reference_overlay` is the same rule.
#
#   scripts/reference_overlay.sh [<reference library>] [<out dir>]
#
# The reference library defaults to `SYSL_ORACLE_LIB`, else `<prefix>/share/sysl/library` beside the
# `sysl` on the PATH; the directory defaults to `${TMPDIR:-/tmp}/sysl-reference-overlay`, made afresh.
# It prints the directory, so the gate is
#
#   cd compiler
#   env -u SYSL_LIB sysl test . --lib "$(sh ../scripts/reference_overlay.sh)"
#
# `scripts/release.sh` hands its stage-1 seed the same overlay, so the seed is any earlier release.

set -e

root=$(cd "$(dirname "$0")/.." && pwd)

lib=${1:-$SYSL_ORACLE_LIB}

if [ -z "$lib" ]; then
    lib=$(cd "$(dirname "$(command -v sysl)")/../share/sysl/library" && pwd)
fi

out=${2:-${TMPDIR:-/tmp}/sysl-reference-overlay}
out=${out%/}

rm -rf "$out"
mkdir -p "$out"

grep -v '^#' "$root/scripts/library_patches.tsv" | cut -f1 | while read -r file; do
    [ -n "$file" ] || continue
    [ -e "$lib/$file" ] && continue
    [ -e "$root/library/$file" ] || continue

    # A new FILE is not a new MODULE: a file beside sources the reference's library already has in
    # that directory adds to a module the reference has, and the reference refuses a `--lib` root
    # declaring a standard module. It is left out, as `tests_oracle_support.sysl`'s `holds_sources` leaves it.
    # A platform directory (`sysl/fs/__posix__/errtext.c`) is its parent module's, so the parent is
    # the one asked.
    dir=$(dirname "$file")

    case "$dir" in
        */__*__) dir=$(dirname "$dir") ;;
    esac

    if [ "$dir" != "." ] && [ -n "$(ls "$lib/$dir" 2>/dev/null | grep -E '\.l?sysl$')" ]; then
        continue
    fi

    mkdir -p "$out/$(dirname "$file")"
    cp "$root/library/$file" "$out/$file"
done

echo "$out"
