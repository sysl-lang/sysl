package io.github.edadma.sysl

/** The sysl abstract syntax tree.
 *
 * This is the *settled* surface only — the expression grammar of
 * `01-scalar-types-and-operators.md` plus the statement and declaration forms needed to
 * write and run real programs (functions, structs, loops). It grows as the language does;
 * nothing here is ported wholesale from the previous implementation, whose tree carried a
 * large amount of cut or deferred surface.
 *
 * The tree is *untyped*: the parser produces it structurally, and the analyzer
 * (`Analyzer`) turns it into a typed tree (`tast.scala`) that codegen consumes.
 */

sealed trait Expr

case class IntLit(value: BigInt, suffix: Option[String]) extends Expr
case class FloatLit(text: String, suffix: Option[String]) extends Expr
case class CharLit(codepoint: Int)                        extends Expr
case class StrLit(value: String)                          extends Expr
case class BoolLit(value: Boolean)                        extends Expr

/** `()` — the sole value of `unit`. */
case class UnitLit() extends Expr

/** `null` — the absent raw pointer. It has no type of its own and takes the `*T` its context
 * expects; there is no null in the safe subset, where an absent reference is `Option[&T]`.
 */
case class NullLit() extends Expr

case class Ident(name: String) extends Expr

/** A prefix operator: `-x`, `!b`, `~n`, `*p`, `&x`. */
case class Unary(op: String, operand: Expr) extends Expr

case class PreIncDec(op: String, operand: Expr)  extends Expr
case class PostIncDec(op: String, operand: Expr) extends Expr

case class Binary(op: String, left: Expr, right: Expr) extends Expr

/** A comparison chain: `a < b < c` holds `operands = [a, b, c]`, `ops = ["<", "<"]` and
 * means `a < b && b < c`. A single comparison is just the two-operand case.
 */
case class Compare(operands: List[Expr], ops: List[String]) extends Expr

/** `a..b` (inclusive) / `a..<b` (exclusive), with either end optionally open. */
case class RangeExpr(lo: Option[Expr], hi: Option[Expr], inclusive: Boolean) extends Expr

/** Assignment as an expression (`=` and the compound forms), right-associative. */
case class Assign(op: String, target: Expr, value: Expr) extends Expr

case class Call(callee: Expr, args: List[Expr]) extends Expr
case class Index(receiver: Expr, index: Expr)   extends Expr
case class Field(receiver: Expr, name: String)  extends Expr

/** The postfix `?` error-propagation operator. */
case class TryExpr(expr: Expr) extends Expr

case class Tuple(elements: List[Expr]) extends Expr

/** `[a, b, c]` — an array literal, whose length is how many elements were written. An empty
 * one has no element type of its own and takes it from the context.
 */
case class ArrayLit(elements: List[Expr]) extends Expr

/** `if cond then a else b` as an **expression**: it yields the value of the taken branch.
 * In statement position the `else` may be omitted and the whole thing has type `unit`.
 * Each branch is a statement list whose trailing expression is the branch's value.
 */
case class IfExpr(cond: Expr, thenBody: List[Stmt], elseBody: Option[List[Stmt]]) extends Expr

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

/** `Circle(r)`, `Wrap(Val(v))` — matches a data-enum variant and recurses into its fields.
 * Each sub-pattern matches the field at that position (a binding, a nested variant, `_`, or a
 * literal).
 */
case class VariantPattern(name: String, args: List[Pattern]) extends Pattern

/** `pat, pat, … [if guard] -> body`. Alternatives share one body; the optional guard is a
 * boolean the scrutinee value must additionally satisfy. Each body is a statement list whose
 * trailing expression is the arm's value.
 */
case class MatchArm(patterns: List[Pattern], guard: Option[Expr], body: List[Stmt])

/** `match scrutinee` with indented arms — an **expression** yielding the taken arm's value
 * (or `unit` in statement position). Arms are tried top to bottom.
 */
case class MatchExpr(scrutinee: Expr, arms: List[MatchArm]) extends Expr

sealed trait TypeRef

/** A named type, optionally applied to type arguments: `int`, `Box[int]`,
 * `Result[int, string]`. A bare name may also be a type *parameter* of the enclosing
 * declaration; the analyzer decides which from the substitution in scope.
 */
case class NamedType(name: String, args: List[TypeRef] = Nil) extends TypeRef

/** `*T` — a raw pointer to `T`. */
case class PtrType(inner: TypeRef) extends TypeRef

/** `&T`, or `&sync T` when the refcount is atomic. */
case class RefType(inner: TypeRef, sync: Boolean) extends TypeRef

/** `[N]T` — a fixed array — or `[]T`, a slice, when no length is written. */
case class ArrayType(length: Option[Expr], elem: TypeRef) extends TypeRef

/** One `name: type` binding, shared by function parameters and struct fields. */
case class Param(name: String, typ: TypeRef)

/** How an instance member takes its receiver — the memory-mode sigil written before `self`.
 * A property receiver is implicit and not spelled, so it is absent here (a property carries no
 * `RecvMode`); an associated function has no receiver at all.
 */
enum RecvMode:
  case ByValue                 // self
  case ByPtr                   // *self
  case ByRef(sync: Boolean)    // &self, &sync self

/** A member declared in a type's body. Exactly one of three kinds, told apart by shape:
 *
 *   - an **instance method** has a `receiver` (the `self` sigil form) and a parameter list;
 *   - an **associated function** has no `receiver` and a parameter list, called `Type.name(…)`;
 *   - a **computed property** has `isProperty` set, no parameter list at all, and an implicit
 *     borrow receiver, read as `value.name` with no parentheses.
 */
case class MethodDecl(
    name: String,
    receiver: Option[RecvMode],
    isProperty: Boolean,
    tparams: List[String],
    params: List[Param],
    retType: Option[TypeRef],
    body: List[Stmt],
)

sealed trait Stmt

/** `var name [: type] [= init]`. A declaration with a type and no initializer starts at that
 * type's zero value, which is how a scratch buffer is written; a type that has no zero value
 * (one containing a `&T`, which always points at a live object) must be initialized.
 */
case class VarDecl(name: String, typ: Option[TypeRef], init: Option[Expr]) extends Stmt
case class ExprStmt(expr: Expr)                                    extends Stmt

/** `['label] while cond body [else elseBody]` as an **expression**. A `break expr` in the body
 * makes `expr` the loop's value; the optional `else` runs on normal completion (the condition
 * became false with no break) and its trailing expression is the loop's value on that path. With
 * no `else`, normal completion yields `unit`, so a value-carrying `break` needs an `else` to give
 * a matching value when the loop finishes on its own. An optional `label` names the loop so a
 * `break`/`continue` in a nested loop can reach it.
 */
case class While(label: Option[String], cond: Expr, body: List[Stmt], elseBody: Option[List[Stmt]]) extends Expr

/** `['label] for name in iter body [else elseBody]` — an **expression** with the same
 * `break`/`else` value rules as `while`. `iter` is a range for now (`a..b`, `a..<b`).
 */
case class For(label: Option[String], name: String, iter: Expr, body: List[Stmt], elseBody: Option[List[Stmt]])
    extends Expr

/** `return` / `return expr` inside a function body. */
case class Return(value: Option[Expr]) extends Stmt

/** `break ['label] [expr]` — leaves an enclosing loop, optionally carrying the loop's value; the
 * label names which loop, defaulting to the nearest. `continue ['label]` skips to a loop's next
 * iteration.
 */
case class Break(label: Option[String], value: Option[Expr]) extends Stmt
case class Continue(label: Option[String]) extends Stmt

/** A function declaration. The body is a statement list whose trailing expression is the
 * implicit return value; an `= expr` short body is stored as a single-element list. A
 * missing `retType` means the function returns `unit`. `tparams` names the type parameters of
 * a generic function, which is instantiated afresh for each set of type arguments.
 */
case class FuncDecl(
    name: String,
    tparams: List[String],
    params: List[Param],
    retType: Option[TypeRef],
    body: List[Stmt],
) extends Stmt

/** `struct Name[T…]` with `name: type` fields and, intermixed, member declarations (methods,
 * properties, associated functions). Positional construction is `Name(a, b, …)`.
 */
case class StructDecl(name: String, tparams: List[String], fields: List[Param], members: List[MethodDecl] = Nil)
    extends Stmt

/** One variant of an `enum`. A variant with `fields` is a data-carrying (tagged-union)
 * variant; a variant with an optional `value` and no fields is a simple integer constant.
 */
case class EnumVariantDecl(name: String, value: Option[Expr], fields: List[Param])

/** `enum Name[T…]` with indented variants. All-dataless variants make a *simple* enum (integer
 * constants, auto-incrementing, with optional explicit `= value`); any data-carrying variant
 * makes a *data* enum (a tagged union whose variants are constructed and destructured).
 */
case class EnumDecl(name: String, tparams: List[String], variants: List[EnumVariantDecl]) extends Stmt

case class Program(body: List[Stmt])
