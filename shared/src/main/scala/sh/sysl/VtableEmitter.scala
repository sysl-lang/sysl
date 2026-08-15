package sh.sysl

import ir.{Arg, Inst, LType, Val}

/** The method tables trait objects dispatch through (`02`).
 *
 * A table is a constant array of function pointers, one per method the trait declares, and every
 * entry has the same shape: the object's data word first, then the method's own parameters. That
 * uniformity is what lets a call site load a slot and call it knowing only the trait.
 *
 * Keeping it uniform is what the adapters here are for. An implementation may want its receiver by
 * value, behind a pointer, or behind a reference, and the data word addresses the value itself in a
 * raw object but the reference-counted box in a counted one — so between the word a call site has
 * and the receiver an implementation declared there may be a header to step over and a value to
 * load. Where the two already agree, the implementation goes into the slot directly.
 */
trait VtableEmitter extends ArcEmitter {

  /** One table's constant, with whatever adapters its slots need queued for emission. */
  protected def genVtable(vt: TVtable): String = {
    val entries = vt.slots.map(s => s"ptr @${slotFn(vt, s)}")

    s"@${vt.name} = private constant [${vt.slots.length} x ptr] [${entries.mkString(", ")}]\n"
  }

  /** The function a slot holds: the implementation itself where its receiver already *is* the data
   * word — a `*self` method reached through a raw object, a `&self` method reached through a counted
   * one — and an adapter for everything else.
   */
  private def slotFn(vt: TVtable, slot: TVSlot): String = (slot.recv, vt.boxed) match
    case (RecvMode.ByPtr, false)   => slot.target
    case (RecvMode.ByRef(_), true) => slot.target
    case _                         => adapter(vt, slot)

  private def adapter(vt: TVtable, slot: TVSlot): String = {
    val name    = s"vt.adapt.${if vt.boxed then "ref." else ""}${slot.target}"
    val ret     = syslResult(slot.retTy)
    // The signature's spelling above carries the extension a narrow result owes whoever called
    // through the table; the `ret` at the bottom names the type alone, a terminator taking no
    // return attribute.
    val retType = syslResultType(slot.retTy)
    // A large result is written into the caller's storage, so the adapter neither receives it in a
    // register nor returns one — it forwards the out-pointer it was handed and returns nothing.
    val out     = syslSret(slot.retTy)
    // A zero-sized parameter is not in the implementation's signature, so it is not in the
    // adapter's either — the two have to agree, and the argument was never a word to forward.
    val forwarded = slot.params.zipWithIndex.filterNot((t, _) => Type.zeroSized(t))
    val declare   = forwarded.map { case (t, i) => s"${syslParam(t)} %a$i" }
    val pass      = forwarded.map { case (t, i) => Arg(syslParamLty(t), Val.Reg(s"a$i")) }

    request(name) {
      inFunction(
        s"define private $ret @$name(" +
          (out.map(_ + s" $sretParam").toList ::: "ptr %d" :: declare).mkString(", ") + ")") {
        val payload =
          if !vt.boxed then Val.Reg("d")
          else
            val p = freshReg()
            emit(Inst.Gep(p, LType.Named(boxName(vt.forType)), Val.Reg("d"),
                          List(Arg(LType.I(32), Val.Int(0)), Arg(LType.I(32), Val.Int(headerFields)))))
            p

        // The receiver is *borrowed* here, not owned: the implementation retains its parameters on
        // entry and releases them on return, so handing it a value loaded out of the object leaves
        // the object's own count exactly where it was.
        val self = slot.recv match
          // A large receiver is passed at its address like any other large argument, so the
          // implementation makes the copy it was always going to make and the adapter makes none.
          case RecvMode.ByValue if layout.indirect(vt.forType) => Arg(LType.Ptr, payload)
          case RecvMode.ByValue =>
            val v = freshReg(); emit(Inst.Load(v, vt.forType.lty, payload, ir.Access.Plain))
            Arg(vt.forType.lty, v)
          case RecvMode.ByPtr => Arg(LType.Ptr, payload)
          // A `&self` method on a raw object is refused where the object's type is formed, and on a
          // counted one the implementation is named directly, so no adapter is ever built for it.
          case RecvMode.ByRef(_) => sys.error("unreachable adapter for a '&self' method")

        // The out-pointer is forwarded with its `sret` intact and its `noalias` dropped: the adapter
        // did not create the storage and cannot promise nothing else addresses it.
        val forward = out.map(o => Arg(LType.Ptr, sretParam,
                                       o.replace("noalias ", "").stripPrefix("ptr "))).toList
        val call    = forward ::: self :: pass

        if retType == "void" then
          emit(Inst.Call(None, "void", Val.Global(slot.target), call))
          emitTerm(Inst.Ret(None, None))
        else
          val r = freshReg()
          emit(Inst.Call(Some(r), ret, Val.Global(slot.target), call))
          emitTerm(Inst.Ret(Some(syslResultLty(slot.retTy)), Some(r)))
      }
    }
  }
}
