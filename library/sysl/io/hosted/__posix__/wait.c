#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <poll.h>
#include <sys/select.h>
#include <time.h>

// Waiting for one descriptor, as a descriptor, a direction and a count of microseconds in and one
// integer out.
//
// **`struct pollfd` and `nfds_t` do not cross into sysl**, for the reason `sleep.c` in
// `sysl.posix.time` keeps `struct timespec` on this side: the layout is the platform's. `nfds_t` is
// an `unsigned int` on Darwin and an `unsigned long` on Linux, and a caller-built `pollfd` is a
// shape only C spells right on both. Three numbers in and one out is the whole interface.
//
// **Darwin's `poll` does not wait on a device**: a terminal, `/dev/null` or any other character
// device answers `POLLNVAL` at once, as if it were not open. Its `select` does wait on them, so a
// `POLLNVAL` for a descriptor `fcntl` says IS open is asked again through `select`. Everywhere else
// the second path is never taken.

static long long sysl_io_now_us(void) {
    struct timespec t;

    clock_gettime(CLOCK_MONOTONIC, &t);
    return (long long) t.tv_sec * 1000000LL + t.tv_nsec / 1000;
}

// One `poll` of at most `ms` milliseconds: 1, 0, -1 for "not open", or -2 with `errno` set.
static int sysl_io_poll_once(int fd, int writable, int ms) {
    struct pollfd p;

    p.fd = fd;
    p.events = writable ? POLLOUT : POLLIN;
    p.revents = 0;

    int n = poll(&p, 1, ms);

    if (n < 0) return -2;
    if (n == 0) return 0;
    if (p.revents & POLLNVAL) return -1;

    return 1;
}

// The same through `select`, for a descriptor `poll` cannot wait on: 1, 0, or -2 with `errno` set.
static int sysl_io_select_once(int fd, int writable, long long us) {
    if (fd >= FD_SETSIZE) {
        errno = EINVAL;
        return -2;
    }

    fd_set set;
    struct timeval t;

    FD_ZERO(&set);
    FD_SET(fd, &set);
    t.tv_sec = (time_t) (us / 1000000LL);
    t.tv_usec = (suseconds_t) (us % 1000000LL);

    int n = writable ? select(fd + 1, NULL, &set, NULL, &t) : select(fd + 1, &set, NULL, NULL, &t);

    if (n < 0) return -2;

    return n > 0 ? 1 : 0;
}

// Answers 1 when `fd` is ready -- readable, or writable where `writable` is non-zero -- within
// `micros` microseconds, 0 when the time ran out first, or an `errno` negated.
//
// **The wait is rounded UP to whole milliseconds** where `poll` asks it, so it never answers "not
// yet" before the time asked for has passed, and a wait longer than `poll` can be asked for at once
// is asked for in pieces. **A signal does not cut it short**: `EINTR` asks again for what is left of
// the time, read off the monotonic clock, so a wait that is interrupted ends when it would have.
//
// **A hang-up or an error on the descriptor is readiness**, because the call that follows is what
// reports it: a pipe whose writers have all gone reads as its end, and a write to one whose reader has
// gone answers `EPIPE`. Only a descriptor that is not open is a failure here, answered as `EBADF`; a
// negative one, which `poll` would silently skip, is `EBADF` too.
int sysl_io_posix_wait(int fd, int writable, long long micros) {
    if (fd < 0) return -EBADF;

    if (micros < 0) micros = 0;

    long long deadline = sysl_io_now_us() + micros;
    long long left = micros;
    int by_select = 0;

    for (;;) {
        int n;
        int whole = 1;

        if (by_select) {
            // `select` takes a `timeval`, so it is asked in pieces of at most a day.
            long long piece = left > 86400000000LL ? 86400000000LL : left;

            whole = piece == left;
            n = sysl_io_select_once(fd, writable, piece);
        } else {
            long long ms = (left + 999) / 1000;
            int piece = ms > INT_MAX ? INT_MAX : (int) ms;

            whole = piece == ms;
            n = sysl_io_poll_once(fd, writable, piece);

            if (n == -1) {
                if (fcntl(fd, F_GETFD) == -1) return -EBADF;

                by_select = 1;
                continue;
            }
        }

        if (n == 1) return 1;
        if (n == -2 && errno != EINTR) return -errno;

        // Timed out, or interrupted: ask again for whatever of the wait is still owed.
        if (n == 0 && whole) return 0;

        left = deadline - sysl_io_now_us();

        if (left <= 0) {
            if (n == 0) return 0;

            // An interruption at the very end still asks once, with no wait, so that a descriptor
            // that became ready in time is not reported as having timed out.
            left = 0;
        }
    }
}
