package sh.sysl

import Type.*

/** What can be asked of a type, and the keys the answers make.
 *
 * Split out of `Type.scala`, which is one file because `Type` is sealed and every shape it has must
 * be declared beside it. These are not shapes: they are questions over the shapes, so they are the
 * part the language lets move. `object Type` extends this, so every one of them is still spelled
 * `Type.isNumeric`, `Type.zeroSized`, and so on — nothing about how they are called changes.
 *
 * Three groups. **What a type costs** — whether it carries a value at run time and whether it takes
 * any storage, which are two questions and not one. **What a type is made of** — whether a stand-in
 * for a type parameter, or an unresolved type, is anywhere inside it, and the keys a definition-time
 * walk files such a type under. **What can be done with it** — the arithmetic, ordering and equality
 * a value of it admits, and for an integer, the range it holds.
 */
trait TypeQueries {

  /** Whether a type carries no value at run time: `unit`, whose only value is nothing at all, and
   * `never`, which has no values. Both lower to `void`, so neither ever needs a merge slot, a
   * result register, or a `store` — which is the one thing codegen has to know about either.
   */
  def noValue(t: Type): Boolean = t == Unit || t == Never

  /** Whether a type occupies no storage — the **layout** question, as against `noValue`'s question
   * about a register.
   *
   * `unit` is zero-sized: it has one value, and that value is nothing at all, so a field of it is
   * not a field, a parameter of it is not an argument, and a binding of it is not a slot. Each is
   * skipped where the layout is built, with whatever follows shifted up, and nothing is emitted to
   * read or write one. That is what lets `Result[unit, E]` be written.
   *
   * `never` is deliberately **not** here. It has no values at all, so a field of it could never be
   * given one; skipping its layout would leave a type nobody can construct, which is a different
   * thing from one that costs nothing to construct.
   */
  def zeroSized(t: Type): Boolean = t == Unit

  /** Whether a type is built out of something that is not a type — a parameter standing in for
   * itself, or anything holding one. Such a type is a step in a definition-time walk (`14 §4`)
   * rather than something a value is ever laid out at, so nothing keyed on it may outlive the walk.
   */
  def mentionsAbstract(t: Type): Boolean = t match
    case _: Abstract      => true
    case n: Named         => n.targs.exists(mentionsAbstract)
    case Ptr(inner)       => mentionsAbstract(inner)
    case Ref(inner, _)    => mentionsAbstract(inner)
    case Weak(inner)      => mentionsAbstract(inner)
    case Array(_, elem)   => mentionsAbstract(elem)
    case Vector(_, elem)  => mentionsAbstract(elem)
    case Slice(elem, _)   => mentionsAbstract(elem)
    case Volatile(inner)  => mentionsAbstract(inner)
    case CFn(ps, r)       => ps.exists(mentionsAbstract) || mentionsAbstract(r)
    case _                => false

  /** What a cache key needs beyond the spelling, when the type is built out of stand-ins.
   *
   * **An `Abstract` is its name**, so `Buf[T]` under a `[T: Ord]` and `Buf[T]` under a `[T: Display]`
   * are one string and two types. A map keyed on the spelling hands the second declaration the first
   * one's instantiation, fields and all — and the diagnostic then lands inside whichever declaration
   * asked second, saying its own bounds do not promise what its body assumes. Every pass that makes
   * such an instantiation already sandboxes it, and this is the other half of that rule: while it
   * lives, it must not be mistaken for a different declaration's.
   *
   * Empty for a type with no stand-in in it, which is every type a value is ever laid out at — so a
   * real instantiation's key is exactly what it always was.
   *
   * Two stand-ins that agree on their bounds still share, and should: what a definition-time walk can
   * do with one is what its bounds promise, so they are interchangeable.
   */
  def standInTag(t: Type): String = t match
    case a: Abstract      => s"{${a.name}:${a.bounds.map(_.key).mkString("+")}}"
    case n: Named         => n.targs.map(standInTag).mkString
    case Ptr(inner)       => standInTag(inner)
    case Ref(inner, _)    => standInTag(inner)
    case Weak(inner)      => standInTag(inner)
    case Array(_, elem)   => standInTag(elem)
    case Vector(_, elem)  => standInTag(elem)
    case Slice(elem, _)   => standInTag(elem)
    case Volatile(inner)  => standInTag(inner)
    case CFn(ps, r)       => ps.map(standInTag).mkString + standInTag(r)
    case _                => ""

  /** The key an instantiation is cached under: its spelling, plus what tells one stand-in from
   * another spelled the same way.
   */
  def instanceKey(base: String, targs: List[Type]): String =
    qualified(base, targs) + targs.map(standInTag).mkString

  /** Whether a type is built out of one that could not be worked out. `Unknown` is only ever
   * produced where the thing that would have decided the type was already reported, so a check
   * that reaches one is a check whose answer is a consequence of a mistake the reader has already
   * been told about — and a second diagnostic about the consequence sends them somewhere else.
   *
   * The shape is `mentionsAbstract`'s, and for the same reason: a poisoned type is nearly always
   * nested inside a good one, as `Result[unit, <unknown>]` is when its error type was misspelled.
   */
  def mentionsUnknown(t: Type): Boolean = t match
    case Unknown          => true
    case n: Named         => n.targs.exists(mentionsUnknown)
    case Ptr(inner)       => mentionsUnknown(inner)
    case Ref(inner, _)    => mentionsUnknown(inner)
    case Weak(inner)      => mentionsUnknown(inner)
    case Array(_, elem)   => mentionsUnknown(elem)
    case Vector(_, elem)  => mentionsUnknown(elem)
    case Slice(elem, _)   => mentionsUnknown(elem)
    case Volatile(inner)  => mentionsUnknown(inner)
    case CFn(ps, r)       => ps.exists(mentionsUnknown) || mentionsUnknown(r)
    case _                => false

  def isNumeric(t: Type): Boolean = underlying(t) match
    case _: Integer | _: Floating => true
    case _                        => false

  /** The type whose operator table applies — a vector's **lane**, and any other type itself.
   *
   * A vector has exactly the operators its lane has, applied to every lane, so every question of
   * the form "does this type have `*`" is answered about the lane. It is deliberately separate from
   * `underlying`, which strips what a *subtype* added: this strips what the register added, and a
   * caller wanting one nearly always does not want the other. `<4>Celsius` reaches `int` through
   * both, and in that order.
   */
  def opSubject(t: Type): Type = underlying(t) match
    case Vector(_, lane) => underlying(lane)
    case other           => other

  /** Whether a type computes with the numeric operators — a number, or a vector of them.
   *
   * What this gates is the **expected type flowing into an operand**, so that the `2.0` in
   * `v * 2.0` is read at `f32` rather than falling to `real`. `isNumeric` is the narrower question
   * and stays narrow: a literal *pattern* may not match a vector, and a vector is not a number.
   */
  def computesNumerically(t: Type): Boolean = isNumeric(opSubject(t))

  /** Whether `<`, `<=`, `>`, `>=` are defined — the numeric types, `char`, and `string`, which
   * orders by its bytes and so, being well-formed UTF-8, by codepoint. A constrained subtype
   * inherits the ordering of its base, so it is ordered exactly when its base is.
   */
  def isOrdered(t: Type): Boolean = { val u = underlying(t); isNumeric(u) || u == Char || u == Str }

  /** Whether `==` and `!=` are defined. Everything ordered, plus the types that have equality
   * without an ordering: `bool`, the two pointer-shaped modes, which compare by address, and a
   * **simple** enum.
   *
   * A trait object is two words rather than one, and only the second of them is an address — two
   * objects over the same value through different traits are the same value and different tables —
   * so what "equal" would mean is a question the trait has to answer, not the machine.
   *
   * **A simple enum is a member for the reason the open `iN` family is: there is nothing else it
   * could mean, and no finite list an `impl` could be written over.** Every variant is dataless, so
   * the value *is* its discriminant and the comparison is the integer compare already emitted for
   * the `: iN` it lowers to — nothing to walk, nothing to decide. What it replaces is a few lines
   * per enum-shaped API saying what the declaration had already fixed: a hand-written `eq` over a
   * conversion, an `int(a) == int(b)`, or a two-armed `match` answering one question.
   *
   * **A data enum is deliberately not here.** Structural equality over payloads needs every payload
   * type to be `Eq` itself, which is a real feature and a different one; `simple` is exactly the
   * line between the two, and it is the same line the lowering already draws.
   *
   * There is no matching `Ord`. The declaration order is an order and it is not a *meaning* — `Srgb
   * < Linear` is not a claim anybody wants a language to make on their behalf — so an enum that
   * genuinely has one says so with an `impl`.
   */
  def isEquatable(t: Type): Boolean = t match
    case _ if erased(t)                  => false
    // A function pointer is one word and compares by it, which is what makes "is a callback
    // installed" answerable — the question every C interface with a null default asks.
    case Bool | _: Ptr | _: Ref | _: CFn => true
    case e: Enum                         => e.simple
    case _                               => isOrdered(t)

  /** The extremes an integer type can hold — what `T::Min` and `T::Max` answer with (`01`).
   *
   * These are questions about **magnitude**, which is why they are not spelled `First` and `Last`:
   * those name the ends of a declared sequence, and for an enum with explicit discriminants the
   * first-declared variant need not be the smallest. The two coincide on an integer and only there.
   *
   * The open family is the reason these must be computed rather than tabulated. A program can write
   * `4294967295` for a `u32` and cannot write the largest `u10000` at all — it is 3,011 digits — so
   * for a wide member of the family the attribute is not a convenience but the only way to name a
   * value the type obviously has.
   */
  def minOf(t: Integer): BigInt = if t.signed then -(BigInt(1) << (t.bits - 1)) else BigInt(0)

  def maxOf(t: Integer): BigInt = (BigInt(1) << (if t.signed then t.bits - 1 else t.bits)) - 1

  /** Whether a literal value is representable in an integer type. Out of range is an error
   * rather than a wrap: the width is the programmer's statement of intent.
   */
  def fits(value: BigInt, t: Integer): Boolean = value >= minOf(t) && value <= maxOf(t)

  /** A value reduced to what an integer type can hold, as a **written** conversion does it: the low
   * bits are kept and the rest discarded, with the result read back signed where the target is
   * (`01`). This is the truncation `u8(300)` performs at run time, done at compile time so that a
   * constant means the same thing as the expression it stands for.
   */
  def wrap(value: BigInt, t: Integer): BigInt = {
    val span = BigInt(1) << t.bits
    val low  = value.mod(span)

    if t.signed && low >= (span >> 1) then low - span else low
  }
}
