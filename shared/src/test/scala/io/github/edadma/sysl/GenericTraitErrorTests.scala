package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** What a generic trait refuses, and why. Three groups of rule: the arguments must be there and be
 * right wherever the trait is named; the promise an implementation makes is the one a bound has to
 * ask for; and one trait is implemented once per type, whatever arguments would have distinguished
 * two implementations.
 */
class GenericTraitErrorTests extends AnyFreeSpec with CodegenSupport {

  "a trait applied to the wrong arguments is refused where it is written" - {
    "an 'impl' that leaves them out" in {
      err(
        """trait Into[T]
          |    into(self) -> T
          |struct P
          |    v: int
          |impl Into for P
          |    into(self) -> int = self.v""".stripMargin,
      ) should include("trait 'Into' takes 1 type argument, but 0 type arguments were given")
    }

    "an 'impl' that supplies too many" in {
      err(
        """trait Get[T]
          |    get(self) -> T
          |struct P
          |    v: int
          |impl Get[int, real] for P
          |    get(self) -> int = 1""".stripMargin,
      ) should include("trait 'Get' takes 1 type argument, but 2 type arguments were given")
    }

    "a bound that leaves them out" in {
      err(
        """trait Get[T]
          |    get(self) -> T
          |f[X: Get](x: X) -> int = 1""".stripMargin,
      ) should include("trait 'Get' takes 1 type argument, but 0 type arguments were given")
    }

    "arguments on a trait that declares none" in {
      err(
        """trait Show
          |    show(self) -> int
          |f[X: Show[int]](x: X) -> int = 1""".stripMargin,
      ) should include("trait 'Show' does not take type arguments")
    }

    "an argument that names no type" in {
      err(
        """trait Get[T]
          |    get(self) -> T
          |f[X: Get[Nope]](x: X) -> int = 1""".stripMargin,
      ) should include("unknown type 'Nope'")
    }

    "the trait itself, standing where a type was asked for" in {
      err(
        """trait Get[T]
          |    get(self) -> T
          |f(x: Get[int]) -> int = 1""".stripMargin,
      ) should include("'Get' is a trait, so it describes behaviour rather than a layout")
    }
  }

  "the promise an implementation makes is the one a bound must ask for" - {
    "a bound at other arguments is not met" in {
      err(
        """trait Get[T]
          |    get(self) -> T
          |struct P
          |    v: int
          |impl Get[int] for P
          |    get(self) -> int = self.v
          |f[X: Get[real]](x: X) -> int = 1
          |print(f(P(1)))""".stripMargin,
      ) should include(
        "'f' requires its type parameter 'X' to implement 'Get[real]', but P does not — " +
          "it implements 'Get[int]', and one trait is implemented once per type",
      )
    }

    "a trait object at other arguments has no table to point at" in {
      err(
        """trait Sink[T]
          |    put(self, x: T) -> int
          |struct A
          |    tag: int
          |impl Sink[int] for A
          |    put(self, x: int) -> int = x
          |var u: &Sink[real] = A(0)""".stripMargin,
      ) should include(
        "a &Sink[real] needs a type that implements 'Sink[real]', and A does not — " +
          "it implements 'Sink[int]', and one trait is implemented once per type",
      )
    }

    "a type with no implementation at all is told so plainly" in {
      err(
        """trait Get[T]
          |    get(self) -> T
          |struct P
          |    v: int
          |f[X: Get[int]](x: X) -> int = 1
          |print(f(P(1)))""".stripMargin,
      ) should include("'f' requires its type parameter 'X' to implement 'Get[int]', but P does not")
    }

    "a conditional implementation's own bound still reports" in {
      err(
        """trait Show
          |    show(self) -> int
          |trait Get[T]
          |    get(self) -> T
          |struct Box[U]
          |    v: U
          |impl[U: Show] Get[int] for Box[U]
          |    get(self) -> int = self.v.show()
          |f[X: Get[int]](x: X) -> int = 1
          |print(f(Box(1)))""".stripMargin,
      ) should include("the 'impl' that covers it asks 'Show' of int, which does not implement it")
    }
  }

  "one trait is implemented once per type" - {
    "a second implementation at other arguments is refused" in {
      err(
        """trait From[T]
          |    from(x: T) -> Self
          |struct C
          |    v: int
          |impl From[int] for C
          |    from(x: int) -> Self = C(x)
          |impl From[real] for C
          |    from(x: real) -> Self = C(int(x))""".stripMargin,
      ) should include(
        "'C' already implements 'From[int]' — a trait's members become the type's, so a second " +
          "'From' would give 'from' two meanings and a call no way to say which",
      )
    }

    "a second implementation at the same arguments is the duplicate it always was" in {
      err(
        """trait Get[T]
          |    get(self) -> T
          |struct C
          |    v: int
          |impl Get[int] for C
          |    get(self) -> int = 1
          |impl Get[int] for C
          |    get(self) -> int = 2""".stripMargin,
      ) should include("'C' already implements 'Get[int]'")
    }
  }

  "an 'impl' fixes the trait's arguments and its own parameters separately" - {
    "the trait's argument may not be one of the block's own parameters" in {
      err(
        """trait Get[T]
          |    get(self) -> T
          |struct Box[U]
          |    v: U
          |impl[U] Get[U] for Box[U]
          |    get(self) -> U = self.v""".stripMargin,
      ) should include(
        "'U' is a type parameter of this 'impl', so it leaves which 'Get' this block implements " +
          "open — write the type the trait is applied to",
      )
    }

    "nor may it be built out of one" in {
      err(
        """trait Get[T]
          |    get(self) -> T
          |struct Box[U]
          |    v: U
          |impl[U] Get[Box[U]] for Box[U]
          |    get(self) -> Box[U] = self""".stripMargin,
      ) should include("'U' is a type parameter of this 'impl'")
    }

    "the block may not spell one of its own the way the trait spells one of its" in {
      err(
        """trait Get[T]
          |    get(self) -> T
          |struct Box[T]
          |    v: T
          |impl[T] Get[int] for Box[T]
          |    get(self) -> int = 1""".stripMargin,
      ) should include(
        "trait 'Get' already declares a type parameter 'T', so this 'impl' cannot declare one of that name",
      )
    }
  }

  "a trait's own parameters carry bounds, and everything applying it supplies them" - {
    "an 'impl' whose argument does not meet them" in {
      err(
        """trait Show
          |    show(self) -> int
          |trait Get[T: Show]
          |    get(self) -> T
          |struct P
          |    v: int
          |impl Get[int] for P
          |    get(self) -> int = self.v""".stripMargin,
      ) should include("'Get' requires its type parameter 'T' to implement 'Show', but int does not")
    }

    "a bound whose argument does not meet them" in {
      err(
        """trait Show
          |    show(self) -> int
          |trait Get[T: Show]
          |    get(self) -> T
          |f[X: Get[int]](x: X) -> int = 1""".stripMargin,
      ) should include("'Get' requires its type parameter 'T' to implement 'Show', but int does not")
    }
  }

  "a member reached through a generic bound is checked against the trait's signature" - {
    "at the arguments the bound supplied" in {
      err(
        """trait Sink[T]
          |    put(self, x: T) -> int
          |f[X: Sink[int]](x: X) -> int = x.put("no")""".stripMargin,
      ) should include("'x' of 'Sink.put' is int, but string was given")
    }

    "the result is the trait's argument, not whatever the body wants" in {
      err(
        """trait Get[T]
          |    get(self) -> T
          |struct P
          |    v: int
          |impl Get[string] for P
          |    get(self) -> string = "s"
          |f[X: Get[string]](x: X) -> int = x.get()
          |print(f(P(1)))""".stripMargin,
      ) should include("should return int, but its body yields string")
    }

    "a parameter the bound leaves abstract needs its own bound to be rendered" in {
      err(
        """trait Into[T]
          |    into(self) -> T
          |show[X: Into[Y], Y](x: X)
          |    print(x.into())""".stripMargin,
      ) should include("'print' needs 'Y: Display'")
    }
  }

  "the rules a trait already had are unchanged by its taking parameters" - {
    "a default body may assume only what the trait promises" in {
      err(
        """trait Tag[T]
          |    tag(self) -> T
          |    bad(self) -> T = self.nope()""".stripMargin,
      ) should include("no trait declares a method 'nope'")
    }

    "'Self' away from the receiver still refuses an object" in {
      err(
        """trait Swap[T]
          |    swap(self, other: Self) -> T
          |struct A
          |    tag: int
          |impl Swap[int] for A
          |    swap(self, other: A) -> int = 1
          |var u: &Swap[int] = A(0)""".stripMargin,
      ) should include(
        "'swap' of 'Swap' mentions 'Self' away from its receiver, and an erased value has " +
          "forgotten which type that is — so there is no '&Swap[int]' to form",
      )
    }

    "a generic method is still refused at the declaration" in {
      err(
        """trait Get[T]
          |    pick[U](self, u: U) -> T""".stripMargin,
      ) should include("generic methods are not supported yet — 'Get.pick'")
    }

    "an implementation still supplies every member the trait declares" in {
      err(
        """trait Pairing[A, B]
          |    left(self) -> A
          |    right(self) -> B
          |struct P
          |    n: int
          |impl Pairing[int, string] for P
          |    left(self) -> int = self.n""".stripMargin,
      ) should include("'P' does not implement 'Pairing': method 'right' is missing")
    }

    "a member written at the wrong type is compared against the arguments the block fixed" in {
      err(
        """trait Sink[T]
          |    put(self, x: T) -> int
          |struct A
          |    tag: int
          |impl Sink[int] for A
          |    put(self, x: string) -> int = 1""".stripMargin,
      ) should include("parameter 'x' of method 'put' is string, but trait 'Sink' declares int")
    }
  }
}
