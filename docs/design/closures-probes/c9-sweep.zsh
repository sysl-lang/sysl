#!/bin/zsh
# The C9 census sweep (closures-c9-census.md): each repository in turn, census on, one log per
# repository. Argument: a scratch directory holding c9-repos.txt, the repository names one per line.
# The compiler is a stage 1 built with c9_census.sysl in it (see the census page for the two hooks).
sp=$1
out=$sp/c9-out
mkdir -p $out $sp/c9-bin
compiler=/Users/ed/dev/sysl-lang/sysl-clo-c9/compiler/sysl
export SYSL_LIB=/Users/ed/dev/sysl-lang/sysl-clo-c9/library
export SYSL_C9_CENSUS=1
export PICO_SDK_PATH=/Users/ed/dev/sysl-lang/pico-scratch/pico-sdk

for repo in $(cat $sp/c9-repos.txt); do
    dir=/Users/ed/dev/sysl-lang/$repo
    cd $dir || continue
    if [ -f package.hocon ]; then
        kind=package
        nice -n 15 $compiler test . --filter c9-census-matches-nothing > $out/$repo.log 2>&1
    else
        kind=program
        nice -n 15 $compiler build . -o $sp/c9-bin/$repo > $out/$repo.log 2>&1
    fi
    rc=$?
    echo "$repo $kind rc=$rc hits=$(grep -c '^C9|' $out/$repo.log)" >> $out/summary.txt
done

cd /Users/ed/dev/sysl-lang/sysl-clo-c9/compiler
nice -n 15 $compiler build . -o $sp/c9-bin/sysl-compiler > $out/sysl-compiler.log 2>&1
echo "sysl-compiler program rc=$? hits=$(grep -c '^C9|' $out/sysl-compiler.log)" >> $out/summary.txt
nice -n 15 $compiler test ../library --std --filter c9-census-matches-nothing > $out/sysl-library.log 2>&1
echo "sysl-library package rc=$? hits=$(grep -c '^C9|' $out/sysl-library.log)" >> $out/summary.txt
echo DONE >> $out/summary.txt
