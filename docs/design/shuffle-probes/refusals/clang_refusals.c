// What clang refuses. Compile with `clang -fsyntax-only clang_refusals.c` -- every function below
// is an error, and the note quotes each diagnostic. Not part of run.sh's -O2 pass (it does not compile).

typedef float f4 __attribute__((ext_vector_type(4)));

// A non-constant index.
f4 nonconst(f4 a, f4 b, int k) { return __builtin_shufflevector(a, b, k, 4, 1, 5); }

// An index past both inputs (8 lanes in total, 0..7).
f4 out_of_range(f4 a, f4 b) { return __builtin_shufflevector(a, b, 0, 8, 1, 5); }

// Two inputs of different types.
typedef float f2 __attribute__((ext_vector_type(2)));
f4 mismatched(f4 a, f2 b) { return __builtin_shufflevector(a, b, 0, 1, 2, 3); }

// A swizzle naming a lane the vector does not have.
f2 swz_past(f2 a) { return a.zy; }

// A swizzle that names a lane twice cannot be assigned to.
f4 swz_dup_store(f4 v, f4 w) {
    v.xx = w.xy;
    return v;
}

// Mixing the two naming sets in one swizzle.
f4 swz_mixed(f4 a) { return a.xys2; }
