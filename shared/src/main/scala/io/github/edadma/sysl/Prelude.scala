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
 * **Each of them honours its `FormatSpec`**, and `display_pad` is where they all end up: it puts a
 * finished run of bytes in the field the specifier asked for, padding with spaces on whichever side
 * justification says. Doing it once is the point — six renderers each growing their own padding is
 * six chances for `%8s` to mean something slightly different.
 *
 * A specifier describes **the field the whole value occupies**, so an implementation rendering
 * *parts* hands each part the neutral specifier and applies `fmt` to the whole — which
 * `display_pad` is public for. Forwarding `fmt` straight down is right only when the part being
 * rendered *is* the whole rendering, as it is for a wrapper around one field.
 *
 * What a precision means is left to each renderer, because the conversion letter does not cross the
 * boundary — only a width, a precision, and a justification do. Text truncates to it, a number
 * fills out to it in digits, and a real reads it as significant digits, which is printf's meaning
 * under each of `%s`, `%d`, and `%g` respectively. Width and precision count **bytes**, as C's do,
 * so a `string` renders the same width whether it went through `snprintf` or through here — with
 * the one refinement that truncation backs off to a character boundary rather than handing a sink
 * half of a codepoint.
 *
 * The three `extern`s are the only things here that are not sysl. Two of them are plumbing rather
 * than surface, so they take a link name and leave `putchar` and `snprintf` free for a program to
 * declare itself. `exit` is deliberately not one of those: it is the prelude's offer of the hosted
 * exit, what `unwrap` and `expect` stop the program with — a diagnostic printed and a non-zero
 * status, which is what `11-error-handling.md` says a trap does under the `os` capability — and it
 * is the reason those two need no compiler support of their own.
 *
 * **`ByteSink` is the one writer supplied here**, and the reason it is supplied rather than left to
 * each program is `14 §2`'s rule that a specifier describes the field the *whole* value occupies: an
 * implementation rendering more than one part has to gather them before it can pad what they came
 * to, and gathering needs somewhere to put them. It is ordinary sysl over `Buf[u8]`, so it could not
 * be written at all until a `[]T` could be sized while running. The writer standing for standard
 * output stays the compiler's, because it holds no state and there is no struct with no fields.
 *
 * **`from_utf8` is the one direction a `string` could not otherwise be reached from**, and it is
 * here rather than in the compiler because only its last line needs to be: validation is ordinary
 * sysl over a `[]u8`, and the compiler supplies just `from_utf8_unchecked`, the primitive that says
 * "these bytes are a string now". The validator is Unicode's own well-formedness table rather than a
 * decode-and-range-check, which is what makes the four ways of being ill-formed — overlong, a
 * surrogate, a value past `10FFFF`, and a byte that can never appear — fall out of the lead byte's
 * row instead of each needing its own test. `Utf8Error` carries the offset `04` asks for plus the
 * one distinction a caller can act on: whether the input merely **ended** in the middle of a
 * sequence, which more bytes would fix, or holds one that no continuation could rescue.
 *
 * **The four operations that make new bytes** (`04`) are the rest of what a `string` can do, and
 * three of them are here rather than in the compiler for the reason `from_utf8` is: only the last
 * line of each needs to be underneath the language. `StrBuilder` gathers text and hands back one
 * string, over the same `Buf[u8]` `ByteSink` uses; `cstring` copies a string into the
 * NUL-terminated shape C reads, and `CString` is what owns that copy, since a language with no
 * manual free has to say who frees it. The fourth, `string(c)`, is a conversion the compiler
 * already had the encoder for. `s.copy()` needs no declaration at all — it is bytes copied into a
 * string that owns them, which is `from_utf8_unchecked` of a string's own bytes.
 *
 * **`args_of` is how a program's arguments become a `[]string`**, and it is here for the reason
 * `from_utf8` is: every line of it is ordinary sysl. What the platform hands the entry point is C's
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
 * **`char_from_u32` is the fallible half of `u32` → `char`** (`00 §1`), and it is a free function
 * for the reason `from_utf8` is one: a scalar has no member namespace to hang a `char.try` on. It
 * needs no unchecked primitive to sit on top of — the guard it writes and the check `char(u)`
 * already makes are the same two comparisons on the same value, so the cast on the far side of the
 * guard can never trip and the optimizer folds it. Trading a primitive for a redundant compare in
 * a cold branch is the better side of that bargain.
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
      |trait Add[Rhs = Self, Out = Self]
      |    add(self, rhs: Rhs) -> Out
      |
      |trait Sub[Rhs = Self, Out = Self]
      |    sub(self, rhs: Rhs) -> Out
      |
      |trait Mul[Rhs = Self, Out = Self]
      |    mul(self, rhs: Rhs) -> Out
      |
      |trait Div[Rhs = Self, Out = Self]
      |    div(self, rhs: Rhs) -> Out
      |
      |trait Rem[Rhs = Self, Out = Self]
      |    rem(self, rhs: Rhs) -> Out
      |
      |trait BitAnd[Rhs = Self, Out = Self]
      |    bitand(self, rhs: Rhs) -> Out
      |
      |trait BitOr[Rhs = Self, Out = Self]
      |    bitor(self, rhs: Rhs) -> Out
      |
      |trait BitXor[Rhs = Self, Out = Self]
      |    bitxor(self, rhs: Rhs) -> Out
      |
      |trait Shl[Rhs = Self, Out = Self]
      |    shl(self, rhs: Rhs) -> Out
      |
      |trait Shr[Rhs = Self, Out = Self]
      |    shr(self, rhs: Rhs) -> Out
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
      |trait Hash
      |    hash(self) -> u64
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
      |hash_u64(v: u64) -> u64
      |    var h = v + 0x9e3779b97f4a7c15u64
      |    h = (h ^ (h >> 30u64)) * 0xbf58476d1ce4e5b9u64
      |    h = (h ^ (h >> 27u64)) * 0x94d049bb133111ebu64
      |    h ^ (h >> 31u64)
      |
      |hash_u128(v: u128) -> u64 = hash_u64(u64(v)) * 0x100000001b3u64 ^ hash_u64(u64(v >> 64))
      |
      |hash_bool(b: bool) -> u64 = hash_u64(if b then 1u64 else 0u64)
      |
      |hash_str(s: string) -> u64
      |    var h = 0xcbf29ce484222325u64
      |    for b in s.bytes
      |        h = (h ^ u64(b)) * 0x100000001b3u64
      |    h
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
      |display_fill(out: *Writer, b: u8, n: int)
      |    var buf: [16]u8
      |    for i in 0..<16 do buf[i] = b
      |    var left = n
      |    while left > 0
      |        var k = if left < 16 then left else 16
      |        out.write(buf[0..<usize(k)])
      |        left -= k
      |end display_fill
      |
      |display_pad(text: []u8, out: *Writer, fmt: FormatSpec)
      |    var pad = fmt.width - int(text.len)
      |    if pad < 0 then pad = 0
      |    if !fmt.left then display_fill(out, 32u8, pad)
      |    out.write(text)
      |    if fmt.left then display_fill(out, 32u8, pad)
      |end display_pad
      |
      |display_digits(text: []u8, out: *Writer, fmt: FormatSpec)
      |    var sign = if text.len > 0usize && text[0] == 45u8 then 1usize else 0usize
      |    var zeros = 0
      |    if fmt.prec > int(text.len - sign) then zeros = fmt.prec - int(text.len - sign)
      |    var pad = fmt.width - int(text.len) - zeros
      |    if pad < 0 then pad = 0
      |    if !fmt.left then display_fill(out, 32u8, pad)
      |    if sign > 0usize then out.write(text[0..<1usize])
      |    display_fill(out, 48u8, zeros)
      |    out.write(text[sign..<text.len])
      |    if fmt.left then display_fill(out, 32u8, pad)
      |end display_digits
      |
      |display_str(s: string, out: *Writer, fmt: FormatSpec)
      |    var b = s.bytes
      |    var n = b.len
      |    if fmt.prec >= 0 && usize(fmt.prec) < n then
      |        n = usize(fmt.prec)
      |        while n > 0usize && (b[n] & 192u8) == 128u8
      |            n -= 1usize
      |    display_pad(b[0..<n], out, fmt)
      |end display_str
      |
      |display_int(n: long, out: *Writer, fmt: FormatSpec)
      |    var buf: [24]u8
      |    var k = sysl_snprintf(&buf[0], 24usize, c"%lld", n)
      |    display_digits(buf[0..<usize(k)], out, fmt)
      |end display_int
      |
      |display_uint(n: ulong, out: *Writer, fmt: FormatSpec)
      |    var buf: [24]u8
      |    var k = sysl_snprintf(&buf[0], 24usize, c"%llu", n)
      |    display_digits(buf[0..<usize(k)], out, fmt)
      |end display_uint
      |
      |display_wide(s: string, out: *Writer, fmt: FormatSpec) = display_digits(s.bytes, out, fmt)
      |
      |display_real(x: real, out: *Writer, fmt: FormatSpec)
      |    var buf: [64]u8
      |    var p = fmt.prec
      |    if p < 0 then p = 6
      |    if p > 40 then p = 40
      |    var k = sysl_snprintf(&buf[0], 64usize, c"%.*g", p, x)
      |    display_pad(buf[0..<usize(k)], out, fmt)
      |end display_real
      |
      |display_bool(b: bool, out: *Writer, fmt: FormatSpec) = display_str(if b then "true" else "false", out, fmt)
      |
      |display_char(ch: char, out: *Writer, fmt: FormatSpec)
      |    var buf: [4]u8
      |    var cp = uint(ch)
      |    var n = 0usize
      |    if cp < 128u32 then
      |        buf[0] = u8(cp)
      |        n = 1usize
      |    elif cp < 2048u32 then
      |        buf[0] = u8(192u32 | (cp >> 6u32))
      |        buf[1] = u8(128u32 | (cp & 63u32))
      |        n = 2usize
      |    elif cp < 65536u32 then
      |        buf[0] = u8(224u32 | (cp >> 12u32))
      |        buf[1] = u8(128u32 | ((cp >> 6u32) & 63u32))
      |        buf[2] = u8(128u32 | (cp & 63u32))
      |        n = 3usize
      |    else
      |        buf[0] = u8(240u32 | (cp >> 18u32))
      |        buf[1] = u8(128u32 | ((cp >> 12u32) & 63u32))
      |        buf[2] = u8(128u32 | ((cp >> 6u32) & 63u32))
      |        buf[3] = u8(128u32 | (cp & 63u32))
      |        n = 4usize
      |    display_pad(buf[0..<n], out, fmt)
      |end display_char
      |
      |impl[A: Eq, B: Eq] Eq for (A, B)
      |    eq(self, rhs: Self) -> bool = self.0 == rhs.0 && self.1 == rhs.1
      |
      |impl[A: Eq, B: Eq, C: Eq] Eq for (A, B, C)
      |    eq(self, rhs: Self) -> bool = self.0 == rhs.0 && self.1 == rhs.1 && self.2 == rhs.2
      |
      |impl[A: Ord, B: Ord] Ord for (A, B)
      |    lt(self, rhs: Self) -> bool
      |        if self.0 < rhs.0 then return true
      |        if rhs.0 < self.0 then return false
      |        self.1 < rhs.1
      |    end lt
      |
      |impl[A: Ord, B: Ord, C: Ord] Ord for (A, B, C)
      |    lt(self, rhs: Self) -> bool
      |        if self.0 < rhs.0 then return true
      |        if rhs.0 < self.0 then return false
      |        if self.1 < rhs.1 then return true
      |        if rhs.1 < self.1 then return false
      |        self.2 < rhs.2
      |    end lt
      |
      |impl[A: Hash, B: Hash] Hash for (A, B)
      |    hash(self) -> u64 = hash_u64(self.0.hash() * 0x100000001b3u64 ^ self.1.hash())
      |
      |impl[A: Hash, B: Hash, C: Hash] Hash for (A, B, C)
      |    hash(self) -> u64 =
      |        hash_u64((self.0.hash() * 0x100000001b3u64 ^ self.1.hash()) * 0x100000001b3u64 ^ self.2.hash())
      |
      |impl[A: Display, B: Display] Display for (A, B)
      |    display(self, out: *Writer, fmt: FormatSpec) =
      |        display_pad(("(" + str(self.0) + ", " + str(self.1) + ")").bytes, out, fmt)
      |
      |impl[A: Display, B: Display, C: Display] Display for (A, B, C)
      |    display(self, out: *Writer, fmt: FormatSpec) =
      |        display_pad(("(" + str(self.0) + ", " + str(self.1) + ", " + str(self.2) + ")").bytes, out, fmt)
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
      |char_from_u32(u: u32) -> Option[char]
      |    if u > 0x10FFFFu32 || (u >= 0xD800u32 && u <= 0xDFFFu32) then
      |        None
      |    else
      |        Some(char(u))
      |end char_from_u32
      |
      |struct Utf8Error
      |    offset: usize
      |    truncated: bool
      |
      |from_utf8(b: []u8) -> Result[string, Utf8Error]
      |    var i = 0usize
      |    while i < b.len
      |        var c = b[i]
      |        var need = 0usize
      |        var lo = 128u8
      |        var hi = 191u8
      |
      |        if c < 128u8 then
      |            need = 1usize
      |        elif c < 194u8 then
      |            return Err(Utf8Error(i, false))
      |        elif c < 224u8 then
      |            need = 2usize
      |        elif c < 240u8 then
      |            need = 3usize
      |            if c == 224u8 then lo = 160u8
      |            if c == 237u8 then hi = 159u8
      |        elif c < 245u8 then
      |            need = 4usize
      |            if c == 240u8 then lo = 144u8
      |            if c == 244u8 then hi = 143u8
      |        else
      |            return Err(Utf8Error(i, false))
      |
      |        var have = b.len - i
      |        var k = 1usize
      |        while k < need && k < have
      |            var t = b[i + k]
      |            var min = if k == 1usize then lo else 128u8
      |            var max = if k == 1usize then hi else 191u8
      |            if t < min || t > max then return Err(Utf8Error(i, false))
      |            k += 1usize
      |
      |        if have < need then return Err(Utf8Error(i, true))
      |        i += need
      |
      |    Ok(from_utf8_unchecked(b))
      |end from_utf8
      |
      |struct Chars
      |    rest: []u8
      |    at: usize
      |end Chars
      |
      |chars_of(b: []u8) -> Chars = Chars(b, 0usize)
      |
      |impl Iterate[char] for Chars
      |    next(*self) -> Option[char]
      |        if self.at >= self.rest.len then return None
      |
      |        var lead = self.rest[self.at]
      |        var need = 1usize
      |        var v = u32(lead)
      |
      |        if lead >= 240u8
      |            need = 4usize
      |            v = u32(lead & 7u8)
      |        elif lead >= 224u8
      |            need = 3usize
      |            v = u32(lead & 15u8)
      |        elif lead >= 192u8
      |            need = 2usize
      |            v = u32(lead & 31u8)
      |
      |        for k in 1usize..<need
      |            v = (v << 6u32) | u32(self.rest[self.at + k] & 63u8)
      |
      |        self.at += need
      |        Some(char(v))
      |    end next
      |
      |struct Buf[T]
      |    elems: []T
      |    count: usize
      |
      |    len(self) -> usize = self.count
      |
      |    cap(self) -> usize = self.elems.len
      |
      |    is_empty(self) -> bool = self.count == 0usize
      |
      |    at(self, i: usize) -> T
      |        if i >= self.count
      |            print("panic: index", i, "past the", self.count, "elements of a Buf")
      |            exit(1)
      |        self.elems[i]
      |    end at
      |
      |    set(*self, i: usize, v: T)
      |        if i >= self.count
      |            print("panic: index", i, "past the", self.count, "elements of a Buf")
      |            exit(1)
      |        self.elems[i] = v
      |    end set
      |
      |    push(*self, v: T)
      |        if self.count == self.elems.len
      |            var bigger: []T = [v; if self.elems.len == 0usize then 8usize else self.elems.len * 2]
      |
      |            for i in 0..<self.count do bigger[i] = self.elems[i]
      |
      |            self.elems = bigger
      |
      |        self.elems[self.count] = v
      |        self.count += 1usize
      |    end push
      |
      |    pop(*self) -> Option[T]
      |        if self.count == 0usize then return None
      |
      |        self.count -= 1usize
      |        Some(self.elems[self.count])
      |    end pop
      |
      |    truncate(*self, n: usize)
      |        if n < self.count then self.count = n
      |
      |    clear(*self)
      |        self.truncate(0usize)
      |
      |    remove(*self, i: usize) -> T
      |        if i >= self.count
      |            print("panic: index", i, "past the", self.count, "elements of a Buf")
      |            exit(1)
      |
      |        var gone = self.elems[i]
      |
      |        for j in i..<self.count - 1usize
      |            self.elems[j] = self.elems[j + 1usize]
      |
      |        self.truncate(self.count - 1usize)
      |        gone
      |    end remove
      |
      |    view(self) -> []T = self.elems[..<self.count]
      |end Buf
      |
      |impl[T] Index[usize, T] for Buf[T]
      |    index(self, i: usize) -> T = self.at(i)
      |
      |impl[T] IndexSet[usize, T] for Buf[T]
      |    index_set(*self, i: usize, v: T) = self.set(i, v)
      |
      |buf[T]() -> Buf[T]
      |    var b: Buf[T] = Buf([], 0usize)
      |
      |    b
      |
      |struct ByteSink
      |    bytes: &Buf[u8]
      |
      |    text(self) -> []u8 = self.bytes.view()
      |end ByteSink
      |
      |byte_sink() -> ByteSink = ByteSink(buf())
      |
      |impl Writer for ByteSink
      |    write(*self, bytes: []u8)
      |        for b in bytes do self.bytes.push(b)
      |
      |struct StrBuilder
      |    bytes: &Buf[u8]
      |
      |    len -> usize = self.bytes.len()
      |
      |    is_empty -> bool = self.bytes.is_empty()
      |
      |    push(*self, s: string)
      |        for b in s.bytes do self.bytes.push(b)
      |    end push
      |
      |    push_char(*self, c: char)
      |        var cp = u32(c)
      |
      |        if cp < 128
      |            self.bytes.push(u8(cp))
      |        elif cp < 2048
      |            self.bytes.push(u8(192 | (cp >> 6)))
      |            self.bytes.push(u8(128 | (cp & 63)))
      |        elif cp < 65536
      |            self.bytes.push(u8(224 | (cp >> 12)))
      |            self.bytes.push(u8(128 | ((cp >> 6) & 63)))
      |            self.bytes.push(u8(128 | (cp & 63)))
      |        else
      |            self.bytes.push(u8(240 | (cp >> 18)))
      |            self.bytes.push(u8(128 | ((cp >> 12) & 63)))
      |            self.bytes.push(u8(128 | ((cp >> 6) & 63)))
      |            self.bytes.push(u8(128 | (cp & 63)))
      |    end push_char
      |
      |    clear(*self)
      |        self.bytes.clear()
      |
      |    finish(self) -> string = from_utf8_unchecked(self.bytes.view())
      |end StrBuilder
      |
      |str_builder() -> StrBuilder = StrBuilder(buf())
      |
      |struct CString
      |    bytes: []u8
      |
      |    ptr -> *u8 = &self.bytes[0]
      |
      |    len -> usize = self.bytes.len - 1
      |end CString
      |
      |cstring(s: string) -> CString
      |    var b: []u8 = [0u8; s.len + 1]   // one byte longer than the text, and the fill is the terminator
      |
      |    for i in 0..<s.len do b[i] = s.bytes[i]
      |
      |    CString(b)
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
