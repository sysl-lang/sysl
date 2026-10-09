/* Running a child and waiting for it: what `sysl.process.hosted` answers `sysl.process.sys`'s hooks
 * with on a POSIX host. Each function exported here answers 0 or an `errno`, and the hook beside it
 * negates that.
 *
 * This is a shim for the same reason `sysl/fs/hosted/__posix__/paths.c` is one: what the module needs
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
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/wait.h>
#include <termios.h>
#include <time.h>
#include <unistd.h>

/* Whether a child is started with `posix_spawnp` rather than `fork` and `execvp` -- see
 * `start_spawned`. It needs `posix_spawn_file_actions_addchdir_np` for a child's directory, which
 * macOS has had since 10.15 and glibc since 2.29; anywhere else keeps the `fork` path. */
#if defined(__APPLE__) || (defined(__GLIBC__) && \
    (__GLIBC__ > 2 || (__GLIBC__ == 2 && __GLIBC_MINOR__ >= 29)))
#define SYSL_PROC_SPAWNS 1
#include <spawn.h>
#if defined(__APPLE__)
#include <crt_externs.h>
#else
extern char **environ;
#endif
#else
#define SYSL_PROC_SPAWNS 0
#endif

/* How long a child that has been asked to stop is given before it is made to.
 *
 * Deliberately short. A child that means to tidy up on `SIGTERM` has already had the whole of its
 * timeout to finish, and one that ignores the signal is not going to honour a longer wait either --
 * the caller asked for a bound, and the grace is part of it rather than an extension to it.
 */
#define SYSL_PROC_GRACE_MS 200

/* The longest a bounded wait sleeps between asking whether the child has ended.
 *
 * It starts at a millisecond and doubles up to this, so a child that ends at once is noticed at
 * once and one that runs for an hour is not asked about a thousand times a second.
 */
#define SYSL_PROC_NAP_MAX_MS 20

/* The monotonic clock in milliseconds, which is what a deadline is measured against.
 *
 * Monotonic rather than the wall clock, because a deadline compared against a clock somebody can
 * set backwards is a deadline that can be moved after the fact -- an NTP step during a long child
 * would either cut its timeout short or extend it indefinitely.
 */
static long long now_ms(void) {
    struct timespec ts = {0, 0};

    clock_gettime(CLOCK_MONOTONIC, &ts);

    return (long long) ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

/* Sleeps for `ms`, or for however much of it a signal leaves.
 *
 * What is left of an interrupted sleep is not carried over: the cost of cutting one short is
 * looking at the child a little early, and the deadline is checked against the clock rather than
 * against a count of naps, so nothing drifts.
 */
static void nap(long long ms) {
    struct timespec want = { (time_t) (ms / 1000), (long) (ms % 1000) * 1000000L };

    nanosleep(&want, NULL);
}

/* One of the child's streams pointed at a descriptor the parent opened. Answers an `errno`, or zero.
 *
 * A negative descriptor means the stream is left alone -- so a capture of standard output only, of
 * standard error only, or of both is the same code path with different arguments, and there is no
 * combination the caller can ask for that this does not answer. One that already is the stream is
 * left alone too.
 */
static int place(int fd, int fd_no) {
    if (fd < 0) return 0;

    /* A descriptor that already is the stream -- a pipe made while this program had that stream
     * closed -- is the child's only if it stays open across the `exec`, and a pipe's ends are made
     * to close there. */
    if (fd == fd_no) return fcntl(fd, F_SETFD, 0) < 0 ? errno : 0;

    return dup2(fd, fd_no) < 0 ? errno : 0;
}

/* Whether `fd` is a descriptor the parent handed over that is not one of the three a child is
 * started with, and so is closed in the child once it has been placed. */
static int handed(int fd) {
    return fd > STDERR_FILENO;
}

/* The terminal `tty` made to give its keys to process group `pgid`, answering 0 or an `errno`.
 *
 * **`SIGTTOU` is held back for the length of the call**, which is what a shell does: a process outside
 * the terminal's foreground group that changes it is otherwise stopped by that signal, and a shell
 * taking the terminal back after a job -- or a child handing it to its own new group -- is exactly such
 * a process. Blocked, POSIX lets the change through and sends nothing. The thread's own mask is what
 * changes, and it is put back as it was, so a signal the program itself blocks stays blocked.
 */
static int handed_terminal(int tty, pid_t pgid) {
    sigset_t ttou;
    sigset_t was;

    sigemptyset(&ttou);
    sigaddset(&ttou, SIGTTOU);

    int e = pthread_sigmask(SIG_BLOCK, &ttou, &was);

    if (e != 0) return e;

    int r = tcsetpgrp(tty, pgid) == 0 ? 0 : errno;

    (void) pthread_sigmask(SIG_SETMASK, &was, NULL);
    return r;
}

/* The child put in its process group and, where `tty` is a terminal's descriptor rather than -1, that
 * group made the terminal's foreground -- both before anything else the child does, so the program it
 * becomes runs from its first instruction where a shell's job control expects it. `pgid` is -1 to stay
 * in the parent's group, 0 for a group of its own, or the group to join. Answers 0 or an `errno`. */
static int child_group(int pgid, int tty) {
    if (pgid < 0) return 0;
    if (setpgid(0, pgid) != 0) return errno;

    return tty < 0 ? 0 : handed_terminal(tty, getpgrp());
}

/* Everything the child does before it becomes the other program, as one function so that the
 * caller below is a straight line. Answers an `errno`, or zero.
 *
 * It allocates nothing and opens nothing, which is the constraint the whole between-fork-and-exec
 * window is written under: the descriptors were opened by the parent, the terminal's among them.
 */
static int child_setup(const char *const *names, const char *const *values, const char *dir,
                       int in_fd, int out_fd, int err_fd, int pgid, int tty) {
    int grouped = child_group(pgid, tty);

    if (grouped != 0) return grouped;

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

    int e = place(in_fd, STDIN_FILENO);

    if (e == 0) e = place(out_fd, STDOUT_FILENO);
    if (e == 0) e = place(err_fd, STDERR_FILENO);
    if (e != 0) return e;

    /* The originals are the parent's, and the child has its copies where they belong. */
    if (handed(in_fd)) close(in_fd);
    if (handed(out_fd)) close(out_fd);
    if (handed(err_fd)) close(err_fd);

    return 0;
}

/* Whether the child has ended, reaping it if it has.
 *
 * Answers 1 for ended, 0 for still running, and -1 for a failure whose `errno` is left in `*err`.
 * `EINTR` is retried rather than reported, for the reason the blocking wait below retries it: a
 * signal arriving here is not the child's doing and must not turn it into a failure.
 */
static int reaped(pid_t pid, int *status, int *err) {
    for (;;) {
        pid_t ended = waitpid(pid, status, WNOHANG);

        if (ended == pid) return 1;

        if (ended == 0) return 0;

        if (errno == EINTR) continue;

        *err = errno;
        return -1;
    }
}

/* Wait for the child for as long as it takes. Answers 0, or an `errno`. */
static int wait_out(pid_t pid, int *status) {
    while (waitpid(pid, status, 0) < 0) {
        if (errno != EINTR) return errno;
    }

    return 0;
}

/* Wait for the child until `deadline`. Answers 1 if it ended in time, 0 if the deadline arrived
 * first, and -1 for a failure whose `errno` is left in `*err`.
 *
 * **Polling rather than waiting for `SIGCHLD`, because the alternatives all change state that
 * belongs to the whole program.** A handler, a blocked signal or a `sigtimedwait` is process-wide:
 * installing one here would be a library deciding what a caller's own signal handling looks like,
 * and restoring it afterwards still races with anything else running at the time. `sigtimedwait` is
 * not on macOS in any case. A `WNOHANG` loop asks the kernel a question and leaves nothing behind,
 * and what it costs is a wake-up every few milliseconds while a child runs.
 */
static int wait_until(pid_t pid, int *status, long long deadline, int *err) {
    long long step = 1;

    for (;;) {
        int ended = reaped(pid, status, err);

        if (ended != 0) return ended;

        long long left = deadline - now_ms();

        if (left <= 0) return 0;

        nap(step < left ? step : left);

        if (step < SYSL_PROC_NAP_MAX_MS) step *= 2;
    }
}

/* Asked first and made second, then reaped: the end of a child nobody is going to wait out.
 * Answers 0, or an `errno`.
 *
 * A child that stops on being asked gets to run whatever it does on the way out -- flush what it
 * was writing, remove what it was building -- and one that does not is still gone when this
 * returns. `SIGKILL` cannot be caught or ignored, so the last wait is for something already on its
 * way rather than for the child's cooperation.
 *
 * **The signal goes to the child and not to its process group**, which is the same restraint the
 * module keeps everywhere else: putting the child in a group of its own would take it out of the
 * terminal's foreground group, so a person's own interrupt would stop reaching it. What that costs
 * is that a child which forked grandchildren of its own leaves them behind -- for which the answer
 * is to run the program rather than a shell that runs it, which is what this module does anyway.
 */
static int stop(pid_t pid, int *status) {
    int wait_errno = 0;

    kill(pid, SIGTERM);

    int in_time = wait_until(pid, status, now_ms() + SYSL_PROC_GRACE_MS, &wait_errno);

    if (in_time < 0) return wait_errno;

    if (in_time == 1) return 0;

    kill(pid, SIGKILL);
    return wait_out(pid, status);
}

/* Whether `entry` (`NAME=value`) sets the variable `name`. */
static int sets(const char *entry, const char *name) {
    size_t n = strlen(name);

    return strncmp(entry, name, n) == 0 && entry[n] == '=';
}

/* The environment a child is handed. Inheriting, it is this process's own with each named variable
 * added or replaced -- what `setenv` in the child did on the `fork` path, the last of two equal
 * names winning as the second `setenv` would -- and NULL when nothing is named, meaning "this one,
 * unchanged". Not inheriting, it is the named variables and nothing else, and never NULL: an empty
 * list is an environment with nothing in it, which is a different answer from "this one". `*made`
 * collects what was allocated, for `release_environment`. */
static char **child_environment(const char *const *names, const char *const *values, int inherit,
                                char **current, char ***made, int *err) {
    *made = NULL;

    int none = !names || !values || !names[0];

    if (inherit && none) return NULL;

    size_t have = 0;
    size_t named = 0;

    while (inherit && current && current[have]) have++;
    while (!none && names[named]) named++;

    char **env = calloc(have + named + 1, sizeof *env);
    char **owned = calloc(named + 1, sizeof *owned);

    if (!env || !owned) {
        free(env);
        free(owned);
        *err = ENOMEM;
        return NULL;
    }

    size_t at = 0;

    for (size_t i = 0; i < have; i++) {
        int replaced = 0;

        for (size_t j = 0; j < named && !replaced; j++) replaced = sets(current[i], names[j]);

        if (!replaced) env[at++] = current[i];
    }

    size_t made_n = 0;

    for (size_t j = 0; j < named; j++) {
        int later = 0;

        for (size_t k = j + 1; k < named && !later; k++) later = strcmp(names[j], names[k]) == 0;

        if (later) continue;

        size_t len = strlen(names[j]) + 1 + strlen(values[j]) + 1;
        char *line = malloc(len);

        if (!line) {
            for (size_t m = 0; m < made_n; m++) free(owned[m]);
            free(owned);
            free(env);
            *err = ENOMEM;
            return NULL;
        }

        snprintf(line, len, "%s=%s", names[j], values[j]);
        owned[made_n++] = line;
        env[at++] = line;
    }

    *made = owned;
    return env;
}

/* Where `program` is found on the `PATH` the CHILD is given, written into `buf`. Answers 0, or an
 * `errno`: ENOENT where nothing on it is called that, EACCES where something is and may not be run.
 *
 * `posix_spawnp` searches the parent's `PATH`, while `execvp` after a `setenv` searched the child's,
 * so a caller setting `PATH`, or replacing the environment, is asking for this search -- and is only
 * reached then. */
static int found_on(const char *program, const char *dirs, char *buf, size_t n) {
    int refused = 0;
    const char *at = dirs;

    for (;;) {
        const char *end = strchr(at, ':');
        size_t len = end ? (size_t) (end - at) : strlen(at);
        int wrote = len == 0 ? snprintf(buf, n, "./%s", program)
                             : snprintf(buf, n, "%.*s/%s", (int) len, at, program);

        if (wrote > 0 && (size_t) wrote < n) {
            if (access(buf, X_OK) == 0) return 0;

            if (errno == EACCES) refused = 1;
        }

        if (!end) break;

        at = end + 1;
    }

    return refused ? EACCES : ENOENT;
}

static void release_environment(char **env, char **made) {
    if (made) {
        for (size_t m = 0; made[m]; m++) free(made[m]);
    }

    free(made);
    free(env);
}

/* The directories a bare program name is looked for in when the parent's own search will not do,
 * or NULL when it will.
 *
 * A `PATH` the caller named is the child's, so it is searched -- the last one, as the last `setenv`
 * would have left it. **Replacing the environment without naming one leaves the child with no
 * `PATH` at all**, and then the program is looked for on the system's default search path,
 * `confstr(_CS_PATH)` -- where `execvp` itself looks when a process has no `PATH`, and what libuv,
 * Python's `subprocess` and Rust's `Command` all do with a replaced environment. Never the parent's
 * `PATH`: the caller asked for a child that inherits nothing, and where its program comes from is
 * part of what it inherits. */
static const char *search_dirs(const char *const *names, const char *const *values, int inherit,
                               char *buf, size_t n) {
    const char *named = NULL;

    for (size_t i = 0; names && values && names[i]; i++) {
        if (strcmp(names[i], "PATH") == 0) named = values[i];
    }

    if (named || inherit) return named;

    size_t need = confstr(_CS_PATH, buf, n);

    if (need == 0 || need > n) snprintf(buf, n, "%s", "/usr/bin:/bin");

    return buf;
}

/* Start `program` and answer which child it became, without waiting for it.
 *
 * Returns 0 having set `*pid_out` and `*started_ms`, or an `errno` if the child could not be
 * started at all -- in which case there is no child left over: the one that failed to exec has
 * already been reaped here, so a caller has nothing to wait for and nothing to clean up.
 *
 * `*started_ms` is the monotonic clock at the fork, which is what a timeout is measured from, so
 * that a bound covers the child's whole life rather than only the part after somebody began waiting.
 *
 * **The pipe is how a failed `execvp` is told from a program that ran and exited 127**, which is
 * the distinction a caller most wants and the one `system(3)` cannot make. It is close-on-exec, so
 * a successful exec closes it and the parent's read sees end-of-file; a failure writes the `errno`
 * into it first. Without this, a missing program and a program whose own exit status is 127 are the
 * same answer -- and "no such file or directory" is the single most likely thing to go wrong when a
 * tool shells out.
 *
 * **A replaced environment is built here, in the parent, and handed to `execve` whole** -- the
 * child may not allocate, and `setenv` can only add. The program is looked for here too, on the
 * directories `search_dirs` names, so a program that is not there is answered before anything is
 * forked, exactly as `posix_spawn` answers it on the other path.
 *
 * **A group and a terminal are set from both sides of the fork**, as a shell sets them: the child does
 * it before anything else (`child_group`), and the parent does it again the moment `fork` returns. The
 * child's is what makes the program run where it should from its first instruction; the parent's is
 * what makes the group exist, and the terminal be its, however the two processes are scheduled -- and
 * a refusal the parent earns because the child has already done it (or already become the program,
 * `EACCES`) is no failure. **What fails a start is the child's**, reported through the pipe like a
 * failed exec; the terminal is then handed back to whichever group had it, since the group it was
 * given to has just ended. `tty` is -1 unless the group is to be the foreground.
 */
__attribute__((unused))
static int start_forked(const char *program, char *const *argv,
                        const char *const *env_names, const char *const *env_values, int inherit,
                        const char *dir, int in_fd, int out_fd, int err_fd,
                        int *pid_out, long long *started_ms, int pgid, int tty) {
    char **env = NULL;
    char **made = NULL;
    char where[4096];
    const char *run = program;

    if (!inherit) {
        int err = 0;

        env = child_environment(env_names, env_values, 0, NULL, &made, &err);

        if (err != 0) return err;

        char dirs[1024];

        if (!strchr(program, '/')) {
            int e = found_on(program, search_dirs(env_names, env_values, 0, dirs, sizeof dirs),
                             where, sizeof where);

            if (e != 0) {
                release_environment(env, made);
                return e;
            }

            run = where;
        }
    }

    int report[2];

    if (pipe(report) != 0) {
        int e = errno;

        release_environment(env, made);
        return e;
    }

    if (fcntl(report[1], F_SETFD, FD_CLOEXEC) != 0) {
        int e = errno;

        close(report[0]);
        close(report[1]);
        release_environment(env, made);
        return e;
    }

    /* Who has the terminal now, to give it back to if the child never becomes the program. */
    pid_t had = tty >= 0 ? tcgetpgrp(tty) : -1;

    pid_t pid = fork();

    /* The clock starts here, so the timeout covers the child's whole life rather than only the
     * part of it after the exec. */
    long long started_at = now_ms();

    if (pid < 0) {
        int e = errno;

        close(report[0]);
        close(report[1]);
        release_environment(env, made);
        return e;
    }

    if (pid == 0) {
        close(report[0]);

        int e = inherit ? child_setup(env_names, env_values, dir, in_fd, out_fd, err_fd, pgid, tty)
                        : child_setup(NULL, NULL, dir, in_fd, out_fd, err_fd, pgid, tty);

        if (e == 0) {
            if (inherit) execvp(program, argv);
            else execve(run, argv, env);

            e = errno;
        }

        /* The parent is about to learn why from the pipe; the status is the shell's convention for
         * a command that could not be run, and is what a caller sees if the write is lost. */
        ssize_t ignored = write(report[1], &e, sizeof e);

        (void) ignored;
        _exit(127);
    }

    close(report[1]);
    release_environment(env, made);

    if (pgid >= 0) {
        pid_t group = pgid == 0 ? pid : pgid;

        (void) setpgid(pid, group);

        if (tty >= 0) (void) handed_terminal(tty, group);
    }

    int child_errno = 0;
    ssize_t got = read(report[0], &child_errno, sizeof child_errno);

    close(report[0]);

    if (got == (ssize_t) sizeof child_errno && child_errno != 0) {
        /* The child wrote why and is exiting straight after, so this wait is short; reaping it
         * here is what makes "could not be started" leave nothing behind for anybody to wait on. */
        int status = 0;

        (void) wait_out(pid, &status);

        if (tty >= 0 && had > 0) (void) handed_terminal(tty, had);

        return child_errno;
    }

    *pid_out = (int) pid;
    *started_ms = started_at;
    return 0;
}

#if SYSL_PROC_SPAWNS

/* The same child `start_forked` makes, made by `posix_spawnp`.
 *
 * **`fork` copies the parent's address space, and what that costs grows with the parent's heap.**
 * Copy-on-write spares the bytes but not the map: every page of a heap made of many small objects
 * has to be marked, so a parent holding a gigabyte and a half of them paid about ten milliseconds a
 * child and one holding six paid forty -- a test runner that has just compiled a large program pays
 * it once per test. `posix_spawn` starts the child without copying anything, at the same price
 * whatever the parent holds.
 *
 * What the child is handed is what the `fork` path gave it, in the same order: the environment with
 * the named variables added or replaced (or, not inheriting, the named variables alone), then the
 * directory, then each stream placed onto its descriptor. A program that cannot be run is the `errno`
 * `posix_spawnp` answers, so there is nothing to reap. A process group is `POSIX_SPAWN_SETPGROUP`,
 * set in the child before it runs, exactly as `child_group` sets it; a group that is also to have the
 * terminal takes the `fork` path instead, `posix_spawn` having no portable way to hand one over.
 */
static int start_spawned(const char *program, char *const *argv,
                         const char *const *env_names, const char *const *env_values, int inherit,
                         const char *dir, int in_fd, int out_fd, int err_fd,
                         int *pid_out, long long *started_ms, int pgid) {
#if defined(__APPLE__)
    char **current = *_NSGetEnviron();
#else
    char **current = environ;
#endif
    char **made = NULL;
    int err = 0;
    char **env = child_environment(env_names, env_values, inherit, current, &made, &err);

    if (err != 0) return err;

    posix_spawn_file_actions_t actions;
    int e = posix_spawn_file_actions_init(&actions);

    if (e != 0) {
        release_environment(env, made);
        return e;
    }

    /* The `_np` spelling is the one every macOS since 10.15 and glibc since 2.29 has; macOS 26
     * deprecates it for a plain name older systems lack. */
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wdeprecated-declarations"
    if (dir && dir[0]) e = posix_spawn_file_actions_addchdir_np(&actions, dir);
#pragma clang diagnostic pop

    /* Each stream onto the descriptor the parent opened for it, then the originals closed in the
     * child -- each once, since two streams may share one. */
    const int fds[3] = { in_fd, out_fd, err_fd };

    for (int i = 0; i < 3 && e == 0; i++) {
        if (fds[i] >= 0 && fds[i] != i) e = posix_spawn_file_actions_adddup2(&actions, fds[i], i);

        /* One that already is the stream is not copied, so it must not close at the `exec` either --
         * which a pipe's ends are made to. A standard stream is never meant to. */
        if (e == 0 && fds[i] == i && fcntl(i, F_SETFD, 0) < 0) e = errno;
    }

    for (int i = 0; i < 3 && e == 0; i++) {
        int seen = !handed(fds[i]);

        for (int j = 0; j < i && !seen; j++) seen = fds[j] == fds[i];

        if (!seen) e = posix_spawn_file_actions_addclose(&actions, fds[i]);
    }

    posix_spawnattr_t attr;
    posix_spawnattr_t *attrs = NULL;

    if (e == 0 && pgid >= 0) {
        e = posix_spawnattr_init(&attr);

        if (e == 0) {
            attrs = &attr;
            e = posix_spawnattr_setflags(&attr, POSIX_SPAWN_SETPGROUP);
        }

        if (e == 0) e = posix_spawnattr_setpgroup(&attr, pgid);
    }

    pid_t pid = 0;
    char dirs[1024];
    const char *child_path = search_dirs(env_names, env_values, inherit, dirs, sizeof dirs);

    if (e == 0 && child_path && !strchr(program, '/')) {
        char where[4096];

        e = found_on(program, child_path, where, sizeof where);

        if (e == 0) e = posix_spawn(&pid, where, &actions, attrs, argv, env);
    } else if (e == 0) {
        e = posix_spawnp(&pid, program, &actions, attrs, argv, env ? env : current);
    }

    long long started_at = now_ms();

    if (attrs) posix_spawnattr_destroy(attrs);

    posix_spawn_file_actions_destroy(&actions);
    release_environment(env, made);

    if (e != 0) return e;

    *pid_out = (int) pid;
    *started_ms = started_at;
    return 0;
}

#endif

/* A run of bytes as `sysl.process.sys`'s `Text` lays it out: lent, and not terminated. */
typedef struct {
    const char *ptr;
    size_t len;
} sysl_text;

/* `n` bytes at `p` as a C string of their own, or NULL where there is no memory for one. */
static char *terminated(const char *p, size_t n) {
    char *s = malloc(n + 1);

    if (!s) return NULL;

    if (n > 0) memcpy(s, p, n);

    s[n] = '\0';
    return s;
}

/* Every string `begin` made, so that one call frees them whichever way it ended. */
typedef struct {
    char **owned;
    size_t count;
} made_strings;

static char *keep(made_strings *m, const char *p, size_t n) {
    char *s = terminated(p, n);

    if (s) m->owned[m->count++] = s;

    return s;
}

static void release_strings(made_strings *m) {
    for (size_t i = 0; i < m->count; i++) free(m->owned[i]);

    free(m->owned);
}

/* The controlling terminal, opened by name: the terminal this program's session belongs to, whatever
 * its standard streams have been pointed at. A program with none is refused here with `ENXIO`. It
 * closes when a program is started, so a child holds it only for as long as it is setting itself up. */
static int controlling_terminal(int *fd) {
    int t = open("/dev/tty", O_RDWR | O_NOCTTY | O_CLOEXEC);

    if (t < 0) return errno;

    *fd = t;
    return 0;
}

static int begin(const char *program, char *const *argv,
                 const char *const *env_names, const char *const *env_values, int inherit,
                 const char *dir, int in_fd, int out_fd, int err_fd,
                 int *pid_out, long long *started_ms, int pgid, int foreground) {
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

    /* A group that is to have the terminal is started by `fork`, which is the one path that can hand
     * it over in the child before the program runs. With no terminal nothing is started at all. */
    if (foreground && pgid >= 0) {
        int tty = -1;
        int e = controlling_terminal(&tty);

        if (e != 0) return e;

        e = start_forked(program, argv, env_names, env_values, inherit, dir, in_fd, out_fd, err_fd,
                         pid_out, started_ms, pgid, tty);
        close(tty);
        return e;
    }

#if SYSL_PROC_SPAWNS
    return start_spawned(program, argv, env_names, env_values, inherit, dir, in_fd, out_fd, err_fd,
                         pid_out, started_ms, pgid);
#else
    return start_forked(program, argv, env_names, env_values, inherit, dir, in_fd, out_fd, err_fd,
                        pid_out, started_ms, pgid, -1);
#endif
}

/* Start `program` with `argv` (`argc` texts, its own name first) and the `envc` `NAME=VALUE` texts
 * of `envp`, answering 0 having set `*pid_out` and `*started_ms`, or an `errno`.
 *
 * The texts are copied into C strings here, the argument vector and the two arrays `child_setup`
 * and `child_environment` walk -- one of names, one of values, split at each entry's first `=` --
 * and freed before this returns: the child has its own copies by then, or never started.
 */
int sysl_proc_posix_start(const char *program, size_t program_len,
                          const sysl_text *argv, size_t argc, const sysl_text *envp, size_t envc,
                          int inherit, const char *dir, size_t dir_len,
                          int in_fd, int out_fd, int err_fd, int *pid_out, long long *started_ms,
                          int pgid, int foreground) {
    made_strings m = { calloc(2 + argc + 2 * envc, sizeof(char *)), 0 };
    char **args = calloc(argc + 1, sizeof(char *));
    const char **names = calloc(envc + 1, sizeof(char *));
    const char **values = calloc(envc + 1, sizeof(char *));
    int e = (m.owned && args && names && values) ? 0 : ENOMEM;

    const char *path = e == 0 ? keep(&m, program, program_len) : NULL;
    const char *where = e == 0 ? keep(&m, dir, dir_len) : NULL;

    if (!path || !where) e = ENOMEM;

    for (size_t i = 0; i < argc && e == 0; i++) {
        args[i] = keep(&m, argv[i].ptr, argv[i].len);

        if (!args[i]) e = ENOMEM;
    }

    for (size_t i = 0; i < envc && e == 0; i++) {
        const char *at = envp[i].len > 0 ? memchr(envp[i].ptr, '=', envp[i].len) : NULL;
        size_t name_len = at ? (size_t) (at - envp[i].ptr) : envp[i].len;
        size_t rest = at ? envp[i].len - name_len - 1 : 0;

        names[i] = keep(&m, envp[i].ptr, name_len);
        values[i] = keep(&m, at ? at + 1 : "", rest);

        if (!names[i] || !values[i]) e = ENOMEM;
    }

    if (e == 0) {
        e = begin(path, args, names, values, inherit, where, in_fd, out_fd, err_fd, pid_out,
                  started_ms, pgid, foreground);
    }

    if (m.owned) release_strings(&m);

    free(args);
    free(names);
    free(values);
    return e;
}

/* Wait for a child `sysl_proc_posix_start` began, and say how it ended.
 *
 * Returns 0 having set `*code` and `*sig`, or an `errno`.
 *
 * `timeout_ms` bounds the whole of the child's life, measured from `started_ms`, and zero or less
 * means it is unbounded. On a deadline that arrives first the child is stopped as `stop` above
 * stops one, and `*timed_out` is set, because the exit status of a child that was killed because it
 * ran out of time says nothing a caller wants to hear. **A child that had already ended is asked
 * first**, so one that finished before anybody came to wait for it is reported as it finished
 * rather than as timed out -- it did not outstay anything; its parent was simply busy.
 *
 * **A deadline already past is how a child nobody is going to wait for is ended**: one that has
 * ended is only reaped -- it is a zombie until somebody asks, and asking is the whole of the cure --
 * and one still running is stopped the way a timeout stops one.
 */
int sysl_proc_posix_wait(int pid, long long started_ms, long long timeout_ms,
                         int *code, int *sig, int *timed_out) {
    *timed_out = 0;

    int status = 0;

    if (timeout_ms <= 0) {
        int e = wait_out(pid, &status);

        if (e != 0) return e;
    } else {
        int wait_errno = 0;
        int in_time = wait_until(pid, &status, started_ms + timeout_ms, &wait_errno);

        if (in_time < 0) return wait_errno;

        if (in_time == 0) {
            int e = stop(pid, &status);

            if (e != 0) return e;

            *timed_out = 1;
        }
    }

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

/* A pipe whose two descriptors close when a program is started, answering zero or an `errno`.
 *
 * Close-on-exec is the whole of what makes a pipeline end: a child is started with every descriptor
 * its parent has that is not so marked, so a write end left unmarked would be inherited by the child
 * reading the other end, which would then wait for a writer that is itself. A child receives an end
 * only as one of its three streams, where `dup2` makes a copy that is not marked.
 *
 * The mark is set straight after the pipe is made rather than in the same call: `pipe2` would close
 * the window in which another thread starting a child inherits an unmarked end, but macOS has no
 * `pipe2`, and glibc declares it only under `_GNU_SOURCE`, which would change what this file's other
 * declarations mean.
 */
int sysl_proc_posix_pipe(int *read_end, int *write_end) {
    int fds[2];

    if (pipe(fds) != 0) return errno;

    for (int i = 0; i < 2; i++) {
        if (fcntl(fds[i], F_SETFD, FD_CLOEXEC) != 0) {
            int e = errno;

            close(fds[0]);
            close(fds[1]);
            return e;
        }
    }

    *read_end = fds[0];
    *write_end = fds[1];
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
int sysl_proc_posix_temp_path(char *buf, size_t n) {
    const char *tmp = getenv("TMPDIR");

    if (!tmp || !tmp[0]) tmp = "/tmp";

    int wrote = snprintf(buf, n, "%s/sysl-proc-XXXXXX", tmp);

    if (wrote < 0 || (size_t) wrote >= n) return ENAMETOOLONG;

    int fd = mkstemp(buf);

    if (fd < 0) return errno;

    close(fd);
    return 0;
}

/* Replace this program with `program`, keeping the process, its id and its descriptors. Answers only
 * where that failed, with an `errno` -- and then this program is exactly as it was: the environment
 * is built beside the current one rather than into it, and the program is looked for before anything
 * is given up.
 *
 * **The environment and the search are `start_spawned`'s**, so `exec` and `run` agree about which
 * program a name means and which variables it sees: inheriting, this program's own with each named
 * variable added or replaced; not inheriting, the named ones alone; a bare name looked for on the
 * `PATH` the new image will have -- the named one, this program's, or, where a replaced environment
 * names none, the system's default search path.
 *
 * **Every output stream is flushed first.** A C library buffers standard output fully whenever it is
 * not a terminal, and the buffer lives in the image `execve` throws away -- so text this program
 * printed just before it became another would otherwise never be written at all.
 */
static int replace_image(const char *program, char *const *argv,
                         const char *const *env_names, const char *const *env_values, int inherit) {
#if defined(__APPLE__)
    char **current = *_NSGetEnviron();
#else
    extern char **environ;
    char **current = environ;
#endif
    char **made = NULL;
    int err = 0;
    char **env = child_environment(env_names, env_values, inherit, current, &made, &err);

    if (err != 0) return err;

    char dirs[1024];
    const char *search = search_dirs(env_names, env_values, inherit, dirs, sizeof dirs);

    if (!search) search = getenv("PATH");

    if (!search) search = search_dirs(NULL, NULL, 0, dirs, sizeof dirs);

    char where[4096];
    const char *run = program;

    if (!strchr(program, '/')) {
        err = found_on(program, search, where, sizeof where);
        run = where;
    }

    if (err == 0) {
        fflush(NULL);
        execve(run, argv, env ? env : current);
        err = errno;
    }

    release_environment(env, made);
    return err;
}

/* Become `program` with `argv` (`argc` texts, its own name first) and the `envc` `NAME=VALUE` texts
 * of `envp`, answering an `errno` only where that failed. The texts are copied into C strings as
 * `sysl_proc_posix_start` copies them, and freed on the way out of a failure; a success has no way
 * out, the memory going with the image. */
int sysl_proc_posix_exec(const char *program, size_t program_len,
                         const sysl_text *argv, size_t argc, const sysl_text *envp, size_t envc,
                         int inherit) {
    made_strings m = { calloc(1 + argc + 2 * envc, sizeof(char *)), 0 };
    char **args = calloc(argc + 1, sizeof(char *));
    const char **names = calloc(envc + 1, sizeof(char *));
    const char **values = calloc(envc + 1, sizeof(char *));
    int e = (m.owned && args && names && values) ? 0 : ENOMEM;

    const char *path = e == 0 ? keep(&m, program, program_len) : NULL;

    if (!path) e = ENOMEM;

    for (size_t i = 0; i < argc && e == 0; i++) {
        args[i] = keep(&m, argv[i].ptr, argv[i].len);

        if (!args[i]) e = ENOMEM;
    }

    for (size_t i = 0; i < envc && e == 0; i++) {
        const char *at = envp[i].len > 0 ? memchr(envp[i].ptr, '=', envp[i].len) : NULL;
        size_t name_len = at ? (size_t) (at - envp[i].ptr) : envp[i].len;
        size_t rest = at ? envp[i].len - name_len - 1 : 0;

        names[i] = keep(&m, envp[i].ptr, name_len);
        values[i] = keep(&m, at ? at + 1 : "", rest);

        if (!names[i] || !values[i]) e = ENOMEM;
    }

    if (e == 0) e = replace_image(path, args, names, values, inherit);

    if (m.owned) release_strings(&m);

    free(args);
    free(names);
    free(values);
    return e;
}

/* The controlling terminal's foreground process group, written into `*pgid`; 0 or an `errno` --
 * `ENXIO` for a program with no controlling terminal. */
int sysl_proc_posix_tcgetpgrp(int *pgid) {
    int tty = -1;
    int e = controlling_terminal(&tty);

    if (e != 0) return e;

    pid_t g = tcgetpgrp(tty);

    e = g < 0 ? errno : 0;
    close(tty);

    if (e == 0) *pgid = (int) g;

    return e;
}

/* The controlling terminal's foreground made process group `pgid`, from the foreground or from the
 * background alike (`handed_terminal`); 0 or an `errno`. */
int sysl_proc_posix_tcsetpgrp(int pgid) {
    int tty = -1;
    int e = controlling_terminal(&tty);

    if (e != 0) return e;

    e = handed_terminal(tty, (pid_t) pgid);
    close(tty);
    return e;
}
