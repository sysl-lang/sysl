#include <stdio.h>
#include <sys/stat.h>

// How many bytes a file holds, asked of the file rather than measured by moving through it.
//
// `struct stat` is laid out differently by every platform's headers, which is the transcription
// `sysl.fs` refuses -- so it stays here and one field crosses. `fstat` also answers about the open
// file rather than about a path, so nothing can be renamed or replaced between opening it and asking.
//
// Negative on failure, which is what the sysl side already tests `ftell` for; it reads `errno`
// itself, exactly as it does after any other call that reported trouble.
long long sysl_fs_file_size(void *stream) {
    struct stat st;

    if (fstat(fileno((FILE *) stream), &st) != 0) return -1;

    return (long long) st.st_size;
}
