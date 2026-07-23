package io.github.edadma.sysl

/** A sysl type, as resolved by the analyzer. This is the bring-up subset — the scalar
 * defaults plus value structs and enums — carrying just enough to drive instruction selection
 * in codegen. It grows toward the full scalar-type table and the memory-mode qualifiers.
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

  case object Int  extends Type { def llvm = "i32"    } // the `int` default
  case object Real extends Type { def llvm = "double" } // the `real` default
  case object Bool extends Type { def llvm = "i1"     }
  case object Str  extends Type { def llvm = "ptr"    } // ptr to UTF-8 bytes
  case object Unit extends Type { def llvm = "void"   }

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

  /** How a type is written in a diagnostic. */
  def show(t: Type): String = t match
    case s: Struct => s.name
    case e: Enum   => e.name
    case Int       => "int"
    case Real      => "real"
    case Bool      => "bool"
    case Str       => "string"
    case Unit      => "unit"
}
