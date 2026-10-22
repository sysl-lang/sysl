; The IR the proposed lowering emits: ONE `shufflevector` per shuffle or swizzle, with the mask as
; a constant `<M x i32>` (poison for a don't-care lane), exactly the instruction `gen_splat` already
; emits with a zero mask. Written by hand in the shape `emit_vector.sysl` would print, and compiled
; by run.sh at -O0 and -O1 to show the instruction is chosen by instruction selection, not by an
; optimisation pass -- which is the guarantee today's literal-of-lane-reads does not have.
;
; Each function carries sysl's `nounwind` and is not `internal`, so it survives to the assembly.

; a.shuffle(b, [0, 4, 1, 5])              -> zip1 / unpcklps
define <4 x float> @zip_lo(<4 x float> %a, <4 x float> %b) nounwind {
  %r = shufflevector <4 x float> %a, <4 x float> %b, <4 x i32> <i32 0, i32 4, i32 1, i32 5>
  ret <4 x float> %r
}

; v.wzyx  ==  v.shuffle([3, 2, 1, 0])     -> rev64 + ext / shufps $0x1b
define <4 x float> @wzyx(<4 x float> %v) nounwind {
  %r = shufflevector <4 x float> %v, <4 x float> poison, <4 x i32> <i32 3, i32 2, i32 1, i32 0>
  ret <4 x float> %r
}

; a.shuffle(b, [1, 2, 3, 4])              -> ext #4
define <4 x float> @rotate1(<4 x float> %a, <4 x float> %b) nounwind {
  %r = shufflevector <4 x float> %a, <4 x float> %b, <4 x i32> <i32 1, i32 2, i32 3, i32 4>
  ret <4 x float> %r
}

; v.zzzz  ==  v.shuffle([2, 2, 2, 2])     -> dup v.s[2] / shufps $0xaa
define <4 x float> @zzzz(<4 x float> %v) nounwind {
  %r = shufflevector <4 x float> %v, <4 x float> poison, <4 x i32> <i32 2, i32 2, i32 2, i32 2>
  ret <4 x float> %r
}

; a.shuffle(b, [0, 1, 2, 3, 4, 5, 6, 7])  -- <8> out of two <4>: no instruction (vinsertf128 on AVX)
define <8 x i32> @concat(<4 x i32> %a, <4 x i32> %b) nounwind {
  %r = shufflevector <4 x i32> %a, <4 x i32> %b, <8 x i32> <i32 0, i32 1, i32 2, i32 3, i32 4, i32 5, i32 6, i32 7>
  ret <8 x i32> %r
}

; v.shuffle([4, 5, 6, 7]) on an <8>       -- <4> out of one <8>: the upper register
define <4 x i32> @high_half(<8 x i32> %v) nounwind {
  %r = shufflevector <8 x i32> %v, <8 x i32> poison, <4 x i32> <i32 4, i32 5, i32 6, i32 7>
  ret <4 x i32> %r
}

; an irregular byte permutation           -> tbl / pshufb, mask from the constant pool
define <16 x i8> @bytes(<16 x i8> %v) nounwind {
  %r = shufflevector <16 x i8> %v, <16 x i8> poison, <16 x i32> <i32 3, i32 0, i32 9, i32 7, i32 15, i32 1, i32 1, i32 4, i32 12, i32 2, i32 8, i32 6, i32 10, i32 14, i32 5, i32 13>
  ret <16 x i8> %r
}

; a <4>bool mask shuffled like any other vector, then used by select -- (a < b).wzyx.select(a, b)
define <4 x float> @mask_shuffled(<4 x float> %a, <4 x float> %b) nounwind {
  %m = fcmp olt <4 x float> %a, %b
  %w = shufflevector <4 x i1> %m, <4 x i1> poison, <4 x i32> <i32 3, i32 2, i32 1, i32 0>
  %r = select <4 x i1> %w, <4 x float> %a, <4 x float> %b
  ret <4 x float> %r
}

; v.xy = w.zw -- a swizzle store is a two-input shuffle of the old value and the new lanes
define <4 x float> @store_xy(<4 x float> %v, <4 x float> %w) nounwind {
  %r = shufflevector <4 x float> %w, <4 x float> %v, <4 x i32> <i32 2, i32 3, i32 6, i32 7>
  ret <4 x float> %r
}
