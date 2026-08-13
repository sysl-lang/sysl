package sh.sysl

import scala.collection.mutable

/** The substrate every part of codegen emits into: registers, basic blocks, the entry-block
 * prologue, module-level globals, and the queue of runtime helpers.
 *
 * This trait holds *how* IR is written down and none of what it says. The lowering rules live
 * in the traits and the class mixed on top of it — `ArcEmitter` for reference counting,
 * `ScalarEmitter` for arithmetic and printing, `Codegen` for the tree walk itself.
 */
trait Emitter {

  /** The machine this module is being emitted for (`targets.md`). Little consults it directly: what
   * does is the handful of places where the C ABI genuinely differs, each of which says so where it
   * reads this, plus the two derived values below that every emitter uses without naming a target
   * at all.
   */
  protected def target: Target

  /** The two C symbols this module's storage comes from and goes back to (`packages.md § 13`).
   *
   * A program has one pair, settled from the packages it is built from before any of this runs, so
   * nothing below decides it — every emitter that allocates or releases reads it here rather than
   * spelling `malloc`. Defaulted to libc's, which is what every program got before a package could say
   * otherwise and what a `Codegen` constructed without one still gets.
   */
  protected def allocator: Allocator = Allocator.c

  /** The allocating half, as it is written in IR — `@malloc`, or whatever the packages named. */
  protected def mallocSym: String = allocator.alloc

  /** The releasing half, the same way. */
  protected def freeSym: String = allocator.free

  /** Given for the same reason `Word` is: the runtime helpers are text templates on the companion
   * objects, so a helper that allocates needs the pair in scope without every caller threading it.
   */
  protected given Allocator = allocator

  /** How wide an address is, given to every `Type.llvm` in scope. A view's length is a `usize`, so
   * an emitter cannot write the LLVM form of a slice without it — and because there is no default
   * anywhere, an emitter that somehow had no target would not compile rather than quietly writing
   * a 64-bit type for a 32-bit machine.
   */
  protected given Word = target.word

  /** What this machine's types cost, which is the same question with the same one answer in it.
   * A `given` because `CAbi` asks for one, and an emitter has exactly one to give.
   */
  protected given layout: Layout = Layout(target)

  /** The LLVM integer exactly one address wide — `i64` or `i32` — which is what a **length, a size,
   * an index, or anything else that is a `usize`** must be spelled as.
   *
   * Reach for this rather than writing `i64`, and the test that says whether you got it right is
   * `CrossTargetBuildTests`: a module mixing the two is text like any other and only clang will say
   * so. A loop counter internal to a helper may honestly be either, since nothing outside sees it —
   * but it is compared against a length often enough that using this everywhere is both simpler and
   * harder to get wrong.
   */
  protected def word: String = target.word.llvm

  protected val globals  = new mutable.StringBuilder
  private var strId      = 0

  /** The emitted name of every struct that asked for a boundary of its own, and the boundary
   * (`15 §1`). Consulted wherever storage is created, which is the only place an alignment can be
   * *said* — LLVM's textual form gives a named type no alignment, so `@align` has to be stamped onto
   * each alloca and global rather than declared once with the type.
   *
   * Keyed by the emitted name because that is all a slot has to go on: `emitAlloca` is handed a type
   * string, and threading a `Type` to all forty-six of its callers would be a large change to say a
   * thing that only ever applies to a handful of them.
   */
  protected val raisedAligns = mutable.Map.empty[String, Int]
  protected var boolStrs = false
  protected var charBuf  = false
  protected var traps    = false

  /** Whether any string operation renders through `snprintf` — a float `str`, or an `f"…"` format.
   * The single declaration then lives in the module header rather than in each helper.
   */
  protected var usesSnprintf = false

  /** Whether any variadic body walked its tail, which is what the two `llvm.va_*` intrinsics are
   * declared for. `va_arg` is an instruction and needs no declaration of its own.
   */
  protected var usesVarargs = false

  /** Whether anything duplicated a walk, which `llvm.va_copy` is declared for. It is asked
   * separately because a function handed a `*va_list` may copy one without ever starting one.
   */
  protected var usesVaCopy = false

  /** Whether anything copied a block of storage — today only a walk handed to a foreign function on
   * a target that passes `va_list` indirectly (`VaListAbi.Copied`), which is why a module built for
   * one machine declares `llvm.memcpy` and the same module built for another does not.
   */
  protected var usesMemcpy = false

  /** LLVM intrinsic `declare` lines the module turned out to need — the saturating float-to-integer
   * casts, the checked arithmetic, and the bit operations `sysl.math`'s `Bits` lowers to. Each is
   * declared once under its overload-mangled name, which is why this is a set rather than a flag:
   * the name carries the width, so a program using one member at three widths needs three
   * declarations and a flag could not tell them apart.
   */
  protected val satDecls = mutable.LinkedHashSet.empty[String]

  /** Box layouts to declare, keyed by their LLVM name and held in the order they were first
   * needed — a box's payload type is always declared before it.
   */
  protected val boxes = mutable.LinkedHashMap.empty[String, Type]

  /** Buffer layouts to declare, keyed the same way. A buffer carries its element count, since the
   * elements it holds are only reachable from the hook that destroys them.
   */
  protected val bufs = mutable.LinkedHashMap.empty[String, Type]

  /** Whether the module sizes an allocation from something it computed, and so needs the checked
   * arithmetic that keeps a count from wrapping into a buffer too small for it.
   */
  protected var checked = false

  /** Runtime helpers (retain, release, destructors) to emit, generated on demand. Generating one
   * may ask for another, so they are queued rather than emitted inline.
   */
  private val requested            = mutable.HashSet.empty[String]
  protected val runtimeQueue = mutable.Queue.empty[() => String]

  /** Which parts of the ARC runtime the module turned out to need: the heap at all, the atomic
   * pair a `&sync` uses, the null-tolerant pair a slice's owner needs, and the weak trio.
   *
   * The weak *header word* is not on this list, because every box carries it whether or not
   * anything weakly refers to one (`03 § What it costs`) — what is optional is the three functions
   * that read it, and only a program holding a `weak T` calls those.
   */
  protected var heap      = false
  protected var syncHeap  = false
  protected var maybeHeap = false
  protected var weakHeap  = false

  // Per-function emission state, reset at each function boundary.
  private var prologue   = new mutable.StringBuilder
  private var body       = new mutable.StringBuilder
  private var temp       = 0
  private var label      = 0
  private var terminated = false
  private var scratch    = mutable.HashMap.empty[String, String]

  /** References this expression owns and must let go of. The stack mirrors the regions a value
   * may not escape: a statement, and each branch of an `if` or arm of a `match`, release their
   * own before control leaves them, so every release site dominates what it releases.
   */
  protected var tempStack: List[mutable.ListBuffer[(String, Type)]] = Nil

  /** Named slots — parameters, locals, pattern bindings — that hold a reference of their own,
   * innermost scope first. Each holds one count, taken when the slot is written and given back
   * when the scope ends or the function returns.
   */
  protected var owned: List[mutable.ListBuffer[(String, Type)]] = Nil

  /** What each scope has been asked to run on its way out — the `defer` stack (`03 § defer`),
   * innermost first. It sits beside `owned` because it is pushed, popped and unwound with it and
   * never on its own, so the two are always the same length and one index reaches both.
   */
  protected var deferrals: List[mutable.ListBuffer[TStmt]] = Nil

  /** The enclosing loops, innermost first, so a `break`/`continue` knows where to jump, what
   * result slot to store into, and how far to unwind the ownership regions on the way out. The
   * depths are the sizes of `owned`/`tempStack` at loop entry, so leaving releases exactly what
   * the body accrued.
   */
  protected case class GenLoop(breakL: String, continueL: String, slot: String, resultTy: Type,
                               ownedDepth: Int, tempDepth: Int)
  protected var genLoops: List[GenLoop] = Nil

  /** Where a tail self-call jumps, and which calls are the ones that jump (`TailCalls`).
   *
   * `tailTarget` is the label sitting just after the parameter slots are written, so the jump lands
   * where a fresh call would have landed: the preconditions are checked again and each `old(e)` is
   * snapshotted again, because a tail call *is* a call and those belong to the invocation rather
   * than to the frame. It is `None` in a function with no tail call, which is what keeps a label
   * out of every other function in the module.
   *
   * `tailCalls` is compared by identity, since two calls written alike are equal case classes
   * standing in different positions — only one of which the walk may have named.
   */
  protected var tailTarget: Option[String] = None
  protected var tailCalls: List[TCall]     = Nil

  /** The parameters a jump rebinds, in declaration order — every one of them, so that they line up
   * with the arguments a call was written with. A zero-sized one has no slot to write and is
   * skipped where the writing happens, not here.
   */
  protected var tailParams: List[(String, Type)] = Nil

  /** Whether this call is the one the walk named, rather than merely equal to it. */
  protected def isTailCall(c: TCall): Boolean = tailCalls.exists(_ eq c)

  /** Folding `and` / `or` over `i1`s, with the constant cases collapsed rather than emitted — a
   * pattern test that cannot fail contributes `"true"`, and ANDing that in would be an instruction
   * saying nothing. Both halves of codegen build conditions this way, so they live here.
   */
  protected def andI1(a: String, b: String): String =
    if a == "true" then b
    else if b == "true" then a
    else { val r = freshTemp(); emit(s"$r = and i1 $a, $b"); r }

  protected def orI1(a: String, b: String): String =
    if a == "true" || b == "true" then "true"
    else { val r = freshTemp(); emit(s"$r = or i1 $a, $b"); r }

  /** Negating an `i1`, with the same constant folded away — what `is not` does to the test its
   * pattern produced.
   */
  protected def notI1(a: String): String =
    if a == "true" then "false"
    else { val r = freshTemp(); emit(s"$r = xor i1 $a, true"); r }

  // --- bit ranges of a bitfield struct's container ---------------------------------------
  //
  // A bitfield struct is one integer and its fields are ranges of it (`Bitfields`), so the two
  // things every emitter needs are here rather than in each of them: lifting a range out of a
  // container, and putting one back. Both halves of codegen reach a field — the one that reads a
  // value and the one that writes through a place — which is the reason the `i1` folding above
  // lives here too.

  /** The ranges of the bitfield struct this type is one of, through whatever qualifies it, or
   * `None` — which is every other type.
   */
  protected def bitfieldOf(t: Type): Option[List[BitRange]] = Type.underlying(t) match
    case s: Type.Struct => Bitfields.of(s)
    case _              => None

  /** The container type a bitfield struct's fields are ranges of. */
  protected def containerLlvm(ranges: List[BitRange]): String = s"i${Bitfields.bits(ranges)}"

  /** A field's value widened to the container and shifted to where it belongs — the half of a write
   * that is about the range, before anything is said about what was there already.
   */
  protected def placeBits(ranges: List[BitRange], r: BitRange, v: String): String = {
    val ct = containerLlvm(ranges)

    val wide =
      if r.width == Bitfields.bits(ranges) then v
      else { val t = freshTemp(); emit(s"$t = zext i${r.width} $v to $ct"); t }

    if r.offset == 0 then wide
    else { val t = freshTemp(); emit(s"$t = shl $ct $wide, ${r.offset}"); t }
  }

  /** A whole container built from one value per field, which is what constructing a bitfield struct
   * is. It ors the placed ranges together rather than folding `writeBits` over a zero, because there
   * is nothing to preserve: every bit of the result is being written.
   */
  protected def buildBits(ranges: List[BitRange], vals: List[String]): String = {
    val ct = containerLlvm(ranges)

    ranges.zip(vals).map(placeBits(ranges, _, _)).reduceOption { (a, b) =>
      val t = freshTemp(); emit(s"$t = or $ct $a, $b"); t
    }.getOrElse("0")
  }

  /** One range lifted out of the container `c`.
   *
   * **No sign fixup is needed and none is emitted**, which is worth saying because a C bitfield
   * needs one: an `i5` field *is* an LLVM `i5`, so truncating the shifted container to the field's
   * own width lands the two's-complement value already in place. The signedness is in the type
   * rather than in the extraction.
   */
  protected def readBits(ranges: List[BitRange], r: BitRange, c: String): String = {
    val ct     = containerLlvm(ranges)
    val shifted =
      if r.offset == 0 then c
      else { val t = freshTemp(); emit(s"$t = lshr $ct $c, ${r.offset}"); t }

    if r.width == Bitfields.bits(ranges) then shifted
    else { val t = freshTemp(); emit(s"$t = trunc $ct $shifted to i${r.width}"); t }
  }

  /** The container `c` with one range replaced by `v` — the read-modify-write a bitfield write is,
   * with the read already done by the caller so that the whole of a multi-field update is one load
   * and one store rather than one of each per field.
   */
  protected def writeBits(ranges: List[BitRange], r: BitRange, c: String, v: String): String = {
    val ct         = containerLlvm(ranges)
    val bits       = Bitfields.bits(ranges)
    val (_, clear) = Bitfields.mask(r, bits)
    val shifted    = placeBits(ranges, r, v)

    // A field occupying the whole container has nothing to preserve, so the mask and the or would
    // be two instructions saying `shifted` — and the constant one of them ands with is zero, which
    // reads as a bug rather than as an identity.
    if r.width == bits then shifted
    else
      val cleared = freshTemp(); emit(s"$cleared = and $ct $c, $clear")
      val merged  = freshTemp(); emit(s"$merged = or $ct $cleared, $shifted")
      merged
  }

  // --- how a sysl signature is spelled --------------------------------------------------
  //
  // Six places write down what a sysl function looks like to LLVM — its definition, a declaration
  // of one linked in from a library, a call, the whole function type a variadic call has to name,
  // a method table's adapter, and the `ret` itself. They agree because they all ask here.

  /** The out-pointer a **large** result comes back through, in front of every declared parameter.
   *
   * A result that fits in registers is returned as itself, as it always was. One that does not is
   * written straight into storage the caller supplies, so it is never a first-class LLVM value at
   * either end — which is the whole of what `layout.indirect` buys. It is the same `sret` the
   * foreign boundary has always used (`ForeignEmitter`), asked for the same reason on a call that
   * happens to have this compiler on both sides.
   */
  protected def syslSret(retTy: Type): Option[String] =
    Option.when(layout.indirect(retTy))(s"ptr noalias sret(${retTy.llvm}) align ${layout.align(retTy)}")

  /** What a sysl `define`, `declare` and `call` name as the result type.
   *
   * A **narrow** result carries the C convention's extension (`CAbi.extension`), which is the one
   * thing about a sysl signature that is not sysl's own answer. Widening a result is the *callee's*
   * obligation, and a definition cannot know who calls it: `@export` and `&f` hand this very symbol
   * to C, and a `bool` returned without the attribute would reach it as whatever was in the top of
   * the register. Stating it everywhere costs a sysl caller nothing — it does not ask for the
   * extension and is not harmed by receiving one — and it means no part of the compiler has to work
   * out which definitions C can reach.
   *
   * A **parameter** gets none, which is the same rule seen from the other side: widening an argument
   * is the caller's obligation, so it is stated at a foreign call (`ForeignEmitter`) and nowhere
   * else. Neither end of a sysl-to-sysl call claims it, so neither may rely on it.
   */
  protected def syslResult(retTy: Type): String =
    CAbi.returning(if syslResultType(retTy) == "void" then "" else CAbi.extension(retTy, target),
                   syslResultType(retTy))

  /** The same **type**, with no attribute on it — for the `ret` instruction, which takes none.
   *
   * A return attribute belongs to the signature: `define zeroext i1 @f()` states what the function
   * guarantees, and the terminator inside it just names the value's type. LLVM refuses `ret zeroext
   * i1 %x` outright, which is a parse error in the emitted module rather than anything a test of the
   * compiler's own types would notice — so the two spellings are kept apart here rather than at each
   * of the places that needs one.
   */
  protected def syslResultType(retTy: Type): String =
    if Type.noValue(retTy) || layout.indirect(retTy) then "void" else retTy.llvm

  /** How a parameter is declared. A **large** one arrives as the address of storage the caller
   * holds; the callee makes its own copy at entry, which is the copy it always made — the only
   * difference is that the value crosses the boundary in memory rather than in registers.
   */
  protected def syslParam(ty: Type): String = if layout.indirect(ty) then "ptr" else ty.llvm

  /** The name the out-pointer takes inside a function that has one. */
  protected val sretParam = "%sret.out"

  // --- hooks provided by the Codegen class ---------------------------------------------
  //
  // The recursive entry points live in the class that walks the tree; the emitting traits call
  // back into them.

  /** Lowers an expression, returning the register or immediate holding its value. */
  protected def genExpr(expr: TExpr): String

  /** Lowers an expression into the storage at `dest`, leaving the destination owning what lands
   * there — a count taken for every reference inside (`CallEmitter`).
   */
  protected def genOwnedInto(dest: String, e: TExpr): Unit

  /** The same, taking no counts: what lands at `dest` is borrowed, exactly as the register
   * `genExpr` hands back is (`CallEmitter`).
   */
  protected def genBorrowedInto(dest: String, e: TExpr): Unit

  /** Lowers one statement for its effects. */
  protected def genStmt(stmt: TStmt): Unit

  /** The value a compound assignment stores: what its operator makes of the place's current value
   * and the value on the right, whether that is an instruction or a trait method (`14 §3`).
   */
  protected def combine(op: String, ty: Type, valueTy: Type, dispatch: Option[TDispatch],
                        cur: String, v: String): String

  /** Traps unless a struct's `invariant` clauses hold of the value in `v` (`16 §6`). */
  protected def emitInvCheck(v: String, struct: Type.Struct, invFn: String): Unit

  /** Traps unless `v` satisfies everything the constrained subtype `c` asks of its values — its
   * `within` range and its `where` predicate (`16 §4`).
   */
  protected def emitConstraintChecks(v: String, c: Type.Constrained): Unit

  protected def startFunction(): Unit = {
    prologue = new mutable.StringBuilder
    body = new mutable.StringBuilder
    temp = 0
    label = 0
    terminated = false
    tempStack = Nil
    owned = Nil
    deferrals = Nil
    genLoops = Nil
    tailTarget = None
    tailCalls = Nil
    tailParams = Nil
    scratch = mutable.HashMap.empty
    promoted = Set.empty
    promotedBoxes = mutable.HashMap.empty
    refStorage = mutable.HashMap.empty
    refPlaceOf = mutable.HashMap.empty
  }

  /** The **storage** type each `ref` in this body names (`03 § ref`), which is not the same as the
   * type of the values that come out of it.
   *
   * A qualifier lives on storage rather than on a value (`03 § Device memory`), and the ordinary way
   * to read one is off the place — a field knows its own declaration. A ref's uses are all a plain
   * name, which has no declaration to consult, so the qualifier it found at the binding is kept here
   * and put back at each access. Without it a ref to a register would emit an unmarked load and
   * store: the same access the direct path marks `volatile`, silently free to be reordered or
   * dropped.
   *
   * Per function, like every other name-keyed table here, since a name is unique only within one.
   */
  protected var refStorage: mutable.HashMap[String, Type] = mutable.HashMap.empty

  /** The place each `ref` in this body names, for the walks that have to reach past the name to the
   * storage's **root** rather than to its address — which the address alone cannot tell them.
   *
   * `promotedOwner` is the one that needs it: a view records the box it borrows from, and a ref that
   * names a promoted array has to hand over that array's box rather than the null a name with no
   * declaration would otherwise give.
   */
  protected var refPlaceOf: mutable.HashMap[String, TExpr] = mutable.HashMap.empty

  /** The local arrays this body allocates on the heap rather than in its frame, decided by the
   * escape analysis (`05`), and the buffer each one ended up in.
   *
   * A promoted array keeps its `[N]T` type and only its storage moves, so `%name.addr` is the
   * buffer's data pointer instead of an `alloca` and every index, store and copy is emitted exactly
   * as it was. What the box is needed for is the one thing that differs: a slice of the array names
   * it as its owner, where a frame-backed array has none.
   */
  protected var promoted: Set[String]                 = Set.empty
  protected var promotedBoxes: mutable.HashMap[String, String] = mutable.HashMap.empty

  /** One stack slot per LLVM type per function, for the type punning a union needs: a value goes
   * in written as one type and comes back out read as another. Sharing the slot is safe because
   * every use of it stores and immediately loads with nothing emitted in between, and it is worth
   * doing because a function that matches on an enum in a hundred places would otherwise carry a
   * hundred slots it uses one at a time.
   */
  protected def scratchSlot(ty: String): String =
    scratch.getOrElseUpdate(ty, emitAlloca(freshTemp(), ty))

  /** The address of the payload region inside an enum sitting at `base`. */
  protected def payloadPtr(en: Type.Enum, base: String): String = {
    val r = freshTemp()
    emit(s"$r = getelementptr ${en.llvm}, ptr $base, i32 0, i32 1")
    r
  }

  /** Reads a variant's payload out of an enum value, at that variant's type.
   *
   * A union is read by putting the whole value somewhere and loading the part back at the type the
   * variant that wrote it used, since an aggregate value has no operation that reinterprets part of
   * itself. Reading a variant the value is not holding yields whatever the one it *is* holding left
   * there — which is what a pattern test does before it knows the tag, and the tag test beside it is
   * what makes the answer count.
   */
  protected def enumPayload(en: Type.Enum, variant: Type.EnumVariant, value: String): String = {
    val slot = scratchSlot(en.llvm)

    emit(s"store ${en.llvm} $value, ptr $slot")

    val p = payloadPtr(en, slot)
    val r = freshTemp()

    emit(s"$r = load ${en.payloadLlvm(variant)}, ptr $p")
    r
  }

  /** The text of the function just emitted: its header, the hoisted slots, and its blocks. */
  protected def finishFunction(header: String): String =
    s"$header {\nentry:\n$prologue$body}\n"

  protected def freshTemp(): String         = { temp += 1; s"%t$temp" }
  protected def freshLabel(s: String): String = { label += 1; s"$s$label" }

  /** Emits a plain instruction, unless the current block is already terminated. */
  protected def emit(line: String): Unit =
    if !terminated then { body ++= "  "; body ++= line; body ++= "\n" }

  /** Emits a stack slot into the function's entry block rather than where it is needed.
   * Every name is unique within a function, so hoisting is safe — and it keeps a slot inside
   * a loop from growing the stack on every iteration.
   *
   * `align` is a boundary the **declaration** asked for (`@align(n)` on a `var` or `val`), and it
   * wins over the one the type carries. It cannot ask for less: `@align` only ever raises, so the
   * larger of the two is what satisfies both claims, and one written on the declaration is by that
   * rule already at or above the type's.
   */
  protected def emitAlloca(name: String, ty: String, align: Option[Int] = None): String = {
    prologue ++= s"  $name = alloca $ty${align.map(n => s", align $n").getOrElse(alignSuffix(ty))}\n"
    name
  }

  /** `, align n` where the type this storage holds asked for a boundary, and nothing otherwise —
   * LLVM's own choice is the natural alignment, which is right for everything that did not ask.
   *
   * An array is covered by the same lookup: `[8 x %struct.Frame]` names the struct it is made of, so
   * a region of aligned elements begins where its first element must, which is what makes an aligned
   * type usable as a buffer rather than only as a single value.
   */
  protected def alignSuffix(ty: String): String = {
    val at = ty.indexOf("%struct.")

    if at < 0 then ""
    else
      val name = ty.drop(at).takeWhile(c => c.isLetterOrDigit || c == '.' || c == '_' || c == '%')

      raisedAligns.get(name).map(n => s", align $n").getOrElse("")
  }

  /** Emits a block terminator (`br` / `ret` / `unreachable`) and marks the block closed. */
  protected def emitTerm(line: String): Unit =
    if !terminated then { body ++= "  "; body ++= line; body ++= "\n"; terminated = true }

  protected def emitLabel(l: String): Unit = { body ++= l; body ++= ":\n"; terminated = false }

  /** Generates one whole function while another is in progress, which is how a runtime helper
   * gets written at the moment it is first asked for.
   */
  protected def inFunction(header: String)(gen: => Unit): String = {
    val saved =
      (prologue, body, temp, label, terminated, tempStack, owned, scratch, promoted, promotedBoxes, deferrals)
    // A helper is emitted in the middle of whatever asked for it, and the asking function may be one
    // with a jump in it — so what says where that jump goes is put back too. Without this the helper's
    // reset would leave the interrupted function with no target and its remaining tail calls would be
    // emitted as ordinary ones, which is a miscompile only a body long enough to need a helper shows.
    val savedTail = (tailTarget, tailCalls, tailParams)

    startFunction()
    gen
    val text = finishFunction(header)

    prologue = saved._1; body = saved._2; temp = saved._3; label = saved._4
    terminated = saved._5; tempStack = saved._6; owned = saved._7; scratch = saved._8
    promoted = saved._9; promotedBoxes = saved._10; deferrals = saved._11
    tailTarget = savedTail._1; tailCalls = savedTail._2; tailParams = savedTail._3
    text
  }

  /** Queues a runtime helper for emission, once per name. */
  protected def request(name: String)(gen: => String): String = {
    if requested.add(name) then runtimeQueue.enqueue(() => gen)
    name
  }

  // --- string interning ------------------------------------------------------------------

  protected def stringGlobal(s: String): String = {
    strId += 1
    val name           = s"@.str$strId"
    val (escaped, len) = encode(s)
    globals ++= s"$name = private constant [$len x i8] c\"$escaped\"\n"
    name
  }

  private def encode(s: String): (String, Int) = {
    val bytes = s.getBytes("UTF-8")
    val sb    = new mutable.StringBuilder
    for b <- bytes do
      val u = b & 0xff
      if u == '"'.toInt || u == '\\'.toInt || u < 0x20 || u >= 0x7f then sb ++= f"\\$u%02X"
      else sb += u.toChar
    sb ++= "\\00"
    (sb.toString, bytes.length + 1)
  }
}
