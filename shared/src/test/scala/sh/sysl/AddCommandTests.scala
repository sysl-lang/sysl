package sh.sysl

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** `sysl add` — reading what was typed, and rewriting the manifest.
  *
  * **The rewrite is what these are mostly about, and the property under test is what is NOT
  * changed.** A manifest in this org is about three fifths comment, so the failure worth catching is
  * not a malformed entry — the command reads its own result back before writing, so that one cannot
  * reach disk — it is a rewrite that quietly took somebody's paragraph out, or re-laid-out a block
  * to fit a longer name.
  *
  * Nothing here touches a network or a file: `read`, `newest` and `addDependency` are text in and
  * text out, which is why the interesting half of the command can be asserted at all.
  */
class AddCommandTests extends AnyFreeSpec with Matchers {

  private val manifest =
    """// Why this project takes what it takes, which is the part a reprint would eat.
      |
      |package {
      |  name    = "demo"
      |  version = "0.1.0"
      |}
      |
      |dependencies {
      |  sdl3       { git = "github.com/sysl-lang/sdl3",       version = "0.3.1" }
      |  sdl3-ttf   { git = "github.com/sysl-lang/sdl3-ttf",   version = "0.3.0" }
      |}
      |
      |requires {
      |  os = true
      |}
      |""".stripMargin

  "what was typed" - {
    "a bare coordinate asks for the newest version" in {
      Add.read("github.com/sysl-lang/sdl3") shouldBe
        Right(Add.Asked("github.com/sysl-lang/sdl3", None, "sdl3"))
    }

    // Both spellings arrive from people reading the same repository page, so the `v` is taken and
    // dropped rather than refused.
    "a pinned one takes its version, with or without the tag's 'v'" in {
      Add.read("github.com/sysl-lang/sdl3@0.3.1").map(_.version) shouldBe Right(Some("0.3.1"))
      Add.read("github.com/sysl-lang/sdl3@v0.3.1").map(_.version) shouldBe Right(Some("0.3.1"))
    }

    // A coordinate is an identity rather than a URL, and somebody who pasted one from a browser
    // gets told what to write instead of being told it is wrong.
    "a URL is refused, and the refusal says what to write" in {
      Add.read("https://github.com/sysl-lang/sdl3").left.getOrElse("") should include(
        "github.com/sysl-lang/sdl3")
    }

    "so is a version that is not one" in {
      Add.read("github.com/sysl-lang/sdl3@newest").isLeft shouldBe true
    }

    // The label is what an import line will say, so a major-version suffix is not part of it: that
    // names the version, and the package is still `json`.
    "the entry is named for the repository, not for its major version" in {
      Add.labelOf("github.com/edadma/json") shouldBe "json"
      Add.labelOf("github.com/edadma/json/v2") shouldBe "json"
    }
  }

  "which version is newest" - {
    "the highest release tag wins, and ordering is numeric rather than lexical" in {
      Add.newest(List("v0.9.0", "v0.10.0", "v0.2.0")).map(_.toString) shouldBe Some("0.10.0")
    }

    // Repositories carry tags that are not versions, and none of them is something a manifest can
    // pin — so they are passed over rather than refused, which would make the command fail on a
    // repository that is perfectly usable.
    "a tag that is not a version is passed over" in {
      Add.newest(List("latest", "nightly", "v1.2.3", "v2.0.0-rc1")).map(_.toString) shouldBe
        Some("1.2.3")
    }

    "and a repository with no version tag has no answer" in {
      Add.newest(List("latest", "main")) shouldBe None
    }
  }

  "rewriting the manifest" - {
    "the entry goes in, lined up with its siblings" in {
      val out = ManifestEdit.addDependency(manifest, "png", "github.com/sysl-lang/png", "0.1.0")
        .getOrElse(fail("the rewrite was refused"))

      out should include("""  png        { git = "github.com/sysl-lang/png", version = "0.1.0" }""")
    }

    // The whole reason the rewrite is textual. Asserted as an equality on everything else rather
    // than by looking for the comment, so a rewrite that moved a blank line or re-indented the
    // `package` block fails here too.
    "and nothing else in the file moves" in {
      val out = ManifestEdit.addDependency(manifest, "png", "github.com/sysl-lang/png", "0.1.0")
        .getOrElse(fail("the rewrite was refused"))

      out.linesIterator.filterNot(_.contains("png")).mkString("\n") shouldBe
        manifest.linesIterator.mkString("\n")
    }

    "a name already there is refused rather than added twice" in {
      ManifestEdit.addDependency(manifest, "sdl3", "github.com/sysl-lang/sdl3", "0.4.0")
        .left.getOrElse("") should include("already a dependency")
    }

    // A project that takes nothing yet is the case somebody meets first, and it is the one where
    // there is no block to put anything in.
    "a project with no dependencies block gets one" in {
      val bare = "package {\n  name = \"demo\"\n}\n"
      val out  = ManifestEdit.addDependency(bare, "png", "github.com/sysl-lang/png", "0.1.0")
        .getOrElse(fail("the rewrite was refused"))

      out should startWith(bare)
      out should include("dependencies {")
      out should include("""  png { git = "github.com/sysl-lang/png", version = "0.1.0" }""")
    }

    // A `}` inside a comment or a string closes nothing, and a brace counter that did not know the
    // difference would stop in the middle of a sentence and write the entry there.
    "a brace inside a comment or a string does not end the block" in {
      val tricky =
        """dependencies {
          |  // a closing brace } in a sentence
          |  gc { git = "github.com/sysl-lang/gc", version = "0.2.2" }
          |}
          |""".stripMargin

      val out = ManifestEdit.addDependency(tricky, "png", "github.com/sysl-lang/png", "0.1.0")
        .getOrElse(fail("the rewrite was refused"))

      out.linesIterator.toList(3) should include("png")
      out.linesIterator.toList.last shouldBe "}"
    }

    // The result has to still be a manifest, which is what the command checks before writing — so
    // the rewrite is asserted against the reader rather than against the eye.
    "and what comes out still reads as a manifest" in {
      val out = ManifestEdit.addDependency(manifest, "png", "github.com/sysl-lang/png", "0.1.0")
        .getOrElse(fail("the rewrite was refused"))

      val read = PackageConfig.read(out).getOrElse(fail("the rewritten manifest did not read"))

      read.dependencies.map(_.label) should contain("png")
    }
  }
}
