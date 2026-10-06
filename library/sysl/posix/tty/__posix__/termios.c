#include <termios.h>
#include <stdio.h>

// Cbreak mode, and putting back exactly what was there.
//
// `struct termios` is caller-allocated and every platform lays it out differently, which is the
// transcription `library/sysl` refuses -- so the storage stays here and sysl never sees it. What
// crosses the boundary is a file descriptor and a status, both of which are `int`.
//
// The saved copy is a `static` for the same reason the sysl side keeps `entered` in module storage:
// a terminal's mode belongs to the process, so there is one of it and no value owns it.

static struct termios saved;
static int have_saved = 0;

// `-icanon -echo -isig`, with `opost onlcr` asserted rather than assumed and `min 1 time 0` so a
// read blocks until a byte arrives. Naming the output flags matters on a terminal that has had
// them turned off by something else -- see the sysl side, where that cost a session.
int sysl_tty_raw(int fd) {
    struct termios t;

    if (tcgetattr(fd, &t) != 0) return -1;

    if (!have_saved) {
        saved = t;
        have_saved = 1;
    }

    t.c_lflag &= ~(ICANON | ECHO | ISIG);
    t.c_oflag |= OPOST | ONLCR;
    t.c_cc[VMIN]  = 1;
    t.c_cc[VTIME] = 0;

    return tcsetattr(fd, TCSANOW, &t);
}

// Exactly what was there before the first `sysl_tty_raw`, rather than a set of flags somebody
// chose. `stty sane` would reset settings the program never touched; naming three flags to put
// back, as the shell version had to, restores a *different* terminal from the one it found.
int sysl_tty_cooked(int fd) {
    if (!have_saved) return 0;

    return tcsetattr(fd, TCSANOW, &saved);
}

// Push out what the C library is holding for standard output, and nothing else.
//
// **It is here rather than in sysl because `stdout` is a macro on one of the two platforms this
// builds for.** Darwin's `<stdio.h>` defines it as `__stdoutp`; glibc has a real symbol called
// `stdout`. So an `extern` variable on the sysl side reaches it under one spelling and not the
// other, and naming `__stdoutp` there would put one libc's private symbol in a portable library --
// which is the transcription this directory exists to avoid. In C the macro is simply what it
// always was.
//
// The sysl side calls this per write on the editor's sink, where the alternative it replaces was
// `fflush(NULL)` -- every open stream, on every keystroke, to flush the one the terminal reads.
void sysl_tty_flush_out(void) {
    fflush(stdout);
}
