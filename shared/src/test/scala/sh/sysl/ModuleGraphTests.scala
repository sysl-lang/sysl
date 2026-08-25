package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** The module graph is acyclic (`reference/modules.md § The module graph is acyclic`).
 *
 * What the rule is about is the **reference** graph: a qualified path reaches another module with
 * no import at all, so an edge is whatever resolution found, and an import adds one of its own. The
 * cases below are therefore mostly about *how* a dependency was written — a call, a type, a bound,
 * an instantiation, an import that bought a name and never spent it — since each of those is a way
 * to make an edge that a header scan would not see.
 */
class ModuleGraphTests extends AnyFreeSpec with CodegenSupport with RunSupport {

  private val cycle = "modules may not depend on each other, directly or through a chain"

  "two modules may not depend on each other" - {
    "when each calls into the other" in {
      val e = errIn(
        ("", "main.sysl", "print(a.f())"),
        ("a", "a.sysl", "module a\nf() -> int = b.g()\nh() -> int = 1"),
        ("b", "b.sysl", "module b\ng() -> int = a.h()"),
      )

      e should include(s"'a' depends on 'b', which depends on 'a' — $cycle")
    }

    // The reference that begins the chain is the one to point at: from there a reader can follow
    // the rest of the message through the modules it names. It lands on the *member* being reached
    // across the boundary — `b.g()`'s `g` — rather than on the dot it used to sit on. The module
    // name would arguably be better still, since the message is about modules; that would be the
    // analyzer choosing this diagnostic's position rather than taking the node's.
    "and the diagnostic points at the reference that begins the chain" in {
      errIn(
        ("", "main.sysl", "print(a.f())"),
        ("a", "a.sysl", "module a\nf() -> int = b.g()\nh() -> int = 1"),
        ("b", "b.sysl", "module b\ng() -> int = a.h()"),
      ) should include("--> a.sysl:2:16")
    }

    "when one names the other's type" in {
      errIn(
        ("", "main.sysl", "print(1)"),
        ("a", "a.sysl", "module a\nstruct P\n    n: int\nuse(q: b.Q) -> int = q.p.n"),
        ("b", "b.sysl", "module b\nstruct Q\n    p: a.P"),
      ) should include(s"'a' depends on 'b', which depends on 'a' — $cycle")
    }

    "when one names the other's trait as a bound" in {
      errIn(
        ("", "main.sysl", "print(1)"),
        ("a", "a.sysl", "module a\ntrait Marker\n    tag(self) -> int\nuse() -> int = b.n()"),
        ("b", "b.sysl", "module b\nf[T: a.Marker](x: T) -> int = x.tag()\nn() -> int = 7"),
      ) should include(s"'a' depends on 'b', which depends on 'a' — $cycle")
    }

    // An instantiation is resolved long after the declaration that asked for it, which is why the
    // check waits for the end of the walk rather than running beside hoisting.
    "when the only reference back is a generic instantiated in a body" in {
      errIn(
        ("", "main.sysl", "print(1)"),
        ("a", "a.sysl", "module a\nstruct Box[T]\n    v: T\nuse() -> int = b.n()"),
        ("b", "b.sysl", "module b\nn() -> int =\n    var x: a.Box[int] = a.Box(7)\n    x.v"),
      ) should include(s"'a' depends on 'b', which depends on 'a' — $cycle")
    }

    "when each imports the other" in {
      errIn(
        ("", "main.sysl", "print(a.f())"),
        ("a", "a.sysl", "module a\nimport b.{g}\nf() -> int = g()"),
        ("b", "b.sysl", "module b\nimport a.{h}\ng() -> int = h()\nh() -> int = 1"),
      ) should include(s"'a' depends on 'b', which depends on 'a' — $cycle")
    }

    // An import is a dependency whether or not the shorter spelling it bought is ever written: a
    // file's imports are meant to be readable as what it needs, and a dependency that came and went
    // with a use would not be.
    "even where the import is never used" in {
      errIn(
        ("", "main.sysl", "print(b.g())"),
        ("a", "a.sysl", "module a\nimport b.*\nf() -> int = 1"),
        ("b", "b.sysl", "module b\ng() -> int = a.f()"),
      ) should include(s"'a' depends on 'b', which depends on 'a' — $cycle")
    }

    "and a wildcard and a qualified path are one edge each" in {
      errIn(
        ("", "main.sysl", "print(b.g())"),
        ("a", "a.sysl", "module a\nimport b.*\nf() -> int = 1"),
        ("b", "b.sysl", "module b\ng() -> int = a.f()"),
      ) should include("--> a.sysl:2:1")
    }
  }

  "a chain of any length closes the same way" - {
    "three modules" in {
      errIn(
        ("", "main.sysl", "print(a.f())"),
        ("a", "a.sysl", "module a\nf() -> int = b.g()\nh() -> int = 1"),
        ("b", "b.sysl", "module b\ng() -> int = c.k()"),
        ("c", "c.sysl", "module c\nk() -> int = a.h()"),
      ) should include(s"'a' depends on 'b', which depends on 'c', which depends on 'a' — $cycle")
    }

    // Which module a walk *reaches* a cycle at is an accident of where it started, so the message
    // begins at the first of the modules on it by name — here `b`, though the walk arrives from `a`.
    "and the message begins at the first module on the cycle by name" in {
      errIn(
        ("", "main.sysl", "print(a.f())"),
        ("a", "a.sysl", "module a\nf() -> int = b.g()"),
        ("b", "b.sysl", "module b\ng() -> int = c.k()\nm() -> int = 1"),
        ("c", "c.sysl", "module c\nk() -> int = b.m()"),
      ) should include(s"'b' depends on 'c', which depends on 'b' — $cycle")
    }

    "a module reached only through the cycle is not named in it" in {
      errIn(
        ("", "main.sysl", "print(a.f())"),
        ("a", "a.sysl", "module a\nf() -> int = b.g()"),
        ("b", "b.sysl", "module b\ng() -> int = c.k()\nm() -> int = 1"),
        ("c", "c.sysl", "module c\nk() -> int = b.m()"),
      ) should not include "'a' depends on"
    }

    // Reporting a cycle breaks it, so an unrelated one somewhere else in the graph is found too
    // rather than waiting for the first to be fixed.
    "and two unrelated cycles are both reported" in {
      val e = errIn(
        ("", "main.sysl", "print(1)"),
        ("a", "a.sysl", "module a\nf() -> int = b.g()\nh() -> int = 1"),
        ("b", "b.sysl", "module b\ng() -> int = a.h()"),
        ("c", "c.sysl", "module c\nf() -> int = d.g()\nh() -> int = 1"),
        ("d", "d.sysl", "module d\ng() -> int = c.h()"),
      )

      e should include(s"'a' depends on 'b', which depends on 'a' — $cycle")
      e should include(s"'c' depends on 'd', which depends on 'c' — $cycle")
    }

    "and one module on two cycles is told about each of them" in {
      val e = errIn(
        ("", "main.sysl", "print(1)"),
        ("a", "a.sysl", "module a\nf() -> int = b.g() + c.k()\nz() -> int = 1"),
        ("b", "b.sysl", "module b\ng() -> int = a.z()"),
        ("c", "c.sysl", "module c\nk() -> int = a.z()"),
      )

      e should include(s"'a' depends on 'b', which depends on 'a' — $cycle")
      e should include(s"'a' depends on 'c', which depends on 'a' — $cycle")
    }
  }

  "a module and a module beneath it are two modules" - {
    "so they may not depend on each other either" in {
      errIn(
        ("", "main.sysl", "print(1)"),
        ("a", "a.sysl", "module a\nf() -> int = 1\nuse() -> int = a.b.g()"),
        ("a.b", "b.sysl", "module a.b\ng() -> int = a.f()"),
      ) should include(s"'a' depends on 'a.b', which depends on 'a' — $cycle")
    }

    "though one depending on the other is ordinary" in {
      runIn(
        ("", "main.sysl", "print(a.b.g())"),
        ("a", "a.sysl", "module a\nf() -> int = 21"),
        ("a.b", "b.sysl", "module a.b\ng() -> int = a.f() * 2"),
      ) shouldBe "42\n"
    }
  }

  "a graph with no cycle in it is left alone" - {
    "a chain" in {
      runIn(
        ("", "main.sysl", "print(a.f())"),
        ("a", "a.sysl", "module a\nf() -> int = b.g() + 1"),
        ("b", "b.sysl", "module b\ng() -> int = c.k() + 1"),
        ("c", "c.sysl", "module c\nk() -> int = 40"),
      ) shouldBe "42\n"
    }

    // Two paths to one module is not two visits to it: a diamond is the shape a walk that marked
    // nothing as settled would take exponential time on, and it is a perfectly good DAG.
    "a diamond" in {
      runIn(
        ("", "main.sysl", "print(top.f())"),
        ("top", "t.sysl", "module top\nf() -> int = left.g() + right.h()"),
        ("left", "l.sysl", "module left\ng() -> int = base.n()"),
        ("right", "r.sysl", "module right\nh() -> int = base.n() * 2"),
        ("base", "n.sysl", "module base\nn() -> int = 14"),
      ) shouldBe "42\n"
    }

    // The standard module is auto-imported everywhere, so it is outside the dependency graph
    // (`reference/modules.md § The module graph is acyclic`) — if a use of `print` were an edge,
    // every named module would depend on it and the library's own files would depend on themselves.
    "a module that prints, reached from a root file that prints" in {
      runIn(
        ("", "main.sysl", "print(geom.twice(21))"),
        ("geom", "g.sysl", "module geom\ntwice(n: int) -> int =\n    print(\"in geom\")\n    n * 2"),
      ) shouldBe "in geom\n42\n"
    }

    // Cycles *within* a module carry no ceremony at all: its files are one scope, so this is the
    // mutual recursion `12 §4` always allowed, spread across two files.
    "mutual recursion across the files of one module" in {
      runIn(
        ("", "main.sysl", "print(m.even(4))"),
        ("m", "x.sysl", "module m\neven(n: int) -> int = if n == 0 then 1 else odd(n - 1)"),
        ("m", "y.sysl", "module m\nodd(n: int) -> int = if n == 0 then 0 else even(n - 1)"),
      ) shouldBe "1\n"
    }

    "a module named from two others that do not name each other" in {
      runIn(
        ("", "main.sysl", "print(a.f() + b.g())"),
        ("a", "a.sysl", "module a\nf() -> int = base.n()"),
        ("b", "b.sysl", "module b\ng() -> int = base.n() + 1"),
        ("base", "n.sysl", "module base\nn() -> int = 20"),
      ) shouldBe "41\n"
    }

    // A dependency is something a *file* wrote. The compiler copies a trait's default into every
    // implementing type, so the trait's module ends up holding a declaration whose receiver names
    // the implementing module — which is a re-spelling of what the `impl` block already said, not a
    // second dependency running the other way.
    "a trait whose default is copied into another module's type" in {
      runIn(
        // The trait is imported and the struct is not: `twice` comes with `a.Show`
        // (`reference/modules.md § Visibility`), while `b.P` is named where it is used.
        ("", "main.sysl", "import a.Show\nprint(b.P(1).twice())"),
        ("a", "a.sysl", "module a\ntrait Show\n    n(self) -> int\n    twice(self) -> int = self.n() * 2"),
        ("b", "b.sysl", "module b\nstruct P[T]\n    v: T\nimpl[T] a.Show for P[T]\n    n(self) -> int = 21"),
      ) shouldBe "42\n"
    }

    "and a root file that names several modules depends on them all, being named by none" in {
      runIn(
        ("", "main.sysl", "print(a.f() + b.g())"),
        ("a", "a.sysl", "module a\nf() -> int = 20"),
        ("b", "b.sysl", "module b\ng() -> int = 22"),
      ) shouldBe "42\n"
    }
  }
}
