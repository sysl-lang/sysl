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
 * `%.*s` — stops at one. It is also one of the two functions a freestanding target has to replace:
 * swap its body for a `write` syscall, and `FdReader.read`'s for a `read` one, and the rest of the
 * surface above both is unchanged.
 *
 * The integer and float renderings lean on `snprintf`, which is formatting rather than I/O. Doing
 * them in sysl is a small job for the integers and a large one for the floats (correct shortest
 * round-trip), so they wait until there is a reason — a target without a C library.
 *
 * **The core trait catalog is in the standard module** (`14 §2`): `Add`, `Ord`, `Eq` and the rest,
 * beside the structural rows that make a tuple comparable when its parts are. What the compiler adds
 * is identity — which operator token means which trait, and which built-in types are members — and
 * that part lives in `CoreTraits`, which holds them as *spellings* and deliberately does not record
 * which half of the library a trait is in.
 *
 * What is left here is written *against* the moved half — the `print*` family above names the
 * standard module's declarations with no import, because a name written in either part of the
 * library is looked for among the library's own first.
 *
 * The `extern`s are the only things here that are not sysl. All but one are plumbing rather than
 * surface, so they take a link name and leave `putchar`, `snprintf`, `read` and `memchr` free for a
 * program to declare itself. `exit` is deliberately not one of those: it is the prelude's offer of the hosted
 * exit, what `unwrap` and `expect` stop the program with — a diagnostic printed and a non-zero
 * status, which is what `11-error-handling.md` says a trap does under the `os` capability — and it
 * is the reason those two need no compiler support of their own.
 *
 * **`args_of` is how a program's arguments become a `[]string`**, and it is here because every line
 * of it is ordinary sysl. What the platform hands the entry point is C's
 * `argc` and `argv` — a count and a vector of NUL-terminated byte runs — and what a sysl program
 * asks for is a slice of strings, so something has to walk the one and build the other. Doing it in
 * the prelude is what keeps the pair out of every sysl signature: a `main(args: []string)` is called
 * with the result of this, and the two foreign types are named in one place instead of in each
 * program that wants its arguments.
 *
 * Each run's length is found by looking for the terminator rather than by calling `strlen`, so the
 * conversion asks the platform for nothing beyond the two values it was handed. The bytes are then
 * **validated and copied**: a `string` owns what it holds, so an argument outlives the vector it
 * came from and nothing a program does to it reaches memory the platform still owns. An argument
 * that is not UTF-8 stops the program the way `unwrap` does, with the offset of the byte that made
 * it ill-formed — `04` puts that check at the boundary, and this is one.
 *
 * **Most of the library is now in the standard module** — the rendering, reading, text, buffer and
 * builder surfaces all live under `lib/sysl/`. What is left here is what they stand on and what has
 * not moved yet: the `print*` family and the `extern`s above, `args_of` below, and the `Option` and
 * `Result` every fallible answer arrives in.
 *
 * `args_of` is written *against* the moved half — it names `Buf`, `buf` and `from_utf8` with no
 * import, because a name written in either part of the library is looked for among the library's
 * own first. That direction is the whole reason the drain can proceed one surface at a time.
 *
 * None of this costs an unused program anything: the enums' members are generic, so one exists
 * only where a call asks for it, a top-level function is analyzed and emitted only if something
 * reaches it, a **member of a non-generic type declared here** is held back by that same
 * reachability, and an `extern` is declared only if something calls it. Layout is the one exception,
 * and not one of this file's making — a non-generic type is instantiated eagerly wherever it is
 * declared, so every type declared here has its LLVM type emitted whether or not anything reaches
 * it. Those lines name no storage and emit no instructions, which is why the rule is worth what it
 * saves.
 */
object Prelude {

  val source: String =
    """extern exit(code: int) -> never
      |extern "putchar" sysl_putchar(c: int) -> int
      |extern "snprintf" sysl_snprintf(buf: *u8, n: usize, fmt: *u8, ...) -> int
      |extern "read" sysl_read(fd: int, p: *u8, n: usize) -> isize
      |extern "memchr" sysl_memchr(p: *u8, c: int, n: usize) -> *u8
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
      |trait Index[I, E]
      |    index(self, i: I) -> E
      |
      |trait IndexSet[I, E]
      |    index_set(*self, i: I, v: E)
      |
      |trait Iterate[E]
      |    next(*self) -> Option[E]
      |
      |trait Fn0[R]
      |    call(*self) -> R
      |
      |trait Fn1[A, R]
      |    call(*self, a: A) -> R
      |
      |trait Fn2[A, B, R]
      |    call(*self, a: A, b: B) -> R
      |
      |trait Fn3[A, B, C, R]
      |    call(*self, a: A, b: B, c: C) -> R
      |
      |trait Fn4[A, B, C, D, R]
      |    call(*self, a: A, b: B, c: C, d: D) -> R
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
      |    is_some(self) -> bool = self match
      |        Some(_) -> true
      |        None -> false
      |
      |    is_none(self) -> bool = !self.is_some()
      |
      |    unwrap_or(self, default: T) -> T = self match
      |        Some(v) -> v
      |        None -> default
      |
      |    unwrap(self) -> T = self match
      |        Some(v) -> v
      |        None ->
      |            print("panic: unwrap of a None value")
      |            exit(1)
      |
      |    expect(self, msg: string) -> T = self match
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
      |    is_ok(self) -> bool = self match
      |        Ok(_) -> true
      |        Err(_) -> false
      |
      |    is_err(self) -> bool = !self.is_ok()
      |
      |    unwrap_or(self, default: T) -> T = self match
      |        Ok(v) -> v
      |        Err(_) -> default
      |
      |    unwrap(self) -> T = self match
      |        Ok(v) -> v
      |        Err(_) ->
      |            print("panic: unwrap of an Err value")
      |            exit(1)
      |
      |    expect(self, msg: string) -> T = self match
      |        Ok(v) -> v
      |        Err(_) ->
      |            print("panic:", msg)
      |            exit(1)
      |
      |    unwrap_err(self) -> E = self match
      |        Err(e) -> e
      |        Ok(_) ->
      |            print("panic: unwrap_err of an Ok value")
      |            exit(1)
      |
      |    expect_err(self, msg: string) -> E = self match
      |        Err(e) -> e
      |        Ok(_) ->
      |            print("panic:", msg)
      |            exit(1)
      |end Result
      |
      |args_of(argc: i32, argv: **u8) -> []string
      |    var out: Buf[string] = buf()
      |    var i = 0
      |
      |    while i < int(argc)
      |        var p = argv[i]
      |        var n = 0usize
      |
      |        while p[n] != 0u8
      |            n += 1usize
      |
      |        from_utf8(p[0..<n]) match
      |            Ok(s) -> out.push(s)
      |            Err(e) ->
      |                print("panic: command-line argument", i, "is not UTF-8 at byte", e.offset)
      |                exit(1)
      |
      |        i += 1
      |
      |    out.view()
      |
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

  /** Whether a declaration came from here rather than from the program being compiled.
   *
   * Asked through `Library.owns` rather than directly, so that what counts as the library's is one
   * question with one answer while declarations are moving out of here and into a module.
   */
  def declares(s: Positioned): Boolean = s.pos.exists(_.source eq origin)
}
