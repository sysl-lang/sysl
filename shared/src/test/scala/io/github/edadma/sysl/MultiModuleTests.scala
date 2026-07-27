package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** A program of more than one module, and the fully-qualified path that reaches into one (`13 §3`).
 *
 * A public member is reachable by its module path with no import at all — import exists only to
 * shorten the reference — so this is the whole of cross-module access as it stands: what a path
 * reaches, what two modules may each call their own, and what a module may *not* see.
 */
class MultiModuleTests extends AnyFreeSpec with CodegenSupport with RunSupport {

  "a module path reaches" - {
    "a function" in {
      runIn(
        ("", "main.sysl", "print(geom.twice(21))"),
        ("geom", "g.sysl", "module geom\ntwice(n: int) -> int = n * 2"),
      ) shouldBe "42\n"
    }

    "a type, written where a type is asked for" in {
      runIn(
        ("", "main.sysl", "var p: geom.Point = geom.Point(3, 4)\nprint(p.x + p.y)"),
        ("geom", "g.sysl", "module geom\nstruct Point\n    x: int\n    y: int"),
      ) shouldBe "7\n"
    }

    "a method, which the receiver's own type finds without being told" in {
      runIn(
        ("", "main.sysl", "print(geom.Point(3, 4).norm())"),
        ("geom", "g.sysl",
         "module geom\nstruct Point\n    x: int\n    y: int\n\n    norm(self) -> int = self.x + self.y"),
      ) shouldBe "7\n"
    }

    "an associated function" in {
      runIn(
        ("", "main.sysl", "print(geom.Point.zero().x)"),
        ("geom", "g.sysl",
         "module geom\nstruct Point\n    x: int\n    y: int\n\n    zero() -> Point = Point(0, 0)"),
      ) shouldBe "0\n"
    }

    "an enum variant, through its enum" in {
      runIn(
        ("", "main.sysl",
         "var s: geom.Shape = geom.Shape.Round(7)\nmatch s\n    Dot -> print(0)\n    Round(r) -> print(r)"),
        ("geom", "g.sysl", "module geom\nenum Shape\n    Dot\n    Round(r: int)"),
      ) shouldBe "7\n"
    }

    "a nullary variant just as directly" in {
      runIn(
        ("", "main.sysl",
         "var s: geom.Shape = geom.Shape.Dot\nmatch s\n    Dot -> print(0)\n    Round(r) -> print(r)"),
        ("geom", "g.sysl", "module geom\nenum Shape\n    Dot\n    Round(r: int)"),
      ) shouldBe "0\n"
    }

    // A pattern names a variant of the scrutinee's own enum, so the module the enum came from is
    // already settled by the value being matched and needs no repeating.
    "and a match on one names its variants unqualified" in {
      runIn(
        ("", "main.sysl",
         "var s: geom.Shape = geom.Shape.Round(7)\nmatch s\n    Dot -> print(0)\n    Round(r) -> print(r)"),
        ("geom", "g.sysl", "module geom\nenum Shape\n    Dot\n    Round(r: int)"),
      ) shouldBe "7\n"
    }

    "a trait, as a bound" in {
      runIn(
        ("", "main.sysl", "loud[T: fmt.Show](x: T) -> int = x.show()\nprint(loud(Tag(5)))\n" +
          "struct Tag\n    n: int\nimpl fmt.Show for Tag\n    show(self) -> int = self.n * 2"),
        ("fmt", "f.sysl", "module fmt\ntrait Show\n    show(self) -> int"),
      ) shouldBe "10\n"
    }

    "a trait, as an object" in {
      runIn(
        ("", "main.sysl", "var s: &fmt.Show = Tag(5)\nprint(s.show())\n" +
          "struct Tag\n    n: int\nimpl fmt.Show for Tag\n    show(self) -> int = self.n * 2"),
        ("fmt", "f.sysl", "module fmt\ntrait Show\n    show(self) -> int"),
      ) shouldBe "10\n"
    }

    "a generic, instantiated from where it was named" in {
      runIn(
        ("", "main.sysl", "print(box.first(box.Pair(7, 9)))"),
        ("box", "b.sysl",
         "module box\nstruct Pair[T]\n    a: T\n    b: T\nfirst[T](p: Pair[T]) -> T = p.a"),
      ) shouldBe "7\n"
    }

    "however many segments the path has" in {
      runIn(
        ("", "main.sysl", "print(a.b.c.deep(1))"),
        ("a.b.c", "d.sysl", "module a.b.c\ndeep(n: int) -> int = n + 41"),
      ) shouldBe "42\n"
    }

    // A module `a` and a module `a.b` may both exist, so the prefix has to be the longest one that
    // names a module — otherwise `a.b.f` would be read as `a`'s `b`, which is a field of nothing.
    "and the longest prefix that names a module wins" in {
      runIn(
        ("", "main.sysl", "print(a.b.f())"),
        ("a", "x.sysl", "module a\ng() -> int = 1"),
        ("a.b", "y.sysl", "module a.b\nf() -> int = 2"),
      ) shouldBe "2\n"
    }
  }

  "two modules may each declare" - {
    "a type of the same name" in {
      runIn(
        ("", "main.sysl", "var p: geom.Point = geom.Point(3)\nvar q: text.Point = text.Point(4)\n" +
          "print(p.x + q.x)"),
        ("geom", "g.sysl", "module geom\nstruct Point\n    x: int"),
        ("text", "t.sysl", "module text\nstruct Point\n    x: int"),
      ) shouldBe "7\n"
    }

    "a function of the same name, emitted under distinct symbols" in {
      val out = irIn(
        ("", "main.sysl", "print(geom.size() + text.size())"),
        ("geom", "g.sysl", "module geom\nsize() -> int = 3"),
        ("text", "t.sysl", "module text\nsize() -> int = 4"),
      )

      out should include("define i32 @geom$size()")
      out should include("define i32 @text$size()")
    }

    // Variant names are unique within a module rather than across the program, since a bare
    // `Round(…)` is resolved against the module it is written in.
    "an enum variant of the same name" in {
      runIn(
        ("", "main.sysl",
         "var a: geom.Shape = geom.Shape.Round(1)\nvar b: text.Glyph = text.Glyph.Round(2)\n" +
           "match a\n    Round(r) -> print(r)\nmatch b\n    Round(r) -> print(r)"),
        ("geom", "g.sysl", "module geom\nenum Shape\n    Round(r: int)"),
        ("text", "t.sysl", "module text\nenum Glyph\n    Round(r: int)"),
      ) shouldBe "1\n2\n"
    }

    "and a member of the same name on each of their types" in {
      runIn(
        ("", "main.sysl", "print(geom.Point(3).size() + text.Point(4).size())"),
        ("geom", "g.sysl", "module geom\nstruct Point\n    x: int\n\n    size(self) -> int = self.x"),
        ("text", "t.sysl", "module text\nstruct Point\n    x: int\n\n    size(self) -> int = self.x * 2"),
      ) shouldBe "11\n"
    }
  }

  "what a module can see" - {
    // The prelude is in scope everywhere with no import; nothing else is (`13 §8`).
    "the prelude, wherever it is written" in {
      runIn(
        ("", "main.sysl", "print(m.label(2))"),
        ("m", "a.sysl",
         "module m\nlabel(n: int) -> string =\n    var o: Option[int] = Some(n)\n" +
           "    match o\n        Some(v) -> str(v)\n        None -> \"-\""),
      ) shouldBe "2\n"
    }

    "its own declarations, unqualified" in {
      runIn(
        ("", "main.sysl", "print(m.outer())"),
        ("m", "a.sysl", "module m\ninner() -> int = 5\nouter() -> int = inner() * 2"),
      ) shouldBe "10\n"
    }

    "its own declarations across its own files, unqualified" in {
      runIn(
        ("", "main.sysl", "print(m.outer())"),
        ("m", "a.sysl", "module m\nouter() -> int = inner() * 2"),
        ("m", "b.sysl", "module m\ninner() -> int = 5"),
      ) shouldBe "10\n"
    }

    // A module earns visibility by being named, and the root module has no name — so its
    // declarations are its own files' to use and nothing else can reach them.
    "and not the root module, which has no name to be reached through" in {
      errIn(
        ("", "main.sysl", "helper() -> int = 1\nprint(m.f())"),
        ("m", "a.sysl", "module m\nf() -> int = helper()"),
      ) should include("undefined function 'helper'")
    }

    "nor a sibling module's names unqualified" in {
      errIn(
        ("", "main.sysl", "print(m.f())"),
        ("m", "a.sysl", "module m\nf() -> int = other()"),
        ("other", "b.sysl", "module other\nother() -> int = 1"),
      ) should include("undefined function 'other'")
    }

    "nor a sibling module's types unqualified" in {
      errIn(
        ("", "main.sysl", "print(1)"),
        ("m", "a.sysl", "module m\nf(p: Point) -> int = p.x"),
        ("geom", "b.sysl", "module geom\nstruct Point\n    x: int"),
      ) should include("unknown type 'Point'")
    }
  }

  "a local binding shadows a module name" - {
    // `13 §3`: resolution is innermost-first, so a value bound to `geom` makes `geom.x` the field
    // read it looks like. This is also why the module graph cannot come from a textual scan.
    "so a dotted reference off it is a field read" in {
      runIn(
        ("", "main.sysl", "struct Holder\n    v: int\nvar geom = Holder(9)\nprint(geom.v)"),
        ("geom", "g.sysl", "module geom\nv() -> int = 1"),
      ) shouldBe "9\n"
    }

    "and the module is still reachable where nothing shadows it" in {
      runIn(
        ("", "main.sysl", "shadowed() -> int =\n    var geom = 1\n    geom\nprint(shadowed() + geom.v())"),
        ("geom", "g.sysl", "module geom\nv() -> int = 41"),
      ) shouldBe "42\n"
    }
  }

  "a path that names nothing" - {
    "in a module that exists is reported against the name" in {
      errIn(
        ("", "main.sysl", "print(geom.nope())"),
        ("geom", "g.sysl", "module geom\nf() -> int = 1"),
      ) should include("geom.nope")
    }

    "and a prefix that is no module at all is read as a value" in {
      errIn(
        ("", "main.sysl", "print(nowhere.f())"),
        ("geom", "g.sysl", "module geom\nf() -> int = 1"),
      ) should include("nowhere")
    }

    "a type reached through a module that has none of that name" in {
      errIn(
        ("", "main.sysl", "var p: geom.Nope = 1"),
        ("geom", "g.sysl", "module geom\nstruct Point\n    x: int"),
      ) should include("unknown type 'geom.Nope'")
    }

    "and a trait named where a type was asked for still says so" in {
      errIn(
        ("", "main.sysl", "var s: fmt.Show = 1"),
        ("fmt", "f.sysl", "module fmt\ntrait Show\n    show(self) -> int"),
      ) should include("is a trait")
    }
  }

  "a diagnostic names a foreign declaration by the path a program would write" - {
    "for a type" in {
      errIn(
        ("", "main.sysl", "var p: geom.Point = geom.Point(1, 2, 3)"),
        ("geom", "g.sysl", "module geom\nstruct Point\n    x: int\n    y: int"),
      ) should include("struct 'geom.Point'")
    }

    "for a mismatch between two modules' types of one name" in {
      errIn(
        ("", "main.sysl", "var p: geom.Point = text.Point(1)"),
        ("geom", "g.sysl", "module geom\nstruct Point\n    x: int"),
        ("text", "t.sysl", "module text\nstruct Point\n    x: int"),
      ) should include("text.Point")
    }

    "and for a bound a foreign trait supplies" in {
      errIn(
        ("", "main.sysl", "loud[T: fmt.Show](x: T) -> int = x.show()\nprint(loud(5))"),
        ("fmt", "f.sysl", "module fmt\ntrait Show\n    show(self) -> int"),
      ) should include("fmt.Show")
    }
  }

  "a pattern names a foreign type" - {
    "by its path, in the named form" in {
      runIn(
        ("", "main.sysl", "var p: geom.Point = geom.Point(3, 4)\nmatch p\n    geom.Point{x} -> print(x)"),
        ("geom", "g.sysl", "module geom\nstruct Point\n    x: int\n    y: int"),
      ) shouldBe "3\n"
    }

    "and in the positional form" in {
      runIn(
        ("", "main.sysl", "var p: geom.Point = geom.Point(3, 4)\nmatch p\n    geom.Point(x, y) -> print(x + y)"),
        ("geom", "g.sysl", "module geom\nstruct Point\n    x: int\n    y: int"),
      ) shouldBe "7\n"
    }

    "so the bare name does not match it" in {
      errIn(
        ("", "main.sysl", "var p: geom.Point = geom.Point(3, 4)\nmatch p\n    Point{x} -> print(x)"),
        ("geom", "g.sysl", "module geom\nstruct Point\n    x: int\n    y: int"),
      ) should include("'Point{…}' does not match a geom.Point value")
    }
  }

  "a body means what it meant where it was written" - {
    // The members an `impl` supplies are filed under the type it is for, which may be another
    // module's — so the module a name in one of those bodies resolves in travels with the body
    // rather than being read back off the key.
    "so an 'impl' in one module for a type in another reads its own module's names" in {
      runIn(
        ("", "main.sysl", "print(fmt.render())"),
        ("geom", "g.sysl", "module geom\nstruct Point\n    x: int"),
        ("fmt", "f.sysl",
         "module fmt\ntrait Show\n    show(self) -> int\nstruct Tag\n    n: int\n" +
           "impl Show for geom.Point\n    show(self) -> int = self.x + Tag(1).n\n" +
           "render() -> int = geom.Point(4).show()"),
      ) shouldBe "5\n"
    }

    // A default is copied into every implementing type, wherever those are, and its body is the
    // trait's source — so it reads the trait's module even in a copy made for a foreign type.
    "and a trait's default reads the trait's module in every copy of it" in {
      runIn(
        ("", "main.sysl",
         "struct P\n    n: int\nimpl fmt.Show for P\n    raw(self) -> int = self.n\nprint(P(5).show())"),
        ("fmt", "f.sysl",
         "module fmt\nhelper(n: int) -> int = n * 2\ntrait Show\n    raw(self) -> int\n" +
           "    show(self) -> int = helper(self.raw())"),
      ) shouldBe "10\n"
    }

    "and a generic type's bound on a foreign trait is met by the caller's own type" in {
      runIn(
        ("", "main.sysl",
         "print(box.Box(P(3)).get().n)\nstruct P\n    n: int\nimpl fmt.Show for P\n    show(self) -> int = self.n"),
        ("fmt", "f.sysl", "module fmt\ntrait Show\n    show(self) -> int"),
        ("box", "b.sysl", "module box\nstruct Box[T: fmt.Show]\n    v: T\n\n    get(self) -> T = self.v"),
      ) shouldBe "3\n"
    }
  }

  "what the module prefix does not reach" - {
    // An `extern`'s symbol names something the linker already has, which knows nothing about
    // sysl's modules — so it is pinned to what was written while the name a program calls it by
    // carries the module like any other.
    "an extern's symbol, which two modules may each declare" in {
      runIn(
        ("", "main.sysl", "print(a.n() + b.n())"),
        ("a", "a.sysl", "module a\nextern abs(v: int) -> int\nn() -> int = abs(0 - 3)"),
        ("b", "b.sysl", "module b\nextern abs(v: int) -> int\nn() -> int = abs(0 - 4)"),
      ) shouldBe "7\n"
    }

    "nor a scalar type name, which is no module's to shadow" in {
      runIn(
        ("", "main.sysl", "var x: int = 1\nprint(x + int.two())"),
        ("int", "i.sysl", "module int\ntwo() -> int = 2"),
      ) shouldBe "3\n"
    }
  }

  "a module and a type may not spell one path" - {
    // A dotted reference takes the longest prefix that names a module, so a module named for a type
    // of its parent would win `geom.Point.dist` outright and leave the member no spelling at all.
    // The keys stay distinct; what collides is the path a program writes.
    "since the module would take the whole of it" in {
      errIn(
        ("", "main.sysl", "print(geom.Point.dist())"),
        ("geom", "g.sysl", "module geom\nstruct Point\n    x: int\n\n    dist() -> int = 1"),
        ("geom.Point", "p.sysl", "module geom.Point\ndist() -> int = 2"),
      ) should include("'geom.Point' is also a module")
    }

    "which holds for an enum too, whose variants are reached the same way" in {
      errIn(
        ("", "main.sysl", "print(1)"),
        ("geom", "g.sysl", "module geom\nenum Shape\n    Dot"),
        ("geom.Shape", "s.sysl", "module geom.Shape\nf() -> int = 2"),
      ) should include("'geom.Shape' is also a module")
    }
  }

  "an enum reached through its module" - {
    "converts from an integer" in {
      runIn(
        ("", "main.sysl",
         "var c: geom.Color = geom.Color(1)\nmatch c\n    Red -> print(0)\n    Green -> print(1)"),
        ("geom", "g.sysl", "module geom\nenum Color\n    Red\n    Green"),
      ) shouldBe "1\n"
    }

    "and offers the fallible constructor beside it" in {
      runIn(
        ("", "main.sysl",
         "var c = geom.Color.try(5)\nmatch c\n    Some(v) -> print(1)\n    None -> print(0)"),
        ("geom", "g.sysl", "module geom\nenum Color\n    Red\n    Green"),
      ) shouldBe "0\n"
    }
  }

  "a path is an ordinary operand" - {
    "so it stands wherever an expression does" in {
      runIn(
        ("", "main.sysl", "for i in 0..<geom.limit() do print(i)"),
        ("geom", "g.sysl", "module geom\nlimit() -> int = 2"),
      ) shouldBe "0\n1\n"
    }

    // A directory that holds no source is no module, but the modules under it are still reached
    // through its name — the driver walks past it rather than stopping.
    "and a directory between two modules need hold no source of its own" in {
      runIn(
        ("", "main.sysl", "print(a.b.f())"),
        ("a.b", "y.sysl", "module a.b\nf() -> int = 7"),
      ) shouldBe "7\n"
    }
  }
}
