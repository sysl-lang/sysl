package io.github.edadma.sysl

/** The two sinks a rendering ends in (`14 §6`): standard output, which `print` hands a `Display`,
 * and a growable buffer, whose bytes `str` and an `f"…"` hole turn into a fresh `string`.
 *
 * Both are `*Writer` trait objects, and both are the compiler's rather than the prelude's — not
 * because either does anything a sysl body could not, but because neither has a *type* sysl can
 * give it. A writer over standard output holds no state, and there is no struct with no fields; a
 * growable byte buffer is `07`'s *Not yet*. What a program writes for itself is an ordinary
 * `impl Writer for MyThing`, and that is the case the trait exists for.
 *
 * Each table is laid out by hand in the order `Writer` declares its methods, which the analyzer
 * checks before it emits a node that reaches one.
 */
trait WriterEmitter extends Emitter {

  /** The buffer's layout — the bytes, how many are used, and how many fit. It is written literally
   * rather than declared as a named type because nothing outside this file ever names it.
   */
  protected val bufferLayout = "{ ptr, i64, i64 }"

  /** The table for standard output, whose data word is null since there is nothing to point at. */
  protected def stdoutTable(): String = request("sysl.vt.out")(WriterEmitter.stdout)

  /** The table for a fresh buffer, whose data word is the caller's stack slot. */
  protected def bufferTable(): String = request("sysl.vt.buf")(WriterEmitter.buffer)
}

object WriterEmitter {

  /** Writing to standard output. It goes through the library's own `putbytes` rather than straight
   * to the byte loop, so the one function a freestanding target replaces is still that one — the
   * sink moved, not the seam.
   *
   * The symbol is the **key** `putbytes` is filed under and not the spelling, because a key is what
   * an emitted name is (`Modules`) — so this table names whichever module the library declares it in.
   */
  val stdout: String =
    s"""define private void @sysl.w.out.write(ptr %self, { ptr, ptr, i64 } %b) {
      |entry:
      |  call void @${Library.key("putbytes")}({ ptr, ptr, i64 } %b)
      |  ret void
      |}
      |
      |define private i1 @sysl.w.out.failed(ptr %self) {
      |entry:
      |  ret i1 false
      |}
      |
      |@sysl.vt.out = private constant [2 x ptr] [ptr @sysl.w.out.write, ptr @sysl.w.out.failed]
      |""".stripMargin

  /** Writing into a growable buffer, and finishing with one.
   *
   * Capacity doubles, with a floor, so rendering a value costs a bounded number of allocations
   * rather than one per chunk written. The buffer starts as a zeroed stack slot — no allocation at
   * all until the first byte arrives — and `finish` copies what landed there into an ordinary
   * string buffer and gives the working storage back, so the string that comes out owns its own
   * bytes and the slot is inert again.
   *
   * Neither of these can fail in a way a caller could act on: `malloc` returning null is the
   * program's end rather than this writer's business, so `failed` is the constant a latch with
   * nothing to latch reports.
   */
  val buffer: String =
    """define private void @sysl.w.buf.write(ptr %self, { ptr, ptr, i64 } %b) {
      |entry:
      |  %src = extractvalue { ptr, ptr, i64 } %b, 1
      |  %n = extractvalue { ptr, ptr, i64 } %b, 2
      |  %bufp = getelementptr { ptr, i64, i64 }, ptr %self, i32 0, i32 0
      |  %lenp = getelementptr { ptr, i64, i64 }, ptr %self, i32 0, i32 1
      |  %capp = getelementptr { ptr, i64, i64 }, ptr %self, i32 0, i32 2
      |  %len = load i64, ptr %lenp
      |  %cap = load i64, ptr %capp
      |  %need = add i64 %len, %n
      |  %fits = icmp ule i64 %need, %cap
      |  br i1 %fits, label %copy, label %grow
      |grow:
      |  %twice = mul i64 %cap, 2
      |  %bigger = icmp ugt i64 %need, %twice
      |  %want = select i1 %bigger, i64 %need, i64 %twice
      |  %tiny = icmp ult i64 %want, 32
      |  %newcap = select i1 %tiny, i64 32, i64 %want
      |  %new = call ptr @malloc(i64 %newcap)
      |  %old = load ptr, ptr %bufp
      |  br label %move
      |move:
      |  %i = phi i64 [ 0, %grow ], [ %inext, %step ]
      |  %more = icmp ult i64 %i, %len
      |  br i1 %more, label %step, label %swap
      |step:
      |  %sp = getelementptr i8, ptr %old, i64 %i
      |  %sb = load i8, ptr %sp
      |  %dp = getelementptr i8, ptr %new, i64 %i
      |  store i8 %sb, ptr %dp
      |  %inext = add i64 %i, 1
      |  br label %move
      |swap:
      |  %had = icmp ne ptr %old, null
      |  br i1 %had, label %drop, label %install
      |drop:
      |  call void @free(ptr %old)
      |  br label %install
      |install:
      |  store ptr %new, ptr %bufp
      |  store i64 %newcap, ptr %capp
      |  br label %copy
      |copy:
      |  %buf = load ptr, ptr %bufp
      |  br label %append
      |append:
      |  %j = phi i64 [ 0, %copy ], [ %jnext, %put ]
      |  %left = icmp ult i64 %j, %n
      |  br i1 %left, label %put, label %done
      |put:
      |  %ap = getelementptr i8, ptr %src, i64 %j
      |  %ab = load i8, ptr %ap
      |  %at = add i64 %len, %j
      |  %tp = getelementptr i8, ptr %buf, i64 %at
      |  store i8 %ab, ptr %tp
      |  %jnext = add i64 %j, 1
      |  br label %append
      |done:
      |  store i64 %need, ptr %lenp
      |  ret void
      |}
      |
      |define private i1 @sysl.w.buf.failed(ptr %self) {
      |entry:
      |  ret i1 false
      |}
      |
      |define private { ptr, ptr, i64 } @sysl.w.buf.finish(ptr %self) {
      |entry:
      |  %bufp = getelementptr { ptr, i64, i64 }, ptr %self, i32 0, i32 0
      |  %lenp = getelementptr { ptr, i64, i64 }, ptr %self, i32 0, i32 1
      |  %buf = load ptr, ptr %bufp
      |  %len = load i64, ptr %lenp
      |  %s = call { ptr, ptr, i64 } @sysl.str.from_bytes(ptr %buf, i64 %len)
      |  %had = icmp ne ptr %buf, null
      |  br i1 %had, label %drop, label %done
      |drop:
      |  call void @free(ptr %buf)
      |  br label %done
      |done:
      |  ret { ptr, ptr, i64 } %s
      |}
      |
      |@sysl.vt.buf = private constant [2 x ptr] [ptr @sysl.w.buf.write, ptr @sysl.w.buf.failed]
      |""".stripMargin
}
