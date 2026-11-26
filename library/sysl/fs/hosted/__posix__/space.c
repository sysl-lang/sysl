#include <errno.h>
#include <sys/statvfs.h>

// How big the filesystem holding a path is, and how much of it is unused, as three byte counts.
//
// `struct statvfs` is the C library's layout and its counts are `fsblkcnt_t`, whose width differs
// between platforms, so this is the one place that reads one. **Every count is in `f_frsize` units**,
// the fragment size, which is what POSIX says `f_blocks`, `f_bfree` and `f_bavail` count; `f_bsize`
// is the preferred transfer size, and multiplying by it overstates a filesystem whose two differ. A
// library that leaves `f_frsize` zero means the two are the same, so `f_bsize` stands in.
//
// Writes the total, the free bytes and the bytes an unprivileged program may take to `out[0..3]` and
// answers zero, or answers -1 with `errno` set.
int sysl_fs_host_space(const char *path, unsigned long long *out) {
    struct statvfs fs;

    if (statvfs(path, &fs) != 0) return -1;

    unsigned long long unit = fs.f_frsize != 0 ? (unsigned long long) fs.f_frsize
                                               : (unsigned long long) fs.f_bsize;

    out[0] = (unsigned long long) fs.f_blocks * unit;
    out[1] = (unsigned long long) fs.f_bfree * unit;
    out[2] = (unsigned long long) fs.f_bavail * unit;

    return 0;
}
