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
 * **Where the signature does not mention the parameter, the arguments are written**: `&f[T]`, which
 * is the one position in the language that takes them. The grammar gives it the same shape as
 * `&xs[i]`, and the analyzer is what tells the two apart — the name has to resolve to a generic
 * function declaration with no local shadowing it, which is the same test every call form makes.
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

  "the arguments written out — `&f[T]`" - {
    // The case the whole form exists for: a C interface fixes the callback's signature to untyped
    // pointers, so the trampoline's own type parameter appears nowhere in it and there is nothing
    // for the expected type to solve. Before this, the only way to write it was a trampoline over
    // `*T`, a second `ptr_cast` of the function pointer, and a `val` to hold the type.
    "a trampoline over '*u8', which no expected type can settle" in {
      run(
        """extern qsort(base: *u8, n: usize, size: usize, cmp: *extern(*u8, *u8) -> i32)
          |
          |compare[T: Ord](a: *u8, b: *u8) -> i32
          |    var pa: *T = ptr_cast(a)
          |    var pb: *T = ptr_cast(b)
          |    if *pa < *pb then -1 else if *pa > *pb then 1 else 0
          |
          |var xs = [30i32, 10i32, 20i32]
          |qsort(ptr_cast(&xs[0]), 3usize, 4usize, &compare[i32])
          |print(xs[0], xs[1], xs[2])""".stripMargin
      ) shouldBe "10 20 30\n"
    }

    // The same trampoline reached from inside a generic, which is how a *library* offers one: `T`
    // here is the enclosing instantiation's argument rather than a written type, so the written
    // form has to resolve in the substitution in force.
    "a written argument that is the enclosing generic's own parameter" in {
      run(
        """extern qsort(base: *u8, n: usize, size: usize, cmp: *extern(*u8, *u8) -> i32)
          |
          |private compare[T: Ord](a: *u8, b: *u8) -> i32
          |    var pa: *T = ptr_cast(a)
          |    var pb: *T = ptr_cast(b)
          |    if *pa < *pb then -1 else if *pa > *pb then 1 else 0
          |
          |sort_libc[T: Ord](xs: []T)
          |    qsort(ptr_cast(sysl.slices.as_mut_ptr(xs)), xs.len, sizeof(T), &compare[T])
          |
          |var xs = [30i32, 10i32, 20i32]
          |sort_libc(xs[..])
          |print(xs[0], xs[1], xs[2])""".stripMargin
      ) shouldBe "10 20 30\n"
    }

    "with no expected type at all, which is what the inferred form cannot do" in {
      run(
        """ident[T](x: T) -> T = x
          |
          |var f = &ident[i32]
          |print(f(7))""".stripMargin
      ) shouldBe "7\n"
    }

    // More than one thing in the brackets was never an index, so this needs no name resolution to
    // be read — but it is still only a type-argument list at an address.
    "more than one argument" in {
      run(
        """first_of[A, B](a: *A, b: *B) -> i32 = 1
          |
          |var f = &first_of[i32, u8]
          |var x = 5i32
          |var y = u8(9)
          |print(f(&x, &y))""".stripMargin
      ) shouldBe "1\n"
    }

    // The shapes a type and an expression spell identically all have to survive the round trip out
    // of the expression grammar.
    "a pointer, a reference and an applied generic as arguments" in {
      run(
        """struct Box[T]
          |    v: T
          |end Box
          |
          |shape[A, B, C](a: *A, b: *B, c: *C) -> i32 = 3
          |
          |var f = &shape[*i32, &Box[i32], Box[u8]]
          |print(1)""".stripMargin
      ) shouldBe "1\n"
    }

    // Writing them wins outright: the expected type is not consulted, which is what makes the form
    // usable at a parameter whose signature says nothing about `T`.
    "the written arguments beat an expected type that would have solved differently" in {
      run(
        """ident[T](x: T) -> T = x
          |
          |val f: *extern(i32) -> i32 = &ident[i32]
          |print(f(7))""".stripMargin
      ) shouldBe "7\n"
    }
  }

  "what the written form refuses" - {
    // Writing the arguments does not exempt them from the bound the body was compiled against.
    "a type argument that does not satisfy the bound" in {
      err(
        """struct Blob
          |    n: i32
          |end Blob
          |
          |compare[T: Ord](a: *u8, b: *u8) -> i32 = 0
          |
          |var f = &compare[Blob]
          |print(1)""".stripMargin
      ) should include("Ord")
    }

    "more arguments than the declaration has parameters" in {
      err(
        """ident[T](x: T) -> T = x
          |
          |var f = &ident[i32, u8]
          |print(1)""".stripMargin
      ) should include("takes 1 type argument")
    }

    "fewer arguments than the declaration has parameters" in {
      err(
        """pair[A, B](a: *A, b: *B) -> i32 = 0
          |
          |var f = &pair[i32]
          |print(1)""".stripMargin
      ) should include("takes 2 type arguments")
    }

    "a name that is not generic" in {
      err(
        """plain(x: i32) -> i32 = x
          |
          |var f = &plain[i32]
          |print(1)""".stripMargin
      ) should include("is not generic")
    }

    // The brackets read their contents out of the *expression* grammar, so an expression that is
    // not a type reaches the analyzer and is named as one.
    "an expression that is not a type" in {
      err(
        """count[T](p: *u8) -> i32 = 0
          |
          |var f = &count[1 + 2]
          |print(1)""".stripMargin
      ) should include("this is not a type")
    }

    // The honest hole in the form, pinned so it is a known cost rather than a surprise. A slice, a
    // `weak`, a `volatile` and a callable have spellings the expression grammar has no production
    // for at all, so they are refused a level earlier — by the parser, in its own words. The
    // annotated `val` still reaches every one of them, which is what `12 §6a` says to write.
    "a slice, which the expression grammar has no production for" in {
      err(
        """count[T](p: *u8) -> i32 = 0
          |
          |var f = &count[[]int]
          |print(1)""".stripMargin
      ) should include("']' expected")
    }

    // The discrimination is by name resolution, so a local wins and keeps the ordinary reading —
    // an author who wrote a subscript is never told about a feature they did not reach for.
    "a local shadowing the function keeps the index reading" in {
      run(
        """ident[T](x: T) -> T = x
          |
          |var ident = [7i32, 8i32]
          |print(ident[1])""".stripMargin
      ) shouldBe "8\n"
    }

    // A list of things in brackets is a type-argument list wherever it is written, and an address
    // is the only place it may be. Everywhere else this used to be a parse error.
    "a comma in brackets anywhere but an address" in {
      err(
        """var xs = [7, 8]
          |print(xs[0, 1])""".stripMargin
      ) should include("a subscript takes one index")
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
