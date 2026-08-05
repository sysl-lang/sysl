package sh.sysl

import java.nio.charset.StandardCharsets.UTF_8

import io.github.edadma.cross_platform.*

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** The `ar` container, read (`Ar`).
 *
 * A `.syslib` is one of these, so this is the layer everything about libraries stands on. What is
 * worth pinning is the **names**, because that is the part that fails quietly: a member's body is
 * either there or it is not, but a mis-parsed name comes back as plausible garbage while the bytes
 * beside it are perfect — and the metadata is found by scanning, so a scan would keep working and
 * hide it.
 */
class ArTests extends AnyFreeSpec with Matchers {

  private def bytes(s: String): Array[Byte] = s.getBytes(UTF_8)

  private def read(archive: Array[Byte]): List[Ar.Member] =
    Ar.members(archive) match
      case Right(m)  => m
      case Left(why) => fail(s"the archive did not read: $why")

  private def shown(members: List[Ar.Member]): List[(String, String)] =
    members.map(m => (m.name, new String(m.body, UTF_8)))

  "the members of an archive" - {

    "come back in order, with their names and their bytes" in {
      shown(read(FakeAr("first.o" -> bytes("one"), "second.o" -> bytes("two")))) shouldBe
        List(("first.o", "one"), ("second.o", "two"))
    }

    "survive a body of odd length, which is padded without the size saying so" in {
      // The pad byte is not counted by the size field, so a reader that advanced by the size alone
      // would start the next header one byte early — and only ever on an odd-length member, which is
      // why a fixture of exactly that shape is here.
      shown(read(FakeAr("odd.o" -> bytes("abc"), "next.o" -> bytes("xy")))) shouldBe
        List(("odd.o", "abc"), ("next.o", "xy"))
    }

    "carry a body with no characters in it at all" in {
      // Object files are not text, and the reader must not decode anything to walk past one.
      val body = Array[Byte](0, -1, 10, -128, 127)

      read(FakeAr("raw.o" -> body)).head.body.toList shouldBe body.toList
    }

    "include an empty one rather than stopping at it" in {
      shown(read(FakeAr("empty.o" -> Array.emptyByteArray, "after.o" -> bytes("z")))) shouldBe
        List(("empty.o", ""), ("after.o", "z"))
    }
  }

  "a name too long for the header field" - {

    "is read back whole from the GNU string table" in {
      val long = "a-really-long-member-name.o"

      shown(Ar.members(FakeAr.gnu(List(long -> bytes("body")))).getOrElse(fail("did not read"))) shouldBe
        List((long, "body"))
    }

    "is read back whole from the BSD name-in-the-body form" in {
      // The size field counts the name as well as the body, so a reader that took the whole body
      // would hand back the name glued to the front of the object — which links, and is wrong.
      val long = "a-really-long-member-name.o"

      shown(Ar.members(FakeAr.bsd(List(long -> bytes("body")))).getOrElse(fail("did not read"))) shouldBe
        List((long, "body"))
    }

    "does not take the string table itself for a member" in {
      // The `//` member is the archiver's bookkeeping, not content, and handing it back would put a
      // member in the list that no compiler ever put there.
      read(FakeAr.gnu(List("a-really-long-member-name.o" -> bytes("body")))).map(_.name) shouldBe
        List("a-really-long-member-name.o")
    }
  }

  "what is not an archive" - {

    "is refused rather than read as an empty one" in {
      // Handing back no members would make a foreign file indistinguishable from a library that
      // happened to be empty, and the diagnostic the user needs names the file, not its contents.
      Ar.members(bytes("not an archive at all")) match
        case Left(why) => why should include("not an archive")
        case Right(_)  => fail("a foreign file was read as an archive")
    }

    "and neither is an empty file" in {
      Ar.members(Array.emptyByteArray) should matchPattern { case Left(_) => }
    }

    "nor one whose member runs off the end of it" in {
      val truncated = FakeAr("code.o" -> bytes("0123456789")).dropRight(4)

      Ar.members(truncated) match
        case Left(why) => why should include("damaged")
        case Right(_)  => fail("a truncated archive was accepted")
    }

    "nor one whose size field is not a number" in {
      val damaged = FakeAr("code.o" -> bytes("body"))
      val at      = Ar.magic.length + 48

      damaged(at) = 'x'.toByte

      Ar.members(damaged) should matchPattern { case Left(_) => }
    }
  }

  "an archive a real archiver wrote" - {

    // Everything above is read from a fixture this suite wrote, which pins the reader against the
    // format as *we* understand it. This pins that understanding against the tool, which is the only
    // thing that can catch the two agreeing with each other and both being wrong.

    "reads back the member it was given, symbol index and all" in {
      assume(Toolchain.clangAvailable, "clang not available")

      val ar = Toolchain.findAr(None) match
        case Right(path) => path
        case Left(why)   => cancel(why)

      val staging = createTempDirectory("sysl-ar-")
      val obj     = s"$staging/probe.o"
      val out     = s"$staging/probe.a"

      Toolchain.compileObject("@probe = constant [4 x i8] c\"halt\"\n", obj) match
        case Left(err) => fail(s"the probe did not assemble: $err")
        case Right(_)  => ()

      Toolchain.archive(List(obj), out, ar) shouldBe Right(())

      val members = read(readBytes(out))

      // The symbol index is a member like any other and comes back with them; what matters is that
      // the real one is found by name and its bytes are the object that went in.
      members.map(_.name) should contain("probe.o")
      members.find(_.name == "probe.o").map(_.body.toList) shouldBe Some(readBytes(obj).toList)

      List(obj, out, staging).foreach(p => try deleteFile(p) catch case _: Exception => ())
    }
  }
}
