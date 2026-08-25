package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Tier-2: generic functions, structs, and enums compiled and run. Each distinct set of type
 * arguments is monomorphized into its own function or aggregate, so these check that the
 * instantiations really are independent — and that the type arguments are inferred.
 */
class GenericsRunTests extends AnyFreeSpec with RunSupport with CodegenSupport {

  "a generic function instantiated at three types" in {
    run("""id[T](x: T) -> T = x
          |print(id(7), id(2.5), id("hi"))
          |""".stripMargin) shouldBe "7 2.5 hi\n"
  }

  /** A stand-in for a type parameter is **its name**, so two declarations whose parameters are both
   * spelled `T` build ones that render the same — and an instantiation cached under the rendering is
   * handed from whichever declaration asked first to whichever asks next, carrying the first one's
   * bounds with it.
   *
   * The library is the injured party and never the culprit: `sysl.container.Heap[T: Ord]` holds a
   * `Buf[T]` and compares what it reads back, so a program declaring its own `[T: <anything else>]`
   * over a `Buf[T]` used to make `Heap`'s elements unordered — and the diagnostic landed on the
   * *library's* line, naming a bound the library had written correctly. Renaming the program's
   * parameter to a free letter made it go away, which is what says it is about the letter.
   *
   * The `impl` blocks are what made it reachable rather than incidental: they are the third thing to
   * resolve `Holder[T]`, and the two before them are sandboxed together.
   */
  "a program's type parameter does not lend its bounds to a library's of the same name" in {
    run("""import sysl.buf.{Buf, buf}
          |trait Tag: Add + Mul
          |    tag(self) -> int
          |impl Tag for real
          |    tag(self) -> int = 1
          |struct Holder[T: Tag]
          |    cells: &Buf[T]
          |    first(self) -> T = self.cells.at(0usize)
          |impl[T: Tag] Mul[Holder[T], T] for Holder[T]
          |    mul(self, rhs: Holder[T]) -> T = self.first() * rhs.first()
          |impl[T: Tag] Mul[T] for Holder[T]
          |    mul(self, k: T) -> Holder[T] = self
          |var cells: Buf[real] = buf()
          |cells.push(2.5)
          |var h = Holder(cells)
          |print(h.first(), h * h)
          |""".stripMargin) shouldBe "2.5 6.25\n"
  }

  "a generic function is instantiated once per type, not once per call" in {
    val out = Compiler.compileToLlvm("""id[T](x: T) -> T = x
                                       |print(id(1), id(2), id(3.5))
                                       |""".stripMargin)

    // Counting only `id`'s own definitions: the module also holds the ARC runtime and whatever
    // library renderers `print` reached, neither of which this is about.
    out.map(_.linesIterator.count(l => l.startsWith("define") && l.contains("@id."))) shouldBe Right(2)
  }

  "a generic struct at two types" in {
    run("""struct Box[T]
          |    value: T
          |end Box
          |var a = Box(41)
          |var b = Box("boxed")
          |print(a.value + 1, b.value)
          |""".stripMargin) shouldBe "42 boxed\n"
  }

  "a generic struct with two parameters" in {
    run("""struct Pair[A, B]
          |    first: A
          |    second: B
          |end Pair
          |var p = Pair(1, "one")
          |print(p.first, p.second)
          |""".stripMargin) shouldBe "1 one\n"
  }

  "a generic struct nested in itself at another type" in {
    run("""struct Box[T]
          |    value: T
          |end Box
          |var bb = Box(Box(9))
          |print(bb.value.value)
          |""".stripMargin) shouldBe "9\n"
  }

  "a generic struct nested three deep" in {
    run("""struct Box[T]
          |    value: T
          |end Box
          |var d = Box(Box(Box(7)))
          |print(d.value.value.value)
          |""".stripMargin) shouldBe "7\n"
  }

  "nested generic enums destructure through both layers" in {
    run("""var x: Option[Result[int, string]] = Some(Ok(99))
          |var y: Result[Option[int], string] = Ok(Some(5))
          |var a = x match
          |    Some(Ok(n)) -> n
          |    else -1
          |var b = y match
          |    Ok(Some(n)) -> n
          |    else -1
          |print(a, b)
          |""".stripMargin) shouldBe "99 5\n"
  }

  "a generic function inferred through a generic construction" in {
    run("""id[T](x: T) -> T = x
          |struct Box[T]
          |    value: T
          |end Box
          |print(id(Box(id(5))).value)
          |""".stripMargin) shouldBe "5\n"
  }

  "a generic enum destructured in a match" in {
    run("""enum Maybe[T]
          |    Just(value: T)
          |    Nothing
          |end Maybe
          |say(m: Maybe[string]) -> string
          |    m match
          |        Just(s) -> s
          |        Nothing -> "nothing"
          |end say
          |print(say(Just("here")), say(Nothing))
          |""".stripMargin) shouldBe "here nothing\n"
  }

  "a generic function passed through a generic type" in {
    run("""struct Box[T]
          |    value: T
          |end Box
          |unbox[T](b: Box[T]) -> T = b.value
          |print(unbox(Box(3)), unbox(Box("three")))
          |""".stripMargin) shouldBe "3 three\n"
  }

  "the expected type supplies a type argument the arguments do not" in {
    run("""empty[T]() -> Option[T] = None
          |var e: Option[real] = empty()
          |var isNone = e match
          |    Some(x) -> false
          |    None -> true
          |print(isNone)
          |""".stripMargin) shouldBe "true\n"
  }

  // The bound is `library/core.md § Rendering to a sink` working as intended: a body that prints
  // its parameter says so, and the cost that use imposes is written where the parameter is declared
  // rather than discovered at whichever call site happened to supply a printable type.
  "a recursive generic function" in {
    run("""countdown[T: Display](n: int, x: T)
          |    if n > 0 then
          |        print(n, x)
          |        countdown(n - 1, x)
          |end countdown
          |countdown(3, "go")
          |""".stripMargin) shouldBe "3 go\n2 go\n1 go\n"
  }

  /** An array written where a slice is asked for, at a generic callee.
   *
   * A non-generic `plain(xs: []const int)` has always taken `plain([1, 2, 3])`: the literal is
   * analyzed against the parameter, and a written-out array in a slice position becomes one. A
   * generic callee analyzes its arguments **before** any parameter type is known, so the same
   * argument arrived as a `[3]int` at a parameter nothing had solved yet — and answered a perfectly
   * ordinary call with "cannot infer the type argument 'T'".
   *
   * It needed both halves. `unify` never matched an array against a `[]T` parameter, so `T` stayed
   * unbound; and once it was bound, the call path still had a `[3]int` in hand at a position it had
   * already been analyzed for, which is what the re-analysis exists to redo.
   */
  "an array where a slice is asked for" - {
    "a literal solves the element type and becomes the slice" in {
      run("""count[T](xs: []T) -> int = int(xs.len)
            |print(count([1, 2, 3]))
            |""".stripMargin) shouldBe "3\n"
    }

    "the same for a 'const' view, which is what a library parameter is written as" in {
      run("""total[T: Add](xs: []const T, zero: T) -> T
            |    var n = zero
            |
            |    for x in xs
            |        n = n + x
            |
            |    n
            |end total
            |print(total([1, 2, 3], 0))
            |""".stripMargin) shouldBe "6\n"
    }

    "the element type is read off the literal rather than defaulted" in {
      run("""kind[T](xs: []T, probe: T) -> T = probe
            |print(kind([1.5, 2.5], 0.25))
            |""".stripMargin) shouldBe "0.25\n"
    }

    // A **named** array goes the same way, which it did not when this group was written: the two
    // halves get it to a solved `[]const int` parameter, and the coercion there views it. What is
    // still being asserted is that the generic path arrives where the non-generic one does — the
    // conversion itself is `ArrayClaimTests`'.
    "and a named array of the same elements arrives at the same place" in {
      run("""gen[T](xs: []const T) -> int = int(xs.len)
            |var a = [1, 2, 3]
            |print(gen(a))
            |""".stripMargin) shouldBe "3\n"
    }

    "and the array parameter it could have been confused with still binds its length" in {
      run("""len[const N: usize, T](xs: [N]T) -> usize = N
            |var a = [1, 2, 3]
            |print(len(a))
            |""".stripMargin) shouldBe "3\n"
    }
  }

  /** `null` is the one *value* whose type its context supplies, so at a parameter still being
   * solved it has nothing to be analyzed against until the other arguments have been read. Holding
   * it back to the second pass cannot lose the solution — an argument with no type of its own has
   * nothing to unify — which is what makes the answer the same whichever end of the list it is
   * written at.
   *
   * What is *not* claimed is that inference will look for a type where none was given: a call whose
   * only argument is `null` is refused exactly as it was, and so is one where every argument is.
   */
  "null at a parameter still being solved" - {
    "another argument's pointer settles which pointer it is" in {
      run("""two[T](a: *T, b: *T) -> bool = a == b
            |var x: int = 3
            |print(two(&x, null))
            |""".stripMargin) shouldBe "false\n"
    }

    "and it settles it from either side, since a held argument waits rather than queues" in {
      run("""two[T](a: *T, b: *T) -> bool = a == b
            |var x: int = 3
            |print(two(null, &x))
            |""".stripMargin) shouldBe "false\n"
    }

    "a parameter that is the type parameter itself takes it too" in {
      run("""same[T](a: T, b: T) -> bool = true
            |var x: int = 3
            |print(same(&x, null))
            |""".stripMargin) shouldBe "true\n"
    }

    "the pointee is the one the other argument gave, not a default" in {
      run("""first[T](a: *T, b: *T) -> T = *a
            |var x: u8 = 200
            |print(first(&x, null))
            |""".stripMargin) shouldBe "200\n"
    }

    "a generic method's parameter answers for it as well" in {
      run("""struct Box[T]
            |    v: T
            |
            |    beside(self, a: *T, b: *T) -> bool = a == b
            |end Box
            |var x: int = 3
            |print(Box(1).beside(&x, null))
            |""".stripMargin) shouldBe "false\n"
    }

    "a generic constructor's field answers for it" in {
      run("""struct Pair[T]
            |    a: *T
            |    b: *T
            |end Pair
            |var x: int = 3
            |var p = Pair(&x, null)
            |print(p.a == p.b)
            |""".stripMargin) shouldBe "false\n"
    }

    "a member's own type parameter answers for it, not only its type's" in {
      run("""struct Box[T]
            |    v: T
            |
            |    take[U](self, a: *U, b: *U) -> bool = a == b
            |end Box
            |var x: int = 3
            |print(Box("held").take(&x, null))
            |""".stripMargin) shouldBe "false\n"
    }

    // The receiver settles the *type's* parameters before the arguments are read at all, so this
    // one never went through the wait — it is here because it is the case a reader asks about next.
    "a parameter the receiver already settled takes it in the first place" in {
      run("""struct Holder[T]
            |    p: *T
            |
            |    only(self, q: *T) -> bool = q == self.p
            |end Holder
            |var x: int = 3
            |print(Holder(&x).only(null))
            |""".stripMargin) shouldBe "false\n"
    }

    "the arguments are read by name where they are written by name" in {
      run("""two[T](a: *T, b: *T) -> bool = a == b
            |var x: int = 3
            |print(two(b = null, a = &x))
            |""".stripMargin) shouldBe "false\n"
    }

    "and an overload is chosen with one among the arguments" in {
      run("""pick(a: string) -> int = 1
            |pick[T](a: *T, b: *T) -> int = 2
            |var x: int = 3
            |print(pick(&x, null), pick("s"))
            |""".stripMargin) shouldBe "2 1\n"
    }

    // The absent *callback*, which is the shape a C interface reads as "there is none, use the
    // default" — and the one a binding meets, since the parameter that names `T` is usually the
    // function's own.
    "a *extern parameter the same solution reached takes the absent callback" in {
      run("""hook[T](on: *extern(*T) -> unit, off: *extern(*T) -> unit, state: *T) -> bool = off == null
            |
            |ping(n: *int)
            |    *n = *n + 1
            |
            |var x: int = 0
            |print(hook(&ping, null, &x))
            |""".stripMargin) shouldBe "true\n"
    }

    // Reaching the parameter is not agreeing with it. A solved `&T` is a *reference*, so what the
    // `null` gets is the answer any reference gives it — the one naming the type it arrived at,
    // rather than the one saying there was no context.
    "a solved parameter that is not a pointer refuses it in that parameter's own terms" in {
      err("""two[T](a: *T, b: &T) -> bool = true
            |var x: int = 3
            |print(two(&x, null))
            |""".stripMargin) should include("a &int always points at a live object — an absent one is Option[&int]")
    }

    // A variadic tail is not a parameter list, so there is nothing there to have said what the
    // pointer is — which is the answer a non-generic variadic gives too.
    "a variadic tail is still no context, generic callee or not" in {
      err("""count[T](a: *T, ...) -> int = 0
            |var x: int = 3
            |print(count(&x, null))
            |""".stripMargin) should include("'null' takes its type from its context")
    }

    "a concrete parameter beside a solved one is unaffected" in {
      run("""two[T](a: *T, b: *int) -> bool = b == null
            |var x: u8 = 1
            |print(two(&x, null))
            |""".stripMargin) shouldBe "true\n"
    }

    "nothing else to read is the refusal it always was" in {
      err("""one[T](a: *T) -> bool = true
            |print(one(null))
            |""".stripMargin) should include("'null' takes its type from its context")
    }

    "and neither is two of them" in {
      err("""two[T](a: *T, b: *T) -> bool = true
            |print(two(null, null))
            |""".stripMargin) should include("'null' takes its type from its context")
    }

    "a parameter that is not a pointer at all still refuses it" in {
      err("""two[T](a: T, b: T) -> bool = true
            |print(two(1, null))
            |""".stripMargin) should include("'null' is a raw pointer")
    }
  }

  /** `null` is not the only argument whose type its context supplies, and the group above is the
   * narrow case of a wider rule: **an argument that cannot be read on its own waits for the
   * solution, and one that reads differently once the solution exists is read again.**
   *
   * A dataless variant of a generic enum is the shape that found this. `None` says nothing about
   * what an `Option` holds, so analyzing it alone raises where a `null` merely waits — and
   * `Some(3)` reads as an `Option[int]` alone, which does not become an `Option[usize]` by any
   * conversion, so a call whose other argument said `usize` was refused for a difference nobody
   * wrote.
   *
   * Both compile when the callee is written non-generically over the same type, which is the whole
   * of why these are bugs rather than the inference declining to guess.
   */
  "an argument the solution changes" - {
    "a dataless variant waits for the parameter, as a null does" in {
      run("""same[T: Eq](a: T, b: T) -> bool = a == b
            |var n: Option[usize] = None
            |print(same(n, None))
            |""".stripMargin) shouldBe "true\n"
    }

    "and one carrying a literal is read again at the type the others settled" in {
      run("""same[T: Eq](a: T, b: T) -> bool = a == b
            |var s: Option[usize] = Some(3)
            |print(same(s, Some(3)), same(s, Some(4)))
            |""".stripMargin) shouldBe "true false\n"
    }

    // The payload is the point: read alone the literal would be an `int`, and what it has to become
    // is whatever the parameter turned out to be — the same rule a bare literal at a solved
    // parameter already followed, applied to one standing inside a construction.
    "the payload takes the width the solution gave it, not its own default" in {
      run("""first[T](a: T, b: T) -> T = a
            |var wide: Option[u64] = Some(5000000000)
            |print(str(first(wide, Some(5000000000)).unwrap()))
            |""".stripMargin) shouldBe "5000000000\n"
    }

    // An enum of the program's own, so the rule is pinned about the *shape* rather than about the
    // two the library happens to ship.
    "an enum of the program's own is read the same way" in {
      run("""enum Maybe[T]
            |    Just(v: T)
            |    Nothing
            |same[T](a: T, b: T) -> bool = true
            |var m: Maybe[u8] = Just(3u8)
            |print(same(m, Nothing), same(m, Just(4u8)))
            |""".stripMargin) shouldBe "true true\n"
    }

    // Waiting is not guessing. With nothing else in the call to read, the refusal is the one it
    // always was — and it still names the argument that could not be worked out.
    "nothing else to read is the refusal it always was" in {
      err("""one[T](a: Option[T]) -> bool = true
            |print(one(None))
            |""".stripMargin) should include("cannot infer the type argument")
    }

    // And a disagreement that re-reading cannot repair is reported as the mismatch it is, in the
    // parameter's own terms, rather than being quietly accepted.
    "a re-reading that cannot succeed leaves the original complaint" in {
      err("""same[T](a: T, b: T) -> bool = true
            |var s: Option[usize] = Some(3)
            |var t: string = "no"
            |print(same(s, t))
            |""".stripMargin) should include("was given")
    }
  }

  /** The same call written the other way round, which the group above did **not** fix.
   *
   * Re-reading repairs the argument that disagrees with what the others settled, and that is no use
   * when the argument that disagrees is the one which was right. `Some(3)` is not a literal —
   * `isLiteral` reads the spelling and this is a call — so it went in the first round and fixed
   * `T = Option[int]`, a conclusion worth exactly what the unsuffixed `int` inside it was worth.
   * `same(s, Some(3))` therefore compiled and `same(Some(3), s)` was refused, naming two types the
   * reader never wrote.
   *
   * **A construction over adaptable literals is adaptable**, and consulting it last is the whole of
   * the fix. The predicate is `Literals.adaptable`, and both places a position is settled late ask
   * it: the ordering `solve` runs its rounds in, and the operand `analyzeOperands` takes the pair's
   * type from — so the operator half below comes from the same change rather than a second one.
   */
  "a construction over literals written first" - {
    "the argument that knows settles the parameter, whichever end it is written at" in {
      run("""same[T: Eq](a: T, b: T) -> bool = a == b
            |var s: Option[usize] = Some(3)
            |print(same(Some(3), s), same(Some(4), s))
            |""".stripMargin) shouldBe "true false\n"
    }

    "and the operator says the same, because it consults the same predicate" in {
      run("""var s: Option[usize] = Some(3)
            |print(Some(3) == s, Some(4) == s)
            |""".stripMargin) shouldBe "true false\n"
    }

    // The pair of orders in one program, which is the property the card was actually about: a
    // symmetric call is not supposed to have a good side and a bad one.
    "the two orders agree" in {
      run("""same[T: Eq](a: T, b: T) -> bool = a == b
            |var s: Option[usize] = Some(3)
            |print(same(s, Some(3)), same(Some(3), s), s == Some(3), Some(3) == s)
            |""".stripMargin) shouldBe "true true true true\n"
    }

    "the payload takes the solution's width at this end too" in {
      run("""first[T](a: T, b: T) -> T = a
            |var wide: Option[u64] = Some(5000000000)
            |print(str(first(Some(5000000000), wide).unwrap()))
            |""".stripMargin) shouldBe "5000000000\n"
    }

    // A generic struct of the program's own, so the rule is pinned about constructions rather than
    // about the enum the library happens to ship.
    "a generic struct of the program's own is read the same way" in {
      run("""struct Box[T] deriving Eq
            |    v: T
            |same[T: Eq](a: T, b: T) -> bool = a == b
            |var b: Box[usize] = Box(3)
            |print(same(Box(3), b), Box(4) == b)
            |""".stripMargin) shouldBe "true false\n"
    }

    // An array literal reaches this for free, being a construction whose element type the literals
    // inside it decided. It has no `==`, so the claim is made at a call.
    "an array literal written first" in {
      run("""first[T](a: T, b: T) -> T = a
            |var a: [3]usize = [1, 2, 3]
            |print(first([4, 5, 6], a)[0])
            |""".stripMargin) shouldBe "4\n"
    }

    // The nesting is in the source test, so a construction inside a construction is adaptable for
    // the same reason the one holding it is.
    "nested one level deep" in {
      run("""same[T: Eq](a: T, b: T) -> bool = a == b
            |var o: Option[Option[usize]] = Some(Some(3))
            |print(same(Some(Some(3)), o), Some(Some(3)) == o)
            |""".stripMargin) shouldBe "true true\n"
    }

    // A width the reader wrote is what stops all of this: the node is the same shape and the same
    // type, and only the spelling says that this one chose.
    "a suffix inside the construction is still load-bearing" in {
      err("""same[T: Eq](a: T, b: T) -> bool = a == b
            |var s: Option[usize] = Some(3)
            |print(same(Some(3u8), s))
            |""".stripMargin) should include("sysl.Option[byte], but sysl.Option[usize] was given")
    }

    // And a call is not a construction, however it is spelled: `f(3)` parses exactly as `Some(3)`
    // does, and what comes back has nothing to do with the literal handed over.
    "an ordinary call over literals is not adaptable" in {
      err("""same[T: Eq](a: T, b: T) -> bool = a == b
            |f(n: int) -> Option[int] = Some(n)
            |var s: Option[usize] = Some(3)
            |print(same(f(3), s))
            |""".stripMargin) should include("sysl.Option[int], but sysl.Option[usize] was given")
    }

    // Nor is a construction over anything that carries a type of its own, which is the other half
    // of the same test — the string is what fixes the argument, and it disagrees.
    "a construction over a typed payload keeps its own type" in {
      err("""var s: Option[usize] = Some(3)
            |print(Some("x") == s)
            |""".stripMargin) should include("sysl.Option[string] and sysl.Option[usize]")
    }

    // Adaptable is a place in the order, not a refusal to conclude — the same thing `id(7)` being
    // an `int` says about a bare literal. With nothing firmer in the room the first one is still
    // what the pair settles on, and the payload is still an `int`.
    "two constructions with nothing else to go on settle as they always did" in {
      run("""same[T: Eq](a: T, b: T) -> bool = a == b
            |print(Some(1) == Some(2), same(Some(1), Some(1)), str(Some(7).unwrap()))
            |""".stripMargin) shouldBe "false true 7\n"
    }

    // `isLiteral` already reads a negation as part of the literal, so a construction over one is
    // adaptable for the same reason — which is worth pinning, since the minus is a `Unary` node
    // and the source test walks the tree rather than the token.
    "a negated literal inside is still a literal" in {
      run("""same[T: Eq](a: T, b: T) -> bool = a == b
            |var s: Option[i16] = Some(-3)
            |print(same(Some(-3), s), Some(-3) == s)
            |""".stripMargin) shouldBe "true true\n"
    }
  }

  /** `01` lists the parameter type at a call among the positions that fix an unsuffixed literal,
   * and says nothing about the callee being generic — so a parameter written `usize` fixes one
   * whether or not the declaration beside it also has a `T` to solve. What makes this its own group
   * is that inference analyzes the arguments before any parameter type is known, so the ones that
   * *are* known have to be handed over anyway.
   */
  "the literal rule at a generic callee" - {
    "a parameter naming no type parameter fixes a literal" in {
      run("""at[T](xs: []T, i: usize) -> T = xs[i]
            |var ns: [3]int = [10, 20, 30]
            |print(at(ns[..], 2))
            |""".stripMargin) shouldBe "30\n"
    }

    "the same parameter fixes it at every width, and the argument is not merely truncated" in {
      run("""wide[T](x: T, a: u8, b: i16, c: u64) -> u64 = u64(a) + u64(b) + c
            |print(wide("ignored", 200, 30000, 5000000000))
            |""".stripMargin) shouldBe "5000030200\n"
    }

    "a parameter that does name one still takes the literal's own default" in {
      run("""twice[T: Add](x: T) -> T = x + x
            |print(twice(21))
            |""".stripMargin) shouldBe "42\n"
    }

    "a generic struct's field fixes one" in {
      run("""struct Slot[T]
            |    v: T
            |    at: usize
            |end Slot
            |var s = Slot("here", 7)
            |print(s.v, s.at)
            |""".stripMargin) shouldBe "here 7\n"
    }

    "a generic variant's field fixes one" in {
      run("""enum Tagged[T]
            |    Absent
            |    Held(v: T, n: u8)
            |end Tagged
            |var t = Tagged.Held("x", 250)
            |var n: u8 = t match
            |    Held(_, k) -> k
            |    Absent -> 0
            |print(n)
            |""".stripMargin) shouldBe "250\n"
    }

    "a generic method's own parameter fixes one" in {
      run("""struct Box[T]
            |    v: T
            |
            |    pick[U](self, other: U, n: u16) -> u16 = n
            |end Box
            |var b = Box(1)
            |print(b.pick("s", 60000))
            |""".stripMargin) shouldBe "60000\n"
    }

    "a generic associated function's parameter fixes one" in {
      run("""struct Box[T]
            |    v: T
            |
            |    of(x: T, n: u8) -> Box[T] = if n > 0 then Box(x) else Box(x)
            |end Box
            |print(Box.of("held", 200).v)
            |""".stripMargin) shouldBe "held\n"
    }

    // A parameter that *is* the type parameter knows what it wants only once the solution is in, so
    // a literal there is analyzed a second time — against the type its position turned out to have.
    "a literal at a parameter still being solved takes the type it solved to" in {
      run("""rotr[T: BitOr + Shl + Shr + Sub](x: T, n: T) -> T = (x >> n) | (x << (32 - n))
            |print(rotr(0x80000001u32, 4))
            |""".stripMargin) shouldBe "402653184\n"
    }

    "which one settled it does not matter, only that something did" in {
      run("""pick[T: Ord](a: T, b: T, c: T) -> T = if a > b then a else if b > c then b else c
            |print(pick(1, 2, 250u8))
            |""".stripMargin) shouldBe "250\n"
    }

    // Nothing else fixing the type is still the literal's own default, which is what keeps `id(7)`
    // an `int` rather than an inference failure.
    "literals alone still settle it at their default" in {
      run("""id[T](x: T) -> T = x
            |print(id(7), id(2.5))
            |""".stripMargin) shouldBe "7 2.5\n"
    }

    "a literal too wide for the type it solved to is refused" in {
      err("""pair[T](a: T, b: T) -> T = a
            |print(pair(1u8, 300))
            |""".stripMargin) should include("300 does not fit")
    }

    // The expected type is a written type and a literal is not, so it outranks one here for the
    // same reason a suffixed argument does — which is what lets a binding say what a call of
    // nothing but literals should have been.
    "a negated literal adapts like the one it negates" in {
      run("""pair[T: Add](a: T, b: T) -> T = a + b
            |print(pair(-5, 100i8), pair(-2.5, 1.0f32))
            |""".stripMargin) shouldBe "95 -1.5\n"
    }

    "the type may come from inside another parameter's" in {
      run("""nth[T](xs: []T, i: usize, fallback: T) -> T = if i < xs.len then xs[i] else fallback
            |var ns: [3]u16 = [10, 20, 30]
            |print(nth(ns[..], 1, 99), nth(ns[..], 7, 99))
            |""".stripMargin) shouldBe "20 99\n"
    }

    "the expected type outranks a literal" in {
      run("""id[T](x: T) -> T = x
            |var b: u8 = id(200)
            |var big: u64 = id(5000000000)
            |print(b, big)
            |""".stripMargin) shouldBe "200 5000000000\n"
    }

    /** And it still outranks one when a **closure** stands at a parameter the literal also
      * mentions, which is where it used to stop.
      *
      * A callable argument has no type of its own, so it is held back and read against the partial
      * solution the other arguments made — and that solution was built from every argument at once,
      * literals included. So the literal's default settled the parameter before the closure was
      * read, the closure came out at `int`, and the expected type had nothing left to outrank. The
      * order here is `solve`'s: what carries a type, then the expected type, then the literals.
      */
    "and it still does when a closure stands at the same parameter" in {
      run("""twice[A](x: A, f: &Fn(A) -> A) -> A = f(f(x))
            |val n: usize = twice(0, a -> a + 1)
            |print(n)
            |""".stripMargin) shouldBe "2\n"
    }

    /** The same fault, seen through the spelling that lowers to a bound rather than to a box.
      *
      * Here the solve *did* seed the parameter from the expected type — and reported that the
      * closure did not implement what the bound asked for, because the closure had already been
      * analyzed at the literal's default. One fault, two faces: the boxed form let the wrong answer
      * win, and the bounded form refused the right one.
      */
    "a bare-arrow callable is read at the expected type too" in {
      run("""twice[A](x: A, f: A -> A) -> A = f(f(x))
            |val n: usize = twice(0, a -> a + 1)
            |print(n)
            |""".stripMargin) shouldBe "2\n"
    }

    /** An argument that carries a type still beats the expected type, which is the half of the
      * ordering that must not move: the closure is read at what the *argument* said, and the
      * mismatch is reported against the binding rather than against the call.
      */
    "an argument with a type of its own still outranks the expected type" in {
      err("""twice[A](x: A, f: &Fn(A) -> A) -> A = f(f(x))
            |var start: u8 = 1
            |val n: usize = twice(start, a -> a + 1)
            |print(n)
            |""".stripMargin) should include("declared usize")
    }

    "a literal against an argument of a type it cannot be names both" in {
      err("""pair[T](a: T, b: T) -> T = a
            |print(pair("s", 1))
            |""".stripMargin) should include("string")
    }

    // A variadic's tail has no declared parameter, so it is checked by the rule the tail imposes
    // rather than against one — which makes it the case where the arguments and what inference
    // held could come apart.
    "a generic variadic checks its parameters and its tail apart" in {
      run("""tagged[T](tag: T, n: u8, ...) -> u8 = n
            |print(tagged("t", 250, 1, 2), tagged(2.5, 7, 3))
            |""".stripMargin) shouldBe "250 7\n"
    }

    // The two coercions an expected type drives now happen at the argument's own analysis rather
    // than after inference, so both are checked at a generic callee: a bare construction headed for
    // a `&T` is boxed, and a value headed for a trait object is erased.
    "a bare construction at a concrete reference parameter is still boxed" in {
      run("""struct Node
            |    v: int
            |end Node
            |held[T](x: T, n: &Node) -> int = n.v
            |print(held("ignored", Node(5)))
            |""".stripMargin) shouldBe "5\n"
    }

    "a value at a concrete trait-object parameter is still erased" in {
      run("""struct Node
            |    v: int
            |end Node
            |trait Show
            |    show(self) -> string
            |impl Show for Node
            |    show(self) -> string = "node"
            |named[T](x: T, s: *Show) -> string = s.show()
            |var n = Node(9)
            |print(named("ignored", &n))
            |""".stripMargin) shouldBe "node\n"
    }

    // The literal is that type from the start rather than promoted into it (`01`), so a value the
    // parameter cannot hold is refused at the argument — the same complaint a plain callee makes.
    "a literal too wide for the parameter is still refused" in {
      err("""at[T](x: T, i: u8) -> u8 = i
            |print(at("s", 300))
            |""".stripMargin) should include("300 does not fit")
    }

    // Nothing is inferred *from* an argument whose parameter is already known, so a call that
    // leaves the type parameter unreached is a refusal rather than a silent default. This one is the
    // shape below — `T` is in neither the parameters nor the result — so what it is told is that no
    // call reaches it at all, rather than that this call happened not to.
    "an argument at a known parameter settles nothing about the unknown one" in {
      val out = err("""only[T](n: usize) -> usize = n
                      |print(only(3))
                      |""".stripMargin)

      out should include("'T' is in neither the parameters of 'only' nor its result")
      out should include("write it out, as 'only[…](…)'")
    }

    // Where the result *does* mention it, the annotation genuinely is the remedy and is what the
    // message asks for — this is the boundary between the two sentences.
    "while a parameter the result mentions is asked for on the binding" in {
      err("""empty[T](n: usize) -> T
            |    var zero: T
            |    return zero
            |print(empty(3))
            |""".stripMargin) should include("cannot infer the type argument 'T'")
    }

    // Call-site type arguments were deliberately absent (`10 § Open a`) because the list and an
    // index share a grammar. What settles that is name resolution rather than the parser, and
    // `WrittenTypeArgsTests` is where the form is covered — this is the reach a reader makes first.
    "type arguments at a call name the instantiation" in {
      run("""id[T](x: T) -> T = x
            |print(id[int](3))
            |""".stripMargin) shouldBe "3\n"
    }

    /** **A call that says nothing about a parameter is sent to the place that would.**
      *
      * A parameter named by no parameter and by no result is reached by neither of
      * `reference/generics.md § Inference is bidirectional`'s two directions, so `solve` could only
      * report what it failed to find — and what it asked for, an annotation on the expected type,
      * is impossible advice for an expression that has none. Since `reference/generics.md § []
      * means type application in a type, indexing in an expression` the list may be written at the
      * call, which is the one thing that does settle it.
      */
    "a type parameter nothing in the signature mentions is sent to the written list" in {
      val out = err("""scale[const W: usize](xs: []const f32, out: []f32)
                      |    val v: <W>f32 = xs.load(0)
                      |    out.store(0, v)
                      |
                      |var a: [8]f32
                      |var b: [8]f32
                      |scale(a[..], b[..])
                      |""".stripMargin)

      out should include("'W' is in neither the parameters of 'scale' nor its result")
      out should include("write it out, as 'scale[…](…)'")
      out should not include "annotate the expected type"
    }

    // Nor is a local that happens to share a generic function's name: what is indexed there is the
    // local, and the reading that mentions type arguments is about a declaration further away than
    // the one the name reaches.
    "nor a local shadowing a generic name" in {
      val out = err("""id[T](x: T) -> T = x
                      |var id: []int = [1, 2, 3]
                      |print(id[0](3))
                      |""".stripMargin)

      out should include("the thing being called must be a name")
      out should not include "type arguments"
    }
  }
}
