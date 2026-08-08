package sh.sysl

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

  /** The type of the **storage** this node names, as against `ty`, which is the type of the value
   * read out of it. The two differ only by a `volatile` qualifier (`03 § Device memory`), which the
   * projection that built the node stripped: a read of a `volatile u32` hands back a `u32`.
   *
   * It is recovered from the receiver rather than kept on the node, because the receiver is where
   * the declaration that wrote the qualifier still is — a struct's field list, an array's element
   * type, a pointer's pointee. That keeps one statement of the fact instead of two that could drift,
   * and it is what lets `&regs.status` be a `*volatile u32` and every access through the result stay
   * an access to a register.
   */
  def placeTy: Type = this match
    case TDeref(operand, t)     => Type.pointee(operand.ty).getOrElse(t)
    case TIndex(receiver, _, t) => Type.element(receiver.ty).getOrElse(t)
    case TField(receiver, i, t) =>
      receiver.ty match
        case s: Type.Struct if i >= 0 && i < s.fields.length => s.fields(i)._2
        case _                                               => t
    case _ => ty
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

/** Weakens a reference, because a `weak T` was expected where a `&T` was written (`03`). The
 * object's weak count goes up and its strong count does not, so the edge this makes does not keep
 * the object alive.
 */
case class TDowngrade(value: TExpr, weakTy: Type.Weak) extends TExpr { def ty: Type = weakTy }

/** `w.get()` — asks the box whether the object is still there, and takes a count if it is.
 * `optTy` is the `Option[&T]` handed back, with `some`/`none` its two variants.
 */
case class TUpgrade(value: TExpr, optTy: Type.Enum, some: Type.EnumVariant, none: Type.EnumVariant)
    extends TExpr { def ty: Type = optTy }

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
 *
 * The type is carried rather than computed, and it is the only node in this tree where that is so.
 * A `usize` is pointer-width by definition, so its width is the target's answer — and a node that
 * worked its own type out would have to be told the machine to do it, which is a thing a typed tree
 * should already be past. The analyzer knows the target and writes it down once.
 */
case class TLen(receiver: TExpr, ty: Type) extends TExpr

/** `s.bytes` — a string's bytes, as a `[]const u8`. The same three words the string already is, so
 * this reinterprets rather than converts; only the validity guarantee is given up.
 *
 * The view is read-only because the string is, and because the elements may be a literal's, which
 * live in memory the platform will not let anybody write. A writable view of them was the one way
 * a program with no `*T` in it could still be killed by the machine (`03`), so the guarantee that
 * chapter opens with is what this bit is holding up.
 */
case class TBytes(receiver: TExpr) extends TExpr {
  def ty: Type = Type.Slice(Type.Byte, readOnly = true)
}

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

/** Names module-level storage — a `val`, under the key its module gives it, or an `extern` variable,
 * under the symbol the linker resolves. Either way it exists for the whole run and it is a *place*,
 * so indexing and iterating reach into it without copying the whole thing out.
 *
 * `writable` is what tells the two apart, and it is the only thing that does. A `val` is written
 * once and the analyzer refuses both an assignment to one and a `*T` into it (`13 §7`); an `extern`
 * variable is storage this program did not lay down, so there is no such promise to keep — `optind`
 * and `optarg` are assigned by ordinary C and a declaration that could not would name half of what
 * it was added to reach.
 */
case class TGlobal(symbol: String, ty: Type, writable: Boolean = false) extends TExpr

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
 *
 * `check` is present when the place holds a constrained subtype: the operation computes a value the
 * place cannot be given unexamined, so the constraint is tested between the arithmetic and the store
 * (`16 §4`). It is the same test a `TConstrainedCheck` makes; it lives here rather than in a
 * wrapping node because the store is inside this one, and a value has to be refused before it lands.
 */
case class TUpdate(place: TExpr, op: String, value: TExpr, ty: Type, dispatch: Option[TDispatch] = None,
                   check: Option[Type.Constrained] = None)
    extends TExpr

/** `++`/`--`, prefix (new value) or postfix (old value). `check` carries a constrained place's
 * constraint, for the reason `TUpdate`'s does.
 */
case class TIncDec(place: TExpr, op: String, pre: Boolean, ty: Type, check: Option[Type.Constrained] = None)
    extends TExpr

case class TBinary(op: String, left: TExpr, right: TExpr, ty: Type) extends TExpr
case class TUnary(op: String, operand: TExpr, ty: Type)             extends TExpr

/** A member a built-in has by rule rather than by an `impl`, and which lowers to instructions rather
 * than to a call (`14 §5`, `CoreTraits.numeric`).
 *
 * A node of its own rather than a tree of the operators it is equivalent to, because every one of
 * these reads its operand more than once — a magnitude compares it and negates it — and a tree would
 * evaluate the receiver expression once per mention. `f().abs()` must call `f` once.
 *
 * `width` is the integer type the instruction runs at, which is the receiver's own type with any
 * constrained subtype resolved away; `ty` is what the member answers, and the two differ wherever
 * the answer is a **count** of bits rather than a value of the receiver's type. `amount` is the
 * rotations' second operand and nothing else's.
 */
case class TIntOp(op: String, operand: TExpr, amount: Option[TExpr], width: Type, ty: Type) extends TExpr

/** One atomic access, at the address `addr` points at (`06 § The kernel tier`, `Atomics`).
 *
 * `ops` are the value operands the form takes — none for a load, one for a store or a
 * read-modify-write, two for a compare-and-swap — and `at` is the type they and the access run at,
 * read off the address rather than off the operands so that a widened literal cannot decide it.
 *
 * `ordering` is a variant *name* rather than a value, because that is what it becomes in the emitted
 * instruction: LLVM spells the ordering as a keyword, so nothing here could be computed. The
 * analyzer is what refuses an ordering the call did not write out.
 *
 * `ty` differs from `at` for exactly one form — a store answers nothing — which is why both are
 * carried instead of the result being derived where it is read.
 */
case class TAtomic(op: String, addr: TExpr, ops: List[TExpr], ordering: String, at: Type, ty: Type)
    extends TExpr

/** `atomic_fence(ord)` — a barrier that reaches no address, ordering the accesses around it. */
case class TFence(ordering: String) extends TExpr { def ty: Type = Type.Unit }

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
 * printing itself live in the library rather than in codegen.
 */
case class TSeq(exprs: List[TExpr]) extends TExpr { def ty: Type = Type.Unit }

/** The built-in `str(x)` — a primitive value's string form: a decimal for an integer, its UTF-8
 * for a `char`, `"true"`/`"false"` for a `bool`, a `%g` rendering for a float, and a `string`
 * unchanged. It allocates a fresh buffer for every type but `string`, where it is the identity;
 * a `Display` trait method replaces it once traits land.
 */
case class TStr(arg: TExpr) extends TExpr { def ty: Type = Type.Str }

/** The built-in `from_utf8_unchecked(b)` — the bytes of a `[]u8` as a `string`, with nothing
 * checked (`04 § Validity`).
 *
 * It is here rather than in the library for the reason the `va_*` forms are: no sysl body can build
 * a `string`, since every safe route to one already has the UTF-8 guarantee behind it. The bytes are
 * **copied** into a fresh owning string rather than shared with the slice — a `[]u8` is mutable and
 * a `string` is not, so aliasing the source would let a later write break the invariant of a value
 * that had already been checked.
 */
case class TFromBytes(arg: TExpr) extends TExpr { def ty: Type = Type.Str }

/** A `[]T` standing where a `[]const T` was asked for — the one direction between the two views
 * that is safe, since giving up the ability to write cannot be observed by anything holding the
 * view it came from.
 *
 * It carries no instruction. Both views are the same three words and the same layout, so this
 * exists only so that what is written on the expression matches what the context asked for; codegen
 * emits the operand and nothing else.
 */
case class TConstView(arg: TExpr) extends TExpr { def ty: Type = Type.constView(arg.ty) }

/** `format(value, spec)` — one value rendered through a printf specifier, the lowering of an
 * `f"…"` hole. Always allocates a fresh string; `spec` is the sysl specifier (`%08.2f`), which
 * codegen turns into the C one it hands `snprintf`.
 */
case class TFormat(arg: TExpr, spec: String) extends TExpr { def ty: Type = Type.Str }

/** `str(x)` and an `f"…"` hole on a value that renders itself: `method` is the `display` its type
 * reaches, and it writes into a growable buffer whose bytes this yields as a fresh `string`.
 *
 * That buffer is the one sink the compiler still provides. Standard output is no longer one of them:
 * a fieldless struct with an `impl Writer` says the whole of what that sink is, and the library
 * declares it. What keeps this one here is the *buffer*, whose growth and handover are written by
 * hand in `WriterEmitter`.
 *
 * `slot` is set where the value is a **trait object** whose trait requires `Display`: the renderer is
 * then a word read out of the object's own table rather than a function named here, and `method`
 * carries only the name a diagnostic would use.
 */
case class TRender(value: TExpr, method: String, spec: TExpr, slot: Option[Int] = None) extends TExpr {
  def ty: Type = Type.Str
}

/** `c"…"` — the address of a NUL-terminated constant, which is what a C interface reads a string
 * as. The terminator is not counted in anything: it is there for the callee to find the end by, and
 * the value is a plain `*u8`.
 */
case class TCStrLit(value: String) extends TExpr { def ty: Type = Type.Ptr(Type.Integer(8, signed = false)) }

/** A call to a user function. */
case class TCall(name: String, args: List[TExpr], ty: Type, results: Boolean = false) extends TExpr

/** `&f` — the address of a declared function, as the C convention would have somebody call it
 * (`12 §6a`). `name` is the function this addresses; `entry` is the symbol the address is *of*,
 * which is the function itself where sysl's own convention already agrees with C's and a generated
 * adapter where it does not.
 *
 * The two are separate because the choice is the emitter's and the reachability walk's question is
 * about the first: what keeps the definition in the program is that something took its address, and
 * that stays true whichever symbol the address turns out to name.
 */
case class TFuncAddr(name: String, entry: String, ty: Type) extends TExpr

/** A call through a `*extern` — a function pointer with no definition in sight (`12 §6a`).
 *
 * It carries the whole signature rather than looking one up, because there is nothing to look up:
 * the callee is a value, and what is known about it is exactly what its type said. That is also why
 * the arguments cross under the C convention — the type says the other end obeys it, and nothing
 * here can check that it does.
 */
case class TCallPtr(callee: TExpr, args: List[TExpr], params: List[Type], ty: Type) extends TExpr

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
case class TVCall(receiver: TExpr, slot: Int, args: List[TExpr], ty: Type, results: Boolean = false)
    extends TExpr

/** The ABI primitives of a variadic body (`12 §9`), each holding the *address* of the `va_list` it
 * works on — they advance it rather than reading a copy of it, so what the analyzer hands over is
 * the place, exactly as `&ap` would. A `*va_list` a caller lent is already that address and is
 * handed over as it stands.
 *
 * `TVaArg` carries the type it reads, which the analyzer took from the context the value is used
 * in; there is nothing in the tail to check that against, which is what makes it as unsafe as C's.
 *
 * `TVaCopy` takes a second walk over the same tail from where the first has reached, which is what
 * a body needs before lending its walk to somebody who will advance it.
 */
case class TVaStart(ap: TExpr) extends TExpr { def ty: Type = Type.Unit }
case class TVaEnd(ap: TExpr)   extends TExpr { def ty: Type = Type.Unit }
case class TVaArg(ap: TExpr, ty: Type) extends TExpr
case class TVaCopy(dst: TExpr, src: TExpr) extends TExpr { def ty: Type = Type.Unit }

/** A walk handed to a **foreign** function whose C parameter is a `va_list` — `vprintf` and its
 * family (`12 §9`).
 *
 * It holds the address of the walk, like the four forms above, and differs from passing that
 * address in what the callee is given: C's `va_list` is a different type on every target and is
 * passed three different ways, so this is the node that turns the one thing sysl has into what the
 * target's C ABI wants. The result is a `ptr` whichever way that is, which is why the distinction
 * cannot be left for codegen to rediscover from the types — see `VaListAbi`.
 */
case class TVaPass(ap: TExpr) extends TExpr { def ty: Type = Type.Ptr(Type.VaList) }

/** Positional construction of a value struct. */
case class TStructNew(struct: Type.Struct, args: List[TExpr]) extends TExpr { def ty: Type = struct }

/** A struct value checked against its `invariant` clauses (`16 §6`): `value` builds the struct, `invFn`
 * is the synthesised `<Struct>$inv` that takes the fields and returns a `bool`. Codegen emits
 * `value`, calls `invFn` with its field values, traps on a false result, and yields the same value —
 * the struct is unchanged, so the only run-time effect is a trap when an invariant is violated.
 */
case class TStructInvCheck(value: TExpr, struct: Type.Struct, invFn: String) extends TExpr { def ty: Type = struct }

/** Something that may have changed a struct carrying `invariant` clauses (`16 §6`): `after` runs,
 * then `recv` — the struct that could have been mutated — is re-read and passed to `invFn`, trapping
 * on a false result. Yields what `after` yields, so `s.f = v` remains an expression of the field's
 * type.
 *
 * Two things wear this. A **write** into the struct: a direct `s.f = v`, a compound `s.f op= v`, a
 * through-pointer `(*p).f = v`, or one nested inside — `o.a.n = 9`. And a **`*self` method call on a
 * field of it**: `o.a.bump()` hands the callee somewhere to write that no longer names the `Outer`,
 * so the clause is re-run where the whole place is still known, which is the call site.
 *
 * These **nest** where a place lies inside more than one struct that carries clauses: `o.a.n` wraps
 * the store in `Inner`'s check and that in `Outer`'s, so the inner one runs first.
 */
case class TRecheck(after: TExpr, recv: TExpr, struct: Type.Struct, invFn: String) extends TExpr {
  def ty: Type = after.ty
}

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

/** A simple enum's type attribute with a runtime argument (`09 §2`): `kind` names which one —
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

/** One term of an `if`'s or a `while`'s condition — the `&&`-joined chain written out (`09 §12`).
 *
 * A condition is a **list** of these rather than one expression because a term may bind: an `is`
 * that matched leaves names the terms to its right and the guarded branch can read, and that is a
 * fact about the chain's shape, not about a value. An ordinary condition is a one-element list
 * holding a [[TCondTest]], so nothing that never writes `is` pays for it.
 */
sealed trait TCondTerm

/** A plain boolean term — every condition that says nothing about a pattern. */
case class TCondTest(cond: TExpr) extends TCondTerm

/** `subject is Pat` / `subject is not Pat`. The un-negated form's bindings are live from here
 * rightward; the negated form binds nothing, which is why it may hold no binding pattern. Several
 * `patterns` are `|`-alternatives, which share one answer and so may not bind either.
 */
case class TCondIs(subject: TExpr, patterns: List[TPattern], negated: Boolean) extends TCondTerm

/** `if cond then … else …` as a value (or unit when there is no else). */
case class TIf(cond: List[TCondTerm], thenBlock: TBlock, elseBlock: Option[TBlock], ty: Type) extends TExpr

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

/** A block: a sequence of statements optionally ending in a value expression. When `result`
 * is `None` the block's type is `unit`.
 */
case class TBlock(stmts: List[TStmt], result: Option[TExpr], ty: Type)

/** A block standing where an expression does, which is what one copy of an unrolled `for const`
 * becomes (`10 §10`).
 *
 * `if` and `match` carry their blocks in their own nodes because the branch is part of what they
 * are; an unrolled loop has no node of its own by the time this exists — it has become its copies —
 * so each copy needs to be an expression in its own right. It is a scope like any other block, which
 * is what keeps a `var` written inside the loop from being one variable redeclared per copy.
 */
case class TBlockExpr(block: TBlock) extends TExpr { def ty: Type = block.ty }

sealed trait TStmt

case class TVarDecl(name: String, ty: Type, init: TExpr) extends TStmt
case class TExprStmt(expr: TExpr)                         extends TStmt

/** A loop's `invariant`, at the head of its body (`17 §3`) — a condition that traps on false, which
 * is what every other clause in `16` already is. It carries no machinery of its own for that reason.
 */
case class TInvariant(cond: TExpr, msg: Option[String]) extends TStmt

/** A loop's `variant`, at the head of its body (`17 §3`): the measure is evaluated, compared against
 * the previous iteration's, and stored.
 *
 * `slot` names the pair of allocas the enclosing `TCheckedLoop` set up — `%<slot>.prev` holding the
 * last value and `%<slot>.armed` saying whether there has been one. The armed flag is what makes the
 * first iteration pass with nothing to compare against, and it is stored **at loop entry** rather
 * than in the function's prologue, which is the whole reason this statement cannot stand alone: a
 * loop inside another loop is entered many times, and a flag armed once per call would compare the
 * second entry's first measure against the first entry's last.
 */
case class TVariantCheck(slot: String, varTy: Type, expr: TExpr) extends TStmt

/** `ref name = place` (`03 § ref`) — a name bound to the storage `place` found, rather than to a
 * copy of what was in it.
 *
 * It declares **no slot**. Where a `TVarDecl` allocates storage and stores into it, this binds the
 * name's address to the address the place already has, so the walk that reaches an element is made
 * once and every later use is the load or store it would have been anyway. That is also why nothing
 * releases it at scope end: the ref took no count, and the storage belongs to whatever the place was
 * rooted at.
 *
 * The place is kept in the node as well as consumed by codegen because the analyzer's own record of
 * it (`Scoping.refPlaces`) does not survive into the tree, and a later pass reading this statement
 * should see the same place the binding was made from.
 */
case class TRefDecl(name: String, ty: Type, place: TExpr) extends TStmt

/** One write of a multi-assignment: the place, the operator that was written, the value, the trait
 * method a compound operator lowers to when it is not an instruction (`14 §3`), and the `invariant`
 * re-check the receiver needs once the write lands (`05`).
 *
 * The check is carried here rather than wrapped around a store node, as `TRecheck` wraps one,
 * because these writes are not expressions and there is nothing for a node to wrap.
 *
 * `constraint` is the compound arm's counterpart of `TUpdate.check` — a plain arm's value carries
 * its own check, having been analyzed against the place's type, while a compound arm computes one
 * here and so is checked here (`16 §4`).
 */
case class TWrite(place: TExpr, op: String, value: TExpr, dispatch: Option[TDispatch],
                  check: List[(TExpr, Type.Struct, String)],
                  constraint: Option[Type.Constrained] = None)

/** `a, b = b, a` — several places written from several values (`00 §2`).
 *
 * The order of events is the whole content of the form, and it is phases rather than one write at a
 * time. Every place's own subexpressions are computed first, and once, so an index that calls
 * something calls it a single time. Then everything the statement *reads* is read — what a compound
 * arm finds in its place, and then the whole right side — which is what makes a swap a swap instead
 * of two statements that leave both variables holding the same thing, and what makes every operand
 * of every arm see the values the statement started with. Only then does anything land.
 */
case class TMultiAssign(writes: List[TWrite]) extends TStmt

/** `while cond body [else …]` as an expression. `body` runs for effect each iteration; a `break`
 * in it carries the loop's value, and `elseBlock` (if present) supplies the value on normal
 * completion. `ty` is the loop's result type — `unit` when nothing carries a value.
 */
case class TWhile(cond: List[TCondTerm], body: List[TStmt], elseBlock: Option[TBlock], ty: Type) extends TExpr

/** `do body while cond [else …]` — `TWhile`'s shape entered at the body instead of at the test, so
 * the body runs once before `cond` is asked. `continue` targets the test, which is what the `loop` +
 * `if !cond then break` rewrite cannot say.
 */
case class TDoWhile(body: List[TStmt], cond: TExpr, elseBlock: Option[TBlock], ty: Type) extends TExpr

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
case class TCFor(init: List[TStmt], cond: Option[TExpr], step: List[TStmt], body: List[TStmt],
                 elseBlock: Option[TBlock], ty: Type) extends TExpr

/** `for all name in lo..hi do pred` / `for some …` — a quantifier over an integer range (`17 §2`),
 * which is an **expression** yielding a `bool` and not a loop: it carries no `break`, no `else`, and
 * no label, so its type is settled rather than met.
 *
 * The bounds share `TFor`'s shape because they are the same range. What differs is the body: a
 * single `TExpr` rather than a statement list, since a predicate is a condition and not a block.
 */
case class TQuantifier(universal: Boolean, name: String, varTy: Type, lo: TExpr, hi: TExpr,
                       inclusive: Boolean, pred: TExpr) extends TExpr { def ty: Type = Type.Bool }

/** A loop whose body carries a `variant`, wrapped so the measure's slots are set up **once per
 * entry** to the loop rather than once per call to the function (`17 §3`).
 *
 * It wraps rather than adding a field to each of the seven loop nodes, and what it holds is only
 * what has to happen outside the body: the two allocas and the store that disarms the comparison.
 * The check itself is a `TVariantCheck` among the body's statements, where it was written.
 *
 * A loop with only `invariant` clauses is not wrapped — those need nothing outside the body.
 */
case class TCheckedLoop(slot: String, varTy: Type, loop: TExpr) extends TExpr { def ty: Type = loop.ty }

/** `for name in seq [else …]` over an array or a slice. The loop variable is a *copy* of each
 * element, and the sequence is evaluated once.
 */
case class TForEach(name: String, elemTy: Type, seq: TExpr, body: List[TStmt],
                    elseBlock: Option[TBlock], ty: Type) extends TExpr

/** `for name in cursor [else …]` over a type that implements `Iterate` (`14 §7`).
 *
 * `cursor` is the name of the loop's own slot holding the iterator: the sequence expression is
 * evaluated once into it, and `next` is the already-built call that reads it and advances it, so
 * the loop's state is the loop's and nothing outside it moves. `bind` is the `Some(name)` pattern
 * that takes the element out of what `next` gave back — a `None` ends the loop, running the `else`
 * exactly as running out of a range does.
 */
case class TIterate(cursor: String, cursorTy: Type, init: TExpr, next: TExpr, bind: TPattern,
                    body: List[TStmt], elseBlock: Option[TBlock], ty: Type) extends TExpr
case class TReturn(value: Option[TExpr])                  extends TStmt

/** `break [expr]` and `continue`. `break` carries the loop's value when the loop yields one.
 * `depth` names the target loop by its distance out from the innermost — `0` is the nearest,
 * a larger number a loop reached through a `'label` — and indexes the codegen loop stack directly.
 */
case class TBreak(value: Option[TExpr], depth: Int) extends TStmt
case class TContinue(depth: Int)                    extends TStmt

/** `defer stmt` — the statements to run on the way out of the block this sits in (`03 § defer`).
 *
 * It is a list because one written statement can analyze to several, the way a binding that names
 * more than one thing does. Reaching this node emits nothing at the point it stands: it hands the
 * statements to the enclosing scope, which emits them at each edge that leaves it. So a `defer`
 * control never reaches schedules nothing, and one in a loop body schedules for that iteration.
 */
case class TDefer(stmts: List[TStmt]) extends TStmt

/** The one assembly arm that answered for the processor being built for (`inline-assembly.md`).
 *
 * The other arms are gone by the time this exists — an architecture is chosen once, in the
 * analyzer, so nothing downstream carries a branch that was never going to be taken. The
 * instructions are text nothing here reads; the operands are the whole of what the compiler knows
 * about the block, and they are what the constraint string is built from.
 */
case class TAsm(lines: List[String], operands: List[TAsmOperand], clobbers: List[String]) extends TStmt

/** An operand bound to the local it names.
 *
 * `name` is what the template says, and `slot` is the local's unique name — the storage an `in` is
 * loaded from and an `out` is stored to. Both are kept because they are two different jobs: the
 * template is the text a person wrote, and the slot is where the value lives after a scope has
 * renamed it. `reg` is the machine register the operand must occupy, or `None` for any
 * general-purpose one.
 */
case class TAsmOperand(dir: AsmDir, name: String, slot: String, ty: Type, reg: Option[String])

/** A user function. Parameters carry their unique names (the codegen allocates a slot for
 * each so the body can read and mutate them uniformly). `requires`/`ensures` are the
 * design-by-contract clauses: each precondition is checked on entry, each postcondition before
 * every return, with a `TResult` in an `ensure` standing for the returned value.
 *
 * `internal` says every caller of this function is in the module that defines it, so its symbol
 * needs no external linkage (`13 §2`). It is set for a declaration whose reach is the file that
 * wrote it, and it is the only thing the emitted linkage depends on.
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
    internal: Boolean = false,
    conv: Option[CallConv] = None,
    /** `@tailrec` was written above it: an assertion that its self-call is the last thing it does,
     * and a demand to be told at the compile rather than at the stack overflow when an edit stops
     * that being true (`12 § Tail calls`). The jump itself does not wait on this — it applies
     * wherever it applies — so what the flag reaches is `TailCalls.check` and nothing in codegen.
     */
    tailrec: Boolean = false,
    /** The `variant` its contract block declared: an integer measure over the **parameters** that
     * must strictly decrease at every direct recursive call (`17 §4`).
     *
     * That it reads only parameters is what makes the check local, and it is what this field is
     * enough for on its own. At a self-call the emitter has both the current parameter values and
     * the argument values about to replace them, so it evaluates this expression twice — once as it
     * stands, once with the arguments stored into the parameters' own slots — and needs neither a
     * substitution pass nor a hidden argument travelling with the call.
     */
    variant: Option[TExpr] = None,
    /** `@pure` was written above it: an assertion that a caller can observe nothing about this call
     * but its result (`17 §6`).
     *
     * It is checked rather than believed, and what it excludes is written out in `Purity`. It is not
     * inferred: a function is pure because it says so, which is what keeps an edit to a leaf from
     * breaking a caller three levels up with no annotation anywhere naming the promise it broke.
     */
    pure: Boolean = false,
    /** `@ghost` was written above it: the function exists for the specification and is erased before
     * codegen (`17 §8`).
     *
     * Its body is ordinary code and may read real state freely — that is the whole point of an
     * `is_sorted` — and what the mark buys is the pair of rules that make erasing it sound: nothing
     * executable may call it, and a clause that does is a clause that does not run. So a loop
     * invariant costing O(n) inside an O(n) loop costs nothing at all, without a switch that would
     * give one program two meanings.
     */
    ghost: Boolean = false,
    /** `@reads(…)` and `@writes(…)`: the module-level storage this function may touch (`17 §7`).
      *
      * **`None` and `Some(Nil)` are different claims and the distinction carries the whole design.**
      * A function with no annotation has effects nobody has written down — it may call and be called
      * by anything, exactly as before frames existed — while `@reads()` is the positive assertion
      * that it touches no module storage at all. That is what lets adoption run from the leaves up:
      * the first leaf to gain a frame forces its annotated callers to gain one, and everything
      * unannotated is undisturbed.
      *
      * The names are resolved symbols rather than what was written, so a frame means the same thing
      * from inside the module and from outside it.
      *
      * `writes` is included in the readable set rather than being disjoint from it — `count += 1` is
      * a read and a write of one variable, and a form that common should not have to say so twice.
      * SPARK's `Output`/`In_Out` split, which this collapses, is `17 § Open b`.
      */
    reads: Option[Set[String]] = None,
    writes: Option[Set[String]] = None,
)

/** A function the linker supplies, which the module declares rather than defines. Only the ones
 * the program actually calls reach here, so an `extern` the library offers and nobody uses costs
 * the output nothing.
 *
 * `name` is what the program calls it by and `symbol` is what the linker resolves; they differ only
 * where the declaration gave a link name. Two declarations may share one symbol — the library's
 * `snprintf` and a program's own — so the module declares each *symbol* once.
 */
case class TExtern(name: String, symbol: String, params: List[Type], retTy: Type,
                   variadic: Boolean = false)

/** Storage the linker supplies, which the module declares rather than lays down (`12 §1`).
 *
 * The same accounting the externs above get: only the ones something reads or writes reach here, and
 * two declarations may share one symbol, so the module declares each *symbol* once. What it carries
 * is the symbol and the type, because that is the whole of an `external global` line — there is no
 * initializer, and nothing about the storage for this module to decide.
 */
case class TExternVar(symbol: String, ty: Type)

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
 *
 * `computed` says which of the two ways it is filled. A constant tree is written straight into the
 * object file and nothing runs; anything else is code, evaluated once before the program's own
 * statements and stored, in an order the initializers' dependencies settle (`13 §7`).
 */
case class TVal(
    symbol: String,
    ty: Type,
    init: Option[TExpr],
    computed: Boolean,
    writable: Boolean = false,
)

/** The `main` a program declared, which runs after its top-level statements (`13 §7`).
 *
 * `func` is the key the function is filed under, which is what makes it reachable; `argsFn` names the
 * library function that turns the platform's `argc`/`argv` into the `[]string` it wants, and is
 * absent for a `main` that takes no parameters — so a program that does not ask for its arguments
 * carries none of the conversion.
 */
/** The program's entry point: which function it is, how its arguments are made, and -- where it
  * results in a `Result` -- the instantiated `sysl.main_result` that reports the failure and chooses
  * the exit status, together with the type the call answers with.
  */
case class TEntry(func: String, argsFn: Option[String],
                  resultFn: Option[String] = None, resultTy: Option[Type] = None)

/** One `@test` function, as the runner needs it (`testing.md`).
 *
 * `func` is the key the function is filed under, which is what makes it reachable and what the
 * dispatcher matches an argument against. Everything else is for the report: what to call the test,
 * whether returning is the outcome it was after, and where to point a reader whose test failed.
 *
 * The position is carried here because it is the *attribute's*, not the function's, and it is the
 * only place a reader can be sent that is certainly about the test rather than about the code under
 * it. A test that failed has no diagnostic of its own — it has an exit status — so this stands in
 * for one.
 */
case class TTest(
    func: String,
    display: String,
    shouldTrap: Boolean,
    expected: Option[String],
    file: String,
    line: Int,
)

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
    entry: Option[TEntry] = None,
    /** Functions a **library** already compiled, which this module calls but must not define
     * (`LibraryArtifact`). They are declared rather than emitted, and the object file the library
     * shipped supplies the body at link time.
     *
     * They are named here rather than turned into `TExtern`s because an `extern` is declared under
     * the **C** convention, and these are sysl functions: the declaration has to be built from the
     * same signature the definition would have had, or the caller passes its arguments the wrong
     * way and the mistake is a corrupt run rather than a link error.
     */
    precompiled: Set[String] = Set.empty,
    /** Whether this module carries the program's entry point. A library does not: it is lowered on
     * its own to be linked into something else, and a `main` of its own would collide with the one
     * belonging to whatever links it.
     */
    entryPoint: Boolean = true,
    /** The modules that declared `no alloc` (`13 §4`). The analyzer has already held each of them to
     * making no heap storage of its own; what this carries the answer forward for is the one
     * allocation no expression in the tree spells — the **promotion** of a local array whose slice
     * outlives its frame, which escape analysis decides after the walk has finished (`05`).
     */
    noAllocModules: Set[String] = Set.empty,
    /** The module whose terms the statements in `main` were written in — the file that carries the
     * program's entry point (`13 §7`). Every other body says which module it belongs to in its own
     * key; these have no key, so the answer is carried here.
     */
    mainModule: String = Modules.root,
    /** The `@test` functions the sources declared, in the order they were written (`testing.md`).
     *
     * They are carried on the program rather than looked up from `funcs` because what a test *is* —
     * its reported name, what it expects — lives in the attribute, and the typed function is the
     * ordinary function it would have been without one. A compilation that is not a test build
     * drops both this and the functions it names: `Tests.strip`.
     */
    tests: List[TTest] = Nil,
    /** The `extern` variables the program reads or writes (`12 §1`). Declared beside the `val`s
     * rather than up with the `extern` functions, because a named aggregate type has to be defined
     * before anything names it — the same ordering the precompiled declarations need.
     */
    externVars: List[TExternVar] = Nil,
    /** Everything declared in a file that said `@tests` — the scaffolding a module's tests are
     * written against (`testing.md`).
     *
     * Keys rather than declarations, because what is carried here is asked of several lists at once:
     * a test file may declare functions, storage and `extern`s, and `Tests.strip` drops all three by
     * the same question. The names are the module-qualified ones every other table uses.
     *
     * A test build keeps them and every other build drops them, which is the whole of what the
     * header said. The analyzer has already held them to the other half — that nothing outside a
     * test names one — so by the time a tree reaches here, dropping them can leave nothing dangling.
     */
    testOnly: Set[String] = Set.empty,
)
