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

case class TIntLit(value: BigInt, ty: Type)          extends TExpr
case class TFloatLit(bits: String)                   extends TExpr { def ty: Type = Type.Real }
case class TStrLit(value: String)                    extends TExpr { def ty: Type = Type.Str }
case class TBoolLit(value: Boolean)                  extends TExpr { def ty: Type = Type.Bool }
case class TUnitLit()                                extends TExpr { def ty: Type = Type.Unit }

/** Reads a local variable (or parameter) by its unique name. */
case class TLoad(name: String, ty: Type) extends TExpr

/** `name = value` — stores and yields the assigned value. */
case class TStore(name: String, value: TExpr, ty: Type) extends TExpr

/** A compound assignment `name op= value`, yielding the updated value. */
case class TUpdate(name: String, op: String, value: TExpr, ty: Type) extends TExpr

/** `++`/`--`, prefix (new value) or postfix (old value). */
case class TIncDec(name: String, op: String, pre: Boolean) extends TExpr { def ty: Type = Type.Int }

case class TBinary(op: String, left: TExpr, right: TExpr, ty: Type) extends TExpr
case class TUnary(op: String, operand: TExpr, ty: Type)             extends TExpr

/** `&&` / `||` — short-circuit, always boolean. */
case class TLogical(op: String, left: TExpr, right: TExpr) extends TExpr { def ty: Type = Type.Bool }

/** A comparison chain `a < b < c`, ANDing the pairwise results. */
case class TCompare(operands: List[TExpr], ops: List[String]) extends TExpr { def ty: Type = Type.Bool }

/** The built-in `print`. */
case class TPrint(args: List[TExpr]) extends TExpr { def ty: Type = Type.Unit }

/** A call to a user function. */
case class TCall(name: String, args: List[TExpr], ty: Type) extends TExpr

/** Positional construction of a value struct. */
case class TStructNew(struct: Type.Struct, args: List[TExpr]) extends TExpr { def ty: Type = struct }

/** Read field `index` of a struct value. */
case class TField(receiver: TExpr, index: Int, ty: Type) extends TExpr

/** `receiver.field = value` on a local struct variable, yielding the assigned value. */
case class TSetField(name: String, struct: Type.Struct, index: Int, value: TExpr, ty: Type) extends TExpr

/** `if cond then … else …` as a value (or unit when there is no else). */
case class TIf(cond: TExpr, thenBlock: TBlock, elseBlock: Option[TBlock], ty: Type) extends TExpr

/** `match scrutinee` — arms are tried in order; `ty` is the common arm type (or unit). */
case class TMatch(scrutinee: TExpr, arms: List[TArm], ty: Type) extends TExpr

/** One arm: the scrutinee matches if any test holds and the guard (if any) is true. */
case class TArm(tests: List[TArmTest], guard: Option[TExpr], body: TBlock)

sealed trait TArmTest
case class TEqTest(value: TExpr)                                extends TArmTest
case class TRangeTest(lo: TExpr, hi: TExpr, inclusive: Boolean) extends TArmTest
case object TWildTest                                          extends TArmTest

/** A block: a sequence of statements optionally ending in a value expression. When `result`
 * is `None` the block's type is `unit`.
 */
case class TBlock(stmts: List[TStmt], result: Option[TExpr], ty: Type)

sealed trait TStmt

case class TVarDecl(name: String, ty: Type, init: TExpr) extends TStmt
case class TExprStmt(expr: TExpr)                         extends TStmt
case class TWhile(cond: TExpr, body: List[TStmt])         extends TStmt
case class TFor(name: String, lo: TExpr, hi: TExpr, inclusive: Boolean, body: List[TStmt]) extends TStmt
case class TReturn(value: Option[TExpr])                  extends TStmt

/** A user function. Parameters carry their unique names (the codegen allocates a slot for
 * each so the body can read and mutate them uniformly).
 */
case class TFunc(name: String, params: List[(String, Type)], retTy: Type, body: TBlock)

/** A whole program: hoisted struct and function declarations, plus the top-level statements
 * that make up `main`.
 */
case class TProgram(structs: List[Type.Struct], funcs: List[TFunc], main: List[TStmt])
