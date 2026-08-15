package sh.sysl

/** Types **as written** — the surface grammar of a type, before anything has resolved it.
 *
 * A `TypeRef` says what a program spelled; a `Type` (`Type.scala`) says what that turned out to
 * mean. The two are kept apart because a diagnostic raised before resolution can only repeat the
 * spelling it was given, and because the same spelling means different things under different
 * substitutions.
 */

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
    case VectorType(IntLit(n, _), e)         => s"<$n>${e.show}"
    case VectorType(Ident(n), e)             => s"<$n>${e.show}"
    case VectorType(_, elem)                 => s"<…>${elem.show}"
    case VolatileType(inner)              => s"volatile ${inner.show}"
    case TupleType(parts, false)          => s"(${parts.map(_.show).mkString(", ")})"
    case TupleType(parts, true)           => parts.map(_.show).mkString(", ")
    case PackType(n)                      => s"..$n"
    case FnType(List(one), ret, true)     => s"${one.show} -> ${ret.show}"
    case FnType(params, ret, true)        => s"(${params.map(_.show).mkString(", ")}) -> ${ret.show}"
    case FnType(params, ret, false)       => s"Fn(${params.map(_.show).mkString(", ")}) -> ${ret.show}"
    case CFnType(params, ret)             => s"*extern(${params.map(_.show).mkString(", ")}) -> ${ret.show}"
    // A value argument is repeated where it can be read back plainly and elided where it cannot,
    // which is what the length of an array already does one line up.
    case ValueArgType(IntLit(v, _))       => v.toString
    case ValueArgType(Ident(n))           => n
    case ValueArgType(_)                  => "…"
}

/** A named type, optionally applied to type arguments: `int`, `Box[int]`,
 * `Result[int, string]`. A bare name may also be a type *parameter* of the enclosing
 * declaration; the analyzer decides which from the substitution in scope.
 */
case class NamedType(name: String, args: List[TypeRef] = Nil) extends TypeRef

/** A **value** argument as it was written — the `4` in `Buf[4]` (`10 §9`).
 *
 * It is a `TypeRef` because it stands in an argument list of them, which is the same reason
 * `Type.ConstArg` is a `Type`: a declaration's parameters are one list and one argument position,
 * whichever kind each parameter is. It is never a type, and nothing that walks a written type does
 * anything with one but pass it along to be folded.
 *
 * Only an argument that could not be a type arrives here. `Buf[N]` parses as a `NamedType` even
 * where `N` is a value parameter, because a bare name is a type as far as the grammar can see — what
 * it means is decided against the declaration, where the parameter's kind is known. Rust resolves a
 * bare path in a const-argument position the same way and for the same reason.
 */
case class ValueArgType(value: Expr) extends TypeRef

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

/** `<N>T` — N lanes of `T`, an array whose operators work on every lane at once.
 *
 * The lane count has no `None` case, which is the one structural difference from `ArrayType` and is
 * the point of not reusing it: `[]T` drops the length because a slice carries its own at run time,
 * and there is no such thing for a vector. A register's width is decided when the code is generated
 * or it is not a register — so a written vector always says how many lanes, and `<>f32` is refused
 * by the grammar rather than by a check further in.
 */
case class VectorType(lanes: Expr, elem: TypeRef) extends TypeRef

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

/** `(A, B)` — a tuple of two or more parts (`00 §13`). One part is never written here: `(T)` is a
 * type in parentheses, and a product of one thing is the thing.
 */
case class TupleType(parts: List[TypeRef], results: Boolean = false) extends TypeRef

/** `..A` — a **type pack**, one name standing for a list of types (`10 §10`).
 *
 * It is a `TypeRef` for the reason `ValueArgType` is one: a declaration's parameters are one list
 * whichever kind each of them is, and a pack stands in that list. It is never a type on its own —
 * the only place it may be written is inside a tuple, as `(..A)`, which is the tuple of whatever the
 * pack was bound to.
 */
case class PackType(name: String) extends TypeRef

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
    /** Written `x: -> T`: the argument is an expression the *call* does not evaluate, and the body
      * evaluates at each use (`12 § A parameter may be passed by name`).
      *
      * It is a property of the **parameter** rather than of its type, and that is the whole of why
      * this is cheap. The type is `Fn() -> T` exactly as `x: () -> T` is, so nothing downstream —
      * the bound, the monomorphization, the absence of an allocation — has a new case to learn. What
      * differs is only how the call site binds it, which is where the desugar lives.
      */
    byName: Boolean = false,
) extends Positioned
