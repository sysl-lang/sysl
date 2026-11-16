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
    SYSL_FS_OPEN_APPEND = 16,
    SYSL_FS_OPEN_UNCACHED = 32
};

// glibc shows `O_DIRECT` only to `_GNU_SOURCE`, under its own name `__O_DIRECT` otherwise.
#if defined(__linux__) && !defined(O_DIRECT) && defined(__O_DIRECT)
#define O_DIRECT __O_DIRECT
#endif

// The interface's flags turned into this C library's `O_*`, whose values differ between the C
// libraries -- `O_CREAT` is 0x200 on Darwin and 0x40 on Linux -- and are only written down in a header.
static int sysl_fs_host_how(unsigned flags) {
    int how;

    if ((flags & SYSL_FS_OPEN_READ) && (flags & SYSL_FS_OPEN_WRITE)) how = O_RDWR;
    else if (flags & SYSL_FS_OPEN_WRITE) how = O_WRONLY;
    else how = O_RDONLY;

    if (flags & SYSL_FS_OPEN_CREATE) how |= O_CREAT;
    if (flags & SYSL_FS_OPEN_TRUNCATE) how |= O_TRUNC;
    if (flags & SYSL_FS_OPEN_APPEND) how |= O_APPEND;
#ifdef O_DIRECT
    if (flags & SYSL_FS_OPEN_UNCACHED) how |= O_DIRECT;
#endif

    return how;
}

// `open` with the interface's flags. A descriptor, or -1 with `errno` set.
//
// An uncached open is `O_DIRECT` where the C library has it (Linux). Darwin has no such flag: the
// same effect is `fcntl(F_NOCACHE)` on the descriptor afterwards. A C library with neither refuses
// the open with `ENOTSUP` rather than quietly handing back a cached file.
int sysl_fs_host_open(const char *path, unsigned flags, unsigned mode) {
#if !defined(O_DIRECT) && !defined(F_NOCACHE)
    if (flags & SYSL_FS_OPEN_UNCACHED) {
        errno = ENOTSUP;
        return -1;
    }
#endif

    /* Close-on-exec, so a child started by `sysl.process` does not inherit the file. */
    int fd = open(path, sysl_fs_host_how(flags) | O_CLOEXEC, (mode_t) mode);

#if !defined(O_DIRECT) && defined(F_NOCACHE)
    if (fd >= 0 && (flags & SYSL_FS_OPEN_UNCACHED) && fcntl(fd, F_NOCACHE, 1) != 0) {
        int why = errno;

        close(fd);
        errno = why;
        return -1;
    }
#endif

    return fd;
}

// What `File.sync_data` asks: the file's contents on the device, and the metadata only where reading
// them back needs it. `fdatasync` on Linux; elsewhere, where the C library has no cheaper call, plain
// `fsync` -- which on Darwin hands the data to the drive without forcing the drive's own cache.
int sysl_fs_host_sync_data(int fd) {
#if defined(__linux__) || defined(__wasi__)
    return fdatasync(fd);
#else
    return fsync(fd);
#endif
}

// What `File.full_sync` asks: the bytes on the platter or the flash, whatever the drive's write cache
// holds. Darwin's `fsync` stops at the drive, so it takes `fcntl(F_FULLFSYNC)`; a filesystem that does
// not know that request (some network and FUSE ones) answers `ENOTSUP` or `EINVAL`, and `fsync` is
// then the strongest call there is. Elsewhere `fsync` already is the full one.
int sysl_fs_host_full_sync(int fd) {
#ifdef F_FULLFSYNC
    if (fcntl(fd, F_FULLFSYNC) == 0) return 0;
    if (errno != ENOTSUP && errno != EINVAL && errno != ENOTTY) return -1;
#endif
    return fsync(fd);
}

// The five calls that take or answer an `off_t`, whose width is the platform's: 64 bits on every
// 64-bit host and on wasm32, 32 on a 32-bit Linux. The sysl side always speaks 64.
long long sysl_fs_host_lseek(int fd, long long offset, int whence) {
    return (long long) lseek(fd, (off_t) offset, whence);
}

long long sysl_fs_host_pread(int fd, void *into, size_t room, long long offset) {
    return (long long) pread(fd, into, room, (off_t) offset);
}

long long sysl_fs_host_pwrite(int fd, const void *from, size_t len, long long offset) {
    return (long long) pwrite(fd, from, len, (off_t) offset);
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

// -- beneath a directory ------------------------------------------------------------------------------
//
// What `sysl.fs.Dir` stands on: a path resolved inside one directory descriptor, with a symbolic link
// at ANY component refused (`ELOOP`) and a way out of the directory refused (`EXDEV`). The kernel does
// the refusing, in the same call that opens, so nothing can be swapped between a check and the use.
//
// - **Linux**: `openat2` with `RESOLVE_BENEATH | RESOLVE_NO_SYMLINKS` (5.6). glibc has no wrapper, so
//   it is the raw system call; a kernel without it answers `ENOSYS`, which the sysl side hears as "not
//   supported on this target" -- never as a fallback that would not confine.
// - **Darwin**: `openat` with `O_NOFOLLOW_ANY` (macOS 11), and `O_RESOLVE_BENEATH` where the SDK has
//   it. Darwin answers `ENOTCAPABLE` for an escape, which is turned into Linux's `EXDEV` here so the
//   sysl side knows one number.
// - **Anything else** answers `ENOSYS` from every call below.
//
// `sysl.fs` has already refused an absolute path and every `..` component before it gets here.

#if defined(__linux__)
#include <sys/syscall.h>
#if defined(SYS_openat2) && defined(__has_include)
#if __has_include(<linux/openat2.h>)
#include <linux/openat2.h>
#define SYSL_FS_HOST_OPENAT2 1
#endif
#endif
#endif

#include <string.h>

#if defined(SYSL_FS_HOST_OPENAT2) || (defined(__APPLE__) && defined(O_NOFOLLOW_ANY))
#define SYSL_FS_HOST_BENEATH 1
#endif

// `rel` opened beneath `dir` with the C library's `how`, refusing every link and every escape.
static int sysl_fs_host_beneath(int dir, const char *rel, int how, mode_t mode) {
#if defined(SYSL_FS_HOST_OPENAT2)
    struct open_how wanted;

    memset(&wanted, 0, sizeof wanted);
    wanted.flags = (unsigned long long) (how | O_CLOEXEC);
    /* `openat2` refuses a mode where nothing is created, where `openat` ignores it. */
    if (how & O_CREAT) wanted.mode = (unsigned long long) mode;
    wanted.resolve = RESOLVE_BENEATH | RESOLVE_NO_SYMLINKS;

    return (int) syscall(SYS_openat2, dir, rel, &wanted, sizeof wanted);
#elif defined(SYSL_FS_HOST_BENEATH)
    int confine = O_NOFOLLOW_ANY;
#ifdef O_RESOLVE_BENEATH
    confine |= O_RESOLVE_BENEATH;
#endif
    int fd = openat(dir, rel, how | confine | O_CLOEXEC, mode);

#ifdef ENOTCAPABLE
    if (fd < 0 && errno == ENOTCAPABLE) errno = EXDEV;
#endif
    return fd;
#else
    (void) dir; (void) rel; (void) how; (void) mode;
    errno = ENOSYS;
    return -1;
#endif
}

// How a directory is opened only to resolve names inside it. glibc shows `O_PATH` only to
// `_GNU_SOURCE`, under its own name `__O_PATH` otherwise.
#if defined(SYSL_FS_HOST_OPENAT2) && !defined(O_PATH) && defined(__O_PATH)
#define O_PATH __O_PATH
#endif

#if defined(SYSL_FS_HOST_OPENAT2) && defined(O_PATH)
#define SYSL_FS_HOST_SEARCH (O_PATH | O_DIRECTORY)
#elif defined(O_SEARCH)
#define SYSL_FS_HOST_SEARCH O_SEARCH
#else
#define SYSL_FS_HOST_SEARCH (O_RDONLY | O_DIRECTORY)
#endif

// The directory to be confined to: `path` opened as a directory, its last component not followed if
// it is a link (`ELOOP`) and refused if it is not a directory (`ENOTDIR`). The components before it
// are the caller's own path and are followed as any path is -- `/tmp` is a link on macOS.
//
// Darwin answers `ENOTDIR` rather than `ELOOP` for a link to a directory under `O_DIRECTORY`, so that
// one answer is looked at again. The kernel has already refused; the second look only picks the words.
int sysl_fs_host_dir_open(const char *path) {
    int fd = open(path, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);

    if (fd < 0 && errno == ENOTDIR) {
        struct stat st;

        errno = (lstat(path, &st) == 0 && S_ISLNK(st.st_mode)) ? ELOOP : ENOTDIR;
    }
    return fd;
}

int sysl_fs_host_open_beneath(int dir, const char *rel, unsigned flags, unsigned mode) {
    return sysl_fs_host_beneath(dir, rel, sysl_fs_host_how(flags), (mode_t) mode);
}

// The directory holding `rel`'s last component, opened beneath `dir`, with `*name` set to that
// component. `rel` is written: trailing slashes go, and the last slash becomes a terminator. Where
// `rel` is one name the answer is `dir` itself and `*owned` is zero, so the caller closes only what
// this opened. -1 with `errno` set where the directories above the name cannot be reached.
#if defined(SYSL_FS_HOST_BENEATH)
static int sysl_fs_host_parent(int dir, char *rel, const char **name, int *owned) {
    size_t n = strlen(rel);

    while (n > 1 && rel[n - 1] == '/') rel[--n] = 0;

    char *slash = strrchr(rel, '/');

    if (slash == (char *) 0) {
        *name = rel;
        *owned = 0;
        return dir;
    }

    *slash = 0;
    *name = slash + 1;
    *owned = 1;
    return sysl_fs_host_beneath(dir, rel, SYSL_FS_HOST_SEARCH, 0);
}
#endif

// What `fstatat` knows about `rel` beneath `dir`, in `sysl.fs.sys.Stat`'s order. The last component is
// described rather than followed, so a link there is reported as a link; one above it is refused.
int sysl_fs_host_stat_beneath(int dir, char *rel, long long *out) {
#if defined(SYSL_FS_HOST_BENEATH)
    const char *name;
    int owned;
    int at = sysl_fs_host_parent(dir, rel, &name, &owned);

    if (at < 0) return -1;

    struct stat st;
    int rc = fstatat(at, name, &st, AT_SYMLINK_NOFOLLOW);
    int why = errno;

    if (owned) close(at);
    if (rc != 0) {
        errno = why;
        return -1;
    }

    sysl_fs_host_fill(&st, out);
    return 0;
#else
    (void) dir; (void) rel; (void) out;
    errno = ENOSYS;
    return -1;
#endif
}

// `unlinkat` of `rel` beneath `dir`. The last component is the name removed, so a link there is the
// link removed and never what it points at; a link above it is refused.
int sysl_fs_host_unlink_beneath(int dir, char *rel) {
#if defined(SYSL_FS_HOST_BENEATH)
    const char *name;
    int owned;
    int at = sysl_fs_host_parent(dir, rel, &name, &owned);

    if (at < 0) return -1;

    int rc = unlinkat(at, name, 0);
    int why = errno;

    if (owned) close(at);

    errno = why;
    return rc;
#else
    (void) dir; (void) rel;
    errno = ENOSYS;
    return -1;
#endif
}

#endif
