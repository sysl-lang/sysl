package sh.sysl

import java.io.IOException

import io.github.edadma.cross_platform.*

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** Making the directory a build is about to write into, when something else is making it too.
 *
 * **Every caller wants the directory to exist, and none of them wants to have been the one that made
 * it** — which is not what the filesystem call says. `createDirectory` refuses a path that is already
 * there, and `createDirectories` decides what is missing before it starts, so two compilations
 * racing for a cold artifact cache both walk up to make the same directory and the loser is refused.
 *
 * The refusal names the directory rather than the race, and on Native it is a
 * `DirectoryNotEmptyException` — a complaint that the directory is not empty, which it is not
 * precisely because the winner has already written the artifact into it. Nothing about that reads as
 * a race, and it lands as a failed build on whichever process was second.
 *
 * These assert the tolerance at the leaf, which is where the window is. A failure that leaves no
 * directory behind is still a failure and is asserted too — swallowing that one would turn an
 * unwritable path into a build that reaches the linker with nowhere to put its output.
 */
class DirectoryCreationTests extends AnyFreeSpec with Matchers {

  private def scratch(check: String => Unit): Unit = {
    val dir = createTempDirectory("sysl-mkdir-")

    def remove(path: String): Unit = {
      if isDirectory(path) then listFiles(path).foreach(remove)

      Project.discard(path)
    }

    try check(dir)
    finally remove(dir)
  }

  "one directory" - {
    "is made where there is none" in scratch { dir =>
      Project.makeDirectory(s"$dir/fresh")

      isDirectory(s"$dir/fresh") shouldBe true
    }

    "is tolerated where it is already there and empty" in scratch { dir =>
      Project.makeDirectory(s"$dir/twice")
      Project.makeDirectory(s"$dir/twice")

      isDirectory(s"$dir/twice") shouldBe true
    }

    /** The one the race actually produces: the winner has not only made the directory, it has
     * written into it. `createDirectory` answers that with a different exception from the one it
     * answers an empty directory with, so a tolerance written against the empty case alone would
     * still have failed here.
     */
    "is tolerated where it is already there and something has been written into it" in scratch {
      dir =>
        Project.makeDirectory(s"$dir/occupied")
        writeFile(s"$dir/occupied/artifact", "x")

        Project.makeDirectory(s"$dir/occupied")

        isDirectory(s"$dir/occupied") shouldBe true
    }

    "is still refused where the name is taken by a file" in scratch { dir =>
      writeFile(s"$dir/taken", "x")

      an[IOException] should be thrownBy Project.makeDirectory(s"$dir/taken")
    }

    /** The refusal the helper exists to absorb, pinned here so that it is not mistaken for
     * belt-and-braces: the plain call really does refuse a directory that is already there, which is
     * what a compilation that lost the race is holding.
     */
    "where the plain call refuses exactly that" in scratch { dir =>
      Project.makeDirectory(s"$dir/present")
      writeFile(s"$dir/present/artifact", "x")

      an[IOException] should be thrownBy createDirectory(s"$dir/present")
    }
  }

  "a whole path" - {
    "is made a level at a time" in scratch { dir =>
      Project.makeDirectories(s"$dir/a/b/c")

      isDirectory(s"$dir/a/b/c") shouldBe true
    }

    /** The shape the cache is in on the second target of a run: the root is there and holds the
     * first target's artifact, and the directory being asked for is not.
     */
    "is made under an ancestor that is already there and occupied" in scratch { dir =>
      Project.makeDirectories(s"$dir/cache/first")
      writeFile(s"$dir/cache/first/std.syslib", "x")

      Project.makeDirectories(s"$dir/cache/second")

      isDirectory(s"$dir/cache/second") shouldBe true
    }

    "is tolerated where the whole of it is already there" in scratch { dir =>
      Project.makeDirectories(s"$dir/a/b/c")
      Project.makeDirectories(s"$dir/a/b/c")

      isDirectory(s"$dir/a/b/c") shouldBe true
    }
  }
}
