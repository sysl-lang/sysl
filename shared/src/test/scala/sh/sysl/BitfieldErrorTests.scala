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

  "a bitfield may not be volatile" - {
    "because reading one is a read-modify-write of the whole struct" in {
      val e = err("@packed\nstruct Reg\n    a: volatile u3\n    b: u5\nval r = Reg(1, 2)\nprint(r.a)")

      e should include("'Reg.a' is 'volatile' and is a bitfield")
      e should include("single access a device is entitled to")
    }
    "and it says where the qualifier belongs instead" in {
      err("@packed\nstruct Reg\n    a: volatile u3\n    b: u5\nval r = Reg(1, 2)\nprint(r.a)") should
        include("'reg: volatile Reg'")
    }
    "a struct of whole bytes still takes one, which is what a register block is" in {
      ir("@packed\nstruct Block\n    a: volatile u32\n    b: volatile u32\n" +
        "var p: *Block = ptr_cast(4096usize)\np.a = 1u32\n") should include("store volatile")
    }

    // **And so a bitfield struct cannot be a volatile register at all today**, which is a corner the
    // feature exposed rather than one it created: `volatile` qualifies *scalar* storage, so the
    // struct cannot carry the qualifier either — not on a field, and not as a `*T` pointee. Both
    // halves are pinned here so that whichever way the question is settled, the test says so.
    "and the struct cannot carry the qualifier instead, by either route" in {
      val field = err("@packed\nstruct Ctrl\n    a: u3\n    b: u5\n@packed\nstruct Regs\n" +
        "    ctrl: volatile Ctrl\nvar p: *Regs = ptr_cast(4096usize)\nprint(p.ctrl.a)")

      field should include("'volatile Ctrl' is not a type")
      field should include("qualified one field at a time")

      err("@packed\nstruct Ctrl\n    a: u3\n    b: u5\n" +
        "var p: *volatile Ctrl = ptr_cast(4096usize)\nprint(p.a)") should
        include("'volatile Ctrl' is not a type")
    }
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
