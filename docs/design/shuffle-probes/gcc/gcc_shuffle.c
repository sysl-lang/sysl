// GCC's __builtin_shuffle (gcc-16, `gcc-16 -O2 -S gcc_shuffle.c`; clang does not accept it).
//
// The mask is a VECTOR of integers of the same lane count and width, not a list of literals, and it
// need not be constant: GCC reduces each index modulo the lane count (one input) or twice the lane
// count (two inputs), so it never has an out-of-range case to refuse.

typedef float f4 __attribute__((vector_size(16)));
typedef int i4 __attribute__((vector_size(16)));

// Constant mask: the same zip1 clang emits.
f4 zip_lo(f4 a, f4 b) { return __builtin_shuffle(a, b, (i4){0, 4, 1, 5}); }

// Constant mask, one input.
f4 reverse(f4 a) { return __builtin_shuffle(a, (i4){3, 2, 1, 0}); }

// Runtime mask, one input: on arm64 an index-to-byte expansion and one tbl.
f4 runtime_permute(f4 a, i4 idx) { return __builtin_shuffle(a, idx); }

// Runtime mask, two inputs: tbl over a register pair.
f4 runtime_permute2(f4 a, f4 b, i4 idx) { return __builtin_shuffle(a, b, idx); }

// An out-of-range constant is not an error: 9 is taken modulo 8, which is lane 1.
f4 wraps(f4 a, f4 b) { return __builtin_shuffle(a, b, (i4){0, 9, 1, 5}); }
