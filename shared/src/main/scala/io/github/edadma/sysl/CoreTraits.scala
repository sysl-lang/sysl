package io.github.edadma.sysl

/** The traits the compiler knows by name, and which built-in types satisfy them (`14 §2`, `§5`).
 *
 * The traits themselves are ordinary declarations in the prelude — a program can read them, and can
 * call their methods directly (`5.add(3)`) exactly as it calls any other. What lives here is the
 * part a source declaration cannot say: which operator each trait's one method *is*, and which
 * built-in types are members.
 *
 * **The memberships have to be a rule rather than a table of `impl`s**, because the integer family
 * is open: `i5` and `u12` are types a program may name, so there is no finite list of scalars to
 * write an `impl` for. That is the same reason the memberships are *compiler-provided* in the first
 * place (`§5`) — a built-in has no source body to hang one off.
 *
 * A membership changes no codegen. A scalar operator still lowers to its native instruction, and so
 * does a scalar's trait method called by name; the membership exists so the *type system* agrees a
 * scalar satisfies `Add`, which is what lets one be passed where a `[T: Add]` was asked for.
 */
object CoreTraits {

  /** What shape of instruction a trait's method lowers to on a built-in. */
  enum Kind {

    /** A binary operator yielding the operand type: `Add`, `Shl`, and the rest of the table. */
    case Arith

    /** A comparison yielding `bool`. The trait requires the *one* method the other operators are
     * derived from (`§2` — `a > b` is `lt(b, a)`), so this is `==` for `Eq` and `<` for `Ord`.
     */
    case Compare

    /** A prefix operator on the receiver alone: `Neg`, `Not`. */
    case Prefix
  }

  /** Each trait, the one method it requires, and the operator that method *is*.
   *
   * There is exactly one method per trait, and that is the point: implementing a comparison means
   * writing `lt`, not four functions that could disagree with each other.
   */
  val required: Map[String, (String, String, Kind)] = Map(
    "Add"    -> ("add",    "+",  Kind.Arith),
    "Sub"    -> ("sub",    "-",  Kind.Arith),
    "Mul"    -> ("mul",    "*",  Kind.Arith),
    "Div"    -> ("div",    "/",  Kind.Arith),
    "Rem"    -> ("rem",    "%",  Kind.Arith),
    "BitAnd" -> ("bitand", "&",  Kind.Arith),
    "BitOr"  -> ("bitor",  "|",  Kind.Arith),
    "BitXor" -> ("bitxor", "^",  Kind.Arith),
    "Shl"    -> ("shl",    "<<", Kind.Arith),
    "Shr"    -> ("shr",    ">>", Kind.Arith),
    "Neg"    -> ("neg",    "-",  Kind.Prefix),
    "Not"    -> ("not",    "~",  Kind.Prefix),
    "Eq"     -> ("eq",     "==", Kind.Compare),
    "Ord"    -> ("lt",     "<",  Kind.Compare),
  )

  /** The trait a method name belongs to, which is the direction member lookup needs: a call written
   * `x.add(y)` has to find `Add` before it can ask whether `x`'s type is a member. Method names are
   * distinct across the catalog, so this is unambiguous.
   */
  def declaring(method: String): Option[String] =
    required.collectFirst { case (name, (m, _, _)) if m == method => name }

  /** Whether a **built-in** type satisfies `traitName` without an `impl` written for it (`§5`).
   *
   * This is `01`'s operator table restated as trait membership, and it is deliberately no wider
   * than that table: a membership the compiler could not lower would promise a bounded generic an
   * operation that fails at the instantiation it was supposed to have proven.
   *
   * `Eq` and `Ord` are exactly the equatable and ordered predicates the analyzer already used for
   * `==` and `<`, so nothing here decides anything those did not already decide — `bool` has
   * equality and no ordering, a pointer compares by address and nothing else.
   */
  def builtin(traitName: String, t: Type): Boolean = traitName match {
    case "Add"                 => Type.isNumeric(t) || t == Type.Str
    case "Sub" | "Mul" | "Div" => Type.isNumeric(t)

    // Remainder, the bitwise operators, and the shifts are integer-only, which is what `01` gives
    // them: there is no `frem` in the lowering, and no bit pattern a float agrees to be treated as.
    case "Rem" | "BitAnd" | "BitOr" | "BitXor" | "Shl" | "Shr" | "Not" =>
      t.isInstanceOf[Type.Integer]

    case "Neg" =>
      t match
        case i: Type.Integer  => i.signed
        case _: Type.Floating => true
        case _                => false

    case "Eq"  => Type.isEquatable(t)
    case "Ord" => Type.isOrdered(t)
    case _     => false
  }
}
