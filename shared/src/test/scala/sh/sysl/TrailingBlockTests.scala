package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** `f:` and then an indented block — an argument written as layout (`reference/expressions.md
  * § A trailing block`).
  *
  * The feature is one rule with two outcomes, and which outcome a block gets is read off the
  * parameter it stands at. So the suite is organized around that: the two readings, the shapes that
  * decide neither, and the two limits the design accepts rather than builds machinery for.
  *
  * **Both limits have tests of their own and they are the point of the last section.** A block
  * filling a collection is a list of its lines, so every line has to be a value — which makes a
  * loop inside one a refusal rather than an oversight, and Swift's `buildArray` is exactly what
  * would cover it. And a bracket suspends the off-side rule, so a *trailing* block cannot be written
  * inside one — `match` and `->` open a block there since card `0248`, and `:` does not. A test that pins each is what keeps the cost stated rather than rediscovered.
  */
class TrailingBlockTests extends AnyFreeSpec with ParseSupport with RunSupport with CodegenSupport {

  "what the parser builds" - {

    // Nothing is decided here — the parser does not know what `f` is, let alone what its parameters
    // want, so the block travels as itself and argument binding reads it.
    "a block with no argument list is the call's only argument" in {
      prog("""f:
             |    1
             |    2
             |""".stripMargin) shouldBe
        List(ExprStmt(Call(Ident("f"), List(BlockArg(List(ExprStmt(i(1)), ExprStmt(i(2))))))))
    }

    "a block after an argument list is appended to it" in {
      prog("""f(7):
             |    1
             |""".stripMargin) shouldBe
        List(ExprStmt(Call(Ident("f"), List(i(7), BlockArg(List(ExprStmt(i(1))))))))
    }

    "a block after a method call is appended to that call" in {
      prog("""x.f(7):
             |    1
             |""".stripMargin) shouldBe
        List(ExprStmt(Call(Field(Ident("x"), "f"), List(i(7), BlockArg(List(ExprStmt(i(1))))))))
    }

    "a block may hold a block" in {
      prog("""outer:
             |    inner:
             |        1
             |""".stripMargin) shouldBe
        List(ExprStmt(Call(Ident("outer"), List(BlockArg(List(
          ExprStmt(Call(Ident("inner"), List(BlockArg(List(ExprStmt(i(1))))))),
        ))))))
    }

    "a block on the right of a binding" in {
      prog("""val v = f:
             |    1
             |""".stripMargin) shouldBe
        List(ValDecl("v", None, Call(Ident("f"), List(BlockArg(List(ExprStmt(i(1))))))))
    }

    // The one other place the language writes a colon after something the expression grammar could
    // have read. The trailing block commits to nothing until the indent is there, which is what
    // leaves this reading alone.
    "'should_trap:' is not a block" in {
      prog("""@test("it traps", should_trap: "out of range")
             |f()
             |    print(1)
             |""".stripMargin) should not be empty
    }

    "an annotation's colon is not a block either" in {
      prog("""val x: int = 5
             |""".stripMargin) shouldBe List(ValDecl("x", Some(NamedType("int")), i(5)))
    }
  }

  "a block at a collection parameter is an array of its lines" - {

    "the lines become the elements" in {
      run("""total(xs: []int) -> int
            |    var t = 0
            |
            |    for i in 0..<xs.len
            |        t += xs[i]
            |
            |    t
            |
            |val n = total:
            |    1
            |    2
            |    3
            |
            |print(n)
            |""".stripMargin) shouldBe "6\n"
    }

    "a block beside arguments written in the parentheses" in {
      run("""joined(sep: string, xs: []string) -> string
            |    var s = ""
            |
            |    for i in 0..<xs.len
            |        if i > 0 then s += sep
            |
            |        s += xs[i]
            |
            |    s
            |
            |val out = joined("-"):
            |    "a"
            |    "b"
            |    "c"
            |
            |print(out)
            |""".stripMargin) shouldBe "a-b-c\n"
    }

    // What Swift needs `buildEither` for, and sysl needs nothing for: `if` is already an expression
    // whose arms must agree in type, so at `int` they agree and there is no second shape to
    // assemble.
    "a branch is an ordinary line, because 'if' is an expression" in {
      run("""total(xs: []int) -> int
            |    var t = 0
            |
            |    for i in 0..<xs.len
            |        t += xs[i]
            |
            |    t
            |
            |val big = true
            |
            |val n = total:
            |    1
            |    if big then 100 else 2
            |
            |print(n)
            |""".stripMargin) shouldBe "101\n"
    }

    "a defaulted parameter after the block still takes its default" in {
      run("""total(xs: []int, bonus: int = 10) -> int
            |    var t = bonus
            |
            |    for i in 0..<xs.len
            |        t += xs[i]
            |
            |    t
            |
            |val n = total:
            |    1
            |    2
            |
            |print(n)
            |""".stripMargin) shouldBe "13\n"
    }

    // A block has no position of its own, so a name written before it does not strand it — the
    // block fills whatever nothing else filled. Without this the form and named arguments could not
    // be used together, and `column(spacing = 4):` is the first thing anybody writes.
    "a named argument before it does not take its place" in {
      run("""total(xs: []int, bonus: int) -> int
            |    var t = bonus
            |
            |    for i in 0..<xs.len
            |        t += xs[i]
            |
            |    t
            |
            |val n = total(bonus = 100):
            |    1
            |    2
            |
            |print(n)
            |""".stripMargin) shouldBe "103\n"
    }

    // The shape the whole feature exists for (`0168`): a tree of erased views, each line a
    // different concrete type reaching one `[]&Trait`. If this did not work the form would be of no
    // use to the thing it was built for.
    "the lines may be different types reaching one erased element type" in {
      run("""trait Shape
            |    area(&self) -> int
            |end Shape
            |
            |struct Square
            |    w: int
            |end Square
            |
            |impl Shape for Square
            |    area(&self) -> int = self.w * self.w
            |
            |struct Rect
            |    w: int
            |    h: int
            |end Rect
            |
            |impl Shape for Rect
            |    area(&self) -> int = self.w * self.h
            |
            |total(shapes: []&Shape) -> int
            |    var t = 0
            |
            |    for i in 0..<shapes.len
            |        t += shapes[i].area()
            |
            |    t
            |
            |val n = total:
            |    Square(3)
            |    Rect(2, 5)
            |    Square(1)
            |
            |print(n)
            |""".stripMargin) shouldBe "20\n"
    }

    // The shape the feature exists for: a tree written as indentation rather than as a nest of
    // brackets. Each level is a call whose last argument is the level below it.
    "nested blocks build a tree" in {
      run("""node(name: string, kids: []string) -> string
            |    var s = name + "("
            |
            |    for i in 0..<kids.len
            |        if i > 0 then s += " "
            |
            |        s += kids[i]
            |
            |    s + ")"
            |
            |val tree = node("a"):
            |    node("b"):
            |        "c"
            |        "d"
            |    "e"
            |
            |print(tree)
            |""".stripMargin) shouldBe "a(b(c d) e)\n"
    }
  }

  "a block at a callable parameter is a closure over its lines" - {

    "the block is the body and the last line is its value" in {
      run("""apply(f: () -> int) -> int = f()
            |
            |val n = apply:
            |    val a = 6
            |    val b = 7
            |    a * b
            |
            |print(n)
            |""".stripMargin) shouldBe "42\n"
    }

    // Unlike a collection's, a callable's block is a *body*, so a statement in it is ordinary. That
    // asymmetry is the whole of what the two readings differ by and is worth a test of its own.
    "a binding inside it is an ordinary statement" in {
      run("""twice(f: () -> unit)
            |    f()
            |    f()
            |
            |twice:
            |    var n = 3
            |    print(n * n)
            |""".stripMargin) shouldBe "9\n9\n"
    }

    "a block at a '&Fn' parameter, which is the same reading through a mode" in {
      run("""later(f: &Fn() -> unit)
            |    f()
            |
            |later:
            |    print("ran")
            |""".stripMargin) shouldBe "ran\n"
    }

    "it closes over what it reads" in {
      run("""apply(f: () -> int) -> int = f()
            |
            |val n = 5
            |
            |val sq = apply:
            |    n * n
            |
            |print(sq)
            |""".stripMargin) shouldBe "25\n"
    }

    // A by-name parameter's type is `Fn() -> T`, so a block at one is a closure by the ordinary
    // rule — and the thunk the call site would otherwise wrap it in must not be applied twice.
    "a block at a by-name parameter is not thunked twice" in {
      run("""rep(n: int, x: -> int) -> int
            |    var t = 0
            |
            |    for i in 0..<n
            |        t += x
            |
            |    t
            |
            |val n = rep(3):
            |    5
            |
            |print(n)
            |""".stripMargin) shouldBe "15\n"
    }
  }

  "a block reaches every call form, because it is bound where every call form binds" - {

    "a method's parameter" in {
      run("""struct Runner
            |    n: int
            |
            |    go(self, f: &Fn() -> unit)
            |        for i in 0..<self.n
            |            f()
            |end Runner
            |
            |val r = Runner(2)
            |
            |r.go:
            |    print("tick")
            |""".stripMargin) shouldBe "tick\ntick\n"
    }

    "a struct's field" in {
      run("""struct Menu
            |    title: string
            |    items: [3]int
            |end Menu
            |
            |val m = Menu("m"):
            |    1
            |    2
            |    3
            |
            |print(m.title + str(m.items[2]))
            |""".stripMargin) shouldBe "m3\n"
    }

    // Overload resolution reads the candidates off the arguments' *types*, and it does that before
    // the parameter lists have been paired off — so a block, whose whole nature is decided by the
    // parameter it is about to be paired with, is asked a question it cannot answer. What matters
    // is that it declines rather than taking the compilation down, and that the reader is told the
    // arguments did not settle it.
    "an overload set, which a block cannot tell apart" in {
      err("""trait Runs
            |    on(&self, f: &Fn() -> unit)
            |end Runs
            |
            |trait Counts
            |    on(&self, n: int)
            |end Counts
            |
            |struct W
            |    k: int
            |end W
            |
            |impl Runs for W
            |    on(&self, f: &Fn() -> unit) = f()
            |
            |impl Counts for W
            |    on(&self, n: int) = print(n)
            |
            |val w = W(1)
            |
            |w.on:
            |    print("x")
            |""".stripMargin) should include("nothing in the call says which was meant")
    }
  }

  "what a block may not stand at" - {

    "a parameter that is neither a collection nor a callable" in {
      err("""f(n: int) = n
            |
            |val x = f:
            |    1
            |
            |print(x)
            |""".stripMargin) should include("a trailing block stands at 'n', which is a 'int'")
    }

    "a call with no parameters to stand at" in {
      err("""f() = ()
            |
            |f:
            |    1
            |""".stripMargin) should include("no parameter for this trailing block to stand at")
    }

    "a line of a collection's block that is not a value" in {
      err("""total(xs: []int) -> int = 0
            |
            |val n = total:
            |    var k = 1
            |    k
            |
            |print(n)
            |""".stripMargin) should include("this one declares a name instead")
    }

    // The stated cost of refusing a result builder (`0171`): a loop is a statement, so it is not a
    // line of a list. The message says where the loop goes instead.
    "a loop inside a collection's block, which is the limit the design accepts" in {
      val msg = err("""total(xs: []int) -> int = 0
                      |
                      |val n = total:
                      |    for k in 0..<3
                      |        k
                      |
                      |print(n)
                      |""".stripMargin)

      msg should include("would contribute one element and not one per iteration")
      msg should include("'Buf'")
    }

    // A bracket suspends the off-side rule until it closes (`00 §9`), so there is no indent inside
    // one for a trailing block to be made of. It is pinned here because the spelling looks
    // reasonable and the failure is a parse error about something else.
    //
    // **`:` is not among the tokens card `0248` gave a block to**, which are `match` and `->`. Each
    // of those can only ever open a block, so a reader is never left wondering whether the line
    // ended; `:` appears inside brackets in positions that open nothing, so it stays out and the
    // name-it-first form remains the way to pass a block.
    "a block written inside parentheses, which the off-side rule leaves no room for" in {
      progError("""print(total:
                  |    1
                  |)
                  |""".stripMargin) should not be empty
    }
  }

  /** The one value a block is passed has a name, and the name is `it` (`0209`).
    *
    * A block wrote no parameter list, so the arity it stands at is what names it — which is the
    * whole of the feature and is why every test here is a *run* rather than a tree: what is being
    * checked is that the value arrives, not that a parameter appeared.
    *
    * **The section is mostly about `it` being ORDINARY.** It is not reserved and it is not magic: it
    * shadows, it is shadowed, an inner block's hides an outer block's, and a nested closure captures
    * it as it would capture any parameter. Each of those is a rule that already held for a written
    * parameter, and each is pinned here because a reader meeting an implicit name has no way to know
    * which of them it obeys.
    */
  "the one value a block is passed is 'it'" - {

    "a block at a one-parameter callable binds it" in {
      run("""on_change(f: &Fn(int) -> unit) = f(42)
            |
            |on_change:
            |    print(it)
            |""".stripMargin) shouldBe "42\n"
    }

    "and the block is still a body, so it is what the lines are written against" in {
      run("""apply(f: &Fn(int) -> int) -> int = f(6)
            |
            |val n = apply:
            |    val k = it + 1
            |    k * k
            |
            |print(n)
            |""".stripMargin) shouldBe "49\n"
    }

    // The bare arrow is the likeliest spelling of a callable parameter and is the one that is no
    // longer written by the time argument binding reads it: `MemberLowering.callBounds` has already
    // turned it into a bounded type parameter carrying no arity at all. So the name cannot be given
    // there, and this is the test that says the arity reaches the place that can.
    "including a parameter written as a bare arrow, whose arity binding cannot see" in {
      run("""each(xs: []int, f: int -> unit)
            |    for i in 0..<xs.len
            |        f(xs[i])
            |
            |each([1, 2, 3]):
            |    print(it * 10)
            |""".stripMargin) shouldBe "10\n20\n30\n"
    }

    "a block that has no use for the value simply never writes it" in {
      run("""on_change(f: &Fn(int) -> unit) = f(42)
            |
            |on_change:
            |    print("changed")
            |""".stripMargin) shouldBe "changed\n"
    }

    "a zero-arity callable binds nothing, so 'it' there is whatever the scope already had" in {
      run("""later(f: &Fn() -> unit) = f()
            |
            |val it = 7
            |
            |later:
            |    print(it)
            |""".stripMargin) shouldBe "7\n"
    }

    "the block's own binding shadows it, as it would shadow a written parameter" in {
      run("""apply(f: &Fn(int) -> int) -> int = f(6)
            |
            |val n = apply:
            |    val it = 100
            |    it + 1
            |
            |print(n)
            |""".stripMargin) shouldBe "101\n"
    }

    "and it shadows an outer name of its own" in {
      run("""apply(f: &Fn(int) -> int) -> int = f(6)
            |
            |val it = 100
            |
            |val n = apply:
            |    it + 1
            |
            |print(n)
            |""".stripMargin) shouldBe "7\n"
    }

    "an inner block's hides an outer block's" in {
      run("""outer(f: &Fn(int) -> int) -> int = f(1)
            |inner(f: &Fn(int) -> int) -> int = f(2)
            |
            |val n = outer:
            |    inner:
            |        it * 10
            |
            |print(n)
            |""".stripMargin) shouldBe "20\n"
    }

    // The outer block's `it` is a parameter of the outer closure, so the inner one reaches it by
    // capture — which is the ordinary rule and needs nothing of its own, but is the case a reader
    // would most reasonably doubt.
    "and a zero-arity block inside a one-arity block still reads the outer one's" in {
      run("""outer(f: &Fn(int) -> int) -> int = f(6)
            |later(f: &Fn() -> unit) = f()
            |
            |val n = outer:
            |    later:
            |        print(it)
            |
            |    it * 2
            |
            |print(n)
            |""".stripMargin) shouldBe "6\n12\n"
    }

    "a closure literal written inside the block captures it" in {
      run("""apply(f: &Fn(int) -> int) -> int = f(6)
            |twice(g: &Fn(int) -> int) -> int = g(1) + g(2)
            |
            |val n = apply:
            |    twice((k) -> k * it)
            |
            |print(n)
            |""".stripMargin) shouldBe "18\n"
    }

    "it reaches a method's parameter, which is where a widget's handler is" in {
      run("""struct Field
            |    tag: int
            |
            |    on_change(self, f: &Fn(int) -> unit) = f(self.tag * 2)
            |end Field
            |
            |val fld = Field(21)
            |
            |fld.on_change:
            |    print(it)
            |""".stripMargin) shouldBe "42\n"
    }

    // `0171`'s rule is that the reading is decided by the parameter, and the collection reading must
    // not learn a name from this: a block at `[]T` is a list of its lines and nothing in it is a
    // parameter of anything.
    "a collection's block binds nothing, so 'it' there is an undefined name" in {
      err("""total(xs: []int) -> int = xs[0]
            |
            |val n = total:
            |    it
            |
            |print(n)
            |""".stripMargin) should include("undefined name 'it'")
    }

    // A block has one name to give and no way to give two, so this is where the form stops. The
    // sentence has to say that rather than count parameters the reader did not write.
    "two or more is refused by name, and the closure literal is what it points at" in {
      val msg = err("""on_drag(f: &Fn(int, int) -> unit) = f(1, 2)
                      |
                      |on_drag:
                      |    print(it)
                      |""".stripMargin)

      msg should include("binds the one value it is passed as 'it'")
      msg should include("being used as takes 2")
      msg should include("'(a, b) -> …'")
    }

    // A **raw** trait object is the one callable spelling a block cannot fill, and the reason is not
    // about blocks: `*Fn` points at a value the caller owns, and a block is a temporary exactly as
    // the closure literal beside it is. `TraitObjectErrorTests` has the literal's half of this; what
    // it means here is that the value has to be named before the call, which is what the advice
    // says. The counted `&Fn` above is the spelling a handler actually takes.
    "a raw trait object is the one callable spelling a block cannot fill" in {
      err("""on_change(f: *Fn(int) -> unit) = f(42)
            |
            |on_change:
            |    print(it)
            |""".stripMargin) should include("points at a value, so it needs an address")
    }

    // The library's own one-parameter callables are trait members taking `&Fn(T) -> U`, which is
    // the shape a widget's handler has and the shape the card was surveying for. A block reaching
    // one through a generic member is the case with the most machinery between the written type and
    // the arity, so it is the one worth a test against the real library rather than a local
    // declaration.
    "a block reaches the standard library's own callables" in {
      run("""import sysl.seq.Sequence
            |
            |val xs = [1, 2, 3, 4]
            |
            |val evens = xs[..].filter:
            |    it % 2 == 0
            |
            |print(evens)
            |
            |val doubled = xs[..].map:
            |    it * 2
            |
            |print(doubled)
            |""".stripMargin) shouldBe "[2, 4]\n[2, 4, 6, 8]\n"
    }

    // The other half of that: a closure the reader *did* write parameters for still gets the
    // message that counts them, because there the count is something they chose.
    "while a written closure's arity is still refused by counting it" in {
      err("""on_drag(f: &Fn(int, int) -> unit) = f(1, 2)
            |
            |on_drag((a) -> print(a))
            |""".stripMargin) should include("this closure takes 1 parameter, and what it is being used as takes 2")
    }
  }
}
