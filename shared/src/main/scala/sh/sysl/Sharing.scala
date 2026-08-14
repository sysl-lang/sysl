package sh.sysl

/** `06 § &sync T` — what an object reached through an atomic reference may contain.
 *
 * `&sync T` makes the **reference** safe to hold in two concurrency domains at once. That is a
 * promise about the count in `T`'s own box and about nothing else, so it is sound only when every
 * count the object reaches is atomic too: a domain holding the object holds everything the object
 * holds, and one ordinary count anywhere inside it is the same race one level down.
 *
 * The walk stops at two things. A `&sync U` is a leaf, because whatever *that* type reaches was
 * settled where it was written — the same argument that makes `&T` a leaf for ARC. A `*U` is a leaf
 * because it carries no count at all: it is the unsafe tier, and `06 § How strong this is` names it
 * as one of the two greppable ways to share on purpose.
 *
 * This is the strict half of the chapter's structural rule, and it is asked in **two** places. The
 * first is where a `&sync T` is written. The second is a `@crossing` parameter, where a facility
 * says that what is passed there reaches another domain — and the strict half is the right one to
 * ask there, because a parameter copies nothing: the crossable list's leniency towards a view is
 * about a channel that copies its bytes, and its leniency towards a `*T` is exactly what the
 * annotation suspends.
 *
 * The other half — which values may **cross by being copied** — is enforced at a channel, and
 * channels are library surface the chapter defers.
 */
object Sharing {

  /** What stops an object being shareable: the field path that reaches it, and its type. An empty
   * path means the object asked about is itself the thing in the way.
   */
  case class Part(path: List[String], ty: Type)

  /** The part of `t` that may not be shared, or `None` when every count it reaches is atomic. */
  def unshareable(t: Type): Option[Part] = walk(t, Nil)

  private def walk(t: Type, path: List[String]): Option[Part] = t match
    case Type.Ref(_, true) | _: Type.Ptr => None

    case _: Type.Ref | _: Type.Weak | _: Type.View => Some(Part(path.reverse, t))

    // An array *is* its elements, so sharing one shares each of them; the path stops at the field,
    // since naming the element is what the reported type already does.
    case Type.Array(_, elem) => walk(elem, path)

    case c: Type.Constrained => walk(c.base, path)

    case s: Type.Struct => s.fields.iterator.flatMap((n, ft) => walk(ft, n :: path)).nextOption()

    // A data enum shares one payload region, so every variant's fields are reachable from it.
    case e: Type.Enum =>
      e.variants.iterator
        .flatMap(v => v.fields.iterator.flatMap((n, ft) => walk(ft, s"${v.name}($n)" :: path)))
        .nextOption()

    // A type parameter promises nothing about its counts, and nothing is decided here: the
    // instantiation resolves this type again with an argument in its place, and that is where the
    // question has an answer. A trait object is the same case one step further — the type it forgot
    // is known where a value is erased into one, and that is where it is asked.
    case _ => None

  /** Why a `&sync T` may not be formed, or `None` when it may. The complaint names the part in the
   * way and what to do about it — a plain reference has an atomic spelling to reach for, a weak one
   * has none yet, and a view has a distinction the language cannot draw.
   */
  def complaint(inner: Type): Option[String] =
    unshareable(inner).map { case Part(path, ty) =>
      val named = !Closures.literal(inner)

      val subject =
        if named then s"'&sync ${Type.show(inner)}' may be reached from two domains at once, so " +
          "every count inside it has to be atomic"
        else "a closure shared between two domains may be called from either, so every count it " +
          "captures has to be atomic"

      val where =
        if path.isEmpty && ty == inner then s"but it is a '${Type.show(ty)}'"
        else if path.isEmpty then s"but it holds a '${Type.show(ty)}'"
        else if named then s"but its '${path.mkString(".")}' reaches a '${Type.show(ty)}'"
        else s"but the '${path.mkString(".")}' it captures reaches a '${Type.show(ty)}'"

      remedy(s"$subject — $where, ", ty)
    }

  /** Why a value may not be handed to a `@crossing` parameter, or `None` when it may
   * (`06 § Marking a domain boundary`).
   *
   * `subject` is the caller's words for what is crossing — the argument, or what the argument points
   * at — because the two shapes a boundary takes are worth telling apart at the call and are not
   * something this walk can see. Everything after it is the same sentence a `&sync T` gets, and
   * deliberately so: the question being asked is the same one, and the answer a reader needs is the
   * spelling that would have been sound.
   */
  def crossing(subject: String, crossed: Type): Option[String] =
    unshareable(crossed).map { case Part(path, ty) =>
      val where =
        if path.isEmpty && ty == crossed then s"but it is a '${Type.show(ty)}'"
        else if path.isEmpty then s"but it holds a '${Type.show(ty)}'"
        else s"but its '${path.mkString(".")}' reaches a '${Type.show(ty)}'"

      remedy(s"$subject reaches another concurrency domain, so every count inside it has to be " +
        s"atomic — $where, ", ty)
    }

  /** What to do about the part in the way: a plain reference has an atomic spelling to reach for, a
   * weak one has none yet, and a view has a distinction the language cannot draw.
   */
  private def remedy(head: String, ty: Type): String = ty match
    case Type.Ref(i, _) =>
      head + s"whose count is not. Hold it as a '&sync ${Type.show(i)}' ('06')"

    case _: Type.Weak =>
      head + "and a weak count has no atomic form yet ('06 § Deferred')"

    case _ =>
      val what = if ty == Type.Str then "bytes" else "elements"

      head + s"which owns its $what through a count that is not atomic. A literal's $what are " +
        s"immortal and would be safe to share, but nothing in the type says whether these are, " +
        "so a shared object holds no view at all ('06')"
}
