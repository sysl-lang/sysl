package io.github.edadma.sysl

/** The *typed* abstract syntax tree.
 *
 * The analyzer turns the structural `ast.scala` tree into this one: every node carries a
 * resolved `Type`, names are resolved to their unique bindings, and every rule that could
 * fail (unknown name, type mismatch, wrong arity) has already been checked. Codegen is then
 * a straight lowering — it selects instructions from the types it is handed and never makes
 * a semantic decision of its own.
 *
 * A typed node keeps the position of the untyped one it came from, so the passes that run *after*
 * the analyzer — escape analysis, which sees only this tree — can point at source too.
 */

sealed trait TExpr extends Positioned {
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
case class TArrayFill(value: TExpr, arrayTy: Type.Array)      extends TExpr { def ty: Type = arrayTy }

/** The same two forms written where a `[]T` was expected: storage of the program's own, and a view
 * of all of it. The count of a `TBufFill` is an ordinary expression rather than part of a type,
 * which is the whole reason these exist — an array's length is fixed when it is compiled, and a
 * length read out of a file is not (`07 §Storage sized while running`).
 */
case class TBufLit(elems: List[TExpr], sliceTy: Type.Slice) extends TExpr { def ty: Type = sliceTy }
case class TBufFill(value: TExpr, count: TExpr, sliceTy: Type.Slice) extends TExpr { def ty: Type = sliceTy }

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

/** A value produced into a constrained subtype (`03`): `value` is the base-typed value, and
 * `target` names the subtype whose `within` range and `where` predicate it must satisfy. Codegen
 * emits `value`, checks it, and yields the same value — the representation is unchanged, so the
 * only run-time effect is a trap when a constraint is violated.
 */
case class TConstrainedCheck(value: TExpr, target: Type.Constrained) extends TExpr { def ty: Type = target }

/** `T::Valid(x)` — whether `x` satisfies `target`'s `within` range, as a `bool`. Never traps; it is
 * the total test a checked cast is the trapping form of.
 */
case class TConstrainedValid(value: TExpr, target: Type.Constrained) extends TExpr { def ty: Type = Type.Bool }

/** `T::Succ(x)` / `T::Pred(x)` — the next (`up`) or previous value in `target`'s range, trapping at
 * the far end (`Succ` at `Last`, `Pred` at `First`). Yields the base integer.
 */
case class TConstrainedStep(value: TExpr, target: Type.Constrained, up: Boolean, ty: Type) extends TExpr

/** Reads a local variable (or parameter) by its unique name. */
case class TLoad(name: String, ty: Type) extends TExpr

/** `result` inside an `ensure` postcondition — the value the function is about to return. */
case class TResult(ty: Type) extends TExpr

/** `old(e)` inside an `ensure` — reads the value expression `e` had at function entry, captured
 * into the function's `olds` slab before the body ran. `index` is that slab position.
 */
case class TOld(index: Int, ty: Type) extends TExpr

/** Names a module-level `val` — storage that exists for the whole run, under the key its module
 * gives it. It is a *place*, so indexing and iterating reach into it without copying the whole
 * thing out; what it is not is a writable one, which the analyzer enforces rather than the type.
 */
case class TGlobal(symbol: String, ty: Type) extends TExpr

/** `*p` — reads through a pointer or reference. */
case class TDeref(operand: TExpr, ty: Type) extends TExpr

/** `&place` — the address of a place, as a raw pointer. */
case class TAddrOf(place: TExpr, ty: Type) extends TExpr

/** `place = value` — stores and yields the assigned value. The place is a `TLoad`, a `TDeref`,
 * or a `TField` chain over one of those; codegen computes its address rather than its value.
 */
case class TStore(place: TExpr, value: TExpr, ty: Type) extends TExpr

/** A compound assignment `place op= value`, yielding the updated value. `dispatch` is present when
 * the operator is a trait method rather than an instruction (`14 §3`).
 */
case class TUpdate(place: TExpr, op: String, value: TExpr, ty: Type, dispatch: Option[TDispatch] = None)
    extends TExpr

/** `++`/`--`, prefix (new value) or postfix (old value). */
case class TIncDec(place: TExpr, op: String, pre: Boolean, ty: Type) extends TExpr

case class TBinary(op: String, left: TExpr, right: TExpr, ty: Type) extends TExpr
case class TUnary(op: String, operand: TExpr, ty: Type)             extends TExpr

/** `&&` / `||` — short-circuit, always boolean. */
case class TLogical(op: String, left: TExpr, right: TExpr) extends TExpr { def ty: Type = Type.Bool }

/** An operator that a trait supplies rather than the machine (`14 §3`): the function it lowers to,
 * whether the derivation swaps its operands, and whether it negates the result (`14 §2`).
 *
 * It rides on the node the operator already lowers to instead of replacing that node with a `TCall`,
 * and the reason is the two forms that use one operand **twice from a single evaluation** — a
 * comparison chain, which compares each middle operand against both its neighbours, and a compound
 * assignment, which updates the place it read. Codegen holds that operand in a register for the
 * scalar lowering; naming the method here lets the call read the same register, where a call built
 * over the operand's own tree would evaluate it a second time.
 */
case class TDispatch(name: String, swap: Boolean = false, negate: Boolean = false)

/** One comparison in a chain: the operator, and the method that performs it when the operand type
 * reaches `Eq`/`Ord` through a trait instead of an instruction.
 */
case class TCmp(op: String, dispatch: Option[TDispatch] = None)

/** A comparison chain `a < b < c`, ANDing the pairwise results. */
case class TCompare(operands: List[TExpr], cmps: List[TCmp]) extends TExpr { def ty: Type = Type.Bool }

/** Several expressions evaluated in order for their effects, yielding nothing. `print(a, b, c)`
 * desugars to one of these — a call per value, with the separators between — which is what lets the
 * printing itself live in the prelude rather than in codegen.
 */
case class TSeq(exprs: List[TExpr]) extends TExpr { def ty: Type = Type.Unit }

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

/** Standard output as a `*Writer` — the sink a value renders itself into when `print` is given one
 * that is not a built-in (`14 §6`).
 *
 * It carries no state at all, and that is why it is a node rather than a value the prelude could
 * have declared: a writer over standard output has nothing to keep, and there is no struct with no
 * fields for it to be one of. Its data word is therefore null, and the `write` in its table is the
 * prelude's own `putbytes` — so the one function a freestanding target replaces is still that one.
 */
case class TStdout() extends TExpr { def ty: Type = Type.Ptr(Type.Trait("Writer")) }

/** `str(x)` and an `f"…"` hole on a value that renders itself: `method` is the `display` its type
 * reaches, and it writes into a growable buffer whose bytes this yields as a fresh `string`.
 *
 * That buffer is the second sink the compiler provides, for the sibling of `TStdout`'s reason — a
 * growable byte buffer is not something sysl can express yet (`07`, *Not yet*), so the prelude
 * could no more declare this writer than the other one.
 */
case class TRender(value: TExpr, method: String, spec: TExpr) extends TExpr { def ty: Type = Type.Str }

/** `c"…"` — the address of a NUL-terminated constant, which is what a C interface reads a string
 * as. The terminator is not counted in anything: it is there for the callee to find the end by, and
 * the value is a plain `*u8`.
 */
case class TCStrLit(value: String) extends TExpr { def ty: Type = Type.Ptr(Type.Integer(8, signed = false)) }

/** A call to a user function. */
case class TCall(name: String, args: List[TExpr], ty: Type) extends TExpr

/** Forgets a value's type, keeping what its trait says can still be done to it (`02`): the operand
 * goes on pointing where it pointed, and the method table for the type it is losing rides beside it.
 *
 * The operand is a `*T` for a raw trait object and a `&T` for a counted one, which is what decides
 * which of the type's two tables this names — the data word addresses the value in the first case
 * and its box in the second.
 */
case class TErase(operand: TExpr, vtable: String, ty: Type) extends TExpr

/** A call through a trait object's method table. `slot` is the method's index in the trait's
 * declaration order, and the receiver's data word is the first argument — so which function runs is
 * read out of the table at run time rather than named here.
 */
case class TVCall(receiver: TExpr, slot: Int, args: List[TExpr], ty: Type) extends TExpr

/** The three ABI primitives of a variadic body (`12 §9`), each holding the *address* of the
 * `va_list` it works on — they advance it rather than reading a copy of it, so what the analyzer
 * hands over is the place, exactly as `&ap` would.
 *
 * `TVaArg` carries the type it reads, which the analyzer took from the context the value is used
 * in; there is nothing in the tail to check that against, which is what makes it as unsafe as C's.
 */
case class TVaStart(ap: TExpr) extends TExpr { def ty: Type = Type.Unit }
case class TVaEnd(ap: TExpr)   extends TExpr { def ty: Type = Type.Unit }
case class TVaArg(ap: TExpr, ty: Type) extends TExpr

/** Positional construction of a value struct. */
case class TStructNew(struct: Type.Struct, args: List[TExpr]) extends TExpr { def ty: Type = struct }

/** A struct value checked against its `invariant` clauses (`05`): `value` builds the struct, `invFn`
 * is the synthesised `<Struct>$inv` that takes the fields and returns a `bool`. Codegen emits
 * `value`, calls `invFn` with its field values, traps on a false result, and yields the same value —
 * the struct is unchanged, so the only run-time effect is a trap when an invariant is violated.
 */
case class TStructInvCheck(value: TExpr, struct: Type.Struct, invFn: String) extends TExpr { def ty: Type = struct }

/** Construction of an enum value: a simple enum's integer constant, or a data enum's variant
 * (with `args` for a data-carrying variant, empty for a nullary one).
 */
case class TEnumNew(enumTy: Type.Enum, variant: Type.EnumVariant, args: List[TExpr]) extends TExpr {
  def ty: Type = enumTy
}

/** `Color(n)` — an integer reinterpreted as a simple enum, checked: it traps on an integer that
 * is not a declared discriminant. Yields the enum, stored at its underlying integer width.
 */
case class TEnumFromInt(value: TExpr, en: Type.Enum) extends TExpr { def ty: Type = en }

/** `Color.try(n)` — the fallible constructor: `Some(Color)` when `n` is a declared discriminant,
 * `None` otherwise. `optTy` is the resulting `Option[Color]` and `some`/`none` its two variants.
 */
case class TEnumTry(value: TExpr, en: Type.Enum, optTy: Type.Enum,
                    some: Type.EnumVariant, none: Type.EnumVariant) extends TExpr { def ty: Type = optTy }

/** A simple enum's type attribute with a runtime argument (`04`): `kind` names which one —
 * `Pos` (a value's 0-based position), `Val` (the value at a position, trapping out of range),
 * `Succ`/`Pred` (the neighbouring value, trapping at the end), `Image` (a value's name as a
 * string), or `Value` (the value named by a string, trapping on no match). `arg` is the one
 * operand. The bare `First`/`Last` are compile-time constants and need no node of their own.
 */
case class TEnumAttr(kind: String, en: Type.Enum, arg: TExpr, ty: Type) extends TExpr

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

/** `loop body` — the same shape with the condition removed, so the only way out is a `break`.
 * `ty` is the type its `break`s meet at, and `never` where it has none: nothing arrives after a
 * loop that cannot end.
 */
case class TLoop(body: List[TStmt], ty: Type) extends TExpr

/** `for name in lo..hi [else …]` — the loop variable has the integer type `varTy` of its bounds;
 * `ty` is the loop expression's result type.
 */
case class TFor(name: String, varTy: Type, lo: TExpr, hi: TExpr, inclusive: Boolean,
                body: List[TStmt], elseBlock: Option[TBlock], ty: Type) extends TExpr

/** `for init; cond; step [else …]` — the three-clause loop. An absent condition is `true`, and the
 * step is what `continue` runs before the next test.
 */
case class TCFor(init: Option[TStmt], cond: Option[TExpr], step: Option[TStmt], body: List[TStmt],
                 elseBlock: Option[TBlock], ty: Type) extends TExpr

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
 * each so the body can read and mutate them uniformly). `requires`/`ensures` are the
 * design-by-contract clauses: each precondition is checked on entry, each postcondition before
 * every return, with a `TResult` in an `ensure` standing for the returned value.
 */
case class TFunc(
    name: String,
    params: List[(String, Type)],
    retTy: Type,
    body: TBlock,
    variadic: Boolean = false,
    requires: List[(TExpr, Option[String])] = Nil,
    ensures: List[(TExpr, Option[String])] = Nil,
    olds: List[TExpr] = Nil,
)

/** A function the linker supplies, which the module declares rather than defines. Only the ones
 * the program actually calls reach here, so an `extern` the prelude offers and nobody uses costs
 * the output nothing.
 *
 * `name` is what the program calls it by and `symbol` is what the linker resolves; they differ only
 * where the declaration gave a link name. Two declarations may share one symbol — the prelude's
 * `snprintf` and a program's own — so the module declares each *symbol* once.
 */
case class TExtern(name: String, symbol: String, params: List[Type], retTy: Type,
                   variadic: Boolean = false)

/** One method table — the constant a trait object's first word points at, holding one function
 * pointer per method the trait declares, in declaration order.
 *
 * There is one table per (trait, implementing type, **memory mode**), and the mode is why `boxed`
 * is here: a `*Trait`'s data word is the value's own address, while a `&Trait`'s is the address of
 * the reference-counted box the value sits inside, so the two reach the same implementation through
 * different arithmetic.
 */
case class TVtable(name: String, traitName: String, forType: Type, boxed: Boolean, slots: List[TVSlot])

/** One slot of a method table: the function it ends at, how that function wants its receiver, and
 * the signature a call site sees. Between the data word and the receiver the function declared
 * there may be a header to step over and a value to load, which is what the mode decides.
 */
case class TVSlot(target: String, recv: RecvMode, params: List[Type], retTy: Type)

/** One module-level `val`: read-only storage laid down whole, under the key its module gives it.
 * The initializer is a constant tree, so it is written into the object file rather than computed —
 * there is no code to run before `main` and nothing to order.
 */
case class TVal(symbol: String, ty: Type, init: TExpr)

/** A whole program: hoisted struct, enum, and function declarations, the method tables its trait
 * objects dispatch through, the externs it calls, the module-level `val`s it reads, plus the
 * top-level statements that make up `main`. Only data enums appear in `enums` — a simple enum
 * lowers to `i32` and needs no type declaration.
 */
case class TProgram(
    structs: List[Type.Struct],
    enums: List[Type.Enum],
    vtables: List[TVtable],
    externs: List[TExtern],
    vals: List[TVal],
    funcs: List[TFunc],
    main: List[TStmt],
)
