// An out-of-range swizzle index in Rust: refused at compile time, after monomorphization.
//   RUSTC_BOOTSTRAP=1 rustc --crate-type=lib rust_out_of_range.rs
#![feature(portable_simd)]
use std::simd::{f32x4, simd_swizzle};

pub fn past(a: f32x4, b: f32x4) -> f32x4 {
    simd_swizzle!(a, b, [0, 8, 1, 5])
}
