package sh.sysl

/** The traits the compiler knows by name, and which built-in types satisfy them
 * (`library/core.md § What is in it`, `reference/expressions.md § Operator dispatch`).
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
   * members only where it has named the trait (`reference/modules.md § Visibility`). A
   * compiler-provided membership settles which types have a member, never which files may write it.
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

  /** The compiler-provided members that have **no receiver** — a trait, and the whole number its
   * one member answers with (`reference/expressions.md § Operator dispatch`).
   *
   * `numeric`'s members are reached from a value and lower from it; these are reached through the
   * *type*, because there is no value of `T` to hand a `zero()` before the accumulator that wants
   * one exists. That is the whole difference, and it is why they are a table of their own: what a
   * receiverless member lowers to cannot be read off a receiver, so the value is stated here.
   *
   * Both are in the standard module, so unlike `Signed` and `Bits` no file has to name the trait to
   * reach them — but the scope question is still asked at the call, for the reason `builtinNumeric`
   * asks it: a membership settles which types have the member and never which files may write it.
   */
  val constants: Map[String, (String, Int)] = Map(
    "zero" -> ("Zero", 0),
    "one"  -> ("One",  1),
  )

  /** The compiler-provided receiverless members whose answer is **read off the type** rather than
   * stated — `u32.width()`, and `T.width()` in a body bounded by `Bits`.
   *
   * These are `constants`' other half and are a table of their own for the reason `constants` is
   * one: a receiverless member has no value to lower from, so something here has to supply the
   * answer. The difference is where the answer comes from. `zero` is `0` at every width, so the
   * number is written in the table; a width is different at every width, so what is written here is
   * only which trait declares it and `measure` computes the rest.
   *
   * **`Bits`' membership is the compiler's, which is why this cannot be an `impl` in the library.**
   * The integers are an open family — the trait's own comment turns `swap_bytes` away over `u24` —
   * so there is no finite list of widths to write blocks for, and a member every integer has must be
   * supplied the way the rest of `Bits` is.
   */
  val measures: Map[String, String] = Map("width" -> "Bits")

  /** What a measure answers for one subject type, and nothing for a type it has no answer about.
   *
   * Asked at `opSubject` for the reason `builtin` asks there: a constrained subtype has its base's
   * whole catalog, and a range narrows which values a type holds rather than how wide it is — so a
   * subtype of `u32` is thirty-two bits, and a vector is asked about at its lane.
   */
  def measure(mname: String, subject: Type): Option[Int] =
    (mname, Type.opSubject(subject)) match
      case ("width", i: Type.Integer) => Some(i.bits)
      case _                          => None

  /** Each infix operator token, and the trait its operands must satisfy (`§3`). The four derived
   * comparisons name the trait they are derived *from*, not one of their own — there is no `Gt`.
   */
  val infix: Map[String, String] =
    required.collect { case (name, (_, op, Kind.Arith)) => op -> name } ++
      Map("==" -> "Eq", "!=" -> "Eq", "<" -> "Ord", ">" -> "Ord", "<=" -> "Ord", ">=" -> "Ord")

  /** Each prefix operator token and its trait, kept apart from `infix` because `-` is in both. */
  val prefix: Map[String, String] =
    required.collect { case (name, (_, op, Kind.Prefix)) => op -> name }

  /** Whether a trait's implementations are told apart by an operand rather than by the whole
   * argument list — which is to say, whether its last argument is the operator's **result**
   * (`library/core.md § Walking a type of your own`).
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
    // A constrained subtype is asked about at its **base**: `reference/errors.md § Constrained
    // types` makes a transparent one the same type as its base, and `reference/errors.md § A
    // derivation inherits its base's behaviour and may replace none of it` gives a derived one the
    // base's whole catalog, so a subtype narrows which values a type has and never which operations
    // it has. `Eq` and `Ord` already read it this way through `isOrdered`; the rows below used to
    // match the type as written, which left `%`, the bitwise operators, the shifts and unary `-`
    // off a subtype that plainly has them. **A vector is asked about at its lane**, for the reason
    // the sentence above gives about a subtype: a register does not decide which operations a type
    // has, only how many of them happen at once. What this settles is that the *compiler* owns `+`
    // on a `<4>f32` and it is not looked for among the `impl` blocks — which pair is actually
    // defined is `arithType`'s answer, and it refuses integer `/` there with a reason rather than
    // by falling through to a missing `impl`.
    val t = Type.opSubject(subject)

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
    // **Every integer type, and nothing else — the family named by what it ranges over.**
    //
    // This is the membership a *blanket* `impl` is written over, which is the whole reason it
    // exists: `impl[T: Integer] Display for T` says once, in source, what no finite list of blocks
    // could say about an open family. So unlike every other row here it promises no operation of its
    // own; what it promises is that `T` is one of the integers, and the operations come from the
    // traits it requires.
    //
    // It is deliberately **not** `Bits` under another name (`reference/expressions.md § Operator
    // dispatch`). `Bits` is named for what it provides — a population count, a rotation — and
    // `Integer` for what it ranges over; they extend alike today and would part company the moment
    // a bitset or a lane mask wanted the first without being the second. `Float` is the counterpart
    // on the closed side, and it is a trait with two written `impl`s for exactly the reason this
    // one cannot be.
    case "Integer" => t.isInstanceOf[Type.Integer]

    // The additive and multiplicative identities. They are the one pair here that is a **value**
    // rather than an operation, and that is what decides the domain — every other row promises
    // something a type can *do*, so it is asked at `opSubject` and a subtype inherits it, while
    // these two promise a particular value exists at the type.
    //
    // So they are asked of the type **as written**, and two things fall outside on purpose. A
    // **constrained subtype** is a claim about which values it holds, and nothing says a range that
    // was written to exclude zero has one — `reference/errors.md § A derivation inherits its base's
    // behaviour and may replace none of it` gives a subtype its base's operations, which these are
    // not. A **vector** is left out because a splat is not what `zero()` says: `<4>i32` has four
    // lanes and the member names none of them, where every other row here is the lane's operation
    // happening four times at once.
    //
    // The integers are the whole of what is added, because the floats already have written `impl`
    // blocks in `sysl.ops` and a membership beside one is what `HoistImpl` refuses. What could not
    // be written is exactly the open family: a program may name `u256`, so no list of blocks covers
    // it, which is the argument every membership in this file exists for.
    case "Zero" | "One" => subject.isInstanceOf[Type.Integer]

    case _ => false
    }
  }

  /** The traits a program may never write an `impl` for, because their membership is a **family**
   * rather than a promise: they say which types something *is one of*, and that is the compiler's
   * answer alone.
   *
   * This is what makes a blanket `impl` over one of them sound, and the two facts are the same fact.
   * A blanket covers every type meeting its bound, so coherence needs the set of such types to be
   * one nothing outside the compiler can add to — otherwise a program joins a family after the fact
   * and acquires an implementation written for something else entirely, with no block naming either.
   * Closing the trait is what fixes the set, and fixing the set is what lets one block stand for all
   * of it.
   *
   * `Bits` and `Signed` are **not** here, and the difference is worth being exact about: they are
   * ordinary traits whose members happen to be compiler-provided for the built-ins, so a program's
   * own type declaring `count_ones` and joining is meaningful. `Integer` has no members to supply,
   * so an `impl` of it could only be a claim to be an integer, which is not a claim a program is in
   * a position to make.
   */
  val closed: Set[String] = Set("Integer")

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
}
