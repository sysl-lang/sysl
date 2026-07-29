package io.github.edadma.sysl

/** Closures (`12 §5`–`§8`): the arrow literal, what it captures, and the type it inhabits.
 *
 * **A closure is a struct and an `impl`, and that is the whole reduction.** The struct's fields are
 * the variables the body names from the scope it was written in; the `impl` is of the call trait for
 * the closure's arity, and its one member is the body with those names read off `self`. Everything
 * downstream then already works and is not asked to learn anything: layout, ARC, monomorphization,
 * trait objects, and the two calling conventions of `§6` are the static and dynamic halves the
 * language already had.
 *
 * What is genuinely new is here and is only this — deciding which names are captured, building the
 * two declarations, and analyzing the body in a scope that is neither the enclosing function's nor a
 * top-level member's but is written in terms of both.
 */
trait Closures extends CallAnalysis {

  /** A closure literal. The parameter types come from the context asking for a callable, and the
   * result comes from the body — never the other way round, so a closure is analyzed once and what
   * it yields is what it yields.
   */
  protected def analyzeLambda(l: Lambda, expected: Option[Type]): TExpr =
    err("a closure literal has nothing to be yet")
}
