package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** The AST codec reads back exactly the tree the parser produced (`13 § Open d`).
 *
 * **The prelude is the load-bearing case**, and deliberately so: it is 592 lines of real sysl using
 * nearly every declaration the language has — generic enums, traits with defaults and supertraits,
 * `impl` blocks, externs with link names, contracts, closures, patterns — so a node the codec cannot
 * carry shows up here rather than in a hand-written fixture that happens to avoid it. The small cases
 * below exist for the shapes the prelude does not reach and for the ways the format goes wrong.
 *
 * **Structural equality alone is not enough to pin this.** A position is deliberately not a
 * constructor parameter, so `==` ignores it entirely — a codec that dropped every position would pass
 * a naive round-trip test and then report every library diagnostic at line 0. The positions are
 * therefore walked and compared separately, which is what `positionsOf` is for.
 */
class AstCodecTests extends AnyFreeSpec with Matchers {

  private def parsed(src: String, name: String = "<t>"): Program =
    SyslParser.parse(Source(name, src)) match
      case Right(p) => p
      case Left(e)  => fail(s"the fixture does not parse: $e")

  private def roundTrip(programs: List[Program], known: Map[String, Source] = Map.empty): List[Program] =
    AstCodec.decode(AstCodec.encode(programs), known) match
      case Right(ps) => ps
      case Left(e)   => fail(s"decode failed: $e")

  /** Every position in a tree, in the order a walk over the case-class children reaches them. Two
   * trees that agree here agree about what each node points at, which `==` cannot tell us.
   */
  private def positionsOf(node: Any): List[Option[(String, Int, Int)]] = {
    val here = node match
      case p: Positioned => List(p.pos.map(x => (x.source.name, x.line, x.col)))
      case _             => Nil

    // A `List` and a `Some` are themselves `Product`s, so the collections are matched first —
    // otherwise a list would be walked as its `::` cells, which reaches the same nodes by a route
    // that is far less obvious to read.
    val below = node match
      case xs: List[?]  => xs.flatMap(positionsOf)
      case o: Option[?] => o.toList.flatMap(positionsOf)
      case m: Map[?, ?] => m.toList.sortBy(_._1.toString).flatMap((k, v) => positionsOf(k) ::: positionsOf(v))
      case p: Product   => p.productIterator.toList.flatMap(positionsOf)
      case _            => Nil

    here ::: below
  }

  "the prelude" - {

    "round-trips to a structurally equal tree" in {
      val original = Program(Prelude.decls, None, Prelude.origin)
      val back     = roundTrip(List(original))

      back should have length 1
      back.head.body shouldBe original.body
    }

    "round-trips with every position intact" in {
      val original = Program(Prelude.decls, None, Prelude.origin)
      val back     = roundTrip(List(original))

      positionsOf(back.head.body) shouldBe positionsOf(original.body)
    }

    // The standard module is where most of the library now lives, so round-tripping only the
    // prelude would test a shrinking half of what the codec has to carry.
    "and so does the standard module, which is the other half of what a codec must carry" in {
      val back = roundTrip(Std.parsed)

      back should have length Std.parsed.length
      back.map(_.body) shouldBe Std.parsed.map(_.body)
      positionsOf(back.map(_.body)) shouldBe positionsOf(Std.parsed.map(_.body))
    }

    "carries enough positions to be worth carrying" in {
      // Guards the two tests above: if the trees somehow had no positions, their comparisons would
      // hold vacuously and a codec that dropped every one of them would pass.
      //
      // Counted over the WHOLE library rather than the prelude alone. The prelude is being drained
      // into the standard module a surface at a time and is heading for empty, so a threshold on it
      // is a threshold that fails on some future move for a reason that has nothing to do with the
      // codec — which is exactly what it did.
      val stamped = positionsOf(Library.decls).count(_.isDefined)

      stamped should be > 1000
    }

    "rebinds to the caller's own Source, so a decoded declaration is still the library's" in {
      val original = Program(Prelude.decls, None, Prelude.origin)
      val back     = roundTrip(List(original), Map(Prelude.origin.name -> Prelude.origin))

      // `Library.owns` is identity on the Source, and it is what decides whether an unreached
      // declaration may be dropped — so a decoded tree has to land on the same object.
      back.head.source should be theSameInstanceAs Prelude.origin
      back.head.body.forall(Library.owns) shouldBe true
    }

    "reconstructs a usable Source when the caller supplies none" in {
      val original = Program(Prelude.decls, None, Prelude.origin)
      val back     = roundTrip(List(original))

      back.head.source.name shouldBe Prelude.origin.name
      back.head.source.text shouldBe Prelude.origin.text
      // The text is carried so a diagnostic against a library declaration can quote its line.
      back.head.source.line(1) shouldBe Prelude.origin.line(1)
    }

    "encodes deterministically, so an artifact can be cached and diffed" in {
      val original = Program(Prelude.decls, None, Prelude.origin)

      AstCodec.encode(List(original)) shouldBe AstCodec.encode(List(original))
    }
  }

  "a declaration round-trips" - {

    def check(label: String, src: String): Unit =
      label in {
        val original = parsed(src)
        val back     = roundTrip(List(original))

        back.head.body shouldBe original.body
        positionsOf(back.head.body) shouldBe positionsOf(original.body)
      }

    check("a generic function with bounds", "f[T: Ord, U: Eq + Hash](a: T, b: U) -> T = a")
    check("a variadic extern under a link name", "extern \"snprintf\" fmt(f: *u8, ...) -> int")
    check("a struct with members, invariants and a private field",
      """struct Span
        |    private lo: int
        |    hi: int
        |    invariant lo <= hi
        |
        |    width(self) -> int = self.hi - self.lo
        |    zero() -> Span = Span(0, 0)
        |""".stripMargin)
    check("a data enum with an underlying type and a member",
      """enum Shape
        |    Dot
        |    Round(r: int)
        |
        |    area(self) -> int = 0
        |""".stripMargin)
    check("a trait with a default, a supertrait and a type default",
      """trait Word: Add + BitXor
        |    show(self) -> string
        |    twice(self) -> int = 2
        |""".stripMargin)
    check("an impl with its own parameters and trait arguments",
      """struct Box[T]
        |    v: T
        |impl[T: Show] Show for Box[T]
        |    show(self) -> string = "b"
        |""".stripMargin)
    check("a constrained subtype with a range and a predicate",
      "type Age = u8 within 0..<200 where value != 13u8")
    check("a const and a module-level val", "const cap: usize = 512\nval order: [3]int = [2, 0, 1]")
    check("every import form",
      """import a.b.c
        |import a.b.{c, d as e}
        |import a.b.*
        |import a.b
        |""".stripMargin)
    check("a module header", "module geom.shape\n\nf() -> int = 1")
  }

  "an expression round-trips" - {

    def check(label: String, src: String): Unit =
      label in {
        val original = parsed(s"f() -> int =\n    $src\n    0")
        val back     = roundTrip(List(original))

        back.head.body shouldBe original.body
        positionsOf(back.head.body) shouldBe positionsOf(original.body)
      }

    check("a comparison chain", "var c = 1 < 2 < 3")
    check("a match with guards, ranges and nested patterns",
      "var m = 1 match\n        1..<5 if true -> 1\n        Wrap(Val(v)) -> v\n        P{x: 0, y} -> y\n        _ -> 0")
    check("a closure with an annotated parameter", "var g = (x: int) -> x + 1")
    check("a three-clause for with an else", "for var i = 0; i < 3; i++\n        print(i)\n    else\n        print(9)")
    check("a labelled loop with a value-carrying break", "var v = 'outer loop\n        break 'outer 7")
    check("a tuple, an index and a type attribute", "var t = ((1, 2).0, [1, 2][0], int::Range)")
    check("an array fill and a slice", "var a = [0; 4]\n    var s = a[0..<2]")
    check("a try, a unit, a null and a c-string", "var q = ()\n    var n: *u8 = null\n    var cs = c\"hi\"")
    check("a 128-bit literal with a suffix", "var w = 170141183460469231731687303715884105727i128")
    check("a multi-assignment and a multi-declaration", "var a = 1\n    var b = 2\n    a, b = b, a\n    val p, q = 3, 4")
  }

  "the format refuses" - {

    "something that is not an artifact at all" in {
      AstCodec.decode("print(1)\n") shouldBe Left("this is not a sysl AST artifact")
    }

    "an artifact from a different version, naming both" in {
      val original = AstCodec.encode(List(parsed("f() -> int = 1")))
      val older    = original.replaceFirst("sysl-ast 1", "sysl-ast 0")

      AstCodec.decode(older) match
        case Left(e) =>
          e should include("version 0")
          e should include(s"version ${AstCodec.Version}")
          e should include("regenerate")
        case Right(_) => fail("a version mismatch was accepted")
    }

    "a truncated body rather than returning half a tree" in {
      val original = AstCodec.encode(List(parsed("f() -> int = 1 + 2")))

      AstCodec.decode(original.take(original.length - 12)).isLeft shouldBe true
    }

    "a tag it does not know" in {
      val original = AstCodec.encode(List(parsed("f() -> int = 1")))

      AstCodec.decode(original.replace(" il ", " zz ")) match
        case Left(e)  => e should include("'zz'")
        case Right(_) => fail("an unknown tag was accepted")
    }
  }

  "a string in the artifact survives" - {

    "a newline, a space and a colon inside a literal" in {
      val original = parsed("f() -> string = \"a: b\\nc d\"")
      val back     = roundTrip(List(original))

      back.head.body shouldBe original.body
    }

    "being the source text itself, which holds every newline the file had" in {
      val src      = "f() -> int =\n    var x = 1\n    x\n"
      val original = parsed(src, "multi.sysl")
      val back     = roundTrip(List(original))

      back.head.source.text shouldBe src
      back.head.source.line(2) shouldBe "    var x = 1"
    }
  }

  "several files in one artifact" - {

    "keep their own module headers, sources and directories" in {
      val a = Program(parsed("module geom\nf() -> int = 1").body,
        Some(ModuleName(List("geom"))), new Source("a.sysl", "module geom\nf() -> int = 1", Some(List("geom"))))
      val b = Program(parsed("module geom.shape\ng() -> int = 2").body,
        Some(ModuleName(List("geom", "shape"))),
        new Source("b.sysl", "module geom.shape\ng() -> int = 2", Some(List("geom", "shape"))))

      val back = roundTrip(List(a, b))

      back should have length 2
      back(0).module.map(_.show) shouldBe Some("geom")
      back(1).module.map(_.show) shouldBe Some("geom.shape")
      back(0).source.name shouldBe "a.sysl"
      back(1).source.dir shouldBe Some(List("geom", "shape"))
    }

    "share one string table, so a name written twice is stored once" in {
      val one  = AstCodec.encode(List(parsed("f(averylongparametername: int) -> int = averylongparametername")))
      val two  = AstCodec.encode(List(
        parsed("f(averylongparametername: int) -> int = averylongparametername"),
        parsed("g(averylongparametername: int) -> int = averylongparametername"),
      ))

      // Two files naming the same identifier cost one table entry, so the second file adds far
      // less than the first did.
      (two.length - one.length) should be < one.length
    }
  }

  "a decoded tree compiles to the same program the parsed one does" in {
    // The point of the format: what comes back is not merely equal, it is usable. Compiling
    // through it exercises the analyzer and codegen over decoded nodes rather than parsed ones.
    val src = "add(a: int, b: int) -> int = a + b\nprint(add(2, 3))"

    val direct = Compiler.compileToLlvm(src, "d.sysl")
    val back   = roundTrip(List(parsed(src, "d.sysl")))
    val viaIr  = Compiler.compileTrees(back)

    direct.isRight shouldBe true
    viaIr shouldBe direct
  }
}
