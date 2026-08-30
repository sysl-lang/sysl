package sh.sysl

import io.github.edadma.cross_platform.*

/** An aggregate crossing at a function pointer **held in a struct member**, in both directions.
 *
 * `CExportAbiTests` pins the same classification at a call to a **named symbol**: C calls
 * `probe_big` and the linker resolves it. That is not the shape a callback-table library has. In
 * CEF, in llhttp's settings, in COM and in every other vtable-shaped C API, nothing is ever called
 * by name — a caller loads a function pointer out of a struct and calls through it, and the struct
 * was filled in by the other language. The two questions are separable: the classification is
 * chosen from the signature, and the signature reaches an indirect call through a *type* rather
 * than through a declaration the compiler emitted a `declare` line for.
 *
 * **`999` is the C saying it read something other than what it wrote**, and a `0` means every field
 * of every aggregate survived. The sysl-driven cases return a bitmask, so a failure names which
 * member disagreed rather than only that one did.
 *
 * The shapes are CEF's, transcribed from its C API, because that library is the reason the question
 * is worth a suite: `cef_size_t` and `cef_point_t` are eight bytes, `cef_rect_t` sixteen,
 * `cef_string_utf16_t` twenty-four of mixed pointer and integer, and `cef_time_t` thirty-two of
 * plain `int` — which is both sides of every convention's register threshold, and both the uniform
 * and the mixed reading of the memory case. Its getters return geometry **by value**, and a client
 * implements them, so the direction that matters most here is the one where sysl is the callee
 * returning an aggregate it never gets to see the caller's storage for.
 *
 * **A struct whose members take the struct they are a member of** is the other thing being pinned:
 * every CEF object begins with a `cef_base_ref_counted_t` whose four members take a
 * `cef_base_ref_counted_t*`, and the object's own members take the object. A binding cannot be
 * written at all if that type does not go through.
 */
class VtableAbiTests extends LibraryCliSupport with CodegenSupport {

  private def guard(): Unit = assume(Toolchain.clangAvailable, "clang not available")

  private def project(sysl: String, c: String): String = {
    val root = createTempDirectory("sysl-vtable-abi-")

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

  /** The shapes, declared once in each language so a case says only what it is about. */
  private val types =
    """struct Point
      |    x: i32
      |    y: i32
      |
      |struct Size
      |    width: i32
      |    height: i32
      |
      |struct Rect
      |    x: i32
      |    y: i32
      |    width: i32
      |    height: i32
      |
      |struct Str
      |    text: *u16
      |    length: usize
      |    dtor: *extern(*u16) -> unit
      |
      |struct Stamp
      |    year: i32
      |    month: i32
      |    day: i32
      |    hour: i32
      |    minute: i32
      |    second: i32
      |    milli: i32
      |    micro: i32
      |
      |struct Base
      |    size: usize
      |    add_ref: *extern(*Base) -> unit
      |    release: *extern(*Base) -> i32
      |
      |struct Delegate
      |    base: Base
      |    preferred_size: *extern(*Delegate) -> Size
      |    bounds: *extern(*Delegate) -> Rect
      |    label: *extern(*Delegate) -> Str
      |    stamp: *extern(*Delegate) -> Stamp
      |    on_point: *extern(*Delegate, Point) -> i32
      |    on_stamp: *extern(*Delegate, Stamp) -> i32
      |
      |struct Table
      |    base: Base
      |    bounds: *extern(*Table) -> Rect
      |    extent: *extern(*Table) -> Size
      |    label: *extern(*Table) -> Str
      |    on_point: *extern(*Table, Point) -> i32
      |    on_stamp: *extern(*Table, Stamp) -> i32
      |
      |""".stripMargin

  /** The callbacks C reaches through the struct, and the struct itself.
    *
    * Every one carries `@export`, which is what gives a sysl function an address C can call: the
    * plain form is refused, and the last case in this suite is that refusal.
    *
    * **`self` is a reserved word**, so the parameter every one of these takes is written in
    * backticks — which is the name a binding generator emitting CEF's own spelling has to produce.
    */
  private val handlers =
    """@export("d_add_ref")
      |d_add_ref(`self`: *Base) -> unit = ()
      |
      |@export("d_release")
      |d_release(`self`: *Base) -> i32 = 4242
      |
      |@export("d_preferred_size")
      |d_preferred_size(`self`: *Delegate) -> Size = Size(1280, 720)
      |
      |@export("d_bounds")
      |d_bounds(`self`: *Delegate) -> Rect = Rect(11, 22, 33, 44)
      |
      |@export("d_label")
      |d_label(`self`: *Delegate) -> Str = Str(null, 7usize, null)
      |
      |@export("d_stamp")
      |d_stamp(`self`: *Delegate) -> Stamp = Stamp(2026, 8, 29, 13, 5, 7, 250, 61)
      |
      |@export("d_on_point")
      |d_on_point(`self`: *Delegate, p: Point) -> i32 = p.x * 1000 + p.y
      |
      |@export("d_on_stamp")
      |d_on_stamp(`self`: *Delegate, s: Stamp) -> i32 = s.year * 1000 + s.micro
      |
      |var delegate: Delegate = Delegate(
      |    Base(sizeof(Delegate), &d_add_ref, &d_release),
      |    &d_preferred_size,
      |    &d_bounds,
      |    &d_label,
      |    &d_stamp,
      |    &d_on_point,
      |    &d_on_stamp)
      |
      |extern "probe_ask" ask(d: *Delegate) -> int
      |
      |check() -> int = ask(&delegate)
      |
      |""".stripMargin

  private val cTypes =
    """#include <stdint.h>
      |#include <stddef.h>
      |
      |typedef struct { int32_t x, y; } Point;
      |typedef struct { int32_t width, height; } Size;
      |typedef struct { int32_t x, y, width, height; } Rect;
      |typedef struct { uint16_t* text; size_t length; void ( *dtor )( uint16_t* ); } Str;
      |typedef struct { int32_t year, month, day, hour, minute, second, milli, micro; } Stamp;
      |
      |typedef struct _Base {
      |	size_t size;
      |	void ( *add_ref )( struct _Base* self );
      |	int32_t ( *release )( struct _Base* self );
      |} Base;
      |
      |typedef struct _Delegate {
      |	Base base;
      |	Size ( *preferred_size )( struct _Delegate* self );
      |	Rect ( *bounds )( struct _Delegate* self );
      |	Str ( *label )( struct _Delegate* self );
      |	Stamp ( *stamp )( struct _Delegate* self );
      |	int32_t ( *on_point )( struct _Delegate* self, Point p );
      |	int32_t ( *on_stamp )( struct _Delegate* self, Stamp s );
      |} Delegate;
      |
      |typedef struct _Table {
      |	Base base;
      |	Rect ( *bounds )( struct _Table* self );
      |	Size ( *extent )( struct _Table* self );
      |	Str ( *label )( struct _Table* self );
      |	int32_t ( *on_point )( struct _Table* self, Point p );
      |	int32_t ( *on_stamp )( struct _Table* self, Stamp s );
      |} Table;
      |
      |""".stripMargin

  /** The library-implemented side: a table C built, for the cases sysl drives. */
  private val cTable =
    """static void t_add_ref( Base* self ) { (void)self; }
      |static int32_t t_release( Base* self ) { (void)self; return 7; }
      |
      |static Rect t_bounds( Table* self ) { (void)self; return (Rect){ 101, 102, 103, 104 }; }
      |static Size t_extent( Table* self ) { (void)self; return (Size){ 201, 202 }; }
      |static Str t_label( Table* self ) { (void)self; return (Str){ NULL, 31, NULL }; }
      |static int32_t t_on_point( Table* self, Point p ) { (void)self; return p.x * 1000 + p.y; }
      |static int32_t t_on_stamp( Table* self, Stamp s ) { (void)self; return s.year * 1000 + s.micro; }
      |
      |static Table the_table = {
      |	{ sizeof(Table), t_add_ref, t_release },
      |	t_bounds,
      |	t_extent,
      |	t_label,
      |	t_on_point,
      |	t_on_stamp
      |};
      |
      |Table* probe_table( void ) { return &the_table; }
      |
      |void probe_sizes( size_t* out )
      |{
      |	out[0] = sizeof(Base);
      |	out[1] = sizeof(Delegate);
      |	out[2] = sizeof(Str);
      |	out[3] = sizeof(Stamp);
      |}
      |
      |""".stripMargin

  private def cDrives(body: String): String = ranProject(types + handlers, cTypes + body)

  private def syslDrives(body: String): String =
    ranProject(types +
                 """extern "probe_table" probe_table() -> *Table
                   |
                   |""".stripMargin + body,
               cTypes + cTable)

  "an aggregate a sysl callback returns through a struct member arrives whole" - {

    /** Eight bytes: one integer register on AArch64, two on the conventions that split it. The
     * callee never sees the caller's storage, so a wrong classification here returns whatever was
     * in the register the caller reads.
     */
    "a small one" in {
      cDrives(
        """int probe_ask( Delegate* d )
          |{
          |	Size s = d->preferred_size( d );
          |
          |	return s.width == 1280 && s.height == 720 ? 0 : 999;
          |}
          |""".stripMargin) shouldBe "0\n"
    }

    /** Sixteen bytes, which several conventions name `i128` once it is aligned and which is the
     * last size that still travels in registers.
     */
    "one at the register threshold" in {
      cDrives(
        """int probe_ask( Delegate* d )
          |{
          |	Rect r = d->bounds( d );
          |
          |	return r.x == 11 && r.y == 22 && r.width == 33 && r.height == 44 ? 0 : 999;
          |}
          |""".stripMargin) shouldBe "0\n"
    }

    /** Twenty-four bytes of **mixed** pointer and integer, which is past the threshold and so comes
     * back through storage the caller supplied — a parameter the sysl signature does not mention
     * and which sits in front of every one it does.
     */
    "one past it, of mixed pointer and integer" in {
      cDrives(
        """int probe_ask( Delegate* d )
          |{
          |	Str l = d->label( d );
          |
          |	return l.length == 7 && l.text == NULL && l.dtor == NULL ? 0 : 999;
          |}
          |""".stripMargin) shouldBe "0\n"
    }

    /** Thirty-two bytes of plain `int`. Same memory case as the one above and a different reading
     * of it, since nothing in it is a pointer and every field has to be checked to see a partial
     * copy.
     */
    "one past it, of nothing but integers" in {
      cDrives(
        """int probe_ask( Delegate* d )
          |{
          |	Stamp s = d->stamp( d );
          |
          |	return s.year == 2026 && s.month == 8 && s.day == 29 && s.hour == 13 &&
          |	       s.minute == 5 && s.second == 7 && s.milli == 250 && s.micro == 61 ? 0 : 999;
          |}
          |""".stripMargin) shouldBe "0\n"
    }
  }

  "an aggregate C passes by value into a sysl callback through a struct member arrives whole" - {

    "a small one, beside the receiver" in {
      cDrives(
        """int probe_ask( Delegate* d )
          |{
          |	return d->on_point( d, (Point){ 6, 7 } ) == 6007 ? 0 : 999;
          |}
          |""".stripMargin) shouldBe "0\n"
    }

    /** Large enough to cross in memory, which is `byval` on the conventions that ask the caller for
     * a copy the callee owns and a plain pointer on the ones that do not.
     */
    "one too large for registers" in {
      cDrives(
        """int probe_ask( Delegate* d )
          |{
          |	Stamp s = { 1, 2, 3, 4, 5, 6, 7, 8 };
          |
          |	return d->on_stamp( d, s ) == 1008 ? 0 : 999;
          |}
          |""".stripMargin) shouldBe "0\n"
    }
  }

  "the struct a callback table is reached through agrees between the two compilers" - {

    /** The member is not the struct's own — it is a member of the `Base` embedded as its first
     * field, and it takes that `Base` rather than the object. Every call a refcounted C API makes
     * to release something has this shape, and it is only correct if both compilers put the
     * embedded struct at the same offset and give it the same size.
     */
    "a call through a member of an embedded struct reaches the right function" in {
      cDrives(
        """int probe_ask( Delegate* d )
          |{
          |	return d->base.release( &d->base ) == 4242 ? 0 : 999;
          |}
          |""".stripMargin) shouldBe "0\n"
    }

    /** A struct of function pointers has a layout there is little to get wrong, and the size is
     * what a C API checks to decide whether the struct it was handed is the one this version of the
     * library expects — CEF refuses one whose `size` disagrees. So sysl's `sizeof` has to be C's,
     * and the value sysl wrote into the field has to be the one C reads back.
     */
    "the size sysl wrote into the base is the size C measures" in {
      cDrives(
        """int probe_ask( Delegate* d )
          |{
          |	return d->base.size == sizeof(Delegate) ? 0 : 999;
          |}
          |""".stripMargin) shouldBe "0\n"
    }

    /** Asked of the C compiler directly rather than through a call, so a disagreement is reported
     * as a size rather than as a wrong answer from a function.
     */
    "and every shape in the table measures the same in both" in {
      syslDrives(
        """extern "probe_sizes" probe_sizes(out: *usize) -> unit
          |
          |check() -> int
          |    var cs: [4]usize = [0usize, 0usize, 0usize, 0usize]
          |
          |    probe_sizes(&cs[0])
          |
          |    var bad = 0
          |
          |    if cs[0] != sizeof(Base) then bad = bad + 1
          |    if cs[1] != sizeof(Delegate) then bad = bad + 2
          |    if cs[2] != sizeof(Str) then bad = bad + 4
          |    if cs[3] != sizeof(Stamp) then bad = bad + 8
          |
          |    bad
          |""".stripMargin) shouldBe "0\n"
    }
  }

  "an aggregate crossing the other way, at a function pointer C filled in" - {

    /** The direction a binding spends most of its time in: the library hands over a table and every
     * call the program makes goes through one of its members. Both register sizes and the memory
     * case in one call, since a table is used whole rather than a member at a time.
     */
    "one a C function pointer returns, called from sysl" in {
      syslDrives(
        """check() -> int
          |    var t = probe_table()
          |    var r = (t.bounds)(t)
          |    var e = (t.extent)(t)
          |    var l = (t.label)(t)
          |
          |    var bad = 0
          |
          |    if r.x != 101 || r.y != 102 || r.width != 103 || r.height != 104 then bad = bad + 1
          |    if e.width != 201 || e.height != 202 then bad = bad + 2
          |    if l.length != 31usize then bad = bad + 4
          |
          |    bad
          |""".stripMargin) shouldBe "0\n"
    }

    "one sysl passes by value through a C function pointer" in {
      syslDrives(
        """check() -> int
          |    var t = probe_table()
          |
          |    var bad = 0
          |
          |    if (t.on_point)(t, Point(6, 7)) != 6007 then bad = bad + 1
          |    if (t.on_stamp)(t, Stamp(1, 2, 3, 4, 5, 6, 7, 8)) != 1008 then bad = bad + 2
          |
          |    bad
          |""".stripMargin) shouldBe "0\n"
    }

    /** A member of the embedded base, in the direction where C laid the struct out — the mirror of
     * the release above, and what pins the offset from the side that did not choose it.
     */
    "one through a member of the base C embedded" in {
      syslDrives(
        """check() -> int
          |    var t = probe_table()
          |    var b = &t.base
          |
          |    if (b.release)(b) == 7 && b.size == sizeof(Table) then 0 else 999
          |""".stripMargin) shouldBe "0\n"
    }
  }

  /** Without `@export` the address would be of the definition sysl emitted its own way, and C would
   * read the aggregate out of the registers *its* convention names. Refusing is what keeps the
   * whole suite above meaningful: the correct form is the only one that compiles, so a binding
   * cannot reach the wrong one by leaving an annotation off.
   */
  "a callback returning an aggregate has no address without '@export'" in {
    err("""struct Size
          |    width: i32
          |    height: i32
          |
          |struct Delegate
          |    preferred_size: *extern(*Delegate) -> Size
          |
          |plain(d: *Delegate) -> Size = Size(1280, 720)
          |
          |var v: Delegate = Delegate(&plain)
          |
          |print(((v.preferred_size)(&v)).width)
          |""".stripMargin) should include("an aggregate crosses to C in whichever registers")
  }
}
