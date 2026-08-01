package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** The run tier compiled against a **prebuilt standard module** (`PrebuiltCore`) rather than the copy
 * the compiler carries.
 *
 * The point of these is not that the artifact works — `LibraryArtifactTests` is where that is held —
 * but that **the suite is actually taking that path**. `PrebuiltCore.forHost` answers `None` where
 * there is no toolchain, and a `None` is indistinguishable at every call site from the compilation
 * the suite has always done: everything would stay green while the artifact half went unexercised,
 * which is the failure this file exists to make loud.
 */
class PrebuiltCoreTests extends AnyFreeSpec with Matchers {

  private val hello =
    """print("hello")
      |""".stripMargin

  private def defines(ir: String): Int = ir.linesIterator.count(_.startsWith("define"))

  "the artifact the run tier compiles against" - {

    "is built wherever a toolchain can build one" in {
      assume(Toolchain.clangAvailable, "clang not available")

      PrebuiltCore.forHost shouldBe defined
    }

    "carries the standard module's own modules, decoded from the archive" in {
      assume(Toolchain.clangAvailable, "clang not available")

      val (core, _, _) = PrebuiltCore.forHost.get

      core.modules should contain allElementsOf Library.modules
    }

    "says which symbols it has already compiled, so a program need not emit them again" in {
      assume(Toolchain.clangAvailable, "clang not available")

      val (_, precompiled, _) = PrebuiltCore.forHost.get

      // Printing is the surface every program reaches, so its absence here would mean the object
      // half had compiled nothing and the "precompiled" set was empty in a way nothing else notices.
      precompiled should not be empty
      precompiled.exists(_.contains("print")) shouldBe true
    }
  }

  "a program compiled against it" - {

    "declares the library's functions where the carried copy defines them" in {
      assume(Toolchain.clangAvailable, "clang not available")

      val (core, precompiled, _) = PrebuiltCore.forHost.get
      val sources                = List(Source("<input>", hello))

      val linked  = Compiler.compiledWith(sources, Nil, Target.default, precompiled, Some(core)).toOption.get._1
      val emitted = Compiler.compile(sources).toOption.get

      // The whole of what the artifact buys, stated as a number: the program keeps its own
      // definition and every one of the library's becomes a declaration the linker resolves.
      defines(linked) shouldBe 1
      defines(emitted) should be > defines(linked)
      linked.length should be < emitted.length
    }

    "runs, and prints what the same program prints without it" in {
      assume(Toolchain.clangAvailable, "clang not available")

      val (core, precompiled, archive) = PrebuiltCore.forHost.get
      val sources                      = List(Source("<input>", hello))

      val out =
        Toolchain.compileAndRun(sources, Nil, Nil, Some(core), precompiled, List(archive)).toOption.get

      out shouldBe ((0, "hello\n"))
    }
  }
}
