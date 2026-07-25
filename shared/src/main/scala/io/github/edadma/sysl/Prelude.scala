package io.github.edadma.sysl

/** Declarations every program starts with.
 *
 * These are ordinary sysl source, parsed and hoisted ahead of the user's own declarations —
 * they use nothing the language does not already offer. `Option` and `Result` are here rather
 * than built into the analyzer because they *are* just generic enums; only the `?` operator
 * knows their names.
 *
 * **The printing surface lives here rather than in the compiler.** `print(a, b, c)` is a
 * desugaring onto these one-value functions, chosen by each argument's static type, so the
 * compiler knows a handful of *names* and implements no printing of its own.
 *
 * Everything goes out through the single sink `putbytes`, and that is not incidental: two
 * mechanisms means two buffers, and output emerging in the wrong order. It writes a byte at a time
 * because a `string` may hold an interior NUL and every shortcut through C — `puts`, `%s`, even
 * `%.*s` — stops at one. It is also the one function a freestanding target has to replace: swap its
 * body for a `write` syscall and the rest of the surface is unchanged.
 *
 * The integer and float renderings lean on `snprintf`, which is formatting rather than I/O. Doing
 * them in sysl is a small job for the integers and a large one for the floats (correct shortest
 * round-trip), so they wait until there is a reason — a target without a C library.
 *
 * The three `extern`s are the only things here that are not sysl. Two of them are plumbing rather
 * than surface, so they take a link name and leave `putchar` and `snprintf` free for a program to
 * declare itself. `exit` is deliberately not one of those: it is the prelude's offer of the hosted
 * exit, what `unwrap` and `expect` stop the program with — a diagnostic printed and a non-zero
 * status, which is what `11-error-handling.md` says a trap does under the `os` capability — and it
 * is the reason those two need no compiler support of their own.
 *
 * None of this costs an unused program anything: the enums' members are generic, so one exists
 * only where a call asks for it, a top-level function is analyzed and emitted only if something
 * reaches it, and an `extern` is declared only if something calls it.
 */
object Prelude {

  val source: String =
    """extern exit(code: int) -> never
      |extern "putchar" sysl_putchar(c: int) -> int
      |extern "snprintf" sysl_snprintf(buf: *u8, n: usize, fmt: *u8, ...) -> int
      |
      |putbytes(b: []u8)
      |    var i = 0usize
      |    while i < b.len
      |        sysl_putchar(int(b[i]))
      |        i += 1usize
      |end putbytes
      |
      |prints(s: string) = putbytes(s.bytes)
      |
      |printi(n: long)
      |    var buf: [24]u8
      |    var k = sysl_snprintf(&buf[0], 24usize, c"%lld", n)
      |    putbytes(buf[0..<usize(k)])
      |end printi
      |
      |printu(n: ulong)
      |    var buf: [24]u8
      |    var k = sysl_snprintf(&buf[0], 24usize, c"%llu", n)
      |    putbytes(buf[0..<usize(k)])
      |end printu
      |
      |printr(x: real)
      |    var buf: [32]u8
      |    var k = sysl_snprintf(&buf[0], 32usize, c"%g", x)
      |    putbytes(buf[0..<usize(k)])
      |end printr
      |
      |printb(b: bool) = prints(if b then "true" else "false")
      |
      |printc(ch: char)
      |    var buf: [4]u8
      |    var cp = uint(ch)
      |    if cp < 128u32 then
      |        buf[0] = u8(cp)
      |        putbytes(buf[0..<1usize])
      |    elif cp < 2048u32 then
      |        buf[0] = u8(192u32 | (cp >> 6u32))
      |        buf[1] = u8(128u32 | (cp & 63u32))
      |        putbytes(buf[0..<2usize])
      |    elif cp < 65536u32 then
      |        buf[0] = u8(224u32 | (cp >> 12u32))
      |        buf[1] = u8(128u32 | ((cp >> 6u32) & 63u32))
      |        buf[2] = u8(128u32 | (cp & 63u32))
      |        putbytes(buf[0..<3usize])
      |    else
      |        buf[0] = u8(240u32 | (cp >> 18u32))
      |        buf[1] = u8(128u32 | ((cp >> 12u32) & 63u32))
      |        buf[2] = u8(128u32 | ((cp >> 6u32) & 63u32))
      |        buf[3] = u8(128u32 | (cp & 63u32))
      |        putbytes(buf[0..<4usize])
      |end printc
      |
      |enum Option[T]
      |    Some(value: T)
      |    None
      |
      |    is_some(self) -> bool = match self
      |        Some(_) -> true
      |        None -> false
      |
      |    is_none(self) -> bool = !self.is_some()
      |
      |    unwrap_or(self, default: T) -> T = match self
      |        Some(v) -> v
      |        None -> default
      |
      |    unwrap(self) -> T = match self
      |        Some(v) -> v
      |        None ->
      |            print("panic: unwrap of a None value")
      |            exit(1)
      |
      |    expect(self, msg: string) -> T = match self
      |        Some(v) -> v
      |        None ->
      |            print("panic:", msg)
      |            exit(1)
      |end Option
      |
      |enum Result[T, E]
      |    Ok(value: T)
      |    Err(error: E)
      |
      |    is_ok(self) -> bool = match self
      |        Ok(_) -> true
      |        Err(_) -> false
      |
      |    is_err(self) -> bool = !self.is_ok()
      |
      |    unwrap_or(self, default: T) -> T = match self
      |        Ok(v) -> v
      |        Err(_) -> default
      |
      |    unwrap(self) -> T = match self
      |        Ok(v) -> v
      |        Err(_) ->
      |            print("panic: unwrap of an Err value")
      |            exit(1)
      |
      |    expect(self, msg: string) -> T = match self
      |        Ok(v) -> v
      |        Err(_) ->
      |            print("panic:", msg)
      |            exit(1)
      |end Result
      |""".stripMargin

  /** The source the prelude's own declarations point into, so a diagnostic against one quotes the
   * prelude rather than the user's file at some unrelated line — and so a declaration can be told
   * to have come from here, which is what makes an unused one droppable.
   */
  val origin: Source = Source("<prelude>", source)

  /** The parsed prelude declarations, parsed once. */
  lazy val decls: List[Stmt] =
    SyslParser.parse(origin) match
      case Right(p) => p.body
      case Left(e)  => sys.error(s"the prelude does not parse: $e")

  /** Whether a declaration came from here rather than from the program being compiled. */
  def declares(s: Positioned): Boolean = s.pos.exists(_.source eq origin)

  /** The enum `?` unwraps, paired with its success and failure variant names. */
  def tryVariants(base: String): Option[(String, String)] = base match
    case "Result" => Some(("Ok", "Err"))
    case "Option" => Some(("Some", "None"))
    case _        => None
}
