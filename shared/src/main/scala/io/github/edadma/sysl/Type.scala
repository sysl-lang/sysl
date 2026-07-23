package io.github.edadma.sysl

/** A sysl type, as resolved by the analyzer. This is the bring-up subset — the scalar
 * defaults plus value structs — carrying just enough to drive instruction selection in
 * codegen. It grows toward the full scalar-type table and the memory-mode qualifiers.
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

  /** A value struct: fields in declaration order, lowered to a named LLVM aggregate. */
  case class Struct(name: String, fields: List[(String, Type)]) extends Type {
    def llvm: String = s"%struct.$name"

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
  case class Enum(name: String, simple: Boolean, variants: List[EnumVariant]) extends Type {
    def llvm: String = if simple then "i32" else s"%enum.$name"

    def variant(v: String): Option[EnumVariant] = variants.find(_.name == v)

    /** The payload aggregate type name for a data variant, e.g. `%Shape.Circle`. */
    def payloadLlvm(v: EnumVariant): String = s"%$name.${v.name}"
  }
}
