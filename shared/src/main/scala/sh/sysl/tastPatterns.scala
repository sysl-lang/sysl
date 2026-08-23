package sh.sysl

/** Typed patterns — the `match` arm's tests, resolved.
 *
 * Split from `tast.scala`, which holds `TExpr`; `sealed` is why they are two files rather than one
 * section. `TArm` pairs these with the block it guards and stays beside the expression it belongs
 * to.
 */

/** A typed pattern, matched against a value of type `ty`. Patterns are recursive: a variant
 * pattern's sub-patterns match the payload fields, which may themselves be variants.
 */
sealed trait TPattern { def ty: Type }

/** `_` — matches anything, binds nothing. */
case class TWildPattern(ty: Type) extends TPattern

/** A binding: matches anything and stores the value in a fresh local. */
case class TBindPattern(name: String, ty: Type) extends TPattern

/** `n @ pat` — whatever `inner` matches, with the **whole** value bound to `name` besides.
 *
 * The type is the inner pattern's, since both are tests of the same value. Everything that reads a
 * pattern reads this one by reading `inner` and adding the binding: the test is the inner test, the
 * exhaustiveness is the inner coverage, and the refutability is the inner refutability — a binding
 * never rules anything in or out, which is exactly why the wrapper can be transparent.
 */
case class TAtPattern(name: String, inner: TPattern) extends TPattern { def ty: Type = inner.ty }

/** A scalar literal: matches a value equal to it. */
case class TLitPattern(value: TExpr) extends TPattern { def ty: Type = value.ty }

/** A scalar range `lo..hi` / `lo..<hi`. */
case class TRangePattern(lo: TExpr, hi: TExpr, inclusive: Boolean) extends TPattern { def ty: Type = lo.ty }

/** A data-enum variant `V(sub…)`: matches when the tag is the variant's, then recurses into
 * each payload field with the corresponding sub-pattern.
 */
case class TVariantPattern(enumTy: Type.Enum, variant: Type.EnumVariant, args: List[TPattern]) extends TPattern {
  def ty: Type = enumTy
}

/** A struct pattern: `args` holds one sub-pattern per field in declaration order — a wildcard for
 * any field the source left unlisted — so the positional and named source forms lower to one shape.
 * A struct has a single form, so a struct pattern whose sub-patterns are all irrefutable matches
 * every value of the type.
 */
case class TStructPattern(struct: Type.Struct, args: List[TPattern]) extends TPattern {
  def ty: Type = struct
}
