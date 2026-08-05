package io.github.edadma.sysl

/** The traits the compiler knows by name, and which built-in types satisfy them (`14 §2`, `§5`).
 *
 * The traits themselves are ordinary declarations of the library — a program can read them, and can
 * call their methods directly (`5.add(3)`) exactly as it calls any other. What lives here is the
 * part a source declaration cannot say: which operator each trait's one method *is*, and which
 * built-in types are members.
 *
 * **Everything in this file is a spelling**, which is what a program writes, and a consumer holding
 * a resolved key goes through `Library.spelling` before asking. The two used to coincide and no
 * longer do: a trait in the standard module is filed under `sysl$Display`, not `Display`, and a
 * table written in keys would have to be edited every time a declaration moved. Which half of the
 * library a trait is in is a fact this table deliberately does not hold — where a trait lives is not
 * what its operator is, and `Library` is the one place that has to know.
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

  /** The traits whose members a built-in has by rule, but which are **not** operators — so they are
   * not in `required`, whose entries pair a method with the token it is.
   *
   * These exist for the reason the memberships do: the `iN` / `uN` families are open, so a magnitude
   * or a population count cannot be a library `impl` however small the list of them is. What is here
   * is the method, its trait, and nothing else — the signature is read off the trait's own
   * declaration like any other, and the lowering is `TIntOp`'s.
   *
   * Unlike `required`'s, these traits are **not** in the standard module, so a file reaches their
   * members only where it has named the trait (`13 §2`). A compiler-provided membership settles
   * which types have a member, never which files may write it.
   */
  val numeric: Map[String, String] = Map(
    "abs"    -> "Signed",
    "signum" -> "Signed",

    // The bit surface. Each is one machine instruction on the targets sysl serves, reached through
    // the LLVM intrinsic that is the portable spelling of it — which is why they are worth a member
    // rather than being left to the shifts and masks a program would otherwise write, and why
    // `rotate_left` is here at all when `(x << n) | (x >> (w - n))` looks like it says the same
    // thing. It does not: that expression shifts by `w` when `n` is zero, and a shift by the width
    // is undefined.
    "count_ones"     -> "Bits",
    "count_zeros"    -> "Bits",
    "leading_zeros"  -> "Bits",
    "leading_ones"   -> "Bits",
    "trailing_zeros" -> "Bits",
    "trailing_ones"  -> "Bits",
    "reverse_bits"   -> "Bits",
    "rotate_left"    -> "Bits",
    "rotate_right"   -> "Bits",
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

  /** Whether a trait's implementations are told apart by an operand rather than by the whole argument
   * list — which is to say, whether its last argument is the operator's **result** (`14 §7`).
   *
   * The ten binary arithmetic and bitwise traits are declared `[Rhs = Self, Out = Self]`, and a use
   * writes neither: `a * b` fixes the operands and asks to be told the result. So the operands select
   * and the result is what the selected implementation supplies — which is why two implementations
   * agreeing on the operands are refused however their results differ.
   */
  def selectsByOperand(traitName: String): Boolean =
    required.get(traitName).exists(_._3 == Kind.Arith)

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
  def builtin(traitName: String, subject: Type): Boolean = {
    // A constrained subtype is asked about at its **base**: `16 §1` makes a transparent one the same
    // type as its base, and `16 §3` gives a derived one the base's whole catalog, so a subtype
    // narrows which values a type has and never which operations it has. `Eq` and `Ord` already read
    // it this way through `isOrdered`; the rows below used to match the type as written, which left
    // `%`, the bitwise operators, the shifts and unary `-` off a subtype that plainly has them.
    val t = Type.underlying(subject)

    traitName match {
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

    // The magnitude and the sign are questions about a value that has a sign to discard, which is
    // the signed integers and nothing else. A **float** is left out deliberately: `sysl.math`'s
    // `Float` already declares both, binding `abs` to libm's `fabs` so that it can see the sign bit
    // — which is what makes `(-0.0).abs()` answer `0.0`, and is exactly what a comparison against
    // zero cannot do. Two members of one name disagreeing about a zero is worse than one of them
    // not existing.
    case "Signed" =>
      t match
        case i: Type.Integer => i.signed
        case _               => false

    // The bit surface is every integer, signed and unsigned, which is the domain `01` already gives
    // `&`, `|`, `^`, `~` and the shifts — a signed value has a bit pattern like any other, and a
    // population count of one is the same question at either signedness.
    //
    // **Every member here is total over that domain, and that is what decided the surface.** A
    // membership the compiler cannot lower at some width would promise a `[T: Bits]` body an
    // operation that fails at an instantiation the bound was supposed to have proven — so
    // `swap_bytes` is deliberately absent, `llvm.bswap` being defined only at widths that are a
    // multiple of 16. A member that exists at `u32` and not at `u24` is worse than one that exists
    // nowhere, because only the second is visible when the generic is written.
    case "Bits" => t.isInstanceOf[Type.Integer]

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
  }

  /** The library function a built-in's `Hash` goes through, and the type its receiver widens to.
   *
   * Everything whose value is one whole number arrives at the same mixer, widened to 64 bits, which
   * is what makes `1u8` and `1i64` hash alike — they compare alike, and the law is that they must.
   * The mix is splitmix64's finalizer: a hash table indexes by the *low* bits of what it is given,
   * and consecutive keys have none worth indexing by until they have been spread.
   */
  def hash(t: Type): Option[(String, Type)] = t match
    // A value too wide for the mixer is mixed in two halves. Truncating instead would keep the law —
    // equal values would still hash equal — and throw away every bit above the 64th, so a table keyed
    // on values that differ only in their high half would put all of them in one bucket. That is the
    // shape of a 128-bit identifier, which is the reason to have the width at all.
    //
    // **Past 128 bits this does truncate, and that is a choice rather than an oversight.** Widths
    // now run to `Type.MaxIntegerBits`, so an `i256` reaching here is cast down to `u128` and its
    // top half is dropped. The hash *law* is untouched — equal values still hash equal, which is all
    // a hash owes — and what is lost is only collision resistance among values agreeing in their low
    // 128 bits. Mixing every width in 128-bit chunks is the answer whenever something is keying a
    // table on values that wide; nothing is, so the mixer that exists is the one that runs.
    case i: Type.Integer if i.bits > 64 => Some(("hash_u128", Type.Integer(128, signed = false)))
    case _: Type.Integer | Type.Char => Some(("hash_u64", Type.Integer(64, signed = false)))
    // A `bool` is one bit, and one bit does not widen to a number in this language — the same
    // reason `display_bool` exists rather than a widening into the integer renderer.
    case Type.Bool => Some(("hash_bool", Type.Bool))
    case Type.Str  => Some(("hash_str", Type.Str))
    case _         => None

  /** The library function a built-in's `Display` renders through (`14 §5`), which is the sink
   * counterpart of the one `print` reaches for the same type.
   *
   * A built-in has no `impl` block and so no lowered `int.display` to call, exactly as it has no
   * `int.add`; what it has is a rendering the library already writes, and naming it here is what
   * lets a `Display` written for a struct render the struct's own fields.
   *
   * These are **spellings**, as everything in this table is. The family is in the standard module,
   * so `display_int` is filed under `sysl$display_int` and a caller goes through `Library.key`
   * before it can name one.
   */
  def display(t: Type): Option[(String, Type)] = t match
    // Past 128 bits the renderer takes the digits rather than the number, because working them out
    // needs a buffer whose size follows the width, and a fixed array's length cannot be written in
    // terms of the receiver. Rendering that wide therefore costs an allocation, which is the one
    // place `Display`'s allocation-free promise does not reach.
    case i: Type.Integer if i.bits > 128 => Some(("display_wide", Type.Str))

    // Between 64 and 128 the digits are worked out in the library against a frame-local buffer.
    // `snprintf` is what the two below reach and C has no conversion wider than `%lld`, so these
    // exist rather than widening into them — and going through a `string` instead would put the
    // digits on the heap and stop a `no alloc` module printing a number.
    case i: Type.Integer if i.bits > 64 && i.signed => Some(("display_i128", Type.Integer(128, signed = true)))
    case i: Type.Integer if i.bits > 64             => Some(("display_u128", Type.Integer(128, signed = false)))

    case i: Type.Integer if i.signed => Some(("display_int", Type.Integer(64, signed = true)))
    case _: Type.Integer             => Some(("display_uint", Type.Integer(64, signed = false)))
    case _: Type.Floating            => Some(("display_real", Type.Real))
    case Type.Bool                   => Some(("display_bool", Type.Bool))
    case Type.Char                   => Some(("display_char", Type.Char))
    case Type.Str                    => Some(("display_str", Type.Str))
    case _                           => None
}
