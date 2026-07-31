package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** What `07-arrays-and-slices.md` claims, run rather than read.
 *
 * The chapter's ordinary surface has suites of its own — `ArrayTests`, `SliceTests`, `BufTests`.
 * What this one covers is the sentence that turned out not to be true of the compiler, and the
 * probes that confirmed the rest.
 *
 * The sentence is `§ Length`: *"It is a **property** — a member read without parentheses"*, said of
 * `len` on the built-in types. Writing the parentheses was answered with *"type '[3]int' has no
 * method 'len'"* — which denies the member the same paragraph says the type has. A property a
 * program **declares** had said so plainly since properties existed; the ones the *compiler*
 * provides fell through to the generic complaint, so the one paragraph that goes out of its way to
 * call them properties was the one place the property diagnostic never fired.
 */
class ArrayClaimTests extends AnyFreeSpec with RunSupport with CodegenSupport {

  /** An array and a slice are the language's; the growable sequence built on them is `sysl.buf`'s,
   * so the programs here that reach for one say so and the rest are unchanged.
   */
  private def growable(src: String): String = run("import sysl.buf.*\n\n" + src)

  private def growableErr(src: String): String = err("import sysl.buf.*\n\n" + src)

  "a compiler-provided property written with parentheses says so, rather than denying the member" - {

    "on a fixed array, whose length is a constant" in {
      val e = err("var a = [1, 2, 3]\nprint(a.len())")

      e should include("'len' is a property the compiler provides for '[3]int'")
      e should include("without '()'")
      e should not include "has no method"
    }

    "on a slice, whose length is its third word" in {
      err("var xs: []int = [1, 2, 3]\nprint(xs.len())") should
        include("'len' is a property the compiler provides for '[]int'")
    }

    "on a string, of which everything the chapter says about a slice is true" in {
      err("var s = \"hello\"\nprint(s.len())") should
        include("'len' is a property the compiler provides for 'string'")
    }

    "and on the other two a string provides" in {
      err("var s = \"hello\"\nprint(s.bytes())") should include("'bytes' is a property")
      err("var s = \"hello\"\nprint(s.chars())") should include("'chars' is a property")
    }

    """while the provided members that really are called keep working — reaching the complaint at
      |all means the name did not resolve as a call""".stripMargin in {
      run("var s = \"hello\"\nvar c = s.copy()\nprint(c, c.len)") shouldBe "hello 5\n"
      run("var r: &int = 7\nvar w: weak int = r\nw.get() match\n    Some(v) -> print(*v)\n    None -> print(0)")
        .shouldBe("7\n")
    }

    "and a name the type genuinely has no member of keeps the plain complaint" in {
      err("var a = [1, 2, 3]\nprint(a.nope())") should include("type '[3]int' has no method 'nope'")
    }

    "a property a program declares was already answered this way, and still is" in {
      err("struct Box\n    v: int\n\n    doubled -> int = self.v * 2\nvar b = Box(21)\nprint(b.doubled())") should
        include("'doubled' is a property of 'Box'")
    }

    "reading it without the parentheses is what the paragraph asks for" in {
      run("var a = [1, 2, 3]\nprint(a.len, \"hello\".len, \"hello\".bytes.len)") shouldBe "3 5 5\n"
    }
  }

  "the forms a program writes an array down in" - {

    "a literal fixes the length from how many elements there are" in {
      run("var primes = [2, 3, 5, 7]\nprint(primes.len, primes[3])") shouldBe "4 7\n"
    }

    "a declaration with no initializer starts at the zero value" in {
      run("var buf: [64]u8\nvar counters: [8]int\nprint(buf.len, buf[63], counters[7])") shouldBe "64 0 0\n"
    }

    "an empty literal takes its element type from the context" in {
      run("var empty: [0]int = []\nprint(empty.len)") shouldBe "0\n"
    }

    "a repeat fills every element, and a const may give the count" in {
      run("const n: usize = 64\nvar window = [0u8; n]\nvar ones = [1; 8]\nprint(window.len, ones[7])")
        .shouldBe("64 1\n")
    }

    "a repeat nests, which is how a grid is written" in {
      run("var grid = [[0; 3]; 3]\nprint(grid.len, grid[2].len, grid[2][2])") shouldBe "3 3 0\n"
    }

    """the repeat's value is evaluated ONCE and copied, which is what makes the form a construction
      |rather than shorthand for writing the value out that many times""".stripMargin in {
      run("tick() -> int\n    print(\"call\")\n    5\nvar three = [tick(); 3]\nprint(three[0], three[1], three[2])")
        .shouldBe("call\n5 5 5\n")
    }

    "a 'val' is the same forms written at the top of a file, and may be indexed at a running value" in {
      run("val order: [4]usize = [16, 17, 18, 0]\nvar i = 2\nprint(order.len, order[i])") shouldBe "4 18\n"
    }

    "and a table nobody wrote down is the same declaration with a call on the right of it" in {
      run("build() -> [4]u32 = [7u32; 4]\nprivate val table: [4]u32 = build()\nprint(table.len, table[3])")
        .shouldBe("4 7\n")
    }
  }

  "what a type with no zero value costs, which is the rule that keeps '03' true without a special case" - {

    "a struct holding a reference has none, and the diagnostic asks for the initializer" in {
      err("struct Node\n    r: &int\nvar bad: Node") should
        include("Node has no zero value, so 'bad' needs an initial value")
    }

    "an array of one has none either, and is named as the array" in {
      err("struct Node\n    r: &int\nvar nodes: [4]Node") should include("[4]Node has no zero value")
    }
  }

  "storage sized while running" - {

    "a slice takes a count the program computes, where an array's must be constant" in {
      run("var n = 5\nvar raw: []u8 = [0u8; n]\nprint(raw.len, raw[4])") shouldBe "5 0\n"
      err("var k = 5\nvar fixed: [k]int") should include("an array length must be a constant")
    }

    "a function may build one and return it, which the fixed forms cannot" in {
      run("decode(n: int) -> []u8 = [7u8; n]\nvar built = decode(9)\nprint(built.len, built[8])")
        .shouldBe("9 7\n")
    }

    """a negative count is read unsigned and so arrives as a very large one, which traps rather than
      |allocating a small buffer the program then writes past""".stripMargin in {
      exits("mk(n: int) -> []u8 = [0u8; n]\nvar neg = -1\nvar v = mk(neg)\nprint(v.len)")
    }
  }

  "indexing and slicing" - {

    "an element is a place, so every form the place machinery already had follows" in {
      run("var a = [10, 20, 30, 40]\na[0] = 11\na[1] += 1\na[2]++\nvar p = &a[3]\n*p = 44\n" +
        "print(a[0], a[1], a[2], a[3])") shouldBe "11 21 31 44\n"
    }

    "the index may be any integer type, since the check has to happen anyway" in {
      run("var a = [10, 20, 30, 40, 50, 60]\nvar i: i8 = 4\nvar u: u8 = 5u8\nprint(a[i], a[u])")
        .shouldBe("50 60\n")
    }

    "the two range operators keep the meanings they have everywhere else" in {
      run("var a = [10, 20, 30, 40, 50, 60]\n" +
        "print(a[2..5].len, a[2..<5].len, a[2..].len, a[..<5].len, a[..].len)") shouldBe "4 3 4 5 6\n"
    }

    "an empty slice is legal, including at the very end" in {
      run("var a = [10, 20, 30]\nprint(a[a.len..].len, a[2..<2].len)") shouldBe "0 0\n"
    }

    """the inclusive form additionally requires that its named high element exist — the one thing
      |that tells it apart from the exclusive form at the end""".stripMargin in {
      run("var a = [10, 20, 30, 40, 50, 60]\nprint(a[2..<6].len, a[2..5].len)") shouldBe "4 4\n"
      exits("var a = [10, 20, 30, 40, 50, 60]\nprint(a[2..6].len)")
    }

    "an open-ended slice has one spelling, and the other is rejected rather than quietly meaning it" in {
      err("var a = [1, 2, 3, 4]\nvar bad = a[1..<]") should include("an open-ended slice is written 'a[lo..]'")
    }

    "a subscript reaches through one level of indirection, as a field selection does" in {
      run("var arr: [4]int = [1, 2, 3, 4]\nvar r: &[4]int = arr\nprint(r[0..<2].len, r[1], r.len)")
        .shouldBe("2 2 4\n")
    }

    """a raw pointer is the one receiver with nothing to check against, and is indexed anyway — with
      |the end written, because nothing in the type can supply one""".stripMargin in {
      run("var a = [10, 20, 30, 40]\nvar p = &a[0]\np[1] = 99\nvar run = p[0..<3]\n" +
        "print(p[0], a[1], run.len, run[2])") shouldBe "10 99 3 30\n"
    }

    "while a '*[N]T', whose length is in its type, keeps every check an array has" in {
      run("var arr: [4]int = [1, 2, 3, 4]\nvar ap = &arr\nprint(ap.len, ap[3], ap[1..<3].len)")
        .shouldBe("4 4 2\n")
    }

    """a type indexed through 'Index' is a call rather than a walk to an address, so its element is
      |not a place and a compound assignment is refused rather than read-and-written-back""".stripMargin in {
      growableErr("var b: Buf[int] = buf()\nb.push(1)\nb[0] += 5") should
        include("would evaluate the receiver and the index twice")
    }
  }

  "iterating binds a copy, which is what value semantics mean" - {

    "so assigning to the loop variable leaves the sequence alone" in {
      run("var a = [1, 2, 3]\nfor x in a\n    x += 100\nprint(a[0], a[1], a[2])") shouldBe "1 2 3\n"
    }

    "and the loop evaluates its sequence once, so a slice temporary lives for the whole loop" in {
      run("var a = [1, 2, 3]\nvar n = 0\nfor x in a[1..]\n    n += 1\nprint(n)") shouldBe "2\n"
    }

    "a container is walked through its view rather than by implementing a cursor" in {
      growable("var b: Buf[int] = buf()\nfor i in 0..<3 do b.push(i * 7)\nvar sum = 0\n" +
        "for x in b.view() do sum += x\nprint(sum)") shouldBe "21\n"
    }
  }

  "the growable array the chapter says is ordinary sysl" - {

    "gives up every member the chapter lists, and 'is_empty' besides" in {
      growable("var b: Buf[int] = buf()\nfor i in 0..<5 do b.push(i)\n" +
        "print(b.len(), b.cap(), b.is_empty(), b.at(2), b.view().len)") shouldBe "5 8 false 2 5\n"
    }

    "'truncate' does nothing where the length is one the buffer does not have" in {
      growable("var b: Buf[int] = buf()\nfor i in 0..<5 do b.push(i)\nb.truncate(99usize)\nprint(b.len())")
        .shouldBe("5\n")
    }

    "'remove' shifts the survivors down and hands the element back" in {
      growable("var b: Buf[int] = buf()\nfor i in 0..<5 do b.push(i)\nvar gone = b.remove(1)\n" +
        "print(gone, b.len(), b.at(0), b.at(1), b.at(2), b.at(3))") shouldBe "1 4 0 2 3 4\n"
    }

    "'clear' is 'truncate(0)', and 'set' and 'pop' reach the ends" in {
      growable("var b: Buf[int] = buf()\nfor i in 0..<3 do b.push(i)\nb.set(0, 42)\n" +
        "b.pop() match\n    Some(v) -> print(v, b.at(0))\n    None -> print(0, 0)\n" +
        "b.clear()\nprint(b.len(), b.is_empty())") shouldBe "2 42\n0 true\n"
    }

    """a COPY of a Buf taken before a removal reads the shifted elements at the length it was copied
      |at — the sentence the chapter draws out of the spare capacity holding values""".stripMargin in {
      growable("var b: Buf[int] = buf()\nfor i in 0..<5 do b.push(i)\nvar copy = b\nvar gone = b.remove(1)\n" +
        "print(copy.len(), copy.at(0), copy.at(1), copy.at(4))\nprint(b.len(), b.at(1))")
        .shouldBe("5 0 2 4\n4 2\n")
    }

    "a view taken before a growth keeps its own storage and its own length" in {
      growable("var g: Buf[int] = buf()\nfor i in 0..<4 do g.push(i * 10)\nvar before = g.view()\n" +
        "for i in 0..<20 do g.push(999)\nprint(before.len, before[0], before[3], g.len())")
        .shouldBe("4 0 30 24\n")
    }

    """how a push is seen follows from how the buffer is held, which is a choice the language
      |already makes the author write""".stripMargin in {
      growable("var p: &Buf[int] = buf()\np.push(1)\nvar q = p\nvar c = *p\np.push(2)\nprint(q.len(), c.len())")
        .shouldBe("2 1\n")
    }
  }

  "what a generic may declare storage for" - {

    """a fixed array of a type parameter CAN be written — the chapter says it cannot, and what
      |actually cannot be had is storage sized while running with no value to seed it""".stripMargin in {
      run("fixed[K](witness: K) -> usize\n    var storage: [16]K\n    storage.len\nprint(fixed(0))")
        .shouldBe("16\n")
    }

    "and it is refused per instantiation, at the element type that has no zero value" in {
      err("struct Node\n    r: &int\nfixed[K](witness: K) -> usize\n    var storage: [16]K\n" +
        "    storage.len\nvar n = Node(5)\nprint(fixed(n))") should include("[16]Node has no zero value")
    }

    "a slice declared with no initializer is empty, which is the storage a container cannot get" in {
      run("sized[K](witness: K) -> usize\n    var storage: []K\n    storage.len\nprint(sized(0))")
        .shouldBe("0\n")
    }

    "while a repeat whose value needs nothing of the parameters works, as the chapter says" in {
      run("opts[T](n: int) -> []Option[T] = [None; n]\nvar slots: []Option[int] = opts(4)\n" +
        "print(slots.len)\nslots[0] match\n    Some(v) -> print(v)\n    None -> print(\"none\")")
        .shouldBe("4\nnone\n")
    }
  }

  "an array IS its elements, which is what makes it the storage half of the pair" - {

    "so a copy is a copy, and writing one leaves the other alone" in {
      run("var a = [1, 2, 3]\nvar b = a\nb[0] = 99\nprint(a[0], b[0])") shouldBe "1 99\n"
    }

    "and a parameter is one too — the callee's writes do not reach the caller's array" in {
      run("take(xs: [3]int) -> int\n    xs[0] = 77\n    xs[0]\nvar a = [1, 2, 3]\nprint(take(a), a[0])")
        .shouldBe("77 1\n")
    }
  }

  "everything here is true of a string as well, and the two places it is not are the ones '04' accounts for" - {

    "its length is its bytes, not its characters" in {
      run("var s = \"héllo\"\nprint(s.len, s.bytes.len)") shouldBe "6 6\n"
    }

    "it slices like the view it is" in {
      run("var s = \"héllo\"\nprint(s[0..<1])") shouldBe "h\n"
    }

    "and 's[i] = v' does not exist, which is the first of the two" in {
      err("var s = \"hello\"\ns[0] = 74u8") should
        include("a string is immutable, so its bytes have no address to write through")
    }
  }

  /** `§ Not yet` promised two refusals against the day a view could record something about the
    * storage it views. One of them has been paid off and the other has not, and they are kept
    * together because what separates them is the point: read-only-ness is a property of the **view**,
    * so a type can carry it, while whether a count is atomic is a property of the **owner**, which
    * the view can only report on.
    */
  "what '§ Not yet' promised, and which half of it a view type reaches" - {

    "a 'val' CAN be sliced now, and what comes back may not be written" in {
      run("""val table: [4]int = [1, 2, 3, 4]
            |var sliced = table[0..<2]
            |print(sliced.len, sliced[1])
            |""".stripMargin) shouldBe "2 2\n"

      err("val table: [4]int = [1, 2, 3, 4]\nvar sliced = table[0..<2]\nsliced[0] = 9") should
        include("views elements it may not write")
    }

    // The bit follows the storage rather than the expression, which is the whole of what makes it
    // sound: re-slicing, binding and passing all keep it, and the refusal lands wherever the write is.
    "and it stays read-only through a re-slice and through a call" in {
      err("""val table: [4]int = [1, 2, 3, 4]
            |var sliced = table[0..]
            |var again = sliced[1..]
            |again[0] = 9
            |""".stripMargin) should include("views elements it may not write")

      err("""val table: [4]int = [1, 2, 3, 4]
            |take(xs: []int) = print(xs.len)
            |take(table[0..])
            |""".stripMargin) should include("does not become the other")
    }

    "but a '&sync' array still cannot be sliced, since that is the owner's property and not the view's" in {
      err("var p: &sync [4]int = [1, 2, 3, 4]\nvar v = p[..]") should
        include("a slice does not record whether its owner's count is atomic")
    }
  }

  /** The claims `07 § A view that may not be written` makes, asked one at a time. Each of these is
    * a sentence of that section rather than a case the code suggested, which is the point of asking
    * them separately: a claim written for a category is satisfied by whichever form a test picks.
    */
  "what a view that may not be written promises" - {

    // Every spelling of a write, not just the one an implementation happens to route first.
    "every way of writing an element is refused, not only the plain assignment" in {
      val view = "val k: [4]int = [1, 2, 3, 4]\nvar v = k[0..]\n"

      err(view + "v[0] = 9") should include("may not write")
      err(view + "v[0] += 1") should include("may not write")
      err(view + "v[0]++") should include("may not write")
    }

    // The one thing it does NOT refuse, and the reason a read-only view is usable at all: `&` is a
    // `*T`, the tier `03` excludes, and it is how the view reaches C. The library's own `find_byte`
    // is `memchr` over exactly this shape.
    "while taking an address is allowed, because that is the raw tier and is how a view reaches C" in {
      run("""extern printf(fmt: *u8, ...) -> int
            |val k: [4]u8 = [104u8, 105u8, 0u8, 0u8]
            |var v = k[0..<2]
            |printf(c"[%.*s]\n", int(v.len), &v[0])
            |""".stripMargin) shouldBe "[hi]\n"
    }

    "a length in the brackets contradicts the word, and the message names 'val'" in {
      err("var bad: [4]const int = [1, 2, 3, 4]") should
        include("read-only storage is declared with 'val'")
    }

    // Storage the expression makes has no other holder to disagree with it, so a literal takes
    // whichever form was asked for rather than needing a conversion.
    "storage the expression makes takes the read-only form directly" in {
      run("""var lit: []const int = [7, 8, 9]
            |var filled: []const int = [5; 3]
            |var empty: []const int = []
            |print(lit[2], filled[1], empty.len)
            |""".stripMargin) shouldBe "9 5 0\n"
    }

    // The places a type has to work to be a type at all: a field, a type argument, an element of
    // something else, and a result. None of these is mentioned by the section, which is why they
    // are worth asking — a bit added to one type tends to be dropped by whatever composes it.
    "and it composes — as a field, a type argument, an element, and a result" in {
      run("""struct Holder
            |    items: []const int
            |sum(h: Holder) -> int
            |    var s = 0
            |    for x in h.items do s += x
            |    s
            |count[T](xs: []const T) -> usize = xs.len
            |give() -> []const int = [4, 5]
            |var a = [1, 2, 3]
            |var nested: [2][]const int = [a[0..], a[1..]]
            |print(sum(Holder(a[0..])), count(a[0..]), count("ab".bytes))
            |print(nested[0].len, nested[1].len, give().len)
            |""".stripMargin) shouldBe "6 3 2\n3 2 2\n"
    }

    /* Widening at a return, and the distinction the section never has to state because it reads as
     * obvious — that assigning the *field* replaces which elements are viewed, and is not a write
     * *through* the view. A rule implemented at the subscript could easily refuse both. */
    "widens at a result, and a field holding one may still be reassigned" in {
      run("""widen(xs: []int) -> []const int = xs
            |struct Holder
            |    items: []const int
            |var a = [1, 2, 3]
            |var b = [9, 9]
            |var h = Holder(a[0..])
            |h.items = b[0..]
            |print(widen(a[0..]).len, h.items[0])
            |""".stripMargin) shouldBe "3 9\n"
    }

    /* The bit has to survive being *stored*, not merely passed — a container holds its element as a
     * type argument, and a type argument is where a bit on a type gets dropped. Both a generic the
     * program writes and one the prelude supplies, since they instantiate by different routes. */
    "and survives a round trip through a generic container, written or supplied" in {
      val cell = """struct Cell[T]
                   |    item: T
                   |    got(self) -> T = self.item
                   |end Cell
                   |val k: [4]int = [1, 2, 3, 4]
                   |var r: Cell[[]const int] = Cell(k[0..])
                   |""".stripMargin

      run(cell + "print(r.got().len, r.got()[2])") shouldBe "4 3\n"
      err(cell + "r.got()[0] = 9") should include("views elements it may not write")

      err("""val k: [4]int = [1, 2, 3, 4]
            |var o: Option[[]const int] = Some(k[0..])
            |o.unwrap()[0] = 9
            |""".stripMargin) should include("views elements it may not write")
    }

    /* A writable view stored the same way keeps *its* form, which is what makes the test above mean
     * something: the two instantiations are distinct types rather than one that took whichever bit
     * arrived first. That distinctness is what mangling the bit buys. */
    "while the writable form stored the same way is a different instantiation, and still writes" in {
      run("""struct Cell[T]
            |    item: T
            |    got(self) -> T = self.item
            |end Cell
            |var a = [1, 2, 3]
            |val k: [4]int = [1, 2, 3, 4]
            |var w: Cell[[]int] = Cell(a[0..])
            |var r: Cell[[]const int] = Cell(k[0..])
            |w.got()[0] = 9
            |print(w.got().len, r.got().len, a[0])
            |""".stripMargin) shouldBe "3 4 9\n"
    }
  }

  /** The three sequence types answer the same questions, so the questions are asked of all three at
    * once. Each of these reaches the array, the slice and the string by a different path in the
    * compiler, and a claim about "a sequence" is satisfied by whichever one a test happens to pick —
    * which is exactly how a gap survives a dense suite.
    */
  "array, slice and string answer the same three questions" - {
    "length" in {
      run("""var a = [1, 2, 3]
            |var s = a[..]
            |print(a.len, s.len, "abc".len)
            |""".stripMargin) shouldBe "3 3 3\n"
    }

    // A string indexes in bytes and yields a `u8` (`04 §`), which is why `97` and not `'a'`.
    "an index" in {
      run("""var a = [1, 2, 3]
            |var s = a[..]
            |print(a[0], s[0], "abc"[0])
            |""".stripMargin) shouldBe "1 1 97\n"
    }

    "and a slice of themselves" in {
      run("""var a = [1, 2, 3]
            |var s = a[..]
            |print(a[1..].len, s[1..].len, "abcd"[1..].len)
            |""".stripMargin) shouldBe "2 2 3\n"
    }

    // Where they part, and it is deliberate: a string has bytes and characters both, so iterating
    // one directly would have to pick, and the message makes the program pick instead.
    "but iterating a string directly is refused, since it would have to choose bytes or characters" in {
      err("""for c in "ab" do print(c)""") should
        include("iterated as 's.bytes' or 's.chars'")
    }
  }
}
