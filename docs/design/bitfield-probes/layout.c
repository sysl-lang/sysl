/* Bitfield layout probes: compiled with -fdump-record-layouts on every sysl target. */

struct mixed      { unsigned a:3; unsigned b:7; unsigned c:22; };
struct strad_int  { unsigned a:30; unsigned b:4; };
struct strad_char { unsigned char a:5; unsigned char b:4; };
struct strad_ll   { unsigned a:30; unsigned long long b:40; };
struct fits_ll    { unsigned a:20; unsigned long long b:20; };
struct zero_int   { unsigned a:3; unsigned :0; unsigned b:3; };
struct zero_char  { unsigned char a:3; unsigned char :0; unsigned char b:3; };
struct zero_align { char a; int :0; char b; };
struct unnamed    { unsigned a:3; unsigned :5; unsigned b:3; };
struct unnamed_al { char a; int :4; };
struct sgn        { int a:3; unsigned b:3; char c:3; };
struct char_int   { char a:3; int b:5; };
struct char_int2  { char a; int b:5; };
struct short_char { short a:9; char b:7; };
struct int_char   { int a:20; char b:6; };
struct beside     { unsigned a:3; unsigned char c; unsigned b:5; };
struct beside2    { unsigned char c; unsigned b:20; };
struct boolf      { _Bool a:1; _Bool b:1; unsigned char c:6; };
struct bool_int   { _Bool a:1; unsigned b:3; };
struct __attribute__((packed)) packed1 { unsigned a:3; unsigned b:30; };
struct __attribute__((packed)) packed2 { unsigned char c; unsigned b:20; unsigned long long d:40; };
#pragma pack(push, 1)
struct pragma1    { unsigned a:3; unsigned b:30; };
struct pragma2    { unsigned char c; unsigned b:20; };
#pragma pack(pop)
struct one_char   { unsigned char a:3; };
struct one_int    { unsigned a:3; };
struct one_ll     { unsigned long long a:3; };
struct wide63     { unsigned a:1; unsigned long long b:63; };
struct esr        { unsigned long long iss:25, il:1, ec:6, iss2:5, res0:27; };
enum E { EX, EY, EZ };
struct enumf      { enum E e:2; unsigned b:3; };

int sizes[] = {
    sizeof(struct mixed), sizeof(struct strad_int), sizeof(struct strad_char),
    sizeof(struct strad_ll), sizeof(struct fits_ll), sizeof(struct zero_int),
    sizeof(struct zero_char), sizeof(struct zero_align), sizeof(struct unnamed),
    sizeof(struct unnamed_al), sizeof(struct sgn), sizeof(struct char_int),
    sizeof(struct char_int2), sizeof(struct short_char), sizeof(struct int_char),
    sizeof(struct beside), sizeof(struct beside2), sizeof(struct boolf),
    sizeof(struct bool_int), sizeof(struct packed1), sizeof(struct packed2),
    sizeof(struct pragma1), sizeof(struct pragma2), sizeof(struct one_char),
    sizeof(struct one_int), sizeof(struct one_ll), sizeof(struct wide63),
    sizeof(struct esr), sizeof(struct enumf),
};

/* Signedness of a plain `char`/`int`/enum bitfield, read through a 3-bit field holding 7. */
int rd_char(struct sgn *s) { return s->c; }
int rd_int(struct sgn *s) { return s->a; }
int rd_enum(struct enumf *s) { return s->e; }
