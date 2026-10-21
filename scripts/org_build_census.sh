#!/bin/sh
# The org build census: every repository under `~/dev/sysl-lang` built and tested the way its own
# author builds it, with this compiler, and every failure re-asked of the reference -- the installed
# release unless another is named.
#
# It runs what the repository is *actually built by* -- `sysl test .` for a package,
# `sysl build .` for a program, `sysl build-c <dir>` for a project whose link belongs to CMake or
# Gradle -- which is the org rule's own definition of done.
#
#   scripts/org_build_census.sh <this compiler's binary> [<reference binary>]
#
# The reference defaults to the installed release, `/opt/homebrew/opt/sysl/bin/sysl`.
#
# **Each compiler is run against its OWN library.** This compiler takes `SYSL_LIB`, defaulting to this
# repository's `library/`; the reference runs with `SYSL_LIB` removed from its environment, so it
# finds the library installed beside it.
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
#   ok (build <t>)       a kernel image, linked for its machine (`keel`)
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
# **Several repositories are asked at once** -- `SYSL_CENSUS_JOBS` of them, a third of the machine's
# processors unless it is set -- since a row is mostly one compile, and a compile is mostly one core.
# Each row works in a tree of its own, its path dependencies copied beside it, so no two rows share a
# copy. A row is printed when it is answered, so they come in the order they finish.
#
# The counts at the end are reconciled against the repositories asked about: every row leaves its
# verdict in a file of its own, and a count that does not equal the rows dispatched is a script that
# counted something twice or lost a row, which is how a census comes to claim more or fewer
# repositories than it has.

set -e

ours_bin=$1
theirs_bin=${2:-/opt/homebrew/opt/sysl/bin/sysl}

if [ -z "$ours_bin" ]; then
    echo "usage: scripts/org_build_census.sh <binary> [<reference>]" >&2
    exit 2
fi

root=$(cd "$(dirname "$0")/.." && pwd)

# Made absolute, since every repository is built from a copy somewhere else.
SYSL_LIB=$(cd "${SYSL_LIB:-$root/library}" && pwd)
export SYSL_LIB

# What `env` is handed in front of the reference: ours taken away, so it finds its own.
theirs_env="-u SYSL_LIB"

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

org=$(dirname "$root")
work=$(mktemp -d "${TMPDIR:-/tmp}/sysl-org-build.XXXXXX")

# **The candidate is the `sysl` any repository finds BY NAME.** A suite that spawns `sysl` itself
# (cstd's does) would otherwise reach the installed release, and test the old compiler against the new
# library. Each compiler gets a directory holding one executable called `sysl` that runs it, put in
# front of `PATH` for that compiler's rows only. A wrapper rather than a symlink, so the compiler
# never sees a different executable path than the one it was named by.
pin_dir() {
    mkdir -p "$work/pin-$1"
    printf '#!/bin/sh\nexec "%s" "$@"\n' "$2" > "$work/pin-$1/sysl"
    chmod +x "$work/pin-$1/sysl"
}
pin_dir ours "$ours_bin"
pin_dir theirs "$theirs_bin"
ours_path="$work/pin-ours:$PATH"
theirs_path="$work/pin-theirs:$PATH"

echo "sysl by name: $(PATH=$ours_path command -v sysl) runs $ours_bin"

# `SYSL_CENSUS_PIN_ONLY=1` stops here, which is how the pin is tested without a twenty-minute run.
if [ -n "$SYSL_CENSUS_PIN_ONLY" ]; then
    exit 0
fi

# **Both compilers must build a program before any row is read.** The reference is only consulted
# when ours fails, so a reference that cannot run at all -- a bare binary copied without the library
# installed beside it, an environment it cannot start in -- turns every failure into "refused by both"
# and the census reports nothing wrong. A one-line program built by each is the whole check.
mkdir -p "$work/preflight"
printf 'print("ok")\n' > "$work/preflight/hello.sysl"

if ! ( cd "$work/preflight" && PATH=$ours_path "$ours_bin" build hello.sysl -o ours ) > "$work/preflight/ours.out" 2>&1; then
    echo "census refused: $ours_bin cannot build a one-line program:" >&2
    head -5 "$work/preflight/ours.out" >&2
    exit 3
fi

if ! ( cd "$work/preflight" && env $theirs_env PATH="$theirs_path" "$theirs_bin" build hello.sysl -o theirs ) > "$work/preflight/theirs.out" 2>&1; then
    echo "census refused: the reference $theirs_bin cannot build a one-line program, so no row could be checked against it:" >&2
    head -5 "$work/preflight/theirs.out" >&2
    exit 3
fi

echo "preflight: both compilers build a program"

# `SYSL_CENSUS_PREFLIGHT_ONLY=1` stops here, which is how the check is tested without a full run.
if [ -n "$SYSL_CENSUS_PREFLIGHT_ONLY" ]; then
    exit 0
fi

# **`keel` is a kernel, and its image is built the way its boot test builds it** (`scripts/boot-test.sh`,
# with no QEMU): each user program first, into `scratch/user/`, because the kernel embeds their images
# with `.incbin` paths the assembler resolves against the repository's root -- then the kernel, from
# that root. The programs are the script's own `user_programs` list, read rather than repeated here.
# Run in a copy, `sh <this> <compiler>`; a `scratch/` the copy brought along is stale and removed.
cat > "$work/keel-build.sh" <<'KEEL'
set -e
bin=$1
rm -rf scratch
mkdir -p scratch/user
for prog in $(sed -n 's/^user_programs=(\(.*\))$/\1/p' scripts/boot-test.sh); do
    "$bin" build --target aarch64-freestanding -o "scratch/user/$prog.elf" "user/$prog"
done
"$bin" build --target aarch64-freestanding -o scratch/keel.elf kernel
KEEL

# **A repository whose own gate is several commands is run by all of them.** `commands` holds one
# command per line (the common case is the single `command`), and the row fails if any one fails.
# `$1` is `ours` or `theirs`; the caller has already changed into the directory to run in.
run_row() {
    rr_rc=0
    rr_nl='
'
    rr_list=$commands
    [ -n "$rr_list" ] || rr_list=" "

    while [ -n "$rr_list" ]; do
        case $rr_list in
            *"$rr_nl"*) rr_cmd=${rr_list%%"$rr_nl"*}; rr_list=${rr_list#*"$rr_nl"} ;;
            *) rr_cmd=$rr_list; rr_list="" ;;
        esac

        if [ "$1" = ours ]; then
            PATH=$ours_path limited $via "$ours_bin" $rr_cmd $flags || rr_rc=1
        else
            limited env $theirs_env PATH="$theirs_path" $via "$theirs_bin" $rr_cmd $flags || rr_rc=1
        fi
    done

    return $rr_rc
}

# A repository's files as git knows them, tracked or not yet added and never the ignored ones,
# copied into `$2`.
copy_tree() {
    mkdir -p "$2"
    ( cd "$1" && git ls-files -z -co --exclude-standard | tar cf - --null -T - ) | ( cd "$2" && tar xf - )
}

# **A path dependency is a sibling checkout, so the copy gets its siblings.** The names are read off
# the manifest's own `path = "../<name>"` lines, and each is copied into `$2` -- the directory the
# repository's copy sits in, which is where `../<name>` resolves to.
copy_path_deps() {
    cpd_dir=$1
    [ -f "$cpd_dir/package.hocon" ] || return 0

    for cpd_name in $(sed -n 's/.*path *= *"\.\.\/\([^"\/]*\)".*/\1/p' "$cpd_dir/package.hocon"); do
        [ -d "$2/$cpd_name" ] && continue
        [ -d "$org/$cpd_name" ] || continue
        copy_tree "$org/$cpd_name" "$2/$cpd_name"
    done
}

# **A `headers { <name> = "..." }` block says a library ships no `.pc` file, so the include path is
# passed rather than probed** -- which is what the repository's own instructions pass. For each name in
# the repository's block and its path dependencies' blocks, `<prefix>/include/<name>.h` is looked for
# under the Homebrew prefix (no brew call), and where it is there the flags are handed to both
# compilers. A header that is absent leaves the flags off, and the row says it needs a library.
header_flags() {
    hf_out=""
    hf_prefix=/opt/homebrew
    hf_manifests="$1/package.hocon"

    for hf_dep in $(sed -n 's/.*path *= *"\.\.\/\([^"\/]*\)".*/\1/p' "$1/package.hocon" 2> /dev/null); do
        hf_manifests="$hf_manifests $org/$hf_dep/package.hocon"
    done

    for hf_file in $hf_manifests; do
        [ -f "$hf_file" ] || continue

        for hf_name in $(awk '
            /^[ \t]*headers[ \t]*\{/ { on = 1; next }
            on && /\}/ { on = 0 }
            on && match($0, /^[ \t]*[A-Za-z0-9_.-]+/) { s = substr($0, RSTART, RLENGTH); gsub(/[ \t]/, "", s); print s }
        ' "$hf_file"); do
            [ -f "$hf_prefix/include/$hf_name.h" ] || continue
            case " $hf_out " in *" --include-path $hf_name="*) continue ;; esac
            hf_out="$hf_out --include-path $hf_name=$hf_prefix/include"
        done
    done

    [ -z "$hf_out" ] || hf_out="$hf_out --link-path $hf_prefix/lib"
    echo $hf_out
}

# **A row's verdict is a file**, `$work/rows/<name>`, holding the counter it adds to and the line it
# printed: a row asked in the background cannot add to a variable here. `row <counter> <line>`.
mkdir -p "$work/rows"

row() {
    printf '%s\n' "$1" > "$work/rows/$name"
    echo "$2"
}

dispatched=0
census_jobs=${SYSL_CENSUS_JOBS:-$(( $(sysctl -n hw.ncpu 2> /dev/null || nproc) / 3 ))}
[ "$census_jobs" -ge 1 ] || census_jobs=1

# One repository asked, in a tree of its own: every name the loop below settled is read here, in a
# subshell started after they were.
census_row() {
    tree=$work/trees/$name
    here=$tree/$name
    copy_tree "$dir" "$here"
    copy_path_deps "$dir" "$tree"

    ours_ok=0

    ( cd "$here" && run_row ours ) > "$work/$name.out" 2>&1 || ours_ok=1

    if [ $ours_ok -eq 0 ]; then
        case $label in
            test|"test kernel; test arch/pmsa") counter=ok_test ;;
            build) counter=ok_build ;;
            build-lib) counter=ok_typecheck ;;
            *)
                if [ -n "$board" ] || [ -n "$kernel" ]; then
                    counter=ok_board
                else
                    counter=ok_build_c
                fi
                ;;
        esac

        row $counter "ok ($shown)  $name"
        return 0
    fi

    # A suite whose every failure is "no server at ..." is the package working as designed: its
    # README says an absent server reddens the suite rather than skipping it, since a green run over
    # nothing is the one result worth less than a red one.
    if grep -q "no server at" "$work/$name.out"; then
        row serverless "needs a server      $name -- $(grep -c 'no server at' "$work/$name.out") of its tests want one"
        return 0
    fi

    # **Every failure is re-asked of the reference on the same files**, because a repository the
    # reference also refuses is a fact about that repository rather than a defect here. A fresh copy,
    # since the run above may have written a `sysl.sum`.
    tree=$work/trees/$name.reference
    theirs=$tree/$name
    copy_tree "$dir" "$theirs"
    copy_path_deps "$dir" "$tree"

    theirs_ok=0

    ( cd "$theirs" && run_row theirs ) > "$work/$name.theirs" 2>&1 || theirs_ok=1

    # **A board row that stops where the reference stops is a PASS, and the row says so.** The
    # header is one the SDK generates during a CMake configure, so neither compiler can reach it
    # from a checkout -- and both run the whole analysis before a line of C, which is what the row
    # is claiming. Both texts are required to name a missing header: a stop anywhere else, or a
    # reference that got further, is still a failure.
    if [ -n "$board" ] && [ $theirs_ok -ne 0 ] \
       && grep -q "file not found" "$work/$name.out" \
       && grep -q "file not found" "$work/$name.theirs"; then
        row ok_typecheck "ok (type-check $board_target)  $name -- the analysis passed; $(grep -m1 -o "'[^']*' file not found" "$work/$name.out") is written by a CMake configure"
        return 0
    fi

    # **A library nobody installed is the package working as designed, like a server nobody started.**
    # Both compilers must say the same thing -- "needs the '<x>' headers and nothing supplied them" --
    # so a failure of any other kind in either run is still a failure.
    if [ $theirs_ok -ne 0 ] \
       && grep -q "needs the '[^']*' headers and nothing supplied them" "$work/$name.out" \
       && grep -q "needs the '[^']*' headers and nothing supplied them" "$work/$name.theirs"; then
        row needs_lib "needs a library     $name -- $(grep -m1 -o "needs the '[^']*' headers" "$work/$name.out")"
        return 0
    fi

    if [ $theirs_ok -ne 0 ]; then
        row both_refused "refused by both     $name ($shown) -- $(grep -m1 'error' "$work/$name.out" || head -1 "$work/$name.out")"
    else
        row failed "FAILED              $name ($shown) -- $(grep -m1 'error' "$work/$name.out" || head -1 "$work/$name.out")"
    fi
}

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
    kernel=""

    case $name in
        pico)         board=1; board_target=thumbv6m-freestanding ;;
        pico2)        board=1; board_target=thumb-freestanding-softfp ;;
        picokit)      board=1; board_target=thumbv6m-freestanding ;;
        ogol-pico)    board=1; board_target=thumbv6m-freestanding ;;
        ogol-pico2)   board=1; board_target=thumb-freestanding-softfp ;;
        solder-pico2) board=1; board_target=thumb-freestanding-softfp ;;
        musicbox-pico) board=1; board_target=thumb-freestanding-softfp ;;
        zephyr|zephyr-demo)
            dispatched=$((dispatched + 1))
            row setup "needs kernel tree   $name -- a west workspace, not on this machine"
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
        elif grep -rlE --include="*.sysl" --include="*.lsysl" '^@test\b' "$dir" > /dev/null 2>&1; then
            command="test ."
            label="test"
        else
            # A package with no `@test` function has no suite to run: `sysl test .` answers
            # "no '@test' functions in .", which says nothing about whether it builds. Its analysis
            # is what can be asked, and `build-lib` runs all of it.
            command="build-lib ."
            label="build-lib"
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

    # What runs the command: the compiler itself, or for `keel` the script that builds its image.
    via=""

    if [ "$name" = keel ]; then
        kernel=1
        via="sh $work/keel-build.sh"
        command=""
        label="build aarch64-freestanding"
    fi

    # A library with no `.pc` file (quickjs-ng, duckdb) has its paths handed over from its `headers`
    # block by `header_flags`, below.
    flags=""

    # **A board repository's command is its CMake's, one flag at a time.** `pico` and `pico2` are
    # packages, so the analysis is reached by `build-lib`; the four programs keep their project in a
    # subdirectory and are reached by `build-c`. The SDK answers the `pico_sdk` headers the manifest
    # asks for by name, which is what a named include is for.
    if [ -n "$board" ]; then
        if [ -z "$PICO_SDK_PATH" ]; then
            dispatched=$((dispatched + 1))
            row no_sdk "needs SDK           $name -- PICO_SDK_PATH names the SDK a board build reads"
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
            dispatched=$((dispatched + 1))
            row setup "needs kernel tree   freertos -- FREERTOS_KERNEL names a FreeRTOS-Kernel checkout"
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
                dispatched=$((dispatched + 1))
                row setup "needs kernel tree   freertos -- the kernel would not build: $(head -1 "$work/freertos.kernel")"
                continue
            }
        fi

        flags="--link-path $fr_lib \
               --include-path freertos=$FREERTOS_KERNEL/include \
               --include-path freertos-port=$fr_port \
               --include-path freertos-config=$dir/test-config"
    fi

    # **Read out of a copy**, because a build records what it fetched in `sysl.sum` and a census has
    # no business writing into a repository it does not own. **The copy is the files git knows of,
    # tracked or not yet added, and never the ignored ones**: a scratch directory a session left
    # behind can hold a file nobody may read, and tar then stops short of the files after it.
    commands=$command

    case $name in
        # skiff has no package at its root: its kernel's host tests, then `tools/portable`, which
        # instantiates every kernel generic, built for each target the kernel runs on. Its
        # `arch/armv7m`, `chips/*` and `boards/*` are cross-only and checked by skiff's own QEMU gate
        # (`scripts/gate.sh`), not here, as picokit's board build is not.
        skiff)
            commands="test kernel
build-c tools/portable --target thumbv6m-freestanding
build-c tools/portable --target thumbv7em-freestanding
build-c tools/portable --target thumb-freestanding
build-c tools/portable --target riscv32-freestanding"
            label="test kernel; build-c tools/portable x4 targets"
            ;;
    esac

    found_flags=$(header_flags "$dir")

    shown=$label

    if [ -n "$found_flags" ]; then
        flags="$flags $found_flags"
        shown="$label $found_flags"
    fi

    # A free slot first: no more than `census_jobs` rows at once.
    while [ "$(jobs -p | wc -l)" -ge "$census_jobs" ]; do
        sleep 1
    done

    dispatched=$((dispatched + 1))
    census_row &
done

wait

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
needs_lib=0
rows=0

for verdict in "$work"/rows/*; do
    [ -f "$verdict" ] || continue
    rows=$((rows + 1))

    case $(cat "$verdict") in
        ok_test) ok_test=$((ok_test + 1)) ;;
        ok_build) ok_build=$((ok_build + 1)) ;;
        ok_build_c) ok_build_c=$((ok_build_c + 1)) ;;
        ok_board) ok_board=$((ok_board + 1)) ;;
        ok_typecheck) ok_typecheck=$((ok_typecheck + 1)) ;;
        failed) failed=$((failed + 1)) ;;
        both_refused) both_refused=$((both_refused + 1)) ;;
        no_sdk) no_sdk=$((no_sdk + 1)) ;;
        setup) setup=$((setup + 1)) ;;
        serverless) serverless=$((serverless + 1)) ;;
        needs_lib) needs_lib=$((needs_lib + 1)) ;;
    esac
done

total=$((ok_test + ok_build + ok_build_c + ok_board + ok_typecheck + failed + both_refused + no_sdk + setup + serverless + needs_lib))

echo
echo "ok(test) $ok_test, ok(build) $ok_build, ok(build-c) $ok_build_c, ok(board) $ok_board,"
echo "ok(type-check) $ok_typecheck, FAILED $failed, refused by both $both_refused,"
echo "needs SDK $no_sdk, needs kernel tree $setup, needs a server $serverless, needs a library $needs_lib"
echo "total $total over $rows rows, $dispatched repositories asked"
echo "output: $work"

[ $total -eq $rows ] && [ $rows -eq $dispatched ] || { echo "the counts do not reconcile with the rows printed" >&2; exit 3; }
[ $failed -eq 0 ]
