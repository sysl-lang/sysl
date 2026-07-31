package io.github.edadma.sysl

import java.nio.charset.StandardCharsets.UTF_8

/** The `ar` archive format, read.
 *
 * A `.syslib` is a real archive (`LibraryArtifact`), so getting the metadata back out means walking
 * the container the linker walks. The format is old and tiny — a magic, then a run of members, each
 * a fixed-width text header followed by its bytes — and it is the same on every platform, which is
 * the reason it is worth reading here rather than shelling out to `ar t`: the compiler cross-compiles
 * to machines whose object format it cannot parse, and it never has to, because **the container is
 * format-neutral even when its members are not**.
 *
 * Only reading is here. Writing an archive the linker will accept means writing a symbol index, and a
 * symbol index means parsing Mach-O, ELF and COFF symbol tables — which is exactly the dependency
 * this avoids. `llvm-ar` writes them (`Toolchain.archive`).
 */
object Ar {

  /** What every archive starts with. */
  val magic: Array[Byte] = "!<arch>\n".getBytes(UTF_8)

  private val headerSize = 60

  /** One member: the name the archiver recorded, and its bytes. */
  case class Member(name: String, body: Array[Byte])

  /** The members of an archive, or why the bytes are not one.
   *
   * Two long-name conventions are handled because both are met in practice, and which one appears
   * depends on the *target* rather than on the machine doing the archiving: `llvm-ar` writes BSD-style
   * names for a Darwin archive and GNU-style ones for an ELF archive, so a compiler that cross-builds
   * sees both. Getting this wrong does not fail loudly — it hands back members whose names are garbage
   * while their bodies are intact, which is why the names are parsed properly rather than scanned past.
   *
   *   - **BSD** — a name too long for the header is written `#1/<n>`, and the real name occupies the
   *     first `n` bytes *of the body*, with the size field counting them.
   *   - **GNU** — a long name is written `/<offset>` into the body of a member called `//`, and every
   *     short name is terminated with `/` so that trailing spaces are not part of it.
   */
  def members(bytes: Array[Byte]): Either[String, List[Member]] =
    if !bytes.startsWith(magic) then Left("it is not an archive")
    else {
      val gathered = List.newBuilder[Member]
      var names    = Array.empty[Byte]
      var pos      = magic.length

      while pos + headerSize <= bytes.length do {
        val header = bytes.slice(pos, pos + headerSize)
        val raw    = text(header, 0, 16)

        text(header, 48, 10).toIntOption match
          case Some(size) if size >= 0 && pos + headerSize + size <= bytes.length =>
            val body = bytes.slice(pos + headerSize, pos + headerSize + size)

            // The GNU string table has to be recorded before the members that point into it, and it
            // is written ahead of them for exactly that reason.
            if raw == "//" then names = body
            else gathered += resolve(raw, body, names)

            // A member's body is padded to an even offset, and the padding is not counted by its size.
            pos += headerSize + size + (size & 1)

          case _ => return Left("it is a damaged archive")
      }

      Right(gathered.result())
    }

  /** A member's real name and body, with either long-name convention undone. */
  private def resolve(raw: String, body: Array[Byte], names: Array[Byte]): Member =
    if raw.startsWith("#1/") then
      raw.drop(3).toIntOption.filter(n => n >= 0 && n <= body.length) match
        case Some(n) => Member(new String(body.take(n), UTF_8).takeWhile(_ != 0), body.drop(n))
        case None    => Member(raw, body)
    else if raw.startsWith("/") && raw.drop(1).forall(_.isDigit) && raw.length > 1 then
      raw.drop(1).toIntOption.filter(o => o >= 0 && o < names.length) match
        case Some(at) => Member(new String(names.drop(at), UTF_8).takeWhile(c => c != '/' && c != '\n'), body)
        case None     => Member(raw, body)
    else Member(raw.stripSuffix("/"), body)

  private def text(header: Array[Byte], at: Int, len: Int): String =
    new String(header.slice(at, at + len), UTF_8).trim
}
