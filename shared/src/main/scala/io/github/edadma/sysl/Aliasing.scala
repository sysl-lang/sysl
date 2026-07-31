package io.github.edadma.sysl

import scala.collection.mutable

/** What a struct's `invariant` clauses (`16 §6`) demand of the aliases a program makes.
 *
 * A clause is discharged by re-checking it at the write, and the write is found by walking outward
 * through the **place** being written — so the whole obligation rests on the place still naming the
 * struct. A pointer is where that runs out: `&o.a` is a `*Inner`, and an `Inner` knows nothing of
 * the `Outer` whose clause reads `a.n`, so a write through it breaks a promise nothing is left to
 * check. No amount of checking at the write closes this; it is a rule about **what may be aliased**.
 *
 * The rule restricts alias *creation* rather than alias *use*, which is what keeps it a local,
 * type-only question and not the borrow checker `03` rejects. An alias is safe when its type still
 * carries every promise over the memory it can reach, and the three ways that is arranged are here:
 * the walk that finds what a write owes (`invCheckFor`), the walk that finds what an alias would
 * sever (`severing`), and the clause's own read set, which is what makes the refusal precise enough
 * to leave `&o.c` alone when no clause mentions `c`.
 *
 * Nothing here costs a program that declares no invariants anything: every entry point answers
 * `Nil` or `false` before it looks at a clause.
 */
trait Aliasing extends AnalyzerBase {

  /** Whether a struct type carries clauses this machinery has to honour. A generic one is refused
   * where it is declared, so it never reaches a check that would have to be instantiated first.
   */
  protected def carriesInvariants(s: Type.Struct): Boolean =
    structDecls.get(s.base).exists(d => d.invariants.nonEmpty && d.tparams.isEmpty)

  /** Every struct a write through `place` obliges a re-check of: what to re-read, its type, and the
   * predicate to call, innermost first.
   *
   * The walk goes **outward through the whole place**, not just to the field's own receiver, because
   * an invariant may read through a field — `invariant a.n <= b` is a claim `o.a.n = 9` can break
   * just as `o.b = 0` can, and only the enclosing struct knows it. Nothing is owed by the parts of a
   * place that merely locate it, so an index contributes no check of its own and is walked through.
   */
  protected def invCheckFor(place: TExpr): List[(TExpr, Type.Struct, String)] =
    def owed(recv: TExpr): List[(TExpr, Type.Struct, String)] = recv.ty match
      case s: Type.Struct if carriesInvariants(s) => List((recv, s, invKey(s.base)))
      case _                                      => Nil

    place match
      case TField(recv, _, _) => owed(recv) ++ invCheckFor(recv)
      case TIndex(recv, _, _) => invCheckFor(recv)
      case _                  => Nil

  /** The same walk asked the other question: which enclosing structs an alias of this place would be
   * typed **below**, each with the field path from that struct down to the place.
   *
   * The path is what makes the refusal answer about the clause rather than about the struct. It is
   * spelled as a clause spells it, so an index step contributes no name and an element stands for
   * its array — `g.items[0]` and `g.items[1]` are one path, since a clause reading the first says
   * nothing about which element a dynamic index would reach.
   */
  protected def severing(place: TExpr): List[(Type.Struct, List[String])] = {
    // A field is a *position* in the typed tree and a *name* in the clause, so the receiver's type is
    // what turns one into the other. Anything else under a field step is storage this walk cannot
    // spell, and it stops rather than guessing at a path.
    def walk(e: TExpr, below: List[String]): List[(Type.Struct, List[String])] = e match
      case TField(recv, i, _) =>
        recv.ty match
          case s: Type.Struct if i < s.fields.length =>
            val path = s.fields(i)._1 :: below
            (if carriesInvariants(s) then List((s, path)) else Nil) ::: walk(recv, path)
          case _ => Nil
      case TIndex(recv, _, _) => walk(recv, below)
      case _                  => Nil

    walk(place, Nil)
  }

  /** The field paths a struct's clauses read, cached because a program that takes several addresses
   * inside one struct would otherwise re-walk the same clauses at each of them.
   */
  private val readSets = mutable.HashMap.empty[String, Set[List[String]]]

  protected def invReads(base: String): Set[List[String]] =
    readSets.getOrElseUpdate(base, structDecls.get(base).fold(Set.empty)(readsOf))

  /** Which of a struct's fields each clause reaches, as paths from the struct.
   *
   * A clause is an expression over the fields by name, so the paths are read straight off it: a name
   * that is a field roots one, a `.f` extends it, and an index is walked through for the same reason
   * the place walk above walks through one. An index *expression* is not part of the path but may
   * read fields of its own, so it is collected separately rather than dropped.
   *
   * A clause that hides an expression inside a **statement** — the body of an `if` used as a value, a
   * `match` arm, a lambda — is not walked, and the answer for the struct becomes every field it has.
   * Reading too much only ever refuses an alias that would have been allowed; reading too little
   * would allow one that severs a promise, which is the mistake this whole file exists to prevent.
   */
  private def readsOf(decl: StructDecl): Set[List[String]] = {
    val fields = decl.fields.map(_.name).toSet
    var opaque = false

    def collect(e: Expr): List[List[String]] = e match
      case _: Lambda | _: IfExpr | _: MatchExpr => opaque = true; Nil
      case _ =>
        clausePath(fields, e) match
          case Some(p) => p :: chainIndices(e).flatMap(collect)
          case None    => exprKids(e).flatMap(collect)

    val found = decl.invariants.flatMap(collect).toSet

    if opaque then fields.map(List(_)) else found
  }

  /** The path a clause expression names, when it is a place rooted at one of the struct's fields. */
  private def clausePath(fields: Set[String], e: Expr): Option[List[String]] = e match
    case Ident(n) if fields(n) => Some(List(n))
    case Field(r, n)           => clausePath(fields, r).map(_ :+ n)
    case Index(r, _)           => clausePath(fields, r)
    case _                     => None

  /** The index expressions along a place chain, which are read but are not part of the path. */
  private def chainIndices(e: Expr): List[Expr] = e match
    case Field(r, _) => chainIndices(r)
    case Index(r, i) => i :: chainIndices(r)
    case _           => Nil

  /** Every sub-expression a node holds, read off the case class rather than matched arm by arm —
   * the clause walks want a total descent and have nothing to say about any particular node.
   * Expressions reachable only through a *statement* are not here, which is what the walks above
   * fall back to reading everything for.
   */
  private def exprKids(e: Expr): List[Expr] = e match
    case p: Product =>
      p.productIterator.toList.flatMap {
        case x: Expr         => List(x)
        case Some(x: Expr)   => List(x)
        case xs: Iterable[?] => xs.toList.collect { case x: Expr => x }
        case _               => Nil
      }
    case _ => Nil

  /** Holds a clause to reading storage the struct **owns** (`16 §6`).
   *
   * Everything else here restricts the aliases a program may make, and that only works while the
   * clause is a claim about the struct's own bytes. A clause that reads through a pointer, a
   * reference or a view is a claim about somebody else's — the far side of the hop has an identity of
   * its own, every other alias of it is out of this struct's sight, and a write through one breaks
   * the clause with nothing anywhere that could re-check it. So the clause is refused where it is
   * written rather than quietly meaning less than it says.
   *
   * A view's `len` is the exception, and it is not really one: the three words are stored in the
   * struct, so the length is the struct's own and the elements are not.
   */
  protected def checkInvariantReads(decl: StructDecl, ftypes: Map[String, Type]): Unit = {
    val fields = decl.fields.map(_.name).toSet

    def indirect(t: Type): Boolean = t match
      case _: Type.Ptr | _: Type.Ref | _: Type.Weak | _: Type.View => true
      case _                                                       => false

    def refuse(t: Type, what: String): Unit =
      err(s"an invariant may only read storage the struct owns, and $what is read through " +
        s"${show(t)} — what is on the far side has an identity of its own, so another alias of it " +
        "could break the clause with nothing left to re-check it against. Hold the value in a field " +
        "of the struct, and state the invariant over that")

    /** The type a chain step lands on, refusing the step that leaves the struct's own storage. */
    def stepped(e: Expr): Option[Type] = e match
      case Ident(n) => ftypes.get(n)
      case Field(r, n) =>
        stepped(r).flatMap {
          case s: Type.Struct              => s.fields.find(_._1 == n).map(_._2)
          case _: Type.View if n == "len"  => None
          case t if indirect(t)            => refuse(t, s"'$n'"); None
          case _                           => None
        }
      case Index(r, _) =>
        stepped(r).flatMap {
          case Type.Array(_, elem) => Some(elem)
          case t if indirect(t)    => refuse(t, "an element"); None
          case _                   => None
        }
      case _ => None

    def walk(e: Expr): Unit =
      if clausePath(fields, e).isDefined then
        stepped(e)
        chainIndices(e).foreach(walk)
      else exprKids(e).foreach(walk)

    decl.invariants.foreach(e => recover(())(at(e.pos)(walk(e))))
  }

  /** Whether a write through an alias at `alias` could change what a clause reading `read` sees.
   *
   * Either path being a prefix of the other is enough, and the two directions are different
   * mistakes: an alias of `a` reaches the `a.n` a clause reads, and an alias of `a.n.x` changes the
   * `a.n` a clause reads whole. Equal paths are both at once.
   */
  private def overlaps(alias: List[String], read: List[String]): Boolean =
    alias.startsWith(read) || read.startsWith(alias)

  /** The first clause an alias of this place would put out of reach, if there is one. */
  private def severed(place: TExpr): Option[(Type.Struct, List[String])] =
    severing(place).flatMap { (s, path) =>
      invReads(s.base).find(overlaps(path, _)).map(read => (s, read))
    }.headOption

  /** `&place`, refused where the pointer it makes would be typed below a promise the place carries.
   *
   * `&o` is left alone — a `*Outer` names the struct, so a write through it is found by the ordinary
   * walk and checked. What is refused is the pointer that drops the name on the way out.
   */
  protected def checkAddressable(place: TExpr): Unit =
    for (s, read) <- severed(place) do
      err(s"'&' here makes a '*${show(place.ty)}' pointing inside ${show(s)}, whose invariant reads " +
        s"'${read.mkString(".")}' — and a '*${show(place.ty)}' names no ${show(s)}, so a write through " +
        s"it would break the clause with nothing left to re-check it against. Take the address of the " +
        s"${show(s)} itself, which keeps the invariant in its type, or make the change through a method")

  /** The same refusal for the other way to reach storage that outlives the expression: a view of it
   * that may be written. A `[]const T` is left alone, since giving up the write is exactly what makes
   * the alias carry no promise it could break.
   */
  protected def checkSliceable(base: TExpr, view: Type): Unit =
    if !Type.readOnlyView(view) then
      for (s, read) <- severed(base) do
        err(s"this view may be written, and it views storage inside ${show(s)}, whose invariant reads " +
          s"'${read.mkString(".")}' — a '${show(view)}' names no ${show(s)}, so a write through it would " +
          s"break the clause with nothing left to re-check it against. Take it as a '[]const " +
          s"${show(Type.element(view).getOrElse(Type.Unknown))}', which may not write, or make the " +
          s"change through a method")

  /** Re-checks, after a call, every invariant the receiver's place lies below (`16 §6`).
   *
   * A `*self` method reached through a field — `o.a.bump()` — is the one severed place the language
   * still has, since there are no parameter modes (`12 §2`) and so no other way to hand a callee
   * somewhere to write without writing `&`. What makes it recoverable is that the **call site** still
   * knows the whole place: the receiver is `o.a`, `o` is right there, and the clause can be re-run
   * the moment the call returns. So the alias is allowed and the promise is kept at the boundary
   * instead of inside.
   *
   * Wrapping is by the same fold a write uses, so where a place lies below more than one struct the
   * innermost clause is the one that fires first.
   */
  protected def recheckAfter(recvArg: TExpr, call: TExpr): TExpr = recvArg match
    case TAddrOf(place, _) =>
      invCheckFor(place).foldLeft(call)((acc, c) => TRecheck(acc, c._1, c._2, c._3).setPos(call.pos))
    case _ => call

  /** The structs whose storage can lie **inside** one that carries clauses, reached by fields and by
   * array elements — the hops that keep the two in one object. A reference or a view is not one of
   * them: what is on the far side has an identity of its own and is not what an enclosing struct's
   * clause is a claim about.
   *
   * This is what keeps `SelfAlias` from being a rule about every method in every program. A struct
   * that is nobody's field cannot have its receiver be a severed place, so nothing its methods do
   * with `&self.f` can put a clause out of reach, and they are left alone. A program that declares no
   * invariants has an empty set here and pays nothing.
   */
  private def coveredStructs: Set[String] = {
    def inlineStructs(t: Type): List[Type.Struct] = t match
      case s: Type.Struct   => List(s)
      case Type.Array(_, e) => inlineStructs(e)
      case e: Type.Enum     => e.variants.flatMap(_.fields.map(_._2)).flatMap(inlineStructs)
      case _                => Nil

    val found = mutable.Set.empty[String]
    var work =
      structInsts.values.filter(carriesInvariants).toList.flatMap(_.fields.flatMap(f => inlineStructs(f._2)))

    while work.nonEmpty do
      val s = work.head

      work = work.tail
      if found.add(s.base) then work = s.fields.flatMap(f => inlineStructs(f._2)) ::: work

    found.toSet
  }

  /** Holds every `*self` method of such a struct to finishing with its receiver when it returns.
   *
   * Runs once, over the whole program, because the set above is only complete when every type the
   * program mentions has been instantiated. Each finding is reported and the walk goes on, so a body
   * with two escapes names both rather than the first.
   */
  protected def checkSelfAliasing(funcs: List[TFunc]): Unit = {
    val covered = coveredStructs

    if covered.nonEmpty then
      for f <- funcs do
        f.params.headOption match
          case Some(("self", Type.Ptr(s: Type.Struct))) if covered(s.base) =>
            for (msg, pos) <- SelfAlias.check(f) do recover(())(at(pos)(err(msg)))
          case _ =>
  }
}
