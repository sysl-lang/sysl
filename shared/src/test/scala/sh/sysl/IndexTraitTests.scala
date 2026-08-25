package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Indexing a type the compiler has no elements for — `b[i]` through the library's `Index`.
 *
 * `library/core.md § Walking a type of your own` filed this under associated types, on the reading
 * that a subscript wants the element type *and* the index type and neither is `Self`. That reading
 * turned out to be wrong in one place, and the place is worth stating because it is what this
 * feature rests on. The element type does not have to be inferred from anything: it can be an
 * ordinary trait argument, written where the block is written, **because the subject binds it** —
 * `impl[T] Index[usize, T] for Buf[T]` says a `Buf` of anything is indexed by a `usize` and gives
 * back whatever it holds, and every particular `Buf` settles what that is. What made this
 * impossible before was one refusal, not a missing feature.
 *
 * That refusal was right about its own case and wrong about this one, and both cases are pinned
 * here. `impl[T] From[T] for Wrapper` leaves the argument genuinely open — nothing decides which
 * `From` the block implements — and stays refused. The relaxation is safe because sysl has no
 * specialization at all: an `impl` for a generic type covers every instantiation and a block for one
 * instantiation is refused outright, so a parameter the subject binds is a key that matches exactly
 * one thing per subject.
 *
 * The suite is in the two halves `9b` asks for. The first asks whether the documents' claims survive
 * — about coherence, about how an implementation is selected, about what a bound means. The second
 * asks what breaks at the second implementation, the empty case, the parameter written twice, and
 * the boundaries with the built-in subscript and with assignment.
 */
class IndexTraitTests extends AnyFreeSpec with RunSupport with CodegenSupport {

  /** `Index` and `IndexSet` are the standard module's, since a subscript is what reaches them — but
   * the library type they are demonstrated on is `sysl.buf`'s, so the programs here ask for it.
   */
  private val importing = "import sysl.buf.*\n\n"

  override protected def run(src: String): String = super.run(importing + src)

  override protected def err(src: String): String = super.err(importing + src)

  override protected def ir(src: String): String = super.ir(importing + src)

  override protected def panics(src: String, message: String): Unit = super.panics(importing + src, message)

  /** A trait with an index type and an element type, and a struct that keeps two of it. */
  private val get =
    """trait Get[I, E]
      |    get(self, i: I) -> E
      |struct Row
      |    a: int
      |impl Get[usize, int] for Row
      |    get(self, i: usize) -> int = self.a + int(i)
      |impl Get[bool, string] for Row
      |    get(self, f: bool) -> string = if f then "yes" else "no"
      |""".stripMargin

  "what the documents claim" - {
    // `reference/traits.md § One implementation per argument list` — and a two-parameter trait is
    // no different from a one-parameter one, which is the claim being checked rather than assumed.
    "a trait taking two arguments is implemented at two argument lists" in {
      run(get + """print(Row(3).get(1usize))
                  |print(Row(3).get(true))""".stripMargin) shouldBe "4\nyes\n"
    }

    // The element types differ between the two, so what the call gives back is decided by which
    // implementation the *index* selected. Nothing at the call site wrote the element type.
    "the element type comes from the implementation the index selected" in {
      run(get + """var s: string = Row(3).get(false)
                  |var n: int = Row(3).get(0usize)
                  |print(s, n)""".stripMargin) shouldBe "no 3\n"
    }

    // `reference/traits.md § An impl covers a generic type as a whole` — the fact the relaxation
    // rests on. If a block for one instantiation were allowed, a parameter in an argument list
    // would be a key matching many things, which is the matching problem sysl does not have.
    "a block for one instantiation of a generic type is refused, so there is no overlap to resolve" in {
      err("""trait Named
            |    name(self) -> string
            |struct Box[T]
            |    v: T
            |impl[T] Named for Box[T]
            |    name(self) -> string = "any"
            |impl Named for Box[int]
            |    name(self) -> string = "int"
            |""".stripMargin) should include(
        "'Box' is generic, so an 'impl' for it covers every instantiation at once")
    }

    // A trait argument built out of a parameter the subject settles. This is the whole feature: the
    // block is written once and promises one thing per instantiation, exactly as a defaulted
    // argument list does on a generic subject.
    "a generic subject may write a trait argument its own type settles" in {
      run("""trait Get[I, E]
            |    get(self, i: I) -> E
            |impl[T] Get[usize, T] for Buf[T]
            |    get(self, i: usize) -> T = self.at(i)
            |var b: Buf[string] = buf()
            |b.push("hi")
            |print(b.get(0usize))""".stripMargin) shouldBe "hi\n"
    }

    // And the argument is read as what the subject made it: the diagnostic names `Get[usize, string]`
    // for a `Buf[string]`, not the `T` the block wrote.
    "a bound is met by what the subject makes the argument, and reported that way when it is not" in {
      val src =
        """trait Get[I, E]
          |    get(self, i: I) -> E
          |impl[T] Get[usize, T] for Buf[T]
          |    get(self, i: usize) -> T = self.at(i)
          |firstOf[C: Get[usize, int]](c: C) -> int = c.get(0usize)
          |var s: Buf[string] = buf()
          |s.push("x")
          |print(firstOf(s))""".stripMargin

      err(src) should include(
        s"requires its type parameter 'C' to implement 'Get[usize, int]', but " +
          s"${Modules.show(Library.key("Buf"))}[string] does not — it implements 'Get[usize, string]'")
    }

    "and the same bound is met by the instantiation that does supply it" in {
      run("""trait Get[I, E]
            |    get(self, i: I) -> E
            |impl[T] Get[usize, T] for Buf[T]
            |    get(self, i: usize) -> T = self.at(i)
            |firstOf[C: Get[usize, int]](c: C) -> int = c.get(0usize)
            |var a: Buf[int] = buf()
            |a.push(5)
            |print(firstOf(a))""".stripMargin) shouldBe "5\n"
    }

    // `reference/expressions.md § Operator dispatch` — an operator is the trait's one method. `[]`
    // is `Index`'s, and the library implements it for the one growable container it ships.
    "a Buf is read with a subscript" in {
      run("""var b: Buf[int] = buf()
            |b.push(7)
            |b.push(8)
            |print(b[0usize], b[1usize])""".stripMargin) shouldBe "7 8\n"
    }

    "and written with one" in {
      run("""var b: Buf[int] = buf()
            |b.push(7)
            |b.push(8)
            |b[1usize] = 42
            |print(b[0usize], b[1usize])""".stripMargin) shouldBe "7 42\n"
    }

    // `reference/traits.md § Conditional conformance` — the condition is answered against the
    // arguments the subject was made with, and the block's parameters are matched to the subject's
    // positions rather than to the order the block declared them in.
    "a conditional block's bound follows the subject's positions, not the order the block wrote" in {
      val src =
        """trait Show2
          |    show2(self) -> string
          |struct Pair[X, Y]
          |    a: X
          |    b: Y
          |impl Show2 for int
          |    show2(self) -> string = "int"
          |impl[A, B: Show2] Show2 for Pair[B, A]
          |    show2(self) -> string = "pair"
          |tell[T: Show2](x: T) -> string = x.show2()
          |""".stripMargin

      run(src + "print(tell(Pair(1, true)))") shouldBe "pair\n"
      err(src + "print(tell(Pair(true, 1)))") should include(
        "the 'impl' that covers it asks 'Show2' of bool, which does not implement it")
    }
  }

  "what a subscript may index by" - {
    // The index is not held to being an integer, which the built-in subscript is. A container read
    // by something else is implementing a different `Index`, not misusing this one.
    "a type may be indexed by something that is not an integer" in {
      run("""struct Env
            |    fallback: int
            |impl Index[string, int] for Env
            |    index(self, k: string) -> int = if k == "answer" then 42 else self.fallback
            |var e = Env(0)
            |print(e["answer"], e["other"])""".stripMargin) shouldBe "42 0\n"
    }

    "and by two things at once, the written index saying which" in {
      run("""struct Env
            |    fallback: int
            |impl Index[string, int] for Env
            |    index(self, k: string) -> int = if k == "answer" then 42 else self.fallback
            |impl Index[bool, int] for Env
            |    index(self, f: bool) -> int = if f then 1 else 0
            |var e = Env(7)
            |print(e["answer"], e["nope"], e[true])""".stripMargin) shouldBe "42 7 1\n"
    }

    // The built-in subscript is untouched: an array, a slice and a string are indexed by the
    // compiler and no block competes with that.
    "a built-in's subscript is still the compiler's" in {
      run("""var a: [3]int = [4, 5, 6]
            |var s = a[1..<3]
            |print(a[1], s[1], "abc"[2])""".stripMargin) shouldBe "5 6 99\n"
    }

    "a type with no Index still cannot be indexed" in {
      err("""struct Box
            |    v: int
            |print(Box(1)[0usize])""".stripMargin) should include("cannot index Box")
    }
  }

  "what a second implementation may not be" - {
    // The open case — `impl[T] From[T] for Wrapper`, an implementation at every `From` at once — is
    // refused, and it is refused *before* the trait's arguments are read: a block for a type with
    // no arguments has nothing to be generic over, so the parameter it declared never gets as far as
    // being written into an argument list.
    "a block whose subject takes no arguments has no parameter to write into one" in {
      err("""trait From[T]
            |    of(x: T) -> Self
            |struct Wrapper
            |    v: int
            |impl[T] From[T] for Wrapper
            |    of(x: T) -> Wrapper = Wrapper(0)
            |""".stripMargin) should include(
        "'Wrapper' takes no type arguments, so an 'impl' for it has nothing to be generic over")
    }

    // And a parameter the subject does not name is refused for the same reason one step along: this
    // is what makes an argument built out of a parameter safe without a check of its own, since
    // every parameter an argument *can* name is one the subject settles.
    "a parameter the subject does not name is refused, which is what settles every argument" in {
      err("""trait Get[I, E]
            |    get(self, i: I) -> E
            |impl[T, U] Get[U, T] for Buf[T]
            |    get(self, i: U) -> T = self.at(0usize)
            |""".stripMargin) should include(
        "'U' is declared by this 'impl' but does not appear in 'Buf[T]', so nothing would ever fix it")
    }

    // Two blocks that promise the same thing are the same promise however their authors spelled the
    // parameter, which is what the substitution at the duplicate check is for.
    "two blocks that differ only in the letter of the parameter are one implementation twice" in {
      err("""trait Get[I, E]
            |    get(self, i: I) -> E
            |impl[T] Get[usize, T] for Buf[T]
            |    get(self, i: usize) -> T = self.at(i)
            |impl[U] Get[usize, U] for Buf[U]
            |    get(self, i: usize) -> U = self.at(i)
            |""".stripMargin) should include("already implements 'Get[usize, U]'")
    }

    // And two that differ in the argument are two promises, not a redeclaration.
    "two that differ in the argument are two implementations" in {
      run("""trait Get[I, E]
            |    get(self, i: I) -> E
            |impl[T] Get[usize, T] for Buf[T]
            |    get(self, i: usize) -> T = self.at(i)
            |impl[T] Get[bool, usize] for Buf[T]
            |    get(self, f: bool) -> usize = if f then self.len() else 0usize
            |var b: Buf[int] = buf()
            |b.push(3)
            |b.push(4)
            |print(b.get(1usize), b.get(true))""".stripMargin) shouldBe "4 2\n"
    }
  }

  "what the edge cases do" - {
    // A parameter may be written *inside* an argument rather than as the whole of one, and the
    // substitution has to reach in — `[]T` at a `Buf[int]` is `[]int`.
    "a parameter reached through a composed argument is substituted too" in {
      run("""trait Get[I, E]
            |    get(self, i: I) -> E
            |impl[T] Get[bool, []T] for Buf[T]
            |    get(self, f: bool) -> []T = if f then self.view() else self.view()[0..<0usize]
            |var b: Buf[int] = buf()
            |b.push(3)
            |b.push(4)
            |var all: []int = b.get(true)
            |print(all.len, b.get(false).len)""".stripMargin) shouldBe "2 0\n"
    }

    // The element type is an argument like any other, so nothing infers it from the bound alone.
    // This is `02`'s "two generics cannot solve each other" met one step further along, and the
    // annotation is the same answer it always was.
    "the element type is not inferred from a bound, and the annotation supplies it" in {
      val decls =
        """trait Get[I, E]
          |    get(self, i: I) -> E
          |struct Row
          |    a: int
          |impl Get[usize, int] for Row
          |    get(self, i: usize) -> int = self.a
          |first[E, C: Get[usize, E]](c: C) -> E = c.get(0usize)
          |""".stripMargin

      err(decls + "print(first(Row(3)))") should include("cannot infer the type argument 'E' of 'first'")
      run(decls + "var x: int = first(Row(3))\nprint(x)") shouldBe "3\n"
    }

    // A subscript is a call, so the receiver and the index would each be evaluated twice by a
    // read-modify-write. Written out, the program says that itself.
    "a compound assignment through Index is refused rather than evaluated twice" in {
      err("""var b: Buf[int] = buf()
            |b.push(1)
            |b[0usize] += 2""".stripMargin) should include(
        s"'+=' on an element read through '${lib("Index")}' would evaluate the receiver and the " +
          "index twice")
    }

    "and the form it points at works" in {
      run("""var b: Buf[int] = buf()
            |b.push(1)
            |b[0usize] = b[0usize] + 2
            |print(b[0usize])""".stripMargin) shouldBe "3\n"
    }

    // `library/core.md § Walking a type of your own` defers slicing through the trait and gives the
    // reason — the index would have to be a range, and a range is not a type a program can name. A
    // type that has an `Index` is exactly who reaches for the neighbouring form, and the bare
    // "cannot slice" reads as though its shape were wrong for an operation that exists rather than
    // as a feature that is not built.
    "slicing through Index is not built, and says so rather than that the type is the wrong shape" in {
      val src =
        """struct Row
          |    a: int
          |    b: int
          |
          |impl Index[usize, int] for Row
          |    index(self, i: usize) -> int = if i == 0usize then self.a else self.b
          |
          |var r = Row(1, 2)
          |print(r[0usize..<2usize])""".stripMargin

      err(src) should include("slicing through the trait is not built")
      err(src) should include("a range is not yet a type a program can name")
      err(src) should not include "cannot slice Row"
    }

    // While a type with no indexing at all keeps the plain refusal, since for it the shape really
    // is the whole story.
    "while a type that indexes no way at all keeps the plain one" in {
      err("struct P\n    v: int\nvar p = P(1)\nprint(p[0usize..<1usize])") should include("cannot slice P")
    }

    // Reading takes `self` by value and writing takes `*self`, so a container reached through a
    // pointer indexes both ways, and one held by a counted reference does too.
    "a container is indexed through a pointer and through a counted reference" in {
      run("""fill(b: *Buf[int])
            |    b.push(1)
            |    b[0usize] = 5
            |var b: Buf[int] = buf()
            |fill(&b)
            |var r: &Buf[int] = buf()
            |r.push(2)
            |r[0usize] = 9
            |print(b[0usize], r[0usize])""".stripMargin) shouldBe "5 9\n"
    }

    // The bounds check is the container's, not the compiler's, and it is still there under the
    // subscript — the operator changed how it is written, not what it does.
    "a subscript past the end stops the program the way the method did" in {
      panics("""var b: Buf[int] = buf()
               |b.push(1)
               |print(b[4usize])""".stripMargin, "panic: index 4 past the 1 elements of a Buf")
    }

    // Writing takes `*self`, so the receiver has to be something an address can be taken of — a
    // freshly built container is not, and the store is refused rather than made to a temporary that
    // nothing could read back.
    "writing into a container that is not a place is refused" in {
      err("""trait Get[I, E]
            |    get(self, i: I) -> E
            |var b: Buf[int] = buf()
            |b.push(1)
            |buf()[0usize] = 5""".stripMargin) should not be empty
    }

    // The element type travels with the implementation rather than with the trait, so two
    // containers over one trait each give back their own.
    "the element type travels with the implementation, so two containers of one trait keep theirs" in {
      run("""var a: Buf[int] = buf()
            |var s: Buf[string] = buf()
            |a.push(1)
            |s.push("x")
            |var n: int = a[0usize]
            |var t: string = s[0usize]
            |print(n, t)""".stripMargin) shouldBe "1 x\n"
    }

    // An implementation whose members were filed under a suffix is still reached by the subscript,
    // which is the one thing the operator path could have got wrong: `Index` written second gets
    // `index.2`, and nothing outside the hoist knows that name.
    "a subscript reaches an Index that was written after another implementation of it" in {
      run("""struct Env
            |    fallback: int
            |impl Index[bool, int] for Env
            |    index(self, f: bool) -> int = if f then 1 else 0
            |impl Index[string, int] for Env
            |    index(self, k: string) -> int = if k == "answer" then 42 else self.fallback
            |var e = Env(7)
            |print(e["answer"], e[false])""".stripMargin) shouldBe "42 0\n"
    }

    // The index is the trait's own argument and is not held to being an integer, so a container
    // read by a pair is read by a *tuple*, and the two-index accessor `14` said a subscript could
    // not spell is ordinary. It needed nothing of `Index`: the shape arrived when tuples did.
    "a container read by two indices is read by a tuple" in {
      run("""struct Img
            |    w: int
            |    cells: [6]int
            |impl Index[(int, int), int] for Img
            |    index(self, at: (int, int)) -> int = self.cells[at.1 * self.w + at.0]
            |impl IndexSet[(int, int), int] for Img
            |    index_set(*self, at: (int, int), v: int)
            |        self.cells[at.1 * self.w + at.0] = v
            |var i = Img(3, [1, 2, 3, 4, 5, 6])
            |print(i[(2, 1)])
            |i[(0, 0)] = 60
            |print(i[(0, 0)], i[(1, 0)])""".stripMargin) shouldBe "6\n60 2\n"
    }

    // Two implementations, and an index that names neither: reported rather than resolved by a
    // preference rule, which is the same answer a call with the same ambiguity gets.
    "an index that names no implementation is reported" in {
      err("""struct Env
            |    fallback: int
            |impl Index[string, int] for Env
            |    index(self, k: string) -> int = self.fallback
            |impl Index[bool, int] for Env
            |    index(self, f: bool) -> int = if f then 1 else 0
            |var e = Env(7)
            |print(e[0usize])""".stripMargin) should include("none of them takes")
    }
  }
}
