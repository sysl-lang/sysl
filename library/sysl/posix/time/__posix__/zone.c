#include <time.h>

// The host zone's offset from UTC at a given instant, in whole minutes.
//
// **`struct tm` does not cross into sysl and neither does the zone**, which is the same rule
// `clock.c` beside this file follows with `struct timespec` and the clock ids. The structure is
// caller-allocated and `tm_gmtoff` is the one field of it anybody here wants, so the shim answers
// with the finished number and sysl has nothing to transcribe.
//
// **`tm_gmtoff` is the whole reason a shim is enough.** It reports the offset that was in force *at
// that instant*, read out of the host's own zone data, so a date in a year whose rules differed from
// this year's is answered correctly and nothing here needs a table. It was a BSD extension until
// POSIX Issue 8 (2024) adopted it, and it is present on macOS, glibc, musl and Bionic -- every
// operating system this file is compiled for.
//
// **What POSIX will not do, and why sysl does it instead.** There is no reentrant way to ask about a
// zone other than the process's own: `tzalloc`, `localtime_rz` and `mktime_z` are a NetBSD extension
// that macOS and glibc do not have. And there is no way at all to ask whether a wall clock reading is
// unique -- `mktime` answers one number for a reading that happened twice and silently rewrites its
// argument for one that never happened. `sysl.time.resolve` is what answers that, in sysl, out of
// this one lookup.
//
// Minutes rather than seconds because `Offset` counts minutes, for the reason its own comment gives:
// India is at +05:30 and Nepal at +05:45, and every zone in the database is a whole number of them.

int sysl_posix_time_local_offset_min(long long us) {
    // Floor rather than truncate, so that an instant before 1970 lands in the second it belongs to.
    // Truncation would round it *up* toward the epoch, which is a second's error and lands on the
    // wrong side of a transition roughly once per transition.
    long long secs = us / 1000000;

    if (us % 1000000 != 0 && us < 0) secs--;

    time_t t = (time_t) secs;
    struct tm lt;

    // Every call rather than once, so that a program which sets `TZ` sees the change. POSIX does not
    // require `localtime_r` to call this -- glibc's does not -- so leaving it out means reading
    // whatever the last `tzset` in the process happened to leave behind, which on a program that
    // never calls one is UTC. The cost is a cheap comparison against the cached `TZ` on every libc
    // that matters.
    tzset();

    // Zero, meaning UTC, on the one failure this has: a year outside what `struct tm` can hold. A
    // `Result` on every rendering of a local time would be paid for by every caller against a
    // failure that needs an instant a quarter of a million years from now to reach.
    if (localtime_r(&t, &lt) == NULL) return 0;

    return (int) (lt.tm_gmtoff / 60);
}
