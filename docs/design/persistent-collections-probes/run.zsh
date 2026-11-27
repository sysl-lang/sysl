#!/bin/zsh
# Runs every probe under a counted heap with the installed sysl and, where a second compiler is
# named, with that one too (`run.zsh <dev sysl> <its library>`), and prints what each said.
dir=${0:A:h}
dev=$1
devlib=$2
for f in $dir/p*.sysl; do
    print -- "=== ${f:t} (installed: $(sysl --version))"
    SYSL_COUNT_ALLOCS=1 sysl run $f 2>&1 | head -24
    print -- "--- exit ${pipestatus[1]}"
    if [[ -n $dev ]]; then
        print -- "=== ${f:t} (dev)"
        SYSL_COUNT_ALLOCS=1 SYSL_LIB=$devlib $dev run $f 2>&1 | head -24
        print -- "--- exit ${pipestatus[1]}"
    fi
done
