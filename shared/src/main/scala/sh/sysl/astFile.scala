package sh.sysl

/** A file as a whole — the header it carries and the parse it yields.
 *
 * None of these is a statement: they are what a file *is* rather than what it holds, which is why
 * a header clause has no place in `Stmt` and why `Program` is not a node of the tree at all.
 */

/** The `module a.b.c` header a file carries, naming the module the file contributes to. The name is
 * a directory path with the separators read as dots (`13 §1`), so it is kept as its segments rather
 * than as one string — the segments are what a visibility scope and a platform layout are written
 * against, and the dotted spelling is recovered by `show`.
 */
case class ModuleName(parts: List[String]) extends Positioned {
  def show: String = parts.mkString(".")
}

/** Which way a capability clause points (`capabilities.md`, `13 §4`).
 *
 * The two are not opposites of one degree: `Narrows` *removes* a capability the target offers and
 * is enforced at every use inside the module, while `Requires` states a dependency the module
 * already has by using it, and buys one early diagnostic instead of one per use.
 */
enum CapabilityDirection:

  /** `no alloc` — the module gives the capability up, so using it here is an error. */
  case Narrows

  /** `requires alloc` — the module cannot be built where the capability is missing. */
  case Requires

/** One capability clause of a file's header: `no alloc`, `requires alloc` (`13 §4`).
 *
 * The name is kept as written rather than resolved to a member of the core set, because which names
 * are capabilities is a property of the project's configuration and not of the grammar
 * (`capabilities.md` — "the set is extensible"). The analyzer is what holds the set and what says
 * so when a clause names something that is not in it.
 */
case class CapabilityClause(direction: CapabilityDirection, name: String) extends Positioned

/** `link "z"` — a library the linker must be given for this file's `extern`s to resolve (`15 §8`).
 *
 * The name is the library's, not a flag: `m` rather than `-lm`. What that becomes on a command line
 * is the target's answer and not the author's, because where a library lives is a property of the
 * machine — libm is a file of its own on ELF, part of `libSystem` on Darwin, and absent altogether
 * from a freestanding target. A directive that spelled the flag would be right on one platform and
 * wrong everywhere else, which is the mistake `Toolchain.libraryFlags` exists to make impossible.
 */
case class LinkClause(name: String) extends Positioned

/** `@include("FreeRTOS.h")` — a C header the file's `c const` block is evaluated against (`15 §7`).
 *
 * It is written the way C writes it, quotes and extension and all, because it is handed to the C
 * compiler unchanged: what a header is called, and whether it is found by `"…"` or by `<…>`, are
 * facts about the C project rather than about sysl, and a directive that respelled them would be
 * inventing a second name for something that already has one. Where it is looked for is
 * `SearchPaths.include`'s answer, exactly as it is for a shim beside the module.
 *
 * **It says nothing to the rest of the compilation.** No name from the header becomes visible in
 * sysl, and no declaration is read out of it: what it buys is that a `c const`'s expression
 * compiles. A type a program wants from the header is still declared with `opaque struct` and a
 * function with `extern`, which is `15 §9`'s arrangement and is unchanged.
 */
case class IncludeClause(header: String) extends Positioned

/** `@tests` — the file holds a module's tests and the scaffolding they need, and no build but
 * `sysl test` keeps any of it (`testing.md`).
 *
 * It says nothing about the declarations under it beyond where they go: a helper in such a file is
 * an ordinary function, written and analyzed exactly as it would be anywhere else. What the header
 * buys is the pair of rules the declarations could not state for themselves — every build but a test
 * build drops them, and nothing outside a test may name them.
 *
 * It carries no argument. What a *test* says about itself is `@test`'s to say, and this is a
 * property of the file rather than of any declaration in it.
 */
case class TestsClause() extends Positioned

/** What one `@` line of a file's header may turn out to be (`13 §4`, `15 §8`, `testing.md`).
 *
 * They are gathered as one list because they are read as one run of lines, and separated by what
 * each means once the file is parsed rather than by which rule matched — `@requires` alone yields
 * several, so the list is not one clause per line either way.
 */
type HeaderClause = CapabilityClause | LinkClause | IncludeClause | TestsClause

/** One file's parse: the module it contributes to, the capabilities and link requirements its header
 * declares, its statements, and the source it came from.
 *
 * `module` is absent for a file that declares no header, which puts it in the **anonymous root
 * module** — the module whose name is the empty path. A single-file program is exactly that case,
 * which is why one needs no header to compile.
 *
 * `capabilities` is a property of the *module* written on each of its files, so it is read per file
 * and held to agreeing across them (`13 §4`).
 *
 * `links` is **not** held to agreeing, and that is the one place these two headers differ. A
 * capability describes what the whole module may do, so files that disagree describe different
 * modules; a link requirement describes what one file's `extern`s need, so a module whose externs
 * all sit in one file has nothing to say in the other four. The module's requirement is the union of
 * its files' (`15 §8`).
 *
 * `includes` is per file for the same reason `links` is, and more sharply: two files of one module
 * may include headers that contradict each other, and each is compiled on its own, so a header
 * gathered across the module would be one file's context leaking into another's.
 *
 * `source` is carried because a file is the unit several module rules are stated over, and a
 * diagnostic about one has to name it even where the file holds nothing to point at.
 *
 * `testOnly` is `@tests` (`TestsClause`): the file is scaffolding for the module's tests, kept by
 * `sysl test` and dropped by every other build. It is a `Boolean` rather than the clause because
 * nothing downstream has anything to ask of it but whether it was written — the position a
 * diagnostic wants belongs to the declaration it is about, not to the header.
 *
 * `docs` is the file's `/** … */` comments, which are **not part of the tree**. They sit beside the
 * body rather than on the nodes they describe, and that is the design rather than an expedient: a
 * doc comment changes nothing about what a declaration means, so putting it on the node would mean
 * every walk, every pattern match and every codec entry carrying a field no analysis reads. What
 * wants them — a generator, an editor's hover text — asks `DocComments.above` for the prose above a
 * line, which is the same question a reader asks.
 */
case class Program(
    body: List[Stmt],
    module: Option[ModuleName],
    capabilities: List[CapabilityClause],
    links: List[LinkClause],
    source: Source,
    testOnly: Boolean = false,
    includes: List[IncludeClause] = Nil,
    docs: List[DocComments.Doc] = Nil,
)
