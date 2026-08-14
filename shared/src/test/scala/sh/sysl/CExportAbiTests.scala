package sh.sysl

import io.github.edadma.cross_platform.*

/** An **aggregate** crossing into an `@export`ed function, run rather than read (`15 §12`, 0137).
 *
 * `@export` was a rename: the definition took the C symbol and kept sysl's own parameter lowering,
 * so a C caller put its arguments where its convention says and the body read them where sysl's
 * does. For a scalar those coincide and the two agreed by luck. For an aggregate they do not, and
 * what arrived was whatever had been in the registers sysl looked at — with no diagnostic, no
 * warning, and a link that succeeded.
 *
 * **This is the suite that can see it, and it is the only kind that can.** `CAbiTests` and
 * `AbiAgainstClangTests` compare sysl's `declare` lines against clang's, which is what pins the
 * classification; neither says anything about the *definition* side, because there is no second
 * compiler in them to disagree with. Here the C is really compiled beside the sysl and really calls
 * in, so what is asserted is the number that arrived.
 *
 * **`999` is the C saying it read something other than what it wrote**, which is what every case
 * below printed before the entry was lowered through `CAbi` (`ExportThunk`).
 *
 * The shapes are chosen to reach a different arm of the classification rather than to be varied for
 * its own sake: `Id` is `{i32,u16,u16}` — `b2BodyId` exactly, and eight bytes, so one integer
 * register on AArch64 where sysl's own lowering passes a two-field LLVM struct; `Pair` is
 * `{f32,f32}`, a homogeneous floating aggregate, which travels in floating registers on a machine
 * that has them and nowhere near the integer ones; `Big` is twenty-four bytes, which is past every
 * convention's register threshold and goes through memory in both directions.
 */
class CExportAbiTests extends LibraryCliSupport {

  private def guard(): Unit = assume(Toolchain.clangAvailable, "clang not available")

  /** A project whose module carries the C that calls back into it, and a `main.sysl` printing the
   * answer — the same shape `CNarrowScalarTests` uses, for the same reason.
   */
  private def project(sysl: String, c: String): String = {
    val root = createTempDirectory("sysl-export-abi-")

    createDirectories(s"$root/p")
    writeFile(s"$root/main.sysl", "print(p.check())\n")
    writeFile(s"$root/p/p.sysl", s"module p\n\n$sysl")
    writeFile(s"$root/p/probe.c", c)
    root
  }

  private def ranProject(sysl: String, c: String): String = {
    guard()
    ran(Config(command = "run", file = project(sysl, c)))
  }

  /** The three shapes, declared once in each language so that a case says only what it is about. */
  private val types =
    """struct Id
      |    index1: i32
      |    world0: u16
      |    generation: u16
      |
      |struct Pair
      |    x: f32
      |    y: f32
      |
      |struct Big
      |    a: i64
      |    b: i64
      |    c: i64
      |
      |""".stripMargin

  private val cTypes =
    """#include <stdint.h>
      |
      |typedef struct { int32_t index1; uint16_t world0; uint16_t generation; } Id;
      |typedef struct { float x; float y; } Pair;
      |typedef struct { int64_t a; int64_t b; int64_t c; } Big;
      |
      |""".stripMargin

  "an aggregate C passes to an exported function arrives whole" - {

    /** The single-parameter case, and it fails too — which the report did not find, because the
     * function it measured read only `index1`. That field is the low four bytes of the eight the
     * struct occupies, so it lands in the same place under either lowering; everything above it does
     * not. Reading a second field is the whole of the difference between seeing this and not.
     */
    "one parameter, every field of it read" in {
      ranProject(
        types +
          """@export("probe_one")
            |one(a: Id) -> int = a.index1 * 1000 + int(a.world0) * 10 + int(a.generation)
            |
            |extern "probe_ask" ask() -> int
            |
            |check() -> int = ask()
            |""".stripMargin,
        cTypes +
          """int probe_one( Id a );
            |
            |int probe_ask( void )
            |{
            |	Id a = { 7, 3, 9 };
            |
            |	return probe_one( a ) == 7039 ? 0 : 999;
            |}
            |""".stripMargin) shouldBe "0\n"
    }

    /** The reported case. Two aggregates: the first happened to coincide and the second did not, so
     * what came back was `a.index1 * 1000` plus whatever `b.index1` read as — `-1` in the report.
     */
    "two parameters, which is the case that was reported" in {
      ranProject(
        types +
          """@export("probe_two")
            |two(a: Id, b: Id) -> int = a.index1 * 1000 + b.index1
            |
            |extern "probe_ask" ask() -> int
            |
            |check() -> int = ask()
            |""".stripMargin,
        cTypes +
          """int probe_two( Id a, Id b );
            |
            |int probe_ask( void )
            |{
            |	Id a = { 7, 3, 9 };
            |	Id b = { 5, 4, 8 };
            |
            |	return probe_two( a, b ) == 7005 ? 0 : 999;
            |}
            |""".stripMargin) shouldBe "0\n"
    }

    /** An aggregate beside a pointer — the shape every C callback has, since the library hands the
     * handle and the registration's `void *` together.
     */
    "an aggregate and a context pointer" in {
      ranProject(
        types +
          """@export("probe_ctx")
            |ctx(a: Id, context: *u8) -> int = a.index1 * 1000 + int(context[0])
            |
            |extern "probe_ask" ask() -> int
            |
            |check() -> int = ask()
            |""".stripMargin,
        cTypes +
          """int probe_ctx( Id a, unsigned char *context );
            |
            |int probe_ask( void )
            |{
            |	Id a = { 7, 3, 9 };
            |	unsigned char buf[1] = { 4 };
            |
            |	return probe_ctx( a, buf ) == 7004 ? 0 : 999;
            |}
            |""".stripMargin) shouldBe "0\n"
    }

    /** A scalar in front of it, which is what moves the aggregate off the first register — the
     * report's last row, where the scalar was right and the id was garbage.
     */
    "a scalar first, then the aggregate" in {
      ranProject(
        types +
          """@export("probe_after")
            |after(n: i32, a: Id) -> int = n * 1000000 + a.index1 * 1000 + int(a.generation)
            |
            |extern "probe_ask" ask() -> int
            |
            |check() -> int = ask()
            |""".stripMargin,
        cTypes +
          """int probe_after( int32_t n, Id a );
            |
            |int probe_ask( void )
            |{
            |	Id a = { 7, 3, 9 };
            |
            |	return probe_after( 2, a ) == 2007009 ? 0 : 999;
            |}
            |""".stripMargin) shouldBe "0\n"
    }

    /** A homogeneous floating aggregate, which is the arm nothing above reaches: it travels in the
     * floating registers on a machine that has them, so a lowering that put it in the integer ones
     * would read two numbers that were never written rather than the wrong half of one.
     */
    "a homogeneous floating aggregate" in {
      ranProject(
        types +
          """@export("probe_pair")
            |pair(p: Pair, q: Pair) -> f64 = f64(p.x) + f64(p.y) * 10.0 + f64(q.x) * 100.0
            |
            |extern "probe_ask" ask() -> int
            |
            |check() -> int = ask()
            |""".stripMargin,
        cTypes +
          """double probe_pair( Pair p, Pair q );
            |
            |int probe_ask( void )
            |{
            |	Pair p = { 1.0f, 2.0f };
            |	Pair q = { 3.0f, 4.0f };
            |	double got = probe_pair( p, q );
            |
            |	return got > 320.9 && got < 321.1 ? 0 : 999;
            |}
            |""".stripMargin) shouldBe "0\n"
    }

    /** Past every convention's register threshold, so it crosses in memory — `byval` on the
     * conventions that ask for a copy the callee owns, and a plain pointer on the ones that do not.
     */
    "one too large for registers, which crosses in memory" in {
      ranProject(
        types +
          """@export("probe_big")
            |big(v: Big, w: Big) -> i64 = v.a + v.b * 10 + v.c * 100 + w.a * 1000
            |
            |extern "probe_ask" ask() -> int
            |
            |check() -> int = ask()
            |""".stripMargin,
        cTypes +
          """int64_t probe_big( Big v, Big w );
            |
            |int probe_ask( void )
            |{
            |	Big v = { 1, 2, 3 };
            |	Big w = { 4, 5, 6 };
            |
            |	return probe_big( v, w ) == 4321 ? 0 : 999;
            |}
            |""".stripMargin) shouldBe "0\n"
    }
  }

  "an aggregate an exported function returns arrives whole" - {

    "a small one, which comes back in registers" in {
      ranProject(
        types +
          """@export("probe_make")
            |make(n: i32) -> Id = Id(n, 3, 9)
            |
            |extern "probe_ask" ask() -> int
            |
            |check() -> int = ask()
            |""".stripMargin,
        cTypes +
          """Id probe_make( int32_t n );
            |
            |int probe_ask( void )
            |{
            |	Id a = probe_make( 7 );
            |
            |	return a.index1 == 7 && a.world0 == 3 && a.generation == 9 ? 0 : 999;
            |}
            |""".stripMargin) shouldBe "0\n"
    }

    /** Through the out-pointer the caller supplies, which is a parameter the sysl signature does not
     * mention and which sits in front of every one it does.
     */
    "a large one, which comes back through storage the caller supplied" in {
      ranProject(
        types +
          """@export("probe_make_big")
            |make_big(n: i64) -> Big = Big(n, n + 1, n + 2)
            |
            |extern "probe_ask" ask() -> int
            |
            |check() -> int = ask()
            |""".stripMargin,
        cTypes +
          """Big probe_make_big( int64_t n );
            |
            |int probe_ask( void )
            |{
            |	Big v = probe_make_big( 10 );
            |
            |	return v.a == 10 && v.b == 11 && v.c == 12 ? 0 : 999;
            |}
            |""".stripMargin) shouldBe "0\n"
    }

    /** Both directions in one call, which is what a C library's `b2Body_GetTransform`-shaped entry
     * points do and what neither half above proves on its own.
     */
    "one that went in and came back out" in {
      ranProject(
        types +
          """@export("probe_bump")
            |bump(a: Id) -> Id = Id(a.index1 + 1, a.world0, a.generation)
            |
            |extern "probe_ask" ask() -> int
            |
            |check() -> int = ask()
            |""".stripMargin,
        cTypes +
          """Id probe_bump( Id a );
            |
            |int probe_ask( void )
            |{
            |	Id a = { 7, 3, 9 };
            |	Id b = probe_bump( a );
            |
            |	return b.index1 == 8 && b.world0 == 3 && b.generation == 9 ? 0 : 999;
            |}
            |""".stripMargin) shouldBe "0\n"
    }
  }

  /** `&f` (`12 §6a`, 0136) — the address, which is the half a callback registration needs and the
   * half that was refused for every function with an aggregate anywhere in its signature.
   */
  "the address of an exported function is one C can call through" - {

    "and it is the thunk's, so the aggregate arrives whole through it too" in {
      ranProject(
        types +
          """@export("probe_filter")
            |filter(a: Id, b: Id) -> bool = a.index1 < b.index1
            |
            |extern "probe_through" through(f: *extern(Id, Id) -> bool) -> int
            |
            |check() -> int = through(&filter)
            |""".stripMargin,
        cTypes +
          """typedef _Bool ( *FilterFn )( Id, Id );
            |
            |int probe_through( FilterFn f )
            |{
            |	Id a = { 1, 3, 9 };
            |	Id b = { 2, 4, 8 };
            |
            |	return f( a, b ) && !f( b, a ) ? 0 : 999;
            |}
            |""".stripMargin) shouldBe "0\n"
    }

    /** The `extern` row. Its address was refused with a sentence about sysl's lowering, which cannot
     * apply: this compiler neither compiled `probe_c_filter` nor chose its convention, and its type
     * is what the declaration transcribed from the header.
     */
    "and the address of an extern is C's own, which sysl never had an opinion about" in {
      ranProject(
        types +
          """extern "probe_c_filter" c_filter(a: Id, b: Id) -> bool
            |extern "probe_through" through(f: *extern(Id, Id) -> bool) -> int
            |
            |check() -> int = through(&c_filter)
            |""".stripMargin,
        cTypes +
          """typedef _Bool ( *FilterFn )( Id, Id );
            |
            |_Bool probe_c_filter( Id a, Id b ) { return a.index1 < b.index1; }
            |
            |int probe_through( FilterFn f )
            |{
            |	Id a = { 1, 3, 9 };
            |	Id b = { 2, 4, 8 };
            |
            |	return f( a, b ) && !f( b, a ) ? 0 : 999;
            |}
            |""".stripMargin) shouldBe "0\n"
    }
  }

  /** The generated header, given to the compiler it is written for.
   *
   * `ExportTests` asserts what is *in* it, which is a check on the text. This is the other half: a
   * header that names a struct now has to define one, and a definition is C the consumer's compiler
   * either accepts or does not. Nothing in a substring assertion would notice a missing semicolon,
   * a member declared with its array brackets in the wrong place, or a struct used before it is
   * complete — and all three are new as of 0137.
   */
  "the generated header is C that clang accepts" in {
    guard()

    val src =
      """module demo
        |
        |struct Inner
        |    x: i32
        |
        |struct Id
        |    index1: i32
        |    world0: u16
        |    inner: Inner
        |    bytes: [4]u8
        |
        |enum Colour: u8
        |    Red
        |    Green
        |
        |@export("demo_bump")
        |bump(a: Id, c: Colour, p: *u8) -> Id = a
        |
        |@export("demo_spin")
        |spin() -> never =
        |    loop
        |        print(1)
        |""".stripMargin

    val exports = Compiler.compiled(List(Source("<input>", src))) match
      case Right(c) => c.exports
      case Left(e)  => fail(e)

    val header = createTempFile("sysl-header-", ".h")
    val user   = createTempFile("sysl-header-user-", ".c")

    try {
      writeFile(header, CHeader.render(exports, "demo"))
      // Included **twice**, which is what the guard is for, and used — so the struct has to be a
      // complete type rather than merely a name the prototype mentions.
      writeFile(user,
        s"""#include "$header"
           |#include "$header"
           |
           |int use( void )
           |{
           |	demo_Id a = { 7, 3, { 1 }, { 2, 3, 4, 5 } };
           |	unsigned char b = 9;
           |	demo_Id r = demo_bump( a, 1, &b );
           |
           |	return r.index1 + r.inner.x + r.bytes[3];
           |}
           |""".stripMargin)

      val cc = Toolchain.findClang(Target.default).getOrElse(cancel("no clang here"))
      val r  = exec(Seq(cc, "-std=c99", "-Wall", "-Werror", "-fsyntax-only", user))

      withClue(s"clang refused the generated header:\n${r.stderr}")(r.exitCode shouldBe 0)
    } finally
      try { deleteFile(user); deleteFile(header) }
      catch case _: Exception => ()
  }

  /** A sysl caller of an exported function reaches the **definition**, not the thunk — so the
   * conversion is not paid on a call that never leaves sysl, and the two lowerings stay apart.
   */
  "an export costs a sysl caller nothing" in {
    ranProject(
      types +
        """@export("probe_two")
          |two(a: Id, b: Id) -> int = a.index1 * 1000 + b.index1
          |
          |check() -> int = two(Id(7, 3, 9), Id(5, 4, 8))
          |""".stripMargin,
      cTypes) shouldBe "7005\n"
  }
}
