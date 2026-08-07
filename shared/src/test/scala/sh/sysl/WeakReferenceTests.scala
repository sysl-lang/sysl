package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** `weak T` — the reference that does not keep its referent alive (`03 § weak T`).
 *
 * The feature is two conversions and one call. A `&T` becomes a `weak T` wherever one is asked
 * for, `get()` asks the box whether the object is still there and hands back `Option[&T]`, and
 * `None` is what an empty one is written as — the same answer `get()` gives for one. Everything
 * else about it is what the box's third header word does at run time, which is what the run tests
 * here are for: an assertion about emitted text cannot tell a count that is taken from one that is
 * taken twice.
 */
class WeakReferenceTests extends AnyFreeSpec with CodegenSupport with RunSupport {

  private val node = "struct Node\n    value: int\n"

  "a reference becomes weak wherever a weak one is asked for" - {

    "a field of a struct being constructed" in {
      run(node + """    up: weak Node
                   |var r: &Node = Node(1, None)
                   |var k: &Node = Node(2, r)
                   |print(k.up.get().unwrap().value)
                   |""".stripMargin) shouldBe "1\n"
    }

    "an argument" in {
      run(node + """peek(w: weak Node) -> int = w.get().unwrap().value
                   |var r: &Node = Node(5)
                   |print(peek(r))
                   |""".stripMargin) shouldBe "5\n"
    }

    "a declared local, and an assignment into one" in {
      run(node + """var r: &Node = Node(3)
                   |var s: &Node = Node(4)
                   |var w: weak Node = r
                   |print(w.get().unwrap().value)
                   |w = s
                   |print(w.get().unwrap().value)
                   |""".stripMargin) shouldBe "3\n4\n"
    }

    "a returned value" in {
      run(node + """weaken(r: &Node) -> weak Node = r
                   |var r: &Node = Node(9)
                   |print(weaken(r).get().unwrap().value)
                   |""".stripMargin) shouldBe "9\n"
    }

    "and an element of a slice of them" in {
      run(node + """var r: &Node = Node(1)
                   |var s: &Node = Node(2)
                   |var ws: []weak Node = [r, s]
                   |print(ws[0].get().unwrap().value, ws[1].get().unwrap().value)
                   |""".stripMargin) shouldBe "1 2\n"
    }

    // The chapter says a weak reference is written "in the same places" the other two modes are,
    // which makes the positions above a sample and not the list. These are the rest of the places a
    // '&T' reaches, kept because "the same places" is the kind of claim that stays true only while
    // something asks: an array element, a tuple part, a generic argument, and the parameter of a
    // callable type — the last being a separate mechanism from a bare arrow rather than a spelling
    // of it, so passing there is no evidence about passing here.
    "an array element, a tuple part, and a generic argument" in {
      run(node + """var r: &Node = Node(1)
                   |var s: &Node = Node(2)
                   |var arr: [2]weak Node = [r, s]
                   |var tu: (weak Node, int) = (r, 3)
                   |var op: Option[weak Node] = Some(s)
                   |print(arr[1].get().unwrap().value, tu.0.get().unwrap().value, tu.1)
                   |print(op.unwrap().get().unwrap().value)
                   |""".stripMargin) shouldBe "2 1 3\n2\n"
    }

    "the parameter of a callable type" in {
      run(node + """peek(w: weak Node) -> int = w.get().unwrap().value
                   |var r: &Node = Node(7)
                   |var f: &Fn(weak Node) -> int = peek
                   |print(f(r))
                   |""".stripMargin) shouldBe "7\n"
    }

    // The one position a 'weak T' does NOT reach, and it is worth two tests because the reason is
    // not a rule about weak references at all. A default is produced afresh at each call that omits
    // it, in a scope holding no locals, so what it names has to outlive every frame — and the only
    // two things that could hand it a 'weak Node' are both closed. A construction is refused for
    // having nowhere to live (asserted below, under "and only a reference does"), which leaves a
    // name, and neither kind of name works.
    "but not a default parameter value, because nothing a default may name can produce one" - {

      // A top-level 'var' is a local of the entry point, so a default reading one is reading the
      // caller's locals — which is what the scope emptied for defaults is there to prevent.
      "not a top-level 'var', which is a local of the caller however far above it is written" in {
        err(node + """var fallback: &Node = Node(4)
                     |peek(w: weak Node = fallback) -> int = w.get().unwrap().value
                     |print(peek())
                     |""".stripMargin) should include("undefined name 'fallback'")
      }

      // And the declaration that does outlive every frame cannot hold a reference in the first
      // place, which closes the other half.
      "and not a module-level 'val', which outlives every frame and so counts nothing" in {
        err(node + """static val fallback: &Node = Node(4)
                     |peek(w: weak Node = fallback) -> int = w.get().unwrap().value
                     |print(peek())
                     |""".stripMargin) should include("a count with nowhere to write the release")
      }
    }
  }

  "and only a reference does" - {

    // The value would be boxed and the weak edge would be the only thing holding the box, so the
    // object would be gone before the statement finished. Named rather than allowed, because a
    // reference that is empty the instant it is made is never what anybody meant.
    "a construction with nowhere else to live is refused, and says where to put it" in {
      err(node + "var w: weak Node = Node(1)") should include(
        "a weak reference does not keep Node alive, and nothing else here holds this one")
    }

    /** The advice that message gives has to be **writable**, which is the whole of what advice is.
     * `weak T` already means a weak edge to a counted `T`, so someone who wrote the `&` as well has
     * written the mode twice — and telling them to hold it in a `&&Node` would be sending them to a
     * spelling the parser does not take. The reply is what they meant instead.
     */
    "a doubled mode is told what it already means, not sent to a spelling that does not parse" in {
      val out = err(node + "var r: &Node = Node(1)\nvar w: weak &Node = r")

      out should include("'weak Node' is already a weak edge to a counted Node")
      out should not include "'&&Node'"

      // The parser is why: the advice that used to be given cannot be typed. `&&` is one token, so
      // what the sigil is told it wants is the type name that is not behind it.
      err(node + "var r: &&Node = Node(1)") should include("identifier expected")
    }

    "'null' is not the empty weak reference, and the message says what is" in {
      err(node + "var w: weak Node = null") should include(
        "'null' is a raw pointer, and an empty weak Node is written 'None'")
    }

    // `&sync` is a distinct type from `&`, so weakening one is a distinct question, and the answer
    // is a chapter that is not built (`03 § weak sync T`).
    "and an atomic reference has no weak form yet" in {
      err(node + "var w: weak sync Node = None") should include(
        "wants the concurrency model of '06'")
    }
  }

  // A weak edge takes a word in the referent's header and gives it back when the edge goes, so it
  // is counted in the sense `13 §7` means: storage that lasts the whole run has nowhere to write
  // that release. It reads as the outlier of the four refused types, because it is the one that
  // keeps nothing alive — but what it owes is a decrement, not a lifetime.
  "a weak reference is counted too, so a module-level 'val' refuses one" - {
    "directly" in {
      err(node + """weaken(r: &Node) -> weak Node = r
                   |static val w: weak Node = weaken(Node(1))
                   |""".stripMargin) should include("a count with nowhere to write the release")
    }

    "and inside a struct, which is the recursive half of the same rule" in {
      err(node + """struct Slot
                   |    back: weak Node
                   |end Slot
                   |mk(r: &Node) -> Slot = Slot(r)
                   |static val s: Slot = mk(Node(1))
                   |""".stripMargin) should include("a count with nowhere to write the release")
    }
  }

  "'get()' is the only thing a weak reference answers" - {

    "so a field of the referent is not read through one" in {
      err(node + """    up: weak Node
                   |var r: &Node = Node(1, None)
                   |print(r.up.value)
                   |""".stripMargin) should include(
        "a weak Node may be gone, so nothing is read off one directly — 'get()' hands back " +
          "'Option[&Node]', and 'value' is read off what is inside it")
    }

    "nor is a method of it called through one" in {
      err(node + """    twice(self) -> int = self.value * 2
                   |var r: &Node = Node(1)
                   |var w: weak Node = r
                   |print(w.twice())
                   |""".stripMargin) should include(
        "a weak Node has no method 'twice' — a weak reference may be gone, so 'get()' is the only " +
          "thing to ask one")
    }

    "and 'get' takes nothing" in {
      err(node + "var r: &Node = Node(1)\nvar w: weak Node = r\nprint(w.get(1))") should include(
        "'get' takes no arguments")
    }

    // What comes back is an ordinary `Option`, so the whole of its surface applies with no rule of
    // its own — which is the reason for handing back one rather than inventing a form.
    "what it hands back is an ordinary Option" in {
      run(node + """var r: &Node = Node(6)
                   |var w: weak Node = r
                   |print(w.get().is_some(), w.get().unwrap_or(r).value)
                   |""".stripMargin) shouldBe "true 6\n"
    }
  }

  "the object goes when its strong references do, and the weak one says so" - {

    // The whole of the feature in one program: inside the block a strong reference holds the
    // object and `get()` finds it, outside there is none and `get()` finds nothing. A weak edge
    // that kept the object alive would print `true` twice; one that dangled would not print at all.
    "a weak reference reads 'None' once the last strong one is gone" in {
      run(node + """var w: weak Node = None
                   |if true
                   |    var n: &Node = Node(7)
                   |    w = n
                   |    print("inside", w.get().is_some())
                   |print("after", w.get().is_some())
                   |""".stripMargin) shouldBe "inside true\nafter false\n"
    }

    "and holding it weakly is not holding it" in {
      run(node + """var w: weak Node = None
                   |var v: weak Node = None
                   |if true
                   |    var n: &Node = Node(7)
                   |    w = n
                   |    v = n
                   |print(w.get().is_some(), v.get().is_some())
                   |""".stripMargin) shouldBe "false false\n"
    }

    // The referent's storage outlives the object exactly as long as a weak reference is still
    // asking about it, so reading a dead one is a load rather than a fault — the run is what shows
    // that, since a use-after-free would as often as not print the old value.
    "asking a dead one is safe, however many times it is asked" in {
      run(node + """var w: weak Node = None
                   |if true
                   |    var n: &Node = Node(7)
                   |    w = n
                   |var i = 0
                   |var seen = 0
                   |while i < 100000
                   |    if w.get().is_some() then seen += 1
                   |    i += 1
                   |print(seen)
                   |""".stripMargin) shouldBe "0\n"
    }

    // The cycle ARC cannot reclaim, made reclaimable. Both objects come back every iteration, so a
    // hundred thousand of them fit in whatever memory the test machine has; with the back-edge
    // written `&Node` instead, none of them ever would.
    "a parent-and-child cycle comes apart when the back-edge is weak" in {
      run(node + """    up: weak Node
                   |    down: Option[&Node]
                   |pair(v: int) -> int
                   |    var p: &Node = Node(v, None, None)
                   |    var c: &Node = Node(v + 1, p, None)
                   |    p.down = Some(c)
                   |    return c.up.get().unwrap().value
                   |end pair
                   |var i = 0
                   |while i < 100000
                   |    if pair(i) != i then
                   |        print("wrong")
                   |        exit(1)
                   |    i += 1
                   |print("ok")
                   |""".stripMargin) shouldBe "ok\n"
    }

    // The other half of that pair, and the half a program can *use*: with the back-edge strong the
    // objects are unreachable and still alive, and a weak reference is the only thing that can say
    // so. sysl has no user-facing destructor to hang a live-object count on (`03`'s open list), so
    // this is what makes a leak an observation rather than a suspicion — `guide/lisp` counts an
    // interpreter's environments with nothing else.
    //
    // The chain is what makes the cycle mean something. Both build two objects the same way and drop
    // every reference from outside; only the back-edge differs, so a `weak` that answered `true` for
    // both would be reporting on nothing.
    "and stands, observably, when it is not — which is how a leak is counted at all" in {
      run(node + """    next: Option[&Node]
                   |cycle() -> weak Node
                   |    var a: &Node = Node(1, None)
                   |    var b: &Node = Node(2, None)
                   |    a.next = Some(b)
                   |    b.next = Some(a)
                   |    var w: weak Node = a
                   |    w
                   |end cycle
                   |chain() -> weak Node
                   |    var a: &Node = Node(1, None)
                   |    var b: &Node = Node(2, None)
                   |    a.next = Some(b)
                   |    var w: weak Node = a
                   |    w
                   |end chain
                   |var leaked: weak Node = cycle()
                   |var freed: weak Node = chain()
                   |print(leaked.get().is_some(), freed.get().is_none())
                   |print(leaked.get().unwrap().value)
                   |""".stripMargin) shouldBe "true true\n1\n"
    }
  }

  "an empty weak reference is written the way an empty answer reads" - {

    "'None' makes one, and 'get()' gives the same 'None' back" in {
      run(node + """var w: weak Node = None
                   |print(w.get().is_none())
                   |""".stripMargin) shouldBe "true\n"
    }

    "a struct is built with one, and a field of one is emptied by assigning it" in {
      run(node + """    up: weak Node
                   |var r: &Node = Node(1, None)
                   |var k: &Node = Node(2, r)
                   |print(k.up.get().is_some())
                   |k.up = None
                   |print(k.up.get().is_some())
                   |""".stripMargin) shouldBe "true\nfalse\n"
    }

    // A struct declared with no initializer starts at its zero value, and a weak field's zero has
    // to be the empty reference rather than an address nothing wrote — the run is what shows it,
    // since the difference between the two is a fault.
    "and a weak field of a zero-valued struct is empty rather than wild" in {
      run(node + """    up: weak Node
                   |var z: Node
                   |print(z.value, z.up.get().is_none())
                   |""".stripMargin) shouldBe "0 true\n"
    }
  }

  "a weak reference is a value like any other, and counted like one" - {

    // Copying one takes a share of the *weak* count, which is what keeps the storage there for the
    // copy to read; a copy that took nothing would leave the second reader looking at freed bytes.
    "copying one keeps the storage there for the copy" in {
      run(node + """var w: weak Node = None
                   |if true
                   |    var n: &Node = Node(7)
                   |    w = n
                   |var i = 0
                   |while i < 100000
                   |    var c = w
                   |    if c.get().is_some() then
                   |        print("alive")
                   |        exit(1)
                   |    i += 1
                   |print("ok")
                   |""".stripMargin) shouldBe "ok\n"
    }

    "one held inside an enum comes apart with it" in {
      run(node + """enum Edge
                   |    Up(w: weak Node)
                   |    Root
                   |var r: &Node = Node(3)
                   |var e = Up(r)
                   |var seen = e match
                   |    Up(w) -> w.get().unwrap().value
                   |    Root -> 0
                   |print(seen)
                   |""".stripMargin) shouldBe "3\n"
    }

    "and a trait object may be held weakly too" in {
      run("""trait Speak
            |    say(self) -> int
            |struct Dog
            |    n: int
            |impl Speak for Dog
            |    say(self) -> int = self.n
            |var d: &Speak = Dog(4)
            |var w: weak Speak = d
            |print(w.get().unwrap().say())
            |""".stripMargin) shouldBe "4\n"
    }

    "an array of them is a field like any other" in {
      run(node + """    kin: [2]weak Node
                   |var a: &Node = Node(1, [None, None])
                   |print(a.kin[0].get().is_none())
                   |""".stripMargin) shouldBe "true\n"
    }

    "an Option may hold one, since nothing about it is special downstream" in {
      run(node + """var a: &Node = Node(1)
                   |var w: weak Node = a
                   |var o = Some(w)
                   |print(o.unwrap().get().unwrap().value)
                   |""".stripMargin) shouldBe "1\n"
    }

    // The read takes a share before the write gives one back, so the one count in play is not the
    // one being dropped — the same ordering an assignment between two `&T` needs, reached here
    // through the weak count instead.
    "and assigning one to itself keeps it" in {
      run(node + """var a: &Node = Node(1)
                   |var w: weak Node = a
                   |w = w
                   |print(w.get().unwrap().value)
                   |""".stripMargin) shouldBe "1\n"
    }
  }

  /** Probes of what the neighbouring chapters claim, each of which would have been a hole. They are
   * here because they were run, not because any of them failed.
   */
  "what a weak reference is not" - {

    // `03`'s three modes are ways of *holding* a type, and only one of them is refused as an
    // `impl` subject for a reason of its own: a member call reaches through a `*T` and a `&T`, and
    // through a weak reference it reaches nothing.
    "an 'impl' may not be written for one" in {
      err("""trait Show
            |    show(self) -> string
            |struct Node
            |    value: int
            |impl Show for weak Node
            |    show(self) -> string = "x"
            |""".stripMargin) should include(
        "and nothing goes through one but 'get()', so a member written here could never be called")
    }

    // Two weak references to one object are equal in a sense the machine can answer, and two to
    // objects that are both gone are a question it cannot — so the catalog leaves weak references
    // out and the comparison is written on what `get()` hands back.
    "'==' is not defined on one" in {
      err(node + """var a: &Node = Node(1)
                   |var w: weak Node = a
                   |var v: weak Node = a
                   |print(w == v)
                   |""".stripMargin) should include("'==' is not defined for weak Node")
    }

    "and neither is printing one" in {
      err(node + "var a: &Node = Node(1)\nvar w: weak Node = a\nprint(w)") should include(
        "cannot print a weak Node value — it does not implement 'sysl.Display'")
    }

    // `13 §2` — a declaration may not be more visible than the types it names, and the mode in
    // front of a name changes nothing about which names it holds.
    "a public field may not name a private type through one" in {
      err("""private struct Hidden
            |    v: int
            |struct Open
            |    up: weak Hidden
            |""".stripMargin) should include("'Open.up' is public, but its type names 'Hidden'")
    }
  }

  "what a weak reference is generic over, and what holds it up" - {

    "a type parameter may be weakened, and a '&T' argument settles it" in {
      run(node + """alive[T](w: weak T) -> bool = w.get().is_some()
                   |var a: &Node = Node(1)
                   |print(alive(a))
                   |""".stripMargin) shouldBe "true\n"
    }

    "a generic type may be held weakly at one instantiation" in {
      run("""struct Box[T]
            |    v: T
            |var b: &Box[int] = Box(3)
            |var w: weak Box[int] = b
            |print(w.get().unwrap().v)
            |""".stripMargin) shouldBe "3\n"
    }

    // Whatever a `&T` may point at, a `weak T` may be taken of — a scalar and an array included,
    // since both are things the language boxes.
    "and so may anything else a reference points at" in {
      run("""var a: &[3]int = [1, 2, 3]
            |var w: weak [3]int = a
            |print(w.get().unwrap()[0])
            |""".stripMargin) shouldBe "1\n"
    }

    // Returning one is legal and answers honestly: the object was alive when the reference was
    // weakened and gone by the time the caller looked, which is the state the form exists to
    // report rather than a mistake to refuse.
    "a weak reference to something only the callee held reads empty at the caller" in {
      run(node + """make() -> weak Node
                   |    var n: &Node = Node(4)
                   |    return n
                   |end make
                   |print(make().get().is_some())
                   |""".stripMargin) shouldBe "false\n"
    }
  }

  "what the box carries, and who pays for it" - {

    // The third word is in every box whether or not the program holds a weak reference, because
    // releasing one is type-erased and two layouts would need a word to tell them apart
    // (`03 § What it costs`).
    "every box carries the weak count, weak reference or not" in {
      ir(node + "var r: &Node = Node(1)\nprint(r.value)") should
        include("%arc.Node = type { i64, ptr, i64, %struct.Node }")
    }

    "and so does a buffer, whose elements come after it" in {
      ir("f(n: usize) -> []int = [0; n]\nprint(f(2usize).len)") should
        include("%arc.buf.int = type { i64, ptr, i64, i64, [0 x i32] }")
    }

    // The three functions that *read* that word are another matter: only a program with a weak
    // reference in it calls them, so only one gets them.
    "the three functions that read it are emitted only where one is held" in {
      val without = ir(node + "var r: &Node = Node(1)\nprint(r.value)")

      without should not include "@arc.upgrade"
      without should not include "@arc.weak_retain"
    }

    "and are emitted where one is" in {
      val out = ir(node + "var r: &Node = Node(1)\nvar w: weak Node = r\nprint(w.get().is_some())")

      out should include("define private ptr @arc.upgrade(ptr %p) {")
      out should include("call void @arc.weak_retain(ptr")
      out should include("call void @arc.weak_release(ptr")
    }

    // Weakening touches the third word and leaves the first alone — that asymmetry *is* the
    // feature, so it is pinned in the text as well as in the runs above.
    "weakening takes a weak share and no strong one" in {
      val body = mainOf(ir(node + "var r: &Node = Node(1)\nvar w: weak Node = r\nprint(w.get().is_some())"))

      body should include regex raw"call void @arc\.weak_retain\(ptr %t\d+\)\n  store ptr %t\d+, ptr %w\.addr"
    }
  }
}
