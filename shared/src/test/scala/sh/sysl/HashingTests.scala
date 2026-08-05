package sh.sysl

import io.github.edadma.cross_platform.*

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** The content hash `sysl.sum` is written in (`packages.md § 6`).
 *
 * Two things are pinned here and they are pinned separately, because they fail separately. The
 * **wiring** — that what comes back really is SHA-256 — is held to the published vectors, so a
 * platform whose utility printed something else, or printed it in another column, is caught by a
 * number nobody here chose. The **definition** — what a tree hashes to — is held to a constant
 * computed by hand from the two commands the doc comment describes, so that the day the shell-out is
 * replaced by an implementation in Scala, a `sysl.sum` written today still verifies.
 */
class HashingTests extends AnyFreeSpec with Matchers {

  private def fileOf(text: String): String = {
    val path = createTempFile("sysl-hash-", "")
    writeFile(path, text)
    path
  }

  private def digest(text: String): String =
    Hashing.digestsOf(List(fileOf(text))) match
      case Right(List(d)) => d
      case other          => fail(s"expected one digest, got: $other")

  /** A tree on disk from `relative path -> content`, which is what a fetched package is. */
  private def treeOf(files: (String, String)*): String = {
    val root = createTempDirectory("sysl-hash-tree-")

    for (path, text) <- files do
      Project.parentOf(s"$root/$path").foreach(createDirectories)
      writeFile(s"$root/$path", text)

    root
  }

  private def hashOf(root: String): String =
    Hashing.treeHash(root) match
      case Right(h) => h
      case Left(e)  => fail(e)

  "the digest really is SHA-256" - {

    // FIPS 180-4's own two, which is the point: neither number was chosen here, so a utility that
    // answered with anything else — a different algorithm, a different column of its output — cannot
    // agree with them by accident.
    "the empty input" in {
      digest("") shouldBe "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    }

    "'abc'" in {
      digest("abc") shouldBe "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    }
  }

  "many files at once" - {

    // Whether a batch boundary can disturb the answer, which is the one thing batching could get
    // wrong: the digests come back by position, so a call that lost or reordered a line would pair
    // every digest after it with the wrong file. Alternating two known contents across the boundary
    // makes that visible — a slip of one turns every later pair over.
    "are answered in the order they were asked, across the batch boundary" in {
      val texts   = (0 until 600).map(i => if i % 2 == 0 then "abc" else "").toList
      val paths   = texts.map(fileOf)
      val empty   = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
      val abc     = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
      val digests = Hashing.digestsOf(paths) match
        case Right(d) => d
        case Left(e)  => fail(e)

      digests shouldBe texts.map(t => if t == "abc" then abc else empty)
    }

    "no files at all is no digests, and no process" in {
      Hashing.digestsOf(Nil) shouldBe Right(Nil)
    }
  }

  "the tree hash is the definition, not whatever the utility printed" - {

    // Computed by hand, and reproducible by hand:
    //
    //     shasum -a 256 a.sysl sub/b.sysl        # with the paths written as they are below
    //     shasum -a 256 <that output>
    //
    // If this constant ever has to change, something about the *definition* changed, and every
    // `sysl.sum` in existence stopped verifying. That is what it is here to make loud.
    val expected = "sha256:318922b4f287252c897a51afc023ee780d60abf62d60977e4cac7b060b5eea70"

    "a known tree hashes to a known number" in {
      hashOf(treeOf("a.sysl" -> "one\n", "sub/b.sysl" -> "two\n")) shouldBe expected
    }

    "the same content unpacked somewhere else hashes the same" in {
      val here  = treeOf("a.sysl" -> "one\n", "sub/b.sysl" -> "two\n")
      val there = treeOf("a.sysl" -> "one\n", "sub/b.sysl" -> "two\n")

      hashOf(here) shouldBe hashOf(there)
      here should not be there
    }

    // The listing is sorted by relative path rather than by the order a directory happened to be
    // read in, so the walk's own order cannot reach the answer.
    "the order the files were written in does not reach it" in {
      hashOf(treeOf("sub/b.sysl" -> "two\n", "a.sysl" -> "one\n")) shouldBe expected
    }
  }

  "what changes the hash, and what does not" - {

    "a changed byte" in {
      hashOf(treeOf("a.sysl" -> "one\n")) should not be hashOf(treeOf("a.sysl" -> "one"))
    }

    // The path is in the listing beside the digest, so a file moved is a different tree even though
    // every byte of content is the same. A hash that missed this would call two different packages
    // one package.
    "a file moved to another path, with its content untouched" in {
      hashOf(treeOf("a.sysl" -> "one\n")) should not be hashOf(treeOf("sub/a.sysl" -> "one\n"))
    }

    // Two clones of one commit differ here — packing, timestamps, remote names — so a hash that
    // included it would depend on how the package was fetched rather than on what was fetched.
    "the repository's own bookkeeping" in {
      val bare = treeOf("a.sysl" -> "one\n")
      val cloned = treeOf("a.sysl" -> "one\n", ".git/HEAD" -> "ref: refs/heads/main\n",
        ".git/objects/ab/cdef" -> "whatever")

      hashOf(cloned) shouldBe hashOf(bare)
    }

    // The hash covers what the package *is*, not the part of it a compiler happens to parse. A
    // package's C is compiled and linked into whoever depends on it (`15 §7`), so a listing that
    // took only `.sysl` would let a shim be swapped under a `sysl.sum` that still verified — and
    // that is the one file in a package that can reach a system call without a line of sysl saying
    // so. `packages.md § 6` is only worth what it covers.
    "a C file beside the sysl, which is as much of the package as the sysl is" in {
      val one = treeOf("a.sysl" -> "one\n", "shim.c" -> "int f(void) { return 1; }\n")
      val two = treeOf("a.sysl" -> "one\n", "shim.c" -> "int f(void) { return 2; }\n")

      hashOf(one) should not be hashOf(two)
    }

    // Nor is it a list of extensions that C was added to. Anything under the root is in, which is
    // what makes the answer a property of the tree rather than of what a reader thought to name.
    "and a file of no kind the compiler knows at all" in {
      val bare = treeOf("a.sysl" -> "one\n")

      hashOf(treeOf("a.sysl" -> "one\n", "NOTICE" -> "x\n")) should not be hashOf(bare)
    }

    "a `.git` further down, which is a submodule's and is not this package's either" in {
      val bare   = treeOf("a.sysl" -> "one\n", "sub/b.sysl" -> "two\n")
      val nested = treeOf("a.sysl" -> "one\n", "sub/b.sysl" -> "two\n", "sub/.git/HEAD" -> "x\n")

      hashOf(nested) shouldBe hashOf(bare)
    }
  }

  "a name that would break the listing is refused rather than escaped" in {
    val root = treeOf("a.sysl" -> "one\n")

    writeFile(s"$root/two\nlines.sysl", "x")

    Hashing.treeHash(root) match
      case Left(e)  => e should include("may not hold a newline")
      case Right(h) => fail(s"expected a refusal, got $h")
  }

  "a path is spelled relative to the package root" in {
    Hashing.relative("/tmp/pkg", "/tmp/pkg/a.sysl") shouldBe "a.sysl"
    Hashing.relative("/tmp/pkg/", "/tmp/pkg/sub/b.sysl") shouldBe "sub/b.sysl"
    Hashing.relative("/tmp/pkg", "/tmp/pkg") shouldBe "/tmp/pkg"
  }
}
