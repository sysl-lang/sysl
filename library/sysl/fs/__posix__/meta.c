#include <errno.h>
#include <limits.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>

// What `stat` knows, as a run of `long long` the sysl side reads by position.
//
// `struct stat` is the transcription `sysl.fs` refuses: every field's offset, and several fields'
// widths, differ between the platforms, and being wrong about one reads the wrong bytes rather than
// failing. So the layout stays here, where the header is what decides it, and a fixed run of
// numbers crosses -- the same shape `sysl_fs_file_size` uses, widened from one field to thirteen.
//
// `follow` picks between `stat` and `lstat`, which is the whole difference between asking about what
// a path names and asking about the path itself. It is a parameter rather than two functions because
// the thirteen assignments below would otherwise be written twice.
//
// The timestamps are seconds and nanoseconds separately, because the *member* holding them is what
// the two platforms disagree about -- `st_mtimespec` against `st_mtim` -- while `struct timespec`
// itself is standard. Combining them here would hide which of the two spellings was read.
int sysl_fs_stat(const char *path, int follow, long long *out) {
    struct stat st;

    if ((follow ? stat(path, &st) : lstat(path, &st)) != 0) return -1;

    out[0]  = (long long) st.st_size;
    out[1]  = (long long) st.st_mode;
    out[2]  = (long long) st.st_nlink;
    out[3]  = (long long) st.st_uid;
    out[4]  = (long long) st.st_gid;
    out[5]  = (long long) st.st_ino;
    out[6]  = (long long) st.st_dev;

#ifdef __APPLE__
    out[7]  = (long long) st.st_mtimespec.tv_sec;
    out[8]  = (long long) st.st_mtimespec.tv_nsec;
    out[9]  = (long long) st.st_atimespec.tv_sec;
    out[10] = (long long) st.st_atimespec.tv_nsec;
    out[11] = (long long) st.st_ctimespec.tv_sec;
    out[12] = (long long) st.st_ctimespec.tv_nsec;
#else
    out[7]  = (long long) st.st_mtim.tv_sec;
    out[8]  = (long long) st.st_mtim.tv_nsec;
    out[9]  = (long long) st.st_atim.tv_sec;
    out[10] = (long long) st.st_atim.tv_nsec;
    out[11] = (long long) st.st_ctim.tv_sec;
    out[12] = (long long) st.st_ctim.tv_nsec;
#endif

    return 0;
}

// `realpath` into storage the caller owns, which is what makes it bindable at all.
//
// Its two-argument form writes into a buffer that must be at least `PATH_MAX`, and its one-argument
// form returns storage the caller must `free` -- an ownership transfer no binding in this org has,
// and the one shape `sysl.fs` has nowhere to put. Resolving into a local of exactly the size the
// platform demands and copying out what fits sidesteps both: the sysl side supplies an ordinary
// slice and never learns what `PATH_MAX` is here.
//
// Answers the length written, not counting the terminator, or negative with `errno` set -- which is
// the convention the sysl side already reads every other call in this module by.
long long sysl_fs_realpath(const char *path, char *out, unsigned long long room) {
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
