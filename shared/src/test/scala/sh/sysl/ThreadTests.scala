package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** `sysl.posix.threads` — starting a thread, waiting for one, and the lock that owns what it protects.
 *
 * This is the first suite in which a sysl program has **two threads in it**, so it is the first
 * place several things stop being claims about emitted text. The per-thread reaper worklist is the
 * one that has been waiting longest: its storage class was asserted in the IR and a single-threaded
 * drain was run, and neither of those says anything about the case the whole change exists for,
 * which is two threads draining at once.
 *
 * **A concurrency test that passes proves less than an ordinary one**, and the tests below are
 * shaped around that. A lock that did nothing at all would still produce the right total on a fast
 * enough machine with a short enough loop, so the contended counts here are long enough that a
 * missing lock loses increments in practice, and what each test claims is written down beside it.
 * The orderings themselves cannot be established by running at all — this machine would give the
 * right answer whatever was asked for — so they are read out of the emitted text.
 */
class ThreadTests extends AnyFreeSpec with RunSupport with CodegenSupport {

  private val importing = "import sysl.sync.*\nimport sysl.posix.threads.*\n\n"

  override protected def run(src: String): String = super.run(importing + src)

  override protected def ir(src: String): String = super.ir(importing + src)

  override protected def err(src: String): String = super.err(importing + src)

  /** A `&sync` chain and a counter to hang the reaper tests off, kept out of the tests themselves so
   * that a change to the shape does not have to be made in three places.
   */
  private val chain =
    """struct Node
      |    value: int
      |    next: Option[&sync Node]
      |
      |build(n: int) -> &sync Node
      |    var head: &sync Node = Node(0, None)
      |    var i = 1
      |
      |    while i < n
      |        head = Node(i, Some(head))
      |        i++
      |
      |    head
      |""".stripMargin

  "starting a thread and waiting for one" - {
    "a body runs, and the join is what makes its output land before the line after it" in {
      run(
        """say(p: *int)
          |    print("thread saw", *p)
          |
          |var n = 7
          |var t = spawn(&say, &n).unwrap()
          |var waited = t.join()
          |
          |print("joined", waited)""".stripMargin
      ) shouldBe "thread saw 7\njoined true\n"
    }

    /** The discriminating test for `spawn` actually spawning: a body called *directly* would print
     * the same thing as one on a new thread in every test above, and this is the one that can tell
     * the difference. `pthread_self` is the thread, so the two handles differ exactly when there
     * were two threads.
     */
    "the body is on a thread of its own, which is what tells this apart from a call" in {
      run(
        """struct Seen
          |    caller: usize
          |    same: bool
          |
          |look(s: *Seen)
          |    s.same = current().id == s.caller
          |
          |var s = Seen(current().id, true)
          |
          |spawn(&look, &s).unwrap().join()
          |print(s.same)""".stripMargin
      ) shouldBe "false\n"
    }

    /** `T` is inferred from the body, and `null` is what that inference cannot be given: it takes
     * its type from its context and the context is the very thing being inferred. So an address is
     * always written — which costs a body with nothing of its own to read nothing at all, since it
     * is handed the address of whatever it reads instead.
     */
    "a body with nothing of its own is handed the address of what it reads" in {
      run(
        """var ticks = 0
          |
          |tick(n: *int)
          |    *n = *n + 1
          |
          |spawn(&tick, &ticks).unwrap().join()
          |print(ticks)""".stripMargin
      ) shouldBe "1\n"
    }

    "and a null cannot stand in for that address, since nothing would say what it points at" in {
      err(
        """alone(p: *int)
          |    print("nothing passed")
          |
          |spawn(&alone, null).unwrap().join()""".stripMargin
      ) should include("'null' takes its type from its context")
    }

    "several threads each get their own argument" in {
      run(
        """struct Slot
          |    n: int
          |    doubled: int
          |
          |double(s: *Slot)
          |    s.doubled = s.n * 2
          |
          |var a = Slot(1, 0)
          |var b = Slot(2, 0)
          |var c = Slot(3, 0)
          |var ta = spawn(&double, &a).unwrap()
          |var tb = spawn(&double, &b).unwrap()
          |var tc = spawn(&double, &c).unwrap()
          |
          |ta.join()
          |tb.join()
          |tc.join()
          |print(a.doubled, b.doubled, c.doubled)""".stripMargin
      ) shouldBe "2 4 6\n"
    }

    "and yielding answers that the system took the hint" in {
      run("print(yield_now())") shouldBe "true\n"
    }

    // A thread starts threads of its own, which nothing in the module treats as a special case —
    // `pthread_create` does not care which thread called it, and the reaper's worklist being per
    // thread is what keeps the inner one from finding the outer one's list.
    "a thread may start threads of its own" in {
      run(
        """struct Tree
          |    depth: Atomic[int]
          |
          |leaf(t: *Tree)
          |    t.depth.add(1)
          |
          |branch(t: *Tree)
          |    var a = spawn(&leaf, t).unwrap()
          |    var b = spawn(&leaf, t).unwrap()
          |
          |    a.join()
          |    b.join()
          |    t.depth.add(10)
          |
          |var t = Tree(Atomic(0))
          |
          |spawn(&branch, &t).unwrap().join()
          |print(t.depth.load())""".stripMargin
      ) shouldBe "12\n"
    }
  }

  "the lock that owns what it protects" - {
    "hands back the address of what it holds, and the value survives the round trip" in {
      run(
        """var m = Mutex.new(41)
          |var p = m.lock()
          |
          |*p = *p + 1
          |m.unlock()
          |print(*m.lock())""".stripMargin
      ) shouldBe "42\n"
    }

    "is taken by the first to ask and refused to the next, and free again once released" in {
      run(
        """var m = Mutex.new(0)
          |
          |print(m.try_lock().is_some(), m.try_lock().is_none())
          |m.unlock()
          |print(m.try_lock().is_some())""".stripMargin
      ) shouldBe "true true\ntrue\n"
    }

    /** The test the type exists for. Two threads, twenty thousand increments each, and the total is
     * exact — a read-modify-write split across the lock is what a broken lock loses, and at this
     * length it loses them in practice rather than in principle.
     */
    "two threads incrementing through it lose nothing" in {
      run(
        s"""struct Job
           |    m: Mutex[int]
           |    rounds: int
           |
           |bump(j: *Job)
           |    var i = 0
           |
           |    while i < j.rounds
           |        var p = j.m.lock()
           |
           |        defer j.m.unlock()
           |        *p = *p + 1
           |        i = i + 1
           |
           |var j = Job(Mutex.new(0), 20000)
           |var a = spawn(&bump, &j).unwrap()
           |var b = spawn(&bump, &j).unwrap()
           |
           |a.join()
           |b.join()
           |print(*j.m.lock())""".stripMargin
      ) shouldBe "40000\n"
    }

    // `defer` is block-scoped, so the release above is at the end of each pass round the loop rather
    // than at the end of the body — which the total already proves, since a lock held to the end of
    // `bump` would deadlock the second thread rather than miscount.
    "and the release a 'defer' writes happens at the end of its block" in {
      run(
        """var m = Mutex.new(0)
          |var i = 0
          |
          |while i < 3
          |    var p = m.lock()
          |
          |    defer m.unlock()
          |    *p = *p + 1
          |    i = i + 1
          |
          |print(*m.lock())""".stripMargin
      ) shouldBe "3\n"
    }

    // What it holds is a whole value rather than a word, which is the case the type exists for: a
    // struct is what a lock guards and an `Atomic[T]` cannot.
    "holds an aggregate, which is what a lock is for and an atomic is not" in {
      run(
        """struct Ledger
          |    hits: int
          |    last: int
          |
          |var m = Mutex.new(Ledger(0, 0))
          |var p = m.lock()
          |
          |p.hits = p.hits + 1
          |p.last = 9
          |m.unlock()
          |
          |var q = m.lock()
          |
          |print(q.hits, q.last)""".stripMargin
      ) shouldBe "1 9\n"
    }

    /** `&sync Mutex[T]` is the shape `06` calls the ordinary answer: the lock is in the counted
     * object rather than beside it, so the object's own atomic count is what keeps it alive while
     * two threads hold it. The receiver reaches through the reference, which is what makes it
     * usable at all — a `*self` method wants a place, and a counted reference is one.
     */
    "and works through the counted reference the chapter reaches it by" in {
      run(
        """var m: &sync Mutex[int] = Mutex.new(4)
          |var p = m.lock()
          |
          |*p = *p * 10
          |m.unlock()
          |print(*m.lock())""".stripMargin
      ) shouldBe "40\n"
    }

    /** Eight threads on a machine with fewer processors is the case the yield exists for: a waiter
     * cannot make progress by spinning, because the holder is not running. A `SpinLock` here would
     * burn a quantum per waiter per round; this gives the processor up and the total still comes
     * out exact.
     */
    "more threads than the machine has processors still finish, since a waiter yields" in {
      run(
        """struct Job
          |    m: Mutex[int]
          |    rounds: int
          |
          |bump(j: *Job)
          |    var i = 0
          |
          |    while i < j.rounds
          |        var p = j.m.lock()
          |
          |        defer j.m.unlock()
          |        *p = *p + 1
          |        i = i + 1
          |
          |var j = Job(Mutex.new(0), 5000)
          |var ts = [spawn(&bump, &j).unwrap(), spawn(&bump, &j).unwrap(),
          |          spawn(&bump, &j).unwrap(), spawn(&bump, &j).unwrap(),
          |          spawn(&bump, &j).unwrap(), spawn(&bump, &j).unwrap(),
          |          spawn(&bump, &j).unwrap(), spawn(&bump, &j).unwrap()]
          |
          |for t in ts
          |    t.join()
          |
          |print(*j.m.lock())""".stripMargin
      ) shouldBe "40000\n"
    }

    /** An ordering is a keyword in the emitted instruction and this machine would compute the right
     * total whichever one was written, so the pairing that makes the protected data safe to touch
     * has to be read out of the text. Acquire on the way in, release on the way out, and the spin
     * between attempts on a plain relaxed load.
     */
    "takes with an acquire and releases with a release" in {
      val out = ir("var m = Mutex.new(0)\nm.lock()\nm.unlock()")

      out should include regex raw"atomicrmw xchg ptr %t\d+, i32 1 acquire"
      out should include regex raw"store atomic i32 0, ptr %t\d+ release"
      out should include regex raw"load atomic i32, ptr %t\d+ monotonic"
    }
  }

  /** `06 § Refcount ordering` fixes the sequence an atomic reference count moves through, and says
   * why in one line: getting it wrong produces a bug nobody finds. Nothing a program can run would
   * notice — a stronger sequence is correct and a weaker one fails on a machine this is not — so it
   * is asserted where it is written.
   */
  "the atomic refcount moves the way the chapter says" - {
    "a relaxed increment, a releasing decrement, and an acquire fence before the free" in {
      val out = ir(chain + "var list: &sync Node = Node(1, None)")

      out should include("atomicrmw add ptr %p, i64 1 monotonic")
      out should include("atomicrmw sub ptr %p, i64 1 release")
      out should include("fence acquire")
    }

    // The non-atomic path is what a plain `&T` gets, and it is the same arithmetic with none of
    // this: a `&sync` that lowered to it would be the race the spelling exists to prevent.
    "while a plain reference counts with ordinary loads and stores" in {
      val out = ir(chain.replace("&sync Node", "&Node") + "var list: &Node = Node(1, None)")

      out should not include ("atomicrmw add ptr %p, i64 1 monotonic")
      out should not include ("fence acquire")
    }
  }

  /** The behavioural half of the per-thread worklist, which could not be written until something
   * could spawn.
   *
   * The reaper drains iteratively through a worklist rather than recursing, and that worklist is
   * `thread_local` so that two threads releasing the last reference to two unrelated objects do not
   * each overwrite the other's list (`06 § &sync T`, `03 § Teardown is iterative`). A plain global
   * would put both threads' chains on one list: the visible failures are a double free, a node freed
   * while the other thread is walking it, or a chain silently dropped — so the total is what says it
   * did not happen, and it is exact.
   */
  "two threads dropping shared structures at the same moment" - {
    "each drain finds its own list" in {
      run(
        chain +
          """struct Work
            |    rounds: int
            |    length: int
            |    seen: Atomic[int]
            |
            |churn(w: *Work)
            |    var i = 0
            |
            |    while i < w.rounds
            |        var list = build(w.length)
            |
            |        w.seen.add(list.value)
            |        i = i + 1
            |
            |var w = Work(50, 2000, Atomic(0))
            |var a = spawn(&churn, &w).unwrap()
            |var b = spawn(&churn, &w).unwrap()
            |
            |a.join()
            |b.join()
            |print(w.seen.load())""".stripMargin
      ) shouldBe "199900\n"
    }
  }

  /** `posix` is an environment capability, so what it gates is **which modules exist** rather than
   * what any declaration may do — and the check is the module graph's, shared with `os`.
   *
   * **There is no `threads` capability, and this section is where that is pinned.** The module used
   * to require one beside `posix`, on the reading that a scheduler is a thing a target either has or
   * has not. What it is actually built on is pthreads, which is POSIX and nothing narrower — so the
   * module moved here and the fourth capability went, because nothing in the library was ever gated
   * on a scheduler existing. A target running its own kernel reaches threads through a package
   * binding it, which asks the compiler for nothing.
   */
  "the capability that gates it" - {
    "a module that gave up posix may not reach it" in {
      val out = errOf(
        "quiet/work.sysl" ->
          """module quiet
            |@no_posix
            |
            |import sysl.posix.threads.Mutex
            |
            |guarded() -> Mutex[int] = Mutex.new(0)
            |""".stripMargin,
        "main.sysl" -> "import quiet.guarded\n\nprint(guarded().try_lock().is_some())\n",
      )

      out should include("this reaches 'sysl.posix.threads', which requires 'posix'")
      out should include("'quiet' declared 'no posix'")
    }

    // Giving up `os` gives up `posix` with it, since posix is what needs an operating system rather
    // than the other way round — so the narrower clause is not the only one that puts this module out
    // of reach.
    "and so may a module that gave up its operating system, since pthreads needs one" in {
      val out = errOf(
        "bare/work.sysl" ->
          """module bare
            |@no_os
            |
            |import sysl.posix.threads.yield_now
            |
            |step() -> bool = yield_now()
            |""".stripMargin,
        "main.sysl" -> "import bare.step\n\nprint(step())\n",
      )

      out should include("this reaches 'sysl.posix.threads', which requires 'os'")
      out should include("'bare' declared 'no os'")
    }

    // A module that gives posix up goes on compiling — it just cannot reach the modules that need it.
    "while giving it up on its own is an ordinary clause" in {
      super.run("@no_posix\n\nprint(1 + 1)") shouldBe "2\n"
    }

    /** The capability that is gone. Written as a test rather than left to the absence of one, because
      * an unknown capability is refused by name and that refusal is the only thing telling a reader
      * of the old spelling where their module went.
      */
    "and 'threads' is no longer a capability at all" in {
      val out = errOf("main.sysl" -> "@no_threads\n\nprint(1 + 1)\n")

      out should include("no capability is called 'threads'")
      out should include("'heap', 'os', 'posix'")
    }
  }

  /** `06 § Crossing copies` decides structurally what may cross a domain boundary, and `spawn` is
   * where a program meets that rule: it is declared `@crossing(arg)`, so the pointer it takes is
   * looked *through* and every count the state reaches has to be atomic.
   *
   * Until `@crossing` existed this section pinned the opposite — a plain `&T` reaching another
   * thread with nothing said — on the reading that a raw pointer is crossable on purpose. That is
   * still true of the *pointer*, and it was never true of the object at the far end, which is the
   * thing that crossed. `CrossingAttrErrorTests` holds the annotation itself; these are the two
   * answers the standard library's own thread API gives.
   */
  "what crossing a domain is checked to be" - {
    "a state whose counts are all atomic reaches another thread" in {
      run(
        """struct Cell
          |    v: int
          |
          |struct Local
          |    r: &sync Cell
          |
          |look(p: *Local)
          |    print("saw", p.r.v)
          |
          |var l = Local(Cell(1))
          |
          |spawn(&look, &l).unwrap().join()""".stripMargin
      ) shouldBe "saw 1\n"
    }

    "and one holding a plain reference is refused at the call" in {
      val out = err(
        """struct Cell
          |    v: int
          |
          |struct Local
          |    r: &Cell
          |
          |look(p: *Local)
          |    print("saw", p.r.v)
          |
          |var l = Local(Cell(1))
          |
          |spawn(&look, &l).unwrap().join()""".stripMargin
      )

      out should include("points at reaches another concurrency domain")
      out should include("its 'r' reaches a '&Cell'")
      out should include("Hold it as a '&sync Cell'")
    }
  }

  "what is refused" - {
    /** Both fields are private, so the positional constructor is out of reach — which is the whole
     * of what makes `Mutex.new` the only way in, and what keeps a lock from being built already
     * held. `08 §` puts the constructor in the same list as a selection and a pattern for exactly
     * this reason: a private field a caller could still set by position restricts nothing.
     */
    "building one by naming its fields, which would let it start held" in {
      val out = err("var m = Mutex(1i32, 5)\nprint(m.try_lock().is_some())")

      out should include("the constructor names every field of 'sysl.posix.threads.Mutex' in order")
      out should include("'held' is private")
      out should include("associated function")
    }

    "reading what it protects without taking it" in {
      err("var m = Mutex.new(5)\nprint(m.value)") should include("'value'")
    }

    /** The `&sync` rule reaches through the new type without knowing anything about it: what a
     * shared object may hold is decided from its fields, and a `Mutex[&Cell]` has a plain counted
     * reference among them however deep. The lock protects the *field*, and the field's own count
     * is what two threads would race on — which is the race one level down that `06` names.
     */
    "and sharing one that guards a non-atomically counted reference" in {
      val out = err(
        """struct Cell
          |    v: int
          |
          |var m: &sync Mutex[&Cell] = Mutex.new(Cell(1))
          |
          |print(*m.lock())""".stripMargin
      )

      out should include("&sync")
    }

    /** A thread body is C's shape and a callable is not (`12 §6a`): the address is what pthreads
     * takes, and a closure would need boxing and would carry captures across a boundary nothing
     * checks. The two differ by one character, so the diagnostic is the whole of what stands between
     * the reader and the fix, and it names the `&`.
     *
     * It is the *general* form of the message rather than the sharp one `ClosureRunTests` pins,
     * because the sharp one asks what the context wanted and `spawn`'s parameter is generic — a
     * type argument still being inferred is not yet a `*extern` for the question to be answered
     * against. The `&` is named either way, which is the part a reader needs.
     */
    "handing a thread a callable rather than the address of one" in {
      val out = err(
        """work(p: *int)
          |    print(*p)
          |
          |var n = 1
          |
          |spawn(work, &n).unwrap().join()""".stripMargin
      )

      out should include("'work' is a function")
      out should include("that is written '&work'")
    }
  }
}
