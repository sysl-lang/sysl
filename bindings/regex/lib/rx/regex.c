/* The C that a POSIX regex binding cannot be written without.
 *
 * Everything here is reachable from C and from nothing else, which is the whole
 * reason this file exists beside `rx.sysl` rather than being transcribed into it:
 *
 *   - `sizeof(regex_t)`. The caller allocates it, and it is 32 bytes on Darwin
 *     and 64 under glibc. A sysl program cannot spell storage whose size it has
 *     no way to learn -- so this file allocates it and hands back a pointer, and
 *     the sysl side never learns the size at all.
 *   - `REG_EXTENDED`. A `#define` has no symbol, so there is nothing for a
 *     linker to resolve and nothing for `extern` to name.
 *   - `regmatch_t`. Another layout only the header knows. The bounds are copied
 *     out into two plain integers here, so it never has to cross.
 *
 * Nothing in this file is sysl-specific. It is the ordinary shim any language
 * writes when it binds a C library, and `build-lib` compiles it because it is
 * sitting in the library's tree.
 */
#include <regex.h>
#include <stdlib.h>

void *sysl_rx_new(void) { return calloc(1, sizeof(regex_t)); }

void sysl_rx_free(void *re) {
    if (re) {
        regfree((regex_t *)re);
        free(re);
    }
}

/* Zero on success, and otherwise a code `sysl_rx_error` turns into a message. */
int sysl_rx_compile(void *re, const char *pattern) {
    return regcomp((regex_t *)re, pattern, REG_EXTENDED);
}

/* Whether the pattern matches, with the bounds of the whole match written back
 * through `so` and `eo` when it does. Splitting the answer from the bounds is
 * what keeps `regmatch_t` on this side of the boundary.
 */
int sysl_rx_exec(void *re, const char *s, long *so, long *eo) {
    regmatch_t m;

    if (regexec((regex_t *)re, s, 1, &m, 0) != 0) return 0;

    *so = (long)m.rm_so;
    *eo = (long)m.rm_eo;

    return 1;
}

/* The message the C library itself supplies for a compile that failed, written
 * into storage the caller owns -- the same shape as `strerror_r`. The sysl side
 * turns it into a `string` with `from_cstring`.
 */
void sysl_rx_error(void *re, int code, char *buf, unsigned long n) {
    regerror(code, (regex_t *)re, buf, (size_t)n);
}
