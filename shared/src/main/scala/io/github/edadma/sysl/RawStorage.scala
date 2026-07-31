package io.github.edadma.sysl

/** Reinterpreting storage, and asking what a type's storage costs — `03 § Reinterpreting storage`.
 *
 * Three operations that only look unrelated. An allocator carves bytes and hands back a typed
 * pointer, a driver takes an address the datasheet gives as a number and reaches the registers at
 * it, and both need to know how wide the thing they are pointing at is. Each is a place where the
 * language's guarantees stop, so each is written.
 *
 * The split between here and `Literals.convert` is the one the chapter draws. A pointer **becoming
 * an integer** is an ordinary conversion — total, lossless, and producing a value nothing can
 * dereference — so it lives in that table with the others. The two directions that **produce** a
 * pointer are the unsafe ones and are here.
 */
trait RawStorage extends ExprSupport {

  /** `sizeof(T)` / `alignof(T)` — the measurement `Layout` already makes, asked from outside.
   *
   * The answer is a `usize` constant folded here rather than an instruction, which is what makes it
   * usable where a constant is required: an array length, a `require`, the block size a slab is laid
   * out in.
   *
   * The measurement itself is `layoutBytes`, shared with the constant folder so that `sizeof(T)` in
   * an array bound and `sizeof(T)` in an expression cannot disagree. It answers `None` for a type
   * parameter and for a poisoned type, and a placeholder stands in for both: a generic body is
   * walked once with its parameters standing in for themselves and that tree is thrown away, and a
   * poisoned type has been complained about already, so neither placeholder is ever emitted.
   */
  protected def layoutOf(what: String, tr: TypeRef): TExpr =
    TIntLit(layoutBytes(what, resolveType(tr, tsubst)).getOrElse(0), Type.Usize)

  /** `ptr_cast(x)` — an address read as a pointer to something else.
   *
   * The target is the type the context asks for, the way `va_arg`, a bare `None` and a bare `null`
   * all take theirs (`12 §9`, `03 § Null`). It is not written in the call because there is nowhere to
   * write it: square brackets in an expression are indexing, and call-site type arguments are refused
   * language-wide (`10 § Open a`), so a written target would need a syntax nothing else here has.
   *
   * What comes out is always a `*T` and never a `&T`. A reference is a safe-tier value — non-null,
   * refcounted, and relied on by everything the safe subset promises — and an address invented from
   * bytes has no count for ARC to own and no object to be non-null about. Producing one would put a
   * value the safe subset trusts into a state only the unsafe tier can reach, which is the single
   * thing the two-tier split exists to prevent.
   */
  protected def ptrCast(args: List[Expr], expected: Option[Type]): TExpr = {
    if args.length != 1 then err("'ptr_cast' takes exactly one value, the address to read as a pointer")

    val t  = analyzeExpr(args.head)
    val to = expected.getOrElse(
      err("'ptr_cast' reads an address as a pointer to some type, and nothing here says which — " +
        "annotate the variable, field, or result it is read into"),
    )

    castTarget(to)
    source(t)
    TCast(t, to)
  }

  /** Checks that the context asked for something a reinterpreted address may be.
   *
   * Each refusal names what the type promises that an address cannot supply, because being told
   * "not a pointer" would leave the reader looking for a spelling rather than reading the reason.
   */
  private def castTarget(to: Type): Unit = Type.underlying(to) match
    case Type.Ptr(_: Type.Trait) =>
      err("'ptr_cast' produces a plain pointer, and a pointer to a trait is two words — the address " +
        "and the table of the type it was erased from, which an address alone does not say")
    case _: Type.Ptr => ()

    case _: Type.Ref =>
      err("'ptr_cast' never produces a reference: a '&T' is counted and non-null, and an address " +
        "read out of bytes carries no count for anything to own — read it as a '*T'")
    case _: Type.Weak =>
      err("'ptr_cast' never produces a weak reference, which is a counted reference that does not " +
        "keep its referent alive — read the address as a '*T'")
    case v: Type.View =>
      err(s"'ptr_cast' produces a pointer, and a ${show(v)} is a pointer and a length together — " +
        s"read the address as a pointer and take the view from it, as 'p[0..<n]'")

    case Type.Unknown => ()
    case other =>
      err(s"'ptr_cast' produces a pointer, and ${show(other)} is not one")

  /** Checks that the value is something an address can be read out of. */
  private def source(t: TExpr): Unit = Type.underlying(t.ty) match
    case Type.Ptr(_: Type.Trait) =>
      err("'ptr_cast' takes a plain pointer, and a pointer to a trait is two words — select the " +
        "address half by reading through it, rather than reinterpreting the pair")
    case _: Type.Ptr => ()

    // An integer narrower than an address would be read as a pointer with its top bits invented,
    // which is the one mistake here that produces a wild pointer silently rather than a diagnostic.
    case i: Type.Integer if i.pointerWidth => ()
    case i: Type.Integer =>
      err(s"'ptr_cast' reads an address, and ${show(i)} is not address-width — convert it first, as " +
        "'ptr_cast(usize(n))'")

    case Type.Unknown => ()
    case other =>
      err(s"'ptr_cast' takes a pointer or an address-width integer, and the value has type ${show(other)}")
}
