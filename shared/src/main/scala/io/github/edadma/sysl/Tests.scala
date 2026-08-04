package io.github.edadma.sysl

/** The `@test` functions a program declares, and what separates a test build from every other one
 * (`testing.md`).
 *
 * A test is an ordinary function with an attribute on it. That is the whole of the language part:
 * nothing about the body is special, it may call anything the module can reach, and it is analyzed
 * and type-checked exactly as it would be without the line above it. What the attribute buys is a
 * *caller* — `sysl test` builds an entry point that calls one of them by name, and the program's own
 * entry point is not built at all.
 *
 * **A test is not part of the program it is written in.** `sysl run`, `sysl build`, `sysl emit-llvm`
 * and `sysl build-lib` all drop them, and drop them *after* analysis, so a test that does not compile
 * is still a compilation error in a build that would never have run it. That is the one property
 * worth stating twice, because it is what lets a test sit beside what it tests: a library's tests do
 * not travel in the library, and a program's do not run when it runs.
 */
object Tests {

  /** The requirements a `@test` function meets, checked at the declaration.
   *
   * They say the same thing from different sides: **the runner must be able to call it with nothing
   * and learn the answer from whether it returned.** A parameter is something the runner has no value
   * for, and a type parameter leaves nothing to call at all, since a generic has no compiled form
   * until a caller fixes its arguments.
   *
   * A variadic tail needs no case of its own: `12 §9` already requires a named parameter in front of
   * one, so a function that could take a tail has taken a parameter and is refused by that.
   *
   * Each is reported where the attribute is rather than where the signature is, because the attribute
   * is the part that is wrong: the function is a perfectly good function, and it is `@test` that made
   * a promise about it that it cannot keep.
   */
  def problem(f: FuncDecl): Option[String] =
    if f.params.nonEmpty then
      Some(s"a '@test' function takes no parameters, and '${Modules.bare(f.name)}' takes " +
        (if f.params.length == 1 then "one" else s"${f.params.length}") +
        " — 'sysl test' calls it with nothing, so there is nowhere for an argument to come from")
    else if f.tparams.nonEmpty then
      Some(s"a '@test' function has no type parameters, and '${Modules.bare(f.name)}' declares " +
        s"'${f.tparams.mkString(", ")}' — a generic is compiled for the arguments a caller fixes, and " +
        "the runner supplies none")
    else None

  /** A test's result type, which must be `unit` — written as `-> unit` or, as almost every test will,
   * not written at all.
   *
   * Checked against the *resolved* type rather than the syntax so that a result reached through an
   * alias is refused with everything else, and so the message can name what the function actually
   * returns. A test's verdict is whether it came back, and a value returned beside that would be one
   * nothing is going to look at — which is a mistake about how the test reports, and the sort that
   * ends with someone believing an assertion ran.
   */
  def resultProblem(f: FuncDecl, retTy: Type): Option[String] =
    Option.when(!Type.noValue(retTy))(
      s"a '@test' function returns nothing, and '${Modules.bare(f.name)}' returns " +
        s"'${Type.show(retTy)}' — a test's result is whether it came back, so there is nothing to read a " +
        "value with")

  /** What the runner is told about one test: the key that calls it, the name that reports it, and
   * where the attribute was written.
   *
   * The reported name defaults to the function's own **bare** name — the module is already the file
   * the report groups under, so repeating it in every line would be noise. A `@test("…")` string
   * replaces it outright rather than decorating it, which is the point of writing one.
   */
  def describe(key: String, attr: TestAttr): TTest =
    TTest(
      key,
      attr.display.getOrElse(Modules.bare(key)),
      attr.shouldTrap,
      attr.expected,
      attr.pos.map(_.source.name).getOrElse("<unknown>"),
      attr.pos.map(_.line).getOrElse(0),
    )

  /** The same program with every test dropped — the tree a build that is not `sysl test` lowers.
   *
   * Dropping the functions is what keeps a test out of the output; dropping the list is what keeps
   * anything downstream from believing there are tests to dispatch to. Both, because either alone is
   * a tree that contradicts itself.
   *
   * A test's *callees* are not dropped here and do not need to be: a helper only a test calls becomes
   * unreachable the moment the test does, and `Reachability.prune` is what notices. What that leaves
   * is exact — a helper the program also calls stays, because the program still calls it.
   */
  def strip(program: TProgram): TProgram = {
    val tests = program.tests.map(_.func).toSet

    if tests.isEmpty then program
    else program.copy(funcs = program.funcs.filterNot(f => tests(f.name)), tests = Nil)
  }

  /** The same program lowered **as** a test build: the tests kept, and the program's own entry point
   * put aside.
   *
   * A program's top-level statements and its `main` are what it does when it is run, and running it
   * is not what `sysl test` does — so they are dropped, and what remains of the entry point is the
   * dispatcher `Codegen` lays down instead. The module-level `val`s stay: they are storage the
   * program reads rather than work it performs, and a test that reads one would otherwise see it
   * empty.
   *
   * The tests are the **roots**, in place of the `main` that is no longer there, so that everything
   * one of them calls survives the pruning and nothing else does. A program whose tests reach half
   * of it compiles half of it, which is the same bargain every other build gets.
   */
  def only(program: TProgram): TProgram = {
    val kept  = program.copy(main = Nil, entry = None)
    val roots = List(kept.vals, kept.vtables, kept.tests.map(t => TEntry(t.func, None)))
    val live  = Reachability.reachedFrom(roots, kept.funcs, kept.vtables).calls

    kept.copy(
      externs = kept.externs.filter(e => live(e.name)),
      funcs = kept.funcs.filter(f => live(f.name)),
    )
  }
}
