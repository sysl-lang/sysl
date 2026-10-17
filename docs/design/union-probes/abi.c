/* How each C ABI passes and returns a union by value: read the `define` lines of the IR. */
typedef unsigned char u8;
typedef unsigned int u32;
typedef unsigned long long u64;

union FI { float f; u32 i; };              /* float / int overlap, 4 bytes */
union F1 { float f; };                     /* one float member */
union FF { float a; float b[2]; };         /* all float, 8 bytes */
union DI { double d; u64 i; };             /* double / int, 8 bytes */
union D2 { double d[2]; };                 /* all double, 16 bytes */
union FD { float f[4]; double d[2]; };     /* floats of two widths, 16 bytes */
union B12 { u8 b[12]; u32 w[3]; };         /* integer, 12 bytes */
union B24 { u8 b[24]; u64 w[3]; };         /* integer, 24 bytes */
union PI { void *p; u32 n; };              /* pointer / int */
struct SFU { float x; union { float y; u32 z; } u; }; /* struct holding a union */

union FI id_fi(union FI a) { return a; }
union F1 id_f1(union F1 a) { return a; }
union FF id_ff(union FF a) { return a; }
union DI id_di(union DI a) { return a; }
union D2 id_d2(union D2 a) { return a; }
union FD id_fd(union FD a) { return a; }
union B12 id_b12(union B12 a) { return a; }
union B24 id_b24(union B24 a) { return a; }
union PI id_pi(union PI a) { return a; }
struct SFU id_sfu(struct SFU a) { return a; }
