/* What a sysl program says when it runs out of stack, and what it recovers of its own output.
 *
 * A program that recurses without bound takes SIGSEGV and, with no handler, says NOTHING AT ALL --
 * not even the lines it had already printed, which sit in stdio's buffer and go with the process.
 * A reader then has a segfault and no reason to suspect recursion depth rather than a wild pointer,
 * which is a much more alarming place to go looking. Rust, Go and Java each print their own; clang
 * is the one language on that list nobody would hold up as the model.
 *
 * The whole of it is here rather than in sysl because there is nothing for sysl to say: an
 * alternate signal stack, a `sigaction`, and the address arithmetic that tells an overflow from a
 * bad pointer are three POSIX calls and a comparison. This file is selected by the `__posix__`
 * directory it sits in, so a freestanding target compiles none of it and links none of it --
 * there is no signal to catch on a machine with no operating system.
 *
 * `Codegen.genMain` emits the call to `sysl_install_stack_guard` as the first thing a program does,
 * and only for a target whose operating system is POSIX. A `build-c` archive has no entry point and
 * so installs nothing: what a project links its own `main` to is that project's business.
 */

/* **`pthread_getattr_np` is a GNU extension and glibc hides it behind this**, so a file without it
 * compiles cleanly here and fails on Linux with a call to an undeclared function — which clang
 * treats as an error rather than a warning. macOS needs nothing for its own pair, which is exactly
 * why the omission would have been invisible until the release's Linux tarballs were built.
 *
 * It has to come before every include: a header included first has already made its decisions. */
#define _GNU_SOURCE

#include <signal.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <pthread.h>

/* The alternate stack the handler runs on. The ordinary one is what has just run out, so a handler
 * without this is entered on a stack that cannot hold it and faults again -- which is why an
 * overflow is silent by default rather than merely undiagnosed.
 *
 * `SIGSTKSZ` is not a constant on every libc (glibc reads it from the kernel), so it cannot size a
 * static array; a fixed 64 KiB is comfortably above every value it takes and is paid once per
 * process. */
static char sysl_alt_stack[65536];

/* The bottom of the main thread's stack, and how far below it a fault still counts as an overflow.
 * Both are settled at install time, where the ordinary POSIX calls for them are safe to make. */
static char *sysl_stack_low = 0;
static char *sysl_stack_high = 0;

/* A frame larger than one page can step over the guard page entirely and fault further down, so the
 * window is wider than the guard itself. A megabyte is far enough to catch a large frame and near
 * enough that a wild pointer is unlikely to land in it. */
#define SYSL_GUARD_SLACK (1024 * 1024)

static void sysl_say(const char *s) {
  /* `write` is async-signal-safe and `fprintf` is not. What this says has to come out even if the
   * flush below cannot, which is why it goes first and by the safe call. */
  ssize_t ignored = write(2, s, strlen(s));

  (void)ignored;
}

static void sysl_fault(int sig, siginfo_t *info, void *ctx) {
  char *at = info ? (char *)info->si_addr : 0;
  int overflowed = sysl_stack_low != 0 && at >= sysl_stack_low - SYSL_GUARD_SLACK &&
                   at < sysl_stack_high;

  (void)sig;
  (void)ctx;

  if (overflowed)
    sysl_say("sysl: this program has overflowed its stack -- a recursion with no base case, or a "
             "walk over a structure that contains itself\n");
  else
    sysl_say("sysl: this program faulted on an address it does not own\n");

  /* **The program's own output, recovered as far as it safely can be.** This is the second half of
   * what the silence cost: every line the program had printed was still in stdio's buffer. It is
   * last rather than first because `fflush` is not async-signal-safe -- a fault raised *inside*
   * stdio would deadlock on its lock -- so the diagnostic above is already out by the time this is
   * attempted, and the worst case is that it is the only thing that appears. */
  fflush(stdout);

  _exit(139);
}

void sysl_install_stack_guard(void) {
  stack_t alt;
  struct sigaction sa;

  /* Where the main thread's stack is. macOS answers with the high address and the size; Linux
   * answers with the low address and the size, through an attribute object it fills. Neither is
   * portable to the other, and there is no third form to want. The three locals the second form
   * needs are declared inside it, or the first form compiles with three it never touches. */
#if defined(__APPLE__)
  sysl_stack_high = (char *)pthread_get_stackaddr_np(pthread_self());
  sysl_stack_low = sysl_stack_high - pthread_get_stacksize_np(pthread_self());
#else
  pthread_attr_t attr;
  void *base = 0;
  size_t size = 0;

  if (pthread_getattr_np(pthread_self(), &attr) == 0) {
    if (pthread_attr_getstack(&attr, &base, &size) == 0) {
      sysl_stack_low = (char *)base;
      sysl_stack_high = sysl_stack_low + size;
    }
    pthread_attr_destroy(&attr);
  }
#endif

  alt.ss_sp = sysl_alt_stack;
  alt.ss_size = sizeof sysl_alt_stack;
  alt.ss_flags = 0;

  if (sigaltstack(&alt, 0) != 0) return;

  memset(&sa, 0, sizeof sa);
  sa.sa_sigaction = sysl_fault;
  sa.sa_flags = SA_SIGINFO | SA_ONSTACK;
  sigemptyset(&sa.sa_mask);

  /* Both, because which of the two an overflow raises is the operating system's choice: Linux
   * raises SIGSEGV and macOS raises SIGBUS for some of them. A handler for one alone is a
   * diagnostic that appears on one platform. */
  sigaction(SIGSEGV, &sa, 0);
  sigaction(SIGBUS, &sa, 0);
}
