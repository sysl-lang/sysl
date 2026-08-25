package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** A function's address, and a call through one — `reference/ffi.md § A function's address`.
 *
 * The claim this file exists to hold is that **both directions of the C callback seam work**, and
 * both are checked by running a real libc interface rather than by reading IR: `qsort` calls a sysl
 * comparison back and the array comes out sorted, and `dlsym` hands an address in that is then
 * called. Neither is provable by inspection — a wrong address compiles, and a wrong convention links.
 *
 * The refusals are the other half, and each names a reason rather than a shape. An address that is
 * not an address of what its type says would be worse than no address at all, because nothing
 * downstream could notice.
 */
class FuncAddressTests extends AnyFreeSpec with RunSupport with CodegenSupport {

  "a sysl function's address, given to C" - {
    // The interface the whole feature exists for. `qsort` calls back N times through the pointer it
    // was handed, and a sorted array is a result no accident produces.
    "sorts an array through a comparison qsort calls back" in {
      run("""extern qsort(base: *u8, n: usize, size: usize, cmp: *extern(*u8, *u8) -> i32)
            |
            |compare(a: *u8, b: *u8) -> i32
            |    var pa: *i32 = ptr_cast(a)
            |    var pb: *i32 = ptr_cast(b)
            |    *pa - *pb
            |
            |var xs = [30i32, 10i32, 20i32, 40i32]
            |
            |qsort(ptr_cast(&xs[0]), 4usize, 4usize, &compare)
            |
            |print(xs[0], xs[1], xs[2], xs[3])
            |""".stripMargin) shouldBe "10 20 30 40\n"
    }

    // The comparison decides the order, so reversing it must reverse the answer — which is what says
    // the callback is what sorted the array rather than the array having been in order.
    "and the reversed comparison reverses it, so the callback is what decided" in {
      run("""extern qsort(base: *u8, n: usize, size: usize, cmp: *extern(*u8, *u8) -> i32)
            |
            |descending(a: *u8, b: *u8) -> i32
            |    var pa: *i32 = ptr_cast(a)
            |    var pb: *i32 = ptr_cast(b)
            |    *pb - *pa
            |
            |var xs = [30i32, 10i32, 20i32, 40i32]
            |
            |qsort(ptr_cast(&xs[0]), 4usize, 4usize, &descending)
            |
            |print(xs[0], xs[1], xs[2], xs[3])
            |""".stripMargin) shouldBe "40 30 20 10\n"
    }

    // Two of them in one program, told apart only by which address was passed.
    "and two functions of one signature are told apart by the address" in {
      run("""extern qsort(base: *u8, n: usize, size: usize, cmp: *extern(*u8, *u8) -> i32)
            |
            |up(a: *u8, b: *u8) -> i32
            |    var pa: *i32 = ptr_cast(a)
            |    var pb: *i32 = ptr_cast(b)
            |    *pa - *pb
            |
            |down(a: *u8, b: *u8) -> i32
            |    var pa: *i32 = ptr_cast(a)
            |    var pb: *i32 = ptr_cast(b)
            |    *pb - *pa
            |
            |sorted(which: *extern(*u8, *u8) -> i32) -> i32
            |    var xs = [3i32, 1i32, 2i32]
            |    qsort(ptr_cast(&xs[0]), 3usize, 4usize, which)
            |    xs[0]
            |
            |print(sorted(&up), sorted(&down))
            |""".stripMargin) shouldBe "1 3\n"
    }

    // A function nothing calls is dropped from the program (`Reachability`). Taking its address has
    // to count as a use, or the address would be of a definition that is not there — a link failure
    // at best and a wild jump at worst.
    "keeps a function nothing calls, since the address is the only use" in {
      val out = ir("""extern qsort(base: *u8, n: usize, size: usize, cmp: *extern(*u8, *u8) -> i32)
                     |
                     |only_addressed(a: *u8, b: *u8) -> i32 = 0
                     |
                     |var xs = [2i32, 1i32]
                     |
                     |qsort(ptr_cast(&xs[0]), 2usize, 4usize, &only_addressed)
                     |print(xs[0])
                     |""".stripMargin)

      out should include("define i32 @only_addressed")
      out should include("@only_addressed")
    }

    // An `extern`'s address is the same word, and is what a program installing one C function as
    // another's callback passes.
    "and a C function's own address is one too" in {
      run("""extern abs(n: i32) -> i32
            |
            |var f: *extern(i32) -> i32 = &abs
            |
            |print(f(-5))
            |""".stripMargin) shouldBe "5\n"
    }
  }

  "an address C hands back" - {
    // The direction that was shut for the same reason: a `*u8` from `dlsym` was not callable, so the
    // whole dynamic-loading interface was declarable and useless.
    "is callable once it is read as a function pointer" in {
      run("""extern dlopen(path: *u8, mode: i32) -> *u8
            |extern dlsym(handle: *u8, name: *u8) -> *u8
            |
            |var handle = dlopen(null, 1i32)
            |var raw = dlsym(handle, c"abs")
            |var absolute: *extern(i32) -> i32 = ptr_cast(raw)
            |
            |print(absolute(-41), absolute(41))
            |""".stripMargin) shouldBe "41 41\n"
    }

    // A symbol that is not there gives a null pointer, which is the value C uses for "no callback"
    // everywhere else too — so it has to be writable and it has to compare.
    "and a symbol that is not there compares equal to null" in {
      run("""extern dlopen(path: *u8, mode: i32) -> *u8
            |extern dlsym(handle: *u8, name: *u8) -> *u8
            |
            |var handle = dlopen(null, 1i32)
            |var missing: *extern(i32) -> i32 = ptr_cast(dlsym(handle, c"no_such_symbol_anywhere"))
            |var present: *extern(i32) -> i32 = ptr_cast(dlsym(handle, c"abs"))
            |
            |print(missing == null, present == null, missing != present)
            |""".stripMargin) shouldBe "true false true\n"
    }

    "and the null callback is writable directly" in {
      run("""var none: *extern(i32) -> i32 = null
            |
            |print(none == null)
            |""".stripMargin) shouldBe "true\n"
    }
  }

  "where a function pointer may be kept" - {
    "in a struct field, called through the selection that reads it" in {
      run("""extern abs(n: i32) -> i32
            |
            |struct Handler
            |    name: string
            |    run: *extern(i32) -> i32
            |
            |var h = Handler("abs", &abs)
            |
            |print(h.name, (h.run)(-7))
            |""".stripMargin) shouldBe "abs 7\n"
    }

    "in an array of them, chosen by index" in {
      run("""double(n: i32) -> i32 = n * 2
            |negate(n: i32) -> i32 = 0i32 - n
            |
            |var table: [2]*extern(i32) -> i32 = [&double, &negate]
            |
            |print(table[0](21), table[1](21))
            |""".stripMargin) shouldBe "42 -21\n"
    }

    "as an ordinary parameter of a sysl function" in {
      run("""apply(f: *extern(i32) -> i32, x: i32) -> i32 = f(x)
            |
            |triple(n: i32) -> i32 = n * 3
            |
            |print(apply(&triple, 14))
            |""".stripMargin) shouldBe "42\n"
    }

    "as a result, so a program may choose one and hand it back" in {
      run("""double(n: i32) -> i32 = n * 2
            |negate(n: i32) -> i32 = 0i32 - n
            |
            |pick(up: bool) -> *extern(i32) -> i32 = if up then &double else &negate
            |
            |print(pick(true)(21), pick(false)(21))
            |""".stripMargin) shouldBe "42 -21\n"
    }

    // It is one word, which is the difference from a `*Fn` that the whole design turns on.
    "and it costs one word, where a callable trait object costs two" in {
      run("""print(sizeof(*extern(i32) -> i32), alignof(*extern(i32) -> i32))
            |""".stripMargin) shouldBe "8 8\n"
    }
  }

  "the shapes an address may have" - {
    "no parameters, and no result" in {
      run("""announce()
            |    print("called")
            |
            |var f: *extern() -> unit = &announce
            |
            |f()
            |""".stripMargin) shouldBe "called\n"
    }

    "a pointer in and a pointer out, which is what a thread body is" in {
      run("""identity(p: *u8) -> *u8 = p
            |
            |var f: *extern(*u8) -> *u8 = &identity
            |var n = 7
            |var back: *i32 = ptr_cast(f(ptr_cast(&n)))
            |
            |print(*back)
            |""".stripMargin) shouldBe "7\n"
    }

    "a float, which travels in its own registers" in {
      run("""scale(x: f64) -> f64 = x * 2.5
            |
            |var f: *extern(f64) -> f64 = &scale
            |
            |print(f(4.0))
            |""".stripMargin) shouldBe "10\n"
    }

    // A simple enum is its underlying integer and nothing else, so it crosses as one — where a data
    // enum, being a tag beside a union, does not.
    "a simple enum, which is an integer" in {
      run("""enum Level
            |    Low
            |    High
            |
            |rank(l: Level) -> i32
            |    l match
            |        Low  -> 0i32
            |        High -> 1i32
            |
            |var f: *extern(Level) -> i32 = &rank
            |
            |print(f(Level.High), f(Level.Low))
            |""".stripMargin) shouldBe "1 0\n"
    }
  }

  // This used to be refused, and the refusal was too broad: a generic function is a body per set of
  // type arguments, but the *expected type* settles them, and an instantiation is one body like any
  // other. `GenericFuncAddressTests` holds the rest; what is kept here is the case this suite has
  // always been about, now answering rather than refusing.
  "a generic function's address, at the instantiation the type asks for" in {
    run("""first[T](a: T, b: T) -> T = a
          |
          |val f: *extern(i32, i32) -> i32 = &first
          |print(f(7i32, 9i32))
          |""".stripMargin) shouldBe "7\n"
  }

  "what is refused, and why" - {
    // What survives of the old refusal: with nothing to read the arguments off, there is still no
    // one body to name.
    "a generic function with nothing to say which copy" in {
      val e = err("""first[T](a: T, b: T) -> T = a
                    |
                    |var f = &first
                    |""".stripMargin)

      e should include("'first' is generic")
      e should include("nothing here says what they are")
    }

    // A `...` is read relative to the last named argument, and a signature that fixed the tail would
    // not be a variadic one.
    "a variadic function, whose tail no signature states" in {
      val e = err("""total(first: int, ...) -> int = first
                    |
                    |var f: *extern(int) -> int = &total
                    |""".stripMargin)

      e should include("'total' is variadic")
      e should include("fixes the arguments a call passes")
    }

    // Its environment is the frame it was declared in, which is exactly what an address cannot carry.
    "a nested function, whose environment is a frame" in {
      val e = err("""outer(n: int) -> int
                    |    inner(k: int) -> int = k + n
                    |    var f: *extern(int) -> int = &inner
                    |    inner(1)
                    |""".stripMargin)

      e should include("'inner' is a nested function")
      e should include("no address to take")
    }

    // A test is dropped from every build but `sysl test`, so its address would be of a definition
    // the program does not have — the same refusal a call to one gets, for the same reason.
    "a '@test' function, which no ordinary build contains" in {
      val e = err("""@test
                    |t()
                    |    print(1)
                    |
                    |var f: *extern() -> unit = &t
                    |""".stripMargin)

      e should include("'t' is a '@test' function")
      e should include("its address would be of a definition the program does not have")
    }

    /** An aggregate crosses to C in whichever registers that machine's convention names, which is
      * not where a sysl definition put it (`targets.md`, and the ABI work `CAbi` holds). So the
      * address of such a function would be an address C cannot call correctly — and nothing
      * downstream could notice, which is why it is refused at the `&` rather than passed on.
      *
      * The refusal is by **shape** rather than by asking the target's classification, so a program
      * accepted for one machine is accepted for every machine.
      */
    "a signature carrying an aggregate, which the two conventions disagree about" in {
      val param = err("""struct Point
                        |    x: i32
                        |    y: i32
                        |
                        |sum(p: Point) -> i32 = p.x + p.y
                        |
                        |var f: *extern(Point) -> i32 = &sum
                        |""".stripMargin)

      param should include("the 1st parameter of 'sum'")
      param should include("an aggregate")

      val result = err("""struct Point
                         |    x: i32
                         |    y: i32
                         |
                         |origin() -> Point = Point(0i32, 0i32)
                         |
                         |var f: *extern() -> Point = &origin
                         |""".stripMargin)

      result should include("the result of 'origin'")
      result should include("an aggregate")
    }

    /** **But not an `extern`, and that was the whole of 0136.** The refusal above is a statement
      * about code *this compiler emitted* — sysl put the aggregate where its own convention says, so
      * the address would be of a function C cannot call correctly. None of that can be true of a C
      * function: sysl neither compiled it nor chose its convention, and its type is what the
      * declaration transcribed from the header. Refusing one made every C callback a binding wants
      * to register unnameable, which is most of what a callback-shaped library is.
      */
    "and an extern is past the question, since its convention was never sysl's to choose" in {
      val out = ir("""struct Point
                     |    x: i32
                     |    y: i32
                     |
                     |extern "c_sum" c_sum(p: Point) -> i32
                     |extern "c_take" c_take(f: *extern(Point) -> i32) -> i32
                     |
                     |print(c_take(&c_sum))
                     |""".stripMargin)

      out should include("@c_sum")
    }

    /** So is a function carrying `@export`, whose address is its **thunk's** — a definition that
      * genuinely has the convention this rule is written about (`ExportThunk`). `reference/ffi.md §
      * @export` said that all along; what changed with 0137 is that it became true.
      */
    "and so is an exported function, whose address is the C-convention entry" in {
      val out = ir("""struct Point
                     |    x: i32
                     |    y: i32
                     |
                     |@export("c_sum")
                     |sum(p: Point) -> i32 = p.x + p.y
                     |
                     |extern "c_take" c_take(f: *extern(Point) -> i32) -> i32
                     |
                     |print(c_take(&sum))
                     |""".stripMargin)

      // The thunk, not the definition — which is the whole point of the address being admitted.
      out should include("define i32 @c_sum(")
      out should include("@c_sum)")
      out should not include "@$sum)"
    }

    // A string is three words in sysl and one in C, so it is an aggregate here for the same reason a
    // struct is — and saying so is what stops a program handing C a shape it has no reading for.
    "a string, which is a view rather than the address C would read" in {
      err("""greet(s: string) -> i32 = 0i32
            |
            |var f: *extern(string) -> i32 = &greet
            |""".stripMargin) should include("an aggregate")
    }

    "a call through one at the wrong arity" in {
      val e = err("""double(n: i32) -> i32 = n * 2
                    |
                    |var f: *extern(i32) -> i32 = &double
                    |
                    |print(f(1i32, 2i32))
                    |""".stripMargin)

      e should include("is called with 1 argument")
      e should include("2 were given")
    }

    // A function pointer's parameters have no names — the type is the only thing describing them —
    // so a mismatch names the position, and the callee it names is the type itself.
    "a call through one at the wrong argument type" in {
      val e = err("""double(n: i32) -> i32 = n * 2
                    |
                    |var f: *extern(i32) -> i32 = &double
                    |
                    |print(f("no"))
                    |""".stripMargin)

      e should include("the 1st argument of *extern(int) -> int")
      e should include("but string was given")
    }

    // A closure carries an environment, and an address is one word with nowhere to put it.
    "a closure, which is a value rather than a declaration" in {
      err("""var c: &Fn(int) -> int = x -> x + 1
            |var f: *extern(int) -> int = &c
            |""".stripMargin) should include("*extern")
    }

    // The signature is the whole of what makes the call safe to emit, so leaving it off is refused
    // with the spelling rather than with a parse error about the token after it.
    "the spelling with no signature on it" in {
      val e = err("""var f: *extern = null
                    |""".stripMargin)

      e should include("'*extern' is a foreign function's address")
      e should include("'*extern(int) -> int'")
    }
  }

  /** The claims `reference/ffi.md § A function's address` makes in prose, each run rather than read. */
  "what the chapter claims" - {
    /** §6a: *"A call through one goes out under C's convention … the same lowering an `extern` call
      * gets (§1), aggregates and all."* That is the asymmetry the section turns on — **taking** the
      * address of a function with an aggregate in its signature is refused, and **calling** through
      * one is not, because the call site is where the coercion happens and it already knows how.
      *
      * `div` returns a two-integer struct, which is exactly the shape the ABI work found was being
      * read out of the wrong registers — so a right answer here is a real check and not a shrug.
      */
    "a call through one coerces an aggregate, where taking the address of one is refused" in {
      run("""struct div_t
            |    quot: i32
            |    rem: i32
            |
            |extern dlopen(path: *u8, mode: i32) -> *u8
            |extern dlsym(handle: *u8, name: *u8) -> *u8
            |
            |var d: *extern(i32, i32) -> div_t = ptr_cast(dlsym(dlopen(null, 1i32), c"div"))
            |var r = d(7i32, 2i32)
            |
            |print(r.quot, r.rem)
            |""".stripMargin) shouldBe "3 1\n"
    }

    /** §6a: *"The test is made by **shape** rather than by asking the target's classification, so a
      * program accepted for one machine is accepted for every machine."* A rule that consulted
      * `CAbi` would answer differently per convention — an aggregate that happens to travel in one
      * register on one target and not another — and a program would then compile for one machine and
      * not the next. Checked against four targets whose classifications genuinely differ.
      */
    "an address is accepted or refused the same way on every target" in {
      val ok = """double(n: i32) -> i32 = n * 2
                 |var f: *extern(i32) -> i32 = &double
                 |print(f(21))
                 |""".stripMargin

      for t <- Target.all do irFor(t, ok) should include("@double")

      val no = """struct Point
                 |    x: i32
                 |    y: i32
                 |sum(p: Point) -> i32 = p.x + p.y
                 |var f: *extern(Point) -> i32 = &sum
                 |""".stripMargin

      for t <- Target.all do
        Compiler.compileToLlvm(no, "<input>", t) match
          case Left(e)  => e should include("an aggregate")
          case Right(_) => fail(s"the aggregate was accepted for ${t.name}")
    }

    // §6a: *"`ptr_cast` reaches between an address of code and an address of bytes"* — the direction
    // the dlsym tests do not cover, which is a callback handed back to a C interface storing them as
    // `void *`.
    "an address of code goes back out as an address of bytes" in {
      run("""extern qsort(base: *u8, n: usize, size: usize, cmp: *extern(*u8, *u8) -> i32)
            |
            |compare(a: *u8, b: *u8) -> i32
            |    var pa: *i32 = ptr_cast(a)
            |    var pb: *i32 = ptr_cast(b)
            |    *pa - *pb
            |
            |var as_bytes: *u8 = ptr_cast(&compare)
            |var back: *extern(*u8, *u8) -> i32 = ptr_cast(as_bytes)
            |var xs = [2i32, 1i32]
            |
            |qsort(ptr_cast(&xs[0]), 2usize, 4usize, back)
            |
            |print(xs[0], xs[1], as_bytes == null)
            |""".stripMargin) shouldBe "1 2 false\n"
    }

    // §6a: *"two compare by address so a program can ask whether one is installed"* — and the
    // address of one function is not the address of another.
    "two addresses of one function are equal, and of two functions are not" in {
      run("""a(n: i32) -> i32 = n
            |b(n: i32) -> i32 = n
            |
            |print(&a == &a, &a == &b)
            |""".stripMargin) shouldBe "true false\n"
    }
  }

  /** The inputs that break code like this: the second occurrence, the empty case, and two internal
    * notions of identity that disagree.
    */
  "the edges" - {
    // The mangled name carries the signature, so a generic instantiated at two function-pointer
    // types must get two bodies. Sharing one would be a miscompile that no diagnostic could reach.
    "a generic at two function-pointer types gets two instantiations" in {
      run("""struct Box[T]
            |    value: T
            |
            |unwrap[T](b: Box[T]) -> T = b.value
            |
            |narrow(n: i32) -> i32 = n * 2
            |wide(n: i64) -> i64 = n * 3i64
            |
            |var a = Box(&narrow)
            |var b = Box(&wide)
            |
            |print(unwrap(a)(21i32), unwrap(b)(14i64))
            |""".stripMargin) shouldBe "42 42\n"
    }

    // A mangled name that dropped the signature would collide here, and the symbol would be illegal
    // besides — `*extern(int) -> int` has characters an LLVM name cannot hold.
    "and the mangled name of one is a legal symbol" in {
      val out = ir("""struct Box[T]
                     |    value: T
                     |
                     |double(n: i32) -> i32 = n * 2
                     |
                     |var b = Box(&double)
                     |print(b.value(21))
                     |""".stripMargin)

      out should include("cfn1")
      out should not include "*extern"
    }

    // A `unit` parameter is zero-sized and dropped from the emitted signature (`reference/declarations.md § Functions`), so the
    // argument is evaluated for its effect and the ones after it shift up. The positions a
    // function-pointer call checks against must be the written ones, not the emitted ones.
    "a zero-sized parameter is dropped from the call but not from the arity" in {
      run("""second(a: unit, b: i32) -> i32 = b
            |
            |var f: *extern(unit, i32) -> i32 = &second
            |
            |print(f((), 7i32))
            |""".stripMargin) shouldBe "7\n"

      err("""second(a: unit, b: i32) -> i32 = b
            |var f: *extern(unit, i32) -> i32 = &second
            |print(f(7i32))
            |""".stripMargin) should include("is called with 2 arguments")
    }

    // `never` says the callee does not come back, and a call through a pointer has to end the block
    // exactly as a direct one does — otherwise the code after it is emitted and LLVM rejects it.
    "a result of 'never' ends the block" in {
      run("""extern exit(code: i32) -> never
            |
            |var stop: *extern(i32) -> never = &exit
            |
            |print("before")
            |stop(0i32)
            |print("after")
            |""".stripMargin) shouldBe "before\n"
    }

    // One returning another, which is what a C interface installing a handler and giving back the
    // previous one does — `signal` is exactly this shape.
    "one whose result is another one" in {
      run("""double(n: i32) -> i32 = n * 2
            |
            |chooser() -> *extern(i32) -> i32 = &double
            |
            |var pick: *extern() -> *extern(i32) -> i32 = &chooser
            |
            |print(pick()(21))
            |""".stripMargin) shouldBe "42\n"
    }

    // A function that takes its own address, which is the shape a re-arming signal handler has.
    "a function that takes its own address" in {
      run("""extern qsort(base: *u8, n: usize, size: usize, cmp: *extern(*u8, *u8) -> i32)
            |
            |compare(a: *u8, b: *u8) -> i32
            |    var pa: *i32 = ptr_cast(a)
            |    var pb: *i32 = ptr_cast(b)
            |    var mine: *extern(*u8, *u8) -> i32 = &compare
            |    if mine == null then 0i32 else *pa - *pb
            |
            |var xs = [2i32, 1i32]
            |
            |qsort(ptr_cast(&xs[0]), 2usize, 4usize, &compare)
            |print(xs[0], xs[1])
            |""".stripMargin) shouldBe "1 2\n"
    }

    // Reachability is transitive through an address: the addressed function is kept, and so is
    // whatever *it* reaches — including another function reached only by its address.
    "a function reached only through an address that is itself only addressed" in {
      val out = ir("""extern qsort(base: *u8, n: usize, size: usize, cmp: *extern(*u8, *u8) -> i32)
                     |
                     |inner(n: i32) -> i32 = n
                     |
                     |compare(a: *u8, b: *u8) -> i32
                     |    var f: *extern(i32) -> i32 = &inner
                     |    f(0i32)
                     |
                     |var xs = [2i32, 1i32]
                     |qsort(ptr_cast(&xs[0]), 2usize, 4usize, &compare)
                     |print(xs[0])
                     |""".stripMargin)

      out should include("define i32 @compare")
      out should include("define i32 @inner")
    }

    "one held in a module-level val" in {
      run("""double(n: i32) -> i32 = n * 2
            |
            |val doubler: *extern(i32) -> i32 = &double
            |
            |print(doubler(21))
            |""".stripMargin) shouldBe "42\n"
    }

    /** **A signature is named once, with a `type` alias**, which it could not be until the deferral
      * `16` carried was closed. This test is the tripwire that was left here for the day it arrived:
      * it used to assert that both spellings were refused, on the grounds that `type` declared a
      * constrained subtype and a subtype's base must be a scalar. A transparent alias declares no
      * type at all, so neither restriction reaches it.
      *
      * The cost this removes is real and it is what a binding pays: `signal` below mentions its
      * handler type three times, and every one of them could now be the alias's name.
      * `TypeAliasTests` is where the feature itself is pinned; both halves stay asserted here so that
      * a regression in *this* direction — a pointer or a signature alias being refused again —
      * fails beside the code that wants it.
      */
    "is named once, with an alias" in {
      ir("""type Comparison = *extern(*u8, *u8) -> i32
           |
           |compare(a: *u8, b: *u8) -> i32 = i32(a[0]) - i32(b[0])
           |
           |call(f: Comparison, a: *u8, b: *u8) -> i32 = f(a, b)
           |
           |var xs = [9u8, 4u8]
           |
           |print(str(call(&compare, &xs[0], &xs[1])))
           |""".stripMargin) should include("@compare")

      ir("""type Handle = *u8
           |
           |first(h: Handle) -> u8 = h[0]
           |
           |var bytes = [7u8]
           |
           |print(str(first(&bytes[0])))
           |""".stripMargin) should not be empty
    }

    // A slice of them, which is the table shape a dispatch loop reads.
    /** An allocator-free module (`reference/modules.md § Capabilities are a module property`,
      * `capabilities.md`) is held to what its calls **reach**, and taking an address is a way of
      * reaching. Refusing it is conservative in the right direction: the address is handed to
      * something this compiler cannot see, so if it were not a use here it would be no use
      * anywhere.
      *
      * The allocator-free half is what makes the test discriminating — a rule that refused every
      * address would pass the second half of this and mean nothing.
      */
    "an address in a 'no alloc' module reaches what the function reaches" in {
      irOf("thing/a.sysl" ->
        ("module thing\n@no_alloc\n\nplain(n: i32) -> i32 = n * 2\n" +
          "addr() -> *extern(i32) -> i32 = &plain\n"),
        "main.sysl" -> "print(thing.addr()(21))") should include("define")

      errOf("thing/a.sysl" ->
        ("module thing\n@no_alloc\n\nboxes(n: int) -> &int = n\n" +
          "addr() -> *extern(int) -> &int = &boxes\n"),
        "main.sysl" -> "print(*thing.addr()(1))") should
        include("an allocator-free module may only call what is allocator-free itself")
    }

    "a slice of them, walked" in {
      run("""double(n: i32) -> i32 = n * 2
            |negate(n: i32) -> i32 = 0i32 - n
            |
            |var table: &[2]*extern(i32) -> i32 = [&double, &negate]
            |
            |sum(fs: []*extern(i32) -> i32, x: i32) -> i32
            |    var t = 0i32
            |    for f in fs do t += f(x)
            |    t
            |
            |print(sum(table[..], 10i32))
            |""".stripMargin) shouldBe "10\n"
    }
  }

  "what it is not" - {
    /** `*Fn(A) -> R` is an unowned trait object over a callable: two words, a method table beside
      * the value, and an environment at the other end. It stays exactly what it was — this is the
      * reason a C function pointer needed a spelling of its own rather than a third mode over the
      * call trait, and a test that stops holding would mean one of them had eaten the other.
      */
    "a '*Fn', which is a trait object over a callable and is unchanged" in {
      run("""struct Doubler
            |    k: int
            |
            |impl Fn(int) -> int for Doubler
            |    call(*self, a: int) -> int = a * self.k
            |
            |var d = Doubler(3)
            |var raw: *Fn(int) -> int = &d
            |
            |print(raw(5), sizeof(*Fn(int) -> int))
            |""".stripMargin) shouldBe "15 16\n"
    }

    // The two are different types, so neither stands where the other is asked for.
    "and the two do not stand in for each other" in {
      err("""double(n: int) -> int = n * 2
            |
            |var f: *Fn(int) -> int = &double
            |""".stripMargin) should not be empty
    }

    // A bare function name still means the capture-free closure it always meant
    // (`reference/expressions.md § Closures`), so the `&` is what tells the two readings apart and
    // nothing was quietly rerouted.
    "and a bare name is still the capture-free closure it was" in {
      run("""square(n: int) -> int = n * n
            |
            |apply(f: int -> int, x: int) -> int = f(x)
            |
            |print(apply(square, 7))
            |""".stripMargin) shouldBe "49\n"
    }

    // Where nothing asks for a callable, a bare name is still the mistake it was — and the message
    // now has an address to point at.
    "and a bare name where nothing wants a callable still says so" in {
      err("""square(n: int) -> int = n * n
            |print(square)
            |""".stripMargin) should include("'square' is a function")
    }
  }

  /** A function named **through its module** reaches an address exactly as the imported spelling
    * does.
    *
    * This is `0104`'s shape met at `&` instead of in a constant expression: a qualified name is a
    * chain of field reads after parsing, and every case above matched a bare `Ident`, so the same
    * declaration was addressable when imported and not when named through its module. A function is
    * not a place, so it never reached the walk that folds a module path into the name — which is the
    * one thing that would have rewritten the chain.
    *
    * The tests assert the two spellings **agree** rather than asserting an address, which is the only
    * claim worth making here: an address is a number nobody can predict, and two spellings of one
    * declaration disagreeing is precisely the defect.
    */
  "a function named through its module" - {
    "reaches an address, and it is the address the bare spelling gives" in {
      runIn(
        ("shapes", "shapes.sysl", "module shapes\nless(a: int, b: int) -> bool = a < b\n"),
        ("", "main.sysl",
          """import shapes
            |import shapes.less
            |
            |val qualified: *extern(int, int) -> bool = &shapes.less
            |val bare: *extern(int, int) -> bool = &less
            |
            |print(qualified == bare, qualified(2, 5), qualified(5, 2))
            |""".stripMargin),
      ) shouldBe "true true false\n"
    }

    // `&f[T]` is the one position where type arguments are written, and it had the same `Ident`-only
    // reading — so a binding's trampoline, which is what that form exists for, could not be named
    // through the module it lives in.
    "including an instantiation with its type argument written" in {
      runIn(
        ("shapes", "shapes.sysl", "module shapes\npick[T](a: T, b: T) -> T = a\n"),
        ("", "main.sysl",
          """import shapes
            |
            |val g: *extern(i32, i32) -> i32 = &shapes.pick[i32]
            |
            |print(g(7, 9))
            |""".stripMargin),
      ) shouldBe "7\n"
    }

    "and one with more than one type argument written" in {
      runIn(
        ("shapes", "shapes.sysl", "module shapes\neither[A, B](a: A, b: B) -> A = a\n"),
        ("", "main.sysl",
          """import shapes
            |
            |val g: *extern(i32, bool) -> i32 = &shapes.either[i32, bool]
            |
            |print(g(4, true))
            |""".stripMargin),
      ) shouldBe "4\n"
    }

    /** A path deeper than one segment is the same question asked twice, and it is worth its own case
      * because flattening a chain of field reads is where a fix could stop one level short — which is
      * the reason `0104` carries the matching case.
      */
    "however deep the module path is" in {
      runIn(
        ("a.b", "b.sysl", "module a.b\nless(x: int, y: int) -> bool = x < y\n"),
        ("", "main.sysl",
          """import a.b
            |
            |val f: *extern(int, int) -> bool = &a.b.less
            |
            |print(f(1, 2))
            |""".stripMargin),
      ) shouldBe "true\n"
    }

    /** The other half, and the rule this shares with every other qualified form: **a local binding
      * shadows a module name** (`reference/modules.md § Imports`). A head bound to a value makes
      * the chain a field read and nothing else, so reading it as a module path would take an
      * address of the wrong thing entirely — and silently, since both readings produce a pointer.
      */
    "while a local of the module's name is still a field read" in {
      runIn(
        ("shapes", "shapes.sysl", "module shapes\nless(a: int, b: int) -> bool = a < b\n"),
        ("", "main.sysl",
          """import shapes
            |
            |struct Holder
            |    less: int
            |
            |var shapes = Holder(42)
            |val p: *int = &shapes.less
            |
            |print(*p)
            |""".stripMargin),
      ) shouldBe "42\n"
    }

    /** The refusal that sent this card in: the message named the declaration by the **key** the table
      * holds it under, which carries the module separator — so a reader who wrote `shapes.less` was
      * shown `shapes$less` and told to write `'&shapes$less'`, a spelling nothing in sysl may contain.
      *
      * `qn` is where a key becomes the path a reader would type, and this message is now one of the
      * messages that goes through it. The advice it gives is true as well as typable, which is the
      * other half of this card's fix: `&shapes.less` is what the case above compiles.
      */
    "and a refusal quotes a spelling the reader can actually type" in {
      val e = errIn(
        ("shapes", "shapes.sysl", "module shapes\nless(a: int, b: int) -> bool = a < b\n"),
        ("", "main.sysl", "import shapes\nval x: int = shapes.less\n"),
      )

      e should include("'shapes.less' is a function")
      e should include("'&shapes.less'")
      e should not include "shapes$less"
    }
  }
}
