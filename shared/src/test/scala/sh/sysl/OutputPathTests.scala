package sh.sysl

import io.github.edadma.cross_platform.*

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** Where a build writes when the caller named no output.
 *
 * **This exists because the answer used to depend on where the caller was standing.** The default
 * was the path's last segment with an extension dropped, which for a directory project is the
 * directory — so the linker was handed a path it could not open. `sysl build .` failed for every
 * project there has ever been, and `sysl build test` failed for every project sitting in the working
 * directory, both with `ld: open() failed, errno=21 (Is a directory)`, which names neither sysl nor
 * the thing that was wrong.
 *
 * A file project is untouched and is asserted here beside the rest: its extension is what always
 * saved it, and a fix that quietly moved `foo.sysl`'s executable would be a worse bug than the one
 * being fixed.
 */
class OutputPathTests extends AnyFreeSpec with Matchers {

  private def cli(cfg: Config): Int =
    Console.withOut(Discarded)(Console.withErr(Discarded)(sh.sysl.execute(cfg.copy(noStdLib = true))))

  /** A project directory holding one program, emptied afterwards — a build that reached the linker
   * leaves its executable behind, and the directory will not go while it is there.
   */
  private def project(check: String => Unit): Unit = {
    val dir = createTempDirectory("sysl-output-")

    try
      writeFile(s"$dir/main.sysl", "main()\n    print(1)\n")
      check(dir)
    finally
      for f <- listFiles(dir) do deleteFile(f)
      deleteFile(dir)
  }

  /** A library tree, which is a different shape from a program's: a library is reached by naming its
   * module, so its files sit in a directory under the root rather than in the root itself.
   */
  private def library(check: String => Unit): Unit = {
    val dir    = createTempDirectory("sysl-output-lib-")
    val module = s"$dir/demo"

    try
      createDirectory(module)
      writeFile(s"$module/lib.sysl", "module demo\n\ndouble(n: int) -> int = n * 2\n")
      check(dir)
    finally
      for f <- listFiles(module) do deleteFile(f)
      deleteFile(module)
      for f <- listFiles(dir) do deleteFile(f)
      deleteFile(dir)
  }

  "what a project is called" - {

    "is the last segment of where it is, for a path that ends in one" in {
      Project.nameOf("/tmp/scratch/test") shouldBe "test"
      Project.nameOf("/tmp/scratch/test/") shouldBe "test"
    }

    // The three spellings that carry the name somewhere other than the end of the string. `.` is the
    // one that matters most, because it is how a person standing in their own project names it.
    "and is worked out where the path does not end in one" in {
      Project.nameOf(".") shouldBe Project.basename(getCurrentDirectory)
      Project.nameOf("./") shouldBe Project.basename(getCurrentDirectory)
      Project.nameOf("/tmp/scratch/test/..") shouldBe "scratch"
      Project.nameOf("/tmp/scratch/../scratch/test") shouldBe "test"
    }

    "and a relative path is resolved against the working directory rather than guessed at" in {
      Project.nameOf("sub") shouldBe "sub"
      Project.nameOf("a/b/c") shouldBe "c"
    }
  }

  "where a build writes" - {

    "is inside a directory project, named after it — from wherever the build was started" in {
      project { dir =>
        val name = Project.basename(dir)

        cli(Config(command = "build", file = dir)) shouldBe 0

        isFile(s"$dir/$name") shouldBe true
      }
    }

    // The case that could not be built at all: `.` named the directory, and the directory is not
    // something a linker can open.
    "including when the project is named as the working directory" in {
      project { dir =>
        val name = Project.basename(dir)

        // `file` is what a person types; the driver reads the project through it either way, so
        // naming the directory absolutely and naming it `.` have to reach the same file.
        cli(Config(command = "build", file = s"$dir/.")) shouldBe 0

        isFile(s"$dir/$name") shouldBe true
      }
    }

    "and beside the caller for a file project, which is what it always was" in {
      project { dir =>
        cli(Config(command = "build", file = s"$dir/main.sysl")) shouldBe 0

        isFile(s"$dir/main") shouldBe false
        isFile("main") shouldBe true

        deleteFile("main")
      }
    }
  }

  // `build-lib` had the same defect wearing a different symptom: the extension is appended to the
  // name, so `sysl build-lib .` wrote `..syslib` — a hidden file, in the caller's directory, named
  // after nothing.
  "where a library artifact is written" - {

    "is inside the root it was built from, named after it" in {
      library { dir =>
        val name = Project.basename(dir)

        cli(Config(command = "build-lib", file = dir)) shouldBe 0

        isFile(s"$dir/$name${LibraryArtifact.extension}") shouldBe true
      }
    }

    "including when the root is named as the working directory" in {
      library { dir =>
        val name = Project.basename(dir)

        cli(Config(command = "build-lib", file = s"$dir/.")) shouldBe 0

        isFile(s"$dir/$name${LibraryArtifact.extension}") shouldBe true
      }
    }
  }
}
