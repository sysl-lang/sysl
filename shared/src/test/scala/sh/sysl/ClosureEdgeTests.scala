package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** The exhaustive pass over closures (`12 §5`–`§8`): every kind of thing that can be captured, every
 * place a captured name can have come from, every position a callable can stand in, and the way each
 * of those goes wrong.
 *
 * What earns a test here is a case where the answer could plausibly differ from the one beside it —
 * a type whose capture costs a retain rather than a copy, a binding form the free-variable walk has
 * to know about, a position where the type is written rather than inferred.
 */
class ClosureEdgeTests extends AnyFreeSpec with RunSupport with CodegenSupport {

  /** A one-argument caller, which most of these go through. */
  private val apply = "apply(f: int -> int, x: int) -> int = f(x)\n"

  "every kind of value can be captured" - {
    "a narrow integer, which is copied in at its own width" in {
      run(apply + """var b: u8 = 7
                    |
                    |print(apply(k -> k + int(b), 0))
                    |""".stripMargin) shouldBe "7\n"
    }

    "a wide integer" in {
      run(apply + """var big: i64 = 100i64
                    |
                    |print(apply(k -> k + int(big), 0))
                    |""".stripMargin) shouldBe "100\n"
    }

    "a float" in {
      run("""var fl = 1.5
            |var f: &Fn(int) -> string = k -> str(fl)
            |
            |print(f(0))
            |""".stripMargin) shouldBe "1.5\n"
    }

    "a bool and a char" in {
      run("""var bo = true
            |var ch = 'A'
            |var f: &Fn(int) -> string = k -> if bo then string(ch) else "no"
            |
            |print(f(0))
            |""".stripMargin) shouldBe "A\n"
    }

    "a string, which takes a share of the buffer rather than copying the bytes" in {
      run("""var s = "hi"
            |var f: &Fn(int) -> string = k -> s + "!"
            |
            |print(f(0))
            |""".stripMargin) shouldBe "hi!\n"
    }

    "a fixed array, which is a value and is copied whole" in {
      run(apply + """var arr = [1, 2, 3]
                    |
                    |print(apply(k -> arr[1], 0))
                    |""".stripMargin) shouldBe "2\n"
    }

    "a slice, which views what it viewed" in {
      run(apply + """var arr = [1, 2, 3]
                    |var sl = arr[..]
                    |
                    |print(apply(k -> sl[2], 0))
                    |""".stripMargin) shouldBe "3\n"
    }

    "a struct by value" in {
      run(apply + """struct P
                    |    x: int
                    |    y: int
                    |
                    |var st = P(3, 4)
                    |
                    |print(apply(k -> st.x + st.y, 0))
                    |""".stripMargin) shouldBe "7\n"
    }

    "a counted reference, which is retained" in {
      run(apply + """struct P
                    |    x: int
                    |    y: int
                    |
                    |var rf: &P = P(5, 6)
                    |
                    |print(apply(k -> rf.x + rf.y, 0))
                    |""".stripMargin) shouldBe "11\n"
    }

    "a weak reference, which is not" in {
      run("""struct P
            |    x: int
            |
            |var rf: &P = P(5)
            |var wk: weak P = rf
            |var f: &Fn(int) -> int = k -> wk.get() match
            |    Some(p) -> p.x
            |    None -> -1
            |
            |print(f(0))
            |""".stripMargin) shouldBe "5\n"
    }

    "a raw pointer, which is the unsafe tier and is unchanged by being captured" in {
      run(apply + """var arr = [1u8, 2u8, 3u8]
                    |var pt = &arr[0]
                    |
                    |print(apply(k -> int(pt[1]), 0))
                    |""".stripMargin) shouldBe "2\n"
    }

    "a simple enum and a data enum" in {
      run("""enum Color: u8
            |    Red = 1
            |    Blue = 2
            |
            |enum Shape
            |    Circle(r: int)
            |    Nothing
            |
            |var col = Color.Blue
            |var sh = Circle(9)
            |var f: &Fn(int) -> int = k -> sh match
            |    Circle(r) -> r + int(u8(col))
            |    Nothing -> 0
            |
            |print(f(0))
            |""".stripMargin) shouldBe "11\n"
    }

    "a tuple" in {
      run(apply + """var tp = (1, "two")
                    |
                    |print(apply(k -> tp.0, 0))
                    |""".stripMargin) shouldBe "1\n"
    }

    "another callable, so a closure may capture a closure" in {
      run("""var inner: &Fn(int) -> int = k -> k * 2
            |var outer: &Fn(int) -> int = k -> inner(k) + 1
            |
            |print(outer(5))
            |""".stripMargin) shouldBe "11\n"
    }

    "several at once, in the order the body first names them" in {
      val out = ir(apply + """var a = 1
                             |var b = 2
                             |var c = 3
                             |
                             |print(apply(k -> c + a, 0))
                             |""".stripMargin)

      // `b` is never named, so it is not a field; `c` comes first because it is named first.
      envs(out) shouldBe List("i32, i32")
    }
  }

  "every place a captured name can come from" - {
    "the enclosing function's parameter" in {
      run(apply + """outer(n: int) -> int = apply(k -> k + n, 1)
                    |
                    |print(outer(10))
                    |""".stripMargin) shouldBe "11\n"
    }

    "a local 'var'" in {
      run(apply + """var n = 10
                    |
                    |print(apply(k -> k + n, 1))
                    |""".stripMargin) shouldBe "11\n"
    }

    "a local 'val', which stays written-once inside the closure" in {
      err("""var f: &Fn(int) -> int = k ->
            |    val n = 1
            |    n = 2
            |    k
            |""".stripMargin) should include("a 'val' is written once")
    }

    "a 'for' loop variable" in {
      run(apply + """var total = 0
                    |
                    |for i in 0..<3 do total += apply(k -> k + i, 0)
                    |
                    |print(total)
                    |""".stripMargin) shouldBe "3\n"
    }

    "a match-arm binding" in {
      run(apply + """enum Shape
                    |    Circle(r: int)
                    |    Square(s: int)
                    |
                    |var sh = Circle(3)
                    |
                    |sh match
                    |    Circle(r) -> print(apply(k -> k + r, 1))
                    |    Square(s) -> print(s)
                    |""".stripMargin) shouldBe "4\n"
    }

    "'self' inside a method" in {
      run(apply + """struct Box2
                    |    v: int
                    |
                    |    twice(self) -> int = apply(x -> x + self.v, self.v)
                    |
                    |print(Box2(5).twice())
                    |""".stripMargin) shouldBe "10\n"
    }

    "a capture of the closure around it, which reaches through" in {
      run(apply + """var k = 100
                    |
                    |print(apply(x -> apply(y -> y + k, x), 5))
                    |""".stripMargin) shouldBe "105\n"
    }

    "a module-level declaration, which is reached and not captured" in {
      // A `const` and a top-level `val` are declarations rather than locals, so a body naming one
      // reaches it the way any other function does — the environment stays empty.
      val out = ir("""const LIMIT: int = 7
                     |
                     |apply(f: int -> int, x: int) -> int = f(x)
                     |
                     |print(apply(k -> k + LIMIT, 1))
                     |""".stripMargin)

      envs(out) shouldBe List("")
    }

    "a local declared inside the body itself, which is not a capture at all" in {
      val out = ir("""var f: &Fn(int) -> int = k ->
                     |    var own = 5
                     |    k + own
                     |
                     |print(f(1))
                     |""".stripMargin)

      envs(out) shouldBe List("")
    }
  }

  "every arity the library declares" - {
    "none through four" in {
      run("""zero(f: () -> int) -> int = f()
            |one(f: int -> int) -> int = f(1)
            |two(f: (int, int) -> int) -> int = f(1, 2)
            |three(f: (int, int, int) -> int) -> int = f(1, 2, 3)
            |four(f: (int, int, int, int) -> int) -> int = f(1, 2, 3, 4)
            |
            |print(zero(() -> 0), one(a -> a), two((a, b) -> a + b))
            |print(three((a, b, c) -> a + b + c), four((a, b, c, d) -> a + b + c + d))
            |""".stripMargin) shouldBe "0 1 3\n6 10\n"
    }

    "and five is where the library stops" in {
      err("""f(g: (int, int, int, int, int) -> int) -> int = 0
            |""".stripMargin) should include("takes up to 4 parameters")
    }
  }

  "every position a callable may stand in" - {
    "an element of an array of them" in {
      run("""var fs: [3]&Fn(int) -> int = [k -> k + 1, k -> k + 2, k -> k + 3]
            |
            |for i in 0..<3 do print(fs[i](10))
            |""".stripMargin) shouldBe "11\n12\n13\n"
    }

    "the payload of an enum" in {
      run("""var maybe: Option[&Fn(int) -> int] = Some(k -> k * 5)
            |
            |maybe match
            |    Some(g) -> print(g(4))
            |    None -> print("none")
            |""".stripMargin) shouldBe "20\n"
    }

    "a part of a tuple" in {
      run("""var pair: (int, &Fn(int) -> int) = (2, k -> k * 100)
            |
            |print(pair.0, (pair.1)(3))
            |""".stripMargin) shouldBe "2 300\n"
    }

    "an item of a container" in {
      run("""import sysl.buf.*
            |
            |var bag: Buf[&Fn(int) -> int] = buf()
            |
            |bag.push(k -> k * 7)
            |print(bag[0usize](3))
            |""".stripMargin) shouldBe "21\n"
    }

    // `12 §6` lists the result of another call among these, which is the one that never passes
    // through a name at all: the head of the outer call is the inner call itself.
    "the result of another call, called straight away" in {
      run("""pick(which: bool) -> &Fn(int) -> int
            |    if which then x -> x + 1 else x -> x * 2
            |
            |print(pick(true)(10), pick(false)(10))
            |""".stripMargin) shouldBe "11 20\n"
    }

    "the result of a callable, so one may yield another" in {
      run("""mk() -> &Fn(int) -> &Fn(int) -> int
            |    a -> b -> a + b
            |
            |var outer = mk()
            |var inner = outer(10)
            |
            |print(inner(5))
            |""".stripMargin) shouldBe "15\n"
    }

    "both branches of an 'if', which meet at the written type" in {
      run("""var pick: &Fn(int) -> int = if true then k -> k + 1 else k -> k - 1
            |
            |print(pick(10))
            |""".stripMargin) shouldBe "11\n"
    }

    "and something that is not callable, called anyway, says so" in {
      err("""var xs = [1, 2, 3]
            |
            |print(xs[0](1))
            |""".stripMargin) should include("must be a name, or something whose type says it is callable")
    }
  }

  "what a closure composes with" - {
    "a trait implementation's method may write one" in {
      run(apply + """trait Runs
                    |    go(self) -> int
                    |
                    |struct Holder
                    |    n: int
                    |
                    |impl Runs for Holder
                    |    go(self) -> int
                    |        var k = self.n
                    |
                    |        apply(x -> x + k, 1)
                    |
                    |var h = Holder(41)
                    |
                    |print(h.go())
                    |""".stripMargin) shouldBe "42\n"
    }

    "a generic function may take one over its own parameter" in {
      run("""pairup[T](a: T, mk: int -> T) -> T = mk(0)
            |
            |print(pairup(7, k -> k + 1))
            |""".stripMargin) shouldBe "1\n"
    }

    // `12 § Open b` asked whether a closure written *inside* a generic body — capturing a value whose
    // type is the enclosing function's parameter — interacts with monomorphization in a way the
    // top-level cases do not. It does not: the closure is a struct whose field has that type, so it is
    // monomorphized with the function that holds it, once per instantiation. Two instantiations here,
    // and the second is a float, so a struct laid out for the first would be measurably wrong.
    "a closure inside a generic body captures the parameter's own type" in {
      run("""bump[T: Add](x: T, by: T) -> T
            |    var g = (y: T) -> y + by
            |
            |    g(x)
            |
            |print(bump(5, 7), bump(1.5, 0.25))
            |""".stripMargin) shouldBe "12 1.75\n"
    }

    // The bound travels with it: `+` inside the body is dispatched through what the *enclosing*
    // declaration asked of `T`, which is the only place the requirement is written.
    "and the bound the enclosing declaration carries reaches the body" in {
      run("""shown[T: Display](x: T) -> string
            |    var f: &Fn(T) -> string = (v: T) -> str(v)
            |
            |    f(x)
            |
            |print(shown(42), shown(true), shown("s"))
            |""".stripMargin) shouldBe "42 true s\n"
    }

    "a boxed one may be returned out of a generic body" in {
      run("""adder[T: Add](by: T) -> &Fn(T) -> T = (y: T) -> y + by
            |
            |var i = adder(10)
            |var f = adder(0.5)
            |
            |print(i(1), f(1.25))
            |""".stripMargin) shouldBe "11 1.75\n"
    }

    // The other half of `12 § Open b` — a closure that is itself generic — needs no decision of its
    // own, and this is the reason rather than the spelling: a callable's type is the library's `FnN`
    // trait, and `02` refuses a trait member with type parameters because no vtable slot can hold a
    // function that does not exist until a call names its types. So there is nothing for an arrow to
    // declare them for. What the grammar says today is only that it cannot read one.
    "a closure of its own may not be generic, because its call trait's member may not be" in {
      // `[T]` reads as an index of `T` and `(x` as a call on the result, so the refusal lands on
      // the `:` that neither of those admits — the grammar has no reading in which the brackets
      // before an arrow declare anything.
      err("""var f = [T](x: T) -> x
            |""".stripMargin) should include("')' expected")

      err("""trait Maps
            |    over[T](self, x: T) -> T
            |""".stripMargin) should include("which a trait's member may not")
    }

    "'?' inside a body unwraps into the closure's own result" in {
      run("""find(n: int) -> Result[int, string]
            |    if n > 0 then Ok(n) else Err("no")
            |
            |var g: &Fn(int) -> Result[int, string] = k ->
            |    var v = find(k)?
            |    Ok(v * 2)
            |
            |g(3) match
            |    Ok(v) -> print("ok", v)
            |    Err(e) -> print("err", e)
            |
            |g(-1) match
            |    Ok(v) -> print("ok", v)
            |    Err(e) -> print("err", e)
            |""".stripMargin) shouldBe "ok 6\nerr no\n"
    }

    "an interpolated string reads the captures around it" in {
      run("""var name = "world"
            |var s: &Fn(int) -> string = k -> s"hello $name $k"
            |
            |print(s(3))
            |""".stripMargin) shouldBe "hello world 3\n"
    }

    "a boxed one holding a reference releases it with the box" in {
      // Twenty thousand closures, each holding a share of a fresh object and each dropped at the end
      // of its iteration. A capture that was retained and never released grows the heap instead.
      run("""struct Node
            |    v: int
            |
            |make(n: int) -> &Fn(int) -> int
            |    var node: &Node = Node(n)
            |
            |    x -> x + node.v
            |
            |var total = 0
            |
            |for i in 0..<20000
            |    var f = make(i)
            |    total += f(0)
            |
            |print(total)
            |""".stripMargin) shouldBe "199990000\n"
    }
  }

  "how a call on one goes wrong" - {
    "too few arguments" in {
      err("""var f: &Fn(int, int) -> int = (a, b) -> a + b
            |
            |print(f(1))
            |""".stripMargin) should include("this callable takes 2 arguments, but 1 argument was given")
    }

    "too many" in {
      err("""var f: &Fn(int) -> int = x -> x + 1
            |
            |print(f(1, 2))
            |""".stripMargin) should include("this callable takes 1 argument, but 2 arguments were given")
    }

    "an argument of the wrong type names its position, not the trait behind it" in {
      val message = err("""var f: &Fn(int) -> int = x -> x + 1
                          |
                          |print(f("no"))
                          |""".stripMargin)

      message should include("the 1st argument of this callable is int, but string was given")
      message should not include "Fn1"
    }

    "the same, at an inlined one" in {
      val message = err(apply + """print(apply(x -> x + 1, "no"))
                                  |""".stripMargin)

      message should not include "Fn1"
    }

    "a closure at a parameter that wanted an ordinary value" in {
      err("""take(n: int) -> int = n
            |
            |print(take(x -> x))
            |""".stripMargin) should include("'x' has no type here")
    }

    "a body naming something that does not exist" in {
      err(apply + """print(apply(k -> k + missing, 1))
                    |""".stripMargin) should include("undefined name 'missing'")
    }

    /** A closure standing where a bare function address is asked for, which is the case a program
     * meets at every C callback: `*extern(A) -> R` is one machine word (`12 §6a`) and a closure is a
     * struct with an environment, so there is nothing to convert.
     *
     * **What the reader is told it gave has to be "a closure".** The struct is filed under a serial
     * number that runs over the whole compilation with `lib/` lowered first, so the message used to
     * read *"but .closure4 was given"* — a name nothing in the program is called, nothing can be
     * grepped for, and whose digit moves whenever the standard library gains a closure literal of its
     * own. That is not hypothetical: `sysl.slices` arriving with four of them broke five assertions
     * here and turned a page of the site red at the next version bump.
     */
    "a closure where a bare function address is wanted is called a closure, not a number" in {
      // The parameters are written out, so the closure has a type of its own to be complained
      // about. Left bare it is refused a step earlier, for having nothing to read its parameter
      // types from — a good message, and not this one.
      val message = err("""take(f: *extern(int) -> int) -> int = f(1)
                          |
                          |print(take((x: int) -> x + 1))
                          |""".stripMargin)

      message should include("but a closure was given")
      message should not include regex ("""closure\d""")
    }
  }

  "the far corners" - {
    "five closures deep, each capturing through the last" in {
      run(apply + """print(apply(a -> apply(b -> apply(c -> apply(d -> apply(e ->
                    |    e + a + b + c + d, d), c), b), a), 1))
                    |""".stripMargin) shouldBe "5\n"
    }

    "one as a loop condition" in {
      run("""var i = 0
            |var go: &Fn(int) -> bool = k -> k < 3
            |
            |while go(i) do i = i + 1
            |
            |print(i)
            |""".stripMargin) shouldBe "3\n"
    }

    "no parameters and no result" in {
      run("""var noop: &Fn() -> unit = () -> print("noop")
            |
            |noop()
            |""".stripMargin) shouldBe "noop\n"
    }

    "behind a raw pointer rather than a reference" in {
      run("""struct Doubler
            |    k: int
            |
            |impl Fn(int) -> int for Doubler
            |    call(*self, a: int) -> int = a * self.k
            |
            |var d = Doubler(3)
            |var raw: *Fn(int) -> int = &d
            |
            |print(raw(5))
            |""".stripMargin) shouldBe "15\n"
    }

    "twenty thousand of them capturing a string, none of them leaked" in {
      run("""var name = "abc"
            |var total = 0
            |
            |for j in 0..<20000
            |    var f: &Fn(int) -> int = k -> k + int(name.len)
            |    total += f(0)
            |
            |print(total)
            |""".stripMargin) shouldBe "60000\n"
    }

    "a module-level 'val' may not hold one, for the reason it holds no reference" in {
      err("""val greeter: &Fn(int) -> string = k -> "x"
            |""".stripMargin) should include("a count with nowhere to write the release")
    }

    "two of them are not compared" in {
      err("""var f: &Fn(int) -> int = x -> x
            |var g: &Fn(int) -> int = x -> x
            |
            |print(f == g)
            |""".stripMargin) should include("'==' is not defined for &Fn(int) -> int")
    }

    "a type is callable at one arity, since its members are one namespace" in {
      // `Fn(int) -> int` and `Fn(int, int) -> int` are two traits, and each would give the type a
      // member named `call`. Two traits naming one member is ordinarily allowed, told apart by
      // which of them a file can name (`13 §2`) — but `call` is reached through the **call syntax**,
      // which names no trait, so nothing a program could write would say which arity it meant.
      // `callableOf` answers with the first it finds, which makes the second one silent rather than
      // ambiguous, and that is what is refused here.
      err("""struct Twin
            |    k: int
            |
            |impl Fn(int) -> int for Twin
            |    call(*self, a: int) -> int = a
            |
            |impl Fn(int, int) -> int for Twin
            |    call(*self, a: int, b: int) -> int = a + b
            |""".stripMargin) should include("already has a member named 'call'")
    }

    // The same rule from the other side: a trait of the program's own may not take the name either,
    // however ordinary that trait is. Two traits sharing a name are told apart by which is in scope,
    // and the call syntax is exactly the use that has no trait in it to be told by.
    "nor may a trait of the program's own take the name a call trait holds" in {
      err("""struct Twin
            |    k: int
            |
            |trait Mine
            |    call(*self, a: int) -> int
            |
            |impl Fn(int) -> int for Twin
            |    call(*self, a: int) -> int = a
            |
            |impl Mine for Twin
            |    call(*self, a: int) -> int = a + 1
            |""".stripMargin) should include("already has a member named 'call'")
    }

    "an implementation whose 'call' disagrees is told in the arrow it was written with" in {
      val message = err("""struct Wrong
                          |    k: int
                          |
                          |impl Fn(int) -> int for Wrong
                          |    call(*self, a: string) -> int = 0
                          |""".stripMargin)

      message should include("trait 'Fn(int) -> int' declares int")
      message should not include "Fn1"
    }
  }

  "what the representation must not quietly become" - {
    "two closures of one written shape are two types" in {
      val out = ir(apply + """print(apply(x -> x + 1, 1), apply(x -> x * 2, 1))
                             |""".stripMargin)

      // **Two environments, not one.** Both closures are written `x -> …` over no captures, so both
      // are empty structs — and the claim is that they are nonetheless two *types*, one specialising
      // `apply` each, rather than one shared representation.
      //
      // This pair used to assert that `$closure0` and `$closure1` existed, which stopped being a
      // claim about this program the moment the library began lowering closures of its own: both
      // names were then present whatever the program did, and the test passed while asserting
      // nothing. Counting *this program's* environments is the thing that was always meant.
      envs(out) shouldBe List("", "")
    }

    "an inlined callable is called directly and a boxed one through its table" in {
      val direct = ir(apply + "print(apply(x -> x + 1, 5))\n")
      val boxed  = ir("""var f: &Fn(int) -> int = x -> x + 1
                        |
                        |print(f(5))
                        |""".stripMargin)

      direct should not include "@vt."
      boxed should include("@vt.")
    }

    "a capture is read once, where the closure is formed, and not at each call" in {
      // Formed once and called three times: a closure that read its capture at the call would see
      // the assignment between the calls and print 1 2 3 rather than 0 0 0.
      run("""struct Held
            |    f: &Fn(int) -> int
            |
            |var n = 0
            |var h = Held(k -> n)
            |
            |n = 1
            |var a = h.f(0)
            |
            |n = 2
            |var b = h.f(0)
            |
            |n = 3
            |print(a, b, h.f(0))
            |""".stripMargin) shouldBe "0 0 0\n"
    }
  }
}
