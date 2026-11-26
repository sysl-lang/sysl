# Type-level const arithmetic — computed value arguments, solved offsets, value predicates — design

**Status: approved (2026-11-26), item U1.2 of 0.14.** Part of §5.1–§5.2 is already built (§2.3):
`value_terms.sysl`'s `Mono` form and the symbolic definition walk, with D1 and D2, shipped in 0.13.
It lifts the refusal
`generics.md § What may be written with N` pins today ("the length 'N + 1' computes with the value
parameter 'N' … type-level arithmetic, which is a separate feature") and removes the first row of
`generics.md § What is deliberately not here`. The feature: a value parameter may be computed with
in a **type** — `[N + M]T`, `Buf[N + 1]`, `M[R, K]`, `Q[L1 - L2, T1 - T2]` — and the compiler decides
when two such types are one.

The brief's spellings are Rust's; in sysl they read:

| brief | sysl |
|---|---|
| `fn concat[N, M](a: [T; N], b: [T; M]) -> [T; N + M]` | `concat[T, const N: usize, const M: usize](a: [N]T, b: [M]T) -> [N + M]T` |
| `Array[T, N + M]` | `[N + M]T`, or `Buf[N + M]` for a struct with a value parameter |
| `Matrix[R, C] * Matrix[C, K] -> Matrix[R, K]` | `impl[const R: usize, const C: usize, const K: usize] Mul[Mat[C, K], Mat[R, K]] for Mat[R, C]` |

## Why

**Units.** The motivating use is dimension exponents as value parameters, so that `Length / Time`
is `Velocity` at compile time and `Length + Time` does not compile, at zero cost — the quantity is a
struct of one `real`, and every exponent is gone after monomorphization:

```sysl
struct Q[const L: i8, const T: i8]
    v: real

impl[const L: i8, const T: i8] Add for Q[L, T]
    add(self, o: Q[L, T]) -> Q[L, T] = Q(self.v + o.v)

impl[const L1: i8, const T1: i8, const L2: i8, const T2: i8] Div[Q[L2, T2], Q[L1 - L2, T1 - T2]] for Q[L1, T1]
    div(self, o: Q[L2, T2]) -> Q[L1 - L2, T1 - T2] = Q(self.v / o.v)

type Length = Q[1, 0]
type Time = Q[0, 1]
type Velocity = Q[1, -1]

val d: Length = Q(10)
val t: Time = Q(2)
val v: Velocity = d / t
```

Half of this works on 0.12.0 (probed): signed value parameters (`Q[-1, 2]`), aliases of an instance
(`type Velocity = Q[1, -1]`), and same-dimension `Add`, with `a + t` refused as *"'+' between
Q[1, -1] and Q[0, 1] needs 'Add[Q[0, 1]]' — it implements 'Add'"*. The `Div` block is refused twice:
`L1 - L2` computes in a type, and `L2`/`T2` appear only in a trait argument (§2).

**Fixed-size algebra.** `concat`, `split_at`, `push` onto a `[N]T` giving `[N + 1]T`, matrix
products, a ring buffer of `2 * N` slots, an SIMD reduction halving `<N>T` to `<N / 2>T` — each is
today a body that computes the length and a signature that cannot say it, so the program writes a
slice (a run-time length and a bounds check) or one copy per size.

## 1. Decisions

| # | question | decided | why, in one line |
|---|---|---|---|
| 1 | where computation may appear | **anywhere a type is written** — a signature, a field, a variant's payload, a body's type, an `impl`'s trait arguments, a default — in an **output** position (its value is computed from parameters already fixed). An **input** position (one a parameter is solved from) is restricted by decision 5 | an output is evaluated, never inverted; evaluation is what `const` already does |
| 2 | which expressions | **every constant expression** `generics.md § Every constant position reads the same expressions` already admits. Of them, **`+`, `-`, unary `-` and `*`** get algebra; **`/` and `%`** get algebra only when the divisor is a constant dividing every coefficient (§3.3); `min`, `max`, the six comparisons, `&&`/`||`/`!`, `if`, and `const` function calls are **opaque atoms** compared by structure | the ring fragment is decidable and complete; the rest is decidable only as structure |
| 3 | arithmetic of the definition | over **ℤ, unbounded** — the normal form neither wraps nor traps | an identity of polynomials over ℤ holds at every instance where both sides evaluate |
| 4 | normalisation | a **canonical polynomial**: a sum of monomials with non-zero integer coefficients, each monomial a product of parameters (by declaration order) and atoms (by a total order on their normal forms), sorted; `N + M` and `M + N`, `2 * N` and `N + N`, `N * (M + 1)` and `N * M + N` are each **one** form. Two value arguments are equal iff their forms are identical | equality by form, no search, no solver |
| 5 | solving (unification) | a parameter is **solved** from an input position that is **the parameter alone**, or **the parameter plus or minus a parameter-free constant** (`N + 1`, `N - c`) — `N + 1 = 5` gives `N = 4`, fit-checked against `N`'s type. Any other expression in an input position is a **check**, allowed only when every parameter it names is solved elsewhere; a parameter solved nowhere is refused **at the declaration** | one unknown, unit coefficient: always one answer or none; anything wider is a Diophantine problem |
| 6 | the definition walk | value parameters become **symbolic** (today each stands at zero and takes no part in equality — §2.2): `Buf[N]` vs `Buf[M]` is a mismatch at the declaration, as `T` vs `U` is | a generic is answered for by the body it was written as (`compiler-notes § Names`); today a value parameter's body is answered for only by its instances |
| 7 | what the definition walk does with an equality it cannot show | **refuses it** at the declaration, saying which forms differ and why (a constant difference, or an atom it does not see into) | sound and early beats C++'s "ill-formed, no diagnostic required" |
| 8 | evaluation at an instance | every computed argument is evaluated with the parameters bound, by the ordinary constant evaluator, and must **fit** the receiving parameter's type (a length: `usize`; `Q`'s `L`: `i8`). A failure (negative length, overflow, division by zero) is refused **at the use that made the instance**, with the instance's arguments named and a note at the declaration | the one post-monomorphization error left, and `where` (decision 9) moves it to the caller's words |
| 9 | `where` on value parameters | **a predicate**: `where N > 0`, `where L % 2 == 0`, any `bool` constant expression over the declaration's value parameters, beside trait bounds in the same clause. Checked **at each instantiation** (a call, a written type, an `impl` selection), reported at the use; the definition walk does **not** reason from it | a predicate states the domain; using it as a fact would need an arithmetic decision procedure (§9) |
| 10 | `if const` on a value parameter | an arm guarded by **`N == c`** (a bare parameter against a constant, either side) reads its body with `N` replaced by `c`; any other condition adds nothing | the one refinement that is a substitution, and the shape `if const N == 0` code already takes |
| 11 | an `impl`'s parameters | may be fixed by the receiver **or by a trait argument the use supplies** (an operator's `Rhs`), not only by the receiver; the result argument (`Out`) may be computed. In an `impl`'s receiver and supplied arguments a value argument is a **bare** parameter or a constant (no offsets) | units and matrices need `Rhs` to fix `L2`/`K`; bare-only keeps the overlap check as it is |
| 12 | how a type with computed arguments is shown | **as written** in a declaration's own diagnostics and in `sysl doc`; **in normal form** where the compiler composed it (`Buf[M + N]` from substituting); **concrete** at an instance | a reader recognises their own text; a composed form has no text |
| 13 | the existing value-argument kinds | unchanged: integers, `bool`, `char`, a simple enum's variant. Arithmetic applies to integers; a `bool` argument may be a comparison or a connective (an atom); `char`/enum arguments are bare or constant | nothing to compute with in a `char` or a tag |

## 2. What exists today, and what each piece becomes (probed on 0.12.0)

### 2.1 What works

- `[const N: usize]` on functions, members, structs, enums, traits and `impl`s; `N` solved from
  `[N]T` and from `Buf[N]` exactly as `T` is from `Box[T]` (`generics.md § A parameter may stand for a
  value`).
- **An argument may already be any constant expression when it names no parameter**: `Ring[double(Base)
  + 1]`, and `const Next = u8(Op.Store) + 1`.
- **A default may compute with an earlier parameter**: `struct Pad[const M: usize, const N: usize = M
  * 2]` — evaluated per instance, so this is computed-in-a-type already, at one position.
- **A struct's value argument computed in a FIELD or a BODY is accepted and is right**, evaluated per
  instance: `struct W[const N: usize] inner: Buf[N + 1]` gives `W[2].inner` a `Buf[3]` (a `room()` of
  3), and `W(Buf[7](0))` is refused *"'inner' of 'W[2]' is Buf[3], but Buf[7] was given"*; in a body,
  `val g: Buf[N + 1] = Buf(b.used)` works and `width[N + 1]()` and `width[N * 2]()` work.
- Signed and `bool` value parameters, negative arguments, aliases of an instance.

### 2.2 What is refused, or wrongly accepted

| program | 0.12.0 says | after |
|---|---|---|
| `f[const N: usize](xs: [N]int) -> [N + 1]int` | *the length 'N + 1' computes with the value parameter 'N' …* | accepted (decision 1); the body must yield `[N + 1]int` |
| `struct S[const N: usize] data: [N * 2]int` | same refusal (an array length in a field) | accepted — the **inconsistency** with `inner: Buf[N + 1]` (accepted) goes |
| `grow[const N: usize](b: Buf[N]) -> Buf[N + 1]` | same refusal, **calling `N + 1` "the length"** though it is a struct's argument | accepted |
| `var r: [N + N]int = [0; N + N]` in a body | same refusal (`resolve_array` asks the substitution again) | accepted |
| `per[const L2: i8, const T2: i8](self, o: Q[L2, T2]) -> Q[L1 - L2, T1 - T2]` | refused twice, "the length 'L1 - L2'" | accepted |
| `impl[…, const L2: i8, const T2: i8] Div[Q[L2, T2], …] for Q[L1, T1]` | *'L2', 'T2' is declared by this 'impl' but does not appear in 'Q[L1, T1]', so nothing would ever fix it*, and then *'/' needs matching types, got Q[1, 0] and Q[0, 1]* | accepted (decision 11) |
| `shrink[const N: usize](b: Buf[N + 1]) -> usize` | the refusal, then *cannot infer the type argument 'N' of 'shrink' here* | accepted; `shrink(Buf[4](0))` solves `N = 3` (decision 5) |
| `cast[const N: usize, const M: usize](b: Buf[N]) -> Buf[M] = b` | **accepted**; `cast[3, 3]` runs; `cast[3, 4]` is refused only at that instance | **refused at the declaration**: `Buf[N]` is not `Buf[M]` (decision 6) — **built in 0.13** (§2.3) |
| `wrong[const N: usize](b: Buf[N]) -> Buf[N] = Buf[N + 1](b.used)`, never called | **accepted** | **refused at the declaration** — **built in 0.13** (§2.3) |
| the same, called at `N = 3` | *function 'wrong' should return Buf[3], but its body yields Buf[4]* | refused at the declaration instead, in `N` — built in 0.13 |
| `struct Ring[const N: usize]` with `@assert(N > 0)` inside | *'@assert' stands where a declaration stands …* | unchanged; `struct Ring[const N: usize] where N > 0` is the spelling (decision 9) |
| `sum[const N: usize](xs: [N]int) -> int where N: Display` | *'N' stands for a value rather than a type …* | unchanged — a `where` entry `N: Trait` is still a bound, and still refused on a value |

**Why the last-but-four rows are wrong today.** A value parameter stands at a **zero that takes no
part in equality** while the declaration is read in its own terms (`types.sysl`'s `ConstArg(…,
stand_in: true)`; `resolve_type.sysl`'s comment: "the array stands at length zero for that one
walk"). So the definition walk cannot tell `N` from `M` or from `N + 1`, and every such mistake is
found only by an instance — or never, in a library nobody instantiates that way. Monomorphization
re-reads each instance's body with the arguments bound, which is why nothing ill-typed has ever
*run*; it is also the soundness backbone this design keeps (§9).

The refusal itself is `hoist.sysl`'s `length_arithmetic`, reached from `walk_pack_spellings` (a
signature's arrays, vectors and `ValueArgType`s) and from `resolve_array` (every array, bodies
included). It does not reach a struct's value argument in a field or a body, which is why those
work: they are evaluated per instance and never compared in the definition walk.

### 2.3 Re-probed on 0.13.7 (2026-11-26)

The value-parameter symbol work of 2026-11-21 (`value_terms.sysl`; `tests_vpq_`, `tests_vpl_`,
`tests_vpc_`) postdates the table above. On 0.13.7:

- **Rows 1–7 of §2.2 are unchanged**: every computed length or value argument in a signature, a field's
  array length and a body's array length is refused by `length_arithmetic`, and the `Div` `impl` by
  *"'L2', 'T2' is declared by this 'impl' but does not appear in 'Q[L1, T1]'"*. The two `where`/`@assert`
  rows are unchanged.
- **`cast` and `wrong` are already refused at the declaration** (decision 6, D1, D2):
  *"function 'cast' should return Buf[M], but its body yields Buf[N] — 'N' and 'M' are different value
  parameters"* and *"function 'wrong' should return Buf[N], but its body yields Buf[N + 1] — the two
  differ by 1 for every N"*. That behaviour change shipped in 0.13; `generics.md § What may be written
  with N` pins both.
- **A struct's value argument in a body or field is compared by a polynomial form**: `val a: Buf[N + M]
  = Buf[M + N](1)`, `Buf[N * (M + 1)]` against `Buf[N * M + N]`, `Buf[(2 * N + 4) / 2]` against
  `Buf[N + 2]` are all accepted (N1, N3, N5), and `Buf[(2 * N + 3) / 2]` against `Buf[N + 1]` is refused
  (N6) — but the atom prints as `(2 * N + 3 / 2)`, losing the parentheses of its left operand.
- **`min`/`max` do not exist in a type position**: `Buf[min(N, M)]` is *"undefined function 'min'"*, so
  decision 2's `min`/`max` atoms have nothing to normalise until a constant `min` exists.

How `value_terms.sysl`'s form differs from §5.1: a factor is a parameter's **name** (a string), not
its declaration index, so N11 (renamed parameters) is not one form; an atom is its **text**, with no
normalisation inside (no `min`/`max` sorting, no comparison folding — N8 — and no const-call atom by
key — N9; a call carries no term at all); and past the cap the argument **carries no term** and is
compared by value, where §5.1 refuses it (D11).

## 3. Prior art

| | computed in types | equality of `N + M` / `M + N` | solving `N + 1 = 5` | when checked | predicate |
|---|---|---|---|---|---|
| **Rust** `generic_const_exprs` | yes, unstable since 2021, marked incomplete | **no** — an expression is an anonymous const compared by structure; `N + 1` and `1 + N` differ | **no** — a non-parameter expression is never inferred from | generic bodies checked once, generically; the language's rule is *no post-monomorphization errors* | `where [(); N + 1]:` ("evaluatable" bounds) that every caller must repeat |
| **Zig** comptime | yes — a type is a comptime value, `[n + m]T` is a call | not asked: nothing is checked before an instance | not asked: `comptime n` is passed or computed, never inferred from a type | **only** at each instantiation, with a reference trace | `if (n == 0) @compileError(…)` |
| **C++** NTTP | yes (`std::array<T, N + M>`) | "functionally equivalent but not equivalent" declarations are **ill-formed, no diagnostic required** | a non-deduced context: `N` is never deduced from `N + 1` | at instantiation (two-phase lookup checks only non-dependent parts) | C++20 `requires (N > 0)` |
| **F#** units of measure | yes, units only: `float<m/s>` | **decided**: units form a free abelian group, compared by normal form (exponent vectors) | **inferred**: abelian-group unification (Kennedy), complete | once, generically, then erased | none |
| **Rust `uom`, C++ mp-units / Boost.Units** | dimension exponents as type-level integers (typenum) or NTTPs | by the type system of the host, encoded | as the host | as the host | — |

**Why Rust's is still unstable** (as of this writing; the summary is the tracking issue's and the
const-generics project group's): (1) **well-formedness** — `[T; N - 1]` can fail to evaluate, and
Rust forbids that failure after monomorphization, so the bound `where [(); N - 1]:` must be stated and
then repeated by every generic caller, which leaks through whole libraries; (2) **unification** —
unevaluated constants inside the trait solver are compared without normalisation, so obviously-equal
types differ, and normalising them interacts with query cycles (a const in a `where` clause that
depends on its own item); (3) generality — the feature admits arbitrary `const fn` calls, so there is
no decidable equality to fall back on. The project's current direction (`min_generic_const_args`)
narrows what may be written rather than widening the solver.

**What sysl takes from each.** From **F#**: decide equality by a normal form over a decidable
algebra; sysl's is the polynomial ring rather than the abelian group, because array lengths
multiply. From **Zig and C++**: evaluate per instance, which sysl already does and which makes the
definition-time layer a matter of *early errors and inference*, not of soundness. From **Rust's
trouble**: do not make the instance's evaluability a definition-time obligation that callers repeat;
sysl reports an evaluation failure at the use, and `where` lets an author word it. From **C++**: never
deduce from an expression wider than an offset. F#'s *inference* of unit signatures is not needed:
sysl's signatures are written, so the compiler only checks them and solves bare or offset positions.

## 4. The rule

**A type may compute with value parameters.** In a declaration, a written value argument (an array
length, a vector's lanes, a value argument of a generic type, a default) is a constant expression
over the declaration's value parameters and constants. Two value arguments are the same when their
normal forms (§5.1) are identical. Each **instance** evaluates them with its arguments, and each must
fit the parameter it is handed to.

**A parameter is solved only where it stands alone or offset by a constant.** At a call or a use,
each value parameter is solved from an argument type at a position written `N`, `N + c` or `N - c`
(`c` naming no parameter); every other position that names it is checked once all are solved.

**A predicate bounds the values.** `where N > 0` refuses every instance at which it is false, at the
use that makes the instance.

## 5. The analysis

### 5.1 The normal form — `ConstTerm`

A new value type, beside `ConstArg`:

```
ConstTerm   = Σ (coeff: BigInt ≠ 0) × Monomial       -- sorted, merged
Monomial    = Π Factor ^ (exp ≥ 1)                   -- sorted
Factor      = Param(index)                           -- the declaration's i-th value parameter
            | Atom(op, args: Buf[ConstTerm])         -- min, max, /, %, a comparison, &&, ||, !,
                                                     -- if, a const call (by the function's key)
```

- `+`, `-`, `*`, unary `-`, literals and parameter-free constants (folded to a coefficient) build it
  by the ring operations; a product distributes.
- **Exact division** (decision 2): `P / c` and `P % c` with `c` a non-zero constant that divides every
  coefficient of `P` *and* every monomial of `P` has at least one parameter or atom — normalise to
  `P/c` and `0`. `(2 * N + 4) / 2` is `N + 2`. Otherwise `/` and `%` are atoms. (Truncating division
  agrees with exact division when exact, for every sign.)
- **Atoms are normalised inside**: their arguments are `ConstTerm`s; `min`/`max` sort and deduplicate
  their arguments (commutative, idempotent) and flatten nesting; a comparison whose two sides differ
  by a **constant** folds to `true`/`false` (`N + 1 > N` is `true` over ℤ); `!`/`&&`/`||` fold
  constants. Nothing else is simplified.
- **A cap**: a form over 64 monomials, or of total degree over 8, is refused where it arises
  (*"the type argument … is too large to compare"*). No program shown in §Why comes near it.
- Parameters are numbered by declaration order, so the form is independent of their spelling, and a
  term's identity is a structural compare — no hashing is needed for correctness.

### 5.2 Symbolic value parameters in the definition walk

`ConstArg` gains a term: a value parameter read in its own declaration is `ConstArg(0, ty, stand_in:
true, term: Param(i))`, and an argument computed over it carries the term of its expression. **The
zero stays** for every use that needs a number during the definition walk (a layout, a `sizeof`, a
loop bound) — that is today's behaviour and the body is read again per instance. **Equality**
(`types.sysl`'s `type_eq` on `ConstArg`) compares terms when either side has one, and values
otherwise. A term never reaches an instance: substitution evaluates it (§5.4).

### 5.3 Solving — `unify` on a value argument

Given a written position `W` (callee's view) and a given argument `G` (concrete, or a term in the
caller's own definition walk):

1. **Bare first**: every position that is a bare parameter binds it (`G` concrete → a value; `G` a
   term → that term). Two bindings must agree (equal values, or equal terms).
2. **Offsets next**: `N + c` binds `N` to `G - c`, `N - c` to `G + c` (as a value, fit-checked against
   `N`'s type; as a term, by ring subtraction). Agreement as in 1.
3. **Checks last**: every other position is evaluated (or normalised) with the bindings and compared.
4. A parameter still unbound is the existing *"cannot infer the type argument 'N' …"*.

The declaration walk refuses, once, a parameter that **no** position can solve (decision 5), so the
inference failure at 4 is reached only where the parameter was meant to be written at the call.

### 5.4 Instances

Substitution (`generic.sysl`'s binding of `ConstArg`s; `resolve_type.sysl`'s `resolve_array`) evaluates
a written value argument by the **ordinary constant evaluator** (`const_eval`) with the parameters
bound to their values, then fits the result to the receiving parameter's type (as `generics.md`
already does for a written constant: *"the default for 'N' does not fit byte: 300"*). The ordinary
typing rules hold inside the expression — `N + M` with `N: usize`, `M: u8` is refused as it is in a
body; a value parameter is read at its declared type, and a literal at the operand's.

### 5.5 `where` predicates

The clause's grammar gains an entry kind: an expression that is not `Name: Bounds`. Its names must be
the declaration's value parameters (else *"this declaration has no value parameter 'K' …"*, the
existing wording's sibling). It must type as `bool`. Each instantiation — `instantiate` for a
function, the resolution of `Ring[0]` for a type, the selection of an `impl` — evaluates it; false is
refused at the use (§7), with an *"in 'outer[0]'"* chain where the use is itself inside an instance.
A struct's predicate holds for every value of the struct, so a function taking `Ring[N]` need not
repeat it.

### 5.6 `impl` parameters fixed by a supplied trait argument

`hoist_impl.sysl`'s `check_argument_names` accepts a parameter that appears bare in the receiver
**or** in a trait argument other than the trait's result position. "Result position" is a property
of the trait: the operator traits of `sysl/ops.sysl` say in their docstring that "the operands select
the implementation and the result is whatever that one supplies"; this design writes that down as
**a trait argument that no member takes as a parameter's type** (`Out` appears only as a result) and
lets the selection solve the others from the operands. A use selects the block by unifying the
receiver and the supplied arguments (§5.3 step 1 only — bare), then evaluates `Out`.

## 6. Hazards, each mapped

| # | hazard | where it bites | handled by |
|---|---|---|---|
| H1 | the stand-in zero is still a number: a definition-walk layout or `sizeof` of `[N + 1]T` sees 1 | layout queries in a generic body | the zero answers numbers, the term answers identity (§5.2); every instance is re-read |
| H2 | `N - 1` at `N = 0` (`usize`), `L1 - L2` past `i8` | an instance | evaluated by `const_eval`, fit-checked, refused at the use (decision 8); `where N > 0` words it |
| H3 | `N - 1 + 1` is `N` in ℤ but traps at `N = 0` in `usize` | definition says equal | sound: equality is only claimed where both sides evaluate, and the failing side is an instance error (§9) |
| H4 | a polynomial blows up (`(N + 1) * (N + 1) * …`) | normalisation | the cap (§5.1) |
| H5 | two atoms that are equal but differ in structure (`N / 2 * 2` vs `N - N % 2`) | definition walk | refused, naming the atom (decision 7) — incompleteness, never unsoundness |
| H6 | a const call's identity across modules | an atom | by the function's **key**, not its spelling (the rule instantiation symbols already follow) |
| H7 | a reading thrown away (`sandboxed_reading`) registers instances of `Buf[N + 1]` | the four rewinds | `ConstTerm` is a value; it registers nothing. An instance created from it is rewound like any other |
| H8 | offset solving in a caller's definition walk (`G` a term) | `unify` | ring subtraction; agreement by form (§5.3) |
| H9 | `if const N == 0` arms that return `[0]T` where `[N]T` is declared | strictness of decision 6 | the `N == c` refinement (decision 10) |
| H10 | trait conformance: `div(self, o: Q[L2, T2]) -> Q[L1 - L2, T1 - T2]` against `div(self, rhs: Rhs) -> Out` | `conformance.sysl` | substitution then form equality |
| H11 | overlap of two `impl`s whose arguments compute | coherence | decision 11: receiver and supplied arguments are bare or constant, so overlap is decided as today |
| H12 | `.syslib` carries generic declarations | separate compilation | generics are re-read from their source text in the artifact; a signature with a computed argument is text like any other. Item TL3 proves it with a library-built generic |
| H13 | `sysl doc` / LSP hover of a signature | printers | as written (decision 12); `doc_signature` already prints `ValueArgType` expressions |
| H14 | mangled names | instances only | every instance's arguments are concrete; mangling is unchanged (negative arguments already mangle, probed) |
| H15 | a trait object of a trait whose member computes in its signature | object safety | a member with its own value parameters is already a generic member and already unerasable; computing over the trait's own parameters changes nothing |
| H16 | a program accepted today becomes an error (`cast`, `wrong` above) | every org repo | behaviour change; TL2 runs the org census with the new check (§10) and the release notes list it |
| H17 | a type pack's length (`..T`) beside a value parameter | packs | out of scope: a pack's length is not a value parameter; `[N]T` inside a `for const` copy is unchanged |

## 7. Observable behaviour, and the reference-page text

**Diagnostics** (composed in the style of `compiler-notes § A new diagnostic`; each is pinned by a
test row and, where marked, a page's `error` block):

| # | case | text |
|---|---|---|
| D1 | forms differ by a constant (`-> Buf[N] = Buf[N + 1](…)`) | `function 'wrong' should return Buf[N], but its body yields Buf[N + 1] — the two differ by 1 for every N` (page) |
| D2 | forms differ otherwise | `function 'cast' should return Buf[M], but its body yields Buf[N] — 'N' and 'M' are different value parameters` |
| D3 | an atom stands in the way | `Buf[N / 2 * 2] is not known to be Buf[N]: '/' by 2 is compared as written, and 'N / 2 * 2' is not 'N' for every N` (page) |
| D4 | instance does not evaluate / fit | `'concat' at N = 2, M = 3: the length 'N - M' is -1, which does not fit usize` at the use, with a note at the declaration (page) |
| D5 | offset solve does not fit | `'shrink' takes Buf[N + 1], and Buf[0] would need N = -1, which does not fit usize` |
| D6 | no position solves a parameter | `'N' is solved from no argument: '[2 * N]int' computes with it, and a parameter is solved only where it stands alone or plus or minus a constant — write 'N' alone in another argument, or have callers write 'f[…]'` (page) |
| D7 | `where` false | `'first' needs 'N > 0', and N is 0 here` (+ `in 'outer[0]', called from …` where nested) (page) |
| D8 | `where` predicate on a type parameter | `'T' is a type parameter, and a predicate in 'where' is about value parameters — a type parameter is bounded by a trait, 'T: Trait'` |
| D9 | `impl` parameter fixed by nothing | today's text gains the alternative: `… does not appear in 'Q[L1, T1]' or in an argument of 'Div' a use supplies, so nothing would ever fix it` |
| D10 | an `impl` receiver computes | `an 'impl' covers 'Buf[N + 1]', and a block's receiver names its parameters alone — write 'Buf[N]'` |
| D11 | too large | `the type argument '…' is too large to compare (over 64 terms)` |

The existing *"the length 'N + 1' computes with the value parameter 'N' …"* is **removed**.

**`generics.md`**: `§ What may be written with N` is rewritten — the text below is the page's, its
blocks runnable — and the table row *type-level arithmetic* leaves `§ What is deliberately not here`
(replaced by *solving from a product*, `[2 * N]T`, with D6's reason). `§ A bound may be written out of
line, with where` gains the predicate paragraph and D7; *A value parameter cannot be bounded* stays,
narrowed to trait bounds. A new `§ What it is for: units` carries the `Q` example of §Why, with
`sqrt` needing `where L % 2 == 0`.

> ### What may be written with `N`
>
> `N` may be computed with in a type, as in a body. A result built from parameters is computed for
> each instance:
>
> ```sysl
> concat[const N: usize, const M: usize](a: [N]int, b: [M]int) -> [N + M]int
>     var r = [0; N + M]
>     for i in 0..<N do r[i] = a[i]
>     for j in 0..<M do r[N + j] = b[j]
>     r
>
> print(concat([1, 2], [3]))
> ```
>
> ```output
> [1, 2, 3]
> ```
>
> **Two lengths are one when they are one polynomial.** `N + M` and `M + N`, `2 * N` and `N + N`,
> `N * (M + 1)` and `N * M + N` are each one type, decided where the generic is declared rather than
> per instance. `+`, `-` and `*` are reasoned about; `/` and `%` only when a constant divides exactly;
> anything else — `min`, a comparison, a call — is compared as written. A length the compiler cannot
> show equal is refused where it is written, not at a call that happens to make it so:
>
> *(D3's block)*
>
> **A parameter is solved from a length that is the parameter alone, or the parameter plus or minus a
> constant.** `head` takes an array of at least one element:
>
> ```sysl
> head[const N: usize](xs: [N + 1]int) -> int = xs[0]
>
> print(head([7, 8, 9]))
> ```
>
> ```output
> 7
> ```
>
> Matching `[3]int` against `[N + 1]int` makes `N` 2. A length that multiplies a parameter solves
> nothing (*D6's block*); it may still be written where another argument fixes the parameter, and is
> then checked.
>
> **Each instance must evaluate its lengths.** A length that comes out negative, or does not fit, is
> refused at the use that made the instance (*D4's block*), and a `where` clause says so in the
> author's words (*D7's block*).

**`declarations.md` / `types.md`**: nothing — the forms are the existing type grammar.

## 8. `--explain`

None. The normal form is shown in D1–D3 where it matters; no flag is added.

## 9. Soundness

**Claim.** No program whose types disagree runs, and no program is refused for an equality that holds
at every instance *unless* it involves an atom (§5.1) — the definition-time layer is sound, complete
for `+`, `-`, `*` and exact division, and incomplete only by refusing.

1. **Instances are the backstop.** Each instance's body is read again with every argument concrete
   (`resolve_type.sysl`: "the body is analyzed again for each instantiation with the parameter
   bound"), and concrete value arguments compare by value. Whatever the definition walk concludes,
   an ill-typed instance is refused. The design changes *when* an error is reported and *what is
   inferred*, not this invariant.
2. **Form equality implies ℤ-equality.** The normal form is the standard canonical form of the
   polynomial ring ℤ[x₁…xₙ, atoms]; two forms are identical iff the polynomials are equal as
   polynomials, and equal polynomials agree at every assignment. Exact division is sound because `P
   / c` with `c | P` is the unique `Q` with `c·Q = P`, which truncating division returns.
3. **ℤ-equality implies machine equality where both sides evaluate.** The instance evaluator
   computes in the parameter types with overflow refused, so a side that evaluates has its ℤ value;
   two evaluated sides with equal ℤ values are equal. A side that does not evaluate is D4 (H3).
4. **Completeness for the ring fragment.** Two polynomials over ℤ that agree on every integer
   assignment are equal as polynomials (an infinite integral domain), so the definition walk refuses
   no `+`/`-`/`*` equality that is true over ℤ. Over a *bounded* parameter type it is conservative: a
   polynomial vanishing on all 256 values of a `u8` (degree ≥ 256) is refused — beyond the degree cap
   anyway.
5. **Solving is unique.** `N + c = g` has the one solution `g - c` in ℤ; it is accepted iff it fits.
   Steps 1–3 of §5.3 run in a fixed order and never backtrack.
6. **Termination.** Normalisation is structural recursion on the written expression, bounded by the
   cap; atoms do not unfold `const` calls; `where` is evaluated only at instances, by the evaluator
   that already terminates (its step budget).
7. **What is not claimed.** The definition walk does not use `where` facts (decision 9): `N - 1 + 1`
   is accepted (2.), but `[N - 1]T` is not *known* to evaluate at the definition even under
   `where N > 0` — it is evaluated at each instance, where the predicate has already been checked
   first, so the user sees D7 rather than D4. Reasoning from predicates would need linear integer
   arithmetic (Presburger, decidable but costly) and is an open question.

## 10. The test matrix

Each item owns its rows. Proofs are `error` substrings, `output` blocks, and for TL2 the org census.
Axes: **position** (function param / result, member, field, variant payload, body local, `impl` trait
argument, `impl` receiver, default) × **kind** (array length, vector lanes, struct value argument,
`bool` argument) × **operation** (+, −, ×, exact ÷, atom) × **where checked** (definition, instance,
call-site solve) × **build** (program, `.syslib`, `sysl doc`).

**Normal form (TL1, unit tests on `ConstTerm`):**

| # | input | expect |
|---|---|---|
| N1 | `N + M`, `M + N` | one form |
| N2 | `2 * N`, `N + N`, `N * 2` | one form |
| N3 | `N * (M + 1)`, `N * M + N` | one form |
| N4 | `N - N` | `0` |
| N5 | `(2 * N + 4) / 2`, `N + 2` | one form |
| N6 | `(2 * N + 3) / 2` | atom; ≠ `N + 1` |
| N7 | `min(N, M)`, `min(M, N, N)` | one form |
| N8 | `N + 1 > N` | `true` |
| N9 | `double(N)` in two modules naming one function | one atom |
| N10 | a 65-term product | refused (D11) |
| N11 | parameters renamed, same order | one form |

**Positive (accepted, and the output is right):**

| # | program | expect |
|---|---|---|
| P1 | `concat` of §7 | `[1, 2, 3]` |
| P2 | `head(xs: [N + 1]int)` with `[7, 8, 9]` | `7`, `N = 2` |
| P3 | `push[N](xs: [N]int, x: int) -> [N + 1]int` | runs |
| P4 | `Mat[R, C] * Mat[C, K]` | product right, type `Mat[R, K]` |
| P5 | units: `Length / Time` is `Velocity`; `d / t / t` is `Q[1, -2]` | runs |
| P6 | `struct S[const N: usize] data: [N * 2]int` | `S[2].data.len` is 4 |
| P7 | `-> [M + N]T` with body yielding `[N + M]T` | accepted |
| P8 | `if const N == 0 then [] else …` with result `[N]int` | accepted (decision 10) |
| P9 | a caller generic over `N` calling `head` with `[N + 1]int` | solves `N' = N` symbolically |
| P10 | `<N / 2>f32` from `<N>f32` with `where N % 2 == 0` | runs at `N = 8` |
| P11 | `Flag[N > 3]` (`bool` argument from a comparison) | one type per truth value |
| P12 | `Pad[const M, const N = M * 2]` (existing default) | unchanged |
| P13 | a generic with a computed result, built as a `.syslib` and called from a program | runs |
| P14 | `sysl doc` of `concat` | signature as written |

**Refused (with the text of §7):**

| # | program | expect |
|---|---|---|
| R1 | `wrong` (never called) | D1 at the declaration |
| R2 | `cast[N, M]` (never called) | D2 |
| R3 | `-> [N]int` with body `[N / 2 * 2]int` | D3 |
| R4 | `[N - M]int` at `N = 2, M = 3` | D4 at the call, note at the declaration |
| R5 | `shrink(Buf[0](…))` | D5 |
| R6 | `f[const N: usize](xs: [2 * N]int)` | D6 at the declaration |
| R7 | `first` with `where N > 0` at `N = 0`, directly and through a generic caller | D7, with the chain |
| R8 | `where T > 0` with `T` a type parameter | D8 |
| R9 | `impl` with a parameter in neither receiver nor supplied argument | D9 |
| R10 | `impl[const N: usize] X for Buf[N + 1]` | D10 |
| R11 | `L1 - L2` overflowing `i8` at an instance | D4 |
| R12 | `Length + Time` | today's *needs 'Add[Q[0, 1]]'* |
| R13 | `N + M` with `N: usize`, `M: u8` | the ordinary mixed-width refusal |
| R14 | `where N: Display` | today's text, unchanged |

**Design-claim pass**: every row of §6 has a row above or is named in an item; every diagnostic of
§7 is pinned once; every block in the §7 page text runs in the docs sweep.

## 11. Implementation items, in order

Each is one agent, on its own branch off dev, gated targeted (its own test file, the files naming the
functions it changes — `tests_value_generics.sysl`, `tests_ice_if_const_expr.sysl` and the
`impl`-shape tests `tests_igx_`/`tests_isx_`/`tests_idt_` are the blast radius — and the docs sweep of
`generics.md`).

1. **TL1 — `ConstTerm`** (new `const_term.sysl`: the form of §5.1, built from an `Expr` and a
   parameter list; equality; printing; the cap). No language change, no diagnostic reaches a user.
   Tests (`tlk_`): N1–N11. Design-only dependency for the rest.
2. **TL2 — symbolic value parameters in the definition walk** (`types.sysl`'s `ConstArg` gains the
   term; `type_eq`; `generic.sysl`'s stand-in creation; the `if const N == c` refinement; D1, D2,
   D3). **Behaviour change**: R1, R2 become errors. Runs the org census through marshal with the
   stage-1 compiler and reports every repo the check newly refuses (a fix there is that repo's sweep).
   Tests (`tls_`): R1–R3, P7, P8, and today's per-instance texts left only where no definition exists
   (a library body read only per instance).
3. **TL3 — computed arguments in output positions** (`hoist.sysl`'s `length_arithmetic` removed;
   `resolve_type.sysl`'s `resolve_array` and value-argument substitution evaluate and fit-check; D4;
   D11 reached). Page: `generics.md § What may be written with N` and the absent-table row. Tests
   (`tla_`): P1, P3, P6, P10, P11, P12, P13, P14, R4, R11, R13. Needs TL2.
4. **TL4 — solving in input positions** (`generic.sysl`'s `unify` for `ConstArg`: bare, offset, check
   order; the declaration-time D6; D5). Tests (`tlu_`): P2, P9, R5, R6, and an offset position beside
   a bare one that disagrees. Needs TL3. Parallel with TL5 and TL6.
5. **TL5 — `where` predicates** (`parse_decl`/`parse_member` clause entry; `hoist.sysl` checks the
   names; evaluation at `instantiate`, at a written type, at `impl` selection; D7, D8, the chain note).
   Page: `§ A bound may be written out of line`. Tests (`tlw_`): R7, R8, R14, a struct predicate at a
   written type and at an inferred one, a predicate on an `impl`, P10's `where`. Needs TL3.
6. **TL6 — `impl` parameters from a supplied argument** (`hoist_impl.sysl`'s `check_argument_names`;
   operator and member selection solving from `Rhs`; D9, D10; the result-position rule of §5.6).
   Tests (`tli_`): P4, P5, R9, R10, R12, and an `impl Mul[real]` beside the generic block (no overlap).
   Needs TL3.
7. **TL7 — units on the page** (`generics.md § What it is for: units`: §Why's `Q`, `Mul`, `Div`,
   `sqrt` with `where`). Docs only (Rule 4-0); runs the page's sweep. Needs TL4–TL6.

Items 1 → 2 → 3 are sequential; 4, 5 and 6 run in parallel after 3; 7 last. Every item updates
`compiler-notes.md § Members, traits, generics` (the stand-in zero's new meaning, the term, the solve
order) and adds its `fix_tests.tsv` row where the flow asks.

## 12. Open questions — for the user

1. **The behaviour change of TL2.** `cast[N, M](b: Buf[N]) -> Buf[M] = b` and an uncalled
   `wrong` compile on 0.12.0 and are refused after. Is a straight refusal in 0.13 right, or should
   0.13 warn and 0.14 refuse? (TL2's census gives the count before this needs answering.)
   **Decided (user, 2026-11-26): refuse outright in 0.14, with no warning period.** (The refusal of
   `cast` and `wrong` already shipped in 0.13 — §2.3.)
2. **Dimension vectors.** SI has seven base dimensions, so `Q[L, M, T, I, Θ, N, J]` is seven
   parameters on every signature. Should a value parameter be allowed to be a **struct of integers**
   (`const D: Dim`, with `D1 + D2` defined field-wise) — a new value kind, which `generics.md § Which
   values` currently restricts to integers, `bool`, `char` and simple enums?
3. **Rational exponents.** F# allows `m^(1/2)`; with integer exponents `sqrt` needs `where L % 2 ==
   0`. Is that enough, or should exponents be fixed-point (`q`-typed value parameters are excluded
   today along with floats)?
4. **Where the units live.** A `sysl.units` module in the library, a `sysl-lang/units` package, or only
   the page's example?
5. **Solving from a product.** `[2 * N]T` solves nothing here (D6). Solving it with a divisibility
   check is decidable for one unknown; is it wanted, or is the offset rule the whole of it?
6. **Predicates as facts.** Should the definition walk reason from `where` (so that `where N > 0`
   proves `[N - 1]T` evaluates at the definition, Presburger-style), or is checking predicates per
   instance (decision 9) the line?
7. **Propagation of predicates.** A generic caller of `first[N] where N > 0` is checked per instance
   and reported with a chain (C++/Zig style). Rust would require the caller to restate the predicate.
   Keep the chain, or require restating?
