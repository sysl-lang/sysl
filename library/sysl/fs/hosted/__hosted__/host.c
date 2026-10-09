// What `sysl.fs.hosted` asks of C because only a header can answer it. Every function here makes one
// call and hands back that call's own answer; the `errno` a failure leaves is read on the sysl side,
// straight afterwards.
//
// Everything used here is in wasi-libc as well as in the POSIX C libraries, which is why the file sits
// under `__hosted__` rather than `__posix__`. Windows is hosted too and has none of it, so the file is
// empty there and the hooks are left for whatever answers them on that system.
#if !defined(_WIN32)

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

// `sysl.fs.sys`'s open flags, which are that interface's own numbers.
enum {
    SYSL_FS_OPEN_READ = 1,
    SYSL_FS_OPEN_WRITE = 2,
    SYSL_FS_OPEN_CREATE = 4,
    SYSL_FS_OPEN_TRUNCATE = 8,
    SYSL_FS_OPEN_APPEND = 16
};

// `open` with the interface's flags turned into this C library's `O_*`, whose values differ between
// the C libraries -- `O_CREAT` is 0x200 on Darwin and 0x40 on Linux -- and are only written down in a
// header. A descriptor, or -1 with `errno` set.
int sysl_fs_host_open(const char *path, unsigned flags, unsigned mode) {
    int how;

    if ((flags & SYSL_FS_OPEN_READ) && (flags & SYSL_FS_OPEN_WRITE)) how = O_RDWR;
    else if (flags & SYSL_FS_OPEN_WRITE) how = O_WRONLY;
    else how = O_RDONLY;

    if (flags & SYSL_FS_OPEN_CREATE) how |= O_CREAT;
    if (flags & SYSL_FS_OPEN_TRUNCATE) how |= O_TRUNC;
    if (flags & SYSL_FS_OPEN_APPEND) how |= O_APPEND;

    return open(path, how, (mode_t) mode);
}

// The three calls that take or answer an `off_t`, whose width is the platform's: 64 bits on every
// 64-bit host and on wasm32, 32 on a 32-bit Linux. The sysl side always speaks 64.
long long sysl_fs_host_lseek(int fd, long long offset, int whence) {
    return (long long) lseek(fd, (off_t) offset, whence);
}

int sysl_fs_host_ftruncate(int fd, long long length) {
    return ftruncate(fd, (off_t) length);
}

int sysl_fs_host_truncate(const char *path, long long length) {
    return truncate(path, (off_t) length);
}

// What `stat` knows, as the thirteen numbers of `sysl.fs.sys.Stat` in its order.
//
// `struct stat` is the transcription `sysl.fs` refuses: every field's offset, and several fields'
// widths, differ between the platforms, and being wrong about one reads the wrong bytes rather than
// failing. The timestamps are seconds and nanoseconds separately, because the *member* holding them
// is what the platforms disagree about -- `st_mtimespec` against `st_mtim` -- while `struct
// timespec` itself is standard.
static void sysl_fs_host_fill(const struct stat *st, long long *out) {
    out[0]  = (long long) st->st_size;
    out[1]  = (long long) st->st_mode;
    out[2]  = (long long) st->st_nlink;
    out[3]  = (long long) st->st_uid;
    out[4]  = (long long) st->st_gid;
    out[5]  = (long long) st->st_ino;
    out[6]  = (long long) st->st_dev;

#ifdef __APPLE__
    out[7]  = (long long) st->st_mtimespec.tv_sec;
    out[8]  = (long long) st->st_mtimespec.tv_nsec;
    out[9]  = (long long) st->st_atimespec.tv_sec;
    out[10] = (long long) st->st_atimespec.tv_nsec;
    out[11] = (long long) st->st_ctimespec.tv_sec;
    out[12] = (long long) st->st_ctimespec.tv_nsec;
#else
    out[7]  = (long long) st->st_mtim.tv_sec;
    out[8]  = (long long) st->st_mtim.tv_nsec;
    out[9]  = (long long) st->st_atim.tv_sec;
    out[10] = (long long) st->st_atim.tv_nsec;
    out[11] = (long long) st->st_ctim.tv_sec;
    out[12] = (long long) st->st_ctim.tv_nsec;
#endif
}

// `follow` picks between `stat` and `lstat`: what a path names, or the path itself.
int sysl_fs_host_stat(const char *path, int follow, long long *out) {
    struct stat st;

    if ((follow ? stat(path, &st) : lstat(path, &st)) != 0) return -1;

    sysl_fs_host_fill(&st, out);
    return 0;
}

int sysl_fs_host_fstat(int fd, long long *out) {
    struct stat st;

    if (fstat(fd, &st) != 0) return -1;

    sysl_fs_host_fill(&st, out);
    return 0;
}

// The name of the next entry in a directory stream, or NULL at the end of it -- and NULL after a
// failure too, which is why `errno` is zeroed first: only a number left behind tells the two apart.
// `struct dirent` is laid out differently on every platform, and the name is the one field a caller
// wants.
const char *sysl_fs_host_readdir(void *stream) {
    errno = 0;

    struct dirent *entry = readdir((DIR *) stream);

    return entry ? entry->d_name : (const char *) 0;
}

#endif
