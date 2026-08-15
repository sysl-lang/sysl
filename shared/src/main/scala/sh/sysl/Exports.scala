package sh.sysl

/** The two things about `@export` that only the whole program can answer (`15 §12`).
 *
 * `ExportCheck` holds each declaration to a *shape* C can call, which is a question one declaration
 * answers on its own and is asked where the declarations are. These three are not:
 *
 *   - **whether the parameters and result are types C can spell**, which needs them resolved — and a
 *     short name resolves against the scope its declaration sits in, which the typed tree has
 *     already worked out;
 *   - **whether an exported function reaches a computed module `val`**, which is a question about the
 *     call graph, and so exists only once every body has been analyzed;
 *   - **whether two exports claim one symbol**, which is a question about the set of them.
 *
 * All three run from `Compiler.analyzed`, beside `Escape.check` and `TailCalls.check`, for the reason
 * those do: the typed tree is what they read, and a program that has not got one has nothing for
 * them to say.
 */
object Exports {

  /** `own` names the modules this compilation is building, and it is here because all three
   * questions below are about the **emitted** symbol table. An export in a dependency module the
   * program never reaches is not emitted (`Reachability.contributing`), so holding it to a shape C can
   * spell, or reporting it as the second claimant of a symbol, would be refusing a build over a
   * definition that build does not contain — which is the `main` collision this card was filed for,
   * reappearing as a diagnostic instead of as a link error.
   */
  def check(program: TProgram, own: Option[Set[String]] = None): Either[String, Unit] = {
    val exported = Reachability.exports(program, own)
    val refused  = exported.flatMap(signature) ::: duplicates(exported) ::: names(exported) :::
      storage(exported, program)

    if refused.nonEmpty then Left(Diagnostic.report(refused)) else Right(())
  }

  /** A parameter or a result C has no way to spell.
   *
   * A scalar and a pointer are one register on every machine sysl lowers for, so the only thing to
   * decide about one is whether it is widened on the way — which `CAbi.extension` decides, on the
   * definition itself, and which is therefore not a reason to refuse anything. An aggregate is the
   * opposite: each ABI says which registers a struct arrives in, LLVM applies no rule of its own, and
   * `CAbi` exists precisely because sysl's own lowering and C's published one differ. Passing one by
   * value would be a **corrupt call rather than a link error**, which is why it is refused here
   * instead of lowered hopefully.
   *
   * Every refusal carries the shape to write instead, because there always is one — a slice becomes
   * the pointer and length C's own buffer functions already take, an aggregate becomes a pointer to
   * itself. That is what makes the boundary layer writable rather than merely restricted.
   */
  private def signature(f: TFunc): List[String] = {
    val bad = f.params.filterNot((_, t) => ExportCheck.crosses(t))

    val params = bad.map { (name, t) =>
      Diagnostic.render(
        s"'$name' of the exported '${Modules.show(f.name)}' is ${Type.show(t)}, which C has no way " +
          s"to spell — an exported function takes ${ExportCheck.spellable}. ${ExportCheck.advice(t)}",
        None)
    }

    val result =
      Option.when(!Type.noValue(f.retTy) && !ExportCheck.crosses(f.retTy))(
        Diagnostic.render(
          s"the exported '${Modules.show(f.name)}' returns ${Type.show(f.retTy)}, which C has no way " +
            s"to spell — an exported function returns that or nothing at all. ${ExportCheck.advice(f.retTy)}",
          None))

    params ::: result.toList
  }

  /** Two definitions exporting one symbol.
   *
   * The linker would report this itself, as a duplicate definition, naming a symbol that appears in
   * no sysl file — `mylib_parse` where the reader wrote `parse` twice in two modules. Saying it here
   * costs one grouping and names both declarations, which is the difference between a diagnostic and
   * an archaeology exercise.
   */
  private def duplicates(exported: List[TFunc]): List[String] =
    exported
      .groupBy(_.exported.get)
      .toList
      .sortBy(_._1)
      .collect { case (symbol, fs) if fs.length > 1 =>
        Diagnostic.render(
          s"'$symbol' is exported by ${fs.map(f => s"'${Modules.show(f.name)}'").sorted.mkString(" and ")} " +
            "— one symbol is one definition, and the linker has no way to tell which was meant",
          None)
      }

  /** Two things in one header answering to one name.
   *
   * **This is the property `@export("…")` on a struct takes away, so it is the property this hands
   * back.** A derived name is the mangled instantiation, which is unique because a module path is in
   * it; a chosen one is a claim the author makes, and two of them may agree — or one may land on
   * another struct's derived name. Both are the same mistake, and it is `duplicates` above read at
   * the other kind of declaration.
   *
   * **A function is in the list too, because C has one namespace and not two.** At file scope a
   * `typedef` name and a function name are both ordinary identifiers, so a header carrying
   * `typedef struct { … } add;` beside `add add(…)` is not two declarations that happen to rhyme —
   * it is one name declared twice, and the consumer's compiler says so. Nothing on the sysl side
   * suggests it: the two are a type and a function, which collide nowhere here.
   *
   * The types are `CHeader.aggregates`' rather than every struct in the program, because a name is
   * only claimed where the type actually reaches the header: two modules may each declare a `Point`
   * and name it, and while only one is in an exported signature there is nothing to collide. Asking
   * the renderer is also what keeps the two from disagreeing about what is in the file.
   *
   * A symbol claimed by two *functions* is left to `duplicates`, whose sentence is about the linker
   * and is the better one for that case — hence the group having to hold a type to be reported here.
   */
  private def names(exported: List[TFunc]): List[String] = {
    val types = CHeader.aggregates(exported).map(t => CHeader.cName(t) -> s"the type '${Type.show(t)}'")
    val syms  = exported.map(f => f.exported.get -> s"the function '${Modules.show(f.name)}'")
    val typed = types.map(_._1).toSet

    (types ::: syms)
      .groupBy(_._1)
      .toList
      .sortBy(_._1)
      .collect { case (name, xs) if xs.length > 1 && typed(name) =>
        Diagnostic.render(
          s"'$name' is the C name of ${xs.map(_._2).sorted.mkString(" and ")} — a header declares " +
            "both in one namespace, so a C project including it would see the name twice. Give " +
            "each the name it should carry, '@export(\"...\")'",
          None)
      }
  }

  /** An exported function that reaches a **computed** module `val`.
   *
   * Module storage is filled by the entry point (`13 §7`), and a C project linking this artifact
   * supplies its own `main` — so nothing here runs before the C side calls in, and the storage a
   * computed initializer would have written is whatever the loader left. That is a silent wrong
   * answer rather than a link error, which is why it is refused.
   *
   * **A `val` whose initializer is constant data is fine and is not looked at**, because nothing
   * runs to fill it: `TVal.computed` is exactly that distinction, and a constant tree is written
   * straight into the object file. This is the rule C already has for a static-storage initializer,
   * so a reader arriving from that side needs no explaining — and it is the reason the restriction
   * bites so rarely in practice.
   *
   * The walk is `Reachability`'s, so what it reports is the storage reached *transitively*: the
   * `val` may be three calls down inside the library, and the function that named it is what the
   * author has to look at.
   */
  private def storage(exported: List[TFunc], program: TProgram): List[String] = {
    val computed = program.vals.filter(_.computed).map(_.symbol).toSet

    if computed.isEmpty || exported.isEmpty then Nil
    else
      exported.flatMap { f =>
        val reached = Reachability.reachedFrom(List(f), program.funcs, program.vtables).vals & computed

        Option.when(reached.nonEmpty)(
          Diagnostic.render(
            s"'${Modules.show(f.name)}' is exported and reaches " +
              s"${reached.toList.sorted.map(v => s"'${Modules.show(v)}'").mkString(", ")}, which is " +
              "module storage an initializer fills before the program's own statements run. A C " +
              "project linking this supplies its own 'main', so nothing fills it and the function " +
              "would read whatever the loader left. A module 'val' whose initializer is constant " +
              "data is laid straight into the object file and is fine here — it is a computed one " +
              "that has nowhere to be computed",
            None))
      }
  }
}
