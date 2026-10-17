#!/bin/zsh
# Dumps every probe struct's layout on every sysl target (plus MSVC) as one line per struct.
dir=${0:A:h}
cc=/opt/homebrew/opt/llvm/bin/clang
triples=(arm64-apple-macosx x86_64-apple-macosx aarch64-unknown-linux-gnu x86_64-unknown-linux-gnu
  riscv64-unknown-linux-gnu x86_64-pc-windows-msvc aarch64-linux-android24 aarch64-none-elf
  x86_64-unknown-none-elf riscv64-unknown-elf thumbv8m.main-none-eabihf thumbv8m.main-none-eabi
  thumbv6m-none-eabi thumbv7m-none-eabi thumbv7em-none-eabihf riscv32-unknown-elf
  wasm32-unknown-unknown wasm32-wasip1 i386-unknown-linux-gnu)
mkdir -p $dir/out $dir/layouts
for t in $triples; do
  $cc --target=$t -ffreestanding -fsyntax-only -Xclang -fdump-record-layouts $dir/layout.c > $dir/out/$t.dump 2>&1
  awk '
    /\*\*\* Dumping AST Record Layout/ { if (line != "") print line; line=""; next }
    /\| struct / && line == "" { sub(/.*\| struct /, ""); line=$0 ":"; next }
    /^ *[0-9]+:[0-9]+-[0-9]+ \|/ { split($1, p, ":"); split(p[2], r, "-"); f=$NF; line=line " " f "@" p[1]*8+r[1] "+" r[2]-r[1]+1; next }
    /^ *[0-9]+ \|   / { f=$NF; line=line " " f "@" $1*8; next }
    /sizeof=/ { s=$0; sub(/.*sizeof=/, "", s); sub(/,.*/, "", s); a=$0; sub(/.*[^d]align=/, "", a); sub(/[],].*/, "", a); line=line " | " s "/" a; next }
    END { if (line != "") print line }
  ' $dir/out/$t.dump | sort > $dir/layouts/$t.txt
  $cc --target=$t -ffreestanding -O1 -S -emit-llvm -o $dir/out/$t.ll $dir/layout.c
done
