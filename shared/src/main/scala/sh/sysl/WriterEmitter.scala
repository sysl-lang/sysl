package sh.sysl

/** The growable buffer a rendering can end in (`14 §6`): the sink whose bytes `str` and an `f"…"`
 * hole turn into a fresh `string`.
 *
 * It is a `*Writer` trait object, and it is the compiler's rather than the library's — not because
 * it does anything a sysl body could not, but because it has no *type* sysl can give it: a growable
 * byte buffer is `07`'s *Not yet*. The other sink used to be here for a reason of the same shape and
 * is not any more — a writer over standard output holds no state, and a struct may now have no
 * fields, so the library declares it as `Stdout` and this file has nothing to say about it. What a
 * program writes for itself is an ordinary `impl Writer for MyThing`, which is the case the trait
 * exists for and is now also the case the library's own sink is.
 *
 * The table is laid out by hand in the order `Writer` **offers** its methods — which is not the
 * order it declares them, because a trait offers what it requires first: `Writer: Fallible` puts
 * `failed` in slot 0 and `write` in slot 1. The analyzer checks that order against the flattened
 * list every call site indexes into, before it emits a node that reaches this.
 */
trait WriterEmitter extends Emitter {

  /** The buffer's layout — the bytes, how many are used, and how many fit. It is written literally
   * rather than declared as a named type because nothing outside this file ever names it.
   *
   * The two counts are the machine's word: a length that outgrew an address would name storage the
   * machine cannot address, and the string this buffer finishes into carries its length as a
   * `usize` anyway.
   */
  protected def bufferLayout: String = s"{ ptr, $word, $word }"

  /** The table for a fresh buffer, whose data word is the caller's stack slot. */
  protected def bufferTable(): String = request("sysl.vt.buf")(WriterEmitter.buffer)
}

object WriterEmitter {

  /** The order `Writer` offers its members in — which is **not** the order it declares them, since a
   * trait offers what it requires first and `Writer: Fallible`.
   *
   * It is written down once because three things depend on it and none of them can see the other
   * two: the table below is laid out by hand in this order, `Escape` reaches into a program's own
   * `Writer` tables by slot, and every call through a `*Writer` indexes by whatever the analyzer
   * computed. `SpecialForms.checkWriterShape` compares this list against the flattened member list
   * the analyzer builds, so a library edit that reorders them fails the build here rather than
   * calling the wrong function with the right arguments.
   */
  val members: List[String] = List("failed", "write")

  /** Which slot of a `Writer`'s table holds the writing. */
  val writeSlot: Int = members.indexOf("write")

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
  def buffer(using w: Word, a: Allocator): String = {
    val word = w.llvm
    val str  = Type.Str.llvm
    val slot = s"{ ptr, $word, $word }"

    s"""define private void @sysl.w.buf.write(ptr %self, $str %b) {
       |entry:
       |  %src = extractvalue $str %b, 1
       |  %n = extractvalue $str %b, 2
       |  %bufp = getelementptr $slot, ptr %self, i32 0, i32 0
       |  %lenp = getelementptr $slot, ptr %self, i32 0, i32 1
       |  %capp = getelementptr $slot, ptr %self, i32 0, i32 2
       |  %len = load $word, ptr %lenp
       |  %cap = load $word, ptr %capp
       |  %need = add $word %len, %n
       |  %fits = icmp ule $word %need, %cap
       |  br i1 %fits, label %copy, label %grow
       |grow:
       |  %twice = mul $word %cap, 2
       |  %bigger = icmp ugt $word %need, %twice
       |  %want = select i1 %bigger, $word %need, $word %twice
       |  %tiny = icmp ult $word %want, 32
       |  %newcap = select i1 %tiny, $word 32, $word %want
       |  %new = call ptr @${a.alloc}($word %newcap)
       |  %old = load ptr, ptr %bufp
       |  br label %move
       |move:
       |  %i = phi $word [ 0, %grow ], [ %inext, %step ]
       |  %more = icmp ult $word %i, %len
       |  br i1 %more, label %step, label %swap
       |step:
       |  %sp = getelementptr i8, ptr %old, $word %i
       |  %sb = load i8, ptr %sp
       |  %dp = getelementptr i8, ptr %new, $word %i
       |  store i8 %sb, ptr %dp
       |  %inext = add $word %i, 1
       |  br label %move
       |swap:
       |  %had = icmp ne ptr %old, null
       |  br i1 %had, label %drop, label %install
       |drop:
       |  call void @${a.free}(ptr %old)
       |  br label %install
       |install:
       |  store ptr %new, ptr %bufp
       |  store $word %newcap, ptr %capp
       |  br label %copy
       |copy:
       |  %buf = load ptr, ptr %bufp
       |  br label %append
       |append:
       |  %j = phi $word [ 0, %copy ], [ %jnext, %put ]
       |  %left = icmp ult $word %j, %n
       |  br i1 %left, label %put, label %done
       |put:
       |  %ap = getelementptr i8, ptr %src, $word %j
       |  %ab = load i8, ptr %ap
       |  %at = add $word %len, %j
       |  %tp = getelementptr i8, ptr %buf, $word %at
       |  store i8 %ab, ptr %tp
       |  %jnext = add $word %j, 1
       |  br label %append
       |done:
       |  store $word %need, ptr %lenp
       |  ret void
       |}
       |
       |define private i1 @sysl.w.buf.failed(ptr %self) {
       |entry:
       |  ret i1 false
       |}
       |
       |define private $str @sysl.w.buf.finish(ptr %self) {
       |entry:
       |  %bufp = getelementptr $slot, ptr %self, i32 0, i32 0
       |  %lenp = getelementptr $slot, ptr %self, i32 0, i32 1
       |  %buf = load ptr, ptr %bufp
       |  %len = load $word, ptr %lenp
       |  %s = call $str @sysl.str.from_bytes(ptr %buf, $word %len)
       |  %had = icmp ne ptr %buf, null
       |  br i1 %had, label %drop, label %done
       |drop:
       |  call void @${a.free}(ptr %buf)
       |  br label %done
       |done:
       |  ret $str %s
       |}
       |
       |@sysl.vt.buf = private constant [2 x ptr] [ptr @sysl.w.buf.failed, ptr @sysl.w.buf.write]
       |""".stripMargin
  }
}
