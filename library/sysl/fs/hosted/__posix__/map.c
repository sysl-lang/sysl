#include <stddef.h>
#include <sys/mman.h>
#include <unistd.h>

// A file's bytes mapped into the address space, read-only and private, for `sysl_fs_map`.
//
// It is C because the call's flags are the platform's own `#define`s and its offset is an `off_t`,
// whose width is the platform's; and because `MAP_FAILED` is the failure, not null. It needs a POSIX
// C library -- wasi-libc has `mmap` only behind an emulation flag -- which is why it sits under
// `__posix__/`.
//
// Answers the address, or null with `errno` set. A mapping requested with no address hint is never
// placed at zero, so null is free to mean failure.
void *sysl_fs_host_map(int fd, size_t len) {
    void *at = mmap((void *) 0, len, PROT_READ, MAP_PRIVATE, fd, (off_t) 0);

    return at == MAP_FAILED ? (void *) 0 : at;
}

// The same, readable and writable and shared, for `sysl_fs_map_shared`. Null with `errno` set.
void *sysl_fs_host_map_shared(int fd, size_t len) {
    void *at = mmap((void *) 0, len, PROT_READ | PROT_WRITE, MAP_SHARED, fd, (off_t) 0);

    return at == MAP_FAILED ? (void *) 0 : at;
}

// `msync` of a range inside a shared mapping: `MS_SYNC` where `wait` is non-zero, `MS_ASYNC`
// otherwise. Zero, or -1 with `errno` set.
int sysl_fs_host_msync(void *at, size_t len, int wait) {
    return msync(at, len, wait ? MS_SYNC : MS_ASYNC);
}

// Zero, or -1 with `errno` set.
int sysl_fs_host_unmap(void *at, size_t len) {
    return munmap(at, len);
}

// `madvise` for a range inside a mapping. `advice` is `sysl.fs.Advice`'s position; the flags are the
// platform's own `#define`s, which is why this is C. Zero, or -1 with `errno` set. Linux acts on
// every value; macOS honours the hints for file mappings but treats `MADV_DONTNEED` as advisory and
// does not drop the pages.
int sysl_fs_host_advise(void *at, size_t len, int advice) {
    int flag;

    switch (advice) {
    case 1: flag = MADV_RANDOM; break;
    case 2: flag = MADV_SEQUENTIAL; break;
    case 3: flag = MADV_WILLNEED; break;
    case 4: flag = MADV_DONTNEED; break;
    default: flag = MADV_NORMAL; break;
    }

    return madvise(at, len, flag);
}

// The size of a page of memory, in bytes.
size_t sysl_fs_host_page_size(void) {
    return (size_t) sysconf(_SC_PAGESIZE);
}
