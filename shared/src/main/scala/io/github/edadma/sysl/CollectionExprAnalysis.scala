package io.github.edadma.sysl

/** The expressions that build a sequence or reach into one: an array literal, a repeat, a slice,
 * and a subscript.
 *
 * They belong together because they share one question — how many elements are there, and is this
 * index one of them. An array's count is part of its type and a view's is carried beside its
 * elements, so the same subscript is checked against a constant in one case and against a loaded
 * word in the other; a `*T` has neither and is checked against nothing, which is what `*T` is.
 *
 * Split out of `ExprAnalysis`, whose expression dispatch calls in here.
 */
trait CollectionExprAnalysis extends ExprSupport {

  /** An array form, a repeat, a slice, or a subscript. The parameter names the four so that the
   * match below is exhaustive over exactly what the dispatch sends here and nothing else.
   */
  protected def sequenceExpr(expr: ArrayLit | ArrayFill | Index, expected: Option[Type]): TExpr = expr match
    case ArrayLit(elems) =>
      val elemExp = expected.flatMap(elementWanted)
      val ts      = elems.map(analyzeExpr(_, elemExp))

      for t <- ts do
        if Type.noValue(t.ty) then err(s"an array cannot hold ${show(t.ty)} values")
        if t.ty != ts.head.ty then
          err(s"an array literal needs one element type, got ${show(ts.head.ty)} and ${show(t.ty)}")

      val elemTy = ts.headOption.map(_.ty).orElse(elemExp).getOrElse(
        err("an empty array literal takes its element type from its context, and there is none here"),
      )

      expected match
        // The buffer is storage this expression makes, so nobody else can be holding a writable
        // view of it and it takes whichever form was asked for. Under a `[]const T` that is a
        // buffer written by its own literal and never again.
        case Some(Type.Slice(_, ro)) => TBufLit(ts, Type.Slice(elemTy, ro))
        case _                       => TArrayLit(ts, Type.Array(ts.length, elemTy))

    // `[v; n]` — the form for an array whose element type has no zero, or has one that is not the
    // wanted starting value. The value is evaluated **once** and copied into every element, which
    // is what makes `[f(); 8]` mean one call rather than eight.
    //
    // What is being asked for decides which of the two things this is (`07 §Storage sized while
    // running`). Under a `[N]T` the count is part of the type and so a compile-time constant; under
    // a `[]T` the length is not in the type at all, so the count is an ordinary expression and the
    // elements are storage of their own that the view owns.
    case ArrayFill(value, count) =>
      val elemExp = expected.flatMap(elementWanted)
      val tv      = analyzeExpr(value, elemExp)

      if Type.noValue(tv.ty) then err(s"an array cannot hold ${show(tv.ty)} values")

      expected match
        case Some(Type.Slice(_, ro)) =>
          val tc = analyzeExpr(count)

          // A count is an index's twin, so it takes a transparent subtype for the same reason one
          // does — and refuses a derived one for the same reason too, and is held to the same width
          // for the same reason: a truncated count would size the storage by a number nobody wrote.
          val checked = Type.repr(tc.ty) match
            case i: Type.Integer => checkedIndexWidth(tc, i)
            case _ => err(s"a repeat count is a number of elements, and ${show(tc.ty)} is not an integer")

          TBufFill(tv, checked, Type.Slice(tv.ty, ro))

        case _ =>
          val n = constInt(count) match
            case Some(v) if v >= 0 && v.isValidInt => v.toInt
            case Some(v)                           => err(s"an array cannot have $v elements")
            case None =>
              err("an array's repeat count must be a constant, since it is the array's bound — a " +
                "literal, or a 'const' naming one. A count computed while running makes storage " +
                "instead, which is written where a '[]T' is expected")

          TArrayFill(tv, Type.Array(n, tv.ty))

    // A range subscript takes a view. The receiver is left *undereferenced* on purpose: for a
    // heap array the reference is both where the elements are and what keeps them alive, and
    // evaluating it once is what makes those the same object.
    case Index(receiver, RangeExpr(lo, hi, inclusive)) =>
      if !inclusive && hi.isEmpty then err("an open-ended slice is written 'a[lo..]'")

      val tr = analyzeExpr(receiver)

      // A view of read-only storage is read-only, which is the whole of what it takes to make this
      // safe: the view records the property, so it carries it wherever it is bound or passed rather
      // than losing it at the end of the expression that made it. Slicing a `val` was refused for
      // exactly as long as there was no type to say that in.
      //
      // A `[]const T` the context asked for is answered directly, the way the two forms above answer
      // one, rather than by making a writable view and then giving the ability up. The result is the
      // same view either way; what it changes is that a writable one is never made, so asking for a
      // read-only view of storage no writable view may be taken of is the ordinary thing it reads as.
      val viewIsConst = readOnly(tr) || expected.exists(Type.readOnlyView)

      val elem = tr.ty match
        case Type.Ref(Type.Array(_, e), false) => e
        case Type.Ref(Type.Array(_, _), true) =>
          err("a slice does not record whether its owner's count is atomic, so a '&sync' array cannot be sliced")
        case w: Type.View               => w.elem
        case Type.Array(_, e)           => e
        case Type.Ptr(Type.Array(_, e)) => e

        // A view of a `*T` region — the shape every C function that fills a buffer hands back. The
        // pointer carries no length, so the end must be written and is taken on trust; that is the
        // same trust `*p` already asks for, and the resulting view owns nothing (`05`).
        case Type.Ptr(e) =>
          if hi.isEmpty then
            err(s"a ${show(tr.ty)} carries no length, so a view of one needs its end written — " +
              "'p[0..<n]' with the number of elements that are really there")
          e

        // A type read by a subscript through `Index` cannot be *sliced* through it, and `14 §7`
        // says why: the index would have to be a range, and a range is not yet a type a program can
        // name, so there is nothing for the trait's argument to be. Somebody who has written an
        // `Index` and is reaching for the neighbouring form is owed that rather than "cannot
        // slice", which reads as though their type were the wrong shape for an operation that
        // exists.
        case other if indexes(Library.key("Index"), other) =>
          err(s"${show(other)} is read by a subscript through '${qn(Library.key("Index"))}', but slicing " +
            "through the trait is not built: the index would have to be a range, and a range is not yet " +
            "a type a program can name")

        case other => err(s"cannot slice ${show(other)}")

      // Part of a string is a string, not a `[]u8` — the bytes between two character boundaries
      // are still well-formed UTF-8, which is what the check at those boundaries is for.
      //
      // Anything else is a slice, read-only when what it views is: `val` storage, or another view
      // that had already given the ability up. Re-slicing is where a bit like this is easiest to
      // drop, and dropping it would make `xs[1..]` the way around `xs`.
      val viewTy =
        if tr.ty == Type.Str then Type.Str
        else Type.Slice(elem, readOnly = viewIsConst || Type.readOnlyView(tr.ty))

      // A view that may be written is an alias like any other, so it is refused where a `&` would be:
      // over storage inside a struct whose invariant reads it (`16 §6`).
      checkSliceable(tr, viewTy)
      TSlice(tr, lo.map(bound), hi.map(bound), inclusive, viewTy)

    case Index(receiver, index) =>
      val raw = analyzeExpr(receiver)

      // `p[i]` on a raw pointer is C's, unchecked: the pointer is a bare address, so the subscript
      // is the address arithmetic C spells the same way. It is read off the *undereferenced*
      // receiver — a pointer to an array keeps the checked form below, because that one has a
      // length in its type to check against.
      val tr = raw.ty match
        case Type.Ptr(_: Type.Array) => autoDeref(raw)
        case _: Type.Ptr             => raw
        case _                       => autoDeref(raw)

      Type.element(tr.ty) match
        case Some(elem) =>
          val ti = analyzeExpr(index, Some(Type.Usize))

          // A transparent constrained subtype stands where its base does, so an `Index within 0..<n`
          // indexes without a cast. A derived one does not: `new` is nominal, and reaching the base
          // is exactly what a written conversion is for.
          Type.repr(ti.ty) match
            case i: Type.Integer => TIndex(tr, checkedIndexWidth(ti, i), elem)
            case other           => err(s"an index must be an integer, not ${show(other)}")

        // A type with no elements of its own is indexed through `Index`, whose one method the
        // subscript *is* (`14 §3`). The index is not held to being an integer here: what a
        // container is read by is the trait's own argument, and a type that indexes by something
        // else is implementing a different `Index` rather than misusing this one.
        case None if indexes(Library.key("Index"), tr.ty) => callMethodOn(raw, "index", List(index), expected)

        case None =>
          tr.ty match
            // A parameter has no implementation to find — `indexes` asks after one, and a bound is
            // not that — so the subscript goes the way every other use of a parameter goes: to the
            // member machinery, which answers from the bounds and complains through `boundErr` when
            // they license nothing. A subscript **is** `Index`'s one method (`14 §3`), so this is
            // the same question the dot form asks, and `10 §5` puts indexing among what an unbounded
            // parameter may not do. Without this the definition-time pass dropped the complaint and
            // the reader met it at whatever first instantiated the body, against a type the
            // definition never named — which is the outcome §5 exists to prevent.
            case _: Type.Abstract => callMethodOn(raw, "index", List(index), expected)
            case other            => err(s"cannot index ${show(other)}")


  /** One end of a slice range: an index like any other, so any integer will do. */
  protected def bound(e: Expr): TExpr = {
    val t = analyzeExpr(e, Some(Type.Usize))

    t.ty match
      case i: Type.Integer => checkedIndexWidth(t, i)
      case other           => err(s"a slice bound must be an integer, not ${show(other)}")
  }

  /** An index or a slice bound, held to a width the bounds test can actually be made at.
   *
   * Reaching an element is `usize` arithmetic (`00 §7`), and a narrower index is *widened* into it —
   * value-preserving, so nothing has to be written. A **wider** one cannot be: it would have to be
   * truncated, and a truncated index is not the index that was written. At 128 bits `2^64 + 5` would
   * arrive as 5 and pass the test on a six-element array — checked, and wrong, which is worse than
   * unchecked. No array can hold more than `usize` elements, so a wider index is either a mistake or
   * a narrowing the programmer means; either way it is written, exactly as `01`'s rule says a
   * conversion that can lose information is.
   */
  protected def checkedIndexWidth(t: TExpr, i: Type.Integer): TExpr =
    if i.bits <= 64 then t
    else
      err(s"an index is reached at ${Type.pointerBits} bits and ${show(t.ty)} is wider, so it cannot " +
        "be one without losing what it holds — write 'usize(i)' to say which value is meant")

  /** Whether a subscript on this type reaches one of the two indexing traits, asked of a type that
   * has no elements of its own. A built-in's subscript is never this: an array, a slice and a
   * string are indexed by the compiler, and nothing a program writes competes with that.
   */
  protected def indexes(traitName: String, ty: Type): Boolean =
    implsOf(traitName, memberOwner(ty)._1).nonEmpty

  /** The same, asked of an assignment target before the statement has committed to being a store.
   * Whatever goes wrong while typing the receiver is left for the ordinary path to report, in the
   * place the programmer wrote it.
   */
  protected def indexes(traitName: String, receiver: Expr): Boolean =
    probe(autoDeref(analyzeExpr(receiver)).ty)
      .exists(t => Type.element(t).isEmpty && indexes(traitName, t))
}
