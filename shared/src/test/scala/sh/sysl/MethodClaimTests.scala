package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** What `08-methods.md` claims, run rather than read.
 *
 * The chapter's ordinary surface has suites of its own — `MethodRunTests`, `MethodParserTests`,
 * `AnalyzerMemberErrorTests`, `MultipleImplementationTests`, `MemberVisibilityTests`. What this one
 * covers is the two sentences that turned out not to be true of the compiler, and the probes that
 * confirmed the rest.
 *
 * The first is `§ Built-in members`: *"a member declared as `len` on a slice or `bytes` on a string
 * would be registered and never found, so it is refused at the declaration"*. Every written-out
 * sequence was refused — `[]int`, `[3]int`, `string` — and the **shape**, `impl[T] Sized for []T`,
 * was accepted and then never found, which is the one spelling the sentence names. `Self` is not a
 * type while a shape's members are hoisted, so the type-keyed guard had nothing to ask.
 *
 * The second is `§ Calling a method`: *"It stays one level: a `**Point` is not walked for you"*. It
 * does stay one level, and said so by naming the type left after the one dereference and reporting a
 * method that does not exist — the same shape the field selection had, and fixed the same way.
 */
class MethodClaimTests extends AnyFreeSpec with RunSupport with CodegenSupport {

  /** One type carrying a property and a method that compute the same way, so that the only thing
   * differing between the two halves of a case below is which form is written.
   */
  private val node =
    """struct Node
      |    n: int
      |
      |    doubled -> int = self.n * 2
      |
      |    scaled(self, k: int) -> int = self.n * k
      |""".stripMargin

  private val point =
    """struct Point
      |    x: int
      |    y: int
      |
      |    dist(self) -> int = self.x + self.y
      |""".stripMargin

  private val sized =
    """trait Sized
      |    len -> usize
      |""".stripMargin

  "a compiler-provided member may not be hidden by one a program declares" - {
    "not on a slice written out" in {
      err(s"${sized}impl Sized for []int\n    len -> usize = 99usize") should
        include("'len' is a member the compiler provides for []int")
    }

    "nor on a fixed array, nor on a string" in {
      err(s"${sized}impl Sized for [3]int\n    len -> usize = 99usize") should
        include("'len' is a member the compiler provides for [3]int")
      err(s"${sized}impl Sized for string\n    len -> usize = 99usize") should
        include("'len' is a member the compiler provides for string")
    }

    """and not on the shape either, which was accepted and left unreachable — 'xs.len' answered
      |with the built-in while the declared member sat in the table""".stripMargin in {
      val e = err(s"${sized}impl[T] Sized for []T\n    len -> usize = 99usize")

      e should include("'len' is a member the compiler provides for every slice")
      e should include("a member of this name would hide it")
    }

    "an array shape is the same answer" in {
      err(s"${sized}impl[T] Sized for [3]T\n    len -> usize = 99usize") should
        include("is a member the compiler provides for every array of 3")
    }

    "a string's other provided members are refused the same way" in {
      err("trait Raw\n    bytes -> []u8\nimpl Raw for string\n    bytes -> []u8 = [0u8]") should
        include("'bytes' is a member the compiler provides for string")
    }

    "while a name the compiler does not provide is an ordinary member of the shape" in {
      run("trait Total\n    total(self) -> int\nimpl[T] Total for []T\n    total(self) -> int = 7\n" +
        "var xs: []int = [1, 2, 3]\nprint(xs.total(), xs.len)") shouldBe "7 3\n"
    }
  }

  "a call reaches through one level of indirection, as selection does" - {
    "so a method is reached through a '&T' and a '*T' with nothing written" in {
      run(s"${point}var v = Point(1, 2)\nvar p = &v\nvar r: &Point = Point(3, 4)\nprint(p.dist(), r.dist())")
        .shouldBe("3 7\n")
    }

    "and a '**T' is told the reach stops short, not that the method is missing" in {
      val e = err(s"${point}var v = Point(1, 2)\nvar p = &v\nvar pp = &p\nprint(pp.dist())")

      e should include("a method call reaches through one level of indirection and **Point has more")
      e should include("'(*x).dist(…)'")
      e should not include "has no method"
    }

    "while the written dereference gets through" in {
      run(s"${point}var v = Point(1, 2)\nvar p = &v\nvar pp = &p\nprint((*pp).dist())") shouldBe "3\n"
    }

    "and a type that genuinely has no such method keeps the plain complaint" in {
      err(s"${point}var v = Point(1, 2)\nprint(v.nope())") should include("has no method 'nope'")
    }
  }

  "the receiver rules the chapter states, probed and holding" - {
    "a '*self' method on a temporary has nothing to point at, and says so" in {
      err(s"struct P\n    x: int\n    bump(*self)\n        self.x += 1\nmk() -> P = P(1)\nmk().bump()") should
        include("'*self' needs a variable, field, or dereference to point at")
    }

    "'self' is reserved, so it is not a name a program may bind" in {
      err("var self = 5\nprint(self)") shouldNot be(empty)
    }

    "a property declares no type parameters, there being no read to fix them at" in {
      err("struct Box[T]\n    v: T\n    zero[U] -> int = 7\nvar b = Box(1)\nprint(b.zero)") should
        include("a property takes no type parameters")
    }

    "an associated function needs a name in call position, which a composed type has not" in {
      err("trait Make\n    kind() -> int\nimpl[T] Make for []T\n    kind() -> int = 3") should
        include("is not a name a call could reach it through")
    }

    "a member may declare its own type parameters even where its type declares none" in {
      run("struct Counter\n    n: int\n    make[T](x: T) -> Counter = Counter(1)\n" +
        "var c = Counter.make(5)\nprint(c.n)") shouldBe "1\n"
    }
  }

  /** *"A property **is** a function with the parameter list left off"* (`§ Properties`) — asked of
   * every memory mode, which is the one place the claim had never been put.
   *
   * The trait surface of it is pinned test by test in `TraitPropertyRunTests` (a bound, a table, a
   * default, an enum, a built-in), and the body spellings in `MethodRunTests`. What none of them
   * crosses is `03`'s modes: a claim of the form *"a property behaves as a method does"* is
   * satisfied by any one receiver, so a suite dense at the plain receiver is no evidence at all
   * about the others. Both forms are therefore asked of each mode **in one test**.
   */
  "a property is read through whatever a method is called through" - {
    "a counted reference answers both" in {
      run(node +
        "prop(x: &Node) -> int = x.doubled\n" +
        "meth(x: &Node) -> int = x.scaled(2)\n" +
        "var r: &Node = Node(7)\nprint(prop(r), meth(r))") shouldBe "14 14\n"
    }

    "a raw pointer answers both" in {
      run(node +
        "prop(p: *Node) -> int = p.doubled\n" +
        "meth(p: *Node) -> int = p.scaled(2)\n" +
        "var owned = Node(9)\nprint(prop(&owned), meth(&owned))") shouldBe "18 18\n"
    }

    // The mode that answers neither, which is the discriminating half: `get()` is the only thing to
    // ask a weak reference, so both forms are refused — and each has to say so in its own words
    // rather than one of them falling through to a message about a name that does not exist.
    "a weak reference answers neither, and says the same thing about both" in {
      val onProp = err(node + "prop(w: weak Node) -> int = w.doubled\nvar r: &Node = Node(7)\nprint(prop(r))")
      val onMeth = err(node + "meth(w: weak Node) -> int = w.scaled(2)\nvar r: &Node = Node(7)\nprint(meth(r))")

      onProp should include("get()")
      onProp should include("doubled")
      onMeth should include("get()")
      onMeth should include("scaled")
    }
  }
}
