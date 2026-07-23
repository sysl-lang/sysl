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
case class NamedType(name: String) extends TypeRef

/** One `name: type` binding, shared by function parameters and struct fields. */
case class Param(name: String, typ: TypeRef)

sealed trait Stmt

case class VarDecl(name: String, typ: Option[TypeRef], init: Expr) extends Stmt
case class ExprStmt(expr: Expr)                                    extends Stmt
case class While(cond: Expr, body: List[Stmt])                    extends Stmt

/** `for name in iter body` — `iter` is a range for now (`a..b`, `a..<b`). */
case class For(name: String, iter: Expr, body: List[Stmt]) extends Stmt

/** `return` / `return expr` inside a function body. */
case class Return(value: Option[Expr]) extends Stmt

/** A function declaration. The body is a statement list whose trailing expression is the
 * implicit return value; an `= expr` short body is stored as a single-element list. A
 * missing `retType` means the function returns `unit`.
 */
case class FuncDecl(name: String, params: List[Param], retType: Option[TypeRef], body: List[Stmt]) extends Stmt

/** `struct Name` with `name: type` fields; positional construction is `Name(a, b, …)`. */
case class StructDecl(name: String, fields: List[Param]) extends Stmt

case class Program(body: List[Stmt])
