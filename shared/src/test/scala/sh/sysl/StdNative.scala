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

  /** The objects the library's own C compiles to. */
  def objects(): List[String] =
    Std.cSources(Target.default.os).map { src =>
      val c   = createTempFile("sysl-stdc-", ".c")
      val obj = createTempFile("sysl-stdc-", ".o")

      writeFile(c, src.text)
      Toolchain.compileC(c, obj, Target.default) match
        case Left(err) => sys.error(s"the library's own C did not compile: $err")
        case Right(_)  => ()
      deleteFile(c)
      obj
    }

  /** Removes what [[objects]] made. */
  def clean(objs: List[String]): Unit = objs.foreach(deleteFile)
}
