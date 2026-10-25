#include <sys/ioctl.h>

// The size of the terminal at `fd`, packed as the sysl side unpacks it: the rows above bit 16 and the
// columns below, or -1 where `fd` is no terminal.
//
// **It is here rather than in sysl because all three parts of the call are the platform's own.**
// `TIOCGWINSZ` is a different number on Darwin and under glibc, `struct winsize` is declared by each
// system's header, and `ioctl` is variadic -- so transcribing any of them on the sysl side would be the
// copy of a header this directory exists to avoid.
int sysl_tty_window_size(int fd) {
    struct winsize ws;

    if (ioctl(fd, TIOCGWINSZ, &ws) != 0) return -1;

    return ((int)(ws.ws_row & 0x7fff) << 16) | (int)ws.ws_col;
}
