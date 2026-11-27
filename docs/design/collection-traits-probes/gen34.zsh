#!/bin/zsh
# Writes p34_<k>/main.sysl: p31's adapters with a k-stage chain (alternating map and filter), then
# builds each with the installed sysl under SYSL_TIMINGS and reports wall time and the longest symbol.
root=${0:A:h}
head_src=$root/p31/main.sysl
for k in 5 10 20 40; do
  dir=$root/p34_$k
  mkdir -p $dir
  awk '/^@export\("chain_sum"\)/ { exit } { print }' $head_src > $dir/main.sysl
  {
    print "chain(n: int) -> int"
    print "    val s0 = Count(0, n)"
    for i in $(seq 1 $k); do
      prev=$((i - 1))
      if (( i % 2 == 1 )); then
        print "    val s$i = map(s$prev, x -> x + $i)"
      else
        print "    val s$i = filter(s$prev, x -> x % $((i + 1)) != 0)"
      fi
    done
    print "    fold(s$k, 0, (a, b) -> a +% b)"
    print ""
    print "main()"
    print "    print(chain(1000))"
  } >> $dir/main.sysl
  cd $dir
  start=$EPOCHREALTIME
  SYSL_NO_CACHE=1 SYSL_TIMINGS=1 env -u SYSL_LIB sysl build main.sysl -o chain > build.txt 2>&1
  rc=$?
  end=$EPOCHREALTIME
  printf "k=%d rc=%d wall=%.2fs run=%s\n" $k $rc $((end - start)) "$(./chain 2>&1)"
  grep -E "bodies|codegen|link|declarations" build.txt | tr '\n' ' '
  print
  env -u SYSL_LIB sysl emit-llvm main.sysl 2>/dev/null | awk '/^define/ { if (length($0) > m) m = length($0) } END { print "longest define line: " m " chars" }'
done
