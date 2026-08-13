package sh.sysl

import io.github.edadma.cross_platform.*
import org.scalatest.freespec.AnyFreeSpec

/** `@section("…")` — where the linker puts one object or one definition (`15 §13`).
 *
 * It is asserted from the **IR**, because there is nothing at run time to ask: a program cannot read
 * back which section its own storage landed in, and the section name is a string the back end passes
 * through to the object file. What the back end was told is the whole of the claim.
 *
 * The one thing that *is* run is a program carrying a placed object through the real toolchain, and
 * it names its section the way **Mach-O** spells one, because that is the machine the suite runs on.
 * A bare `.vectors` is ELF's spelling and clang refuses it here — which is exactly why nothing in the
 * compiler validates the string.
 */
class SectionAttrTests extends AnyFreeSpec with RunSupport with CodegenSupport {

  "@section places module storage" - {
    "a 'var' carries the section to the global it lays down" in {
      irOf("m/m.sysl" -> "module m\n\n@section(\".vectors\")\nvar table: [4]u64\n",
        "main.sysl" -> "print(m.table[0])\n") should include("section \".vectors\"")
    }

    "a 'val' does too, a section being about storage rather than about writing" in {
      irOf("m/m.sysl" -> "module m\n\n@section(\".rodata.boot\")\nval magic: u32 = 0xdeadbeefu32\n",
        "main.sysl" -> "print(m.magic)\n") should include("section \".rodata.boot\"")
    }

    "and so does the 'static' spelling of the same declaration" in {
      ir("@section(\".noinit\")\nstatic var reason: u32 = 0u32\nprint(reason)") should
        include("section \".noinit\"")
    }

    "one that asks for nothing carries none" in {
      ir("static var n: u32 = 0u32\nprint(n)") should not include "section \""
    }
  }

  /** The pair Zephyr's stack macro is written with — `__aligned(N)` and a section, in one
   * declaration — so this is the case rather than a corner.
   */
  "@section composes with @align on a binding" - {
    "both reach the global, the section first as LLVM writes it" in {
      ir("@align(4096)\n@section(\".noinit\")\nstatic var page: [8]u8\nprint(page[0])") should
        include("section \".noinit\", align 4096")
    }

    "the order they are written in does not matter" in {
      ir("@section(\".noinit\")\n@align(4096)\nstatic var page: [8]u8\nprint(page[0])") should
        include("section \".noinit\", align 4096")
    }
  }

  /** `.ramfunc` — code copied into RAM so it runs while flash is being erased — is a function, and it
   * is the second thing the C attribute is used for on a part with no operating system.
   */
  "@section places a definition" - {
    "the 'define' line carries it" in {
      ir("@section(\".ramfunc\")\nerase() -> int = 7\nprint(erase())") should
        include("section \".ramfunc\"")
    }

    "a placed function nothing calls is still emitted" in {
      val out = ir("@section(\".ramfunc\")\nerase() -> int = 7\nprint(1)")

      out should include("section \".ramfunc\"")
      out should include("@erase")
    }

    "one nothing calls and nothing places is dropped, which is what makes that a claim" in {
      ir("erase() -> int = 7\nprint(1)") should not include "@erase"
    }

    // The two say different things about one definition — one names the symbol a C caller resolves,
    // the other says where that definition lands — and a boot entry a linker script places is
    // routinely both.
    "it stands beside '@export', which is about the symbol rather than the address" in {
      val out = ir("@export(\"boot_entry\")\n@section(\".text.boot\")\nstart() -> int = 0\nprint(1)")

      out should include("@boot_entry")
      out should include("section \".text.boot\"")
    }

    // A function declared inside another is hoisted to a definition of its own, so the placement has
    // to travel with it — and this is where a driver writes the routine that must not be in flash
    // while flash is being written.
    "a nested function carries it too" in {
      run("outer() -> int\n    @section(\"__TEXT,__mine\")\n    inner() -> int = 7\n    inner()\n\n" +
        "print(outer())") shouldBe "7\n"
    }

    // A generic is one declaration and many definitions, so the section is a property each of them
    // carries — which is what a placed helper instantiated at two types means.
    "an instantiation of a generic carries it" in {
      val out = ir("@section(\".ramfunc\")\nid[T](v: T) -> T = v\nprint(id(1), id(2u8))")

      out.linesIterator.count(l => l.startsWith("define") && l.contains("section \".ramfunc\"")) shouldBe 2
    }
  }

  /** The list that keeps the feature from compiling, linking and placing nothing: a table gathered
   * by a linker script has no reader inside the program, and the globals are `private`.
   */
  "a placed symbol is written into llvm.used" - {
    "storage is named there" in {
      ir("@section(\".noinit\")\nstatic var reason: u32 = 0u32\nprint(reason)") should
        include("@llvm.used = appending global")
    }

    "and so is a definition" in {
      ir("@section(\".ramfunc\")\nerase() -> int = 7\nprint(erase())") should
        include("@llvm.used = appending global [1 x ptr]")
    }

    "two placed things share one list" in {
      ir("@section(\".ramfunc\")\nerase() -> int = 7\n@section(\".noinit\")\nstatic var n: u32 = 0u32\n" +
        "print(erase(), n)") should include("@llvm.used = appending global [2 x ptr]")
    }

    "a program that places nothing carries no list at all" in {
      ir("print(1)") should not include "@llvm.used"
    }
  }

  /** The whole path, through clang and the linker, on the machine the suite runs on. The section is
   * spelled Mach-O's way because that is what this machine's assembler accepts.
   */
  "a placed object goes through the real toolchain" - {
    "the program still runs, and reads what it put there" in {
      run("@section(\"__DATA,__mine\")\nstatic var ticks: u32 = 7u32\nticks += 1u32\nprint(ticks)") shouldBe
        "8\n"
    }

    "a placed function is called like any other" in {
      run("@section(\"__TEXT,__mine\")\ndouble(n: int) -> int = n * 2\nprint(double(21))") shouldBe "42\n"
    }
  }

  /** The claim the whole design rests on, asked of the optimizer rather than remembered.
   *
   * A table gathered by a linker script has no reader inside the program, the globals are emitted
   * `private`, and a program builds at `-O1` — so the pass that deletes an unreferenced private
   * global is between the attribute and the object file. `llvm.used` is what stands in the way, and
   * the **control** is what makes this a measurement: the same IR with that one line removed loses
   * the section entirely, which is the silent failure the line is there to prevent.
   */
  "a placed object nothing reads survives the optimizer" - {
    val program = "@section(\"__DATA,__mine\")\nstatic var table: [4]u64\nprint(1)"

    "the section reaches the assembler" in {
      assembly(ir(program)) should include("__mine")
    }

    "and without the 'used' list it would not, which is what that line buys" in {
      val stripped = ir(program).linesIterator.filterNot(_.startsWith("@llvm.used")).mkString("\n")

      stripped should include("__mine")           // the section is still named in the IR
      assembly(stripped) should not include "__mine" // and the object file has none of it
    }
  }

  /** The assembly clang makes of a module at the level a program is built at. It is read rather than
   * an object file because a section directive is text in every format, where reading a section out
   * of an object needs a different tool per platform.
   */
  private def assembly(llvm: String): String = {
    val here = Target.host.getOrElse(cancel("this machine is not a target the registry knows"))
    val cc   = Toolchain.findClang(here).getOrElse(cancel(s"no clang here builds for ${here.name}"))
    val src  = createTempFile("sysl-section-", ".ll")

    try {
      writeFile(src, llvm)

      val r = exec(Seq(cc, "-O1", "-S", "-o", "-", src))

      withClue(s"clang refused the IR:\n${r.stderr}")(r.exitCode shouldBe 0)
      r.stdout
    } finally try deleteFile(src) catch case _: Exception => ()
  }
}
