/* The machine half of `sysl.fiber`: a stack the library maps, and the switch from one stack to another.
 *
 * **The switch is a naked function in C rather than a block of sysl's module-level `asm`**, for three
 * reasons this file has and the sysl beside it does not: the C compiler spells the symbol for the
 * object format (Mach-O's leading underscore, ELF's `.type`/`.size`) and emits its unwind entry, which
 * a top-level block would need a copy of per operating system; the same file needs AddressSanitizer's
 * fiber hooks, which only `__has_feature(address_sanitizer)` can switch on; and mapping, guarding and
 * `mincore` are POSIX calls already written in C here, as `stackguard.c` writes its own.
 *
 * **A context is one saved stack pointer.** Everything a callee-saved convention promises survives a
 * call is pushed onto the stack being left, and the pointer to it is the context: the switch is a
 * call that returns on another stack. What is saved:
 *
 * | machine | integer | floating point | control |
 * |---|---|---|---|
 * | aarch64 | x19--x28, x29 (frame), x30 (return) | d8--d15 | FPCR |
 * | x86_64 | rbx, rbp, r12--r15 (and the return address) | -- (System V saves none) | MXCSR, x87 control word |
 *
 * No system call and no signal mask, which is what makes it some nanoseconds rather than
 * `swapcontext`'s microsecond.
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

#if defined(__aarch64__) || defined(__x86_64__)

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

/* `sysl.fiber.Context`, laid out the same. `lo` and `len` are the stack's bounds, which only
 * AddressSanitizer reads; a context for a thread's own stack learns them the first time it is left. */
typedef struct sysl_fiber_context {
  void *sp;
  void *lo;
  size_t len;
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

__attribute__((used)) static void sysl_fiber_first_entered(void) {
  sysl_fiber_context *from = *sysl_fiber_from_slot();
  const void *lo = 0;
  size_t len = 0;

  __sanitizer_finish_switch_fiber(0, &lo, &len);
  if (from) {
    from->lo = (void *)lo;
    from->len = len;
  }
}
#endif

#if defined(__aarch64__)

/* `sysl_fiber_swap(&from->sp, &to->sp)`: 176 bytes pushed, the pointer to them saved, the other
 * stack's taken, and the same 176 popped from it. */
__attribute__((naked)) static void sysl_fiber_swap(void **from, void **to) {
  __asm__ volatile(
      "sub sp, sp, #176\n"
      "stp x19, x20, [sp, #0]\n"
      "stp x21, x22, [sp, #16]\n"
      "stp x23, x24, [sp, #32]\n"
      "stp x25, x26, [sp, #48]\n"
      "stp x27, x28, [sp, #64]\n"
      "stp x29, x30, [sp, #80]\n"
      "stp d8, d9, [sp, #96]\n"
      "stp d10, d11, [sp, #112]\n"
      "stp d12, d13, [sp, #128]\n"
      "stp d14, d15, [sp, #144]\n"
      "mrs x9, fpcr\n"
      "str x9, [sp, #160]\n"
      "mov x9, sp\n"
      "str x9, [x0]\n"
      "ldr x9, [x1]\n"
      "mov sp, x9\n"
      "ldr x9, [sp, #160]\n"
      "mrs x10, fpcr\n"
      "cmp x9, x10\n"
      "b.eq 1f\n"
      "msr fpcr, x9\n"
      "1:\n"
      "ldp x19, x20, [sp, #0]\n"
      "ldp x21, x22, [sp, #16]\n"
      "ldp x23, x24, [sp, #32]\n"
      "ldp x25, x26, [sp, #48]\n"
      "ldp x27, x28, [sp, #64]\n"
      "ldp x29, x30, [sp, #80]\n"
      "ldp d8, d9, [sp, #96]\n"
      "ldp d10, d11, [sp, #112]\n"
      "ldp d12, d13, [sp, #128]\n"
      "ldp d14, d15, [sp, #144]\n"
      "add sp, sp, #176\n"
      "ret\n");
}

/* Where a new fiber's first switch returns to: the entry in x19, its argument in x20, and a zero
 * frame pointer in x29, so the frame chain a debugger or a sampler walks ends here. The return
 * address is marked undefined for an unwinder that reads the tables instead. */
__attribute__((naked, used)) static void sysl_fiber_boot(void) {
  __asm__ volatile(
      ".cfi_undefined x30\n"
#if SYSL_FIBER_ASAN
      "bl " SYSL_SYM(sysl_fiber_first_entered) "\n"
#endif
      "mov x0, x20\n"
      "blr x19\n"
      "bl " SYSL_SYM(sysl_fiber_returned) "\n"
      "brk #1\n");
}

void sysl_fiber_start(sysl_fiber_context *c, char *top, char *lo, size_t len, void (*entry)(void *),
                      void *arg) {
  uint64_t *f = (uint64_t *)(top - 176);
  uint64_t fpcr;

  memset(f, 0, 176);
  f[0] = (uint64_t)entry;      /* x19 */
  f[1] = (uint64_t)arg;        /* x20 */
  f[10] = 0;                   /* x29: the end of the frame chain */
  f[11] = (uint64_t)&sysl_fiber_boot; /* x30 */
  __asm__ volatile("mrs %0, fpcr" : "=r"(fpcr));
  f[20] = fpcr;
  c->sp = f;
  c->lo = lo;
  c->len = len;
}

#else /* x86_64 */

/* `sysl_fiber_swap(&from->sp, &to->sp)`: six registers and the two control words pushed, the pointer
 * saved, the other stack's taken, and the same popped from it. */
__attribute__((naked)) static void sysl_fiber_swap(void **from, void **to) {
  __asm__ volatile(
      "pushq %rbp\n"
      "pushq %rbx\n"
      "pushq %r12\n"
      "pushq %r13\n"
      "pushq %r14\n"
      "pushq %r15\n"
      "subq $8, %rsp\n"
      "stmxcsr (%rsp)\n"
      "fnstcw 4(%rsp)\n"
      "movq %rsp, (%rdi)\n"
      "movq (%rsi), %rsp\n"
      "ldmxcsr (%rsp)\n"
      "fldcw 4(%rsp)\n"
      "addq $8, %rsp\n"
      "popq %r15\n"
      "popq %r14\n"
      "popq %r13\n"
      "popq %r12\n"
      "popq %rbx\n"
      "popq %rbp\n"
      "retq\n");
}

/* Where a new fiber's first switch returns to: the entry in r12, its argument in r13, rbp zero so
 * the frame chain ends here, and the stack 16-byte aligned at the call. */
__attribute__((naked, used)) static void sysl_fiber_boot(void) {
  __asm__ volatile(
      ".cfi_undefined %rip\n"
#if SYSL_FIBER_ASAN
      "call " SYSL_SYM(sysl_fiber_first_entered) "\n"
#endif
      "movq %r13, %rdi\n"
      "callq *%r12\n"
      "call " SYSL_SYM(sysl_fiber_returned) "\n"
      "ud2\n");
}

void sysl_fiber_start(sysl_fiber_context *c, char *top, char *lo, size_t len, void (*entry)(void *),
                      void *arg) {
  /* From low to high: the control words, r15, r14, r13, r12, rbx, rbp, and the return address, which
   * sits just below `top` so the boot code starts with the stack 16-byte aligned. */
  uint64_t *f = (uint64_t *)(top - 64);
  uint32_t mxcsr;
  uint16_t cw;

  memset(f, 0, 64);
  __asm__ volatile("stmxcsr %0" : "=m"(mxcsr));
  __asm__ volatile("fnstcw %0" : "=m"(cw));
  f[0] = (uint64_t)mxcsr | ((uint64_t)cw << 32);
  f[3] = (uint64_t)arg;   /* r13 */
  f[4] = (uint64_t)entry; /* r12 */
  f[6] = 0;               /* rbp: the end of the frame chain */
  f[7] = (uint64_t)&sysl_fiber_boot;
  c->sp = f;
  c->lo = lo;
  c->len = len;
}

#endif

/* The switch a program calls. Its first switch on a thread gives that thread a signal stack, so an
 * overflow on any fiber it runs is reported rather than silent. */
static _Thread_local int sysl_fiber_carrier_ready;

void sysl_fiber_switch(sysl_fiber_context *from, sysl_fiber_context *to) {
  if (!sysl_fiber_carrier_ready) {
    sysl_stack_guard_thread();
    sysl_fiber_carrier_ready = 1;
  }

#if SYSL_FIBER_ASAN
  {
    void *fake = 0;
    const void *lo = 0;
    size_t len = 0;

    *sysl_fiber_from_slot() = from;
    __sanitizer_start_switch_fiber(&fake, to->lo, to->len);
    sysl_fiber_swap(&from->sp, &to->sp);
    __sanitizer_finish_switch_fiber(fake, &lo, &len);

    {
      sysl_fiber_context *left = *sysl_fiber_from_slot();

      if (left) {
        left->lo = (void *)lo;
        left->len = len;
      }
    }
  }
#else
  sysl_fiber_swap(&from->sp, &to->sp);
#endif
}

#endif
