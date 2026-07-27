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
 *
 * Every node carries the source position the parser found it at (`Positioned`), which is what
 * lets a diagnostic quote the line and point at the column. The position is deliberately not a
 * constructor parameter, so structural equality is unaffected by it.
 */

sealed trait Expr extends Positioned

case class IntLit(value: BigInt, suffix: Option[String]) extends Expr
case class FloatLit(text: String, suffix: Option[String]) extends Expr
case class CharLit(codepoint: Int)                        extends Expr
case class StrLit(value: String)                          extends Expr

/** `c"…"` — a NUL-terminated C string, of type `*u8`. It is the shape a C interface reads a string
 * as, which a sysl `string` is not: that carries a length and no terminator (`04`).
 */
case class CStrLit(value: String) extends Expr
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
 * literal). Against a *struct* value the same positional form destructures every field in
 * declaration order.
 */
case class VariantPattern(name: String, args: List[Pattern]) extends Pattern

/** `Point{x, y}`, `Point{x: 0}` — matches a struct by field name. Each entry is a field and the
 * sub-pattern it must match; the shorthand `{x}` binds field `x` to a variable of the same name.
 * Fields left unlisted are unconstrained, so a named pattern may match on a subset.
 */
case class StructPattern(name: String, fields: List[(String, Pattern)]) extends Pattern

/** `pat, pat, … [if guard] -> body`. Alternatives share one body; the optional guard is a
 * boolean the scrutinee value must additionally satisfy. Each body is a statement list whose
 * trailing expression is the arm's value.
 */
case class MatchArm(patterns: List[Pattern], guard: Option[Expr], body: List[Stmt]) extends Positioned

/** `scrutinee match` with indented arms — an **expression** yielding the taken arm's value
 * (or `unit` in statement position). Arms are tried top to bottom.
 */
case class MatchExpr(scrutinee: Expr, arms: List[MatchArm]) extends Expr

sealed trait TypeRef extends Positioned {

  /** The reference written back out, for a diagnostic that has to name a type before anything has
   * resolved it — the `end` marker closing an `impl`, and the complaints about what an `impl` may
   * be for. Every other message names the *resolved* type, which is canonical; this one can only
   * repeat the spelling it was given.
   */
  def show: String = this match
    case NamedType(n, Nil)                => n
    case NamedType(n, args)               => s"$n[${args.map(_.show).mkString(", ")}]"
    case PtrType(inner)                   => s"*${inner.show}"
    case RefType(inner, sync)             => s"&${if sync then "sync " else ""}${inner.show}"
    case ArrayType(None, elem)            => s"[]${elem.show}"
    case ArrayType(Some(IntLit(n, _)), e) => s"[$n]${e.show}"
    case ArrayType(Some(_), elem)         => s"[…]${elem.show}"
}

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

/** A trait as a **bound** names it: `Show`, or `From[int]` where the trait takes parameters of its
 * own. It is not a `TypeRef` — a trait is not a type, and the one thing that may stand here is a
 * trait applied to as many arguments as it declares.
 *
 * The arguments stay unresolved until something has a substitution to resolve them under, because a
 * bound may mention the parameters of the declaration that wrote it: `f[T: From[U], U]` is held to
 * `From[int]` at a call that fixes `U = int`.
 */
case class BoundRef(name: String, args: List[TypeRef] = Nil) extends Positioned {
  def show: String = if args.isEmpty then name else s"$name[${args.map(_.show).mkString(", ")}]"
}

/** One `name: type` binding, shared by function parameters and struct fields. */
case class Param(name: String, typ: TypeRef) extends Positioned

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
 *
 * An **empty `body` means a signature rather than a definition**, which is a shape only a trait's
 * members have: the grammar gives every other member a body, and a trait member written with one
 * is a default an `impl` inherits.
 *
 * `tparams` and `bounds` are the member's **own** type parameters, which are not the type's: a
 * method of a `Box[T]` that also takes a `[U]` is generic over `U` at each call, while `T` is fixed
 * by the receiver. A property has none — there would be nothing at the read to fix them with.
 */
case class MethodDecl(
    name: String,
    receiver: Option[RecvMode],
    isProperty: Boolean,
    tparams: List[String],
    params: List[Param],
    retType: Option[TypeRef],
    body: List[Stmt],
    bounds: Map[String, List[BoundRef]] = Map.empty,
) extends Positioned {

  /** The mode this member takes its receiver in, or `None` for an associated function — which is the
   * one kind that has no receiver at all.
   *
   * A property's is by value and unwritten, so asking `receiver` about one answers `None` and means
   * something else entirely. Everything that dispatches on a receiver — a lowered `self` parameter,
   * a vtable slot, the object-safety rule — asks here instead, and a property is then the instance
   * member it is rather than a shape each of those has to special-case.
   */
  def recvMode: Option[RecvMode] = receiver.orElse(Option.when(isProperty)(RecvMode.ByValue))
}

sealed trait Stmt extends Positioned

/** How far a top-level declaration is visible (`13 §2`), as the modifier before it was written.
 *
 * Public is the unmarked default, so `Public` is what every declaration carries until one says
 * otherwise and what every declaration the compiler synthesizes carries outright. `private` names
 * the **file**, which is the one level that provably never crosses a file boundary; `private[M]`
 * widens that to a module and everything beneath it.
 *
 * `Scoped` holds the name **as written**, which is a simple name rather than a path: which module
 * it means is settled against the enclosing ones where the declaration sits, so the answer belongs
 * to hoisting rather than to the grammar.
 */
enum Visibility:
  case Public
  case File
  case Scoped(module: String)

/** One name an `import` brings in, and what it is to be called here: `read`, or `read as rd`.
 *
 * The alias is what a reader of the importing file sees, and the name is what the imported module
 * calls it — which is the direction that matters, since the two differ only where a name would
 * otherwise collide or read badly out of its home module.
 */
case class ImportSelector(name: String, alias: Option[String]) extends Positioned {

  /** The name this selector binds here. */
  def bound: String = alias.getOrElse(name)
}

/** `import a.b.c`, `import a.b.{c, d as e}`, `import a.b.*` — a shorter spelling for names that
 * are already reachable by their full path (`13 §3`).
 *
 * The path is kept **as written**, undivided, because which part of `a.b.c` is the module and
 * which the member is a question only the analyzer can answer: `a.b.c` names a member `c` of
 * module `a.b` where that is the module, and the module `a.b.c` itself where *that* is. The
 * longest prefix that names a module wins, the same rule a qualified reference is read by.
 *
 * `selectors` is empty for the bare-path form, and `wildcard` marks the `.*` form; the two are
 * mutually exclusive by the grammar.
 */
case class ImportDecl(path: List[String], selectors: List[ImportSelector] = Nil, wildcard: Boolean = false)
    extends Stmt {

  /** The path as a programmer wrote it, for a diagnostic. */
  def show: String = path.mkString(".")
}

/** `var name [: type] [= init]`. A declaration with a type and no initializer starts at that
 * type's zero value, which is how a scratch buffer is written; a type that has no zero value
 * (one containing a `&T`, which always points at a live object) must be initialized.
 */
case class VarDecl(name: String, typ: Option[TypeRef], init: Option[Expr]) extends Stmt

/** `const name: type = value` — a **module member**, and the one kind of module-level binding there
 * is (`13 §7`). It is what a top-level `var` is not: hoisted, order-free, and visible beyond its
 * file under the ordinary rules, where a `var` at the top of a file is a local of the entry point.
 *
 * The type is written rather than inferred because `13 §2`'s "anything visible outside its file
 * states its types" is what keeps interface extraction parse-only, and this is the first
 * declaration that rule has ever had to bind. Writing it is also what fixes the initializer's type,
 * so `const capacity: usize = 512` needs no suffix on the literal.
 *
 * It has no address and no storage: every use is folded to the value, which is why it needs no
 * initialization order and why an array bound may name one.
 */
case class ConstDecl(name: String, typ: TypeRef, value: Expr, vis: Visibility = Visibility.Public) extends Stmt

case class ExprStmt(expr: Expr)                                    extends Stmt

/** `['label] while cond body [else elseBody]` as an **expression**. A `break expr` in the body
 * makes `expr` the loop's value; the optional `else` runs on normal completion (the condition
 * became false with no break) and its trailing expression is the loop's value on that path. With
 * no `else`, normal completion yields `unit`, so a value-carrying `break` needs an `else` to give
 * a matching value when the loop finishes on its own. An optional `label` names the loop so a
 * `break`/`continue` in a nested loop can reach it.
 */
case class While(label: Option[String], cond: Expr, body: List[Stmt], elseBody: Option[List[Stmt]]) extends Expr

/** `['label] loop body` — a loop with no condition, which runs until something leaves it.
 *
 * It is `while true` with the part that was never a question taken out, and it carries one thing
 * that spelling cannot: a loop nothing breaks out of does not finish, so its type is `never` and
 * the code after it is unreachable. There is no `else`, because `else` runs on normal completion
 * and this loop has none — the value it yields is the value its `break`s carry, and `unit` where
 * they carry none.
 */
case class Loop(label: Option[String], body: List[Stmt]) extends Expr

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
 *
 * `bounds` maps a type parameter to the traits it is bounded by (`f[T: Show, U: Ord + Hash]`),
 * keyed by name so it carries no positional dependence on `tparams`; a parameter with no bound
 * is absent from the map. A bound is what a caller must satisfy — the concrete type it supplies
 * for that parameter must implement every trait named — and is checked at each call site.
 *
 * `variadic` is the trailing `...` of `sum(n: int, ...)`: the same ellipsis an `extern` takes, under
 * the same rules for what the tail may hold. The body reads it through `va_start`/`va_arg`/`va_end`
 * (`12-functions-and-closures.md` §9).
 */
case class FuncDecl(
    name: String,
    tparams: List[String],
    params: List[Param],
    retType: Option[TypeRef],
    body: List[Stmt],
    bounds: Map[String, List[BoundRef]] = Map.empty,
    variadic: Boolean = false,
    vis: Visibility = Visibility.Public,
) extends Stmt

/** `extern name(params) -> ret` — a function this program does not define but may call, resolved
 * by the linker under the name it is declared with.
 *
 * It is a declaration and nothing else: no body, no type parameters, and no way to see what it
 * does. That is what makes it the seam a language reaches the outside world through — libc's
 * `exit`, a driver's MMIO helper — and why the escape analysis has to assume the worst of it
 * (`05-escape-analysis.md`): every argument may be kept, and the result may view any of them.
 *
 * `link` is the optional leading string of `extern "snprintf" fmt(…)`: the symbol the linker
 * resolves, when that differs from the name the program calls it by. Absent, the two are the same.
 * The distinction exists because a symbol's spelling belongs to whoever exported it — it may be
 * taken already, or shaped nothing like sysl — and because a declaration in the prelude would
 * otherwise spend a name out of every program's namespace.
 *
 * `variadic` is the trailing `...` of `extern printf(fmt: *u8, ...) -> int`: the C ellipsis, which a
 * sysl function may carry too (`12-functions-and-closures.md` §1, §9) under the same rules for what
 * the tail may hold.
 */
case class ExternDecl(name: String, params: List[Param], retType: Option[TypeRef],
                      variadic: Boolean = false, link: Option[String] = None,
                      vis: Visibility = Visibility.Public) extends Stmt:
  /** The symbol the linker resolves this to. */
  def symbol: String = link.getOrElse(name)

/** `struct Name[T…]` with `name: type` fields and, intermixed, member declarations (methods,
 * properties, associated functions). Positional construction is `Name(a, b, …)`.
 *
 * `bounds` is what the type asks of its own parameters — `struct SortedList[T: Ord]` — keyed by
 * parameter name, with an unbounded one simply absent. Every application of the type is held to
 * them, and its members may assume them: they are what makes a member checkable at its definition
 * rather than once per instantiation (`10 §5`).
 */
case class StructDecl(
    name: String,
    tparams: List[String],
    fields: List[Param],
    members: List[MethodDecl] = Nil,
    bounds: Map[String, List[BoundRef]] = Map.empty,
    vis: Visibility = Visibility.Public,
) extends Stmt

/** One variant of an `enum`. A variant with `fields` is a data-carrying (tagged-union)
 * variant; a variant with an optional `value` and no fields is a simple integer constant.
 */
case class EnumVariantDecl(name: String, value: Option[Expr], fields: List[Param]) extends Positioned

/** `enum Name[T…]` with indented variants and, intermixed, member declarations (methods,
 * properties, associated functions) exactly as a struct body holds them. All-dataless variants make
 * a *simple* enum (integer constants, auto-incrementing, with optional explicit `= value`); any
 * data-carrying variant makes a *data* enum (a tagged union whose variants are constructed and
 * destructured).
 *
 * `underlying` is the `: iN`/`uN` annotation that pins a non-generic simple enum's storage type;
 * unspecified it is `int`. It is meaningless on a generic or data enum, which the analyzer rejects.
 *
 * `bounds` is what the type asks of its own parameters, exactly as a struct's are.
 */
case class EnumDecl(name: String, tparams: List[String], underlying: Option[TypeRef],
                    variants: List[EnumVariantDecl], members: List[MethodDecl] = Nil,
                    bounds: Map[String, List[BoundRef]] = Map.empty,
                    vis: Visibility = Visibility.Public) extends Stmt

/** `trait Name` with indented method declarations — a method with a receiver and a parameter list,
 * written either as a bare **signature** (`show(self) -> string`) or with a body, which makes it a
 * **default**. A trait is nominal: a type participates only through an explicit `impl`, never by
 * coincidence of method names.
 *
 * A signature is a `MethodDecl` with an empty `body`, and that is the whole of the difference: a
 * method with one is a definition every `impl` inherits unless it writes its own.
 *
 * `tparams` makes the trait **generic**: `trait From[T]` is a different promise for every `T`, so a
 * type may implement it once per argument list and a bound naming it must say which one it means.
 * `bounds` is what the trait asks of those parameters, exactly as a struct's are.
 */
case class TraitDecl(
    name: String,
    tparams: List[String],
    methods: List[MethodDecl],
    bounds: Map[String, List[BoundRef]] = Map.empty,
    vis: Visibility = Visibility.Public,
) extends Stmt

/** `impl Trait for Type` with indented method **bodies**. Every method the trait declares without a
 * default must be present with a matching signature, and no method the trait does not declare; the
 * methods then become inherent members of `forType`, callable as `value.method(…)` exactly as a
 * method written in the type's own body. A default the block leaves out becomes a member of
 * `forType` just the same, from the body the trait supplied.
 *
 * `forType` is a full type reference rather than a name, because a trait may be implemented for a
 * type that has no name to be written under — `impl Show for []int` says how a slice of ints
 * renders, and `[]int` is a type the same way `Point` is.
 *
 * `tparams` are the block's own type parameters, written between `impl` and the trait —
 * `impl[T] Show for Box[T]` implements the trait for **every** `Box`, and its methods are
 * monomorphized per instantiation the way a generic type's own members are. `bounds` are what the
 * block asks of them: `impl[T: Show] Show for Box[T]` is **conditional conformance**, so a
 * `Box[int]` implements `Show` exactly when `int` does. Both are empty for an ordinary `impl`,
 * whose subject is one concrete type.
 *
 * `traitArgs` are the arguments the **trait** is applied to, for a trait that takes any:
 * `impl From[int] for Celsius`. They are what makes one type able to implement a trait more than
 * once, so they are part of what an implementation is filed under.
 */
case class ImplDecl(
    traitName: String,
    forType: TypeRef,
    methods: List[MethodDecl],
    tparams: List[String] = Nil,
    bounds: Map[String, List[BoundRef]] = Map.empty,
    traitArgs: List[TypeRef] = Nil,
) extends Stmt

/** The `module a.b.c` header a file carries, naming the module the file contributes to. The name is
 * a directory path with the separators read as dots (`13 §1`), so it is kept as its segments rather
 * than as one string — the segments are what a visibility scope and a platform layout are written
 * against, and the dotted spelling is recovered by `show`.
 */
case class ModuleName(parts: List[String]) extends Positioned {
  def show: String = parts.mkString(".")
}

/** One file's parse: the module it contributes to, its statements, and the source it came from.
 *
 * `module` is absent for a file that declares no header, which puts it in the **anonymous root
 * module** — the module whose name is the empty path. A single-file program is exactly that case,
 * which is why one needs no header to compile.
 *
 * `source` is carried because a file is the unit several module rules are stated over, and a
 * diagnostic about one has to name it even where the file holds nothing to point at.
 */
case class Program(body: List[Stmt], module: Option[ModuleName], source: Source)
