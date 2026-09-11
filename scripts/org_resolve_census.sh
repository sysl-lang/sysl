#!/bin/sh
# The org census: every repository beside this one whose manifest names dependencies, resolved and
# read by both compilers, and their answers diffed.
#
# **What it proves is the dependency layer and nothing else.** A repository whose manifest has a
# `dependencies` block is one whose resolution, fetching, version selection and import tables are
# all exercised by simply asking a compiler to read it -- so a matched row is the two compilers
# agreeing about what a project's packages are and what its files mean by them. Running each
# repository's own suite is a different question and is not asked here.
#
# Two texts per repository, because they answer different halves: `emit-typed --no-spans` is what a
# module *means*, where a dependency's modules keep the names they were written with; `emit-llvm` is
# what it *links against*, where each fetched package sits under the canonical prefix its coordinate
# gives it.
#
#   scripts/org_resolve_census.sh <this compiler's binary> [<reference binary>]
#
# `SYSL_LIB` must name the standard module's source, as it must for every whole-program sweep. The
# rows are printed as they are decided and the counts come last:
#
#   matched            both compilers answered, and the two texts are identical
#   mismatched         both answered and the texts differ -- a defect, named by repository
#   refused by both    neither could read it, which is a fact about the repository
#   refused by one     one answered and the other did not -- a defect, named by which side refused

set -e

ours_bin=$1
theirs_bin=${2:-sysl}

if [ -z "$ours_bin" ]; then
    echo "usage: scripts/org_resolve_census.sh <binary> [<reference>]" >&2
    exit 2
fi

if [ -z "$SYSL_LIB" ]; then
    echo "SYSL_LIB must name the standard module's source" >&2
    exit 2
fi

case $ours_bin in
    /*) ;;
    *) ours_bin=$(cd "$(dirname "$ours_bin")" && pwd)/$(basename "$ours_bin") ;;
esac

root=$(cd "$(dirname "$0")/.." && pwd)
org=$(dirname "$root")
work=$(mktemp -d "${TMPDIR:-/tmp}/sysl-org-census.XXXXXX")

matched=0
mismatched=0
both_refused=0
one_refused=0

for repo in "$org"/*/; do
    name=$(basename "$repo")

    [ -f "$repo/package.hocon" ] || continue
    grep -q '^dependencies' "$repo/package.hocon" || continue

    # This compiler's own tree and its worktrees are the own-tree census's subject, not this one's.
    if grep -qE '^ *name *= *"sysl"' "$repo/package.hocon"; then
        continue
    fi

    # **Read out of a copy, so that nothing here writes into a repository it does not own.** Both
    # compilers record what they fetched in `sysl.sum`, which is the right thing for a build and the
    # wrong thing for a census: the org's own files stay exactly as they are.
    here=$work/$name
    rm -rf "$here"
    mkdir -p "$here"
    ( cd "$repo" && tar cf - --exclude .git . ) | ( cd "$here" && tar xf - )
    repo=$here

    for command in "emit-typed --no-spans" "emit-llvm"; do
        label="$name ${command%% *}"
        theirs="$work/$name.$(echo "$command" | cut -d' ' -f1).theirs"
        ours="$work/$name.$(echo "$command" | cut -d' ' -f1).ours"

        theirs_ok=0
        ours_ok=0

        ( cd "$repo" && "$theirs_bin" $command . ) > "$theirs" 2> "$theirs.err" || theirs_ok=1
        ( cd "$repo" && "$ours_bin" $command . ) > "$ours" 2> "$ours.err" || ours_ok=1


        if [ $theirs_ok -ne 0 ] && [ $ours_ok -ne 0 ]; then
            both_refused=$((both_refused + 1))
            echo "refused by both  $label"
        elif [ $theirs_ok -ne 0 ] || [ $ours_ok -ne 0 ]; then
            one_refused=$((one_refused + 1))

            # **A refusal is written to standard output, not to standard error**, so that is where
            # the sentence is read from -- both compilers print `error: …` on the stream the text
            # would have gone to and exit non-zero.
            if [ $ours_ok -ne 0 ]; then
                echo "REFUSED BY US    $label -- $(head -1 "$ours")"
            else
                echo "REFUSED BY THEM  $label -- $(head -1 "$theirs")"
            fi
        elif cmp -s "$theirs" "$ours"; then
            matched=$((matched + 1))
            echo "matched          $label"
        else
            mismatched=$((mismatched + 1))
            echo "MISMATCHED       $label -- $theirs vs $ours"
        fi
    done
done

echo
echo "matched $matched, mismatched $mismatched, refused by both $both_refused, refused by one $one_refused"
echo "texts: $work"

[ $mismatched -eq 0 ] && [ $one_refused -eq 0 ]
