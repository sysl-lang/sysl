#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

// `sysl.fs.watch`'s watcher over the kernel's own: kqueue's `EVFILT_VNODE` on Darwin and the BSDs,
// inotify on Linux. What `sysl.fs.hosted` answers the `sysl_fs_watch_*` hooks with.
//
// **The descriptor a program waits on is the kernel's**: the kqueue, or the inotify instance. Both
// are readable while the kernel holds something for this watcher, so the watcher composes with
// `sysl.io.poll` and `sysl.io.wait` as any descriptor does, and both are non-blocking to read here.
//
// **One event model over two kernels that disagree.** inotify names the entry an event concerns;
// kqueue says only that a directory's entries changed. So under kqueue each watched directory keeps a
// snapshot of its names and inode numbers, and a change is the difference between the snapshot and
// a fresh listing: a name that went, a name that came, and -- an inode that went under one name and
// came under another -- a rename. What both report, and nothing else:
//
// | change | a watched file | a watched directory |
// |---|---|---|
// | created | -- | an entry appeared in it |
// | removed | the file itself went | an entry went from it, or the directory itself went |
// | modified | its contents changed, or its length | -- |
// | renamed | it moved (the new name is not known) | an entry moved, within it or between two watched directories; or it moved |
// | attributes | its mode, owner, times or link count changed | its own changed |
// | overflow | the kernel dropped events, or a directory could not be listed again | |
//
// A change to the contents or the attributes of an entry *inside* a watched directory is not
// reported on either kernel -- inotify reports it and kqueue cannot without a descriptor per entry --
// so a program watches the file itself to follow it.
//
// **The events describe what changed, not every operation.** kqueue folds every change to one
// watched object between two reads into one notification, and a snapshot compared with a listing sees
// only the net difference: a file made and removed between two reads is nothing. inotify queues each
// operation, and identical events one after another are folded here as the kernel folds them. A
// program asks again whatever it needs to be sure of.
//
// **What crosses into sysl is a run of records**, each a 16-byte head -- the change (one byte),
// whether a second path follows (one byte), two zero bytes, the watch's number, then the lengths of
// the two paths, each a 32-bit little-endian number -- and the paths' bytes after it with no
// terminator. Every function answers zero, a count or a number, or an `errno` negated.

enum {
    SYSL_WATCH_CREATED = 1,
    SYSL_WATCH_REMOVED = 2,
    SYSL_WATCH_MODIFIED = 3,
    SYSL_WATCH_RENAMED = 4,
    SYSL_WATCH_ATTRIBUTES = 5,
    SYSL_WATCH_OVERFLOW = 6,
};

#define SYSL_WATCH_HEAD 16

// Records made and not yet handed over, in the order they were made.
struct sysl_watch_out {
    unsigned char *at;
    size_t len;
    size_t cap;
    size_t head;
    size_t last;
    int any;
};

static void sysl_watch_put32(unsigned char *p, uint32_t v) {
    p[0] = (unsigned char) v;
    p[1] = (unsigned char) (v >> 8);
    p[2] = (unsigned char) (v >> 16);
    p[3] = (unsigned char) (v >> 24);
}

// Adds a record, unless it is the very one added last and not yet handed over. Answers 0 or an errno.
static int sysl_watch_emit(struct sysl_watch_out *o, int kind, int id, const char *from, size_t from_len,
                           const char *to, size_t to_len, int has_to) {
    size_t n = SYSL_WATCH_HEAD + from_len + to_len;

    if (from_len > UINT32_MAX || to_len > UINT32_MAX) return ENAMETOOLONG;

    if (o->any && o->last >= o->head) {
        unsigned char *l = o->at + o->last;
        size_t ln = o->len - o->last;

        if (ln == n && l[0] == kind && l[1] == (has_to != 0) &&
            memcmp(l + SYSL_WATCH_HEAD, from, from_len) == 0 &&
            memcmp(l + SYSL_WATCH_HEAD + from_len, to, to_len) == 0) {
            unsigned char idb[4];

            sysl_watch_put32(idb, (uint32_t) id);
            if (memcmp(l + 4, idb, 4) == 0) return 0;
        }
    }

    if (o->len + n > o->cap) {
        size_t cap = o->cap ? o->cap : 4096;

        while (cap < o->len + n) cap *= 2;

        unsigned char *at = realloc(o->at, cap);

        if (!at) return ENOMEM;
        o->at = at;
        o->cap = cap;
    }

    unsigned char *r = o->at + o->len;

    r[0] = (unsigned char) kind;
    r[1] = has_to != 0;
    r[2] = 0;
    r[3] = 0;
    sysl_watch_put32(r + 4, (uint32_t) id);
    sysl_watch_put32(r + 8, (uint32_t) from_len);
    sysl_watch_put32(r + 12, (uint32_t) to_len);
    if (from_len) memcpy(r + SYSL_WATCH_HEAD, from, from_len);
    if (to_len) memcpy(r + SYSL_WATCH_HEAD + from_len, to, to_len);
    o->last = o->len;
    o->any = 1;
    o->len += n;
    return 0;
}

static uint32_t sysl_watch_get32(const unsigned char *p) {
    return (uint32_t) p[0] | (uint32_t) p[1] << 8 | (uint32_t) p[2] << 16 | (uint32_t) p[3] << 24;
}

// Hands over as many whole records as fit in `room`, answering the bytes written, or `-ERANGE` where
// not even the first fits. `more` says whether any are still held.
static long long sysl_watch_hand(struct sysl_watch_out *o, unsigned char *into, size_t room, int32_t *more) {
    size_t put = 0;

    while (o->head < o->len) {
        const unsigned char *r = o->at + o->head;
        size_t n = SYSL_WATCH_HEAD + sysl_watch_get32(r + 8) + sysl_watch_get32(r + 12);

        if (put + n > room) break;
        memcpy(into + put, r, n);
        put += n;
        o->head += n;
    }

    if (o->head == o->len) {
        o->head = 0;
        o->len = 0;
        o->any = 0;
    }

    *more = o->len > 0;
    if (put == 0 && o->len > 0) return -ERANGE;
    return (long long) put;
}

// `dir` and `name` joined by one `/`, in memory the caller frees, or NULL where there is none.
static char *sysl_watch_join(const char *dir, size_t dir_len, const char *name, size_t name_len, size_t *len) {
    int slash = dir_len > 0 && dir[dir_len - 1] != '/';
    size_t n = dir_len + slash + name_len;
    char *p = malloc(n + 1);

    if (!p) return NULL;
    memcpy(p, dir, dir_len);
    if (slash) p[dir_len] = '/';
    memcpy(p + dir_len + slash, name, name_len);
    p[n] = 0;
    *len = n;
    return p;
}

// Emits one change to an entry `name` of the directory at `dir`.
static int sysl_watch_entry(struct sysl_watch_out *o, int kind, int id, const char *dir, size_t dir_len,
                            const char *name, size_t name_len) {
    size_t n = 0;
    char *p = sysl_watch_join(dir, dir_len, name, name_len, &n);

    if (!p) return ENOMEM;

    int e = sysl_watch_emit(o, kind, id, p, n, NULL, 0, 0);

    free(p);
    return e;
}

static char *sysl_watch_copy(const char *p, size_t n) {
    char *c = malloc(n + 1);

    if (!c) return NULL;
    memcpy(c, p, n);
    c[n] = 0;
    return c;
}

#if defined(__APPLE__) || defined(__FreeBSD__) || defined(__OpenBSD__) || defined(__NetBSD__) || \
    defined(__DragonFly__)

#include <dirent.h>
#include <sys/event.h>
#include <sys/time.h>

#ifndef O_EVTONLY
#define O_EVTONLY O_RDONLY
#endif

#define SYSL_WATCH_NOTES (NOTE_DELETE | NOTE_WRITE | NOTE_EXTEND | NOTE_ATTRIB | NOTE_LINK | NOTE_RENAME | \
                          NOTE_REVOKE)

struct sysl_watch_name {
    char *name;
    size_t len;
    ino_t ino;
};

struct sysl_watch_one {
    int id;
    int fd;
    char *path;
    size_t path_len;
    int is_dir;
    struct stat st;
    struct sysl_watch_name *names;
    size_t names_len;
    int rescan;
    int gone;
};

struct sysl_watch {
    int kq;
    pthread_mutex_t lock;
    struct sysl_watch_one **w;
    size_t len;
    size_t cap;
    int next_id;
    struct sysl_watch_out out;
};

static void sysl_watch_free_names(struct sysl_watch_name *names, size_t n) {
    for (size_t i = 0; i < n; i++) free(names[i].name);
    free(names);
}

static void sysl_watch_free_one(struct sysl_watch_one *w) {
    if (w->fd >= 0) close(w->fd);
    sysl_watch_free_names(w->names, w->names_len);
    free(w->path);
    free(w);
}

// Every name in the directory open at `fd` with its inode, `.` and `..` left out. Answers 0 or an errno.
static int sysl_watch_list(int fd, struct sysl_watch_name **names, size_t *len) {
    int d = openat(fd, ".", O_RDONLY | O_DIRECTORY | O_CLOEXEC);

    if (d < 0) return errno;

    DIR *dir = fdopendir(d);

    if (!dir) {
        int e = errno;

        close(d);
        return e;
    }

    struct sysl_watch_name *got = NULL;
    size_t n = 0;
    size_t cap = 0;
    int e = 0;
    struct dirent *ent;

    for (;;) {
        errno = 0;
        ent = readdir(dir);
        if (!ent) {
            e = errno;
            break;
        }

        const char *nm = ent->d_name;

        if (nm[0] == '.' && (nm[1] == 0 || (nm[1] == '.' && nm[2] == 0))) continue;

        if (n == cap) {
            size_t c = cap ? cap * 2 : 16;
            struct sysl_watch_name *g = realloc(got, c * sizeof *g);

            if (!g) {
                e = ENOMEM;
                break;
            }
            got = g;
            cap = c;
        }

        size_t nl = strlen(nm);

        got[n].name = sysl_watch_copy(nm, nl);
        if (!got[n].name) {
            e = ENOMEM;
            break;
        }
        got[n].len = nl;
        got[n].ino = ent->d_ino;
        n++;
    }

    closedir(dir);

    if (e != 0) {
        sysl_watch_free_names(got, n);
        return e;
    }

    *names = got;
    *len = n;
    return 0;
}

static struct sysl_watch_one *sysl_watch_find(struct sysl_watch *s, int id) {
    for (size_t i = 0; i < s->len; i++)
        if (s->w[i]->id == id) return s->w[i];
    return NULL;
}

static void sysl_watch_drop(struct sysl_watch *s, struct sysl_watch_one *w) {
    for (size_t i = 0; i < s->len; i++) {
        if (s->w[i] != w) continue;
        s->w[i] = s->w[s->len - 1];
        s->len--;
        break;
    }
    sysl_watch_free_one(w);
}

int sysl_fs_host_watch_open(uintptr_t *handle) {
    struct sysl_watch *s = calloc(1, sizeof *s);

    if (!s) return -ENOMEM;

    s->kq = kqueue();
    if (s->kq < 0) {
        int e = errno;

        free(s);
        return -e;
    }

    fcntl(s->kq, F_SETFD, FD_CLOEXEC);
    pthread_mutex_init(&s->lock, NULL);
    s->next_id = 1;
    *handle = (uintptr_t) s;
    return s->kq;
}

int sysl_fs_host_watch_add(uintptr_t handle, const char *path, size_t len) {
    struct sysl_watch *s = (struct sysl_watch *) handle;
    char *p = sysl_watch_copy(path, len);

    if (!p) return -ENOMEM;
    if (strlen(p) != len) {
        free(p);
        return -EINVAL;
    }

    int fd = open(p, O_EVTONLY | O_CLOEXEC);

    if (fd < 0) {
        int e = errno;

        free(p);
        return -e;
    }

    struct stat st;

    if (fstat(fd, &st) != 0) {
        int e = errno;

        close(fd);
        free(p);
        return -e;
    }

    pthread_mutex_lock(&s->lock);

    // A path already watched -- under this name or another -- answers the watch it already has.
    for (size_t i = 0; i < s->len; i++) {
        struct sysl_watch_one *o = s->w[i];

        if (!o->gone && o->st.st_dev == st.st_dev && o->st.st_ino == st.st_ino) {
            int id = o->id;

            pthread_mutex_unlock(&s->lock);
            close(fd);
            free(p);
            return id;
        }
    }

    struct sysl_watch_one *w = calloc(1, sizeof *w);
    int e = w ? 0 : ENOMEM;

    if (w) {
        w->fd = fd;
        w->path = p;
        w->path_len = len;
        w->st = st;
        w->is_dir = S_ISDIR(st.st_mode);
        if (w->is_dir) e = sysl_watch_list(fd, &w->names, &w->names_len);
    }

    if (e == 0 && s->len == s->cap) {
        size_t c = s->cap ? s->cap * 2 : 8;
        struct sysl_watch_one **g = realloc(s->w, c * sizeof *g);

        if (!g) e = ENOMEM;
        else {
            s->w = g;
            s->cap = c;
        }
    }

    if (e == 0) {
        struct kevent k;

        w->id = s->next_id++;
        EV_SET(&k, (uintptr_t) fd, EVFILT_VNODE, EV_ADD | EV_CLEAR, SYSL_WATCH_NOTES, 0,
               (void *) (intptr_t) w->id);
        while (kevent(s->kq, &k, 1, NULL, 0, NULL) != 0) {
            if (errno != EINTR) {
                e = errno;
                break;
            }
        }
    }

    if (e != 0) {
        pthread_mutex_unlock(&s->lock);
        if (w) sysl_watch_free_one(w);
        else {
            close(fd);
            free(p);
        }
        return -e;
    }

    s->w[s->len++] = w;
    pthread_mutex_unlock(&s->lock);
    return w->id;
}

int sysl_fs_host_watch_remove(uintptr_t handle, int id) {
    struct sysl_watch *s = (struct sysl_watch *) handle;

    pthread_mutex_lock(&s->lock);

    struct sysl_watch_one *w = sysl_watch_find(s, id);

    if (w) sysl_watch_drop(s, w);
    pthread_mutex_unlock(&s->lock);
    return w ? 0 : -ENOENT;
}

// One entry that went from, or came into, a watched directory since its snapshot.
struct sysl_watch_move {
    struct sysl_watch_one *w;
    struct sysl_watch_name *n;
    int paired;
};

static int sysl_watch_has(struct sysl_watch_name *names, size_t len, struct sysl_watch_name *n) {
    for (size_t i = 0; i < len; i++)
        if (names[i].len == n->len && names[i].ino == n->ino && memcmp(names[i].name, n->name, n->len) == 0)
            return 1;
    return 0;
}

static int sysl_watch_push_move(struct sysl_watch_move **at, size_t *len, size_t *cap, struct sysl_watch_one *w,
                                struct sysl_watch_name *n) {
    if (*len == *cap) {
        size_t c = *cap ? *cap * 2 : 16;
        struct sysl_watch_move *g = realloc(*at, c * sizeof *g);

        if (!g) return ENOMEM;
        *at = g;
        *cap = c;
    }

    (*at)[*len].w = w;
    (*at)[*len].n = n;
    (*at)[*len].paired = 0;
    (*len)++;
    return 0;
}

// Lists every directory marked for it again and reports the difference from its snapshot, pairing an
// inode that went from one name with one that came under another as a rename -- within one directory or
// between two this watcher watches on one device.
static int sysl_watch_rescan(struct sysl_watch *s) {
    struct sysl_watch_move *went = NULL, *came = NULL;
    size_t went_len = 0, went_cap = 0, came_len = 0, came_cap = 0;
    struct sysl_watch_name **olds = NULL;
    size_t *old_lens = NULL;
    struct sysl_watch_one **dirs = NULL;
    size_t dirs_len = 0;
    int e = 0;

    olds = calloc(s->len ? s->len : 1, sizeof *olds);
    old_lens = calloc(s->len ? s->len : 1, sizeof *old_lens);
    dirs = calloc(s->len ? s->len : 1, sizeof *dirs);
    if (!olds || !old_lens || !dirs) e = ENOMEM;

    for (size_t i = 0; e == 0 && i < s->len; i++) {
        struct sysl_watch_one *w = s->w[i];

        if (!w->is_dir || !w->rescan || w->gone) continue;
        w->rescan = 0;

        struct sysl_watch_name *now = NULL;
        size_t now_len = 0;
        int le = sysl_watch_list(w->fd, &now, &now_len);

        if (le == ENOENT || le == ENOTDIR) continue;
        if (le != 0) {
            e = sysl_watch_emit(&s->out, SYSL_WATCH_OVERFLOW, w->id, NULL, 0, NULL, 0, 0);
            continue;
        }

        olds[dirs_len] = w->names;
        old_lens[dirs_len] = w->names_len;
        dirs[dirs_len++] = w;
        w->names = now;
        w->names_len = now_len;

        for (size_t k = 0; e == 0 && k < old_lens[dirs_len - 1]; k++)
            if (!sysl_watch_has(now, now_len, &olds[dirs_len - 1][k]))
                e = sysl_watch_push_move(&went, &went_len, &went_cap, w, &olds[dirs_len - 1][k]);

        for (size_t k = 0; e == 0 && k < now_len; k++)
            if (!sysl_watch_has(olds[dirs_len - 1], old_lens[dirs_len - 1], &now[k]))
                e = sysl_watch_push_move(&came, &came_len, &came_cap, w, &now[k]);
    }

    // An inode that went under one name and came under another moved.
    for (size_t c = 0; e == 0 && c < came_len; c++) {
        for (size_t g = 0; g < went_len; g++) {
            if (went[g].paired || went[g].n->ino != came[c].n->ino) continue;
            if (went[g].w->st.st_dev != came[c].w->st.st_dev) continue;
            went[g].paired = 1;
            came[c].paired = 1;
            break;
        }
    }

    // A name that went where a rename put another inode was replaced by the rename, not removed.
    for (size_t g = 0; e == 0 && g < went_len; g++) {
        if (went[g].paired) continue;
        for (size_t c = 0; c < came_len; c++) {
            if (!came[c].paired || came[c].w != went[g].w || came[c].n->len != went[g].n->len) continue;
            if (memcmp(came[c].n->name, went[g].n->name, went[g].n->len) != 0) continue;
            went[g].paired = 2;
            break;
        }
    }

    for (size_t g = 0; e == 0 && g < went_len; g++)
        if (!went[g].paired)
            e = sysl_watch_entry(&s->out, SYSL_WATCH_REMOVED, went[g].w->id, went[g].w->path, went[g].w->path_len,
                                 went[g].n->name, went[g].n->len);

    for (size_t c = 0; e == 0 && c < came_len; c++) {
        if (!came[c].paired) continue;
        for (size_t g = 0; g < went_len; g++) {
            if (went[g].paired != 1 || went[g].n->ino != came[c].n->ino) continue;
            if (went[g].w->st.st_dev != came[c].w->st.st_dev) continue;

            size_t fl = 0, tl = 0;
            char *from = sysl_watch_join(went[g].w->path, went[g].w->path_len, went[g].n->name, went[g].n->len, &fl);
            char *to = sysl_watch_join(came[c].w->path, came[c].w->path_len, came[c].n->name, came[c].n->len, &tl);

            if (from && to) e = sysl_watch_emit(&s->out, SYSL_WATCH_RENAMED, came[c].w->id, from, fl, to, tl, 1);
            else e = ENOMEM;
            free(from);
            free(to);
            went[g].paired = 3;
            break;
        }
    }

    for (size_t c = 0; e == 0 && c < came_len; c++)
        if (!came[c].paired)
            e = sysl_watch_entry(&s->out, SYSL_WATCH_CREATED, came[c].w->id, came[c].w->path, came[c].w->path_len,
                                 came[c].n->name, came[c].n->len);

    for (size_t i = 0; i < dirs_len; i++) sysl_watch_free_names(olds[i], old_lens[i]);
    free(olds);
    free(old_lens);
    free(dirs);
    free(went);
    free(came);
    return e;
}

// What the kernel holds, turned into records. Answers 0 or an errno.
static int sysl_watch_gather(struct sysl_watch *s) {
    struct kevent got[64];
    struct timespec zero = {0, 0};
    int e = 0;

    for (;;) {
        int n = kevent(s->kq, NULL, 0, got, 64, &zero);

        if (n < 0) {
            if (errno == EINTR) continue;
            return errno;
        }

        for (int i = 0; e == 0 && i < n; i++) {
            struct sysl_watch_one *w = sysl_watch_find(s, (int) (intptr_t) got[i].udata);
            unsigned f = got[i].fflags;

            if (!w || w->gone) continue;

            if (f & (NOTE_DELETE | NOTE_REVOKE)) {
                e = sysl_watch_emit(&s->out, SYSL_WATCH_REMOVED, w->id, w->path, w->path_len, NULL, 0, 0);
                w->gone = 1;
                continue;
            }

            if (w->is_dir) {
                if (f & (NOTE_WRITE | NOTE_LINK | NOTE_EXTEND)) w->rescan = 1;

                struct stat st;

                if ((f & NOTE_ATTRIB) && fstat(w->fd, &st) == 0) {
                    if (st.st_mode != w->st.st_mode || st.st_uid != w->st.st_uid || st.st_gid != w->st.st_gid)
                        e = sysl_watch_emit(&s->out, SYSL_WATCH_ATTRIBUTES, w->id, w->path, w->path_len, NULL, 0, 0);
                    else if (!(f & (NOTE_WRITE | NOTE_LINK)))
                        e = sysl_watch_emit(&s->out, SYSL_WATCH_ATTRIBUTES, w->id, w->path, w->path_len, NULL, 0, 0);
                    w->st = st;
                }
            } else {
                struct stat st;
                int have = fstat(w->fd, &st) == 0;
                int modified = (f & (NOTE_WRITE | NOTE_EXTEND)) != 0;

                // A truncation is only an attribute change to kqueue; its length says otherwise.
                if (have && st.st_size != w->st.st_size) modified = 1;

                int meta = have && (st.st_mode != w->st.st_mode || st.st_uid != w->st.st_uid ||
                                    st.st_gid != w->st.st_gid || st.st_nlink != w->st.st_nlink);
                int attrs = ((f & NOTE_ATTRIB) && (meta || !modified)) || ((f & NOTE_LINK) && meta);

                if (modified) e = sysl_watch_emit(&s->out, SYSL_WATCH_MODIFIED, w->id, w->path, w->path_len, NULL, 0, 0);
                if (e == 0 && attrs)
                    e = sysl_watch_emit(&s->out, SYSL_WATCH_ATTRIBUTES, w->id, w->path, w->path_len, NULL, 0, 0);
                if (have) w->st = st;
            }

            if (e == 0 && (f & NOTE_RENAME))
                e = sysl_watch_emit(&s->out, SYSL_WATCH_RENAMED, w->id, w->path, w->path_len, NULL, 0, 0);
        }

        if (e != 0 || n < 64) break;
    }

    if (e == 0) e = sysl_watch_rescan(s);

    // A watch whose object went is over, as inotify ends one.
    for (size_t i = 0; i < s->len;) {
        if (s->w[i]->gone) sysl_watch_drop(s, s->w[i]);
        else i++;
    }

    return e;
}

int sysl_fs_host_watch_close(uintptr_t handle) {
    struct sysl_watch *s = (struct sysl_watch *) handle;
    int e = close(s->kq) != 0 ? errno : 0;

    for (size_t i = 0; i < s->len; i++) sysl_watch_free_one(s->w[i]);
    free(s->w);
    free(s->out.at);
    pthread_mutex_destroy(&s->lock);
    free(s);
    return -e;
}

#elif defined(__linux__)

#include <sys/inotify.h>

#define SYSL_WATCH_MASK (IN_MODIFY | IN_ATTRIB | IN_CREATE | IN_DELETE | IN_DELETE_SELF | IN_MOVE_SELF | \
                         IN_MOVED_FROM | IN_MOVED_TO)

struct sysl_watch_one {
    int wd;
    char *path;
    size_t path_len;
};

struct sysl_watch {
    int fd;
    pthread_mutex_t lock;
    struct sysl_watch_one *w;
    size_t len;
    size_t cap;
    struct sysl_watch_out out;
};

static struct sysl_watch_one *sysl_watch_find(struct sysl_watch *s, int wd) {
    for (size_t i = 0; i < s->len; i++)
        if (s->w[i].wd == wd) return &s->w[i];
    return NULL;
}

static void sysl_watch_drop(struct sysl_watch *s, struct sysl_watch_one *w) {
    free(w->path);
    *w = s->w[s->len - 1];
    s->len--;
}

int sysl_fs_host_watch_open(uintptr_t *handle) {
    struct sysl_watch *s = calloc(1, sizeof *s);

    if (!s) return -ENOMEM;

    s->fd = inotify_init1(IN_NONBLOCK | IN_CLOEXEC);
    if (s->fd < 0) {
        int e = errno;

        free(s);
        return -e;
    }

    pthread_mutex_init(&s->lock, NULL);
    *handle = (uintptr_t) s;
    return s->fd;
}

int sysl_fs_host_watch_add(uintptr_t handle, const char *path, size_t len) {
    struct sysl_watch *s = (struct sysl_watch *) handle;
    char *p = sysl_watch_copy(path, len);

    if (!p) return -ENOMEM;
    if (strlen(p) != len) {
        free(p);
        return -EINVAL;
    }

    pthread_mutex_lock(&s->lock);

    int wd = inotify_add_watch(s->fd, p, SYSL_WATCH_MASK);
    int e = wd < 0 ? errno : 0;

    // A path already watched -- under this name or another -- is the same watch descriptor.
    if (e == 0 && sysl_watch_find(s, wd)) {
        pthread_mutex_unlock(&s->lock);
        free(p);
        return wd;
    }

    if (e == 0 && s->len == s->cap) {
        size_t c = s->cap ? s->cap * 2 : 8;
        struct sysl_watch_one *g = realloc(s->w, c * sizeof *g);

        if (!g) {
            e = ENOMEM;
            inotify_rm_watch(s->fd, wd);
        } else {
            s->w = g;
            s->cap = c;
        }
    }

    if (e != 0) {
        pthread_mutex_unlock(&s->lock);
        free(p);
        return -e;
    }

    s->w[s->len].wd = wd;
    s->w[s->len].path = p;
    s->w[s->len].path_len = len;
    s->len++;
    pthread_mutex_unlock(&s->lock);
    return wd;
}

int sysl_fs_host_watch_remove(uintptr_t handle, int id) {
    struct sysl_watch *s = (struct sysl_watch *) handle;

    pthread_mutex_lock(&s->lock);

    struct sysl_watch_one *w = sysl_watch_find(s, id);

    if (w) {
        inotify_rm_watch(s->fd, id);
        sysl_watch_drop(s, w);
    }

    pthread_mutex_unlock(&s->lock);
    return w ? 0 : -ENOENT;
}

// A move out of a directory not yet matched by the move in that shares its cookie.
struct sysl_watch_pending {
    uint32_t cookie;
    int wd;
    char *path;
    size_t len;
};

static int sysl_watch_unpaired(struct sysl_watch *s, struct sysl_watch_pending *m) {
    if (!m->path) return 0;

    int e = sysl_watch_emit(&s->out, SYSL_WATCH_REMOVED, m->wd, m->path, m->len, NULL, 0, 0);

    free(m->path);
    m->path = NULL;
    return e;
}

static int sysl_watch_goes(const unsigned char *buf, size_t n, int wd) {
    for (size_t at = 0; at + sizeof(struct inotify_event) <= n;) {
        const struct inotify_event *ev = (const struct inotify_event *) (buf + at);

        if (ev->wd == wd && (ev->mask & IN_DELETE_SELF)) return 1;
        at += sizeof(struct inotify_event) + ev->len;
    }
    return 0;
}

static int sysl_watch_gather(struct sysl_watch *s) {
    _Alignas(struct inotify_event) unsigned char buf[65536];
    struct sysl_watch_pending moved = {0, 0, NULL, 0};
    int e = 0;

    for (;;) {
        ssize_t n = read(s->fd, buf, sizeof buf);

        if (n < 0) {
            if (errno == EINTR) continue;
            if (errno != EAGAIN && errno != EWOULDBLOCK) e = errno;
            break;
        }
        if (n == 0) break;

        for (size_t at = 0; e == 0 && at + sizeof(struct inotify_event) <= (size_t) n;) {
            const struct inotify_event *ev = (const struct inotify_event *) (buf + at);
            uint32_t m = ev->mask;
            // The name is padded with zeros to `len`; `memchr` finds its end without `strnlen`, which
            // musl and glibc declare only to a program asking for POSIX 2008.
            const char *end = ev->len ? memchr(ev->name, 0, ev->len) : NULL;
            size_t name_len = ev->len ? (end ? (size_t) (end - ev->name) : ev->len) : 0;

            at += sizeof(struct inotify_event) + ev->len;

            if (m & IN_Q_OVERFLOW) {
                e = sysl_watch_unpaired(s, &moved);
                if (e == 0) e = sysl_watch_emit(&s->out, SYSL_WATCH_OVERFLOW, -1, NULL, 0, NULL, 0, 0);
                continue;
            }

            struct sysl_watch_one *w = sysl_watch_find(s, ev->wd);

            if (!w) continue;

            if (m & IN_IGNORED) {
                sysl_watch_drop(s, w);
                continue;
            }

            if (name_len > 0) {
                if ((m & IN_MOVED_TO) && moved.path && moved.cookie == ev->cookie) {
                    size_t tl = 0;
                    char *to = sysl_watch_join(w->path, w->path_len, ev->name, name_len, &tl);

                    if (!to) e = ENOMEM;
                    else e = sysl_watch_emit(&s->out, SYSL_WATCH_RENAMED, w->wd, moved.path, moved.len, to, tl, 1);
                    free(to);
                    free(moved.path);
                    moved.path = NULL;
                    continue;
                }

                e = sysl_watch_unpaired(s, &moved);
                if (e != 0) break;

                if (m & IN_MOVED_FROM) {
                    moved.path = sysl_watch_join(w->path, w->path_len, ev->name, name_len, &moved.len);
                    moved.cookie = ev->cookie;
                    moved.wd = w->wd;
                    if (!moved.path) e = ENOMEM;
                } else if (m & (IN_CREATE | IN_MOVED_TO)) {
                    e = sysl_watch_entry(&s->out, SYSL_WATCH_CREATED, w->wd, w->path, w->path_len, ev->name, name_len);
                } else if (m & IN_DELETE) {
                    e = sysl_watch_entry(&s->out, SYSL_WATCH_REMOVED, w->wd, w->path, w->path_len, ev->name, name_len);
                }
                // A change to an entry's contents or attributes is not reported, as under kqueue.
                continue;
            }

            e = sysl_watch_unpaired(s, &moved);
            if (e != 0) break;

            // The link count falling as the object goes is its removal, not a change of attributes.
            if ((m & (IN_MODIFY | IN_ATTRIB)) && sysl_watch_goes(buf + at, (size_t) n - at, w->wd)) continue;

            if (m & IN_MODIFY) e = sysl_watch_emit(&s->out, SYSL_WATCH_MODIFIED, w->wd, w->path, w->path_len, NULL, 0, 0);
            else if (m & IN_ATTRIB)
                e = sysl_watch_emit(&s->out, SYSL_WATCH_ATTRIBUTES, w->wd, w->path, w->path_len, NULL, 0, 0);
            else if (m & IN_MOVE_SELF)
                e = sysl_watch_emit(&s->out, SYSL_WATCH_RENAMED, w->wd, w->path, w->path_len, NULL, 0, 0);
            else if (m & IN_DELETE_SELF)
                e = sysl_watch_emit(&s->out, SYSL_WATCH_REMOVED, w->wd, w->path, w->path_len, NULL, 0, 0);
        }

        if (e != 0) break;
    }

    int u = sysl_watch_unpaired(s, &moved);

    return e != 0 ? e : u;
}

int sysl_fs_host_watch_close(uintptr_t handle) {
    struct sysl_watch *s = (struct sysl_watch *) handle;
    int e = close(s->fd) != 0 ? errno : 0;

    for (size_t i = 0; i < s->len; i++) free(s->w[i].path);
    free(s->w);
    free(s->out.at);
    pthread_mutex_destroy(&s->lock);
    free(s);
    return -e;
}

#else

struct sysl_watch {
    pthread_mutex_t lock;
    struct sysl_watch_out out;
};

int sysl_fs_host_watch_open(uintptr_t *handle) {
    (void) handle;
    return -ENOSYS;
}

int sysl_fs_host_watch_add(uintptr_t handle, const char *path, size_t len) {
    (void) handle;
    (void) path;
    (void) len;
    return -ENOSYS;
}

int sysl_fs_host_watch_remove(uintptr_t handle, int id) {
    (void) handle;
    (void) id;
    return -ENOSYS;
}

static int sysl_watch_gather(struct sysl_watch *s) {
    (void) s;
    return ENOSYS;
}

int sysl_fs_host_watch_close(uintptr_t handle) {
    (void) handle;
    return -ENOSYS;
}

#endif

long long sysl_fs_host_watch_read(uintptr_t handle, unsigned char *into, size_t room, int32_t *more) {
    struct sysl_watch *s = (struct sysl_watch *) handle;

    pthread_mutex_lock(&s->lock);

    int e = s->out.len == 0 ? sysl_watch_gather(s) : 0;
    long long got = e != 0 && s->out.len == 0 ? -e : sysl_watch_hand(&s->out, into, room, more);

    if (e != 0 && s->out.len == 0) *more = 0;
    pthread_mutex_unlock(&s->lock);
    return got;
}
