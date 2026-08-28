/* Blocking TCP, and the names a host and a service resolve to.
 *
 * This is a shim for the reason `sysl/process/__posix__/spawn.c` is one: what the module needs from
 * POSIX is not reachable by symbol alone. Four things put it here rather than in sysl --
 *
 *   - `AF_INET`, `SOCK_STREAM`, `SHUT_WR`, `SO_RCVTIMEO` and the rest are macros, and several of
 *     them hold *different numbers* on the two platforms this builds for -- `AF_INET6` is 30 on
 *     Darwin and 10 under glibc, and `SO_RCVTIMEO` is 0x1006 against 20. A transcription would
 *     compile everywhere and connect nowhere;
 *   - `struct sockaddr_in`, `sockaddr_in6` and `sockaddr_storage` are layouts, and Darwin's carry a
 *     length byte that Linux's do not, so the two are not the same bytes in the same order;
 *   - `getaddrinfo` answers a linked list of allocations that have to be freed with a call of its
 *     own, which is the one ownership shape the library has nowhere to put;
 *   - a timeout is a `struct timeval` handed to `setsockopt` by address, which is a layout again.
 *
 * **The addresses cross as opaque bytes.** A `sockaddr_storage` is 128 bytes on both platforms and
 * sysl never looks inside one: it carries the bytes and the length, hands them back to `connect` or
 * `bind`, and asks the functions below whatever it wants to know about them. That is what keeps the
 * layout question entirely on this side of the boundary.
 *
 * It sits under `__posix__` so that it is absent on a target with no sockets, which is what lets the
 * module go on being compiled for every target.
 */

#include <errno.h>
#include <netdb.h>
#include <netinet/in.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <sys/types.h>
#include <unistd.h>
#include <arpa/inet.h>
#include <stdlib.h>

/* The width sysl reserves per address, and the whole of what it has to agree with this file about. */
#define SYSL_NET_ADDR_BYTES 128

/* What `resolve` answers when the failure is the resolver's rather than the system's.
 *
 * `getaddrinfo` reports `EAI_*` codes, which are a numbering of their own and overlap `errno`'s --
 * `EAI_AGAIN` is 2 on Darwin, which is `ENOENT`. So they are not passed through: a resolver failure
 * that carries an `errno` (`EAI_SYSTEM`) reports that one, and everything else reports the single
 * code below, which is `EAI_NONAME`'s honest meaning in the vocabulary the caller already has.
 */
#define SYSL_NET_UNRESOLVED ENOENT

/* Every address a host and a service resolve to, as bytes.
 *
 * `host` empty with `passive` set is the wildcard a listener binds to -- `INADDR_ANY` and its v6
 * twin -- which is why the flag is here rather than being inferred: the same call answers "where do
 * I connect to" and "where do I listen", and nothing about the arguments alone says which.
 *
 * Writes at most `max` addresses, each into its own `SYSL_NET_ADDR_BYTES` slot of `out`, with the
 * used length of each into `lens`. Answers 0 having set `*count`, or an `errno`.
 *
 * **Both families are asked for and both are returned, in the resolver's own order.** `AF_UNSPEC`
 * is what makes a program work on a v6-only network without knowing it is on one, and the order is
 * the system's answer to that question -- RFC 6724 says how a machine sorts them, and second-
 * guessing it here would be this module inventing policy it has no business having.
 */
int sysl_net_resolve(const char *host, const char *service, int passive,
                     unsigned char *out, int *lens, int max, int *count) {
    struct addrinfo hints;

    memset(&hints, 0, sizeof hints);
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_STREAM;
    hints.ai_protocol = IPPROTO_TCP;
    if (passive) hints.ai_flags = AI_PASSIVE;

    struct addrinfo *first = NULL;
    int rc = getaddrinfo((host && host[0]) ? host : NULL, service, &hints, &first);

    if (rc != 0) return rc == EAI_SYSTEM ? (errno ? errno : SYSL_NET_UNRESOLVED) : SYSL_NET_UNRESOLVED;

    int n = 0;

    for (struct addrinfo *a = first; a && n < max; a = a->ai_next) {
        if (a->ai_addrlen > SYSL_NET_ADDR_BYTES) continue;

        memcpy(out + (size_t) n * SYSL_NET_ADDR_BYTES, a->ai_addr, a->ai_addrlen);
        lens[n] = (int) a->ai_addrlen;
        n++;
    }

    freeaddrinfo(first);

    /* A resolver that answered and gave nothing this could carry is the same outcome as one that
     * answered nothing, and the caller has the same thing to do about it. */
    if (n == 0) return SYSL_NET_UNRESOLVED;

    *count = n;
    return 0;
}

/* 4 for an IPv4 address, 6 for an IPv6 one, 0 for anything else. Named by the version rather than
 * by `AF_INET`'s number, because the number is what a program must not be given.
 */
int sysl_net_family(const unsigned char *addr) {
    const struct sockaddr *sa = (const struct sockaddr *) addr;

    if (sa->sa_family == AF_INET) return 4;
    if (sa->sa_family == AF_INET6) return 6;
    return 0;
}

/* The numeric form of an address, and its port. `getnameinfo` with `NI_NUMERICHOST` rather than
 * `inet_ntop` on a family switch: one call, both families, and no second place that has to know
 * which offset the address sits at.
 */
int sysl_net_text(const unsigned char *addr, int len, char *out, size_t n, int *port) {
    char service[NI_MAXSERV];
    const struct sockaddr *sa = (const struct sockaddr *) addr;

    int rc = getnameinfo(sa, (socklen_t) len, out, (socklen_t) n, service, sizeof service,
                         NI_NUMERICHOST | NI_NUMERICSERV);

    if (rc != 0) return rc == EAI_SYSTEM ? (errno ? errno : SYSL_NET_UNRESOLVED) : SYSL_NET_UNRESOLVED;

    *port = atoi(service);
    return 0;
}

/* A socket of the family an address belongs to. The family comes from the address rather than from
 * the caller, because a socket of the wrong one cannot be connected to it and there is no reason to
 * let the two be said separately.
 */
int sysl_net_socket(const unsigned char *addr, int *fd) {
    const struct sockaddr *sa = (const struct sockaddr *) addr;
    int s = socket(sa->sa_family, SOCK_STREAM, IPPROTO_TCP);

    if (s < 0) return errno;

    *fd = s;
    return 0;
}

int sysl_net_connect(int fd, const unsigned char *addr, int len) {
    if (connect(fd, (const struct sockaddr *) addr, (socklen_t) len) != 0) return errno;
    return 0;
}

int sysl_net_bind(int fd, const unsigned char *addr, int len) {
    if (bind(fd, (const struct sockaddr *) addr, (socklen_t) len) != 0) return errno;
    return 0;
}

int sysl_net_listen(int fd, int backlog) {
    if (listen(fd, backlog) != 0) return errno;
    return 0;
}

int sysl_net_accept(int fd, unsigned char *addr, int *len, int *out_fd) {
    struct sockaddr_storage from;
    socklen_t n = sizeof from;

    int s = accept(fd, (struct sockaddr *) &from, &n);

    if (s < 0) return errno;

    if (n > SYSL_NET_ADDR_BYTES) n = SYSL_NET_ADDR_BYTES;

    memcpy(addr, &from, n);
    *len = (int) n;
    *out_fd = s;
    return 0;
}

/* What the socket is actually bound to, which is how a caller that asked for port 0 learns which
 * port it got. A test needs it and so does anything that wants an ephemeral listener.
 */
int sysl_net_local(int fd, unsigned char *addr, int *len) {
    struct sockaddr_storage here;
    socklen_t n = sizeof here;

    if (getsockname(fd, (struct sockaddr *) &here, &n) != 0) return errno;

    if (n > SYSL_NET_ADDR_BYTES) n = SYSL_NET_ADDR_BYTES;

    memcpy(addr, &here, n);
    *len = (int) n;
    return 0;
}

/* `send` and `recv` answer how many bytes moved, which is a `ssize_t` and not an error code -- so
 * the count goes out by address and the answer stays an `errno` like everything else here. A short
 * write is not an error and never was: it is what a stream does, and the sysl side loops.
 */
int sysl_net_send(int fd, const unsigned char *buf, size_t n, size_t *sent) {
    ssize_t k = send(fd, buf, n, 0);

    if (k < 0) return errno;

    *sent = (size_t) k;
    return 0;
}

int sysl_net_recv(int fd, unsigned char *buf, size_t n, size_t *got) {
    ssize_t k = recv(fd, buf, n, 0);

    if (k < 0) return errno;

    *got = (size_t) k;
    return 0;
}

/* 0 stops reading, 1 stops writing, 2 stops both -- SHUT_RD, SHUT_WR and SHUT_RDWR, which are 0, 1
 * and 2 on both platforms and are still named rather than passed through, because "they agree
 * today" is how a transcription gets written.
 */
int sysl_net_shutdown(int fd, int how) {
    int h = how == 0 ? SHUT_RD : how == 1 ? SHUT_WR : SHUT_RDWR;

    if (shutdown(fd, h) != 0) return errno;
    return 0;
}

int sysl_net_close(int fd) {
    if (close(fd) != 0) return errno;
    return 0;
}

/* A read or write timeout in milliseconds, or none at all where `ms` is zero.
 *
 * `SO_RCVTIMEO` rather than a non-blocking socket and a `select`, because this module is the
 * blocking tier -- the whole point of it is that a call returns when the work is done, and a
 * timeout is the one thing a program needs so that "when the work is done" cannot be "never". A
 * call that times out answers `EAGAIN`, which `timed_out` on the sysl side recognises.
 */
int sysl_net_timeout(int fd, int recv_side, long ms) {
    struct timeval tv;

    tv.tv_sec = ms / 1000;
    tv.tv_usec = (ms % 1000) * 1000;

    if (setsockopt(fd, SOL_SOCKET, recv_side ? SO_RCVTIMEO : SO_SNDTIMEO, &tv, sizeof tv) != 0)
        return errno;

    return 0;
}

/* What a listener needs so that a restart does not have to wait out `TIME_WAIT` on its own port.
 *
 * It is the one socket option here beyond the timeout, and it is here because without it a server
 * that has just been stopped cannot be started again for a minute or two -- which reads as the
 * program being broken. Everything else `setsockopt` can do is out of scope until something asks.
 */
int sysl_net_reuse_address(int fd, int on) {
    int flag = on ? 1 : 0;

    if (setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &flag, sizeof flag) != 0) return errno;
    return 0;
}

/* Whether an `errno` is what a timed-out blocking call reports. `EAGAIN` and `EWOULDBLOCK` are the
 * same number on both platforms here and are not required by POSIX to be, so both are asked.
 */
int sysl_net_is_timeout(int code) {
    return code == EAGAIN || code == EWOULDBLOCK;
}
