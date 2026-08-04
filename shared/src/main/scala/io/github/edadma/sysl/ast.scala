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

/** Every expression node is a **case class**, and saying so in the type is what lets a walk that has
 * nothing to say about any particular node read its children off the product rather than matching
 * arm by arm (`Aliasing.exprKids`). Such a walk is total by construction, which is the property that
 * matters: a node added below without a case of its own is descended into rather than silently
 * treated as a leaf.
 */
sealed trait Expr extends Positioned, Product

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

/** `name = value` at a call — the argument stands at the parameter it names rather than at the one
 * its position would have given it (`12 §2a`).
 *
 * It is an `Expr` so that it travels in `Call.args` beside the positional arguments it is mixed
 * with, and every call form binds its arguments before looking at any of them. Reaching
 * `analyzeExpr` means it was written somewhere no declaration is being called, which is what the
 * arm there reports.
 */
case class NamedArg(name: String, value: Expr) extends Expr

/** A parameter's default, spliced in at a call that left the argument out (`12 §2a`).
 *
 * Synthesized by argument binding and never parsed, so it carries the position of the default as
 * written rather than one of its own.
 *
 * `owner` is the key of the declaration the default was written in, and is the scope it is analyzed
 * under: a default names what its own file names, wherever it is called from. It is absent for a
 * **nested** function, whose declaration has no key of its own and whose every call is inside the
 * body it was written in — so the scope already in force is the one it was written in.
 */
case class DefaultArg(owner: Option[String], value: Expr) extends Expr
case class Index(receiver: Expr, index: Expr)   extends Expr
case class Field(receiver: Expr, name: String)  extends Expr

/** `T::Attr` — a type attribute (`16 §5`, `09 §2`): metadata a type exposes under a name, with `::`
 * rather than `.` because it belongs to the type itself, not to a value of it. `Age::First`,
 * `Day::Succ(d)`. The receiver is a type name; a bare `Attr` reads a value, and `Attr(args)` is a
 * `Call` over this node, exactly as an enum's associated function is a `Call` over a `Field`.
 */
case class TypeAttr(receiver: Expr, attr: String) extends Expr

/** `sizeof(T)` / `alignof(T)` — how much storage a type occupies and what it must be aligned to
 * (`03 § Reinterpreting storage`).
 *
 * The operand is a **type**, which is why this is a node of its own rather than a `Call` the analyzer
 * recognizes by name: an argument list holds values, and the whole type grammar is written here —
 * `sizeof(*Node)`, `sizeof([16]u8)`, `sizeof((int, real))`. `op` is the word that was written, so one
 * node carries both and the two never drift apart.
 */
case class LayoutOf(op: String, typ: TypeRef) extends Expr

/** The postfix `?` error-propagation operator. */
case class TryExpr(expr: Expr) extends Expr

case class Tuple(elements: List[Expr]) extends Expr

/** One of a closure literal's parameters. Its type is written only where nothing else can supply
 * one (`12 §5`), so the annotation is optional here in a way a declared function's never is.
 */
case class LambdaParam(name: String, typ: Option[TypeRef]) extends Positioned

/** `x -> x + 1` — a closure literal (`12 §5`). The body is a statement list for the same reason a
 * function's is: an indented block's trailing expression is its value, and the `= expr` short form
 * is that list with one statement in it.
 */
case class Lambda(params: List[LambdaParam], body: List[Stmt]) extends Expr

/** `[a, b, c]` — an array literal, whose length is how many elements were written. An empty
 * one has no element type of its own and takes it from the context.
 */
case class ArrayLit(elements: List[Expr]) extends Expr
case class ArrayFill(value: Expr, count: Expr) extends Expr

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

/** `(a, b)` — matches a tuple, one sub-pattern per part (`00 §13`). It is the positional struct
 * pattern with the name left off, which is all a tuple has to leave off.
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

/** `scrutinee match` with indented arms — an **expression** yielding the taken arm's value
 * (or `unit` in statement position). Arms are tried top to bottom.
 */
case class MatchExpr(scrutinee: Expr, arms: List[MatchArm]) extends Expr

/** `subject is Pat` / `subject is not Pat` — a pattern tested where a condition is wanted
 * (`09 §12`). It yields a `bool` and, in the un-negated form, binds whatever the pattern names for
 * the rest of the condition and the branch the condition guards.
 *
 * `patterns` holds the `|`-alternatives an arm's left side may hold, and under the same rule: they
 * share one answer, so none of them may bind.
 *
 * Its position is checked rather than its type: it is a term of an `if`'s or a `while`'s condition
 * and nowhere else, so that the reach of a binding is one sentence long. Everywhere else the
 * analyzer refuses it, which is why this stays an ordinary `Expr` — the grammar has one place to put
 * it and one rule to give it back.
 */
case class IsPattern(subject: Expr, patterns: List[Pattern], negated: Boolean) extends Expr

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
    case WeakType(inner)                  => s"weak ${inner.show}"
    case ArrayType(None, elem, ro)           => s"[]${if ro then "const " else ""}${elem.show}"
    case ArrayType(Some(IntLit(n, _)), e, _) => s"[$n]${e.show}"
    case ArrayType(Some(_), elem, _)         => s"[…]${elem.show}"
    case VolatileType(inner)              => s"volatile ${inner.show}"
    case TupleType(parts, false)          => s"(${parts.map(_.show).mkString(", ")})"
    case TupleType(parts, true)           => parts.map(_.show).mkString(", ")
    case FnType(List(one), ret, true)     => s"${one.show} -> ${ret.show}"
    case FnType(params, ret, true)        => s"(${params.map(_.show).mkString(", ")}) -> ${ret.show}"
    case FnType(params, ret, false)       => s"Fn(${params.map(_.show).mkString(", ")}) -> ${ret.show}"
    case CFnType(params, ret)             => s"*extern(${params.map(_.show).mkString(", ")}) -> ${ret.show}"
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

/** `weak T` — a reference that does not keep its referent alive (`03`). */
case class WeakType(inner: TypeRef) extends TypeRef

/** `[N]T` — a fixed array — or `[]T`, a slice, when no length is written, and `[]const T` when the
 * slice may not be written through.
 *
 * `const` sits after the brackets rather than before them for the reason `sync` sits after the `&`:
 * it is a property of the *view*, not of the element type, and putting it where the element type
 * goes would say a program had a type called "const T".
 */
case class ArrayType(length: Option[Expr], elem: TypeRef, readOnly: Boolean = false) extends TypeRef

/** `volatile T` — storage a device may change and a read of which may itself do something
 * (`03 § Device memory`).
 *
 * It goes *before* the type rather than after a sigil, the way C's qualifier does and the way
 * `[]const T`'s does not, because it qualifies the type it is written on rather than the mode
 * reaching it: `*volatile u32` points at a volatile register, while a `volatile *u32` would be a
 * pointer that itself sits in device memory. Both are writable and they are different things, which
 * is the whole reason the position carries meaning.
 */
case class VolatileType(inner: TypeRef) extends TypeRef

/** `a, b` where a function's own result list is what is being produced (`12 §5b`) — the callee's
 * side of the form. It is never a value: the analyzer accepts it only where the enclosing
 * function's declared result is a list, and builds the aggregate the caller takes apart.
 */
case class ResultList(values: List[Expr]) extends Expr

/** `(A, B)` — a tuple of two or more parts (`00 §13`). One part is never written here: `(T)` is a
 * type in parentheses, and a product of one thing is the thing.
 */
case class TupleType(parts: List[TypeRef], results: Boolean = false) extends TypeRef

/** The type of a callable (`12 §6`) — the parameters it is called with and the result it yields.
 *
 * One node covers both spellings because they name the same thing. `Fn(int) -> int` writes the
 * trait out; `int -> int` is the sugar a *parameter* may use, and `bare` records which was written
 * so the analyzer can hold the sugar to the one position it is allowed in. Neither is a type on its
 * own — a bare arrow becomes a bounded type parameter and a written `Fn` becomes a trait — so what
 * reaches here is always resolved in the light of where it stands.
 */
case class FnType(params: List[TypeRef], ret: TypeRef, bare: Boolean) extends TypeRef {

  /** The trait this names, written the way an ordinary applied trait is: the parameters and then
   * the result, under the name that carries the arity (`Fn2[A, B, R]`).
   *
   * Every walk over written types goes through this rather than growing a case of its own, which is
   * what keeps a callable's type from needing a second answer to questions — does it name this type
   * parameter, does it mention `Self` — that the applied form already answers.
   */
  def asTrait: NamedType = NamedType(Type.Fn.base(params.length), params :+ ret).setPos(pos)
}

/** `*extern(A, B) -> R` — the address of a function compiled to the machine's C convention, which is
 * the one word a C library means by a function pointer.
 *
 * It is written as one spelling rather than a mode applied to a callable's type, because it is not a
 * pointer to any sysl value: there is nothing at the other end that a program could read, copy, or
 * count, and `*T` promises all three (`03`). `*Fn(A) -> R` is already the *other* thing — an unowned
 * trait object over a callable, two words, a table beside the value — so a shared spelling would put
 * a fat pointer where C reads one word.
 *
 * The `extern` in it is the same word the declaration form uses and means the same thing: what is at
 * the other end obeys a published convention rather than this compiler's.
 */
case class CFnType(params: List[TypeRef], ret: TypeRef) extends TypeRef

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

/** One `name: type` binding, shared by function parameters and struct fields.
 *
 * `vis` is a **field's** — how far the field may be read from (`08 § Visibility`). A function
 * parameter is named by nobody outside the signature it is written in, so it carries the unmarked
 * default and the grammar gives it no place to write anything else.
 *
 * `default` is a **parameter's** — the value a call that leaves the argument out stands there
 * instead (`12 §2a`). It is the mirror image of `vis`: a field declares none, and the grammar gives
 * a field no place to write one, because what a field falls back to is a different question (`07`).
 */
case class Param(
    name: String,
    typ: TypeRef,
    vis: Visibility = Visibility.Public,
    default: Option[Expr] = None,
) extends Positioned

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
 *
 * `vis` is how far the member may be named from (`08 § Visibility`). The unmarked default means
 * *its type's* reach rather than public, so `Public` here is "said nothing" and not "said public" —
 * which is why a trait's member and an `impl`'s, neither of which may say anything, carry it too.
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
    tdefaults: Map[String, TypeRef] = Map.empty,
    vis: Visibility = Visibility.Public,
    variadic: Boolean = false,
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

/** `import a.b.c`, `import a.b.c as d`, `import a.b.{c, d as e}`, `import a.b.*` — a shorter
 * spelling for names that are already reachable by their full path (`13 §3`).
 *
 * The path is kept **as written**, undivided, because which part of `a.b.c` is the module and
 * which the member is a question only the analyzer can answer: `a.b.c` names a member `c` of
 * module `a.b` where that is the module, and the module `a.b.c` itself where *that* is. The
 * longest prefix that names a module wins, the same rule a qualified reference is read by.
 *
 * `selectors` is empty for the bare-path form, and `wildcard` marks the `.*` form; the two are
 * mutually exclusive by the grammar.
 *
 * `alias` is the **unbraced rename**, and it belongs to the bare-path form alone — `a.b.{c as d}`
 * carries its rename on the selector, where a list needs one per name. It renames whatever the path
 * turned out to name, module or member, because the two are one piece of syntax here and a reader
 * asking for a shorter word does not first have to know which they wrote.
 */
case class ImportDecl(
    path: List[String],
    selectors: List[ImportSelector] = Nil,
    wildcard: Boolean = false,
    alias: Option[String] = None,
) extends Stmt {

  /** The path as a programmer wrote it, for a diagnostic. */
  def show: String = path.mkString(".")

  /** The name the bare-path form binds here — the alias where one was written, the last segment
   * otherwise, which is the same rule `ImportSelector.bound` follows.
   */
  def bound: String = alias.getOrElse(path.last)
}

/** `var name [: type] [= init]`. A declaration with a type and no initializer starts at that
 * type's zero value, which is how a scratch buffer is written; a type that has no zero value
 * (one containing a `&T`, which always points at a live object) must be initialized.
 */
case class VarDecl(name: String, typ: Option[TypeRef], init: Option[Expr]) extends Stmt

/** `const name: type = value` — a **module member** (`13 §7`). It is what a top-level `var` is not:
 * hoisted, order-free, and visible beyond its file under the ordinary rules, where a `var` at the
 * top of a file is a local of the entry point.
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

/** `val name [: type] = value` — a binding written once and never assigned to.
 *
 * One keyword, read at two levels, because it is one idea at both. Written at the top of a file it
 * is a **module member**: storage the program owns for its whole run, initialized before anything
 * runs, and read-only. Written inside a block it is a **local** — the immutable counterpart of
 * `var`, in the same frame with the same lifetime, differing only in that it may not be assigned
 * to again.
 *
 * What separates it from a `const` is an **address**. A constant is folded into every use and has
 * no storage at all, which is what lets an array bound name one; a `val` is a thing that sits
 * somewhere, so it may be indexed, iterated, and sliced — the slice being a `[]const T`, which is
 * how the read-only-ness survives being handed on. The rule for a reader is short: if it has to be
 * indexed, pointed at, or is bigger than a scalar, it is a `val`.
 *
 * The type is optional in the syntax and required by the analyzer at module level, where `13 §2`'s
 * "anything visible outside its file states its types" applies. A local states nothing to anyone,
 * so it infers exactly as a `var` does.
 */
case class ValDecl(name: String, typ: Option[TypeRef], value: Expr, vis: Visibility = Visibility.Public)
    extends Stmt

/** `ref name = place` — a name for a place rather than for a value (`03 § ref`).
 *
 * The place is evaluated once, where this is written, and the name means the storage that was found
 * afterwards. So it neither copies what it names nor re-walks the path at each use, which are the
 * only two things a `var` and a re-written path respectively could offer.
 *
 * It carries no type annotation and no visibility, and both absences are the same fact: this is a
 * **local declaration and never a type**, so there is nothing for a reader elsewhere to be told. A
 * ref cannot be a field, a parameter, a return type, or a type argument, which is what keeps the
 * analyzer holding the place expression for as long as the name exists — and that, rather than the
 * address it lowers to, is what lets a write through the name re-check the invariants of every
 * struct the place lies inside.
 */
case class RefDecl(name: String, place: Expr) extends Stmt

/** `a, b = b, a` — several places written from several values in one step (`00 §2`).
 *
 * It is a statement rather than an expression, and that is what keeps it small: a single assignment
 * yields the value it stored, so a multiple one would have to yield several, and there is nothing an
 * expression could be that carries several values without becoming a tuple by another name.
 *
 * `op` is the operator that was written. Only `=` means anything here — a compound form is read so
 * that the rule against it can be explained rather than left to a parse failure.
 */
case class MultiAssign(op: String, targets: List[Expr], values: List[Expr]) extends Stmt

/** `val a, b = …` / `var a, b = …` — one binding that names several things at once (`00 §2`).
 *
 * The names are declared only after every value has been produced, so a value on the right may
 * still name whatever the surrounding scope calls one of them.
 */
case class MultiDecl(names: List[String], mutable: Boolean, values: List[Expr]) extends Stmt

/** `val (a, b) = …` / `var (a, b) = …` — one binding that takes a tuple apart by **pattern**
 * (`00 §13`).
 *
 * This is the comma form's sibling and not a replacement for it: `val a, b = f()` takes a result
 * list or a tuple apart at one level, while a pattern says the *shape* and so reaches inside a
 * nested one — `val ((a, b), c) = p` names three things across two levels, which no list of names
 * can say.
 *
 * **Only an irrefutable pattern may stand here** — a tuple pattern, a **struct** pattern, a name, a
 * wildcard, and those nested inside one another. A binding has no arm to fall through to, so a
 * pattern that can fail to match would leave its names standing for nothing; `09 §5`'s refutable
 * forms are refused with that as the reason.
 *
 * **A struct pattern qualifies because a struct has exactly one shape**, which is the same property
 * that makes a tuple pattern irrefutable — `09 §` calls a tuple pattern the positional form of this
 * one. A *variant* pattern is the one that does not qualify: an enum has several shapes and naming
 * one of them is a test.
 */
case class PatternDecl(pattern: Pattern, mutable: Boolean, value: Expr) extends Stmt

case class ExprStmt(expr: Expr)                                    extends Stmt

/** `['label] while cond body [else elseBody]` as an **expression**. A `break expr` in the body
 * makes `expr` the loop's value; the optional `else` runs on normal completion (the condition
 * became false with no break) and its trailing expression is the loop's value on that path. With
 * no `else`, normal completion yields `unit`, so a value-carrying `break` needs an `else` to give
 * a matching value when the loop finishes on its own. An optional `label` names the loop so a
 * `break`/`continue` in a nested loop can reach it.
 */
case class While(label: Option[String], cond: Expr, body: List[Stmt], elseBody: Option[List[Stmt]]) extends Expr

/** `['label] do body while cond [else elseBody]` — the post-test loop, whose body runs once before
 * anything is asked.
 *
 * The value rules are `while`'s exactly: a `break expr` carries the loop's value, and the `else`
 * runs on normal completion, which here means the test at the foot finally failed. What it carries
 * that `while` cannot is the same thing the three-clause `for` carries — a `continue` runs the
 * **test** rather than restarting the body blind. The shape a program reaches for otherwise is
 * `loop` with `if !cond then break` at the foot, and that rewrite is not this loop: a `continue`
 * added to it jumps over the test and never leaves.
 *
 * `cond` is an ordinary boolean rather than a condition's term list, for the reason the three-clause
 * `for`'s is: a test at the foot of the body has no branch after it for an `is` binding to be read
 * from.
 */
case class DoWhile(label: Option[String], body: List[Stmt], cond: Expr, elseBody: Option[List[Stmt]]) extends Expr

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

/** `['label] for init; cond; step` — the three-clause loop, written without parentheses as Go
 * writes it, since every other header in the language is parenthesis-free.
 *
 * Each clause may be empty. It is an **expression** with the same `break`/`else` rules as `while`,
 * whose `else` runs when the condition turns false. What it carries that `while` cannot is the
 * **step**: `continue` runs it before testing again, which is the whole reason a counted loop
 * written as a `while` is a bug waiting for its first `continue`. A binding introduced by the init
 * is scoped to the loop — the condition, the step, the body, and the `else`.
 */
case class CFor(
    label: Option[String],
    init: Option[Stmt],
    cond: Option[Expr],
    step: Option[Stmt],
    body: List[Stmt],
    elseBody: Option[List[Stmt]],
) extends Expr

/** `return` / `return expr` inside a function body. */
case class Return(value: Option[Expr]) extends Stmt

/** `break ['label] [expr]` — leaves an enclosing loop, optionally carrying the loop's value; the
 * label names which loop, defaulting to the nearest. `continue ['label]` skips to a loop's next
 * iteration.
 */
case class Break(label: Option[String], value: Option[Expr]) extends Stmt
case class Continue(label: Option[String]) extends Stmt

/** `defer stmt` — a statement to run on the way out of the block containing it, whichever edge
 * control leaves by (`03 § defer`). It is registered when control reaches it and not before, so
 * one in a branch never taken schedules nothing.
 */
case class Defer(stmt: Stmt) extends Stmt

/** A design-by-contract clause at the top of a function body. `require` is a precondition,
 * checked once on entry; `ensure` is a postcondition, checked before every return. The optional
 * `msg` accompanies the runtime trap. Inside an `ensure` condition the identifier `result`
 * denotes the value being returned.
 */
case class Require(cond: Expr, msg: Option[String]) extends Stmt
case class Ensure(cond: Expr, msg: Option[String]) extends Stmt

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
/** The calling convention a definition is entered under, where that is not the ordinary one
 * (`15 §10`).
 *
 * **A name and an optional argument, rather than an LLVM convention spelled through.** What
 * `interrupt` *is* differs by processor — a calling convention on x86-64, a function attribute on
 * RISC-V, and nothing at all on AArch64 — so the source names the concept and the back end decides
 * what that becomes. A pass-through would put an LLVM spelling in a source file and be wrong for
 * every other machine the file is built for.
 *
 * `arg` is the parenthesised word: RISC-V's privilege mode, `interrupt(supervisor)`. It is optional
 * because most conventions have nothing to say and because the common mode has a default.
 *
 * One convention is known today and the shape carries a name anyway, so that the second one is an
 * analyzer change rather than a change to every tree that holds a function.
 */
case class CallConv(name: String, arg: Option[String] = None) extends Positioned

case class FuncDecl(
    name: String,
    tparams: List[String],
    params: List[Param],
    retType: Option[TypeRef],
    body: List[Stmt],
    bounds: Map[String, List[BoundRef]] = Map.empty,
    variadic: Boolean = false,
    vis: Visibility = Visibility.Public,
    tdefaults: Map[String, TypeRef] = Map.empty,
    test: Option[TestAttr] = None,
    conv: Option[CallConv] = None,
    /** `@tailrec` — see `TFunc.tailrec`. */
    tailrec: Boolean = false,
) extends Stmt

/** What `@test` says about the function it is written above (`testing.md`).
 *
 * A test is a function the program does not call: `sysl test` calls it, and whether it *returned* is
 * the whole of the result. So the attribute carries only what the runner cannot work out for itself
 * — what to call the test in its report, and whether returning is the outcome it was after.
 *
 * `display` is the name a report shows, defaulting to the function's own. It exists because a
 * function name is a name and a test's subject is a sentence: `@test("an empty slice has no first
 * element")` says something `first_of_empty` only gestures at.
 *
 * `shouldTrap` inverts the verdict: the test passes exactly when the process does *not* come back.
 * That is how a runtime check is tested at all — a bounds violation, a broken `require`, a `within`
 * that does not hold each end in `llvm.trap`, which no program survives to report anything about.
 * `expected` narrows it to a run whose output holds a given substring, which is what tells a trap
 * from the *right* trap where the failure prints something first.
 */
case class TestAttr(display: Option[String], shouldTrap: Boolean, expected: Option[String]) extends Positioned

/** One attribute written above a declaration, before it has been folded into the `FuncDecl` it
 * qualifies. It exists for the fold and for the refusal of a repeat — `word` is the spelling that
 * refusal names, and is what makes two attributes the same one.
 */
enum Attr(val word: String) {
  case Test(attr: TestAttr) extends Attr("test")
  case TailRec              extends Attr("tailrec")
}

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
 * taken already, or shaped nothing like sysl — and because a library declaration reaching a C name
 * directly would otherwise spend that word out of every program's namespace.
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

/** `extern name: type` — storage this program does not lay down but may read and write, resolved by
 * the linker exactly as an `extern` function is.
 *
 * It is the same seam as the declaration above, pointed at the other kind of thing a C library
 * exports. `stdout` and `environ` are variables rather than functions, and a language whose only way
 * out is a call cannot name either: `fputs(s, stdout)` needs the *variable*, and there is no getter
 * to reach it through.
 *
 * The type is written and never inferred — there is no initializer to infer it from, and what the
 * other side laid down is not something this compiler can see. Writing the wrong one is the same
 * kind of promise a wrong parameter list to an `extern` function is (`12 §1`).
 *
 * `link` is the leading string of `extern "environ" env: **u8`, and means what it means for a
 * function: the symbol the linker resolves, when that differs from what the program calls it by.
 */
case class ExternVarDecl(name: String, typ: TypeRef, link: Option[String] = None,
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
 *
 * `opaque` withholds the **layout** from every module but the one declaring it (`15 §9`): outside,
 * the type may be named only as the pointee of a `*`. It is not a `vis`, and the two are orthogonal —
 * `vis` decides who may say the *name*, `opaque` decides who may know the *shape*, and a type whose
 * name nobody could say would have nothing to be opaque to.
 */
case class StructDecl(
    name: String,
    tparams: List[String],
    fields: List[Param],
    members: List[MethodDecl] = Nil,
    bounds: Map[String, List[BoundRef]] = Map.empty,
    invariants: List[Expr] = Nil,
    vis: Visibility = Visibility.Public,
    tdefaults: Map[String, TypeRef] = Map.empty,
    opaque: Boolean = false,
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
                    vis: Visibility = Visibility.Public,
                    tdefaults: Map[String, TypeRef] = Map.empty) extends Stmt

/** The `within lo..hi` clause of a constrained subtype. `exclusiveHi` marks `..<`, which excludes
 * the upper endpoint; a plain `..` includes it. Bounds are literal expressions — an integer, a
 * float, or a character literal, optionally negated — evaluated to constants when the type resolves.
 */
case class RangeBound(lo: Expr, hi: Expr, exclusiveHi: Boolean) extends Positioned

/** `type Name = [new] Base [within lo..hi] [where predicate]` — a constrained subtype (`16`).
 *
 * `Base` is a scalar (an integer, a float, or `char`). Without `new` the subtype is **transparent**:
 * a value flows to and from its base with no cast, and every value produced into it is checked at
 * run time against `range` and `pred`, trapping on violation. With `new` it is a **derived** type:
 * nominally distinct from its base and from other deriveds, mixed only through an explicit cast.
 *
 * `range` is the `within` clause and `pred` the `where` predicate; either or both may be present,
 * and at least one must be unless the type is `new` (a bare transparent alias carries no constraint
 * and is not yet a form the language accepts). Inside `pred`, the contextual name `value` binds the
 * value being checked.
 */
case class TypeDecl(
    name: String,
    base: TypeRef,
    derived: Boolean,
    range: Option[RangeBound],
    pred: Option[Expr],
    vis: Visibility = Visibility.Public,
) extends Stmt

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
 *
 * `supers` are the traits this one **requires**, written after the name as a bound is written:
 * `trait Word: Add + BitXor`. A supertrait is a promise the trait itself makes, so an `impl` supplies
 * it and everything that names the trait — a bound, a trait object — gets the required traits'
 * members along with its own.
 *
 * `tdefaults` are the `= Type` clauses of `trait Mul[Rhs = Self]`, keyed by the parameter they
 * belong to. A trait is one of the three declarations whose arguments are *written* where it is
 * applied, so a default has somewhere to stand in — the others are a struct and an enum. The
 * declarations whose parameters are solved instead carry the field only so the analyzer can say
 * why they may not have one.
 */
case class TraitDecl(
    name: String,
    tparams: List[String],
    methods: List[MethodDecl],
    bounds: Map[String, List[BoundRef]] = Map.empty,
    supers: List[BoundRef] = Nil,
    vis: Visibility = Visibility.Public,
    tdefaults: Map[String, TypeRef] = Map.empty,
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
    tdefaults: Map[String, TypeRef] = Map.empty,
) extends Stmt

/** The `module a.b.c` header a file carries, naming the module the file contributes to. The name is
 * a directory path with the separators read as dots (`13 §1`), so it is kept as its segments rather
 * than as one string — the segments are what a visibility scope and a platform layout are written
 * against, and the dotted spelling is recovered by `show`.
 */
case class ModuleName(parts: List[String]) extends Positioned {
  def show: String = parts.mkString(".")
}

/** Which way a capability clause points (`capabilities.md`, `13 §4`).
 *
 * The two are not opposites of one degree: `Narrows` *removes* a capability the target offers and
 * is enforced at every use inside the module, while `Requires` states a dependency the module
 * already has by using it, and buys one early diagnostic instead of one per use.
 */
enum CapabilityDirection:

  /** `no alloc` — the module gives the capability up, so using it here is an error. */
  case Narrows

  /** `requires alloc` — the module cannot be built where the capability is missing. */
  case Requires

/** One capability clause of a file's header: `no alloc`, `requires alloc` (`13 §4`).
 *
 * The name is kept as written rather than resolved to a member of the core set, because which names
 * are capabilities is a property of the project's configuration and not of the grammar
 * (`capabilities.md` — "the set is extensible"). The analyzer is what holds the set and what says
 * so when a clause names something that is not in it.
 */
case class CapabilityClause(direction: CapabilityDirection, name: String) extends Positioned

/** `link "z"` — a library the linker must be given for this file's `extern`s to resolve (`15 §8`).
 *
 * The name is the library's, not a flag: `m` rather than `-lm`. What that becomes on a command line
 * is the target's answer and not the author's, because where a library lives is a property of the
 * machine — libm is a file of its own on ELF, part of `libSystem` on Darwin, and absent altogether
 * from a freestanding target. A directive that spelled the flag would be right on one platform and
 * wrong everywhere else, which is the mistake `Toolchain.libraryFlags` exists to make impossible.
 */
case class LinkClause(name: String) extends Positioned

/** One file's parse: the module it contributes to, the capabilities and link requirements its header
 * declares, its statements, and the source it came from.
 *
 * `module` is absent for a file that declares no header, which puts it in the **anonymous root
 * module** — the module whose name is the empty path. A single-file program is exactly that case,
 * which is why one needs no header to compile.
 *
 * `capabilities` is a property of the *module* written on each of its files, so it is read per file
 * and held to agreeing across them (`13 §4`).
 *
 * `links` is **not** held to agreeing, and that is the one place these two headers differ. A
 * capability describes what the whole module may do, so files that disagree describe different
 * modules; a link requirement describes what one file's `extern`s need, so a module whose externs
 * all sit in one file has nothing to say in the other four. The module's requirement is the union of
 * its files' (`15 §8`).
 *
 * `source` is carried because a file is the unit several module rules are stated over, and a
 * diagnostic about one has to name it even where the file holds nothing to point at.
 */
case class Program(
    body: List[Stmt],
    module: Option[ModuleName],
    capabilities: List[CapabilityClause],
    links: List[LinkClause],
    source: Source,
)
