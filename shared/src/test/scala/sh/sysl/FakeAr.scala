package sh.sysl

import java.nio.charset.StandardCharsets.UTF_8

/** An `ar` archive assembled by hand, for tests.
 *
 * Two things need this. The reader's failure paths — not an archive, a damaged one, one carrying no
 * metadata — have no other way to be reached: every archive a toolchain produces is a *good* one, so
 * a suite that could only build them could never check what happens to a bad one.
 *
 * And the long-name conventions are only met by *making* them. Which of the two an archive uses is
 * decided by the target it was built for rather than by the machine building it, so a suite that ran
 * `llvm-ar` on the host would exercise exactly one of them and leave the other — the one that turns
 * up the first time somebody cross-builds a library — never having been read.
 */
object FakeAr {

  /** An archive whose member names all fit the header field, which is the ordinary case. */
  def apply(members: (String, Array[Byte])*): Array[Byte] = gnu(members.toList)

  /** GNU style: a short name is written with a trailing `/`, and a long one as `/<offset>` into the
   * body of a member called `//`, which is written ahead of the members that point into it.
   */
  def gnu(members: List[(String, Array[Byte])]): Array[Byte] = {
    val long   = members.map(_._1).filter(_.length > 15).distinct
    val table  = long.map(_ + "/\n").mkString
    val offset = long.map(n => n -> table.indexOf(n + "/\n")).toMap

    val named = members.map((name, body) =>
      member(if long.contains(name) then s"/${offset(name)}" else s"$name/", body))

    Ar.magic ++ (if long.isEmpty then Array.emptyByteArray else member("//", table.getBytes(UTF_8))) ++
      named.foldLeft(Array.emptyByteArray)(_ ++ _)
  }

  /** BSD style: a name too long for the header is written `#1/<n>`, and the real name occupies the
   * first `n` bytes of the body — with the size field counting them, which is the part a reader gets
   * wrong.
   *
   * The name is padded with NULs to a multiple of eight, as `llvm-ar` writes it, so that `n` is
   * longer than the name itself. A reader that took all `n` bytes for the name would come back with
   * one that compares equal to nothing, and a fixture that did not pad could never show it.
   */
  def bsd(members: List[(String, Array[Byte])]): Array[Byte] =
    Ar.magic ++ members.map { (name, body) =>
      if name.length > 16 then {
        val padded = name.getBytes(UTF_8).padTo((name.length + 7) / 8 * 8, 0.toByte)

        member(s"#1/${padded.length}", padded ++ body)
      } else member(name, body)
    }.foldLeft(Array.emptyByteArray)(_ ++ _)

  /** One member: the fixed-width text header, the body, and a pad byte to leave the next member on an
   * even offset. The pad is not counted by the size field, which is the other thing a reader gets
   * wrong — and only on a member of odd length, so it survives a suite whose fixtures are all even.
   */
  private def member(name: String, body: Array[Byte]): Array[Byte] = {
    val header =
      (name.padTo(16, ' ') + "0".padTo(12, ' ') + "0".padTo(6, ' ') + "0".padTo(6, ' ') +
        "644".padTo(8, ' ') + body.length.toString.padTo(10, ' ') + "`\n").getBytes(UTF_8)

    header ++ body ++ (if body.length % 2 == 1 then Array('\n'.toByte) else Array.emptyByteArray)
  }
}
