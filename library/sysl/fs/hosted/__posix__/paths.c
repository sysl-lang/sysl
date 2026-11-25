// `mkostemp` is declared to glibc and musl programs only under `_GNU_SOURCE`; nothing else this file
// calls changes meaning under it.
#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif

#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

// The calls `sysl.fs.hosted` makes that need a POSIX C library rather than any hosted one: each
// writes into a buffer whose size is `PATH_MAX`, a `#define`, or builds a name in place.

// `realpath` into storage the caller owns, which is what makes it bindable at all.
//
// Its two-argument form writes into a buffer that must be at least `PATH_MAX`, and its one-argument
// form returns storage the caller must `free` -- an ownership transfer the sysl side has nowhere to
// put. Resolving into a local of exactly the size the platform demands and copying out what fits
// sidesteps both: the sysl side supplies an ordinary slice and never learns what `PATH_MAX` is here.
//
// Answers the length written, not counting the terminator, or negative with `errno` set.
long long sysl_fs_host_realpath(const char *path, char *out, unsigned long long room) {
    char resolved[PATH_MAX];

    if (realpath(path, resolved) == (char *) 0) return -1;

    size_t len = strlen(resolved);

    if (len >= room) {
        errno = ENAMETOOLONG;
        return -1;
    }

    memcpy(out, resolved, len + 1);

    return (long long) len;
}

// The directory temporary files go in, written into `buf`: `TMPDIR` where it is set and not empty;
// otherwise, on macOS, the per-user directory `confstr(_CS_DARWIN_USER_TEMP_DIR)` names, which is the
// one Apple's own `NSTemporaryDirectory` answers and which nobody else can write in; otherwise `/tmp`.
// Trailing slashes are dropped (macOS's own `TMPDIR` ends in one), except from `/` itself, so a name
// joined to the answer with one `/` is the name the file system will report.
//
// It is the one rule for the three temporary calls, so `temp_root` cannot name a different place from
// the one `temp_dir` and `temp_file` make things in.
//
// Answers the length written, not counting the terminator, or negative with `errno` set.
long long sysl_fs_host_temp_root(char *buf, size_t n) {
    const char *tmp = getenv("TMPDIR");
    size_t len = 0;

    if (tmp && tmp[0]) {
        len = strlen(tmp);

        if (len >= n) {
            errno = ENAMETOOLONG;
            return -1;
        }

        memcpy(buf, tmp, len + 1);
    } else {
#ifdef __APPLE__
        // The answer counts its terminator; zero is a failure, and more than `n` did not fit.
        size_t need = confstr(_CS_DARWIN_USER_TEMP_DIR, buf, n);

        if (need > 1 && need <= n) len = need - 1;
#endif
        if (len == 0) {
            if (n < 5) {
                errno = ENAMETOOLONG;
                return -1;
            }

            memcpy(buf, "/tmp", 5);
            len = 4;
        }
    }

    while (len > 1 && buf[len - 1] == '/') buf[--len] = 0;

    return (long long) len;
}

// The template `mkdtemp` and `mkostemp` are handed: the root, one `/`, the prefix and the six `X`s
// they replace. Answers zero, or the `errno` that stops it.
static int sysl_fs_host_temp_template(const char *prefix, char *buf, size_t n) {
    long long root = sysl_fs_host_temp_root(buf, n);

    if (root < 0) return errno;

    size_t len = (size_t) root;
    const char *sep = len == 1 && buf[0] == '/' ? "" : "/";
    int wrote = snprintf(buf + len, n - len, "%s%sXXXXXX", sep, prefix);

    if (wrote < 0 || (size_t) wrote >= n - len) return ENAMETOOLONG;

    return 0;
}

// A fresh directory nobody else can have, made rather than named, with mode `0700`.
//
// **`mkdtemp` rewrites the template it is given in place**, which is the shape a binding cannot pass
// a `string` to -- a sysl `string` is immutable and its bytes are shared. Building the template here
// from the temporary root and a caller's prefix keeps the mutation where the buffer is.
//
// The alternative -- inventing a name and then creating it -- is the race this call exists to close:
// between the check and the create, somebody else can take the name, and on a shared `/tmp` that
// somebody is not necessarily friendly.
//
// Answers zero, or the `errno` the failure left.
int sysl_fs_host_temp_dir(const char *prefix, char *buf, size_t n) {
    int code = sysl_fs_host_temp_template(prefix, buf, n);

    if (code != 0) return code;

    if (mkdtemp(buf) == (char *) 0) return errno;

    return 0;
}

// A fresh regular file nobody else can have, created and opened in one step: `mkostemp` opens with
// `O_CREAT | O_EXCL` and mode `0600`, so there is no moment at which the name exists and is somebody
// else's, or exists with wider permissions. `O_CLOEXEC` keeps it out of a child, as every other
// descriptor this library opens is.
//
// Answers zero with the descriptor in `*fd`, or the `errno` the failure left.
int sysl_fs_host_temp_file(const char *prefix, char *buf, size_t n, int *fd) {
    int code = sysl_fs_host_temp_template(prefix, buf, n);

    if (code != 0) return code;

    int got = mkostemp(buf, O_CLOEXEC);

    if (got < 0) return errno;

    *fd = got;

    return 0;
}
