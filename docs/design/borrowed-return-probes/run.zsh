#!/bin/zsh
# Runs every probe with the installed sysl: what it printed, and for a b-probe, the count operations
# and whole-struct loads in each of the program's own functions (unoptimised IR, -O0).
dir=${0:A:h}
scratch=$(mktemp -d)
for f in $dir/[bd]*.sysl; do
    print -- "=== ${f:t}"
    SYSL_COUNT_ALLOCS=1 sysl run $f 2>&1 | head -20
    [[ ${f:t} == b* ]] || continue
    sysl emit-llvm $f > $scratch/out.ll 2>&1 || continue
    awk '
        /^define/ && !/@arc\.|@sysl|@main/ {
            fn = $0; sub(/\(.*/, "", fn); sub(/.*@/, "", fn); inside = 1; rr = 0; cd = 0; loads = 0; next }
        inside && /^}/ { if (fn != "") printf "    %-36s retain/release %d, copy/dispose %d, struct loads %d\n", fn, rr, cd, loads; inside = 0; fn = ""; next }
        inside && /call void @arc\.(retain|release)\(/ { rr++ }
        inside && /call void @arc\.(copy|dispose)\./ { cd++ }
        inside && /= load %struct\./ { loads++ }
    ' $scratch/out.ll
done
rm -rf $scratch
