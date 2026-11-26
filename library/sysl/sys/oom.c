/* The word an export that answers out of memory keeps its state in (`reference/ffi.md § An export
 * that answers out of memory`).
 *
 * **Twice the number of such exports the thread is inside, plus one while a refused allocation is
 * being carried out to the nearest of them.** The compiler reads and writes it directly: the thunk of
 * an export marked `@out_of_memory(...)` adds two on the way in and takes them back on the way out, a
 * refused allocation inside it sets the low bit, and every call that could have refused one is
 * followed by a test of that bit. Nothing here is called, so nothing here can fail.
 *
 * **It is one per thread on a hosted target** (`_Thread_local`), so a thread started inside such an
 * export begins outside every boundary, and a refusal on it traps as it always did. **On a
 * freestanding one it is one word for the image**: a kernel that switches between tasks inside such
 * an export saves and restores it with the rest of a task's state.
 *
 * Only a build holding such an export names it, so no other program links this.
 */

#include <stdint.h>

#if __STDC_HOSTED__
_Thread_local uint32_t sysl_oom_state;
#else
uint32_t sysl_oom_state;
#endif
