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
 * **The core trait catalog is here too** (`14 §2`): `Add`, `Ord`, `Eq` and the rest are ordinary
 * trait declarations a program can read and whose methods it can call by name. What the compiler
 * adds is identity — which operator token means which of them, and which built-in types are members
 * — and that part lives in `CoreTraits`, because the open `iN` / `uN` families have no finite list
 * of scalars an `impl` could be written for.
 *
 * **`Writer` and `Display`** (`14 §2`, `§6`) are the rendering half of that catalog. A `Display`
 * writes its value's text into a sink rather than returning a fresh `string`, so rendering costs no
 * allocation and a `no alloc` module can still log; the sink is a `*Writer`, which is the trait
 * object of `02`. `Writer` takes bytes rather than a `string` because that is the direction that is
 * free — a `string` *is* a validated `[]u8` — and it reports failure by latching rather than by
 * returning, so an implementation stays straight-line and `print(x)` stays a statement.
 *
 * `failed` carries a **default** of `false`, which is the prelude's own use of the mechanism `02`
 * calls for: most sinks cannot fail, and one that cannot should not have to write down that it
 * cannot. A sink that can — a bounded buffer, a device that goes away — overrides it, and nothing
 * about the latch changes.
 *
 * The `display_*` family is the sink counterpart of the `print*` family above: the same renderings,
 * into a `*Writer` instead of into standard output, and in the argument order `Display` declares.
 * They are the built-ins' `display` — what `x.display(out, fmt)` lowers to when `x` is a scalar
 * (`14 §5`) — so a `Display` written for a struct can render the struct's own fields without
 * leaving the allocation-free path. The two families are separate because the writer that stands
 * for standard output cannot be written here: it has no state to give a struct, which is also why
 * `print` keeps its direct path rather than routing through a sink it cannot name.
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
 * reaches it, and an `extern` is declared only if something calls it. `FormatSpec` is the one
 * exception, and not one of this file's making — a non-generic type is instantiated eagerly
 * wherever it is declared, so its layout is emitted whether or not anything renders. That is a
 * type declaration with no code behind it, which is why the rule is worth what it saves.
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
      |trait Add
      |    add(self, rhs: Self) -> Self
      |
      |trait Sub
      |    sub(self, rhs: Self) -> Self
      |
      |trait Mul
      |    mul(self, rhs: Self) -> Self
      |
      |trait Div
      |    div(self, rhs: Self) -> Self
      |
      |trait Rem
      |    rem(self, rhs: Self) -> Self
      |
      |trait BitAnd
      |    bitand(self, rhs: Self) -> Self
      |
      |trait BitOr
      |    bitor(self, rhs: Self) -> Self
      |
      |trait BitXor
      |    bitxor(self, rhs: Self) -> Self
      |
      |trait Shl
      |    shl(self, rhs: Self) -> Self
      |
      |trait Shr
      |    shr(self, rhs: Self) -> Self
      |
      |trait Neg
      |    neg(self) -> Self
      |
      |trait Not
      |    not(self) -> Self
      |
      |trait Eq
      |    eq(self, rhs: Self) -> bool
      |
      |trait Ord
      |    lt(self, rhs: Self) -> bool
      |
      |trait Writer
      |    write(*self, bytes: []u8)
      |    failed(*self) -> bool = false
      |
      |struct FormatSpec
      |    width: int
      |    prec: int
      |    left: bool
      |
      |trait Display
      |    display(self, out: *Writer, fmt: FormatSpec)
      |
      |display_str(s: string, out: *Writer, fmt: FormatSpec) = out.write(s.bytes)
      |
      |display_int(n: long, out: *Writer, fmt: FormatSpec)
      |    var buf: [24]u8
      |    var k = sysl_snprintf(&buf[0], 24usize, c"%lld", n)
      |    out.write(buf[0..<usize(k)])
      |end display_int
      |
      |display_uint(n: ulong, out: *Writer, fmt: FormatSpec)
      |    var buf: [24]u8
      |    var k = sysl_snprintf(&buf[0], 24usize, c"%llu", n)
      |    out.write(buf[0..<usize(k)])
      |end display_uint
      |
      |display_real(x: real, out: *Writer, fmt: FormatSpec)
      |    var buf: [32]u8
      |    var k = sysl_snprintf(&buf[0], 32usize, c"%g", x)
      |    out.write(buf[0..<usize(k)])
      |end display_real
      |
      |display_bool(b: bool, out: *Writer, fmt: FormatSpec) = display_str(if b then "true" else "false", out, fmt)
      |
      |display_char(ch: char, out: *Writer, fmt: FormatSpec)
      |    var buf: [4]u8
      |    var cp = uint(ch)
      |    if cp < 128u32 then
      |        buf[0] = u8(cp)
      |        out.write(buf[0..<1usize])
      |    elif cp < 2048u32 then
      |        buf[0] = u8(192u32 | (cp >> 6u32))
      |        buf[1] = u8(128u32 | (cp & 63u32))
      |        out.write(buf[0..<2usize])
      |    elif cp < 65536u32 then
      |        buf[0] = u8(224u32 | (cp >> 12u32))
      |        buf[1] = u8(128u32 | ((cp >> 6u32) & 63u32))
      |        buf[2] = u8(128u32 | (cp & 63u32))
      |        out.write(buf[0..<3usize])
      |    else
      |        buf[0] = u8(240u32 | (cp >> 18u32))
      |        buf[1] = u8(128u32 | ((cp >> 12u32) & 63u32))
      |        buf[2] = u8(128u32 | ((cp >> 6u32) & 63u32))
      |        buf[3] = u8(128u32 | (cp & 63u32))
      |        out.write(buf[0..<4usize])
      |end display_char
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
