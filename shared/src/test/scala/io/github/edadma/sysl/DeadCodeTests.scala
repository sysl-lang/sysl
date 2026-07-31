package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** What a program carries into its output, and what it does not.
 *
 * A declaration nothing can reach is still one the program made: it is checked, and a mistake in it
 * is reported, exactly as an unread `const` and an unused subtype are. What it does not get is a
 * place in the object file. The two halves are the whole of the rule, and they pull in opposite
 * directions — so both are tested here, and the second is what most of these are about.
 *
 * Reaching is over-approximated wherever the target is decided at run time: a slot of a method table
 * stands for every function any table for that trait put there. That is a deliberate bound and one of
 * the cases below pins it, since the alternative — guessing narrower — would drop a function the
 * program can call.
 */
class DeadCodeTests extends AnyFreeSpec with CodegenSupport with RunSupport {

  /** Every function the module defines under a name a program wrote. The `private` ones are the
   * compiler's own — table adapters, runtime helpers, the string and ARC support — and none of them
   * is what this pass decides about.
   */
  private def defined(src: String): Set[String] =
    ir(src).linesIterator
      .filter(l => l.startsWith("define") && !l.startsWith("define private"))
      .map(_.dropWhile(_ != '@').drop(1).takeWhile(_ != '('))
      .toSet

  /** A pair that renders itself, for the cases that need a `Display` of a program's own. */
  private val point =
    """struct Point
      |    x: int
      |    y: int
      |impl Display for Point
      |    display(self, out: *Writer, fmt: FormatSpec)
      |        display_str("(", out, fmt)
      |        self.x.display(out, fmt)
      |        display_str(")", out, fmt)
      |""".stripMargin

  "what the documents claim" - {

    // `14 §2`'s note that a prelude declaration nothing reaches costs the output nothing was only
    // ever half the rule: the program's own declarations were emitted whether or not anything
    // called them.
    "a function nothing calls is not emitted" in {
      val out = defined("""unused() -> int = 1
                          |print(2)
                          |""".stripMargin)

      out should not contain "unused"
      out should contain("main")
    }

    "and one something calls is" in {
      defined("""used() -> int = 1
                |print(used())
                |""".stripMargin) should contain("used")
    }

    // Visibility decides what a program may *name* (`15 §3`); reachability decides what is written
    // out. They are different questions, so an unmarked declaration is dropped exactly as a private
    // one is — nothing outside the program can name either, since the whole of it is compiled at once.
    "a private declaration is dropped the same way an unmarked one is" in {
      val out = defined("""private hidden() -> int = 1
                          |open() -> int = 2
                          |print(3)
                          |""".stripMargin)

      out should not contain "hidden"
      out should not contain "open"
    }

    // The other half, and the one that keeps this from being a way to hide mistakes. Rust's rule:
    // a body that is wrong is wrong whether or not the program would have run it.
    "a mistake in a function nothing calls is still reported" in {
      err("""unused() -> int = true
            |print(1)
            |""".stripMargin) should include("should return int")
    }

    // Escape analysis reads the whole program (`05`), and it has to read the parts of it that are
    // never reached too — which is what fixes the order of the passes: everything that checks runs
    // before anything is dropped.
    "and a slice escaping a function nothing calls is still rejected" in {
      // A **parameter** array, since a local one is promoted rather than reported now (`05`); what
      // is being checked is that an unreachable body is analyzed at all.
      err("""scratch(s: [4]u8) -> []u8
            |    s[..]
            |end scratch
            |print(1)
            |""".stripMargin) should include("is returned")
    }

    // `13 §7`: a computed `val` is filled by a prologue `main` opens with, so its initializer is one
    // of the places the program starts from.
    "a function reached only from a 'val' initializer is emitted" in {
      val out = defined("""build() -> int = 7
                          |val seed: int = build()
                          |print(seed)
                          |""".stripMargin)

      out should contain("build")
    }

    "a function reached only from a 'require' is emitted" in {
      val out = defined("""ok(n: int) -> bool = n > 0
                          |f(n: int) -> int
                          |    require ok(n)
                          |    n
                          |end f
                          |print(f(3))
                          |""".stripMargin)

      out should contain("ok")
    }

    "and one reached only from an 'ensure' is too" in {
      val out = defined("""sane(n: int) -> bool = n > 0
                          |f(n: int) -> int
                          |    ensure sane(result)
                          |    n + 1
                          |end f
                          |print(f(3))
                          |""".stripMargin)

      out should contain("sane")
    }

    // A method table is a constant the program reads a function pointer out of, so what it points at
    // is reachable however little can be proved about the call.
    "a method reached only through a trait object is emitted" in {
      val out = defined("""trait Show
                          |    show(self) -> int
                          |struct Point
                          |    x: int
                          |impl Show for Point
                          |    show(self) -> int = self.x
                          |var s: &Show = Point(3)
                          |print(s.show())
                          |""".stripMargin)

      out should contain("Point.show")
    }

    "an extern reached only from a function nothing calls is not declared" in {
      val out = ir("""extern secret(n: int) -> int
                     |unused() -> int = secret(1)
                     |print(2)
                     |""".stripMargin)

      out should not include "@secret"
    }

    "and one the program does reach is" in {
      ir("""extern secret(n: int) -> int
            |print(secret(1))
            |""".stripMargin) should include("declare i32 @secret(i32)")
    }
  }

  "what the edges do" - {

    "a function that only calls itself is still unreachable" in {
      defined("""spin(n: int) -> int = if n == 0 then 0 else spin(n - 1)
                |print(1)
                |""".stripMargin) should not contain "spin"
    }

    "and so is a pair that only calls each other" in {
      val out = defined("""ping(n: int) -> int = if n == 0 then 0 else pong(n - 1)
                          |pong(n: int) -> int = ping(n)
                          |print(1)
                          |""".stripMargin)

      out should not contain "ping"
      out should not contain "pong"
    }

    "mutual recursion the program does enter is kept whole" in {
      val out = defined("""even(n: int) -> bool = if n == 0 then true else odd(n - 1)
                          |odd(n: int) -> bool = if n == 0 then false else even(n - 1)
                          |print(even(4))
                          |""".stripMargin)

      out should contain("even")
      out should contain("odd")
    }

    "what a function nothing calls calls is dropped with it" in {
      val out = defined("""helper() -> int = 1
                          |unused() -> int = helper()
                          |print(2)
                          |""".stripMargin)

      out should not contain "unused"
      out should not contain "helper"
    }

    "but a function two callers share survives the dead one" in {
      val out = defined("""helper() -> int = 1
                          |unused() -> int = helper()
                          |print(helper())
                          |""".stripMargin)

      out should not contain "unused"
      out should contain("helper")
    }

    "a chain of three is followed to its end" in {
      val out = defined("""c() -> int = 3
                          |b() -> int = c()
                          |a() -> int = b()
                          |print(a())
                          |""".stripMargin)

      out should contain allOf ("a", "b", "c")
    }

    "an instantiation asked for only by a function nothing calls is not emitted" in {
      val out = defined("""id[T](x: T) -> T = x
                          |unused() -> int = id(1)
                          |print(2)
                          |""".stripMargin)

      out should not contain "id.int"
    }

    "while the same instantiation asked for by live code is" in {
      defined("""id[T](x: T) -> T = x
                |print(id(1))
                |""".stripMargin) should contain("id.int")
    }

    // The library was already held back by reachability, but from `funcsUsed` — which records a name
    // wherever it was written, dead code included.
    //
    // Both names are read off `Library`, and the negative is why: `contain` on a set is element
    // equality, so a bare `"printr"` stops matching the moment the declaration is keyed under a
    // module — and a negative that matches nothing passes whatever the compiler emitted.
    "a library function reached only from dead code is not emitted either" in {
      val out = defined("""unused()
                          |    print(1.5)
                          |end unused
                          |print(2)
                          |""".stripMargin)

      out should not contain Library.key("printr")
      out should contain(Library.key("printi"))
    }

    "an unused member of a type the program does use is not emitted" in {
      val out = defined("""struct Point
                          |    x: int
                          |    double(self) -> int = self.x * 2
                          |    triple(self) -> int = self.x * 3
                          |print(Point(3).double())
                          |""".stripMargin)

      out should contain("Point.double")
      out should not contain "Point.triple"
    }

    // A `where` predicate is the one function whose name lives inside a *type* rather than beside it
    // in the tree, so the node that checks against one has to read it out.
    "a subtype's 'where' predicate is emitted for the check that calls it" in {
      val out = defined("""type Even = int within 0..100 where value % 2 == 0
                          |var e: Even = 4
                          |print(int(e))
                          |""".stripMargin)

      out should contain("Even$pred")
    }

    "and it runs, so the check is a real one" in {
      exits("""type Even = int within 0..100 where value % 2 == 0
              |var n = 5
              |var e: Even = n
              |print(int(e))
              |""".stripMargin)
    }

    "a struct's invariant function is emitted for the construction that checks it" in {
      val out = defined("""struct Account
                          |    balance: int
                          |    invariant balance >= 0
                          |print(Account(5).balance)
                          |""".stripMargin)

      out should contain("Account$inv")
    }

    "printing a value that renders itself still reaches the sink" in {
      val out = defined(point + "print(Point(3, 4))")

      out should contain("Point.display")
      out should contain(Library.key("putbytes"))
    }

    "and the program prints what it should" in {
      run(point + "print(Point(3, 4))") shouldBe "(3)\n"
    }

    // A trait's operator method rides on the node the operator lowers to rather than on a call, so
    // the two forms that do that — a compound assignment and a comparison — are each their own case.
    "a method reached only through a compound assignment is emitted" in {
      val out = defined("""struct Meters
                          |    v: int
                          |impl Add for Meters
                          |    add(self, other: Meters) -> Meters = Meters(self.v + other.v)
                          |var m = Meters(1)
                          |m += Meters(2)
                          |print(m.v)
                          |""".stripMargin)

      out should contain("Meters.add")
    }

    "a method reached only through a comparison is emitted" in {
      val out = defined("""struct Meters
                          |    v: int
                          |impl Eq for Meters
                          |    eq(self, other: Meters) -> bool = self.v == other.v
                          |print(Meters(1) == Meters(2))
                          |""".stripMargin)

      out should contain("Meters.eq")
    }

    // The over-approximation, pinned. Erasing a value into a trait object is what builds the table,
    // and a table is a root — so a function nothing can call is kept alive by an erasure that itself
    // never runs. Narrowing this is a question about *tables*, not about functions.
    "a table an unreachable erasure built still holds its implementation alive" in {
      val out = defined("""trait Show
                          |    show(self) -> int
                          |struct Point
                          |    x: int
                          |impl Show for Point
                          |    show(self) -> int = self.x
                          |unused() -> int
                          |    var s: &Show = Point(3)
                          |    s.show()
                          |end unused
                          |print(1)
                          |""".stripMargin)

      out should not contain "unused"
      out should contain("Point.show")
    }

    "a program with nothing in it is still a program" in {
      defined("") shouldBe Set("main")
    }

    // Storage is not code, and a `val` whose initializer is code runs whether or not anything reads
    // what it left behind. Dropping one would be dropping a side effect, which is a different
    // question from dropping a function nobody can arrive at.
    "a 'val' nothing reads is still laid down" in {
      val out = ir("""build() -> int = 7
                     |val seed: int = build()
                     |print(1)
                     |""".stripMargin)

      out should include("@seed = private global")
      out should include("define i32 @build()")
    }

    "a function reached only from a dead function's contract goes with it" in {
      val out = defined("""ok(n: int) -> bool = n > 0
                          |unused(n: int) -> int
                          |    require ok(n)
                          |    n
                          |end unused
                          |print(1)
                          |""".stripMargin)

      out should not contain "unused"
      out should not contain "ok"
    }

    // The synthesised functions are followed like any other, so what *they* call comes too.
    "a function called only from a subtype's predicate is emitted" in {
      val out = defined("""even(n: int) -> bool = n % 2 == 0
                          |type Even = int within 0..100 where even(value)
                          |var e: Even = 4
                          |print(int(e))
                          |""".stripMargin)

      out should contain("even")
    }

    "a function called only from a struct's invariant is emitted" in {
      val out = defined("""sane(n: int) -> bool = n >= 0
                          |struct Account
                          |    balance: int
                          |    invariant sane(balance)
                          |print(Account(5).balance)
                          |""".stripMargin)

      out should contain("sane")
    }

    // Reaching crosses a file the way naming does: the files of a module are one scope, so which
    // file a call was written in decides nothing about what it reaches.
    "reaching crosses the files of a module" in {
      val out = irOf(
        "helper.sysl" -> "module geom\nprivate[geom] scale(n: int) -> int = n * 2\nprivate[geom] spare() -> int = 9",
        "main.sysl"   -> "module geom\nprint(scale(3))",
      )

      out should include("define i32 @geom$scale(i32 %n.param)")
      out should not include "@geom$spare"
    }

    "a whole program of unreachable declarations still runs" in {
      run("""a() -> int = 1
            |b() -> int = a()
            |private c() -> int = b()
            |print(9)
            |""".stripMargin) shouldBe "9\n"
    }
  }
}
