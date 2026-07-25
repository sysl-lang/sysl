package io.github.edadma.sysl

/** The call forms the compiler resolves by name.
 *
 * A call is normally a name looked up among the program's declarations. These six are not: the
 * analyzer recognizes `print`, `str`, `format`, `va_start`, `va_end`, and `va_arg` before it gets
 * that far. Collecting them in one file is deliberate — it is the whole of what the language knows
 * that a program could not have told it, and the list is meant to shrink.
 *
 * The three `va_*` forms belong here permanently. Each is an **ABI primitive** that no sysl body
 * could implement, in the same category as `sizeof`, so there is nothing to put in the prelude
 * (`12` §9). The other three are the temporary ones: `print` is already a desugaring onto prelude
 * functions and needs only a `Display` trait to become an ordinary call, and `str` and `format`
 * follow it out on the same trait.
 */
trait SpecialForms extends Literals {

  /** `print(a, b, …)` — each value rendered by the prelude function its type reaches, a space
   * between and a newline at the end.
   *
   * This is a **desugaring**, not a builtin: the compiler knows six *names* the way it already
   * knows `Option`'s variants, and implements no printing of its own. When a `Display` trait exists
   * it retargets from `printi(x)` to `x.display()` and every program keeps working, which is the
   * point of putting the seam at a name.
   *
   * The space and the newline go out as *characters* rather than one-character strings, so that
   * printing a number reaches nothing that allocates: a `string` is reference-counted, and a single
   * `prints(" ")` would pull the whole ARC runtime and an allocator into a program whose own code
   * never asks for either.
   */
  protected def printCall(args: List[Expr]): TExpr = {
    val parts = args.zipWithIndex.flatMap { case (a, i) =>
      val sep = if i == 0 then Nil else List(printChar(' '))

      sep :+ printOne(analyzeExpr(a))
    }

    TSeq(parts :+ printChar('\n'))
  }

  /** One value, rendered by the prelude function that takes its type.
   *
   * Every argument is widened to the one width its renderer takes — the integers to `long` or
   * `ulong`, the floats to `real` — so the prelude needs one function per *kind* rather than one
   * per type, which sysl has no overloading to hide anyway.
   */
  private def printOne(t: TExpr): TExpr = t.ty match
    case i: Type.Integer if i.signed => callPrelude("printi", widen(t, Type.Integer(64, signed = true)))
    case _: Type.Integer             => callPrelude("printu", widen(t, Type.Integer(64, signed = false)))
    case _: Type.Floating            => callPrelude("printr", widen(t, Type.Real))
    case Type.Bool                   => callPrelude("printb", t)
    case Type.Char                   => callPrelude("printc", t)
    case Type.Str                    => callPrelude("prints", t)
    case Type.Unit                   => err("cannot print a unit value")
    case other                       => err(s"cannot print a ${show(other)} value")

  /** The separator and the terminator `print` puts around its values. */
  private def printChar(c: Char): TExpr = callPrelude("printc", TIntLit(c.toInt, Type.Char))

  /** Widens a value to the width its renderer takes, leaving one that is already there alone. */
  private def widen(t: TExpr, to: Type): TExpr = if t.ty == to then t else TCast(t, to).setPos(t.pos)

  /** A call to a prelude function, built from an already-analyzed argument. Recording the name is
   * what brings the function itself into the program: a prelude declaration nothing reaches is
   * neither analyzed nor emitted.
   */
  private def callPrelude(name: String, arg: TExpr): TExpr = {
    val (_, rtype) = funcInsts(name)

    funcsUsed += name
    TCall(name, List(arg), rtype)
  }

  /** `str(x)` renders a value of a primitive type, and a `string` as itself. Anything else — a
   * struct, an enum, a pointer, a slice — has no one string form to give, so asking for one is an
   * error until a `Display` trait lets a type name its own.
   */
  protected def strCall(args: List[Expr]): TExpr = {
    if args.length != 1 then err("str takes exactly one value")
    val t = analyzeExpr(args.head)

    t.ty match
      case _: Type.Integer | _: Type.Floating | Type.Bool | Type.Char | Type.Str => TStr(t)
      case _ => err(s"cannot make a string of a ${show(t.ty)} value")
  }

  /** `format(value, "%spec")` renders one value through a printf specifier. It is the desugaring of
   * an `f"…"` hole, so the specifier is always a literal here; the lexer has vetted its shape, and
   * what is left is checking the conversion against the value's type.
   */
  protected def formatCall(argExpr: Expr, spec: String): TExpr = {
    val t = analyzeExpr(argExpr)
    val c = FormatSpec.conversion(spec)
    val ok =
      if FormatSpec.isInt(c) then t.ty.isInstanceOf[Type.Integer]
      else if FormatSpec.isFloat(c) then t.ty.isInstanceOf[Type.Floating]
      else t.ty == Type.Str

    if !ok then err(s"format '$spec' expects ${FormatSpec.expects(c)}, but the value has type ${show(t.ty)}")
    TFormat(t, spec)
  }

  /** `va_start(ap)` readies a variadic body's tail. C also names the last fixed parameter here;
   * sysl does not, because the function already knows which parameter that is and repeating it is a
   * chance to get it wrong.
   */
  protected def vaStart(args: List[Expr]): TExpr = {
    if !variadicFn then err("'va_start' is only allowed in a function declared with '...' — there is no tail here")
    TVaStart(vaList("va_start", args))
  }

  /** `va_end(ap)` finishes with one. */
  protected def vaEnd(args: List[Expr]): TExpr = TVaEnd(vaList("va_end", args))

  /** `va_arg[T](ap)` takes the next argument as a `T` and advances.
   *
   * C writes the type as a second argument, which is not something a sysl expression can hold, so
   * it comes from the context the value is read into — the same place `None` and `Ok(5)` get
   * theirs. Nothing in the tail can confirm it, which is the unsafety C has here too.
   */
  protected def vaArg(args: List[Expr], expected: Option[Type]): TExpr = {
    val ap = vaList("va_arg", args)
    val ty = expected.getOrElse(
      err("'va_arg' reads the next argument as some type, and nothing here says which — " +
        "annotate the variable it is read into"),
    )

    TVaArg(ap, vaArgType(ty))
  }

  /** The `va_list` one of the three forms works on, as its address. Each works on the list itself
   * rather than a copy — `va_arg` advances it — so the argument is a place whose address is handed
   * over, exactly as `&ap` would be.
   */
  private def vaList(what: String, args: List[Expr]): TExpr = {
    if args.length != 1 then err(s"'$what' takes exactly one argument, the va_list it walks")
    val place = analyzePlace(args.head, s"'$what'")
    if place.ty != Type.VaList then err(s"'$what' needs a va_list, not ${show(place.ty)}")

    TAddrOf(place, Type.Ptr(place.ty))
  }

  /** Checks that a type is one a variadic tail can be *read* as.
   *
   * Every argument was widened on the way in (`12 §1`), so asking for a narrower type than the
   * promotion produced would read the wrong bytes — C's most common varargs mistake, and one worth
   * a diagnostic rather than a wrong answer. Nothing is lost: read it at the promoted width and
   * convert, which is what C's own callee has to do anyway.
   */
  private def vaArgType(ty: Type): Type = ty match
    case i: Type.Integer if i.bits >= 32  => ty
    case f: Type.Floating if f.bits == 64 => ty
    case Type.Char | _: Type.Ptr          => ty
    case i: Type.Integer =>
      err(s"a variadic argument is promoted to at least 32 bits, so it cannot be read as " +
        s"${show(i)} — read it as ${if i.signed then "'int'" else "'uint'"} and convert")
    case f: Type.Floating =>
      err(s"a variadic argument is promoted to double, so it cannot be read as ${show(f)} — " +
        "read it as 'real' and convert")
    case other =>
      err(s"a variadic argument cannot be read as ${show(other)} — it may be an integer, a real, " +
        "a char, or a raw pointer")
}
