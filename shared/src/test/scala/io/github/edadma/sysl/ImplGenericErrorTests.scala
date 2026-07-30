package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** What an `impl` with type parameters of its own may and may not say (`02`).
 *
 * A generic type has **one** key for all of its instantiations, which is what makes an
 * implementation cover the type as a whole — and what makes an implementation for *some* of its
 * instantiations a second one for a key that holds one. Most of the refusals here are that fact
 * seen from different angles.
 *
 * The rest is what conditional conformance is worth beyond deciding conformance: a block that
 * states what it assumes of its parameters can have its members checked **at their definition**,
 * once, rather than at whichever instantiation first supplies a type without the method.
 */
class ImplGenericErrorTests extends AnyFreeSpec with CodegenSupport {

  private val show = "trait Show\n    show(self) -> string\n"
  private val box  = "struct Box[T]\n    v: T\n"
  private val pair = "struct Pair[A, B]\n    a: A\n    b: B\n"

  "the subject is the type as a whole" - {

    "so a bare generic name says how to write the block" in {
      val msg = err(s"$show${box}impl Show for Box\n    show(self) -> string = \"b\"")

      msg should include("'Box' is generic, so an 'impl' for it covers every instantiation at once")
      msg should include("write 'impl[T] Show for Box[T]'")
    }

    // Fixing the argument is what a second implementation for the one key would be, so it is
    // refused in the same words rather than as a different mistake.
    "and an instantiation fixed to one type is refused the same way" in {
      err(s"$show${box}impl Show for Box[int]\n    show(self) -> string = \"b\"") should
        include("covers every instantiation at once")
    }

    "and one argument fixed among several is too" in {
      err(s"$show${pair}impl[T] Show for Pair[T, int]\n    show(self) -> string = \"b\"") should
        include("'int' fixes an argument of 'Pair' to one type")
    }

    "so two blocks for one generic type collide" in {
      err(
        s"""$show${box}impl[T] Show for Box[T]
           |    show(self) -> string = "b"
           |impl[U] Show for Box[U]
           |    show(self) -> string = "c"""".stripMargin,
      ) should include("'Box' already implements 'Show'")
    }
  }

  "the block's parameters and the subject's arguments line up" - {

    "each argument taking a parameter of its own" in {
      err(s"$show${pair}impl[T] Show for Pair[T, T]\n    show(self) -> string = \"b\"") should
        include("this 'impl' names 'T' twice")
    }

    "every declared parameter appearing in the subject" in {
      err(s"$show${box}impl[T, U] Show for Box[T]\n    show(self) -> string = \"b\"") should
        include("'U' is declared by this 'impl' but does not appear in 'Box[T]'")
    }

    "and the count matching the type's own" in {
      err(s"$show${box}impl[T] Show for Box\n    show(self) -> string = \"b\"") should
        include("'Box' takes 1 type argument, but 0 type arguments were given")
    }

    "a type that takes no arguments has nothing to be generic over" in {
      err(s"${show}struct P\n    v: int\nimpl[T] Show for P\n    show(self) -> string = \"b\"") should
        include("'P' takes no type arguments, so an 'impl' for it has nothing to be generic over")
    }

    "a built-in takes no arguments either" in {
      err(s"${show}impl[T] Show for int\n    show(self) -> string = \"b\"") should
        include("'int' takes no type arguments")
    }

    // What is wrong with the line is the name, and the block's parameters are the one part of it
    // written correctly — so the name is what the diagnostic is about.
    "and a name that declares no type is reported as itself" in {
      err(s"${show}impl[T] Show for Ghost[T]\n    show(self) -> string = \"b\"") should
        include("unknown type 'Ghost'")
    }
  }

  "conformance is checked with the parameters standing in for themselves" - {

    "so a missing member is reported against the generic type" in {
      err(s"$show${box}impl[T] Show for Box[T]") should
        include("'Box' does not implement 'Show': method 'show' is missing")
    }

    "and a result written through the parameter must be the one the trait declares" in {
      err(s"$show${box}impl[T] Show for Box[T]\n    show(self) -> T = self.v") should
        include("method 'show' returns T, but trait 'Show' declares string")
    }

    // `Self` on the trait's side is the subject applied to the block's parameters, so the two
    // spellings are the one signature — which is only visible when they are compared as resolved
    // types rather than as text.
    "and 'Self' matches the subject written out" in {
      err(
        s"""trait Dup
           |    dup(self) -> Self
           |${box}impl[T] Dup for Box[T]
           |    dup(self) -> Box[int] = Box(1)""".stripMargin,
      ) should include("returns Box[int], but trait 'Dup' declares Box[T]")
    }
  }

  "the block's bounds decide which instantiations conform" - {

    "so a bound is refused where the argument does not carry it" in {
      err(
        s"""$show${box}struct P
           |    v: int
           |impl[T: Show] Show for Box[T]
           |    show(self) -> string = self.v.show()
           |name[U: Show](x: U) -> string = x.show()
           |print(name(Box(P(1))))""".stripMargin,
      ) should include("requires its type parameter 'U' to implement 'Show', but Box[P] does not")
    }

    // The same block accepts the instantiation whose argument does carry it, which is what makes
    // the refusal above about the condition rather than about generic impls at all.
    "while the instantiation that carries it is accepted" in {
      ir(
        s"""$show${box}impl Show for int
           |    show(self) -> string = str(self)
           |impl[T: Show] Show for Box[T]
           |    show(self) -> string = self.v.show()
           |name[U: Show](x: U) -> string = x.show()
           |print(name(Box(1)))""".stripMargin,
      ) should include("@Box.show.int(")
    }

    // The erasure asks the same question a bound does, so an instantiation that fails the condition
    // has no table to be given — even though every *other* instantiation of the block has one.
    "and erasing an instantiation that fails the condition is refused" in {
      err(
        s"""$show${box}struct P
           |    v: int
           |impl[T: Show] Show for Box[T]
           |    show(self) -> string = self.v.show()
           |var b: &Box[P] = Box(P(1))
           |var o: &Show = b""".stripMargin,
      ) should include("a &Show needs a type that implements 'Show', and Box[P] does not")
    }

    "and a type with no rendering names the block to write" in {
      err(s"${box}print(Box(1))") should
        include("write an 'impl[T] sysl.Display for Box[T]' to say how it renders")
    }
  }

  "a member is checked at its definition, against the block's bounds" - {

    // This is what the bounds buy beyond deciding conformance: nothing instantiates the block, and
    // the mistake is reported anyway, on the line that made it.
    "a method no bound licenses is reported with nothing instantiated" in {
      err(s"$show${box}impl[T] Show for Box[T]\n    show(self) -> string = self.v.show()") should
        include("'show' needs 'T: Show'")
    }

    "rendering a parameter needs 'Display', and says so" in {
      err(s"$show${box}impl[T] Show for Box[T]\n    show(self) -> string = str(self.v)") should
        include("'str' needs 'T: sysl.Display'")
    }

    "an operator needs its own bound" in {
      err(
        s"""trait Twice
           |    twice(self) -> string
           |${box}impl[T] Twice for Box[T]
           |    twice(self) -> string
           |        var doubled = self.v + self.v
           |        "done\"""".stripMargin,
      ) should include("'+' needs 'T: Add'")
    }

    // One mistake, one diagnostic: the definition-time complaint names the bound that would license
    // the call, and the instantiations that would each complain about a consequence are dropped.
    "and an instantiation of a member already reported adds nothing" in {
      val msg =
        err(
          s"""$show${box}impl[T] Show for Box[T]
             |    show(self) -> string = self.v.show()
             |print(Box(1).show())""".stripMargin,
        )

      msg should include("'show' needs 'T: Show'")
      msg should not include "type 'int' has no method 'show'"
    }
  }

  "the rest of the member surface" - {

    // An associated function has no receiver, so a block's parameter that its signature never
    // mentions has nothing at the call to fix it — the same failure a free `f[T]() -> int` meets.
    "an associated function whose signature never mentions the parameter cannot be called" in {
      err(
        s"""trait Make
           |    make() -> int
           |${box}impl[T] Make for Box[T]
           |    make() -> int = 1
           |print(Box.make())""".stripMargin,
      ) should include("cannot infer the type argument 'T' of 'Box.make'")
    }

    "a member colliding with the type's field is refused" in {
      err(
        s"""trait Named
           |    v(self) -> string
           |${box}impl[T] Named for Box[T]
           |    v(self) -> string = "b"""".stripMargin,
      ) should include("type 'Box' has both a field and a member named 'v'")
    }

    "a member colliding with the type's own is refused" in {
      err(
        s"""$show
           |struct Box[T]
           |    v: T
           |    show(self) -> string = "own"
           |impl[T] Show for Box[T]
           |    show(self) -> string = "impl"""".stripMargin,
      ) should include("type 'Box' already has a member named 'show'")
    }
  }

  "the end marker closes the subject it opened" - {

    "a matching one is accepted" in {
      ir(
        s"""$show${box}impl[T] Show for Box[T]
           |    show(self) -> string = "b"
           |end Box[T]
           |print(Box(1).show())""".stripMargin,
      ) should include("@Box.show.int(")
    }

    "a differently spelled parameter does not match it" in {
      err(
        s"""$show${box}impl[T] Show for Box[T]
           |    show(self) -> string = "b"
           |end Box[U]""".stripMargin,
      ) should include("'end Box[U]' does not match 'Box[T]'")
    }
  }
}
