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
}
