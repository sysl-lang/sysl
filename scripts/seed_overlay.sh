#!/bin/sh
# The source root a SEED compiler (the installed release before this one) is handed (`--lib`) to
# build this compiler's tree: a copy of every module `library/` ADDS relative to the seed's own
# library, found by diffing the two trees. The seed compiles against its own library for everything
# else, which is the only library it is known to compile -- `library/`'s changed modules may use forms
# only a newer compiler has.
#
#   scripts/seed_overlay.sh [<seed library>] [<out dir>] [<library>]
#
# The seed library defaults to `SYSL_RELEASE_SEED_LIB`, else `<prefix>/share/sysl/library` beside the
# `sysl` on the PATH; the directory defaults to `${TMPDIR:-/tmp}/sysl-seed-overlay`, made afresh; the
# library to this repository's `library/`. It prints the directory, which is empty when the seed's
# library already has every module, so
#
#   cd compiler
#   env -u SYSL_LIB sysl build . --lib "$(sh ../scripts/seed_overlay.sh)"
#
# is the seed's build where the overlay is not empty. `scripts/release.sh` builds its stage 1 so.
#
# **A module is a directory holding sources, and a new FILE is not a new MODULE.** A file the seed's
# library has is never copied (it is changed or the same, and either way the seed has the module). A
# file the seed lacks is copied only where its directory holds no source in the seed's library: a file
# beside sources the seed already has in that directory (`sysl/math/nextafter.sysl` in `sysl.math`)
# adds to a module the seed has, and a compiler refuses a `--lib` root declaring a standard module. A
# platform directory (`sysl/fs/__posix__/errtext.c`) is its parent module's, so the parent is the one
# asked; and a file whose directory holds no source in `library/` either belongs to no module and is
# left out.

set -e

root=$(cd "$(dirname "$0")/.." && pwd)

seed=${1:-$SYSL_RELEASE_SEED_LIB}

if [ -z "$seed" ]; then
    seed=$(cd "$(dirname "$(command -v sysl)")/../share/sysl/library" && pwd)
fi

out=${2:-${TMPDIR:-/tmp}/sysl-seed-overlay}
out=${out%/}

library=${3:-$root/library}
library=${library%/}

[ -d "$seed" ] || { echo "seed_overlay.sh: no seed library at '$seed'" >&2; exit 1; }
[ -d "$library" ] || { echo "seed_overlay.sh: no library at '$library'" >&2; exit 1; }

holds_sources() {
    [ -d "$1" ] && [ -n "$(ls "$1" | grep -E '\.l?sysl$')" ]
}

rm -rf "$out"
mkdir -p "$out"

(cd "$library" && find . -type f | sed 's|^\./||' | sort) | while read -r file; do
    [ -e "$seed/$file" ] && continue

    dir=$(dirname "$file")

    case "$dir" in
        __*__) dir=. ;;
        */__*__) dir=$(dirname "$dir") ;;
    esac

    [ "$dir" = "." ] && continue
    holds_sources "$library/$dir" || continue
    holds_sources "$seed/$dir" && continue

    mkdir -p "$out/$(dirname "$file")"
    cp "$library/$file" "$out/$file"
done

echo "$out"
