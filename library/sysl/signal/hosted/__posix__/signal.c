/* The handler `sysl.signal.watch` installs, and the dispositions around it: what `sysl.signal.hosted`
 * answers `sysl.signal.sys`'s hooks with on a POSIX host. Each function here answers 0 or an `errno`
 * (`sysl_sig_posix_arrivals` a count), and the hook beside it negates that.
 *
 * It is C because a signal handler has to be: it runs between two instructions of whatever the
 * program was doing, and the one thing it does -- add one to a count with a lock-free atomic -- is the
 * one thing C promises is safe there. `struct sigaction`'s layout and `SA_RESTART` differ between the
 * C libraries too, and are named once here rather than transcribed.
 *
 * It sits under `__posix__` so that it is absent on a target with no signals, which is what lets the
 * module go on being compiled for every target.
 */

#include <errno.h>
#include <signal.h>
#include <stdatomic.h>
#include <string.h>
#include <sys/types.h>

/* One past the highest signal number any C library this builds for has: 64 on Linux, 31 on a BSD. */
#define SYSL_SIG_LIMIT 65

static atomic_long sysl_sig_counts[SYSL_SIG_LIMIT];
static struct sigaction sysl_sig_before[SYSL_SIG_LIMIT];
static int sysl_sig_kept[SYSL_SIG_LIMIT];

/* The whole of what runs when a watched signal arrives. */
static void sysl_sig_count(int sig) {
    if (sig > 0 && sig < SYSL_SIG_LIMIT)
        atomic_fetch_add_explicit(&sysl_sig_counts[sig], 1, memory_order_relaxed);
}

int sysl_sig_posix_watch(int sig) {
    if (sig <= 0 || sig >= SYSL_SIG_LIMIT) return EINVAL;

    struct sigaction now;
    struct sigaction was;

    memset(&now, 0, sizeof now);
    now.sa_handler = sysl_sig_count;
    now.sa_flags = SA_RESTART;
    sigemptyset(&now.sa_mask);

    if (sigaction(sig, &now, &was) != 0) return errno;

    /* Asked again for a signal it already counts, `was` is this handler: the first one is kept. */
    if (!sysl_sig_kept[sig]) {
        sysl_sig_before[sig] = was;
        sysl_sig_kept[sig] = 1;
    }

    return 0;
}

long sysl_sig_posix_arrivals(int sig) {
    if (sig <= 0 || sig >= SYSL_SIG_LIMIT) return -EINVAL;

    return atomic_load_explicit(&sysl_sig_counts[sig], memory_order_acquire);
}

/* `how` is `sysl.signal.sys`'s: 0 the default action, 1 ignored, 2 what `watch` replaced. */
int sysl_sig_posix_disposition(int sig, int how) {
    if (sig <= 0 || sig >= SYSL_SIG_LIMIT) return EINVAL;

    if (how == 2) {
        if (!sysl_sig_kept[sig]) return 0;
        if (sigaction(sig, &sysl_sig_before[sig], NULL) != 0) return errno;

        sysl_sig_kept[sig] = 0;
        return 0;
    }

    struct sigaction now;

    memset(&now, 0, sizeof now);
    now.sa_handler = how == 1 ? SIG_IGN : SIG_DFL;
    sigemptyset(&now.sa_mask);

    return sigaction(sig, &now, NULL) != 0 ? errno : 0;
}

int sysl_sig_posix_send(long pid, int sig) {
    if ((pid_t)pid != pid) return ESRCH;

    return kill((pid_t)pid, sig) != 0 ? errno : 0;
}

int sysl_sig_posix_raise(int sig) {
    return raise(sig) != 0 ? errno : 0;
}
