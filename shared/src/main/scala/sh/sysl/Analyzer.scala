package sh.sysl

/** The semantic pass: it resolves names, checks types, and turns the untyped `Program` into
 * a typed `TProgram` that codegen lowers directly. All diagnostics live here; codegen trusts
 * the tree it is handed.
 *
 * The work is split across traits mixed into this class, the way codegen is split across
 * `Emitter` and friends: `AnalyzerBase` holds the shared tables and name scopes, `TypeResolution`
 * resolves and instantiates types, `Literals` handles the scalar leaves, `Hoisting` registers
 * declarations, `StmtAnalysis` handles statements and blocks, `CallAnalysis` handles calls and
 * construction, `PatternAnalysis` handles `match`, `SpecialForms` holds the handful of call forms
 * the compiler resolves by name, `ProgramWalk` runs the passes in order and checks function bodies,
 * and `ExprAnalysis` is the expression dispatch and places.
 *
 * What stays in this file is the class that mixes them together and the entry point that runs it.
 * The recursive entry points the traits call back into — `analyzeExpr`, `analyzePlace`,
 * `analyzeBlockBody` — are declared abstract in `AnalyzerBase` and implemented in whichever trait
 * owns them, so a trait may call a form it does not itself analyze.
 *
 * Declarations are hoisted, so functions, structs, and enums may be used before they appear
 * and may be mutually recursive. Each function (and the synthetic `main` around the top-level
 * statements) is its own naming context: a variable that shadows an outer one is renamed to a
 * unique register name, which keeps codegen's per-function SSA names distinct without the
 * analyzer having to understand LLVM.
 *
 * **Generics are monomorphized here.** A generic declaration is kept in its untyped form and
 * instantiated on demand: each distinct set of type arguments produces its own `Type.Struct` /
 * `Type.Enum` / `TFunc` under a mangled name, and codegen never sees a type parameter. Type
 * arguments are inferred from the argument types at a call or construction, and from the
 * *expected* type when the arguments alone do not determine them — which is what lets `None`
 * and `Ok(5)` take their type from the context they appear in.
 */
class Analyzer private (
    protected val units: List[Program],
    protected val building: Set[String],
    protected val std: Stdlib,
    protected val target: Target,
    protected val provides: Set[String],
    protected val packages: Packages,
) extends ProgramWalk with ExprAnalysis {

  /** Every error the walk found, rendered and in source order. */
  def errors: List[String] = diagnostics
}

object Analyzer {

  /** Analyzes a program to a typed tree, or returns every error it found, rendered and in source
   * order.
   *
   * The walk itself never stops at the first mistake — each declaration, function body, and
   * statement is a recovery region — so what comes back on the left is the whole list. An error
   * escaping the regions entirely is still caught here, since a diagnostic that reaches the user
   * beats a stack trace.
   */
  def analyze(program: Program): Either[String, TProgram] = analyze(List(program))

  /** Analyzes the files of one module together. They share a single scope, so a declaration in one
   * is visible to all of them with no ordering and no forward declaration (`13 §6`) — which falls
   * out of hoisting, since the pass that registers every signature already runs over the whole set
   * before any body is checked.
   */
  /** `target` is here for the reason it is a parameter everywhere else: a few rules are the
   * *machine's* rather than the language's, and a diagnostic about one has to be raised where
   * diagnostics are raised. `15 §10`'s calling conventions are the case — whether `interrupt` exists
   * at all, and what signature it demands, differ per processor.
   */
  def analyze(units: List[Program], building: Set[String] = Set.empty,
              std: Stdlib = Stdlib.fromSource(Target.default), target: Target = Target.default,
              provides: Set[String] = Capability.core.toSet, packages: Packages = Packages.none)
      : Either[String, TProgram] = {
    val analyzer = new Analyzer(units, building, std, target, provides, packages)

    val outcome =
      try Right(analyzer.analyze())
      catch
        case AnalyzerError(msg, pos) => Left(List(Diagnostic.render(msg, pos)))
        // A poisoned region carries no message of its own: it means an error was already
        // recorded, and those are what the caller is told about.
        case Poisoned() => Left(Nil)

    val found = analyzer.errors

    outcome match
      case Right(tree) if found.isEmpty => Right(tree)
      case Right(_)                     => Left(Diagnostic.report(found))
      case Left(escaped) =>
        val all = found ::: escaped

        // Reaching here with nothing to say would mean the analyzer gave up without recording
        // why, which is a bug in the analyzer rather than in the program it was handed.
        if all.isEmpty then Left(Diagnostic.render("the analyzer stopped without reporting why", None))
        else Left(Diagnostic.report(all))
  }
}

