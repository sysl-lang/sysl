package sh.sysl

import io.github.edadma.cross_platform.*

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** `wasm32-wasi` — WebAssembly with a libc under it (`getting-started/cli.md § targets`).
  *
  * sysl could build for `wasm32-unknown-unknown` and for nothing else in the family, which is the
  * bare target: no libc, no convention for what the host supplies, so a program with
  * `requires { heap = true }` could not link until somebody wrote `malloc` by hand. WASI is a
  * standardised table of imports a module asks its host for, wasi-libc is a real libc built on it,
  * and this row is what reaches them.
  *
  * **What is asserted here is everything answerable without wasi-sdk installed.** Whether a program
  * actually runs is asserted by building one and putting it through `wasmtime`, which needs the SDK
  * and so cannot be a gate on a machine that has not got it — the row's decisions can be, and the
  * two that would silently produce an unrunnable module are the reason this file exists.
  */
class WasiTargetTests extends AnyFreeSpec with Matchers {

  private val wasi = Target.wasm32Wasi
  private val bare = Target.wasm32Freestanding

  "the row" - {
    // The row is named for the family and the triple is the modern spelling: bare `wasm32-wasi` is
    // clang's deprecated alias, and what goes on a command line has to be the one that is not.
    "is named for what somebody types and hands clang the modern triple" in {
      wasi.name shouldBe "wasm32-wasi"
      wasi.triple shouldBe "wasm32-wasip1"
    }

    "is the same machine as the bare one" in {
      wasi.cpu shouldBe bare.cpu
      wasi.pointerBytes shouldBe bare.pointerBytes
    }

    "and is reachable by name" in {
      Target.named("wasm32-wasi") shouldBe Right(wasi)
    }
  }

  "what a WASI machine has" - {
    // Files, a clock, randomness, arguments and exit, so it is not freestanding; no fork, no sockets
    // and no threads, so it is not POSIX. That rung was already occupied, and by Windows.
    "is an operating system without POSIX, which is the rung Windows stands on" in {
      Os.Wasi.inherentCapabilities should contain(Capability.Os)
      Os.Wasi.inherentCapabilities should not contain Capability.Posix
      Os.Wasi.inherentCapabilities shouldBe Os.Windows.inherentCapabilities
    }

    // The symbol is the system rather than the processor: `#if wasm32` is true of both wasm rows and
    // this is what tells them apart, which is what a file choosing an implementation needs.
    "and a source file can gate on it by name" in {
      Conditional.defined(wasi) should contain("wasi")
      Conditional.defined(wasi) should contain("hosted")
      Conditional.defined(wasi) should not contain "posix"
      Conditional.defined(bare) should not contain "wasi"
    }
  }

  "the link line" - {
    /* **Both halves of the bare row's linking would break this one, and neither would fail loudly.**
     * `-nostdlib` drops the very libc that makes the row worth having, and `--entry=main` replaces
     * the `_start` wasi-libc supplies and a runtime looks for. */
    "keeps the libc that the bare row has to do without" in {
      val bareLine = Toolchain.linkCommand("a.ll", Nil, "a.wasm", bare)
      val wasiLine = Toolchain.linkCommand("a.ll", Nil, "a.wasm", wasi)

      bareLine should contain("-nostdlib")
      bareLine should contain("-Wl,--entry=main")

      wasiLine should not contain "-nostdlib"
      wasiLine should not contain "-Wl,--entry=main"
    }
  }

  "finding the toolchain" - {
    // A clang picked merely for having the wasm32 back end carries no sysroot, so it succeeds at the
    // search and fails at the first `#include`. A refusal naming the variable is a sentence somebody
    // can act on; a silently chosen toolchain is not.
    "refuses with the variable to set rather than guessing" in {
      val said = Toolchain.wasiClangIn(None).left.getOrElse("")

      said should include("WASI_SDK_PATH")
      said should include("sysroot")
    }

    // The path and not the variable, because what somebody set is what they have to look at.
    "and names the directory when it holds no clang" in {
      val empty = createTempDirectory("sysl-nowasi-")

      Toolchain.wasiClangIn(Some(empty)).left.getOrElse("") should include(empty)
    }

    "taking the clang under a directory that has one" in {
      val sdk = createTempDirectory("sysl-wasi-")

      createDirectories(s"$sdk/bin")
      writeFile(s"$sdk/bin/clang", "")

      Toolchain.wasiClangIn(Some(sdk)) shouldBe Right(s"$sdk/bin/clang")
    }
  }
}
