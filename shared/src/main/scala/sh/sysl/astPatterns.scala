package sh.sysl

/** The patterns a `match` arm, an `is` condition, or an irrefutable binding is written with.
 *
 * `Pattern` is sealed, so every form lives here: a walk over one is exhaustive by construction,
 * which is what lets exhaustiveness checking be a question about the *scrutinee* rather than about
 * whether the compiler knows all the shapes.
 */

/** A pattern in a `match` arm. */
sealed trait Pattern

/** `1`, `"one"`, `true` — matches a value equal to the literal expression. */
case class LitPattern(value: Expr) extends Pattern

/** `1..10` / `1..<10` — matches a value in the range. */
case class RangePattern(lo: Expr, hi: Expr, inclusive: Boolean) extends Pattern

/** `_` (and `else`, which desugars to the same) — matches anything. */
case object WildcardPattern extends Pattern

/** A bare name. The analyzer resolves it to a *nullary variant* pattern when the name is a
 * variant of the scrutinee's enum, and to a *binding* (which matches anything and names the
 * value) otherwise.
 */
case class IdentPattern(name: String) extends Pattern

/** `` `limit` `` — a backtick-quoted name, which **references** what the name already stands for
 * and tests the value against it, rather than binding a new one.
 *
 * It is the one pattern form that never binds, and that is the whole of why the quoting is there:
 * a bare name binds unless something answers to it, so a reader cannot tell a test from a binding
 * without knowing what is in scope. The backticks say which was meant, at the site.
 *
 * A `const` folds to the literal test it always did (`reference/modules.md § const — a value`).
 * Anything else — a `val`, an `extern` variable, a local, a parameter — is storage read where the
 * match runs, so the arm becomes a runtime equality against whatever it holds then. That is a test
 * the compiler cannot reason about, so such an arm contributes nothing to exhaustiveness and a
 * catch-all stays required.
 */
case class EqPattern(name: String) extends Pattern

/** `Circle(r)`, `Wrap(Val(v))` — matches a data-enum variant and recurses into its fields.
 * Each sub-pattern matches the field at that position (a binding, a nested variant, `_`, or a
 * literal). Against a *struct* value the same positional form destructures every field in
 * declaration order.
 */
case class VariantPattern(name: String, args: List[Pattern]) extends Pattern

/** `Point{x, y}`, `Point{x: 0}` — matches a struct by field name. Each entry is a field and the
 * sub-pattern it must match; the shorthand `{x}` binds field `x` to a variable of the same name.
 * Fields left unlisted are unconstrained, so a named pattern may match on a subset.
 */
case class StructPattern(name: String, fields: List[(String, Pattern)]) extends Pattern

/** `(a, b)` — matches a tuple, one sub-pattern per part (`reference/types.md § Tuples`). It is the
 * positional struct pattern with the name left off, which is all a tuple has to leave off.
 */
case class TuplePattern(args: List[Pattern]) extends Pattern

/** `n @ Circle(r)` — matches what the inner pattern matches, and binds the **whole** value to `n`
 * besides.
 *
 * It answers the one thing destructuring cannot: a pattern that takes a value apart has, by the time
 * the arm runs, only the parts. Where the arm wants the value back — to hand it on, to store it, to
 * return it — the alternative is a wildcard arm that tests the shape a second time, or a binding
 * that gives up the destructuring. This is both at once, which is why Scala, Rust, OCaml and Haskell
 * all carry it.
 *
 * The `@` is the same character an annotation opens with and the two never compete: an annotation's
 * is a **prefix**, at the start of a line above a declaration, and this one is **infix**, between a
 * name and a pattern, inside a pattern. Nothing reads a pattern where a declaration may stand.
 */
case class BindPattern(name: String, inner: Pattern) extends Pattern

/** `pat, pat, … [if guard] -> body`. Alternatives share one body; the optional guard is a
 * boolean the scrutinee value must additionally satisfy. Each body is a statement list whose
 * trailing expression is the arm's value.
 */
case class MatchArm(patterns: List[Pattern], guard: Option[Expr], body: List[Stmt]) extends Positioned
