package io.github.edadma.sysl

/** Reading a **member**: a field, a property, or an attribute of a type's own name.
 *
 * The three are one form to the reader — `p.x`, `p.twice`, and `Colour::First` are all a name with
 * something selected out of it — and they differ only in what the thing on the left turns out to be.
 * A value on the left is a field or a property (`08`); a *type* on the left is an attribute or a
 * mistake, and which mistake it is decides what the message can usefully say, which is why a
 * constrained subtype, a trait and an enum each get their own wording rather than one about names.
 *
 * Split out of `ExprAnalysis`, whose expression dispatch calls in here.
 */
trait MemberExprAnalysis extends ExprSupport {

  /** `receiver.name` in all its readings. */
  protected def fieldExpr(expr: Field, expected: Option[Type]): TExpr = expr match
    case f: Field if throughModule(f).isDefined =>
      analyzeValueAt(throughModule(f).get, expected)

    case Field(Ident(written), f) if lookupOpt(written).isEmpty && typeKey(written).exists(enumDecls.contains) =>
      val n = typeKey(written).get

      if enumDecls(n).variants.exists(_.name == f) then
        constructVariant(Modules.qualify(Modules.moduleOf(n), f), Nil, expected)
      else
        memberDecls.get((n, f)) match
          case Some(m) if m.isProperty =>
            err(s"'$f' is a property of '${qn(n)}' — read it on a value, as 'value.$f'")
          case Some(m) if m.receiver.isDefined =>
            err(s"'$f' is a method of '${qn(n)}' — call it on a value, as 'value.$f(…)'")
          case Some(_) => err(s"'$f' is an associated function of '${qn(n)}' — call it with '$written.$f(…)'")
          case None    => err(s"enum '${qn(n)}' has no variant '$f'")

    // A struct name is not a value, so a member selected from it is one of the three that could
    // have been meant rather than a field read — which is what the name would otherwise be reported
    // as, in an undefined-name message naming the type instead of the member.
    case Field(Ident(written), f) if lookupOpt(written).isEmpty && typeKey(written).exists(structDecls.contains) =>
      val n = typeKey(written).get

      memberDecls.get((n, f)) match
        case Some(m) if m.isProperty =>
          err(s"'$f' is a property of '${qn(n)}' — read it on a value, as 'value.$f'")
        case Some(m) if m.receiver.isDefined =>
          err(s"'$f' is a method of '${qn(n)}' — call it on a value, as 'value.$f(…)'")
        case Some(_) => err(s"'$f' is an associated function of '${qn(n)}' — call it with '$written.$f(…)'")
        case None    => err(s"type '${qn(n)}' has no member '$f' — and '${qn(n)}' is a type, not a value")

    case Field(Ident(written), f)
        if lookupOpt(written).isEmpty && typeKey(written).exists(constrainedDecls.contains) =>
      constrainedMember(typeKey(written).get, written, f)

    case Field(Ident(written), f)
        if lookupOpt(written).isEmpty && typeKey(written).isEmpty && traitKey(written).isDefined =>
      traitMember(traitKey(written).get, f)

    // `T::Attr` — a type attribute read with no argument (`First`, `Last`). `T::Attr(x)` is a
    // `Call` over this node, handled beside the other call forms.
    case Field(receiver, f) =>
      val tr = autoDeref(analyzeExpr(receiver))
      tr.ty match
        // A trait object has no fields: the layout is exactly what it forgot. What it still has is
        // whatever the trait declares, and a property is declared to be read exactly like this.
        case _ if Type.erased(tr.ty) =>
          readTraitObjectProperty(tr, Type.erasedTrait(tr.ty).get, f)

        // A tuple's parts are named for their positions, so `t.0` arrives here as an ordinary field
        // selection. An index past the end is worth its own complaint: nothing about "no property
        // '3'" tells a reader that what they wrote was one part too far.
        case t: Type.Tuple =>
          val idx = t.fieldIndex(f)
          if idx >= 0 then TField(tr, idx, t.fields(idx)._2)
          else if f.forall(_.isDigit) then
            err(s"${show(t)} has ${quantity(t.fields.length, "part")}, so there is no '.$f' — " +
              s"the parts are numbered from 0")
          else readProperty(tr, t, f)

        case s: Type.Struct =>
          val idx = s.fieldIndex(f)
          if idx >= 0 then
            checkFieldVisible(s.base, f)
            TField(tr, idx, s.fields(idx)._2)
          else readProperty(tr, s, f)

        // An enum has no fields to shadow a member, so every name read off one is a property.
        case e: Type.Enum => readProperty(tr, e, f)

        // A bound promises behaviour, and a property is behaviour spelled like a field — so this is
        // a bound's to license after all, and it is checked at the definition like every other use
        // of a parameter. What no bound reaches is a real *field*: that is layout, which is `10 §5`'s
        // rule and the complaint left when nothing declares a property of the name.
        case a: Type.Abstract => readBoundProperty(a, tr, f)
        // `len`, `bytes` and `chars` are the compiler-provided members: `len` a property on every
        // array, slice, and string, `bytes` the reinterpretation of a string's three words
        // as a `[]u8`, dropping only the validity guarantee, and `chars` a cursor over the scalar
        // values those bytes encode. `chars` is the one that cannot be a view — the decoding is
        // what makes the characters — so it is the prelude's `Chars`, positioned at the start.
        case _: Type.Array | _: Type.View if f == "len" => TLen(tr)
        case Type.Str if f == "bytes"                   => TBytes(tr)
        case Type.Str if f == "chars"                   => callLibrary("chars_of", TBytes(tr))

        // `copy` is the one compiler-provided member of a string that is a *method*, so reading it
        // without the parentheses is told what a user type's method is told. The parentheses are
        // what say it allocates and walks the bytes (`08 § Property or method`), which is exactly
        // the information this line was missing.
        case Type.Str if f == "copy" =>
          err("'copy' is a method of 'string' — call it with 'copy()', since it allocates and " +
            "copies the bytes rather than naming what is already there")

        // Everything about the object is behind `get()`, including whether there still is one, so a
        // weak reference has no fields of its own to offer and none of the referent's either.
        case w: Type.Weak =>
          err(s"a ${show(w)} may be gone, so nothing is read off one directly — 'get()' hands back " +
            s"'Option[&${Type.show(w.inner)}]', and '$f' is read off what is inside it")

        // Any other type reaches its own members too, since an `impl` may be written for one and a
        // trait may ask for a property. A name none of them supplies is the older complaint, which
        // is the better one there: nothing about `x.foo` on an `int` says a property was meant.
        case other if hasMember(other, f) => readProperty(tr, other, f)
        case other                        => err(s"cannot read field '$f' of ${show(other)}")

  /** `T::Attr` — an attribute of a type rather than of a value (`16`). */
  protected def typeAttrExpr(expr: TypeAttr): TExpr = expr match
    case TypeAttr(Ident(name), attr) if lookupOpt(name).isEmpty && typeKey(name).isDefined =>
      typeAttr(typeKey(name).get, attr, Nil)

    case TypeAttr(_, attr) =>
      err(s"'::$attr' is a type attribute, so its left side must be a type name")

  /** A constrained subtype's name with a member selected from it. The name is a **type**, and this
   * case exists so that it is never reported as an undefined *name* — which is what analyzing the
   * receiver would fall out to, and the one thing about a declared type that is certainly false.
   *
   * Three things could have been meant. A method or a property is reached on a value, exactly as a
   * struct's is. `try` is the one everybody writes first, because a simple enum has one, and the
   * answer is that a constrained type deliberately does not: the cast checks and traps, and the
   * question is asked with `Valid`. Anything else is a member the type does not have, and what it
   * does have is spelled with `::`.
   */
  protected def constrainedMember(key: String, written: String, f: String): Nothing = {
    val c      = resolveConstrained(key)
    val ranged = Type.underlying(c.base).isInstanceOf[Type.Integer] && c.lo.isDefined

    memberDecls.get((key, f)) match
      case Some(m) if m.isProperty =>
        err(s"'$f' is a property of '${qn(key)}' — read it on a value, as 'value.$f'")
      case Some(m) if m.receiver.isDefined =>
        err(s"'$f' is a method of '${qn(key)}' — call it on a value, as 'value.$f(…)'")
      case _ if f == "try" =>
        err(s"'${qn(key)}' is a constrained type and has no 'try': a value outside its range is a " +
          s"mistake in the code that produced it rather than a condition to handle, so '$written(x)' " +
          "checks it and traps" +
          (if ranged then s", and '$written::Valid(x)' asks the question without trapping" else ""))
      case _ =>
        err(s"'${qn(key)}' is a type, not a value, and has no member '$f'" +
          (if ranged then
             s" — what a constrained type offers under its own name is written with '::': " +
               s"'$written::First', '$written::Last', '$written::Valid(x)', '$written::Succ(x)', " +
               s"'$written::Pred(x)'"
           else ""))
  }

  /** A trait's name with a member selected from it, which is the same shape of mistake one type
   * over. A trait is not a value and is not a type on its own: what its members are reached
   * through is a value of an implementing type, or a trait object.
   */
  protected def traitMember(key: String, f: String): Nothing =
    traitDecls(key).methods.find(_.name == f) match
      case Some(m) if m.isProperty =>
        err(s"'$f' is a property of the trait '${qn(key)}' — read it on a value of a type that " +
          s"implements '${qn(key)}', or on a '&${qn(key)}'")
      case Some(_) =>
        err(s"'$f' is a member of the trait '${qn(key)}' — call it on a value of a type that " +
          s"implements '${qn(key)}', or on a '&${qn(key)}'")
      case None =>
        err(s"'${qn(key)}' is a trait, not a value, and declares no member '$f'")

  /** A type attribute `T::Attr`, with the arguments a call form supplied (empty for the bare form).
   * Dispatched on the kind of type `T` is: a constrained subtype (`16 §5`) or a simple enum
   * (`09 §2`), which are the two that have questions to answer about their own value sets.
   */
  protected def typeAttr(key: String, attr: String, args: List[Expr]): TExpr =
    if constrainedDecls.contains(key) then constrainedAttr(resolveConstrained(key), key, attr, args)
    else if enumDecls.contains(key) then
      if enumDecls(key).tparams.nonEmpty then
        err(s"'${qn(key)}' is generic, so '${qn(key)}::$attr' has no single enum to read")
      enumAttr(instantiateEnum(key, Nil), key, attr, args)
    else err(s"'${qn(key)}' has no type attributes")

  /** The attributes a constrained integer subtype exposes: its bounds (`First`/`Last`), the total
   * membership test (`Valid`), and the trapping steps (`Succ`/`Pred`).
   */
  protected def constrainedAttr(c: Type.Constrained, key: String, attr: String, args: List[Expr]): TExpr = {
    val base = c.base match
      case i: Type.Integer => i
      case other           => err(s"'${qn(key)}::$attr' needs an integer subtype, not ${show(other)}")

    def ranged: (BigDecimal, BigDecimal) = (c.lo, c.hi) match
      case (Some(lo), Some(hi)) => (lo, hi)
      case _                    => err(s"'${qn(key)}::$attr' needs a 'within' range")

    def noArgs(): Unit = if args.nonEmpty then err(s"'${qn(key)}::$attr' takes no arguments")

    def oneArg(): TExpr =
      if args.length != 1 then err(s"'${qn(key)}::$attr' takes exactly one argument")
      val x = analyzeExpr(args.head, Some(base))
      if disagree(x.ty, base) then err(s"'${qn(key)}::$attr' takes a ${show(base)}, not ${show(x.ty)}")
      x

    attr match
      case "First" => noArgs(); TIntLit(ranged._1.toBigInt, base)
      case "Last"  => noArgs(); val (_, hi) = ranged; TIntLit((if c.exclusiveHi then hi - 1 else hi).toBigInt, base)
      case "Valid" => val x = oneArg(); ranged; TConstrainedValid(x, c)
      case "Succ"  => val x = oneArg(); ranged; TConstrainedStep(x, c, up = true, base)
      case "Pred"  => val x = oneArg(); ranged; TConstrainedStep(x, c, up = false, base)
      case "Range" => err(s"'${qn(key)}::Range' is only meaningful as the iterable of a 'for' loop")
      case _       => err(s"'${qn(key)}' has no attribute '$attr'")
  }

  /** The attributes a simple enum exposes: its endpoints (`First`/`Last`), the ordinal maps
   * (`Pos` a value to its 0-based position, `Val` a position back to its value), the neighbouring
   * values (`Succ`/`Pred`), and the name maps (`Image` a value to its name, `Value` a name to its
   * value). All but `First`/`Last` carry an operand, and the ones that could be handed something
   * out of range (`Val`, `Succ` at the end, `Pred` at the start, `Value` with no such name) trap.
   */
  protected def enumAttr(en: Type.Enum, key: String, attr: String, args: List[Expr]): TExpr = {
    if !en.simple then err(s"'${qn(key)}::$attr' needs a simple enum, and '${qn(key)}' carries data")

    def noArgs(): Unit = if args.nonEmpty then err(s"'${qn(key)}::$attr' takes no arguments")

    def oneArg(want: Type): TExpr =
      if args.length != 1 then err(s"'${qn(key)}::$attr' takes exactly one argument")
      val x = analyzeExpr(args.head, Some(want))
      if disagree(x.ty, want) then err(s"'${qn(key)}::$attr' takes a ${show(want)}, not ${show(x.ty)}")
      x

    attr match
      case "First" => noArgs(); TIntLit(BigInt(en.variants.head.tag), en)
      case "Last"  => noArgs(); TIntLit(BigInt(en.variants.last.tag), en)
      case "Pos"   => TEnumAttr("Pos", en, oneArg(en), Type.Int)
      case "Val"   => TEnumAttr("Val", en, oneArg(Type.Int), en)
      case "Succ"  => TEnumAttr("Succ", en, oneArg(en), en)
      case "Pred"  => TEnumAttr("Pred", en, oneArg(en), en)
      case "Image" => TEnumAttr("Image", en, oneArg(en), Type.Str)
      case "Value" => TEnumAttr("Value", en, oneArg(Type.Str), en)
      case _       => err(s"'${qn(key)}' has no attribute '$attr'")
  }

  /** `value.name` where `name` is not a field: a computed property, which reads with no
   * parentheses and so is spelled exactly as a field is, with an implicit by-value receiver.
   *
   * The receiver may be of **any** type, because every type has an owner key its members are filed
   * under and a trait may declare a property for an `impl` to supply — so `21.twice` reads one
   * exactly as `p.twice` does, through the member the implementation was lowered to.
   *
   * The absent-member wording is the one difference between the kinds: a struct's `x` could have
   * been either a field or a property, while an enum and a built-in have no fields to have meant.
   */
  protected def readProperty(tr: TExpr, ty: Type, f: String): TExpr = {
    val (base, _) = memberKey(ty, f)
    // A property takes no arguments, so where two implementations of one trait both supply one there
    // is nothing to say which is meant — which `pickOverload` reports as the call it is.
    val chosen = pickOverload(ty, base, f, Nil)

    memberDecls.get((base, chosen)) match
      case Some(m) if m.isProperty =>
        checkMemberVisible(base, chosen, m)
        val fname      = memberFuncName(ty, chosen)
        val (_, rtype) = funcInsts(fname)
        funcsUsed += fname
        TCall(fname, List(tr), rtype)
      case Some(_) => err(s"'$f' is a method of '${show(ty)}' — call it with '$f(…)'")
      case None =>
        ty match
          case _: Type.Struct => err(s"'${show(ty)}' has no field or property '$f'")
          case _              => err(s"'${show(ty)}' has no property '$f'")
  }
}
