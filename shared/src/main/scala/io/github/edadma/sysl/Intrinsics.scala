package io.github.edadma.sysl

/** The back end's own operations, reachable from the library as `extern`s.
 *
 * **This is not a new mechanism so much as an opened one.** The compiler has always emitted LLVM
 * intrinsics — the bounds trap, the overflow-checked multiply, the varargs walk, the aggregate copy
 * — but every one of them is wired to a need the compiler has, and nothing written in sysl could
 * reach one. What is here lets the library name one, which is the difference between a square root
 * that is a call into libm and a square root that is an instruction.
 *
 * **It costs no keyword, because LLVM owns the `llvm.` namespace.** A module that defines a symbol
 * beginning `llvm.` is invalid IR, so no C library can export one and a link name in that namespace
 * cannot mean anything else. `extern "llvm.sqrt" sysl_sqrt(x: f64) -> f64` is therefore read as an
 * intrinsic without a second declaration form, and the two kinds of `extern` differ only in who
 * resolves them: the linker, or the back end.
 *
 * **The list is closed, and that is the point.** An intrinsic's signature is LLVM's to change
 * between releases, and a declaration that disagrees with it is not a link error — it is a verifier
 * failure at best and a miscompile at worst. So a program cannot reach an arbitrary one: the base
 * names below are what sysl supports, each checked against the signature it was declared with, and
 * anything else is refused by name. Rust draws the line in the same place, with `core::intrinsics`
 * internal and `f64::sqrt` the surface.
 *
 * **The suffix is derived rather than written.** LLVM overloads an intrinsic on its operand type and
 * spells the choice in the name — `llvm.sqrt.f64`, `llvm.sqrt.f32` — so a declaration that wrote the
 * whole name would be a fact about the types stated twice, and the two could disagree. What is
 * declared is the base, and the width comes from the signature.
 */
object Intrinsics {

  /** The namespace LLVM reserves, and the whole of how an intrinsic is told from a linked symbol. */
  val prefix = "llvm."

  /** What an intrinsic takes: `arity` value parameters, all of one floating type, returning that
   * same type.
   *
   * Every entry today has that shape, which is why it is the only one modelled. The integer
   * operations that will want this next are not all so tidy — `llvm.ctlz` takes a second argument
   * that must be a constant, and `llvm.fshl` takes three — so this is expected to grow a case rather
   * than to have been general enough already.
   */
  private case class Shape(arity: Int)

  /** The supported base names.
   *
   * These are the float operations that lower to an **instruction** on the machines sysl targets.
   * `llvm.sin`, `llvm.exp`, `llvm.log` and `llvm.pow` deliberately are *not* here: they exist, but no
   * target sysl serves has hardware for them, so each lowers to a call to the libm function of the
   * same name — which is what the library's `extern` already says, more directly and with nothing
   * gained by routing it through here.
   */
  private val table: Map[String, Shape] = Map(
    "llvm.sqrt"     -> Shape(1),
    "llvm.fabs"     -> Shape(1),
    "llvm.floor"    -> Shape(1),
    "llvm.ceil"     -> Shape(1),
    "llvm.trunc"    -> Shape(1),
    "llvm.round"    -> Shape(1),
    "llvm.copysign" -> Shape(2),
  )

  /** The supported base names, for a diagnostic to list. */
  def supported: List[String] = table.keys.toList.sorted

  /** Whether a link name asks for an intrinsic rather than a symbol the linker will find. */
  def declared(symbol: String): Boolean = symbol.startsWith(prefix)

  /** The name to emit, or why the declaration cannot be honoured.
   *
   * The checks are the ones that would otherwise be found by the LLVM verifier, where the message
   * names generated IR rather than the line someone wrote.
   */
  def resolve(base: String, params: List[Type], ret: Type): Either[String, String] =
    table.get(base) match
      case None => Left(s"'$base' is not an intrinsic sysl supports — it has ${supported.mkString(", ")}")
      case Some(shape) =>
        if params.length != shape.arity then
          Left(s"'$base' takes ${shape.arity} argument${if shape.arity == 1 then "" else "s"}, " +
            s"but ${params.length} were declared")
        else
          (params :+ ret).distinct match
            case List(Type.Floating(bits)) => Right(s"$base.f$bits")
            case _                         =>
              Left(s"'$base' takes and returns one floating-point type, and this declaration " +
                s"states ${(params :+ ret).map(Type.show).distinct.mkString(" and ")}")
}
