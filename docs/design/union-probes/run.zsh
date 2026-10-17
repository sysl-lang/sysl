#!/bin/zsh
# Compile layout.c (static_asserts) and abi.c (define lines) for every convention cabi.sysl names.
dir=${0:a:h}
targets=(
  "aarch64-apple-darwin"
  "aarch64-unknown-linux-gnu"
  "x86_64-unknown-linux-gnu"
  "x86_64-pc-windows-msvc"
  "thumbv7em-none-eabihf -mfloat-abi=hard -mfpu=fpv5-d16"
  "thumbv7em-none-eabi -mfloat-abi=soft"
  "riscv32-unknown-elf -march=rv32imafc -mabi=ilp32f"
  "riscv64-unknown-elf -march=rv64gc -mabi=lp64d"
  "wasm32-unknown-unknown"
)
for t in $targets; do
  words=(${=t})
  echo "=== ${words[1]} ${words[2,-1]}"
  clang -target ${words[1]} ${words[2,-1]} -std=c11 -ffreestanding -fsyntax-only $dir/layout.c && echo "layout: all static_asserts hold"
  clang -target ${words[1]} ${words[2,-1]} -std=c11 -ffreestanding -O1 -S -emit-llvm -o - $dir/abi.c | grep '^define' | sed -E 's/ local_unnamed_addr//; s/ #[0-9]+ \{//; s/dso_local //; s/noundef //g; s/ captures\(none\)//g; s/nocapture //g; s/ (readonly|writeonly|nonnull|initializes\([^)]*\)|dead_on_unwind|writable)//g'
done
