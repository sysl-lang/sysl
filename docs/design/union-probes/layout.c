/* Layout claims for an untagged union. Each static_assert is one claim; a target where one is
   false fails to compile, naming the line. P is the pointer width in bytes, A8 the alignment of an
   8-byte integer (4 on i386 only; 8 everywhere this probe runs). */
typedef unsigned char u8;
typedef unsigned short u16;
typedef unsigned int u32;
typedef unsigned long long u64;
#define offsetof(t, f) __builtin_offsetof(t, f)
#define P ((int)sizeof(void *))
#define A8 ((int)_Alignof(u64))

/* 1. size = largest member, rounded up to the largest alignment */
union U1 { u8 a; u32 b; };
_Static_assert(sizeof(union U1) == 4 && _Alignof(union U1) == 4, "U1");
union U2 { u8 a[5]; u32 b; };
_Static_assert(sizeof(union U2) == 8 && _Alignof(union U2) == 4, "U2 rounds 5 up to 8");
union U3 { u8 a[3]; u16 b; };
_Static_assert(sizeof(union U3) == 4 && _Alignof(union U3) == 2, "U3 rounds 3 up to 4");
union U4 { u8 c; };
_Static_assert(sizeof(union U4) == 1 && _Alignof(union U4) == 1, "U4 one member");

/* 2. every member at offset 0 */
_Static_assert(offsetof(union U2, a) == 0 && offsetof(union U2, b) == 0, "offset 0");

/* 3. packed: alignment 1, size = largest member, no rounding */
union __attribute__((packed)) UP { u8 a[5]; u32 b; };
_Static_assert(sizeof(union UP) == 5 && _Alignof(union UP) == 1, "packed U2");

/* 4. aligned(n): raises the alignment and rounds the size to it */
union __attribute__((aligned(16))) UA { u8 a[5]; u32 b; };
_Static_assert(sizeof(union UA) == 16 && _Alignof(union UA) == 16, "aligned U2");

/* 5. in6_addr */
struct in6 { union { u8 b8[16]; u16 b16[8]; u32 b32[4]; } u; };
_Static_assert(sizeof(struct in6) == 16 && _Alignof(struct in6) == 4, "in6_addr");

/* 6. epoll_data: 8 bytes, 8-aligned on every target here, 32-bit ones included */
typedef union { void *ptr; int fd; u32 u32_; u64 u64_; } epoll_data;
_Static_assert(sizeof(epoll_data) == 8 && _Alignof(epoll_data) == A8, "epoll_data");

/* 7. a union nested in a struct is laid out as a field of its size and alignment */
struct N { u8 tag; union { u32 i; float f; } v; u8 t; };
_Static_assert(offsetof(struct N, v) == 4 && offsetof(struct N, t) == 8 && sizeof(struct N) == 12, "nested");

/* 8. C11 anonymous union member: its fields are reached through, at the union's offset */
struct AN { u8 tag; union { u32 i; float f; }; u8 t; };
_Static_assert(offsetof(struct AN, i) == 4 && offsetof(struct AN, f) == 4 && sizeof(struct AN) == 12, "anon union");
/* anonymous struct inside a union: its fields at their struct offsets */
union AS { u64 all; struct { u32 lo; u32 hi; }; };
_Static_assert(offsetof(union AS, hi) == 4 && sizeof(union AS) == 8, "anon struct in union");

/* 9. a bitfield struct inside a union: the union is its storage unit's size */
union BF { u32 all; struct { u32 a : 3; u32 b : 5; } bits; };
_Static_assert(sizeof(union BF) == 4, "bitfields in union");

/* 10. a packed struct holding an ordinary union: the union is at offset 1, unaligned */
struct __attribute__((packed)) PS { u8 tag; union { u32 i; float f; } v; };
_Static_assert(offsetof(struct PS, v) == 1 && sizeof(struct PS) == 5, "union in packed struct");

/* 11. a union of a pointer and an integer is pointer-sized */
union PI { void *p; u32 n; };
_Static_assert(sizeof(union PI) == P && _Alignof(union PI) == P, "ptr union");

/* 12. SDL_Event shape: u32 type first in every member, padded out to a fixed size */
typedef struct { u32 type; u32 ts; int x; int y; } MouseEv;
typedef struct { u32 type; u32 ts; u8 key; } KeyEv;
typedef union { u32 type; MouseEv m; KeyEv k; u8 padding[128]; } Event;
_Static_assert(sizeof(Event) == 128 && offsetof(Event, k.key) == 8, "SDL_Event-like");

/* 13. a double in a union on a 32-bit target */
union DU { double d; u32 w[2]; };
_Static_assert(sizeof(union DU) == 8 && _Alignof(union DU) == _Alignof(double), "double union");

int sizes[] = { sizeof(union DU), _Alignof(union DU), _Alignof(u64), P };
