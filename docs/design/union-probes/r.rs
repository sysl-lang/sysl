// What rustc permits in a union: each numbered line is one probe, and the errors name them.
#[repr(C)]
#[derive(Clone, Copy)]
union In6 { b8: [u8; 16], b16: [u16; 8], b32: [u32; 4] }

#[repr(C)]
union Owns { s: String, n: u64 }              // 1: a field with drop glue

#[repr(C)]
#[derive(Clone, Copy)]
union Flag { b: bool, n: u8 }                 // a bool field is allowed

#[derive(PartialEq)]                          // 2: derive(PartialEq) on a union
#[repr(C)]
#[derive(Clone, Copy)]
union Eqd { a: u32, b: f32 }

fn main() {
    let mut a = In6 { b32: [0; 4] };
    a.b8[0] = 1;                              // a write to a Copy field: safe
    let x = a.b16[0];                         // 3: a read: needs unsafe
    let f = Flag { n: 2 };
    let b = unsafe { f.b };                   // compiles; it is UB at run time
    let both = In6 { b8: [0; 16], b16: [0; 8] }; // 4: two fields named
    match a { In6 { b8 } => () }              // 5: a pattern outside unsafe
    println!("{} {} {}", x, b, std::mem::size_of::<In6>());
}
