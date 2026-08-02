/* The C that a SQLite binding cannot be written without.
 *
 * Everything here is reachable from C and from nothing else, which is why this
 * file sits beside `sqlite.sysl` rather than being transcribed into it:
 *
 *   - The result and flag codes are `#define`s. A macro has no symbol, so there
 *     is nothing for a linker to resolve and nothing for `extern` to name. They
 *     are read here from the header of the platform being built for, rather than
 *     copied into sysl as the numbers one machine happened to use.
 *   - `SQLITE_TRANSIENT` is worse than a plain macro: it is the cast
 *     `((sqlite3_destructor_type)-1)`, a function pointer with a value no
 *     signature describes. `sqlite3_bind_text` cannot be called correctly
 *     without it, so the call is made here and the sysl side never sees it.
 *
 * Nothing in this file is sysl-specific. It is the ordinary shim any language
 * writes when it binds a C library, and `build-lib` compiles it because it is
 * sitting in the library's tree.
 */
#include <sqlite3.h>

int sysl_sqlite_ok(void) { return SQLITE_OK; }
int sysl_sqlite_row(void) { return SQLITE_ROW; }
int sysl_sqlite_done(void) { return SQLITE_DONE; }

/* Open for reading and writing, creating the database when it is not there --
 * the combination a caller almost always wants, and one the sysl side cannot
 * spell because both halves are macros.
 */
int sysl_sqlite_open_rw(void) { return SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE; }

int sysl_sqlite_open_ro(void) { return SQLITE_OPEN_READONLY; }

/* Bind a NUL-terminated string, telling SQLite to take its own copy.
 *
 * The copy is not an optimisation to skip: without it SQLite keeps the caller's
 * pointer until the statement is reset, and the sysl string that pointer came
 * from may be gone by then. `SQLITE_TRANSIENT` is what asks for the copy, and
 * passing it is the whole reason this wrapper exists.
 */
int sysl_sqlite_bind_text(sqlite3_stmt *stmt, int index, const char *value) {
    return sqlite3_bind_text(stmt, index, value, -1, SQLITE_TRANSIENT);
}
