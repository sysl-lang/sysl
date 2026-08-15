package sh.sysl

import ir.{Arg, Inst, LType, Val}

/** What a `string` means once it is bytes: how a literal becomes a value, how one is written
 * out, how two are compared, and how an offset is checked to fall between characters.
 *
 * Everything else about a string is already somewhere else, because a `string` *is* an
 * immutable validated `[]u8` — its ownership is the view's (`ArcEmitter`) and reaching an
 * element or taking a substring is the view's too (`Codegen`). What is left here is the part
 * where the bytes have a meaning rather than a length.
 *
 * **Every helper below is a function of the machine's word**, because a string's length is a
 * `usize` and these are the only places in the compiler where a length is written down as IR text
 * rather than emitted from a type. They read `word` from the `given Word` every `Emitter` carries,
 * so a call site needs no argument and a 64-bit target gets exactly the text it always had.
 */
trait StringEmitter extends Emitter {

  /** A literal as a value: three words naming bytes that are never freed, so the owner is null
   * and counting it costs nothing at run time.
   *
   * The length leaves out the NUL the interned constant carries. That byte is there so a
   * literal can be handed to C without a copy — it is not where the string ends, which is why a
   * NUL can also appear *inside* one as an ordinary byte.
   */
  protected def stringValue(s: String): Val = stringConst(s)

  /** The same, as a value rather than as its text. */
  protected def stringConst(s: String): Val.Agg =
    Val.Agg(List(Arg(LType.Ptr, Val.Null),
                 Arg(LType.Ptr, stringGlobal(s)),
                 Arg(wordLty, Val.Int(s.getBytes("UTF-8").length))))

  /** The bytes of a string and how many there are — what every operation on its content needs. */
  protected def strBytes(v: Val): (Val, Val) = {
    val p = freshReg(); emit(Inst.Extract(p, Type.Str.lty, v, List(1)))
    val n = freshReg(); emit(Inst.Extract(n, Type.Str.lty, v, List(2)))
    (p, n)
  }

  /** Writes a string to stdout. `printf`'s `%s` stops at a NUL, and so does `%.*s` — a precision
   * is a maximum, not a count — so a string that carries its own length is written a byte at a
   * time instead. It goes through `putchar` rather than `write`, so it shares stdio's buffer
   * with the `printf` that carries the rest of a `print` and the two cannot come out of order.
   */
  protected def printStr(v: Val): Unit = {
    val (p, n) = strBytes(v)

    emit(Inst.Call(None, LType.Void, Val.Global(requestText("sysl.str.write")(StringEmitter.write)),
                   List(Arg(LType.Ptr, p), Arg(wordLty, n))))
  }

  /** Joins two strings into a fresh one. The result owns a new `StrBuf` — an ordinary ARC box
   * (`03`) whose payload is the two halves' bytes laid end to end — so it carries a count of its
   * own and returns its storage through `@arc.release` like every other heap object. UTF-8 is closed under
   * concatenation, so the validity invariant needs no re-checking. Both operands are copied out,
   * so neither is retained; the caller records the result as an owned temporary.
   */
  protected def strConcat(av: Val, bv: Val): Val = {
    heap = true
    val (ap, an) = strBytes(av)
    val (bp, bn) = strBytes(bv)
    val fn       = requestText("sysl.str.concat")(StringEmitter.concat)
    val r        = freshReg()

    emit(Inst.Call(Some(r), Type.Str.lty, Val.Global(fn), strArgs(ap, an) ::: strArgs(bp, bn)))
    r
  }

  /** Compares two strings by their bytes, yielding the usual -1 / 0 / 1 so that every comparison
   * operator is one `icmp` against the result. For well-formed UTF-8 the byte order is also
   * codepoint order, which is the ordering worth having.
   */
  protected def strCmp(av: Val, bv: Val): Val = {
    val (ap, an) = strBytes(av)
    val (bp, bn) = strBytes(bv)
    val fn       = requestText("sysl.str.cmp")(StringEmitter.cmp)
    val r        = freshReg()

    emit(Inst.Call(Some(r), i32, Val.Global(fn), strArgs(ap, an) ::: strArgs(bp, bn)))
    r
  }

  /** Whether a byte offset falls between characters rather than inside one. The caller has
   * already checked that the offset is within the string, so this only has to look at the byte
   * it names: anything that is not a continuation byte starts a character, and the offset one
   * past the last byte ends the string.
   */
  protected def strBoundary(p: Val, n: Val, i: Val): Val = {
    val fn = requestText("sysl.str.boundary")(StringEmitter.boundary)
    val r  = freshReg()

    emit(Inst.Call(Some(r), i1, Val.Global(fn), strArgs(p, n) :+ Arg(wordLty, i)))
    r
  }

  /** A string's bytes and their count, as a call's two arguments. Every helper in the runtime takes
   * them in that order and at those types, which is what makes this worth naming once.
   */
  private def strArgs(p: Val, n: Val): List[Arg] =
    List(Arg(LType.Ptr, p), Arg(wordLty, n))
}

object StringEmitter {

  /** How `snprintf`'s answer becomes a length.
   *
   * It reports in an `int` and a string's length is a `usize`, so on a 64-bit machine there is a
   * widening to write and on a 32-bit one the two are already the same integer — where
   * `sext i32 %n to i32` is not an instruction at all. So this answers with the line to emit,
   * which may be empty, and the name to read afterwards, which may be the original.
   */
  private def counted(using w: Word): (String, Val) =
    if w.bits > 32 then (s"  %n64 = sext i32 %n to ${w.llvm}", Val.Reg("n64")) else ("", Val.Reg("n"))

  /** The three header words every `StrBuf` starts with — a strong count of one, no deallocation
   * hook that gives the bytes back and does nothing else, and a share count of one. The header's own
   * layout is `ArcEmitter`'s `%arc.header`, and its size is taken from that type rather than written
   * down, so a target that lays it out differently needs nothing changed here.
   *
   * **The hook cannot be null, and that is a change.** It used to be, because raw bytes hold no
   * references and there was nothing for a destructor to walk — the storage came back from
   * `arc.unshare`, which freed every box itself. The free is the hook's now (`ArcEmitter.dropFn`),
   * so a null one here would mean a `StrBuf` whose bytes are never given back. `arc.drop.plain` is
   * exactly the hook for a payload that holds nothing, and `Codegen` emits it into any module whose
   * runtime helpers reach the allocator — which every helper embedding this one does, one line
   * above.
   */
  private def strBufHeader(using w: Word, a: Allocator): String = {
    val word = w.llvm

    s"""  %p = call ptr @${a.alloc}($word %size)
       |  store $word 1, ptr %p
       |  %hook = getelementptr %arc.header, ptr %p, i32 0, i32 1
       |  store ptr @arc.drop.plain, ptr %hook
       |  %share = getelementptr %arc.header, ptr %p, i32 0, i32 2
       |  store $word 1, ptr %share
       |  %bytes = getelementptr %arc.header, ptr %p, i32 1""".stripMargin
  }

  /** Writing bytes out. `putchar` is declared here rather than beside `printf` because a module
   * that never prints a string never needs it.
   */
  def write(using w: Word): String = {
    val word = w.llvm

    s"""declare i32 @putchar(i32)
       |
       |define private void @sysl.str.write(ptr %p, $word %n) {
       |entry:
       |  br label %cond
       |cond:
       |  %i = phi $word [ 0, %entry ], [ %next, %body ]
       |  %more = icmp ult $word %i, %n
       |  br i1 %more, label %body, label %done
       |body:
       |  %ep = getelementptr i8, ptr %p, $word %i
       |  %b = load i8, ptr %ep
       |  %c = zext i8 %b to i32
       |  %w = call i32 @putchar(i32 %c)
       |  %next = add $word %i, 1
       |  br label %cond
       |done:
       |  ret void
       |}
       |""".stripMargin
  }

  /** Byte comparison: the common prefix decides it, and if there is no difference there the
   * shorter string comes first.
   */
  def cmp(using w: Word): String = {
    val word = w.llvm

    s"""define private i32 @sysl.str.cmp(ptr %a, $word %an, ptr %b, $word %bn) {
       |entry:
       |  %shorter = icmp ult $word %an, %bn
       |  %n = select i1 %shorter, $word %an, $word %bn
       |  br label %cond
       |cond:
       |  %i = phi $word [ 0, %entry ], [ %next, %step ]
       |  %more = icmp ult $word %i, %n
       |  br i1 %more, label %body, label %tail
       |body:
       |  %pa = getelementptr i8, ptr %a, $word %i
       |  %pb = getelementptr i8, ptr %b, $word %i
       |  %ba = load i8, ptr %pa
       |  %bb = load i8, ptr %pb
       |  %same = icmp eq i8 %ba, %bb
       |  br i1 %same, label %step, label %diff
       |diff:
       |  %lt = icmp ult i8 %ba, %bb
       |  %d = select i1 %lt, i32 -1, i32 1
       |  ret i32 %d
       |step:
       |  %next = add $word %i, 1
       |  br label %cond
       |tail:
       |  %longer = icmp ugt $word %an, %bn
       |  %t0 = select i1 %longer, i32 1, i32 0
       |  %t1 = select i1 %shorter, i32 -1, i32 %t0
       |  ret i32 %t1
       |}
       |""".stripMargin
  }

  /** Concatenation. A `StrBuf` sized for both halves is allocated the way every ARC box is — a
   * refcount set to one, no deallocation hook (raw bytes hold no references),
   * then the payload — with the header size taken portably from `%arc.header` so a 32-bit target
   * lays it out the same way. The two halves are copied in a byte at a time, and the returned
   * view names the buffer as its owner, the first payload byte as its start, and the summed
   * length.
   */
  def concat(using w: Word, a: Allocator): String = {
    val word = w.llvm
    val str  = Type.Str.llvm

    s"""define private $str @sysl.str.concat(ptr %ap, $word %an, ptr %bp, $word %bn) {
       |entry:
       |  %total = add $word %an, %bn
       |  %hend = getelementptr %arc.header, ptr null, i32 1
       |  %hsize = ptrtoint ptr %hend to $word
       |  %size = add $word %hsize, %total
       |${strBufHeader}
       |  br label %acond
       |acond:
       |  %i = phi $word [ 0, %entry ], [ %inext, %abody ]
       |  %amore = icmp ult $word %i, %an
       |  br i1 %amore, label %abody, label %bcond
       |abody:
       |  %asrc = getelementptr i8, ptr %ap, $word %i
       |  %abyte = load i8, ptr %asrc
       |  %adst = getelementptr i8, ptr %bytes, $word %i
       |  store i8 %abyte, ptr %adst
       |  %inext = add $word %i, 1
       |  br label %acond
       |bcond:
       |  %j = phi $word [ 0, %acond ], [ %jnext, %bbody ]
       |  %bmore = icmp ult $word %j, %bn
       |  br i1 %bmore, label %bbody, label %done
       |bbody:
       |  %bsrc = getelementptr i8, ptr %bp, $word %j
       |  %bbyte = load i8, ptr %bsrc
       |  %off = add $word %an, %j
       |  %bdst = getelementptr i8, ptr %bytes, $word %off
       |  store i8 %bbyte, ptr %bdst
       |  %jnext = add $word %j, 1
       |  br label %bcond
       |done:
       |  %v0 = insertvalue $str undef, ptr %p, 0
       |  %v1 = insertvalue $str %v0, ptr %bytes, 1
       |  %v2 = insertvalue $str %v1, $word %total, 2
       |  ret $str %v2
       |}
       |""".stripMargin
  }

  /** Copies a run of bytes into a fresh owning string. A `StrBuf` sized for the bytes is
   * allocated the way every ARC box is — a refcount of one, no hook — raw bytes hold no references — then the
   * payload — with the header size taken portably from `%arc.header`. Every `str(x)` that renders
   * into a scratch buffer finishes through here, so the allocation and copy live in one place.
   */
  def fromBytes(using w: Word, a: Allocator): String = {
    val word = w.llvm
    val str  = Type.Str.llvm

    s"""define private $str @sysl.str.from_bytes(ptr %src, $word %n) {
       |entry:
       |  %hend = getelementptr %arc.header, ptr null, i32 1
       |  %hsize = ptrtoint ptr %hend to $word
       |  %size = add $word %hsize, %n
       |${strBufHeader}
       |  br label %cond
       |cond:
       |  %i = phi $word [ 0, %entry ], [ %next, %body ]
       |  %more = icmp ult $word %i, %n
       |  br i1 %more, label %body, label %done
       |body:
       |  %s = getelementptr i8, ptr %src, $word %i
       |  %b = load i8, ptr %s
       |  %d = getelementptr i8, ptr %bytes, $word %i
       |  store i8 %b, ptr %d
       |  %next = add $word %i, 1
       |  br label %cond
       |done:
       |  %v0 = insertvalue $str undef, ptr %p, 0
       |  %v1 = insertvalue $str %v0, ptr %bytes, 1
       |  %v2 = insertvalue $str %v1, $word %n, 2
       |  ret $str %v2
       |}
       |""".stripMargin
  }

  /** One `char` as a string: its UTF-8 encoding, whose byte length follows from the codepoint's
   * range. The bytes are laid down by the same encoder printing a `char` uses, into a scratch
   * buffer, then copied out — the length is computed from the range rather than read as a
   * NUL-terminated run, so `char(0)` becomes a one-byte string rather than an empty one.
   */
  def char(using w: Word): String = {
    val word = w.llvm
    val str  = Type.Str.llvm

    s"""define private $str @sysl.str.char(i32 %cp) {
       |entry:
       |  %buf = alloca [5 x i8]
       |  call ptr @sysl.utf8(i32 %cp, ptr %buf)
       |  %lt1 = icmp ult i32 %cp, 128
       |  %lt2 = icmp ult i32 %cp, 2048
       |  %lt3 = icmp ult i32 %cp, 65536
       |  %l34 = select i1 %lt3, $word 3, $word 4
       |  %l234 = select i1 %lt2, $word 2, $word %l34
       |  %len = select i1 %lt1, $word 1, $word %l234
       |  %r = call $str @sysl.str.from_bytes(ptr %buf, $word %len)
       |  ret $str %r
       |}
       |""".stripMargin
  }

  /** An integer as its decimal string, without libc. The digits are written from the end of a
   * scratch buffer wide enough for the largest magnitude of that width plus a sign, dividing by ten
   * each step; a negative signed value is turned into its magnitude first, which is correct even for
   * the type's minimum because the negation wraps to the right unsigned bit pattern and the division
   * that follows is unsigned. The `signed` flag distinguishes a two's-complement negative from a
   * large unsigned value that happens to have its top bit set.
   *
   * It is written once for every width because C's own formatting stops before the widest sysl
   * integer does: `printf` has no length modifier past `long long`, so a value wider than that has
   * to be rendered by the language rather than borrowed from libc.
   *
   * **The value's width and the machine's are different questions here, and both appear.** `$ty` is
   * the integer being rendered, which is the caller's; `$word` is the length of the string that
   * comes out, which is the machine's. A `u128` renders on a 32-bit machine, and neither width
   * follows from the other.
   *
   * **The wide division this loop runs is expanded by the back end, not called out to.** An earlier
   * note here said divide and remainder became compiler-rt calls; they do not — `udiv i256`,
   * `udiv i1024` and `mul i8192` all compile with no undefined symbol behind them.
   *
   * **The buffer is the width's real cost, and it is a stack `alloca`.** `digitCapacity` grows with
   * the number of decimal digits the width can produce — about `bits * 0.302` of them — so a
   * renderer near `Type.MaxIntegerBits` allocates megabytes of stack in one frame and will overflow
   * it. Nothing reaches that by accident, and a program printing an integer that wide has stranger
   * problems than this one, so the size is left as the honest consequence of the width rather than
   * being capped into a wrong answer.
   */
  def int(bits: Int)(using w: Word): String = {
    val ty   = s"i$bits"
    val buf  = digitCapacity(bits)
    val word = w.llvm
    val str  = Type.Str.llvm

    s"""define private $str @${intName(bits)}($ty %v, i1 %signed) {
       |entry:
       |  %isneg = icmp slt $ty %v, 0
       |  %neg = and i1 %isneg, %signed
       |  %negv = sub $ty 0, %v
       |  %mag = select i1 %neg, $ty %negv, $ty %v
       |  %buf = alloca [$buf x i8]
       |  %end = getelementptr i8, ptr %buf, $word $buf
       |  br label %loop
       |loop:
       |  %cur = phi $ty [ %mag, %entry ], [ %q, %loop ]
       |  %pos = phi ptr [ %end, %entry ], [ %pp, %loop ]
       |  %pp = getelementptr i8, ptr %pos, $word -1
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
       |  %sp = getelementptr i8, ptr %pp, $word -1
       |  store i8 45, ptr %sp
       |  br label %finish
       |finish:
       |  %start = phi ptr [ %pp, %sign ], [ %sp, %addsign ]
       |  %si = ptrtoint ptr %start to $word
       |  %ei = ptrtoint ptr %end to $word
       |  %len = sub $word %ei, %si
       |  %res = call $str @sysl.str.from_bytes(ptr %start, $word %len)
       |  ret $str %res
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
  def float(using w: Word, a: Allocator): String = {
    val word         = w.llvm
    val str          = Type.Str.llvm
    val (widen, len) = counted

    s"""@sysl.str.g = private constant [3 x i8] c"%g\\00"
       |
       |define private $str @sysl.str.float(double %v) {
       |entry:
       |  %n = call i32 (ptr, $word, ptr, ...) @snprintf(ptr null, $word 0, ptr @sysl.str.g, double %v)
       |$widen
       |  %cap = add $word $len, 1
       |  %hend = getelementptr %arc.header, ptr null, i32 1
       |  %hsize = ptrtoint ptr %hend to $word
       |  %size = add $word %hsize, %cap
       |${strBufHeader}
       |  %w = call i32 (ptr, $word, ptr, ...) @snprintf(ptr %bytes, $word %cap, ptr @sysl.str.g, double %v)
       |  %v0 = insertvalue $str undef, ptr %p, 0
       |  %v1 = insertvalue $str %v0, ptr %bytes, 1
       |  %v2 = insertvalue $str %v1, $word $len, 2
       |  ret $str %v2
       |}
       |""".stripMargin
  }

  /** An integer rendered through a printf specifier. The caller has widened the value to 64 bits
   * and the format carries the `ll` length modifier, so `snprintf` and the value agree; a sizing
   * call learns the length, and the digits are written into a fresh `StrBuf` a byte longer for the
   * terminator `snprintf` writes, uncounted like the NUL after a literal.
   *
   * **The value stays an `i64` on every machine**, which is the one place in this file where a
   * hardwired sixty-four is right: it is the width `%lld` names, not the width of an address.
   */
  def fmtInt(using w: Word, a: Allocator): String = {
    val word         = w.llvm
    val str          = Type.Str.llvm
    val (widen, len) = counted

    s"""define private $str @sysl.str.fmt_i(ptr %fmt, i64 %v) {
       |entry:
       |  %n = call i32 (ptr, $word, ptr, ...) @snprintf(ptr null, $word 0, ptr %fmt, i64 %v)
       |$widen
       |  %cap = add $word $len, 1
       |  %hend = getelementptr %arc.header, ptr null, i32 1
       |  %hsize = ptrtoint ptr %hend to $word
       |  %size = add $word %hsize, %cap
       |${strBufHeader}
       |  %w = call i32 (ptr, $word, ptr, ...) @snprintf(ptr %bytes, $word %cap, ptr %fmt, i64 %v)
       |  %v0 = insertvalue $str undef, ptr %p, 0
       |  %v1 = insertvalue $str %v0, ptr %bytes, 1
       |  %v2 = insertvalue $str %v1, $word $len, 2
       |  ret $str %v2
       |}
       |""".stripMargin
  }

  /** A float rendered through a printf specifier, the same shape as the integer case with a
   * `double` argument the specifier expects directly.
   */
  def fmtFloat(using w: Word, a: Allocator): String = {
    val word         = w.llvm
    val str          = Type.Str.llvm
    val (widen, len) = counted

    s"""define private $str @sysl.str.fmt_f(ptr %fmt, double %v) {
       |entry:
       |  %n = call i32 (ptr, $word, ptr, ...) @snprintf(ptr null, $word 0, ptr %fmt, double %v)
       |$widen
       |  %cap = add $word $len, 1
       |  %hend = getelementptr %arc.header, ptr null, i32 1
       |  %hsize = ptrtoint ptr %hend to $word
       |  %size = add $word %hsize, %cap
       |${strBufHeader}
       |  %w = call i32 (ptr, $word, ptr, ...) @snprintf(ptr %bytes, $word %cap, ptr %fmt, double %v)
       |  %v0 = insertvalue $str undef, ptr %p, 0
       |  %v1 = insertvalue $str %v0, ptr %bytes, 1
       |  %v2 = insertvalue $str %v1, $word $len, 2
       |  ret $str %v2
       |}
       |""".stripMargin
  }

  /** A string rendered through a printf `%s` specifier, so that width, precision, and
   * justification are the C library's to apply. A sysl string carries a length rather than a
   * terminator, so a NUL-terminated copy is made for `snprintf` and freed after; an interior NUL
   * therefore ends the field, which is exactly `%s`'s own rule.
   */
  def fmtStr(using w: Word, a: Allocator): String = {
    val word         = w.llvm
    val str          = Type.Str.llvm
    val (widen, len) = counted

    s"""define private $str @sysl.str.fmt_s(ptr %fmt, ptr %src, $word %len) {
       |entry:
       |  %ccap = add $word %len, 1
       |  %cstr = call ptr @${a.alloc}($word %ccap)
       |  br label %ccond
       |ccond:
       |  %i = phi $word [ 0, %entry ], [ %inext, %cbody ]
       |  %more = icmp ult $word %i, %len
       |  br i1 %more, label %cbody, label %cdone
       |cbody:
       |  %sp = getelementptr i8, ptr %src, $word %i
       |  %sb = load i8, ptr %sp
       |  %dp = getelementptr i8, ptr %cstr, $word %i
       |  store i8 %sb, ptr %dp
       |  %inext = add $word %i, 1
       |  br label %ccond
       |cdone:
       |  %nulp = getelementptr i8, ptr %cstr, $word %len
       |  store i8 0, ptr %nulp
       |  %n = call i32 (ptr, $word, ptr, ...) @snprintf(ptr null, $word 0, ptr %fmt, ptr %cstr)
       |$widen
       |  %cap = add $word $len, 1
       |  %hend = getelementptr %arc.header, ptr null, i32 1
       |  %hsize = ptrtoint ptr %hend to $word
       |  %size = add $word %hsize, %cap
       |${strBufHeader}
       |  %w = call i32 (ptr, $word, ptr, ...) @snprintf(ptr %bytes, $word %cap, ptr %fmt, ptr %cstr)
       |  call void @${a.free}(ptr %cstr)
       |  %v0 = insertvalue $str undef, ptr %p, 0
       |  %v1 = insertvalue $str %v0, ptr %bytes, 1
       |  %v2 = insertvalue $str %v1, $word $len, 2
       |  ret $str %v2
       |}
       |""".stripMargin
  }

  /** A continuation byte is `10xxxxxx`; every other byte starts a character. */
  def boundary(using w: Word): String = {
    val word = w.llvm

    s"""define private i1 @sysl.str.boundary(ptr %p, $word %n, $word %i) {
       |entry:
       |  %end = icmp eq $word %i, %n
       |  br i1 %end, label %yes, label %check
       |check:
       |  %ep = getelementptr i8, ptr %p, $word %i
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
}
