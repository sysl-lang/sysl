#include <dirent.h>

// The name of the next entry in a directory stream, or NULL at the end of it.
//
// `struct dirent` is the shape `sysl.fs` cannot transcribe: `d_name` sits at an offset the two
// platforms disagree about, and being wrong about it reads the wrong bytes rather than failing.
// A shim answers with the one thing a caller wants, which is a `char *` — so nothing above this
// file has to know the layout, and nothing here had to be measured.
const char *sysl_fs_dir_next(void *stream) {
    struct dirent *entry = readdir((DIR *) stream);

    return entry ? entry->d_name : (const char *) 0;
}
