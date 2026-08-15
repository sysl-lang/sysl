package sh.sysl

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
        constructVariant(Modules.qualify(Modules.moduleOf(n), f), Nil, expected, Some(n))
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

    /** `A.len` — how many types a **pack** stands for (`10 §10`), which is what an unrolled loop
     * counts against. It is a compile-time integer and folds into its use as one, exactly as an
     * array's length does where the length is known: the tuple `(..A)` denotes is the same length,
     * so the two spellings agree by construction.
     *
     * `len` and no other name, because a pack is not a value and has no other member to read. A pack
     * bound to nothing cannot reach here — the substitution holds one wherever the body is walked.
     */
    case Field(Ident(written), f)
        if lookupOpt(written).isEmpty && tsubst.get(written).exists(_.isInstanceOf[Type.Pack]) =>
      val pack = tsubst(written).asInstanceOf[Type.Pack]

      if f == "len" then TIntLit(BigInt(pack.elems.length), Type.usize)
      else
        err(s"'..$written' is a type pack, and 'len' — how many types it stands for — is the whole " +
          s"of what one offers; there is no '$written.$f'")

    case Field(Ident(written), f)
        if lookupOpt(written).isEmpty && typeKey(written).exists(constrainedDecls.contains) =>
      constrainedMember(typeKey(written).get, written, f)

    case Field(Ident(written), f)
        if lookupOpt(written).isEmpty && typeKey(written).isEmpty && traitKey(written).isDefined =>
      traitMember(traitKey(written).get, f)

    // A type in the position a value would be read from. What a type offers under its own name is an
    // associated function and nothing else — a property and a method are read on a value — so the
    // parentheses are what is missing, and a name that offers nothing is told that it is a type.
    case Field(Ident(written), f)
        if lookupOpt(written).isEmpty && typeKey(written).isEmpty && typeNamed(written).isDefined =>
      typeNamed(written).get match
        // Reported rather than raised, because the abstract pass drops an ordinary complaint: this
        // one is about what a bound licenses, which is exactly what that pass is for.
        case a: Type.Abstract =>
          boundAssociated(a, f) match
            case Some(tr) =>
              reported(err(s"'$f' is an associated function of '$tr' — call it with '$written.$f()'"))
            // A property, a method, or nothing a bound licenses at all: the call form's own
            // complaints, each of which says the right thing about a read as well.
            case None => callBoundAssociated(a, f, Nil)
        case concrete =>
          memberDecls.get((memberKey(concrete, f)._1, f)) match
            case Some(m) if m.recvMode.isEmpty =>
              err(s"'$f' is an associated function of '${show(concrete)}' — call it with '$written.$f()'")
            case Some(_) =>
              err(s"'$f' is reached on a value of ${show(concrete)}, and '$written' is the type itself")
            case None =>
              err(s"'$written' is a type, not a value, and has no associated function '$f'")

    // `T::Attr` — a type attribute read with no argument (`First`, `Last`). `T::Attr(x)` is a
    // `Call` over this node, handled beside the other call forms.
    case Field(receiver, f) =>
      val outer = analyzeExpr(receiver)
      val tr    = autoDeref(outer)
      // A property is a method with the parentheses dropped, so it is reached through a value and
      // needs the same question put to the source that a call does.
      val via   = receiverBound(receiver)
      tr.ty match
        // A trait object has no fields: the layout is exactly what it forgot. What it still has is
        // whatever the trait declares, and a property is declared to be read exactly like this.
        case _ if Type.erased(tr.ty) =>
          readTraitObjectProperty(tr, Type.erasedTrait(tr.ty).get, f)

        // A tuple's parts are named for their positions, so `t.0` arrives here as an ordinary field
        // selection. An index past the end is worth its own complaint: nothing about "no property
        // '3'" tells a reader that what they wrote was one part too far.
        case t: Type.Tuple =>
          // A **compile-time constant** selects a part by its value, which is what makes one written
          // line cover parts of different types inside a `for const` (`10 §10`). It is the same
          // selection `t.0` already is, with the position arriving as a constant rather than as a
          // literal — so an index past the end is the same complaint, said about the name that
          // carried it.
          constSelector(f) match
            case Some(v) if v >= 0 && v < t.fields.length => TField(tr, v, t.fields(v)._2)
            case Some(v) =>
              err(s"'$f' is $v here, and ${show(t)} has ${quantity(t.fields.length, "part")} — " +
                s"the parts are numbered from 0")
            case None =>
              val idx = t.fieldIndex(f)
              if idx >= 0 then TField(tr, idx, t.fields(idx)._2)
              else if f.forall(_.isDigit) then
                err(s"${show(t)} has ${quantity(t.fields.length, "part")}, so there is no '.$f' — " +
                  s"the parts are numbered from 0")
              else readProperty(tr, t, f, via)

        // A struct's fields have names, and a number does not address one — so a compile-time index
        // is refused here rather than falling through to a complaint about a missing property, which
        // would send a reader looking for a field they never meant to name (`10 §10`).
        case s: Type.Struct if constSelector(f).isDefined && s.fieldIndex(f) < 0 =>
          err(s"'$f' is a compile-time index, and ${show(s)} is a struct — its fields are reached " +
            "by name, and a position addresses the parts of a tuple")

        case s: Type.Struct =>
          val idx = s.fieldIndex(f)
          if idx >= 0 then
            checkFieldVisible(s.base, f)
            // Whatever qualifier the field was declared with stays in the struct's field list and
            // comes off here: reading a register yields an ordinary value (`03 § Device memory`).
            TField(tr, idx, Type.unqualified(s.fields(idx)._2))
          else readProperty(tr, s, f, via)

        // An enum has no fields to shadow a member, so every name read off one is a property.
        case e: Type.Enum => readProperty(tr, e, f, via)

        // A bound promises behaviour, and a property is behaviour spelled like a field — so this is
        // a bound's to license after all, and it is checked at the definition like every other use
        // of a parameter. What no bound reaches is a real *field*: that is layout, which is `10 §5`'s
        // rule and the complaint left when nothing declares a property of the name.
        case a: Type.Abstract => readBoundProperty(a, tr, f)
        // `len`, `bytes` and `chars` are the compiler-provided members: `len` a property on every
        // array, slice, and string, `bytes` the reinterpretation of a string's three words
        // as a `[]u8`, dropping only the validity guarantee, and `chars` a cursor over the scalar
        // values those bytes encode. `chars` is the one that cannot be a view — the decoding is
        // what makes the characters — so it is the library's `Chars`, positioned at the start.
        case _: Type.Array | _: Type.View if f == "len" => TLen(tr, Type.usize)
        // A vector's `len` is its lane count, which is part of its type — so this is a constant
        // folded here rather than anything read, exactly as an array's is in the emitter.
        case v: Type.Vector if f == "len" => TIntLit(BigInt(v.length), Type.usize)
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
        case other if hasMember(other, f) => readProperty(tr, other, f, via)

        // Still a mode after the one automatic dereference, so the receiver carries more
        // indirection than selection reaches through (`03 § Places`). Falling through to the line
        // below names what is *left* after that dereference — a type the reader never wrote — and
        // says the field does not exist, when it does and the shorthand simply stops short of it.
        case _: Type.Ptr | _: Type.Ref =>
          err(s"selection reaches through one level of indirection and ${show(outer.ty)} has more, " +
            s"so the rest is written: '(*x).$f' reads '$f' off the ${show(tr.ty)} it leaves")

        case other => err(s"cannot read field '$f' of ${show(other)}")

  /** `T::Attr` — an attribute of a type rather than of a value (`16`).
   *
   * A **type parameter** is tried first, because a body's own parameter shadows anything a
   * surrounding scope declares under the same name — the same order `typeNamed` uses for `T(x)`.
   */
  protected def typeAttrExpr(expr: TypeAttr): TExpr = expr match
    case TypeAttr(Ident(name), attr) if lookupOpt(name).isEmpty && tsubst.contains(name) =>
      parameterAttr(name, tsubst(name), attr, Nil)

    case TypeAttr(Ident(name), attr) if lookupOpt(name).isEmpty && typeKey(name).isDefined =>
      typeAttr(typeKey(name).get, attr, Nil)

    case TypeAttr(Ident(name), attr) if lookupOpt(name).isEmpty && builtinInteger(name).isDefined =>
      integerAttr(builtinInteger(name).get, name, attr, Nil)

    case TypeAttr(_, attr) =>
      err(s"'::$attr' is a type attribute, so its left side must be a type name")

  /** `T::Min` and `T::Max` where `T` is a **type parameter**, answered from what the instantiation
   * bound it to (`10`, `16 §5`).
   *
   * **The substitution is the same one three other forms already read.** `sizeof(T)` resolves its
   * operand through `tsubst`, `T(x)` finds its target through `typeNamed`, and `T.f(…)` reaches the
   * parameter's bounds — so a parameter was reachable in expression position by every route but this
   * one, and `ConstFolding` had been folding `T::Max` in an array bound and an `@assert` all along.
   * What was missing was the case, not the mechanism.
   *
   * **Only the two bounds, and that is a limit the abstract walk imposes.** The body is checked once
   * with `T` standing at an `Abstract`, and that walk has to hand back something typed — `Min` and
   * `Max` both answer *in `T`*, so the one placeholder below is right for both and cannot be wrong.
   * `Valid` answers a `bool`, `Pos` a `usize`, `Image` a `string`: admitting those means restating
   * each attribute's result type a second time, where the two copies could drift. They stay
   * reachable on a written type name, which is where they have always been asked.
   *
   * **No bound is required of `T`**, following `sizeof(T)`: a parameter given something with no
   * maximum is reported at the instantiation that gave it one, naming both.
   */
  protected def parameterAttr(name: String, bound: Type, attr: String, args: List[Expr]): TExpr = {
    if attr != "Min" && attr != "Max" then
      err(s"'$name' is a type parameter, and the only attributes one answers are '$name::Min' and " +
        s"'$name::Max' — '::$attr' is asked on a written type name, where the type it belongs to is " +
        "known while the body is being checked rather than once per instantiation")
    if args.nonEmpty then err(s"'$name::$attr' takes no arguments")

    bound match
      // The walk that checks the body, where `T` stands for itself. There is no width to answer with
      // and none is wanted: every instantiation supplies one, and this tree is discarded. It is the
      // same deferral `sizeof(T)` takes, and the literal is typed as the parameter so that a body
      // returning `T::Max` still agrees with its own declared result here.
      case a: Type.Abstract => TIntLit(0, a)

      // A ranged subtype answers in itself, exactly as it does under its own name — which is what
      // lets a generic over `Age` hand back an `Age` rather than the `int` it is stored as.
      case c: Type.Constrained if Type.underlying(c).isInstanceOf[Type.Integer] =>
        constrainedAttr(c, c.name, attr, Nil)

      case i: Type.Integer => integerAttr(i, name, attr, Nil)

      case other =>
        err(s"'$name::$attr' needs an integer type, and '$name' is ${show(other)} here — a maximum " +
          "is a property of a range of numbers, so a type argument without one has none to answer " +
          "with")
  }

  /** The integer type a **built-in** scalar name stands for, which is where `Min`/`Max` are asked.
   *
   * Separate from `typeKey` because that resolves *declared* types only — a struct, an enum, a
   * constrained subtype. `u32` and `u10000` are declared nowhere: the `iN`/`uN` family is open and
   * recognised by width, so it has no table to be in and no key to be looked up by.
   */
  protected def builtinInteger(written: String): Option[Type.Integer] =
    scalarType(written).collect { case i: Type.Integer => i }

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
      case Some(_) =>
        err(s"'$f' is an associated function of '${qn(key)}' — call it with '$written.$f(…)'")
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

    /** One extreme of the subtype: the end the range names where it names one, the base's otherwise.
     * A `where` predicate narrows the type without saying to what, so an end it leaves open is
     * refused rather than answered by the base — `type Small = int where self < 10` would otherwise
     * report `int`'s maximum as a `Small`, which is a value the produce site traps on.
     */
    def extreme(end: Option[BigDecimal], ofBase: => BigInt): BigInt = end match
      case Some(v)                  => v.toBigInt
      case None if c.predFn.isEmpty => ofBase
      case None                     => err(s"'${qn(key)}::$attr' needs a 'within' range")

    def noArgs(): Unit = if args.nonEmpty then err(s"'${qn(key)}::$attr' takes no arguments")

    def oneArg(want: Type): TExpr =
      if args.length != 1 then err(s"'${qn(key)}::$attr' takes exactly one argument")
      val x = analyzeExpr(args.head, Some(want))
      if disagree(x.ty, want) then err(s"'${qn(key)}::$attr' takes a ${show(want)}, not ${show(x.ty)}")
      x

    // Every attribute but `Valid` speaks the subtype, exactly as a simple enum's do below. A bound
    // of `T` is a `T` and the step from one `T` is another, so a **derived** subtype can use its own
    // attributes without casting through the base it was declared `new` to stay out of — and for a
    // transparent one the two agree anyway (`repr`), so nothing about it changes. `Valid` is the one
    // that takes the base, because asking whether a value is a `T` is only a question for something
    // that is not yet one.
    attr match
      case "First" => noArgs(); TIntLit(ranged._1.toBigInt, c)
      case "Last"  => noArgs(); val (_, hi) = ranged; TIntLit((if c.exclusiveHi then hi - 1 else hi).toBigInt, c)
      // A ranged subtype answers **both** pairs, and they are the same two numbers here. They are
      // different questions that happen to agree: `First`/`Last` are the ends of the range as
      // written, `Min`/`Max` the extremes the type can hold. Refusing `Min` on the one type whose
      // whole purpose is bounds would be the odd outcome — and a reader who learned `Min` on `u32`
      // should not have to learn that a subtype of `u32` renamed it.
      //
      // **An end the declaration does not narrow is the base's**, because that is what the type can
      // hold. This is the case a `c type` (`15 §7`) is always in — it lowers to a transparent subtype
      // carrying no range at all — and asking a measured `size_t` for its maximum is asking about the
      // integer C said it is. A `where` predicate is the exception and keeps the refusal: it narrows
      // the type without saying where to, so its extremes are not something to read off a
      // declaration. `First` and `Last` still need a range whatever the predicate says, and that
      // asymmetry is the same one the paragraph above draws — they name the ends of a range as
      // *written*, and an unranged subtype has none.
      case "Min" => noArgs(); TIntLit(extreme(c.lo, Type.minOf(base)), c)
      case "Max" =>
        noArgs()
        TIntLit(extreme(c.hi.map(hi => if c.exclusiveHi then hi - 1 else hi), Type.maxOf(base)), c)
      case "Valid" => val x = oneArg(base); ranged; TConstrainedValid(x, c)
      case "Succ"  => val x = oneArg(c); ranged; TConstrainedStep(x, c, up = true, c)
      case "Pred"  => val x = oneArg(c); ranged; TConstrainedStep(x, c, up = false, c)
      case "Range" => err(s"'${qn(key)}::Range' is only meaningful as the iterable of a 'for' loop")
      case _       => err(s"'${qn(key)}' has no attribute '$attr'")
  }

  /** The attributes a **built-in integer type** exposes: its bounds, `Min` and `Max`.
   *
   * A `within`-ranged subtype has answered `First`/`Last` since `16 §5`, and the integer it is
   * declared *over* answered nothing — so a type derived from `u32` could state its bounds and
   * `u32` could not. This closes that, and does it under different names on purpose: `First` and
   * `Last` are the ends of a *declared sequence*, `Min` and `Max` the numeric extremes a type can
   * hold. They agree on an integer and part company on an enum, whose first-declared variant need
   * not carry the smallest discriminant.
   *
   * Both fold to a literal here, which is what lets them appear in a `const` initializer and in an
   * `@assert` condition — neither admits a call (`13 §5`), so an attribute that resolved as one
   * would be unusable in the two places bounds are most wanted.
   */
  protected def integerAttr(i: Type.Integer, written: String, attr: String, args: List[Expr]): TExpr = {
    def noArgs(): Unit = if args.nonEmpty then err(s"'$written::$attr' takes no arguments")

    attr match
      case "Min" => noArgs(); TIntLit(Type.minOf(i), i)
      case "Max" => noArgs(); TIntLit(Type.maxOf(i), i)
      case "First" | "Last" =>
        err(s"'$written' is an integer, not a declared sequence — its extremes are " +
          s"'$written::Min' and '$written::Max'. 'First' and 'Last' name the ends of an enum's " +
          s"variants or of a 'within' range, which is a different question")
      case _ =>
        err(s"'$written' has no attribute '$attr' — an integer type answers '$written::Min' and " +
          s"'$written::Max'")
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
  /** The value a selector stands for where it names a **compile-time constant** rather than a field
   * — the loop variable of a `for const`, or a value parameter (`10 §9`, `10 §10`).
   *
   * A local of the same name is not consulted and does not shadow this, because a selector is not a
   * name being read: `t.i` asks for a part of `t`, and what a variable called `i` happens to hold at
   * run time could not be the answer. That is the one place this differs from an `Ident`, which asks
   * the scope first for exactly the opposite reason.
   */
  protected def constSelector(f: String): Option[Int] =
    tsubst.get(f).collect {
      case Type.ConstArg(v, _: Type.Integer) if v.isValidInt => v.toInt
    }

  protected def readProperty(tr: TExpr, ty: Type, f: String, via: Set[String] = Set.empty): TExpr = {
    val (base, _) = memberKey(ty, f)
    // A property takes no arguments, so where two implementations of one trait both supply one there
    // is nothing to say which is meant — which `pickOverload` reports as the call it is.
    val chosen = pickOverload(ty, base, f, Nil, via)

    memberDecls.get((base, chosen)) match
      case Some(m) if m.isProperty =>
        checkMemberVisible(base, chosen, m)
        val fname = memberFuncName(ty, chosen)
        // A property whose own signature did not resolve is registered and has no lowered form, so
        // the read is abandoned in silence — the mistake has been reported at the declaration, where
        // it belongs. `MethodCalls` carries the same line for the same reason, and between them they
        // are the whole of the surface a half-registered member is reachable through.
        val (_, rtype) = funcInsts.getOrElse(fname, poisoned())
        funcsUsed += fname
        TCall(fname, List(tr), rtype)
      case Some(_) => err(s"'$f' is a method of '${show(ty)}' — call it with '$f(…)'")
      case None =>
        ty match
          case _: Type.Struct => err(s"'${show(ty)}' has no field or property '$f'")
          case _              => err(s"'${show(ty)}' has no property '$f'")
  }
}
