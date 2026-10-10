#include <errno.h>
#include <stdlib.h>
#include <termios.h>

// A terminal's mode as one integer each way, and putting back what was found when the program ends.
//
// `struct termios` is caller-allocated and every platform lays it out differently, which is the
// transcription `library/sysl` refuses -- so it stays here, and what crosses is a descriptor and a
// packed mode: the five flags `sysl.tty.sys.Mode` names in the low byte, `VMIN` in the next and
// `VTIME` in the one after. Both calls answer -errno on failure, read straight after the call that
// left it.

#define SYSL_TTY_ECHO      1
#define SYSL_TTY_CANONICAL 2
#define SYSL_TTY_SIGNALS   4
#define SYSL_TTY_OUTPUT    8
#define SYSL_TTY_CRLF      16

// What the terminal was before this program first changed it, and the descriptor it was found on.
// It is a `static` because a terminal's mode belongs to the process: there is one of it, and no value
// owns it.
static struct termios found;
static int found_fd = -1;

// What `atexit` runs: the whole `struct termios` that was found, rather than the few settings the
// record names, so a program that ends without restoring leaves exactly the terminal it was given.
static void put_back(void) {
    if (found_fd >= 0) tcsetattr(found_fd, TCSANOW, &found);
}

static void apply(tcflag_t *flags, tcflag_t bit, int on) {
    if (on) *flags |= bit; else *flags &= ~bit;
}

// The mode of the terminal at `fd`, packed; -errno where it has none.
int sysl_tty_hosted_get(int fd) {
    struct termios t;

    if (tcgetattr(fd, &t) != 0) return -errno;

    int bits = 0;

    if (t.c_lflag & ECHO)   bits |= SYSL_TTY_ECHO;
    if (t.c_lflag & ICANON) bits |= SYSL_TTY_CANONICAL;
    if (t.c_lflag & ISIG)   bits |= SYSL_TTY_SIGNALS;
    if (t.c_oflag & OPOST)  bits |= SYSL_TTY_OUTPUT;
    if (t.c_oflag & ONLCR)  bits |= SYSL_TTY_CRLF;

    return bits | (int) t.c_cc[VMIN] << 8 | (int) t.c_cc[VTIME] << 16;
}

// Puts the terminal at `fd` into the packed mode, changing the seven settings it names and nothing
// else; zero, or -errno.
//
// **The first change registers the restore.** What the terminal was is saved whole before anything
// is written, and `atexit` puts it back on every ordinary way out -- which is every way out a program
// that turned `signals` off has, Ctrl-C then being a byte rather than a death nobody hears about.
int sysl_tty_hosted_set(int fd, int mode) {
    struct termios t;

    if (tcgetattr(fd, &t) != 0) return -errno;

    if (found_fd < 0) {
        found = t;
        found_fd = fd;
        atexit(put_back);
    }

    apply(&t.c_lflag, ECHO, mode & SYSL_TTY_ECHO);
    apply(&t.c_lflag, ICANON, mode & SYSL_TTY_CANONICAL);
    apply(&t.c_lflag, ISIG, mode & SYSL_TTY_SIGNALS);
    apply(&t.c_oflag, OPOST, mode & SYSL_TTY_OUTPUT);
    apply(&t.c_oflag, ONLCR, mode & SYSL_TTY_CRLF);

    t.c_cc[VMIN] = (cc_t) (mode >> 8 & 0xff);
    t.c_cc[VTIME] = (cc_t) (mode >> 16 & 0xff);

    if (tcsetattr(fd, TCSANOW, &t) != 0) return -errno;

    return 0;
}
