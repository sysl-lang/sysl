/* `sysl.net.sys`'s hooks over POSIX sockets: the part only C can answer.
 *
 * Four things put it here rather than in sysl --
 *
 *   - `AF_INET`, `SOCK_DGRAM`, `SHUT_WR`, `SO_RCVTIMEO` and the rest are macros, and several of them
 *     hold *different numbers* on the two platforms this builds for -- `AF_INET6` is 30 on Darwin and
 *     10 under glibc, and `SO_RCVTIMEO` is 0x1006 against 20. A transcription would compile
 *     everywhere and connect nowhere;
 *   - `struct sockaddr_in` and `sockaddr_in6` are layouts, and Darwin's carry a length byte that
 *     Linux's do not, so the two are not the same bytes in the same order;
 *   - `getaddrinfo` answers a linked list of allocations that have to be freed with a call of its
 *     own, which is the one ownership shape the library has nowhere to put;
 *   - a timeout is a `struct timeval` handed to `setsockopt` by address, which is a layout again.
 *
 * **An address crosses as `struct sysl_endpoint`**, the layout `sysl.net.sys.Endpoint` declares, and
 * is turned into the platform's `sockaddr` here and nowhere else. Every function answers zero or an
 * `errno`, and `sysl.net.hosted` negates it into the hook's answer.
 */

#include <errno.h>
#include <netdb.h>
#include <netinet/in.h>
#include <stdint.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <sys/types.h>
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

/* The `errno` a failed call left, with a timed-out blocking call's `EAGAIN` reported as what it is:
 * `ETIMEDOUT`, which is the one code `sysl.net.timed_out` recognises on every target.
 */
static int failed(void) {
    int e = errno;

    if (e == EAGAIN || e == EWOULDBLOCK) return ETIMEDOUT;
    return e ? e : EIO;
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

int sysl_net_posix_socket(int family, int kind, int *fd) {
    int f = family == 6 ? AF_INET6 : AF_INET;
    int s = kind == 1 ? socket(f, SOCK_DGRAM, IPPROTO_UDP) : socket(f, SOCK_STREAM, IPPROTO_TCP);

    if (s < 0) return failed();

    *fd = s;
    return 0;
}

int sysl_net_posix_connect(int fd, const struct sysl_endpoint *to) {
    struct sockaddr_storage a;
    socklen_t n = to_sockaddr(to, &a);

    if (n == 0) return EAFNOSUPPORT;
    if (connect(fd, (const struct sockaddr *) &a, n) != 0) return failed();
    return 0;
}

int sysl_net_posix_bind(int fd, const struct sysl_endpoint *at) {
    struct sockaddr_storage a;
    socklen_t n = to_sockaddr(at, &a);

    if (n == 0) return EAFNOSUPPORT;
    if (bind(fd, (const struct sockaddr *) &a, n) != 0) return failed();
    return 0;
}

int sysl_net_posix_listen(int fd, int backlog) {
    if (listen(fd, backlog) != 0) return failed();
    return 0;
}

int sysl_net_posix_accept(int fd, struct sysl_endpoint *peer, int *out_fd) {
    struct sockaddr_storage from;
    socklen_t n = sizeof from;

    int s = accept(fd, (struct sockaddr *) &from, &n);

    if (s < 0) return failed();

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

    if (getsockname(fd, (struct sockaddr *) &here, &n) != 0) return failed();
    if (from_sockaddr((const struct sockaddr *) &here, at) != 0) return EAFNOSUPPORT;
    return 0;
}

/* The four that move bytes answer a count, which is a `ssize_t` and not an error code -- so the count
 * goes out by address and the answer stays an `errno` like everything else here.
 */
int sysl_net_posix_send(int fd, const unsigned char *buf, size_t n, size_t *sent) {
    ssize_t k = send(fd, buf, n, 0);

    if (k < 0) return failed();

    *sent = (size_t) k;
    return 0;
}

int sysl_net_posix_recv(int fd, unsigned char *buf, size_t n, size_t *got) {
    ssize_t k = recv(fd, buf, n, 0);

    if (k < 0) return failed();

    *got = (size_t) k;
    return 0;
}

int sysl_net_posix_send_to(int fd, const unsigned char *buf, size_t n,
                           const struct sysl_endpoint *to, size_t *sent) {
    struct sockaddr_storage a;
    socklen_t len = to_sockaddr(to, &a);

    if (len == 0) return EAFNOSUPPORT;

    ssize_t k = sendto(fd, buf, n, 0, (const struct sockaddr *) &a, len);

    if (k < 0) return failed();

    *sent = (size_t) k;
    return 0;
}

int sysl_net_posix_recv_from(int fd, unsigned char *buf, size_t n, struct sysl_endpoint *from,
                             size_t *got) {
    struct sockaddr_storage a;
    socklen_t len = sizeof a;

    ssize_t k = recvfrom(fd, buf, n, 0, (struct sockaddr *) &a, &len);

    if (k < 0) return failed();

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

    if (shutdown(fd, h) != 0) return failed();
    return 0;
}

int sysl_net_posix_close(int fd) {
    if (close(fd) != 0) return failed();
    return 0;
}

/* A timeout in milliseconds (`which` 0 for receiving, 1 for sending; zero is none), or the address
 * reuse a listener needs (`which` 2), numbered as `sysl.net.sys` numbers them.
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
            return failed();
        return 0;
    }

    if (which == 2) {
        int flag = value ? 1 : 0;

        if (setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &flag, sizeof flag) != 0) return failed();
        return 0;
    }

    return EINVAL;
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
