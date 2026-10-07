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
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

/* Whether a child is started with `posix_spawnp` rather than `fork` and `execvp` -- see
 * `start_spawned`. It needs `posix_spawn_file_actions_addchdir_np` for a child's directory, which
 * macOS has had since 10.15 and glibc since 2.29; anywhere else keeps the `fork` path. */
#if defined(__APPLE__) || (defined(__GLIBC__) && \
    (__GLIBC__ > 2 || (__GLIBC__ == 2 && __GLIBC_MINOR__ >= 29)))
#define SYSL_PROC_SPAWNS 1
#include <spawn.h>
#include <string.h>
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

/* One of the child's streams pointed at a file the parent named. Answers an `errno`, or zero.
 *
 * A path that is empty, or absent, means the stream is left alone -- so a capture of standard
 * output only, of standard error only, or of both is the same code path with a different pair of
 * arguments, and there is no combination the caller can ask for that this does not answer.
 */
static int redirect(const char *path, int fd_no) {
    if (!path || !path[0]) return 0;

    int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0600);

    if (fd < 0) return errno;

    if (dup2(fd, fd_no) < 0) {
        int e = errno;

        close(fd);
        return e;
    }

    close(fd);
    return 0;
}

/* Everything the child does before it becomes the other program, as one function so that the
 * caller below is a straight line. Answers an `errno`, or zero.
 *
 * It allocates nothing and opens at most one descriptor per stream, which is the constraint the
 * whole between-fork-and-exec window is written under.
 */
static int child_setup(const char *const *names, const char *const *values,
                       const char *dir, const char *out_path, const char *err_path) {
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

    int e = redirect(out_path, STDOUT_FILENO);

    if (e != 0) return e;

    return redirect(err_path, STDERR_FILENO);
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
 */
__attribute__((unused))
static int start_forked(const char *program, char *const *argv,
                        const char *const *env_names, const char *const *env_values,
                        const char *dir, const char *out_path, const char *err_path,
                        int *pid_out, long long *started_ms) {
    int report[2];

    if (pipe(report) != 0) return errno;

    if (fcntl(report[1], F_SETFD, FD_CLOEXEC) != 0) {
        int e = errno;

        close(report[0]);
        close(report[1]);
        return e;
    }

    pid_t pid = fork();

    /* The clock starts here, so the timeout covers the child's whole life rather than only the
     * part of it after the exec. */
    long long started_at = now_ms();

    if (pid < 0) {
        int e = errno;

        close(report[0]);
        close(report[1]);
        return e;
    }

    if (pid == 0) {
        close(report[0]);

        int e = child_setup(env_names, env_values, dir, out_path, err_path);

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

    if (got == (ssize_t) sizeof child_errno && child_errno != 0) {
        /* The child wrote why and is exiting straight after, so this wait is short; reaping it
         * here is what makes "could not be started" leave nothing behind for anybody to wait on. */
        int status = 0;

        (void) wait_out(pid, &status);
        return child_errno;
    }

    *pid_out = (int) pid;
    *started_ms = started_at;
    return 0;
}

#if SYSL_PROC_SPAWNS

/* Whether `entry` (`NAME=value`) sets the variable `name`. */
static int sets(const char *entry, const char *name) {
    size_t n = strlen(name);

    return strncmp(entry, name, n) == 0 && entry[n] == '=';
}

/* The environment a child is handed: this process's own, with each named variable added or
 * replaced -- what `setenv` in the child did on the `fork` path, the last of two equal names winning
 * as the second `setenv` would. Answers NULL when nothing is named, meaning "this one, unchanged";
 * `*made` collects what was allocated, for `release_environment`. */
static char **child_environment(const char *const *names, const char *const *values,
                                char **current, char ***made, int *err) {
    *made = NULL;

    if (!names || !values || !names[0]) return NULL;

    size_t have = 0;
    size_t named = 0;

    while (current && current[have]) have++;
    while (names[named]) named++;

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
 * so a caller setting `PATH` is asking for this search -- and is only reached then. */
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
 * the named variables added or replaced, then the directory, then each stream opened onto its file.
 * A program that cannot be run is the `errno` `posix_spawnp` answers, so there is nothing to reap.
 */
static int start_spawned(const char *program, char *const *argv,
                         const char *const *env_names, const char *const *env_values,
                         const char *dir, const char *out_path, const char *err_path,
                         int *pid_out, long long *started_ms) {
#if defined(__APPLE__)
    char **current = *_NSGetEnviron();
#else
    char **current = environ;
#endif
    char **made = NULL;
    int err = 0;
    char **env = child_environment(env_names, env_values, current, &made, &err);

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

    const int opened = O_WRONLY | O_CREAT | O_TRUNC;

    if (e == 0 && out_path && out_path[0])
        e = posix_spawn_file_actions_addopen(&actions, STDOUT_FILENO, out_path, opened, 0600);

    if (e == 0 && err_path && err_path[0])
        e = posix_spawn_file_actions_addopen(&actions, STDERR_FILENO, err_path, opened, 0600);

    pid_t pid = 0;
    const char *child_path = NULL;

    for (size_t i = 0; env_names && env_values && env_names[i]; i++) {
        if (strcmp(env_names[i], "PATH") == 0) child_path = env_values[i];
    }

    if (e == 0 && child_path && !strchr(program, '/')) {
        char where[4096];

        e = found_on(program, child_path, where, sizeof where);

        if (e == 0) e = posix_spawn(&pid, where, &actions, NULL, argv, env);
    } else if (e == 0) {
        e = posix_spawnp(&pid, program, &actions, NULL, argv, env ? env : current);
    }

    long long started_at = now_ms();

    posix_spawn_file_actions_destroy(&actions);
    release_environment(env, made);

    if (e != 0) return e;

    *pid_out = (int) pid;
    *started_ms = started_at;
    return 0;
}

#endif

int sysl_proc_start(const char *program, char *const *argv,
                    const char *const *env_names, const char *const *env_values,
                    const char *dir, const char *out_path, const char *err_path,
                    int *pid_out, long long *started_ms) {
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

#if SYSL_PROC_SPAWNS
    return start_spawned(program, argv, env_names, env_values, dir, out_path, err_path, pid_out,
                         started_ms);
#else
    return start_forked(program, argv, env_names, env_values, dir, out_path, err_path, pid_out,
                        started_ms);
#endif
}

/* Wait for a child `sysl_proc_start` began, and say how it ended.
 *
 * Returns 0 having set `*code` and `*sig`, or an `errno`.
 *
 * `timeout_ms` bounds the whole of the child's life, measured from `started_ms`, and zero or less
 * means it is unbounded. On a deadline that arrives first the child is stopped as `stop` above
 * stops one, and `*timed_out` is set, because the exit status of a child that was killed because it
 * ran out of time says nothing a caller wants to hear. **A child that had already ended is asked
 * first**, so one that finished before anybody came to wait for it is reported as it finished
 * rather than as timed out -- it did not outstay anything; its parent was simply busy.
 */
int sysl_proc_wait(int pid, long long started_ms, int timeout_ms,
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

/* End a child nobody is going to wait for, and reap it. Answers 0, or an `errno`.
 *
 * A child that has already ended is only reaped -- it is a zombie until somebody asks, and asking
 * is the whole of the cure. One still running is stopped the way a timeout stops one, because the
 * handle that owned it is gone and so are the files it was writing into: nothing is left that could
 * read what it goes on to do.
 */
int sysl_proc_stop(int pid) {
    int status = 0;
    int err = 0;
    int ended = reaped(pid, &status, &err);

    if (ended < 0) return err;

    if (ended == 1) return 0;

    return stop(pid, &status);
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
