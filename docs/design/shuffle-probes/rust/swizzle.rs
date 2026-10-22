// Rust's portable SIMD swizzles (nightly `portable_simd`; probed on stable 1.94 with
// RUSTC_BOOTSTRAP=1):
//
//   RUSTC_BOOTSTRAP=1 rustc --crate-type=lib -C opt-level=2 --emit asm swizzle.rs
//
// `simd_swizzle!(a, [..])` / `simd_swizzle!(a, b, [..])` take a const array of lane indices; with
// two inputs the second's lanes are numbered N..2N-1, as LLVM numbers them. The index array is a
// const generic, so a non-constant one does not type-check, and an out-of-range one is a
// compile-time (post-monomorphization) error. The output lane count is the index array's length.
#![feature(portable_simd)]
use std::simd::{f32x4, f32x8, simd_swizzle, u8x16};

#[no_mangle]
pub fn zip_lo(a: f32x4, b: f32x4) -> f32x4 {
    simd_swizzle!(a, b, [0, 4, 1, 5])
}

#[no_mangle]
pub fn reverse(a: f32x4) -> f32x4 {
    simd_swizzle!(a, [3, 2, 1, 0])
}

#[no_mangle]
pub fn rotate1(a: f32x4, b: f32x4) -> f32x4 {
    simd_swizzle!(a, b, [1, 2, 3, 4])
}

#[no_mangle]
pub fn concat(a: f32x4, b: f32x4) -> f32x8 {
    simd_swizzle!(a, b, [0, 1, 2, 3, 4, 5, 6, 7])
}

// The library's named shuffles are built on the same intrinsic.
#[no_mangle]
pub fn interleave_lo(a: f32x4, b: f32x4) -> f32x4 {
    a.interleave(b).0
}

// A run-time byte permutation: `swizzle_dyn`, which is `tbl` on arm64.
#[no_mangle]
pub fn runtime_bytes(a: u8x16, idx: u8x16) -> u8x16 {
    a.swizzle_dyn(idx)
}
