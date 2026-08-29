package sh.sysl

import io.github.edadma.cross_platform.*

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** `vendor/` — a project's own copy of what it depends on, which `sysl vendor` fills.
  *
  * **It is the machine's cache moved into the project rather than a second mechanism beside it**, so
  * there are only two claims to make: `Fetch.cacheRoot` prefers it when it is there, and
  * `Project.collect` walks past it. Everything downstream — resolution, the sums, the fetch — is the
  * code that was already there, which is the point of the design and the reason there is so little
  * to test.
  *
  * **The second claim is the one that bit.** A `vendor/` full of `.sysl` sitting at a project root is
  * compiled *as that project's own modules* by a walk that does not know better, and the first thing
  * it says is that `sh.sysl.json` sits in `vendor.github.com.sysl-lang.json.@v0.1.2.sh.sysl.json` —
  * which reads as a defect in the dependency. `examples/` is skipped for the same reason one line
  * above it.
  */
class VendorTests extends AnyFreeSpec with Matchers {

  /** A project with a dependency-shaped tree under `vendor/`, laid out the way the cache lays one
    * out — and deliberately a module whose name cannot be the project's own, since that is exactly
    * what a walk taking it would complain about.
    */
  private def vendored(): String = {
    val root = createTempDirectory("sysl-vproj-")
    val pkg  = s"$root/${Project.VendorDir}/github.com/sysl-lang/json/@v0.1.2/sh/sysl/json"

    writeFile(s"$root/${PackageConfig.FileName}",
      "package {\n  name = \"demo\"\n  version = \"0.1.0\"\n}\n")
    writeFile(s"$root/main.sysl", "print(21 * 2)\n")
    createDirectories(pkg)
    writeFile(s"$pkg/json.sysl", "module sh.sysl.json\n\nfour() -> int = 4\n")

    root
  }

  "a project's dependencies are not its own source" in {
    val root  = vendored()
    val files = Project.collect(root, Some(Target.default.os)).map(_.name)

    files.map(Project.basename) should contain("main.sysl")
    files.map(Project.basename) should not contain "json.sysl"
  }

  // The whole of what makes a vendored project build with the network off.
  "the cache is the project's own where it has one" in {
    val root = vendored()

    Fetch.cacheRoot(root) shouldBe Right(s"$root/${Project.VendorDir}")
  }

  // And a project without one is exactly where it was: the machine's cache, shared between every
  // project on it, which is what keeps N projects from holding N copies of one library.
  "and the machine's where it has not" in {
    val root = createTempDirectory("sysl-plain-")

    Fetch.cacheRoot(root).getOrElse("") should not include Project.VendorDir
  }
}
