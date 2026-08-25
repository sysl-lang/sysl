package sh.sysl

/** The module-level storage a program lays down before it starts: the declaration line for each
 * `val`, and the lowering of a constant tree into the text that fills one.
 *
 * It sits apart from the rest of codegen because it is the one lowering with **no basic block to put
 * anything in**. Everywhere else an instruction is selected and emitted into the block being built;
 * here there is no block yet, so a narrow float is rounded rather than `fptrunc`ed and a repeat is
 * written out rather than looped. The analyzer has already held a constant initializer to the small
 * set of trees that can be lowered this way (`ModuleStorage.isStatic`), which is what lets this fail
 * loudly on anything else instead of guessing.
 */
trait StaticEmitter extends StringEmitter {

  /** One declaration line per `val`.
   *
   * All of them are `private`, because the whole program is one LLVM module: nothing outside it can
   * name one, so hiding the symbol costs nothing and lets the optimizer see every read of the table.
   *
   * A **computed** one is a `global` rather than a `constant`, because it *is* written — once, by
   * the prologue `main` opens with. The zero it starts at is never read: `13 §7`'s ordering is what
   * guarantees that every initializer able to see the storage has already run.
   */
  protected def genVals(vals: List[TVal]): List[ir.Global] =
    vals.map { v =>
      // `@section("…")` where one was named, and `@align(n)` where a boundary was. Nothing otherwise,
      // which leaves LLVM its own choice — the natural alignment, and the target's business rather
      // than sysl's.
      ir.Global(v.symbol,
                constant = !(v.computed || v.writable),
                v.ty.lty,
                Some(v.init.filterNot(_ => v.computed).map(constantValue).getOrElse(ir.Val.Zero)),
                section = v.section,
                align = v.align)
    }

  /** `@llvm.used` — the symbols this module places by hand and nothing in it reads
   * (`reference/attributes.md § @section("...")`).
   *
   * It is what keeps the feature from compiling, linking and placing nothing. A table gathered by a
   * linker script has no reader inside the program; the globals are emitted `private` and a program
   * builds at `-O1` by default, so the pass that deletes an unreferenced private global would delete
   * exactly the object the attribute was written for. C answers this the same way — every one of
   * Zephyr's iterable-section items carries `__used` beside its section — and this is that answer.
   *
   * `appending` linkage is what makes the list a list: each module contributes its own and the
   * linker concatenates them.
   */
  protected def genUsed(names: List[String]): Option[ir.Global] =
    Option.when(names.nonEmpty)(
      ir.Global("llvm.used",
                constant = false,
                ir.LType.Arr(names.length, ir.LType.Ptr),
                Some(ir.Val.Array(names.map(n => ir.Arg(ir.LType.Ptr, ir.Val.Global(n))))),
                linkage = ir.Linkage.Appending,
                section = Some("llvm.metadata")))

  /** A `val`'s initializer as a **constant expression** — text laid straight into the object file,
   * with no instruction emitted for any of it.
   */
  private def constantValue(t: TExpr): ir.Val = t match
    case TIntLit(v, _)       => ir.Val.Int(v)
    // A module-level `bool` is written as its bit rather than as `true`, which is what LLVM's own
    // constant grammar takes — the word is the *instruction* operand's spelling.
    case TBoolLit(b)         => ir.Val.Int(if b then 1 else 0)
    case TFloatLit(bits, ty) => constantFloat(bits, ty)
    case TArrayLit(elems, arrayTy) =>
      ir.Val.Array(elems.map(e => ir.Arg(arrayTy.elem.lty, constantValue(e))))
    case TArrayFill(value, arrayTy) =>
      ir.Val.Array(List.fill(arrayTy.length)(ir.Arg(arrayTy.elem.lty, constantValue(value))))

    // Three words naming bytes that are never freed. The owner is null, which is what makes the
    // whole value a constant expression rather than something a prologue has to build — and what
    // lets a `string` sit in storage that is never let go of at all (`13 §7`).
    case TStrLit(s) => stringValue(s)

    // A struct constant lists its fields in the order the type declares them, and skips the ones
    // that occupy nothing exactly as the `insertvalue` chain in the ordinary path does — the
    // initializer has to match the type this module emitted, which is built from `stored`.
    case TStructNew(struct, args) =>
      ir.Val.Agg(args.zipWithIndex.collect {
        case (a, i) if !Type.zeroSized(struct.fields(i)._2) =>
          ir.Arg(struct.fields(i)._2.lty, constantValue(a))
      })

    // A device address written as a number: `inttoptr` is a constant expression, so the pointer is
    // in the object file rather than stored into it by a prologue. Only an *integer* read as an
    // address arrives here, which is what `ModuleStorage.isStatic` admits — a pointer reinterpreted as
    // another pointer is a name rather than a literal, so it is code and never reaches this.
    case TCast(v, _) => ir.Val.IntToPtr(v.ty.lty, constantValue(v))

    // A trait pointer is two words, so its empty value is not the one-word `null`.
    case TNullLit(ty) => if ty.lty == ir.LType.Ptr then ir.Val.Null else ir.Val.Zero

    case other => sys.error(s"unreachable constant ${other.getClass.getSimpleName}")

  /** A float constant at the width it is stored at.
   *
   * LLVM's hex form always spells a `double`, and it refuses one that a narrower type could not hold
   * exactly — so a `f32` constant is the source value rounded to a float *first* and then written
   * back out as the double that float is. That is the same rounding the `fptrunc` in the ordinary
   * path performs, done here instead of at run time.
   */
  private def constantFloat(bits: Long, ty: Type): ir.Val = {
    val d = java.lang.Double.longBitsToDouble(bits)

    if ty == Type.Real then ir.Val.Float(bits) else ir.Val.float32(d)
  }
}
