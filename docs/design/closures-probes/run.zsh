#!/bin/zsh
# Runs every probe with the installed sysl and prints what each answered.
dir=${0:A:h}
for f in $dir/p*.sysl; do
    print -- "=== ${f:t}"
    sysl run $f 2>&1 | head -2
done
