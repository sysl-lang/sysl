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
# **Each compiler is run against its OWN library.** This compiler takes `SYSL_LIB`, defaulting to this
# repository's `library/`; the reference takes `SYSL_ORACLE_LIB` where it is set and otherwise runs
# with `SYSL_LIB` removed from its environment, so it finds the library installed beside it. The
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

root=$(cd "$(dirname "$0")/.." && pwd)

# Made absolute, since every repository is read from a copy somewhere else.
SYSL_LIB=$(cd "${SYSL_LIB:-$root/library}" && pwd)
export SYSL_LIB

# What `env` is handed in front of the reference: its own library named, or ours taken away.
if [ -n "$SYSL_ORACLE_LIB" ]; then
    theirs_env="SYSL_LIB=$SYSL_ORACLE_LIB"
else
    theirs_env="-u SYSL_LIB"
fi

case $ours_bin in
    /*) ;;
    *) ours_bin=$(cd "$(dirname "$ours_bin")" && pwd)/$(basename "$ours_bin") ;;
esac

org=$(dirname "$root")
work=$(mktemp -d "${TMPDIR:-/tmp}/sysl-org-census.XXXXXX")

# **This compiler reports a failed bounds check before trapping and the reference traps silently**, so
# every `emit-llvm` text holding a check differs by exactly what `bounds_fail.sysl` adds. That is taken
# out of OUR text, and only that -- the four rules of `tests_oracle.sysl`'s
# `ours_without_the_bounds_report`, each removing one construct matched by a prefix only it writes:
# (1) the `call void @sysl_bounds_fail(` a failure block gains, (2) the `@.loc<n>` location
# constants, (3) the weak default's `@sysl.bounds.` constants and its three `define`s through their
# closing brace, (4) the `write` declaration where the reference declares none -- plus the blank line
# a removed run leaves doubled. The `declare` lines are then compared as a set, the `write`
# declaration landing where the bounds default asked for it (`same_text_but_declare_order`).
without_bounds_report() {
    theirs_writes=0
    grep -q '^declare .* @write(i32, ptr, ' "$2" && theirs_writes=1
    awk -v tw=$theirs_writes '
        {
            line = $0
            drop = 0
            if (inside) {
                drop = 1
                if (line == "}") inside = 0
            } else if (index(line, "  call void @sysl_bounds_fail(") == 1) {
                drop = 1
            } else if (line ~ /^@\.loc[0-9]+ = private constant \[/) {
                drop = 1
            } else if (index(line, "@sysl.bounds.") == 1) {
                drop = 1
            } else if (index(line, "define weak void @sysl_bounds_fail(") == 1 ||
                       index(line, "define private ptr @sysl.bounds.put(") == 1 ||
                       index(line, "define private ptr @sysl.bounds.num(") == 1) {
                drop = 1
                inside = 1
            } else if (!tw && line ~ /^declare / && index(line, " @write(i32, ptr, ") > 0) {
                drop = 1
            } else if (line == "" && dropped && kept > 0 && last == "") {
                drop = 1
            }
            if (!drop) {
                print line
                last = line
                kept++
            }
            dropped = drop && line != ""
        }' "$1"
}

# Two `emit-llvm` texts that are one text but for what the bounds report adds and the order of their
# `declare` lines.
same_llvm_text() {
    without_bounds_report "$2" "$1" > "$2.normal"
    grep -v '^declare ' "$1" > "$1.body" || true
    grep -v '^declare ' "$2.normal" > "$2.body" || true
    grep '^declare ' "$1" | sort > "$1.declares" || true
    grep '^declare ' "$2.normal" | sort > "$2.declares" || true
    cmp -s "$1.body" "$2.body" && cmp -s "$1.declares" "$2.declares"
}

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

        ( cd "$repo" && env $theirs_env "$theirs_bin" $command . ) > "$theirs" 2> "$theirs.err" || theirs_ok=1
        ( cd "$repo" && "$ours_bin" $command . ) > "$ours" 2> "$ours.err" || ours_ok=1


        if [ $theirs_ok -ne 0 ] && [ $ours_ok -ne 0 ]; then
            both_refused=$((both_refused + 1))
            echo "refused by both  $label"
        elif [ $theirs_ok -ne 0 ] || [ $ours_ok -ne 0 ]; then
            one_refused=$((one_refused + 1))

            # A source diagnostic is written to standard output and a refusal about the command line or
            # the project (`sysl: error: …`) to standard error, so the sentence is read from both.
            if [ $ours_ok -ne 0 ]; then
                echo "REFUSED BY US    $label -- $(cat "$ours" "$ours.err" | head -1)"
            else
                echo "REFUSED BY THEM  $label -- $(cat "$theirs" "$theirs.err" | head -1)"
            fi
        elif cmp -s "$theirs" "$ours"; then
            matched=$((matched + 1))
            echo "matched          $label"
        elif [ "$command" = "emit-llvm" ] && same_llvm_text "$theirs" "$ours"; then
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
