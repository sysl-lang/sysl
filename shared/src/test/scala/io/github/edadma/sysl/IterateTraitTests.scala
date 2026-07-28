package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Walking a sequence a program has to produce a value at a time — `for x in cursor` through the
  * prelude's `Iterate`, and `s.chars`, which is what decided the shape.
  *
  * The deciding question `14 §7` left open was whether iteration is something a *type* implements or
  * something `for` knows about, and `s.chars` is what settles it: a string cannot hand out a view of
  * its scalar values the way a container hands out a view of its storage, because the decoding is
  * what makes them. So there has to be a value that carries a position and answers "the next one",
  * and once there is, `for` accepting it is a smaller change than teaching `for` about strings.
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
      |impl Iterate[int] for Upto
      |    next(*self) -> Option[int]
      |        if self.at > self.last then return None
      |        self.at += 1
      |        Some(self.at - 1)
      |    end next
      |""".stripMargin

  "what the documents claim" - {
    // `14 §7` — the protocol's whole reason to exist is a sequence that is not storage.
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

    // `07 § Iterating` — the sequence is evaluated once. A `next` with a side effect would show a
    // second evaluation immediately.
    "the sequence expression is evaluated once" in {
      run(upto + """start() -> Upto
                   |    print("built")
                   |    Upto(1, 3)
                   |for i in start() do print(i)""".stripMargin) shouldBe "built\n1\n2\n3\n"
    }

    // `00 §10` — a loop's `else` runs on normal completion, and running out of elements is normal
    // completion. This is the claim being checked rather than assumed for a new loop form.
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

    // `00 §10` — a loop is an expression, and `break value` carries one out. The `else` supplies
    // the value on the path where the elements ran out.
    "the loop is an expression, and its 'break' carries a value" in {
      run(upto + """var found = for i in Upto(1, 9)
                   |    if i * i > 20 then break i
                   |else
                   |    0
                   |print(found)""".stripMargin) shouldBe "5\n"
    }

    // `08 § Built-in members` — a compiler-provided member is reached ahead of the member table, so
    // an `impl` may not declare one of the same name. `chars` joins `len` and `bytes` in that rule.
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

    // Two implementations of one trait on one type is legal (`02`), and every other member picks
    // between them by its arguments — but `next` takes none, so the loop has nothing to decide
    // with. Reported at the loop, where the sentence a program needs can name it.
    "a type implementing 'Iterate' twice leaves a 'for' nothing to choose with" in {
      val two =
        """struct Both
          |    n: int
          |end Both
          |impl Iterate[int] for Both
          |    next(*self) -> Option[int] = None
          |impl Iterate[bool] for Both
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
             |impl Iterate[&Cell] for Cells
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

    // `10 §5` — a bound promises behaviour, and `Iterate`'s behaviour is a method, so a generic
    // body may call it. This is the probe that says whether the protocol reaches generic code at
    // all, and it does: `next` is an ordinary bounded method call.
    "a bound promising 'Iterate' licenses the call in a generic body" in {
      run(upto + """total[T: Iterate[int]](it: T) -> int
                   |    var sum = 0
                   |    var cur = it
                   |    loop
                   |        var got = cur.next()
                   |        if got.is_none() then break
                   |        sum += got.unwrap()
                   |    sum
                   |print(total(Upto(1, 4)))""".stripMargin) shouldBe "10\n"
    }

    // …and so does the loop form, which was worth probing rather than assuming: the loop looks for
    // an implementation filed under the receiver's *type*, and a type parameter has none — but
    // `10 §7`'s per-instantiation lowering means the body is analyzed once `T` is a real type, so
    // by the time the loop asks, there is one. A generic walk over any cursor needs nothing added.
    "a 'for' over a bounded type parameter walks it" in {
      run(upto + """walk[T: Iterate[int]](it: T)
                   |    for x in it do print(x)
                   |walk(Upto(1, 2))""".stripMargin) shouldBe "1\n2\n"
    }

    // `02 § An object keeps one trait and what that trait requires` — an erased value reaches its
    // trait's members through a table, and `next` is object-safe (`*self`, no `Self` away from the
    // receiver). What a `for` cannot do is find an *implementation* for an erased type, since there
    // is none: the object is the implementation. Both halves are pinned, because the second is the
    // one a program would be surprised by.
    "an erased cursor answers 'next' and is still not what a 'for' takes" in {
      run(upto + """var o: &Iterate[int] = Upto(1, 2)
                   |print(o.next().unwrap())
                   |print(o.next().unwrap())
                   |print(o.next().is_none())""".stripMargin) shouldBe "1\n2\ntrue\n"

      err(upto + """var o: &Iterate[int] = Upto(1, 2)
                   |for x in o do print(x)""".stripMargin) should include("Iterate")
    }

    // The built-in walk of a slice is not something a program competes with, exactly as the
    // built-in subscript is not: an `impl Iterate` for a slice type registers a `next` a program
    // may call, and the loop still reads the elements the slice already has.
    "an 'Iterate' for a slice does not take over the built-in walk" in {
      run("""impl Iterate[int] for []int
            |    next(*self) -> Option[int] = Some(99)
            |var xs = [1, 2, 3]
            |for x in xs[..] do print(x)
            |var s = xs[..]
            |print(s.next().unwrap())""".stripMargin) shouldBe "1\n2\n3\n99\n"
    }

    // `05` — a cursor is a value, so one that views a local array is subject to the same escape
    // rule every other view is: the loop may walk it, and nothing may carry it out of the frame.
    "a cursor over a local array walks it and may not leave the frame" in {
      run("""struct Take
             |    xs: []int
             |    at: usize
             |end Take
             |impl Iterate[int] for Take
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
    "a 'return' from inside the loop lets the cursor go" in {
      run("""struct Cell
             |    v: int
             |end Cell
             |struct One
             |    c: &Cell
             |    done: bool
             |end One
             |impl Iterate[int] for One
             |    next(*self) -> Option[int]
             |        if self.done then return None
             |        self.done = true
             |        Some(self.c.v)
             |    end next
             |first(n: int) -> int
             |    for v in One(Cell(n), false) do return v
             |    0
             |var total = 0
             |for i in 0..<5000 do total += first(i)
             |print(total)""".stripMargin) shouldBe "12497500\n"
    }

    // The loop gives its cursor a slot, and a zero-sized type has none (`00 §12`) — so the one
    // shape that could have assumed storage where there is none is a cursor with no state. It has
    // to be an empty sequence: with nothing to advance, anything else never finishes.
    "a cursor with no state is a slot the loop does not need" in {
      run("""struct Tick
             |    u: unit
             |end Tick
             |impl Iterate[int] for Tick
             |    next(*self) -> Option[int] = None
             |for x in Tick(()) do print(x)
             |print("through")""".stripMargin) shouldBe "through\n"
    }

    // The decoder trusts `string`'s validity invariant, and a `Chars` a program built itself over
    // bytes that are not a string has no such invariant behind it — so the property that has to
    // hold is the safety one rather than the correctness one. A truncated lead byte makes the
    // decoder ask for a continuation byte that is not there, and the slice's own bounds check is
    // what stops it: the read traps rather than running off the end.
    "a hand-built cursor over truncated bytes traps rather than reading past the end" in {
      exits("""var b = [0xF0u8]
              |for c in chars_of(b[..]) do print(c)""".stripMargin)
    }

    // `next` must give back an `Option` of the element type — the trait says so, and an
    // implementation that says otherwise is caught where the trait is implemented rather than at
    // the loop, since it is the implementation that broke the promise.
    "an implementation whose 'next' has the wrong shape is refused" in {
      err("""struct Bad
            |    n: int
            |end Bad
            |impl Iterate[int] for Bad
            |    next(*self) -> int = 0
            |""".stripMargin) should include("next")
    }
  }
}
