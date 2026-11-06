#include <errno.h>
#include <limits.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

// `sysl.io.poll`'s readiness set over the kernel's own: kqueue on Darwin and the BSDs, epoll on Linux.
//
// **The kernel's event record does not cross into sysl.** `struct kevent` is 32 bytes on Darwin and 64
// on FreeBSD, and `struct epoll_event` is packed to 12 bytes on x86-64 and laid out in 16 everywhere
// else, so a sysl transcription would be right on one machine. What crosses is
// `struct sysl_poll_event`, the layout `sysl.io.poll.Event` declares; the kernel is handed the caller's
// own array as scratch, as many of its records as fit in the bytes, and each record is rewritten in
// place as an event, **from the last to the first** -- an event is at least as long as a record here,
// so the one being written never reaches a record not yet read. That is what lets a wait allocate
// nothing.
//
// **A wake is never one of the events.** It is the set's own registration -- kqueue's `EVFILT_USER`,
// or an `eventfd` under epoll -- and its token is `UINT64_MAX`, which `register` refuses a program, so
// a wait drops it while compacting what it found.
//
// **A signal does not end a wait**: `EINTR` asks again for what is left of the time, read off the
// monotonic clock, as `wait.c`'s single-descriptor wait does.
//
// Every function answers zero, a count or a descriptor, or an `errno` negated.

struct sysl_poll_event {
    uint64_t token;
    _Bool readable;
    _Bool writable;
    _Bool hangup;
    _Bool error;
    uint8_t spare[20];
};

_Static_assert(sizeof(struct sysl_poll_event) == 32, "sysl.io.poll.Event is 32 bytes");

#define SYSL_POLL_WAKE_TOKEN UINT64_MAX

static long long sysl_poll_now_us(void) {
    struct timespec t;

    clock_gettime(CLOCK_MONOTONIC, &t);
    return (long long) t.tv_sec * 1000000LL + t.tv_nsec / 1000;
}

// How many kernel records of `size` bytes fit in `room` events, at most `INT_MAX`.
static int sysl_poll_capacity(size_t room, size_t size) {
    size_t cap = room * sizeof(struct sysl_poll_event) / size;

    if (cap > INT_MAX) cap = INT_MAX;
    return (int) cap;
}

// Drops the wake from `n` events at `into`, answering how many are left.
static long long sysl_poll_compact(struct sysl_poll_event *into, int n) {
    long long kept = 0;

    for (int i = 0; i < n; i++) {
        if (into[i].token == SYSL_POLL_WAKE_TOKEN) continue;
        if (kept != i) into[kept] = into[i];
        kept++;
    }

    return kept;
}

static void sysl_poll_set(struct sysl_poll_event *e, uint64_t token, int r, int w, int hup, int err) {
    struct sysl_poll_event out;

    memset(&out, 0, sizeof out);
    out.token = token;
    out.readable = r != 0;
    out.writable = w != 0;
    out.hangup = hup != 0;
    out.error = err != 0;
    memcpy(e, &out, sizeof out);
}

#if defined(__APPLE__) || defined(__FreeBSD__) || defined(__OpenBSD__) || defined(__NetBSD__) || \
    defined(__DragonFly__)

#include <fcntl.h>
#include <sys/event.h>
#include <sys/types.h>

// The wake's identity among the set's user events; it shares no namespace with descriptors.
#define SYSL_POLL_WAKE_IDENT 1

static int sysl_kq_change(int kq, uintptr_t ident, short filter, unsigned short flags, unsigned fflags,
                          uint64_t token) {
    struct kevent ch;

    EV_SET(&ch, ident, filter, flags, fflags, 0, (void *) (uintptr_t) token);

    for (;;) {
        if (kevent(kq, &ch, 1, NULL, 0, NULL) == 0) return 0;
        if (errno != EINTR) return errno;
    }
}

int sysl_io_posix_poll_open(int32_t *wake) {
    int kq = kqueue();

    if (kq < 0) return -errno;

    fcntl(kq, F_SETFD, FD_CLOEXEC);

    int e = sysl_kq_change(kq, SYSL_POLL_WAKE_IDENT, EVFILT_USER, EV_ADD | EV_CLEAR, 0,
                           SYSL_POLL_WAKE_TOKEN);

    if (e != 0) {
        close(kq);
        return -e;
    }

    *wake = -1;
    return kq;
}

// Register (`op` 0), change (1) or remove (2). **A change is a removal of both filters and an addition
// of the wanted ones**, because a knote's clear and dispatch flags are fixed when it is added; a
// level-triggered filter added again reports the descriptor's state at once, so nothing is missed in
// between. Removing a filter that is not there is not a failure unless neither was.
int sysl_io_posix_poll_control(int kq, int fd, int op, int interest, int mode, uint64_t token) {
    if (fd < 0) return -EBADF;

    if (op != 0) {
        int r = sysl_kq_change(kq, (uintptr_t) fd, EVFILT_READ, EV_DELETE, 0, 0);
        int w = sysl_kq_change(kq, (uintptr_t) fd, EVFILT_WRITE, EV_DELETE, 0, 0);

        if (r == EBADF || w == EBADF) return -EBADF;
        if (r == ENOENT && w == ENOENT) return -ENOENT;
        if (r != 0 && r != ENOENT) return -r;
        if (w != 0 && w != ENOENT) return -w;
        if (op == 2) return 0;
    }

    unsigned short flags = EV_ADD | EV_ENABLE;

    if (mode == 1) flags |= EV_DISPATCH;
    if (mode == 2) flags |= EV_CLEAR;

    if (interest & 1) {
        int e = sysl_kq_change(kq, (uintptr_t) fd, EVFILT_READ, flags, 0, token);

        if (e != 0) return -e;
    }

    if (interest & 2) {
        int e = sysl_kq_change(kq, (uintptr_t) fd, EVFILT_WRITE, flags, 0, token);

        if (e != 0) {
            if (interest & 1) sysl_kq_change(kq, (uintptr_t) fd, EVFILT_READ, EV_DELETE, 0, 0);
            return -e;
        }
    }

    return 0;
}

long long sysl_io_posix_poll_wait(int kq, int wake, void *into, size_t room, long long micros) {
    (void) wake;

    if (room == 0) return 0;

    int cap = sysl_poll_capacity(room, sizeof(struct kevent));
    long long deadline = micros < 0 ? 0 : sysl_poll_now_us() + micros;
    long long left = micros;
    int n;

    for (;;) {
        struct timespec t;

        if (left >= 0) {
            t.tv_sec = (time_t) (left / 1000000LL);
            t.tv_nsec = (long) (left % 1000000LL) * 1000L;
        }

        n = kevent(kq, NULL, 0, (struct kevent *) into, cap, left < 0 ? NULL : &t);

        if (n >= 0) break;
        if (errno != EINTR) return -errno;

        if (micros >= 0) {
            left = deadline - sysl_poll_now_us();
            if (left < 0) left = 0;
        }
    }

    struct sysl_poll_event *out = (struct sysl_poll_event *) into;

    for (int i = n - 1; i >= 0; i--) {
        struct kevent k;

        memcpy(&k, (char *) into + (size_t) i * sizeof(struct kevent), sizeof k);

        if (k.filter == EVFILT_USER) {
            sysl_poll_set(&out[i], SYSL_POLL_WAKE_TOKEN, 0, 0, 0, 0);
            continue;
        }

        int eof = (k.flags & EV_EOF) != 0;
        int err = (k.flags & EV_ERROR) != 0 || (eof && k.fflags != 0);

        sysl_poll_set(&out[i], (uint64_t) (uintptr_t) k.udata, k.filter == EVFILT_READ,
                      k.filter == EVFILT_WRITE, eof, err);
    }

    return sysl_poll_compact(out, n);
}

int sysl_io_posix_poll_wake(int kq, int wake) {
    (void) wake;
    return -sysl_kq_change(kq, SYSL_POLL_WAKE_IDENT, EVFILT_USER, 0, NOTE_TRIGGER, 0);
}

#elif defined(__linux__)

#include <sys/epoll.h>
#include <sys/eventfd.h>

int sysl_io_posix_poll_open(int32_t *wake) {
    int ep = epoll_create1(EPOLL_CLOEXEC);

    if (ep < 0) return -errno;

    int ev = eventfd(0, EFD_NONBLOCK | EFD_CLOEXEC);

    if (ev < 0) {
        int e = errno;

        close(ep);
        return -e;
    }

    struct epoll_event w;

    memset(&w, 0, sizeof w);
    w.events = EPOLLIN;
    w.data.u64 = SYSL_POLL_WAKE_TOKEN;

    if (epoll_ctl(ep, EPOLL_CTL_ADD, ev, &w) != 0) {
        int e = errno;

        close(ev);
        close(ep);
        return -e;
    }

    *wake = ev;
    return ep;
}

// Register (`op` 0), change (1) or remove (2). Readable asks for `EPOLLRDHUP` too, so a peer's
// shutdown is a hang-up here as `EV_EOF` makes it one under kqueue.
int sysl_io_posix_poll_control(int ep, int fd, int op, int interest, int mode, uint64_t token) {
    if (fd < 0) return -EBADF;

    struct epoll_event e;

    memset(&e, 0, sizeof e);
    e.events = ((interest & 1) ? EPOLLIN | EPOLLRDHUP : 0) | ((interest & 2) ? EPOLLOUT : 0) |
               (mode == 1 ? EPOLLONESHOT : 0) | (mode == 2 ? EPOLLET : 0);
    e.data.u64 = token;

    int how = op == 0 ? EPOLL_CTL_ADD : op == 1 ? EPOLL_CTL_MOD : EPOLL_CTL_DEL;

    return epoll_ctl(ep, how, fd, &e) == 0 ? 0 : -errno;
}

long long sysl_io_posix_poll_wait(int ep, int wake, void *into, size_t room, long long micros) {
    if (room == 0) return 0;

    int cap = sysl_poll_capacity(room, sizeof(struct epoll_event));
    long long deadline = micros < 0 ? 0 : sysl_poll_now_us() + micros;
    long long left = micros;
    int n;

    // `epoll_wait` counts milliseconds in an `int`: the wait is rounded up, so it never answers early,
    // and a longer one is asked for in pieces.
    for (;;) {
        int ms = -1;
        int whole = 1;

        if (left >= 0) {
            long long m = (left + 999) / 1000;

            whole = m <= INT_MAX;
            ms = whole ? (int) m : INT_MAX;
        }

        n = epoll_wait(ep, (struct epoll_event *) into, cap, ms);

        if (n > 0 || (n == 0 && whole)) break;
        if (n < 0 && errno != EINTR) return -errno;

        if (micros >= 0) {
            left = deadline - sysl_poll_now_us();
            if (left < 0) left = 0;
        }
    }

    struct sysl_poll_event *out = (struct sysl_poll_event *) into;

    for (int i = n - 1; i >= 0; i--) {
        struct epoll_event k;

        memcpy(&k, (char *) into + (size_t) i * sizeof(struct epoll_event), sizeof k);

        if (k.data.u64 == SYSL_POLL_WAKE_TOKEN) {
            uint64_t drained;
            ssize_t got = read(wake, &drained, sizeof drained);

            (void) got;
            sysl_poll_set(&out[i], SYSL_POLL_WAKE_TOKEN, 0, 0, 0, 0);
            continue;
        }

        sysl_poll_set(&out[i], k.data.u64, (k.events & EPOLLIN) != 0 || (k.events & EPOLLRDHUP) != 0,
                      (k.events & EPOLLOUT) != 0, (k.events & (EPOLLHUP | EPOLLRDHUP)) != 0,
                      (k.events & EPOLLERR) != 0);
    }

    return sysl_poll_compact(out, n);
}

// A full counter is already a pending wake, so `EAGAIN` is success.
int sysl_io_posix_poll_wake(int ep, int wake) {
    (void) ep;

    uint64_t one = 1;

    for (;;) {
        if (write(wake, &one, sizeof one) == (ssize_t) sizeof one) return 0;
        if (errno == EAGAIN) return 0;
        if (errno != EINTR) return -errno;
    }
}

#else

int sysl_io_posix_poll_open(int32_t *wake) {
    *wake = -1;
    return -ENOSYS;
}

int sysl_io_posix_poll_control(int poller, int fd, int op, int interest, int mode, uint64_t token) {
    (void) poller, (void) fd, (void) op, (void) interest, (void) mode, (void) token;
    return -ENOSYS;
}

long long sysl_io_posix_poll_wait(int poller, int wake, void *into, size_t room, long long micros) {
    (void) poller, (void) wake, (void) into, (void) room, (void) micros;
    return -ENOSYS;
}

int sysl_io_posix_poll_wake(int poller, int wake) {
    (void) poller, (void) wake;
    return -ENOSYS;
}

#endif

int sysl_io_posix_poll_close(int poller, int wake) {
    int e = close(poller) == 0 ? 0 : errno;

    if (wake >= 0 && close(wake) != 0 && e == 0) e = errno;

    return -e;
}
