#include <stdio.h>

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
// Changing the terminal's mode is `sysl.tty`'s, over the hooks `sysl.tty.hosted` answers.
void sysl_tty_flush_out(void) {
    fflush(stdout);
}
