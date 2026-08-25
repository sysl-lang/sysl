package sh.sysl

/** `link "z"` — what a module of `extern`s tells the driver its symbols are resolved by
 * (`reference/ffi.md § @link`).
 *
 * **The directive names a library, and the flag is the target's business.** `link "m"` says the file
 * needs the mathematics; whether that is `-lm`, or nothing because the host already links what holds
 * it, or nothing because the target has no such library at all, is decided at the command line by
 * `Toolchain.libraryFlags`. Writing `-lm` in a source file would be right on ELF and wrong on
 * everything else, and the file that wrote it could not be told so by any compiler on the machine
 * that wrote it.
 *
 * **A requirement is a property of the file, not of the module.** That is the one place this
 * differs from the capability clause it is otherwise shaped like: `reference/modules.md §
 * Capabilities are a module property` holds the files of a module to declaring the *same*
 * capabilities, because a capability describes what the whole module may do and files that
 * disagreed would describe different modules. A link requirement describes what one file's
 * `extern`s need, and a module whose externs all sit in one file has nothing for the other four to
 * repeat. So the module's requirement is the union of its files', and no agreement is asked for.
 *
 * **Nothing is pruned.** Every unit of the compilation contributes, whether or not the program
 * reaches it — so a Linux program is handed `-lm` because `sysl.math` is in the standard module it
 * was compiled against, exactly as it was when the driver carried that decision itself. An unused
 * `-l` costs a linker nothing: an archive is scanned only for symbols already undefined, and one
 * that resolves none of them contributes no members. Narrowing this to the modules a program can
 * actually reach is available later, and would be a size question rather than a correctness one.
 */
object LinkDirectives {

  /** The libraries a compilation must be linked against, in the order they were first written.
   *
   * **Order is kept rather than sorted**, because it is the author's one lever over a link. A static
   * archive is scanned once, left to right, so a library that calls into another has to come first —
   * `-lpng -lz`, never the reverse. Sorting would decide that by spelling, which is right by accident
   * for those two and wrong for the next pair. A repeat is dropped at its second appearance so the
   * first one's position is what survives, since that is the one that was placed deliberately.
   *
   * A malformed name is left out rather than passed on. The analyzer has already reported it, and a
   * compilation that reported an error emits nothing anyway; this is what keeps a bad directive from
   * reaching a command line if that ever stops being true.
   */
  def required(units: List[Program]): List[String] =
    units.flatMap(_.links).map(_.name).filter(complaint(_).isEmpty).distinct

  /** What is wrong with a library name, or `None` if it is one.
   *
   * The checks are about the *command line* rather than about any platform's naming: a name is
   * pasted onto a `-l`, so one that is empty, that carries whitespace, or that begins with a dash
   * would arrive at the linker as something other than a library — a stray flag at worst, and a flag
   * this compiler did not intend to pass. Nothing here can reach a shell, because the driver execs a
   * list rather than a string, so this is not a quoting question; it is that a directive should say
   * what it means.
   *
   * Everything else is allowed through, deliberately. `stdc++` is a real library and not an
   * identifier, `SDL2_ttf` is a real library and not lowercase, and a compiler that ruled on which
   * spellings the world is permitted would be wrong about a platform it had never seen.
   */
  def complaint(name: String): Option[String] =
    if name.isEmpty then Some("a link directive names a library, and '' is not a name")
    else if name.startsWith("-") then
      Some(s"'$name' begins with a dash, so it is a linker flag rather than a library — a directive " +
        "names the library itself and the target decides what that becomes on a command line")
    else if name.exists(_.isWhitespace) then
      Some(s"'$name' holds a space, so it is more than one word — a directive names one library, and " +
        "a file needing two writes two of them")
    else None
}

/** The pass that reads every file's link directives and refuses the ones that are not directives
 * about anything. It runs beside `readCapabilities` and for the same reason: a header is settled by
 * the text of the file alone, so there is nothing to wait for.
 */
trait LinkRequirements extends AnalyzerBase {

  protected def units: List[Program]

  /** Reports a malformed library name, and a file that asks for the same library twice.
   *
   * The library's own headers are **not** read here, matching `readCapabilities`: they were checked
   * when the library was built, and a complaint about one would point at a file the program's author
   * did not write and could not change. They still reach the *command line* — `Compiler.analyzed`
   * collects from the standard module as well — which is the whole point of an artifact carrying its directives.
   */
  protected def readLinkDirectives(): Unit =
    for u <- units do
      for l <- u.links do
        recover(())(at(l.pos)(LinkDirectives.complaint(l.name).foreach(err)))

      // Within one file a repeat says nothing the first said, exactly as a repeated capability does.
      // Across the files of a module it is not a repeat at all: each file states what its own
      // `extern`s need, and two files needing the same library is the ordinary case.
      for (name, ls) <- u.links.groupBy(_.name) if ls.length > 1 do
        recover(())(at(ls(1).pos)(err(s"'$name' is already linked by this file, and asking twice " +
          "links it once")))
}
