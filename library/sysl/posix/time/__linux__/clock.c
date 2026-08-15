#include <time.h>

// The two clocks, read as one count of microseconds each.
//
// **Neither `struct timespec` nor a clock id crosses into sysl**, which is the point of the file.
// The structure is caller-allocated and the ids are `#define`s -- `CLOCK_MONOTONIC` is 1 on Linux,
// 6 on macOS and 4 on the BSDs -- and both are exactly what `15 §7` says only C can reach. Answering
// with the finished number leaves sysl nothing to transcribe and nothing to get wrong.
//
// Microseconds rather than nanoseconds because that is what `Duration` counts. A `long` of
// nanoseconds runs out in 1678-2262, and `sysl.time` settled that a range which quietly ends is a
// worse defect than a precision that never begins.

static long long micros_of(clockid_t id) {
    struct timespec ts;

    // Zero on failure, which is the behaviour the sysl side had and for the reason it gave: the id
    // is this file's own and the storage is a local, so neither of `clock_gettime`'s two failures is
    // reachable. A `Result` here would be paid for by every timing call against a failure the types
    // already exclude.
    if (clock_gettime(id, &ts) != 0) return 0;

    return (long long) ts.tv_sec * 1000000 + ts.tv_nsec / 1000;
}

long long sysl_posix_time_realtime_us(void) {
    return micros_of(CLOCK_REALTIME);
}

long long sysl_posix_time_monotonic_us(void) {
    return micros_of(CLOCK_MONOTONIC);
}
