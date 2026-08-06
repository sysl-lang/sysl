package sh.sysl

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

  private val twoTraits =
    """trait A
      |    go(self) -> int
      |trait B
      |    go(self) -> int
      |impl A for []int
      |    go(self) -> int = 1
      |impl B for []int
      |    go(self) -> int = 2
      |""".stripMargin

  "two impls for one composed type share its member table" - {

    // A composed type's members are one namespace like any other type's, and like any other type's
    // that namespace is **per trait** (`13 §2`): both blocks are accepted, because which `go` a use
    // means is a question about what the file can name rather than about the slice.
    "so both are filed, and the declarations stand" in {
      run(twoTraits +
        """main()
          |    print("ok")""".stripMargin) shouldBe "ok\n"
    }

    // What used to be reported at the second `impl` is reported at the use that reaches both — the
    // later point, and the only one that knows a program actually wrote something ambiguous.
    "and a call reaching both is what is refused" in {
      err(twoTraits +
        """main()
          |    var a = [1, 2]
          |    print(a[0..].go())""".stripMargin,
      ) should include("which was meant")
    }
  }

  "reaching a member that is not there" - {

    "a slice with no impl has no method of that name" in {
      err(
        """var a = [1, 2]
          |print(a[0..].show())""".stripMargin,
      ) should include("type '[]int' has no method 'show'")
    }

    // A **slice** no longer reaches this advice at all, because the library implements `Display` for
    // every slice: what a `[]P` fails is that block's condition, so the diagnostic names the element
    // that does not render rather than telling the reader to write a block. An **array** is where
    // the advice still lives, since one `impl` cannot cover every length and nothing covers arrays.
    "printing a composed type points at the impl to write, where one could be written" in {
      err("""struct P
            |    v: int
            |var a = [P(1), P(2)]
            |print(a)""".stripMargin) should
        include(s"write an 'impl ${lib("Display")} for [2]P' to say how it renders")
    }

    // The slice half of the same question, which is now a condition rather than a missing block —
    // and the better answer, since the element is the part a reader can act on.
    "while a slice names the element its covering block asks about" in {
      err("""struct P
            |    v: int
            |var a = [P(1), P(2)]
            |print(a[0..])""".stripMargin) should
        include(s"the 'impl' that covers it asks '${lib("Display")}' of P, which does not implement it")
    }

    "and an array of them the same way, through however many shapes it takes" in {
      val e = err("""struct P
                    |    v: int
                    |var a = [[P(1)], [P(2)]]
                    |print(a)""".stripMargin)

      e should include(s"write an 'impl ${lib("Display")} for [2][1]P' to say how it renders")
    }

    /** A `[]int` prints, so the case is an **array** of a built-in: nothing covers arrays and
      * nothing in `[2]int` is the program's, which would leave the reader with a block they could
      * not write. What they are given instead is the whole-array view, which needs no block at all
      * — the assertion that matters here is still that no impossible `impl` is named.
      */
    "but where nothing in the subject is this module's, it names no block at all" in {
      val e = err("var a = [1, 2]\nprint(a)")

      e should include("print the whole-array view '[..]'")
      e should not include s"write an 'impl ${lib("Display")} for"
      e should not include "no home outside the library"
    }

    /** The defect the pair above exists for. Both diagnostics used to arrive in the same run — the
      * coherence rule refusing the block as having no home, and the print telling the reader to
      * write that very block. Advice naming something the compiler refuses is worse than none.
      */
    "so a program that tries the advice is not told to try it again" in {
      val e = err("""impl Display for [2]int
                    |    display(self, out: *Writer, fmt: FormatSpec)
                    |        display_str("ints", out, fmt)
                    |
                    |var a = [1, 2]
                    |print(a)""".stripMargin)

      e should include("has no home")
      e should not include s"write an 'impl ${lib("Display")} for"
    }

    // The same advice reaches the other renderer, since both ask one function for it.
    "and an interpolation is told the same thing print is" in {
      val e = err("var a = [1, 2]\nprint(f\"${a}\")")

      e should include("cannot make a string of")
      e should include("print the whole-array view '[..]'")
    }

    // A memory mode is one of the shapes an `impl` may not be for, so there is nothing to suggest.
    "printing a pointer says only that it does not render" in {
      err(
        """struct P
          |    v: int
          |var p = P(1)
          |print(&p)""".stripMargin,
      ) should include("it does not implement 'sysl.Display'")
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
