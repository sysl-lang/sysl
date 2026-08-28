package sh.sysl

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** An identifier may be written in any script (`reference/lexical.md § Identifiers`).
  *
  * **The rule is one predicate: a name begins with `_` or anything Unicode calls a letter, and
  * continues with that or a digit.** Not a list of ranges — a table of accepted blocks is a thing
  * somebody has to extend every time a script is asked for, and the first extension request would
  * arrive from whoever was left out of the last one.
  *
  * **What it is for is readability rather than internationalisation.** A language whose identifiers
  * are ASCII asks everybody who does not think in English to transliterate their own vocabulary, and
  * the words that suffer are the domain ones — the names a reader most needs to recognise. Go, Java,
  * Scala and C# all took this road.
  *
  * **The symbol half needed nothing**, which is the fact worth carrying: `LlvmName.escape` already
  * wrote a non-ASCII character as `$XX` / `$uXXXXXX`, because a backtick-quoted name could already
  * hold one. So a name that was previously reachable only by quoting is now reachable by writing it,
  * and it lowers to exactly the symbol the quoted form always did.
  */
class UnicodeIdentTests extends AnyFreeSpec with Matchers with CodegenSupport with RunSupport {

  private def withLexer(body: SyslLexical => Any): Unit = { body(new SyslLexical); () }

  extension (l: SyslLexical) {

    def bare(src: String): List[Any] =
      l.scan(src).filterNot(t => t == l.Newline || t == l.Indent || t == l.Dedent)

    def bad(src: String): String = l.bare(src).head.toString
  }

  "the token" - {

    "a Latin letter with an accent on it" in withLexer { l =>
      l.bare("café") shouldBe List(l.Identifier("café"))
      l.bare("año") shouldBe List(l.Identifier("año"))
      l.bare("größe") shouldBe List(l.Identifier("größe"))
      l.bare("ação") shouldBe List(l.Identifier("ação"))
    }

    // The rule is Unicode's own answer rather than a Latin-1 carve-out, so it is worth pinning a
    // script that has nothing to do with the request that prompted it.
    "and any other script's letters, on the same rule" in withLexer { l =>
      l.bare("μ") shouldBe List(l.Identifier("μ"))
      l.bare("Ελλάδα") shouldBe List(l.Identifier("Ελλάδα"))
      l.bare("Москва") shouldBe List(l.Identifier("Москва"))
      l.bare("名前") shouldBe List(l.Identifier("名前"))
    }

    "an accented letter may begin a name, since it is a letter" in withLexer { l =>
      l.bare("área") shouldBe List(l.Identifier("área"))
      l.bare("Ómnibus") shouldBe List(l.Identifier("Ómnibus"))
    }

    // The declaration is what a caller has to spell, so the two forms are two names rather than one
    // spelling of it. This is the case UAX #31's normalization would fold together and this rule
    // deliberately does not — see the refusal below for the one that is refused outright.
    "a precomposed letter and a base plus a combining mark are different names" in withLexer { l =>
      l.bare("café") shouldBe List(l.Identifier("café"))
      l.bare("café") should not be List(l.Identifier("café"))
    }

    "a digit still may not begin one" in withLexer { l =>
      l.bare("3café") should not be List(l.Identifier("3café"))
    }

    // A name written in a script with its own digits may use them; the *literal* grammar is
    // deliberately unmoved, since a literal is a value the arithmetic has to read.
    "a non-ASCII digit may continue a name and may not begin one" in withLexer { l =>
      l.bare("caf٣") shouldBe List(l.Identifier("caf٣"))
      l.bare("٣") should not be List(l.Identifier("٣"))
    }

    // A `Char` is one UTF-16 unit, so a letter above U+FFFF arrives as a surrogate pair and
    // `Character.isLetter` answers false for either half. Every living script's letters are inside
    // the BMP, CJK Unified Ideographs included; what is outside is the historic scripts and the CJK
    // extension planes. Asserted rather than left to be discovered, because the diagnostic says
    // "illegal character" and gives no hint that the plane is what decided it.
    "a letter above the BMP is refused, and that edge is real" in withLexer { l =>
      l.bad("𠮷") should include("illegal character")
    }

    "an operator is still not a letter" in withLexer { l =>
      l.bad("×") should include("illegal character")
      l.bad("÷") should include("illegal character")
    }
  }

  "a name in any script is an ordinary name" - {

    "declared, read and assigned" in {
      val src =
        """var año = 3
          |año += 4
          |print(año)""".stripMargin

      run(src) shouldBe "7\n"
    }

    "as a function and its parameters" in {
      val src =
        """área(ancho: real, altura: real) -> real = ancho * altura
          |print(área(3.0, 4.0))""".stripMargin

      run(src) shouldBe "12\n"
    }

    "as a struct, its fields and its methods" in {
      val src =
        """struct Círculo
          |    radio: real
          |
          |    área(self) -> real = 3.0 * self.radio * self.radio
          |
          |val c = Círculo(2.0)
          |
          |print(c.radio, c.área())""".stripMargin

      run(src) shouldBe "2 12\n"
    }

    "as an enum and its variants" in {
      val src =
        """enum Estación
          |    Otoño
          |    Invierno
          |
          |val e = Estación.Otoño
          |
          |print(e == Otoño, e == Invierno)""".stripMargin

      run(src) shouldBe "true false\n"
    }

    // The one that exercises the symbol rather than the scope: module storage is laid into the
    // object file under a mangled name, so this is what says `LlvmName.escape` carries a bare
    // identifier as well as it carried a quoted one.
    "as module storage, which is what reaches the emitted symbol" in {
      val src =
        """module contabilidad
          |
          |var contador: int = 0
          |
          |incrementar(cuánto: int) -> int
          |    contador += cuánto
          |    contador""".stripMargin

      runOf(
        "contabilidad/c.sysl" -> src,
        "main.sysl"           -> "import contabilidad.incrementar\n\nprint(incrementar(5), incrementar(37))",
      ) shouldBe "5 42\n"
    }

    // A bare name and the quoted form of the same name were two spellings with two lowerings until
    // the identifier grammar caught up; now they are one name written two ways, which is what makes
    // the quoted form's job purely "a name the grammar still cannot reach".
    "and the quoted form of the same name is the same name" in {
      val src =
        """var café = 3
          |`café` += 1
          |print(café)""".stripMargin

      run(src) shouldBe "4\n"
    }

    "across a module boundary, imported by name" in {
      runOf(
        "geometría/a.sysl" -> "module geometría\n\nárea(x: int) -> int = x + 1",
        "main.sysl"        -> "import geometría.área\n\nprint(área(41))",
      ) shouldBe "42\n"
    }
  }
}
