package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Tier-2: generic functions, structs, and enums compiled and run. Each distinct set of type
 * arguments is monomorphized into its own function or aggregate, so these check that the
 * instantiations really are independent — and that the type arguments are inferred.
 */
class GenericsRunTests extends AnyFreeSpec with RunSupport {

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
          |var a = match x
          |    Some(Ok(n)) -> n
          |    else -> -1
          |var b = match y
          |    Ok(Some(n)) -> n
          |    else -> -1
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
          |    match m
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
          |var isNone = match e
          |    Some(x) -> false
          |    None -> true
          |print(isNone)
          |""".stripMargin) shouldBe "true\n"
  }

  "a recursive generic function" in {
    run("""countdown[T](n: int, x: T)
          |    if n > 0 then
          |        print(n, x)
          |        countdown(n - 1, x)
          |end countdown
          |countdown(3, "go")
          |""".stripMargin) shouldBe "3 go\n2 go\n1 go\n"
  }
}
