package io.github.edadma.sysl

import scala.collection.mutable

/** Lowers a typed program (`TProgram`) to a textual LLVM IR module.
 *
 * Codegen makes no semantic decisions — the analyzer has already resolved every name, fixed
 * every type, and checked every rule. This pass is a straight translation: it selects
 * instructions from the types the tree carries and lays out basic blocks. Opaque pointers and
 * hex-double float constants keep the output stable across the textual round-trip.
 *
 * The work is split across traits the way the analyzer's is: `Emitter` holds the buffers, block
 * state, and name counters, `ArcEmitter` the ownership runtime, `StringEmitter` and `ScalarEmitter`
 * the two ends of the value world, `PlaceEmitter` addressing and composite values,
 * `ControlFlowEmitter` everything that makes a basic block, `StaticEmitter` the module-level storage
 * that is laid down before any block exists, `ForeignEmitter` the C boundary, `ContractEmitter`
 * everything that traps, `EnumAttrEmitter` the simple-enum attributes, and `ExprEmitter` the
 * expression dispatch they all call back into.
 *
 * What stays here is the spine, which is the part that needs the whole program rather than one
 * expression: the module's assembly in declaration order, the two function forms, and statements —
 * the only level at which a temporary's lifetime is decided.
 */
class Codegen private (protected val program: TProgram, promotions: Escape.Promotions,
                       protected val target: Target)
    extends ExprEmitter {

  // --- module --------------------------------------------------------------------------

  private def gen(): String = {
    // A function a library already compiled is declared, not defined: its body is in the object file
    // the library shipped, and emitting it again would be a duplicate symbol at the link.
    val (imported, own) = program.funcs.partition(f => program.precompiled(f.name))
    val funcTexts       = own.map(genFunction)
    // A library has no entry point. Emitting one would put a second `main` in every program that
    // linked against it, which the linker reports as a duplicate symbol and nothing else explains.
    val mainText =
      if !program.entryPoint then ""
      else if program.tests.nonEmpty then genTestMain(program.vals, program.tests)
      else genMain(program.vals, program.main, program.entry)
    // The tables come after the bodies because a table only exists for a type something erased, and
    // it may ask for an adapter, which the queue below is what emits.
    val vtableText = program.vtables.map(genVtable).mkString

    // Emitting a runtime helper may ask for another (a destructor releases the references its
    // payload holds), so this runs until nothing new is requested.
    val runtimeTexts = mutable.ListBuffer.empty[String]
    while runtimeQueue.nonEmpty do runtimeTexts += runtimeQueue.dequeue()()

    val out = new mutable.StringBuilder

    // The module says which machine it is for, which is what makes an invocation naming a target
    // mean anything downstream: LLVM derives the data layout from the triple, so stating it is also
    // what keeps a module built for one machine from being read as a module for whatever read it.
    out ++= s"target triple = \"${target.triple}\"\n\n"

    if traps then out ++= "declare void @llvm.trap()\n"
    if heap then
      out ++= "declare ptr @malloc(i64)\n"
      out ++= "declare void @free(ptr)\n"
    if checked then
      out ++= "declare { i64, i1 } @llvm.umul.with.overflow.i64(i64, i64)\n"
      out ++= "declare { i64, i1 } @llvm.uadd.with.overflow.i64(i64, i64)\n"
    if usesSnprintf then out ++= "declare i32 @snprintf(ptr, i64, ptr, ...)\n"
    // The test dispatcher's one dependency, and the only thing in a test build that is not also in an
    // ordinary one. It is declared from the shape of the program rather than from a flag set while
    // emitting, because the entry point is emitted after this line runs.
    if program.entryPoint && program.tests.nonEmpty then out ++= "declare i32 @strcmp(ptr, ptr)\n"
    if usesVarargs then
      out ++= "declare void @llvm.va_start.p0(ptr)\n"
      out ++= "declare void @llvm.va_end.p0(ptr)\n"
    if usesVaCopy then out ++= "declare void @llvm.va_copy.p0(ptr, ptr)\n"
    if usesMemcpy then out ++= "declare void @llvm.memcpy.p0.p0.i64(ptr, ptr, i64, i1)\n"

    // An `extern` the program calls is declared here, unless the symbol is already declared — by the
    // runtime, whose own spelling of `malloc` is the one its code calls, or by an earlier `extern`,
    // since two declarations may name one symbol under different sysl names. A module may not
    // declare one symbol twice.
    val declared = mutable.Set("llvm.trap") ++
      (if heap then Set("malloc", "free") else Set.empty) ++
      (if usesSnprintf then Set("snprintf") else Set.empty) ++
      (if program.entryPoint && program.tests.nonEmpty then Set("strcmp") else Set.empty)

    // An aggregate does not cross to a foreign callee as itself: the convention the other side was
    // compiled against says which registers it arrives in, so that is what the declaration names
    // (`ForeignEmitter`).
    for e <- program.externs if declared.add(e.symbol) do
      val (ret, params) = foreignSignature(e.retTy, e.params, e.variadic)
      out ++= s"declare $ret @${e.symbol}(${params.mkString(", ")})\n"

    for d <- satDecls do out ++= d + "\n"
    out ++= "\n"

    // A zero-sized field is skipped: the aggregate is what occupies storage, and `Type.slot` is what
    // keeps every index that reaches into it agreeing with what was laid down here.
    for s <- program.structs do
      out ++= s"${s.llvm} = type { ${s.stored.map(_._2.llvm).mkString(", ")} }\n"
    if program.structs.nonEmpty then out ++= "\n"

    // A data enum is the tag and **one** payload region, wide enough and aligned for whichever
    // variant needs the most (`09 §3`). Each variant's own payload keeps its named aggregate type,
    // which is what a construction stores into that region and what a match reads back out of it.
    for e <- program.enums do
      for v <- e.variants if v.carries do
        out ++= s"${e.payloadLlvm(v)} = type { ${v.stored.map(_._2.llvm).mkString(", ")} }\n"
      val (unit, count) = Layout.payloadArea(e)
      out ++= s"${e.llvm} = type { i32, [$count x $unit] }\n"
    if program.enums.nonEmpty then out ++= "\n"

    // A box is the strong count, the function that destroys it, the weak count, and the payload —
    // so ARC works the same everywhere, and an object frees itself into whichever heap made it.
    for (name, payload) <- boxes do
      out ++= s"$name = type { i64, ptr, i64, ${payload.llvm} }\n"
    // A buffer is the same box with the element count in front of elements there may be any number
    // of, so the hook — which is reached with no static type — can still find them all.
    for (name, elem) <- bufs do
      out ++= s"$name = type { i64, ptr, i64, i64, [0 x ${elem.llvm}] }\n"
    if boxes.nonEmpty || bufs.nonEmpty then out ++= "\n"

    // A library's own functions are declared here rather than up with the `extern`s, and it has to be
    // **after** the type definitions: a named struct used before its `= type` line is opaque at that
    // point, and an opaque type cannot be passed by value. Their signatures are built the way the
    // *definition* would have built them rather than through `foreignSignature`, because these are
    // sysl's convention and not C's — getting that wrong passes arguments the other way, which is a
    // corrupt run rather than a link error.
    for f <- imported do
      val params = (Type.stored(f.params).map(_._2.llvm) ++ Option.when(f.variadic)("...")).mkString(", ")
      out ++= s"declare ${f.retTy.llvm} @${symbolOf(f.name)}($params)\n"
    if imported.nonEmpty then out ++= "\n"

    if boolStrs then
      out ++= "@.true = private constant [5 x i8] c\"true\\00\"\n"
      out ++= "@.false = private constant [6 x i8] c\"false\\00\"\n"
    // Storage the linker supplies: the declaration line and nothing else, since this module lays none
    // of it down. It goes here rather than up with the `extern` functions because a named aggregate
    // type is opaque until its `= type` line, and an `external global` naming one has to come after.
    // Two declarations may share one symbol, so the symbol is what is declared once.
    val declaredGlobals = mutable.Set.empty[String]

    for v <- program.externVars if declaredGlobals.add(v.symbol) do
      out ++= s"@${v.symbol} = external global ${v.ty.llvm}\n"
    out ++= genVals(program.vals)
    out ++= globals.toString
    out ++= vtableText
    if globals.nonEmpty || boolStrs || vtableText.nonEmpty || program.vals.nonEmpty ||
      program.externVars.nonEmpty
    then out ++= "\n"

    if charBuf then out ++= ScalarEmitter.utf8Encoder
    if heap then out ++= ArcEmitter.core
    if syncHeap then out ++= ArcEmitter.atomic
    if maybeHeap then out ++= ArcEmitter.maybe
    if weakHeap then out ++= ArcEmitter.weak
    for t <- runtimeTexts do out ++= t; out ++= "\n"

    for t <- funcTexts do out ++= t; out ++= "\n"
    out ++= mainText
    out.toString
  }

  /** `@main`, which is three things in the order the language puts them: the computed `val`s, the
   * program's own top-level statements, and the `main` it declared if it declared one.
   *
   * The `val`s are laid down here rather than in a constructor the platform runs, and that is the
   * whole of the mechanism: the entry point is the one place that certainly runs first and it is
   * already written. A `.init_array` entry would run before the runtime is up, be spelled differently
   * on every target, and put the order somewhere a reader could not see it.
   *
   * Each one gets a region of its own, exactly as a statement does, so whatever its initializer
   * allocated on the way to the value is let go of before the next one starts.
   *
   * **The signature is C's**, whether or not anything asks for the arguments, because this is the
   * function the platform's own start-up code calls and that code passes two values. Taking them here
   * is what lets a declared `main(args: []string)` be handed a slice: the pair is converted by an
   * ordinary library call, and neither `argc` nor `argv` appears in a sysl signature anywhere.
   */
  private def genMain(vals: List[TVal], stmts: List[TStmt], entry: Option[TEntry]): String = {
    startFunction()
    promoted = promotions(None)
    pushTemps()
    pushOwned()

    for v <- vals if v.computed do
      pushTemps()
      emit(s"store ${v.ty.llvm} ${genExpr(v.init)}, ptr @${v.symbol}")
      popTemps()

    stmts.foreach(genStmt)

    // The declared `main` runs in a region of its own, so the slice its arguments came in is released
    // when it returns — the same handover an ordinary call makes, since a callee takes a count for
    // each parameter on entry and gives it back at its own return.
    for e <- entry do
      pushTemps()
      val args = e.argsFn.map { fn =>
        val slice = Type.Slice(Type.Str)
        val r     = freshTemp()

        emit(s"$r = call ${slice.llvm} @$fn(i32 %argc, ptr %argv)")
        s"${slice.llvm} ${ownTemp(r, slice)}"
      }

      emit(s"call void @$entrySymbol(${args.getOrElse("")})")
      popTemps()

    releaseAll()
    emitTerm("ret i32 0")
    finishFunction("define i32 @main(i32 %argc, ptr %argv)")
  }

  /** `@main` for a **test build**: the entry point `sysl test` drives, which runs the one test named
   * on its command line and nothing else (`testing.md`).
   *
   * **One test to a process, chosen from outside.** A test that fails does so by trapping, and a trap
   * takes the process with it — so a runner that called them in a loop would learn the name of the
   * first failure and nothing after it. Dispatching on an argument instead means one compile and one
   * short-lived process per test, and a test's verdict is then the exit status the platform already
   * reports.
   *
   * The status says which of three things happened, and the runner reads all three: `0` for a test
   * that returned, whatever the platform makes of a trap for one that did not, and `2` for a name
   * that matches nothing here — which is a runner and a binary that disagree, not a test result.
   *
   * The computed `val`s are laid down first, exactly as an ordinary entry point lays them down. A
   * test reads a module's storage the same way any other function does, and skipping the
   * initialization here would leave it reading zeros.
   */
  private def genTestMain(vals: List[TVal], tests: List[TTest]): String = {
    startFunction()
    promoted = promotions(None)
    pushTemps()
    pushOwned()

    for v <- vals if v.computed do
      pushTemps()
      emit(s"store ${v.ty.llvm} ${genExpr(v.init)}, ptr @${v.symbol}")
      popTemps()

    val named   = freshLabel("test.named")
    val missing = freshLabel("test.missing")
    val enough  = freshTemp()

    emit(s"$enough = icmp sgt i32 %argc, 1")
    emitTerm(s"br i1 $enough, label %$named, label %$missing")

    emitLabel(named)
    val slot = freshTemp(); emit(s"$slot = getelementptr ptr, ptr %argv, i64 1")
    val want = freshTemp(); emit(s"$want = load ptr, ptr $slot")

    // Each arm compares the wanted name against one test's key and calls it on a match. The keys are
    // interned with the terminator `strcmp` reads to, which an ordinary sysl string constant does not
    // carry: a `string` knows its own length and has never had a use for one.
    for t <- tests do
      val run  = freshLabel("test.run")
      val next = freshLabel("test.next")
      val cmp  = freshTemp(); emit(s"$cmp = call i32 @strcmp(ptr $want, ptr ${stringGlobal(t.func + "\u0000")})")
      val hit  = freshTemp(); emit(s"$hit = icmp eq i32 $cmp, 0")

      emitTerm(s"br i1 $hit, label %$run, label %$next")
      emitLabel(run)
      emit(s"call void @${symbolOf(t.func)}()")
      emitTerm("ret i32 0")
      emitLabel(next)

    // Falling off the end of the arms and arriving with no name at all are the same answer: this
    // binary has no such test. They share a block rather than each getting one, because a runner
    // reading the status cannot tell them apart and there is nothing it would do differently.
    emitTerm(s"br label %$missing")
    emitLabel(missing)
    emitTerm("ret i32 2")

    finishFunction("define i32 @main(i32 %argc, ptr %argv)")
  }

  /** A function owns its parameters and returns its result with a count already taken, so a caller
   * can hand over a temporary and a callee can store one without either having to know what the
   * other did with it.
   */
  private def genFunction(f: TFunc): String = {
    startFunction()
    promoted = promotions(Some(f.name))
    pushTemps()
    pushOwned()
    ensures = f.ensures

    // A zero-sized parameter is not an argument: there is nothing to receive and nothing to keep,
    // so it takes no slot and the emitted signature below does not mention it.
    for (name, ty) <- f.params if !Type.zeroSized(ty) do
      emitAlloca(s"%$name.addr", ty.llvm)
      emit(s"store ${ty.llvm} %$name.param, ptr %$name.addr")
      retainValue(ty, s"%$name.param")
      ownSlot(name, ty)

    for (cond, _) <- f.requires do emitContract(cond, "require")

    // Each `old(e)` snapshots the entry value into a hidden owned slot, exactly as a `var` would,
    // so a postcondition can compare the returned state against the state on entry. The slot is
    // released with every other local at each return.
    for (oldExpr, i) <- f.olds.zipWithIndex do
      pushTemps()
      val v = genExpr(oldExpr)
      emitAlloca(s"%old.$i.addr", oldExpr.ty.llvm)
      retainValue(oldExpr.ty, v)
      emit(s"store ${oldExpr.ty.llvm} $v, ptr %old.$i.addr")
      ownSlot(s"old.$i", oldExpr.ty)
      popTemps()

    f.body.stmts.foreach(genStmt)

    // A `never` result is `void` like `unit`: the body's trailing expression diverges, so it has
    // already terminated the block and the `ret` emitted here is dropped.
    f.body.result match
      case Some(r) if !Type.noValue(f.retTy) =>
        val v = genExpr(r)
        retainValue(f.retTy, v)
        emitEnsures(Some(v))
        releaseAll()
        emitTerm(s"ret ${f.retTy.llvm} $v")
      case Some(r) =>
        genExpr(r); emitEnsures(None); releaseAll(); emitTerm("ret void")
      case None if Type.noValue(f.retTy) =>
        emitEnsures(None); releaseAll(); emitTerm("ret void")
      case None =>
        releaseAll(); emitTerm(s"ret ${f.retTy.llvm} ${zero(f.retTy)}")

    val declared = Type.stored(f.params).map { case (name, ty) => s"${ty.llvm} %$name.param" }
    val params   = (declared ++ Option.when(f.variadic)("...")).mkString(", ")
    // A file-private declaration has every caller in the module that defines it (`13 §2`), so its
    // symbol is `internal`: nothing outside may resolve it, and the linker is free to discard it
    // when nothing inside calls it either — which is what an exported helper in a library artifact
    // costs today.
    val linkage = if f.internal then "internal " else ""
    finishFunction(s"define $linkage${f.retTy.llvm} @${symbolOf(f.name)}($params)")
  }

  // --- statements ----------------------------------------------------------------------

  /** A statement is the region a temporary lives in: whatever it allocated or was handed is
   * released once the statement is over, leaving only what a slot has taken a count of.
   */
  protected def genStmt(stmt: TStmt): Unit = {
    pushTemps()
    genStmtBody(stmt)
    popTemps()
  }

  private def genStmtBody(stmt: TStmt): Unit = stmt match
    // A zero-sized binding is not a slot: the initializer still runs, for its effect, and there is
    // nothing to keep afterwards. Every later read of the name yields nothing in the same way.
    case TVarDecl(_, ty, init) if Type.zeroSized(ty) =>
      genExpr(init)

    // An array a view of which gets out of this frame lives in a buffer instead of a slot, and
    // that is the whole of the difference: the name still means the address of the storage, so the
    // store below and every later use are the ones an unpromoted array gets. What the buffer adds
    // is an owner for the views to count against, and a release when the name goes out of scope.
    case TVarDecl(name, ty @ Type.Array(n, elem), init) if promoted(name) =>
      val v           = genExpr(init)
      val (box, data) = genBuffer(elem, n.toString)

      promotedBoxes(name) = box
      emit(s"%$name.addr = getelementptr ${elem.llvm}, ptr $data, i64 0")
      retainValue(ty, v)
      emit(s"store ${ty.llvm} $v, ptr %$name.addr")
      ownBox(name, box, elem)

    case TVarDecl(name, ty, init) =>
      val v = genExpr(init)
      emitAlloca(s"%$name.addr", ty.llvm)
      retainValue(ty, v)
      emit(s"store ${ty.llvm} $v, ptr %$name.addr")
      ownSlot(name, ty)

    case TExprStmt(expr) =>
      genExpr(expr)

    case TMultiAssign(writes) =>
      genMultiAssign(writes)

    case TReturn(opt) =>
      opt match
        case Some(t) =>
          val v = genExpr(t)
          retainValue(t.ty, v)
          emitEnsures(Some(v))
          releaseAll()
          emitTerm(s"ret ${t.ty.llvm} $v")
        case None =>
          emitEnsures(None)
          releaseAll()
          emitTerm("ret void")

    // A `break`/`continue` leaves the body from the middle, so it unwinds the body's ownership
    // regions before jumping — the same discipline as `return`, bounded to the loop. `break v`
    // hands its value over with a count taken (so it survives the unwind) into the loop's slot.
    case TBreak(opt, depth) =>
      val loop = genLoops(depth)
      opt.foreach { t =>
        val v = genExpr(t)
        retainValue(t.ty, v)
        emit(s"store ${t.ty.llvm} $v, ptr ${loop.slot}")
      }
      releaseToDepth(loop.ownedDepth, loop.tempDepth)
      emitTerm(s"br label %${loop.breakL}")

    case TContinue(depth) =>
      val loop = genLoops(depth)
      releaseToDepth(loop.ownedDepth, loop.tempDepth)
      emitTerm(s"br label %${loop.continueL}")

    // Reaching a `defer` emits nothing: it hands its statements to the scope, which lays them down
    // at each edge that leaves the block. Registering here rather than at the top of the block is
    // what makes a `defer` control never reached schedule nothing.
    case TDefer(stmts) =>
      deferStmts(stmts)

}

object Codegen {

  /** Lowers a typed program to an LLVM IR module, for a given machine (`targets.md`). */
  def generate(
      program: TProgram,
      promotions: Escape.Promotions = Escape.Promotions.none,
      target: Target = Target.default,
  ): String =
    new Codegen(program, promotions, target).gen()
}
