package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** What an `impl` may and may not be *for* (`02`), now that the type it names is a full type
 * reference rather than a name.
 *
 * The permissive half is the point — a slice and an array are types, so they carry an `impl` — and
 * the three refusals are each about a shape an implementation could not be about: a memory mode,
 * which is a way of holding a type; a trait object, which has forgotten which type it holds; and a
 * generic type, which is later work.
 */
class ImplComposedErrorTests extends AnyFreeSpec with CodegenSupport with RunSupport {

  private val show = "trait Show\n    show(self) -> string\n"

  "a shape an impl cannot be about" - {

    "a raw pointer is a way of holding a type, not one of its own" in {
      val msg = err(s"${show}struct P\n    v: int\nimpl Show for *P\n    show(self) -> string = \"p\"")

      msg should include("'*P' is a way of holding a P rather than a type of its own")
      msg should include("write the 'impl' for P")
    }

    "a counted reference is refused the same way" in {
      err(s"${show}struct P\n    v: int\nimpl Show for &P\n    show(self) -> string = \"p\"") should
        include("'&P' is a way of holding a P rather than a type of its own")
    }

    "a sync reference names its own spelling in the complaint" in {
      err(s"${show}struct P\n    v: int\nimpl Show for &sync P\n    show(self) -> string = \"p\"") should
        include("'&sync P' is a way of holding a P")
    }

    // The one thing an `impl` says is how *one particular* type behaves, which is exactly the fact
    // an erased value no longer carries.
    "a trait object has already forgotten which type it holds" in {
      val msg = err(
        """trait Show
          |    show(self) -> string
          |impl Show for *Show
          |    show(self) -> string = "s"""".stripMargin,
      )

      msg should include("has forgotten which type it holds")
    }

    // An implementation for one instantiation is a second implementation for a key that holds one,
    // which is why the fix is the block that covers the type as a whole.
    "an applied generic type is refused, naming the block to write instead" in {
      err(
        s"""${show}struct Box[T]
           |    v: T
           |impl Show for Box[int]
           |    show(self) -> string = "b"""".stripMargin,
      ) should include("write 'impl[T] Show for Box[T]'")
    }

    "an element type that does not exist is an unknown type, not a bad impl" in {
      err(s"${show}impl Show for []Ghost\n    show(self) -> string = \"g\"") should
        include("unknown type 'Ghost'")
    }
  }

  "the type is the key, not the spelling" - {

    "two spellings of one slice are one implementation" in {
      err(
        s"""${show}impl Show for []int
           |    show(self) -> string = "a"
           |impl Show for []i32
           |    show(self) -> string = "b"""".stripMargin,
      ) should include("'[]int' already implements 'Show'")
    }

    // The length is part of an array's type, so these are two types and the second is no duplicate
    // — what makes this a rejection is the *third* block, which repeats one of them.
    "two arrays of one length and element type are one implementation" in {
      err(
        s"""${show}impl Show for [2]int
           |    show(self) -> string = "a"
           |impl Show for [3]int
           |    show(self) -> string = "b"
           |impl Show for [2]int
           |    show(self) -> string = "c"""".stripMargin,
      ) should include("'[2]int' already implements 'Show'")
    }
  }

  "conformance names the type as a diagnostic writes it" - {

    "a missing member is reported against the slice" in {
      err(s"${show}impl Show for []int") should
        include("'[]int' does not implement 'Show': method 'show' is missing")
    }

    "a member the trait does not declare is reported the same way" in {
      err(s"${show}impl Show for []int\n    show(self) -> string = \"s\"\n    extra(self) -> int = 1") should
        include("trait 'Show' declares no method 'extra'")
    }

    "a mismatched result names the trait's" in {
      err(s"${show}impl Show for []int\n    show(self) -> int = 1") should
        include("returns int, but trait 'Show' declares string")
    }
  }

  "a compiler-provided member may not be hidden" - {

    // `len` is reached ahead of the member table, so a member of that name would be registered and
    // never found. It is the built-in analogue of an `impl` method colliding with a field.
    "a slice's len is out of reach for an impl" in {
      err(
        """trait Size
          |    len -> usize
          |impl Size for []int
          |    len -> usize = 0usize""".stripMargin,
      ) should include("'len' is a member the compiler provides for []int")
    }

    "a string's bytes are too" in {
      err(
        """trait Raw
          |    bytes -> []u8
          |impl Raw for string
          |    bytes -> []u8 = self.bytes""".stripMargin,
      ) should include("'bytes' is a member the compiler provides for string")
    }

    "an array's len is too" in {
      err(
        """trait Size
          |    len -> usize
          |impl Size for [3]int
          |    len -> usize = 0usize""".stripMargin,
      ) should include("'len' is a member the compiler provides for [3]int")
    }
  }

  "the end marker closes the type it opened" - {

    "a matching one is accepted" in {
      ir(
        s"""${show}impl Show for []int
           |    show(self) -> string = "s"
           |end []int
           |var a = [1]
           |print(a[0..].show())""".stripMargin,
      ) should include("@slice.int.show(")
    }

    "a different one is a parse error naming both" in {
      err(
        s"""${show}impl Show for []int
           |    show(self) -> string = "s"
           |end []bool""".stripMargin,
      ) should include("'end []bool' does not match '[]int'")
    }

    // The marker matches the *spelling*, exactly as `end Point` does — nothing has resolved either
    // reference when the parser compares them, so an alias of the same type is a different marker.
    "another spelling of the same type does not match it" in {
      err(
        s"""${show}impl Show for []int
           |    show(self) -> string = "s"
           |end []i32""".stripMargin,
      ) should include("'end []i32' does not match '[]int'")
    }
  }

  "two impls for one composed type share its member table" - {

    "so a name in both collides" in {
      err(
        """trait A
          |    go(self) -> int
          |trait B
          |    go(self) -> int
          |impl A for []int
          |    go(self) -> int = 1
          |impl B for []int
          |    go(self) -> int = 2""".stripMargin,
      ) should include("type '[]int' already has a member named 'go'")
    }
  }

  "reaching a member that is not there" - {

    "a slice with no impl has no method of that name" in {
      err(
        """var a = [1, 2]
          |print(a[0..].show())""".stripMargin,
      ) should include("type '[]int' has no method 'show'")
    }

    // Now that a slice may carry one, the advice is the `impl` to write rather than a bare
    // statement that it renders no way at all.
    "printing a slice points at the impl to write" in {
      err("var a = [1, 2]\nprint(a[0..])") should
        include("write an 'impl Display for []int' to say how it renders")
    }

    // A memory mode is one of the shapes an `impl` may not be for, so there is nothing to suggest.
    "printing a pointer says only that it does not render" in {
      err(
        """struct P
          |    v: int
          |var p = P(1)
          |print(&p)""".stripMargin,
      ) should include("it does not implement 'Display'")
    }
  }

  // The elements a slice views belong to whoever made them, so putting one inside an object that
  // outlives the frame is the ordinary escape rule rather than anything new about the erasure.
  // Erasing a view into a trait object is an escape — which is now answered by moving the array to
  // the heap rather than by refusing (`05`), so what this checks is that the erasure still happens
  // and still dispatches. The refusal that remains is for storage the body did not declare, and
  // `EscapeErrorTests` holds it.
  "erasing a frame-owned slice moves the array to the heap" in {
    run(
      """trait Show
        |    show(self) -> string
        |impl Show for []int
        |    show(self) -> string = "s"
        |name(t: &Show) -> string = t.show()
        |var a = [1, 2]
        |print(name(a[0..]))""".stripMargin,
    ) shouldBe "s\n"
  }
}
