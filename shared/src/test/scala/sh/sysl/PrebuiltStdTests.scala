package sh.sysl

import io.github.edadma.cross_platform.*

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** The run tier compiled against a **prebuilt standard module** — `Stdlib.resolve` at the default
 * path — rather than against the copy compiled in from source.
 *
 * The point of these is not that the artifact works (`LibraryArtifactTests` is where that is held)
 * but that **the suite is actually taking that path**. Resolution answers with a `Left` where there
 * is no toolchain, and at every call site that is indistinguishable from the compilation the suite
 * has always done: everything would stay green while the artifact half went unexercised, which is
 * the failure this file exists to make loud.
 *
 * It also pins the API sysl.sh depends on. The documentation site drives the compiler as a published
 * library and reaches its standard module through exactly these calls, so a change that made
 * `resolve` answer differently would break a repository that is not this one — and would do it at
 * whatever moment somebody next raised a version there, rather than here.
 */
class PrebuiltStdTests extends AnyFreeSpec with Matchers {

  private val hello =
    """print("hello")
      |""".stripMargin

  private def defines(ir: String): Int = ir.linesIterator.count(_.startsWith("define"))

  private def prebuilt: Stdlib.Resolved =
    Stdlib.resolve(Stdlib.Choice.Default(), Target.default) match
      case Right(r) => r
      case Left(e)  => fail(e)

  "the artifact the run tier compiles against" - {

    "is built wherever a toolchain can build one" in {
      assume(Toolchain.clangAvailable, "clang not available")

      // Matched rather than asserted with `shouldBe Symbol("right")`, which is what this was. That
      // matcher reaches `isRight` by runtime reflection, and Scala Native has none -- so on the
      // platform the compiler actually ships as, it compared an `Either` against a `Symbol` and
      // failed however well resolution had worked. Matching also gets the reason into the failure,
      // which a boolean assertion throws away.
      Stdlib.resolve(Stdlib.Choice.Default(), Target.default) match
        case Right(_) => succeed
        case Left(e)  => fail(e)
    }

    "arrives with the archive to link it from" in {
      assume(Toolchain.clangAvailable, "clang not available")

      prebuilt.archive shouldBe defined
    }

    "carries the standard module's own modules, decoded from the archive" in {
      assume(Toolchain.clangAvailable, "clang not available")

      prebuilt.std.modules should contain allElementsOf Library.modules
    }

    "says which symbols it has already compiled, so a program need not emit them again" in {
      assume(Toolchain.clangAvailable, "clang not available")

      // Printing is the surface every program reaches, so its absence here would mean the object
      // half had compiled nothing and the "precompiled" set was empty in a way nothing else notices.
      prebuilt.precompiled should not be empty
      prebuilt.precompiled.exists(_.contains("print")) shouldBe true
    }

    // The default path is keyed by a fingerprint of the library, so two resolutions of it in one
    // process are two spellings of one question — and the second must not pay for a second decode.
    // This is what four suites used to get from a fixture of their own.
    "is resolved once, however many times it is asked for" in {
      assume(Toolchain.clangAvailable, "clang not available")

      // **The memo holds one answer and clears on a miss**, so another suite resolving a different
      // key between these two calls evicts the first and the second decodes afresh. Holding the
      // memo's own monitor across both is what makes this assert memoization instead of testing the
      // scheduler — without it the assertion fails a few runs in a hundred, which is worse than
      // failing always because it reads as an unrelated regression in whatever branch is gating.
      val (first, second) = Stdlib.memo.synchronized {
        (
          Stdlib.resolve(Stdlib.Choice.Default(), Target.default),
          Stdlib.resolve(Stdlib.Choice.Default(), Target.default),
        )
      }

      first.toOption.get.std should be theSameInstanceAs second.toOption.get.std
    }
  }

  "compiling the library in, instead" - {

    // The other end of the same API, and the one that needs no toolchain at all.
    "needs nothing built and links nothing" in {
      val resolved = Stdlib.resolve(Stdlib.Choice.FromSource, Target.default) match
        case Right(r) => r
        case Left(e)  => fail(e)

      resolved.archive shouldBe empty
      resolved.precompiled shouldBe empty
      resolved.std.modules should contain allElementsOf Library.modules
    }
  }

  "an artifact somebody named is never built and never fallen back from" in {
    val missing = s"${createTempDirectory("sysl-std-named-")}/nowhere${LibraryArtifact.extension}"

    Stdlib.resolve(Stdlib.Choice.Artifact(missing), Target.default) match
      case Left(e)  => e should include("cannot read")
      case Right(r) => fail(s"expected a refusal, got ${r.archive}")
  }

  "a program compiled against it" - {

    "declares the library's functions where the carried copy defines them" in {
      assume(Toolchain.clangAvailable, "clang not available")

      val Stdlib.Resolved(std, precompiled, _) = prebuilt
      val sources                              = List(Source("<input>", hello))

      val linked  = Compiler.compiledWith(sources, Nil, Target.default, precompiled, Some(std)).toOption.get._1
      val emitted = Compiler.compile(sources).toOption.get

      // The whole of what the artifact buys, stated as a number: the program keeps its own
      // definition and every one of the library's becomes a declaration the linker resolves.
      defines(linked) shouldBe 1
      defines(emitted) should be > defines(linked)
      linked.length should be < emitted.length
    }

    "runs, and prints what the same program prints without it" in {
      assume(Toolchain.clangAvailable, "clang not available")

      val Stdlib.Resolved(std, precompiled, archive) = prebuilt
      val sources                                    = List(Source("<input>", hello))

      val out =
        Toolchain.compileAndRun(sources, Nil, Nil, Some(std), precompiled, archive.toList).toOption.get

      out shouldBe ((0, "hello\n"))
    }
  }
}
