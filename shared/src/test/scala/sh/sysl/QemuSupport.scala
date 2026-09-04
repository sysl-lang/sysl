package sh.sysl

import io.github.edadma.cross_platform.*
import org.scalatest.matchers.should.Matchers

/** Running a freestanding program **on the machine it was built for**, under QEMU.
 *
 * Every tier below this one reads what the compiler wrote. `CAbiTests` checks a table against
 * itself, `AbiAgainstClangTests` checks it against clang, and `CrossTargetBuildTests` checks that
 * the module verifies and assembles. None of them can see valid IR that computes the wrong thing —
 * a wrong coercion, a length loaded at the wrong width, an index widened to a size the machine does
 * not have — because all three are text, and text is what a miscompile also is.
 *
 * So this tier executes. It links the module against a startup written for the board, boots it in
 * an emulator, and reads **two channels**, which are complementary and both wanted:
 *
 *   - **the exit status**, which says *whether* anything failed, through a device the board has for
 *     the purpose;
 *   - **standard output**, which says *what* did, through the board's UART.
 *
 * A count alone cannot identify a failure that by definition does not reproduce on the host — that
 * being the only kind this tier exists to find.
 *
 * ### What happens when the tools are not here
 *
 * **A test that silently skips is a test that stops running**, so nothing here passes for want of a
 * tool. A missing emulator, linker or back end **cancels the test by name** — cancelled shows in
 * scalatest's own count where a pass does not, and the message says which tool and which board. The
 * gate is therefore honest on a machine with no QEMU and loud on a machine that had one yesterday.
 */
trait QemuSupport extends Matchers {

  /** The boot files, which are in the repository rather than generated: a startup that gives the
   * program a stack and reports what it returned, and a linker script that says where RAM is.
   * `shared/src/test/resources/qemu/README.md` explains both, and what each board needs that the
   * other does not.
   */
  private val boot = "shared/src/test/resources/qemu"

  /** What it takes to boot one board: the machine it is, the target whose code it runs, the
   * emulator, the arguments before the image, the startup, the support package and the linker
   * script. A target with no board here has no recipe, and asking for one cancels rather than
   * guessing — a wrong `-M` produces a program that hangs, not one that fails.
   *
   * **`bsp` is what the board owes the library**, and it is not scaffolding for the tests: a
   * freestanding sysl program names `putchar` whenever anything prints, and `malloc` and `free`
   * whenever it puts something on the heap. Neither is something the language supplies. A real
   * bare-metal project has the same file under another name — pico-sdk's, or newlib-nano's. The two
   * files say the rest.
   *
   * A program that allocates **nothing** owes the board neither, and `linksWithoutSupport` is what
   * holds the compiler to that.
   *
   * ==A board carries its target rather than being found by one==
   *
   * This was a `Map` from target name to recipe until 2026-08-10, which made a target and a machine
   * the same thing and put a ceiling of one machine per target on the whole tier. That ceiling is
   * wrong on its own terms — a target is an *architecture* and an architecture has many machines —
   * and `thumbv7em-freestanding` is where it started costing something, being the first row that
   * deliberately serves two different chips.
   *
   * So the list is the unit and a target is a field on it. Two boards may name the same target, and
   * the suites iterate boards; the console, the addresses and the machine arguments are all board
   * facts and now live where the board does.
   */
  protected case class Board(name: String, target: Target, qemu: String, machine: List[String],
                             startup: String, bsp: String, script: String)

  /** The library's own C, compiled for one board, **archived**, and kept for the rest of the run.
   *
   * A real compilation puts this C on the link line, and this harness did not until
   * `library/sysl/unicode` arrived -- every `.c` in the library had sat under a `__posix__` folder a
   * freestanding target never selects, so the omission was invisible and the whole tier failed at
   * `undefined symbol: utf8proc_toupper` the moment a program here called into it.
   *
   * **An ARCHIVE and not a list of objects, which is what a real link is handed and is the whole of
   * why this works.** A named object is linked entire, so passing the objects put 330 KB of Unicode
   * tables into every board image -- the micro:bit overflowed its flash by 80,644 bytes on a program
   * whose whole text is two `putc` calls. `-Wl,--gc-sections` looks like the answer and is not: it
   * fixed the size and hung `mps2-an505` outright, because these images are placed by a linker
   * script and reached through a vector table, and section garbage collection over that is a
   * different question from the one a hosted link answers. An archive member is pulled in only to
   * resolve a symbol something already left undefined, which is exactly the selection wanted and
   * needs nothing from the link line.
   *
   * **Memoized per target because the database is 2.3 MB of C**: five distinct targets across the
   * boards against sixty-odd cases. Nothing is discarded, these being temporaries of a short-lived
   * test process.
   */
  private val libraryArchives = collection.mutable.Map.empty[String, String]

  protected def libraryC(t: Target): String =
    libraryArchives.synchronized(libraryArchives.getOrElseUpdate(t.triple, {
      val ar = Toolchain.findAr(None) match
        case Right(path) => path
        case Left(why)   => cancel(why)

      val built = NativeSources.build(NativeSources.of(StdRoot.root.toList, t.os), t) match
        case Left(err)    => fail(s"the library's own C did not compile for ${t.triple}:\n$err")
        case Right(built) => built

      val out = createTempFile("sysl-qemu-lib-", ".a")

      Toolchain.archive(built.objects, out, ar) match
        case Left(err) => fail(s"the library's own C did not archive for ${t.triple}:\n$err")
        case Right(_)  => ()

      built.scratch.foreach(Project.discard)
      out
    }))

  protected val boards: List[Board] = List(
    Board("virt", Target.riscv32Freestanding, "qemu-system-riscv32",
      List("-M", "virt", "-bios", "none", "-nographic", "-kernel"),
      "start_rv32.s", "bsp_rv32.c", "rv32.ld"),

    // The Arm half of the same board. It has no `sifive_test`, so the result channel is semihosting
    // — which has to be asked for, and a run without `-semihosting-config` reports nothing and
    // exits as though the program had said zero.
    Board("mps2-an505", Target.thumbFreestanding, "qemu-system-arm",
      List("-M", "mps2-an505", "-nographic", "-semihosting-config", "enable=on,target=native",
        "-kernel"),
      "start_thumb.s", "bsp_thumb.c", "thumb.ld"),

    // Armv6-M, which is the RP2040's core and not a smaller setting of the one above. QEMU has no
    // RP2040 machine, and this tier does not need one: what it is here to exercise is the
    // *architecture* — no Thumb-2, no divider, no unaligned access — running instructions the back
    // end actually chose. The micro:bit's nRF51822 is the Cortex-M0 QEMU does have.
    Board("microbit", Target.thumbv6mFreestanding, "qemu-system-arm",
      List("-M", "microbit", "-nographic", "-semihosting-config", "enable=on,target=native",
        "-kernel"),
      "start_thumbv6m.s", "bsp_thumbv6m.c", "thumbv6m.ld"),

    // Armv7E-M, the STM32 Nucleo boards' architecture, on **both** of the cores its target row
    // serves. The AN500 is a Cortex-M7 and the AN386 is a Cortex-M4, and the row covers the two on
    // the argument that both pass a `double` in `d0`/`d1` under `eabihf` — an argument nothing ran
    // until the second board existed.
    //
    // **The two share every file, which is the reason this pair and not another.** The AN386 is the
    // AN500's map: `mps.ssram1` at address zero and the CMSDK UART at 0x40004000, measured with
    // `info mtree -f` rather than assumed. So the M4 end costs one line and a different `-M`, and
    // what differs between the runs is the *processor executing the image* and nothing else — which
    // is exactly the claim the target row makes.
    Board("mps2-an500", Target.thumbv7emFreestanding, "qemu-system-arm",
      List("-M", "mps2-an500", "-nographic", "-semihosting-config", "enable=on,target=native",
        "-kernel"),
      "start_thumbv7em.s", "bsp_thumbv7em.c", "thumbv7em.ld"),

    Board("mps2-an386", Target.thumbv7emFreestanding, "qemu-system-arm",
      List("-M", "mps2-an386", "-nographic", "-semihosting-config", "enable=on,target=native",
        "-kernel"),
      "start_thumbv7em.s", "bsp_thumbv7em.c", "thumbv7em.ld"),

    // Armv7-M, on the AN385's Cortex-M3 — the third MPS2 in this list and the only one of the three
    // with **no floating-point unit at all**. It is here for the reason the micro:bit is: what has to
    // be run is the *architecture*, and nothing else on this tier boots an image whose back end was
    // told there is nothing to compute a float with.
    //
    // Its map is the AN500's to the byte, checked with `info mtree -f` rather than assumed from the
    // family name — so the three board files are that board's text under names saying Armv7-M, and
    // `bsp_thumbv7m.c`'s own header explains why they are copies rather than one shared file.
    Board("mps2-an385", Target.thumbv7mFreestanding, "qemu-system-arm",
      List("-M", "mps2-an385", "-nographic", "-semihosting-config", "enable=on,target=native",
        "-kernel"),
      "start_thumbv7m.s", "bsp_thumbv7m.c", "thumbv7m.ld")
  )

  /** Every board that runs `t`'s code. A target with none has no recipe here at all. */
  protected def boardsFor(t: Target): List[Board] = boards.filter(_.target == t)

  /** One board for `t`, for a check that is about the *target* rather than about a machine — a link
   * that must not name an allocator is the same link on every board of an architecture. Cancels
   * rather than guessing when the target has no recipe.
   */
  protected def someBoard(t: Target): Board =
    boardsFor(t).headOption.getOrElse(cancel(s"no QEMU recipe for ${t.name}"))


  /** The board's console, as a sysl module a test program imports.
   *
   * **It is a module rather than part of the program's root file, and it has to be.** A root file's
   * top-level `val` and `var` are bindings in the statement stream, and an `impl` member can reach
   * neither one of them nor any root function that touches one — so a `Writer` whose `write` needs
   * the device pointer, or a `putc` built on it, cannot be written there at all. One directory down
   * it is ordinary code.
   *
   * `console()` both enables the device and hands back the sink, so a program's whole arrangement is
   * one line. `virt`'s 16550 takes a byte at offset zero and needs no setting up; the MPS2's CMSDK
   * takes a *word* and **transmits nothing until it is enabled** — a program that writes to it cold
   * produces no output at all, and QEMU says so only under `-d guest_errors`.
   *
   * `Console` has no fields, so the pointer the sink is built on addresses nothing and nothing ever
   * reads through it — `sysl.print`'s `Stdout` uses the same trick, and for the same reason: a
   * destination fixed at compile time keeps no state.
   */
  protected def boardModule(b: Board, extra: String = ""): Source = {
    val regs = b.name match
      case "virt" =>
        """struct Uart
          |    data: volatile u8
          |
          |val UART: usize = 0x10000000
          |val regs: *Uart = ptr_cast(UART)
          |
          |putc(c: u8)
          |    regs.data = c
          |
          |console() -> *Writer = uart
          |""".stripMargin

      case "mps2-an505" =>
        """struct Uart
          |    data: volatile u32
          |    state: volatile u32
          |    ctrl: volatile u32
          |    intstatus: volatile u32
          |    bauddiv: volatile u32
          |
          |val UART: usize = 0x40200000
          |val regs: *Uart = ptr_cast(UART)
          |
          |putc(c: u8)
          |    regs.data = u32(c)
          |
          |console() -> *Writer
          |    regs.bauddiv = 16
          |    regs.ctrl = 1
          |    uart
          |""".stripMargin

      // The AN500's CMSDK UART is the AN505's device at a different address -- five of them in the
      // APB region from 0x40004000, of which `-nographic` wires the first to QEMU's stdout. Same
      // word-wide registers, and the same refusal to transmit until `bauddiv` and `ctrl` are set.
      // The AN386 and the AN385 are the same board one and two processor generations earlier, and
      // put them in the same place — measured with `info mtree -f` on each rather than inferred from
      // the three sharing a family name.
      case "mps2-an500" | "mps2-an386" | "mps2-an385" =>
        """struct Uart
          |    data: volatile u32
          |    state: volatile u32
          |    ctrl: volatile u32
          |    intstatus: volatile u32
          |    bauddiv: volatile u32
          |
          |val UART: usize = 0x40004000
          |val regs: *Uart = ptr_cast(UART)
          |
          |putc(c: u8)
          |    regs.data = u32(c)
          |
          |console() -> *Writer
          |    regs.bauddiv = 16
          |    regs.ctrl = 1
          |    uart
          |""".stripMargin

      // The nRF51's transmitter is a peripheral to be started rather than a register to be written,
      // and its ready flag is an *event* that has to be cleared before the next byte — a second
      // write without clearing spins forever on a stale ready. `bsp_thumbv6m.c` says what each
      // number is; the pin is the micro:bit's wiring and the rest is the chip's.
      case "microbit" =>
        """struct Reg
          |    v: volatile u32
          |
          |val UART: usize = 0x40002000
          |
          |val starttx: *Reg = ptr_cast(UART + 0x008)
          |val txdrdy: *Reg = ptr_cast(UART + 0x11c)
          |val enable: *Reg = ptr_cast(UART + 0x500)
          |val pseltxd: *Reg = ptr_cast(UART + 0x50c)
          |val txd: *Reg = ptr_cast(UART + 0x51c)
          |val baudrate: *Reg = ptr_cast(UART + 0x524)
          |
          |putc(c: u8)
          |    txdrdy.v = 0
          |    txd.v = u32(c)
          |
          |    while txdrdy.v == 0
          |        ()
          |
          |console() -> *Writer
          |    pseltxd.v = 24
          |    baudrate.v = 0x01d7e000
          |    enable.v = 4
          |    starttx.v = 1
          |    uart
          |""".stripMargin

      case n => cancel(s"no console source for $n")

    val console =
      """
        |struct Console
        |end Console
        |
        |impl Fallible for Console
        |
        |impl Writer for Console
        |    write(*self, bytes: []const u8)
        |        for b in bytes do putc(b)
        |end Console
        |
        |val uart: *Console = ptr_cast(0usize)
        |""".stripMargin

    Source("board.sysl", s"module board\n\n$regs$console$extra", List("board"))
  }

  /** Whether a program exists to be run. `--version` rather than `which`, because a name on the
   * PATH that will not start is the failure this is meant to catch.
   */
  private def present(program: String): Boolean =
    try exec(Seq(program, "--version")).exitCode == 0
    catch case _: Exception => false

  /** **QEMU has no wall-clock limit of its own**, and a program that never reaches the exit device
   * runs until something kills it — which, in a suite, is the suite. `perl -e 'alarm'` is the
   * portable way to put a bound on it: perl ships with macOS and with every CI image, and `exec`
   * replaces the shell so the signal reaches the emulator rather than a wrapper.
   *
   * **The emulator's own standard error is folded into the guest's output**, which is deliberate and
   * is what makes a dead board explain itself. QEMU reports a fault the guest cannot survive on its
   * own stderr and then aborts — `Lockup: can't escalate 3 to HardFault` — so a test that reads only
   * the guest's stdout sees an empty string and a status of 134, which names nothing. The two
   * streams are ordered against each other this way too, so the last thing the program printed sits
   * immediately above what killed it.
   */
  private def bounded(seconds: Int, command: List[String]): List[String] =
    "perl" :: "-e" :: "alarm shift; open(STDERR, '>&STDOUT'); exec @ARGV" :: seconds.toString :: command

  /** Builds `src` for `t`, boots it, and answers with what the board said and what it exited with.
   *
   * The startup is assembled by the same clang that compiled the module, for the same triple, so
   * the two agree about the machine without anything having to state it twice. The link is
   * `-nostdlib` — there is no libc on a bare board and asking for one finds the host's — and
   * `-fuse-ld=lld`, because these are ELF images and the system linker on a Mac is not an ELF
   * linker.
   */
  protected def bootUnderQemu(board: Board, src: String, seconds: Int = 20): (Int, String) =
    bootUnderQemu(board, List(Source("p.sysl", src)), seconds)

  /** The same, for a program that is more than one file.
   *
   * A program whose console is its own module needs this, and **why it has to be its own module is
   * worth knowing**: a root file's top-level `val` and `var` are bindings in the program's statement
   * stream, in scope from where they are written onward, and an `impl` member can reach neither one
   * of them nor any root function that touches one. A `Writer` that talks to a memory-mapped device
   * is exactly a member that must, so a board's console cannot be written at the root at all. One
   * directory down it is ordinary code.
   */
  /** The **link**, with the board's startup and nothing else — no support package, so no `putchar`,
   * no `malloc` and no `free`. What comes back is the linker's status and what it said.
   *
   * ==This is the tier that was missing, and its absence is why 0037 lived for months==
   *
   * Every cross-target tier below QEMU stops at an object file, and a call to a function nothing
   * defines makes a perfectly good object: it verifies, it assembles, and `clang -c` is happy. So a
   * compiler that emitted `call @free` into every program that touched a slice was invisible to all
   * of them. The QEMU tier does link — and links **against the support package**, which defines
   * `free`, so it was invisible there too.
   *
   * Linking without that file is the only arrangement in which "this program needs no allocator" is
   * a claim something can fail. A program that names one gets `undefined symbol: free`, which is the
   * message the board build gave and the reason the card exists.
   */
  protected def linksWithoutSupport(board: Board, sources: List[Source]): (Int, String) = {
    val t  = board.target
    val cc = Toolchain.findClang(t).getOrElse(cancel(s"no clang here has a back end for ${t.name}"))

    if !present("ld.lld") then cancel(s"ld.lld is not installed, so a ${t.name} image cannot be linked")

    val ir = Compiler.compile(sources, t) match
      case Right(ir) => ir
      case Left(e)   => fail(s"did not compile for ${t.name}:\n$e")

    val prog  = createTempFile("sysl-link-", ".o")
    val start = createTempFile("sysl-link-start-", ".o")
    val image = createTempFile("sysl-link-", ".elf")

    try {
      withClue(s"compiling the module for ${t.triple}: ")(Toolchain.compileObject(ir, prog, t) shouldBe Right(()))

      val asm = exec(Seq(cc, s"--target=${t.triple}", "-c", s"$boot/${board.startup}", "-o", start))

      withClue(s"assembling ${board.startup}:\n${asm.stderr}")(asm.exitCode shouldBe 0)

      val link = exec(Seq(cc, s"--target=${t.triple}", "-nostdlib", "-fuse-ld=lld",
        "-T", s"$boot/${board.script}", start, prog, "-o", image))

      (link.exitCode, link.stderr)
    } finally
      for f <- List(prog, start, image) do try deleteFile(f) catch case _: Exception => ()
  }

  protected def bootUnderQemu(board: Board, sources: List[Source], seconds: Int): (Int, String) = {
    val t  = board.target
    val cc = Toolchain.findClang(t).getOrElse(cancel(s"no clang here has a back end for ${t.name}"))

    if !present(board.qemu) then cancel(s"${board.qemu} is not installed, so ${board.name} cannot be booted")
    if !present("ld.lld") then cancel(s"ld.lld is not installed, so a ${t.name} image cannot be linked")

    // The compiler's own message, not a summary of it: a diagnostic swallowed here is a diagnostic
    // that has to be rediscovered by hand, and this tier's programs are written for a machine the
    // author is not sitting at.
    val shown = sources.map(s => s"---- ${s.name}\n${s.text}").mkString("\n")

    val ir = Compiler.compile(sources, t) match
      case Right(ir) => ir
      case Left(e)   => fail(s"did not compile for ${t.name}:\n$e\n\nthe program was:\n$shown")

    val prog  = createTempFile("sysl-qemu-", ".o")
    val start = createTempFile("sysl-qemu-start-", ".o")
    val bsp   = createTempFile("sysl-qemu-bsp-", ".o")
    val image = createTempFile("sysl-qemu-", ".elf")

    try {
      withClue(s"compiling the module for ${t.triple}: ")(Toolchain.compileObject(ir, prog, t) shouldBe Right(()))

      val asm = exec(Seq(cc, s"--target=${t.triple}", "-c", s"$boot/${board.startup}", "-o", start))

      withClue(s"assembling ${board.startup}:\n${asm.stderr}")(asm.exitCode shouldBe 0)

      // `-ffreestanding` says there is no hosted library here, which is what makes it legal to
      // define `putchar` and `malloc`; without it clang knows those names and objects to the
      // definitions. `-Os` because the arena and the UART are the whole file and none of it is hot.
      val sup = exec(Seq(cc, s"--target=${t.triple}", "-ffreestanding", "-Os", "-c",
        s"$boot/${board.bsp}", "-o", bsp))

      withClue(s"compiling ${board.bsp}:\n${sup.stderr}")(sup.exitCode shouldBe 0)

      // **The library's own C, compiled for the board.** A real `sysl build` puts these objects on
      // the link line (`NativeSources`), and this harness did not — which was invisible for as long
      // as every `.c` in `library/` sat under a `__posix__` folder a freestanding target never
      // selects. `library/sysl/unicode/utf8proc.c` is the first that does not, and the whole tier
      // failed at `undefined symbol: utf8proc_toupper` the moment a program here called into it.
      // Building them the same way a compilation does is what keeps the two from drifting again.
      val native = libraryC(t)

      // The archive goes last, after everything that might leave one of its symbols undefined --
      // which is the ordinary rule for an archive on a link line and is why the order here matters.
      val link = exec(Seq(cc, s"--target=${t.triple}", "-nostdlib", "-fuse-ld=lld",
        "-T", s"$boot/${board.script}", start, prog, bsp, native, "-o", image))

      withClue(s"linking the image:\n${link.stderr}")(link.exitCode shouldBe 0)

      val ran = exec(bounded(seconds, board.qemu :: board.machine ::: List(image)))

      (ran.exitCode, ran.stdout)
    } finally
      // `SYSL_QEMU_KEEP=<dir>` copies the linked image there before it goes, which is the only way
      // to get a disassembly of a board program that misbehaved -- the temporary is deleted on exit,
      // so reading the path out of a log is too late. Nothing reads the variable in an ordinary run.
      envVar("SYSL_QEMU_KEEP") match
        case Some(dir) =>
          val kept = s"$dir/${board.name}-${image.split('/').last}"
          exec(Seq("cp", image, kept))
          println(s"[qemu] kept $kept")
        case None =>
      for f <- List(prog, start, bsp, image) do try deleteFile(f) catch case _: Exception => ()
  }
}
