package io.github.edadma.sysl

/** What a `string` means once it is bytes: how a literal becomes a value, how one is written
 * out, how two are compared, and how an offset is checked to fall between characters.
 *
 * Everything else about a string is already somewhere else, because a `string` *is* an
 * immutable validated `[]u8` — its ownership is the view's (`ArcEmitter`) and reaching an
 * element or taking a substring is the view's too (`Codegen`). What is left here is the part
 * where the bytes have a meaning rather than a length.
 */
trait StringEmitter extends Emitter {

  /** A literal as a value: three words naming bytes that are never freed, so the owner is null
   * and counting it costs nothing at run time.
   *
   * The length leaves out the NUL the interned constant carries. That byte is there so a
   * literal can be handed to C without a copy — it is not where the string ends, which is why a
   * NUL can also appear *inside* one as an ordinary byte.
   */
  protected def stringValue(s: String): String =
    s"{ ptr null, ptr ${stringGlobal(s)}, i64 ${s.getBytes("UTF-8").length} }"

  /** The bytes of a string and how many there are — what every operation on its content needs. */
  protected def strBytes(v: String): (String, String) = {
    val p = freshTemp(); emit(s"$p = extractvalue ${Type.Str.llvm} $v, 1")
    val n = freshTemp(); emit(s"$n = extractvalue ${Type.Str.llvm} $v, 2")
    (p, n)
  }

  /** Writes a string to stdout. `printf`'s `%s` stops at a NUL, and so does `%.*s` — a precision
   * is a maximum, not a count — so a string that carries its own length is written a byte at a
   * time instead. It goes through `putchar` rather than `write`, so it shares stdio's buffer
   * with the `printf` that carries the rest of a `print` and the two cannot come out of order.
   */
  protected def printStr(v: String): Unit = {
    val (p, n) = strBytes(v)

    emit(s"call void @${request("sysl.str.write")(StringEmitter.write)}(ptr $p, i64 $n)")
  }

  /** Joins two strings into a fresh one. The result owns a new `StrBuf` — an ordinary ARC box
   * (`03`) whose payload is the two halves' bytes laid end to end — so it carries a count of its
   * own and returns its storage through `@arc.release` like every other heap object. UTF-8 is closed under
   * concatenation, so the validity invariant needs no re-checking. Both operands are copied out,
   * so neither is retained; the caller records the result as an owned temporary.
   */
  protected def strConcat(av: String, bv: String): String = {
    heap = true
    val (ap, an) = strBytes(av)
    val (bp, bn) = strBytes(bv)
    val fn       = request("sysl.str.concat")(StringEmitter.concat)
    val r        = freshTemp()

    emit(s"$r = call ${Type.Str.llvm} @$fn(ptr $ap, i64 $an, ptr $bp, i64 $bn)")
    r
  }

  /** Compares two strings by their bytes, yielding the usual -1 / 0 / 1 so that every comparison
   * operator is one `icmp` against the result. For well-formed UTF-8 the byte order is also
   * codepoint order, which is the ordering worth having.
   */
  protected def strCmp(av: String, bv: String): String = {
    val (ap, an) = strBytes(av)
    val (bp, bn) = strBytes(bv)
    val fn       = request("sysl.str.cmp")(StringEmitter.cmp)
    val r        = freshTemp()

    emit(s"$r = call i32 @$fn(ptr $ap, i64 $an, ptr $bp, i64 $bn)")
    r
  }

  /** Whether a byte offset falls between characters rather than inside one. The caller has
   * already checked that the offset is within the string, so this only has to look at the byte
   * it names: anything that is not a continuation byte starts a character, and the offset one
   * past the last byte ends the string.
   */
  protected def strBoundary(p: String, n: String, i: String): String = {
    val fn = request("sysl.str.boundary")(StringEmitter.boundary)
    val r  = freshTemp()

    emit(s"$r = call i1 @$fn(ptr $p, i64 $n, i64 $i)")
    r
  }
}

object StringEmitter {

  /** Writing bytes out. `putchar` is declared here rather than beside `printf` because a module
   * that never prints a string never needs it.
   */
  val write: String =
    """declare i32 @putchar(i32)
      |
      |define private void @sysl.str.write(ptr %p, i64 %n) {
      |entry:
      |  br label %cond
      |cond:
      |  %i = phi i64 [ 0, %entry ], [ %next, %body ]
      |  %more = icmp ult i64 %i, %n
      |  br i1 %more, label %body, label %done
      |body:
      |  %ep = getelementptr i8, ptr %p, i64 %i
      |  %b = load i8, ptr %ep
      |  %c = zext i8 %b to i32
      |  %w = call i32 @putchar(i32 %c)
      |  %next = add i64 %i, 1
      |  br label %cond
      |done:
      |  ret void
      |}
      |""".stripMargin

  /** Byte comparison: the common prefix decides it, and if there is no difference there the
   * shorter string comes first.
   */
  val cmp: String =
    """define private i32 @sysl.str.cmp(ptr %a, i64 %an, ptr %b, i64 %bn) {
      |entry:
      |  %shorter = icmp ult i64 %an, %bn
      |  %n = select i1 %shorter, i64 %an, i64 %bn
      |  br label %cond
      |cond:
      |  %i = phi i64 [ 0, %entry ], [ %next, %step ]
      |  %more = icmp ult i64 %i, %n
      |  br i1 %more, label %body, label %tail
      |body:
      |  %pa = getelementptr i8, ptr %a, i64 %i
      |  %pb = getelementptr i8, ptr %b, i64 %i
      |  %ba = load i8, ptr %pa
      |  %bb = load i8, ptr %pb
      |  %same = icmp eq i8 %ba, %bb
      |  br i1 %same, label %step, label %diff
      |diff:
      |  %lt = icmp ult i8 %ba, %bb
      |  %d = select i1 %lt, i32 -1, i32 1
      |  ret i32 %d
      |step:
      |  %next = add i64 %i, 1
      |  br label %cond
      |tail:
      |  %longer = icmp ugt i64 %an, %bn
      |  %t0 = select i1 %longer, i32 1, i32 0
      |  %t1 = select i1 %shorter, i32 -1, i32 %t0
      |  ret i32 %t1
      |}
      |""".stripMargin

  /** Concatenation. A `StrBuf` sized for both halves is allocated the way every ARC box is — a
   * refcount set to one, no deallocation hook (raw bytes hold no references),
   * then the payload — with the header size taken portably from `%arc.header` so a 32-bit target
   * lays it out the same way. The two halves are copied in a byte at a time, and the returned
   * view names the buffer as its owner, the first payload byte as its start, and the summed
   * length.
   */
  val concat: String =
    """define private { ptr, ptr, i64 } @sysl.str.concat(ptr %ap, i64 %an, ptr %bp, i64 %bn) {
      |entry:
      |  %total = add i64 %an, %bn
      |  %hend = getelementptr %arc.header, ptr null, i32 1
      |  %hsize = ptrtoint ptr %hend to i64
      |  %size = add i64 %hsize, %total
      |  %p = call ptr @malloc(i64 %size)
      |  store i64 1, ptr %p
      |  %hook = getelementptr %arc.header, ptr %p, i32 0, i32 1
      |  store ptr null, ptr %hook
      |  %share = getelementptr %arc.header, ptr %p, i32 0, i32 2
      |  store i64 1, ptr %share
      |  %bytes = getelementptr %arc.header, ptr %p, i32 1
      |  br label %acond
      |acond:
      |  %i = phi i64 [ 0, %entry ], [ %inext, %abody ]
      |  %amore = icmp ult i64 %i, %an
      |  br i1 %amore, label %abody, label %bcond
      |abody:
      |  %asrc = getelementptr i8, ptr %ap, i64 %i
      |  %abyte = load i8, ptr %asrc
      |  %adst = getelementptr i8, ptr %bytes, i64 %i
      |  store i8 %abyte, ptr %adst
      |  %inext = add i64 %i, 1
      |  br label %acond
      |bcond:
      |  %j = phi i64 [ 0, %acond ], [ %jnext, %bbody ]
      |  %bmore = icmp ult i64 %j, %bn
      |  br i1 %bmore, label %bbody, label %done
      |bbody:
      |  %bsrc = getelementptr i8, ptr %bp, i64 %j
      |  %bbyte = load i8, ptr %bsrc
      |  %off = add i64 %an, %j
      |  %bdst = getelementptr i8, ptr %bytes, i64 %off
      |  store i8 %bbyte, ptr %bdst
      |  %jnext = add i64 %j, 1
      |  br label %bcond
      |done:
      |  %v0 = insertvalue { ptr, ptr, i64 } undef, ptr %p, 0
      |  %v1 = insertvalue { ptr, ptr, i64 } %v0, ptr %bytes, 1
      |  %v2 = insertvalue { ptr, ptr, i64 } %v1, i64 %total, 2
      |  ret { ptr, ptr, i64 } %v2
      |}
      |""".stripMargin

  /** Copies a run of bytes into a fresh owning string. A `StrBuf` sized for the bytes is
   * allocated the way every ARC box is — a refcount of one, no hook — raw bytes hold no references — then the
   * payload — with the header size taken portably from `%arc.header`. Every `str(x)` that renders
   * into a scratch buffer finishes through here, so the allocation and copy live in one place.
   */
  val fromBytes: String =
    """define private { ptr, ptr, i64 } @sysl.str.from_bytes(ptr %src, i64 %n) {
      |entry:
      |  %hend = getelementptr %arc.header, ptr null, i32 1
      |  %hsize = ptrtoint ptr %hend to i64
      |  %size = add i64 %hsize, %n
      |  %p = call ptr @malloc(i64 %size)
      |  store i64 1, ptr %p
      |  %hook = getelementptr %arc.header, ptr %p, i32 0, i32 1
      |  store ptr null, ptr %hook
      |  %share = getelementptr %arc.header, ptr %p, i32 0, i32 2
      |  store i64 1, ptr %share
      |  %bytes = getelementptr %arc.header, ptr %p, i32 1
      |  br label %cond
      |cond:
      |  %i = phi i64 [ 0, %entry ], [ %next, %body ]
      |  %more = icmp ult i64 %i, %n
      |  br i1 %more, label %body, label %done
      |body:
      |  %s = getelementptr i8, ptr %src, i64 %i
      |  %b = load i8, ptr %s
      |  %d = getelementptr i8, ptr %bytes, i64 %i
      |  store i8 %b, ptr %d
      |  %next = add i64 %i, 1
      |  br label %cond
      |done:
      |  %v0 = insertvalue { ptr, ptr, i64 } undef, ptr %p, 0
      |  %v1 = insertvalue { ptr, ptr, i64 } %v0, ptr %bytes, 1
      |  %v2 = insertvalue { ptr, ptr, i64 } %v1, i64 %n, 2
      |  ret { ptr, ptr, i64 } %v2
      |}
      |""".stripMargin

  /** One `char` as a string: its UTF-8 encoding, whose byte length follows from the codepoint's
   * range. The bytes are laid down by the same encoder printing a `char` uses, into a scratch
   * buffer, then copied out — the length is computed from the range rather than read as a
   * NUL-terminated run, so `char(0)` becomes a one-byte string rather than an empty one.
   */
  val char: String =
    """define private { ptr, ptr, i64 } @sysl.str.char(i32 %cp) {
      |entry:
      |  %buf = alloca [5 x i8]
      |  call ptr @sysl.utf8(i32 %cp, ptr %buf)
      |  %lt1 = icmp ult i32 %cp, 128
      |  %lt2 = icmp ult i32 %cp, 2048
      |  %lt3 = icmp ult i32 %cp, 65536
      |  %l34 = select i1 %lt3, i64 3, i64 4
      |  %l234 = select i1 %lt2, i64 2, i64 %l34
      |  %len = select i1 %lt1, i64 1, i64 %l234
      |  %r = call { ptr, ptr, i64 } @sysl.str.from_bytes(ptr %buf, i64 %len)
      |  ret { ptr, ptr, i64 } %r
      |}
      |""".stripMargin

  /** An integer as its decimal string, without libc. The digits are written from the end of a
   * scratch buffer wide enough for the largest magnitude of that width plus a sign, dividing by ten
   * each step; a negative signed value is turned into its magnitude first, which is correct even for
   * the type's minimum because the negation wraps to the right unsigned bit pattern and the division
   * that follows is unsigned. The `signed` flag distinguishes a two's-complement negative from a
   * large unsigned value that happens to have its top bit set.
   *
   * It is written once for every width because C's own formatting stops before the widest sysl
   * integer does: `printf` has no length modifier for a 128-bit argument, so a value that needs one
   * has to be rendered by the language rather than borrowed from libc. Divide and remainder at 128
   * bits become compiler-rt calls, which is the whole cost of the width and is paid by the
   * arithmetic in the program besides.
   */
  def int(bits: Int): String = {
    val ty  = s"i$bits"
    val buf = digitCapacity(bits)

    s"""define private { ptr, ptr, i64 } @${intName(bits)}($ty %v, i1 %signed) {
       |entry:
       |  %isneg = icmp slt $ty %v, 0
       |  %neg = and i1 %isneg, %signed
       |  %negv = sub $ty 0, %v
       |  %mag = select i1 %neg, $ty %negv, $ty %v
       |  %buf = alloca [$buf x i8]
       |  %end = getelementptr i8, ptr %buf, i64 $buf
       |  br label %loop
       |loop:
       |  %cur = phi $ty [ %mag, %entry ], [ %q, %loop ]
       |  %pos = phi ptr [ %end, %entry ], [ %pp, %loop ]
       |  %pp = getelementptr i8, ptr %pos, i64 -1
       |  %q = udiv $ty %cur, 10
       |  %r = urem $ty %cur, 10
       |  %rb = trunc $ty %r to i8
       |  %digit = add i8 %rb, 48
       |  store i8 %digit, ptr %pp
       |  %more = icmp ne $ty %q, 0
       |  br i1 %more, label %loop, label %sign
       |sign:
       |  br i1 %neg, label %addsign, label %finish
       |addsign:
       |  %sp = getelementptr i8, ptr %pp, i64 -1
       |  store i8 45, ptr %sp
       |  br label %finish
       |finish:
       |  %start = phi ptr [ %pp, %sign ], [ %sp, %addsign ]
       |  %si = ptrtoint ptr %start to i64
       |  %ei = ptrtoint ptr %end to i64
       |  %len = sub i64 %ei, %si
       |  %res = call { ptr, ptr, i64 } @sysl.str.from_bytes(ptr %start, i64 %len)
       |  ret { ptr, ptr, i64 } %res
       |}
       |""".stripMargin
  }

  /** The renderer's symbol at a given width. The 64-bit one keeps the unqualified name it has always
   * had, so the emitted text of every program that does not reach past 64 bits is unchanged.
   */
  def intName(bits: Int): String = if bits == 64 then "sysl.str.int" else s"sysl.str.int$bits"

  /** Digits enough for the widest magnitude of `bits`, plus one for a sign. */
  private def digitCapacity(bits: Int): Int = (BigInt(2).pow(bits) - 1).toString.length + 1

  /** A float as a string, rendered the way `print` renders one — `snprintf` with `%g`, so the two
   * agree to the byte. A sizing call learns the length, then the digits are written into a
   * `StrBuf` a byte longer to hold the terminator `snprintf` insists on writing, which rides along
   * uncounted exactly as the NUL after a literal does. This is the one case that needs libc, and
   * `print` already does, so it adds no dependency in practice.
   */
  val float: String =
    """@sysl.str.g = private constant [3 x i8] c"%g\00"
      |
      |define private { ptr, ptr, i64 } @sysl.str.float(double %v) {
      |entry:
      |  %n = call i32 (ptr, i64, ptr, ...) @snprintf(ptr null, i64 0, ptr @sysl.str.g, double %v)
      |  %n64 = sext i32 %n to i64
      |  %cap = add i64 %n64, 1
      |  %hend = getelementptr %arc.header, ptr null, i32 1
      |  %hsize = ptrtoint ptr %hend to i64
      |  %size = add i64 %hsize, %cap
      |  %p = call ptr @malloc(i64 %size)
      |  store i64 1, ptr %p
      |  %hook = getelementptr %arc.header, ptr %p, i32 0, i32 1
      |  store ptr null, ptr %hook
      |  %share = getelementptr %arc.header, ptr %p, i32 0, i32 2
      |  store i64 1, ptr %share
      |  %bytes = getelementptr %arc.header, ptr %p, i32 1
      |  %w = call i32 (ptr, i64, ptr, ...) @snprintf(ptr %bytes, i64 %cap, ptr @sysl.str.g, double %v)
      |  %v0 = insertvalue { ptr, ptr, i64 } undef, ptr %p, 0
      |  %v1 = insertvalue { ptr, ptr, i64 } %v0, ptr %bytes, 1
      |  %v2 = insertvalue { ptr, ptr, i64 } %v1, i64 %n64, 2
      |  ret { ptr, ptr, i64 } %v2
      |}
      |""".stripMargin

  /** An integer rendered through a printf specifier. The caller has widened the value to 64 bits
   * and the format carries the `ll` length modifier, so `snprintf` and the value agree; a sizing
   * call learns the length, and the digits are written into a fresh `StrBuf` a byte longer for the
   * terminator `snprintf` writes, uncounted like the NUL after a literal.
   */
  val fmtInt: String =
    """define private { ptr, ptr, i64 } @sysl.str.fmt_i(ptr %fmt, i64 %v) {
      |entry:
      |  %n = call i32 (ptr, i64, ptr, ...) @snprintf(ptr null, i64 0, ptr %fmt, i64 %v)
      |  %n64 = sext i32 %n to i64
      |  %cap = add i64 %n64, 1
      |  %hend = getelementptr %arc.header, ptr null, i32 1
      |  %hsize = ptrtoint ptr %hend to i64
      |  %size = add i64 %hsize, %cap
      |  %p = call ptr @malloc(i64 %size)
      |  store i64 1, ptr %p
      |  %hook = getelementptr %arc.header, ptr %p, i32 0, i32 1
      |  store ptr null, ptr %hook
      |  %share = getelementptr %arc.header, ptr %p, i32 0, i32 2
      |  store i64 1, ptr %share
      |  %bytes = getelementptr %arc.header, ptr %p, i32 1
      |  %w = call i32 (ptr, i64, ptr, ...) @snprintf(ptr %bytes, i64 %cap, ptr %fmt, i64 %v)
      |  %v0 = insertvalue { ptr, ptr, i64 } undef, ptr %p, 0
      |  %v1 = insertvalue { ptr, ptr, i64 } %v0, ptr %bytes, 1
      |  %v2 = insertvalue { ptr, ptr, i64 } %v1, i64 %n64, 2
      |  ret { ptr, ptr, i64 } %v2
      |}
      |""".stripMargin

  /** A float rendered through a printf specifier, the same shape as the integer case with a
   * `double` argument the specifier expects directly.
   */
  val fmtFloat: String =
    """define private { ptr, ptr, i64 } @sysl.str.fmt_f(ptr %fmt, double %v) {
      |entry:
      |  %n = call i32 (ptr, i64, ptr, ...) @snprintf(ptr null, i64 0, ptr %fmt, double %v)
      |  %n64 = sext i32 %n to i64
      |  %cap = add i64 %n64, 1
      |  %hend = getelementptr %arc.header, ptr null, i32 1
      |  %hsize = ptrtoint ptr %hend to i64
      |  %size = add i64 %hsize, %cap
      |  %p = call ptr @malloc(i64 %size)
      |  store i64 1, ptr %p
      |  %hook = getelementptr %arc.header, ptr %p, i32 0, i32 1
      |  store ptr null, ptr %hook
      |  %share = getelementptr %arc.header, ptr %p, i32 0, i32 2
      |  store i64 1, ptr %share
      |  %bytes = getelementptr %arc.header, ptr %p, i32 1
      |  %w = call i32 (ptr, i64, ptr, ...) @snprintf(ptr %bytes, i64 %cap, ptr %fmt, double %v)
      |  %v0 = insertvalue { ptr, ptr, i64 } undef, ptr %p, 0
      |  %v1 = insertvalue { ptr, ptr, i64 } %v0, ptr %bytes, 1
      |  %v2 = insertvalue { ptr, ptr, i64 } %v1, i64 %n64, 2
      |  ret { ptr, ptr, i64 } %v2
      |}
      |""".stripMargin

  /** A string rendered through a printf `%s` specifier, so that width, precision, and
   * justification are the C library's to apply. A sysl string carries a length rather than a
   * terminator, so a NUL-terminated copy is made for `snprintf` and freed after; an interior NUL
   * therefore ends the field, which is exactly `%s`'s own rule.
   */
  val fmtStr: String =
    """define private { ptr, ptr, i64 } @sysl.str.fmt_s(ptr %fmt, ptr %src, i64 %len) {
      |entry:
      |  %ccap = add i64 %len, 1
      |  %cstr = call ptr @malloc(i64 %ccap)
      |  br label %ccond
      |ccond:
      |  %i = phi i64 [ 0, %entry ], [ %inext, %cbody ]
      |  %more = icmp ult i64 %i, %len
      |  br i1 %more, label %cbody, label %cdone
      |cbody:
      |  %sp = getelementptr i8, ptr %src, i64 %i
      |  %sb = load i8, ptr %sp
      |  %dp = getelementptr i8, ptr %cstr, i64 %i
      |  store i8 %sb, ptr %dp
      |  %inext = add i64 %i, 1
      |  br label %ccond
      |cdone:
      |  %nulp = getelementptr i8, ptr %cstr, i64 %len
      |  store i8 0, ptr %nulp
      |  %n = call i32 (ptr, i64, ptr, ...) @snprintf(ptr null, i64 0, ptr %fmt, ptr %cstr)
      |  %n64 = sext i32 %n to i64
      |  %cap = add i64 %n64, 1
      |  %hend = getelementptr %arc.header, ptr null, i32 1
      |  %hsize = ptrtoint ptr %hend to i64
      |  %size = add i64 %hsize, %cap
      |  %p = call ptr @malloc(i64 %size)
      |  store i64 1, ptr %p
      |  %hook = getelementptr %arc.header, ptr %p, i32 0, i32 1
      |  store ptr null, ptr %hook
      |  %share = getelementptr %arc.header, ptr %p, i32 0, i32 2
      |  store i64 1, ptr %share
      |  %bytes = getelementptr %arc.header, ptr %p, i32 1
      |  %w = call i32 (ptr, i64, ptr, ...) @snprintf(ptr %bytes, i64 %cap, ptr %fmt, ptr %cstr)
      |  call void @free(ptr %cstr)
      |  %v0 = insertvalue { ptr, ptr, i64 } undef, ptr %p, 0
      |  %v1 = insertvalue { ptr, ptr, i64 } %v0, ptr %bytes, 1
      |  %v2 = insertvalue { ptr, ptr, i64 } %v1, i64 %n64, 2
      |  ret { ptr, ptr, i64 } %v2
      |}
      |""".stripMargin

  /** A continuation byte is `10xxxxxx`; every other byte starts a character. */
  val boundary: String =
    """define private i1 @sysl.str.boundary(ptr %p, i64 %n, i64 %i) {
      |entry:
      |  %end = icmp eq i64 %i, %n
      |  br i1 %end, label %yes, label %check
      |check:
      |  %ep = getelementptr i8, ptr %p, i64 %i
      |  %b = load i8, ptr %ep
      |  %top = and i8 %b, -64
      |  %cont = icmp eq i8 %top, -128
      |  %ok = xor i1 %cont, true
      |  ret i1 %ok
      |yes:
      |  ret i1 true
      |}
      |""".stripMargin
}
