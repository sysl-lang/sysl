package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** The library's input half: `Reader`, `FdReader`, `find_byte`, and the `Lines` cursor `getline`
 * belongs to.
 *
 * Almost everything here runs, because what is being checked is that bytes arrive where the program
 * looks for them — a declaration test could not tell a working newline scan from one off by one. The
 * bytes come from a temporary file rather than from standard input for the reason `runReading`
 * records: a compiled program's stdin is closed by the harness, so a path through `argv` is the only
 * way real input reaches one. Everything above the file descriptor is exercised either way.
 */
class ReadingSurfaceTests extends AnyFreeSpec with RunSupport {

  /** The reading surface is a module rather than part of the standard one, so a program that reads
   * says so. It is written once here and prepended to every program below, since what each of them
   * is about is the bytes and not the import — but it is a real line in a real file, and a program
   * that leaves it out is refused, which `LibraryTreeTests` is where that is asserted.
   */
  private val importing = "import sysl.io.*\n\n"

  override protected def run(src: String): String = super.run(importing + src)

  override protected def runWith(src: String, args: String*): String =
    super.runWith(importing + src, args*)

  override protected def panics(src: String, message: String): Unit =
    super.panics(importing + src, message)

  /** The library offers no way to turn a path into a file descriptor, and deliberately: `Reader` is
   * about bytes arriving, not about where a program got its descriptors. So every program here opens
   * its own, which is also the demonstration that `FdReader` is not tied to standard input.
   */
  private val opening =
    """extern "open" c_open(path: *u8, flags: int) -> int
      |extern "close" c_close(fd: int) -> int
      |
      |opened(path: string) -> int
      |    var fd = c_open(cstring(path).ptr, 0)
      |
      |    if fd < 0
      |        print("could not open", path)
      |        exit(1)
      |
      |    fd
      |""".stripMargin

  private def reading(body: String, input: String): String = runReading(opening + body, input)

  /** A reader over bytes a test chose, which is how input that a `string` could not hold gets in:
   * the file-backed tests can only carry well-formed text, since the harness writes their input as
   * UTF-8.
   */
  private val byteReader =
    """struct Bytes
      |    src: []const u8
      |    at: usize
      |end Bytes
      |
      |bytes_reader(b: []const u8) -> Bytes = Bytes(b, 0usize)
      |
      |impl Reader for Bytes
      |    read(*self, into: []u8) -> []const u8
      |        var n = 0usize
      |
      |        while n < into.len && self.at < self.src.len
      |            into[n] = self.src[self.at]
      |            self.at += 1usize
      |            n += 1usize
      |
      |        into[0..<n]
      |""".stripMargin

  /** Every line of a program that just echoes what it read, since most of these differ only in the
   * bytes going in.
   */
  private val echo =
    """main(args: []string)
      |    var fd = opened(args[1])
      |    var r = fd_reader(fd)
      |
      |    for line in lines(&r)
      |        print("[", line, "]")
      |
      |    c_close(fd)""".stripMargin

  /** The library reads in 4096-byte bites. Nothing outside it should care, but the cases either side
   * of a boundary are exactly where an off-by-one lives, so the tests that sit on one say which
   * number they are sitting on — and are the reminder if it ever changes.
   */
  private val chunk = 4096

  "a reader hands back the prefix it filled" - {
    // The whole argument for the chosen shape: the count and the bytes arrive as one value, so there
    // is no way to be given a length and forget to apply it.
    "the slice is as long as what arrived, not as long as what was offered" in {
      val src =
        """main(args: []string)
          |    var fd = opened(args[1])
          |    var r = fd_reader(fd)
          |    var room: [64]u8
          |    var got = r.read(room[..])
          |    var text = from_utf8(got) match
          |        Ok(s) -> s
          |        Err(e) -> "not text"
          |
          |    print("asked", room.len, "got", got.len)
          |    print(text)
          |    c_close(fd)""".stripMargin

      reading(src, "hello") shouldBe "asked 64 got 5\nhello\n"
    }

    "a second read past the end comes back empty, and says nothing about failing" in {
      val src =
        """main(args: []string)
          |    var fd = opened(args[1])
          |    var r = fd_reader(fd)
          |    var room: [16]u8
          |
          |    print("first", r.read(room[..]).len)
          |    print("second", r.read(room[..]).len)
          |    print("failed", r.failed())
          |    c_close(fd)""".stripMargin

      reading(src, "abc") shouldBe "first 3\nsecond 0\nfailed false\n"
    }

    // A zero-length ask has no first byte to hand the platform, so it is answered without asking.
    "an empty buffer is filled without going anywhere" in {
      val src =
        """main(args: []string)
          |    var fd = opened(args[1])
          |    var r = fd_reader(fd)
          |    var none: [0]u8
          |
          |    print("got", r.read(none[..]).len, "failed", r.failed())
          |    c_close(fd)""".stripMargin

      reading(src, "plenty here") shouldBe "got 0 failed false\n"
    }

    "reading a file a bite at a time reaches every byte" in {
      val src =
        """main(args: []string)
          |    var fd = opened(args[1])
          |    var r = fd_reader(fd)
          |    var room: [4]u8
          |    var total = 0usize
          |    var reads = 0
          |
          |    loop
          |        var got = r.read(room[..])
          |
          |        if got.len == 0usize then break
          |
          |        total += got.len
          |        reads += 1
          |
          |    print("bytes", total, "reads", reads)
          |    c_close(fd)""".stripMargin

      reading(src, "0123456789") shouldBe "bytes 10 reads 3\n"
    }
  }

  /** `failed` is the only thing that separates "input ended" from "input ended badly", since an
   * empty read says the first and cannot say the second.
   */
  "the failure latch" - {
    "a read that could not happen latches, and reads empty" in {
      val src =
        """var r = fd_reader(99)
          |var room: [8]u8
          |
          |print("got", r.read(room[..]).len)
          |print("failed", r.failed())""".stripMargin

      run(src) shouldBe "got 0\nfailed true\n"
    }

    "it stays latched once set" in {
      val src =
        """var r = fd_reader(99)
          |var room: [8]u8
          |
          |r.read(room[..])
          |r.read(room[..])
          |print(r.failed())""".stripMargin

      run(src) shouldBe "true\n"
    }

    // The default in the trait is `false`, so a source that cannot fail says nothing about failing.
    "a reader that cannot fail inherits the answer rather than writing it" in {
      val src =
        """struct Empty
          |    unused: int
          |end Empty
          |
          |impl Reader for Empty
          |    read(*self, into: []u8) -> []const u8 = into[0..<0usize]
          |
          |var e = Empty(0)
          |var room: [4]u8
          |
          |print(e.read(room[..]).len, e.failed())""".stripMargin

      run(src) shouldBe "0 false\n"
    }
  }

  "find_byte" - {
    "reports where the byte is, and nothing when it is absent" in {
      val src =
        """say(o: Option[usize])
          |    o match
          |        Some(i) -> print(i)
          |        None -> print("-")
          |
          |var b = "abcdef".bytes
          |
          |say(find_byte(b, 97u8))
          |say(find_byte(b, 102u8))
          |say(find_byte(b, 122u8))""".stripMargin

      run(src) shouldBe "0\n5\n-\n"
    }

    "finds the first of several rather than any of them" in {
      val src =
        """var at = find_byte("axbxc".bytes, 120u8) match
          |    Some(i) -> i
          |    None -> 99usize
          |
          |print(at)""".stripMargin

      run(src) shouldBe "1\n"
    }

    // An empty slice has no first byte to hand `memchr`, so the answer comes before the call.
    "an empty slice holds nothing" in {
      val src =
        """var b: [0]u8
          |var said = find_byte(b[..], 0u8) match
          |    Some(i) -> "found"
          |    None -> "nothing"
          |
          |print(said)""".stripMargin

      run(src) shouldBe "nothing\n"
    }

    // Which is the difference between this and a search over a C string.
    "a NUL is a byte like any other" in {
      val src =
        """var b: [4]u8
          |
          |b[0] = 65u8
          |b[1] = 0u8
          |b[2] = 66u8
          |b[3] = 0u8
          |
          |var at = find_byte(b[..], 0u8) match
          |    Some(i) -> i
          |    None -> 99usize
          |
          |print(at)""".stripMargin

      run(src) shouldBe "1\n"
    }

    "it searches the slice it was given and not one byte past it" in {
      val src =
        """var b: [6]u8
          |
          |for i in 0..<6 do b[i] = 65u8
          |
          |b[4] = 10u8
          |
          |var said = find_byte(b[0..<4], 10u8) match
          |    Some(i) -> "found"
          |    None -> "nothing in the first four"
          |
          |print(said)""".stripMargin

      run(src) shouldBe "nothing in the first four\n"
    }
  }

  "reading lines" - {
    "one line per newline" in {
      reading(echo, "alpha\nbeta\ngamma\n") shouldBe "[ alpha ]\n[ beta ]\n[ gamma ]\n"
    }

    // The last line of a file often has no terminator, and dropping it is the classic bug here.
    "a final line without a newline is still a line" in {
      reading(echo, "one\ntwo") shouldBe "[ one ]\n[ two ]\n"
    }

    "a trailing newline ends the last line rather than starting an empty one" in {
      reading(echo, "only\n") shouldBe "[ only ]\n"
    }

    "empty input holds no lines at all" in {
      reading(echo, "") shouldBe ""
    }

    "an empty line is a line" in {
      reading(echo, "\n\nmid\n\n") shouldBe "[  ]\n[  ]\n[ mid ]\n[  ]\n"
    }

    // Input written where lines end `\r\n` reads the same as input written where they end `\n`,
    // which is `bufio.Scanner`'s choice rather than C `getline`'s.
    "a carriage return leaves with the newline it came with" in {
      reading(echo, "a\r\nb\r\n") shouldBe "[ a ]\n[ b ]\n"
    }

    "a lone carriage return in the middle of a line stays there" in {
      reading(echo, "a\rb\n") shouldBe "[ a\rb ]\n"
    }

    "a carriage return ending an unterminated last line goes too" in {
      reading(echo, "a\r") shouldBe "[ a ]\n"
    }

    "a line of nothing but a carriage return is an empty line" in {
      reading(echo, "\r\nafter\n") shouldBe "[  ]\n[ after ]\n"
    }

    "text is text, whatever width its characters are" in {
      reading(echo, "héllo\n日本語\n") shouldBe "[ héllo ]\n[ 日本語 ]\n"
    }

    // A `for` stops at the first `None`, but a caller holding the cursor can ask again — and the
    // answer has to stay `None`, since an empty read is the end and not a pause.
    "asking again past the end keeps saying there is nothing" in {
      val src =
        """main(args: []string)
          |    var fd = opened(args[1])
          |    var r = fd_reader(fd)
          |    var ls = lines(&r)
          |
          |    for i in 0..<4
          |        var s = ls.getline() match
          |            Some(t) -> t
          |            None -> "-"
          |
          |        print(i, s)
          |
          |    c_close(fd)""".stripMargin

      reading(src, "one\ntwo\n") shouldBe "0 one\n1 two\n2 -\n3 -\n"
    }
  }

  /** A line is found by scanning the buffer bytes arrived in, so the cases that matter are the ones
   * where a line does not fit inside one — and the two either side of the boundary, where an
   * off-by-one would live.
   */
  "a line that does not fit in one read" - {
    "a line longer than the buffer arrives whole" in {
      val long = "z" * (chunk * 2 + 17)

      reading(echo, long + "\nafter\n") shouldBe s"[ $long ]\n[ after ]\n"
    }

    // The gathering buffer is reused between lines, so a second long line is what would show a
    // missing reset — it would arrive with the first still in front of it.
    "two long lines in a row do not run into each other" in {
      val a = "a" * (chunk + 500)
      val b = "b" * (chunk + 900)

      reading(echo, s"$a\n$b\nshort\n") shouldBe s"[ $a ]\n[ $b ]\n[ short ]\n"
    }

    "a newline as the buffer's last byte" in {
      val fits = "x" * (chunk - 1)

      reading(echo, fits + "\nafter\n") shouldBe s"[ $fits ]\n[ after ]\n"
    }

    "a newline as the first byte of the next read" in {
      val fills = "y" * chunk

      reading(echo, fills + "\nafter\n") shouldBe s"[ $fills ]\n[ after ]\n"
    }

    "a long line with no newline after it" in {
      val long = "w" * (chunk + 3)

      reading(echo, long) shouldBe s"[ $long ]\n"
    }

    // Validation happens once, on the whole line, so a character split across two reads is put back
    // together before anything looks at it — where a per-read check would have rejected it.
    "a character split across two reads is one character" in {
      val pad = "q" * (chunk - 1)

      reading(echo, pad + "中rest\n") shouldBe s"[ ${pad}中rest ]\n"
    }

    "many lines come back in order and unchanged" in {
      val text = (0 until 2000).map(i => s"line $i").mkString("", "\n", "\n")

      reading(echo, text) shouldBe text.linesIterator.map(l => s"[ $l ]").mkString("", "\n", "\n")
    }
  }

  /** `getline` yields a `string`, so the bytes are validated where they arrive. A caller who would
   * rather look before trusting has the layer underneath — which is the point of having two.
   */
  "text that is not text" - {
    "ill-formed input stops the program and says where in the line" in {
      val src =
        s"""$byteReader
           |var raw: [4]u8
           |
           |raw[0] = 97u8
           |raw[1] = 255u8
           |raw[2] = 98u8
           |raw[3] = 10u8
           |
           |var r = bytes_reader(raw[..])
           |
           |for line in lines(&r)
           |    print("[", line, "]")""".stripMargin

      panics(src, "not UTF-8 at byte 1 of the line")
    }

    "the offset is into the line, not into the file" in {
      val src =
        s"""$byteReader
           |var raw: [7]u8
           |
           |raw[0] = 111u8
           |raw[1] = 107u8
           |raw[2] = 10u8
           |raw[3] = 120u8
           |raw[4] = 121u8
           |raw[5] = 255u8
           |raw[6] = 10u8
           |
           |var r = bytes_reader(raw[..])
           |
           |for line in lines(&r)
           |    print("[", line, "]")""".stripMargin

      panics(src, "not UTF-8 at byte 2 of the line")
    }

    "the lines before it were handed over before it stopped" in {
      val src =
        s"""$byteReader
           |var raw: [4]u8
           |
           |raw[0] = 111u8
           |raw[1] = 107u8
           |raw[2] = 10u8
           |raw[3] = 255u8
           |
           |var r = bytes_reader(raw[..])
           |
           |for line in lines(&r)
           |    print("[", line, "]")""".stripMargin

      panics(src, "[ ok ]")
    }

    // Reading bytes asks nothing about what they mean, so the same input is inspectable rather than
    // fatal one layer down.
    "the same bytes read as bytes are not fatal" in {
      val src =
        s"""$byteReader
           |var raw: [3]u8
           |
           |raw[0] = 97u8
           |raw[1] = 98u8
           |raw[2] = 255u8
           |
           |var r = bytes_reader(raw[..])
           |var room: [16]u8
           |var got = r.read(room[..])
           |var said = from_utf8(got) match
           |    Ok(s) -> 99usize
           |    Err(e) -> e.offset
           |
           |print(said)""".stripMargin

      run(src) shouldBe "2\n"
    }
  }

  /** `Lines` borrows its reader instead of owning it, and these are why: a `for` iterates a copy, so
   * a reader held inside the cursor would latch its failure where nobody could ask.
   */
  "the cursor borrows the reader" - {
    "a 'for' loop leaves the reader in the caller's hands, still answerable" in {
      val src =
        """main(args: []string)
          |    var fd = opened(args[1])
          |    var r = fd_reader(fd)
          |    var n = 0
          |
          |    for line in lines(&r)
          |        n += 1
          |
          |    print("lines", n, "failed", r.failed())
          |    c_close(fd)""".stripMargin

      reading(src, "a\nb\nc\n") shouldBe "lines 3 failed false\n"
    }

    "a reader that could not be read from says so after the loop, not during it" in {
      val src =
        """var r = fd_reader(99)
          |var n = 0
          |
          |for line in lines(&r)
          |    n += 1
          |
          |print("lines", n, "failed", r.failed())""".stripMargin

      run(src) shouldBe "lines 0 failed true\n"
    }

    // The fact the borrow exists for: a `for` hands its iterator a copy, so state the loop advanced
    // is not state the loop's variable holds afterwards. Were the reader inside the cursor, `failed`
    // would be answering about a copy nobody can reach.
    "a 'for' advances a copy of its iterator, not the variable" in {
      val src =
        """struct Count
          |    at: int
          |end Count
          |
          |impl Iterate[int] for Count
          |    next(*self) -> Option[int]
          |        if self.at >= 3 then return None
          |
          |        self.at += 1
          |        Some(self.at)
          |
          |var c = Count(0)
          |var seen = 0
          |
          |for v in c do seen += v
          |
          |print("seen", seen, "but the var is still at", c.at)""".stripMargin

      run(src) shouldBe "seen 6 but the var is still at 0\n"
    }
  }

  /** `Reader` is a trait rather than a concrete type, so nothing above it is about file descriptors.
   * A program that reads from somewhere else supplies its own and keeps `getline`.
   */
  "any reader will do" - {
    "a reader over bytes reads lines exactly as a file does" in {
      val src =
        s"""$byteReader
           |var r = bytes_reader("first\\nsecond\\n".bytes)
           |
           |for line in lines(&r)
           |    print("[", line, "]")""".stripMargin

      run(src) shouldBe "[ first ]\n[ second ]\n"
    }

    // A reader that hands back one byte at a time is the pathological case for a buffered scan: the
    // line is assembled entirely out of refills, so every boundary is a line boundary.
    "a reader that yields one byte at a time still yields whole lines" in {
      val src =
        s"""$byteReader
           |struct Trickle
           |    inner: &Bytes
           |end Trickle
           |
           |impl Reader for Trickle
           |    read(*self, into: []u8) -> []const u8
           |        if into.len == 0usize then return into
           |
           |        self.inner.read(into[0..<1usize])
           |
           |var b = bytes_reader("ab\\ncd\\n".bytes)
           |var t = Trickle(b)
           |
           |for line in lines(&t)
           |    print("[", line, "]")""".stripMargin

      run(src) shouldBe "[ ab ]\n[ cd ]\n"
    }

    // The cursor scans the slice `read` returned rather than the one it offered, so a reader that
    // hands back a view of a buffer of its own — never touching `into` at all — reads correctly.
    // Scanning the offered buffer would have found whatever was left in it instead.
    "a reader may hand back its own buffer rather than fill the one it was given" in {
      val src =
        """struct Canned
          |    text: []const u8
          |    at: usize
          |end Canned
          |
          |impl Reader for Canned
          |    read(*self, into: []u8) -> []const u8
          |        if self.at > 0usize then return self.text[0..<0usize]
          |
          |        self.at = 1usize
          |        self.text
          |
          |var c = Canned("own\nbuffer\n".bytes, 0usize)
          |
          |for line in lines(&c)
          |    print("[", line, "]")""".stripMargin

      run(src) shouldBe "[ own ]\n[ buffer ]\n"
    }

    // An empty read is the end and not a pause, which is what `read(2)` means by zero — a failure
    // arrives as `-1` and latches instead. So a source that goes quiet and then speaks again is not
    // heard, and that is the documented reading rather than an oversight.
    "an empty read ends the cursor even if the reader had more to say" in {
      val src =
        """struct Stutter
          |    step: int
          |end Stutter
          |
          |impl Reader for Stutter
          |    read(*self, into: []u8) -> []const u8
          |        var text = if self.step == 0 then "first\n" else if self.step == 1 then "" else "late\n"
          |
          |        self.step += 1
          |
          |        var n = 0usize
          |
          |        while n < into.len && n < text.len
          |            into[n] = text.bytes[n]
          |            n += 1usize
          |
          |        into[0..<n]
          |
          |var s = Stutter(0)
          |
          |for line in lines(&s)
          |    print("[", line, "]")
          |
          |print("stopped after step", s.step)""".stripMargin

      run(src) shouldBe "[ first ]\nstopped after step 2\n"
    }

    "a cursor reaches its reader through the trait, whatever the reader is" in {
      val src =
        s"""$byteReader
           |count(r: *Reader) -> int
           |    var n = 0
           |
           |    for line in lines(r)
           |        n += 1
           |
           |    n
           |
           |var a = bytes_reader("x\\ny\\n".bytes)
           |var b = fd_reader(99)
           |
           |print(count(&a), count(&b))""".stripMargin

      run(src) shouldBe "2 0\n"
    }
  }

  /** `stdin` is the descriptor the platform opens for a program, named so a caller does not have to
   * know it is zero. Nothing here can feed it — see `runReading` — so what is checked is that it is
   * that descriptor and that reading it is uneventful.
   */
  "standard input" - {
    "is descriptor zero, and reads empty when there is nothing on it" in {
      val src =
        """var r = stdin()
          |var room: [16]u8
          |
          |print("fd", r.fd, "got", r.read(room[..]).len, "failed", r.failed())""".stripMargin

      run(src) shouldBe "fd 0 got 0 failed false\n"
    }

    "a line cursor over it ends at once rather than hanging" in {
      val src =
        """var r = stdin()
          |var n = 0
          |
          |for line in lines(&r)
          |    n += 1
          |
          |print("lines", n)""".stripMargin

      run(src) shouldBe "lines 0\n"
    }
  }

  /** None of this costs a program that reads nothing, which is the rule `14 §2` states for a library
   * member and the reason the surface can live in the library at all.
   */
  "a program that reads nothing carries none of it" - {
    "the platform's read and memchr are not declared" in {
      val ir = Compiler.compileToLlvm("print(1)", "<input>").getOrElse(fail("the library broke"))

      ir should not include "@read("
      ir should not include "@memchr("
    }

    "no part of the cursor is emitted" in {
      // Every name here is read off the seam, and a **negative** assertion is where that matters
      // most: a spelled `@find_byte` would go on passing after the declaration moved and the symbol
      // became `@sysl$find_byte` — vacuously, testing nothing, with nothing to say it had stopped.
      val ir = Compiler.compileToLlvm("print(1)", "<input>").getOrElse(fail("the library broke"))

      ir should not include s"@${Library.key("Lines")}.getline"
      ir should not include s"@${Library.key("find_byte")}("
      ir should not include s"@${Library.key("lines")}("
    }

    // The one thing that *is* paid: a non-generic type the library declares gets its layout line
    // whether or not anything reaches it. It names no storage and emits no instruction.
    "only the layout of the types is, which is what the library says it costs" in {
      val ir = Compiler.compileToLlvm("print(1)", "<input>").getOrElse(fail("the library broke"))

      ir should include(s"%struct.${Library.key("FdReader")} = type")
      ir should include(s"%struct.${Library.key("Lines")} = type")
    }

    "and the platform's read arrives the moment something asks for it" in {
      val src =
        importing +
          """var r = stdin()
            |var room: [8]u8
            |
            |print(r.read(room[..]).len)""".stripMargin
      val ir = Compiler.compileToLlvm(src, "<input>").getOrElse(fail("the reading surface broke"))

      ir should include("declare i64 @read(i32, ptr, i64)")
    }
  }
}
