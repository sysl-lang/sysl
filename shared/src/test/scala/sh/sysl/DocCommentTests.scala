package sh.sysl

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** Documentation comments — `/** … */` above a declaration, and what is read out of one.
 *
 * The three tiers here answer three separate questions, and conflating them is how a doc-comment
 * feature comes to be tested only at the end that is easiest to reach:
 *
 *   - the **lexer** collects doc comments and nothing else, keyed by offset;
 *   - `DocComments.parse` turns one comment's text into a summary, a body and its tags;
 *   - `DocComments.above` decides which declaration a comment belongs to.
 *
 * What is deliberately *not* asserted anywhere is that a doc comment changes what a program means.
 * It does not, and the first test says so — a program compiles identically with every doc comment in
 * it deleted, which is the property that makes all of this safe to add to a language.
 */
class DocCommentTests extends AnyFreeSpec with Matchers {

  private def scan(src: String): List[(Int, Int, String)] = {
    val lex = new SyslLexical

    lex.scanPositioned(src)
    lex.docComments
  }

  private def docs(src: String): List[DocComments.Doc] =
    DocComments.of(Source("test.sysl", src), scan(src))

  private def one(src: String): DocComments.Doc = {
    val all = docs(src)

    all.length shouldBe 1
    all.head
  }

  "a doc comment is trivia, so it cannot change what a program means" in {
    val lex = new SyslLexical

    val documented = lex.scanPositioned("/** the answer */\nval n = 42\n").map(_._1.toString)
    val bare       = lex.scanPositioned("val n = 42\n").map(_._1.toString)

    documented shouldBe bare
  }

  "the lexer collects the doc form and leaves every other comment alone" - {
    "`/**` is collected" in {
      scan("/** documented */\nval n = 1\n").map(_._3) shouldBe List("/** documented */")
    }

    "an ordinary block comment is not" in {
      scan("/* just a note */\nval n = 1\n") shouldBe empty
    }

    "a line comment is not, however much prose it carries" in {
      scan("// a long and careful explanation\nval n = 1\n") shouldBe empty
    }

    "the empty block comment is not a doc comment" in {
      scan("/**/\nval n = 1\n") shouldBe empty
    }

    "several are collected in source order" in {
      val src = "/** first */\nval a = 1\n\n/** second */\nval b = 2\n"

      scan(src).map(_._3) shouldBe List("/** first */", "/** second */")
    }

    "a comment reported twice by the lexer's lookahead is collected once" in {
      // `IndentationLexical.comment` documents that the same comment can be reported twice, and the
      // lexer keys by offset for exactly this. A duplicate here would be a repeated paragraph in
      // generated documentation.
      val src = "val a = 1\n/** about b */\nval b = 2\n"

      scan(src).map(_._3) shouldBe List("/** about b */")
    }

    "a second scan does not inherit the first scan's comments" in {
      val lex = new SyslLexical

      lex.scanPositioned("/** from the first file */\nval a = 1\n")
      lex.scanPositioned("val b = 2\n")

      lex.docComments shouldBe empty
    }
  }

  "the margin is stripped, and what the author wrote is kept" - {
    "a single-line comment" in {
      one("/** the answer */\nval n = 1\n").body shouldBe "the answer"
    }

    "the scaladoc margin" in {
      val src =
        """/** The first line.
          | *
          | * The second paragraph.
          | */
          |val n = 1
          |""".stripMargin

      one(src).body shouldBe "The first line.\n\nThe second paragraph."
    }

    "a comment written with no margin at all" in {
      val src =
        """/**
          |The first line.
          |The second.
          |*/
          |val n = 1
          |""".stripMargin

      one(src).body shouldBe "The first line.\nThe second."
    }

    "indentation inside a fenced block survives, because a code example is shaped" in {
      val src =
        """/** Sorting a slice.
          | *
          | * ```
          | * sort(xs)
          | *     print(xs)
          | * ```
          | */
          |val n = 1
          |""".stripMargin

      one(src).body should include("\n    print(xs)")
    }
  }

  "the summary is the first sentence, because an index has one column to spend" - {
    "a sentence ending in a full stop" in {
      one("/** The answer. And then some more. */\nval n = 1\n").summary shouldBe "The answer."
    }

    "a dotted module name does not end a sentence" in {
      // The case that matters in a language whose module names are dotted: `sysl.text` mid-sentence
      // would end the summary under a naive rule, and the summary would be two words long.
      val doc = one("/** Reads sysl.text and answers a width. Nothing else. */\nval n = 1\n")

      doc.summary shouldBe "Reads sysl.text and answers a width."
    }

    "a body with no sentence stop is all summary" in {
      one("/** a width in columns */\nval n = 1\n").summary shouldBe "a width in columns"
    }

    "a question ends one too" in {
      one("/** Is it sorted? The check is linear. */\nval n = 1\n").summary shouldBe "Is it sorted?"
    }

    "the summary is flattened, so a wrapped sentence is one line" in {
      val src =
        """/** The first sentence
          | * wraps across lines.
          | * The second does not.
          | */
          |val n = 1
          |""".stripMargin

      one(src).summary shouldBe "The first sentence wraps across lines."
    }
  }

  "tags" - {
    "`@param` takes a name and its prose" in {
      val src =
        """/** Sorts a slice.
          | *
          | * @param xs the slice, sorted in place
          | */
          |val n = 1
          |""".stripMargin

      val doc = one(src)

      doc.body shouldBe "Sorts a slice."
      doc.params.map(t => (t.subject, t.text)) shouldBe List((Some("xs"), "the slice, sorted in place"))
    }

    "`@return` takes no name" in {
      val doc = one("/** Counts.\n * @return the number of BYTES, not characters\n */\nval n = 1\n")

      doc.returns.map(_.text) shouldBe Some("the number of BYTES, not characters")
      doc.returns.flatMap(_.subject) shouldBe None
    }

    "`@tparam` is a parameter tag over a type" in {
      val doc = one("/** Sorts.\n * @tparam T the element type\n */\nval n = 1\n")

      doc.tparams.map(t => (t.subject, t.text)) shouldBe List((Some("T"), "the element type"))
    }

    "a tag runs on to its continuation lines, blank lines included" in {
      val src =
        """/** Sorts.
          | *
          | * @param xs the slice.
          | *
          | *   It is sorted in place, so the caller's storage is what changes.
          | */
          |val n = 1
          |""".stripMargin

      val text = one(src).params.head.text

      text should include("the slice.")
      text should include("It is sorted in place")
    }

    "several tags keep their source order" in {
      val src =
        """/** Sorts.
          | * @param xs the slice
          | * @param lt the comparison
          | * @return nothing
          | */
          |val n = 1
          |""".stripMargin

      val doc = one(src)

      doc.params.flatMap(_.subject) shouldBe List("xs", "lt")
      doc.tags.map(_.name) shouldBe List("param", "param", "return")
    }

    "a tag knows the line it was written on, so a diagnostic can point at it" in {
      val src =
        """/** Sorts.
          | * @param xs the slice
          | */
          |val n = 1
          |""".stripMargin

      one(src).params.head.line shouldBe 2
    }

    "an `@` inside a sentence is prose, not a tag" in {
      val doc = one("/** Reachable from a @test function, which is the point. */\nval n = 1\n")

      doc.tags shouldBe empty
      doc.body should include("@test")
    }

    "a line beginning with a language annotation is left in the body, not read as a tag" in {
      // sysl's `@` is the annotation sigil, so a doc comment discussing one is discussing the
      // language. The vocabulary is closed and this is what closing it buys.
      val src =
        """/** Sorting.
          | *
          | * @requires is what a capability clause is spelled with, and this module has none.
          | */
          |val n = 1
          |""".stripMargin

      one(src).unknownTags shouldBe empty
    }

    "an unrecognized tag is recorded as unknown, though nothing acts on it" in {
      val doc = one("/** Sorts.\n * @parm xs a typo for param\n */\nval n = 1\n")

      doc.unknownTags.map(_.name) shouldBe List("parm")
    }

    "there is no `@throws`, because sysl has no exceptions" in {
      // A fallible function answers with the `E` of a `Result[T, E]`, which is part of the return
      // type — so `@return` describes it and a second channel would be describing something that
      // does not exist. It parses as an unknown tag rather than being refused.
      val doc = one("/** Reads.\n * @throws nothing, ever\n */\nval n = 1\n")

      doc.unknownTags.map(_.name) shouldBe List("throws")
    }
  }

  "a tag that names the signature is checked, and only in the one direction" - {
    def doc(text: String) = DocComments.parse(text, 1, 1)

    "a `@param` naming a real parameter is fine" in {
      val d = doc("/** Sorts.\n * @param xs the slice\n */")

      DocComments.check(d, List("xs"), Nil) shouldBe empty
    }

    "a `@param` naming nothing in the signature is refused, and the message says what is there" in {
      // The shape a rename leaves behind: the parameter moved on and the paragraph did not.
      val found = DocComments.check(doc("/** Sorts.\n * @param items the slice\n */"), List("xs", "lt"), Nil)

      found should have length 1
      found.head._1.subject shouldBe Some("items")
      found.head._2 shouldBe "'items' is not a parameter of this declaration — it has xs, lt"
    }

    "a declaration with no parameters at all says so, rather than listing nothing" in {
      val found = DocComments.check(doc("/** N.\n * @param x a thing\n */"), Nil, Nil)

      found.map(_._2) shouldBe List("'x' is not a parameter of this declaration — it has no parameters")
    }

    "a bare `@param` with no name after it is its own mistake" in {
      val found = DocComments.check(doc("/** Sorts.\n * @param\n */"), List("xs"), Nil)

      found.map(_._2) shouldBe
        List("'@param' names no parameter — write the name it documents after the tag")
    }

    "`@tparam` is checked against the type parameters, separately" in {
      val d = doc("/** Sorts.\n * @tparam E the element\n */")

      DocComments.check(d, List("xs"), List("T")) should have length 1
      DocComments.check(d, List("xs"), List("E")) shouldBe empty
    }

    "a parameter with NO `@param` is not an error, and this is the property that matters" in {
      // Documentation is optional and a partial doc comment is better than none. Requiring the full
      // set is what makes people write `@param n the n` to silence a warning, and it is what would
      // stop the library being converted a file at a time.
      DocComments.check(doc("/** Sorts a slice. */"), List("xs", "lt"), List("T")) shouldBe empty
    }

    "an undocumented parameter beside a documented one is still not an error" in {
      val d = doc("/** Sorts.\n * @param xs the slice\n */")

      DocComments.check(d, List("xs", "lt"), Nil) shouldBe empty
    }

    "an unknown tag is not checked, because it is prose" in {
      DocComments.check(doc("/** Sorts.\n * @parm xs a typo\n */"), List("xs"), Nil) shouldBe empty
    }
  }

  "which declaration a comment belongs to" - {
    val source = (src: String) => Source("test.sysl", src)

    "the one on the next line" in {
      val src = "/** about n */\nval n = 1\n"

      DocComments.above(source(src), docs(src), 2).map(_.body) shouldBe Some("about n")
    }

    "one separated by a blank line belongs to nothing, which is what lets a file open with prose" in {
      val src = "/** about the file */\n\nval n = 1\n"

      DocComments.above(source(src), docs(src), 3) shouldBe None
    }

    "an annotation may sit between the prose and the declaration" in {
      // The ordinary shape: `@test` above a function, `@export` above another. A rule demanding
      // adjacency would silently drop the documentation of every annotated declaration.
      val src = "/** about f */\n@test\nval n = 1\n"

      DocComments.above(source(src), docs(src), 3).map(_.body) shouldBe Some("about f")
    }

    "and so may several, with a line comment among them" in {
      val src = "/** about f */\n@test\n// an implementation note\n@export\nval n = 1\n"

      DocComments.above(source(src), docs(src), 5).map(_.body) shouldBe Some("about f")
    }

    "the nearest one wins, so two declarations keep their own prose" in {
      val src = "/** about a */\nval a = 1\n\n/** about b */\nval b = 2\n"
      val all = docs(src)

      DocComments.above(source(src), all, 2).map(_.body) shouldBe Some("about a")
      DocComments.above(source(src), all, 5).map(_.body) shouldBe Some("about b")
    }

    "a declaration with nothing above it has no doc" in {
      val src = "val n = 1\n"

      DocComments.above(source(src), docs(src), 1) shouldBe None
    }

    "a multi-line comment is placed by where it ENDS, not where it starts" in {
      val src =
        """/** About n.
          | *
          | * At length.
          | */
          |val n = 1
          |""".stripMargin

      val doc = one(src)

      doc.startLine shouldBe 1
      doc.endLine shouldBe 4
      DocComments.above(source(src), docs(src), 5).map(_.summary) shouldBe Some("About n.")
    }
  }
}
