package sh.sysl

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** `opaque struct` — a type whose **layout** is withheld from every module but the one declaring it
 * (`15 §9`).
 *
 * Two things want it and they meet in the middle. A sysl library stabilizing its own surface wants
 * to add and reorder fields without anything downstream noticing; a binding to a C library wants
 * `*sqlite3` to be a type that a `*u8` cannot be mistaken for, where nobody in sysl knows the layout
 * at all. One rule covers both: outside the declaring module the type is **incomplete**, exactly as
 * C's `struct foo;` is, and the only thing that may be said about it is `*Name`.
 */
class OpaqueStructTests extends AnyFreeSpec with Matchers with RunSupport with CodegenSupport {

  /** A module declaring an opaque type with a real layout, plus the functions that are the only way
   * to do anything with one. Everything the type's shape is needed for happens in here; what leaves
   * is a `*Conn`, which is the whole downstream interface.
   */
  private val conn =
    """module net
      |
      |opaque struct Conn
      |    fd: int
      |    live: bool
      |end Conn
      |
      |fd_of(c: *Conn) -> int = c.fd
      |
      |sum(a: *Conn, b: *Conn) -> int = a.fd + b.fd
      |
      |here() -> int
      |    var c = Conn(7, true)
      |
      |    fd_of(&c)
      |end here
      |""".stripMargin

  /** The same type carrying both receiver forms, since which of them survives the boundary is the
   * question a caller of an opaque type actually has.
   */
  private val withMethods =
    """module net
      |
      |opaque struct Handle
      |    fd: int
      |
      |    through(*self) -> int = self.fd
      |
      |    copied(self) -> int = self.fd
      |end Handle
      |
      |make() -> int
      |    var h = Handle(9)
      |
      |    h.through()
      |end make
      |""".stripMargin

  private def withConn(main: String): String = runOf("net/conn.sysl" -> conn, "main.sysl" -> main)

  private def rejecting(main: String): String = errOf("net/conn.sysl" -> conn, "main.sysl" -> main)

  "inside the module that declares it, nothing changes" - {

    "it is built, held by value, and its fields are read" in {
      withConn("print(net.here())") shouldBe "7\n"
    }

    "a member may take it by value, which only this module could have written" in {
      runOf("net/conn.sysl" ->
        """module net
          |
          |opaque struct Conn
          |    fd: int
          |end Conn
          |
          |doubled(c: Conn) -> int = c.fd * 2
          |
          |here() -> int = doubled(Conn(21))
          |""".stripMargin,
        "main.sysl" -> "print(net.here())") shouldBe "42\n"
    }
  }

  "outside, only a pointer to it may be named" - {

    "a pointer may be held, passed back in, and compared" in {
      withConn("""var c: *net.Conn = null
                 |print(c == null)
                 |""".stripMargin) shouldBe "true\n"
    }

    "a binding of it by value is refused" in {
      val e = rejecting("var c: net.Conn\nprint(1)\n")

      e should include("opaque outside 'net'")
      e should include("not known here")
    }

    "a by-value parameter is refused" in {
      rejecting("take(c: net.Conn) -> int = 1\nprint(1)\n") should include("opaque outside 'net'")
    }

    "a by-value result is refused" in {
      rejecting("give(c: *net.Conn) -> net.Conn = *c\nprint(1)\n") should include("opaque outside 'net'")
    }

    "embedding it in another type is refused, since that type would have to lay it out" in {
      rejecting("struct Holder\n    c: net.Conn\nend Holder\n\nprint(1)\n") should include("opaque outside 'net'")
    }

    "an array of them is refused, since a row of them needs a stride" in {
      rejecting("var cs: [4]net.Conn\nprint(1)\n") should include("opaque outside 'net'")
    }

    "constructing one is refused — the module's own function is the way to get one" in {
      rejecting("var c = net.Conn(1, true)\nprint(1)\n") should include("opaque outside 'net'")
    }

    "'sizeof' is refused, which is the question asked outright" in {
      rejecting("print(sizeof(net.Conn))\n") should include("opaque outside 'net'")
    }

    "and so is 'alignof'" in {
      rejecting("print(alignof(net.Conn))\n") should include("opaque outside 'net'")
    }

    // An offset is the one fact the modifier exists to withhold, so this is the question asked most
    // directly of all. It is refused by the same path the other two are — resolving the written type
    // — rather than by a rule of its own.
    "and 'offsetof', which asks for the withheld fact by name" in {
      rejecting("print(offsetof(net.Conn, fd))\n") should include("opaque outside 'net'")
    }

    "reading a field through the pointer is refused, since that needs an offset" in {
      rejecting("f(c: *net.Conn) -> int = c.fd\nprint(1)\n") should include("opaque")
    }

    // Dereferencing produces the value, which is the thing there is no layout for.
    "dereferencing one is refused" in {
      rejecting("f(c: *net.Conn) -> int\n    var here = *c\n    1\nend f\n\nprint(1)\n") should include("opaque")
    }

    // A pointer to a pointer is still only ever a pointer, so the layout is never wanted.
    "a pointer to a pointer to one is fine" in {
      withConn("""var c: *net.Conn = null
                 |var p = &c
                 |print(*p == null)
                 |""".stripMargin) shouldBe "true\n"
    }
  }

  // The nesting case the one-level flag exists for: a pointer excuses its own pointee and nothing
  // deeper. `*Holder` is legal, and `Holder`'s by-value field is not made legal by it.
  "a pointer excuses one level and not the subtree" in {
    rejecting("""struct Holder
                |    c: net.Conn
                |end Holder
                |
                |f(h: *Holder) -> int = 1
                |
                |print(1)
                |""".stripMargin) should include("opaque outside 'net'")
  }

  "a struct with no body at all is C's incomplete type" - {

    "an opaque one may have none, which is what a C handle is" in {
      runOf("sq/lib.sysl" ->
        """module sq
          |
          |opaque struct Session
          |
          |extern "strlen" length(s: *u8) -> usize
          |""".stripMargin,
        "main.sysl" -> "var s: *sq.Session = null\nprint(s == null)\n") shouldBe "true\n"
    }

    "an ordinary one may not, and is told what to write instead" in {
      val e = err("struct Empty\n\nprint(1)\n")

      e should include("declares no fields")
      e should include("opaque struct Empty")
    }

    // The point of declaring it at all: `*Session` and `*u8` are different types, where a binding
    // that used `*u8` for the handle had nothing to stop the two being swapped.
    "and its pointer is a type of its own, not interchangeable with '*u8'" in {
      val e = errOf("sq/lib.sysl" -> "module sq\n\nopaque struct Session\n",
        "main.sysl" -> "f(p: *u8) -> int = 1\nvar s: *sq.Session = null\nprint(f(s))\n")

      e should include("*byte")
      e should include("*sq.Session")
    }
  }

  // `opaque` is a soft keyword, for the reason `link` is: it is an ordinary word, and the fully
  // opaque end of an alpha channel is exactly the field somebody wants to call it.
  "'opaque' is still an ordinary name" - {

    "a field may be called it" in {
      run("""struct Colour
            |    opaque: bool
            |end Colour
            |
            |print(Colour(true).opaque)
            |""".stripMargin) shouldBe "true\n"
    }

    "and a variable may be" in {
      run("var opaque = 5\nprint(opaque + 1)\n") shouldBe "6\n"
    }
  }

  // The reach is the declaring module **exactly**, not a subtree the way `private[M]` widens. What
  // the modifier buys is that a field may move with nothing downstream recompiled, and the unit that
  // recompiles together is the module — its files share one scope, and a submodule is a different
  // scope however its name reads.
  "the reach is the declaring module, no more and no less" - {

    "another file of the same module sees the layout" in {
      runOf("net/conn.sysl" -> conn,
        "net/more.sysl" -> "module net\n\ndoubled() -> int\n    var c = Conn(21, true)\n    c.fd * 2\nend doubled\n",
        "main.sysl" -> "print(net.doubled())") shouldBe "42\n"
    }

    "a submodule does not, though its name sits underneath" in {
      errOf("net/conn.sysl" -> conn,
        "net/deep/inner.sysl" -> "module net.deep\n\nf() -> usize = sizeof(net.Conn)\n",
        "main.sysl" -> "print(1)") should include("opaque outside 'net'")
    }
  }

  "the counted and viewing forms are refused too, since only a raw pointer says nothing about shape" - {

    // A counted reference's release has to know what the object holds, which is the layout.
    "a '&' to one is refused" in {
      rejecting("f(c: &net.Conn) -> int = 1\nprint(1)\n") should include("opaque outside 'net'")
    }

    "a slice of them is refused, a slice being a row with a stride" in {
      rejecting("f(cs: []net.Conn) -> int = 1\nprint(1)\n") should include("opaque outside 'net'")
    }

    "and a type argument is refused, since the instantiation would hold one" in {
      rejecting("f(o: sysl.Option[net.Conn]) -> int = 1\nprint(1)\n") should include("opaque outside 'net'")
    }
  }

  "taking one apart is refused wherever the shape would be needed" - {

    "a pattern naming its fields" in {
      rejecting("""f(c: *net.Conn) -> int
                  |    *c match
                  |        net.Conn(a, b) -> a
                  |print(1)
                  |""".stripMargin) should include("opaque")
    }

    "and writing a field through the pointer, which is the same offset read backwards" in {
      rejecting("f(c: *net.Conn) -> unit\n    c.fd = 3\nend f\n\nprint(1)\n") should include("opaque")
    }
  }

  // Methods are the intended way to use one from outside, so which of them work is worth pinning:
  // a `*self` method is a call through a pointer and needs no shape, while a by-value `self` is
  // handed a copy and needs the whole of it.
  "methods on an opaque type" - {

    "a '*self' method may be called through the pointer" in {
      runOf("net/h.sysl" -> withMethods, "main.sysl" -> "print(net.make())").shouldBe("9\n")
    }

    "a by-value 'self' method is refused, since the call would copy the whole of it" in {
      errOf("net/h.sysl" -> withMethods,
        "main.sysl" -> "f(h: *net.Handle) -> int = h.copied()\nprint(1)\n").should(include("opaque"))
    }
  }

  "a generic struct may be opaque, and its instantiations are too" in {
    errOf("box/b.sysl" ->
      """module box
        |
        |opaque struct Box[T]
        |    item: T
        |end Box
        |
        |peek(b: *Box[int]) -> int = b.item
        |""".stripMargin,
      "main.sysl" -> "var b: box.Box[int]\nprint(1)\n") should include("opaque outside 'box'")
  }

  "the modifier survives an artifact" in {
    val parsed = SyslParser.parse("module net\n\nopaque struct Conn\n    fd: int\nend Conn\n", "c.sysl") match
      case Right(p)  => p
      case Left(err) => fail(err)

    val back = AstCodec.decode(AstCodec.encode(List(parsed))) match
      case Right(ps) => ps.head
      case Left(err) => fail(err)

    back.body.collectFirst { case s: StructDecl => s.opaque } shouldBe Some(true)
  }
}
