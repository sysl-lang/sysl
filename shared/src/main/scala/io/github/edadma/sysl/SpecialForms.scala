package io.github.edadma.sysl

/** The call forms the compiler resolves by name.
 *
 * A call is normally a name looked up among the program's declarations. These eight are not: the
 * analyzer recognizes `print`, `str`, `format`, `from_utf8_unchecked`, `va_start`, `va_end`,
 * `va_arg`, and `va_copy` before it gets that far. Collecting them in one file is deliberate — it is
 * the whole of what the language knows that a program could not have told it, and the list is meant
 * to shrink.
 *
 * The four `va_*` forms belong here permanently. Each is an **ABI primitive** that no sysl body
 * could implement, in the same category as `sizeof`, so there is nothing to put in the prelude
 * (`12` §9). `from_utf8_unchecked` is permanent for the same reason from the other direction: every
 * safe route to a `string` carries the UTF-8 guarantee, so the one operation that sets it aside can
 * only come from underneath the language. The other three are the temporary ones: all three are now
 * desugarings onto a `Display`, and what keeps them here is the sink each supplies — standard output
 * for `print`, a fresh buffer for `str` and `format` — neither of which the prelude can name yet.
 */
trait SpecialForms extends Closures {

  /** `print(a, b, …)` — each value rendered by the prelude function its type reaches, a space
   * between and a newline at the end.
   *
   * This is a **desugaring**, not a builtin: the compiler knows a handful of *names* the way it
   * already knows `Option`'s variants, and implements no printing of its own. A scalar reaches the
   * prelude renderer for its width; everything else reaches its own `Display`, which is what
   * putting the seam at a name was for.
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
   *
   * A scalar keeps this direct path rather than going through its `Display` (`14 §8 b`): the two
   * render identically, and the one that does not build a sink is the one to emit. Everything else
   * writes itself into standard output through the trait.
   */
  private def printOne(t: TExpr): TExpr = t.ty match
    // A width past 64 bits has no printf conversion to be widened to, so it renders itself first
    // and goes out as the string that came back. That is the one scalar whose printing allocates,
    // and it allocates because C cannot be asked to do this one.
    case i: Type.Integer if i.bits > 64  => callPrelude("prints", TStr(t))
    case i: Type.Integer if i.signed => callPrelude("printi", widen(t, Type.Integer(64, signed = true)))
    case _: Type.Integer             => callPrelude("printu", widen(t, Type.Integer(64, signed = false)))
    case _: Type.Floating            => callPrelude("printr", widen(t, Type.Real))
    case Type.Bool                   => callPrelude("printb", t)
    case Type.Char                   => callPrelude("printc", t)
    case Type.Str                    => callPrelude("prints", t)
    case _ =>
      renderer(t, "print") match
        case (_, value, Some(slot)) => TVCall(value, slot, List(stdout(), plainSpec), Type.Unit)
        case (method, value, None)  => TCall(method, List(value, stdout(), plainSpec), Type.Unit)

  /** The separator and the terminator `print` puts around its values. */
  private def printChar(c: Char): TExpr = callPrelude("printc", TIntLit(c.toInt, Type.Char))

  /** A call to a prelude function, built from an already-analyzed argument. Recording the name is
   * what brings the function itself into the program: a prelude declaration nothing reaches is
   * neither analyzed nor emitted.
   */
  protected def callPrelude(name: String, arg: TExpr): TExpr = {
    val (_, rtype) = funcInsts(name)

    funcsUsed += name
    TCall(name, List(arg), rtype)
  }

  /** `str(x)` renders a value of a primitive type, and a `string` as itself. Anything else writes
   * itself into a buffer through its `Display`, and the bytes that land there become the string.
   */
  protected def strCall(args: List[Expr]): TExpr = {
    if args.length != 1 then err("str takes exactly one value")
    val t = analyzeExpr(args.head)

    t.ty match
      case _: Type.Integer | _: Type.Floating | Type.Bool | Type.Char | Type.Str => TStr(t)
      case _ =>
        val (method, value, slot) = renderer(t, "str")

        TRender(value, method, plainSpec, slot)
  }

  /** `from_utf8_unchecked(b)` — a `[]u8` taken as a `string` without looking at it.
   *
   * This is the whole of what the prelude's `from_utf8` cannot write for itself: it validates, and
   * then it needs somewhere to say "these bytes are a string now". `04` puts the unchecked form in
   * the `*T` tier deliberately — breaking the UTF-8 invariant breaks `char`'s downstream — so the
   * spelling is long and greppable rather than convenient.
   *
   * A `string` argument is refused rather than passed through. It would be the identity, but the
   * only way to write one is to have gone looking for this function, and a program that reaches for
   * the unchecked conversion on a value that is already checked has misunderstood which direction it
   * is going.
   */
  protected def fromUtf8Unchecked(args: List[Expr]): TExpr = {
    if args.length != 1 then err("'from_utf8_unchecked' takes exactly one value, the bytes to take as a string")
    val t = analyzeExpr(args.head)

    Type.underlying(t.ty) match
      case Type.Slice(Type.Byte) => TFromBytes(t)
      case Type.Array(_, Type.Byte) =>
        err("'from_utf8_unchecked' takes a []u8, and an array is not one — slice it, as 'from_utf8_unchecked(a[..])'")
      case Type.Str =>
        err("'from_utf8_unchecked' makes a string out of bytes, and this value is already a string")
      case other =>
        err(s"'from_utf8_unchecked' takes a []u8, but the value has type ${show(other)}")
  }

  /** `format(value, "%spec")` renders one value through a printf specifier. It is the desugaring of
   * an `f"…"` hole, so the specifier is always a literal here; the lexer has vetted its shape, and
   * what is left is checking the conversion against the value's type.
   *
   * A type that renders itself has no printf conversion, so `%s` is what reaches it — and the
   * specifier is handed on rather than applied, since only the implementation knows what a width
   * means for the text it is about to write. A **built-in** keeps the strict check: `%s` on an
   * integer stays the mistake it was, rather than becoming a rendering that silently drops the
   * width the programmer asked for.
   */
  protected def formatCall(argExpr: Expr, spec: String): TExpr = {
    val t = analyzeExpr(argExpr)
    val c = FormatSpec.conversion(spec)

    // A specifier is applied by `snprintf`, and C has no length modifier for an argument wider than
    // `long long` — so the one thing that cannot be honoured here is a width past 64 bits. It is
    // refused by name rather than silently narrowed, and the message names `str`, which renders such
    // a value without a specifier because the language rather than libc writes its digits.
    Type.underlying(t.ty) match
      case i: Type.Integer if i.bits > 64 && FormatSpec.isInt(c) =>
        err(s"'$spec' is applied by C's formatting, which has no conversion for the ${i.bits} bits " +
          s"of ${show(t.ty)} — render it with 'str' instead")
      case _ =>

    val ok =
      if FormatSpec.isInt(c) then t.ty.isInstanceOf[Type.Integer]
      else if FormatSpec.isFloat(c) then t.ty.isInstanceOf[Type.Floating]
      else t.ty == Type.Str

    if ok then TFormat(t, spec)
    else if FormatSpec.isStr(c) && rendersItself(t.ty) then
      val (method, value, slot)   = renderer(t, "format")
      val (width, prec, left)     = FormatSpec.parts(spec)

      TRender(value, method, specValue(width, prec, left), slot)
    else err(s"format '$spec' expects ${FormatSpec.expects(c)}, but the value has type ${show(t.ty)}")
  }

  // --- rendering through Display ---------------------------------------------------------

  /** The `display` a value renders through, and the value in whatever width that renderer takes.
   *
   * The answers are `14 §3`'s one dispatch rule, applied to rendering: a **built-in** reaches the
   * prelude renderer its membership provides (`§5`), a **user type** the member its `impl` produced,
   * and a **bounded type parameter** the trait's own method, since which implementation runs is
   * monomorphization's to decide once a concrete type is known. A **trait object** answers with a
   * slot rather than a name, since which `display` runs is a word in its table — and it has one
   * exactly when the trait it was erased to **requires** `Display` (`02`). That is what a supertrait
   * buys the ordinary way of printing: without one, a value stops being printable at the moment it
   * is erased, which is the moment a program most wants to describe what it is holding.
   *
   * `op` is the form that asked, so a type that renders no way at all is complained about in the
   * words the programmer wrote rather than in the machinery underneath them.
   */
  private def renderer(t: TExpr, op: String): (String, TExpr, Option[Int]) = {
    // A constrained subtype renders exactly as its base does — a number or a character — so it
    // reaches the base's renderer rather than asking for a `Display` impl of its own.
    checkWriterShape(); Type.underlying(t.ty)
  } match
    case a: Type.Abstract =>
      if !satisfies("Display", a) then boundErr(s"'$op' needs '${a.name}: Display'")
      ("Display.display", t, None)

    case Type.Ptr(o: Type.Trait) => objectRenderer(t, o, op)
    case Type.Ref(o: Type.Trait, _) => objectRenderer(t, o, op)

    case ty =>
      CoreTraits.display(ty) match
        case Some((name, want)) =>
          funcsUsed += name
          (name, rendered(t, want), None)

        case None =>
          if !conforms("Display", ty) then
            // Naming the `impl` to write is only advice where one could be written at all: a memory
            // mode is the shape `02` refuses, so it is told what is true of it rather than pointed
            // at a block that would not compile. A generic type is written for as a whole, so the
            // advice names the block's own parameters rather than the arguments this value has. And
            // a type an implementation already covers is told what that implementation asked of it,
            // since writing a second one is exactly what it may not do.
            val fix = unmetBound("Display", ty).getOrElse(ty match
              case n: Type.Named if n.targs.nonEmpty =>
                val tps = nominalTparams(n.base).mkString(", ")
                s"write an 'impl[$tps] Display for ${qn(n.base)}[$tps]' to say how it renders"
              case n: Type.Named                 => s"write an 'impl Display for ${show(n)}' to say how it renders"
              case _: Type.Array | _: Type.Slice => s"write an 'impl Display for ${show(ty)}' to say how it renders"
              case _                             => "it does not implement 'Display'")
            val asked = if op == "print" then "cannot print" else "cannot make a string of"
            err(s"$asked a ${show(ty)} value — $fix")

          val fname = memberFuncName(ty, "display")

          funcsUsed += fname
          (fname, t, None)

  /** The `display` slot in a trait object's table, or why the object has none.
   *
   * The advice is what tells the two failures apart. A trait that could require `Display` and does
   * not is told to, since that is a one-word change to a declaration; the object type itself is not
   * the thing to change, and neither is the value, which very likely implements `Display` already
   * and lost it at the erasure.
   */
  private def objectRenderer(t: TExpr, o: Type.Trait, op: String): (String, TExpr, Option[Int]) =
    displaySlot(o) match
      case Some(slot) => ("Display.display", t, Some(slot))
      case None =>
        val asked = if op == "print" then "cannot print" else "cannot make a string of"

        err(s"$asked a ${show(t.ty)} value — an object offers what its trait declares and what that " +
          s"trait requires, so write 'trait ${qn(o.name)}: Display' to keep the rendering the value " +
          "had before it was erased")

  /** Which slot of a trait object's table holds `Display`'s renderer, if the trait requires it. */
  private def displaySlot(o: Type.Trait): Option[Int] =
    traitMembers(o.bound).zipWithIndex.collectFirst {
      case ((from, m), slot) if from.name == "Display" && m.name == "display" => slot
    }

  /** Whether a type renders through an `impl` rather than through a printf conversion — which is
   * the case `format` hands its specifier on for instead of applying it.
   */
  private def rendersItself(ty: Type): Boolean = ty match
    case a: Type.Abstract           => satisfies("Display", a)
    case Type.Ptr(o: Type.Trait)    => displaySlot(o).isDefined
    case Type.Ref(o: Type.Trait, _) => displaySlot(o).isDefined
    case _                          => conforms("Display", ty)

  /** The compiler's own writers are laid out by hand and reached by slot index, so `Writer`'s shape
   * is something the emitter depends on rather than merely reads. Checking it here turns a later
   * edit to the prelude into a failed build instead of a call through the wrong slot.
   */
  private def checkWriterShape(): Unit = {
    val declared = traitDecls.get("Writer").map(_.methods.map(_.name)).getOrElse(Nil)

    if declared != List("write", "failed") then
      sys.error(s"the compiler's own writers assume 'Writer' declares 'write' then 'failed', " +
        s"but the prelude declares ${declared.mkString("'", "', '", "'")}")
  }

  /** Standard output as a sink. Recording `putbytes` is what brings it into the program: the table
   * this node's writer dispatches through ends at that function, and a prelude declaration nothing
   * reaches is neither analyzed nor emitted.
   */
  private def stdout(): TExpr = {
    funcsUsed += "putbytes"
    TStdout()
  }

  /** The specifier `print` and `str` hand a `Display`: no width, no precision, no justification,
   * since neither was written with one.
   */
  private def plainSpec: TExpr = specValue(0, -1, left = false)

  private def specValue(width: Int, prec: Int, left: Boolean): TExpr = {
    val s = instantiateStruct("FormatSpec", Nil)

    TStructNew(s, List(TIntLit(width, s.fields.head._2), TIntLit(prec, s.fields(1)._2), TBoolLit(left)))
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

  /** `va_copy(dst, src)` starts `dst` where `src` has reached, so the tail can be walked twice.
   *
   * It is what makes lending a walk usable: the callee advances the list it was handed, so a body
   * that wants to go on reading its own hands over a copy. C's rule that the destination must not
   * already be in use, and must be ended in its turn, is C's here too — nothing in a `va_list` says
   * which state it is in.
   */
  protected def vaCopy(args: List[Expr]): TExpr = {
    if args.length != 2 then err("'va_copy' takes two arguments, the va_list to start and the one to copy")
    TVaCopy(vaListArg("va_copy", args.head), vaListArg("va_copy", args(1)))
  }

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

  private def vaList(what: String, args: List[Expr]): TExpr = {
    if args.length != 1 then err(s"'$what' takes exactly one argument, the va_list it walks")
    vaListArg(what, args.head)
  }

  /** The `va_list` a form works on, as its address. Each works on the list itself rather than on a
   * copy — `va_arg` advances it — so what is handed over is where it lives.
   *
   * Two spellings reach that, and they are the same thing seen from the two sides of a call. A
   * `va_list` is a body's own walk, and its address is taken here exactly as `&ap` would take it. A
   * **`*va_list`** is a walk some other function lent (`12 §9`, *Handing a walk on*): it is already
   * the address, so it is passed along as it stands, and it need not be a place — a pointer
   * arriving as a parameter, out of a struct, or straight from a call all say the same thing.
   */
  private def vaListArg(what: String, arg: Expr): TExpr = {
    val t = analyzeExpr(arg)

    t.ty match
      case Type.Ptr(Type.VaList) => t
      case Type.VaList           => TAddrOf(requirePlace(t, arg, s"'$what'"), Type.Ptr(Type.VaList))
      case other                 => err(s"'$what' needs a va_list, not ${show(other)}")
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
