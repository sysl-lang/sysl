#!/bin/zsh
# Runs every probe with the installed sysl and prints what it said.
dir=${0:A:h}
for f in $dir/t*.sysl; do
    print -- "=== ${f:t}"
    sysl run $f 2>&1 | head -12
done
