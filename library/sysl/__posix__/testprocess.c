/* What a test process does before its test: keep what it prints, and not outlive its runner.
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
 */

#include <pthread.h>
#include <signal.h>
#include <stdio.h>
#include <time.h>
#include <unistd.h>

static pid_t sysl_test_parent;

static void *sysl_watch_parent(void *unused) {
    (void)unused;

    struct timespec nap = { 0, 100 * 1000 * 1000 };

    while (getppid() == sysl_test_parent) nanosleep(&nap, 0);

    if (getpgrp() == getpid()) kill(0, SIGKILL);

    kill(getpid(), SIGKILL);
    return 0;
}

void sysl_test_process_start(void) {
    setvbuf(stdout, 0, _IOLBF, 0);

    sysl_test_parent = getppid();

    sigset_t all, before;
    pthread_t thread;
    pthread_attr_t attr;

    sigfillset(&all);
    pthread_sigmask(SIG_SETMASK, &all, &before);
    pthread_attr_init(&attr);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
    pthread_attr_setstacksize(&attr, 64 * 1024);
    pthread_create(&thread, &attr, sysl_watch_parent, 0);
    pthread_attr_destroy(&attr);
    pthread_sigmask(SIG_SETMASK, &before, 0);
}
