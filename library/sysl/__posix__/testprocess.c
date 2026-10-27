/* What a test process does before its test: keep what it prints, and not outlive its runner -- and
 * what a program `sysl run` started does, which is the second half of that.
 *
 * `sysl test` starts one process per test and waits for it, for at most the test's limit. Both halves
 * of this file serve the case where that wait ends badly.
 *
 * **What the test prints goes out a line at a time.** Standard output is a file here, so stdio holds
 * it in a buffer until the buffer fills or the process exits -- and a test stopped for running too
 * long, or killed by a trap, does not exit, so everything it had printed went with it, which is the
 * very thing a reader of a hung test most wants to see. Line buffering costs a write per line; a test
 * is not where output volume is the point.
 *
 * **A test process that outlives its runner ends itself.** If the runner goes -- killed, interrupted,
 * its terminal closed, a job scheduler giving up on it -- nothing waits any more, and a test that was
 * looping keeps looping under `init`, at full speed, until somebody finds it. A runner cannot clean up
 * after a death it does not survive (`SIGKILL` gives it no chance), so the test does it: it watches its
 * parent, and when the parent is gone it takes itself and its process group down.
 *
 * **The group, because the runner gives each test one of its own.** Whatever the test started -- a
 * compiler, a server, a child that forked again -- is in it, so ending the group leaves nothing of the
 * test behind. A test binary run by hand is in its shell's group instead, and then only the process
 * itself is ended: that group is the shell's, and not the test's to end.
 *
 * **A thread that asks, rather than a signal that tells**, because neither platform has a signal for
 * this that reaches a group: Linux's `PR_SET_PDEATHSIG` signals the one process, and macOS has nothing
 * like it. A process whose parent has gone is adopted, and its parent's number changes; the thread
 * asks for it ten times a second. It holds no descriptor -- a test that checks which descriptor it is
 * handed next is handed the one it expects -- blocks every signal, so a handler the test installs runs
 * on the thread it expects, and after it starts it allocates nothing and takes no lock.
 *
 * **A parent of 1 is not taken to mean gone**, since a runner may itself be the first process of a
 * container; the parent recorded is whatever it is when the test starts, and only a change counts.
 *
 * `gen_test_main` emits the call first in a test build's entry point, and only for a POSIX machine;
 * this file is selected by the `__posix__` directory it sits in, as `stackguard.c` is.
 *
 * **A program `sysl run` started does not outlive its runner either** (`sysl_run_process_start`).
 * The runner and the program share a process group, so Ctrl-C at a terminal reaches both; a signal
 * sent to the runner alone -- `kill <pid>` of a `sysl run … &`, a job scheduler stopping it -- reaches
 * only the runner, and the program went on under `init`, a window and all. The same watch ends it,
 * with two differences from a test's. **The runner is named, not guessed**: `sysl run` puts its own
 * pid in `SYSL_RUN_PARENT`, so a runner that died before this thread began is seen at once rather
 * than recorded as the parent, and a program run any other way watches nothing. The variable is
 * taken out of the environment before the program's own code runs, so the program and whatever it
 * starts see the environment they always did. **And the program is asked first**: `SIGTERM`, which
 * a program that ignores nothing dies of and a program that watches it can clean up after, and
 * `SIGKILL` two seconds later for one that is still there. Only the process itself is ended -- the
 * group is the runner's caller's, not the program's.
 */

#include <pthread.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <time.h>
#include <unistd.h>

static pid_t sysl_test_parent;
static pid_t sysl_run_parent;

static void *sysl_watch_parent(void *unused) {
    (void)unused;

    struct timespec nap = { 0, 100 * 1000 * 1000 };

    while (getppid() == sysl_test_parent) nanosleep(&nap, 0);

    if (getpgrp() == getpid()) kill(0, SIGKILL);

    kill(getpid(), SIGKILL);
    return 0;
}

static void *sysl_watch_runner(void *unused) {
    (void)unused;

    struct timespec nap = { 0, 100 * 1000 * 1000 };

    while (getppid() == sysl_run_parent) nanosleep(&nap, 0);

    kill(getpid(), SIGTERM);

    for (int i = 0; i < 20; i++) nanosleep(&nap, 0);

    kill(getpid(), SIGKILL);
    return 0;
}

/* A detached thread running `watch`, started with every signal blocked so that it inherits that mask
 * and the thread that called this keeps its own. */
static void sysl_start_watch(void *(*watch)(void *)) {
    sigset_t all, before;
    pthread_t thread;
    pthread_attr_t attr;

    sigfillset(&all);
    pthread_sigmask(SIG_SETMASK, &all, &before);
    pthread_attr_init(&attr);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
    pthread_attr_setstacksize(&attr, 64 * 1024);
    pthread_create(&thread, &attr, watch, 0);
    pthread_attr_destroy(&attr);
    pthread_sigmask(SIG_SETMASK, &before, 0);
}

void sysl_test_process_start(void) {
    setvbuf(stdout, 0, _IOLBF, 0);

    sysl_test_parent = getppid();
    sysl_start_watch(sysl_watch_parent);
}

void sysl_run_process_start(void) {
    const char *named = getenv("SYSL_RUN_PARENT");

    if (named == 0) return;

    long runner = strtol(named, 0, 10);

    unsetenv("SYSL_RUN_PARENT");

    if (runner <= 0 || (pid_t)runner != runner) return;

    sysl_run_parent = (pid_t)runner;
    sysl_start_watch(sysl_watch_runner);
}
