package sh.sysl

/** The `deriving` clause: turning `struct Size deriving Eq, Ord` into the `impl` blocks a person
 * would otherwise have written out field by field.
 *
 * **What is synthesised is source-level AST**, an `ImplDecl` carrying ordinary `MethodDecl` bodies,
 * built before anything is hoisted and walked from then on exactly as a written block is. That is
 * the whole design decision, and everything else follows from it: a derived implementation
 * type-checks, conforms, lowers and reports through one path rather than two, and there is no
 * second road through the compiler to keep in step with the first. A field whose type is not `Hash`
 * fails the same way it would in a block somebody typed.
 *
 * **The bodies are the tuple catalog's, with a name where the position was.** `library/sysl/ops.sysl`
 * already implements all four traits structurally for every tuple, over an unrolled `for const` — so
 * what a struct needs is that body with `self.i` replaced by `self.<field>`, and the ordering, the
 * mixing and the padding are decided once for both. Where the two disagree in future, they are
 * wrong: a named product and a positional one should not compare or render by different rules.
 *
 * **Why synthesis rather than a compile-time loop over the fields.** Swift and Rust — the two
 * languages this feature answers to — both derive in the compiler: Swift writes `==` and
 * `hash(into:)` memberwise for a type that declares the conformance, and Rust's `derive` is a
 * built-in macro. Neither has a user-visible walk over a type's fields, and adding one here would
 * have cost a third compile-time kind (a field is a name, a type and a selector, where `for const i`
 * binds a `usize`) and an answer to who may read a private field structurally. The clause is on the
 * declaration, so the block it writes is in the type's own module and that question does not arise.
 *
 * A user-written structural walk — a serializer, an encoder, a property inspector — is a real
 * feature and a different one. Nothing here forecloses it.
 */
object Deriving {

  /** The four traits a clause may name, each with the method it requires.
   *
   * The list is **closed**, and deliberately: an open one would be an extension point, and the
   * extension point would be the field loop this feature exists instead of. These four are the ones
   * the library already provides structurally for every tuple, which is the same claim in the same
   * words — a composite has them exactly when its parts do.
   */
  val traits: List[String] = List("Eq", "Ord", "Hash", "Display")

  /** FNV-1a's offset basis and prime, which is what the tuple's `Hash` mixes with and therefore what
   * a struct's must. The prime is what makes position matter — a plain XOR would make `Size(1, 2)`
   * and `Size(2, 1)` one key.
   */
  private val fnvBasis = BigInt("cbf29ce484222325", 16)
  private val fnvPrime = BigInt("100000001b3", 16)

  private def u64(v: BigInt): Expr = IntLit(v, Some("u64"))

  /** What a derived body walks: a **product**, whose parts are named fields, or a **sum**, whose
   * parts are variants each carrying a product of its own.
   *
   * The two are kept apart here rather than by asking the declaration, because everything below is
   * about the shape and nothing about it is about a `struct` or an `enum` — and because a *simple*
   * sum is a third case that neither declaration form tells you about on its own.
   */
  private sealed trait Shape {

    /** Whether anything at all is written between brackets — a fieldless struct and a simple enum
     * render as one string literal and have nothing to gather, which is what lets a derived
     * `Display` for either keep the one-call form.
     */
    def hasParts: Boolean = this match
      case Product(fields) => fields.nonEmpty
      case Sum(variants)   => variants.exists(_.fields.nonEmpty)
  }

  private case class Product(fields: List[String]) extends Shape

  private case class Sum(variants: List[EnumVariantDecl]) extends Shape {

    /** Whether no variant carries anything, which is what makes the value its own discriminant. */
    def simple: Boolean = variants.forall(_.fields.isEmpty)

    /** A tag comparison is only needed where there is more than one variant to tell apart, and a
     * catch-all arm is only *reachable* then — a one-variant sum's tuple match covers every pair.
     */
    def many: Boolean = variants.length > 1
  }

  /** The binders one side of a two-sided match introduces: `l0`, `l1` for the left operand's fields
   * and `r0`, `r1` for the right's. Positional, because a variant's fields are matched positionally.
   */
  private def binder(side: String, i: Int): String = s"$side$i"

  /** The pattern matching variant `v`, binding each of its fields through `name`, or `_` where the
   * body has no use for them. A variant carrying nothing is a bare name (`IdentPattern`), which is
   * what the analyzer reads as a nullary variant.
   */
  private def variantPattern(v: EnumVariantDecl, name: Int => String): Pattern =
    if v.fields.isEmpty then IdentPattern(v.name)
    else VariantPattern(v.name, v.fields.indices.toList.map(i => IdentPattern(name(i))))

  private def anyOf(v: EnumVariantDecl): Pattern =
    if v.fields.isEmpty then IdentPattern(v.name)
    else VariantPattern(v.name, v.fields.toList.map(_ => WildcardPattern))

  /** Which variant a value is, as an `int` — one `match` whose arms are the declaration positions.
   *
   * A data enum's discriminant is not something a program can read, and it does not need to be:
   * every arm knows its own position at the moment this is built, so the literal is the tag.
   */
  private def tagOf(scrutinee: Expr, variants: List[EnumVariantDecl]): Expr =
    MatchExpr(scrutinee, variants.zipWithIndex.map { (v, i) =>
      MatchArm(List(anyOf(v)), None, List(ExprStmt(IntLit(i, None))))
    })

  private def arm(patterns: List[Pattern], body: List[Stmt]): MatchArm =
    MatchArm(patterns, None, body)

  /** The arms of a match over **both** operands: one per variant, pairing that variant with itself,
   * and a catch-all for every pair that disagrees.
   */
  private def bothArms(sum: Sum, same: EnumVariantDecl => List[Stmt], otherwise: Boolean): List[MatchArm] = {
    val matched = sum.variants.map { v =>
      arm(List(TuplePattern(List(variantPattern(v, binder("l", _)), variantPattern(v, binder("r", _))))),
          same(v))
    }

    if sum.many then matched :+ arm(List(WildcardPattern), List(ExprStmt(BoolLit(otherwise))))
    else matched
  }

  private def self(field: String): Expr  = Field(Ident("self"), field)
  private def rhs(field: String): Expr   = Field(Ident("rhs"), field)
  private def cmp(op: String, l: Expr, r: Expr): Expr = Compare(List(l, r), List(op))

  /** `if <cond> then return <v>` — the one statement shape three of the four bodies are built from. */
  private def guard(cond: Expr, v: Boolean): Stmt =
    ExprStmt(IfExpr(cond, List(Return(Some(BoolLit(v)))), None))

  /** The `impl` blocks a declaration's clause asks for, in the order the clause wrote them.
   *
   * Everything is positioned at the **trait's own entry** in the clause rather than at the
   * declaration, so a struct deriving four traits whose third one cannot be written points at the
   * third word and not at the type.
   */
  def expand(stmt: Stmt): List[Stmt] = stmt match
    case s: StructDecl =>
      usable(s.deriving, s).flatMap(one(_, s.name, s.tparams, s.bounds, s.tvalues, Product(fieldNames(s))))
    case e: EnumDecl =>
      usable(e.deriving, e).flatMap(one(_, e.name, e.tparams, e.bounds, e.tvalues, Sum(e.variants)))
    case _ => Nil

  /** The clause as written, or nothing where the declaration has none — what the check below reads. */
  def clause(stmt: Stmt): List[BoundRef] = stmt match
    case s: StructDecl => s.deriving
    case e: EnumDecl   => e.deriving
    case _             => Nil

  /** The entries worth building a block for: the ones `problem` had nothing to say about, deduped,
   * since an entry already reported is one the reader has been told about and a second diagnostic
   * from the block it would have made says nothing new.
   */
  private def usable(clause: List[BoundRef], stmt: Stmt): List[BoundRef] =
    clause.filter(problem(_, stmt).isEmpty).distinctBy(_.name.split('.').last)

  /** What is wrong with one entry of a clause, or `None` where nothing is.
   *
   * Separate from the expansion because the two run at different moments — every entry is reported
   * on, and only the sound ones become blocks — and because a refusal here is about the *clause*,
   * where every other refusal a derived block can raise is about the body it wrote.
   */
  def problem(entry: BoundRef, stmt: Stmt): Option[String] = {
    val simple = entry.name.split('.').last

    // An **opaque** struct with no fields is C's incomplete type: the storage belongs to whoever
    // allocated it and nothing here knows its shape. Derived over no fields at all, `Eq` answers
    // `true` for every pair and `Display` renders every value the same — a wrong answer with nothing
    // to warn the reader, which is why this is a refusal and not a note.
    if (stmt match { case s: StructDecl => s.opaque && s.fields.isEmpty; case _ => false }) then
      Some("this type is opaque and declares no fields, so a derived implementation would have " +
        "nothing to walk — every value would compare equal and render the same. An opaque handle is " +
        "reached as a pointer; write an 'impl' block where one genuinely needs a trait")

    // A **simple** enum is already `Eq` by rule: every variant is dataless, so the value is its
    // discriminant and there is one thing equality could mean (`Type.isEquatable`). An `impl` for
    // one is refused, and a derived block would be refused by that same rule — with a sentence
    // telling the reader to delete a block they did not write. Answered here instead, at the word
    // they did write.
    else if simple == "Eq" && (stmt match { case e: EnumDecl => Sum(e.variants).simple; case _ => false })
    then
      Some("a simple enum is already 'Eq' — no variant of it carries anything, so its value is its " +
        "discriminant and '==' is that comparison. Remove 'Eq' from the 'deriving' clause; the " +
        "other three are still worth deriving")
    else if entry.args.nonEmpty then
      Some(s"'${entry.show}' writes trait arguments, and a derived implementation takes none — the " +
        s"compiler writes ${traits.mkString(", ")} over a type's own fields, and there is nothing " +
        "for an argument to vary")
    else if !traits.contains(simple) then
      Some(s"'$simple' is not a trait the compiler knows how to write — 'deriving' names " +
        s"${traits.init.mkString(", ")} or ${traits.last}, which are the four the library provides " +
        "structurally for every tuple. A trait of your own is implemented with an 'impl' block")
    else None
  }

  /** An entry naming a trait an earlier one in the same clause already named. Reported rather than
   * ignored: two of them mean the reader believes something is happening twice.
   */
  def duplicates(clause: List[BoundRef]): List[BoundRef] = {
    val seen = scala.collection.mutable.Set.empty[String]

    clause.filter(e => !seen.add(e.name.split('.').last))
  }

  /** A struct's fields, in declaration order — which is the order everything derived walks them in,
   * and is already part of the type's contract because it is the layout order (`15`).
   */
  private def fieldNames(s: StructDecl): List[String] = s.fields.map(_.name)

  private def one(
      derived: BoundRef,
      name: String,
      tparams: List[String],
      bounds: Map[String, List[BoundRef]],
      tvalues: Map[String, TypeRef],
      shape: Shape,
  ): List[Stmt] = {
    val trait_  = derived.name
    val simple  = trait_.split('.').last
    val subject = NamedType(name, tparams.map(NamedType(_))).setPos(derived.pos)

    // **A generic type derives conditionally**: every *type* parameter gains the derived trait as a
    // bound, so a `Box[int]` is `Eq` and a `Box[Unequatable]` is not, and neither needs saying. A
    // `const` value parameter gains nothing — it is not a type and has no membership to ask for.
    // The type's own bounds come along because the subject does not resolve without them: a
    // `struct SortedList[T: Ord]` is only a type at all where `T` is `Ord`.
    val derivedBounds =
      tparams.filterNot(tvalues.contains).foldLeft(bounds) { (acc, t) =>
        acc.updated(t, acc.getOrElse(t, Nil) :+ BoundRef(trait_).setPos(derived.pos))
      }

    // `Display` is the one that needs a declaration of its own beside the block: the parts are
    // written straight through a renderer rather than gathered into a string, and a renderer is a
    // function. Everything else is a body and nothing more.
    val (extra, method) = simple match
      case "Eq"   => (Nil, eqMethod(shape))
      case "Ord"  => (Nil, ordMethod(shape))
      case "Hash" => (Nil, hashMethod(shape))
      case "Display" => displayDecls(name, tparams, derivedBounds, tvalues, shape)
      case _ => sys.error(s"unreachable: '$simple' is not a derivable trait")

    extra.map(_.setPos(derived.pos)) :+
      ImplDecl(trait_, subject, List(method.setPos(derived.pos)), tparams, derivedBounds,
               tvalues = tvalues)
        .setPos(derived.pos)
  }

  private def method(name: String, params: List[Param], ret: Option[TypeRef], body: List[Stmt]) =
    MethodDecl(name, Some(RecvMode.ByValue), isProperty = false, Nil, params, ret, body)

  private val selfParam = Param("rhs", NamedType("Self"))
  private val boolRef   = NamedType("bool")

  /** Field by field, first disagreement decides — which is the tuple's body with a name in place of
   * the position, and is the only thing equality on a product can mean.
   */
  private def eqMethod(shape: Shape): MethodDecl = {
    val body = shape match
      case Product(fields) =>
        fields.map(f => guard(cmp("!=", self(f), rhs(f)), false)) :+ ExprStmt(BoolLit(true))

      // Two variants agree when they are the same variant and every field of it agrees, which is
      // one match over the pair rather than a tag comparison and a second match inside it.
      case sum: Sum =>
        List(ExprStmt(MatchExpr(Tuple(List(Ident("self"), Ident("rhs"))),
                                bothArms(sum, sameVariantEq, otherwise = false))))

    method("eq", List(selfParam), Some(boolRef), body)
  }

  private def sameVariantEq(v: EnumVariantDecl): List[Stmt] =
    v.fields.indices.toList
      .map(i => guard(cmp("!=", Ident(binder("l", i)), Ident(binder("r", i))), false))
      :+ ExprStmt(BoolLit(true))

  /** Lexicographic, first field first, written as a ladder rather than as `<` on each field in turn:
   * deciding a position takes *two* comparisons — this one is less, or it is greater, or the two
   * agree and the next field decides. All-tied ends `false`, since a value is not less than one it
   * agrees with everywhere.
   */
  private def ordMethod(shape: Shape): MethodDecl = {
    val body = shape match
      case Product(fields) => ladder(fields.map(f => (self(f), rhs(f)))) :+ ExprStmt(BoolLit(false))

      // A simple sum is its discriminant, so its order is the discriminants' and there is nothing to
      // walk. `int` reaches one for the reason `==` did before this feature existed.
      case sum: Sum if sum.simple =>
        List(ExprStmt(cmp("<", Call(Ident("int"), List(Ident("self"))),
                          Call(Ident("int"), List(Ident("rhs"))))))

      // **Variants first, then fields.** A `Circle` is less than a `Rect` because it is declared
      // first, and two `Rect`s are decided by their fields — so the tag is compared before anything
      // is taken apart, and the pair-match below only ever runs where the tags agree.
      case sum: Sum =>
        val tags =
          List(ValDecl("l", None, tagOf(Ident("self"), sum.variants)): Stmt,
               ValDecl("r", None, tagOf(Ident("rhs"), sum.variants)),
               ExprStmt(IfExpr(cmp("!=", Ident("l"), Ident("r")),
                               List(Return(Some(cmp("<", Ident("l"), Ident("r"))))), None)))

        tags :+ ExprStmt(MatchExpr(Tuple(List(Ident("self"), Ident("rhs"))),
                                   bothArms(sum, sameVariantOrd, otherwise = false)))

    method("lt", List(selfParam), Some(boolRef), body)
  }

  /** The comparison ladder over a list of paired parts: this one is less, or it is greater, or the
   * two agree and the next pair decides.
   *
   * Written as two tests per position rather than as `<` on each in turn because that is what
   * lexicographic ordering *is* — and doing it this way is what removes the last position's special
   * case, which a per-arity hand-written `Ord` always had.
   */
  private def ladder(pairs: List[(Expr, Expr)]): List[Stmt] =
    pairs.flatMap((l, r) => List(guard(cmp("<", l, r), true), guard(cmp("<", r, l), false)))

  private def sameVariantOrd(v: EnumVariantDecl): List[Stmt] =
    ladder(v.fields.indices.toList.map(i => (Ident(binder("l", i)): Expr, Ident(binder("r", i)): Expr)))
      :+ ExprStmt(BoolLit(false))

  /** The fields mixed in order with FNV's prime, seeded from the offset basis rather than from the
   * first field's hash — which is what lets the empty struct and the one-field struct take the same
   * shape rather than needing a case each.
   */
  private def hashMethod(shape: Shape): MethodDecl = {
    val mix: List[Stmt] = shape match
      case Product(fields) => fields.map(f => mixIn(Call(Field(self(f), "hash"), Nil)))

      // The **tag is mixed first**, so two variants carrying equal payloads are different keys —
      // `Some(0)` and a one-field `Other(0)` have to be, or a table holding both collides on every
      // insert. Then each variant's own fields, which is the product case one level down.
      case sum: Sum =>
        mixIn(Call(Ident("hash_u64"), List(Call(Ident("u64"), List(tagOf(Ident("self"), sum.variants)))))) ::
          (if sum.simple then Nil
           else
             List(ExprStmt(MatchExpr(Ident("self"), sum.variants.map { v =>
               arm(List(variantPattern(v, binder("l", _))),
                   if v.fields.isEmpty then List(ExprStmt(UnitLit()))
                   else v.fields.indices.toList.map(i =>
                     mixIn(Call(Field(Ident(binder("l", i)), "hash"), Nil))))
             }))))

    method("hash", Nil, Some(NamedType("u64")),
           (VarDecl("h", None, Some(u64(fnvBasis))): Stmt) :: mix :::
             List(ExprStmt(Call(Ident("hash_u64"), List(Ident("h"))))))
  }

  /** `h = h * prime ^ <part>` — one step of the FNV fold. The multiply is what carries the position,
   * so that a value's parts in a different order are a different key; a plain XOR would make them
   * the same one.
   */
  private def mixIn(part: Expr): Stmt =
    ExprStmt(Assign("=", Ident("h"), Binary("^", Binary("*", Ident("h"), u64(fnvPrime)), part)))

  /** `Size(3, 4)` — the type's own name and its fields in order, which is the tuple's rendering with
   * the name in front.
   *
   * **Two declarations, because the field belongs to the whole value and the parts do not.** A
   * specifier describes the field the *whole* value occupies (`library/core.md § A specifier is the
   * whole value's field`), so `%12s` on a `Size` pads the `Size` and not its first field — and the
   * only way to pad once is to know how wide the parts came out before writing them. The renderer
   * below writes the parts straight through to wherever it is pointed, and `display` points it at a
   * `Counting` sink first when a width was asked for, exactly as `[]T`, `Option`, `Result` and
   * `Complex[F]` do.
   *
   * **What it replaces is a fold of `+` over `str` of each part**, which built one string per field
   * plus one per separator and threw every one of them away — in every program that derives a
   * `Display`, which is the ordinary way to give a type a rendering.
   *
   * A shape with no parts to write needs none of it: a fieldless struct and a simple enum render as
   * one string literal, which `display_pad` already pads without gathering anything.
   */
  private def displayDecls(
      name: String,
      tparams: List[String],
      bounds: Map[String, List[BoundRef]],
      tvalues: Map[String, TypeRef],
      shape: Shape,
  ): (List[Stmt], MethodDecl) = {
    val out = Param("out", PtrType(NamedType("Writer")))
    val fmt = Param("fmt", NamedType("FormatSpec"))

    if !shape.hasParts then
      val text: Expr = shape match
        case Product(_) => StrLit(name)
        case sum: Sum =>
          MatchExpr(Ident("self"), sum.variants.map(v => arm(List(anyOf(v)), List(ExprStmt(StrLit(v.name))))))

      (Nil, method("display", List(out, fmt), None,
                   List(ExprStmt(Call(Ident("display_pad"),
                                      List(Field(text, "bytes"), Ident("out"), Ident("fmt")))))))
    else
      val helper = renderName(name)

      val body: List[Stmt] = shape match
        case Product(fields) => writes(name, fields.map(f => Field(Ident(subjectParam), f)))

        // A variant renders under **its own** name rather than the enum's, which is how it is
        // written: `Circle(2)`, not `Shape(2)`. The enum's name appears nowhere, exactly as it does
        // not at the construction.
        case sum: Sum =>
          List(ExprStmt(MatchExpr(Ident(subjectParam), sum.variants.map { v =>
            arm(List(variantPattern(v, binder("l", _))),
                writes(v.name, v.fields.indices.toList.map(i => Ident(binder("l", i)))))
          })))

      val subject  = NamedType(name, tparams.map(NamedType(_)))
      val renderer = FuncDecl(helper, tparams,
                              List(Param(subjectParam, subject), out, fmt.copy(name = "parts")),
                              None, body, bounds, vis = Visibility.File, tvalues = tvalues)

      (List(renderer), method("display", List(out, fmt), None, padded(helper)))
  }

  /** The name of the renderer a derived `Display` writes its parts through.
   *
   * **The space is what makes it unforgeable.** An ordinary identifier is letters, digits and `_`
   * (`reference/lexical.md § Identifiers`), so no unquoted name can collide with this one, and a
   * program that wanted to collide would have to write the backticks out itself. A plain
   * `render_Size` would take a name the program is entitled to.
   */
  private def renderName(name: String): String = s"render $name"

  /** What the renderer's receiver is called. Not `self`: it is an ordinary function rather than a
   * member, and `self` there would read as a receiver it does not have.
   */
  private val subjectParam = "v"

  private def write(text: String): Stmt =
    ExprStmt(Call(Field(Ident("out"), "write"), List(Field(StrLit(text), "bytes"))))

  /** `Size(`, each part, `, ` between them, `)` — the rendering as a sequence of writes rather than
   * as a string that was built to be written once.
   *
   * Each part is handed the **neutral** specifier, which is exactly what `str` handed it before: the
   * specifier that arrived describes the field the whole value occupies (`library/core.md § A
   * specifier is the whole value's field`), so applying it to a part would pad inside the rendering.
   * `[]T`, `Option` and `Result` hand their parts the same thing for the same reason.
   */
  private def writes(name: String, parts: List[Expr]): List[Stmt] =
    if parts.isEmpty then List(write(name))
    else
      val pieces = parts.zipWithIndex.flatMap { (p, i) =>
        val piece = ExprStmt(Call(Field(p, "display"), List(Ident("out"), Ident("parts"))))

        if i == 0 then List(piece) else List(write(", "), piece)
      }

      (write(s"$name(") :: pieces) :+ write(")")

  /** Measure, pad, render, pad — the body of a derived `display`, which is the shape every compound
   * rendering in the library has.
   *
   * The sink is built only where a width was actually asked for, so an ordinary print costs one pass
   * and allocates nothing at all.
   */
  private def padded(helper: String): List[Stmt] = {
    val width = Field(Ident("fmt"), "width")
    val fill  = ExprStmt(Call(Ident("display_fill"), List(Ident("out"), IntLit(32, None), Ident("pad"))))

    def render(sink: Expr) = ExprStmt(Call(Ident(helper), List(Ident("self"), sink, Ident("parts"))))

    val measure = List(
      VarDecl("count", None, Some(Call(Ident("Counting"), List(IntLit(0, None))))),
      VarDecl("sink", Some(PtrType(NamedType("Writer"))), Some(Unary("&", Ident("count")))),
      render(Ident("sink")),
      ExprStmt(Assign("=", Ident("pad"),
                      Binary("-", width, Call(Ident("int"), List(Field(Ident("count"), "n")))))),
      ExprStmt(IfExpr(cmp("<", Ident("pad"), IntLit(0, None)),
                      List(ExprStmt(Assign("=", Ident("pad"), IntLit(0, None)))), None)),
    )

    List(
      ValDecl("parts", None, Call(Ident("FormatSpec"),
                                  List(IntLit(0, None), Unary("-", IntLit(1, None)), BoolLit(false)))),
      VarDecl("pad", None, Some(IntLit(0, None))),
      ExprStmt(IfExpr(cmp(">", width, IntLit(0, None)), measure, None)),
      ExprStmt(IfExpr(Unary("!", Field(Ident("fmt"), "left")), List(fill), None)),
      render(Ident("out")),
      ExprStmt(IfExpr(Field(Ident("fmt"), "left"), List(fill), None)),
    )
  }
}
