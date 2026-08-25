package sh.sysl

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** `@no_alloc` meeting a **generic**, which is where "what this module does" stops being obvious
 * (`reference/modules.md § A generic answers for what it wrote, not for what its caller chose`).
 *
 * The clause is a promise a module makes about its own conduct — *no execution that begins in this
 * module's code makes heap storage.* A generic has no execution until a type is chosen, so the module
 * that declared it is not the module that decided what it reaches. That is the whole of what these
 * pin: an instance is charged for what it **constructs**, which is the same at every instantiation
 * and is written in the library's own text, and not for what it **reaches**, which its caller chose.
 *
 * The promise is not weakened where it is load-bearing, and the second section is that claim: on a
 * target with no heap every module is allocator-free without a clause being written anywhere, so the
 * caller is checked too and the walk from *its* body goes straight through the instance.
 */
class NoAllocGenericTests extends AnyFreeSpec with Matchers with RunSupport with CodegenSupport {

  /** An allocator-free library whose generic calls through a bound, so what it reaches is the
   * caller's choice of type and nothing else.
   */
  private val lib =
    """module lib
      |@no_alloc
      |
      |trait Sink
      |    put(*self, s: string)
      |
      |twice[S: Sink](s: *S, msg: string)
      |    s.put(msg)
      |    s.put(msg)
      |""".stripMargin

  /** A hosted program whose `impl` allocates — `cstring` makes heap storage, which is what every real
   * backend for a bound like this does, since handing a sysl `string` to C needs one.
   */
  private val hosted =
    """import lib.*
      |import sysl.text.cstring
      |
      |struct Loud
      |    n: int
      |
      |impl Sink for Loud
      |    put(*self, s: string)
      |        val c = cstring(s)
      |        self.n += 1
      |
      |main()
      |    var l = Loud(0)
      |    twice(&l, "hi")
      |    print(l.n)
      |""".stripMargin

  private def compiledFor(caps: Set[String])(fs: (String, String)*): Either[String, String] =
    Compiler.compiledWith(fs.toList.map((n, t) => Source(n, t)), Nil, Target.default, Set.empty, None, caps)
      .map(_.ir)

  /** These two state what a bound's call should cost and are **not yet what the compiler does**: the
   * pair is refused, with the diagnostic pointing at `s.put(msg)` inside the library — a line that is
   * allocator-free as written, in a module that promised nothing about a type it never saw.
   *
   * The distinction the fix has to draw is finer than "an instance is nobody's declaration", which was
   * tried and is too blunt: a call in a generic body to a **concrete** function is the declaring
   * module's own conduct at every instantiation, and only a call **through a bound** is the caller's
   * choice. Excusing the whole instance excuses both, and lets a generic whose body calls an allocator
   * outright compile on a target that has no heap — which the section below is what catches.
   */
  "a generic's caller is who chose the type, so the caller is who answers for it" - {
    "a hosted program may instantiate an allocator-free library's generic at a type that allocates" in {
      runOf("lib/lib.sysl" -> lib, "main.sysl" -> hosted) shouldBe "2\n"
    }

    "and the same program compiles identically with the library's clause removed" in {
      runOf("lib/lib.sysl" -> lib.replace("@no_alloc\n", ""), "main.sysl" -> hosted) shouldBe "2\n"
    }

    // What the module *constructs* is unchanged by the type argument — `&x` boxes at every
    // instantiation — so it is still charged where it is written, which is what keeps the clause from
    // meaning nothing on a generic.
    "but a construction in the generic's own body is still the library's, at every instantiation" in {
      val e = errOf(
        "lib/lib.sysl" ->
          """module lib
            |@no_alloc
            |
            |boxed[T](x: T) -> &T = x
            |""".stripMargin,
        "main.sysl" -> "import lib.*\n\nprint(*boxed(3))\n",
      )

      e should include("a reference needs an allocator")
      e should include("lib/lib.sysl")
    }
  }

  "the promise is not weakened where it is load-bearing" - {

    // The argument the whole decision rests on. A project with no heap makes *every* module
    // allocator-free with no clause written anywhere, so the module that chose the type is itself
    // checked — and the reachability walk from its body goes through the instance to what the type
    // argument dragged in. Nothing is given up on the machine the clause exists for.
    "on a target with no heap the instantiating module is refused, since it is checked too" in {
      val e = compiledFor(Capability.core.toSet - Capability.Heap)(
        "lib/lib.sysl" -> lib,
        "main.sysl"    -> hosted,
      )

      e.isLeft shouldBe true
      e.swap.getOrElse("") should include("makes heap storage")
    }

    // Ignored with the two above and for the same reason: it is the accepting half of the same pair.
    // **The counter-example that killed the first attempt at this**, kept as a test because it is the
    // exact shape a blunter fix gets wrong. `cstring` here has nothing to do with `T` — it is the
    // module's own conduct at every instantiation — and it appears nowhere but inside an instance
    // body, so excusing the whole instance hides it and this compiles with no heap anywhere.
    "a generic that allocates outright is still its own module's, since no type argument chose it" in {
      val e = compiledFor(Capability.core.toSet - Capability.Heap)(
        "main.sysl" ->
          """import sysl.text.cstring
            |
            |thru[T](x: T, s: string) -> usize = cstring(s).len
            |
            |main()
            |    print(thru(1, "hi"))
            |""".stripMargin,
      )

      e.isLeft shouldBe true
      e.swap.getOrElse("") should include("makes heap storage")
    }

    "while the same pair compiles for a target that has one" in {
      compiledFor(Capability.core.toSet)("lib/lib.sysl" -> lib, "main.sysl" -> hosted).isRight shouldBe true
    }

    // The one-line way around the whole thing, if a generic's own calls were not followed: `f` makes
    // no storage and reaches an allocator only through another generic, whose instantiation belongs
    // to nobody. What answers it is that a call in a generic body to another generic leads to the
    // **body that was written**, exactly as a call to a concrete function leads to its.
    "a generic that reaches an allocator through another generic is still its own module's" in {
      val e = errOf(
        "lib/lib.sysl" ->
          """module lib
            |
            |grow[T: Display](x: T) -> string = s"$x!"
            |""".stripMargin,
        "a/a.sysl" ->
          """module a
            |@no_alloc
            |
            |import lib.*
            |
            |f[U: Display](u: U) -> usize = grow(u).len
            |""".stripMargin,
        "main.sysl" -> "import a.*\n\nprint(f(1))\n",
      )

      e should include("a/a.sysl")
    }

    // A member of a generic type is a generic like any other, and what it constructs is the
    // declaring module's at every instantiation, whoever chose the `T`.
    "a generic type's own member is charged where it is written" in {
      val e = errOf(
        "lib/lib.sysl" ->
          """module lib
            |@no_alloc
            |
            |struct Cell[T]
            |    v: T
            |
            |    boxed(self) -> &T = self.v
            |""".stripMargin,
        "main.sysl" -> "import lib.*\n\nprint(*Cell(3).boxed())\n",
      )

      e should include("a reference needs an allocator")
      e should include("lib/lib.sysl")
    }
  }

  "the line is drawn at the type argument, and these are the cases that test where it falls" - {

    // A trait's **default** body is written in the trait's own module and is the same at every
    // instantiation, so a default that allocates is the library's conduct however the caller's type
    // is chosen. It is excluded by name without a rule of its own: a default is emitted under the
    // trait's symbol, not the argument's.
    "a trait default that allocates is the library's, not the caller's" in {
      val e = errOf(
        "lib/lib.sysl" ->
          """module lib
            |@no_alloc
            |
            |import sysl.text.cstring
            |
            |trait Chatty
            |    say(*self) -> usize = cstring("spoken").len
            |
            |use[C: Chatty](c: *C) -> usize = c.say()
            |""".stripMargin,
        "main.sysl" ->
          """import lib.*
            |
            |struct Quiet
            |    n: int
            |
            |impl Chatty for Quiet
            |
            |main()
            |    var q = Quiet(0)
            |    print(use(&q))
            |""".stripMargin,
      )

      e should include("lib/lib.sysl")
    }

    // The type argument is itself an instantiation, so its members are emitted under the mangled
    // name — `Box.int.put` and not `Box.put`. The ownership test is over that mangling, so a nested
    // argument is recognised exactly as a plain one is.
    "a type argument that is itself generic is still the caller's choice" in {
      runOf(
        "lib/lib.sysl" ->
          """module lib
            |@no_alloc
            |
            |trait Sink
            |    put(*self, s: string)
            |
            |once[S: Sink](s: *S, msg: string)
            |    s.put(msg)
            |""".stripMargin,
        "main.sysl" ->
          """import lib.*
            |import sysl.text.cstring
            |
            |struct Box[T]
            |    v: T
            |    n: int
            |
            |impl[T] Sink for Box[T]
            |    put(*self, s: string)
            |        val c = cstring(s)
            |        self.n += 1
            |
            |main()
            |    var b = Box(1, 0)
            |    once(&b, "hi")
            |    print(b.n)
            |""".stripMargin,
      ) shouldBe "1\n"
    }
  }

  /** Two allocator-free modules calling **one** instantiation. It is memoized, so there is a single
   * `TFunc` behind both — which is what made the answer belong to whichever module the walk reached
   * first, and made marking one silence the other.
   */
  private def two(second: String) =
    List(
        "lib/lib.sysl" ->
          """module lib
            |
            |grow[T: Display](x: T) -> string = s"$x!"
            |""".stripMargin,
        "a/a.sysl" ->
          """module a
            |@no_alloc
            |
            |import lib.*
            |
            |f() -> usize = grow(1).len
            |""".stripMargin,
        "b/b.sysl" ->
          s"""module b
             |$second
             |
             |import lib.*
             |
             |g() -> usize = grow(1).len
             |""".stripMargin,
        "main.sysl" -> "import a.*\nimport b.*\n\nprint(f() + g())\n",
    )

  "the verdict does not depend on which caller the walk reached first" - {

    "both are reported, rather than whichever got there first" in {
      val e = errOf(two("@no_alloc")*)

      e should include("a/a.sysl")
      e should include("b/b.sysl")
    }

    "and marking the second does not silence the first" in {
      errOf(two("")*) should include("a/a.sysl")
    }
  }
}
