// Clang's two spellings of a lane rearrangement, and the instruction each pattern becomes.
//
//   __builtin_shufflevector(a, b, i...)  -- constant indices, two inputs, -1 = don't care;
//                                           the result has as many lanes as indices are given.
//   ext_vector_type swizzles            -- v.xyzw / v.s0123 / v.hi / v.lo / v.even / v.odd
//
// Each function is one pattern the design note cites.

typedef float f4 __attribute__((ext_vector_type(4)));
typedef float f8 __attribute__((ext_vector_type(8)));
typedef int i4 __attribute__((ext_vector_type(4)));
typedef int i8 __attribute__((ext_vector_type(8)));
typedef unsigned char u16x __attribute__((ext_vector_type(16)));

// Interleave the low halves: arm64 zip1, x86 unpcklps.
f4 zip_lo(f4 a, f4 b) { return __builtin_shufflevector(a, b, 0, 4, 1, 5); }

// Interleave the high halves: arm64 zip2, x86 unpckhps.
f4 zip_hi(f4 a, f4 b) { return __builtin_shufflevector(a, b, 2, 6, 3, 7); }

// Even lanes of the pair: arm64 uzp1, x86 shufps.
f4 uzp_even(f4 a, f4 b) { return __builtin_shufflevector(a, b, 0, 2, 4, 6); }

// Rotate by one lane across two vectors: arm64 ext #4, x86 palignr / shufps pair.
f4 rotate1(f4 a, f4 b) { return __builtin_shufflevector(a, b, 1, 2, 3, 4); }

// Reverse a single vector: arm64 rev64 + ext, x86 shufps $0x1b.
f4 reverse(f4 a) { return __builtin_shufflevector(a, a, 3, 2, 1, 0); }

// Broadcast one lane: arm64 dup v.s[2], x86 shufps $0xaa.
f4 bcast2(f4 a) { return __builtin_shufflevector(a, a, 2, 2, 2, 2); }

// Concatenate two <4> into an <8>: no instruction at all -- two registers become one value.
i8 concat(i4 a, i4 b) { return __builtin_shufflevector(a, b, 0, 1, 2, 3, 4, 5, 6, 7); }

// Narrow: the high half of an <8> as an <4>.
i4 high_half(i8 a) { return __builtin_shufflevector(a, a, 4, 5, 6, 7); }

// A don't-care lane (-1): LLVM may pick whatever is cheapest for it.
f4 dont_care(f4 a, f4 b) { return __builtin_shufflevector(a, b, 0, 4, -1, -1); }

// An irregular byte permutation: arm64 tbl, x86 pshufb with a constant-pool mask.
u16x bytes_irregular(u16x a) {
    return __builtin_shufflevector(a, a, 3, 0, 9, 7, 15, 1, 1, 4, 12, 2, 8, 6, 10, 14, 5, 13);
}

// The ext_vector swizzle spellings: same IR as the builtin.
f4 swz_wzyx(f4 a) { return a.wzyx; }
f4 swz_xxyy(f4 a) { return a.xxyy; }
f4 swz_s1032(f4 a) { return a.s1032; }
f4 swz_lo_hi(f8 a) { return a.hi; }      // <8> -> <4>: the upper half
f4 swz_even(f8 a) { return a.even; }     // <8> -> <4>: lanes 0,2,4,6

// A swizzle as an assignment target: writes lanes 0 and 1 of v, keeps 2 and 3.
f4 swz_store(f4 v, f4 w) {
    v.xy = w.zw;
    return v;
}

// A shuffle whose indices are NOT constant: clang has __builtin_shufflevector refuse it, so the
// runtime form is a lane-by-lane gather, which arm64 can do in one tbl over bytes.
f4 runtime_permute(f4 a, i4 idx) {
    f4 r;
    for (int i = 0; i < 4; i++) r[i] = a[idx[i] & 3];
    return r;
}

// A shuffle combined with a mask: select between a and its reversal by a comparison.
f4 with_mask(f4 a, f4 b) {
    i4 m = a < b;
    f4 rev = __builtin_shufflevector(a, a, 3, 2, 1, 0);
    return m ? rev : b;
}
