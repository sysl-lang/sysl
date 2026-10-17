// What Zig permits, settled at comptime so `build-obj` needs no linker.
const Bare = union { i: u32, f: f32 };
const Ext = extern union { i: u32, f: f32, b: bool };
const In6 = extern union { b8: [16]u8, b16: [8]u16, b32: [4]u32 };

comptime {
    // an extern union reads a field that was not the one written: the bytes, reinterpreted
    const e = Ext{ .f = 1.0 };
    if (e.i != 0x3f800000) @compileError("extern union pun");
    if (@sizeOf(In6) != 16 or @alignOf(In6) != 4) @compileError("in6");
    // an extern union may hold a bool, as C's may
    if (@sizeOf(Ext) != 4) @compileError("ext size");
}

export fn bare_read(x: f32) u32 {
    // a bare union has a hidden safety tag in Debug and ReleaseSafe: this read panics there
    const u = Bare{ .f = x };
    return u.i;
}

export fn bare_size() usize {
    return @sizeOf(Bare); // 8 in a safe build: the hidden tag is stored
}
