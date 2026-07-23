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
