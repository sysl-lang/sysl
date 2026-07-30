package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** A **type's own name** with a member selected from it — `Age.try(200)`, `Show.show()`,
 * `Point.origin`. None of these is a value, so analyzing the receiver reported an undefined *name*,
 * which is the one thing about a declared type that is certainly false.
 *
 * Each kind of name answers for itself: a struct and an enum already did, a constrained subtype and
 * a trait now do. The three things that could have been meant are the same in every case — a method
 * or a property, which is reached on a value; an associated function, which is reached exactly this
 * way; and a member the type does not have.
 */
class TypeNameMemberTests extends AnyFreeSpec with CodegenSupport with RunSupport {

  private val Age    = "type Age = int within 0..150\n"
  private val Meters = "type Meters = new f64\n"
  private val Show   = "trait Show\n    show(self) -> string\n"

  "a constrained subtype" - {
    "`try` is answered by the ruling that there is no `try`" in {
      val e = err(Age + "var a = Age.try(200)\nprint(int(a))")

      e should include("'Age' is a constrained type and has no 'try'")
      e should include("'Age(x)' checks it and traps")
      e should include("'Age::Valid(x)' asks the question without trapping")
      e should not include "undefined name"
    }

    // The bare form is the same mistake with the parentheses left off, so it gets the same answer
    // rather than falling through to a field read of a name that stands for nothing.
    "`try` read without parentheses says the same thing" in {
      err(Age + "var a = Age.try\nprint(1)") should include("has no 'try'")
    }

    // `Valid`/`Succ`/`Pred` are integer-range attributes, so a derived float must not be told to
    // reach for them: the advice would be refused by the very next message.
    "a derived type with no range is not offered the range attributes" in {
      val e = err(Meters + "var m = Meters.try(1.0)\nprint(1)")

      e should include("has no 'try'")
      e should not include "Valid"
    }

    "a member the type does not have names the attributes instead" in {
      val e = err(Age + "var a = Age.limit\nprint(1)")

      e should include("'Age' is a type, not a value, and has no member 'limit'")
      e should include("'Age::First'")
      e should include("'Age::Valid(x)'")
    }

    "a derived type with no range says only that the member is absent" in {
      val e = err(Meters + "var m = Meters.scale\nprint(1)")

      e should include("has no member 'scale'")
      e should not include "::"
    }

    "a method of the subtype is reported as one to call on a value" in {
      val e = err(
        Meters +
          """trait Show
            |    show(self) -> string
            |impl Show for Meters
            |    show(self) -> string = "m"
            |print(Meters.show())""".stripMargin
      )

      e should include("'show' is a method of 'Meters' — call it on a value")
      e should not include "undefined name"
    }

    "a property of the subtype is reported as one to read on a value" in {
      val e = err(
        Meters +
          """trait Size
            |    size -> int
            |impl Size for Meters
            |    size -> int = 1
            |print(Meters.size)""".stripMargin
      )

      e should include("'size' is a property of 'Meters' — read it on a value")
    }

    // What the diagnostic tells the reader to write has to work, or it is a second wrong answer.
    "the two forms the message names both compile and run" in {
      run(Age + "print(Age::Valid(200), int(Age(40)))") shouldBe "false 40\n"
    }
  }

  "a trait" - {
    "a member of the trait is reported as one to call on a value" in {
      val e = err(Show + "print(Show.show())")

      e should include("'show' is a member of the trait 'Show'")
      e should include("or on a '&Show'")
      e should not include "undefined name"
    }

    "a property of the trait is reported as one to read on a value" in {
      val e = err("trait Size\n    size -> int\nprint(Size.size)")

      e should include("'size' is a property of the trait 'Size'")
    }

    "a member the trait does not declare says the trait is not a value" in {
      err(Show + "print(Show.render())") should
        include("'Show' is a trait, not a value, and declares no member 'render'")
    }

    // A trait object is the value the message names, so it has to be the thing that works.
    "the form the message names compiles and runs" in {
      run(
        Show +
          """struct P
            |    v: int
            |impl Show for P
            |    show(self) -> string = "p"
            |f(s: &Show) -> string = s.show()
            |print(f(P(1)))""".stripMargin
      ) shouldBe "p\n"
    }
  }

  "the edges of `the name is a type`" - {
    // A local of the same name is nearer, so it is what the selection reaches — the same shadowing
    // test every other form of this case makes. Otherwise a program could not name a variable after
    // a type it uses.
    "a local shadowing the type name is an ordinary field read, not a type" in {
      val e = err(Age + "var Age = 1\nprint(Age.try)")

      e should include("int")
      e should not include "is a constrained type"
    }

    "and a local shadowing a trait name is too" in {
      val e = err(Show + "var Show = 1\nprint(Show.show())")

      e should not include "is a member of the trait"
    }

    // The module-qualified form folds the module into the name it qualifies before anything else
    // looks at it (`13 §3`), so the answer has to be the same one, and it has to name the type by
    // its qualified name rather than by the bare word that was written.
    "a subtype reached through its module gets the same answer" in {
      val e = errIn(
        ("geom", "g.sysl", "module geom\ntype Age = int within 0..150\n"),
        ("", "main.sysl", "print(int(geom.Age.try(200)))"),
      )

      e should include("has no 'try'")
      e should not include "undefined name"
    }

    "a trait reached through its module gets the same answer" in {
      val e = errIn(
        ("geom", "g.sysl", "module geom\ntrait Show\n    show(self) -> string\n"),
        ("", "main.sysl", "print(geom.Show.show())"),
      )

      e should include("is a member of the trait")
    }

    // `12 §6` keeps the arity-carrying name out of diagnostics by rendering every `FnN[…]` *type*
    // as its arrow. This is the one position where the name is legitimately printed: a call trait
    // is an ordinary trait of the library's, and here the reader is the one who wrote it.
    "a call trait is answered as the ordinary trait it is" in {
      err("print(Fn1.call())") should include(s"is a member of the trait '${lib("Fn1")}'")
    }

    // And what that message points at is real — a callable's member is reached on a value, which is
    // the same call applying it makes.
    "and its member is reached on a value, which is what applying one does" in {
      run("var f = (x: int) -> x + 1\nprint(f(1), f.call(2))") shouldBe "2 3\n"
    }
  }

  // The two kinds that already answered by name, pinned here so the whole surface is one file and a
  // change to any one of the four is measured against the other three.
  "a struct and an enum keep answering by name" - {
    "a struct's absent member" in {
      err("struct P\n    v: int\nprint(P.origin)") should
        include("type 'P' has no member 'origin' — and 'P' is a type, not a value")
    }

    "an enum's absent variant" in {
      err("enum Color\n    Red\nprint(Color.Purple)") should include("enum 'Color' has no variant 'Purple'")
    }

    // An enum *does* have `try`, and it is why a reader writes `Age.try` in the first place.
    "an enum's `try` is a fallible constructor, which is what makes the subtype's absence surprising" in {
      run(
        """enum Color
          |    Red
          |    Green
          |Color.try(1) match
          |    Some(c) -> print(Color::Image(c))
          |    None -> print("none")""".stripMargin
      ) shouldBe "Green\n"
    }
  }
}
