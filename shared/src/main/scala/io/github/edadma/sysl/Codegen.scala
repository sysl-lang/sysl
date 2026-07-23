package io.github.edadma.sysl

import scala.collection.mutable

/** Lowers a typed program (`TProgram`) to a textual LLVM IR module.
 *
 * Codegen makes no semantic decisions — the analyzer has already resolved every name, fixed
 * every type, and checked every rule. This pass is a straight translation: it selects
 * instructions from the types the tree carries and lays out basic blocks. Opaque pointers and
 * hex-double float constants keep the output stable across the textual round-trip.
 */
class Codegen private (program: TProgram) {

  private val globals  = new mutable.StringBuilder
  private var strId    = 0
  private var boolStrs = false
  private var charBuf  = false
  private var traps    = false

  /** Box layouts to declare, keyed by their LLVM name and held in the order they were first
   * needed — a box's payload type is always declared before it.
   */
  private val boxes = mutable.LinkedHashMap.empty[String, Type]

  /** Runtime helpers (retain, release, destructors) to emit, generated on demand. Generating one
   * may ask for another, so they are queued rather than emitted inline.
   */
  private val requested    = mutable.HashSet.empty[String]
  private val runtimeQueue = mutable.Queue.empty[() => String]
  private var heap         = false

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
  private var tempStack: List[mutable.ListBuffer[(String, Type)]] = Nil

  /** Named slots — parameters, locals, pattern bindings — that hold a reference of their own,
   * innermost scope first. Each holds one count, taken when the slot is written and given back
   * when the scope ends or the function returns.
   */
  private var owned: List[mutable.ListBuffer[(String, Type)]] = Nil

  private def startFunction(): Unit = {
    prologue = new mutable.StringBuilder
    body = new mutable.StringBuilder
    temp = 0
    label = 0
    terminated = false
    tempStack = Nil
    owned = Nil
  }

  private def freshTemp(): String            = { temp += 1; s"%t$temp" }
  private def freshLabel(s: String): String  = { label += 1; s"$s$label" }

  /** Emits a plain instruction, unless the current block is already terminated. */
  private def emit(line: String): Unit =
    if !terminated then { body ++= "  "; body ++= line; body ++= "\n" }

  /** Emits a stack slot into the function's entry block rather than where it is needed.
   * Every name is unique within a function, so hoisting is safe — and it keeps a slot inside
   * a loop from growing the stack on every iteration.
   */
  private def emitAlloca(name: String, ty: String): String = {
    prologue ++= s"  $name = alloca $ty\n"
    name
  }

  /** Emits a block terminator (`br` / `ret` / `unreachable`) and marks the block closed. */
  private def emitTerm(line: String): Unit =
    if !terminated then { body ++= "  "; body ++= line; body ++= "\n"; terminated = true }

  private def emitLabel(l: String): Unit = { body ++= l; body ++= ":\n"; terminated = false }

  /** Generates one whole function while another is in progress, which is how a runtime helper
   * gets written at the moment it is first asked for.
   */
  private def inFunction(header: String)(gen: => Unit): String = {
    val saved =
      (prologue, body, temp, label, terminated, tempStack, owned)

    startFunction()
    gen
    val text = s"$header {\nentry:\n$prologue$body}\n"

    prologue = saved._1; body = saved._2; temp = saved._3; label = saved._4
    terminated = saved._5; tempStack = saved._6; owned = saved._7
    text
  }

  /** Queues a runtime helper for emission, once per name. */
  private def request(name: String)(gen: => String): String = {
    if requested.add(name) then runtimeQueue.enqueue(() => gen)
    name
  }

  // --- reference counting ----------------------------------------------------------------

  /** Whether copying a value of this type has to touch a refcount. A raw pointer never does —
   * it is the mode that opts out of management — and a `&T` is a leaf, so a recursive type
   * cannot make this recur forever.
   */
  private def containsRef(t: Type): Boolean = t match
    case _: Type.Ref    => true
    case s: Type.Struct => s.fields.exists(f => containsRef(f._2))
    case e: Type.Enum   => e.variants.exists(_.fields.exists(f => containsRef(f._2)))
    case _              => false

  /** The LLVM name of the box that holds a `T` on the heap: the refcount, the deallocation
   * hook, and the payload.
   */
  private def boxName(payload: Type): String = {
    heap = true
    val n = s"%arc.${Type.mangle(payload)}"
    boxes.getOrElseUpdate(n, payload)
    n
  }

  /** Takes a share of everything a value refers to. A bare reference is one refcount; an
   * aggregate delegates to a per-type helper that walks its reference-carrying fields.
   */
  private def retainValue(ty: Type, v: String): Unit = ty match
    case Type.Ref(_, sync) =>
      heap = true
      emit(s"call void @arc.retain${if sync then "_sync" else ""}(ptr $v)")
    case t if containsRef(t) => emit(s"call void @${valueHelper(t, retain = true)}(${t.llvm} $v)")
    case _                   => ()

  /** Gives back a share of everything a value refers to. */
  private def releaseValue(ty: Type, v: String): Unit = ty match
    case Type.Ref(inner, sync) => emit(s"call void @${releaseFn(inner, sync)}(ptr $v)")
    case t if containsRef(t)   => emit(s"call void @${valueHelper(t, retain = false)}(${t.llvm} $v)")
    case _                     => ()

  /** The release function for a box, which is per payload type because reaching zero has to run
   * that type's destructor. Atomicity is a property of the *reference*, not of the object, so
   * the two orderings are two functions over one layout.
   */
  private def releaseFn(payload: Type, sync: Boolean): String = {
    val m  = Type.mangle(payload)
    val bn = boxName(payload)

    request(s"arc.drop.$m") {
      inFunction(s"define private void @arc.drop.$m(ptr %p)") {
        if containsRef(payload) then
          val pa = freshTemp(); emit(s"$pa = getelementptr $bn, ptr %p, i32 0, i32 2")
          val v  = freshTemp(); emit(s"$v = load ${payload.llvm}, ptr $pa")
          releaseValue(payload, v)
        val h = freshTemp(); emit(s"$h = getelementptr $bn, ptr %p, i32 0, i32 1")
        val f = freshTemp(); emit(s"$f = load ptr, ptr $h")
        emit(s"call void $f(ptr %p)")
        emitTerm("ret void")
      }
    }

    val name = if sync then s"arc.release_sync.$m" else s"arc.release.$m"

    request(name) {
      inFunction(s"define private void @$name(ptr %p)") {
        val dropL  = freshLabel("arc.drop")
        val doneL  = freshLabel("arc.live")
        val atZero = freshTemp()

        if sync then
          // Release ordering publishes every write this domain made before letting go.
          val old = freshTemp(); emit(s"$old = atomicrmw sub ptr %p, i64 1 release")
          emit(s"$atZero = icmp eq i64 $old, 1")
        else
          val cur = freshTemp(); emit(s"$cur = load i64, ptr %p")
          val nxt = freshTemp(); emit(s"$nxt = sub i64 $cur, 1")
          emit(s"store i64 $nxt, ptr %p")
          emit(s"$atZero = icmp eq i64 $nxt, 0")

        emitTerm(s"br i1 $atZero, label %$dropL, label %$doneL")
        emitLabel(dropL)
        // The acquire fence makes every other domain's writes visible to the thread that frees.
        if sync then emit("fence acquire")
        emit(s"call void @arc.drop.$m(ptr %p)")
        emitTerm("ret void")
        emitLabel(doneL)
        emitTerm("ret void")
      }
    }
  }

  /** The retain / release helper for an aggregate type, which walks the fields that carry
   * references. Emitted once per type rather than inlined, since a data enum needs a tag test
   * per reference-carrying variant.
   */
  private def valueHelper(ty: Type, retain: Boolean): String = {
    val name = s"arc.${if retain then "copy" else "dispose"}.${Type.mangle(ty)}"

    request(name) {
      inFunction(s"define private void @$name(${ty.llvm} %v)") {
        walkValue(ty, "%v", retain)
        emitTerm("ret void")
      }
    }
  }

  private def walkValue(ty: Type, v: String, retain: Boolean): Unit = {
    def each(fields: List[(String, Type)], aggregate: String, value: String): Unit =
      for ((_, fty), i) <- fields.zipWithIndex if containsRef(fty) do
        val f = freshTemp()
        emit(s"$f = extractvalue $aggregate $value, $i")
        if retain then retainValue(fty, f) else releaseValue(fty, f)

    ty match
      case s: Type.Struct => each(s.fields, s.llvm, v)

      case e: Type.Enum =>
        val tag  = freshTemp(); emit(s"$tag = extractvalue ${e.llvm} $v, 0")
        val endL = freshLabel("arc.done")
        for variant <- e.variants if variant.fields.exists(f => containsRef(f._2)) do
          val hitL  = freshLabel("arc.variant")
          val nextL = freshLabel("arc.next")
          val is    = freshTemp(); emit(s"$is = icmp eq i32 $tag, ${variant.tag}")
          emitTerm(s"br i1 $is, label %$hitL, label %$nextL")
          emitLabel(hitL)
          val payload = freshTemp()
          emit(s"$payload = extractvalue ${e.llvm} $v, ${variant.payloadSlot.get}")
          each(variant.fields, e.payloadLlvm(variant), payload)
          emitTerm(s"br label %$endL")
          emitLabel(nextL)
        emitTerm(s"br label %$endL")
        emitLabel(endL)

      case _ => ()
  }

  // --- ownership regions -----------------------------------------------------------------

  private def pushTemps(): Unit = tempStack = mutable.ListBuffer.empty[(String, Type)] :: tempStack

  /** Records a value the expression owns outright — a fresh box, or a call result, which every
   * function returns with a count already taken.
   */
  private def ownTemp(v: String, ty: Type): String = {
    if containsRef(ty) then tempStack.head += ((v, ty))
    v
  }

  /** Releases the innermost region's temporaries and pops it. */
  private def popTemps(): Unit = {
    for (v, ty) <- tempStack.head.reverse do releaseValue(ty, v)
    tempStack = tempStack.tail
  }

  private def pushOwned(): Unit = owned = mutable.ListBuffer.empty[(String, Type)] :: owned

  /** Records a slot that holds a count of its own, after it has been written. */
  private def ownSlot(name: String, ty: Type): Unit =
    if containsRef(ty) then owned.head += ((s"%$name.addr", ty))

  /** Emits the releases for the innermost scope without popping it — the guard-failure path out
   * of a match arm, where the bindings have been made but the arm is not taken.
   */
  private def releaseOwned(): Unit =
    for (slot, ty) <- owned.head.reverse do
      val v = freshTemp(); emit(s"$v = load ${ty.llvm}, ptr $slot")
      releaseValue(ty, v)

  private def popOwned(): Unit = { releaseOwned(); owned = owned.tail }

  /** Lets go of everything the function holds, for a `return` that leaves from the middle of
   * it. Nothing is popped: the block is terminated straight afterwards, so the scopes that
   * follow this one lexically emit their own releases into unreachable code and are dropped.
   */
  private def releaseAll(): Unit = {
    for frame <- tempStack; (v, ty) <- frame.reverse do releaseValue(ty, v)
    for scope <- owned; (slot, ty) <- scope.reverse do
      val v = freshTemp(); emit(s"$v = load ${ty.llvm}, ptr $slot")
      releaseValue(ty, v)
  }

  // --- string interning ----------------------------------------------------------------

  private def stringGlobal(s: String): String = {
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

  // --- module --------------------------------------------------------------------------

  private def gen(): String = {
    val funcTexts = program.funcs.map(genFunction)
    val mainText  = genMain(program.main)

    // Emitting a runtime helper may ask for another (a destructor releases the references its
    // payload holds), so this runs until nothing new is requested.
    val runtimeTexts = mutable.ListBuffer.empty[String]
    while runtimeQueue.nonEmpty do runtimeTexts += runtimeQueue.dequeue()()

    val out = new mutable.StringBuilder
    out ++= "declare i32 @printf(ptr, ...)\n"
    if traps then out ++= "declare void @llvm.trap()\n"
    if heap then
      out ++= "declare ptr @malloc(i64)\n"
      out ++= "declare void @free(ptr)\n"
    out ++= "\n"

    for s <- program.structs do
      out ++= s"${s.llvm} = type { ${s.fields.map(_._2.llvm).mkString(", ")} }\n"
    if program.structs.nonEmpty then out ++= "\n"

    for e <- program.enums do
      for v <- e.variants if v.payloadSlot.isDefined do
        out ++= s"${e.payloadLlvm(v)} = type { ${v.fields.map(_._2.llvm).mkString(", ")} }\n"
      val slots = "i32" :: e.variants.collect { case v if v.payloadSlot.isDefined => e.payloadLlvm(v) }
      out ++= s"${e.llvm} = type { ${slots.mkString(", ")} }\n"
    if program.enums.nonEmpty then out ++= "\n"

    // A box is the refcount, the function that frees it, and the payload — so ARC works the
    // same everywhere, and an object frees itself into whichever heap made it.
    for (name, payload) <- boxes do
      out ++= s"$name = type { i64, ptr, ${payload.llvm} }\n"
    if boxes.nonEmpty then out ++= "\n"

    if boolStrs then
      out ++= "@.true = private constant [5 x i8] c\"true\\00\"\n"
      out ++= "@.false = private constant [6 x i8] c\"false\\00\"\n"
    out ++= globals.toString
    if globals.nonEmpty || boolStrs then out ++= "\n"

    if charBuf then out ++= Codegen.utf8Encoder
    if heap then out ++= Codegen.arcRuntime
    for t <- runtimeTexts do out ++= t; out ++= "\n"

    for t <- funcTexts do out ++= t; out ++= "\n"
    out ++= mainText
    out.toString
  }

  private def genMain(stmts: List[TStmt]): String = {
    startFunction()
    pushTemps()
    pushOwned()
    stmts.foreach(genStmt)
    releaseAll()
    emitTerm("ret i32 0")
    s"define i32 @main() {\nentry:\n$prologue$body}\n"
  }

  /** A function owns its parameters and returns its result with a count already taken, so a
   * caller can hand over a temporary and a callee can store one without either having to know
   * what the other did with it.
   */
  private def genFunction(f: TFunc): String = {
    startFunction()
    pushTemps()
    pushOwned()

    for (name, ty) <- f.params do
      emitAlloca(s"%$name.addr", ty.llvm)
      emit(s"store ${ty.llvm} %$name.param, ptr %$name.addr")
      retainValue(ty, s"%$name.param")
      ownSlot(name, ty)

    f.body.stmts.foreach(genStmt)

    f.body.result match
      case Some(r) if f.retTy != Type.Unit =>
        val v = genExpr(r)
        retainValue(f.retTy, v)
        releaseAll()
        emitTerm(s"ret ${f.retTy.llvm} $v")
      case Some(r) =>
        genExpr(r); releaseAll(); emitTerm("ret void")
      case None if f.retTy == Type.Unit =>
        releaseAll(); emitTerm("ret void")
      case None =>
        releaseAll(); emitTerm(s"ret ${f.retTy.llvm} ${zero(f.retTy)}")

    val params = f.params.map { case (name, ty) => s"${ty.llvm} %$name.param" }.mkString(", ")
    s"define ${f.retTy.llvm} @${f.name}($params) {\nentry:\n$prologue$body}\n"
  }

  private def zero(ty: Type): String = ty match
    case _: Type.Integer  => "0"
    case _: Type.Floating => "0.0"
    case Type.Char        => "0"
    case Type.Bool        => "0"
    case Type.Str         => "null"
    case _: Type.Ptr      => "null"
    case _: Type.Ref      => "null"
    case _: Type.Struct   => "zeroinitializer"
    case e: Type.Enum     => if e.simple then "0" else "zeroinitializer"
    case Type.Unit        => ""

  // --- statements ----------------------------------------------------------------------

  /** A statement is the region a temporary lives in: whatever it allocated or was handed is
   * released once the statement is over, leaving only what a slot has taken a count of.
   */
  private def genStmt(stmt: TStmt): Unit = {
    pushTemps()
    genStmtBody(stmt)
    popTemps()
  }

  private def genStmtBody(stmt: TStmt): Unit = stmt match
    case TVarDecl(name, ty, init) =>
      val v = genExpr(init)
      emitAlloca(s"%$name.addr", ty.llvm)
      retainValue(ty, v)
      emit(s"store ${ty.llvm} $v, ptr %$name.addr")
      ownSlot(name, ty)

    case TExprStmt(expr) =>
      genExpr(expr)

    case TWhile(cond, whileBody) =>
      val condL = freshLabel("while.cond")
      val bodyL = freshLabel("while.body")
      val endL  = freshLabel("while.end")
      emitTerm(s"br label %$condL")
      emitLabel(condL)
      // The condition is re-evaluated every iteration, so whatever it borrows is let go before
      // the branch rather than accumulating in the enclosing statement's region.
      pushTemps()
      val c = genExpr(cond)
      popTemps()
      emitTerm(s"br i1 $c, label %$bodyL, label %$endL")
      emitLabel(bodyL)
      pushOwned()
      whileBody.foreach(genStmt)
      popOwned()
      emitTerm(s"br label %$condL")
      emitLabel(endL)

    case TFor(name, ty, lo, hi, inclusive, forBody) =>
      val w     = ty.llvm
      val loV   = genExpr(lo)
      val hiV   = genExpr(hi)
      val condL = freshLabel("for.cond")
      val bodyL = freshLabel("for.body")
      val endL  = freshLabel("for.end")
      emitAlloca(s"%$name.addr", w)
      emit(s"store $w $loV, ptr %$name.addr")
      emitTerm(s"br label %$condL")
      emitLabel(condL)
      val iv  = freshTemp(); emit(s"$iv = load $w, ptr %$name.addr")
      val cmp = freshTemp(); emit(s"$cmp = icmp ${predicate(if inclusive then "<=" else "<", ty)} $w $iv, $hiV")
      emitTerm(s"br i1 $cmp, label %$bodyL, label %$endL")
      emitLabel(bodyL)
      pushOwned()
      forBody.foreach(genStmt)
      popOwned()
      val cur = freshTemp(); emit(s"$cur = load $w, ptr %$name.addr")
      val nxt = freshTemp(); emit(s"$nxt = add $w $cur, 1")
      emit(s"store $w $nxt, ptr %$name.addr")
      emitTerm(s"br label %$condL")
      emitLabel(endL)

    case TReturn(opt) =>
      opt match
        case Some(t) =>
          val v = genExpr(t)
          retainValue(t.ty, v)
          releaseAll()
          emitTerm(s"ret ${t.ty.llvm} $v")
        case None =>
          releaseAll()
          emitTerm("ret void")

  // --- expressions ---------------------------------------------------------------------

  /** Lowers an expression, returning the register or immediate holding its value (empty for a
   * unit-typed expression, whose value is never read).
   */
  private def genExpr(expr: TExpr): String = expr match
    case TIntLit(v, _) => v.toString
    case TStrLit(s)    => stringGlobal(s)
    case TBoolLit(b)   => if b then "1" else "0"
    case TNullLit(_)   => "null"
    case TUnitLit()    => ""

    // A narrower float is the `double` constant rounded to it, which folds away entirely.
    case TFloatLit(bits, ty) =>
      if ty == Type.Real then bits
      else { val r = freshTemp(); emit(s"$r = fptrunc double $bits to ${ty.llvm}"); r }

    case TCast(operand, ty) =>
      convert(operand.ty, ty, genExpr(operand))

    case TLoad(name, ty) =>
      val r = freshTemp(); emit(s"$r = load ${ty.llvm}, ptr %$name.addr"); r

    case TDeref(operand, ty) =>
      val p = payloadAddr(operand)
      val r = freshTemp(); emit(s"$r = load ${ty.llvm}, ptr $p"); r

    case TAddrOf(place, _) =>
      address(place)

    case TBox(value, refTy) =>
      genBox(value, refTy)

    case TStore(place, value, ty) =>
      val v = genExpr(value)
      val p = address(place)
      if containsRef(ty) then
        // The new value is retained before the old is released, so assigning something to
        // itself does not briefly drop the last count.
        val old = freshTemp(); emit(s"$old = load ${ty.llvm}, ptr $p")
        retainValue(ty, v)
        emit(s"store ${ty.llvm} $v, ptr $p")
        releaseValue(ty, old)
      else emit(s"store ${ty.llvm} $v, ptr $p")
      v

    case TUpdate(place, op, value, ty) =>
      val p       = address(place)
      val cur     = freshTemp(); emit(s"$cur = load ${ty.llvm}, ptr $p")
      val v       = genExpr(value)
      val updated = arith(op.dropRight(1), ty, cur, v)
      emit(s"store ${ty.llvm} $updated, ptr $p")
      updated

    case TIncDec(place, op, pre, ty) =>
      val w   = ty.llvm
      val p   = address(place)
      val cur = freshTemp(); emit(s"$cur = load $w, ptr $p")
      val nv  = freshTemp(); emit(s"$nv = ${if op == "++" then "add" else "sub"} $w $cur, 1")
      emit(s"store $w $nv, ptr $p")
      if pre then nv else cur

    case TBinary(op, l, r, _) =>
      arith(op, l.ty, genExpr(l), genExpr(r))

    case TUnary("-", operand, ty: Type.Integer) =>
      val v = genExpr(operand); val r = freshTemp(); emit(s"$r = sub ${ty.llvm} 0, $v"); r
    case TUnary("-", operand, ty: Type.Floating) =>
      val v = genExpr(operand); val r = freshTemp(); emit(s"$r = fneg ${ty.llvm} $v"); r
    case TUnary("!", operand, _) =>
      val v = genExpr(operand); val r = freshTemp(); emit(s"$r = xor i1 $v, true"); r
    case TUnary("~", operand, ty) =>
      val v = genExpr(operand); val r = freshTemp(); emit(s"$r = xor ${ty.llvm} $v, -1"); r
    case TUnary(op, _, _) =>
      sys.error(s"unreachable unary '$op'")

    case TLogical(op, l, r) =>
      val lv = genExpr(l); val rv = genExpr(r); val res = freshTemp()
      emit(s"$res = ${if op == "&&" then "and" else "or"} i1 $lv, $rv"); res

    case TCompare(operands, ops) =>
      val cmps = ops.indices.map(i => compareOne(ops(i), operands(i), operands(i + 1))).toList
      cmps.reduce { (a, b) => val r = freshTemp(); emit(s"$r = and i1 $a, $b"); r }

    case TPrint(args) =>
      genPrint(args); ""

    case TCall(name, args, ty) =>
      val argVals = args.map(a => s"${a.ty.llvm} ${genExpr(a)}")
      if ty == Type.Unit then
        emit(s"call void @$name(${argVals.mkString(", ")})"); ""
      else
        val r = freshTemp(); emit(s"$r = call ${ty.llvm} @$name(${argVals.mkString(", ")})")
        ownTemp(r, ty)

    case TStructNew(struct, args) =>
      val vals = args.map(genExpr)
      var acc  = "undef"
      for (v, i) <- vals.zipWithIndex do
        val r = freshTemp()
        emit(s"$r = insertvalue ${struct.llvm} $acc, ${struct.fields(i)._2.llvm} $v, $i")
        acc = r
      acc

    case TEnumNew(en, variant, args) =>
      enumValue(en, variant, args.map(genExpr))

    case TTry(operand, ok, fail, retEnum, retFail, _) =>
      genTry(operand, ok, fail, retEnum, retFail)

    case TField(receiver, index, ty) =>
      val rv = genExpr(receiver); val r = freshTemp()
      emit(s"$r = extractvalue ${receiver.ty.llvm} $rv, $index"); r

    case TIf(cond, thenBlock, elseBlock, ty) =>
      genIf(cond, thenBlock, elseBlock, ty)

    case TMatch(scrutinee, arms, ty) =>
      genMatch(scrutinee, arms, ty)

  /** The address of a place, as a `ptr` register or an existing slot name. Every place bottoms
   * out either in a local's stack slot or in a pointer the program already holds, so this walks
   * the field chain with `getelementptr` rather than reading values out with `extractvalue`.
   */
  private def address(place: TExpr): String = place match
    case TLoad(name, _)     => s"%$name.addr"
    case TDeref(operand, _) => payloadAddr(operand)
    case TField(receiver, index, _) =>
      val base = address(receiver)
      val r    = freshTemp()
      emit(s"$r = getelementptr ${receiver.ty.llvm}, ptr $base, i32 0, i32 $index")
      r
    case other => sys.error(s"unreachable address of ${other.getClass.getSimpleName}")

  /** Where a pointer's pointee actually lives. A `*T` addresses its value directly; a `&T`
   * addresses the box, whose payload sits after the refcount and the deallocation hook.
   */
  private def payloadAddr(operand: TExpr): String = {
    val p = genExpr(operand)

    operand.ty match
      case Type.Ref(inner, _) =>
        val r = freshTemp()
        emit(s"$r = getelementptr ${boxName(inner)}, ptr $p, i32 0, i32 2")
        r
      case _ => p
  }

  /** Puts a value on the heap: one count for the reference this yields, the default hook, and
   * a copy of the payload — whose own references the box now holds a share of.
   */
  private def genBox(value: TExpr, refTy: Type.Ref): String = {
    val inner = refTy.inner
    val bn    = boxName(inner)
    val v     = genExpr(value)

    val end  = freshTemp(); emit(s"$end = getelementptr $bn, ptr null, i32 1")
    val size = freshTemp(); emit(s"$size = ptrtoint ptr $end to i64")
    val p    = freshTemp(); emit(s"$p = call ptr @malloc(i64 $size)")

    emit(s"store i64 1, ptr $p")
    val hook = freshTemp(); emit(s"$hook = getelementptr $bn, ptr $p, i32 0, i32 1")
    emit(s"store ptr @arc.free, ptr $hook")

    val slot = freshTemp(); emit(s"$slot = getelementptr $bn, ptr $p, i32 0, i32 2")
    retainValue(inner, v)
    emit(s"store ${inner.llvm} $v, ptr $slot")

    ownTemp(p, refTy)
  }

  /** Builds an enum value from already-lowered payload values: the tag, then the variant's
   * payload aggregate dropped into its slot.
   */
  private def enumValue(en: Type.Enum, variant: Type.EnumVariant, vals: List[String]): String =
    if en.simple then variant.tag.toString
    else
      val tagged = freshTemp()
      emit(s"$tagged = insertvalue ${en.llvm} undef, i32 ${variant.tag}, 0")
      variant.payloadSlot match
        case None => tagged
        case Some(slot) =>
          var payload = "undef"
          for (v, i) <- vals.zipWithIndex do
            val r = freshTemp()
            emit(s"$r = insertvalue ${en.payloadLlvm(variant)} $payload, ${variant.fields(i)._2.llvm} $v, $i")
            payload = r
          val r = freshTemp()
          emit(s"$r = insertvalue ${en.llvm} $tagged, ${en.payloadLlvm(variant)} $payload, $slot")
          r

  /** Reads every field of a variant's payload out of an enum value. */
  private def payloadFields(en: Type.Enum, variant: Type.EnumVariant, value: String): List[String] =
    variant.payloadSlot match
      case None => Nil
      case Some(slot) =>
        val p = freshTemp()
        emit(s"$p = extractvalue ${en.llvm} $value, $slot")
        variant.fields.indices.map { i =>
          val f = freshTemp()
          emit(s"$f = extractvalue ${en.payloadLlvm(variant)} $p, $i")
          f
        }.toList

  /** `expr?` — on success the payload becomes the expression's value; on failure the function
   * returns immediately with the failure re-wrapped in its own return type, carrying the error
   * payload across unchanged.
   */
  private def genTry(
      operand: TExpr,
      ok: Type.EnumVariant,
      fail: Type.EnumVariant,
      retEnum: Type.Enum,
      retFail: Type.EnumVariant,
  ): String = {
    val en = operand.ty.asInstanceOf[Type.Enum]
    val v  = genExpr(operand)

    val tag  = freshTemp(); emit(s"$tag = extractvalue ${en.llvm} $v, 0")
    val isOk = freshTemp(); emit(s"$isOk = icmp eq i32 $tag, ${ok.tag}")

    val okL   = freshLabel("try.ok")
    val failL = freshLabel("try.fail")
    emitTerm(s"br i1 $isOk, label %$okL, label %$failL")

    emitLabel(failL)
    val failed = enumValue(retEnum, retFail, payloadFields(en, fail, v))
    retainValue(retEnum, failed)
    releaseAll()
    emitTerm(s"ret ${retEnum.llvm} $failed")

    emitLabel(okL)
    payloadFields(en, ok, v).head
  }

  private def genIf(cond: TExpr, thenBlock: TBlock, elseBlock: Option[TBlock], ty: Type): String = {
    val c      = genExpr(cond)
    val thenL  = freshLabel("if.then")
    val elseL  = freshLabel("if.else")
    val endL   = freshLabel("if.end")
    val target = if elseBlock.isDefined then elseL else endL

    if ty == Type.Unit then
      emitTerm(s"br i1 $c, label %$thenL, label %$target")
      emitLabel(thenL)
      genBlockVoid(thenBlock)
      emitTerm(s"br label %$endL")
      elseBlock.foreach { eb =>
        emitLabel(elseL)
        genBlockVoid(eb)
        emitTerm(s"br label %$endL")
      }
      emitLabel(endL)
      ""
    else
      val slot = emitAlloca(freshTemp(), ty.llvm)
      emitTerm(s"br i1 $c, label %$thenL, label %$elseL")
      emitLabel(thenL)
      emit(s"store ${ty.llvm} ${genBlockValue(thenBlock)}, ptr $slot")
      emitTerm(s"br label %$endL")
      emitLabel(elseL)
      emit(s"store ${ty.llvm} ${genBlockValue(elseBlock.get)}, ptr $slot")
      emitTerm(s"br label %$endL")
      emitLabel(endL)
      // Each branch handed its value over with a count taken, so what the merge loads is the
      // one temporary the enclosing region has to let go of.
      val r = freshTemp(); emit(s"$r = load ${ty.llvm}, ptr $slot"); ownTemp(r, ty)
  }

  private def genMatch(scrutinee: TExpr, arms: List[TArm], ty: Type): String = {
    val sv   = genExpr(scrutinee)
    val sty  = scrutinee.ty
    val endL = freshLabel("match.end")
    val slot = if ty == Type.Unit then "" else emitAlloca(freshTemp(), ty.llvm)

    for arm <- arms do
      val bodyL = freshLabel("match.arm")
      val nextL = freshLabel("match.next")
      val patCond =
        arm.patterns.map(patternTest(_, sty, sv)).reduce(orI1)

      // Bindings are established only after the pattern matches, and a guard may reference them,
      // so a guarded arm branches first on the pattern, then binds, then tests the guard.
      // Only a single (non-alternative) pattern may bind.
      def bind(): Unit = if arm.patterns.length == 1 then patternBind(arm.patterns.head, sty, sv)

      arm.guard match
        case None =>
          emitTerm(s"br i1 $patCond, label %$bodyL, label %$nextL")
          emitLabel(bodyL)
          pushOwned()
          bind()
        case Some(g) =>
          val guardL = freshLabel("match.guard")
          emitTerm(s"br i1 $patCond, label %$guardL, label %$nextL")
          emitLabel(guardL)
          pushOwned()
          bind()
          pushTemps()
          val gv = genExpr(g)
          popTemps()
          // A guard that fails leaves an arm whose bindings were already made, so they are
          // given back before falling through to the next one.
          val unbindL = freshLabel("match.unbind")
          emitTerm(s"br i1 $gv, label %$bodyL, label %$unbindL")
          emitLabel(unbindL)
          releaseOwned()
          emitTerm(s"br label %$nextL")
          emitLabel(bodyL)

      if ty == Type.Unit then genBlockVoid(arm.body)
      else emit(s"store ${ty.llvm} ${genBlockValue(arm.body)}, ptr $slot")
      popOwned()
      emitTerm(s"br label %$endL")
      emitLabel(nextL)

    // Fallthrough with no matching arm: a value or enum match is exhaustive (the analyzer
    // required full coverage or a catch-all), so this point is unreachable; a plain scalar
    // statement match simply proceeds.
    if ty == Type.Unit then emitTerm(s"br label %$endL") else emitTerm("unreachable")
    emitLabel(endL)
    if ty == Type.Unit then ""
    else { val r = freshTemp(); emit(s"$r = load ${ty.llvm}, ptr $slot"); ownTemp(r, ty) }
  }

  /** The i1 result of testing a pattern against a value of type `ty`. Pattern tests are pure
   * value reads (`extractvalue`, comparisons), so nested variant fields are extracted and
   * tested unconditionally — a failed outer tag simply ANDs a `false` through.
   */
  private def patternTest(p: TPattern, ty: Type, value: String): String = p match
    case _: TWildPattern | _: TBindPattern => "true"
    case TLitPattern(v)                    => compareValue("==", v.ty, value, genExpr(v))
    case TRangePattern(lo, hi, inclusive) =>
      val loOk = compareValue(">=", lo.ty, value, genExpr(lo))
      val hiOk = compareValue(if inclusive then "<=" else "<", hi.ty, value, genExpr(hi))
      andI1(loOk, hiOk)
    case TVariantPattern(en, variant, args) =>
      val tagVal =
        if en.simple then value
        else { val t = freshTemp(); emit(s"$t = extractvalue ${en.llvm} $value, 0"); t }
      val tagOk = freshTemp(); emit(s"$tagOk = icmp eq i32 $tagVal, ${variant.tag}")
      if args.isEmpty then tagOk
      else
        val payload = freshTemp()
        emit(s"$payload = extractvalue ${en.llvm} $value, ${variant.payloadSlot.get}")
        args.zipWithIndex.foldLeft(tagOk) { case (acc, (arg, i)) =>
          val fv = freshTemp(); emit(s"$fv = extractvalue ${en.payloadLlvm(variant)} $payload, $i")
          andI1(acc, patternTest(arg, variant.fields(i)._2, fv))
        }

  /** Establishes the bindings a pattern introduces, once its arm has been taken. Only binding
   * and (nested) variant patterns carry bindings; the rest are no-ops.
   */
  private def patternBind(p: TPattern, ty: Type, value: String): Unit = p match
    case TBindPattern(name, bty) =>
      emitAlloca(s"%$name.addr", bty.llvm)
      retainValue(bty, value)
      emit(s"store ${bty.llvm} $value, ptr %$name.addr")
      ownSlot(name, bty)
    case TVariantPattern(en, variant, args) if args.exists(bindsAny) =>
      val payload = freshTemp()
      emit(s"$payload = extractvalue ${en.llvm} $value, ${variant.payloadSlot.get}")
      for (arg, i) <- args.zipWithIndex do
        val fv = freshTemp(); emit(s"$fv = extractvalue ${en.payloadLlvm(variant)} $payload, $i")
        patternBind(arg, variant.fields(i)._2, fv)
    case _ => ()

  private def bindsAny(p: TPattern): Boolean = p match
    case _: TBindPattern    => true
    case v: TVariantPattern => v.args.exists(bindsAny)
    case _                  => false

  /** ANDs / ORs two i1 values, folding away the `"true"` immediate a trivially-true pattern
   * produces so the emitted condition stays readable.
   */
  private def andI1(a: String, b: String): String =
    if a == "true" then b
    else if b == "true" then a
    else { val r = freshTemp(); emit(s"$r = and i1 $a, $b"); r }

  private def orI1(a: String, b: String): String =
    if a == "true" || b == "true" then "true"
    else { val r = freshTemp(); emit(s"$r = or i1 $a, $b"); r }

  private def genBlockVoid(b: TBlock): Unit = {
    pushTemps()
    pushOwned()
    b.stmts.foreach(genStmt)
    b.result.foreach(genExpr)
    popOwned()
    popTemps()
  }

  /** A branch's value, handed out with a count of its own. The block's locals and temporaries
   * are released before control leaves it — that is what keeps every release site dominating
   * the value it releases — so the result is retained first and becomes the caller's to let go.
   */
  private def genBlockValue(b: TBlock): String = {
    pushTemps()
    pushOwned()
    b.stmts.foreach(genStmt)
    val v = genExpr(b.result.get)
    retainValue(b.result.get.ty, v)
    popOwned()
    popTemps()
    v
  }

  // --- arithmetic and comparison -------------------------------------------------------

  /** Arithmetic wraps at the operand's declared width, which plain LLVM integer instructions
   * already do — the width is in the type, so no masking is needed even for an odd one.
   * Signedness picks between the division, remainder, and right-shift pairs.
   */
  private def arith(op: String, ty: Type, lv: String, rv: String): String = {
    val instr = ty match
      case i: Type.Integer =>
        op match
          case "+"  => "add"
          case "-"  => "sub"
          case "*"  => "mul"
          case "/"  => if i.signed then "sdiv" else "udiv"
          case "%"  => if i.signed then "srem" else "urem"
          case "<<" => "shl"
          case ">>" => if i.signed then "ashr" else "lshr"
          case "&"  => "and"
          case "|"  => "or"
          case "^"  => "xor"
          case _    => sys.error(s"unreachable arith '$op'")
      case _: Type.Floating =>
        op match
          case "+" => "fadd"
          case "-" => "fsub"
          case "*" => "fmul"
          case "/" => "fdiv"
          case _   => sys.error(s"unreachable arith '$op'")
      case other => sys.error(s"unreachable arith on ${other.llvm}")

    val r = freshTemp(); emit(s"$r = $instr ${ty.llvm} $lv, $rv"); r
  }

  private def compareOne(op: String, a: TExpr, b: TExpr): String =
    compareValue(op, a.ty, genExpr(a), genExpr(b))

  /** The `icmp` / `fcmp` predicate for an operator at a type. `char` compares by scalar
   * value, so it uses the unsigned predicates over its `i32` representation.
   */
  private def predicate(op: String, ty: Type): String = ty match
    // Equality only: a bool and an address have no ordering, so no signed/unsigned choice.
    case Type.Bool | _: Type.Ptr | _: Type.Ref =>
      op match
        case "==" => "eq"; case "!=" => "ne"
        case _    => sys.error(s"unreachable compare '$op'")
    case Type.Char | Type.Integer(_, false, _) =>
      op match
        case "==" => "eq"; case "!=" => "ne"
        case "<"  => "ult"; case ">" => "ugt"; case "<=" => "ule"; case ">=" => "uge"
        case _    => sys.error(s"unreachable compare '$op'")
    case _: Type.Integer =>
      op match
        case "==" => "eq"; case "!=" => "ne"
        case "<"  => "slt"; case ">" => "sgt"; case "<=" => "sle"; case ">=" => "sge"
        case _    => sys.error(s"unreachable compare '$op'")
    case _: Type.Floating =>
      op match
        case "==" => "oeq"; case "!=" => "one"
        case "<"  => "olt"; case ">" => "ogt"; case "<=" => "ole"; case ">=" => "oge"
        case _    => sys.error(s"unreachable compare '$op'")
    case other => sys.error(s"unreachable compare on ${other.llvm}")

  private def compareValue(op: String, ty: Type, av: String, bv: String): String = {
    val instr = if ty.isInstanceOf[Type.Floating] then "fcmp" else "icmp"
    val r     = freshTemp()

    emit(s"$r = $instr ${predicate(op, ty)} ${ty.llvm} $av, $bv")
    r
  }

  // --- conversions ---------------------------------------------------------------------

  /** Lowers an explicit scalar conversion. Every case is a single LLVM cast, except the
   * partial `char(u)` — the one conversion that can fail, and so the one that checks.
   */
  private def convert(from: Type, to: Type, v: String): String = (from, to) match
    case _ if from == to => v

    case (a: Type.Integer, b: Type.Integer) =>
      if b.bits == a.bits then v
      else if b.bits < a.bits then castOp("trunc", a, b, v)
      else castOp(if a.signed then "sext" else "zext", a, b, v)

    case (a: Type.Integer, b: Type.Floating)  => castOp(if a.signed then "sitofp" else "uitofp", a, b, v)
    case (a: Type.Floating, b: Type.Integer)  => castOp(if b.signed then "fptosi" else "fptoui", a, b, v)
    case (a: Type.Floating, b: Type.Floating) => castOp(if b.bits > a.bits then "fpext" else "fptrunc", a, b, v)

    case (Type.Char, b: Type.Integer) => convert(Type.Integer(32, signed = false), b, v)
    case (a: Type.Integer, Type.Char) => checkedChar(a, v)

    case _ => sys.error(s"unreachable conversion from ${from.llvm} to ${to.llvm}")

  private def castOp(instr: String, from: Type, to: Type, v: String): String = {
    val r = freshTemp(); emit(s"$r = $instr ${from.llvm} $v to ${to.llvm}"); r
  }

  /** `char(u)` — a checked conversion. A Unicode scalar value is at most `0x10FFFF` and never
   * a surrogate; anything else traps, in the same runtime-safety category as a bounds check.
   * The test runs at 64 bits so a wide source cannot smuggle a value past it.
   */
  private def checkedChar(from: Type.Integer, v: String): String = {
    traps = true

    val wide     = convert(from, Type.Integer(64, from.signed), v)
    val inRange  = freshTemp(); emit(s"$inRange = icmp ule i64 $wide, 1114111")
    val belowLow = freshTemp(); emit(s"$belowLow = icmp ult i64 $wide, 55296")
    val aboveTop = freshTemp(); emit(s"$aboveTop = icmp ugt i64 $wide, 57343")
    val scalar   = freshTemp(); emit(s"$scalar = or i1 $belowLow, $aboveTop")
    val ok       = freshTemp(); emit(s"$ok = and i1 $inRange, $scalar")

    val okL   = freshLabel("char.ok")
    val badL  = freshLabel("char.bad")
    emitTerm(s"br i1 $ok, label %$okL, label %$badL")
    emitLabel(badL)
    emit("call void @llvm.trap()")
    emitTerm("unreachable")
    emitLabel(okL)

    castOp("trunc", Type.Integer(64, from.signed), Type.Char, wide)
  }

  // --- print ---------------------------------------------------------------------------

  private def genPrint(args: List[TExpr]): Unit = {
    val specs    = mutable.ListBuffer.empty[String]
    val callArgs = mutable.ListBuffer.empty[String]

    for arg <- args do
      arg.ty match
        // Varargs promote, so a narrow value is widened here rather than left to the ABI.
        case i: Type.Integer =>
          val wide = if i.bits <= 32 then Type.Integer(32, i.signed) else Type.Integer(64, i.signed)
          val v    = convert(i, wide, genExpr(arg))
          specs += (if i.signed then (if wide.bits == 32 then "%d" else "%lld")
                    else if wide.bits == 32 then "%u"
                    else "%llu")
          callArgs += s"${wide.llvm} $v"

        case f: Type.Floating =>
          val v = convert(f, Type.Real, genExpr(arg))
          specs += "%g"; callArgs += s"double $v"

        case Type.Char =>
          charBuf = true
          val cp  = genExpr(arg)
          val buf = emitAlloca(freshTemp(), "[5 x i8]")
          val enc = freshTemp()
          emit(s"$enc = call ptr @sysl.utf8(i32 $cp, ptr $buf)")
          specs += "%s"; callArgs += s"ptr $enc"

        case Type.Str => specs += "%s"; callArgs += s"ptr ${genExpr(arg)}"

        case Type.Bool =>
          boolStrs = true
          val v   = genExpr(arg)
          val sel = freshTemp()
          emit(s"$sel = select i1 $v, ptr @.true, ptr @.false")
          specs += "%s"; callArgs += s"ptr $sel"

        case other => sys.error(s"unreachable print of ${other.llvm}")

    val fmt = stringGlobal(specs.mkString(" ") + "\n")
    val r   = freshTemp()
    emit(s"$r = call i32 (ptr, ...) @printf(${(s"ptr $fmt" :: callArgs.toList).mkString(", ")})")
  }
}

object Codegen {

  /** Lowers a typed program to an LLVM IR module. */
  def generate(program: TProgram): String = new Codegen(program).gen()

  /** The part of ARC that is the same for every type: taking a share of a box, and the default
   * deallocation hook. Taking a share needs no ordering — a count you already hold cannot reach
   * zero underneath you — so the atomic form is a relaxed increment. Giving one back *is*
   * per-type, since reaching zero runs that type's destructor.
   */
  private val arcRuntime: String =
    """define private void @arc.retain(ptr %p) {
      |entry:
      |  %c = load i64, ptr %p
      |  %n = add i64 %c, 1
      |  store i64 %n, ptr %p
      |  ret void
      |}
      |
      |define private void @arc.retain_sync(ptr %p) {
      |entry:
      |  %o = atomicrmw add ptr %p, i64 1 monotonic
      |  ret void
      |}
      |
      |define private void @arc.free(ptr %p) {
      |entry:
      |  call void @free(ptr %p)
      |  ret void
      |}
      |
      |""".stripMargin

  /** Encodes one Unicode scalar value as NUL-terminated UTF-8 into a caller-supplied
   * five-byte buffer, so printing a `char` is an ordinary `%s` argument alongside the rest.
   * Emitted only into modules that print one.
   */
  private val utf8Encoder: String =
    """define private ptr @sysl.utf8(i32 %cp, ptr %buf) {
      |entry:
      |  %ascii = icmp ult i32 %cp, 128
      |  br i1 %ascii, label %one, label %wide
      |one:
      |  %a0 = trunc i32 %cp to i8
      |  store i8 %a0, ptr %buf
      |  %a1 = getelementptr i8, ptr %buf, i32 1
      |  store i8 0, ptr %a1
      |  ret ptr %buf
      |wide:
      |  %short = icmp ult i32 %cp, 2048
      |  br i1 %short, label %two, label %wider
      |two:
      |  %b0 = lshr i32 %cp, 6
      |  %b1 = or i32 %b0, 192
      |  %b2 = trunc i32 %b1 to i8
      |  store i8 %b2, ptr %buf
      |  %b3 = and i32 %cp, 63
      |  %b4 = or i32 %b3, 128
      |  %b5 = trunc i32 %b4 to i8
      |  %b6 = getelementptr i8, ptr %buf, i32 1
      |  store i8 %b5, ptr %b6
      |  %b7 = getelementptr i8, ptr %buf, i32 2
      |  store i8 0, ptr %b7
      |  ret ptr %buf
      |wider:
      |  %bmp = icmp ult i32 %cp, 65536
      |  br i1 %bmp, label %three, label %four
      |three:
      |  %c0 = lshr i32 %cp, 12
      |  %c1 = or i32 %c0, 224
      |  %c2 = trunc i32 %c1 to i8
      |  store i8 %c2, ptr %buf
      |  %c3 = lshr i32 %cp, 6
      |  %c4 = and i32 %c3, 63
      |  %c5 = or i32 %c4, 128
      |  %c6 = trunc i32 %c5 to i8
      |  %c7 = getelementptr i8, ptr %buf, i32 1
      |  store i8 %c6, ptr %c7
      |  %c8 = and i32 %cp, 63
      |  %c9 = or i32 %c8, 128
      |  %c10 = trunc i32 %c9 to i8
      |  %c11 = getelementptr i8, ptr %buf, i32 2
      |  store i8 %c10, ptr %c11
      |  %c12 = getelementptr i8, ptr %buf, i32 3
      |  store i8 0, ptr %c12
      |  ret ptr %buf
      |four:
      |  %d0 = lshr i32 %cp, 18
      |  %d1 = or i32 %d0, 240
      |  %d2 = trunc i32 %d1 to i8
      |  store i8 %d2, ptr %buf
      |  %d3 = lshr i32 %cp, 12
      |  %d4 = and i32 %d3, 63
      |  %d5 = or i32 %d4, 128
      |  %d6 = trunc i32 %d5 to i8
      |  %d7 = getelementptr i8, ptr %buf, i32 1
      |  store i8 %d6, ptr %d7
      |  %d8 = lshr i32 %cp, 6
      |  %d9 = and i32 %d8, 63
      |  %d10 = or i32 %d9, 128
      |  %d11 = trunc i32 %d10 to i8
      |  %d12 = getelementptr i8, ptr %buf, i32 2
      |  store i8 %d11, ptr %d12
      |  %d13 = and i32 %cp, 63
      |  %d14 = or i32 %d13, 128
      |  %d15 = trunc i32 %d14 to i8
      |  %d16 = getelementptr i8, ptr %buf, i32 3
      |  store i8 %d15, ptr %d16
      |  %d17 = getelementptr i8, ptr %buf, i32 4
      |  store i8 0, ptr %d17
      |  ret ptr %buf
      |}
      |
      |""".stripMargin
}
