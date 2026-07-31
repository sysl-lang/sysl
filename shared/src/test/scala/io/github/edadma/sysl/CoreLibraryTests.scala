package io.github.edadma.sysl

import io.github.edadma.cross_platform.*

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** Where the core library sits, as seen from wherever the suite was started.
 *
 * `lib/` is a **library project root** holding the single module `sysl`, so a module's name is the
 * path below it exactly as it is for a program (`13 §1`) — which is why the root is what is found
 * here and `lib/sysl` is not.
 */
object CoreLib {

  def root: Option[String] = List("lib", "../lib", "../../lib").find(isDirectory)
}

/** The core library on disk, and what the compiler carries of it (`Std`, `CoreSource`).
 *
 * **This is the test that says the files are the fact.** What a compilation reads is generated from
 * them at build time, and a generator that stopped running — or ran over the wrong directory —
 * leaves a compiler that goes on working while the source a reader opens says something else. That
 * is exactly the failure a second copy is for, and nothing else here would notice it.
 */
class CoreLibraryTests extends AnyFreeSpec with Matchers {

  /** A file's place in the library: its path from the standard module's own directory down, which
   * is the one thing the two copies have in common — the compiler carries `lib/sysl/print.sysl`
   * while the walk returns whatever absolute path it found the same file at.
   *
   * Keyed by the **path** and not by the file name, because the library is a tree: two submodules
   * may each hold a `read.sysl`, and a key that dropped the directory would compare one of them
   * against the other and never say so. It is read off the name rather than off `dir`, so that
   * comparing the two `dir`s below is two derivations of one fact meeting rather than a tautology.
   */
  private def place(s: Source): String = {
    val parts = s.name.split('/').toList

    parts.drop(parts.lastIndexOf(Std.module)).mkString("/")
  }

  private def onDisk: Map[String, Source] =
    Project.collect(CoreLib.root.get).map(s => place(s) -> s).toMap

  private def carried: Map[String, Source] = Std.sources.map(s => place(s) -> s).toMap

  "the source the compiler carries" - {

    "is what `lib/sysl` holds, file for file" in {
      assume(CoreLib.root.isDefined, "lib/ not found from the test working directory")

      carried.keySet shouldBe onDisk.keySet
      for (name, source) <- onDisk do carried(name).text shouldBe source.text
    }

    "and names each file where it actually is, so a diagnostic points at something openable" in {
      Std.sources.map(_.name) shouldBe Std.sources.map(_.name).sorted
      Std.sources.foreach(_.name should startWith("lib/sysl/"))
    }

    // Two derivations of one fact meeting: the driver takes a file's directory from the walk that
    // found it, and the carrier takes it from the path the generator wrote down. Nothing in
    // `lib/sysl` is in a submodule yet, which is exactly why the claim is worth stating — it is what
    // a directory added there would be relying on, and a carrier that named the standard module for
    // every file would go on passing every other test here.
    "and says which module each is in, as the walk that found it would have" in {
      assume(CoreLib.root.isDefined, "lib/ not found from the test working directory")

      for (name, source) <- onDisk do carried(name).dir shouldBe source.dir
      Std.sources.foreach(_.dir.get.head shouldBe Std.module)
    }
  }

  "the library as the driver reads it" - {

    "puts every file in the module its own header names" in {
      assume(CoreLib.root.isDefined, "lib/ not found from the test working directory")

      // The header and the directory both say it, and the driver is what checks they agree — so
      // this is the same question `build-lib lib` would ask, asked without building anything.
      for source <- onDisk.values do
        SyslParser.parse(source) match
          case Right(p)  => p.module.map(_.show) shouldBe Some(source.dir.get.mkString("."))
          case Left(err) => fail(s"${source.name} does not parse: $err")
    }

    "and every module those headers name is one the library says it declares" in {
      assume(CoreLib.root.isDefined, "lib/ not found from the test working directory")

      onDisk.values.map(_.dir.get.mkString(".")).toSet shouldBe Library.modules.toSet
    }
  }

  "two files of one module reach each other with nothing imported" in {
    // `Display.display` names `Writer`, which the *other* file declares, and `13 §6` is why that
    // needs no import: a module's members are one set however many files they came from. It is
    // also what a one-file standard module would never have said.
    val declared = Std.parsed.map(p => place(p.source) -> p.body).toMap

    declared("sysl/write.sysl").collect { case t: TraitDecl => t.name } shouldBe List("Writer")
    declared("sysl/display.sysl").collect { case t: TraitDecl => t.name } shouldBe List("Display")

    // Which is the claim, rather than "the library imports nothing": a file may well have a sibling
    // module of the library to import, and none of them has itself. The two import forms spell the
    // module differently — a selector list or a wildcard names it outright, and a bare path names it
    // with the imported name still on the end — so which segments are the module is read off the
    // form.
    for p <- Std.parsed; i <- p.body.collect { case i: ImportDecl => i } do
      val named = if i.selectors.nonEmpty || i.wildcard then i.path else i.path.init

      named.mkString(".") should not be p.module.map(_.show).get
  }
}
