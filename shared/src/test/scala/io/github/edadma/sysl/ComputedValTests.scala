package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** A module-level `val` whose initializer is **computed** (`13 §7`).
 *
 * `val` shipped taking a constant tree only, and the reason it stopped there was an open question
 * rather than an implementation limit: code that runs before `main` has to run in *some* order, and
 * `13 §6` gives a module's own files none at all. The answer taken here is that the order is the one
 * the initializers' own dependencies describe — a `val` is filled after everything it reads, where
 * "reads" follows through whatever its initializer calls.
 *
 * Two properties fall out of `13 §6` rather than being decided, and both are pinned below: a cycle
 * can only ever be *inside* one module, since a cross-module edge would need the module graph to
 * cycle; and a call through a method table can be followed without knowing what it lands in, because
 * every table for the trait is in the same object file.
 */
class ComputedValTests extends AnyFreeSpec with CodegenSupport with RunSupport {

  "what the documents claim" - {
    // The customer the feature was opened for: `guide/png`'s CRC table is derived rather than
    // written down, so a constant tree could never have carried it.
    "a table a function builds is a 'val'" in {
      val src =
        """build() -> [256]u32
          |    var t: [256]u32
          |    for n in 0usize..<256
          |        var c = u32(n)
          |        for k in 0..<8
          |            c = if c & 1u32 != 0u32 then c >> 1u32 ^ 0xEDB88320u32 else c >> 1u32
          |        t[n] = c
          |    t
          |val crc_table: [256]u32 = build()
          |print(crc_table[0], crc_table[1], crc_table[255])""".stripMargin

      run(src) shouldBe "0 1996959894 755167117\n"
    }

    // The declaration a constant initializer already accepted is unchanged — it is still laid into
    // the object file, and the way to see that from outside is that it is still a `constant`.
    "a constant initializer is still written into the object file" in {
      val out = ir("val k: [4]int = [1, 2, 3, 4]\nprint(k[2])")

      out should include("@k = private constant [4 x i32] [i32 1, i32 2, i32 3, i32 4]")
      out should not include "@k = private global"
    }

    // …and a computed one is storage that gets written, which is a different LLVM declaration.
    "a computed one is storage that the program fills" in {
      val out = ir("f() -> int = 7\nval n: int = f()\nprint(n)")

      out should include("@n = private global i32 zeroinitializer")
      out should include("store i64")
    }

    "and it is filled before the program's own statements run" in {
      val src =
        """report() -> int
          |    print("built")
          |    3
          |val n: int = report()
          |print("running")
          |print(n)""".stripMargin

      run(src) shouldBe "built\nrunning\n3\n"
    }

    // The ordering rule, seen at its smallest: `b` reads `a`, so `a` is filled first whatever order
    // the two were written in.
    "one built out of another is filled after it" in {
      val src =
        """double(x: int) -> int = x * 2
          |val b: int = double(a)
          |val a: int = double(21)
          |print(a, b)""".stripMargin

      run(src) shouldBe "42 84\n"
    }

    // And the same when the read is not in the initializer at all but in what it calls, which is the
    // case a rule that looked only at the initializer expression would get wrong.
    "the read may be inside a function the initializer calls" in {
      val src =
        """base() -> int = 10
          |val a: int = base()
          |from_a() -> int = a + 5
          |val b: int = from_a()
          |print(a, b)""".stripMargin

      run(src) shouldBe "10 15\n"
    }

    "a cycle between two initializers is refused" in {
      val e = err(
        """from_b() -> int = b + 1
          |from_a() -> int = a + 1
          |val a: int = from_b()
          |val b: int = from_a()""".stripMargin
      )

      e should include("cannot be initialized")
      e should include("'a'")
      e should include("'b'")
    }

    "and so is an initializer that needs itself" in {
      err("me() -> int = n\nval n: int = me()") should include("cannot be initialized")
    }

    // A `val` holds plain data whatever fills it, which is the one rule that did not change: the
    // constant form could not have expressed a reference and the computed form must not either.
    "a reference cannot be held in one" in {
      val e = err("struct P\n    x: int\nend P\nmk() -> &P = P(1)\nval p: &P = mk()")

      e should include("cannot be a 'val'")
      e should include("plain data")
    }

    "nor a string, which is a view with an owner" in {
      err("val s: string = \"hi\"") should include("plain data")
    }

    "nor a slice" in {
      err("val xs: []int = [1, 2, 3]") should include("plain data")
    }

    // The read-only rule is about the storage, not about how it was filled — a computed table is no
    // more writable than a written-down one.
    "a computed one is still read-only" in {
      val src = "build() -> [2]int = [1, 2]\nval k: [2]int = build()\nk[0] = 9"

      err(src) should include("a 'val' is written once")
    }
  }

  "what the edges do" - {
    // A struct of numbers is the shape the constant form could not build and the computed one can,
    // so it is the first thing a reader will try.
    "a struct is plain data and may be computed" in {
      val src =
        """struct Point
          |    x: int
          |    y: int
          |end Point
          |origin() -> Point = Point(3, 4)
          |val o: Point = origin()
          |print(o.x, o.y)""".stripMargin

      run(src) shouldBe "3 4\n"
    }

    "so is an enum, including one that carries a payload" in {
      val src =
        """enum Shape
          |    Dot
          |    Round(r: int)
          |end Shape
          |pick() -> Shape = Round(5)
          |val s: Shape = pick()
          |s match
          |    Round(r) -> print(r)
          |    Dot -> print(0)""".stripMargin

      run(src) shouldBe "5\n"
    }

    // An enum whose payload is a reference is not, which is the recursive half of the type rule.
    "but not an enum whose payload is a reference" in {
      val src =
        """enum Tree
          |    Leaf
          |    Node(next: &Tree)
          |end Tree
          |mk() -> Tree = Leaf
          |val t: Tree = mk()""".stripMargin

      err(src) should include("plain data")
    }

    "an array of structs is computed element by element" in {
      val src =
        """struct Pair
          |    a: int
          |    b: int
          |end Pair
          |build() -> [3]Pair
          |    var out: [3]Pair
          |    for i in 0usize..<3
          |        out[i] = Pair(int(i), int(i) * int(i))
          |    out
          |val ps: [3]Pair = build()
          |print(ps[2].a, ps[2].b)""".stripMargin

      run(src) shouldBe "2 4\n"
    }

    // Three in a chain, declared in the order that would be wrong if the sort did nothing.
    "a chain of three is sorted whichever way it was written" in {
      val src =
        """plus(x: int, y: int) -> int = x + y
          |val c: int = plus(b, 1)
          |val b: int = plus(a, 1)
          |val a: int = plus(0, 1)
          |print(a, b, c)""".stripMargin

      run(src) shouldBe "1 2 3\n"
    }

    // A diamond: two independent tables over one base, both correct, and the base filled once.
    "a diamond fills the shared dependency once" in {
      val src =
        """count() -> int
          |    print("base")
          |    2
          |val base: int = count()
          |left() -> int = base * 10
          |right() -> int = base * 100
          |val l: int = left()
          |val r: int = right()
          |print(l, r)""".stripMargin

      run(src) shouldBe "base\n20 200\n"
    }

    // A constant `val` is not in the graph at all, so reading one puts no edge anywhere — and the
    // computed one that reads it is still correct, since the constant was there before the process
    // started.
    "reading a constant 'val' needs no ordering" in {
      val src =
        """val seed: [3]int = [4, 5, 6]
          |sum() -> int = seed[0] + seed[1] + seed[2]
          |val total: int = sum()
          |print(total)""".stripMargin

      run(src) shouldBe "15\n"
      ir(src) should include("@seed = private constant")
    }

    // The claim `13 §6` makes this rest on: a table in one module built out of a table in another
    // is ordered without anyone having said what order the modules run in, because the graph over
    // the `val`s already answers it. The importing module's table is declared first here, so a walk
    // that took the modules in the order it read them would get this wrong.
    "a table built out of another module's table is ordered across the seam" in {
      val out = runIn(
        ("", "main.sysl", "print(scaled[0], scaled[3])"),
        ("tables", "base.sysl",
          """module tables
            |seed() -> [4]int
            |    var t: [4]int
            |    for i in 0usize..<4
            |        t[i] = int(i) + 1
            |    t
            |val base: [4]int = seed()
            |""".stripMargin),
        ("", "derived.sysl",
          """import tables
            |grow() -> [4]int
            |    var t: [4]int
            |    for i in 0usize..<4
            |        t[i] = tables.base[i] * 10
            |    t
            |val scaled: [4]int = grow()
            |""".stripMargin),
      )

      out shouldBe "10 40\n"
    }

    // A generic function reaches its callee under the mangled name its instantiation was given, and
    // the dependency walk reads the same names codegen does — so a table built through one is
    // ordered like any other.
    "an initializer that goes through a generic is ordered" in {
      val src =
        """twice[T: Add](x: T) -> T = x + x
          |val a: int = twice(21)
          |from_a() -> int = twice(a)
          |val b: int = from_a()
          |print(a, b)""".stripMargin

      run(src) shouldBe "42 84\n"
    }

    // A cycle of three, to check the chain is said as a chain rather than as one pair of it.
    "a cycle of three names all three" in {
      val e = err(
        """fa() -> int = c
          |fb() -> int = a
          |fc() -> int = b
          |val a: int = fa()
          |val b: int = fb()
          |val c: int = fc()""".stripMargin
      )

      e should include("its value needs")
      e should include("whose value needs")
    }

    // Mutual recursion among the *functions* an initializer calls is not a cycle among the `val`s:
    // what is being accumulated is a set of reads, and a function already walked adds nothing.
    "recursion in the functions an initializer calls is not a cycle" in {
      val src =
        """even(n: int) -> bool = if n == 0 then true else odd(n - 1)
          |odd(n: int) -> bool = if n == 0 then false else even(n - 1)
          |start() -> int = if even(10) then 1 else 0
          |val flag: int = start()
          |print(flag)""".stripMargin

      run(src) shouldBe "1\n"
    }

    // The initializer may allocate on its way to the value even though the value itself may not
    // carry a reference — the region it runs in lets go of the buffer before the next one starts.
    "an initializer may allocate on the way to a plain value" in {
      val src =
        """total() -> int
          |    var b: &Buf[int] = buf()
          |    for i in 0..<5 do b.push(i)
          |    var s = 0
          |    for x in b.view()
          |        s += x
          |    s
          |val n: int = total()
          |print(n)""".stripMargin

      run(src) shouldBe "10\n"
    }

    // A call through a method table is followed by taking every function that trait's tables put in
    // the slot. This program's `val` reads another one only through such a call, so getting it wrong
    // reads a zero rather than the value.
    "a read through a trait object is still ordered" in {
      val src =
        """trait Source
          |    get(self) -> int
          |struct FromBase
          |    tag: int
          |impl Source for FromBase
          |    get(self) -> int = base * 7
          |ask() -> int
          |    var f = FromBase(0)
          |    var s: *Source = &f
          |    s.get()
          |seed() -> int = 6
          |val answer: int = ask()
          |val base: int = seed()
          |print(base, answer)""".stripMargin

      run(src) shouldBe "6 42\n"
    }

    // A `val` nothing reads is still filled: it is the declaration that was written, and a
    // side-effecting initializer is the only way to see the difference.
    "an unread computed 'val' is still filled" in {
      val src =
        """noisy() -> int
          |    print("filled")
          |    1
          |val unread: int = noisy()
          |print("done")""".stripMargin

      run(src) shouldBe "filled\ndone\n"
    }

    // Two independent ones keep the order they were declared in, which is the only thing left to
    // decide once the dependencies have had their say.
    "independent initializers run in declaration order" in {
      val src =
        """say(s: string) -> int
          |    print(s)
          |    0
          |val first: int = say("one")
          |val second: int = say("two")
          |print(first + second)""".stripMargin

      run(src) shouldBe "one\ntwo\n0\n"
    }

    // A local `val` is untouched by any of this: it is a frame's binding, initialized where it
    // stands, and it may hold whatever a `var` may.
    "a local 'val' still holds what a module one may not" in {
      val src =
        """head() -> int
          |    var b: &Buf[int] = buf()
          |    b.push(3)
          |    val xs = b.view()
          |    xs[0]
          |print(head())""".stripMargin

      run(src) shouldBe "3\n"
    }

    // The value may be produced by an expression that is not a call at all — what decides is whether
    // the object file could have carried it, not what shape the source took.
    "an arithmetic initializer is computed, not folded" in {
      val out = ir("const w: int = 3\nval n: int = w * w\nprint(n)")

      out should include("@n = private global i32 zeroinitializer")
    }

    // A `val` whose initializer traps takes the program down, and it does so before a statement of
    // the program's own has run — which is what "before anything else" has to mean if it means
    // anything. What is observable is the status, since a trap says nothing.
    "an initializer that traps stops the program" in {
      exits(
        """boom() -> int
          |    var xs: [2]int
          |    var i = 5
          |    xs[i]
          |val n: int = boom()
          |print("never")""".stripMargin
      )
    }

    // The whole point of the plain-data rule seen from the far side: a struct whose field is a
    // reference is refused even though the struct itself is a value.
    "a struct with a reference field cannot be a 'val'" in {
      val src =
        """struct Holder
          |    it: &int
          |end Holder
          |mk() -> Holder = Holder(3)
          |val h: Holder = mk()""".stripMargin

      err(src) should include("plain data")
    }

    // A computed `val` may not be sliced either, for the reason a constant one may not: the view
    // would not carry the read-only property.
    "slicing a computed one is refused as slicing a written-down one is" in {
      err("build() -> [4]int = [1, 2, 3, 4]\nval k: [4]int = build()\nvar s = k[1..<3]") should
        include("cannot be sliced")
    }

    // A comparison is the one operator that does *not* become a call — the method rides on the
    // comparison node instead, so a walk that followed only calls would miss the read behind it.
    // `flag` is declared first, so getting this wrong reads a zero and flips the answer.
    "a read behind a comparison's dispatch is followed" in {
      val src =
        """struct Rank
          |    n: int
          |impl Ord for Rank
          |    lt(self, o: Rank) -> bool = self.n + bias < o.n
          |pick() -> int = if Rank(1) < Rank(3) then 1 else 0
          |seed() -> int = 5
          |val flag: int = pick()
          |val bias: int = seed()
          |print(bias, flag)""".stripMargin

      run(src) shouldBe "5 0\n"
    }

    // A contract clause is part of the function without being part of its body, so it is the other
    // place a read can hide. Getting the order wrong here traps rather than printing the wrong
    // number, which is the sharper failure of the two.
    "a read inside a precondition is followed" in {
      val src =
        """guarded(x: int) -> int
          |    require x < limit
          |    x * 2
          |seed() -> int = 4
          |val v: int = guarded(3)
          |val limit: int = seed()
          |print(v)""".stripMargin

      run(src) shouldBe "6\n"
    }

    // The claim of `13 §7` that this rests on, checked from the far side: a `val` is defined by
    // having an address, and a value with no representation has nothing to put one on. Before
    // initializers could be computed this was unreachable, since no constant tree is a `unit`.
    "a 'val' of a type that occupies nothing is refused" in {
      val e = err("noise() -> unit\n    print(\"hi\")\nval u: unit = noise()")

      e should include("occupies nothing")
      e should include("no storage")
    }

    // `13 §6`'s claim, which is what makes cross-module ordering need no rule of its own: the edge a
    // `val` reference makes is an ordinary module edge, so two modules reading each other's tables
    // is refused as a module cycle rather than reaching the `val` sort at all.
    "two modules reading each other's tables is a module cycle" in {
      val e = errIn(
        ("", "main.sysl", "print(up.a)"),
        ("up", "up.sysl",
          """module up
            |from_down() -> int = down.b + 1
            |val a: int = from_down()
            |""".stripMargin),
        ("down", "down.sysl",
          """module down
            |from_up() -> int = up.a + 1
            |val b: int = from_up()
            |""".stripMargin),
      )

      e should include("modules may not depend on each other")
    }

    // `13 §7` says a cycle between *constants* is reported at the declaration, naming the loop. It
    // is a different mechanism — a constant folds and never runs — and it says so its own way, one
    // report per declaration rather than one per loop.
    "a cycle between constants is still reported at the declaration" in {
      err("const a: int = b\nconst b: int = a") should include("'a' is defined in terms of itself: a → b → a")
    }

    // Iterating one reads its storage, which is what having an address is for.
    "a computed table can be walked" in {
      val src =
        """build() -> [4]int
          |    var t: [4]int
          |    for i in 0usize..<4
          |        t[i] = int(i) * 3
          |    t
          |val k: [4]int = build()
          |var s = 0
          |for x in k
          |    s += x
          |print(s)""".stripMargin

      run(src) shouldBe "18\n"
    }
  }
}
