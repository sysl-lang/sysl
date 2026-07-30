package io.github.edadma.sysl

import java.nio.charset.StandardCharsets.UTF_8

/** A library as one **file**: the half that could be compiled ahead of time as an object file, and
 * the half that could not as a tree (`13 § Open d`, `§ Open h`).
 *
 * **The split is the whole design, and it is the one Rust's `.rlib` makes.** A declaration with no
 * type parameters is compiled once, by whoever ships the library, and a program that calls it just
 * links against the result. A **generic** has nothing to compile until a caller fixes its type
 * arguments, so it travels as the tree it was parsed into and is monomorphized in the consuming
 * program. Rust carries MIR in `lib.rmeta` for exactly this reason; we carry the AST, which is the
 * same decision one stage earlier.
 *
 * The metadata carries **every** declaration, not only the generic ones — a call into the
 * precompiled half still has to be type-checked against a signature, and the tree is where the
 * signature is. What the symbol list adds is which of those the consumer must *declare* rather than
 * emit a second time.
 *
 * **Why a container of our own rather than `ar`.** An `.rlib` is an `ar` archive holding `.o`
 * members beside `lib.rmeta`, and that was the obvious thing to copy. It does not survive contact
 * with the platform tools: macOS `ar` runs `ranlib`, which *silently drops* a member that is not
 * Mach-O, and forcing the index off with `-S` only moves the failure to the linker, which reads
 * every member and refuses with `archive member 'lib.smeta' not a mach-o file`. Rust gets away with
 * it by wrapping the metadata in a real object file with the section marked excluded — which needs
 * an object-file writer *and* a reader, per platform, to get back out again. This container costs a
 * length and a copy, and it is the same shape: a header, the metadata, the object.
 *
 * An artifact is therefore **for one machine**, exactly as an `.rlib` is, because half of it is
 * compiled code. The tree half would travel anywhere; the object half is what pins it.
 */
object LibraryArtifact {

  /** What a library artifact is called. */
  val extension: String = ".syslib"

  /** What the metadata half is called where it stands alone — the name of the header section here,
   * and the extension of a metadata-only file. `rmeta` does the same job under the same reasoning.
   */
  val metadataName: String = "smeta"

  /** The container's version, refused on mismatch. It is not `AstCodec.Version`: the layout here and
   * the tree encoding inside it change for different reasons, and either alone makes an artifact
   * unreadable, so each says so on its own.
   */
  val Version: Int = 1

  private val Magic = "syslib"

  /** Whether a path names an artifact rather than a source tree. This is what lets `--lib` take
   * either without a second flag — how a library shipped is not something a program that depends on
   * it should have to write down.
   */
  def isArtifact(path: String): Boolean = path.endsWith(extension)

  /** What a library compiles to: the object code for its determined half, and the metadata a
   * consuming compilation reads.
   *
   * Every file is parsed before any is rejected, so one syntax error does not hide the rest — the
   * same rule the ordinary compilation follows.
   *
   * **The library is analyzed before anything is written.** A library that does not check is broken
   * once, by whoever built it; without this the artifact ships anyway and every program that links
   * against it is handed a diagnostic pointing into somebody else's source. `main` is optional
   * (`13 §7`), so a library having none is not a complaint.
   */
  def build(sources: List[Source], target: Target = Target.default, building: Set[String] = Set.empty)
      : Either[String, (String, String)] = {
    val parsed = sources.map(SyslParser.parse)

    parsed.collect { case Left(e) => e } match
      case errs if errs.nonEmpty => Left(errs.mkString("\n"))
      case _ =>
        val units = parsed.collect { case Right(p) => p }

        rootless(units) match
          case Some(err) => Left(err)
          case None =>
            Compiler.compileLibrary(units, target, building)
              .map((ir, compiled) => (ir, metadata(units, compiled)))
  }

  /** Why a library may not sit in the anonymous root module.
   *
   * Two reasons, and either alone would be enough. A library is reached by **naming** its module
   * (`13 §3`), and the root module has no name — so a declaration there is one no program that
   * depends on the library could write. And the root module is where a headerless *program's*
   * declarations go, so the two would share a key space: the library's `double` and the program's
   * would be one name, and which of them a call meant would depend on what else was linked.
   *
   * It is also what makes the split above exact. Everything the compiler supplied is keyed outside
   * the library's own modules, and that stops being true the moment the library is in the module the
   * prelude is in.
   */
  private def rootless(units: List[Program]): Option[String] =
    units.find(u => Compiler.moduleOf(u) == Modules.root).map(u =>
      s"a library is reached by naming its module, and ${u.source.name} is in none — " +
        "put the library's files in a directory under the root it is built from")

  /** The one library whose source the compiler also carries: its own standard module.
   *
   * Building it is the only compilation that is allowed to declare a module the library supplies,
   * and it is what `sysl build-lib --core` passes. Everything else about the build is the same —
   * which is the claim worth making, since a standard library that needed its own toolchain would
   * not be evidence that the toolchain works.
   */
  def core: Set[String] = Library.modules.toSet

  /** The metadata blob: how many symbols the object half defines, those symbols, then the tree. */
  private def metadata(units: List[Program], compiled: Set[String]): String = {
    val names = compiled.toList.sorted

    (names.length :: names).mkString("", "\n", "\n") + AstCodec.encode(units)
  }

  /** The two halves written as one file: a text header, the metadata, then the object bytes.
   *
   * The header is text and the length is in bytes rather than characters, so that a reader can find
   * the boundary without decoding anything — the metadata may hold any of the library's source text,
   * which is UTF-8 and not fixed-width.
   */
  def pack(meta: String, obj: Array[Byte]): Array[Byte] = {
    val body   = meta.getBytes(UTF_8)
    val header = s"$Magic $Version ${body.length}\n".getBytes(UTF_8)

    header ++ body ++ obj
  }

  /** The halves read back, or why the file is not one of ours. */
  def unpack(name: String, bytes: Array[Byte]): Either[String, (String, Array[Byte])] = {
    val newline = bytes.indexOf('\n'.toByte)

    if newline < 0 then Left(s"$name is not a sysl library: it has no header")
    else
      new String(bytes.take(newline), UTF_8).split(' ') match
        case Array(Magic, version, length) =>
          (version.toIntOption, length.toIntOption) match
            case (Some(v), _) if v != Version =>
              Left(s"$name was built by a different sysl (library format $v, this one reads $Version) " +
                "— rebuild it with 'sysl build-lib'")
            case (Some(_), Some(len)) if newline + 1 + len <= bytes.length =>
              val body = bytes.slice(newline + 1, newline + 1 + len)

              Right((new String(body, UTF_8), bytes.drop(newline + 1 + len)))
            case _ => Left(s"$name is a truncated sysl library")
        case _ => Left(s"$name is not a sysl library")
  }

  /** The modules an artifact carries and the symbols its object half defines. */
  def read(name: String, meta: String): Either[String, (List[Program], Set[String])] = {
    val newline = meta.indexOf('\n')

    Option.when(newline > 0)(meta.take(newline)).flatMap(_.trim.toIntOption).filter(_ >= 0) match
      case None => Left(s"$name is not a readable sysl library: its metadata header is missing")
      case Some(count) =>
        val rest  = meta.drop(newline + 1)
        val lines = rest.linesWithSeparators.take(count).toList
        val tree  = rest.drop(lines.map(_.length).sum)

        AstCodec.decode(tree).left
          .map(e => s"$name is not a readable sysl library: $e")
          .map((_, lines.map(_.stripLineEnd).toSet))
  }
}
