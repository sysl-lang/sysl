package sh.sysl

/** The storage a module lays down — its `val`s, its `var`s, and the `extern` variables it names.
 *
 * All three are read in a state belonging to no function, and that is the point rather than an
 * implementation detail: an initializer here is a *module member's* expression, so the locals of
 * whichever function first mentioned the name must not be in scope while it is read.
 *
 * What separates them is which question is asked of what. A `val`'s release rule is asked of the
 * **value**, because a `val` is forever what it was given and a constant tree was never built; a
 * `var`'s is asked of the **type**, because it may be given something else tomorrow; and an
 * `extern`'s is asked of neither, because the storage is somebody else's. `isStatic` is the test
 * all of that turns on, and `13 §7`'s initialization order is the other thing it decides.
 */
trait ModuleStorage extends ModuleFiles {
  // --- module-level `val`s --------------------------------------------------------------

  /** Analyzes one module-level `val` to the storage it lays down.
   *
   * The initializer is read at the declared type, so an element written `0x428a2f98` in a `[64]u32`
   * needs no suffix — the same courtesy a `const` gets from writing its type, for the same reason.
   *
   * What the initializer *is* decides how the storage gets filled: a constant tree is written into
   * the object file, and anything else becomes code that runs once before the program's own
   * statements. It also decides one of the two things checked here, since whether a counted type
   * may be held at all is a question about the value rather than about the type.
   */
  protected def analyzeVal(key: String): TVal = inDecl(key)(at(valDecls(key).pos) {
    val decl = valDecls(key)
    val ty   = globalType(key)

    // Storage a test file declared is scaffolding, and so is anything its initializer lowers to —
    // the same rule a body gets, asked here because an initializer is analyzed outside every body.
    inTestBody = testOnlyDecls(key)

    val init = analyzeExpr(decl.value, Some(ty))

    if disagree(init.ty, ty) then
      err(s"cannot initialize '${qn(key)}': declared ${show(ty)} but the value is ${show(init.ty)}")

    val static = isStatic(init)

    checkVal(ty, static, key)
    TVal(key, ty, Some(init), !static, align = boundaryOf(key, decl.align))
  })

  /** `@align(n)` above module storage, folded to the boundary it named — the same fold a struct's
   * and a local's go through, reported against the name as a reader wrote it rather than against the
   * key the module qualified it into.
   */
  private def boundaryOf(key: String, align: Option[Expr]): Option[Int] =
    align.flatMap(a => recover(Option.empty[Int])(alignBound(qn(key), a)))

  /** One module `var`: module storage the program may write (`13 §7`), written `static var` in the
   * file the program starts in and plain `var` in any other.
   *
   * Three things separate it from the `val` above, and each is what the word `var` already means.
   *
   * **Its initializer may be absent**, which a `val`'s may not: a variable with no value is still a
   * complete declaration of storage, and the type's zero is what it starts at. That is the cheapest
   * form and the one an arena wants — `zeroinitializer` and no store at all.
   *
   * **The release rule is asked of the TYPE, where a `val`'s is asked of the value**, and the
   * difference is the whole of why this is a separate check. A `val` is forever the value it was
   * given, so `val greeting: string = "hello"` is admissible: a literal's owner word is null and
   * nothing was ever built. A `var` could be given that literal and `str(n)` on the next
   * line, so whatever it holds when the program ends has nowhere to write its release — which makes
   * the question one about what the storage may ever hold, not about what it was first given.
   *
   * **It is `writable`**, which is what `TGlobal` carries to every read of the name so that an
   * assignment through it is allowed and a `@pure` function reading it is not (`17 §6`).
   */
  protected def analyzeStaticVar(key: String): TVal = inDecl(key)(at(staticVarDecls(key).pos) {
    val decl = staticVarDecls(key)
    val ty   = globalType(key)

    inTestBody = testOnlyDecls(key)

    val init = decl.init.map { e =>
      val t = analyzeExpr(e, Some(ty))

      if disagree(t.ty, ty) then
        err(s"cannot initialize '${qn(key)}': declared ${show(ty)} but the value is ${show(t.ty)}")
      t
    }

    if Type.zeroSized(ty) then
      err(s"'${qn(key)}' cannot be module storage: ${show(ty)} has no representation, so there is " +
        "nothing for the storage to be")
    else if !uncounted(ty) then
      err(s"'${qn(key)}' cannot be module storage: storage that exists for the whole run is never " +
        s"let go of, so a count taken in one is a count with nowhere to write the release — and " +
        s"${show(ty)} is a type that takes one. The question is asked of the type here rather than " +
        "of the value, because a variable may be given a different value tomorrow")

    TVal(key, ty, init, computed = init.exists(!isStatic(_)), writable = true,
      align = boundaryOf(key, decl.align))
  })

  /** Holds a module-level `val` to a value that **owes no release** (`13 §7`).
   *
   * There is one reason and it is about lifetime, not about depth. A module `val` exists for the
   * whole run and is therefore never let go of, so a value that owes a release in one is a leak with
   * no line to write the release on.
   *
   * The question is asked of the **value**, not of the type, and the difference is the whole point.
   * A `&T`, a `weak T`, a slice, and a `string` each owe a box or an owner a release *when one is
   * built* — but a value the object file carries as it stands was never built, and a literal string
   * in particular has a null owner word, which both retain and release test for and do nothing
   * about (`04`). So a counted type is admissible exactly when its initializer is a constant tree:
   * `val greeting: string = "hello"` is storage in read-only data with no count anywhere in it,
   * while `val greeting: string = str(n)` is a count with nowhere to write the release.
   *
   * A raw pointer and the address of a function are outside the question entirely: they own nothing
   * at the far end, so they owe nothing however they were arrived at.
   *
   * Read-only *at every depth* is deliberately **not** what this checks, though it once was. That
   * promise is about the storage the declaration lays down, and it is kept where it is made: `k[0] =
   * …` and `&k[0]` are both refused. It was never a promise about what a value *inside* the storage
   * addresses — a `*T` reached through one is the raw tier, where the language guarantees nothing,
   * and slicing a `val` and writing `&v[0]` already produces one on purpose (`07 § A view that may
   * not be written`). Refusing a `val` at pointer type declined one route to what another route
   * grants.
   */
  private def checkVal(ty: Type, static: Boolean, key: String): Unit =
    // The whole difference between a `val` and a `const` is that a `val` has an address (`13 §7`),
    // and a value with no representation has nothing to put one on. The initializer would still run,
    // which is the only thing such a declaration could have been for — and a statement says that
    // without pretending there is storage.
    if Type.zeroSized(ty) then
      err(s"'${qn(key)}' cannot be a 'val': a ${show(ty)} value occupies nothing, so there is no " +
        "storage for the name to stand for — write the call as a statement instead")
    else if !uncounted(ty) && !static then
      err(s"'${qn(key)}' cannot be a 'val': storage that exists for the whole run is never let go " +
        s"of, so a count taken in one is a count with nowhere to write the release — and this " +
        s"${show(ty)} is built while the program runs. One the object file can carry as it stands " +
        "may be held: a string literal owns nothing, and neither does a table of them")

  // --- `extern` variables ----------------------------------------------------------------

  /** Holds one `extern` variable's declared type to being something a symbol could stand for.
   *
   * There is one rule and it is the `val`'s first one, for the same reason: a symbol is an address,
   * and a value with no representation has nothing to put one at. The `val`'s *second* rule — that
   * it counts nothing — is deliberately not applied here. It exists to keep a `val` from owning a
   * count it can never release, and an `extern` variable owns nothing: the storage is the other
   * side's, and so is whatever releasing it would mean.
   */
  protected def checkExternVar(key: String): Unit =
    val ty = externVarType(key)

    if Type.zeroSized(ty) then
      err(s"'${qn(key)}' cannot be an 'extern' variable: a ${show(ty)} value occupies nothing, so " +
        "there is no storage for the linker to resolve the name to")

  /** Whether a type carries no refcount anywhere in it, so storage holding one owes no release.
   *
   * A `*T` and a `*extern(…) -> R` answer yes and are **not** looked through: a pointer owns nothing
   * at the far end, so what the far end counts is not this storage's business. A bare trait type
   * answers no because a value of one is a box the count lives in. Everything built out of parts is
   * as good as its parts.
   */
  private def uncounted(t: Type): Boolean = t match
    case _: Type.Ptr | _: Type.CFn                                 => true
    case _: Type.Ref | _: Type.Weak | _: Type.View | _: Type.Trait => false
    case Type.Array(_, elem)                                       => uncounted(elem)
    case s: Type.Struct                                            => s.fields.forall(f => uncounted(f._2))
    case e: Type.Enum        => e.variants.forall(_.fields.forall(f => uncounted(f._2)))
    case c: Type.Constrained => uncounted(c.base)
    // A qualifier says how storage is reached, never what is in it, so the answer is the answer for
    // what it qualifies — which is always yes, since only a scalar or a raw pointer may carry one.
    case Type.Volatile(inner) => uncounted(inner)
    case _                    => true

  /** Whether an initializer is a value the object file can carry as it stands: numbers, string
   * literals, and the arrays and structs built from them. Everything else is code, which is what
   * `13 §7`'s initialization order is about — this is the test that decides which of the two a
   * declaration is. It is also what `checkVal` asks to decide whether a counted type may be held,
   * since a value that was never built owes no release.
   */
  private def isStatic(t: TExpr): Boolean = t match
    case _: TIntLit | _: TFloatLit | _: TBoolLit => true

    // A literal's bytes are already in the object file and its owner word is null, so the three
    // words a `string` is are complete before anything runs (`04`). Nothing is allocated to make
    // one, which is why a module with no allocator may hold a table of them.
    case _: TStrLit => true

    case TArrayLit(elems, _)  => elems.forall(isStatic)
    case TArrayFill(value, _) => isStatic(value)

    // Fields laid side by side are a constant tree exactly when the fields are — the same rule the
    // array above follows, and the reason a table of `{name, code}` pairs lands in read-only data
    // rather than being filled in by stores. A struct whose `invariant` has to run is wrapped in a
    // `TStructInvCheck` and never reaches this, which is `13 §7`'s rule that a value having to be
    // checked is code however it looks.
    case TStructNew(_, args) => args.forall(isStatic)

    // An address the datasheet gives as a number is a constant like any other, so `ptr_cast` over one
    // stays in this category rather than becoming code. It matters where it is used: a register block
    // reached before anything has run is exactly what a freestanding program wants, and an ordered
    // initializer would be a store to make before the storage could be read.
    case _: TNullLit                                      => true
    case TCast(_: TIntLit, (_: Type.Ptr) | (_: Type.CFn)) => true

    case _ => false
}
