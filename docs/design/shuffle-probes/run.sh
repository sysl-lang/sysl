#!/bin/zsh
# Re-runs every probe the shuffle design note cites.
#
#   zsh docs/design/shuffle-probes/run.sh <out dir> [sysl]
#
# For each C probe: clang -O2 for arm64 and x86_64 (SSE4.1 and AVX2), assembly into <out>/c/.
# For each sysl probe: `sysl emit-llvm` per target, then clang over the IR at -O1 (what `sysl
# build` hands clang by default) and at -O0, so what sysl hands LLVM and what LLVM makes of it
# are both on disk.

set -u
here=${0:A:h}
out=${1:?an output directory}
sysl=${2:-/opt/homebrew/bin/sysl}

mkdir -p $out/c $out/sysl

for src in $here/c/*.c(N); do
    name=${src:t:r}
    clang -O2 -S -target arm64-apple-macos14 -o $out/c/$name.arm64.s $src
    clang -O2 -S -target x86_64-apple-macos14 -msse4.1 -o $out/c/$name.x86_sse41.s $src
    clang -O2 -S -target x86_64-apple-macos14 -mavx2 -o $out/c/$name.x86_avx2.s $src
done

for src in $here/sysl/*.sysl; do
    name=${src:t:r}
    $sysl emit-llvm --target aarch64-macos $src > $out/sysl/$name.arm64.ll
    $sysl emit-llvm --target x86_64-macos $src > $out/sysl/$name.x86.ll
    clang -O1 -S -Wno-override-module -target arm64-apple-macos14 -o $out/sysl/$name.arm64.O1.s $out/sysl/$name.arm64.ll
    clang -O0 -S -Wno-override-module -target arm64-apple-macos14 -o $out/sysl/$name.arm64.O0.s $out/sysl/$name.arm64.ll
    clang -O1 -S -Wno-override-module -target x86_64-apple-macos14 -msse4.1 -o $out/sysl/$name.x86_sse41.O1.s $out/sysl/$name.x86.ll
done

# The IR the proposal emits, at -O0 and -O1: instruction selection alone picks the instruction.
mkdir -p $out/ll
for src in $here/ll/*.ll(N); do
    name=${src:t:r}
    for opt in O0 O1; do
        clang -$opt -S -Wno-override-module -target arm64-apple-macos14 -o $out/ll/$name.arm64.$opt.s $src
        clang -$opt -S -Wno-override-module -target x86_64-apple-macos14 -msse4.1 -o $out/ll/$name.x86_sse41.$opt.s $src
    done
    clang -O1 -S -Wno-override-module -target thumbv6m-none-eabi -o $out/ll/$name.thumbv6m.O1.s $src
done

# GCC's __builtin_shuffle (clang refuses it), arm64 only: gcc-16 here has no x86 back end.
if (( $+commands[gcc-16] )); then
    mkdir -p $out/gcc
    for src in $here/gcc/*.c(N); do
        gcc-16 -O2 -S -o $out/gcc/${src:t:r}.arm64.s $src
    done
fi

# What each refusal says.
clang -fsyntax-only $here/refusals/clang_refusals.c 2> $out/clang_refusals.txt

echo "written to $out"
