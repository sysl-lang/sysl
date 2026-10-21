#!/bin/zsh
# For each target, compares the lowered signature of f_bX (bitfield) with f_pX (plain twin).
dir=${0:A:h}
cc=/opt/homebrew/opt/llvm/bin/clang
triples=(arm64-apple-macosx x86_64-apple-macosx aarch64-unknown-linux-gnu x86_64-unknown-linux-gnu
  riscv64-unknown-linux-gnu x86_64-pc-windows-msvc aarch64-linux-android24 aarch64-none-elf
  x86_64-unknown-none-elf riscv64-unknown-elf thumbv8m.main-none-eabihf thumbv8m.main-none-eabi
  thumbv6m-none-eabi thumbv7m-none-eabi thumbv7em-none-eabihf riscv32-unknown-elf
  wasm32-unknown-unknown wasm32-wasip1 i386-unknown-linux-gnu)
for t in $triples; do
  $cc --target=$t -ffreestanding -O0 -S -emit-llvm -o $dir/out/abi-$t.ll $dir/abi.c
  awk -v T=$t '
    /^define/ {
      n=$0; sub(/.*@f_/, "", n); sub(/\(.*/, "", n)
      s=$0; sub(/ #[0-9]+ \{$/, "", s); sub(/@f_[a-z0-9]+/, "@f", s)
      gsub(/%struct\.[a-z0-9]+/, "%S", s); gsub(/%[0-9a-z_.]+/, "%v", s); gsub(/noundef |dso_local |local_unnamed_addr /, "", s)
      sig[n]=s
    }
    END {
      bad=""
      for (n in sig) if (substr(n,1,1)=="b") { p="p" substr(n,2); if (sig[n] != sig[p]) bad=bad "\n  " n ": " sig[n] "\n  " p ": " sig[p] }
      print T (bad=="" ? ": all same" : ": DIFFER" bad)
    }' $dir/out/abi-$t.ll
done
