package sh.sysl

/** Statements and declarations — everything a file's body may hold.
 *
 * `Stmt` is sealed and is the one hierarchy that spans both, because at the top of a file the two
 * are the same thing: a declaration is a statement that binds a name rather than doing work, and
 * which of the two a given line is depends on the file it is in (`13 §7`).
 */

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
case class VarDecl(name: String, typ: Option[TypeRef], init: Option[Expr],
                   vis: Visibility = Visibility.Public) extends Stmt

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

/** `static val`, `static var`, `static f() -> …` — a declaration in the file the program starts in
 * that belongs to the **module** rather than to that file's body (`13 §7`).
 *
 * The file carrying a program's statements is a body, so what it declares is local to that body: a
 * `val` is a stack local, a function is a nested function (`12 §5a`). That is what a reader wants
 * nearly always, and there are three things it cannot be — a nested function may not be generic, has
 * no address, and is not a value — plus one a local cannot be, which is visible to another file. This
 * is how a declaration opts out and becomes an ordinary module member.
 *
 * It is a **wrapper rather than a flag** because that is the whole of what it does: nothing past the
 * point where a file's declarations are separated from its statements ever sees one. `ProgramWalk`
 * unwraps it, hands the inner declaration to the hoisting passes exactly as another file's would be,
 * and no later pass has a case for it.
 *
 * It is meaningful in exactly one file per program, since only the entry file has a body for a
 * declaration to *not* belong to; anywhere else it says nothing and is refused rather than ignored.
 */
case class StaticDecl(inner: Stmt) extends Stmt

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

/** `invariant <bool> [, "message"]` at the head of a loop body — a condition that holds on every
 * entry to the body (`17 §3`).
 *
 * It is a statement rather than a slot in each loop's header so that one rule serves all five loop
 * forms. Where it may stand is the analyzer's: at the head of a loop's body and nowhere else.
 */
case class Invariant(cond: Expr, msg: Option[String]) extends Stmt

/** `variant <int>` — a measure that strictly decreases (`17 §3`, `17 §4`).
 *
 * At the head of a loop body it decreases from one iteration to the next. In a function's contract
 * block it decreases at each direct recursive call, and there it may read only the parameters, which
 * is what lets the check be made entirely at the call site.
 */
case class Variant(expr: Expr) extends Stmt

/** `asm` — machine instructions, in an arm per architecture (`inline-assembly.md`).
 *
 * Exactly one arm is selected, and every architecture the compiler can build for has to appear in
 * one, so a processor with no answer is reported while building for a different one. What an arm
 * holds is deliberately not a statement list: the instructions are text sysl does not read, and the
 * operands beside them are the whole of what it does.
 */
case class AsmStmt(arms: List[AsmArm]) extends Stmt

/** One architecture arm. `archs` names the processors it answers for, spelled as `#if` spells them. */
case class AsmArm(archs: List[String], body: AsmBody) extends Positioned

sealed trait AsmBody

/** Instructions, the operands they name, and what they destroy besides. All three lists may be
 * empty, and an arm written with nothing under it is exactly that — an architecture on which the
 * operation costs no instruction, which is a different claim from having no answer.
 */
case class AsmCode(lines: List[String], operands: List[AsmOperand], clobbers: List[String]) extends AsmBody

/** `unavailable "reason"` — there is no answer on these processors, and the reason is what a call
 * from one of them is told.
 */
case class AsmUnavailable(reason: String) extends AsmBody

/** Which way a value crosses the instruction boundary. Reading and writing one variable is not
 * spelled with both of these — it is a single operand the language does not have yet, and the pair
 * would compile to two registers rather than one.
 */
enum AsmDir {
  case In, Out
}

/** One operand: a variable already in scope, a direction, and where it has to live. `reg` is the
 * machine register named for it, or `None` for the `reg` class — any general-purpose register the
 * allocator likes.
 */
case class AsmOperand(dir: AsmDir, name: String, reg: Option[String]) extends Positioned

/** One line inside an arm, before the lines are sorted into the three lists `AsmCode` holds. It
 * exists only between the parser reading a block and building the arm from it: the instructions
 * keep their order and nothing else in the block has one, so a single pass over a mixed list is
 * simpler than a grammar that insists on which kind comes first.
 */
enum AsmItem {
  case Line(text: String)
  case Operand(operand: AsmOperand)
  case Clobber(regs: List[String])
}

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
 *
 * `overrides` is the `override` keyword written in front of the member (`02 § override`), which says
 * it replaces a body the trait already supplied rather than answering a requirement the trait left
 * open. It is required where that is what the member does and refused where it is not, so a reader of
 * an `impl` block can tell the two apart without going to the trait to find out.
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
    overrides: Boolean = false,
    /** Which of `tparams` stand for a **value** rather than a type (`10 §9`), and at what type. */
    tvalues: Map[String, TypeRef] = Map.empty,
    /** Which of `tparams` stand for a **list** of types (`10 §10`).
      *
      * A member's parameter list is its own, exactly as a function's is, so a pack may stand in it.
      * What cannot carry one is a declaration whose parameters *are* its shape — a struct, an enum
      * or a trait — and that refusal is about the shape rather than about members.
      */
    tpacks: Set[String] = Set.empty,
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
    tdefaults: Map[String, TypeRef] = Map.empty,
    /** Which of `tparams` stand for **values** rather than types, and the type each argument must
      * have — `[const N: usize]` (`10 §9`). The two kinds share `tparams` because they share one
      * list, one namespace and one argument position; this map is what tells them apart.
      */
    tvalues: Map[String, TypeRef] = Map.empty,
    /** Which of `tparams` stand for a **list** of types — `[..A: Display]` (`10 §10`). */
    tpacks: Set[String] = Set.empty,
    test: Option[TestAttr] = None,
    conv: Option[CallConv] = None,
    /** `@tailrec` — see `TFunc.tailrec`. */
    tailrec: Boolean = false,
    /** `@pure` — see `TFunc.pure`. */
    pure: Boolean = false,
    /** `@ghost` — see `TFunc.ghost`. */
    ghost: Boolean = false,
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
  case Pure                 extends Attr("pure")
  case Ghost                extends Attr("ghost")
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
    /** Which of `tparams` stand for **values** rather than types, and the type each argument must
      * have — `struct Buf[const N: usize]` (`10 §9`).
      */
    tvalues: Map[String, TypeRef] = Map.empty,
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
                    tdefaults: Map[String, TypeRef] = Map.empty,
                    /** Which of `tparams` stand for **values** rather than types (`10 §9`), and the
                      * type each argument must have.
                      */
                    tvalues: Map[String, TypeRef] = Map.empty) extends Stmt

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
 *
 * `overrides` is the `override` keyword written in front of the block (`02 § override`), which says
 * it deliberately replaces a more general implementation that already covers the same type —
 * `override impl Display for []Point` against the library's `impl[T: Display] Display for []T`.
 * Without it the second implementation is refused, so the accidental duplicate the rule exists to
 * catch is still caught.
 */
case class ImplDecl(
    traitName: String,
    forType: TypeRef,
    methods: List[MethodDecl],
    tparams: List[String] = Nil,
    bounds: Map[String, List[BoundRef]] = Map.empty,
    traitArgs: List[TypeRef] = Nil,
    tdefaults: Map[String, TypeRef] = Map.empty,
    overrides: Boolean = false,
    /** Which of `tparams` stand for **values** rather than types, and the type each argument must
      * have — `impl[const N: usize, T: Display] Display for [N]T` (`10 §9`). It is what tells a
      * block covering every array length from one covering the length it named, which the resolved
      * subject cannot: a value parameter stands at zero for the walk that checks the body, so `[N]T`
      * and `[0]T` resolve alike and only the syntax says which was written.
      */
    tvalues: Map[String, TypeRef] = Map.empty,
    /** Which of `tparams` stand for a **list** of types rather than one — `impl[..A: Eq] Eq for
      * (..A)` (`10 §10`). Recorded for the reason `tvalues` is: a pack stands at two types for the
      * walk that checks the body, so `(..A)` and a written-out pair resolve alike and only the
      * syntax says which was meant.
      */
    tpacks: Set[String] = Set.empty,
) extends Stmt
