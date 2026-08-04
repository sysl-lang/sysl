package io.github.edadma.sysl

import scala.collection.mutable

/** The proof backend: a module's typed tree written out as **WhyML**, the input language of the Why3
 * platform (`17 §9`).
 *
 * **Why Why3 rather than SMT-LIB.** The goals a program generates are not one prover's shape. Why3
 * splits a verification condition into goals, transforms them, and tries several provers on each;
 * reproducing that is a project rather than a backend, and a single-prover translation would fail on
 * goals that are only hard for the prover it happened to pick.
 *
 * **It reads the typed tree, not the parse tree.** Old sysl's translator read the parse tree because
 * its analyzer wove the contracts into the body around a `__result__`; here `TFunc` keeps
 * `requires`, `ensures` and `variant` beside the body, so the clauses arrive as clauses and the
 * names are already resolved.
 *
 * **Two worlds, and which one a function lands in is decided by its body.** WhyML separates *terms*,
 * which are mathematics and may appear in a `requires`, from *programs*, which have state and may
 * not. A sysl function whose body is an expression translates as a `let function`, which Why3 gives
 * both views of, so a contract may call it; one with a local variable or a loop translates as a
 * plain `let`, and a contract that calls that one is refused by name. `@ghost` functions are always
 * in the first world — a specification is written in mathematics.
 *
 * **Integer overflow is a proof obligation in code and not in specifications**, which is `§9`'s
 * decision and the sharpest thing about this translation. `01` says sysl's plain integer arithmetic
 * wraps and WhyML's `int` is the mathematical integers, so translating `a + b` to `a + b` would
 * prove theorems about a language sysl is not — silently, which is the worst kind of wrong. So in a
 * *program* body every arithmetic operation goes through a checked wrapper whose precondition is
 * that the true result is representable, and a program that stays in range gets the mathematical
 * model, which is exact for it. A *term* keeps plain arithmetic, because a term has nowhere to
 * discharge an obligation and because the specification is the mathematics the code is measured
 * against — `ensure result == old(n) * 2` says what doubling means, not what the machine does.
 */
object WhyML {

  /** A construct this translation has no answer for. It carries the reader's own words for what was
   * written, so a gap in the translator reads as a gap in the translator rather than as a program the
   * prover disliked.
   */
  private class Unsupported(val what: String) extends RuntimeException(what)

  /** How the widths a program used are bounded, keyed by the name the helpers are generated under. */
  private case class Width(name: String, lo: BigInt, hi: BigInt)

  private def widthOf(i: Type.Integer): Width = {
    val name = (if i.signed then "i" else "u") + i.bits
    val lo   = if i.signed then -(BigInt(2).pow(i.bits - 1)) else BigInt(0)
    val hi   = (if i.signed then BigInt(2).pow(i.bits - 1) else BigInt(2).pow(i.bits)) - 1

    Width(name, lo, hi)
  }

  /** Translates the main module of `program` to a WhyML module.
   *
   * `checkOverflow` off drops the range preconditions, which is for somebody who wants to reason
   * about the rest of a function first. It is off by default in the sense that the honest reading of
   * "this program is proved" should not quietly exclude the most common way integer code is wrong,
   * so the CLI's default is on.
   */
  def generate(program: TProgram, moduleName: String, checkOverflow: Boolean = true,
               ownModules: Set[String] = Set.empty): Either[String, String] = {
    val gen = new Generator(program, checkOverflow, ownModules)

    try Right(gen.module(moduleName))
    catch case u: Unsupported => Left(s"the proof backend does not translate ${u.what}")
  }

  private class Generator(program: TProgram, checkOverflow: Boolean, ownModules: Set[String]) {

    /** The functions this module is about: the ones the program's own module declared. A library
     * function is not translated and not called — a call to one is refused by name, which is the
     * honest answer, since proving something about it would mean translating the library too.
     */
    private val own: List[TFunc] = {
      val mine = if ownModules.isEmpty then Set(program.mainModule) else ownModules

      program.funcs.filter(f => mine(Modules.moduleOf(f.name)))
    }

    private val ownNames = own.map(_.name).toSet

    /** Which of them are terms, which is what a contract may call: the `@ghost` ones, and only those.
     *
     * **The first cut asked whether a body *could* be read as a term — one expression, nothing
     * declared — and that was wrong in a way worth writing down.** It pulled ordinary code into the
     * term world, and a term keeps plain arithmetic, so `gcd` written as one expression was proved
     * against unbounded integers while the same function written with a local got its overflow
     * obligations. Two spellings of one function, two models. The mark is what decides now, which is
     * also what `17 §8` already says: a specification is what `@ghost` marks, and mathematics is
     * what a specification is written in.
     */
    private def terms(name: String): Boolean = ghosts(name)

    private val ghosts: Set[String] = own.filter(_.ghost).map(_.name).toSet

    /** The checked-arithmetic helpers this module turned out to need, in the order they were first
     * asked for so the output is stable.
     */
    private val helpers = mutable.LinkedHashMap.empty[String, String]

    /** The locals that are `ref`s, which is every one a body declares. A parameter is not: WhyML
     * parameters are immutable, and a sysl body that assigns to one is refused by name rather than
     * translated into something that would have to shadow it.
     */
    private var refs: Set[String] = Set.empty

    /** The `old(e)` expressions of the function being written, which `TOld` indexes into. */
    private var olds: List[TExpr] = Nil

    // --- names --------------------------------------------------------------------------

    /** A sysl symbol as a WhyML identifier. Module separators and dots become underscores, and a
     * leading capital is lowered, because WhyML reserves an initial capital for types and
     * constructors.
     */
    private def ident(name: String): String = {
      val cleaned = name.map(c => if c.isLetterOrDigit || c == '_' then c else '_')

      if cleaned.isEmpty then "_x"
      else if cleaned.head.isUpper then cleaned.head.toLower.toString + cleaned.tail
      else if cleaned.head.isDigit then "_" + cleaned
      else cleaned
    }

    // --- types --------------------------------------------------------------------------

    private def typ(t: Type): String = Type.underlying(t) match
      case _: Type.Integer => "int"
      case Type.Bool       => "bool"
      case Type.Unit       => "unit"
      case other           => throw Unsupported(s"the type ${Type.show(other)}")

    // --- the module ---------------------------------------------------------------------

    def module(name: String): String = {
      val bodies = own.map(function).mkString("\n")
      val out    = new StringBuilder

      out ++= s"module ${name.capitalize}\n"
      out ++= "  use int.Int\n"
      out ++= "  use int.ComputerDivision\n"
      out ++= "  use ref.Ref\n\n"
      // The helpers are collected while the bodies are written, so they can only be laid down once
      // the bodies are finished — which is why this is assembled rather than streamed.
      helpers.values.foreach(h => { out ++= h; out ++= "\n" })
      out ++= bodies
      out ++= "end\n"
      out.toString
    }

    // --- functions ----------------------------------------------------------------------

    private def function(f: TFunc): String = {
      refs = Set.empty
      olds = f.olds

      val params =
        if f.params.isEmpty then "(_: unit)"
        else f.params.map((n, t) => s"(${ident(n)}: ${typ(t)})").mkString(" ")

      val head = new StringBuilder

      // A `@ghost` declaration is a specification, so it is a term whatever it costs to say — a
      // ghost function with a loop in it is refused rather than translated into a program nothing
      // may mention (`17 §8`).
      val isTerm = f.ghost
      val rec    = if recursive(f) then "rec " else ""

      // **A `@ghost` function that answers `bool` is a `predicate`, not a `bool`-valued function.**
      // WhyML separates formulas from values: `forall i. …` is a formula and has no type at all, so
      // `let function small (n: int) : bool = forall …` is a syntax error rather than a translation
      // that merely proves something else. `predicate` is the form whose body is a formula, and a
      // specification is formulas — which is why this falls out of `@ghost` rather than needing a
      // rule of its own.
      val predicate = isTerm && Type.underlying(f.retTy) == Type.Bool
      val kind      = if predicate then s"${rec}predicate" else if isTerm then s"let ${rec}function"
                      else s"let $rec"

      head ++= (if predicate then s"  $kind ${ident(f.name)} $params\n"
                else s"  $kind ${ident(f.name)} $params : ${typ(f.retTy)}\n")

      // **What the parameter's type already says.** WhyML's `int` is unbounded and a sysl `int` is
      // not, so without this every range obligation inside the body is about an argument that could
      // be any integer at all — and `half(x) = x / 2` with `x >= 0` fails to prove that its own
      // division stays in range, which is not a fact about `half`. This is not an extra demand on the
      // caller: it is the fact that the argument had the type it was declared with.
      //
      // A `@ghost` function gets none of it. Its parameters are mathematics, and a precondition on a
      // logic symbol is an obligation at every use of it in a term.
      //
      // `--overflow=ignore` drops these too, and it has to: they are the same model as the checked
      // operations, so keeping the ranges while dropping the obligations would leave a function
      // promising a result it is no longer made to stay inside.
      if !isTerm && checkOverflow then
        for (n, t) <- f.params do
          Type.underlying(t) match
            case i: Type.Integer =>
              val w = widthOf(i)
              head ++= s"    requires { ${w.lo} <= ${ident(n)} <= ${w.hi} }\n"
            case _ => ()

      for (c, _) <- f.requires do head ++= s"    requires { ${term(c)} }\n"

      // And what the result's type says, which is the other half: a caller reading a call needs it
      // as much as this body needed the parameters'.
      if !isTerm && checkOverflow then
        Type.underlying(f.retTy) match
          case i: Type.Integer =>
            val w = widthOf(i)
            head ++= s"    ensures  { ${w.lo} <= result <= ${w.hi} }\n"
          case _ => ()

      for (c, _) <- f.ensures do head ++= s"    ensures  { ${term(c)} }\n"
      for v <- f.variant do head ++= s"    variant  { ${term(v)} }\n"

      val body = if isTerm then termBody(f) else programBody(f)

      s"$head  = $body\n"
    }

    private def recursive(f: TFunc): Boolean = calls(f.body).contains(f.name)

    private def calls(x: Any): Set[String] = x match
      case _: Type                => Set.empty
      case TCall(name, _, _, _)   => Set(name) ++ calls(x.asInstanceOf[TCall].args)
      case xs: Iterable[?]        => xs.flatMap(calls).toSet
      case p: Product             => p.productIterator.flatMap(calls).toSet
      case _                      => Set.empty

    /** A body that is one expression, which is what a `@ghost` function's has to be. */
    private def termBody(f: TFunc): String = {
      refs = Set.empty
      f.body match
        case TBlock(Nil, Some(r), _) => term(r)
        case _ =>
          throw Unsupported(s"'${Modules.show(f.name)}', a '@ghost' function whose body declares or " +
            "does something rather than being one expression — a specification is mathematics, so " +
            "it may not declare a variable or run a loop")
    }

    private def programBody(f: TFunc): String = block(f.body)

    // --- terms --------------------------------------------------------------------------

    /** An expression as a WhyML **term**: mathematics, with no state and no obligations.
     *
     * This is what a contract is written in, and it is why arithmetic here is plain: a term has
     * nowhere to discharge an overflow obligation, and a specification is the mathematics the code
     * is measured against rather than a second account of what the machine does.
     */
    private def term(e: TExpr): String = expr(e, inTerm = true)

    /** The same expression as a WhyML **program**, where arithmetic carries its range obligation. */
    private def code(e: TExpr): String = expr(e, inTerm = false)

    private def expr(e: TExpr, inTerm: Boolean): String = e match {
      case TIntLit(v, _)  => if v < 0 then s"($v)" else v.toString
      case TBoolLit(b)    => b.toString
      case TUnitLit()     => "()"

      case TLoad(n, _) => if refs(n) then s"!${ident(n)}" else ident(n)

      case TResult(_) => "result"

      case TOld(i, _) =>
        if i >= olds.length then throw Unsupported("an 'old' the translator lost track of")
        s"(old ${expr(olds(i), inTerm)})"

      case TBinary(op, l, r, ty) => binary(op, l, r, ty, inTerm)

      case TUnary("!", o, _) => s"(not ${expr(o, inTerm)})"
      case TUnary("-", o, ty) =>
        Type.underlying(ty) match
          case i: Type.Integer if !inTerm && checkOverflow =>
            s"(${helper("neg", i)} ${expr(o, inTerm)})"
          case _: Type.Integer => s"(- ${expr(o, inTerm)})"
          case other           => throw Unsupported(s"unary '-' on ${Type.show(other)}")

      // A term is always a formula position in this translation — a contract clause, a loop's
      // invariant, or a `predicate` body — so the connectives are the logical ones. A program keeps
      // WhyML's lazy operators, which are what an `if` in code is written with.
      case TLogical(op, l, r) =>
        val o = (op, inTerm) match
          case ("&&", true)  => "/\\"
          case ("&&", false) => "&&"
          case (_, true)     => "\\/"
          case _             => "||"
        s"(${expr(l, inTerm)} $o ${expr(r, inTerm)})"

      // A chain shares its middle operands, and in this fragment every operand is a pure expression,
      // so writing each one twice means what the chain means. An operand that could do something
      // would not have reached here — a call to a program function in a term is refused, and one in
      // code is refused inside a comparison chain for the same reason.
      case TCompare(operands, cmps) =>
        val parts =
          operands.sliding(2).toList.zip(cmps).map { (pair, cmp) =>
            if cmp.dispatch.isDefined then
              throw Unsupported("a comparison that dispatches to a trait method")
            s"${expr(pair.head, inTerm)} ${compareOp(cmp.op)} ${expr(pair.last, inTerm)}"
          }
        s"(${parts.mkString(if inTerm then " /\\ " else " && ")})"

      case TIf(List(TCondTest(c)), t, Some(e2), _) =>
        s"(if ${expr(c, inTerm)} then ${blockAs(t, inTerm)} else ${blockAs(e2, inTerm)})"

      case _: TIf => throw Unsupported("an 'if' with a binding condition or with no 'else'")

      case TQuantifier(universal, n, _, lo, hi, inclusive, pred) =>
        if !inTerm then
          throw Unsupported("a quantifier outside a contract — it is a statement about every value " +
            "in a range, which a prover reads and a program would have to run")
        val v    = ident(n)
        val upper = if inclusive then "<=" else "<"
        val range = s"${term(lo)} <= $v $upper ${term(hi)}"

        if universal then s"(forall $v: int. $range -> ${term(pred)})"
        else s"(exists $v: int. $range /\\ ${term(pred)})"

      // Several forms lower to a sequence — `print(x)` is a call for the value and another for the
      // newline. It is descended into rather than refused as a shape, so what the reader is told
      // about is the call they wrote and not the wrapper it landed in.
      case TSeq(es) =>
        if inTerm then throw Unsupported("a sequence of expressions in a contract")
        es.map(expr(_, inTerm)).mkString("(", "; ", ")")

      case TCall(name, args, _, _) =>
        if !ownNames(name) then
          throw Unsupported(s"a call to '${Modules.show(name)}', which this module does not declare")
        if inTerm && !terms(name) then
          throw Unsupported(s"a call to '${Modules.show(name)}' from a contract — it is a program " +
            "rather than mathematics, so it has no meaning as a term. Mark it '@ghost', which is " +
            "what says a declaration exists for the specification")
        val as = if args.isEmpty then "()" else args.map(expr(_, inTerm)).mkString(" ")
        s"(${ident(name)} $as)"

      case other => throw Unsupported(shape(other))
    }

    private def compareOp(op: String): String = op match
      case "==" => "="
      case "!=" => "<>"
      case o    => o

    private def binary(op: String, l: TExpr, r: TExpr, ty: Type, inTerm: Boolean): String = {
      val a = expr(l, inTerm)
      val b = expr(r, inTerm)

      Type.underlying(ty) match
        case i: Type.Integer =>
          op match
            case "+" | "-" | "*" if !inTerm && checkOverflow => s"(${helper(word(op), i)} $a $b)"
            case "+" | "-" | "*"                            => s"($a $op $b)"
            // Division is `int.ComputerDivision`'s, which is C's truncating one and is what sysl
            // emits. It goes through a wrapper in code whatever the overflow setting, because
            // dividing by zero is an obligation the arithmetic setting has nothing to do with.
            case "/" if inTerm => s"(div $a $b)"
            case "%" if inTerm => s"(mod $a $b)"
            case "/"           => s"(${helper("div", i)} $a $b)"
            case "%"           => s"(${helper("rem", i)} $a $b)"
            case other         => throw Unsupported(s"the operator '$other' on an integer")
        case other => throw Unsupported(s"the operator '$op' on ${Type.show(other)}")
    }

    private def word(op: String): String = op match
      case "+" => "add"
      case "-" => "sub"
      case _   => "mul"

    /** A checked arithmetic helper for one width, generated the first time it is asked for.
     *
     * The precondition is the whole point: it is the obligation that this operation does not leave
     * the range the type can hold, and a program that discharges every one of them is a program the
     * mathematical model above is exact for.
     */
    private def helper(op: String, i: Type.Integer): String = {
      val w    = widthOf(i)
      val name = s"${op}_${w.name}"

      helpers.getOrElseUpdate(name, {
        val sym = op match
          case "add" => "+"
          case "sub" => "-"
          case "mul" => "*"
          case "neg" => "-"
          case "div" => "div"
          case _     => "mod"

        op match
          case "neg" =>
            s"""  let ${name} (a: int) : int
               |    requires { ${w.lo} <= - a <= ${w.hi} }
               |    ensures  { result = - a }
               |  = - a
               |""".stripMargin
          case "div" | "rem" =>
            s"""  let ${name} (a b: int) : int
               |    requires { b <> 0 }
               |    requires { ${w.lo} <= $sym a b <= ${w.hi} }
               |    ensures  { result = $sym a b }
               |  = $sym a b
               |""".stripMargin
          case _ =>
            s"""  let ${name} (a b: int) : int
               |    requires { ${w.lo} <= a $sym b <= ${w.hi} }
               |    ensures  { result = a $sym b }
               |  = a $sym b
               |""".stripMargin
      })
      name
    }

    // --- programs -----------------------------------------------------------------------

    private def blockAs(b: TBlock, inTerm: Boolean): String =
      if inTerm then
        b match
          case TBlock(Nil, Some(r), _) => term(r)
          case _                       => throw Unsupported("a block with statements inside a term")
      else block(b)

    /** A block as a WhyML program. Every declaration opens a `let … in`, so the block is folded from
     * the end: what follows a binding is nested inside it, which is how WhyML scopes one.
     */
    private def block(b: TBlock): String = {
      val saved = refs
      // The trailing expression is translated **inside** the bindings, not before them, which is
      // what `tail` being by-name buys: a block's last expression reads the locals the block
      // declared, and `refs` only knows about one once its declaration has been walked. Evaluated
      // eagerly, `s` came out as `s` where it had to be `!s`, and the module did not typecheck.
      val out = stmts(b.stmts, b.result.map(code).getOrElse("()"))

      refs = saved
      out
    }

    private def stmts(list: List[TStmt], tail: => String): String = list match {
      case Nil => tail

      case TVarDecl(n, _, init) :: rest =>
        val i = code(init)

        refs += n
        s"let ${ident(n)} = ref $i in\n    ${stmts(rest, tail)}"

      case TExprStmt(e) :: rest =>
        val head = statement(e)

        if rest.isEmpty && tail == "()" then head else s"$head;\n    ${stmts(rest, tail)}"

      case (_: TInvariant) :: _ =>
        throw Unsupported("an 'invariant' outside a loop the translator recognized")

      case (_: TVariantCheck) :: _ =>
        throw Unsupported("a 'variant' outside a loop the translator recognized")

      case other :: _ => throw Unsupported(shape(other))
    }

    /** An expression written for its effect. */
    private def statement(e: TExpr): String = e match {
      case TStore(TLoad(n, _), v, _) if refs(n) => s"${ident(n)} := ${code(v)}"
      case TStore(TLoad(n, _), _, _) =>
        throw Unsupported(s"an assignment to '${ident(n)}', which is a parameter — WhyML's are " +
          "immutable, so give the value a local of its own")
      case _: TStore => throw Unsupported("an assignment to something other than a local")

      // `a += e` carries the operator with its `=` still on it, and what it means is the arithmetic
      // without one — including the range obligation, which is the point: a compound assignment
      // overflows exactly as the long form does.
      case TUpdate(TLoad(n, _), op, v, ty, _, _) if refs(n) =>
        s"${ident(n)} := ${binary(op.dropRight(1), TLoad(n, ty), v, ty, inTerm = false)}"

      case TIncDec(TLoad(n, _), op, _, ty, _) if refs(n) =>
        val one = TIntLit(1, ty)
        s"${ident(n)} := ${binary(if op == "++" then "+" else "-", TLoad(n, ty), one, ty, inTerm = false)}"

      case w: TWhile   => whileLoop(w)
      case l: TCheckedLoop => statement(l.loop)

      case TIf(List(TCondTest(c)), t, e2, _) =>
        val els = e2.map(b => s" else ${block(b)}").getOrElse("")
        s"(if ${code(c)} then ${block(t)}$els)"

      case other => code(other)
    }

    /** `while` with its clauses, which are the leading statements of its body (`17 §3`) and become
     * WhyML's own `invariant` and `variant`.
     */
    private def whileLoop(w: TWhile): String = {
      val TWhile(cond, body, elseBlock, _) = w

      if elseBlock.isDefined then throw Unsupported("a loop with an 'else'")

      val test = cond match
        case List(TCondTest(c)) => code(c)
        case _                  => throw Unsupported("a loop whose condition binds a pattern")

      val (clauses, rest) = body.span {
        case _: TInvariant | _: TVariantCheck => true
        case _                                => false
      }

      val lines = clauses.map {
        case TInvariant(c, _)          => s"      invariant { ${term(c)} }"
        case TVariantCheck(_, _, e)    => s"      variant   { ${term(e)} }"
        case _                         => ""
      }

      s"""while $test do
         |${lines.mkString("\n")}
         |      ${stmts(rest, "()")}
         |    done""".stripMargin
    }

    /** What to call a node a reader would recognize, for the refusal. */
    private def shape(x: Any): String = x match
      case _: TMatch                       => "a 'match'"
      case _: TFor | _: TCFor | _: TForEach | _: TIterate => "a 'for' loop"
      case _: TDoWhile                     => "a 'do while' loop"
      case _: TLoop                        => "a 'loop'"
      case _: TReturn                      => "an early 'return' — WhyML has no way to leave a body part-way through"
      case _: TBreak                       => "a 'break'"
      case _: TContinue                    => "a 'continue'"
      case _: TStrLit | _: TStr            => "a string"
      case _: TArrayLit | _: TArrayFill | _: TBufLit | _: TBufFill | _: TIndex | _: TSlice =>
        "an array or a slice"
      case _: TStructNew | _: TField       => "a struct"
      case _: TEnumNew                     => "an enum"
      case _: TCast                        => "a conversion between types"
      case _: TDeref | _: TAddrOf | _: TNullLit => "a pointer"
      case _: TVCall | _: TCallPtr         => "a call through a value"
      case _: TAsm                         => "an 'asm' block"
      case _: TDefer                       => "a 'defer'"
      case _: TFloatLit                    => "a floating-point value"
      case e: TExpr                        => s"the expression '${e.getClass.getSimpleName.drop(1)}'"
      case s: TStmt                        => s"the statement '${s.getClass.getSimpleName.drop(1)}'"
      case _                               => "something the translator has no name for"
  }

  private def Unsupported(what: String): RuntimeException = new Unsupported(what)
}
