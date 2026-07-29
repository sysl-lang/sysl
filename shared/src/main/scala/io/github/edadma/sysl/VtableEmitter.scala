package io.github.edadma.sysl

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
    val ret     = if Type.noValue(slot.retTy) then "void" else slot.retTy.llvm
    // A zero-sized parameter is not in the implementation's signature, so it is not in the
    // adapter's either — the two have to agree, and the argument was never a word to forward.
    val forwarded = slot.params.zipWithIndex.filterNot((t, _) => Type.zeroSized(t))
    val declare   = forwarded.map { case (t, i) => s"${t.llvm} %a$i" }
    val pass      = forwarded.map { case (t, i) => s"${t.llvm} %a$i" }

    request(name) {
      inFunction(s"define private $ret @$name(${("ptr %d" :: declare).mkString(", ")})") {
        val payload =
          if !vt.boxed then "%d"
          else
            val p = freshTemp()
            emit(s"$p = getelementptr ${boxName(vt.forType)}, ptr %d, i32 0, i32 $headerFields")
            p

        // The receiver is *borrowed* here, not owned: the implementation retains its parameters on
        // entry and releases them on return, so handing it a value loaded out of the object leaves
        // the object's own count exactly where it was.
        val self = slot.recv match
          case RecvMode.ByValue =>
            val v = freshTemp(); emit(s"$v = load ${vt.forType.llvm}, ptr $payload")
            s"${vt.forType.llvm} $v"
          case RecvMode.ByPtr => s"ptr $payload"
          // A `&self` method on a raw object is refused where the object's type is formed, and on a
          // counted one the implementation is named directly, so no adapter is ever built for it.
          case RecvMode.ByRef(_) => sys.error("unreachable adapter for a '&self' method")

        val call = (self :: pass).mkString(", ")

        if ret == "void" then
          emit(s"call void @${slot.target}($call)")
          emitTerm("ret void")
        else
          val r = freshTemp()
          emit(s"$r = call $ret @${slot.target}($call)")
          emitTerm(s"ret $ret $r")
      }
    }
  }
}
