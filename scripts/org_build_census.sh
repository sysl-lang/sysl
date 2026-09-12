#!/bin/sh
# The org build census: every repository under `~/dev/sysl-lang` built and tested the way its own
# author builds it, with this compiler, and every failure re-asked of the reference.
#
# **This is the other half of `org_resolve_census.sh` and asks a different question.** That one reads
# each repository with both compilers and diffs the two texts, which proves the dependency layer;
# this one runs what the repository is *actually built by* -- `sysl test .` for a package,
# `sysl build .` for a program, `sysl build-c <dir>` for a project whose link belongs to CMake or
# Gradle -- which is the org rule's own definition of done.
#
#   scripts/org_build_census.sh <this compiler's binary> [<reference binary>]
#
# `SYSL_LIB` must name the standard module's source, as it must for every whole-program sweep.
#
# **The command that answered is printed beside every row**, and that is not decoration: a script
# that tried `test` and fell back to `build` would print a bare `ok` for a repository whose *suite*
# failed and whose build passed, which is worse than not running the suite at all. So the command is
# decided from the repository's own shape before anything runs, and the row says which one it was.
#
#   ok (test)            `sysl test .` -- a package
#   ok (build)           `sysl build .` -- a program
#   ok (build-c <dir>)   an archive somebody else's build links
#   FAILED               this compiler could not do it and the reference could
#   refused by both      neither could, which is a fact about the repository
#   needs cross target   built for a board against an SDK's headers; not sweepable here
#   needs setup          needs a kernel tree or a west workspace this machine has not got
#   needs a server       the suite runs and its failures are all "no server at ..."
#
# The counts at the end are reconciled against the number of rows printed: a total that does not
# equal the rows is a script that counted something twice, which is how a census comes to claim more
# repositories than it has.

set -e

ours_bin=$1
theirs_bin=${2:-sysl}

if [ -z "$ours_bin" ]; then
    echo "usage: scripts/org_build_census.sh <binary> [<reference>]" >&2
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

# **A suite that hangs is a failure, not a census that never finishes.** A miscompiled loop condition
# is exactly the defect a census is run to find, and it presents as one repository's test binary
# spinning for ever -- which stalls every row after it and reports nothing at all. macOS ships no
# `timeout`, so the alarm is perl's, which every machine here has.
limited() {
    perl -e 'alarm shift; exec @ARGV' "${SYSL_CENSUS_TIMEOUT:-600}" "$@"
}

root=$(cd "$(dirname "$0")/.." && pwd)
org=$(dirname "$root")
work=$(mktemp -d "${TMPDIR:-/tmp}/sysl-org-build.XXXXXX")

ok_test=0
ok_build=0
ok_build_c=0
failed=0
both_refused=0
cross=0
setup=0
serverless=0
rows=0

for dir in "$org"/*/; do
    name=$(basename "$dir")

    # **A worktree's `.git` is a FILE and a repository's is a DIRECTORY**, which is the whole of the
    # test: a worktree is a second checkout of a repository this sweep has already read.
    [ -d "$dir/.git" ] || continue

    case $name in
        # The compiler's own trees, the site, and two directories that are not components.
        sysl|sysl.sh|sysl-bootstrap|harness|pico-scratch) continue ;;
    esac

    # Said before anything is run, because the answer is a fact about the repository rather than
    # something a command could discover: a board's project is compiled for `thumb*-freestanding`
    # against an SDK's headers, which its own CMake supplies.
    case $name in
        pico|pico2|rp2040|rp2040blocks|rp2350|picokit|*-pico|*-pico2)
            rows=$((rows + 1))
            cross=$((cross + 1))
            echo "needs cross target  $name"
            continue
            ;;
        freertos|zephyr|zephyr-demo)
            rows=$((rows + 1))
            setup=$((setup + 1))
            echo "needs setup         $name -- a kernel tree or a west workspace, not on this machine"
            continue
            ;;
    esac

    # What the repository is built by, decided from its shape. A root manifest is a package or a
    # program, and **an entry file is what tells them apart**: a program's beginning is a file at the
    # root with no `module` header, which is the language's own rule for where a program starts. A
    # manifest one directory down is an archive project, whose link belongs to CMake or Gradle.
    command=""
    label=""

    if [ -f "$dir/package.hocon" ]; then
        entry=""

        for file in "$dir"*.sysl "$dir"*.lsysl; do
            [ -f "$file" ] || continue
            grep -q '^module ' "$file" || entry=$file
        done

        if [ -n "$entry" ]; then
            command="build ."
            label="build"
        else
            command="test ."
            label="test"
        fi
    else
        for manifest in "$dir"*/package.hocon; do
            [ -f "$manifest" ] || continue

            sub=$(basename "$(dirname "$manifest")")
            command="build-c $sub"
            label="build-c $sub"
        done
    fi

    # A directory with no manifest anywhere is not a sysl project: the site, the tap, the org's
    # profile, a build tool written in Scala.
    [ -n "$command" ] || continue

    # The two repositories that cannot be read with the bare command and can be read with flags.
    # quickjs-ng ships no `.pc` file at all, which its own manifest says in the sentence a consumer
    # is shown, so the paths are handed over instead.
    flags=""

    case $name in
        quickjs-ng) flags="--include-path quickjs=/opt/homebrew/include --link-path /opt/homebrew/lib" ;;
    esac

    # **Read out of a copy**, because a build records what it fetched in `sysl.sum` and a census has
    # no business writing into a repository it does not own.
    here=$work/$name
    rm -rf "$here"
    mkdir -p "$here"
    ( cd "$dir" && tar cf - --exclude .git . ) | ( cd "$here" && tar xf - )

    rows=$((rows + 1))
    ours_ok=0

    ( cd "$here" && limited "$ours_bin" $command $flags ) > "$work/$name.out" 2>&1 || ours_ok=1

    if [ $ours_ok -eq 0 ]; then
        case $label in
            test) ok_test=$((ok_test + 1)) ;;
            build) ok_build=$((ok_build + 1)) ;;
            *) ok_build_c=$((ok_build_c + 1)) ;;
        esac

        echo "ok ($label)  $name"
        continue
    fi

    # A suite whose every failure is "no server at ..." is the package working as designed: its
    # README says an absent server reddens the suite rather than skipping it, since a green run over
    # nothing is the one result worth less than a red one.
    if grep -q "no server at" "$work/$name.out"; then
        serverless=$((serverless + 1))
        echo "needs a server      $name -- $(grep -c 'no server at' "$work/$name.out") of its tests want one"
        continue
    fi

    # **Every failure is re-asked of the reference on the same copy**, because a repository the
    # reference also refuses is a fact about that repository rather than a defect here. A fresh copy,
    # since the run above may have written a `sysl.sum`.
    theirs=$work/$name.reference
    rm -rf "$theirs"
    mkdir -p "$theirs"
    ( cd "$dir" && tar cf - --exclude .git . ) | ( cd "$theirs" && tar xf - )

    theirs_ok=0

    ( cd "$theirs" && limited "$theirs_bin" $command $flags ) > "$work/$name.theirs" 2>&1 || theirs_ok=1

    if [ $theirs_ok -ne 0 ]; then
        both_refused=$((both_refused + 1))
        echo "refused by both     $name ($label) -- $(grep -m1 'error' "$work/$name.out" || head -1 "$work/$name.out")"
    else
        failed=$((failed + 1))
        echo "FAILED              $name ($label) -- $(grep -m1 'error' "$work/$name.out" || head -1 "$work/$name.out")"
    fi
done

total=$((ok_test + ok_build + ok_build_c + failed + both_refused + cross + setup + serverless))

echo
echo "ok(test) $ok_test, ok(build) $ok_build, ok(build-c) $ok_build_c, FAILED $failed,"
echo "refused by both $both_refused, needs cross target $cross, needs setup $setup, needs a server $serverless"
echo "total $total over $rows rows"
echo "output: $work"

[ $total -eq $rows ] || { echo "the counts do not reconcile with the rows printed" >&2; exit 3; }
[ $failed -eq 0 ]
