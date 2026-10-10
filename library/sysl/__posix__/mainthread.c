/* Whether the calling thread is the program's main one, for `@main_thread` storage.
 *
 * `@main_thread` above a module binding promises that only the main thread reaches it, which lets a
 * body that may run on another thread -- a `&sync` closure, a function read into a `&sync Fn` -- name
 * it. The compiler cannot see the promise kept, so every access from such a body calls
 * `sysl_on_main_thread` first and, where it answers no, `sysl_main_thread_fail` and then traps.
 *
 * **"The main thread" is the one the process began on**, which both platforms can name without
 * anything recorded at start-up: Darwin answers it directly, and on Linux the first thread's id is
 * the process's. So an archive a C project links answers exactly as a sysl program does, with no
 * entry point of its own to record anything in.
 *
 * It sits under `__posix__` so that a target with no threads never compiles it; the compiler refuses
 * `@main_thread` on a freestanding target.
 */

#define _GNU_SOURCE

#include <string.h>
#include <unistd.h>

#if defined(__APPLE__)
#include <pthread.h>
#else
#include <sys/syscall.h>
#endif

/* Nonzero where the calling thread is the one the process began on.
 *
 * On Linux the answer costs a system call, so each thread asks once and keeps it: whether a thread is
 * the first one never changes for as long as the thread lives.
 */
int sysl_on_main_thread(void) {
#if defined(__APPLE__)
    return pthread_main_np() != 0;
#else
    static __thread int known = 0;   /* 0 unasked, 1 the main thread, 2 another */

    if (known == 0) known = syscall(SYS_gettid) == getpid() ? 1 : 2;

    return known == 1;
#endif
}

/* Says which storage was reached from another thread, on standard error with one `write`. The trap
 * that stops the program is the caller's, at the access itself. */
void sysl_main_thread_fail(const char *name) {
    static const char tail[] = "' is @main_thread storage and was reached from another thread\n";
    char msg[512];
    size_t n = strlen(name);

    if (n > sizeof msg - sizeof tail - 1) n = sizeof msg - sizeof tail - 1;

    msg[0] = '\'';
    memcpy(msg + 1, name, n);
    memcpy(msg + 1 + n, tail, sizeof tail - 1);

    ssize_t ignored = write(2, msg, 1 + n + sizeof tail - 1);
    (void)ignored;
}
