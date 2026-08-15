package sh.sysl

import ir.{Access, Arg, BinOp, CastOp, FCmp, ICmp, Inst, LType, Val}

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
 * everything that traps, `EnumAttrEmitter` the simple-enum attributes, `CallEmitter` the call seam
 * and the writers that put a large value straight where it is going to live, `ArithEmitter`
 * arithmetic and the scalar conversions, and `ExprEmitter` the expression dispatch they all call
 * back into.
 *
 * What stays here is the spine, which is the part that needs the whole program rather than one
 * expression: the module's assembly in declaration order, the two function forms, and statements —
 * the only level at which a temporary's lifetime is decided.
 */
class Codegen private (protected val program: TProgram, promotions: Escape.Promotions,
                       protected val target: Target,
                       override protected val allocator: Allocator)
    extends ExprEmitter with ExportThunk {

  /** The ghost functions of this program, which nothing emitted may name (`17 §8`). */
  private val ghostFuncs: Set[String] = program.funcs.filter(_.ghost).map(_.name).toSet

  /** Whether a clause is a proof obligation rather than something to lay down: it names a ghost
   * function, which will not be there.
   */
  private def ghostly(x: Any): Boolean = ghostFuncs.nonEmpty && Ghost.mentions(x, ghostFuncs)

  // --- module --------------------------------------------------------------------------

  private def gen(): String = {
    // A function a library already compiled is declared, not defined: its body is in the object file
    // the library shipped, and emitting it again would be a duplicate symbol at the link.
    val (imported, own) = program.funcs.partition(f => program.precompiled(f.name))
    // A `@ghost` function is not emitted at all (`17 §8`). Nothing executable may call one — that is
    // checked in the analyzer — and the clauses that may are the ones skipped below, so there is no
    // call left to resolve.
    val funcTexts       = own.filterNot(_.ghost).map(genFunction)
    // The C-callable entry each `@export` publishes, in front of the definition it calls
    // (`ExportThunk`). Only this compilation's own functions get one: a precompiled function's thunk
    // was emitted into the artifact that defined it, and a second copy here would be the duplicate
    // symbol every other cross-artifact declaration is careful to avoid.
    val thunkTexts      = own.filterNot(_.ghost).filter(_.exported.isDefined)
                             .map(f => genExportThunk(f, symbolOf(f.name)))
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
    //
    // **A helper that reaches the allocator asks for the plain hook, and it is asked here rather
    // than at the eight places that request one.** Every box a helper builds carries a hook that
    // gives its storage back, and the ones built by the string runtime hold raw bytes, so the hook
    // they install is the one that frees and does nothing else. Reading it off the helper's own text
    // is what keeps that from being a list somebody has to remember to add to: a helper that starts
    // allocating tomorrow brings its hook with it, and one that stops no longer asks.
    val runtimeTexts = mutable.ListBuffer.empty[String]

    while runtimeQueue.nonEmpty do
      val text = runtimeQueue.dequeue()()

      if text.contains(s"call ptr @$mallocSym(") then plainDropFn
      runtimeTexts += text

    val out = new mutable.StringBuilder

    // The module says which machine it is for, which is what makes an invocation naming a target
    // mean anything downstream: LLVM derives the data layout from the triple, so stating it is also
    // what keeps a module built for one machine from being read as a module for whatever read it.
    out ++= s"target triple = \"${target.triple}\"\n\n"

    if traps then out ++= "declare void @llvm.trap()\n"
    // `malloc` and `snprintf` take a `size_t`, and the two overflow intrinsics carry their width in
    // the **name** as well as the signature — so all of these are the machine's word rather than
    // eight bytes, and naming the wrong overload is a call to a function that does not exist.
    if heap then
      out ++= s"declare ptr @$mallocSym($word)\n"
      out ++= s"declare void @$freeSym(ptr)\n"
    if checked then
      out ++= s"declare { $word, i1 } @llvm.umul.with.overflow.$word($word, $word)\n"
      out ++= s"declare { $word, i1 } @llvm.uadd.with.overflow.$word($word, $word)\n"
    if usesSnprintf then out ++= s"declare i32 @snprintf(ptr, $word, ptr, ...)\n"
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
      (if heap then Set(mallocSym, freeSym) else Set.empty) ++
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
    //
    // **One name, one definition.** Two instantiations may share an emitted name, because a
    // transparent subtype mangles as its base: `Box[Age]` and `Box[int]` are two types to the
    // analyzer — only one of them checks what is written into it — and one layout here, which is
    // exactly what sharing a representation means. Emitting per instantiation would define the name
    // twice, and the back end rejects that rather than choosing. The layouts agree wherever the names
    // do, since the only thing mangling collapses is a subtype into the base it is stored as.
    val structs = program.structs.distinctBy(_.llvm)

    // `@packed` is LLVM's `<{ }>`, which is the same declaration order with every interior gap
    // removed and the aggregate's own alignment dropped to one — so the offsets the back end
    // computes are the ones `Layout` computed, rather than two answers that agree until a field
    // needs padding in front of it.
    //
    // `@align` is *not* written here. A type carries no alignment in LLVM's textual form: the
    // boundary is stated where storage is created, which is what `alignAttr` stamps onto an alloca
    // and a global below.
    // A **bitfield struct** has no members here at all: it is one integer, and its fields are bit
    // ranges of that integer rather than anything LLVM is told about (`Bitfields`). Writing the
    // container inside `<{ }>` rather than as a bare `iM` is what keeps every site that reaches into
    // a struct — an alloca, a by-value parameter, a load of the whole of one — on the one shape.
    for s <- structs do
      val fields = Bitfields.of(s) match
        case Some(ranges) => s"i${Bitfields.bits(ranges)}"
        case None         => s.stored.map(_._2.llvm).mkString(", ")

      out ++= (if s.packed then s"${s.llvm} = type <{ $fields }>\n" else s"${s.llvm} = type { $fields }\n")
      s.minAlign.filter(_ > 1).foreach(raisedAligns(s.llvm) = _)
    if structs.nonEmpty then out ++= "\n"

    // A data enum is the tag and **one** payload region, wide enough and aligned for whichever
    // variant needs the most (`09 §3`). Each variant's own payload keeps its named aggregate type,
    // which is what a construction stores into that region and what a match reads back out of it.
    // Deduplicated for the reason the structs above are, and it is the same rule reaching two more
    // definitions apiece: a variant's payload aggregate is named from the enum's mangling too, so
    // `Option[Age]` beside `Option[int]` would define `%…Option.int.Some` twice as well.
    val enums = program.enums.distinctBy(_.llvm)

    for e <- enums do
      for v <- e.variants if v.carries do
        out ++= s"${e.payloadLlvm(v)} = type { ${v.stored.map(_._2.llvm).mkString(", ")} }\n"
      val (unit, count) = layout.payloadArea(e)
      out ++= s"${e.llvm} = type { i32, [$count x $unit] }\n"
    if enums.nonEmpty then out ++= "\n"

    // A box is the strong count, the function that destroys it, the weak count, and the payload —
    // so ARC works the same everywhere, and an object frees itself into whichever heap made it.
    // The two counts are the machine's word, and must stay in step with `%arc.header` — the runtime
    // reads these very fields through that type, so a disagreement is not a type error anywhere,
    // it is a count read at the wrong width.
    for (name, payload) <- boxes do
      out ++= s"$name = type { $word, ptr, $word, ${payload.llvm} }\n"
    // A buffer is the same box with the element count in front of elements there may be any number
    // of, so the hook — which is reached with no static type — can still find them all. That count
    // is a `usize` like any other length.
    for (name, elem) <- bufs do
      out ++= s"$name = type { $word, ptr, $word, $word, [0 x ${elem.llvm}] }\n"
    if boxes.nonEmpty || bufs.nonEmpty then out ++= "\n"

    // A library's own functions are declared here rather than up with the `extern`s, and it has to be
    // **after** the type definitions: a named struct used before its `= type` line is opaque at that
    // point, and an opaque type cannot be passed by value. Their signatures are built the way the
    // *definition* would have built them rather than through `foreignSignature`, because these are
    // sysl's convention and not C's — getting that wrong passes arguments the other way, which is a
    // corrupt run rather than a link error.
    for f <- imported do
      val params =
        (syslSret(f.retTy).toList ++ Type.stored(f.params).map(p => syslParam(p._2)) ++
          Option.when(f.variadic)("...")).mkString(", ")
      out ++= s"declare ${syslResult(f.retTy)} @${symbolOf(f.name)}($params)\n"
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
    if heap then out ++= ArcEmitter.core(target)
    if syncHeap then out ++= ArcEmitter.atomic(target)
    if maybeHeap then out ++= ArcEmitter.maybe
    if weakHeap then out ++= ArcEmitter.weak(target)
    for t <- runtimeTexts do out ++= t; out ++= "\n"

    for t <- funcTexts do out ++= t; out ++= "\n"
    for t <- thunkTexts do out ++= t; out ++= "\n"
    out ++= mainText
    // Last, so that every symbol it names has been written above it. A definition a library already
    // compiled is not in this list: its own module said it was used, and saying so again here would
    // be this module claiming a symbol it does not define.
    out ++= genUsed(
      program.vals.filter(_.section.isDefined).map(v => v.symbol) :::
        own.filterNot(_.ghost).filter(_.section.isDefined).map(f => symbolOf(f.name)))
    out.toString
  }

  /** Filling one piece of computed module storage, at the point in a prologue where it is filled.
   *
   * **The storage takes a count of its own for what it is given, and releases nothing.** Both halves
   * matter and neither is what an ordinary assignment does. The retain is needed because the value
   * is a temporary of the region around this store, so without one it would be released at the end of
   * the region and the storage left addressing freed bytes. The release is *absent* because there is
   * nothing to release: the storage has never held anything, and a `&T`'s zero is not a reference —
   * loading it and letting go of it, which `storeInto` would do, is a decrement through null.
   *
   * That the count taken here is never given back is `13 §7`'s ruling and the whole of what module
   * storage holding a counted value means.
   */
  private def genInitStore(v: TVal, init: TExpr): Unit = {
    val value = genExpr(init)

    if containsRef(v.ty) then retainValue(v.ty, value)
    emit(Inst.Store(v.ty.lty, value, Val.Global(v.symbol), Access.Plain))
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

    for v <- vals if v.computed; init <- v.init do
      pushTemps()
      genInitStore(v, init)
      popTemps()

    stmts.foreach(genStmt)

    // The declared `main` runs in a region of its own, so the slice its arguments came in is released
    // when it returns — the same handover an ordinary call makes, since a callee takes a count for
    // each parameter on entry and gives it back at its own return.
    for e <- entry do
      pushTemps()
      val args = e.argsFn.map { fn =>
        val slice = Type.Slice(Type.Str)
        val r     = freshReg()

        emit(Inst.Call(Some(r), slice.llvm, Val.Global(fn), List(Arg(i32, Val.Reg("argc")), Arg(LType.Ptr, Val.Reg("argv")))))
        Arg(slice.lty, ownTemp(r, slice))
      }.toList

      // **A `main` that answers with a `Result` is called for its answer**, which is then handed
      // to the reporter the walk instantiated: it is what prints the error and picks the status, and
      // it is ordinary sysl for the same reason `args_of` is — the entry point should carry as
      // little hand-written IR as the platform will let it.
      (e.resultFn, e.resultTy) match
        case (Some(fn), Some(ty)) =>
          val r = freshReg()

          emit(Inst.Call(Some(r), ty.llvm, Val.Global(entrySymbol), args))
          emit(Inst.Call(None, "void", Val.Global(fn), List(Arg(ty.lty, r))))

        case _ =>
          emit(Inst.Call(None, "void", Val.Global(entrySymbol), args))

      popTemps()

    releaseAll()
    emitTerm(Inst.Ret(Some(i32), Some(Val.Int(0))))
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

    for v <- vals if v.computed; init <- v.init do
      pushTemps()
      genInitStore(v, init)
      popTemps()

    val named   = freshLabel("test.named")
    val missing = freshLabel("test.missing")
    val enough  = freshReg()

    emit(Inst.IntCmp(enough, ICmp.Sgt, i32, Val.Reg("argc"), Val.Int(1)))
    emitTerm(Inst.CondBr(enough, named, missing))

    emitLabel(named)
    val slot = freshReg(); emit(Inst.Gep(slot, LType.Ptr, Val.Reg("argv"), List(Arg(LType.I(64), Val.Int(1)))))
    val want = freshReg(); emit(Inst.Load(want, LType.Ptr, slot, Access.Plain))

    // Each arm compares the wanted name against one test's key and calls it on a match. The keys are
    // interned with the terminator `strcmp` reads to, which an ordinary sysl string constant does not
    // carry: a `string` knows its own length and has never had a use for one.
    for t <- tests do
      val run  = freshLabel("test.run")
      val next = freshLabel("test.next")
      val cmp  = freshReg()

      emit(Inst.Call(Some(cmp), "i32", Val.Global("strcmp"),
        List(Arg(LType.Ptr, want), Arg(LType.Ptr, stringGlobal(t.func + "\u0000")))))

      val hit  = freshReg(); emit(Inst.IntCmp(hit, ICmp.Eq, i32, cmp, Val.Int(0)))

      emitTerm(Inst.CondBr(hit, run, next))
      emitLabel(run)
      emit(Inst.Call(None, "void", Val.Global(symbolOf(t.func)), Nil))
      emitTerm(Inst.Ret(Some(i32), Some(Val.Int(0))))
      emitLabel(next)

    // Falling off the end of the arms and arriving with no name at all are the same answer: this
    // binary has no such test. They share a block rather than each getting one, because a runner
    // reading the status cannot tell them apart and there is nothing it would do differently.
    emitTerm(Inst.Br(missing))
    emitLabel(missing)
    emitTerm(Inst.Ret(Some(i32), Some(Val.Int(2))))

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
    ensures = f.ensures.filterNot((c, _) => ghostly(c))
    selfName = f.name
    selfParams = f.params
    selfVariant = f.variant

    // A zero-sized parameter is not an argument: there is nothing to receive and nothing to keep,
    // so it takes no slot and the emitted signature below does not mention it.
    for (name, ty) <- f.params if !Type.zeroSized(ty) do
      emitAlloca(Val.Reg(s"$name.addr"), ty.lty)
      // A large one arrived as an address, so the copy the callee makes for itself is a copy of
      // bytes and the count it takes is taken at the slot rather than off a value it never had.
      if layout.indirect(ty) then
        emitMemcpy(Val.Reg(s"$name.addr"), Val.Reg(s"$name.param"), layout.size(ty), layout.align(ty))
        retainAt(ty, Val.Reg(s"$name.addr"))
      else
        emit(Inst.Store(ty.lty, Val.Reg(s"$name.param"), Val.Reg(s"$name.addr"), Access.Plain))
        retainValue(ty, Val.Reg(s"$name.param"))
      ownSlot(name, ty)

    // Where the function calls itself as the last thing it does, that call is a jump to here rather
    // than a second frame (`TailCalls`). The target sits *after* the parameter slots are written and
    // *before* everything below, so a jump arrives where a fresh call would: the arguments are in the
    // slots the body reads, and the preconditions and `old(e)` snapshots that follow belong to the
    // invocation rather than to the frame, so they are taken again.
    //
    // The slots themselves are the entry block's and are not re-entered, which is what makes the jump
    // free: `emitAlloca` hoists every one of them, including a block's, so going round again reuses
    // the frame instead of growing it.
    tailCalls = TailCalls.of(f)

    if tailCalls.nonEmpty then
      val l = freshLabel("tailrec")

      tailParams = f.params
      tailTarget = Some(l)
      emitTerm(Inst.Br(l))
      emitLabel(l)

    for (cond, _) <- f.requires if !ghostly(cond) do emitContract(cond, "require")

    // Each `old(e)` snapshots the entry value into a hidden owned slot, exactly as a `var` would,
    // so a postcondition can compare the returned state against the state on entry. The slot is
    // released with every other local at each return.
    for (oldExpr, i) <- f.olds.zipWithIndex do
      pushTemps()
      val v = genExpr(oldExpr)
      emitAlloca(Val.Reg(s"old.$i.addr"), oldExpr.ty.lty)
      retainValue(oldExpr.ty, v)
      emit(Inst.Store(oldExpr.ty.lty, v, Val.Reg(s"old.$i.addr"), Access.Plain))
      ownSlot(s"old.$i", oldExpr.ty)
      popTemps()

    f.body.stmts.foreach(genStmt)

    // A `never` result is `void` like `unit`: the body's trailing expression diverges, so it has
    // already terminated the block and the `ret` emitted here is dropped.
    f.body.result match
      case Some(r) if layout.indirect(f.retTy) => genIndirectReturn(r)
      case Some(r) if !Type.noValue(f.retTy) =>
        val v = genExpr(r)
        retainValue(f.retTy, v)
        emitEnsures(Some(v))
        releaseAll()
        emitTerm(Inst.Ret(Some(f.retTy.lty), Some(v)))
      case Some(r) =>
        genExpr(r); emitEnsures(None); releaseAll(); emitTerm(Inst.Ret(None, None))
      case None if Type.noValue(f.retTy) =>
        emitEnsures(None); releaseAll(); emitTerm(Inst.Ret(None, None))
      case None if layout.indirect(f.retTy) =>
        emit(Inst.Store(f.retTy.lty, Val.Zero, sretParam, Access.Plain))
        releaseAll(); emitTerm(Inst.Ret(None, None))
      case None =>
        releaseAll(); emitTerm(Inst.Ret(Some(f.retTy.lty), Some(zero(f.retTy))))

    val stored   = Type.stored(f.params)
    val declared = stored.map { case (name, ty) => s"${syslParam(ty)}${frameOf(f, ty, name)} %$name.param" }
    val params   =
      (syslSret(f.retTy).map(_ + s" $sretParam").toList ++ declared ++
        Option.when(f.variadic)("...")).mkString(", ")
    // A file-private declaration has every caller in the module that defines it (`13 §2`), so its
    // symbol is `internal`: nothing outside may resolve it, and the linker is free to discard it
    // when nothing inside calls it either — which is what an exported helper in a library artifact
    // costs today.
    val linkage = if f.internal then "internal " else ""

    // `@section("…")` is written after the attributes, which is where LLVM's own grammar puts it — a
    // section before them is a parse error rather than a different meaning.
    val placement = f.section.map(s => s""" section "$s"""").getOrElse("")

    finishFunction(
      s"define $linkage${convention(f)}${syslResult(f.retTy)} @${symbolOf(f.name)}($params)" +
        s"${attribute(f)}$placement")
  }

  /** The `return` of a **large** result: it is written into the caller's storage rather than handed
   * back in registers, so what would have been the returned value is built where it is going.
   *
   * A postcondition still gets the value it is written about. Reading it back out of the slot is a
   * whole-aggregate load of the kind this lowering exists to avoid — but only on a function that
   * *has* an `ensures`, and only once at each of its returns, which is a price a contract already
   * announces it is willing to pay.
   */
  private def genIndirectReturn(r: TExpr): Unit = {
    genOwnedInto(sretParam, r)

    if ensures.nonEmpty then
      val v = freshReg(); emit(Inst.Load(v, r.ty.lty, sretParam, Access.Plain))
      emitEnsures(Some(v))
    else emitEnsures(None)

    releaseAll()
    emitTerm(Inst.Ret(None, None))
  }

  /** What a calling convention becomes on the `define` line for **this** machine (`15 §10`).
   *
   * Two of these because LLVM spells the one concept two ways: x86-64's interrupt handler is a
   * *calling convention* written before the result type, and RISC-V's is a *function attribute*
   * written after the signature. The analyzer has already refused the combinations that do not
   * exist, so what is left here is the spelling.
   */
  private def convention(f: TFunc): String =
    f.conv.flatMap(_ => Conventions.interruptForm(target.cpu).toOption) match
      case Some(Conventions.Form.Convention(llvm)) => s"$llvm "
      case _                                       => ""

  private def attribute(f: TFunc): String =
    f.conv.zip(Conventions.interruptForm(target.cpu).toOption) match
      case Some((c, Conventions.Form.Attribute(key))) =>
        s""" "$key"="${c.arg.getOrElse(Conventions.defaultRiscvMode)}""""
      case _ => ""

  /** `byval` on an x86-64 interrupt handler's frame parameter, which the ABI requires and which is
   * the one place sysl emits a parameter attribute.
   *
   * The processor pushes the frame and the handler is handed its address; LLVM models exactly that
   * as a `byval` pointer, so the pointee's type has to be named on the parameter. Only the *first*
   * parameter is the frame — a second one, where a vector pushes an error code, is an ordinary
   * integer passed as itself.
   */
  private def frameOf(f: TFunc, ty: Type, name: String): String =
    f.conv.flatMap(_ => Conventions.interruptForm(target.cpu).toOption) match
      case Some(Conventions.Form.Convention(_)) if f.params.headOption.exists(_._1 == name) =>
        ty match
          case Type.Ptr(inner) => s" byval(${inner.llvm})"
          case _               => ""
      case _ => ""

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
    case TVarDecl(_, ty, init, _) if Type.zeroSized(ty) =>
      genExpr(init)

    // An array a view of which gets out of this frame lives in a buffer instead of a slot, and
    // that is the whole of the difference: the name still means the address of the storage, so the
    // store below and every later use are the ones an unpromoted array gets. What the buffer adds
    // is an owner for the views to count against, and a release when the name goes out of scope.
    case TVarDecl(name, ty @ Type.Array(n, elem), init, _) if promoted(name) =>
      val v           = genExpr(init)
      val (box, data) = genBuffer(elem, Val.Int(n))

      promotedBoxes(name) = box
      emit(Inst.Gep(Val.Reg(s"$name.addr"), elem.lty, data, List(Arg(LType.I(64), Val.Int(0)))))
      retainValue(ty, v)
      emit(Inst.Store(ty.lty, v, Val.Reg(s"$name.addr"), Access.Plain))
      ownBox(name, box, elem)

    // The slot is laid down **before** the initializer runs, because a large one is written into it
    // rather than handed to it: the callee of `val k = kernel()` needs somewhere to write.
    case TVarDecl(name, ty, init, align) =>
      emitAlloca(Val.Reg(s"$name.addr"), ty.lty, align)
      genOwnedInto(Val.Reg(s"$name.addr"), init)
      ownSlot(name, ty)

    // `ref name = place` (`03 § ref`). The walk to the place is made **once**, here, and its result
    // becomes the name's address — so every later read and write is the one instruction it would
    // have been, and the bounds check an element owed is paid at this line rather than at each use.
    //
    // The zero-offset `getelementptr` is there to give the address a name of its own: `address` may
    // hand back an existing slot or a global, and a register cannot simply be renamed.
    //
    // Nothing is retained and nothing is owned. A ref names a slot rather than taking a share of what
    // is in it, so binding one to a `&T` field takes no count — and there is correspondingly nothing
    // to release when the block ends, which is why `ownSlot` is absent where `TVarDecl` calls it.
    case TRefDecl(name, _, place) =>
      val base = address(place)

      // The qualifier belongs to the storage, and the name that replaces the path has no declaration
      // to read it off — so it is recorded here and put back at every access through the name.
      if Type.volatileIn(place.placeTy) then refStorage(name) = place.placeTy
      refPlaceOf(name) = place
      emit(Inst.Gep(Val.Reg(s"$name.addr"), LType.I(8), base, List(Arg(LType.I(64), Val.Int(0)))))

    case TExprStmt(expr) =>
      genExpr(expr)

    case TMultiAssign(writes) =>
      genMultiAssign(writes)

    case TReturn(opt) =>
      opt match
        case Some(t) if layout.indirect(t.ty) => genIndirectReturn(t)
        case Some(t) =>
          val v = genExpr(t)
          retainValue(t.ty, v)
          emitEnsures(Some(v))
          releaseAll()
          emitTerm(Inst.Ret(Some(t.ty.lty), Some(v)))
        case None =>
          emitEnsures(None)
          releaseAll()
          emitTerm(Inst.Ret(None, None))

    // A `break`/`continue` leaves the body from the middle, so it unwinds the body's ownership
    // regions before jumping — the same discipline as `return`, bounded to the loop. `break v`
    // hands its value over with a count taken (so it survives the unwind) into the loop's slot.
    case TBreak(opt, depth) =>
      val loop = genLoops(depth)
      opt.foreach { t =>
        val v = genExpr(t)
        retainValue(t.ty, v)
        emit(Inst.Store(t.ty.lty, v, loop.slot, Access.Plain))
      }
      releaseToDepth(loop.ownedDepth, loop.tempDepth)
      emitTerm(Inst.Br(loop.breakL))

    case TContinue(depth) =>
      val loop = genLoops(depth)
      releaseToDepth(loop.ownedDepth, loop.tempDepth)
      emitTerm(Inst.Br(loop.continueL))

    // Reaching a `defer` emits nothing: it hands its statements to the scope, which lays them down
    // at each edge that leaves the block. Registering here rather than at the top of the block is
    // what makes a `defer` control never reached schedule nothing.
    case TDefer(stmts) =>
      deferStmts(stmts)

    // `asm` (`inline-assembly.md`). One architecture's instructions arrived here; the arms that
    // answered for the others were chosen between in the analyzer and are not represented.
    case TAsm(lines, operands, clobbers) =>
      if lines.nonEmpty then genAsm(lines, operands, clobbers)

    // A loop's `invariant` (`17 §3`) is a condition that traps on false, which is every other clause
    // in `16` — so it is the shared check and nothing more, unless it mentions ghost state, in which
    // case it is a proof obligation and nothing runs (`17 §8`).
    case TInvariant(cond, _) =>
      if !ghostly(cond) then emitContract(cond, "invariant")

    case v: TVariantCheck =>
      if !ghostly(v.expr) then genVariantCheck(v)

  /** Lowers the selected arm to LLVM's inline assembly.
   *
   * Every input is loaded before the call and every output stored after it, which is what makes an
   * operand a *value* rather than a register the programmer had to find: LLVM is told what has to be
   * where, and satisfying it is the allocator's problem. The template and the constraint string are
   * built together from one list, because an operand's position in the second is the number its name
   * becomes in the first, and the two disagreeing is exactly the failure this construct removes.
   */
  private def genAsm(lines: List[String], operands: List[TAsmOperand], clobbers: List[String]): Unit = {
    val ins  = operands.filter(_.dir == AsmDir.In)
    val outs = operands.filter(_.dir == AsmDir.Out)
    val slot = Asm.numbering(operands)

    val loaded = ins.map { o =>
      val r = freshReg()

      emit(Inst.Load(r, o.ty.lty, Val.Reg(s"${o.slot}.addr"), Access.Plain))
      Arg(o.ty.lty, r)
    }

    asmSite += 1

    val text = Asm.uniquifyLabels(lines, asmSite).map(Asm.render(_, slot.get)).mkString("\\0A")
    val cons = Asm.constraints(operands, clobbers)

    // `sideeffect` says the block matters even where nothing reads its result, which is true of every
    // assembly sysl can express: the instructions worth reaching for here are the ones whose whole
    // purpose is what they do to the machine rather than what they hand back.
    outs match
      case Nil =>
        emit(Inst.Asm(None, LType.Void, text, cons, loaded))

      case List(o) =>
        val r = freshReg()

        emit(Inst.Asm(Some(r), o.ty.lty, text, cons, loaded))
        emit(Inst.Store(o.ty.lty, r, Val.Reg(s"${o.slot}.addr"), Access.Plain))

      // Several outputs come back as one anonymous structure, which is LLVM's shape rather than
      // anything the language says — so it is taken apart here and never seen above this line.
      case many =>
        val r     = freshReg()
        val shape = LType.Struct(many.map(_.ty.lty))

        emit(Inst.Asm(Some(r), shape, text, cons, loaded))

        for (o, i) <- many.zipWithIndex do
          val part = freshReg()

          emit(Inst.Extract(part, shape, r, List(i)))
          emit(Inst.Store(o.ty.lty, part, Val.Reg(s"${o.slot}.addr"), Access.Plain))
  }

  /** Counts the assembly blocks emitted in this module, so each one's labels can be its own. */
  private var asmSite = 0
}

object Codegen {

  /** Lowers a typed program to an LLVM IR module, for a given machine (`targets.md`) and from a given
   * heap (`packages.md § 13`).
   *
   * `allocator` defaults to libc's pair, so a caller with no opinion — every test that is not about
   * this, and every program whose packages say nothing — gets exactly the module it got before a
   * package could name one.
   */
  def generate(
      program: TProgram,
      promotions: Escape.Promotions = Escape.Promotions.none,
      target: Target = Target.default,
      allocator: Allocator = Allocator.c,
  ): String =
    new Codegen(program, promotions, target, allocator).gen()
}
