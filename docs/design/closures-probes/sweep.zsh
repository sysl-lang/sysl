#!/bin/zsh
# The migration sweep of closures.md § 11. Takes a stage-1 compiler built with one extra fault in
# closure.sysl's refuse_lost_writes, at the arm that accepts a read-back write:
#
#     if sysl.env.get("SYSL_CLOSURE_CENSUS").unwrap_or("") == "1"
#         cx.fault(scan.at.at(i), s"CLOSURE-READBACK '$name'")
#
# and analyses every project of every repository under ~/dev/sysl-lang, listing each hit.
#
#     zsh sweep.zsh <instrumented sysl> <library dir> <output file>
bin=$1
export SYSL_LIB=$2
export SYSL_CLOSURE_CENSUS=1
out=$3
: > $out
for repo in ~/dev/sysl-lang/*(/); do
    [ -d $repo/.git ] || continue
    for manifest in $repo/package.hocon(N) $repo/sysl.hocon(N) $repo/*/package.hocon(N) $repo/*/sysl.hocon(N); do
        proj=${manifest:h}
        print -- "=== $proj" >> $out
        ( cd $proj && nice -n 15 $bin emit-typed . 2>&1 | grep -A1 "CLOSURE-READBACK\|^error" | grep -v "^--$" | head -40 ) >> $out
    done
done
print -- "=== SWEEP DONE" >> $out
