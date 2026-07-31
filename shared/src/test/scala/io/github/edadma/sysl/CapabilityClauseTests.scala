package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** The capability clause of `13 §4` and `capabilities.md`: `no alloc` narrowing a module below what
 * its target offers, and `requires alloc` declaring what it cannot be built without.
 *
 * The clause is a property of the **module** written on each of its **files**, which is where most
 * of the rules here come from: the files have to agree, the clause has a place in the file, and the
 * module's own set is what every construction in it is measured against.
 *
 * What `no alloc` refuses is *making* heap storage, never holding it — a distinction the suite
 * checks from both sides, since a capability that refused a reference it was handed would make the
 * allocator-free subset useless for the kernel code it exists for.
 */
class CapabilityClauseTests extends AnyFreeSpec with RunSupport with CodegenSupport {

  "the clause is read in the file's header" - {

    "'no alloc' beside a module header" in {
      irOf("thing/a.sysl" -> "module thing\nno alloc\n\nf(p: *int) -> int = *p\n",
        "main.sysl" -> "var n = 7\nprint(thing.f(&n))") should include("define")
    }

    "'requires alloc', the other direction" in {
      runOf("thing/a.sysl" -> "module thing\nrequires alloc\n\nf() -> &int = 1\n",
        "main.sysl" -> "print(*thing.f())") shouldBe "1\n"
    }

    "a file with no module header may narrow, since the root module is a module" in {
      run("no alloc\n\nf(p: *int) -> int = *p\n\nvar n = 4\nprint(f(&n))\n") shouldBe "4\n"
    }

    "several clauses on their own lines" in {
      irOf("thing/a.sysl" -> "module thing\nno alloc\nrequires os\n\nf() -> int = 1\n",
        "main.sysl" -> "print(thing.f())") should include("define")
    }

    "a clause on the header's own line is refused, since each takes a line" in {
      err("module m no alloc\n\nf() -> int = 1\n") should include("belongs in the file's header")
    }

    "and a clause written below the statements says where it belongs" in {
      val e = err("f() -> int = 1\nno alloc\n")

      e should include("belongs in the file's header")
      e should include("directly after 'module'")
      e shouldNot include("newline expected")
    }
  }

  "a clause that names nothing, or claims something unchecked, is refused" - {

    "a name that is not a capability" in {
      val e = err("requires sockets\n\nf() -> int = 1\n")

      e should include("no capability is called 'sockets'")
      e should include("'alloc', 'os', 'posix', 'threads'")
    }

    "narrowing away a capability that gates modules nobody has written" in {
      val e = err("no posix\n\nf() -> int = 1\n")

      e should include("'no posix' is not enforced yet")
      e should include("'no alloc' is the narrowing that means something today")
    }

    "the same capability declared twice" in {
      err("no alloc\nno alloc\n\nf() -> int = 1\n") should include("'alloc' is declared twice")
    }

    "and a capability both given up and required" in {
      err("no alloc\nrequires alloc\n\nf() -> int = 1\n") should
        include("'alloc' is both given up and required here")
    }
  }

  "the files of one module state the same clause" - {

    "a file that dropped it is reported against one that kept it" in {
      val e = errOf(
        "a.sysl" -> "module thing\nno alloc\n\nf() -> int = 1\n",
        "b.sysl" -> "module thing\n\ng() -> int = 2\n",
        "main.sysl" -> "print(thing.f())",
      )

      e should include("declare different capabilities")
      e should include("'no alloc'")
      e should include("none")
    }

    "and two files stating it are not" in {
      runOf(
        "a.sysl" -> "module thing\nno alloc\n\nf() -> int = 1\n",
        "b.sysl" -> "module thing\nno alloc\n\ng() -> int = 2\n",
        "main.sysl" -> "print(thing.f() + thing.g())",
      ) shouldBe "3\n"
    }
  }

  "'no alloc' refuses every construction that makes heap storage" - {

    "a reference" in {
      errOf("thing/a.sysl" -> "module thing\nno alloc\n\nf() -> &int = 1\n",
        "main.sysl" -> "print(*thing.f())") should
        include("a reference needs an allocator, and this module declared 'no alloc'")
    }

    "a boxed trait object, which is a reference underneath" in {
      errOf(
        "thing/a.sysl" ->
          """module thing
            |no alloc
            |
            |trait Shape
            |    sides(self) -> int
            |
            |struct Square
            |    side: int
            |
            |impl Shape for Square
            |    sides(self) -> int = 4
            |
            |f() -> &Shape = Square(1)
            |""".stripMargin,
        "main.sysl" -> "print(thing.f().sides())",
      ) should include("a reference needs an allocator")
    }

    "a slice with storage of its own" in {
      errOf("thing/a.sysl" -> "module thing\nno alloc\n\nf() -> []int\n    var xs: []int = [1, 2, 3]\n    xs\n",
        "main.sysl" -> "print(thing.f()[0])") should
        include("a slice with storage of its own needs an allocator")
    }

    "a repeated slice, the other way one is made" in {
      errOf("thing/a.sysl" -> "module thing\nno alloc\n\nf(n: int) -> []int\n    var xs: []int = [0; n]\n    xs\n",
        "main.sysl" -> "print(thing.f(3)[0])") should
        include("a slice with storage of its own needs an allocator")
    }

    "two strings joined" in {
      errOf("thing/a.sysl" -> "module thing\nno alloc\n\nf() -> string = \"a\" + \"b\"\n",
        "main.sysl" -> "print(thing.f())") should
        include("the string two strings join into needs an allocator")
    }

    "a value rendered as a string" in {
      errOf("thing/a.sysl" -> "module thing\nno alloc\n\nf(n: int) -> string = str(n)\n",
        "main.sysl" -> "print(thing.f(1))") should
        include("the string a value renders as needs an allocator")
    }

    "and an interpolation, which is the same thing spelled differently" in {
      errOf("thing/a.sysl" -> "module thing\nno alloc\n\nf(n: int) -> string = s\"n=$n\"\n",
        "main.sysl" -> "print(thing.f(1))") should include("needs an allocator")
    }
  }

  "'no alloc' leaves the whole no-alloc subset alone" - {

    "raw pointers, fixed arrays, and views of them" in {
      runOf(
        "thing/a.sysl" ->
          """module thing
            |no alloc
            |
            |sum(xs: []int) -> int
            |    var total = 0
            |    for x in xs do total += x
            |    total
            |
            |first(p: *int) -> int = *p
            |""".stripMargin,
        "main.sysl" ->
          """var a: [3]int = [4, 5, 6]
            |print(thing.sum(a[0..<3]))
            |print(thing.first(&a[0]))
            |""".stripMargin,
      ) shouldBe "15\n4\n"
    }

    "holding, passing and releasing a reference made somewhere else" in {
      runOf(
        "thing/a.sysl" ->
          """module thing
            |no alloc
            |
            |struct Held
            |    r: &int
            |
            |keep(r: &int) -> Held = Held(r)
            |
            |read(h: Held) -> int = *h.r
            |""".stripMargin,
        "main.sysl" ->
          """var r: &int = 9
            |var h = thing.keep(r)
            |print(thing.read(h))
            |""".stripMargin,
      ) shouldBe "9\n"
    }

    "and a string literal, which is static rather than made" in {
      runOf("thing/a.sysl" -> "module thing\nno alloc\n\nname() -> string = \"kernel\"\n",
        "main.sysl" -> "print(thing.name())") shouldBe "kernel\n"
    }
  }

  "the promotion a module cannot make is reported rather than made silently" - {

    "a slice of a local array that outlives the frame" in {
      val e = errOf(
        "thing/a.sysl" -> "module thing\nno alloc\n\nf() -> []int\n    var a: [3]int = [1, 2, 3]\n    a[0..<3]\n",
        "main.sysl" -> "print(thing.f()[0])",
      )

      e should include("would move to the heap")
      e should include("this module declared 'no alloc'")
    }

    "and the same body in a module that kept its allocator is promoted with no diagnostic at all" in {
      runOf(
        "thing/a.sysl" -> "module thing\n\nf() -> []int\n    var a: [3]int = [1, 2, 3]\n    a[0..<3]\n",
        "main.sysl" -> "print(thing.f()[0])",
      ) shouldBe "1\n"
    }
  }

  "what the guarantee is worth is readable in the output" - {

    // The claim is about **calls**, not about the declaration. `malloc` and `free` are declared
    // together with the ARC helpers, which an allocator-free program still emits: it holds slices,
    // and retaining one it was handed is what those helpers are. Reaching the `free` inside them
    // takes storage that was made, and this program makes none.
    "no function of an allocator-free program calls the allocator" in {
      val out = ir(
        """no alloc
          |
          |sum(p: *int, n: usize) -> int
          |    var total = 0
          |    var i = 0usize
          |    while i < n do
          |        total += p[i]
          |        i += 1
          |    total
          |
          |var a: [3]int = [4, 5, 6]
          |print(sum(&a[0], 3))
          |""".stripMargin,
      )

      out shouldNot include("call ptr @malloc")
      out should include("declare i32 @putchar")
    }

    // A call out of the module is the other half of `13 §4`, and `line_text` is what it catches:
    // the module's own text makes nothing, and the library function it calls validates bytes into a
    // fresh `string`. Without the reachability half this compiles, and the output holds one real
    // call to the allocator in a module that declared it would make none.
    "a call into a function that allocates is refused too" in {
      val e = errOf(
        "thing/a.sysl" -> "module thing\nno alloc\n\ntext(b: []const u8) -> string = line_text(b)\n",
        "main.sysl" -> "var bytes: [2]u8 = [104u8, 105u8]\nprint(thing.text(bytes[0..<2]))\n",
      )

      e should include("which makes heap storage")
      e should include("may only call what is allocator-free itself")
    }

    // The same rule the other way: printing is reached constantly from allocator-free code and does
    // not allocate, so it is not refused. A capability that refused `print` would be one no kernel
    // could carry.
    "and printing, which an allocator-free module does constantly, is not" in {
      runOf("thing/a.sysl" -> "module thing\nno alloc\n\nsay(n: int)\n    print(\"n is\", n)\n",
        "main.sysl" -> "thing.say(3)") shouldBe "n is 3\n"
    }

    "and the same program without the clause is not obliged to say so" in {
      run(
        """sum(p: *int, n: usize) -> int
          |    var total = 0
          |    var i = 0usize
          |    while i < n do
          |        total += p[i]
          |        i += 1
          |    total
          |
          |var a: [3]int = [4, 5, 6]
          |print(sum(&a[0], 3))
          |""".stripMargin,
      ) shouldBe "15\n"
    }
  }

  "a module with no clause is unaffected" in {
    runOf("thing/a.sysl" -> "module thing\n\nf() -> int = 1\n", "main.sysl" -> "print(thing.f())") shouldBe "1\n"
  }
}
