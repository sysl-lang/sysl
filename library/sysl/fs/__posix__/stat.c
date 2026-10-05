#include <stdio.h>
#include <sys/stat.h>
#include <unistd.h>

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

// Cutting an open file to a length, which `File` had no way to do: it could seek and it could ask
// its size, and both of those move or read a position rather than changing what is there.
//
// The flush is not optional and is the reason this is a shim rather than an `ftruncate` binding. A
// `File` is C's buffered `FILE *`, so bytes a program has written may still be in the buffer; cutting
// underneath them would put them back afterwards, past the length that was asked for. Flushing first
// makes the length mean what the caller meant at the moment they said it.
int sysl_fs_file_truncate(void *stream, long long length) {
    FILE *f = (FILE *) stream;

    if (fflush(f) != 0) return -1;

    return ftruncate(fileno(f), (off_t) length);
}
