#!/bin/zsh
# Re-runs every probe the ordered-collections design note cites, one line per probe (name, exit
# status) followed by what it printed, diagnostics included.
#
#   zsh docs/design/ordered-collections-probes/run.zsh [sysl]

set -u
here=${0:A:h}
sysl=${1:-sysl}

$sysl --version

for src in $here/p*.sysl; do
    out=$($sysl run $src 2>&1)
    code=$?
    print -r -- "=== ${src:t:r} exit $code"
    print -r -- "$out" | tail -n 12
done
