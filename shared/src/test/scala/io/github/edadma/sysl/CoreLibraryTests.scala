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

  private def onDisk: Map[String, Source] =
    Project.collect(CoreLib.root.get).map(s => s.name.split('/').last -> s).toMap

  "the source the compiler carries" - {

    "is what `lib/sysl` holds, file for file" in {
      assume(CoreLib.root.isDefined, "lib/ not found from the test working directory")

      val carried = Std.sources.map(s => s.name.split('/').last -> s.text).toMap

      carried.keySet shouldBe onDisk.keySet
      for (name, source) <- onDisk do carried(name) shouldBe source.text
    }

    "and names each file where it actually is, so a diagnostic points at something openable" in {
      Std.sources.map(_.name) shouldBe Std.sources.map(_.name).sorted
      Std.sources.foreach(_.name should startWith("lib/sysl/"))
    }
  }

  "the library as the driver reads it" - {

    "puts every file in the module the compiler was told the standard one is" in {
      assume(CoreLib.root.isDefined, "lib/ not found from the test working directory")

      // The header and the directory both say it, and the driver is what checks they agree — so
      // this is the same question `build-lib lib` would ask, asked without building anything.
      onDisk.values.map(_.dir).toSet shouldBe Set(Some(List(Std.module)))

      for source <- onDisk.values do
        SyslParser.parse(source) match
          case Right(p)  => p.module.map(_.show) shouldBe Some(Std.module)
          case Left(err) => fail(s"${source.name} does not parse: $err")
    }
  }

  "the two files reach each other with nothing imported" in {
    // `Display.display` names `Writer`, which the *other* file declares, and `13 §6` is why that
    // needs no import: a module's members are one set however many files they came from. It is
    // also what a one-file standard module would never have said.
    val declared = Std.parsed.map(p => p.source.name.split('/').last -> p.body).toMap

    declared("write.sysl").collect { case t: TraitDecl => t.name } shouldBe List("Writer")
    declared("display.sysl").collect { case t: TraitDecl => t.name } shouldBe List("Display")
    Std.parsed.flatMap(_.body).collect { case i: ImportDecl => i } shouldBe empty
  }
}
