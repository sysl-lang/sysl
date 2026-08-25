/* Running a child and waiting for it.
 *
 * This is a shim for the same reason `sysl/fs/__posix__/dirent.c` is one: what the module needs
 * from POSIX is not reachable by symbol alone. Three separate things put it here rather than in
 * sysl --
 *
 *   - `WIFEXITED`, `WEXITSTATUS`, `WIFSIGNALED` and `WTERMSIG` are macros over the bits of an int
 *     that no header publishes as a layout, so how a child ended can only be decoded in C;
 *   - everything between `fork` and `execvp` runs in a process that has a copy of this one's
 *     address space and must not allocate, which is not a thing to express across an FFI boundary;
 *   - `pid_t` and the argument vector's exact type differ enough between platforms to be worth
 *     naming once here instead of transcribing.
 *
 * It sits under `__posix__` so that it is absent on a target with no processes to start, which is
 * what lets the module go on being compiled for every target.
 */

#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/wait.h>
#include <unistd.h>

/* Everything the child does before it becomes the other program, as one function so that the
 * caller below is a straight line. Answers an `errno`, or zero.
 *
 * It allocates nothing and opens at most one descriptor, which is the constraint the whole
 * between-fork-and-exec window is written under.
 */
static int child_setup(const char *const *names, const char *const *values,
                       const char *dir, const char *out_path) {
    /* `setenv` here rather than a whole `envp` handed to `execve`, so that a caller adds to the
     * environment instead of replacing it -- a child that lost PATH, HOME and TMPDIR because its
     * parent wanted to set one variable is a surprise nobody wants. This is also the one place
     * `setenv` is safe: the child is single-threaded by construction and is about to exec, so the
     * thread-safety objection that keeps it out of `sysl.env` does not apply. */
    if (names && values) {
        for (int i = 0; names[i]; i++) {
            if (setenv(names[i], values[i], 1) != 0) return errno;
        }
    }

    if (dir && dir[0] && chdir(dir) != 0) return errno;

    if (out_path && out_path[0]) {
        int fd = open(out_path, O_WRONLY | O_CREAT | O_TRUNC, 0600);

        if (fd < 0) return errno;

        if (dup2(fd, STDOUT_FILENO) < 0) {
            int e = errno;

            close(fd);
            return e;
        }

        close(fd);
    }

    return 0;
}

/* Start `program`, wait for it, and say how it ended.
 *
 * Returns 0 having set `*code` and `*sig`, or an `errno` if the child could not be started at all.
 *
 * **The pipe is how a failed `execvp` is told from a program that ran and exited 127**, which is
 * the distinction a caller most wants and the one `system(3)` cannot make. It is close-on-exec, so
 * a successful exec closes it and the parent's read sees end-of-file; a failure writes the `errno`
 * into it first. Without this, a missing program and a program whose own exit status is 127 are the
 * same answer -- and "no such file or directory" is the single most likely thing to go wrong when a
 * tool shells out.
 */
int sysl_proc_run(const char *program, char *const *argv,
                  const char *const *env_names, const char *const *env_values,
                  const char *dir, const char *out_path, int *code, int *sig) {
    /* **Everything this program has written, written, before anything else can write.**
     *
     * A C library buffers standard output, and it buffers it *fully* rather than by line whenever
     * the destination is not a terminal -- a pipe, a file, a CI log. The child writes to the same
     * file description directly and is not buffered by anything of ours, so without this its output
     * lands ahead of text the parent printed first and the log reads in the wrong order. It looks
     * like the parent forgot to say what it was doing.
     *
     * `NULL` flushes every output stream rather than just `stdout`, which is what makes it correct
     * for a program writing to both channels: they are separately buffered and would otherwise be
     * separately out of order.
     *
     * It is also the reason this belongs to the fork rather than to the caller. Any buffered bytes
     * still held here are duplicated into the child by `fork`, and a child that did something other
     * than `exec` immediately would print them a second time -- flushing first is what makes that
     * unreachable rather than merely unlikely.
     */
    fflush(NULL);

    int report[2];

    if (pipe(report) != 0) return errno;

    if (fcntl(report[1], F_SETFD, FD_CLOEXEC) != 0) {
        int e = errno;

        close(report[0]);
        close(report[1]);
        return e;
    }

    pid_t pid = fork();

    if (pid < 0) {
        int e = errno;

        close(report[0]);
        close(report[1]);
        return e;
    }

    if (pid == 0) {
        close(report[0]);

        int e = child_setup(env_names, env_values, dir, out_path);

        if (e == 0) {
            execvp(program, argv);
            e = errno;
        }

        /* The parent is about to learn why from the pipe; the status is the shell's convention for
         * a command that could not be run, and is what a caller sees if the write is lost. */
        ssize_t ignored = write(report[1], &e, sizeof e);

        (void) ignored;
        _exit(127);
    }

    close(report[1]);

    int child_errno = 0;
    ssize_t got = read(report[0], &child_errno, sizeof child_errno);

    close(report[0]);

    int status = 0;

    /* `waitpid` is restarted rather than abandoned on `EINTR`: a signal arriving here would
     * otherwise turn a perfectly ordinary child into a failure, and leave it to be reaped by
     * nobody. */
    while (waitpid(pid, &status, 0) < 0) {
        if (errno != EINTR) return errno;
    }

    if (got == (ssize_t) sizeof child_errno && child_errno != 0) return child_errno;

    if (WIFEXITED(status)) {
        *code = WEXITSTATUS(status);
        *sig = 0;
    } else if (WIFSIGNALED(status)) {
        *code = 0;
        *sig = WTERMSIG(status);
    } else {
        *code = 0;
        *sig = 0;
    }

    return 0;
}

/* A path nothing else holds, created empty so that it stays that way, written into the caller's
 * own buffer.
 *
 * `mkstemp` rather than a name built from a clock and a counter, because it creates the file and
 * hands back the descriptor in one step -- there is no window in which a second process could take
 * the same name. The descriptor is closed straight away: what the caller wants is the path, to hand
 * to a child as its standard output.
 */
int sysl_proc_temp_path(char *buf, size_t n) {
    const char *tmp = getenv("TMPDIR");

    if (!tmp || !tmp[0]) tmp = "/tmp";

    int wrote = snprintf(buf, n, "%s/sysl-proc-XXXXXX", tmp);

    if (wrote < 0 || (size_t) wrote >= n) return ENAMETOOLONG;

    int fd = mkstemp(buf);

    if (fd < 0) return errno;

    close(fd);
    return 0;
}
