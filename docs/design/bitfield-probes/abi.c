/* By-value passing: each bitfield struct beside an ordinary struct of the same size and alignment. */

struct b4  { unsigned a:3; unsigned b:29; };            struct p4  { unsigned x; };
struct b8  { unsigned a:3; unsigned b:30; };            struct p8  { unsigned x, y; };
struct b2  { unsigned short a:3; unsigned short b:9; }; struct p2  { unsigned short x; };
struct b16 { unsigned long long a:40; unsigned long long b:40; }; struct p16 { unsigned long long x, y; };
struct b24 { unsigned long long a:40, b:40, c:40; };    struct p24 { unsigned long long x, y, z; };
struct bf  { float f; unsigned a:3; };                  struct pf  { float f; unsigned x; };
struct bd  { double d; unsigned a:3; };                 struct pd  { double d; unsigned x; };
struct bff { float f; unsigned a:3; float g; };         struct pff { float f; unsigned x; float g; };
struct bz  { float f; int :0; float g; };               struct pz  { float f; float g; };

struct b1  { unsigned a:3; };                           struct p1  { unsigned x; };
struct bfx { float f; unsigned a:3, b:3; };             struct pfx { float f; unsigned x; };
struct bfc { float f; unsigned char a:3; };             struct pfc { float f; unsigned char x; };
struct b1  f_b1(struct b1 v)   { return v; }  struct p1  f_p1(struct p1 v)   { return v; }
struct bfx f_bfx(struct bfx v) { return v; }  struct pfx f_pfx(struct pfx v) { return v; }
struct bfc f_bfc(struct bfc v) { return v; }  struct pfc f_pfc(struct pfc v) { return v; }
struct b4  f_b4(struct b4 v)   { return v; }  struct p4  f_p4(struct p4 v)   { return v; }
struct b8  f_b8(struct b8 v)   { return v; }  struct p8  f_p8(struct p8 v)   { return v; }
struct b2  f_b2(struct b2 v)   { return v; }  struct p2  f_p2(struct p2 v)   { return v; }
struct b16 f_b16(struct b16 v) { return v; }  struct p16 f_p16(struct p16 v) { return v; }
struct b24 f_b24(struct b24 v) { return v; }  struct p24 f_p24(struct p24 v) { return v; }
struct bf  f_bf(struct bf v)   { return v; }  struct pf  f_pf(struct pf v)   { return v; }
struct bd  f_bd(struct bd v)   { return v; }  struct pd  f_pd(struct pd v)   { return v; }
struct bff f_bff(struct bff v) { return v; }  struct pff f_pff(struct pff v) { return v; }
struct bz  f_bz(struct bz v)   { return v; }  struct pz  f_pz(struct pz v)   { return v; }
