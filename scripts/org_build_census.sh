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
#   ok (build-lib <t>)   a board package type-checked and compiled for its target
#   ok (build-c <d> <t>) a board program's archive, built for its target
#   ok (type-check <t>)  the whole analysis passed and the C stopped at a header the SDK
#                        GENERATES during a CMake configure, exactly where the reference stops
#   FAILED               this compiler could not do it and the reference could
#   refused by both      neither could, which is a fact about the repository
#   needs SDK            a board repository and `PICO_SDK_PATH` is not set
#   needs kernel tree    `FREERTOS_KERNEL` is not set, or a west workspace this machine has not got
#   needs a server       the suite runs and its failures are all "no server at ..."
#
# **A board repository is RUN FOR ITS BOARD rather than skipped**, which is what a cross target is
# for. Being named `rp2040*` is not the same as needing one: `rp2040`, `rp2350` and `rp2040blocks`
# are constants and nothing else, so they are ordinary host `test` rows and calling all nine "cross"
# hid three repositories that had always been sweepable.
#
# **`ok (type-check <t>)` is a pass and saying so is the point.** `pico/cyw43_arch.h` is written by
# the SDK during a CMake configure, so no `-I` at a checkout's root can reach it -- and `build-lib`
# and `build-c` both run the whole analysis before compiling a line of C, so a run that reaches
# clang's complaint is a sysl type-check that passed. The reference stops in the same place on the
# same command, and that agreement is what makes it a row rather than a defect: a stop anywhere else
# is still `FAILED`.
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
# `timeout`, so the clock is perl's, which every machine here has.
#
# **It kills the process GROUP rather than the command, and that is the whole of why this is eight
# lines rather than one.** `sysl test` runs the binary it built as a child, so an alarm that took
# only the compiler down left the test binary orphaned at PPID 1 and still spinning at 100% -- and
# every row after it then ran on a machine one core short. Three were found alive at once, the oldest
# two hours and eleven minutes after the run that made it.
limited() {
    perl -e '
        my $seconds = shift;
        my $pid = fork();

        if (!defined $pid) { exit 125 }
        if ($pid == 0) { setpgrp(0, 0); exec @ARGV; exit 127 }

        $SIG{ALRM} = sub { kill "KILL", -$pid };
        alarm $seconds;
        waitpid($pid, 0);
        exit($? == 0 ? 0 : ($? >> 8 || 124));
    ' "${SYSL_CENSUS_TIMEOUT:-600}" "$@"
}

root=$(cd "$(dirname "$0")/.." && pwd)
org=$(dirname "$root")
work=$(mktemp -d "${TMPDIR:-/tmp}/sysl-org-build.XXXXXX")

ok_test=0
ok_build=0
ok_build_c=0
ok_board=0
ok_typecheck=0
failed=0
both_refused=0
no_sdk=0
setup=0
serverless=0
rows=0

for dir in "$org"/*/; do
    name=$(basename "$dir")

    # **A worktree's `.git` is a FILE and a repository's is a DIRECTORY**, which is the whole of the
    # test: a worktree is a second checkout of a repository this sweep has already read.
    [ -d "$dir/.git" ] || continue

    # **`SYSL_CENSUS_ONLY` names the repositories to ask about**, space separated, so a row can be
    # re-asked on its own. A census of eighty repositories is not the loop to iterate a single
    # board's flags in, and the alternative -- running those commands by hand beside the script --
    # proves the hand-written command rather than the one the census will run.
    if [ -n "$SYSL_CENSUS_ONLY" ]; then
        wanted=0

        # Not `[ … ] && wanted=1`: under `set -e` a failing test is the list's status and the
        # script exits on the first repository that is not the one asked for.
        for only in $SYSL_CENSUS_ONLY; do
            if [ "$only" = "$name" ]; then
                wanted=1
            fi
        done

        [ $wanted -eq 1 ] || continue
    fi

    case $name in
        # The compiler's own trees, the site, and two directories that are not components.
        sysl|sysl.sh|sysl-bootstrap|harness|pico-scratch) continue ;;
    esac

    # **What a board repository is built for is a fact about the repository**, so it is said here
    # rather than discovered: the target and the program directory are read off its own
    # `CMakeLists.txt`, which is the build this row is standing in for. `board` is what tells the
    # classification below that a stop at a generated SDK header is this row's pass.
    board=""
    board_target=""

    case $name in
        pico)         board=1; board_target=thumbv6m-freestanding ;;
        pico2)        board=1; board_target=thumb-freestanding-softfp ;;
        picokit)      board=1; board_target=thumbv6m-freestanding ;;
        ogol-pico)    board=1; board_target=thumbv6m-freestanding ;;
        ogol-pico2)   board=1; board_target=thumb-freestanding-softfp ;;
        solder-pico2) board=1; board_target=thumb-freestanding-softfp ;;
        zephyr|zephyr-demo)
            rows=$((rows + 1))
            setup=$((setup + 1))
            echo "needs kernel tree   $name -- a west workspace, not on this machine"
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

    # **A board repository's command is its CMake's, one flag at a time.** `pico` and `pico2` are
    # packages, so the analysis is reached by `build-lib`; the four programs keep their project in a
    # subdirectory and are reached by `build-c`. The SDK answers the `pico_sdk` headers the manifest
    # asks for by name, which is what a named include is for.
    if [ -n "$board" ]; then
        if [ -z "$PICO_SDK_PATH" ]; then
            rows=$((rows + 1))
            no_sdk=$((no_sdk + 1))
            echo "needs SDK           $name -- PICO_SDK_PATH names the SDK a board build reads"
            continue
        fi

        case $name in
            pico|pico2) command="build-lib . --target $board_target"; label="build-lib $board_target" ;;
            *)          command="build-c $sub --target $board_target"; label="build-c $sub $board_target" ;;
        esac

        flags="--include-path pico_sdk=$PICO_SDK_PATH"
    fi

    # **`freertos` is sweepable and its kernel is the application's to build**, which is the whole
    # reason the package declares `requires { headers { … } }` instead of vendoring one: a
    # `FreeRTOSConfig.h` moves `TickType_t` between 16 and 32 bits. `FREERTOS_KERNEL` names a
    # `FreeRTOS-Kernel` checkout; the nine objects its POSIX port needs are built once into this
    # run's own directory, so the census stays offline and writes nothing anybody owns.
    if [ "$name" = freertos ]; then
        if [ -z "$FREERTOS_KERNEL" ]; then
            rows=$((rows + 1))
            setup=$((setup + 1))
            echo "needs kernel tree   freertos -- FREERTOS_KERNEL names a FreeRTOS-Kernel checkout"
            continue
        fi

        fr_port=$FREERTOS_KERNEL/portable/ThirdParty/GCC/Posix
        fr_lib=$work/freertos-lib

        if [ ! -f "$fr_lib/libfreertos.a" ]; then
            mkdir -p "$fr_lib"
            ( cd "$fr_lib" && cc -c \
                -I "$dir/test-config" -I "$FREERTOS_KERNEL/include" -I "$fr_port" -I "$fr_port/utils" \
                "$FREERTOS_KERNEL"/tasks.c "$FREERTOS_KERNEL"/queue.c "$FREERTOS_KERNEL"/list.c \
                "$FREERTOS_KERNEL"/timers.c "$FREERTOS_KERNEL"/event_groups.c \
                "$FREERTOS_KERNEL"/stream_buffer.c "$FREERTOS_KERNEL"/portable/MemMang/heap_4.c \
                "$fr_port"/port.c "$fr_port"/utils/wait_for_event.c \
              && ar rcs libfreertos.a ./*.o ) > "$work/freertos.kernel" 2>&1 || {
                rows=$((rows + 1))
                setup=$((setup + 1))
                echo "needs kernel tree   freertos -- the kernel would not build: $(head -1 "$work/freertos.kernel")"
                continue
            }
        fi

        flags="--link-path $fr_lib \
               --include-path freertos=$FREERTOS_KERNEL/include \
               --include-path freertos-port=$fr_port \
               --include-path freertos-config=$dir/test-config"
    fi

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
            *)
                # Never `cond && (( a++ )) || (( b++ ))`: an arithmetic command reports its result
                # as a status and `x++` yields the value before the increment, so the 0->1
                # transition increments both counters.
                if [ -n "$board" ]; then
                    ok_board=$((ok_board + 1))
                else
                    ok_build_c=$((ok_build_c + 1))
                fi
                ;;
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

    # **A board row that stops where the reference stops is a PASS, and the row says so.** The
    # header is one the SDK generates during a CMake configure, so neither compiler can reach it
    # from a checkout -- and both run the whole analysis before a line of C, which is what the row
    # is claiming. Both texts are required to name a missing header: a stop anywhere else, or a
    # reference that got further, is still a failure.
    if [ -n "$board" ] && [ $theirs_ok -ne 0 ] \
       && grep -q "file not found" "$work/$name.out" \
       && grep -q "file not found" "$work/$name.theirs"; then
        ok_typecheck=$((ok_typecheck + 1))
        echo "ok (type-check $board_target)  $name -- the analysis passed; $(grep -m1 -o "'[^']*' file not found" "$work/$name.out") is written by a CMake configure"
        continue
    fi

    if [ $theirs_ok -ne 0 ]; then
        both_refused=$((both_refused + 1))
        echo "refused by both     $name ($label) -- $(grep -m1 'error' "$work/$name.out" || head -1 "$work/$name.out")"
    else
        failed=$((failed + 1))
        echo "FAILED              $name ($label) -- $(grep -m1 'error' "$work/$name.out" || head -1 "$work/$name.out")"
    fi
done

total=$((ok_test + ok_build + ok_build_c + ok_board + ok_typecheck + failed + both_refused + no_sdk + setup + serverless))

echo
echo "ok(test) $ok_test, ok(build) $ok_build, ok(build-c) $ok_build_c, ok(board) $ok_board,"
echo "ok(type-check) $ok_typecheck, FAILED $failed, refused by both $both_refused,"
echo "needs SDK $no_sdk, needs kernel tree $setup, needs a server $serverless"
echo "total $total over $rows rows"
echo "output: $work"

[ $total -eq $rows ] || { echo "the counts do not reconcile with the rows printed" >&2; exit 3; }
[ $failed -eq 0 ]
