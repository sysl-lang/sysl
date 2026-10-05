#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>

// A fresh directory nobody else can have, made rather than named.
//
// **`mkdtemp` rewrites the template it is given in place**, which is the shape a binding cannot pass
// a `string` to -- a sysl `string` is immutable and its bytes are shared, so the call would be
// writing through storage other values are reading. Building the template here from `TMPDIR` and a
// caller's prefix keeps the mutation where the buffer is, and the sysl side hands over an ordinary
// slice to receive the answer.
//
// The alternative -- inventing a name and then creating it -- is the race this call exists to close:
// between the check and the create, somebody else can take the name, and on a shared `/tmp` that
// somebody is not necessarily friendly. `sysl_proc_temp_path` is the same reasoning for a file.
//
// Answers zero, or an `errno` the sysl side turns into an `IoError` -- which is what
// `sysl_proc_temp_path` already does and is why neither of them reports through the return value.
int sysl_fs_temp_dir(const char *prefix, char *buf, size_t n) {
    const char *tmp = getenv("TMPDIR");

    if (!tmp || !tmp[0]) tmp = "/tmp";

    int wrote = snprintf(buf, n, "%s/%sXXXXXX", tmp, prefix);

    if (wrote < 0 || (size_t) wrote >= n) return ENAMETOOLONG;

    if (mkdtemp(buf) == (char *) 0) return errno;

    return 0;
}
