package sh.sysl

/** A file's header and its imports — `module a.b`, the `@` clauses under it, and `import`.
 *
 * These are one area because they are one run of lines: the header is read before any statement
 * is, and an import is the only declaration whose subject is another file. The dotted path both
 * of them are written with is here for the same reason.
 */
trait HeaderParser extends AttrParser {

  /** A dotted name — the general form, whose segments may be backtick-quoted because they may name
   * declarations (`09`).
   */
  protected lazy val dottedName: Parser[List[String]] = rep1sep(ident, op("."))

  /** A **module path**, whose segments may not be quoted.
   *
   * Two reasons, and the second is the load-bearing one. A module names a *directory*
   * (`reference/modules.md`), so a path is the one name that is not purely the programmer's to
   * choose. And `Modules.split` recovers a module from a key by finding its **first** `$` — an
   * invariant that holds only because the one character a name may not contribute raw is `$`, which
   * `Modules.qualify` marks on the way in and which nothing marks on a module path. A quoted module
   * segment could hold one, and the key would then be read as belonging to a module nobody
   * declared. **This survived the identifier grammar opening up to every script**
   * (`reference/lexical.md § Identifiers`): a letter in any script is not a `$`, so `geometría` is a
   * module path like any other and only quoting was ever the risk.
   */
  protected lazy val modulePath: Parser[List[String]] =
    rep1sep(
      bareIdent | quotedIdent >> (n =>
        err(s"'$n' is backtick-quoted, and a module path is written with plain names — a module " +
          "names a directory, and its parts are what the file system holds")),
      op("."),
    )

  /** A name that may be reached through the module it belongs to: `File`, `std.fs.File`.
   *
   * A public member is always reachable fully-qualified, with no import (`reference/modules.md §
   * Imports`), so every position that names a declaration takes this rather than a bare identifier.
   * The dots are kept in the name as written — which module the prefix is and which part of it is
   * the declaration's own name is a question only the analyzer, holding the program's module names,
   * can answer.
   */
  protected lazy val qualifiedName: Parser[String] = dottedName ^^ (_.mkString("."))

  /** `module a.b.c`, the header naming the module this file contributes to. It is not a statement:
   * it may appear once, and only before everything else, so it is a prefix of the program rather
   * than an alternative within it.
   */
  protected lazy val moduleHeader: Parser[ModuleName] =
    at(op("module") ~> modulePath ^^ ModuleName.apply)

  /** A file-header attribute: `@no_alloc`, `@requires(os, posix)`, `@link("z")`, `@tests`
   * (`reference/modules.md § Capabilities are a module property`, `reference/ffi.md § @link`,
   * `reference/attributes.md § @tests — a file of scaffolding`).
   *
   * **These are attributes rather than grammar, and that is the point of the spelling.** A capability
   * and a library name are things said *about* a module, not constructs the language executes, so
   * they take the notation sysl already uses for that — the one `@test` and `@tailrec` are written
   * in. What it buys is the whole reason to prefer it: **no word is spent.** `alloc` was a reserved
   * word, which took the most natural name in an allocator away from the code that provides one, and
   * `guide/slab` had to call its function `take`. Every name here arrives through `attrWord`, which
   * matches an ordinary identifier, so `alloc`, `no`, `requires` and `link` are all available to a
   * program again.
   *
   * One attribute may yield several clauses, because `@requires` takes a list.
   */
  /** A header attribute, which **commits** once its word is seen — so its own refusal is the answer.
   *
   * A repetition ends when its element fails, so a `@requires(` that will not parse simply ended the
   * header and handed the line to the statement grammar, where `misplacedHeaderAttr` matched it and
   * said it belonged in the header. On line 1 of a file that is false advice with nowhere to act on
   * it, and the message this rule had already produced — `')' expected`, under the parenthesis that
   * was not closed — was thrown away to say it.
   *
   * **The commit is behind `describe` and after a guard that consumes nothing**, which is what makes
   * it safe *and* keeps the message a bare `@` gets. The header's repetition runs before the
   * statements, so it also sees the `@test` or `@tailrec` above the first declaration: those fail the
   * guard without consuming, `describe` renames the refusal to "an attribute expected", and the
   * repetition ends exactly as it always did. Only the four spellings below reach the commit.
   */
  protected lazy val headerAttr: Parser[List[HeaderClause]] =
    op("@") ~> describe("an attribute")(
      guard(headerAttrWord) ~> commit(noAttr | requiresAttr | linkAttr | includeAttr | testsAttr))

  /** The words that make an `@` a **header** attribute rather than a declaration's.
   *
   * It is one list because two would drift, and both readers of it are about the boundary rather
   * than about any one attribute: the commit point below, and the refusal of one written where a
   * statement goes. `reference/modules.md § Capabilities are a module property` and
   * `reference/modules.md § Capabilities are a module property` are where the vocabulary is
   * documented; this is the grammar's single copy of it.
   *
   * **`@test` and `@tailrec` are deliberately not here.** The header's repetition runs before the
   * statements, so it also sees the attributes written above the first declaration — a list that
   * admitted a bare `@` would turn every one of those into a header-attribute parse error.
   */
  protected lazy val headerAttrWord: Parser[Any] =
    attrWordPrefixed("no_") | attrWord("requires") | attrWord("link") | attrWord("include") |
      attrWord("tests")

  /** `@tests` — the file is the module's test scaffolding (`reference/attributes.md § @tests — a file of scaffolding`).
   *
   * It sits with the capability clauses rather than above a declaration because it is a property of
   * the **file**: what it governs is every declaration in it at once, and a mark that had to be
   * repeated on each would be a mark somebody forgets on the twentieth helper. That is also the
   * answer to why it is not spelled `@test` — one word for two scopes reads as though the file were
   * itself a test, and the two say genuinely different things: `@test` names something the runner
   * calls, this names something no build but the runner's keeps.
   */
  private lazy val testsAttr: Parser[List[HeaderClause]] =
    at(attrWord("tests") ^^ (_ => TestsClause())) ^^ (List(_))

  /** `@no_alloc` and its siblings — the module narrowing itself below what the target offers.
   *
   * The capability is the part after `no_`, and it is **not checked here**: the set is a property of
   * the project rather than of the grammar (`Capability`), so `@no_sockets` parses and the analyzer
   * is what says there is no such capability. That keeps one message about an unknown capability
   * instead of two that differ by where it was written.
   *
   * One word per capability rather than `@no(alloc)` follows Rust's `#![no_std]`, which is the
   * nearest precedent and reads the way the thing is spoken.
   */
  private lazy val noAttr: Parser[List[HeaderClause]] =
    at(attrWordPrefixed("no_") ^^ (CapabilityClause(CapabilityDirection.Narrows, _))) ^^ (List(_))

  /** `@requires(os)`, `@requires(heap, posix)` — what the module cannot be built without.
   *
   * Parenthesised and plural where the narrowing form is neither, because that is how each is used:
   * a module gives up one capability at a time and needs several at once. The POSIX regex binding
   * requires both `heap` and `posix` — a `regex_t` is caller-allocated and `regcomp` is POSIX — and
   * writing that as two attributes would be two lines saying one thing.
   */
  private lazy val requiresAttr: Parser[List[HeaderClause]] =
    attrWord("requires") ~> op("(") ~>
      rep1sep(at(ident ^^ (CapabilityClause(CapabilityDirection.Requires, _))), op(",")) <~ op(")")

  /** `@link("z")` — a library the linker must be given for this file's `extern`s to resolve.
   *
   * The library is named by a **string** rather than an identifier because it is a name from outside
   * sysl, exactly as an `extern`'s symbol is, and because plenty of real ones are not identifiers at
   * all. `stdc++` is the everyday example.
   */
  private lazy val linkAttr: Parser[List[HeaderClause]] =
    at(attrWord("link") ~> op("(") ~> linkName <~ op(")") ^^ LinkClause.apply) ^^ (List(_))

  /** `@include("FreeRTOS.h")` — a header this file's `c const` block is evaluated against.
   *
   * A **string**, for `@link`'s reason turned around: a header's name is C's rather than sysl's, and
   * plenty of real ones are not identifiers — `sys/types.h` is the everyday example, and the
   * extension alone would already rule an identifier out. It is written as C writes it, so that the
   * line the compiler builds and the line a reader would have written by hand are the same line.
   *
   * Several are allowed and are included in the order written, because a header that needs another
   * above it is ordinary in C and the file is the only place that order can be stated.
   */
  private lazy val includeAttr: Parser[List[HeaderClause]] =
    at(attrWord("include") ~> op("(") ~> linkName <~ op(")") ^^ IncludeClause.apply) ^^ (List(_))

  /** A header attribute written where a statement goes, which is refused for the reason
   * `noVisibility` is: it has a place, and a reader who writes it in the wrong one should be told
   * which place that is rather than answered with a complaint about the shape of the line.
   *
   * It is a *header*, not a statement, because it is a property of the module and the module is
   * settled before anything in the file runs — and because one part-way down a file would read as
   * though the statements above it were outside its reach.
   */
  protected lazy val misplacedHeaderAttr: Parser[Nothing] =
    guard(op("@") ~ headerAttrWord) ~> err(
      "this attribute belongs in the file's header, on the lines directly after 'module' and before " +
        "everything else — it is a property of the whole module, not of the statements below it")

  /** `import a.b.c`, `import a.b.{c, d as e}`, `import a.b.*` — the Scala forms
   * (`reference/modules.md § Imports`).
   *
   * The path is the greedy dotted name, so the tail forms are read off what is left after it: a
   * `.` followed by something that is not an identifier is not part of the path, which is what
   * lets one rule cover all three without lookahead. Which part of the path is the module is not
   * decided here — it is a question about the program, not about the text.
   *
   * It is a **statement** rather than a header, because an import may also appear inside a block,
   * scoped to it, for a name wanted in one function only.
   */
  protected lazy val importDecl: Parser[ImportDecl] =
    op("import") ~> modulePath ~ opt(importTail) ~ opt(op("as") ~> ident) ^^ {
      case path ~ None ~ alias          => ImportDecl(path, alias = alias)
      case path ~ Some(Left(_)) ~ alias => ImportDecl(path, wildcard = true, alias = alias)
      case path ~ Some(Right(sels)) ~ alias => ImportDecl(path, sels, alias = alias)
    } ^? (
      { case d if d.alias.isEmpty || (!d.wildcard && d.selectors.isEmpty) => d },
      _ =>
        "a rename here would have nothing to name — a wildcard brings in every member, and a " +
          "selector list carries its own 'as' per name, as 'a.b.{c as d}'",
    )

  /** The wildcard's `.*` is **one token**, which is why it is matched here rather than as a `.`
   * followed by the multiplication operator. Nothing about the written form changes; what it buys
   * is that a line never ends in a bare `*` that was really the end of a statement, so `*` can
   * carry a line like every other binary operator (`SyslLexical`).
   */
  protected lazy val importTail: Parser[Either[Unit, List[ImportSelector]]] =
    op(".*") ^^^ Left(()) |
      (op(".") ~> op("{") ~> commaList1(importSelector) <~ op("}")) ^^ (Right(_))

  protected lazy val importSelector: Parser[ImportSelector] =
    at(ident ~ opt(op("as") ~> ident) ^^ { case n ~ a => ImportSelector(n, a) })
}
