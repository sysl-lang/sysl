package io.github.edadma.sysl

import scala.collection.mutable

/** The substrate every part of codegen emits into: registers, basic blocks, the entry-block
 * prologue, module-level globals, and the queue of runtime helpers.
 *
 * This trait holds *how* IR is written down and none of what it says. The lowering rules live
 * in the traits and the class mixed on top of it — `ArcEmitter` for reference counting,
 * `ScalarEmitter` for arithmetic and printing, `Codegen` for the tree walk itself.
 */
trait Emitter {

  /** The machine this module is being emitted for (`targets.md`). Almost nothing consults it —
   * a 64-bit target's layout is the same everywhere sysl runs — and what does is the handful of
   * places where the C ABI genuinely differs, each of which says so where it reads this.
   */
  protected def target: Target

  protected val globals  = new mutable.StringBuilder
  private var strId      = 0
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

  // --- hooks provided by the Codegen class ---------------------------------------------
  //
  // The recursive entry points live in the class that walks the tree; the emitting traits call
  // back into them.

  /** Lowers an expression, returning the register or immediate holding its value. */
  protected def genExpr(expr: TExpr): String

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
   */
  protected def emitAlloca(name: String, ty: String): String = {
    prologue ++= s"  $name = alloca $ty\n"
    name
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

    startFunction()
    gen
    val text = finishFunction(header)

    prologue = saved._1; body = saved._2; temp = saved._3; label = saved._4
    terminated = saved._5; tempStack = saved._6; owned = saved._7; scratch = saved._8
    promoted = saved._9; promotedBoxes = saved._10; deferrals = saved._11
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
