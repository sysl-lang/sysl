/* The error return trace: where a failure was made, and every `?` that handed it one frame up.
 *
 * **What is recorded is the address of a constant**, `file:line:col in function`, NUL-terminated,
 * which the compiler lays down beside the code that records it -- never a return address. It prints
 * on a board with nothing to symbolize against, and it is the same at every optimization level.
 *
 * **The ring is one per thread on a hosted target** (`_Thread_local`), so a failure on one thread
 * never lands in another's report, and **reached through a pointer on a freestanding one**, where
 * there is no thread to tell apart and often no thread-local storage to put it in: the pointer starts
 * at one static store, and a kernel points it at each task's own. It is fixed in size: thirty-two
 * sites and a count, and nothing here allocates. The first thirty-two are kept -- the origin end is
 * where the bug is -- and the count goes on past them, so a report can say how many it dropped.
 *
 * **The compiler calls three of these**: `push` on every failure path that records, and `mark` and
 * `reset` around a statement that handles a failure, which takes the frames that failure left back
 * out. The library reads the rest. A build with traces switched off (`error_traces = false`,
 * `--no-error-traces`) calls none of them and links none of this.
 *
 * **`sysl_error_trace_report` is a weak default**, as `sysl_bounds_fail` is: on a hosted target it
 * writes the trace to standard error with `write`, and on a freestanding one it writes nothing. A
 * program defines its own under the same symbol -- a board writing to its UART -- and reads the trace
 * through `sysl_error_trace_len` and `sysl_error_trace_site`.
 */

#include <stddef.h>
#include <stdint.h>

#define SYSL_TRACE_SITES 32

/* One trace's storage. `sysl.debug.ErrorTraceStore` is laid out the same, so a kernel can hold one in
 * each task's control block. */
typedef struct sysl_trace_store {
    uint32_t count;
    const char *sites[SYSL_TRACE_SITES];
} sysl_trace_store;

#if __STDC_HOSTED__
/* **Hosted: the trace is the thread's own object**, reached directly. Making a store current
 * (`sysl_error_trace_use`) copies the live trace out into the store that was current and the new one
 * in, so recording and pruning never pay for the indirection. `sysl_trace_owner` is the store whose
 * contents are live, or null while the thread's own are; `sysl_trace_own` holds the thread's own
 * while another is current. */
static _Thread_local sysl_trace_store sysl_trace;
static _Thread_local sysl_trace_store *sysl_trace_owner;
static _Thread_local sysl_trace_store sysl_trace_own;
#define SYSL_TRACE (sysl_trace)
#else
/* **Freestanding: the trace is reached through a pointer**, `sysl_error_trace_current`, which starts
 * at a static store, so an image with one flow of control behaves as though the store were the
 * trace. A kernel gives each task a store of its own and points this at it on a context switch --
 * through `sysl_error_trace_use`, or by writing the pointer from its switch code. Both are constant
 * data, laid down in the image with nothing to run at start-up. */
static sysl_trace_store sysl_trace_default;
sysl_trace_store *sysl_error_trace_current = &sysl_trace_default;
#define SYSL_TRACE (*sysl_error_trace_current)
#endif

void sysl_error_trace_push(const char *at) {
    sysl_trace_store *t = &SYSL_TRACE;
    uint32_t n = t->count;

    if (n < SYSL_TRACE_SITES) t->sites[n] = at;
    if (n != UINT32_MAX) t->count = n + 1;
}

uint32_t sysl_error_trace_mark(void) {
    return SYSL_TRACE.count;
}

void sysl_error_trace_reset(uint32_t n) {
    SYSL_TRACE.count = n;
}

/* How many sites were recorded, the dropped ones included. */
uint32_t sysl_error_trace_len(void) {
    return SYSL_TRACE.count;
}

/* The `i`th site kept, origin first, or null past the ones kept. */
const char *sysl_error_trace_site(uint32_t i) {
    sysl_trace_store *t = &SYSL_TRACE;

    return i < t->count && i < SYSL_TRACE_SITES ? t->sites[i] : 0;
}

void sysl_error_trace_clear(void) {
    SYSL_TRACE.count = 0;
}

/* Empties a store, so it can be made current with no trace in it. */
void sysl_error_trace_init(sysl_trace_store *s) {
    s->count = 0;
}

/* Makes `next` the trace that recording and reading reach -- null meaning the program's own (the
 * thread's own, hosted) -- and answers the store that was, which is never null. */
sysl_trace_store *sysl_error_trace_use(sysl_trace_store *next) {
#if __STDC_HOSTED__
    sysl_trace_store *prev = sysl_trace_owner ? sysl_trace_owner : &sysl_trace_own;

    if (!next) next = &sysl_trace_own;
    if (next == prev) return prev;

    *prev = sysl_trace;
    sysl_trace = *next;
    sysl_trace_owner = next == &sysl_trace_own ? 0 : next;
    return prev;
#else
    sysl_trace_store *prev = sysl_error_trace_current;

    sysl_error_trace_current = next ? next : &sysl_trace_default;
    return prev;
#endif
}

#if __STDC_HOSTED__
#include <unistd.h>

static void sysl_trace_write(const char *p, size_t n) {
    while (n > 0) {
        ssize_t k = write(2, p, n);

        if (k <= 0) return;
        p += k;
        n -= (size_t)k;
    }
}

static void sysl_trace_text(const char *s) {
    size_t n = 0;

    while (s[n]) n++;
    sysl_trace_write(s, n);
}

__attribute__((weak)) void sysl_error_trace_report(void) {
    uint32_t n = sysl_trace.count;

    if (n == 0) return;

    sysl_trace_text("error return trace (origin first):\n");

    for (uint32_t i = 0; i < n && i < SYSL_TRACE_SITES; i++) {
        sysl_trace_text("  ");
        sysl_trace_text(sysl_trace.sites[i]);
        sysl_trace_text("\n");
    }

    if (n > SYSL_TRACE_SITES) {
        char digits[16];
        int at = sizeof digits;
        uint32_t more = n - SYSL_TRACE_SITES;

        do {
            digits[--at] = (char)('0' + more % 10);
            more /= 10;
        } while (more > 0);

        sysl_trace_text("  \xe2\x80\xa6 and ");
        sysl_trace_write(digits + at, sizeof digits - (size_t)at);
        sysl_trace_text(" more\n");
    }
}
#else
__attribute__((weak)) void sysl_error_trace_report(void) {
}
#endif
