package sh.sysl

/** What a `[T, U: Show, V = int]` list says, kept as one value because every generic declaration
  * parses the same list and hands all three parts to the node it builds. A parameter carrying
  * neither a bound nor a default is simply absent from both maps.
  *
  * **A parameter may stand for a value rather than a type** (`reference/generics.md § A parameter
  * may stand for a value`), written `[const N: usize]`. Those share `names` with the type
  * parameters, because they share one list, one namespace and one argument position — what marks
  * one out is an entry in `values` giving the type its argument must have. A value parameter
  * carries no bound (a bound is a trait, and a value does not implement one) and its default, where
  * it has one, is an expression rather than a type.
  */
case class TypeParams(
    names: List[String],
    bounds: Map[String, List[BoundRef]] = Map.empty,
    defaults: Map[String, TypeRef] = Map.empty,
    values: Map[String, TypeRef] = Map.empty,
    valueDefaults: Map[String, Expr] = Map.empty,
    packs: Set[String] = Set.empty,
)

object TypeParams {
  val none: TypeParams = TypeParams(Nil)
}

/** One entry of a `[…]` parameter list, before the list is folded into `TypeParams`. The two shapes
  * are kept apart here rather than in maps because the grammar reading them is what tells them
  * apart, and a fold that had to guess would be the ambiguity `const` exists to remove.
  */
sealed trait ParamSpec { def name: String }

case class TypeParamSpec(name: String, bounds: List[BoundRef], default: Option[TypeRef])
    extends ParamSpec

case class ValueParamSpec(name: String, typ: TypeRef, default: Option[Expr]) extends ParamSpec

/** `..A: Display` — a parameter standing for a **list** of types (`reference/generics.md § A
  * parameter may stand for a list of types`). Its bound distributes over the members, which is why
  * the bounds go in the same map a type parameter's do: everything downstream that asks what a name
  * is bounded by gets the same answer, and only the walk that matches a subject needs to know this
  * one is a pack.
  */
case class PackParamSpec(name: String, bounds: List[BoundRef]) extends ParamSpec
