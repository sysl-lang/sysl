package io.github.edadma.sysl

import io.github.edadma.cross_platform.*

import org.scalatest.freespec.AnyFreeSpec

/** The filesystem module: `File` and the two directions it moves bytes in, the questions a path can
 * be asked, and the whole-file calls above both.
 *
 * **Everything here runs.** What is being checked is that bytes reach a real file and come back, and
 * that a failing call reports the case it failed with — neither of which a declaration test could
 * tell from a plausible-looking one that never touched the disk. Each program is handed a directory
 * of its own as `args[1]` and builds its paths under it, so nothing here depends on the tree the
 * suite runs in or on two tests not colliding.
 */
class FsTests extends AnyFreeSpec with RunSupport {

  /** `sysl.fs` is a module of its own, so a program that touches a file says so. Written once and
   * prepended, since what each program below is about is the file and not the import.
   */
  private val importing = "import sysl.fs.*\nimport sysl.io.lines\nimport sysl.text.from_utf8\n\n"

  /** A program run with a directory of its own. It is not removed afterwards: the harness's
   * temporary directories are the operating system's to reclaim, and a test that failed is worth
   * being able to look at.
   */
  private def inDir(src: String): String = runWith(importing + src, createTempDirectory("sysl-fs-"))

  "bytes make the round trip" - {

    "written and read back" in {
      inDir(
        """main(args: []string)
          |    var p = args[1] + "/round"
          |
          |    write_text(p, "hello\n").unwrap()
          |    print(read_text(p).unwrap())""".stripMargin,
      ) shouldBe "hello\n\n"
    }

    "bytes that are not text survive unchanged" in {
      inDir(
        """main(args: []string)
          |    var p = args[1] + "/raw"
          |    var b: []u8 = [0u8, 255u8, 128u8, 10u8]
          |
          |    write_bytes(p, b).unwrap()
          |
          |    var back = read_bytes(p).unwrap()
          |
          |    print(back.len, back[0usize], back[1usize], back[2usize], back[3usize])""".stripMargin,
      ) shouldBe "4 0 255 128 10\n"
    }

    "an empty file reads back empty" in {
      inDir(
        """main(args: []string)
          |    var p = args[1] + "/empty"
          |
          |    write_text(p, "").unwrap()
          |    print(read_bytes(p).unwrap().len, size_of(p).unwrap())""".stripMargin,
      ) shouldBe "0 0\n"
    }

    /** The read loop takes 8192 bytes at a time, so a file either side of that boundary is where an
     * off-by-one in the refill would live. The test says the number it is sitting on, and is the
     * reminder if it ever changes.
     */
    "a file longer than one read of the loop" in {
      inDir(
        """main(args: []string)
          |    var p = args[1] + "/long"
          |    var b: []u8 = [65u8; 20000usize]
          |
          |    write_bytes(p, b).unwrap()
          |
          |    var back = read_bytes(p).unwrap()
          |
          |    print(back.len, back[0usize], back[19999usize])""".stripMargin,
      ) shouldBe "20000 65 65\n"
    }

    "creating over a file that is there empties it first" in {
      inDir(
        """main(args: []string)
          |    var p = args[1] + "/over"
          |
          |    write_text(p, "aaaaaaaaaa").unwrap()
          |    write_text(p, "b").unwrap()
          |    print(read_text(p).unwrap())""".stripMargin,
      ) shouldBe "b\n"
    }

    "appending lands after what was there" in {
      inDir(
        """main(args: []string)
          |    var p = args[1] + "/app"
          |
          |    write_text(p, "one").unwrap()
          |    append_text(p, "two").unwrap()
          |    append_text(p, "three").unwrap()
          |    print(read_text(p).unwrap())""".stripMargin,
      ) shouldBe "onetwothree\n"
    }

    "appending to a file that is not there makes it" in {
      inDir(
        """main(args: []string)
          |    var p = args[1] + "/fresh"
          |
          |    append_text(p, "new").unwrap()
          |    print(read_text(p).unwrap())""".stripMargin,
      ) shouldBe "new\n"
    }
  }

  "a file is a Reader" - {

    /** The whole payoff of implementing the trait that was already there: `lines` was written against
     * `Reader` with no idea a file would exist, and reads one with nothing added to it.
     */
    "the line cursor reads a file with nothing added to it" in {
      inDir(
        """main(args: []string)
          |    var p = args[1] + "/lines"
          |
          |    write_text(p, "one\ntwo\nthree\n").unwrap()
          |
          |    var f = open(p).unwrap()
          |
          |    for line in lines(&f)
          |        print("[", line, "]")
          |
          |    print("failed", f.failed())
          |    f.close().unwrap()""".stripMargin,
      ) shouldBe "[ one ]\n[ two ]\n[ three ]\nfailed false\n"
    }

    "a read hands back the prefix it filled, not the room it was offered" in {
      inDir(
        """main(args: []string)
          |    var p = args[1] + "/short"
          |
          |    write_text(p, "abc").unwrap()
          |
          |    var f = open(p).unwrap()
          |    var room: [64]u8
          |    var got = f.read(room[..])
          |
          |    print("asked", room.len, "got", got.len)
          |    f.close().unwrap()""".stripMargin,
      ) shouldBe "asked 64 got 3\n"
    }

    "a read past the end is empty, and says so every time" in {
      inDir(
        """main(args: []string)
          |    var p = args[1] + "/end"
          |
          |    write_text(p, "x").unwrap()
          |
          |    var f = open(p).unwrap()
          |    var room: [8]u8
          |
          |    print(f.read(room[..]).len, f.read(room[..]).len, f.read(room[..]).len)
          |    print("at_end", f.at_end(), "failed", f.failed())
          |    f.close().unwrap()""".stripMargin,
      ) shouldBe "1 0 0\nat_end true failed false\n"
    }
  }

  "a file is a Writer" - {

    /** The other half of the payoff: anything that renders into a `*Writer` renders into a file, so
     * the rendering surface of `14 §2` reaches the disk with nothing written for it here.
     */
    "a value renders itself into a file" in {
      inDir(
        """main(args: []string)
          |    var p = args[1] + "/render"
          |    var f = create(p).unwrap()
          |
          |    display_int(42i64, &f, FormatSpec(6, -1, false))
          |    display_str("!", &f, FormatSpec(0, -1, false))
          |    f.close().unwrap()
          |
          |    print("[", read_text(p).unwrap(), "]")""".stripMargin,
      ) shouldBe "[     42! ]\n"
    }

    "writing nothing writes nothing" in {
      inDir(
        """main(args: []string)
          |    var p = args[1] + "/none"
          |    var f = create(p).unwrap()
          |    var b: []u8 = []
          |
          |    f.write(b)
          |    print("failed", f.failed())
          |    f.close().unwrap()
          |    print(size_of(p).unwrap())""".stripMargin,
      ) shouldBe "failed false\n0\n"
    }

    "what was written is not there until it is flushed, and then it is" in {
      inDir(
        """main(args: []string)
          |    var p = args[1] + "/flush"
          |    var f = create(p).unwrap()
          |
          |    f.write("buffered".bytes)
          |    f.flush().unwrap()
          |    print(read_text(p).unwrap())
          |    f.close().unwrap()""".stripMargin,
      ) shouldBe "buffered\n"
    }
  }

  "a handle survives being copied, and closing is idempotent" - {

    /** The reason `File` holds its state through a reference rather than holding the pointer itself.
     * A struct is a value, so this is genuinely a second `File`; if it held the handle directly then
     * one of these closes would be freeing what the other still holds.
     */
    "a copy closes the same file, once" in {
      inDir(
        """main(args: []string)
          |    var p = args[1] + "/copy"
          |    var f = create(p).unwrap()
          |    var g = f
          |
          |    f.write("shared".bytes)
          |    g.close().unwrap()
          |
          |    print("f closed", f.closed(), "g closed", g.closed())
          |    print(read_text(p).unwrap())""".stripMargin,
      ) shouldBe "f closed true g closed true\nshared\n"
    }

    "closing twice is not an error" in {
      inDir(
        """main(args: []string)
          |    var p = args[1] + "/twice"
          |    var f = create(p).unwrap()
          |
          |    f.write("x".bytes)
          |    print(f.close().is_ok(), f.close().is_ok(), f.close().is_ok())""".stripMargin,
      ) shouldBe "true true true\n"
    }

    /** Which is what makes `defer` the idiom the module is written for: a function that closes on one
     * path and defers it for the rest reaches the end having done both.
     */
    "a deferred close runs after an early return" in {
      inDir(
        """took(p: string) -> long
          |    var f = open(p).unwrap()
          |
          |    defer f.close()
          |
          |    if f.size().unwrap() > 2i64 then return 1i64
          |
          |    0i64
          |
          |main(args: []string)
          |    var p = args[1] + "/defer"
          |
          |    write_text(p, "abcdef").unwrap()
          |    print(took(p))""".stripMargin,
      ) shouldBe "1\n"
    }
  }

  "seeking, telling and sizing" - {

    "size leaves the position where it found it" in {
      inDir(
        """main(args: []string)
          |    var p = args[1] + "/seek"
          |
          |    write_text(p, "0123456789").unwrap()
          |
          |    var f = open(p).unwrap()
          |    var room: [4]u8
          |
          |    f.read(room[..])
          |    print("at", f.tell().unwrap(), "size", f.size().unwrap(), "still at", f.tell().unwrap())
          |    f.close().unwrap()""".stripMargin,
      ) shouldBe "at 4 size 10 still at 4\n"
    }

    "a seek moves where the next read happens" in {
      inDir(
        """main(args: []string)
          |    var p = args[1] + "/back"
          |
          |    write_text(p, "0123456789").unwrap()
          |
          |    var f = open(p).unwrap()
          |    var room: [3]u8
          |
          |    f.seek(6i64).unwrap()
          |
          |    var got = f.read(room[..])
          |
          |    print(from_utf8(got).unwrap())
          |    f.close().unwrap()""".stripMargin,
      ) shouldBe "678\n"
    }

    "size_of asks without the caller opening anything" in {
      inDir(
        """main(args: []string)
          |    var p = args[1] + "/sized"
          |
          |    write_text(p, "seven!!").unwrap()
          |    print(size_of(p).unwrap())""".stripMargin,
      ) shouldBe "7\n"
    }
  }

  "what a path is" - {

    "a file that is there, and one that is not" in {
      inDir(
        """main(args: []string)
          |    var p = args[1] + "/here"
          |
          |    write_text(p, "x").unwrap()
          |    print(exists(p), exists(args[1] + "/nowhere"))
          |    print(is_file(p), is_dir(p))
          |    print(readable(p), writable(p))""".stripMargin,
      ) shouldBe "true false\ntrue false\ntrue true\n"
    }

    "a directory answers the other way round" in {
      inDir(
        """main(args: []string)
          |    print(exists(args[1]), is_dir(args[1]), is_file(args[1]))""".stripMargin,
      ) shouldBe "true true false\n"
    }
  }

  "directories" - {

    "one is made, found, and removed" in {
      inDir(
        """main(args: []string)
          |    var d = args[1] + "/sub"
          |
          |    print(exists(d))
          |    make_dir(d).unwrap()
          |    print(exists(d), is_dir(d))
          |    remove_dir(d).unwrap()
          |    print(exists(d))""".stripMargin,
      ) shouldBe "false\ntrue true\nfalse\n"
    }

    "a file goes in one and is read back through the path that names both" in {
      inDir(
        """main(args: []string)
          |    var d = args[1] + "/nest"
          |
          |    make_dir(d).unwrap()
          |    write_text(d + "/inner", "deep").unwrap()
          |    print(read_text(d + "/inner").unwrap())""".stripMargin,
      ) shouldBe "deep\n"
    }
  }

  "renaming" - {

    "a file moves, and the old name stops being there" in {
      inDir(
        """main(args: []string)
          |    var a = args[1] + "/from"
          |    var b = args[1] + "/to"
          |
          |    write_text(a, "moved").unwrap()
          |    rename(a, b).unwrap()
          |    print(exists(a), exists(b))
          |    print(read_text(b).unwrap())""".stripMargin,
      ) shouldBe "false true\nmoved\n"
    }

    "and it replaces whatever was at the destination" in {
      inDir(
        """main(args: []string)
          |    var a = args[1] + "/src"
          |    var b = args[1] + "/dst"
          |
          |    write_text(a, "new").unwrap()
          |    write_text(b, "old").unwrap()
          |    rename(a, b).unwrap()
          |    print(read_text(b).unwrap())""".stripMargin,
      ) shouldBe "new\n"
    }
  }

  "what a failure says" - {

    /** The reason `IoError` is an enum and not a number: a caller that wants to act on *why* matches,
     * and the match is exhaustive where a comparison against 2 is a guess that compiled.
     */
    "opening something that is not there is NotFound, by name" in {
      inDir(
        """main(args: []string)
          |    open(args[1] + "/absent") match
          |        Ok(f)  -> print("opened")
          |        Err(e) -> e match
          |            NotFound -> print("not found")
          |            _        -> print("something else:", e)""".stripMargin,
      ) shouldBe "not found\n"
    }

    "reading a directory as a file fails rather than answering bytes" in {
      inDir(
        """main(args: []string)
          |    read_bytes(args[1]) match
          |        Ok(b)  -> print("read", b.len)
          |        Err(e) -> print("no:", e)""".stripMargin,
      ) should startWith("no:")
    }

    "writing under a path whose parent is a file is NotADirectory" in {
      inDir(
        """main(args: []string)
          |    var p = args[1] + "/plain"
          |
          |    write_text(p, "x").unwrap()
          |
          |    write_text(p + "/under", "y") match
          |        Ok(_)  -> print("wrote")
          |        Err(e) -> print(e, e.code())""".stripMargin,
      ) shouldBe "not a directory 20\n"
    }

    "making a directory that is already there is AlreadyExists" in {
      inDir(
        """main(args: []string)
          |    var d = args[1] + "/dup"
          |
          |    make_dir(d).unwrap()
          |
          |    make_dir(d) match
          |        Ok(_)  -> print("made")
          |        Err(e) -> print(e, e.code())""".stripMargin,
      ) shouldBe "already exists 17\n"
    }

    /** The one code in the set the two platforms number differently, which is why `error.sysl` reads
     * it through a call gated on the platform rather than as a literal. If that gate ever rots, this
     * is the test that says so.
     */
    "removing a directory with something in it is DirectoryNotEmpty" in {
      inDir(
        """main(args: []string)
          |    var d = args[1] + "/full"
          |
          |    make_dir(d).unwrap()
          |    write_text(d + "/thing", "x").unwrap()
          |
          |    remove_dir(d) match
          |        Ok(_)  -> print("removed")
          |        Err(e) -> e match
          |            DirectoryNotEmpty -> print("not empty", e.code())
          |            _                 -> print("something else:", e)""".stripMargin,
      ) should startWith("not empty ")
    }

    "removing a file that is not there is NotFound" in {
      inDir(
        """main(args: []string)
          |    remove_file(args[1] + "/ghost") match
          |        Ok(_)  -> print("removed")
          |        Err(e) -> print(e, e.code())""".stripMargin,
      ) shouldBe "no such file or directory 2\n"
    }

    "removing a directory with the call that means files refuses it" in {
      inDir(
        """main(args: []string)
          |    var d = args[1] + "/adir"
          |
          |    make_dir(d).unwrap()
          |
          |    remove_file(d) match
          |        Ok(_)  -> print("removed", exists(d))
          |        Err(e) -> print("refused:", e)""".stripMargin,
      ) should startWith("refused:")
    }

    "renaming something that is not there is NotFound" in {
      inDir(
        """main(args: []string)
          |    rename(args[1] + "/none", args[1] + "/other") match
          |        Ok(_)  -> print("moved")
          |        Err(e) -> print(e, e.code())""".stripMargin,
      ) shouldBe "no such file or directory 2\n"
    }

    "sizing something that is not there reports the open's failure, not zero" in {
      inDir(
        """main(args: []string)
          |    size_of(args[1] + "/missing") match
          |        Ok(n)  -> print("size", n)
          |        Err(e) -> print(e)""".stripMargin,
      ) shouldBe "no such file or directory\n"
    }

    /** `?` is what makes the layered calls above readable, and this is it working through two of
     * them: `read_text` propagates out of `read_bytes`, which propagated out of `open`.
     */
    "a failure propagates out through the layers with '?'" in {
      inDir(
        """first_line(p: string) -> Result[string, IoError]
          |    var t = read_text(p)?
          |
          |    Ok(t)
          |
          |main(args: []string)
          |    first_line(args[1] + "/nope") match
          |        Ok(s)  -> print("got", s)
          |        Err(e) -> print("stopped at:", e)""".stripMargin,
      ) shouldBe "stopped at: no such file or directory\n"
    }

    "an error renders as its sentence and carries its number" in {
      inDir(
        """main(args: []string)
          |    var e = open(args[1] + "/x").unwrap_err()
          |
          |    print(e)
          |    print(e.message(), e.code())
          |    print(Other(99), Other(99).code())""".stripMargin,
      ) shouldBe "no such file or directory\nno such file or directory 2\nerror 99 99\n"
    }
  }
}
