package io.github.edadma.sysl

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

  "a generic function is instantiated once per type, not once per call" in {
    val out = Compiler.compileToLlvm("""id[T](x: T) -> T = x
                                       |print(id(1), id(2), id(3.5))
                                       |""".stripMargin)

    // Counting only `id`'s own definitions: the module also holds the ARC runtime and whatever
    // prelude renderers `print` reached, neither of which this is about.
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

  // The bound is `14 §6` working as intended: a body that prints its parameter says so, and the
  // cost that use imposes is written where the parameter is declared rather than discovered at
  // whichever call site happened to supply a printable type.
  "a recursive generic function" in {
    run("""countdown[T: Display](n: int, x: T)
          |    if n > 0 then
          |        print(n, x)
          |        countdown(n - 1, x)
          |end countdown
          |countdown(3, "go")
          |""".stripMargin) shouldBe "3 go\n2 go\n1 go\n"
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
    // leaves the type parameter unreached is an inference failure rather than a silent default.
    "an argument at a known parameter settles nothing about the unknown one" in {
      err("""only[T](n: usize) -> usize = n
            |print(only(3))
            |""".stripMargin) should include("cannot infer the type argument 'T'")
    }
  }
}
