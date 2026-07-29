package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Every place a value can come to have a constrained subtype, and the check that meets it there
 * (`16 §4`).
 *
 * The chapter's list of produce sites was written by adding them as they came up, so it was evidence
 * that the ones anybody had tried were covered rather than an argument that there were no others.
 * This file is the derivation instead: a value comes to have the type wherever it flows into a slot
 * the type is written on, so the sites are the slots — a binding, an assignment, a parameter, a
 * result, a field, an element, a payload, a part — plus the operations that produce one of their own
 * accord.
 *
 * Each site is written twice, once with a value the range accepts and once with the neighbouring
 * value it does not, because a site with no check passes the first and only the second can tell.
 */
class SubtypeProduceSiteTests extends AnyFreeSpec with RunSupport with CodegenSupport {

  private val Age  = "type Age = int within 0..150\n"
  private val Slot = "type Slot = new u8 within 0..<200\n"
  private val P    = Age + "struct P\n    age: Age\n    tag: int\n"

  "a value flowing into a slot the subtype is written on" - {
    "a local's initializer" in {
      run(Age + "var a: Age = 30\nprint(a)") shouldBe "30\n"
      exits(Age + "var a: Age = 200\nprint(a)")
    }

    "a later assignment to it" in {
      run(Age + "var a: Age = 30\na = 40\nprint(a)") shouldBe "40\n"
      exits(Age + "var a: Age = 30\na = 200\nprint(a)")
    }

    "one arm of a multi-assignment" in {
      run(Age + "var a: Age = 1\nvar b: Age = 2\na, b = 30, 40\nprint(a, b)") shouldBe "30 40\n"
      exits(Age + "var a: Age = 1\nvar b: Age = 2\na, b = 30, 200\nprint(a, b)")
    }

    "a module-level variable, written before anything runs" in {
      run(Age + "var g: Age = 30\nprint(g)") shouldBe "30\n"
      exits(Age + "var g: Age = 200\nprint(g)")
    }

    "an argument at a call" in {
      run(Age + "f(a: Age) -> int\n    int(a)\nprint(f(30))") shouldBe "30\n"
      exits(Age + "f(a: Age) -> int\n    int(a)\nprint(f(200))")
    }

    "a function's result" in {
      run(Age + "g(n: int) -> Age\n    n\nprint(g(30))") shouldBe "30\n"
      exits(Age + "g(n: int) -> Age\n    n\nprint(g(200))")
    }

    "an explicit cast" in {
      run(Age + "print(Age(30))") shouldBe "30\n"
      exits(Age + "print(Age(200))")
    }

    "a write through a pointer" in {
      run(Age + "var a: Age = 1\nvar p = &a\n*p = 30\nprint(a)") shouldBe "30\n"
      exits(Age + "var a: Age = 1\nvar p = &a\n*p = 200\nprint(a)")
    }
  }

  "a value flowing into a slot inside something else" - {
    "a field named by the positional constructor" in {
      run(P + "var p = P(30, 1)\nprint(p.age)") shouldBe "30\n"
      exits(P + "var p = P(200, 1)\nprint(p.age)")
    }

    "a field written later" in {
      run(P + "var p = P(30, 1)\np.age = 40\nprint(p.age)") shouldBe "40\n"
      exits(P + "var p = P(30, 1)\np.age = 200\nprint(p.age)")
    }

    "a field reached through a pointer" in {
      run(P + "var p = P(30, 1)\nvar q = &p\nq.age = 40\nprint(p.age)") shouldBe "40\n"
      exits(P + "var p = P(30, 1)\nvar q = &p\nq.age = 200\nprint(p.age)")
    }

    "an element of an array literal" in {
      run(Age + "var xs: [2]Age = [30, 40]\nprint(xs[0], xs[1])") shouldBe "30 40\n"
      exits(Age + "var xs: [2]Age = [30, 200]\nprint(xs[0], xs[1])")
    }

    "an element written by index" in {
      run(Age + "var xs: [2]Age = [1, 2]\nxs[0] = 30\nprint(xs[0])") shouldBe "30\n"
      exits(Age + "var xs: [2]Age = [1, 2]\nxs[0] = 200\nprint(xs[0])")
    }

    "an element written through a view of that array" in {
      run(Age + "var xs: [2]Age = [1, 2]\nvar v = xs[..]\nv[0] = 30\nprint(xs[0])") shouldBe "30\n"
      exits(Age + "var xs: [2]Age = [1, 2]\nvar v = xs[..]\nv[0] = 200\nprint(xs[0])")
    }

    "a part of a tuple" in {
      run(Age + "var t: (Age, int) = (30, 1)\nprint(t.0)") shouldBe "30\n"
      exits(Age + "var t: (Age, int) = (200, 1)\nprint(t.0)")
    }

    "a part written later" in {
      run(Age + "var t: (Age, int) = (30, 1)\nt.0 = 40\nprint(t.0)") shouldBe "40\n"
      exits(Age + "var t: (Age, int) = (30, 1)\nt.0 = 200\nprint(t.0)")
    }

    "the payload of an enum variant" in {
      val src = "var o: Option[Age] = Some(%s)\no match\n    Some(v) -> print(v)\n    None -> print(0)\n"

      run(Age + src.format("30")) shouldBe "30\n"
      exits(Age + src.format("200"))
    }

    // A generic container's element type is a type *argument*, so the slot the value lands in is
    // written `T` and only the instantiation says it is constrained. The check has to follow the
    // argument, not the spelling.
    "an item pushed into a container instantiated at the subtype" in {
      run(Age + "var b: Buf[Age] = buf()\nb.push(30)\nprint(b[0])") shouldBe "30\n"
      exits(Age + "var b: Buf[Age] = buf()\nb.push(200)\nprint(b[0])")
    }
  }

  "a value flowing out of a body whose result type is the subtype" - {
    "a closure's" in {
      run(Age + "var f: &Fn(int) -> Age = x -> x\nprint(f(30))") shouldBe "30\n"
      exits(Age + "var f: &Fn(int) -> Age = x -> x\nprint(f(200))")
    }

    "a nested function's" in {
      val src = Age + "outer(n: int) -> int\n    inner(x: int) -> Age\n        x\n    int(inner(n))\nprint(outer(%s))\n"

      run(src.format("30")) shouldBe "30\n"
      exits(src.format("200"))
    }

    "a method's, reached through the trait it implements" in {
      val src = Age + "trait Aged\n    age(self) -> Age\nstruct P\n    n: int\n" +
        "impl Aged for P\n    age(self) -> Age\n        self.n\nvar p = P(%s)\nprint(p.age())\n"

      run(src.format("30")) shouldBe "30\n"
      exits(src.format("200"))
    }
  }

  /** The two sites that are the subtype's *own* doing rather than a slot's — an operation whose
   * result is one, which only a derived subtype has (`16 §3`), and the two forms that compute and
   * store in one step. `SubtypeOperatorTests` is where these are worked out; they are named here so
   * that the derivation this file records is complete on its own terms.
   */
  "a value the subtype's own operations produce" - {
    "arithmetic on a derived subtype" in {
      run(Slot + "print(Slot(100) + Slot(50))") shouldBe "150\n"
      exits(Slot + "print(Slot(199) + Slot(1))")
    }

    "a compound assignment" in {
      run(Age + "var a: Age = 30\na += 10\nprint(a)") shouldBe "40\n"
      exits(Age + "var a: Age = 30\na += 200\nprint(a)")
    }

    "an increment" in {
      run(Age + "var a: Age = 30\na++\nprint(a)") shouldBe "31\n"
      exits(Age + "var a: Age = 150\na++\nprint(a)")
    }
  }

  /** The other half of the derivation: a value that already has the type is not re-checked, because
   * it could not have got there unchecked. These are the places a check would be waste rather than
   * safety, and each is written so that a compiler which checked anyway would still pass — what they
   * pin is that the sites above are the ones that matter, not that nothing else may ever be checked.
   */
  "a value already of the type is passed along, not produced again" - {
    "reading one and printing it" in {
      run(Age + "var a: Age = 30\nvar b = a\nprint(b)") shouldBe "30\n"
    }
    "handing one to a parameter of the same type" in {
      run(Age + "f(a: Age) -> Age\n    a\nvar a: Age = 30\nprint(f(a))") shouldBe "30\n"
    }
    "using one as its base, which needs no cast" in {
      run(Age + "var a: Age = 30\nvar n: int = a\nprint(n + 1)") shouldBe "31\n"
    }
    // Passing one to a *different* subtype over the same base is a produce site again, which is what
    // keeps a wider type from leaking into a narrower one.
    "but a different subtype over the same base is checked again" in {
      val both = Age + "type Small = int within 0..10\nnarrow(a: Age) -> Small\n    a\n"

      run(both + "print(narrow(Age(5)))") shouldBe "5\n"
      exits(both + "print(narrow(Age(100)))")
    }
  }

  /** The site that would produce a value nobody wrote is a declaration with no initializer, and it is
   * closed by not existing: a constrained subtype has **no zero value**, so the declaration is
   * refused and there is nothing to check. That matters because a zero is the one value that could
   * reach a slot without passing a produce site at all — `Codegen.zero` answers for a constrained
   * type by answering for its base, which is a `0` whether or not the range contains one.
   *
   * The rule is the *type's*, not the range's: a range containing zero is refused too. One rule means
   * widening or narrowing a range never silently changes whether a declaration elsewhere compiles,
   * and what a program writes instead — `= 0` — is the produce site that says the value was meant.
   */
  "a slot with no written value is refused rather than zeroed" - {
    "a subtype whose range excludes zero has no zero value" in {
      err("type Tens = u8 within 10..20\nvar a: Tens") should include("Tens has no zero value")
    }
    "nor does one whose range contains it" in {
      err("type Small = u8 within 0..20\nvar a: Small") should include("Small has no zero value")
    }
    "and the refusal reaches through a struct, whose zero is its fields'" in {
      err("type Tens = u8 within 10..20\nstruct P\n    a: Tens\nvar p: P") should
        include("P has no zero value")
    }
    "an array of them is refused for the same reason" in {
      err("type Tens = u8 within 10..20\nvar xs: [2]Tens") should include("no zero value")
    }
  }
}
