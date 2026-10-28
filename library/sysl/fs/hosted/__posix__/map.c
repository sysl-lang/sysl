#include <stddef.h>
#include <sys/mman.h>

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

// Zero, or -1 with `errno` set.
int sysl_fs_host_unmap(void *at, size_t len) {
    return munmap(at, len);
}
