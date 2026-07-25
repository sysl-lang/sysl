package io.github.edadma.sysl

/** A sysl type, as resolved by the analyzer: the scalar table of
 * `01-scalar-types-and-operators.md` plus value structs and enums, carrying just enough to
 * drive instruction selection in codegen. It grows toward the memory-mode qualifiers.
 *
 * Generic types are *monomorphic* here: a named type carries the type arguments it was
 * instantiated with, so `Box[int]` and `Box[real]` are two distinct `Struct` values with
 * distinct LLVM names. Nothing in this ADT is ever left with an unresolved type parameter.
 */
sealed trait Type {

  /** The LLVM type this lowers to. */
  def llvm: String
}

object Type {

  /** The pointer width of the target, which is what `usize` and `isize` measure. Bring-up
   * compiles for the 64-bit host only; a target description will own this.
   */
  val pointerBits: Int = 64

  /** An integer type of any width. Arithmetic wraps at `bits` and never promotes, so the
   * width is part of the type rather than a property of the operation.
   *
   * `pointerWidth` marks `usize` / `isize`. They are pointer-width *by definition* on every
   * target, which makes them distinct types from the fixed-width integer that happens to
   * match on this one — converting between the two is a cast the programmer writes.
   */
  case class Integer(bits: Int, signed: Boolean, pointerWidth: Boolean = false) extends Type {
    def llvm: String = s"i$bits"
  }

  /** An IEEE binary floating-point type: `f16`, `f32`, `f64`. A closed set, not a family. */
  case class Floating(bits: Int) extends Type {
    def llvm: String = bits match
      case 16 => "half"
      case 32 => "float"
      case _  => "double"
  }

  /** A Unicode scalar value. Layout-compatible with `u32` but not type-compatible: it has
   * equality and ordering and no arithmetic at all, so reaching a codepoint means casting.
   */
  case object Char extends Type { def llvm = "i32" }

  case object Bool extends Type { def llvm = "i1"  }
  case object Unit extends Type { def llvm = "void" }

  /** The type of something whose real type could not be worked out, because the thing that would
   * have decided it was already reported as an error.
   *
   * It exists only so the analyzer can keep going after a mistake: a `var` whose initializer
   * failed still binds its name, at this type, so the rest of the function reads as the
   * programmer wrote it instead of dissolving into "undefined name". Nothing of this type ever
   * reaches codegen — a program with an error is never lowered — and touching a value of it
   * raises `Poisoned`, which abandons the statement without reporting a second time.
   */
  case object Unknown extends Type { def llvm = "void" }

  /** `*T` — a bare machine address: no length, no refcount, no checks, and a lifetime the
   * programmer keeps track of. The one unsafe primitive, and the reason it is spelled with a
   * sigil is so a reader can find every place a program takes on C's risks.
   */
  case class Ptr(inner: Type) extends Type { def llvm = "ptr" }

  /** `&T` — a reference to a reference-counted heap object, and `&sync T` when its refcount is
   * atomic so the reference may cross a concurrency domain. The two are distinct types with no
   * conversion either way: atomicity is fixed when the object is allocated.
   */
  case class Ref(inner: Type, sync: Boolean) extends Type { def llvm = "ptr" }

  /** `[N]T` — N elements of `T`, laid out end to end with no header. An array *is* its
   * elements: copying one copies all of them, and its length is part of its type, which is what
   * lets every index be checked against a constant.
   */
  case class Array(length: Int, elem: Type) extends Type {
    def llvm: String = s"[$length x ${elem.llvm}]"
  }

  /** A view of elements someone else owns: the reference that keeps the storage alive, the first
   * element, and how many there are. Every view has that same layout, so the element type shows
   * up only in the instructions that reach through it — which is what lets a slice and a string
   * share one implementation.
   */
  sealed trait View extends Type {
    def elem: Type

    def llvm: String = "{ ptr, ptr, i64 }"
  }

  /** `[]T` — a view of any elements at all. */
  case class Slice(elem: Type) extends View

  /** A view of bytes that are well-formed UTF-8 and stay that way: the same three words a slice
   * is, minus the ability to write through it. The validity invariant is what separates the two
   * types, so converting a `[]u8` to a `string` is checked and the other direction is free.
   */
  case object Str extends View { def elem: Type = Byte }

  /** The element type of whatever a subscript may be applied to. */
  def element(t: Type): Option[Type] = t match
    case Array(_, e) => Some(e)
    case v: View     => Some(v.elem)
    case _           => None

  /** The type a `*T` or `&T` points at, for the one level of automatic dereference that field
   * selection performs.
   */
  def pointee(t: Type): Option[Type] = t match
    case Ptr(inner)    => Some(inner)
    case Ref(inner, _) => Some(inner)
    case _             => None

  /** The types an unsuffixed literal falls back to when nothing else fixes it. */
  val Int: Integer   = Integer(32, signed = true)
  val Real: Floating = Floating(64)

  val Byte:  Integer = Integer(8, signed = false)
  val Usize: Integer = Integer(pointerBits, signed = false, pointerWidth = true)
  val Isize: Integer = Integer(pointerBits, signed = true, pointerWidth = true)

  /** The scalar type names that are not systematic — the pointer-width pair, the
   * non-numeric primitives, and the friendly aliases over the common integer and float
   * widths. The `iN` / `uN` / `fN` spellings are recognised by width instead, so the open
   * integer family needs no table.
   */
  val scalars: Map[String, Type] = Map(
    "bool"   -> Bool,
    "char"   -> Char,
    "string" -> Str,
    "unit"   -> Unit,
    "usize"  -> Usize,
    "isize"  -> Isize,
    "int"    -> Integer(32, signed = true),
    "short"  -> Integer(16, signed = true),
    "long"   -> Integer(64, signed = true),
    "byte"   -> Integer(8, signed = false),
    "ushort" -> Integer(16, signed = false),
    "uint"   -> Integer(32, signed = false),
    "ulong"  -> Integer(64, signed = false),
    "real"   -> Floating(64),
  )

  /** The alias a diagnostic prefers when a width has a friendly name. */
  private val friendly: Map[Type, String] =
    scalars.collect { case (name, t: Integer) if !t.pointerWidth => (t, name) } ++
      Map(Floating(64) -> "real")

  private def canonicalName(t: Type): String = t match
    case Integer(_, signed, true) => if signed then "isize" else "usize"
    case Integer(bits, signed, _) => (if signed then "i" else "u") + bits
    case Floating(bits)           => s"f$bits"
    case Char                     => "char"
    case Bool                     => "bool"
    case Str                      => "string"
    case Unit                     => "unit"
    case Unknown                  => "?"
    case Ptr(inner)               => s"*${show(inner)}"
    case Ref(inner, sync)         => s"&${if sync then "sync " else ""}${show(inner)}"
    case Array(n, elem)           => s"[$n]${show(elem)}"
    case Slice(elem)              => s"[]${show(elem)}"
    case other                    => other.llvm

  def isNumeric(t: Type): Boolean = t match
    case _: Integer | _: Floating => true
    case _                        => false

  /** Whether `<`, `<=`, `>`, `>=` are defined — the numeric types, `char`, and `string`, which
   * orders by its bytes and so, being well-formed UTF-8, by codepoint.
   */
  def isOrdered(t: Type): Boolean = isNumeric(t) || t == Char || t == Str

  /** Whether `==` and `!=` are defined. Everything ordered, plus the types that have equality
   * without an ordering: `bool`, and the two pointer-shaped modes, which compare by address.
   */
  def isEquatable(t: Type): Boolean = t match
    case Bool | _: Ptr | _: Ref => true
    case _                      => isOrdered(t)

  /** Whether a literal value is representable in an integer type. Out of range is an error
   * rather than a wrap: the width is the programmer's statement of intent.
   */
  def fits(value: BigInt, t: Integer): Boolean =
    if t.signed then
      val limit = BigInt(1) << (t.bits - 1)
      value >= -limit && value < limit
    else value >= 0 && value < (BigInt(1) << t.bits)

  /** A type the programmer declared and may hang members off: a struct or an enum.
   *
   * Both are *nominal* — identified by the name they were declared under together with the type
   * arguments this instantiation was made with — and that identity is what member lookup, trait
   * conformance, and the mangled name of a lowered member all key on. Nothing about the two
   * layouts is shared, so this carries only the identity, which is exactly the part the analyzer
   * resolves members through: a method on an enum is found the same way a method on a struct is.
   */
  sealed trait Named extends Type {

    /** The name the type was declared under, with no type arguments applied. */
    def base: String

    /** The type arguments this instantiation was made with; empty for a non-generic type. */
    def targs: List[Type]

    /** The instantiation as a diagnostic spells it: `Point`, `Option[int]`. */
    def name: String
  }

  /** A value struct: fields in declaration order, lowered to a named LLVM aggregate. `targs`
   * is empty for an ordinary struct and holds the instantiation for a generic one.
   *
   * `fields` is filled in *after* the instantiation is registered, because a struct may reach
   * itself through a `*T` or `&T` field and resolving that field has to find the instantiation
   * already in place. Identity is therefore `(base, targs)` — the display name identifies the
   * instantiation, and the field list is a consequence of it rather than part of it.
   */
  final class Struct(val base: String, val targs: List[Type]) extends Named {
    var fields: List[(String, Type)] = Nil

    def name: String = qualified(base, targs)

    def llvm: String = s"%struct.${mangled(base, targs)}"

    def fieldIndex(field: String): Int = fields.indexWhere(_._1 == field)

    def fieldType(field: String): Option[Type] = fields.find(_._1 == field).map(_._2)

    override def equals(other: Any): Boolean = other match
      case s: Struct => s.base == base && s.targs == targs
      case _         => false

    override def hashCode: Int  = (base, targs).hashCode
    override def toString: String = s"Struct($name)"
  }

  /** One variant of an enum.
   *
   *   - `tag` is the discriminant: a simple enum's integer value, or a data enum's 0-based
   *     variant index.
   *   - `fields` are the variant's payload (empty for a nullary variant).
   *   - `payloadSlot` is the index of this variant's payload inside the enum aggregate, present
   *     only for data variants that carry fields.
   */
  case class EnumVariant(name: String, tag: Int, fields: List[(String, Type)], payloadSlot: Option[Int])

  /** An enum. A *simple* enum (every variant dataless) lowers to `i32`; a *data* enum lowers to
   * a value aggregate `{ i32 tag, payload₁, payload₂, … }` with one payload slot per
   * data-carrying variant. The payload for variant `V` is the named aggregate `%Name.V`.
   */
  final class Enum(val base: String, val targs: List[Type]) extends Named {
    var simple: Boolean            = true
    var variants: List[EnumVariant] = Nil

    /** A simple enum's storage type — its `: iN` annotation, or `int` when unspecified. A data
     * enum lowers to an aggregate and ignores this; its internal tag is always `i32`.
     */
    var underlying: Integer = Type.Int

    def name: String = qualified(base, targs)

    def llvm: String = if simple then underlying.llvm else s"%enum.${mangled(base, targs)}"

    def variant(v: String): Option[EnumVariant] = variants.find(_.name == v)

    /** The payload aggregate type name for a data variant, e.g. `%Shape.Circle`. */
    def payloadLlvm(v: EnumVariant): String = s"%${mangled(base, targs)}.${v.name}"

    override def equals(other: Any): Boolean = other match
      case e: Enum => e.base == base && e.targs == targs
      case _       => false

    override def hashCode: Int    = (base, targs).hashCode
    override def toString: String = s"Enum($name)"
  }

  /** The source-level spelling of an instantiated named type: `Box`, `Result[int, string]`. */
  def qualified(base: String, targs: List[Type]): String =
    if targs.isEmpty then base else s"$base[${targs.map(show).mkString(", ")}]"

  /** An LLVM-safe name for an instantiation: `Result[int, string]` becomes
   * `Result.int.string`. Every name has a fixed arity, so flattening the arguments this way
   * stays unambiguous while keeping the emitted IR readable.
   */
  def mangled(base: String, targs: List[Type]): String =
    if targs.isEmpty then base else s"$base.${targs.map(mangleOne).mkString(".")}"

  /** One type's contribution to a mangled name, which is also how a runtime helper for a type
   * is named (`@arc.release.Node`, `%arc.Option.int`).
   */
  def mangle(t: Type): String = mangleOne(t)

  /** A memory mode is mangled as a word rather than its sigil, since `*` and `&` are not
   * LLVM-name characters.
   */
  private def mangleOne(t: Type): String = t match
    case n: Named         => mangled(n.base, n.targs)
    case Ptr(inner)       => s"ptr.${mangleOne(inner)}"
    case Ref(inner, false) => s"ref.${mangleOne(inner)}"
    case Ref(inner, true)  => s"sync.${mangleOne(inner)}"
    case Array(n, elem)    => s"arr$n.${mangleOne(elem)}"
    case Slice(elem)       => s"slice.${mangleOne(elem)}"
    case other            => show(other)

  /** How a type is written in a diagnostic: the friendly alias where one exists (`int`,
   * `byte`, `real`), the canonical width spelling otherwise (`i5`, `u12`, `f32`).
   */
  def show(t: Type): String = t match
    case n: Named => n.name
    case other    => friendly.getOrElse(other, canonicalName(other))
}
