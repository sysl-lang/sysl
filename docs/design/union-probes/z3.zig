// A bare union read through its inactive field, at comptime.
const Bare = union { i: u32, f: f32 };
comptime {
    const u = Bare{ .f = 1.0 };
    _ = u.i;
}
const Two = extern union { a: u32, b: f32 };
comptime {
    const q = Two{ .a = 1 };
    _ = q == q;
}
comptime {
    _ = Two{ .a = 1, .b = 2.0 };
}
