package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** What a bitfield struct may not hold, and what may not be asked of one (`15 §1`).
 *
 * Every refusal here follows from the one sentence that a `@packed` struct with a field narrower
 * than a byte **is** an integer, rather than from a policy laid over the feature — which is why each
 * is decided with the feature rather than left to be discovered by whoever writes the first one.
 */
class BitfieldErrorTests extends AnyFreeSpec with CodegenSupport {

  private val ctrl =
    """|@packed
       |struct Ctrl
       |    enable: u1
       |    mode: u3
       |    prescale: u4
       |""".stripMargin

  "every field of one has to be an integer" - {
    "a pointer beside a narrow field is refused" in {
      val e = err("@packed\nstruct Mixed\n    p: *u8\n    a: u3\nval m = Mixed(null, 1)\nprint(m.a)")

      e should include("'Mixed.p' is *byte")
      e should include("narrower than a byte")
      e should include("every field of it has to be one too")
    }
    "and so is a float" in {
      err("@packed\nstruct Mixed\n    x: f32\n    a: u3\nval m = Mixed(1.0, 1)\nprint(m.a)") should
        include("every field of it has to be one too")
    }
    // `bool` is an `i1` whose storage is a byte, so admitting it would make one type mean one width
    // inside a container and another outside it. A flag is a `u1`.
    "a 'bool' is not an integer here" in {
      err("@packed\nstruct Mixed\n    f: bool\n    a: u3\nval m = Mixed(true, 1)\nprint(m.a)") should
        include("every field of it has to be one too")
    }
    "the suggestion is the nesting that does work" in {
      err("@packed\nstruct Mixed\n    p: *u8\n    a: u3\nval m = Mixed(null, 1)\nprint(m.a)") should
        include("Put the narrow fields in a '@packed' struct of their own")
    }
  }

  // A bitfield **may** be `volatile`, and `BitfieldIrTests` is where that is asserted. What stays
  // refused is the qualifier on the struct, which is the answer this feature settled rather than a
  // corner left over from it: `volatile` qualifies scalar storage, and a bitfield field is scalar
  // storage, so nothing had to move for the register case to work. Qualifying the aggregate instead
  // would take away what per-field qualification buys — a shadow field in the middle of a register
  // block that stays ordinary.
  "the struct itself still may not carry the qualifier, by either route" - {
    "not as a field, and the message names the per-field spelling" in {
      val e = err("@packed\nstruct Ctrl\n    a: u3\n    b: u5\n@packed\nstruct Regs\n" +
        "    ctrl: volatile Ctrl\nvar p: *Regs = ptr_cast(4096usize)\nprint(p.ctrl.a)")

      e should include("'volatile Ctrl' is not a type")
      e should include("qualified one field at a time")
    }
    "and not as a '*T' pointee" in {
      err("@packed\nstruct Ctrl\n    a: u3\n    b: u5\n" +
        "var p: *volatile Ctrl = ptr_cast(4096usize)\nprint(p.a)") should
        include("'volatile Ctrl' is not a type")
    }
  }

  // A **simple** enum is one integer and may be a volatile bitfield — `BitfieldIrTests` has it. A
  // data enum is a tag beside a payload, so no single access reaches one whatever the source says.
  "a data enum is not a register field" in {
    val e = err("enum P\n    None\n    Some(x: int)\n@packed\nstruct Bad\n" +
      "    a: volatile u3\n    b: volatile P\nprint(0)")

    e should include("'volatile P' is not a type")
    e should include("carries a payload beside its tag")
  }

  "a bitfield has no byte offset" - {
    "'offsetof' says so rather than rounding down to the byte it begins in" in {
      val e = err(ctrl + "print(offsetof(Ctrl, mode))")

      e should include("is a bitfield")
      e should include("starts at bit 1")
      e should include("3 bits wide")
      e should include("has no byte offset")
    }
    "even for the field that does begin on a byte" in {
      err(ctrl + "print(offsetof(Ctrl, enable))") should include("has no byte offset")
    }
    "a misspelled field is still the diagnostic it was" in {
      err(ctrl + "print(offsetof(Ctrl, mdoe))") should include("has no field 'mdoe'")
    }
  }

  "a bitfield has no address, which is the rule packed already had" in {
    err(ctrl + "var c = Ctrl(1, 5, 9)\nval p = &c.mode\nprint(p[0])") should include("'@packed'")
  }
}
