#include <string.h>

// The platform's sentence for an `errno` value, copied into storage the caller owns.
//
// `strerror` is not thread-safe on every libc -- it may hand back one static buffer it rewrites for an
// unknown code -- so this is `strerror_r`, which comes in two shapes under one name. The XSI one
// (Darwin, musl, and glibc and Bionic unless `_GNU_SOURCE` is set) writes into the buffer and answers
// zero or an error number; the GNU one answers a pointer that may be the buffer or may be a constant
// string of its own, and so has to be copied from whatever it returned.
//
// A code the library does not know is answered in the library's own words ("Unknown error: 4242" on
// Darwin, "Unknown error 4242" under glibc), which is passed through as it is. Answers the length
// written, not counting the terminator, or -1 where the libc wrote nothing at all.
long long sysl_fs_strerror(int code, char *out, unsigned long long room) {
    if (room == 0) return -1;

    out[0] = '\0';

#if (defined(__GLIBC__) || defined(__BIONIC__)) && defined(_GNU_SOURCE)
    const char *said = strerror_r(code, out, (size_t) room);

    if (said == (const char *) 0) return -1;

    if (said != out) {
        size_t len = strlen(said);

        if (len >= room) len = (size_t) room - 1;

        memcpy(out, said, len);
        out[len] = '\0';
    }
#else
    // Zero, `EINVAL` for an unknown code and `ERANGE` for a short buffer all leave text behind on the
    // libcs this builds for; only a buffer still empty means there is nothing to say.
    (void) strerror_r(code, out, (size_t) room);

    out[room - 1] = '\0';
#endif

    if (out[0] == '\0') return -1;

    return (long long) strlen(out);
}
