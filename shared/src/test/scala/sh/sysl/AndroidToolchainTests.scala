package sh.sysl

import io.github.edadma.cross_platform.*
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** Which compiler a build for Android uses, and what it says when there is none.
 *
 * **Android is the first target where having the back end is not having the toolchain**, and that is
 * the whole subject of this file. Every other row in the registry is served by a clang the machine
 * already has: a host build compiles against the host's own headers, a freestanding one includes
 * nothing at all, and RISC-V sends the search on to Homebrew's LLVM, which is still a complete
 * toolchain for the triple. Android needs Bionic's headers, which live in the NDK and nowhere else.
 *
 * So a search by back end picks Apple's clang — it has `aarch64` — and the build then dies on
 * `library/sysl/fs/__posix__/dirent.c:1: 'dirent.h' file not found`. **That reads as a broken
 * standard library rather than as the wrong compiler**, which is why the refusal here has to name
 * the variable instead: a sentence somebody can act on, against a diagnostic that sends them
 * looking at the wrong tree.
 *
 * Everything below asks `androidClangIn` rather than the environment, so what it decides is asserted
 * on a machine with no NDK on it. The one thing that stays out of it is `runs` — whether the binary
 * at the end of the path executes is the real machine's question and cannot be faked with a file.
 */
class AndroidToolchainTests extends AnyFreeSpec with Matchers {

  /** An NDK's directory layout, as much of it as the search walks. The host directory is deliberately
   * **not** named for the host running this test: see the case about it below.
   */
  private def ndk(root: String, host: String = "darwin-x86_64"): String = {
    val bin = s"$root/toolchains/llvm/prebuilt/$host/bin"

    createDirectories(bin)
    writeFile(s"$bin/clang", "")
    s"$bin/clang"
  }

  "with nothing in the environment" - {
    // The refusal is the feature. A compiler that went looking through home directories would find
    // *an* NDK on the machine it was written on and none anywhere else, and the way it would be
    // wrong is by silently choosing one.
    // **`ANDROID_HOME`, not `ANDROID_SDK_ROOT`.** Google's own variables page marks the second
    // deprecated and says the tools check the two agree where both are set, so recommending it would
    // be advice that ages into a second problem. Both are still read — a machine already set up
    // either way goes on working — and only the sentence is opinionated.
    "refuses, and names the variable that is not deprecated" in {
      val why = Toolchain.androidClangIn(None, None).left.getOrElse(fail("found a clang from nothing"))

      why should include("ANDROID_HOME")
      why should include("ANDROID_NDK_ROOT")
      why should not include "ANDROID_SDK_ROOT"
    }

    // The whole point of the message: it has to displace the `dirent.h` diagnostic in the reader's
    // head, which means saying that the *compiler* is what is missing rather than the header.
    "says why the machine's own clang is not a substitute" in {
      val why = Toolchain.androidClangIn(None, None).left.getOrElse(fail("found a clang from nothing"))

      why.toLowerCase should include("ndk")
      why should include("back end")
    }
  }

  "given an SDK root" - {
    "finds the NDK under it" in {
      val sdk = createTempDirectory("sysl-sdk-")
      val cc  = ndk(s"$sdk/ndk/27.0.12077973")

      Toolchain.androidClangIn(None, Some(sdk)) shouldBe Right(cc)
    }

    // **Sorted as numbers, and this pair is the reason.** As text `9.0.111` sorts after `30.0.222`,
    // so a plain `.sorted` picks an NDK three major versions out of date — and picks it silently,
    // producing a build against the wrong platform headers rather than an error.
    "takes the newest of several, compared as numbers rather than as text" in {
      val sdk = createTempDirectory("sysl-sdk-")

      ndk(s"$sdk/ndk/9.0.111")
      val newest = ndk(s"$sdk/ndk/30.0.222")

      Toolchain.androidClangIn(None, Some(sdk)) shouldBe Right(newest)
    }

    // **It names the directory and not the variable, because two spellings reach here and this cannot
    // tell which was set.** Telling somebody who set `ANDROID_HOME` to look at `ANDROID_SDK_ROOT`
    // points them at a variable that is empty and correct — which is the same misdirection as the
    // `dirent.h` diagnostic the whole search exists to replace, reintroduced one step further in.
    "refuses an SDK with no NDK downloaded, naming the directory rather than a variable" in {
      val sdk = createTempDirectory("sysl-sdk-")
      val why = Toolchain.androidClangIn(None, Some(sdk)).left.getOrElse(fail("found a clang"))

      why should include(sdk)
      why should include("ndk")
      why should not include "ANDROID_HOME"
      why should not include "ANDROID_SDK_ROOT"
    }

    "refuses an 'ndk' directory that is there and empty" in {
      val sdk = createTempDirectory("sysl-sdk-")

      createDirectories(s"$sdk/ndk")

      Toolchain.androidClangIn(None, Some(sdk)).isLeft shouldBe true
    }
  }

  "given an NDK directly" - {
    "uses it" in {
      val root = createTempDirectory("sysl-ndk-")
      val cc   = ndk(root)

      Toolchain.androidClangIn(Some(root), None) shouldBe Right(cc)
    }

    // A standalone NDK is a real installation shape, and somebody who named one is owed it rather
    // than whatever happens to sit under an SDK the environment also mentions.
    "and it wins over the SDK root, which is the coarser answer" in {
      val sdk   = createTempDirectory("sysl-sdk-")
      val alone = createTempDirectory("sysl-ndk-")

      ndk(s"$sdk/ndk/30.0.222")
      val named = ndk(alone)

      Toolchain.androidClangIn(Some(alone), Some(sdk)) shouldBe Right(named)
    }

    "refuses a directory that is not an NDK" in {
      val root = createTempDirectory("sysl-notndk-")
      val why  = Toolchain.androidClangIn(Some(root), None).left.getOrElse(fail("found a clang"))

      why should include("toolchains/llvm/prebuilt")
    }

    "refuses an NDK whose toolchain directory holds no clang" in {
      val root = createTempDirectory("sysl-ndk-")

      createDirectories(s"$root/toolchains/llvm/prebuilt/darwin-x86_64/bin")

      Toolchain.androidClangIn(Some(root), None).isLeft shouldBe true
    }

    // **The host directory is listed, never spelled from the host's own architecture.** The NDK on
    // an Apple Silicon Mac is still called `darwin-x86_64` — the clang inside is universal and has a
    // real arm64 slice, and the path has simply never been renamed. A search that composed
    // `darwin-` with the machine's architecture would find nothing on exactly the machine this has
    // to work on, so the name here is one that matches no host at all.
    "and reads the host directory's name off the disk rather than composing it" in {
      val root = createTempDirectory("sysl-ndk-")
      val cc   = ndk(root, host = "some-host-nobody-runs")

      Toolchain.androidClangIn(Some(root), None) shouldBe Right(cc)
    }
  }

  "the two searches" - {
    /** The targets whose toolchain is a *download* rather than a back end.
      *
      * **Android was the first and WASI is the second, and the argument is one argument.** Bionic's
      * headers are the NDK's and wasi-libc's are wasi-sdk's, so a clang picked for having the back
      * end succeeds at the search and fails at the first `#include` — which is the failure the split
      * exists to replace with a sentence naming a variable.
      */
    val downloaded = Set(Os.Android, Os.Wasi)

    // The split is the point of the change, so it is pinned from both sides: they part company on
    // those two and agree everywhere else. `findBackendClang` answers *can this machine lower for
    // that one*; `findClang` answers *can this machine build a program for it*, which is the
    // stronger question and the one a downloaded toolchain is needed for.
    "agree for every target whose toolchain is not a download" in {
      for t <- Target.all if t.supported && t.buildsWithClang && !downloaded(t.os) do
        withClue(t.name)(Toolchain.findClang(t) shouldBe Toolchain.findBackendClang(t))
    }

    // Which is a claim about those two alone, and would be vacuous if the registry lost either row.
    "and both of those are targets, so the case above is excluding something" in {
      for os <- downloaded do withClue(os.toString)(Target.all.map(_.os) should contain(os))
    }
  }

  /** What the reader is told when the standard module could not be rebuilt.
   *
   * **A missing NDK arrives here, not at the toolchain, which is what makes this part of the same
   * change.** The first thing any build does is resolve the standard module, and resolving it
   * rebuilds it where the cache has nothing — so the *first* thing a compiler-less Android build hits
   * is `Stdlib.found`, whose ordinary advice is about the standard module. Wrapped in that, the one
   * sentence naming `ANDROID_SDK_ROOT` sits inside a parenthesis after two suggestions that cannot
   * work, and the message as a whole says the library is broken. Which is the exact misreading the
   * Android row was already prone to.
   */
  "a rebuild that could not happen" - {
    "reports the toolchain's own sentence, with none of the standard module's advice" in {
      val why = Stdlib.rebuildFailure("the artifact did not build", Left("no clang for this machine"))

      why shouldBe "no clang for this machine"
      why should not include "--no-std-lib"
      why should not include "build-lib"
    }

    // The other branch is the one that was always right and must stay: where a compiler exists, the
    // rebuild failed for some other reason and the two commands are worth offering.
    "and keeps that advice where there is a compiler and something else went wrong" in {
      val why = Stdlib.rebuildFailure("the artifact did not build", Right("/usr/bin/clang"))

      why should include("--no-std-lib")
      why should include("build-lib library --std")
      why should include("the artifact did not build")
    }
  }
}
