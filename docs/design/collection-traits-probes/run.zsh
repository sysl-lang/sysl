#!/bin/zsh
# Runs every probe (or those named) with the installed sysl and, when given, a second compiler:
#   zsh run.zsh [<dev sysl> <its library>] [-- p01 p02 ...]
root=${0:A:h}
dev=$1
lib=$2
shift 2 2>/dev/null
[[ $1 == -- ]] && shift
probes=("$@")
if (( ${#probes} == 0 )); then probes=(${root}/p*(/:t)); fi
for p in $probes; do
  print "=== $p installed"
  ( cd $root/$p && env -u SYSL_LIB sysl run main.sysl ) 2>&1 | head -40
  if [[ -n $dev ]]; then
    print "=== $p dev"
    ( cd $root/$p && SYSL_LIB=$lib $dev run main.sysl ) 2>&1 | head -40
  fi
done
