package io.github.edadma.sysl

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

  protected def genEnumAttr(kind: String, en: Type.Enum, arg: TExpr): String = {
    val vs   = en.variants
    val last = vs.length - 1
    val uw   = en.underlying.llvm // a simple enum value's width — the type of a tag
    val v    = genExpr(arg)

    // A select chain over the variant list, folded from the last entry back: `pick(i)` gives the
    // i1 that selects entry `i`, and `value(i)` its result. The last entry is the default, reached
    // when nothing earlier matched — which for a valid operand means it *is* the last.
    def chain(width: String, value: Int => String, pick: Int => String): String =
      (last - 1 to 0 by -1).foldLeft(value(last)) { (acc, i) =>
        val r = freshTemp(); emit(s"$r = select i1 ${pick(i)}, $width ${value(i)}, $width $acc"); r
      }

    def tagEq(i: Int): String = { val r = freshTemp(); emit(s"$r = icmp eq $uw $v, ${vs(i).tag}"); r }

    kind match
      case "Pos" =>
        val iw = Type.Int.llvm
        chain(iw, i => i.toString, tagEq)

      case "Val" =>
        val iw   = Type.Int.llvm
        val geLo = compareValue(">=", Type.Int, v, "0")
        val ltHi = compareValue("<", Type.Int, v, vs.length.toString)
        val ok   = freshTemp(); emit(s"$ok = and i1 $geLo, $ltHi")
        trapUnless(ok, "val")
        chain(uw, i => vs(i).tag.toString, i => { val r = freshTemp(); emit(s"$r = icmp eq $iw $v, $i"); r })

      case "Succ" =>
        trapUnless(compareValue("!=", en.underlying, v, vs(last).tag.toString), "succ")
        // Mapping each value to the one after it: entry `i` (for `i < last`) selects `tag(i+1)`,
        // with the last value as the default the trap above keeps it from reaching.
        (last - 1 to 0 by -1).foldLeft(vs(last).tag.toString) { (acc, i) =>
          val r = freshTemp(); emit(s"$r = select i1 ${tagEq(i)}, $uw ${vs(i + 1).tag}, $uw $acc"); r
        }

      case "Pred" =>
        trapUnless(compareValue("!=", en.underlying, v, vs.head.tag.toString), "pred")
        // Mapping each value to the one before it: entry `i` (for `i > 0`) selects `tag(i-1)`, with
        // the first value as the default it can never actually reach after the trap above.
        (1 to last).foldLeft(vs.head.tag.toString) { (acc, i) =>
          val r = freshTemp(); emit(s"$r = select i1 ${tagEq(i)}, $uw ${vs(i - 1).tag}, $uw $acc"); r
        }

      case "Image" =>
        // The result is a string aggregate, so it is chosen through memory rather than a `select`:
        // each variant's block stores its name, and the default lands on the last.
        val slot = emitAlloca(freshTemp(), Type.Str.llvm)
        val endL = freshLabel("image.end")
        val caseLs = vs.map(_ => freshLabel("image.case"))
        val table = vs.init.zip(caseLs.init).map((vv, l) => s"$uw ${vv.tag}, label %$l").mkString(" ")
        emitTerm(s"switch $uw $v, label %${caseLs.last} [ $table ]")
        for (vv, l) <- vs.zip(caseLs) do
          emitLabel(l)
          emit(s"store ${Type.Str.llvm} ${stringValue(vv.name)}, ptr $slot")
          emitTerm(s"br label %$endL")
        emitLabel(endL)
        val r = freshTemp(); emit(s"$r = load ${Type.Str.llvm}, ptr $slot"); r

      case "Value" =>
        // Each variant contributes one string comparison; the value is the tag of the one that
        // matched, and no match at all traps.
        val eqs = vs.map(vv => compareValue("==", Type.Str, v, stringValue(vv.name)))
        trapUnless(eqs.reduce(orI1), "value")
        (last - 1 to 0 by -1).foldLeft(vs(last).tag.toString) { (acc, i) =>
          val r = freshTemp(); emit(s"$r = select i1 ${eqs(i)}, $uw ${vs(i).tag}, $uw $acc"); r
        }

      case other => sys.error(s"unknown enum attribute '$other'")
  }
}
