package sh.sysl

import io.github.edadma.cross_platform.*

/** The library's own C, compiled, for the tests that link a program by hand.
 *
 * **A real build links the whole `.syslib`**, whose members are `sysl.code.o` and one object per C
 * file the library carries. A test that assembles only the sysl half is not linking a program — and
 * it stopped being able to pretend it was when a hosted entry point began calling into that C
 * (`Codegen.genStackGuard`), which turned "the sysl half alone links" from a true incidental into a
 * broken link in nine places at once.
 */
object StdNative {

  /** The objects the library's own C compiles to.
   *
   * **Each file is compiled where it lies**, which is what `NativeSources.build` does for a real
   * compilation and is the reason this helper cannot do anything else. Writing `src.text` out to a
   * temporary `.c` and compiling that was the same thing for as long as every file in the library
   * was self-contained; `library/sysl/unicode/utf8proc.c` includes two headers beside it, and a copy
   * in a temporary directory has no siblings — so the whole of `StdArtifactTests` failed at
   * `fatal error: 'utf8proc.h' file not found` while every real build was fine. Reading the file the
   * walk found is also the smaller claim: `Source.name` is where `Project.cSources` read it from.
   */
  def objects(): List[String] =
    Std.cSources(Target.default.os).map { src =>
      val obj = createTempFile("sysl-stdc-", ".o")

      Toolchain.compileC(src.name, obj, Target.default) match
        case Left(err) => sys.error(s"the library's own C did not compile: $err")
        case Right(_)  => ()
      obj
    }

  /** Removes what [[objects]] made. */
  def clean(objs: List[String]): Unit = objs.foreach(deleteFile)
}
