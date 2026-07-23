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
  case object Str  extends Type { def llvm = "ptr" } // ptr to UTF-8 bytes
  case object Unit extends Type { def llvm = "void" }

  /** The types an unsuffixed literal falls back to when nothing else fixes it. */
  val Int: Integer   = Integer(32, signed = true)
  val Real: Floating = Floating(64)

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
    case other                    => other.llvm

  def isNumeric(t: Type): Boolean = t match
    case _: Integer | _: Floating => true
    case _                        => false

  /** Whether `<`, `<=`, `>`, `>=` are defined — the numeric types and `char`. */
  def isOrdered(t: Type): Boolean = isNumeric(t) || t == Char

  /** Whether a literal value is representable in an integer type. Out of range is an error
   * rather than a wrap: the width is the programmer's statement of intent.
   */
  def fits(value: BigInt, t: Integer): Boolean =
    if t.signed then
      val limit = BigInt(1) << (t.bits - 1)
      value >= -limit && value < limit
    else value >= 0 && value < (BigInt(1) << t.bits)

  /** A value struct: fields in declaration order, lowered to a named LLVM aggregate. `targs`
   * is empty for an ordinary struct and holds the instantiation for a generic one.
   */
  case class Struct(base: String, targs: List[Type], fields: List[(String, Type)]) extends Type {
    def name: String = qualified(base, targs)

    def llvm: String = s"%struct.${mangled(base, targs)}"

    def fieldIndex(field: String): Int = fields.indexWhere(_._1 == field)

    def fieldType(field: String): Option[Type] = fields.find(_._1 == field).map(_._2)
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
  case class Enum(base: String, targs: List[Type], simple: Boolean, variants: List[EnumVariant]) extends Type {
    def name: String = qualified(base, targs)

    def llvm: String = if simple then "i32" else s"%enum.${mangled(base, targs)}"

    def variant(v: String): Option[EnumVariant] = variants.find(_.name == v)

    /** The payload aggregate type name for a data variant, e.g. `%Shape.Circle`. */
    def payloadLlvm(v: EnumVariant): String = s"%${mangled(base, targs)}.${v.name}"
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

  private def mangleOne(t: Type): String = t match
    case s: Struct => mangled(s.base, s.targs)
    case e: Enum   => mangled(e.base, e.targs)
    case other     => show(other)

  /** How a type is written in a diagnostic: the friendly alias where one exists (`int`,
   * `byte`, `real`), the canonical width spelling otherwise (`i5`, `u12`, `f32`).
   */
  def show(t: Type): String = t match
    case s: Struct => s.name
    case e: Enum   => e.name
    case other     => friendly.getOrElse(other, canonicalName(other))
}
