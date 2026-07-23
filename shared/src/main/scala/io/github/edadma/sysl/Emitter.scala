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

  protected val globals  = new mutable.StringBuilder
  private var strId      = 0
  protected var boolStrs = false
  protected var charBuf  = false
  protected var traps    = false

  /** Box layouts to declare, keyed by their LLVM name and held in the order they were first
   * needed — a box's payload type is always declared before it.
   */
  protected val boxes = mutable.LinkedHashMap.empty[String, Type]

  /** Runtime helpers (retain, release, destructors) to emit, generated on demand. Generating one
   * may ask for another, so they are queued rather than emitted inline.
   */
  private val requested            = mutable.HashSet.empty[String]
  protected val runtimeQueue = mutable.Queue.empty[() => String]

  /** Which parts of the ARC runtime the module turned out to need: the heap at all, the atomic
   * pair a `&sync` uses, and the null-tolerant pair a slice's owner needs.
   */
  protected var heap      = false
  protected var syncHeap  = false
  protected var maybeHeap = false

  // Per-function emission state, reset at each function boundary.
  private var prologue   = new mutable.StringBuilder
  private var body       = new mutable.StringBuilder
  private var temp       = 0
  private var label      = 0
  private var terminated = false

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

  /** Lowers an expression, returning the register or immediate holding its value. Defined by
   * the class that walks the tree; the emitting traits call back into it.
   */
  protected def genExpr(expr: TExpr): String

  protected def startFunction(): Unit = {
    prologue = new mutable.StringBuilder
    body = new mutable.StringBuilder
    temp = 0
    label = 0
    terminated = false
    tempStack = Nil
    owned = Nil
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
    val saved = (prologue, body, temp, label, terminated, tempStack, owned)

    startFunction()
    gen
    val text = finishFunction(header)

    prologue = saved._1; body = saved._2; temp = saved._3; label = saved._4
    terminated = saved._5; tempStack = saved._6; owned = saved._7
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
