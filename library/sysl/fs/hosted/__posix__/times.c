#include <fcntl.h>
#include <sys/stat.h>
#include <time.h>

// Setting a file's times, which `sysl.fs.hosted` asks of C for the numbers only a header knows:
// `UTIME_OMIT`, "leave this time as it is", is -2 on Darwin and (1 << 30) - 2 on Linux, and
// `AT_SYMLINK_NOFOLLOW` is 0x20 on Darwin and 0x100 on Linux.

// The bits of `sysl.fs.sys.times_*`, which are that interface's own numbers.
enum {
    SYSL_FS_TIMES_ACCESSED = 1,
    SYSL_FS_TIMES_MODIFIED = 2
};

// `times` is the access time then the modification time, each seconds then nanoseconds. A time whose
// bit of `given` is clear is passed as `UTIME_OMIT`. `follow` zero sets a symbolic link's own times.
// Zero, or -1 with `errno` set.
int sysl_fs_host_set_times(const char *path, int follow, const long long *times, int given) {
    struct timespec ts[2];

    ts[0].tv_sec = (time_t) times[0];
    ts[0].tv_nsec = (given & SYSL_FS_TIMES_ACCESSED) ? (long) times[1] : UTIME_OMIT;
    ts[1].tv_sec = (time_t) times[2];
    ts[1].tv_nsec = (given & SYSL_FS_TIMES_MODIFIED) ? (long) times[3] : UTIME_OMIT;

    return utimensat(AT_FDCWD, path, ts, follow ? 0 : AT_SYMLINK_NOFOLLOW);
}
