package sh.sysl

import ir.{Access, ICmp, Inst, LType, Val}

/** The seven type attributes of a simple enum — `Pos`, `Val`, `Succ`, `Pred`, `Image`, `Value`, and
 * the `Range` the analyzer folds away before it gets here (`09 §5`).
 *
 * They are one family and they lower one way: the variant list is known and small, so every attribute
 * is a **chain over it** rather than arithmetic. That is not a shortcut, it is forced — a simple
 * enum's discriminants need not be contiguous and need not start at zero, so a tag is not its own
 * position and neither can be computed from the other. What can be done instead is to compare against
 * each tag in turn, which is a fold over at most as many entries as the programmer wrote.
 *
 * Four of them can be asked of a value that has no answer — the successor of the last variant, the
 * name of no variant — and those trap, exactly as a checked integer conversion does. Nothing here
 * decides *which* attributes exist or which types have them; that is `09`'s and the analyzer's.
 */
trait EnumAttrEmitter extends ScalarEmitter {

  protected def genEnumAttr(kind: String, en: Type.Enum, arg: TExpr): Val = {
    val vs   = en.variants
    val last = vs.length - 1
    val uw   = en.underlying.lty // a simple enum value's width — the type of a tag
    val v    = genExpr(arg)

    // A select chain over the variant list, folded from the last entry back: `pick(i)` gives the
    // i1 that selects entry `i`, and `value(i)` its result. The last entry is the default, reached
    // when nothing earlier matched — which for a valid operand means it *is* the last.
    def chain(width: LType, value: Int => Val, pick: Int => Val): Val =
      (last - 1 to 0 by -1).foldLeft(value(last)) { (acc, i) =>
        val r = freshReg()
        emit(Inst.Select(r, pick(i), width, value(i), acc))
        r
      }

    def tagEq(i: Int): Val = {
      val r = freshReg(); emit(Inst.IntCmp(r, ICmp.Eq, uw, v, Val.Int(vs(i).tag))); r
    }

    kind match
      case "Pos" =>
        chain(Type.Int.lty, i => Val.Int(i), tagEq)

      case "Val" =>
        val iw   = Type.Int.lty
        val geLo = compareValue(">=", Type.Int, v, Val.Int(0))
        val ltHi = compareValue("<", Type.Int, v, Val.Int(vs.length))
        val ok   = freshReg(); emit(Inst.Bin(ok, ir.BinOp.And, LType.I(1), geLo, ltHi))
        trapUnless(ok, "val")
        chain(uw, i => Val.Int(vs(i).tag),
              i => { val r = freshReg(); emit(Inst.IntCmp(r, ICmp.Eq, iw, v, Val.Int(i))); r })

      case "Succ" =>
        trapUnless(compareValue("!=", en.underlying, v, Val.Int(vs(last).tag)), "succ")
        // Mapping each value to the one after it: entry `i` (for `i < last`) selects `tag(i+1)`,
        // with the last value as the default the trap above keeps it from reaching.
        (last - 1 to 0 by -1).foldLeft[Val](Val.Int(vs(last).tag)) { (acc, i) =>
          val r = freshReg()
          emit(Inst.Select(r, tagEq(i), uw, Val.Int(vs(i + 1).tag), acc))
          r
        }

      case "Pred" =>
        trapUnless(compareValue("!=", en.underlying, v, Val.Int(vs.head.tag)), "pred")
        // Mapping each value to the one before it: entry `i` (for `i > 0`) selects `tag(i-1)`, with
        // the first value as the default it can never actually reach after the trap above.
        (1 to last).foldLeft[Val](Val.Int(vs.head.tag)) { (acc, i) =>
          val r = freshReg()
          emit(Inst.Select(r, tagEq(i), uw, Val.Int(vs(i - 1).tag), acc))
          r
        }

      case "Image" =>
        // The result is a string aggregate, so it is chosen through memory rather than a `select`:
        // each variant's block stores its name, and the default lands on the last.
        val slot = emitAlloca(freshReg(), Type.Str.lty)
        val endL = freshLabel("image.end")
        val caseLs = vs.map(_ => freshLabel("image.case"))
        val table = vs.init.zip(caseLs.init).map((vv, l) => (BigInt(vv.tag), l))
        emitTerm(Inst.Switch(uw, v, caseLs.last, table))
        for (vv, l) <- vs.zip(caseLs) do
          emitLabel(l)
          emit(Inst.Store(Type.Str.lty, stringConst(vv.name), slot, Access.Plain))
          emitTerm(Inst.Br(endL))
        emitLabel(endL)
        val r = freshReg(); emit(Inst.Load(r, Type.Str.lty, slot, Access.Plain)); r

      case "Value" =>
        // Each variant contributes one string comparison; the value is the tag of the one that
        // matched, and no match at all traps.
        val eqs = vs.map(vv => compareValue("==", Type.Str, v, stringValue(vv.name)))
        trapUnless(eqs.reduce(orI1), "value")
        (last - 1 to 0 by -1).foldLeft[Val](Val.Int(vs(last).tag)) { (acc, i) =>
          val r = freshReg()
          emit(Inst.Select(r, eqs(i), uw, Val.Int(vs(i).tag), acc))
          r
        }

      case other => sys.error(s"unknown enum attribute '$other'")
  }
}
