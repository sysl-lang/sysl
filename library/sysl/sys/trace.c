/* The error return trace: where a failure was made, and every `?` that handed it one frame up.
 *
 * **What is recorded is the address of a constant**, `file:line:col in function`, NUL-terminated,
 * which the compiler lays down beside the code that records it -- never a return address. It prints
 * on a board with nothing to symbolize against, and it is the same at every optimization level.
 *
 * **The ring is one per thread on a hosted target** (`_Thread_local`), so a failure on one thread
 * never lands in another's report, and **one plain object on a freestanding one**, where there is no
 * thread to tell apart and often no thread-local storage to put it in. It is fixed in size: thirty-two
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

#if __STDC_HOSTED__
#define SYSL_TRACE_LOCAL _Thread_local
#else
#define SYSL_TRACE_LOCAL
#endif

static SYSL_TRACE_LOCAL struct {
    uint32_t count;
    const char *sites[SYSL_TRACE_SITES];
} sysl_trace;

void sysl_error_trace_push(const char *at) {
    uint32_t n = sysl_trace.count;

    if (n < SYSL_TRACE_SITES) sysl_trace.sites[n] = at;
    if (n != UINT32_MAX) sysl_trace.count = n + 1;
}

uint32_t sysl_error_trace_mark(void) {
    return sysl_trace.count;
}

void sysl_error_trace_reset(uint32_t n) {
    sysl_trace.count = n;
}

/* How many sites were recorded, the dropped ones included. */
uint32_t sysl_error_trace_len(void) {
    return sysl_trace.count;
}

/* The `i`th site kept, origin first, or null past the ones kept. */
const char *sysl_error_trace_site(uint32_t i) {
    return i < sysl_trace.count && i < SYSL_TRACE_SITES ? sysl_trace.sites[i] : 0;
}

void sysl_error_trace_clear(void) {
    sysl_trace.count = 0;
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
