package sh.sysl

/** The compile-time identity of a type — what `T::Id` answers, and the word every method table
 * carries (`02`).
 *
 * ==It is a hash rather than a counter, and that is forced rather than chosen==
 *
 * A counter over the types a compilation sees is unique where a hash can collide, so a counter is
 * the obvious answer and it is **wrong here**. A method table is a `private` global, so a type
 * erased inside a precompiled library and the same type erased in the program that links it have a
 * table each — and a counter would number them from two different compilations, so two values of one
 * type would answer with two ids. A hash of the type's name is computed the same way by every
 * compilation that ever sees that type, which is the property the feature actually needs.
 *
 * What it costs is the possibility of two types hashing alike. `Analyzer` checks for that within a
 * compilation and refuses, naming both, so the silent case is bounded to two types that never meet
 * in one compilation and collide anyway — which at sixty-four bits is not a thing to plan around.
 *
 * ==What identifies a type here==
 *
 * [[Type.memberSymbol]], which is the identity a type's **members** are filed under, and it is the
 * right one for the same reason: it keeps `Age` apart from `int`. [[Type.mangle]] deliberately does
 * not — a transparent subtype shares its base's representation and mangles as the base — and an id
 * that merged the two would silently merge a cache keyed on it, which is precisely the use this
 * exists for.
 *
 * ==What it guarantees==
 *
 * Equal ids mean the same type, within a compilation. **Nothing else.** It is not stable across
 * releases — the mangling is free to change — it is not a number to persist, and it is not a way
 * back to the type: there is no map from an id to anything, which is what keeps `02 § There is no
 * way back to the type` true.
 */
object TypeId {

  /** FNV-1a over the type's identifying name, finished with MurmurHash3's `fmix64` and narrowed to
   * the target's word.
   *
   * The same construction the standard module's fingerprint uses, and for the same reason: FNV-1a's
   * own diffusion carries a change upward and never back down, so the low bits of the result lean on
   * the low bits of the input — and the low bits are exactly what a narrowing to a 32-bit word keeps.
   */
  def of(t: Type)(using Word): BigInt = ofName(Type.memberSymbol(t))

  /** The same, for the one table the compiler writes by hand rather than from a `Type`.
   *
   * `str(x)` renders through a buffer whose `Writer` implementation is emitted as IR text and has no
   * sysl type behind it — and a user's `display` receives that buffer as a `*Writer`, so `out::Id`
   * on it is a legal question with no type to answer from. The name it is hashed under starts with
   * the module separator, which no type a program can declare does, so the answer cannot collide with
   * one.
   */
  def ofName(name: String)(using w: Word): BigInt = {
    var h = 0xcbf29ce484222325L

    for c <- name do h = (h ^ c.toLong) * 0x100000001b3L

    // Masked rather than truncated by a cast, because the answer is a `usize` and `usize` is
    // unsigned: a `Long` whose top bit is set is a negative number here and a large positive one
    // there, and the literal the analyzer folds has to be in range for the type it carries.
    BigInt(avalanche(h)) & ((BigInt(1) << w.bits) - 1)
  }

  /** MurmurHash3's finalizer, both rounds — one diffuses most of the way and is the easy place to
   * stop, and the second is what its constant was chosen with.
   */
  private def avalanche(h: Long): Long = {
    var x = h

    x ^= x >>> 33
    x *= 0xff51afd7ed558ccdL
    x ^= x >>> 33
    x *= 0xc4ceb9fe1a85ec53L
    x ^= x >>> 33
    x
  }
}
