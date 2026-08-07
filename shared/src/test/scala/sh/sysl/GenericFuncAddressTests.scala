package sh.sysl

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** The address of a **generic** function, whose instantiation is read off the expected type
 * (`12 §6a`).
 *
 * `12 §6a` refused this outright, on the grounds that a generic function "is a body per set of type
 * arguments, so there is no one body to name". That is true only until the arguments are settled,
 * and the chapter's own next sentence — *"a wrapper that calls it at the arguments wanted is what
 * has an address"* — describes exactly what an instantiation is. The refusal was too broad.
 *
 * **What it was costing is a library's ability to offer a callback helper at all.** Every C
 * interface that calls back takes a `void *userdata` beside the function pointer; the trampoline
 * that unpacks it has to be generic over the state type, and that state type belongs to the
 * *application* rather than to the binding. So a binding could ship no helper, and every application
 * hand-rolled a trampoline and an unchecked `ptr_cast`.
 *
 * There is no written form for the arguments, and deliberately: `&f[T]` cannot be told from `&xs[i]`
 * by the grammar. The expected type carries the same information and is information the caller has
 * anyway, since a `*extern` is handed to something whose signature is already fixed.
 */
class GenericFuncAddressTests extends AnyFreeSpec with Matchers with CodegenSupport with RunSupport {

  "an instantiation is read off the expected type" - {
    "at a val's written type" in {
      run(
        """ident[T](x: T) -> T = x
          |
          |val f: *extern(i32) -> i32 = &ident
          |print(f(7))""".stripMargin
      ) shouldBe "7\n"
    }

    // The shape a callback actually takes: the state type appears only behind a pointer.
    "through a pointer parameter" in {
      run(
        """struct State
          |    n: i32
          |end State
          |
          |read_it[S](s: *S, bump: i32) -> i32 = bump
          |
          |val f: *extern(*State, i32) -> i32 = &read_it
          |var st = State(3)
          |print(f(&st, 9))""".stripMargin
      ) shouldBe "9\n"
    }

    "at an extern's parameter, which is where a real callback is handed over" in {
      run(
        """extern qsort(base: *u8, n: usize, size: usize, cmp: *extern(*u8, *u8) -> i32)
          |
          |ascending[T](a: *T, b: *T) -> i32
          |    var pa: *i32 = ptr_cast(a)
          |    var pb: *i32 = ptr_cast(b)
          |    *pa - *pb
          |
          |var xs = [30i32, 10i32, 20i32]
          |qsort(ptr_cast(&xs[0]), 3usize, 4usize, &ascending)
          |print(xs[0], xs[1], xs[2])""".stripMargin
      ) shouldBe "10 20 30\n"
    }

    // Two instantiations of one generic are two bodies, and both have to be reachable at once.
    "two instantiations of the same function are two addresses" in {
      run(
        """ident[T](x: T) -> T = x
          |
          |val f: *extern(i32) -> i32 = &ident
          |val g: *extern(u8) -> u8 = &ident
          |print(f(7), g(u8(3)))""".stripMargin
      ) shouldBe "7 3\n"
    }
  }

  "what it still refuses" - {
    "no expected type to read the arguments off" in {
      err(
        """ident[T](x: T) -> T = x
          |
          |var f = &ident
          |print(1)""".stripMargin
      ) should include("nothing here says what they are")
    }

    // A parameter the signature does not mention cannot be settled by anything in the expected
    // type. This is the honest limit of reading the instantiation off a type.
    "a type parameter the signature never mentions" in {
      err(
        """size_of[T](n: i32) -> i32 = n
          |
          |val f: *extern(i32) -> i32 = &size_of
          |print(1)""".stripMargin
      ) should include("does not say what 'T' should be")
    }

    "an arity that does not match" in {
      err(
        """ident[T](x: T) -> T = x
          |
          |val f: *extern(i32, i32) -> i32 = &ident
          |print(1)""".stripMargin
      ) should include("takes 1 parameter")
    }

    // The spelling a reader arrives with. Before this it read as an index and complained that the
    // function was not a value — true, unhelpful, and it pointed at `&ident`, which alone is a
    // second error.
    "`&f[T]`, the spelling from languages that write the arguments there" in {
      err(
        """ident[T](x: T) -> T = x
          |
          |var f = &ident[i32]
          |print(1)""".stripMargin
      ) should include("type arguments of 'ident' are not written here")
    }

    // Unchanged by this: a nested function's environment is the frame it was declared in, so it has
    // no address whether or not it is generic.
    "a nested function, generic or not" in {
      err(
        """outer() -> int
          |    inner(n: int) -> int = n
          |    val f: *extern(int) -> int = &inner
          |    0
          |print(outer())""".stripMargin
      ) should include("nested function")
    }
  }
}
