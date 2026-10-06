#include <errno.h>
#include <time.h>

// Waiting, as one count of nanoseconds in and one count of nanoseconds left over.
//
// **`struct timespec` does not cross into sysl**, which is the rule `clock.c` beside this file
// follows for the same structure and for the same reason: it is caller-allocated, and
// `reference/ffi.md § A library may carry C` names that as one of the three shapes only C can reach.
// Two numbers is the whole interface.
//
// **Nanoseconds rather than microseconds, which is the opposite of what `clock.c` chose, and the
// asymmetry is deliberate.** A clock reading becomes a `Duration`, which counts microseconds, so a
// finer answer there would be thrown away. A *wait* has no such ceiling — `nanosleep` takes
// nanoseconds and a caller may legitimately want one — so narrowing here would be this file
// deciding that a resolution the system offers is not worth passing on.

// Sleep for `nanos`, and answer what was left when it stopped.
//
// **Zero means it slept the whole time**, and anything else is what a signal interrupted. Returning
// the remainder rather than an error code is what lets the sysl side write the retry loop, which is
// where a loop belongs; it also makes the interruption *visible* to a caller that wants to know,
// which is the difference between the two functions above this shim.
//
// A negative or zero request answers 0 without asking the system, which makes `EINVAL` unreachable
// -- the same argument `clock.c` makes for having no failure to report. `tv_nsec` is kept in range
// by construction, so the only remaining error is `EINTR`, and that is what the remainder is for.
long long sysl_posix_time_nanosleep(long long nanos) {
    if (nanos <= 0) return 0;

    struct timespec want, left;

    want.tv_sec = (time_t) (nanos / 1000000000LL);
    want.tv_nsec = (long) (nanos % 1000000000LL);

    if (nanosleep(&want, &left) == 0) return 0;

    // Anything but EINTR is unreachable given the range check above; answering 0 for it keeps a
    // caller's retry loop from spinning on a failure it cannot do anything about.
    if (errno != EINTR) return 0;

    return (long long) left.tv_sec * 1000000000LL + left.tv_nsec;
}
