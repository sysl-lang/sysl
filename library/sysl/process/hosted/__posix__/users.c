#include <errno.h>
#include <grp.h>
#include <pwd.h>
#include <stdlib.h>
#include <string.h>
#include <sys/types.h>

// The group database by id, and both databases by name, through the re-entrant `_r` forms only: the
// plain `getgrgid` and `getpwnam` hand back storage the next call overwrites, which no two threads
// may share.
//
// **The scratch buffer grows until the entry fits.** An entry's strings live in the caller's buffer,
// and a group's member list has no bound -- a group of every user on a large site needs far more than
// any fixed guess -- so a call answering `ERANGE` is asked again with twice the room, up to a ceiling
// past which the failure is the answer.
//
// Each answers zero or an `errno`; an entry that is not there is `ENOENT`, which `getpwnam_r` and its
// kin report as zero with a null result, so "no such entry" is told apart from a failed lookup.

#define SYSL_USERS_FIRST 16384
#define SYSL_USERS_CEILING (16 * 1024 * 1024)

// The name `name` of `len` bytes as a terminated copy the caller frees, or null: `ENOENT` where it
// holds a zero byte, which no database entry's name can, and `ENOMEM` where there is no room.
static char *sysl_users_terminated(const unsigned char *name, size_t len, int *e) {
    if (memchr(name, 0, len) != NULL) {
        *e = ENOENT;
        return NULL;
    }

    char *c = malloc(len + 1);

    if (c == NULL) {
        *e = ENOMEM;
        return NULL;
    }

    memcpy(c, name, len);
    c[len] = 0;
    return c;
}

// `name` copied into `into` (`room` bytes, not terminated) with its length in `*len`; a name longer
// than `room` is `ENAMETOOLONG`, never a cut-short success.
static int sysl_users_copy_name(const char *name, unsigned char *into, size_t room, size_t *len) {
    if (name == NULL) return ENOENT;

    size_t n = strlen(name);

    if (n > room) return ENAMETOOLONG;

    memcpy(into, name, n);
    *len = n;
    return 0;
}

int sysl_proc_posix_group_name(unsigned int id, unsigned char *into, size_t room, size_t *len) {
    size_t size = SYSL_USERS_FIRST;

    for (;;) {
        char *scratch = malloc(size);

        if (scratch == NULL) return ENOMEM;

        struct group entry;
        struct group *found = NULL;
        int e = getgrgid_r((gid_t) id, &entry, scratch, size, &found);

        if (e == ERANGE && size < SYSL_USERS_CEILING) {
            free(scratch);
            size *= 2;
            continue;
        }

        if (e == 0) e = found == NULL ? ENOENT : sysl_users_copy_name(found->gr_name, into, room, len);

        free(scratch);
        return e;
    }
}

int sysl_proc_posix_user_id(const unsigned char *name, size_t len, unsigned int *id) {
    int e = 0;
    char *c = sysl_users_terminated(name, len, &e);

    if (c == NULL) return e;

    size_t size = SYSL_USERS_FIRST;

    for (;;) {
        char *scratch = malloc(size);

        if (scratch == NULL) {
            free(c);
            return ENOMEM;
        }

        struct passwd entry;
        struct passwd *found = NULL;

        e = getpwnam_r(c, &entry, scratch, size, &found);

        if (e == ERANGE && size < SYSL_USERS_CEILING) {
            free(scratch);
            size *= 2;
            continue;
        }

        if (e == 0) {
            if (found == NULL) e = ENOENT;
            else *id = (unsigned int) found->pw_uid;
        }

        free(scratch);
        free(c);
        return e;
    }
}

int sysl_proc_posix_group_id(const unsigned char *name, size_t len, unsigned int *id) {
    int e = 0;
    char *c = sysl_users_terminated(name, len, &e);

    if (c == NULL) return e;

    size_t size = SYSL_USERS_FIRST;

    for (;;) {
        char *scratch = malloc(size);

        if (scratch == NULL) {
            free(c);
            return ENOMEM;
        }

        struct group entry;
        struct group *found = NULL;

        e = getgrnam_r(c, &entry, scratch, size, &found);

        if (e == ERANGE && size < SYSL_USERS_CEILING) {
            free(scratch);
            size *= 2;
            continue;
        }

        if (e == 0) {
            if (found == NULL) e = ENOENT;
            else *id = (unsigned int) found->gr_gid;
        }

        free(scratch);
        free(c);
        return e;
    }
}
