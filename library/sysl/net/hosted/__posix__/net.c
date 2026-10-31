/* `sysl.net.sys`'s hooks over POSIX sockets: the part only C can answer.
 *
 * Four things put it here rather than in sysl --
 *
 *   - `AF_INET`, `SOCK_DGRAM`, `SHUT_WR`, `SO_RCVTIMEO` and the rest are macros, and several of them
 *     hold *different numbers* on the two platforms this builds for -- `AF_INET6` is 30 on Darwin and
 *     10 under glibc, and `SO_RCVTIMEO` is 0x1006 against 20. A transcription would compile
 *     everywhere and connect nowhere;
 *   - `struct sockaddr_in`, `sockaddr_in6` and `sockaddr_un` are layouts, and Darwin's carry a length
 *     byte that Linux's do not, so the two are not the same bytes in the same order -- and
 *     `sockaddr_un`'s `sun_path` is 104 bytes on Darwin and 108 under glibc;
 *   - non-blocking mode is a bit in `fcntl`'s flags, and `O_NONBLOCK` is another macro;
 *   - `getaddrinfo` answers a linked list of allocations that have to be freed with a call of its
 *     own, which is the one ownership shape the library has nowhere to put;
 *   - a timeout is a `struct timeval` handed to `setsockopt` by address, which is a layout again.
 *
 * **An address crosses as `struct sysl_endpoint`**, the layout `sysl.net.sys.Endpoint` declares, and
 * is turned into the platform's `sockaddr` here and nowhere else. Every function answers zero or an
 * `errno`, and `sysl.net.hosted` negates it into the hook's answer.
 */

#include <errno.h>
#include <fcntl.h>
#include <netdb.h>
#include <netinet/in.h>
#include <stdint.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <sys/types.h>
#include <sys/un.h>
#include <unistd.h>

struct sysl_endpoint {
    uint8_t octets[16];
    uint32_t scope;
    uint16_t port;
    uint16_t family;
};

_Static_assert(sizeof(struct sysl_endpoint) == 24, "sysl.net.sys.Endpoint is 24 bytes");

/* What `resolve` answers when the failure is the resolver's rather than the system's.
 *
 * `getaddrinfo` reports `EAI_*` codes, which are a numbering of their own and overlap `errno`'s --
 * `EAI_AGAIN` is 2 on Darwin, which is `ENOENT`. So they are not passed through: a resolver failure
 * that carries an `errno` (`EAI_SYSTEM`) reports that one, and everything else reports `ENOENT`,
 * which is `EAI_NONAME`'s honest meaning in the vocabulary the caller already has.
 */
#define SYSL_NET_UNRESOLVED ENOENT

/* Whether `fd` is in non-blocking mode. */
static int is_non_blocking(int fd) {
    int flags = fcntl(fd, F_GETFL);

    return flags >= 0 && (flags & O_NONBLOCK) != 0;
}

/* The `errno` a failed call on `fd` left (`fd` negative where there is no socket yet).
 *
 * **`EAGAIN` means two different things**, and which one is decided by the socket's mode. On a
 * blocking socket it is a call its timeout stopped, and is reported as what it is: `ETIMEDOUT`, the
 * one code `sysl.net.timed_out` recognises on every target. On a non-blocking socket it is a call that
 * would have had to wait, and is reported as `EAGAIN` itself, which `sysl.net.would_block` asks for.
 * The mode is read *after* the call, with `errno` saved first, because `fcntl` may overwrite it.
 */
static int failed(int fd) {
    int e = errno;

    if (e == EAGAIN || e == EWOULDBLOCK) return fd >= 0 && is_non_blocking(fd) ? EAGAIN : ETIMEDOUT;
    return e ? e : EIO;
}

/* A path of `len` bytes as the platform's Unix domain address. Answers the used length, or 0 with
 * `*err` set for a path no `sockaddr_un` can carry: an empty one or one holding a zero (`EINVAL`), or
 * one that leaves no room for the terminator `sun_path` needs (`ENAMETOOLONG`).
 */
static socklen_t to_sockaddr_un(const unsigned char *path, size_t len, struct sockaddr_un *out,
                                int *err) {
    memset(out, 0, sizeof *out);

    if (len == 0 || memchr(path, 0, len) != NULL) {
        *err = EINVAL;
        return 0;
    }

    if (len >= sizeof out->sun_path) {
        *err = ENAMETOOLONG;
        return 0;
    }

    out->sun_family = AF_UNIX;
    memcpy(out->sun_path, path, len);
#ifdef __APPLE__
    out->sun_len = sizeof *out;
#endif
    return sizeof *out;
}

/* An endpoint as the platform's address. Answers the used length, or 0 for a family it is not. */
static socklen_t to_sockaddr(const struct sysl_endpoint *e, struct sockaddr_storage *out) {
    memset(out, 0, sizeof *out);

    if (e->family == 4) {
        struct sockaddr_in *a = (struct sockaddr_in *) out;

        a->sin_family = AF_INET;
        a->sin_port = htons(e->port);
        memcpy(&a->sin_addr, e->octets, 4);
#ifdef __APPLE__
        a->sin_len = sizeof *a;
#endif
        return sizeof *a;
    }

    if (e->family == 6) {
        struct sockaddr_in6 *a = (struct sockaddr_in6 *) out;

        a->sin6_family = AF_INET6;
        a->sin6_port = htons(e->port);
        memcpy(&a->sin6_addr, e->octets, 16);
        a->sin6_scope_id = e->scope;
#ifdef __APPLE__
        a->sin6_len = sizeof *a;
#endif
        return sizeof *a;
    }

    return 0;
}

/* The platform's address as an endpoint. Answers 0, or 1 for a family an endpoint cannot carry. */
static int from_sockaddr(const struct sockaddr *sa, struct sysl_endpoint *e) {
    memset(e, 0, sizeof *e);

    if (sa->sa_family == AF_INET) {
        const struct sockaddr_in *a = (const struct sockaddr_in *) sa;

        e->family = 4;
        e->port = ntohs(a->sin_port);
        memcpy(e->octets, &a->sin_addr, 4);
        return 0;
    }

    if (sa->sa_family == AF_INET6) {
        const struct sockaddr_in6 *a = (const struct sockaddr_in6 *) sa;

        e->family = 6;
        e->port = ntohs(a->sin6_port);
        memcpy(e->octets, &a->sin6_addr, 16);
        e->scope = a->sin6_scope_id;
        return 0;
    }

    return 1;
}

/* **A send to a peer that has gone answers `EPIPE`; it never raises `SIGPIPE`.** The default action
 * of that signal is to end the process silently, and a library call that can do that to its caller
 * is not one that reports an error. Linux says so per call (`MSG_NOSIGNAL`); Darwin has no such
 * flag and says so per socket (`SO_NOSIGPIPE`), set on every socket made here and every one
 * accepted. Neither touches the process's signal disposition, so a program that handles `SIGPIPE`
 * itself still hears it for a write to a pipe or file.
 */
#ifdef MSG_NOSIGNAL
#define SEND_FLAGS MSG_NOSIGNAL
#else
#define SEND_FLAGS 0
#endif

static void quiet_pipe(int s) {
#ifdef SO_NOSIGPIPE
    int on = 1;

    setsockopt(s, SOL_SOCKET, SO_NOSIGPIPE, &on, sizeof on);
#else
    (void) s;
#endif
}

int sysl_net_posix_socket(int family, int kind, int *fd) {
    int s;

    if (family == 1)
        s = socket(AF_UNIX, kind == 1 ? SOCK_DGRAM : SOCK_STREAM, 0);
    else {
        int f = family == 6 ? AF_INET6 : AF_INET;

        s = kind == 1 ? socket(f, SOCK_DGRAM, IPPROTO_UDP) : socket(f, SOCK_STREAM, IPPROTO_TCP);
    }

    if (s < 0) return failed(-1);

    quiet_pipe(s);
    *fd = s;
    return 0;
}

int sysl_net_posix_connect(int fd, const struct sysl_endpoint *to) {
    struct sockaddr_storage a;
    socklen_t n = to_sockaddr(to, &a);

    if (n == 0) return EAFNOSUPPORT;
    if (connect(fd, (const struct sockaddr *) &a, n) != 0) return failed(fd);
    return 0;
}

int sysl_net_posix_connect_path(int fd, const unsigned char *path, size_t len) {
    struct sockaddr_un a;
    int err = 0;
    socklen_t n = to_sockaddr_un(path, len, &a, &err);

    if (n == 0) return err;
    if (connect(fd, (const struct sockaddr *) &a, n) != 0) return failed(fd);
    return 0;
}

/* Binding a path creates the socket file, so one already there -- a socket a stopped server left, or
 * anything else -- answers `EADDRINUSE` rather than being replaced: removing it is the caller's call.
 */
int sysl_net_posix_bind_path(int fd, const unsigned char *path, size_t len) {
    struct sockaddr_un a;
    int err = 0;
    socklen_t n = to_sockaddr_un(path, len, &a, &err);

    if (n == 0) return err;
    if (bind(fd, (const struct sockaddr *) &a, n) != 0) return failed(fd);
    return 0;
}

int sysl_net_posix_bind(int fd, const struct sysl_endpoint *at) {
    struct sockaddr_storage a;
    socklen_t n = to_sockaddr(at, &a);

    if (n == 0) return EAFNOSUPPORT;
    if (bind(fd, (const struct sockaddr *) &a, n) != 0) return failed(fd);
    return 0;
}

int sysl_net_posix_listen(int fd, int backlog) {
    if (listen(fd, backlog) != 0) return failed(fd);
    return 0;
}

/* **The connection answered is blocking, whatever the listener is.** A BSD hands an accepted socket
 * its listener's `O_NONBLOCK` and Linux does not, so a program that polls its listener once a frame
 * would get a connection in one mode on a Mac and the other under Linux. It is cleared here, so the
 * two agree and a caller that wants the connection non-blocking says so.
 */
int sysl_net_posix_accept(int fd, struct sysl_endpoint *peer, int *out_fd) {
    struct sockaddr_storage from;
    socklen_t n = sizeof from;

    int s = accept(fd, (struct sockaddr *) &from, &n);

    if (s < 0) return failed(fd);

    int flags = fcntl(s, F_GETFL);

    if (flags >= 0 && (flags & O_NONBLOCK) != 0) fcntl(s, F_SETFL, flags & ~O_NONBLOCK);

    quiet_pipe(s);
    from_sockaddr((const struct sockaddr *) &from, peer);
    *out_fd = s;
    return 0;
}

/* What the socket is actually bound to, which is how a caller that asked for port 0 learns which
 * port it got.
 */
int sysl_net_posix_local(int fd, struct sysl_endpoint *at) {
    struct sockaddr_storage here;
    socklen_t n = sizeof here;

    if (getsockname(fd, (struct sockaddr *) &here, &n) != 0) return failed(fd);
    if (from_sockaddr((const struct sockaddr *) &here, at) != 0) return EAFNOSUPPORT;
    return 0;
}

/* The four that move bytes answer a count, which is a `ssize_t` and not an error code -- so the count
 * goes out by address and the answer stays an `errno` like everything else here.
 */
int sysl_net_posix_send(int fd, const unsigned char *buf, size_t n, size_t *sent) {
    ssize_t k = send(fd, buf, n, SEND_FLAGS);

    if (k < 0) return failed(fd);

    *sent = (size_t) k;
    return 0;
}

int sysl_net_posix_recv(int fd, unsigned char *buf, size_t n, size_t *got) {
    ssize_t k = recv(fd, buf, n, 0);

    if (k < 0) return failed(fd);

    *got = (size_t) k;
    return 0;
}

int sysl_net_posix_send_to(int fd, const unsigned char *buf, size_t n,
                           const struct sysl_endpoint *to, size_t *sent) {
    struct sockaddr_storage a;
    socklen_t len = to_sockaddr(to, &a);

    if (len == 0) return EAFNOSUPPORT;

    ssize_t k = sendto(fd, buf, n, SEND_FLAGS, (const struct sockaddr *) &a, len);

    if (k < 0) return failed(fd);

    *sent = (size_t) k;
    return 0;
}

int sysl_net_posix_recv_from(int fd, unsigned char *buf, size_t n, struct sysl_endpoint *from,
                             size_t *got) {
    struct sockaddr_storage a;
    socklen_t len = sizeof a;

    ssize_t k = recvfrom(fd, buf, n, 0, (struct sockaddr *) &a, &len);

    if (k < 0) return failed(fd);

    from_sockaddr((const struct sockaddr *) &a, from);
    *got = (size_t) k;
    return 0;
}

/* 0 stops reading, 1 stops writing, 2 stops both -- SHUT_RD, SHUT_WR and SHUT_RDWR, which are 0, 1
 * and 2 on both platforms and are still named rather than passed through, because "they agree
 * today" is how a transcription gets written.
 */
int sysl_net_posix_shutdown(int fd, int how) {
    int h = how == 0 ? SHUT_RD : how == 1 ? SHUT_WR : SHUT_RDWR;

    if (shutdown(fd, h) != 0) return failed(fd);
    return 0;
}

int sysl_net_posix_close(int fd) {
    if (close(fd) != 0) return failed(fd);
    return 0;
}

/* A timeout in milliseconds (`which` 0 for receiving, 1 for sending; zero is none), the address
 * reuse a listener needs (`which` 2), or non-blocking mode (`which` 3), numbered as `sysl.net.sys`
 * numbers them.
 *
 * `SO_RCVTIMEO` rather than a non-blocking socket and a `select`, because this is the blocking tier:
 * a timeout is the one thing a program needs so that "when the work is done" cannot be "never".
 */
int sysl_net_posix_option(int fd, int which, long long value) {
    if (which == 0 || which == 1) {
        struct timeval tv;

        tv.tv_sec = (time_t) (value / 1000);
        tv.tv_usec = (suseconds_t) ((value % 1000) * 1000);

        if (setsockopt(fd, SOL_SOCKET, which == 0 ? SO_RCVTIMEO : SO_SNDTIMEO, &tv, sizeof tv) != 0)
            return failed(fd);
        return 0;
    }

    if (which == 2) {
        int flag = value ? 1 : 0;

        if (setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &flag, sizeof flag) != 0) return failed(fd);
        return 0;
    }

    if (which == 3) {
        int flags = fcntl(fd, F_GETFL);

        if (flags < 0) return failed(fd);

        int want = value ? flags | O_NONBLOCK : flags & ~O_NONBLOCK;

        if (want != flags && fcntl(fd, F_SETFL, want) != 0) return failed(fd);
        return 0;
    }

    /* The kernel's send (`which` 4) and receive (`which` 5) buffer, in bytes. A size that is not
     * positive, or that an `int` cannot carry, is refused here: Linux would round zero up to its
     * minimum and a BSD would take it, so the two would disagree about what an empty buffer is.
     * The kernel rounds what it is given -- Linux doubles it -- so `sysl_net_posix_buffer` is what
     * says what was granted.
     */
    if (which == 4 || which == 5) {
        if (value <= 0 || value > 2147483647LL) return EINVAL;

        int size = (int) value;

        if (setsockopt(fd, SOL_SOCKET, which == 4 ? SO_SNDBUF : SO_RCVBUF, &size, sizeof size) != 0)
            return failed(fd);
        return 0;
    }

    return EINVAL;
}

/* The send (`which` 0) or receive (`which` 1) buffer the kernel granted, in bytes. */
int sysl_net_posix_buffer(int fd, int which, long long *size) {
    int got = 0;
    socklen_t n = sizeof got;

    if (getsockopt(fd, SOL_SOCKET, which == 0 ? SO_SNDBUF : SO_RCVBUF, &got, &n) != 0)
        return failed(fd);

    *size = got;
    return 0;
}

/* Every address a host and a port resolve to, in the resolver's own order.
 *
 * **Both families are asked for and both are returned.** `AF_UNSPEC` is what makes a program work on
 * a v6-only network without knowing it is on one, and the order is the system's answer to which to
 * prefer -- RFC 6724 says how a machine sorts them. The hint names a stream only so that each address
 * comes back once rather than once per kind of socket; the address is the same for a datagram.
 *
 * `host` is `host_len` bytes with no terminator, copied into one here; one that does not fit, or that
 * holds a zero, is not a name.
 */
int sysl_net_posix_resolve(const unsigned char *host, size_t host_len, int port, int passive,
                           struct sysl_endpoint *out, size_t room, size_t *count) {
    char name[NI_MAXHOST];
    char service[16];

    /* An empty name may arrive as a null pointer, which `memchr` and `memcpy` may not be handed. */
    if (host_len > 0) {
        if (host_len >= sizeof name || memchr(host, 0, host_len) != NULL) return SYSL_NET_UNRESOLVED;
        memcpy(name, host, host_len);
    }
    name[host_len] = 0;

    int p = port < 0 ? 0 : port > 65535 ? 65535 : port;
    int i = sizeof service - 1;

    service[i] = 0;
    do {
        service[--i] = (char) ('0' + p % 10);
        p /= 10;
    } while (p > 0);

    struct addrinfo hints;

    memset(&hints, 0, sizeof hints);
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_STREAM;
    hints.ai_flags = AI_NUMERICSERV | (passive ? AI_PASSIVE : 0);

    struct addrinfo *first = NULL;
    int rc = getaddrinfo(host_len ? name : NULL, service + i, &hints, &first);

    if (rc != 0) return rc == EAI_SYSTEM ? (errno ? errno : SYSL_NET_UNRESOLVED) : SYSL_NET_UNRESOLVED;

    size_t n = 0;

    for (struct addrinfo *a = first; a && n < room; a = a->ai_next)
        if (from_sockaddr(a->ai_addr, &out[n]) == 0) n++;

    freeaddrinfo(first);

    /* A resolver that answered and gave nothing this could carry is the same outcome as one that
     * answered nothing, and the caller has the same thing to do about it. */
    if (n == 0) return SYSL_NET_UNRESOLVED;

    *count = n;
    return 0;
}
