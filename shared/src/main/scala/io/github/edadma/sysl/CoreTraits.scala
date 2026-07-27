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

  /** Each infix operator token, and the trait its operands must satisfy (`§3`). The four derived
   * comparisons name the trait they are derived *from*, not one of their own — there is no `Gt`.
   */
  val infix: Map[String, String] =
    required.collect { case (name, (_, op, Kind.Arith)) => op -> name } ++
      Map("==" -> "Eq", "!=" -> "Eq", "<" -> "Ord", ">" -> "Ord", "<=" -> "Ord", ">=" -> "Ord")

  /** Each prefix operator token and its trait, kept apart from `infix` because `-` is in both. */
  val prefix: Map[String, String] =
    required.collect { case (name, (_, op, Kind.Prefix)) => op -> name }

  /** How each comparison is built from the one method its trait requires (`§2`): whether the
   * operands are swapped, and whether the result is negated.
   *
   * `a > b` is `lt(b, a)` and `a <= b` is `!lt(b, a)`, so two of the six **swap**. On a scalar that
   * is invisible — `§5`'s memberships keep the built-ins on their native comparisons, which is also
   * what keeps a float's `NaN` behaviour intact — but on a type whose `lt` is a real call, the
   * swap is a change in the order the two operand expressions are evaluated.
   */
  val derivation: Map[String, (Boolean, Boolean)] = Map(
    "==" -> (false, false),
    "!=" -> (false, true),
    "<"  -> (false, false),
    ">"  -> (true, false),
    "<=" -> (true, true),
    ">=" -> (false, true),
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

    // A hash only means anything beside an equality it agrees with — `a == b` must imply that the
    // two hash alike — so membership here is `Eq`'s, minus the two places that law is not free.
    //
    // A **float** is left out for the reason Rust leaves it out: `NaN != NaN` breaks the
    // reflexivity a table lookup assumes, and `-0.0 == 0.0` holds between two different bit
    // patterns, so a hash over the bits contradicts the equality unless it normalizes first. A
    // program that means to key on a float writes the normalization it means.
    //
    // A **pointer or reference** is left out because its `==` is address equality, so a hash of one
    // would be a hash of where the allocator happened to put something — and an address is not a
    // number this language lets a program compute with, which is where the matter rests.
    case "Hash" => t.isInstanceOf[Type.Integer] || t == Type.Char || t == Type.Bool || t == Type.Str

    // Everything with one textual form worth printing. A pointer is deliberately left out: an
    // address renders differently on every run, so a program that wants one in its output asks for
    // it rather than getting it from `print(p)`.
    case "Display" => Type.isNumeric(t) || t == Type.Str || t == Type.Char || t == Type.Bool

    case _ => false
  }

  /** The prelude function a built-in's `Hash` goes through, and the type its receiver widens to.
   *
   * Everything whose value is one whole number arrives at the same mixer, widened to 64 bits, which
   * is what makes `1u8` and `1i64` hash alike — they compare alike, and the law is that they must.
   * The mix is splitmix64's finalizer: a hash table indexes by the *low* bits of what it is given,
   * and consecutive keys have none worth indexing by until they have been spread.
   */
  def hash(t: Type): Option[(String, Type)] = t match
    case _: Type.Integer | Type.Char => Some(("hash_u64", Type.Integer(64, signed = false)))
    // A `bool` is one bit, and one bit does not widen to a number in this language — the same
    // reason `display_bool` exists rather than a widening into the integer renderer.
    case Type.Bool => Some(("hash_bool", Type.Bool))
    case Type.Str  => Some(("hash_str", Type.Str))
    case _         => None

  /** The prelude function a built-in's `Display` renders through (`14 §5`), which is the sink
   * counterpart of the one `print` reaches for the same type.
   *
   * A built-in has no `impl` block and so no lowered `int.display` to call, exactly as it has no
   * `int.add`; what it has is a rendering the prelude already writes, and naming it here is what
   * lets a `Display` written for a struct render the struct's own fields.
   */
  def display(t: Type): Option[(String, Type)] = t match
    case i: Type.Integer if i.signed => Some(("display_int", Type.Integer(64, signed = true)))
    case _: Type.Integer             => Some(("display_uint", Type.Integer(64, signed = false)))
    case _: Type.Floating            => Some(("display_real", Type.Real))
    case Type.Bool                   => Some(("display_bool", Type.Bool))
    case Type.Char                   => Some(("display_char", Type.Char))
    case Type.Str                    => Some(("display_str", Type.Str))
    case _                           => None
}
