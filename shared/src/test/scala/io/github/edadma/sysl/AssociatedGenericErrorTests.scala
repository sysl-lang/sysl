package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** What an associated function on a generic type may and may not say (`10 § Open b`).
 *
 * Having no receiver is the whole difference: the type arguments have to be inferred rather than
 * read, so what is refused here is either an inference the call cannot make or a mistake the
 * inferred instantiation exposes. The definition-time group is the other half — an associated
 * function's body is held to the type's bounds before anything instantiates it, exactly as a
 * method's is.
 */
class AssociatedGenericErrorTests extends AnyFreeSpec with CodegenSupport {

  "inference has to succeed" - {

    "a parameter the signature never mentions cannot be inferred" in {
      err(
        """struct Box[T]
          |    v: T
          |    count() -> int = 0
          |print(Box.count())""".stripMargin,
      ) should include("cannot infer the type argument 'T' of 'Box.count' here — annotate the expected type")
    }

    "a parameter only the body mentions cannot be either" in {
      err(
        """struct Box[T]
          |    v: T
          |    of(x: T) -> Box[T] = Box(x)
          |    empty() -> int = 0
          |print(Box.empty())""".stripMargin,
      ) should include("cannot infer the type argument 'T' of 'Box.empty'")
    }

    "one of several parameters left unsettled is named on its own" in {
      err(
        """struct Pair[A, B]
          |    a: A
          |    b: B
          |    only(x: A) -> int = 0
          |print(Pair.only(1))""".stripMargin,
      ) should include("cannot infer the type argument 'B' of 'Pair.only'")
    }
  }

  "the instantiation the call resolves to is checked" - {

    "an argument that does not meet the type's bound is refused at the call" in {
      err(
        """trait Rank
          |    rank(self) -> int
          |struct Scored[T: Rank]
          |    n: int
          |    of(x: T) -> Scored[T] = Scored(x.rank())
          |print(Scored.of(3).n)""".stripMargin,
      ) should include("'Scored' requires its type parameter 'T' to implement 'Rank', but int does not")
    }

    "and so is one inferred from the expected type rather than from an argument" in {
      err(
        """trait Rank
          |    rank(self) -> int
          |struct Scored[T: Rank]
          |    n: int
          |    zero() -> Scored[T] = Scored(0)
          |var s: Scored[int] = Scored.zero()""".stripMargin,
      ) should include("'Scored' requires its type parameter 'T' to implement 'Rank', but int does not")
    }

    "an argument disagreeing with the parameter the other one fixed is reported" in {
      err(
        """struct Pair[A]
          |    a: A
          |    b: A
          |    of(x: A, y: A) -> Pair[A] = Pair(x, y)
          |var p = Pair.of(1, "two")""".stripMargin,
      ) should include("'x' of 'Pair.of' is string, but int was given")
    }

    "a result disagreeing with the annotated type is reported as the mismatch it is" in {
      err(
        """struct Box[T]
          |    v: T
          |    of(x: T) -> Box[T] = Box(x)
          |var b: Box[string] = Box.of(1)""".stripMargin,
      ) should include("Box[string]")
    }

    "the arity is checked before anything is inferred from the arguments" in {
      err(
        """struct Box[T]
          |    v: T
          |    of(x: T) -> Box[T] = Box(x)
          |print(Box.of(1, 2).v)""".stripMargin,
      ) should include("associated function 'Box.of' takes 1 argument, but 2 arguments were given")
    }
  }

  "the body is checked at the definition, against the type's bounds" - {

    "rendering an unbounded parameter names the bound that would license it" in {
      err(
        """struct Box[T]
          |    v: T
          |    describe(x: T) -> string = str(x)""".stripMargin,
      ) should include(s"'str' needs 'T: ${lib("Display")}'")
    }

    "an operator on an unbounded parameter does too" in {
      err(
        """struct Box[T]
          |    v: T
          |    twice(x: T) -> T = x + x""".stripMargin,
      ) should include(s"'+' needs 'T: ${lib("Add")}'")
    }

    "a bound licenses only what its own trait declares" in {
      err(
        """trait Rank
          |    rank(self) -> int
          |trait Size
          |    size(self) -> int
          |struct Box[T: Rank]
          |    v: T
          |    measure(x: T) -> int = x.size()""".stripMargin,
      ) should include("'size' needs 'T: Size'")
    }
  }

  "a generic caller must ask at least what the type asks" - {

    "an unbounded parameter handed to a bounded type is refused at the caller's definition" in {
      err(
        """trait Rank
          |    rank(self) -> int
          |struct Scored[T: Rank]
          |    n: int
          |    of(x: T) -> Scored[T] = Scored(0)
          |keep[T](x: T) -> Scored[T] = Scored.of(x)""".stripMargin,
      ) should include("'Scored' requires its type parameter 'T' to implement 'Rank', but 'T' is not bounded by it")
    }
  }

  "there has to be a name to reach it through" - {

    // A composed type is what has none: `[]int` is a type an `impl` may be written for and not
    // something that can stand in call position, and a block matching a shape is for a family at
    // once. Either would register an associated function nothing could call.
    "a block matching a shape may not declare one" in {
      err(
        """trait Make
          |    made(x: int) -> int
          |impl[T] Make for []T
          |    made(x: int) -> int = x""".stripMargin,
      ) should include("'made' has no receiver, and '[]T' is not a name a call could reach it through")
    }

    "and neither may a block for a composed type written out in full" in {
      err(
        """trait Make
          |    made(x: int) -> int
          |impl Make for []int
          |    made(x: int) -> int = x""".stripMargin,
      ) should include("'made' has no receiver, and '[]int' is not a name a call could reach it through")
    }

    // A built-in does have one, which is the half of the rule that changed: through a bound the name
    // is the type parameter, and from outside it is the built-in's own name.
    "a block for a built-in may declare one, and the built-in's name reaches it" in {
      ir(
        """trait Make
          |    made(x: int) -> int
          |
          |impl Make for int
          |    made(x: int) -> int = x + 1
          |
          |main()
          |    print(int.made(41))""".stripMargin,
      ) should include("call i32 @int.made(i32 41)")
    }
  }

  "it is reached through the type, not through a value" - {

    "calling one on a value says where it is reached from instead" in {
      err(
        """struct Box[T]
          |    v: T
          |    of(x: T) -> Box[T] = Box(x)
          |var b = Box(1)
          |print(b.of(2).v)""".stripMargin,
      ) should include("'of' is an associated function of 'Box' — call it with 'Box.of(…)'")
    }

    "reading one off the type without parentheses says to call it" in {
      err(
        """struct Box[T]
          |    v: T
          |    of(x: T) -> Box[T] = Box(x)
          |print(Box.of)""".stripMargin,
      ) should include("'of' is an associated function of 'Box' — call it with 'Box.of(…)'")
    }

    "and a name the type does not have at all is reported as the type it was read off" in {
      err(
        """struct Box[T]
          |    v: T
          |print(Box.v)""".stripMargin,
      ) should include("type 'Box' has no member 'v' — and 'Box' is a type, not a value")
    }

    "a method reached through the type name says to use a value" in {
      err(
        """struct Box[T]
          |    v: T
          |    get(self) -> T = self.v
          |print(Box.get())""".stripMargin,
      ) should include("'get' is an instance method of 'Box' — call it on a value, not the type")
    }

    "a property reached through the type name says the same" in {
      err(
        """struct Box[T]
          |    v: T
          |    size -> int = 1
          |print(Box.size())""".stripMargin,
      ) should include("'size' is a property of 'Box' — read it on a value, as 'value.size'")
    }

    "a name the type does not declare is reported against the type" in {
      err(
        """struct Box[T]
          |    v: T
          |print(Box.of(1))""".stripMargin,
      ) should include("type 'Box' has no associated function 'of'")
    }
  }
}
