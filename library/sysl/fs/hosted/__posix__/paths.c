#include <errno.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

// The two calls `sysl.fs.hosted` makes that need a POSIX C library rather than any hosted one: both
// write into a buffer whose size is `PATH_MAX`, a `#define`, or build a name in place.

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

// A fresh directory nobody else can have, made rather than named.
//
// **`mkdtemp` rewrites the template it is given in place**, which is the shape a binding cannot pass
// a `string` to -- a sysl `string` is immutable and its bytes are shared. Building the template here
// from `TMPDIR` and a caller's prefix keeps the mutation where the buffer is.
//
// The alternative -- inventing a name and then creating it -- is the race this call exists to close:
// between the check and the create, somebody else can take the name, and on a shared `/tmp` that
// somebody is not necessarily friendly.
//
// Answers zero, or the `errno` the failure left.
int sysl_fs_host_temp_dir(const char *prefix, char *buf, size_t n) {
    const char *tmp = getenv("TMPDIR");

    if (!tmp || !tmp[0]) tmp = "/tmp";

    int wrote = snprintf(buf, n, "%s/%sXXXXXX", tmp, prefix);

    if (wrote < 0 || (size_t) wrote >= n) return ENAMETOOLONG;

    if (mkdtemp(buf) == (char *) 0) return errno;

    return 0;
}
