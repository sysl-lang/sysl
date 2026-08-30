package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Walking a sequence a program has to produce a value at a time — `for x in cursor` through the
  * library's `Iterate`, and `s.chars`, which is what decided the shape.
  *
  * The deciding question `library/core.md § Walking a type of your own` left open was whether
  * iteration is something a *type* implements or something `for` knows about, and `s.chars` is what
  * settles it: a string cannot hand out a view of its scalar values the way a container hands out a
  * view of its storage, because the decoding is what makes them. So there has to be a value that
  * carries a position and answers "the next one", and once there is, `for` accepting it is a
  * smaller change than teaching `for` about strings.
  *
  * A container is deliberately **not** an iterator. `for x in b.view()` reads a `Buf` by index with
  * no call per element, so a protocol for it would be a slower way to do something that already
  * works; the protocol exists for sequences whose elements are computed. That is why there is one
  * trait here and not Rust's two.
  *
  * The suite is in the two halves `9b` asks for: what the documents claim, and what breaks at the
  * edges — the second implementation, the empty sequence, the loop that is a value, and the cursor
  * that a program holds itself.
  */
class IterateTraitTests extends AnyFreeSpec with RunSupport with CodegenSupport {

  /** A counter: the smallest thing that is a sequence without being storage. */
  private val upto =
    """struct Upto
      |    at: int
      |    last: int
      |end Upto
      |impl Iterate for Upto
      |    type Item = int
      |    next(*self) -> Option[int]
      |        if self.at > self.last then return None
      |        self.at += 1
      |        Some(self.at - 1)
      |    end next
      |""".stripMargin

  /** A cursor whose items own heap, which is what an over-release can be seen through: an `int`
    * is copied and has no count to get wrong.
    */
  private val cursor =
    """struct Src
      |    at: int
      |end Src
      |impl Iterate for Src
      |    type Item = string
      |    next(*self) -> Option[string]
      |        if self.at >= 3 then return None
      |        self.at += 1
      |        Some("v" + str(self.at - 1))
      |    end next
      |""".stripMargin

  "what the documents claim" - {
    // `library/core.md § Walking a type of your own` — the protocol's whole reason to exist is a
    // sequence that is not storage.
    "a type that implements 'Iterate' is what a 'for' walks" in {
      run(upto + """for i in Upto(1, 4) do print(i)""") shouldBe "1\n2\n3\n4\n"
    }

    // `04`'s granularity table: scalar values, no replacement characters. Two-, three- and
    // four-byte encodings are all here, so a decoder that handled only the short forms fails.
    "'s.chars' decodes a string into its scalar values" in {
      run("""for c in "aé→𝄞".chars do print(c)""") shouldBe "a\né\n→\n𝄞\n"
    }

    // The same string as bytes and as characters: `04` says which is meant is written, and the
    // counts differ exactly where the encoding says they should (1 + 2 + 3 + 4).
    "bytes and characters are two granularities of one string" in {
      run("""var b = 0
             |var c = 0
             |for _ in "aé→𝄞".bytes do b += 1
             |for _ in "aé→𝄞".chars do c += 1
             |print(b, c)""".stripMargin) shouldBe "10 4\n"
    }

    // `reference/arrays.md § Iterating` — the sequence is evaluated once. A `next` with a side
    // effect would show a second evaluation immediately.
    "the sequence expression is evaluated once" in {
      run(upto + """start() -> Upto
                   |    print("built")
                   |    Upto(1, 3)
                   |for i in start() do print(i)""".stripMargin) shouldBe "built\n1\n2\n3\n"
    }

    // `reference/statements.md` — a loop's `else` runs on normal completion, and running out of
    // elements is normal completion. This is the claim being checked rather than assumed for a new
    // loop form.
    "an 'else' runs when the elements run out and not when a 'break' left" in {
      run(upto + """for i in Upto(1, 3)
                   |    print(i)
                   |else
                   |    print("done")
                   |for i in Upto(1, 3)
                   |    break
                   |else
                   |    print("unreachable")""".stripMargin) shouldBe "1\n2\n3\ndone\n"
    }

    // `continue` has no step to skip here — advancing is what `next` did — so it goes straight
    // back to the test. A loop that dropped the odd numbers would be wrong either way; what this
    // pins is that nothing is skipped and nothing repeats.
    "'continue' takes the next element" in {
      run(upto + """for i in Upto(1, 6)
                   |    if i % 2 == 1 then continue
                   |    print(i)""".stripMargin) shouldBe "2\n4\n6\n"
    }

    // `reference/statements.md` — a loop is an expression, and `break value` carries one out. The
    // `else` supplies the value on the path where the elements ran out.
    "the loop is an expression, and its 'break' carries a value" in {
      run(upto + """var found = for i in Upto(1, 9)
                   |    if i * i > 20 then break i
                   |else
                   |    0
                   |print(found)""".stripMargin) shouldBe "5\n"
    }

    // `reference/declarations.md § Structs` — a compiler-provided member is reached ahead of the
    // member table, so an `impl` may not declare one of the same name. `chars` joins `len` and
    // `bytes` in that rule.
    "'chars' is compiler-provided, so an 'impl' may not redeclare it" in {
      err("""trait Mine
            |    chars(self) -> int
            |impl Mine for string
            |    chars(self) -> int = 1
            |""".stripMargin) should include("chars")
    }

    // A cursor is an ordinary value: it can be held, passed, and asked for one element at a time.
    // Nothing about the protocol requires a `for`.
    "'next' is a method a program may call itself" in {
      run(upto + """var it = Upto(7, 8)
                   |print(it.next().unwrap())
                   |print(it.next().unwrap())
                   |print(it.next().is_none())""".stripMargin) shouldBe "7\n8\ntrue\n"
    }
  }

  "what the edges do" - {

    // **A `return` out of the body used to give the option back twice.** The loop releases what
    // `next` handed over on the way into the body and again on the exhausted edge, so the region
    // holding it stays populated across both — and `releaseAll`, which a `return` emits, walks
    // every region and reached that one as well. A `break` was never affected: it stops at the
    // depth the loop recorded, which is outside that region.
    //
    // Counted in the IR rather than run, because the second release is a *count*, and a program
    // that over-releases still prints the right answer until the storage is reused by something
    // else — which is what made this intermittent, and filesystem-shaped, when the walk in
    // `sysl.fs` first hit it.
    "a 'return' from the body gives what 'next' handed over back exactly once per edge" in {
      val out = ir(cursor + """first(s: Src) -> string
                              |    for x in s
                              |        return x
                              |
                              |    "none"
                              |
                              |print(first(Src(0)))
                              |""".stripMargin)

      val disposes = raw"call void @arc\.dispose\.${keyRe("Option")}\.string".r.findAllIn(out).length

      disposes shouldBe 2
    }

    // The same shape a program actually writes, run: a search that leaves the loop as soon as it
    // has an answer, over a cursor whose items own heap. Two of them, because one over-release is
    // survivable and the second is what took the storage away.
    "and a value found by an early return survives the loop it was found in" in {
      run(cursor + """first(s: Src) -> string
                     |    for x in s
                     |        return x
                     |
                     |    "none"
                     |
                     |print(first(Src(0)))
                     |print(first(Src(0)))
                     |""".stripMargin) shouldBe "v0\nv0\n"
    }

    // The empty case runs the body no times and the `else` once, which is the same rule an empty
    // range follows. A cursor whose first `next` is `None` is the shape a filter ends at.
    "a sequence with no elements runs the body no times" in {
      run(upto + """for i in Upto(5, 1)
                   |    print(i)
                   |else
                   |    print("empty")""".stripMargin) shouldBe "empty\n"
    }

    "an empty string has no characters" in {
      run("""var n = 0
             |for _ in "".chars do n += 1
             |print(n)""".stripMargin) shouldBe "0\n"
    }

    // The cursor the loop advances is the loop's own copy, which is what value semantics mean
    // everywhere else in the language. Draining a `for` leaves the variable it was written from
    // where it was, so the second loop starts over.
    "the loop drains a copy, so what it was written from is untouched" in {
      run(upto + """var it = Upto(1, 2)
                   |for i in it do print(i)
                   |for i in it do print(i)
                   |print(it.next().unwrap())""".stripMargin) shouldBe "1\n2\n1\n2\n1\n"
    }

    // Two implementations of one trait on one type is legal for a trait that takes *arguments*
    // (`02`), and every member then picks between them by its own — but `Iterate` takes none, and
    // the element it yields is an associated type the subject chooses. So a second implementation
    // is not an ambiguity a `for` has to resolve; it is the duplicate it looks like, and it is
    // refused where it is written rather than at some loop three files away.
    //
    // This is the shape the element being a *parameter* used to make legal, and the loop used to be
    // where it was reported. Nothing reaches the loop now.
    "a type implementing 'Iterate' twice is a duplicate, not a choice a 'for' has to make" in {
      val two =
        """struct Both
          |    n: int
          |end Both
          |impl Iterate for Both
          |    type Item = int
          |    next(*self) -> Option[int] = None
          |impl Iterate for Both
          |    type Item = bool
          |    next(*self) -> Option[bool] = None
          |""".stripMargin

      err(two + "for x in Both(0) do print(x)") should (include("Iterate") and include("Both"))
    }

    // A string is the one type with two granularities and no reason to prefer one, so it is still
    // refused — and the message now names both ways of writing what was meant.
    "a bare string is still not iterable, and the message names both granularities" in {
      err("""for c in "abc" do print(c)""") should (include("s.bytes") and include("s.chars"))
    }

    // Nothing else became iterable. The message lists what a `for` accepts, and a type that
    // implements no protocol is told so rather than told it is not a slice.
    "a type that implements nothing is refused with the whole list" in {
      err("""struct P
            |    x: int
            |end P
            |for v in P(1) do print(v)""".stripMargin) should (include("Iterate") and include("slice"))
    }

    // A container is deliberately not a cursor, which is what the header of this suite says and
    // what keeps `for x in b` from quietly reading storage a `push` inside the body has replaced.
    // What that costs is that the obvious line is refused on the one sequence a program reaches
    // for most, and the refusal used to name `Iterate` and stop — sending a reader to look for a
    // missing implementation when the road was a method on the type in front of them.
    "a 'Buf' names 'view()', which is the road a container takes" in {
      val why = err("""import sysl.buf.*
                      |var b: Buf[int] = buf()
                      |b.push(1)
                      |for x in b do print(x)""".stripMargin)

      why should include("'view()'")
      why should include("for x in b.view()")
    }

    // The other road, and the one every `sysl.container` type takes: a `walk()` that answers with a
    // cursor. It is found the same way — a nullary member whose declared result a `for` walks — so
    // the two need no list of names between them.
    "a container that hands out a cursor names the member that does it" in {
      val why = err(upto +
        """struct Bag
          |    n: int
          |
          |    walk(self) -> Upto = Upto(0, self.n)
          |end Bag
          |for x in Bag(2) do print(x)""".stripMargin)

      why should include("'walk()'")
      // The subject here is a call rather than a name, so there is no spelling to hand back — the
      // member is named and the worked line is left off rather than reconstructed from the tree.
      why should not(include("is the loop"))
    }

    // Only a member the call could have been. One taking an argument is not the loop that was
    // meant, and naming it would send the reader to write a call they have no argument for.
    "a member that takes an argument is not offered as the road" in {
      val why = err("""struct Win
                      |    xs: []int
                      |
                      |    first(self, n: usize) -> []int = self.xs[0..<n]
                      |end Win
                      |for x in Win([1, 2]) do print(x)""".stripMargin)

      why should include("Iterate")
      why should not(include("first"))
    }

    // And a type with no such member says exactly what it said before: the list, and nothing
    // invented. A hint that fires on everything is a hint nobody reads.
    "a type with no road to offer gets the list alone" in {
      val why = err("""struct P
                      |    x: int
                      |end P
                      |for v in P(1) do print(v)""".stripMargin)

      why should not(include("what it does have"))
    }

    // The element is bound by value, exactly as an array's is: assigning to the loop variable
    // changes the binding and nothing behind it.
    "the loop variable is a copy" in {
      run(upto + """for i in Upto(1, 3)
                   |    i = 9
                   |    print(i)""".stripMargin) shouldBe "9\n9\n9\n"
    }

    // A cursor over the bytes of a string keeps the string alive — the slice retains its owner —
    // so a temporary that only the loop refers to survives the whole walk. A great many rounds of
    // a loop that also allocates is what a missing release would show as a growing heap. The total
    // is the sum of the code points of "ab0" … "ab1999", computed outside the program.
    "a cursor over a temporary string keeps it alive for the whole loop" in {
      run("""build(n: int) -> string = "ab" + str(n)
             |var total = 0
             |for i in 0..<2000
             |    for c in build(i).chars
             |        total += int(u32(c))
             |print(total)""".stripMargin) shouldBe "748720\n"
    }

    // A nested loop over two cursors: each has its own slot, and the inner one is rebuilt every
    // round of the outer. A shared slot would give the inner loop no elements after the first pass.
    "cursors nest" in {
      run(upto + """for i in Upto(1, 3)
                   |    for j in Upto(1, i) do print(i, j)""".stripMargin) shouldBe
        "1 1\n2 1\n2 2\n3 1\n3 2\n3 3\n"
    }

    // A labelled `break` leaves both loops, and the cursor slots of both are let go on the way out.
    "a labelled 'break' leaves an outer iterating loop" in {
      run(upto + """'outer for i in Upto(1, 9)
                   |    for j in Upto(1, 9)
                   |        if i * j > 6 then break 'outer
                   |        print(i, j)""".stripMargin) shouldBe
        "1 1\n1 2\n1 3\n1 4\n1 5\n1 6\n"
    }

    // An iterator over references is the case ownership could go wrong in: each element handed out
    // is a count the body must be given and the loop must not keep. Building and dropping a great
    // many is what a leak or a double free shows up as.
    "an iterator that yields references neither leaks nor double-frees" in {
      run("""struct Cell
             |    v: int
             |end Cell
             |struct Cells
             |    at: int
             |    n: int
             |end Cells
             |impl Iterate for Cells
             |    type Item = &Cell
             |    next(*self) -> Option[&Cell]
             |        if self.at >= self.n then return None
             |        self.at += 1
             |        Some(Cell(self.at))
             |    end next
             |var total = 0
             |for _ in 0..<1000
             |    for c in Cells(0, 20) do total += c.v
             |print(total)""".stripMargin) shouldBe "210000\n"
    }

    // The same iterator left through a `break`, which is the edge the test above does not reach:
    // the element the round produced is released once by the body's own unwinding, and the option it
    // came out of belongs to the frame the loop already gave back on the way in. A `break` that
    // unwound that frame as well would hand the same reference back twice, which is a trap rather
    // than a wrong answer — so the assertion is that the program finishes at all, many times over.
    "a 'break' out of an iterator over references does not free the round's element twice" in {
      run("""struct Cell
             |    v: int
             |end Cell
             |struct Cells
             |    at: int
             |    n: int
             |end Cells
             |impl Iterate for Cells
             |    type Item = &Cell
             |    next(*self) -> Option[&Cell]
             |        if self.at >= self.n then return None
             |        self.at += 1
             |        Some(Cell(self.at))
             |    end next
             |var total = 0
             |for _ in 0..<1000
             |    for c in Cells(0, 20)
             |        total += c.v
             |        if c.v == 3 then break
             |print(total)""".stripMargin) shouldBe "6000\n"
    }

    // `reference/generics.md § Bounds` — a bound promises behaviour, and `Iterate`'s behaviour is a
    // method, so a generic body may call it. This is the probe that says whether the protocol
    // reaches generic code at all, and it does: `next` is an ordinary bounded method call.
    "a bound promising 'Iterate' licenses the call in a generic body" in {
      run(upto + """count_all[T: Iterate](it: T) -> int
                   |    var seen = 0
                   |    var cur = it
                   |    loop
                   |        var got = cur.next()
                   |        if got.is_none() then break
                   |        seen += 1
                   |    seen
                   |print(count_all(Upto(1, 4)))""".stripMargin) shouldBe "4\n"
    }

    /** **The signature the element being an associated type is FOR**, and the one the parameter
     * form could not express. `[T: Iterate]` names no element, so nothing at the call has to say
     * what it is; written `[E, T: Iterate[E]]` the same function was refused outright — *"'E' is in
     * neither the parameters of 'count_all' nor its result"* — because the element appeared only in
     * a bound, which a call has nothing to solve it from.
     *
     * The second half is the other direction: a body that *does* want the element names it as
     * `T::Item` and gets the type the implementation chose.
     */
    "and the element is reachable as 'T::Item', which is what a parameter could not be" in {
      run(upto + """first[T: Iterate](it: T) -> Option[T::Item]
                   |    var cur = it
                   |    cur.next()
                   |print(first(Upto(3, 9)).unwrap())""".stripMargin) shouldBe "3\n"
    }

    // …and so does the loop form, which was worth probing rather than assuming: the loop looks for
    // an implementation filed under the receiver's *type*, and a type parameter has none — but
    // `reference/generics.md § Monomorphization`'s per-instantiation lowering means the body is
    // analyzed once `T` is a real type, so by the time the loop asks, there is one. A generic walk
    // over any cursor needs nothing added. The body counts rather than prints, and that is the
    // associated type showing through: a `[T: Iterate]` says what `T` can *do* and nothing about
    // what it yields, so `print(x)` is not licensed — `Iterate` promises nothing of its `Item` and
    // a bound cannot add a promise to a projection. Naming the element in the bound is what a
    // parameter used to allow and what an object now does; a *bound* has no spelling for it.
    "a 'for' over a bounded type parameter walks it" in {
      run(upto + """walk[T: Iterate](it: T) -> int
                   |    var n = 0
                   |    for _ in it do n += 1
                   |    n
                   |print(walk(Upto(1, 2)))""".stripMargin) shouldBe "2\n"
    }

    /** `reference/traits.md § The table carries the required trait's slots` — an erased value
     * reaches its trait's members through a table, and `next` is object-safe (`*self`, no `Self`
     * away from the receiver). So a `for` walks one: the loop asks what may be *called* on the
     * value, which is the question the table answers.
     *
     * **The element comes out of the object's own type**, which is what makes an object over a trait
     * with an associated type formable at all: an erased value has forgotten which implementation it
     * came from, so `*Iterate` alone says nothing about what `next` answers with — and
     * `*Iterate[Item = int]` says it outright. `*Iterate[int]` is the same type: a trait with no
     * parameters of its own and exactly one associated type has only one thing a bare argument could
     * mean, so the short form is what a program writes and both are pinned here.
     *
     * The loop looks for an implementation filed under the receiver's type for every *other* kind
     * of value, and an object has none — it **is** the implementation. Reading the element out of
     * the object's own type instead is the same step that lets a bound take one
     * (`reference/generics.md § Bounds`), and both sigils are pinned because the two are separate
     * types.
     */
    "a 'for' walks an erased cursor, and so does a direct 'next'" in {
      run(upto + """var o: &Iterate[int] = Upto(1, 2)
                   |print(o.next().unwrap())
                   |print(o.next().unwrap())
                   |print(o.next().is_none())""".stripMargin) shouldBe "1\n2\ntrue\n"

      run(upto + """var o: &Iterate[int] = Upto(1, 3)
                   |for x in o do print(x)""".stripMargin) shouldBe "1\n2\n3\n"

      run(upto + """var c = Upto(1, 3)
                   |var o: *Iterate[int] = &c
                   |for x in o do print(x)""".stripMargin) shouldBe "1\n2\n3\n"
    }

    /** The same three, written the long way. `Item = int` is the general spelling and the bare one
     * is sugar for it, so these are not two types — a value erased at one is the value the other
     * holds, and a function declared with one takes what the other made.
     */
    "and the element may be named outright, which is the same type as the short form" in {
      run(upto + """var o: &Iterate[Item = int] = Upto(1, 3)
                   |for x in o do print(x)""".stripMargin) shouldBe "1\n2\n3\n"

      run(upto + """count(o: *Iterate[Item = int]) -> int
                   |    var n = 0
                   |    for _ in o do n += 1
                   |    n
                   |var c = Upto(1, 4)
                   |var short: *Iterate[int] = &c
                   |print(count(short))""".stripMargin) shouldBe "4\n"
    }

    /** An object over a trait that **requires** `Iterate` is walked too, since the element is read
     * out of the requirement closure — the same list the table is laid out from, so the loop cannot
     * disagree with the table about which member it is calling.
     */
    "and one over a trait that requires it, which is the closure being read rather than the name" in {
      run("""trait Counted: Iterate
            |    seen(self) -> int
            |
            |struct Upto
            |    at: int
            |    hi: int
            |
            |impl Iterate for Upto
            |    type Item = int
            |    next(*self) -> Option[int]
            |        if self.at > self.hi then None else
            |            var v = self.at
            |            self.at += 1
            |            Some(v)
            |
            |impl Counted for Upto
            |    seen(self) -> int = self.at
            |end Upto
            |
            |var o: &Counted[int] = Upto(1, 3)
            |
            |for x in o do print(x)
            |
            |print(o.seen())""".stripMargin) shouldBe "1\n2\n3\n4\n"
    }

    // The built-in walk of a slice is not something a program competes with, exactly as the
    // built-in subscript is not: an `impl Iterate` for a slice type registers a `next` a program
    // may call, and the loop still reads the elements the slice already has.
    "an 'Iterate' for a slice does not take over the built-in walk" in {
      run("""struct N
            |    v: int
            |impl Iterate for []N
            |    type Item = N
            |    next(*self) -> Option[N] = Some(N(99))
            |var xs = [N(1), N(2), N(3)]
            |for x in xs[..] do print(x.v)
            |var s = xs[..]
            |print(s.next().unwrap().v)""".stripMargin) shouldBe "1\n2\n3\n99\n"
    }

    // `05` — a cursor is a value, so one that views a local array is subject to the same escape
    // rule every other view is: the loop may walk it, and nothing may carry it out of the frame.
    "a cursor over a local array walks it and may not leave the frame" in {
      run("""struct Take
             |    xs: []int
             |    at: usize
             |end Take
             |impl Iterate for Take
             |    type Item = int
             |    next(*self) -> Option[int]
             |        if self.at >= self.xs.len then return None
             |        self.at += 1usize
             |        Some(self.xs[self.at - 1usize])
             |    end next
             |var a = [4, 5, 6]
             |for v in Take(a[..], 0usize) do print(v)""".stripMargin) shouldBe "4\n5\n6\n"
    }

    // The decoder's own edges: one scalar value on each side of every encoding-length boundary,
    // built with `str(char(n))` and read back with `chars`. `04` says the round trip from bytes to
    // text is closed at both ends now, and this is that claim as an assertion — the byte length
    // pins the encoder, the code points pin the decoder, and a decoder that got the lead-byte
    // classification wrong would disagree with one or the other at the first boundary.
    "every encoding length round-trips at its boundary" in {
      run("""var pts = [0x7Fu32, 0x80u32, 0x7FFu32, 0x800u32, 0xFFFFu32, 0x10000u32, 0x10FFFFu32]
             |var s = ""
             |for p in pts do s += str(char(p))
             |var i = 0
             |var bad = 0
             |for c in s.chars
             |    if u32(c) != pts[i] then bad += 1
             |    i += 1
             |print(s.len, i, bad)""".stripMargin) shouldBe "19 7 0\n"
    }

    // A cursor behind a reference is dereferenced on the way in, exactly as an array behind one is
    // (`03`). What the loop then drains is the copy it took, so the object keeps its position.
    "a cursor reached through a reference is dereferenced" in {
      run(upto + """var r: &Upto = Upto(1, 3)
                   |for i in *r do print(i)
                   |print(r.next().unwrap())""".stripMargin) shouldBe "1\n2\n3\n1\n"
    }

    // A `return` from inside the body leaves with the cursor still held, so the release for it has
    // to be on that path too. A cursor holding a reference is what makes the omission visible.
    //
    // The cursor is `Once` and not the `One` it was until the standard module declared a trait of
    // that name. The standard module is auto-imported, and an auto-import is a wildcard every file
    // starts with, so a program's own declaration of one of its names is **ambiguous** rather than a
    // shadowing — the same as `Option` or `Add` has always been.
    "a 'return' from inside the loop lets the cursor go" in {
      run("""struct Cell
             |    v: int
             |end Cell
             |struct Once
             |    c: &Cell
             |    done: bool
             |end Once
             |impl Iterate for Once
             |    type Item = int
             |    next(*self) -> Option[int]
             |        if self.done then return None
             |        self.done = true
             |        Some(self.c.v)
             |    end next
             |first(n: int) -> int
             |    for v in Once(Cell(n), false) do return v
             |    0
             |var total = 0
             |for i in 0..<5000 do total += first(i)
             |print(total)""".stripMargin) shouldBe "12497500\n"
    }

    // The loop gives its cursor a slot, and a zero-sized type has none (`reference/types.md § unit
    // and never`) — so the one shape that could have assumed storage where there is none is a
    // cursor with no state. It has to be an empty sequence: with nothing to advance, anything else
    // never finishes.
    "a cursor with no state is a slot the loop does not need" in {
      run("""struct Tick
             |    u: unit
             |end Tick
             |impl Iterate for Tick
             |    type Item = int
             |    next(*self) -> Option[int] = None
             |for x in Tick(()) do print(x)
             |print("through")""".stripMargin) shouldBe "through\n"
    }

    // A `Chars` a program built itself over bytes that are not a string has no validity invariant
    // behind it, and `chars_of` is public, so the walk has to decide what such bytes *are* rather
    // than assume they cannot arrive. It answers U+FFFD per ill-formed run, which is what
    // `from_utf8_lossy` does with the same table — so a lexer over a file somebody else wrote gets
    // a character it can report rather than a dead process.
    //
    // **This used to trap, and the trap was asserted here as the property that held**: `0xF0`
    // promises three continuation bytes, the decoder asked for one that was not there, and the
    // slice's bounds check stopped it. Safety was never in question — sysl checks the index — but a
    // public function that ends the program on ordinary untrusted input is not a thing a caller can
    // build a reader on, and `sh.sysl.parsing`'s whole scanner tier is built on exactly this.
    "a hand-built cursor over truncated bytes yields a replacement rather than trapping" in {
      run("""import sysl.text.chars_of
            |
            |var b = [0xF0u8]
            |for c in chars_of(b[..]) do print(u32(c))
            |print("through")""".stripMargin) shouldBe "65533\nthrough\n"
    }

    // `next` must give back an `Option` of the element type — the trait says so, and an
    // implementation that says otherwise is caught where the trait is implemented rather than at
    // the loop, since it is the implementation that broke the promise.
    "an implementation whose 'next' has the wrong shape is refused" in {
      err("""struct Bad
            |    n: int
            |end Bad
            |impl Iterate for Bad
            |    type Item = int
            |    next(*self) -> int = 0
            |""".stripMargin) should include("next")
    }
  }
}
