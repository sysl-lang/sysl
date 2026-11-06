/* The machine half of `sysl.fiber`: a stack the library maps, and the switch from one stack to another.
 *
 * **A direct call of `sysl.fiber.switch` is the compiler's**: it emits the switch inline, between
 * calls of `sysl_fiber_leaving` and `sysl_fiber_back` here, as one block of assembly that declares
 * every register but the stack, frame and base pointers clobbered -- so the code around it keeps
 * only what is live across it, and keeps it where the optimizer chose -- and it re-derives every
 * thread-local address after it, since the switch may come back on another thread. The copy in this
 * file is the same instructions, for a call through a function pointer.
 *
 * **A context is where to resume**, written into the context being left: the stack pointer, the
 * instruction after the switch, the frame and base pointers, and the floating-point control:
 *
 * | machine | pointers | control |
 * |---|---|---|
 * | aarch64 | sp, pc, x29 (frame), x19 (base) | FPCR |
 * | x86_64 | rsp, rip, rbp (frame), rbx (base) | MXCSR, x87 control word |
 *
 * No system call and no signal mask, which is what makes it some nanoseconds rather than
 * `swapcontext`'s microsecond. Mapping, guarding and `mincore` are POSIX calls written in C here,
 * as `stackguard.c` writes its own; AddressSanitizer's fiber hooks are switched on by
 * `__has_feature(address_sanitizer)`, which only C can ask.
 */

#define _GNU_SOURCE

#include <errno.h>
#include <pthread.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

/* Only a hosted macOS or Linux build with floating-point registers gets the switch. A freestanding
 * build of the library still compiles every shim, and an aarch64 one is built with general
 * registers only (no `__ARM_FP`), where saving d8-d15 and FPCR cannot even be assembled; the file is
 * empty there rather than wrong. */
#if (defined(__APPLE__) || defined(__linux__)) && \
    ((defined(__aarch64__) && defined(__ARM_FP)) || (defined(__x86_64__) && defined(__SSE__)))

#ifndef __has_feature
#define __has_feature(x) 0
#endif

#if __has_feature(address_sanitizer) || defined(__SANITIZE_ADDRESS__)
#define SYSL_FIBER_ASAN 1
#include <sanitizer/common_interface_defs.h>
#else
#define SYSL_FIBER_ASAN 0
#endif

#if defined(__APPLE__)
#define SYSL_SYM(name) "_" #name
#else
#define SYSL_SYM(name) #name
#endif

/* `sysl.fiber.Context`, laid out the same, and the layout the compiler's own switch writes and reads
 * at the offsets it names (`fiber_switch.sysl` in the compiler): where to resume -- the stack
 * pointer, the instruction, the frame pointer and the base pointer (x29 and x19, or rbp and rbx,
 * which the code around a switch may have reserved and so cannot hand over as clobbered) -- and the
 * floating-point control word. `lo` and `len` are the stack's bounds and `fake` AddressSanitizer's
 * saved fake stack, which only AddressSanitizer reads; a context for a thread's own stack learns its
 * bounds the first time it is left. */
typedef struct sysl_fiber_context {
  void *sp;
  void *pc;
  void *fp;
  void *bp;
  uint64_t ctl;
  void *lo;
  size_t len;
  void *fake;
} sysl_fiber_context;

/* The guard registry and the per-thread signal stack are `stackguard.c`'s. */
struct sysl_guard_node;
void sysl_stack_guard_add(struct sysl_guard_node *n, char *lo, char *hi);
void sysl_stack_guard_remove(struct sysl_guard_node *n);
void sysl_stack_guard_thread(void);

/* Room at the top of every mapping for the guard's registry node, rounded to keep the stack's top
 * 16-byte aligned. */
#define SYSL_NODE_ROOM 64

static size_t sysl_page(void) {
  long p = sysconf(_SC_PAGESIZE);

  return p > 0 ? (size_t)p : 4096;
}

/* The guard below the stack, a whole number of pages and at least 64 KiB: a frame larger than one
 * page can step over a single guard page and land in whatever is mapped below, and address space is
 * the only cost of a wider one. */
size_t sysl_fiber_guard_len(void) {
  size_t page = sysl_page();
  size_t want = 64 * 1024;

  return (want + page - 1) / page * page;
}

size_t sysl_fiber_page(void) { return sysl_page(); }

/* Maps `len` bytes (already whole pages, guard included) and makes the lowest `guard` of them
 * inaccessible. Nothing is committed: a page is backed when it is first touched. Answers 0, or the
 * error's code negated, with `*at` the mapping's base. */
int sysl_fiber_map(size_t len, size_t guard, char **at) {
  int flags = MAP_PRIVATE | MAP_ANON;
  char *p;

#if defined(MAP_NORESERVE)
  flags |= MAP_NORESERVE;
#endif

  p = mmap(0, len, PROT_READ | PROT_WRITE, flags, -1, 0);
  if (p == MAP_FAILED) return -errno;

  if (mprotect(p, guard, PROT_NONE) != 0) {
    int e = errno;

    munmap(p, len);
    return -e;
  }

  sysl_stack_guard_add((struct sysl_guard_node *)(p + len - SYSL_NODE_ROOM), p, p + guard);
  *at = p;
  return 0;
}

void sysl_fiber_unmap(char *at, size_t len) {
  sysl_stack_guard_remove((struct sysl_guard_node *)(at + len - SYSL_NODE_ROOM));
  munmap(at, len);
}

/* The highest address a stack in the mapping may use: below the registry node, 16-byte aligned. */
char *sysl_fiber_top(char *at, size_t len) {
  return (char *)(((uintptr_t)(at + len - SYSL_NODE_ROOM)) & ~(uintptr_t)15);
}

/* How many bytes of `[at, at + len)` are resident -- the pages a stack has actually touched. */
size_t sysl_fiber_resident(char *at, size_t len) {
  size_t page = sysl_page();
  size_t pages = len / page;
  size_t count = 0;
  unsigned char vec[256];
  size_t done = 0;

  while (done < pages) {
    size_t n = pages - done < sizeof vec ? pages - done : sizeof vec;
    size_t i;

    if (mincore(at + done * page, n * page, (void *)vec) != 0) return 0;
    for (i = 0; i < n; i++)
      if (vec[i] & 1) count++;
    done += n;
  }

  return count * page;
}

/* What a fiber whose entry came back does: there is nowhere to come back to -- the frame above the
 * entry is this library's, not a caller's -- so it says so and traps, as every other broken contract
 * in a sysl program does. */
__attribute__((used, noreturn)) static void sysl_fiber_returned(void) {
  static const char said[] =
      "sysl: a fiber's entry function returned -- an entry must switch away for good, since "
      "there is no caller on its stack to return to\n";
  ssize_t ignored = write(2, said, sizeof said - 1);

  (void)ignored;

  /* What the program had printed, which would otherwise go with the process: this is no signal
   * handler, so stdio's lock is safe to take. */
  fflush(stdout);
  __builtin_trap();
}

#if SYSL_FIBER_ASAN
/* The context the running fiber was entered from, per carrier, so the side that resumes can tell
 * AddressSanitizer's bookkeeping which stack it left. Reached through a call the optimizer cannot
 * merge, since a migrated fiber is on another thread after the switch than before it. */
static _Thread_local sysl_fiber_context *sysl_fiber_from;

__attribute__((noinline)) static sysl_fiber_context **sysl_fiber_from_slot(void) {
  __asm__ volatile("");
  return &sysl_fiber_from;
}

/* What AddressSanitizer is told on arriving: the fake stack the arriving side saved when it left
 * (none for a fiber's first arrival), and, in return, the bounds of the stack just left, which the
 * context that was left keeps for the next switch to it. */
static void sysl_fiber_arrived(void *fake) {
  sysl_fiber_context *left = *sysl_fiber_from_slot();
  const void *lo = 0;
  size_t len = 0;

  __sanitizer_finish_switch_fiber(fake, &lo, &len);
  if (left) {
    left->lo = (void *)lo;
    left->len = len;
  }
}

__attribute__((used)) static void sysl_fiber_first_entered(void) { sysl_fiber_arrived(0); }
#endif

#if defined(__aarch64__)

/* The switch itself, `from` in x0 and `to` in x1: where this side resumes is written into `from`,
 * and `to`'s is taken up -- its stack, frame and base pointers, its rounding mode (written only where
 * it differs, since `msr fpcr` is slow on some cores) -- and jumped to. Every other register is
 * declared clobbered rather than saved, so the code around a switch keeps only what is live across
 * it. The compiler emits the same instructions where a program calls `sysl.fiber.switch`; this copy
 * is the one a call through a function pointer reaches. */
#define SYSL_FIBER_SWITCH                                                                          \
  "mov x9, sp\n"                                                                                   \
  "adr x10, 1f\n"                                                                                  \
  "mrs x11, fpcr\n"                                                                                \
  "stp x9, x10, [x0]\n"                                                                            \
  "stp x29, x19, [x0, #16]\n"                                                                      \
  "str x11, [x0, #32]\n"                                                                           \
  "ldp x9, x10, [x1]\n"                                                                            \
  "ldp x29, x19, [x1, #16]\n"                                                                      \
  "ldr x11, [x1, #32]\n"                                                                           \
  "mov sp, x9\n"                                                                                   \
  "mrs x12, fpcr\n"                                                                                \
  "cmp x11, x12\n"                                                                                 \
  "b.eq 2f\n"                                                                                      \
  "msr fpcr, x11\n"                                                                                \
  "2:\n"                                                                                           \
  "br x10\n"                                                                                       \
  "1:\n"

/* x18 is the platform's on Apple systems and Android, and a scratch register on Linux. */
#if defined(__APPLE__) || defined(__ANDROID__)
#define SYSL_FIBER_X18
#else
#define SYSL_FIBER_X18 "x18",
#endif

#define SYSL_FIBER_CLOBBERS                                                                        \
  "x2", "x3", "x4", "x5", "x6", "x7", "x8", "x9", "x10", "x11", "x12", "x13", "x14", "x15", "x16",  \
      "x17", SYSL_FIBER_X18 "x20", "x21", "x22", "x23", "x24", "x25", "x26", "x27", "x28", "x30",  \
      "v0", "v1", "v2", "v3", "v4", "v5", "v6", "v7", "v8", "v9", "v10", "v11", "v12", "v13", "v14",  \
      "v15", "v16", "v17", "v18", "v19", "v20", "v21", "v22", "v23", "v24", "v25", "v26", "v27",     \
      "v28", "v29", "v30", "v31", "cc", "memory"

static inline void sysl_fiber_swap(sysl_fiber_context *from, sysl_fiber_context *to) {
  register sysl_fiber_context *x0 __asm__("x0") = from;
  register sysl_fiber_context *x1 __asm__("x1") = to;

  __asm__ volatile(SYSL_FIBER_SWITCH : "+r"(x0), "+r"(x1) : : SYSL_FIBER_CLOBBERS);
}

/* Where a new fiber's first switch lands: its entry and argument on top of its stack, and a zero
 * frame pointer in x29, so the frame chain a debugger or a sampler walks ends here. The return
 * address is marked undefined for an unwinder that reads the tables instead. */
__attribute__((naked, used)) static void sysl_fiber_boot(void) {
  __asm__ volatile(
      ".cfi_undefined x30\n"
#if SYSL_FIBER_ASAN
      "bl " SYSL_SYM(sysl_fiber_first_entered) "\n"
#endif
      "ldp x19, x20, [sp], #16\n"
      "mov x0, x20\n"
      "blr x19\n"
      "bl " SYSL_SYM(sysl_fiber_returned) "\n"
      "brk #1\n");
}

void sysl_fiber_start(sysl_fiber_context *c, char *top, char *lo, size_t len, void (*entry)(void *),
                      void *arg) {
  uint64_t *f = (uint64_t *)(top - 16);
  uint64_t fpcr;

  f[0] = (uint64_t)entry;
  f[1] = (uint64_t)arg;
  __asm__ volatile("mrs %0, fpcr" : "=r"(fpcr));
  c->sp = f;
  c->pc = (void *)&sysl_fiber_boot;
  c->fp = 0; /* the end of the frame chain */
  c->bp = 0;
  c->ctl = fpcr;
  c->lo = lo;
  c->len = len;
  c->fake = 0;
}

#else /* x86_64 */

/* The switch itself, `from` in rdi and `to` in rsi, as aarch64's is: the resume point written into
 * `from` -- stack, instruction, rbp and rbx, MXCSR and the x87 control word -- and `to`'s taken up
 * and jumped to, with every other register declared clobbered. Nothing is pushed, so the red zone
 * below the stack pointer is never written. */
#define SYSL_FIBER_SWITCH                                                                          \
  "leaq 1f(%%rip), %%rax\n"                                                                        \
  "movq %%rsp, (%%rdi)\n"                                                                          \
  "movq %%rax, 8(%%rdi)\n"                                                                         \
  "movq %%rbp, 16(%%rdi)\n"                                                                        \
  "movq %%rbx, 24(%%rdi)\n"                                                                        \
  "stmxcsr 32(%%rdi)\n"                                                                            \
  "fnstcw 36(%%rdi)\n"                                                                             \
  "movq (%%rsi), %%rsp\n"                                                                          \
  "movq 16(%%rsi), %%rbp\n"                                                                        \
  "movq 24(%%rsi), %%rbx\n"                                                                        \
  "ldmxcsr 32(%%rsi)\n"                                                                            \
  "fldcw 36(%%rsi)\n"                                                                              \
  "jmpq *8(%%rsi)\n"                                                                               \
  "1:\n"

#define SYSL_FIBER_CLOBBERS                                                                        \
  "rax", "rcx", "rdx", "r8", "r9", "r10", "r11", "r12", "r13", "r14", "r15", "xmm0", "xmm1",       \
      "xmm2", "xmm3", "xmm4", "xmm5", "xmm6", "xmm7", "xmm8", "xmm9", "xmm10", "xmm11", "xmm12",      \
      "xmm13", "xmm14", "xmm15", "xmm16", "xmm17", "xmm18", "xmm19", "xmm20", "xmm21", "xmm22",      \
      "xmm23", "xmm24", "xmm25", "xmm26", "xmm27", "xmm28", "xmm29", "xmm30", "xmm31", "k0", "k1",   \
      "k2", "k3", "k4", "k5", "k6", "k7", "st", "st(1)", "st(2)", "st(3)", "st(4)", "st(5)",         \
      "st(6)", "st(7)", "fpsr", "dirflag", "cc", "memory"

static inline void sysl_fiber_swap(sysl_fiber_context *from, sysl_fiber_context *to) {
  sysl_fiber_context *rdi = from;
  sysl_fiber_context *rsi = to;

  __asm__ volatile(SYSL_FIBER_SWITCH : "+D"(rdi), "+S"(rsi) : : SYSL_FIBER_CLOBBERS);
}

/* Where a new fiber's first switch lands: its entry and argument on top of its stack, rbp zero so the
 * frame chain ends here, and the stack 16-byte aligned at each call. */
__attribute__((naked, used)) static void sysl_fiber_boot(void) {
  __asm__ volatile(
      ".cfi_undefined %rip\n"
#if SYSL_FIBER_ASAN
      "call " SYSL_SYM(sysl_fiber_first_entered) "\n"
#endif
      "popq %r12\n"
      "popq %r13\n"
      "movq %r13, %rdi\n"
      "callq *%r12\n"
      "call " SYSL_SYM(sysl_fiber_returned) "\n"
      "ud2\n");
}

void sysl_fiber_start(sysl_fiber_context *c, char *top, char *lo, size_t len, void (*entry)(void *),
                      void *arg) {
  uint64_t *f = (uint64_t *)(top - 16);
  uint32_t mxcsr;
  uint16_t cw;

  f[0] = (uint64_t)entry;
  f[1] = (uint64_t)arg;
  __asm__ volatile("stmxcsr %0" : "=m"(mxcsr));
  __asm__ volatile("fnstcw %0" : "=m"(cw));
  c->sp = f;
  c->pc = (void *)&sysl_fiber_boot;
  c->fp = 0; /* the end of the frame chain */
  c->bp = 0;
  c->ctl = (uint64_t)mxcsr | ((uint64_t)cw << 32);
  c->lo = lo;
  c->len = len;
  c->fake = 0;
}

#endif

/* What every switch does before it leaves, the compiler's inline one included: the first switch on a
 * thread gives that thread a signal stack, so an overflow on any fiber it runs is reported rather than
 * silent; and AddressSanitizer is told which stack is about to run. */
static _Thread_local int sysl_fiber_carrier_ready;

void sysl_fiber_leaving(sysl_fiber_context *from, sysl_fiber_context *to) {
  if (!sysl_fiber_carrier_ready) {
    sysl_stack_guard_thread();
    sysl_fiber_carrier_ready = 1;
  }

#if SYSL_FIBER_ASAN
  *sysl_fiber_from_slot() = from;
  __sanitizer_start_switch_fiber(&from->fake, to->lo, to->len);
#else
  (void)from;
  (void)to;
#endif
}

/* What every switch does once it is back, on whichever thread resumed it. */
void sysl_fiber_back(sysl_fiber_context *from) {
#if SYSL_FIBER_ASAN
  sysl_fiber_arrived(from->fake);
#else
  (void)from;
#endif
}

/* The switch a call through a function pointer reaches; a direct call is the compiler's inline copy. */
void sysl_fiber_switch(sysl_fiber_context *from, sysl_fiber_context *to) {
  sysl_fiber_leaving(from, to);
  sysl_fiber_swap(from, to);
  sysl_fiber_back(from);
}

#endif
