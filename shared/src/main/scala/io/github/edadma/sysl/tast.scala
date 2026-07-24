package io.github.edadma.sysl

/** The *typed* abstract syntax tree.
 *
 * The analyzer turns the structural `ast.scala` tree into this one: every node carries a
 * resolved `Type`, names are resolved to their unique bindings, and every rule that could
 * fail (unknown name, type mismatch, wrong arity) has already been checked. Codegen is then
 * a straight lowering — it selects instructions from the types it is handed and never makes
 * a semantic decision of its own.
 */

sealed trait TExpr {
  def ty: Type
}

/** An integer, `char`, or simple-enum constant — anything whose value is one whole number. */
case class TIntLit(value: BigInt, ty: Type) extends TExpr

/** A floating-point constant, held as the bits of its `double` value. A narrower type is
 * reached by rounding that constant down to it, which costs nothing at run time.
 */
case class TFloatLit(bits: String, ty: Type) extends TExpr

case class TStrLit(value: String)   extends TExpr { def ty: Type = Type.Str  }
case class TBoolLit(value: Boolean) extends TExpr { def ty: Type = Type.Bool }
case class TUnitLit()               extends TExpr { def ty: Type = Type.Unit }

/** `null` at the pointer type its context fixed. */
case class TNullLit(ty: Type) extends TExpr

/** Puts a value on the heap, because a `&T` was expected where a `T` was written. The box holds
 * the refcount, the deallocation hook, and the payload; the expression yields a reference the
 * caller owns.
 */
case class TBox(value: TExpr, refTy: Type.Ref) extends TExpr { def ty: Type = refTy }

/** The zero value of a type: what a declaration with no initializer starts at. */
case class TZero(ty: Type) extends TExpr

/** `[a, b, c]` — an array value built from its elements. */
case class TArrayLit(elems: List[TExpr], arrayTy: Type.Array) extends TExpr { def ty: Type = arrayTy }

/** `a[i]` — one element of an array, slice, or string, checked against the length. It is a
 * *place* when its receiver is one, which is what makes `a[i] = v` and `&a[i]` ordinary.
 */
case class TIndex(receiver: TExpr, index: TExpr, ty: Type) extends TExpr

/** `a.len` — how many elements, as a `usize`. Constant for an array, a word of the view for a
 * slice or a string, where it counts bytes.
 */
case class TLen(receiver: TExpr) extends TExpr { def ty: Type = Type.Usize }

/** `s.bytes` — a string's bytes, as a `[]u8`. The same three words the string already is, so
 * this reinterprets rather than converts; only the validity guarantee is given up.
 */
case class TBytes(receiver: TExpr) extends TExpr { def ty: Type = Type.Slice(Type.Byte) }

/** `a[lo..hi]` — a view of some of `base`'s elements. `base` is either the reference that owns
 * a heap array or another view, so that one expression yields both where the elements are and
 * what keeps them alive. An absent bound is the start or the end.
 */
case class TSlice(base: TExpr, lo: Option[TExpr], hi: Option[TExpr], inclusive: Boolean, sliceTy: Type.View)
    extends TExpr {
  def ty: Type = sliceTy
}

/** An explicit scalar conversion, written with call syntax: `u32(c)`, `byte(n)`, `char(u)`.
 * Every conversion between scalar types is written, never inferred.
 */
case class TCast(operand: TExpr, ty: Type) extends TExpr

/** Reads a local variable (or parameter) by its unique name. */
case class TLoad(name: String, ty: Type) extends TExpr

/** `*p` — reads through a pointer or reference. */
case class TDeref(operand: TExpr, ty: Type) extends TExpr

/** `&place` — the address of a place, as a raw pointer. */
case class TAddrOf(place: TExpr, ty: Type) extends TExpr

/** `place = value` — stores and yields the assigned value. The place is a `TLoad`, a `TDeref`,
 * or a `TField` chain over one of those; codegen computes its address rather than its value.
 */
case class TStore(place: TExpr, value: TExpr, ty: Type) extends TExpr

/** A compound assignment `place op= value`, yielding the updated value. */
case class TUpdate(place: TExpr, op: String, value: TExpr, ty: Type) extends TExpr

/** `++`/`--`, prefix (new value) or postfix (old value). */
case class TIncDec(place: TExpr, op: String, pre: Boolean, ty: Type) extends TExpr

case class TBinary(op: String, left: TExpr, right: TExpr, ty: Type) extends TExpr
case class TUnary(op: String, operand: TExpr, ty: Type)             extends TExpr

/** `&&` / `||` — short-circuit, always boolean. */
case class TLogical(op: String, left: TExpr, right: TExpr) extends TExpr { def ty: Type = Type.Bool }

/** A comparison chain `a < b < c`, ANDing the pairwise results. */
case class TCompare(operands: List[TExpr], ops: List[String]) extends TExpr { def ty: Type = Type.Bool }

/** The built-in `print`. */
case class TPrint(args: List[TExpr]) extends TExpr { def ty: Type = Type.Unit }

/** The built-in `str(x)` — a primitive value's string form: a decimal for an integer, its UTF-8
 * for a `char`, `"true"`/`"false"` for a `bool`, a `%g` rendering for a float, and a `string`
 * unchanged. It allocates a fresh buffer for every type but `string`, where it is the identity;
 * a `Display` trait method replaces it once traits land.
 */
case class TStr(arg: TExpr) extends TExpr { def ty: Type = Type.Str }

/** `format(value, spec)` — one value rendered through a printf specifier, the lowering of an
 * `f"…"` hole. Always allocates a fresh string; `spec` is the sysl specifier (`%08.2f`), which
 * codegen turns into the C one it hands `snprintf`.
 */
case class TFormat(arg: TExpr, spec: String) extends TExpr { def ty: Type = Type.Str }

/** A call to a user function. */
case class TCall(name: String, args: List[TExpr], ty: Type) extends TExpr

/** Positional construction of a value struct. */
case class TStructNew(struct: Type.Struct, args: List[TExpr]) extends TExpr { def ty: Type = struct }

/** Construction of an enum value: a simple enum's integer constant, or a data enum's variant
 * (with `args` for a data-carrying variant, empty for a nullary one).
 */
case class TEnumNew(enumTy: Type.Enum, variant: Type.EnumVariant, args: List[TExpr]) extends TExpr {
  def ty: Type = enumTy
}

/** The postfix `?` on an `Option`/`Result` value: yields the success payload, or returns the
 * enclosing function early with the failure re-wrapped in *its* return type.
 *
 *   - `okVariant` / `failVariant` are the operand's two variants.
 *   - `retEnum` / `retFail` are the enclosing function's return enum and *its* failing
 *     variant, which the early return constructs (carrying the operand's error payload, if
 *     the variant has one).
 */
case class TTry(
    operand: TExpr,
    okVariant: Type.EnumVariant,
    failVariant: Type.EnumVariant,
    retEnum: Type.Enum,
    retFail: Type.EnumVariant,
    ty: Type,
) extends TExpr

/** Read field `index` of a struct value. It is also a *place* when its receiver is one, which
 * is what makes `s.f = v` and `p.f = v` (through a pointer) ordinary assignments.
 */
case class TField(receiver: TExpr, index: Int, ty: Type) extends TExpr

/** `if cond then … else …` as a value (or unit when there is no else). */
case class TIf(cond: TExpr, thenBlock: TBlock, elseBlock: Option[TBlock], ty: Type) extends TExpr

/** `match scrutinee` — arms are tried in order; `ty` is the common arm type (or unit). */
case class TMatch(scrutinee: TExpr, arms: List[TArm], ty: Type) extends TExpr

/** One arm: the scrutinee matches if any alternative pattern holds and the guard (if any) is
 * true. Only non-binding patterns may share an arm as alternatives.
 */
case class TArm(patterns: List[TPattern], guard: Option[TExpr], body: TBlock)

/** A typed pattern, matched against a value of type `ty`. Patterns are recursive: a variant
 * pattern's sub-patterns match the payload fields, which may themselves be variants.
 */
sealed trait TPattern { def ty: Type }

/** `_` — matches anything, binds nothing. */
case class TWildPattern(ty: Type) extends TPattern

/** A binding: matches anything and stores the value in a fresh local. */
case class TBindPattern(name: String, ty: Type) extends TPattern

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

/** A block: a sequence of statements optionally ending in a value expression. When `result`
 * is `None` the block's type is `unit`.
 */
case class TBlock(stmts: List[TStmt], result: Option[TExpr], ty: Type)

sealed trait TStmt

case class TVarDecl(name: String, ty: Type, init: TExpr) extends TStmt
case class TExprStmt(expr: TExpr)                         extends TStmt

/** `while cond body [else …]` as an expression. `body` runs for effect each iteration; a `break`
 * in it carries the loop's value, and `elseBlock` (if present) supplies the value on normal
 * completion. `ty` is the loop's result type — `unit` when nothing carries a value.
 */
case class TWhile(cond: TExpr, body: List[TStmt], elseBlock: Option[TBlock], ty: Type) extends TExpr

/** `for name in lo..hi [else …]` — the loop variable has the integer type `varTy` of its bounds;
 * `ty` is the loop expression's result type.
 */
case class TFor(name: String, varTy: Type, lo: TExpr, hi: TExpr, inclusive: Boolean,
                body: List[TStmt], elseBlock: Option[TBlock], ty: Type) extends TExpr

/** `for name in seq [else …]` over an array or a slice. The loop variable is a *copy* of each
 * element, and the sequence is evaluated once.
 */
case class TForEach(name: String, elemTy: Type, seq: TExpr, body: List[TStmt],
                    elseBlock: Option[TBlock], ty: Type) extends TExpr
case class TReturn(value: Option[TExpr])                  extends TStmt

/** `break [expr]` and `continue`. `break` carries the loop's value when the loop yields one.
 * `depth` names the target loop by its distance out from the innermost — `0` is the nearest,
 * a larger number a loop reached through a `'label` — and indexes the codegen loop stack directly.
 */
case class TBreak(value: Option[TExpr], depth: Int) extends TStmt
case class TContinue(depth: Int)                    extends TStmt

/** A user function. Parameters carry their unique names (the codegen allocates a slot for
 * each so the body can read and mutate them uniformly).
 */
case class TFunc(name: String, params: List[(String, Type)], retTy: Type, body: TBlock)

/** A whole program: hoisted struct, enum, and function declarations, plus the top-level
 * statements that make up `main`. Only data enums appear in `enums` — a simple enum lowers to
 * `i32` and needs no type declaration.
 */
case class TProgram(
    structs: List[Type.Struct],
    enums: List[Type.Enum],
    funcs: List[TFunc],
    main: List[TStmt],
)
