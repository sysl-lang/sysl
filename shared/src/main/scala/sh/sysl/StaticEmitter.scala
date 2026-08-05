package sh.sysl

/** The module-level storage a program lays down before it starts: the declaration line for each
 * `val`, and the lowering of a constant tree into the text that fills one.
 *
 * It sits apart from the rest of codegen because it is the one lowering with **no basic block to put
 * anything in**. Everywhere else an instruction is selected and emitted into the block being built;
 * here there is no block yet, so a narrow float is rounded rather than `fptrunc`ed and a repeat is
 * written out rather than looped. The analyzer has already held a constant initializer to the small
 * set of trees that can be lowered this way (`ProgramWalk.isStatic`), which is what lets this fail
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
  protected def genVals(vals: List[TVal]): String =
    vals.map { v =>
      val kind = if v.computed || v.writable then "global" else "constant"

      v.init match
        case Some(init) if !v.computed => s"@${v.symbol} = private $kind ${v.ty.llvm} ${constantValue(init)}\n"
        case _                         => s"@${v.symbol} = private $kind ${v.ty.llvm} zeroinitializer\n"
    }.mkString

  /** A `val`'s initializer as a **constant expression** — text laid straight into the object file,
   * with no instruction emitted for any of it.
   */
  private def constantValue(t: TExpr): String = t match
    case TIntLit(v, _)       => v.toString
    case TBoolLit(b)         => if b then "1" else "0"
    case TFloatLit(bits, ty) => constantFloat(bits, ty)
    case TArrayLit(elems, arrayTy) =>
      val elem = arrayTy.elem.llvm
      s"[${elems.map(e => s"$elem ${constantValue(e)}").mkString(", ")}]"
    case TArrayFill(value, arrayTy) =>
      val elem = arrayTy.elem.llvm
      val v    = constantValue(value)
      s"[${List.fill(arrayTy.length)(s"$elem $v").mkString(", ")}]"

    // Three words naming bytes that are never freed. The owner is null, which is what makes the
    // whole value a constant expression rather than something a prologue has to build — and what
    // lets a `string` sit in storage that is never let go of at all (`13 §7`).
    case TStrLit(s) => stringValue(s)

    // A struct constant lists its fields in the order the type declares them, and skips the ones
    // that occupy nothing exactly as the `insertvalue` chain in the ordinary path does — the
    // initializer has to match the type this module emitted, which is built from `stored`.
    case TStructNew(struct, args) =>
      val fields =
        args.zipWithIndex.collect {
          case (a, i) if !Type.zeroSized(struct.fields(i)._2) =>
            s"${struct.fields(i)._2.llvm} ${constantValue(a)}"
        }

      s"{ ${fields.mkString(", ")} }"

    // A device address written as a number: `inttoptr` is a constant expression, so the pointer is
    // in the object file rather than stored into it by a prologue. Only an *integer* read as an
    // address arrives here, which is what `ProgramWalk.isStatic` admits — a pointer reinterpreted as
    // another pointer is a name rather than a literal, so it is code and never reaches this.
    case TCast(v, _) => s"inttoptr (${v.ty.llvm} ${constantValue(v)} to ptr)"

    // A trait pointer is two words, so its empty value is not the one-word `null`.
    case TNullLit(ty) => if ty.llvm == "ptr" then "null" else "zeroinitializer"

    case other => sys.error(s"unreachable constant ${other.getClass.getSimpleName}")

  /** A float constant at the width it is stored at.
   *
   * LLVM's hex form always spells a `double`, and it refuses one that a narrower type could not hold
   * exactly — so a `f32` constant is the source value rounded to a float *first* and then written
   * back out as the double that float is. That is the same rounding the `fptrunc` in the ordinary
   * path performs, done here instead of at run time.
   */
  private def constantFloat(bits: String, ty: Type): String = {
    val d = java.lang.Double.longBitsToDouble(java.lang.Long.parseUnsignedLong(bits.drop(2), 16))

    if ty == Type.Real then bits
    else f"0x${java.lang.Double.doubleToLongBits(d.toFloat.toDouble)}%016X"
  }
}
